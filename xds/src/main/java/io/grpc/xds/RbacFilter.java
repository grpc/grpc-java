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
import io.grpc.ServerInterceptor;
import io.grpc.xds.Filter.ServerInterceptorBuilder;
import javax.annotation.Nullable;

/** The pluggable RBAC http filter implementation. */
final class RbacFilter implements Filter, ServerInterceptorBuilder {

  static final String TYPE_URL = "type.googleapis.com/envoy.extensions.filters.http.rbac.v3.RBAC";

  @Override
  public String[] typeUrls() {
    return new String[] { TYPE_URL };
  }

  @Override
  public ConfigOrError<RbacConfig> parseFilterConfig(Message rawProtoMessage) {
    // TODO: implement me. rawProtoMessage is expected to be a packed proto message of
    //  envoy.extensions.filters.http.rbac.v3.RBAC
    return null;
  }

  @Override
  public ConfigOrError<RbacConfig> parseFilterConfigOverride(Message rawProtoMessage) {
    // TODO: implement me. rawProtoMessage is expected to be a packed proto message of
    //  envoy.extensions.filters.http.rbac.v3.RBACPerRoute
    return null;
  }

  @Override
  public ServerInterceptor buildServerInterceptor(
      FilterConfig config, @Nullable FilterConfig overrideConfig) {
    // TODO: implement me. Both config and overrideConfig (if present) are of type RbacConfig.
    return null;
  }
}
