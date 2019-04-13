/*
 * Copyright 2019 The gRPC Authors
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

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import javax.annotation.Nullable;

/**
 * Common utility methods for Epoll.
 */
public final class EpollUtils {

  // prevents instantiation.
  private EpollUtils() {}

  /**
   * Returns TCP_USER_TIMEOUT channel option for Epoll channel if Epoll is available, otherwise
   * null.
   */
  @Nullable
  public static ChannelOption<Integer> maybeGetTcpUserTimeoutOption() {
    return getEpollChannelOption("TCP_USER_TIMEOUT");
  }

  @Nullable
  @SuppressWarnings("unchecked")
  private static <T> ChannelOption<T> getEpollChannelOption(String optionName) {
    if (isEpollAvailable()) {
      try {
        return
            (ChannelOption<T>) Class.forName("io.netty.channel.epoll.EpollChannelOption")
                .getField(optionName)
                .get(null);
      } catch (Exception e) {
        throw new RuntimeException("ChannelOption(" + optionName + ") is not available", e);
      }
    }
    return null;
  }

  /** Checks if Epoll is available or not. */
  public static boolean isEpollAvailable() {
    try {
      return (boolean) (Boolean)
          Class
              .forName("io.netty.channel.epoll.Epoll")
              .getDeclaredMethod("isAvailable")
              .invoke(null);
    } catch (ClassNotFoundException e) {
      // this is normal if netty-epoll runtime dependency doesn't exist.
      return false;
    } catch (Exception e) {
      throw new RuntimeException("Exception while checking Epoll availability", e);
    }
  }

  static Throwable getEpollUnavailabilityCause() {
    try {
      return (Throwable)
          Class
              .forName("io.netty.channel.epoll.Epoll")
              .getDeclaredMethod("unavailabilityCause")
              .invoke(null);
    } catch (Exception e) {
      return e;
    }
  }

  // Must call when epoll is available
  static Class<? extends Channel> epollChannelType() {
    try {
      return Class.forName("io.netty.channel.epoll.EpollSocketChannel").asSubclass(Channel.class);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException("Cannot load EpollSocketChannel", e);
    }
  }

  // Must call when epoll is available
  static Class<? extends EventLoopGroup> epollEventLoopGroupType() {
    try {
      return Class
          .forName("io.netty.channel.epoll.EpollEventLoopGroup").asSubclass(EventLoopGroup.class);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException("Cannot load EpollEventLoopGroup", e);
    }
  }

  // Must call when epoll is available
  static Class<? extends ServerChannel> epollServerChannelType() {
    try {
      return Class
          .forName("io.netty.channel.epoll.EpollServerSocketChannel")
          .asSubclass(ServerChannel.class);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException("Cannot load EpollServerSocketChannel", e);
    }
  }
}
