package io.grpc.xds.internal.datatype;

import com.google.auto.value.AutoValue;
import com.google.protobuf.Duration;

@AutoValue
public abstract class GrpcService {

  abstract Duration timeout();

  public static GrpcService fromEnvoyProto(
      io.envoyproxy.envoy.config.core.v3.GrpcService grpcService) {
    return GrpcService.create(grpcService.getTimeout());
  }

  public static GrpcService create(Duration timeout) {
    return new AutoValue_GrpcService(timeout);
  }
}
