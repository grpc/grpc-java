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

package io.grpc.netty;

import com.google.common.base.Preconditions;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import javax.annotation.Nullable;

/**
 * Allows autoFlowControl to be turned on and off from interop testing and flow control windows to
 * be accessed.
 */
final class NettyHandlerSettings {

  // These will be the most recently created handlers created using NettyClientTransport and
  // NettyServerTransport
  @Nullable
  private static AbstractNettyHandler clientHandler;
  @Nullable
  private static AbstractNettyHandler serverHandler;

  static void setAutoWindow(AbstractNettyHandler handler) {
    if (!handler.isAutoTuneFlowControlOn()) {
      return;
    }
    synchronized (NettyHandlerSettings.class) {
      if (handler instanceof NettyClientHandler) {
        clientHandler = handler;
      } else if (handler instanceof NettyServerHandler) {
        serverHandler = handler;
      } else {
        throw new RuntimeException("Expecting NettyClientHandler or NettyServerHandler");
      }
    }
  }

  public static synchronized int getLatestClientWindow() {
    return getLatestWindow(clientHandler);
  }

  public static synchronized int getLatestServerWindow() {
    return getLatestWindow(serverHandler);
  }

  private static synchronized void clearHandlers() {
    clientHandler = null;
    serverHandler = null;
  }

  private static synchronized int getLatestWindow(AbstractNettyHandler handler) {
    Preconditions.checkNotNull(handler);
    return handler.decoder().flowController()
        .initialWindowSize(handler.connection().connectionStream());
  }

  static ChannelFutureListener cleanUpTask() {
    return new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        NettyHandlerSettings.clearHandlers();
      }
    };
  }
}
