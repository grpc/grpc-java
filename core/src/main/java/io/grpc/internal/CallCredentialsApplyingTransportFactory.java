/*
 * Copyright 2016 The gRPC Authors
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
import io.grpc.CallOptions;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.SecurityLevel;
import io.grpc.Status;
import java.net.SocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

final class CallCredentialsApplyingTransportFactory implements ClientTransportFactory {
  private static final Logger logger =
      Logger.getLogger(CallCredentialsApplyingTransportFactory.class.getName());
  private final ClientTransportFactory delegate;
  private final Executor appExecutor;

  CallCredentialsApplyingTransportFactory(
      ClientTransportFactory delegate, Executor appExecutor) {
    this.delegate = checkNotNull(delegate, "delegate");
    this.appExecutor = checkNotNull(appExecutor, "appExecutor");
  }

  @Override
  public ConnectionClientTransport newClientTransport(
      SocketAddress serverAddress, ClientTransportOptions options) {
    return new CallCredentialsApplyingTransport(
        delegate.newClientTransport(serverAddress, options), options.getAuthority());
  }

  @Override
  public ScheduledExecutorService getScheduledExecutorService() {
    return delegate.getScheduledExecutorService();
  }

  @Override
  public void close() {
    delegate.close();
  }

  private class CallCredentialsApplyingTransport extends ForwardingConnectionClientTransport {
    private final ConnectionClientTransport delegate;
    private final String authority;

    CallCredentialsApplyingTransport(ConnectionClientTransport delegate, String authority) {
      this.delegate = checkNotNull(delegate, "delegate");
      this.authority = checkNotNull(authority, "authority");
    }

    @Override
    protected ConnectionClientTransport delegate() {
      return delegate;
    }

    @Override
    public ClientStream newStream(
        final MethodDescriptor<?, ?> method, Metadata headers, final CallOptions callOptions) {
      CallCredentials creds = callOptions.getCredentials();
      if (creds != null) {
        MetadataApplierImpl applier = new MetadataApplierImpl(
            delegate, method, headers, callOptions);
        final Attributes transportAttrs = delegate.getAttributes();
        final SecurityLevel securityLevel = transportAttrs.get(GrpcAttributes.ATTR_SECURITY_LEVEL);
        if (securityLevel == null) {
          logger.warning(delegate + " didn't set ATTR_SECURITY_LEVEL. Assuming level to be NONE.");
        }
        CallCredentials.RequestInfo requestInfo = new CallCredentials.RequestInfo() {
            @Override
            public MethodDescriptor<?, ?> getMethodDescriptor() {
              return method;
            }

            @Override
            public SecurityLevel getSecurityLevel() {
              return firstNonNull(securityLevel, SecurityLevel.NONE);
            }

            @Override
            public String getAuthority() {
              return firstNonNull(callOptions.getAuthority(), authority);
            }

            @Override
            public Attributes getTransportAttrs() {
              return transportAttrs;
            }
          };
        try {
          creds.applyRequestMetadata(
              requestInfo, firstNonNull(callOptions.getExecutor(), appExecutor), applier);
        } catch (Throwable t) {
          applier.fail(Status.UNAUTHENTICATED
              .withDescription("Credentials should use fail() instead of throwing exceptions")
              .withCause(t));
        }
        return applier.returnStream();
      } else {
        return delegate.newStream(method, headers, callOptions);
      }
    }
  }
}
