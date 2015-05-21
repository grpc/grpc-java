/*
 * Copyright 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.transport.netty;

import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Utility methods for working with native Netty transports. Use of the methods defined in this
 * class require a dependency on netty-transport-native-epoll and netty-tcnative.
 */
public final class NativeUtil {
  private NativeUtil() {
  }

  public static final String UNIX_DOMAIN_SOCKET_PREFIX = "unix://";

  /**
   * Supported native transports for Netty.
   */
  public enum Transport {
    EPOLL("io.netty.channel.epoll.EpollEventLoopGroup",
        "io.netty.channel.epoll.EpollSocketChannel",
        "io.netty.channel.epoll.EpollServerSocketChannel"),
    UNIX_DOMAIN_SOCKET("io.netty.channel.epoll.EpollEventLoopGroup",
        "io.netty.channel.epoll.EpollDomainSocketChannel",
        "io.netty.channel.epoll.EpollServerDomainSocketChannel");

    private final String eventLoopGroupClass;
    private final String clientSocketChannelClass;
    private final String serverSocketChannelClass;

    Transport(String eventLoopGroupClass, String clientSocketChannelClass,
              String serverSocketChannelClass) {
      this.eventLoopGroupClass = eventLoopGroupClass;
      this.clientSocketChannelClass = clientSocketChannelClass;
      this.serverSocketChannelClass = serverSocketChannelClass;
    }

    /**
     * Indicates whether or not this transport is supported on this system. Just ensures
     * that all necessary classes are available on classpath.
     */
    public boolean isSupported() {
      try {
        Class.forName(eventLoopGroupClass);
        Class.forName(clientSocketChannelClass);
        Class.forName(serverSocketChannelClass);
        return true;
      } catch (ClassNotFoundException e) {
        return false;
      }
    }

    /**
     * Instantiates a new {@link EventLoopGroup} for this {@link Transport}.
     */
    public EventLoopGroup newEventLoopGroup() {
      try {
        return (EventLoopGroup) Class.forName(eventLoopGroupClass).newInstance();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    /**
     * Gets the {@link Channel} type to be used for clients.
     */
    @SuppressWarnings("unchecked")
    public Class<? extends Channel> newClientSocketChannel() {
      try {
        return (Class<? extends Channel>) Class.forName(clientSocketChannelClass);
      } catch (ClassNotFoundException e) {
        throw new RuntimeException(e);
      }
    }

    /**
     * Gets the {@link Channel} type to be used for servers.
     */
    @SuppressWarnings("unchecked")
    public Class<? extends ServerChannel> newServerSocketChannel() {
      try {
        return (Class<? extends ServerChannel>) Class.forName(serverSocketChannelClass);
      } catch (ClassNotFoundException e) {
        throw new RuntimeException(e);
      }
    }

    /**
     * Utility method to configure the given {@link NettyChannelBuilder} for this transport.
     */
    public void configure( NettyChannelBuilder builder) {
      builder.eventLoopGroup(newEventLoopGroup());
      builder.channelType(newClientSocketChannel());
    }

    /**
     * Utility method to configure the given {@link NettyServerBuilder} for this transport.
     */
    public void configure( NettyServerBuilder builder) {
      builder.bossEventLoopGroup(newEventLoopGroup());
      builder.workerEventLoopGroup(newEventLoopGroup());
      builder.channelType(newServerSocketChannel());
    }
  }

  /**
   * Gets the {@link SocketAddress} class used for referring to Unix Domain Socket files.
   */
  @SuppressWarnings("unchecked")
  public static Class<? extends SocketAddress> getUnixDomainSocketAddressClass() {
    try {
      return (Class<? extends SocketAddress>) Class.forName(
          "io.netty.channel.unix.DomainSocketAddress");
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Creates a new Unix Domain Socket address using the given file.
   */
  public static SocketAddress newUnixDomainSocketAddress(File file) {
    Class<?> addressClass = getUnixDomainSocketAddressClass();
    try {
      return (SocketAddress) addressClass.getDeclaredConstructor(File.class)
          .newInstance(file);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Utility method that parses the given socket address an returns either an {@link
   * InetSocketAddress} (e.g. host:port) or a Unix Domain Socket address (e.g. unix://path/to/file).
   * When specifying  Unix Domain Socket address, the provided file is created if it doesn't already
   * exist. If this method creates the file, it also configures it to be deleted when the VM exits.
   */
  public static SocketAddress parseSocketAddress(String value) {
    if (value.startsWith(UNIX_DOMAIN_SOCKET_PREFIX)) {
      // Create the underlying file for the Unix Domain Socket.
      String filePath = value.substring(UNIX_DOMAIN_SOCKET_PREFIX.length());
      File file = new File(filePath);
      if (!file.isAbsolute()) {
        throw new IllegalArgumentException("File path must be absolute: " + filePath);
      }
      try {
        if (file.createNewFile()) {
          // If this application created the file, delete it when the application exits.
          file.deleteOnExit();
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      // Create the SocketAddress referencing the file.
      return newUnixDomainSocketAddress(file);
    }

    // Standard TCP/IP address.
    String[] parts = value.split(":");
    String host = parts[0];
    int port = Integer.parseInt(parts[1]);
    return new InetSocketAddress(host, port);
  }
}
