/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds.internal.extauthz;

import com.google.common.collect.ImmutableList;
import io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz;
import io.grpc.internal.GrpcUtil;
import io.grpc.xds.internal.MatcherParser;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfig;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfigParser;
import io.grpc.xds.internal.grpcservice.GrpcServiceParseException;
import io.grpc.xds.internal.grpcservice.GrpcServiceXdsContextProvider;
import io.grpc.xds.internal.headermutations.HeaderMutationRulesParser;


/**
 * Parser for {@link io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz}.
 */
public final class ExtAuthzConfigParser {

  private ExtAuthzConfigParser() {}

  /**
   * Parses the {@link io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz} proto to
   * create an {@link ExtAuthzConfig} instance.
   *
   * @param extAuthzProto The ext_authz proto to parse.
   * @return An {@link ExtAuthzConfig} instance.
   * @throws ExtAuthzParseException if the proto is invalid or contains unsupported features.
   */
  public static ExtAuthzConfig parse(
      ExtAuthz extAuthzProto, GrpcServiceXdsContextProvider contextProvider)
      throws ExtAuthzParseException {
    if (!extAuthzProto.hasGrpcService()) {
      throw new ExtAuthzParseException(
          "unsupported ExtAuthz service type: only grpc_service is supported");
    }
    GrpcServiceConfig grpcServiceConfig;
    try {
      grpcServiceConfig =
          GrpcServiceConfigParser.parse(extAuthzProto.getGrpcService(), contextProvider);
    } catch (GrpcServiceParseException e) {
      throw new ExtAuthzParseException("Failed to parse GrpcService config: " + e.getMessage(), e);
    }
    ExtAuthzConfig.Builder builder = ExtAuthzConfig.newBuilder().grpcService(grpcServiceConfig)
        .failureModeAllow(extAuthzProto.getFailureModeAllow())
        .failureModeAllowHeaderAdd(extAuthzProto.getFailureModeAllowHeaderAdd())
        .includePeerCertificate(extAuthzProto.getIncludePeerCertificate())
        .denyAtDisable(extAuthzProto.getDenyAtDisable().getDefaultValue().getValue());

    if (extAuthzProto.hasFilterEnabled()) {
      try {
        builder.filterEnabled(
            MatcherParser.parseFractionMatcher(extAuthzProto.getFilterEnabled().getDefaultValue()));
      } catch (IllegalArgumentException e) {
        throw new ExtAuthzParseException(e.getMessage());
      }
    }

    if (extAuthzProto.hasStatusOnError()) {
      builder.statusOnError(
          GrpcUtil.httpStatusToGrpcStatus(extAuthzProto.getStatusOnError().getCodeValue()));
    }

    if (extAuthzProto.hasAllowedHeaders()) {
      builder.allowedHeaders(extAuthzProto.getAllowedHeaders().getPatternsList().stream()
          .map(MatcherParser::parseStringMatcher).collect(ImmutableList.toImmutableList()));
    }

    if (extAuthzProto.hasDisallowedHeaders()) {
      builder.disallowedHeaders(extAuthzProto.getDisallowedHeaders().getPatternsList().stream()
          .map(MatcherParser::parseStringMatcher).collect(ImmutableList.toImmutableList()));
    }

    if (extAuthzProto.hasDecoderHeaderMutationRules()) {
      builder.decoderHeaderMutationRules(
          HeaderMutationRulesParser.parse(extAuthzProto.getDecoderHeaderMutationRules()));
    }

    return builder.build();
  }
}
