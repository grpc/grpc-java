package io.grpc.xds;

import io.grpc.xds.client.BootstrapperImpl;
import java.util.Map;

public class GrpcBootstrapperImpl extends BootstrapperImpl {
  public GrpcBootstrapperImpl() {
  }

  @Override
  protected BootstrapInfo bootstrap(Map<String, ?> rawData) throws XdsInitializationException {
    return super.bootstrap(rawData);
  }
}
