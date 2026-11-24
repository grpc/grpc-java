/*
 * Copyright 2026 The gRPC Authors
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

import com.google.common.annotations.VisibleForTesting;
import io.netty.channel.Channel;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Reflective access to {@code EpollSocketChannel.tcpInfo()}, since grpc-netty does not depend on
 * netty-epoll at compile time.
 */
final class EpollTcpInfoAccessor {
  private static final Logger log = Logger.getLogger(EpollTcpInfoAccessor.class.getName());

  @Nullable
  static final EpollTcpInfoAccessor INSTANCE = load();

  enum Field {
    STATE("state"),
    CA_STATE("caState"),
    RETRANSMITS("retransmits"),
    PROBES("probes"),
    BACKOFF("backoff"),
    OPTIONS("options"),
    SND_WSCALE("sndWscale"),
    RCV_WSCALE("rcvWscale"),
    RTO("rto"),
    ATO("ato"),
    SND_MSS("sndMss"),
    RCV_MSS("rcvMss"),
    UNACKED("unacked"),
    SACKED("sacked"),
    LOST("lost"),
    RETRANS("retrans"),
    FACKETS("fackets"),
    LAST_DATA_SENT("lastDataSent"),
    LAST_ACK_SENT("lastAckSent"),
    LAST_DATA_RECV("lastDataRecv"),
    LAST_ACK_RECV("lastAckRecv"),
    PMTU("pmtu"),
    RCV_SSTHRESH("rcvSsthresh"),
    RTT("rtt"),
    RTTVAR("rttvar"),
    SND_SSTHRESH("sndSsthresh"),
    SND_CWND("sndCwnd"),
    ADVMSS("advmss"),
    REORDERING("reordering"),
    TOTAL_RETRANS("totalRetrans");

    final String methodName;

    Field(String methodName) {
      this.methodName = methodName;
    }
  }

  private final Class<?> channelClass;
  private final Constructor<?> infoConstructor;
  private final Method tcpInfo;
  private final EnumMap<Field, Method> getters;

  private EpollTcpInfoAccessor(
      Class<?> channelClass, Constructor<?> infoConstructor, Method tcpInfo,
      EnumMap<Field, Method> getters) {
    this.channelClass = channelClass;
    this.infoConstructor = infoConstructor;
    this.tcpInfo = tcpInfo;
    this.getters = getters;
  }

  @Nullable
  private static EpollTcpInfoAccessor load() {
    boolean epollAvailable = false;
    try {
      Class<?> epollClass = Class.forName("io.netty.channel.epoll.Epoll");
      Method isAvailableMethod = epollClass.getDeclaredMethod("isAvailable");
      epollAvailable = (Boolean) isAvailableMethod.invoke(null);
      if (epollAvailable) {
        return create(
            Class.forName("io.netty.channel.epoll.EpollSocketChannel"),
            Class.forName("io.netty.channel.epoll.EpollTcpInfo"));
      }
    } catch (ReflectiveOperationException e) {
      log.log(Level.FINE, "Failed to initialize Epoll tcp_info reflection", e);
    } finally {
      log.log(Level.FINE, "Epoll available during static init of EpollTcpInfoAccessor:"
          + "{0}", epollAvailable);
    }
    return null;
  }

  @VisibleForTesting
  static EpollTcpInfoAccessor create(Class<?> channelClass, Class<?> infoClass)
      throws ReflectiveOperationException {
    EnumMap<Field, Method> getters = new EnumMap<>(Field.class);
    for (Field field : Field.values()) {
      getters.put(field, infoClass.getMethod(field.methodName));
    }
    return new EpollTcpInfoAccessor(
        channelClass,
        infoClass.getDeclaredConstructor(),
        channelClass.getMethod("tcpInfo", infoClass),
        getters);
  }

  boolean isEpollSocketChannel(Channel channel) {
    return channelClass.isInstance(channel);
  }

  Object newTcpInfo() throws ReflectiveOperationException {
    return infoConstructor.newInstance();
  }

  /** Fills {@code info} with the current {@code TCP_INFO} of {@code channel}. */
  void read(Channel channel, Object info) throws ReflectiveOperationException {
    tcpInfo.invoke(channel, info);
  }

  long get(Object info, Field field) throws ReflectiveOperationException {
    return ((Number) getters.get(field).invoke(info)).longValue();
  }
}
