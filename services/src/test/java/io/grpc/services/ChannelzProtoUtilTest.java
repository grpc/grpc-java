/*
 * Copyright 2018, gRPC Authors All rights reserved.
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

package io.grpc.services;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import com.google.protobuf.Int64Value;
import com.google.protobuf.util.Timestamps;
import io.grpc.ConnectivityState;
import io.grpc.channelz.v1.Address;
import io.grpc.channelz.v1.Address.OtherAddress;
import io.grpc.channelz.v1.Address.TcpIpAddress;
import io.grpc.channelz.v1.Address.UdsAddress;
import io.grpc.channelz.v1.Channel;
import io.grpc.channelz.v1.ChannelData;
import io.grpc.channelz.v1.ChannelData.State;
import io.grpc.channelz.v1.ChannelRef;
import io.grpc.channelz.v1.Server;
import io.grpc.channelz.v1.ServerData;
import io.grpc.channelz.v1.ServerRef;
import io.grpc.channelz.v1.Socket;
import io.grpc.channelz.v1.SocketData;
import io.grpc.channelz.v1.SocketRef;
import io.grpc.channelz.v1.Subchannel;
import io.grpc.channelz.v1.SubchannelRef;
import io.grpc.internal.Channelz.ChannelStats;
import io.grpc.internal.WithLogId;
import io.grpc.services.ChannelzTestHelper.TestChannel;
import io.grpc.services.ChannelzTestHelper.TestServer;
import io.grpc.services.ChannelzTestHelper.TestSocket;
import io.netty.channel.unix.DomainSocketAddress;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ChannelzProtoUtilTest {

  private final TestChannel channel = new TestChannel();
  private final ChannelRef channelRef = ChannelRef
      .newBuilder()
      .setName(channel.toString())
      .setChannelId(channel.getLogId().getId())
      .build();
  private final ChannelData channelData = ChannelData
      .newBuilder()
      .setTarget("sometarget")
      .setState(State.READY)
      .setCallsStarted(1)
      .setCallsSucceeded(2)
      .setCallsFailed(3)
      .setLastCallStartedTimestamp(Timestamps.fromMillis(4))
      .build();

  private final TestChannel subchannel = new TestChannel();
  private final SubchannelRef subchannelRef = SubchannelRef
      .newBuilder()
      .setName(subchannel.toString())
      .setSubchannelId(subchannel.getLogId().getId())
      .build();
  private final ChannelData subchannelData = ChannelData
      .newBuilder()
      .setTarget("sometarget")
      .setState(State.READY)
      .setCallsStarted(1)
      .setCallsSucceeded(2)
      .setCallsFailed(3)
      .setLastCallStartedTimestamp(Timestamps.fromMillis(4))
      .build();

  private final TestServer server = new TestServer();
  private final ServerRef serverRef = ServerRef
      .newBuilder()
      .setName(server.toString())
      .setServerId(server.getLogId().getId())
      .build();
  private final ServerData serverData = ServerData
      .newBuilder()
      .setCallsStarted(1)
      .setCallsSucceeded(2)
      .setCallsFailed(3)
      .setLastCallStartedTimestamp(Timestamps.fromMillis(4))
      .build();

  private final TestSocket socket = new TestSocket();
  private final SocketRef socketRef = SocketRef
      .newBuilder()
      .setName(socket.toString())
      .setSocketId(socket.getLogId().getId())
      .build();
  private final SocketData socketData = SocketData
      .newBuilder()
      .setStreamsStarted(1)
      .setLastLocalStreamCreatedTimestamp(Timestamps.fromNanos(2))
      .setLastRemoteStreamCreatedTimestamp(Timestamps.fromNanos(3))
      .setStreamsSucceeded(4)
      .setStreamsFailed(5)
      .setMessagesSent(6)
      .setMessagesReceived(7)
      .setKeepAlivesSent(8)
      .setLastMessageSentTimestamp(Timestamps.fromNanos(9))
      .setLastMessageReceivedTimestamp(Timestamps.fromNanos(10))
      .setLocalFlowControlWindow(Int64Value.newBuilder().setValue(11).build())
      .setRemoteFlowControlWindow(Int64Value.newBuilder().setValue(12).build())
      .build();
  private final Address localAddress = Address
      .newBuilder()
      .setTcpipAddress(
          TcpIpAddress
              .newBuilder()
              .setIpAddress(ByteString.copyFrom(
                  ((InetSocketAddress) socket.local).getAddress().getAddress()))
              .build())
      .build();
  private final Address remoteAddress = Address
      .newBuilder()
      .setTcpipAddress(
          TcpIpAddress
              .newBuilder()
              .setIpAddress(ByteString.copyFrom(
                  ((InetSocketAddress) socket.remote).getAddress().getAddress()))
              .build())
      .build();

  @Test
  public void toChannelRef() {
    assertEquals(channelRef, ChannelzProtoUtil.toChannelRef(channel));
  }

  @Test
  public void toSubchannelRef() {
    assertEquals(subchannelRef, ChannelzProtoUtil.toSubchannelRef(subchannel));
  }

  @Test
  public void toServerRef() {
    assertEquals(serverRef, ChannelzProtoUtil.toServerRef(server));
  }

  @Test
  public void toSocketRef() {
    assertEquals(socketRef, ChannelzProtoUtil.toSocketRef(socket));
  }

  @Test
  public void toState() {
    for (ConnectivityState connectivityState : ConnectivityState.values()) {
      assertEquals(
          connectivityState.name(),
          ChannelzProtoUtil.toState(connectivityState).getValueDescriptor().getName());
    }
    assertEquals(State.UNKNOWN, ChannelzProtoUtil.toState(null));
  }

  @Test
  public void toSocket() throws Exception {
    assertEquals(
        Socket
            .newBuilder()
            .setRef(socketRef)
            .setLocal(localAddress)
            .setRemote(remoteAddress)
            .setData(socketData)
            .build(),
        ChannelzProtoUtil.toSocket(socket));
  }

  @Test
  public void toSocketData() {
    assertEquals(
        socketData,
        ChannelzProtoUtil.toSocketData(socket.transportStats));
  }

  @Test
  public void toAddress_inet() throws Exception {
    InetSocketAddress inet4 = new InetSocketAddress(Inet4Address.getByName("10.0.0.1"), 1000);
    assertEquals(
        Address.newBuilder().setTcpipAddress(
            TcpIpAddress
                .newBuilder()
                .setIpAddress(ByteString.copyFrom(inet4.getAddress().getAddress()))
                .build())
            .build(),
        ChannelzProtoUtil.toAddress(inet4));
  }

  @Test
  public void toAddress_uds() throws Exception {
    String path = "/tmp/foo";
    DomainSocketAddress uds = new DomainSocketAddress(path);
    assertEquals(
        Address.newBuilder().setUdsAddress(
            UdsAddress
                .newBuilder()
                .setFilename(path)
                .build())
            .build(),
        ChannelzProtoUtil.toAddress(uds));
  }

  @Test
  public void toAddress_other() throws Exception {
    final String name = "my name";
    SocketAddress other = new SocketAddress() {
      @Override
      public String toString() {
        return name;
      }
    };
    assertEquals(
        Address.newBuilder().setOtherAddress(
            OtherAddress
                .newBuilder()
                .setName(name)
                .build())
            .build(),
        ChannelzProtoUtil.toAddress(other));
  }

  @Test
  public void toServer() throws Exception {
    assertEquals(
        Server
            .newBuilder()
            .setRef(serverRef)
            .setData(serverData)
            .build(),
        ChannelzProtoUtil.toServer(server));
  }

  @Test
  public void toServerData() throws Exception {
    assertEquals(serverData, ChannelzProtoUtil.toServerData(server.serverStats));
  }

  @Test
  public void toChannel() throws Exception {
    Channel baseProto = Channel
        .newBuilder()
        .setRef(channelRef)
        .setData(channelData)
        .build();
    assertEquals(baseProto, ChannelzProtoUtil.toChannel(channel));

    channel.stats = toBuilder(channel.stats)
        .setSubchannels(ImmutableList.<WithLogId>of(subchannel))
        .build();

    assertEquals(
        baseProto
            .toBuilder()
            .addSubchannelRef(subchannelRef)
            .build(),
        ChannelzProtoUtil.toChannel(channel));

    TestChannel otherSubchannel = new TestChannel();
    channel.stats = toBuilder(channel.stats)
        .setSubchannels(ImmutableList.<WithLogId>of(subchannel, otherSubchannel))
        .build();
    assertEquals(
        baseProto
            .toBuilder()
            .addSubchannelRef(subchannelRef)
            .addSubchannelRef(ChannelzProtoUtil.toSubchannelRef(otherSubchannel))
            .build(),
        ChannelzProtoUtil.toChannel(channel));
  }

  @Test
  public void extractChannelData() {
    assertEquals(channelData, ChannelzProtoUtil.extractChannelData(channel.stats));
  }

  @Test
  public void toSubchannel_noChildren() throws Exception {
    Subchannel baseProto = Subchannel
        .newBuilder()
        .setRef(subchannelRef)
        .setData(subchannelData)
        .build();
    assertEquals(
        baseProto,
        ChannelzProtoUtil.toSubchannel(subchannel));
  }

  @Test
  public void toSubchannel_socketChildren() throws Exception {
    Subchannel baseProto = Subchannel
        .newBuilder()
        .setRef(subchannelRef)
        .setData(subchannelData)
        .build();

    subchannel.stats = toBuilder(subchannel.stats)
        .setSockets(ImmutableList.<WithLogId>of(socket))
        .build();

    assertEquals(
        baseProto.toBuilder()
            .addSocketRef(socketRef)
            .build(),
        ChannelzProtoUtil.toSubchannel(subchannel));

    TestSocket otherSocket = new TestSocket();
    subchannel.stats = toBuilder(subchannel.stats)
        .setSockets(ImmutableList.<WithLogId>of(socket, otherSocket))
        .build();
    assertEquals(
        baseProto
            .toBuilder()
            .addSocketRef(socketRef)
            .addSocketRef(ChannelzProtoUtil.toSocketRef(otherSocket))
            .build(),
        ChannelzProtoUtil.toSubchannel(subchannel));
  }

  @Test
  public void toSubchannel_subchannelChildren() throws Exception {
    Subchannel baseProto = Subchannel
        .newBuilder()
        .setRef(subchannelRef)
        .setData(subchannelData)
        .build();

    TestChannel subchannel1 = new TestChannel();
    subchannel.stats = toBuilder(subchannel.stats)
        .setSubchannels(ImmutableList.<WithLogId>of(subchannel1))
        .build();
    assertEquals(
        baseProto.toBuilder()
            .addSubchannelRef(ChannelzProtoUtil.toSubchannelRef(subchannel1))
            .build(),
        ChannelzProtoUtil.toSubchannel(subchannel));

    TestChannel subchannel2 = new TestChannel();
    subchannel.stats = toBuilder(subchannel.stats)
        .setSubchannels(ImmutableList.<WithLogId>of(subchannel1, subchannel2))
        .build();
    assertEquals(
        baseProto
            .toBuilder()
            .addSubchannelRef(ChannelzProtoUtil.toSubchannelRef(subchannel1))
            .addSubchannelRef(ChannelzProtoUtil.toSubchannelRef(subchannel2))
            .build(),
        ChannelzProtoUtil.toSubchannel(subchannel));
  }

  private static ChannelStats.Builder toBuilder(ChannelStats stats) {
    ChannelStats.Builder builder = new ChannelStats.Builder()
        .setTarget(stats.target)
        .setState(stats.state)
        .setCallsStarted(stats.callsStarted)
        .setCallsSucceeded(stats.callsSucceeded)
        .setCallsFailed(stats.callsFailed)
        .setLastCallStartedMillis(stats.lastCallStartedMillis);
    if (!stats.subchannels.isEmpty()) {
      builder.setSubchannels(stats.subchannels);
    }
    if (!stats.sockets.isEmpty()) {
      builder.setSockets(stats.sockets);
    }
    return builder;
  }
}
