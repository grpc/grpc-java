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

package io.grpc.xds.internal.rbac.engine;

import static io.grpc.xds.MatcherParser.createDestinationIpMatcher;
import static io.grpc.xds.MatcherParser.createSourceIpMatcher;
import static io.grpc.xds.MatcherParser.parseAuthenticated;
import static io.grpc.xds.MatcherParser.parseHeaderMatcher;
import static io.grpc.xds.MatcherParser.parsePathMatcher;

import io.envoyproxy.envoy.config.rbac.v3.Permission;
import io.envoyproxy.envoy.config.rbac.v3.Policy;
import io.envoyproxy.envoy.config.rbac.v3.Principal;
import io.grpc.xds.EvaluateArgs;
import io.grpc.xds.Matcher;
import java.util.ArrayList;
import java.util.List;

/** Implements a top level {@link Matcher} for a single RBAC policy configuration per envoy
 * protocol:
 * https://www.envoyproxy.io/docs/envoy/latest/api-v3/config/rbac/v3/rbac.proto#config-rbac-v3-policy.
 *
 * <p>Currently we only support matching some of the request fields. Those unsupported fields are
 * considered not match until we stop ignoring them.
 * */
public final class PolicyMatcher extends Matcher {
  private final Matcher permissions;
  private final Matcher principals;

  public PolicyMatcher(Policy policy) {
    this.permissions = createPermissions(policy.getPermissionsList());
    this.principals = createPrincipals(policy.getPrincipalsList());
  }

  // Top level permissions are being matched with OR semantics.
  private static Matcher createPermissions(List<Permission> permissions) {
    List<Matcher> anyMatch = new ArrayList<>();
    for (Permission permission : permissions) {
      anyMatch.add(createPermission(permission));
    }
    return new OrMatcher(anyMatch);
  }

  private static Matcher createPermission(Permission permission) {
    switch (permission.getRuleCase()) {
      case AND_RULES:
        List<Matcher> andMatch = new ArrayList<>();
        for (Permission p : permission.getAndRules().getRulesList()) {
          andMatch.add(createPermission(p));
        }
        return new AndMatcher(andMatch);
      case OR_RULES:
        return createPermissions(permission.getOrRules().getRulesList());
      case ANY:
        return AlwaysTrueMatcher.INSTANCE;
      case HEADER:
        return parseHeaderMatcher(permission.getHeader());
      case URL_PATH:
        return parsePathMatcher(permission.getUrlPath());
      case DESTINATION_IP:
        return createDestinationIpMatcher(permission.getDestinationIp());
      case DESTINATION_PORT:
        final int port = permission.getDestinationPort();
        return new Matcher() {
          @Override
          public boolean matches(EvaluateArgs args) {
            return args.getLocalPort() == port;
          }
        };
      case NOT_RULE:
        return new InvertMatcher(createPermission(permission.getNotRule()));
      case METADATA:
      case REQUESTED_SERVER_NAME:
        // Ignore. Not supported.
        return new InvertMatcher(AlwaysTrueMatcher.INSTANCE);
      default:
        throw new IllegalArgumentException("Unknown permission rule case.");
    }
  }

  // Top level principals are being matched with OR semantics.
  private static Matcher createPrincipals(List<Principal> principals) {
    List<Matcher> anyMatch = new ArrayList<>();
    for (Principal principal: principals) {
      anyMatch.add(createPrincipal(principal));
    }
    return new OrMatcher(anyMatch);
  }

  private static Matcher createPrincipal(Principal principal) {
    switch (principal.getIdentifierCase()) {
      case OR_IDS:
        return createPrincipals(principal.getOrIds().getIdsList());
      case AND_IDS:
        List<Matcher> nextMatchers = new ArrayList<>();
        for (Principal next : principal.getAndIds().getIdsList()) {
          nextMatchers.add(createPrincipal(next));
        }
        return new AndMatcher(nextMatchers);
      case ANY:
        return AlwaysTrueMatcher.INSTANCE;
      case AUTHENTICATED:
        return parseAuthenticated(principal.getAuthenticated());
      case SOURCE_IP:
        return createSourceIpMatcher(principal.getSourceIp());
      case DIRECT_REMOTE_IP:
        return createSourceIpMatcher(principal.getDirectRemoteIp());
      case HEADER:
        return parseHeaderMatcher(principal.getHeader());
      case NOT_ID:
        return new InvertMatcher(createPrincipal(principal.getNotId()));
      case URL_PATH:
        return parsePathMatcher(principal.getUrlPath());
      case METADATA:
      case REMOTE_IP:
        // Ignore. Not supported.
        return new InvertMatcher(AlwaysTrueMatcher.INSTANCE);
      default:
        throw new IllegalArgumentException("Unknown principal identifier case.");
    }
  }

  @Override
  public boolean matches(EvaluateArgs args) {
    return permissions.matches(args) && principals.matches(args);
  }
}


