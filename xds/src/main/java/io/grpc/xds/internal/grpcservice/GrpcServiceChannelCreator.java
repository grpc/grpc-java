package io.grpc.xds.internal.grpcservice;

import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.grpc.ManagedChannel;

// Interface exists so that unit tests can mock it.
public interface GrpcServiceChannelCreator {
  ManagedChannel create(GrpcService grpcService);
}
