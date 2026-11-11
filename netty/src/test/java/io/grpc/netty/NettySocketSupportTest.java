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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.TruthJUnit.assume;

import io.grpc.InternalChannelz.TcpInfo;
import io.grpc.netty.EpollTcpInfoAccessor.Field;
import io.grpc.netty.NettySocketSupport.NativeSocketOptions;
import io.grpc.netty.NettySocketSupport.NettySocketHelperImpl;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NettySocketSupportTest {
  private static final int TCP_ESTABLISHED = 1;

  @Test
  public void epollSocketChannel_populatesAllTcpInfoFields() throws Exception {
    FakeEpollTcpInfo source = new FakeEpollTcpInfo();
    long value = 1;
    for (Field field : Field.values()) {
      source.set(field, value++);
    }
    NettySocketHelperImpl helper = new NettySocketHelperImpl(FakeEpollTcpInfo.newAccessor());

    NativeSocketOptions options =
        helper.getNativeSocketOptions(new FakeEpollTcpInfo.FakeChannel(source));

    assertThat(options.otherInfo).isEmpty();
    TcpInfo tcpInfo = options.tcpInfo;
    assertThat(tcpInfo.state).isEqualTo((int) source.get(Field.STATE));
    assertThat(tcpInfo.caState).isEqualTo((int) source.get(Field.CA_STATE));
    assertThat(tcpInfo.retransmits).isEqualTo((int) source.get(Field.RETRANSMITS));
    assertThat(tcpInfo.probes).isEqualTo((int) source.get(Field.PROBES));
    assertThat(tcpInfo.backoff).isEqualTo((int) source.get(Field.BACKOFF));
    assertThat(tcpInfo.options).isEqualTo((int) source.get(Field.OPTIONS));
    assertThat(tcpInfo.sndWscale).isEqualTo((int) source.get(Field.SND_WSCALE));
    assertThat(tcpInfo.rcvWscale).isEqualTo((int) source.get(Field.RCV_WSCALE));
    assertThat(tcpInfo.rto).isEqualTo((int) source.get(Field.RTO));
    assertThat(tcpInfo.ato).isEqualTo((int) source.get(Field.ATO));
    assertThat(tcpInfo.sndMss).isEqualTo((int) source.get(Field.SND_MSS));
    assertThat(tcpInfo.rcvMss).isEqualTo((int) source.get(Field.RCV_MSS));
    assertThat(tcpInfo.unacked).isEqualTo((int) source.get(Field.UNACKED));
    assertThat(tcpInfo.sacked).isEqualTo((int) source.get(Field.SACKED));
    assertThat(tcpInfo.lost).isEqualTo((int) source.get(Field.LOST));
    assertThat(tcpInfo.retrans).isEqualTo((int) source.get(Field.RETRANS));
    assertThat(tcpInfo.fackets).isEqualTo((int) source.get(Field.FACKETS));
    assertThat(tcpInfo.lastDataSent).isEqualTo((int) source.get(Field.LAST_DATA_SENT));
    assertThat(tcpInfo.lastAckSent).isEqualTo((int) source.get(Field.LAST_ACK_SENT));
    assertThat(tcpInfo.lastDataRecv).isEqualTo((int) source.get(Field.LAST_DATA_RECV));
    assertThat(tcpInfo.lastAckRecv).isEqualTo((int) source.get(Field.LAST_ACK_RECV));
    assertThat(tcpInfo.pmtu).isEqualTo((int) source.get(Field.PMTU));
    assertThat(tcpInfo.rcvSsthresh).isEqualTo((int) source.get(Field.RCV_SSTHRESH));
    assertThat(tcpInfo.rtt).isEqualTo((int) source.get(Field.RTT));
    assertThat(tcpInfo.rttvar).isEqualTo((int) source.get(Field.RTTVAR));
    assertThat(tcpInfo.sndSsthresh).isEqualTo((int) source.get(Field.SND_SSTHRESH));
    assertThat(tcpInfo.sndCwnd).isEqualTo((int) source.get(Field.SND_CWND));
    assertThat(tcpInfo.advmss).isEqualTo((int) source.get(Field.ADVMSS));
    assertThat(tcpInfo.reordering).isEqualTo((int) source.get(Field.REORDERING));
  }

  @Test
  public void nonEpollChannel_returnsNull() throws Exception {
    NettySocketHelperImpl helper = new NettySocketHelperImpl(FakeEpollTcpInfo.newAccessor());

    assertThat(helper.getNativeSocketOptions(new EmbeddedChannel())).isNull();
  }

  @Test
  public void epollUnavailable_returnsNull() {
    NettySocketHelperImpl helper = new NettySocketHelperImpl(null);

    assertThat(helper.getNativeSocketOptions(new EmbeddedChannel())).isNull();
  }

  @Test
  public void closedChannel_tcpInfoFails_returnsNull() throws Exception {
    NettySocketHelperImpl helper = new NettySocketHelperImpl(
        EpollTcpInfoAccessor.create(ClosedFdChannel.class, FakeEpollTcpInfo.class));
    ClosedFdChannel channel = new ClosedFdChannel();
    channel.close();

    assertThat(helper.getNativeSocketOptions(channel)).isNull();
  }

  @Test
  public void realEpollSocketChannel_reportsEstablishedTcpInfo() throws Exception {
    assume().that(Utils.isEpollAvailable()).isTrue();

    EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, EpollIoHandler.newFactory());
    try {
      Channel server = new ServerBootstrap()
          .group(group)
          .channel(EpollServerSocketChannel.class)
          .childHandler(new ChannelInboundHandlerAdapter())
          .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
          .sync()
          .channel();
      Channel client = new Bootstrap()
          .group(group)
          .channel(EpollSocketChannel.class)
          .handler(new ChannelInboundHandlerAdapter())
          .connect(server.localAddress())
          .sync()
          .channel();

      TcpInfo tcpInfo = Utils.getSocketOptions(client).tcpInfo;

      assertThat(tcpInfo).isNotNull();
      assertThat(tcpInfo.state).isEqualTo(TCP_ESTABLISHED);
      assertThat(tcpInfo.sndMss).isGreaterThan(0);
      client.close().sync();
      server.close().sync();
    } finally {
      group.shutdownGracefully();
    }
  }

  public static final class ClosedFdChannel extends EmbeddedChannel {
    public void tcpInfo(FakeEpollTcpInfo info) {
      if (!isOpen()) {
        throw new ChannelException("getsockopt() failed: Bad file descriptor");
      }
    }
  }
}
