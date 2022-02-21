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

package io.grpc.xds.internal.rbac.engine;

import io.envoyproxy.envoy.config.core.v3.CidrRange;
import io.envoyproxy.envoy.config.rbac.v3.Permission;
import io.envoyproxy.envoy.config.rbac.v3.Policy;
import io.envoyproxy.envoy.config.rbac.v3.Principal;
import io.envoyproxy.envoy.config.rbac.v3.RBAC;
import io.envoyproxy.envoy.type.v3.Int32Range;
import io.grpc.xds.internal.MatcherParser;
import io.grpc.xds.internal.Matchers;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AlwaysTrueMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AndMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AuthConfig;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AuthHeaderMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AuthenticatedMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.DestinationIpMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.DestinationPortMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.DestinationPortRangeMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.InvertMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.Matcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.OrMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.PathMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.PolicyMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.RequestedServerNameMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.SourceIpMatcher;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** RBAC parser implementation. */
public final class RbacParser {
  static final RbacParser INSTANCE = new RbacParser();

  RbacParser() {}

  /** Parses Envoy RBAC proto. */
  public static AuthConfig parseRbac(RBAC rbac) {
    GrpcAuthorizationEngine.Action authAction;
    switch (rbac.getAction()) {
      case ALLOW:
        authAction = GrpcAuthorizationEngine.Action.ALLOW;
        break;
      case DENY:
        authAction = GrpcAuthorizationEngine.Action.DENY;
        break;
      default:
        throw new IllegalArgumentException("Unknown rbac action type: " + rbac.getAction());
    }
    Map<String, Policy> policyMap = rbac.getPoliciesMap();
    List<GrpcAuthorizationEngine.PolicyMatcher> policyMatchers = new ArrayList<>();
    for (Map.Entry<String, Policy> entry: policyMap.entrySet()) {
      try {
        Policy policy = entry.getValue();
        if (policy.hasCondition() || policy.hasCheckedCondition()) {
          throw new IllegalArgumentException(
            "Policy.condition and Policy.checked_condition must not set: " + entry.getKey());
        }
        policyMatchers.add(PolicyMatcher.create(entry.getKey(),
                parsePermissionList(policy.getPermissionsList()),
                parsePrincipalList(policy.getPrincipalsList())));
      } catch (Exception e) {
        throw new IllegalArgumentException("Encountered error parsing policy: " + e);
      }
    }
    return AuthConfig.create(policyMatchers, authAction);
  }

  private static OrMatcher parsePermissionList(List<Permission> permissions) {
    List<Matcher> anyMatch = new ArrayList<>();
    for (Permission permission : permissions) {
      anyMatch.add(parsePermission(permission));
    }
    return OrMatcher.create(anyMatch);
  }

  private static Matcher parsePermission(Permission permission) {
    switch (permission.getRuleCase()) {
      case AND_RULES:
        List<Matcher> andMatch = new ArrayList<>();
        for (Permission p : permission.getAndRules().getRulesList()) {
          andMatch.add(parsePermission(p));
        }
        return AndMatcher.create(andMatch);
      case OR_RULES:
        return parsePermissionList(permission.getOrRules().getRulesList());
      case ANY:
        return AlwaysTrueMatcher.INSTANCE;
      case HEADER:
        return parseHeaderMatcher(permission.getHeader());
      case URL_PATH:
        return parsePathMatcher(permission.getUrlPath());
      case DESTINATION_IP:
        return createDestinationIpMatcher(permission.getDestinationIp());
      case DESTINATION_PORT:
        return createDestinationPortMatcher(permission.getDestinationPort());
      case DESTINATION_PORT_RANGE:
        return parseDestinationPortRangeMatcher(permission.getDestinationPortRange());
      case NOT_RULE:
        return InvertMatcher.create(parsePermission(permission.getNotRule()));
      case METADATA: // hard coded, never match.
        return InvertMatcher.create(AlwaysTrueMatcher.INSTANCE);
      case REQUESTED_SERVER_NAME:
        return parseRequestedServerNameMatcher(permission.getRequestedServerName());
      case RULE_NOT_SET:
      default:
        throw new IllegalArgumentException(
                "Unknown permission rule case: " + permission.getRuleCase());
    }
  }

  private static OrMatcher parsePrincipalList(List<Principal> principals) {
    List<Matcher> anyMatch = new ArrayList<>();
    for (Principal principal: principals) {
      anyMatch.add(parsePrincipal(principal));
    }
    return OrMatcher.create(anyMatch);
  }

  private static Matcher parsePrincipal(Principal principal) {
    switch (principal.getIdentifierCase()) {
      case OR_IDS:
        return parsePrincipalList(principal.getOrIds().getIdsList());
      case AND_IDS:
        List<Matcher> nextMatchers = new ArrayList<>();
        for (Principal next : principal.getAndIds().getIdsList()) {
          nextMatchers.add(parsePrincipal(next));
        }
        return AndMatcher.create(nextMatchers);
      case ANY:
        return AlwaysTrueMatcher.INSTANCE;
      case AUTHENTICATED:
        return parseAuthenticatedMatcher(principal.getAuthenticated());
      case DIRECT_REMOTE_IP:
        return createSourceIpMatcher(principal.getDirectRemoteIp());
      case REMOTE_IP:
        return createSourceIpMatcher(principal.getRemoteIp());
      case SOURCE_IP:
        return createSourceIpMatcher(principal.getSourceIp());
      case HEADER:
        return parseHeaderMatcher(principal.getHeader());
      case NOT_ID:
        return InvertMatcher.create(parsePrincipal(principal.getNotId()));
      case URL_PATH:
        return parsePathMatcher(principal.getUrlPath());
      case METADATA: // hard coded, never match.
        return InvertMatcher.create(AlwaysTrueMatcher.INSTANCE);
      case IDENTIFIER_NOT_SET:
      default:
        throw new IllegalArgumentException(
                "Unknown principal identifier case: " + principal.getIdentifierCase());
    }
  }

  private static PathMatcher parsePathMatcher(
          io.envoyproxy.envoy.type.matcher.v3.PathMatcher proto) {
    switch (proto.getRuleCase()) {
      case PATH:
        return PathMatcher.create(MatcherParser.parseStringMatcher(proto.getPath()));
      case RULE_NOT_SET:
      default:
        throw new IllegalArgumentException(
                "Unknown path matcher rule type: " + proto.getRuleCase());
    }
  }

  private static RequestedServerNameMatcher parseRequestedServerNameMatcher(
          io.envoyproxy.envoy.type.matcher.v3.StringMatcher proto) {
    return RequestedServerNameMatcher.create(MatcherParser.parseStringMatcher(proto));
  }

  private static AuthHeaderMatcher parseHeaderMatcher(
          io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto) {
    if (proto.getName().startsWith("grpc-")) {
      throw new IllegalArgumentException("Invalid header matcher config: [grpc-] prefixed "
          + "header name is not allowed.");
    }
    if (":scheme".equals(proto.getName())) {
      throw new IllegalArgumentException("Invalid header matcher config: header name [:scheme] "
          + "is not allowed.");
    }
    return AuthHeaderMatcher.create(MatcherParser.parseHeaderMatcher(proto));
  }

  private static AuthenticatedMatcher parseAuthenticatedMatcher(
          Principal.Authenticated proto) {
    Matchers.StringMatcher matcher = MatcherParser.parseStringMatcher(proto.getPrincipalName());
    return AuthenticatedMatcher.create(matcher);
  }

  private static DestinationPortMatcher createDestinationPortMatcher(int port) {
    return DestinationPortMatcher.create(port);
  }

  private static DestinationPortRangeMatcher parseDestinationPortRangeMatcher(Int32Range range) {
    return DestinationPortRangeMatcher.create(range.getStart(), range.getEnd());
  }

  private static DestinationIpMatcher createDestinationIpMatcher(CidrRange cidrRange) {
    return DestinationIpMatcher.create(Matchers.CidrMatcher.create(
            resolve(cidrRange), cidrRange.getPrefixLen().getValue()));
  }

  private static SourceIpMatcher createSourceIpMatcher(CidrRange cidrRange) {
    return SourceIpMatcher.create(Matchers.CidrMatcher.create(
            resolve(cidrRange), cidrRange.getPrefixLen().getValue()));
  }

  private static InetAddress resolve(CidrRange cidrRange) {
    try {
      return InetAddress.getByName(cidrRange.getAddressPrefix());
    } catch (UnknownHostException ex) {
      throw new IllegalArgumentException("IP address can not be found: " + ex);
    }
  }
}
