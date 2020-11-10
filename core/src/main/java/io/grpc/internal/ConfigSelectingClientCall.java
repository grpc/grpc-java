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
import io.grpc.Deadline;
import io.grpc.ForwardingClientCall;
import io.grpc.InternalConfigSelector;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.internal.ManagedChannelServiceConfig.MethodInfo;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
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
    if (interceptor != null) {
      delegate = interceptor.interceptCall(method, callOptions, channel);
    } else {
      delegate = channel.newCall(method, callOptions);
    }

    ManagedChannelServiceConfig config = (ManagedChannelServiceConfig) result.getConfig();
    MethodInfo methodInfo = config.getMethodConfig(method);
    applyMethodConfig(methodInfo);
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

  private void applyMethodConfig(MethodInfo info) {
    if (info == null) {
      return;
    }
    callOptions = callOptions.withOption(MethodInfo.KEY, info);
    if (info.timeoutNanos != null) {
      Deadline newDeadline = Deadline.after(info.timeoutNanos, TimeUnit.NANOSECONDS);
      Deadline existingDeadline = callOptions.getDeadline();
      // If the new deadline is sooner than the existing deadline, swap them.
      if (existingDeadline == null || newDeadline.compareTo(existingDeadline) < 0) {
        callOptions = callOptions.withDeadline(newDeadline);
      }
    }
    if (info.waitForReady != null) {
      callOptions =
          info.waitForReady ? callOptions.withWaitForReady() : callOptions.withoutWaitForReady();
    }
    if (info.maxInboundMessageSize != null) {
      Integer existingLimit = callOptions.getMaxInboundMessageSize();
      if (existingLimit != null) {
        callOptions =
            callOptions.withMaxInboundMessageSize(
                Math.min(existingLimit, info.maxInboundMessageSize));
      } else {
        callOptions = callOptions.withMaxInboundMessageSize(info.maxInboundMessageSize);
      }
    }
    if (info.maxOutboundMessageSize != null) {
      Integer existingLimit = callOptions.getMaxOutboundMessageSize();
      if (existingLimit != null) {
        callOptions =
            callOptions.withMaxOutboundMessageSize(
                Math.min(existingLimit, info.maxOutboundMessageSize));
      } else {
        callOptions = callOptions.withMaxOutboundMessageSize(info.maxOutboundMessageSize);
      }
    }
  }

  @Override
  public void cancel(@Nullable String message, @Nullable Throwable cause) {
    if (delegate != null) {
      delegate.cancel(message, cause);
    }
  }
}
