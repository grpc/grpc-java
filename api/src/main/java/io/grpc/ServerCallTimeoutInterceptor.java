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

package io.grpc;

/**
 * An optional ServerInterceptor that can interrupt server calls that are running for too long time.
 * In this way, it prevents problematic code from using up all threads.
 *
 * <p>How to use: you can add it to your server using ServerBuilder#intercept(ServerInterceptor).
 *
 * <p>Limitation: it only applies the timeout to unary calls
 * (streaming calls will still run without timeout).
 */
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
      return new TimeoutServerCallListener<>(
              serverCallHandler.startCall(serverCall, metadata), serverTimeoutManager);
    } else {
      return serverCallHandler.startCall(serverCall, metadata);
    }
  }

  /** A listener that intercepts the RPC method invocation for timeout control. */
  private static class TimeoutServerCallListener<ReqT>
      extends ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {

    private final ServerTimeoutManager serverTimeoutManager;

    private TimeoutServerCallListener(
        ServerCall.Listener<ReqT> delegate,
        ServerTimeoutManager serverTimeoutManager) {
      super(delegate);
      this.serverTimeoutManager = serverTimeoutManager;
    }

    /**
     * Intercepts onHalfClose() because the RPC method is called in it. See
     * io.grpc.stub.ServerCalls.UnaryServerCallHandler.UnaryServerCallListener
     */
    @Override
    public void onHalfClose() {
      serverTimeoutManager.withTimeout(super::onHalfClose);
    }
  }
}
