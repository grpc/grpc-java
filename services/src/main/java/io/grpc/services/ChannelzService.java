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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Status;
import io.grpc.channelz.v1.ChannelzGrpc;
import io.grpc.channelz.v1.GetChannelRequest;
import io.grpc.channelz.v1.GetChannelResponse;
import io.grpc.channelz.v1.GetServerSocketsRequest;
import io.grpc.channelz.v1.GetServerSocketsResponse;
import io.grpc.channelz.v1.GetServersRequest;
import io.grpc.channelz.v1.GetServersResponse;
import io.grpc.channelz.v1.GetSocketRequest;
import io.grpc.channelz.v1.GetSocketResponse;
import io.grpc.channelz.v1.GetSubchannelRequest;
import io.grpc.channelz.v1.GetSubchannelResponse;
import io.grpc.channelz.v1.GetTopChannelsRequest;
import io.grpc.channelz.v1.GetTopChannelsResponse;
import io.grpc.internal.Channelz;
import io.grpc.internal.Channelz.ChannelStats;
import io.grpc.internal.Channelz.ServerList;
import io.grpc.internal.Channelz.ServerStats;
import io.grpc.internal.Channelz.SocketStats;
import io.grpc.internal.Instrumented;
import io.grpc.stub.StreamObserver;

public class ChannelzService extends ChannelzGrpc.ChannelzImplBase {
  private static final int DEFAULT_MAX_PAGE_SIZE = 25;
  private final Channelz channelz;
  private final int maxPageSize;

  public ChannelzService() {
    this(Channelz.instance(), DEFAULT_MAX_PAGE_SIZE);
  }

  @VisibleForTesting
  ChannelzService(Channelz channelz, int maxPageSize) {
    this.channelz = channelz;
    this.maxPageSize = maxPageSize;
  }

  /** Returns top level channel aka {@link io.grpc.internal.ManagedChannelImpl}. */
  @Override
  public void getTopChannels(
      GetTopChannelsRequest request, StreamObserver<GetTopChannelsResponse> responseObserver) {
    try {
      Channelz.RootChannelList rootChannels
          = channelz.getRootChannels(request.getStartChannelId(), maxPageSize);
      GetTopChannelsResponse.Builder responseBuilder = GetTopChannelsResponse
          .newBuilder()
          .setEnd(rootChannels.end);
      for (Instrumented<ChannelStats> c : rootChannels.channels) {
        responseBuilder.addChannel(ChannelzProtoUtil.toChannel(c));
      }
      responseObserver.onNext(responseBuilder.build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      responseObserver.onError(t);
    }
  }

  /** Returns a top level channel aka {@link io.grpc.internal.ManagedChannelImpl}. */
  @Override
  public void getChannel(
      GetChannelRequest request, StreamObserver<GetChannelResponse> responseObserver) {
    try {
      Instrumented<ChannelStats> s = channelz.getRootChannel(request.getChannelId());
      if (s == null) {
        responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
        return;
      }

      responseObserver.onNext(
          GetChannelResponse
              .newBuilder()
              .setChannel(ChannelzProtoUtil.toChannel(s))
              .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      responseObserver.onError(t);
    }
  }

  /** Returns servers. */
  @Override
  public void getServers(
      GetServersRequest request, StreamObserver<GetServersResponse> responseObserver) {
    try {
      ServerList servers = channelz.getServers(request.getStartServerId(), maxPageSize);
      GetServersResponse.Builder responseBuilder = GetServersResponse
          .newBuilder()
          .setEnd(servers.end);
      for (Instrumented<ServerStats> s : servers.servers) {
        responseBuilder.addServer(ChannelzProtoUtil.toServer(s));
      }
      responseObserver.onNext(responseBuilder.build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      responseObserver.onError(t);
    }
  }

  /** Returns a subchannel. */
  @Override
  public void getSubchannel(
      GetSubchannelRequest request, StreamObserver<GetSubchannelResponse> responseObserver) {
    try {
      Instrumented<ChannelStats> s = channelz.getSubchannel(request.getSubchannelId());
      if (s == null) {
        responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
        return;
      }

      responseObserver.onNext(
          GetSubchannelResponse
              .newBuilder()
              .setSubchannel(ChannelzProtoUtil.toSubchannel(s))
              .build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      responseObserver.onError(t);
    }
  }

  /** Returns a socket. */
  @Override
  public void getSocket(
      GetSocketRequest request, StreamObserver<GetSocketResponse> responseObserver) {
    try {
      Instrumented<SocketStats> socket = channelz.getSocket(request.getSocketId());
      if (socket == null) {
        responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
      } else {
        responseObserver.onNext(
            GetSocketResponse
                .newBuilder()
                .setSocket(ChannelzProtoUtil.toSocket(socket))
                .build());
        responseObserver.onCompleted();
      }
    } catch (Throwable t) {
      responseObserver.onError(t);
    }
  }

  @Override
  public void getServerSockets(
      GetServerSocketsRequest request, StreamObserver<GetServerSocketsResponse> responseObserver) {
    // TODO(zpencer): fill this one out after refactoring channelz class
    throw new UnsupportedOperationException();
  }
}
