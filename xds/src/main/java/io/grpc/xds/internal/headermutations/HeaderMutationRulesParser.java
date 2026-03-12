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

package io.grpc.xds.internal.headermutations;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import io.envoyproxy.envoy.config.common.mutation_rules.v3.HeaderMutationRules;
import io.grpc.xds.internal.extauthz.ExtAuthzParseException;

/**
 * Parser for {@link io.envoyproxy.envoy.config.common.mutation_rules.v3.HeaderMutationRules}.
 */
public final class HeaderMutationRulesParser {

  private HeaderMutationRulesParser() {}

  public static HeaderMutationRulesConfig parse(HeaderMutationRules proto)
      throws ExtAuthzParseException {
    HeaderMutationRulesConfig.Builder builder = HeaderMutationRulesConfig.builder();
    builder.disallowAll(proto.getDisallowAll().getValue());
    builder.disallowIsError(proto.getDisallowIsError().getValue());
    if (proto.hasAllowExpression()) {
      builder.allowExpression(
          parseRegex(proto.getAllowExpression().getRegex(), "allow_expression"));
    }
    if (proto.hasDisallowExpression()) {
      builder.disallowExpression(
          parseRegex(proto.getDisallowExpression().getRegex(), "disallow_expression"));
    }
    return builder.build();
  }

  private static Pattern parseRegex(String regex, String fieldName) throws ExtAuthzParseException {
    try {
      return Pattern.compile(regex);
    } catch (PatternSyntaxException e) {
      throw new ExtAuthzParseException(
          "Invalid regex pattern for " + fieldName + ": " + e.getMessage(), e);
    }
  }
}
