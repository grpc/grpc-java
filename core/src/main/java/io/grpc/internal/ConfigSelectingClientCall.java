/*
 * Copyright 2020 The gRPC Authors
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

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Context;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.InternalConfigSelector;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.internal.ManagedChannelServiceConfig.MethodInfo;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;

/**
 * A client call for a given channel that applies a given config selector when it starts.
 */
final class ConfigSelectingClientCall<ReqT, RespT> extends ForwardingClientCall<ReqT, RespT> {

  private final InternalConfigSelector configSelector;
  private final Channel channel;
  private final Executor callExecutor;
  private final MethodDescriptor<ReqT, RespT> method;
  private final Context context;
  private CallOptions callOptions;

  private ClientCall<ReqT, RespT> delegate;

  ConfigSelectingClientCall(
      InternalConfigSelector configSelector, Channel channel, Executor channelExecutor,
      MethodDescriptor<ReqT, RespT> method,
      CallOptions callOptions) {
    this.configSelector = configSelector;
    this.channel = channel;
    this.method = method;
    this.callOptions = callOptions;
    this.callExecutor =
        callOptions.getExecutor() == null ? channelExecutor : callOptions.getExecutor();
    this.context = Context.current();
  }

  @Override
  protected ClientCall<ReqT, RespT> delegate() {
    return delegate;
  }

  @Override
  public void start(Listener<RespT> observer, Metadata headers) {
    PickSubchannelArgs args = new PickSubchannelArgsImpl(method, headers, callOptions);
    InternalConfigSelector.Result result = configSelector.selectConfig(args);
    Status status = result.getStatus();
    if (!status.isOk()) {
      executeCloseObserverInContext(observer, status);
      return;
    }
    ClientInterceptor interceptor = result.getInterceptor();
    ManagedChannelServiceConfig config = (ManagedChannelServiceConfig) result.getConfig();
    MethodInfo methodInfo = config.getMethodConfig(method);
    if (methodInfo != null) {
      callOptions = callOptions.withOption(MethodInfo.KEY, methodInfo);
    }
    if (interceptor != null) {
      delegate = interceptor.interceptCall(method, callOptions, channel);
    } else if (result.getCallOptions() != null) {
      // TODO(zdapeng): Delete this when migrating to use interceptor-based config selector only.
      callOptions = result.getCallOptions();
      if (methodInfo != null) {
        callOptions = callOptions.withOption(MethodInfo.KEY, methodInfo);
      }
      Runnable callback = result.getCommittedCallback();
      if (callback != null) {
        delegate =
            new CommittedCallbackInterceptor(callback).interceptCall(method, callOptions, channel);
      } else {
        delegate = channel.newCall(method, callOptions);
      }
    } else {
      delegate = channel.newCall(method, callOptions);
    }
    delegate.start(observer, headers);
  }

  private void executeCloseObserverInContext(
      final Listener<RespT> observer, final Status status) {
    class CloseInContext extends ContextRunnable {
      CloseInContext() {
        super(context);
      }

      @Override
      public void runInContext() {
        observer.onClose(status, new Metadata());
      }
    }

    callExecutor.execute(new CloseInContext());
  }

  @Override
  public void cancel(@Nullable String message, @Nullable Throwable cause) {
    if (delegate != null) {
      delegate.cancel(message, cause);
    }
  }

  // TODO(zdapeng): Delete this when migrating to use interceptor-based config selector only.
  private final class CommittedCallbackInterceptor implements ClientInterceptor {
    final Runnable callback;

    CommittedCallbackInterceptor(Runnable callback) {
      this.callback = callback;
    }

    @Override
    public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> interceptCall(
        MethodDescriptor<RequestT, ResponseT> method, CallOptions callOptions, Channel next) {
      return new SimpleForwardingClientCall<RequestT, ResponseT>(
          next.newCall(method, callOptions)) {
        @Override
        public void start(Listener<ResponseT> listener, Metadata headers) {
          listener = new SimpleForwardingClientCallListener<ResponseT>(listener) {
            boolean committed;

            @Override
            public void onHeaders(Metadata headers) {
              committed = true;
              callback.run();
              delegate().onHeaders(headers);
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
              if (!committed) {
                callback.run();
              }
              delegate().onClose(status, trailers);
            }
          };
          delegate().start(listener, headers);
        }
      };
    }
  }
}
