/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.xds;

import io.grpc.CallCredentials;
import io.grpc.Internal;
import io.grpc.MetricRecorder;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.client.Bootstrapper.BootstrapInfo;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsInitializationException;
import java.util.Map;

/**
 * Accessor for global factory for managing XdsClient instance.
 */
@Internal
public final class InternalSharedXdsClientPoolProvider {
  // Prevent instantiation
  private InternalSharedXdsClientPoolProvider() {}

  /**
   * Override the global bootstrap.
   *
   * @deprecated Use InternalGrpcBootstrapperImpl.parseBootstrap() and pass the result to
   *     getOrCreate().
   */
  @Deprecated
  public static void setDefaultProviderBootstrapOverride(Map<String, ?> bootstrap) {
    GrpcBootstrapperImpl.setDefaultBootstrapOverride(bootstrap);
  }

  /**
   * Get an XdsClient pool.
   *
   * @deprecated Use InternalGrpcBootstrapperImpl.parseBootstrap() and pass the result to the other
   *     getOrCreate().
   */
  @Deprecated
  public static ObjectPool<XdsClient> getOrCreate(String target)
      throws XdsInitializationException {
    return getOrCreate(target, new MetricRecorder() {});
  }

  /**
   * Get an XdsClient pool.
   *
   * @deprecated Use InternalGrpcBootstrapperImpl.parseBootstrap() and pass the result to the other
   *     getOrCreate().
   */
  @Deprecated
  public static ObjectPool<XdsClient> getOrCreate(String target, MetricRecorder metricRecorder)
      throws XdsInitializationException {
    return getOrCreate(target, metricRecorder, null);
  }

  /**
   * Get an XdsClient pool.
   *
   * @deprecated Use InternalGrpcBootstrapperImpl.parseBootstrap() and pass the result to the other
   *     getOrCreate().
   */
  @Deprecated
  public static ObjectPool<XdsClient> getOrCreate(
      String target, MetricRecorder metricRecorder, CallCredentials transportCallCredentials)
      throws XdsInitializationException {
    return SharedXdsClientPoolProvider.getDefaultProvider()
        .getOrCreate(target, metricRecorder, transportCallCredentials);
  }

  public static XdsClientResult getOrCreate(
      String target, BootstrapInfo bootstrapInfo, MetricRecorder metricRecorder,
      CallCredentials transportCallCredentials) {
    return new XdsClientResult(SharedXdsClientPoolProvider.getDefaultProvider()
        .getOrCreate(target, bootstrapInfo, metricRecorder, transportCallCredentials, null));
  }

  /**
   * An ObjectPool, except without exposing io.grpc.internal, which must not be used for
   * cross-package APIs.
   */
  public static final class XdsClientResult {
    private final ObjectPool<XdsClient> xdsClientPool;

    XdsClientResult(ObjectPool<XdsClient> xdsClientPool) {
      this.xdsClientPool = xdsClientPool;
    }

    public XdsClient getObject() {
      return xdsClientPool.getObject();
    }

    public XdsClient returnObject(XdsClient xdsClient) {
      return xdsClientPool.returnObject(xdsClient);
    }
  }
}
