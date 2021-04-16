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

package io.grpc.services;

import io.grpc.ExperimentalApi;
import io.grpc.channelz.v1.ChannelzGrpc;
import io.grpc.channelz.v1.GetChannelRequest;
import io.grpc.channelz.v1.GetChannelResponse;
import io.grpc.channelz.v1.GetServerRequest;
import io.grpc.channelz.v1.GetServerResponse;
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
import io.grpc.stub.StreamObserver;

/**
 * The channelz service provides stats about a running gRPC process.
 *
 * @deprecated Use {@link io.grpc.protobuf.services.ChannelzService} instead.
 */
@Deprecated
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/4206")
public final class ChannelzService extends ChannelzGrpc.ChannelzImplBase {
  private final io.grpc.protobuf.services.ChannelzService delegate;

  /**
   * Creates an instance.
   */
  public static ChannelzService newInstance(int maxPageSize) {
    return new ChannelzService(maxPageSize);
  }

  private ChannelzService(int maxPageSize) {
    delegate = io.grpc.protobuf.services.ChannelzService.newInstance(maxPageSize);
  }

  /** Returns top level channel aka {@link io.grpc.ManagedChannel}. */
  @Override
  public void getTopChannels(
      GetTopChannelsRequest request, StreamObserver<GetTopChannelsResponse> responseObserver) {
    delegate.getTopChannels(request, responseObserver);
  }

  /** Returns a top level channel aka {@link io.grpc.ManagedChannel}. */
  @Override
  public void getChannel(
      GetChannelRequest request, StreamObserver<GetChannelResponse> responseObserver) {
    delegate.getChannel(request, responseObserver);
  }

  /** Returns servers. */
  @Override
  public void getServers(
      GetServersRequest request, StreamObserver<GetServersResponse> responseObserver) {
    delegate.getServers(request, responseObserver);
  }

  /** Returns a server. */
  @Override
  public void getServer(
      GetServerRequest request, StreamObserver<GetServerResponse> responseObserver) {
    delegate.getServer(request, responseObserver);
  }

  /** Returns a subchannel. */
  @Override
  public void getSubchannel(
      GetSubchannelRequest request, StreamObserver<GetSubchannelResponse> responseObserver) {
    delegate.getSubchannel(request, responseObserver);
  }

  /** Returns a socket. */
  @Override
  public void getSocket(
      GetSocketRequest request, StreamObserver<GetSocketResponse> responseObserver) {
    delegate.getSocket(request, responseObserver);
  }

  @Override
  public void getServerSockets(
      GetServerSocketsRequest request, StreamObserver<GetServerSocketsResponse> responseObserver) {
    delegate.getServerSockets(request, responseObserver);
  }
}
