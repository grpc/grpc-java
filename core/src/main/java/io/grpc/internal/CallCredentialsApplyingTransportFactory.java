/*
 * Copyright 2016,2022 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.internal;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.Attributes;
import io.grpc.CallCredentials;
import io.grpc.CallCredentials.RequestInfo;
import io.grpc.CallOptions;
import io.grpc.ChannelCredentials;
import io.grpc.ChannelLogger;
import io.grpc.ClientStreamTracer;
import io.grpc.CompositeCallCredentials;
import io.grpc.InternalMayRequireSpecificExecutor;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.SecurityLevel;
import io.grpc.Status;
import io.grpc.internal.MetadataApplierImpl.MetadataApplierListener;
import java.net.SocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.concurrent.GuardedBy;

final class CallCredentialsApplyingTransportFactory implements ClientTransportFactory {
  private final ClientTransportFactory delegate;
  private final CallCredentials channelCallCredentials;
  private final Executor appExecutor;

  CallCredentialsApplyingTransportFactory(
      ClientTransportFactory delegate, CallCredentials channelCallCredentials,
      Executor appExecutor) {
    this.delegate = checkNotNull(delegate, "delegate");
    this.channelCallCredentials = channelCallCredentials;
    this.appExecutor = checkNotNull(appExecutor, "appExecutor");
  }

  @Override
  public ConnectionClientTransport newClientTransport(
      SocketAddress serverAddress, ClientTransportOptions options, ChannelLogger channelLogger) {
    return new CallCredentialsApplyingTransport(
        delegate.newClientTransport(serverAddress, options, channelLogger), options.getAuthority());
  }

  @Override
  public ScheduledExecutorService getScheduledExecutorService() {
    return delegate.getScheduledExecutorService();
  }

  @Override
  public SwapChannelCredentialsResult swapChannelCredentials(ChannelCredentials channelCreds) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void close() {
    delegate.close();
  }

  private class CallCredentialsApplyingTransport extends ForwardingConnectionClientTransport {
    private final ConnectionClientTransport delegate;
    private final String authority;
    // Negative value means transport active, non-negative value indicates shutdown invoked.
    private final AtomicInteger pendingApplier = new AtomicInteger(Integer.MIN_VALUE + 1);
    private volatile Status shutdownStatus;
    @GuardedBy("this")
    private Status savedShutdownStatus;
    @GuardedBy("this")
    private Status savedShutdownNowStatus;
    private final MetadataApplierListener applierListener = new MetadataApplierListener() {
      @Override
      public void onComplete() {
        if (pendingApplier.decrementAndGet() == 0) {
          maybeShutdown();
        }
      }
    };

    CallCredentialsApplyingTransport(ConnectionClientTransport delegate, String authority) {
      this.delegate = checkNotNull(delegate, "delegate");
      this.authority = checkNotNull(authority, "authority");
    }

    @Override
    protected ConnectionClientTransport delegate() {
      return delegate;
    }

    @Override
    @SuppressWarnings("deprecation")
    public ClientStream newStream(
        final MethodDescriptor<?, ?> method, Metadata headers, final CallOptions callOptions,
        ClientStreamTracer[] tracers) {
      CallCredentials creds = callOptions.getCredentials();
      if (creds == null) {
        creds = channelCallCredentials;
      } else if (channelCallCredentials != null) {
        creds = new CompositeCallCredentials(channelCallCredentials, creds);
      }
      if (creds != null) {
        MetadataApplierImpl applier = new MetadataApplierImpl(
            delegate, method, headers, callOptions, applierListener, tracers);
        if (pendingApplier.incrementAndGet() > 0) {
          applierListener.onComplete();
          return new FailingClientStream(shutdownStatus, tracers);
        }
        RequestInfo requestInfo = new RequestInfo() {
            @Override
            public MethodDescriptor<?, ?> getMethodDescriptor() {
              return method;
            }

            @Override
            public CallOptions getCallOptions() {
              return callOptions;
            }

            @Override
            public SecurityLevel getSecurityLevel() {
              return firstNonNull(
                  delegate.getAttributes().get(GrpcAttributes.ATTR_SECURITY_LEVEL),
                  SecurityLevel.NONE);
            }

            @Override
            public String getAuthority() {
              return firstNonNull(callOptions.getAuthority(), authority);
            }

            @Override
            public Attributes getTransportAttrs() {
              return delegate.getAttributes();
            }
          };
        try {
          // Hack to allow appengine to work when using AppEngineCredentials (b/244209681)
          // since processing must happen on a specific thread.
          //
          // Ideally would always use appExecutor and we could eliminate the interface
          // InternalMayRequireSpecificExecutor
          Executor executor;
          if (creds instanceof InternalMayRequireSpecificExecutor
              && ((InternalMayRequireSpecificExecutor)creds).isSpecificExecutorRequired()
              && callOptions.getExecutor() != null) {
            executor = callOptions.getExecutor();
          } else {
            executor = appExecutor;
          }

          creds.applyRequestMetadata(requestInfo, executor, applier);
        } catch (Throwable t) {
          applier.fail(Status.UNAUTHENTICATED
              .withDescription("Credentials should use fail() instead of throwing exceptions")
              .withCause(t));
        }
        return applier.returnStream();
      } else {
        if (pendingApplier.get() >= 0) {
          return new FailingClientStream(shutdownStatus, tracers);
        }
        return delegate.newStream(method, headers, callOptions, tracers);
      }
    }

    @Override
    public void shutdown(Status status) {
      checkNotNull(status, "status");
      synchronized (this) {
        if (pendingApplier.get() < 0) {
          shutdownStatus = status;
          pendingApplier.addAndGet(Integer.MAX_VALUE);
        } else {
          return;
        }
        if (pendingApplier.get() != 0) {
          savedShutdownStatus = status;
          return;
        }
      }
      super.shutdown(status);
    }

    // TODO(zivy): cancel pending applier here.
    @Override
    public void shutdownNow(Status status) {
      checkNotNull(status, "status");
      synchronized (this) {
        if (pendingApplier.get() < 0) {
          shutdownStatus = status;
          pendingApplier.addAndGet(Integer.MAX_VALUE);
        } else if (savedShutdownNowStatus != null) {
          return;
        }
        if (pendingApplier.get() != 0) {
          savedShutdownNowStatus = status;
          // TODO(zivy): propagate shutdownNow to the delegate immediately.
          return;
        }
      }
      super.shutdownNow(status);
    }

    private void maybeShutdown() {
      Status maybeShutdown;
      Status maybeShutdownNow;
      synchronized (this) {
        if (pendingApplier.get() != 0) {
          return;
        }
        maybeShutdown = savedShutdownStatus;
        maybeShutdownNow = savedShutdownNowStatus;
        savedShutdownStatus = null;
        savedShutdownNowStatus = null;
      }
      if (maybeShutdown != null) {
        super.shutdown(maybeShutdown);
      }
      if (maybeShutdownNow != null) {
        super.shutdownNow(maybeShutdownNow);
      }
    }
  }
}
