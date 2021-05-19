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

import io.envoyproxy.envoy.config.rbac.v3.Permission;
import io.envoyproxy.envoy.config.rbac.v3.Policy;
import io.envoyproxy.envoy.config.rbac.v3.Principal;
import io.envoyproxy.envoy.config.rbac.v3.RBAC;
import io.envoyproxy.envoy.config.rbac.v3.RBAC.Action;
import io.grpc.xds.AuthorizationEngine.AuthDecision.DecisionType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of gRPC server access control based on envoy RBAC protocol:
 * https://www.envoyproxy.io/docs/envoy/latest/api-v3/config/rbac/v3/rbac.proto
 *
 * <p>One GrpcAuthorizationEngine is initialized with one action type and a list of policies.
 * Policies are examined sequentially in order in an any match fashion, and the first matched policy
 * will be returned. If not matched at all, the opposite action type is returned as a result.
 * The the following example demonstrates the matching criteria corresponding to the engine:
 * action: ALLOW
 * policies:
 *   "service-admin":
 *     permissions:
 *       - any: true
 *     principals:
 *       - authenticated:
 *           principal_name:
 *             exact: "cluster.local/ns/default/sa/admin"
 *       - authenticated:
 *           principal_name:
 *             exact: "cluster.local/ns/default/sa/superuser"
 *   "product-viewer":
 *     permissions:
 *         - and_rules:
 *             rules:
 *               - header: { name: ":method", exact_match: "GET" }
 *               - url_path:
 *                   path: { prefix: "/products" }
 *               - or_rules:
 *                   rules:
 *                     - destination_port: 80
 *                     - destination_port: 443
 *     principals:
 *       - any: true
 *
 * <p>The matching criteria should be immutable once constructed, and
 * {@link #evaluate(EvaluateArgs)} is supposed to be called safely for multiple times.
 */
public final class GrpcAuthorizationEngine implements AuthorizationEngine {
  private final Map<String, PolicyMatcher> policyMatchers = new HashMap<>();
  private final Action action;

  /** Instantiated with envoy RBAC policy configuration. */
  public GrpcAuthorizationEngine(RBAC rbacPolicy) {
    this.action = rbacPolicy.getAction();
    for (Map.Entry<String, Policy> entry: rbacPolicy.getPoliciesMap().entrySet()) {
      policyMatchers.put(entry.getKey(), new PolicyMatcher(entry.getValue()));
    }
  }

  @Override
  public AuthDecision evaluate(EvaluateArgs args) {
    String firstMatch = findFirstMatch(args);
    DecisionType decisionType = DecisionType.DENY;
    if (Action.DENY.equals(action) == (firstMatch == null)) {
      decisionType = DecisionType.ALLOW;
    }
    return AuthDecision.create(decisionType, firstMatch);
  }

  private String findFirstMatch(EvaluateArgs args) {
    for (Map.Entry<String, PolicyMatcher> entry: policyMatchers.entrySet()) {
      if (entry.getValue().matches(args)) {
        return entry.getKey();
      }
    }
    return null;
  }

  /**
   * Implements a top level {@link Matcher} for a single RBAC policy configuration per envoy
   * protocol:
   * https://www.envoyproxy.io/docs/envoy/latest/api-v3/config/rbac/v3/rbac.proto#config-rbac-v3-policy.
   *
   * <p>Currently we only support matching some of the request fields. Those unsupported fields are
   * considered not match until we stop ignoring them.
   */
  private static class PolicyMatcher extends Matcher {
    private final Matcher permissions;
    private final Matcher principals;

    private PolicyMatcher(Policy policy) {
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
          return MatcherParser.parseHeaderMatcher(permission.getHeader());
        case URL_PATH:
          return MatcherParser.parsePathMatcher(permission.getUrlPath());
        case DESTINATION_IP:
          return MatcherParser.createDestinationIpMatcher(permission.getDestinationIp());
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
          return MatcherParser.parseAuthenticated(principal.getAuthenticated());
        case SOURCE_IP:
          return MatcherParser.createSourceIpMatcher(principal.getSourceIp());
        case DIRECT_REMOTE_IP:
          return MatcherParser.createSourceIpMatcher(principal.getDirectRemoteIp());
        case HEADER:
          return MatcherParser.parseHeaderMatcher(principal.getHeader());
        case NOT_ID:
          return new InvertMatcher(createPrincipal(principal.getNotId()));
        case URL_PATH:
          return MatcherParser.parsePathMatcher(principal.getUrlPath());
        case METADATA:
        case REMOTE_IP:
          // Ignore. Not supported.
          return new InvertMatcher(AlwaysTrueMatcher.INSTANCE);
        default:
          throw new IllegalArgumentException("Unknown principal identifier case.");
      }
    }

    @Override
    boolean matches(EvaluateArgs args) {
      return permissions.matches(args) && principals.matches(args);
    }
  }
}
