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

package io.grpc.authz;

import static com.google.common.base.Preconditions.checkNotNull;

import io.envoyproxy.envoy.config.rbac.v3.RBAC;
import io.grpc.InternalServerInterceptors;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.xds.ConfigOrError;
import io.grpc.xds.RbacConfig;
import io.grpc.xds.RbacFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Authorization server interceptor for static policy.
 */
public final class AuthorizationServerInterceptor implements ServerInterceptor {
  private final List<ServerInterceptor> interceptors = new ArrayList<>();

  int getInterceptorsCount() {
    return interceptors.size();
  }

  private AuthorizationServerInterceptor(String authorizationPolicy) 
      throws IllegalArgumentException, IOException {
    List<RBAC> rbacs = AuthorizationPolicyTranslator.translate(authorizationPolicy);
    if (rbacs == null || rbacs.isEmpty() || rbacs.size() > 2) {
      throw new IllegalArgumentException("Failed to create authorization engines");
    }
    for (RBAC rbac: rbacs) {
      ConfigOrError<RbacConfig> filterConfig = new RbacFilter().parseRbacConfig(
          io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC.newBuilder()
          .setRules(rbac).build());
      if (filterConfig.errorDetail != null) {
        throw new IllegalArgumentException("Rbac config is invalid");
      }
      interceptors.add(new RbacFilter().buildServerInterceptor(filterConfig.config, null));
    }
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, 
      ServerCallHandler<ReqT, RespT> next) {
    for (ServerInterceptor interceptor: interceptors) {
      next = InternalServerInterceptors.interceptCallHandlerCreate(interceptor, next);
    }
    return next.startCall(call, headers);
  }

  // Static method that creates an AuthorizationServerInterceptor.
  public static AuthorizationServerInterceptor create(String authorizationPolicy) 
      throws IllegalArgumentException, IOException {
    checkNotNull(authorizationPolicy, "authorizationPolicy");
    return new AuthorizationServerInterceptor(authorizationPolicy);
  }
}
