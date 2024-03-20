/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.util;

import io.grpc.Context;
import io.grpc.ExperimentalApi;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/**
 * An optional ServerInterceptor to stop server calls at best effort when the timeout is reached.
 * In this way, it prevents problematic code from excessively using up all threads in the pool.
 *
 * <p>How to use: install it to your server using ServerBuilder#intercept(ServerInterceptor).
 *
 * <p>Limitation: it only applies the timeout to unary calls
 * (long-running streaming calls are allowed, so they can run without this timeout limit).
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/10361")
public class ServerCallTimeoutInterceptor implements ServerInterceptor {

  private final ServerTimeoutManager serverTimeoutManager;

  public ServerCallTimeoutInterceptor(ServerTimeoutManager serverTimeoutManager) {
    this.serverTimeoutManager = serverTimeoutManager;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> serverCall,
      Metadata metadata,
      ServerCallHandler<ReqT, RespT> serverCallHandler) {
    // Only intercepts unary calls because the timeout is inapplicable to streaming calls.
    if (serverCall.getMethodDescriptor().getType().clientSendsOneMessage()) {
      ServerCall<ReqT, RespT> serializingServerCall = new SerializingServerCall<>(serverCall);
      Context.CancellableContext timeoutContext =
              serverTimeoutManager.startTimeoutContext(serializingServerCall);
      if (timeoutContext != null) {
        return new TimeoutServerCallListener<>(
                serverCallHandler.startCall(serializingServerCall, metadata),
                timeoutContext,
                serverTimeoutManager);
      }
    }
    return serverCallHandler.startCall(serverCall, metadata);
  }

  /** A listener that intercepts RPC callbacks for timeout control. */
  static class TimeoutServerCallListener<ReqT>
      extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {

    private final Context.CancellableContext context;
    private final ServerTimeoutManager serverTimeoutManager;

    private TimeoutServerCallListener(
        ServerCall.Listener<ReqT> delegate,
        Context.CancellableContext context,
        ServerTimeoutManager serverTimeoutManager) {
      super(delegate);
      this.context = context;
      this.serverTimeoutManager = serverTimeoutManager;
    }

    @Override
    public void onMessage(ReqT message) {
      serverTimeoutManager.runWithContext(context, () -> super.onMessage(message));
    }

    /**
     * Adds interruption here because the application RPC method is called in halfClose(). See
     * io.grpc.stub.ServerCalls.UnaryServerCallHandler.UnaryServerCallListener
     */
    @Override
    public void onHalfClose() {
      serverTimeoutManager.runWithContextInterruptibly(context, super::onHalfClose);
    }

    @Override
    public void onCancel() {
      try {
        serverTimeoutManager.runWithContext(context, super::onCancel);
      } finally {
        // Cancel the timeout when the call is finished.
        context.close();
      }
    }

    @Override
    public void onComplete() {
      try {
        serverTimeoutManager.runWithContext(context, super::onComplete);
      } finally {
        // Cancel the timeout when the call is finished.
        context.close();
      }
    }

    @Override
    public void onReady() {
      serverTimeoutManager.runWithContext(context, super::onReady);
    }
  }
}
