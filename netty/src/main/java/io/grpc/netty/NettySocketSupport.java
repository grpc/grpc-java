/*
 * Copyright 2018 The gRPC Authors
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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import io.grpc.InternalChannelz.TcpInfo;
import io.grpc.netty.EpollTcpInfoAccessor.Field;
import io.netty.channel.Channel;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * An class for getting low level socket info.
 */
final class NettySocketSupport {
  private static final Logger log = Logger.getLogger(NettySocketSupport.class.getName());

  private static volatile Helper instance =
      new NettySocketHelperImpl(EpollTcpInfoAccessor.INSTANCE);

  interface Helper {
    /**
     * Returns the info on the socket if possible. Returns null if the info can not be discovered.
     */
    @Nullable
    NativeSocketOptions getNativeSocketOptions(Channel ch);
  }

  /**
   * A TcpInfo and additional other info that will be turned into channelz socket options.
   */
  public static class NativeSocketOptions {
    @Nullable
    public final TcpInfo tcpInfo;
    public final ImmutableMap<String, String> otherInfo;

    /** Creates an instance. */
    public NativeSocketOptions(
        TcpInfo tcpInfo,
        Map<String, String> otherInfo) {
      Preconditions.checkNotNull(otherInfo);
      this.tcpInfo = tcpInfo;
      this.otherInfo = ImmutableMap.copyOf(otherInfo);
    }
  }

  public static NativeSocketOptions getNativeSocketOptions(Channel ch) {
    return instance.getNativeSocketOptions(ch);
  }

  static void setHelper(Helper helper) {
    instance = Preconditions.checkNotNull(helper);
  }

  @VisibleForTesting
  static final class NettySocketHelperImpl implements Helper {
    @Nullable
    private final EpollTcpInfoAccessor epollInfo;

    NettySocketHelperImpl(@Nullable EpollTcpInfoAccessor epollInfo) {
      this.epollInfo = epollInfo;
    }

    @Override
    @Nullable
    public NativeSocketOptions getNativeSocketOptions(Channel ch) {
      if (epollInfo == null || !epollInfo.isEpollSocketChannel(ch)) {
        return null;
      }
      TcpInfo tcpInfo;
      try {
        tcpInfo = readTcpInfo(epollInfo, ch);
      } catch (ReflectiveOperationException e) {
        log.log(Level.FINE, "Failed to read Epoll tcp_info", e);
        return null;
      }
      return new NativeSocketOptions(tcpInfo, ImmutableMap.<String, String>of());
    }

    // Channelz fields are uint32, so narrowing the epoll values to int keeps their bits.
    private static TcpInfo readTcpInfo(EpollTcpInfoAccessor epollInfo, Channel ch)
        throws ReflectiveOperationException {
      Object info = epollInfo.newTcpInfo();
      epollInfo.read(ch, info);
      return new TcpInfo.Builder()
          .setState((int) epollInfo.get(info, Field.STATE))
          .setCaState((int) epollInfo.get(info, Field.CA_STATE))
          .setRetransmits((int) epollInfo.get(info, Field.RETRANSMITS))
          .setProbes((int) epollInfo.get(info, Field.PROBES))
          .setBackoff((int) epollInfo.get(info, Field.BACKOFF))
          .setOptions((int) epollInfo.get(info, Field.OPTIONS))
          .setSndWscale((int) epollInfo.get(info, Field.SND_WSCALE))
          .setRcvWscale((int) epollInfo.get(info, Field.RCV_WSCALE))
          .setRto((int) epollInfo.get(info, Field.RTO))
          .setAto((int) epollInfo.get(info, Field.ATO))
          .setSndMss((int) epollInfo.get(info, Field.SND_MSS))
          .setRcvMss((int) epollInfo.get(info, Field.RCV_MSS))
          .setUnacked((int) epollInfo.get(info, Field.UNACKED))
          .setSacked((int) epollInfo.get(info, Field.SACKED))
          .setLost((int) epollInfo.get(info, Field.LOST))
          .setRetrans((int) epollInfo.get(info, Field.RETRANS))
          .setFackets((int) epollInfo.get(info, Field.FACKETS))
          .setLastDataSent((int) epollInfo.get(info, Field.LAST_DATA_SENT))
          .setLastAckSent((int) epollInfo.get(info, Field.LAST_ACK_SENT))
          .setLastDataRecv((int) epollInfo.get(info, Field.LAST_DATA_RECV))
          .setLastAckRecv((int) epollInfo.get(info, Field.LAST_ACK_RECV))
          .setPmtu((int) epollInfo.get(info, Field.PMTU))
          .setRcvSsthresh((int) epollInfo.get(info, Field.RCV_SSTHRESH))
          .setRtt((int) epollInfo.get(info, Field.RTT))
          .setRttvar((int) epollInfo.get(info, Field.RTTVAR))
          .setSndSsthresh((int) epollInfo.get(info, Field.SND_SSTHRESH))
          .setSndCwnd((int) epollInfo.get(info, Field.SND_CWND))
          .setAdvmss((int) epollInfo.get(info, Field.ADVMSS))
          .setReordering((int) epollInfo.get(info, Field.REORDERING))
          .build();
    }
  }
}
