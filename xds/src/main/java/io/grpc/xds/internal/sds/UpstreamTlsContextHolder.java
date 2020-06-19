/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.xds.internal.sds;

import static com.google.common.base.Preconditions.checkNotNull;

import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;

final class UpstreamTlsContextHolder implements TlsContextHolder {

  private final UpstreamTlsContext upstreamTlsContext;

  UpstreamTlsContextHolder(UpstreamTlsContext upstreamTlsContext) {
    this.upstreamTlsContext = checkNotNull(upstreamTlsContext, "upstreamTlsContext");
  }

  public UpstreamTlsContext getUpstreamTlsContext() {
    return upstreamTlsContext;
  }

  @Override
  public CommonTlsContext getCommonTlsContext() {
    return upstreamTlsContext.getCommonTlsContext();
  }
}
