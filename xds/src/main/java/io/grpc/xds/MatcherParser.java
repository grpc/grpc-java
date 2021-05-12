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

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import io.envoyproxy.envoy.config.core.v3.CidrRange;
import io.envoyproxy.envoy.config.rbac.v3.Principal.Authenticated;
import io.grpc.xds.Matcher.AuthenticatedMatcher;
import io.grpc.xds.Matcher.DestinationIpMatcher;
import io.grpc.xds.Matcher.HeaderMatcher;
import io.grpc.xds.Matcher.IpMatcher;
import io.grpc.xds.Matcher.PathMatcher;
import io.grpc.xds.Matcher.SourceIpMatcher;
import io.grpc.xds.Matcher.StringMatcher;

/** A group of parser utility functions that translate envoy matcher proto into xds internal
 * {@link Matcher} data structure. */
public class MatcherParser {
  /** Translates envoy proto HeaderMatcher to {@link HeaderMatcher}.*/
  public static Matcher parseHeaderMatcher(
      io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto) {
    switch (proto.getHeaderMatchSpecifierCase()) {
      case EXACT_MATCH:
        return HeaderMatcher.forExactValue(
            proto.getName(), proto.getExactMatch(), proto.getInvertMatch());
      case SAFE_REGEX_MATCH:
        String rawPattern = proto.getSafeRegexMatch().getRegex();
        Pattern safeRegExMatch;
        try {
          safeRegExMatch = Pattern.compile(rawPattern);
        } catch (PatternSyntaxException e) {
          throw new IllegalArgumentException(
              "HeaderMatcher [" + proto.getName() + "] contains malformed safe regex pattern: "
                  + e.getMessage());
        }
        return HeaderMatcher.forSafeRegEx(
            proto.getName(), safeRegExMatch, proto.getInvertMatch());
      case RANGE_MATCH:
        HeaderMatcher.Range rangeMatch = HeaderMatcher.Range.create(
            proto.getRangeMatch().getStart(), proto.getRangeMatch().getEnd());
        return HeaderMatcher.forRange(
            proto.getName(), rangeMatch, proto.getInvertMatch());
      case PRESENT_MATCH:
        return HeaderMatcher.forPresent(
            proto.getName(), proto.getPresentMatch(), proto.getInvertMatch());
      case PREFIX_MATCH:
        return HeaderMatcher.forPrefix(
            proto.getName(), proto.getPrefixMatch(), proto.getInvertMatch());
      case SUFFIX_MATCH:
        return HeaderMatcher.forSuffix(
            proto.getName(), proto.getSuffixMatch(), proto.getInvertMatch());
      case HEADERMATCHSPECIFIER_NOT_SET:
      default:
        throw new IllegalArgumentException("Unknown header matcher type.");
    }
  }

  /** Translates envoy proto PathMatcher to {@link PathMatcher}. */
  public static Matcher parsePathMatcher(io.envoyproxy.envoy.type.matcher.v3.PathMatcher proto) {
    switch (proto.getRuleCase()) {
      case PATH:
        return new PathMatcher((StringMatcher) parseStringMatcher(proto.getPath()));
      case RULE_NOT_SET:
      default:
        throw new IllegalArgumentException("Unknown path matcher rule type.");
    }
  }

  /** Translates envoy proto Authenticated to {@link AuthenticatedMatcher}.*/
  public static Matcher parseAuthenticated(Authenticated proto) {
    Matcher matcher = parseStringMatcher(proto.getPrincipalName());
    return new AuthenticatedMatcher((StringMatcher)matcher);
  }

  /** Constructs {@link DestinationIpMatcher} matcher from envoy proto. */
  public static Matcher createDestinationIpMatcher(CidrRange cidrRange) {
    return new DestinationIpMatcher(
        IpMatcher.create(cidrRange.getAddressPrefix(), cidrRange.getPrefixLen().getValue()));
  }

  /** Constructs {@link SourceIpMatcher} from envoy proto. */
  public static Matcher createSourceIpMatcher(CidrRange cidrRange) {
    return new SourceIpMatcher(
        IpMatcher.create(cidrRange.getAddressPrefix(), cidrRange.getPrefixLen().getValue()));
  }

  /** Translates {@link StringMatcher} from envoy proto. */
  static Matcher parseStringMatcher(io.envoyproxy.envoy.type.matcher.v3.StringMatcher
      proto) {
    switch (proto.getMatchPatternCase()) {
      case EXACT:
        return StringMatcher.forExact(proto.getExact(), proto.getIgnoreCase());
      case PREFIX:
        return StringMatcher.forPrefix(proto.getPrefix(), proto.getIgnoreCase());
      case SUFFIX:
        return StringMatcher.forSuffix(proto.getSuffix(), proto.getIgnoreCase());
      case SAFE_REGEX:
        return StringMatcher.forSafeRegEx(Pattern.compile(proto.getSafeRegex().getRegex()),
            proto.getIgnoreCase());
      case CONTAINS:
        return StringMatcher.forContains(proto.getContains(), proto.getIgnoreCase());
      case MATCHPATTERN_NOT_SET:
      default:
        throw new IllegalArgumentException("Unknown StringMatcher match pattern.");
    }
  }
}
