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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC;
import io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBACPerRoute;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.xds.Filter.ServerInterceptorBuilder;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AuthConfig;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AuthDecision;
import io.grpc.xds.internal.rbac.engine.RbacParser;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/** RBAC Http filter implementation. */
final class RbacFilter implements Filter, ServerInterceptorBuilder {
  private static final Logger logger = Logger.getLogger(RbacFilter.class.getName());

  static final RbacFilter INSTANCE = new RbacFilter();

  static final String TYPE_URL =
          "type.googleapis.com/envoy.extensions.filters.http.rbac.v3.RBAC";

  private static final String TYPE_URL_OVERRIDE_CONFIG =
          "type.googleapis.com/envoy.extensions.filters.http.rbac.v3.RBACPerRoute";

  RbacFilter() {}

  @Override
  public String[] typeUrls() {
    return new String[] { TYPE_URL, TYPE_URL_OVERRIDE_CONFIG };
  }

  @Override
  public ConfigOrError<RbacConfig> parseFilterConfig(Message rawProtoMessage) {
    RBAC rbacProto;
    if (!(rawProtoMessage instanceof Any)) {
      return ConfigOrError.fromError("Invalid config type: " + rawProtoMessage.getClass());
    }
    Any anyMessage = (Any) rawProtoMessage;
    try {
      rbacProto = anyMessage.unpack(RBAC.class);
    } catch (InvalidProtocolBufferException e) {
      return ConfigOrError.fromError("Invalid proto: " + e);
    }
    return parseRbacConfig(rbacProto);
  }

  @VisibleForTesting
  static ConfigOrError<RbacConfig> parseRbacConfig(RBAC rbac) {
    if (!rbac.hasRules()) {
      return ConfigOrError.fromConfig(RbacConfig.create(null));
    }
    io.envoyproxy.envoy.config.rbac.v3.RBAC rbacConfig = rbac.getRules();
    if (rbacConfig.getAction() == io.envoyproxy.envoy.config.rbac.v3.RBAC.Action.LOG) {
      return ConfigOrError.fromConfig(RbacConfig.create(null));
    }
    try {
      return ConfigOrError.fromConfig(RbacConfig.create(RbacParser.parseRbac(rbacConfig)));
    } catch (Exception e) {
      return ConfigOrError.fromError(e.getMessage());
    }
  }

  @Override
  public ConfigOrError<RbacConfig> parseFilterConfigOverride(Message rawProtoMessage) {
    RBACPerRoute rbacPerRoute;
    if (!(rawProtoMessage instanceof Any)) {
      return ConfigOrError.fromError("Invalid config type: " + rawProtoMessage.getClass());
    }
    Any anyMessage = (Any) rawProtoMessage;
    try {
      rbacPerRoute = anyMessage.unpack(RBACPerRoute.class);
    } catch (InvalidProtocolBufferException e) {
      return ConfigOrError.fromError("Invalid proto: " + e);
    }
    if (rbacPerRoute.hasRbac()) {
      return parseRbacConfig(rbacPerRoute.getRbac());
    } else {
      return ConfigOrError.fromConfig(RbacConfig.create(null));
    }
  }

  @Nullable
  @Override
  public ServerInterceptor buildServerInterceptor(FilterConfig config,
                                                  @Nullable FilterConfig overrideConfig) {
    checkNotNull(config, "config");
    if (overrideConfig != null) {
      config = overrideConfig;
    }
    AuthConfig authConfig = ((RbacConfig) config).authConfig();
    return authConfig == null ? null : generateAuthorizationInterceptor(authConfig);
  }

  private ServerInterceptor generateAuthorizationInterceptor(AuthConfig config) {
    checkNotNull(config, "config");
    final GrpcAuthorizationEngine authEngine = new GrpcAuthorizationEngine(config);
    return new ServerInterceptor() {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                final ServerCall<ReqT, RespT> call,
                final Metadata headers, ServerCallHandler<ReqT, RespT> next) {
          AuthDecision authResult = authEngine.evaluate(headers, call);
          if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE,
                "Authorization result for serverCall {0}: {1}, matching policy: {2}.",
                new Object[]{call, authResult.decision(), authResult.matchingPolicyName()});
          }
          if (GrpcAuthorizationEngine.Action.DENY.equals(authResult.decision())) {
            Status status = Status.PERMISSION_DENIED.withDescription("Access Denied");
            call.close(status, new Metadata());
            return new ServerCall.Listener<ReqT>(){};
          }
          return next.startCall(call, headers);
        }
    };
  }
}

