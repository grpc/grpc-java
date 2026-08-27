/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.xds.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.HeaderForwardingRules;
import java.util.Locale;

/**
 * Configuration for header forwarding rules in external processing.
 */
public final class HeaderForwardingRulesConfig {
  private final ImmutableList<Matchers.StringMatcher> allowedHeaders;
  private final ImmutableList<Matchers.StringMatcher> disallowedHeaders;

  public HeaderForwardingRulesConfig(
      ImmutableList<Matchers.StringMatcher> allowedHeaders,
      ImmutableList<Matchers.StringMatcher> disallowedHeaders) {
    this.allowedHeaders = checkNotNull(allowedHeaders, "allowedHeaders");
    this.disallowedHeaders = checkNotNull(disallowedHeaders, "disallowedHeaders");
  }

  public static HeaderForwardingRulesConfig create(HeaderForwardingRules proto) {
    ImmutableList<Matchers.StringMatcher> allowedHeaders = ImmutableList.of();
    if (proto.hasAllowedHeaders()) {
      allowedHeaders = MatcherParser.parseListStringMatcher(proto.getAllowedHeaders());
    }
    ImmutableList<Matchers.StringMatcher> disallowedHeaders = ImmutableList.of();
    if (proto.hasDisallowedHeaders()) {
      disallowedHeaders = MatcherParser.parseListStringMatcher(proto.getDisallowedHeaders());
    }
    return new HeaderForwardingRulesConfig(allowedHeaders, disallowedHeaders);
  }

  public boolean isAllowed(String headerName) {
    String lowerHeaderName = headerName.toLowerCase(Locale.ROOT);
    if (!allowedHeaders.isEmpty()) {
      boolean matched = false;
      for (Matchers.StringMatcher matcher : allowedHeaders) {
        if (matcher.matches(lowerHeaderName)) {
          matched = true;
          break;
        }
      }
      if (!matched) {
        return false;
      }
    }
    if (!disallowedHeaders.isEmpty()) {
      for (Matchers.StringMatcher matcher : disallowedHeaders) {
        if (matcher.matches(lowerHeaderName)) {
          return false;
        }
      }
    }
    return true;
  }
}
