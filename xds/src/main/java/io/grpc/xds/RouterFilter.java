/*
 * Copyright 2021 The gRPC Authors
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

import com.google.protobuf.Message;
import io.grpc.ClientInterceptor;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.ServerInterceptor;
import io.grpc.xds.Filter.ClientInterceptorBuilder;
import io.grpc.xds.Filter.ServerInterceptorBuilder;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;

/**
 * Router filter implementation. Currently this filter does not parse any field in the config.
 */
enum RouterFilter implements Filter, ClientInterceptorBuilder, ServerInterceptorBuilder {
  INSTANCE;

  static final String TYPE_URL =
      "type.googleapis.com/envoy.extensions.filters.http.router.v3.Router";

  static final FilterConfig ROUTER_CONFIG = new FilterConfig() {
    @Override
    public String typeUrl() {
      return RouterFilter.TYPE_URL;
    }

    @Override
    public String toString() {
      return "ROUTER_CONFIG";
    }
  };

  @Override
  public String[] typeUrls() {
    return new String[] { TYPE_URL };
  }

  @Override
  public ConfigOrError<? extends FilterConfig> parseFilterConfig(Message rawProtoMessage) {
    return ConfigOrError.fromConfig(ROUTER_CONFIG);
  }

  @Override
  public ConfigOrError<? extends FilterConfig> parseFilterConfigOverride(Message rawProtoMessage) {
    return ConfigOrError.fromError("Router Filter should not have override config");
  }

  @Nullable
  @Override
  public ClientInterceptor buildClientInterceptor(
      FilterConfig config, @Nullable FilterConfig overrideConfig, PickSubchannelArgs args,
      ScheduledExecutorService scheduler) {
    return null;
  }

  @Nullable
  @Override
  public ServerInterceptor buildServerInterceptor(
      FilterConfig config, @Nullable Filter.FilterConfig overrideConfig) {
    return null;
  }
}
