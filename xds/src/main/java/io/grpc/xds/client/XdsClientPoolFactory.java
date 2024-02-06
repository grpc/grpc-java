package io.grpc.xds.client;

import io.grpc.internal.ObjectPool;
import io.grpc.xds.XdsInitializationException;
import java.util.Map;
import javax.annotation.Nullable;

public interface XdsClientPoolFactory {
  void setBootstrapOverride(Map<String, ?> bootstrap);

  @Nullable
  ObjectPool<XdsClient> get();

  ObjectPool<XdsClient> getOrCreate() throws XdsInitializationException;
}
