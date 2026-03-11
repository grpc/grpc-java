package io.grpc.xds.internal.grpcservice;

import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.grpc.ManagedChannel;

public final class GrpcServiceChannelCreatorImpl implements GrpcServiceChannelCreator {
  @Override
  public ManagedChannel create(GrpcService grpcService) {
    // TODO
    return null;
  }
}
