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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;

import com.google.protobuf.UInt32Value;
import io.envoyproxy.envoy.config.core.v3.CidrRange;
import io.envoyproxy.envoy.config.rbac.v3.Permission;
import io.envoyproxy.envoy.config.rbac.v3.Permission.Set;
import io.envoyproxy.envoy.config.rbac.v3.Policy;
import io.envoyproxy.envoy.config.rbac.v3.Principal;
import io.envoyproxy.envoy.config.rbac.v3.Principal.Authenticated;
import io.envoyproxy.envoy.config.rbac.v3.RBAC;
import io.envoyproxy.envoy.config.rbac.v3.RBAC.Action;
import io.envoyproxy.envoy.config.route.v3.HeaderMatcher;
import io.envoyproxy.envoy.type.matcher.v3.MetadataMatcher;
import io.envoyproxy.envoy.type.matcher.v3.PathMatcher;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import io.grpc.xds.EvaluateArgs;
import io.grpc.xds.internal.rbac.engine.AuthorizationEngineInterface.AuthDecision;
import io.grpc.xds.internal.rbac.engine.AuthorizationEngineInterface.AuthDecision.DecisionType;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class GrpcAuthorizationEngineTest {
  private static final String POLICY_NAME = "policy-name";
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  EvaluateArgs args;

  private static final String HEADER_KEY = "header-key";
  private static final String HEADER_VALUE = "header-val";
  private static final HeaderMatcher HEADER_MATCHER = HeaderMatcher.newBuilder()
      .setName(HEADER_KEY).setExactMatch(HEADER_VALUE).build();
  private static final String IP_ADDR1 = "10.10.10.0";
  private static final String IP_ADDR2 = "68.36.0.19";
  private static final CidrRange CIDR_RANGE = CidrRange.newBuilder().setAddressPrefix(IP_ADDR1)
      .setPrefixLen(UInt32Value.of(24)).build();
  private static final CidrRange CIDR_RANGE2 = CidrRange.newBuilder().setAddressPrefix(IP_ADDR2)
      .setPrefixLen(UInt32Value.of(24)).build();
  private static final StringMatcher STRING_MATCHER =
      StringMatcher.newBuilder().setPrefix("auth").setIgnoreCase(true).build();
  private static final Authenticated AUTHENTICATED = Authenticated.newBuilder()
      .setPrincipalName(STRING_MATCHER).build();

  @Test
  public void testHeaderMatch() {
    Permission permission = Permission.newBuilder().setHeader(HEADER_MATCHER).build();
    Principal principal = Principal.newBuilder().setAny(true).build();
    GrpcAuthorizationEngine engine = new GrpcAuthorizationEngine(
        createRbac(Action.ALLOW, POLICY_NAME, permission, principal));

    when(args.getHeaderValue(HEADER_KEY)).thenReturn( HEADER_VALUE);
    assertThat(engine.evaluate(args)).isEqualTo(
        AuthDecision.create(DecisionType.ALLOW, POLICY_NAME));

    when(args.getHeaderValue(HEADER_KEY)).thenReturn(HEADER_VALUE + "-1");
    assertThat(engine.evaluate(args)).isEqualTo(
        AuthDecision.create(DecisionType.DENY, null));
  }

  @Test
  public void testPathMatch() {
    PathMatcher matcher = PathMatcher.newBuilder().setPath(STRING_MATCHER).build();
    Permission permission = Permission.newBuilder().setUrlPath(matcher).build();
    Principal principal = Principal.newBuilder().setUrlPath(matcher).build();
    GrpcAuthorizationEngine engine = new GrpcAuthorizationEngine(
        createRbac(Action.DENY, POLICY_NAME, permission, principal));
    assertThat(engine.evaluate(args)).isEqualTo(
        AuthDecision.create(DecisionType.ALLOW, null));
    when(args.getPath()).thenReturn("authorized");
    assertThat(engine.evaluate(args)).isEqualTo(
        AuthDecision.create(DecisionType.DENY, POLICY_NAME));
  }

  @Test
  @SuppressWarnings("deprecation")
  public void testIpPortMatch() {
    Permission permission = Permission.newBuilder().setDestinationIp(CIDR_RANGE).build();
    Principal principal = Principal.newBuilder().setDirectRemoteIp(CIDR_RANGE2).build();
    GrpcAuthorizationEngine engine = new GrpcAuthorizationEngine(
        createRbac(Action.ALLOW, POLICY_NAME, permission, principal));

    when(args.getLocalAddress()).thenReturn(IP_ADDR1);
    when(args.getPeerAddress()).thenReturn(IP_ADDR2);
    assertThat(engine.evaluate(args)).isEqualTo(
        AuthDecision.create(DecisionType.ALLOW, POLICY_NAME));

    when(args.getLocalAddress()).thenReturn(IP_ADDR2);
    assertThat(engine.evaluate(args)).isEqualTo(
        AuthDecision.create(DecisionType.DENY, null));

    permission = Permission.newBuilder().setOrRules(Set.newBuilder()
        .addRules(Permission.newBuilder().setDestinationPort(8000).build())
        .addRules(Permission.newBuilder().setDestinationPort(8001).build())
        .build()).build();
    principal = Principal.newBuilder().setSourceIp(CIDR_RANGE2).build();
    engine = new GrpcAuthorizationEngine(
        createRbac(Action.ALLOW, POLICY_NAME, permission, principal));
    assertThat(engine.evaluate(args)).isEqualTo(
        AuthDecision.create(DecisionType.DENY, null));
    when(args.getLocalAddress()).thenReturn(IP_ADDR1);
    when(args.getPeerAddress()).thenReturn(IP_ADDR2);
    when(args.getLocalPort()).thenReturn(8001);
    assertThat(engine.evaluate(args)).isEqualTo(
        AuthDecision.create(DecisionType.ALLOW, POLICY_NAME));
  }

  @Test
  public void testNestedRules() {
    Permission nextPermission = Permission.newBuilder().setHeader(HEADER_MATCHER).build();
    Permission permission = Permission.newBuilder().setAndRules(
        Set.newBuilder().addRules(nextPermission).build()
    ).build();
    Principal nextPrincipal = Principal.newBuilder().setHeader(HEADER_MATCHER).build();
    Principal principal = Principal.newBuilder().setOrIds(
        Principal.Set.newBuilder().addIds(nextPrincipal).build()
    ).build();
    GrpcAuthorizationEngine engine = new GrpcAuthorizationEngine(
        createRbac(Action.DENY, POLICY_NAME, permission, principal));

    assertThat(engine.evaluate(args)).isEqualTo(
        AuthDecision.create(DecisionType.ALLOW, null));
    when(args.getHeaderValue(HEADER_KEY)).thenReturn( HEADER_VALUE);
    assertThat(engine.evaluate(args)).isEqualTo(
        AuthDecision.create(DecisionType.DENY, POLICY_NAME));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testInvalidPrincipalType() {
    Permission permission = Permission.newBuilder().setAny(true).build();
    Principal principal = Principal.newBuilder().build();
    GrpcAuthorizationEngine engine = new GrpcAuthorizationEngine(
        createRbac(Action.DENY, POLICY_NAME, permission, principal));
    engine.evaluate(args);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testInvalidPermissionType() {
    Permission permission = Permission.newBuilder().build();
    Principal principal = Principal.newBuilder().setAny(false).build();
    GrpcAuthorizationEngine engine = new GrpcAuthorizationEngine(
        createRbac(Action.DENY, POLICY_NAME, permission, principal));
    engine.evaluate(args);
  }

  @Test
  public void testUnsupportedFields() {
    Permission permission = Permission.newBuilder().setMetadata(
        MetadataMatcher.newBuilder().build()).build();
    Principal principal = Principal.newBuilder().setAny(true).build();
    GrpcAuthorizationEngine engine = new GrpcAuthorizationEngine(
        createRbac(Action.DENY, POLICY_NAME, permission, principal));
    assertThat(engine.evaluate(args)).isEqualTo(
        AuthDecision.create(DecisionType.ALLOW, null));

    permission = Permission.newBuilder().setAny(true).build();
    principal = Principal.newBuilder().setMetadata(
        MetadataMatcher.newBuilder().build()).build();
    engine = new GrpcAuthorizationEngine(
        createRbac(Action.ALLOW, POLICY_NAME, permission, principal));
    assertThat(engine.evaluate(args)).isEqualTo(
        AuthDecision.create(DecisionType.DENY, null));

    permission = Permission.newBuilder().setRequestedServerName(STRING_MATCHER).build();
    principal = Principal.newBuilder().setAny(true).build();
    engine = new GrpcAuthorizationEngine(
        createRbac(Action.DENY, POLICY_NAME, permission, principal));
    assertThat(engine.evaluate(args)).isEqualTo(
        AuthDecision.create(DecisionType.ALLOW, null));

    permission = Permission.newBuilder().setAny(true).build();
    principal = Principal.newBuilder().setRemoteIp(CIDR_RANGE).build();
    engine = new GrpcAuthorizationEngine(
        createRbac(Action.DENY, POLICY_NAME, permission, principal));
    assertThat(engine.evaluate(args)).isEqualTo(
        AuthDecision.create(DecisionType.ALLOW, null));
  }

  @Test
  public void testAuthenticatedMatch() {
    Principal principal = Principal.newBuilder().setAuthenticated(AUTHENTICATED).build();
    Permission permission = Permission.newBuilder().setAny(true).build();
    GrpcAuthorizationEngine engine = new GrpcAuthorizationEngine(
        createRbac(Action.ALLOW, POLICY_NAME, permission, principal));

    when(args.getPrincipalName()).thenReturn("authorized");
    assertThat(engine.evaluate(args)).isEqualTo(
        AuthDecision.create(DecisionType.ALLOW, POLICY_NAME));
    when(args.getPrincipalName()).thenReturn("denied");
    assertThat(engine.evaluate(args)).isEqualTo(
        AuthDecision.create(DecisionType.DENY, null));
  }

  @Test
  public void testMultiplePolicies() {
    Principal principal1 = Principal.newBuilder().setAuthenticated(AUTHENTICATED).build();
    Permission permission1 = Permission.newBuilder()
        .setMetadata(MetadataMatcher.newBuilder().build()).build();

    Principal principal21 = Principal.newBuilder().setAndIds(
        Principal.Set.newBuilder()
            .addIds(Principal.newBuilder().setNotId(
                Principal.newBuilder().setMetadata(MetadataMatcher.newBuilder().build()).build()
            ).build()).build()
    ).build();
    Principal principal22 = Principal.newBuilder().setOrIds(Principal.Set.newBuilder()
        .addIds(Principal.newBuilder().setHeader(HEADER_MATCHER).build())
        .addIds(Principal.newBuilder().setRemoteIp(CIDR_RANGE2).build())
        .build()).build();
    Permission permission21 = Permission.newBuilder().setOrRules(
        Set.newBuilder()
            .addRules(Permission.newBuilder().setNotRule(
                Permission.newBuilder().setDestinationPort(80).build()).build())
            .build()
    ).build();
    Permission permission22 = Permission.newBuilder().setAndRules(Set.newBuilder()
        .addRules(Permission.newBuilder().setDestinationIp(CIDR_RANGE).build())
        .addRules(Permission.newBuilder().setHeader(HEADER_MATCHER).build())
        .build()).build();

    GrpcAuthorizationEngine engine = new GrpcAuthorizationEngine(
        createRbac(Action.ALLOW,
            POLICY_NAME, createPolicy(permission1, principal1),
            POLICY_NAME + "-2",
            Policy.newBuilder().addPermissions(permission21).addPermissions(permission22)
                .addPrincipals(principal21).addPrincipals(principal22).build()));
    when(args.getHeaderValue(HEADER_KEY)).thenReturn( HEADER_VALUE);
    when(args.getLocalAddress()).thenReturn(IP_ADDR1);
    when(args.getLocalPort()).thenReturn(80);
    assertThat(engine.evaluate(args)).isEqualTo(
        AuthDecision.create(DecisionType.ALLOW, POLICY_NAME + "-2"));
  }

  private Policy createPolicy(Permission permission, Principal principal) {
    return Policy.newBuilder().addPermissions(permission).addPrincipals(principal).build();
  }

  private RBAC createRbac(Action action, String name, Permission permission, Principal principal) {
    return RBAC.newBuilder().setAction(action)
        .putPolicies(name,
            Policy.newBuilder().addPermissions(permission).addPrincipals(principal).build())
        .build();
  }

  private RBAC createRbac(Action action, String name, Policy policy, String name2, Policy policy2) {
    return RBAC.newBuilder().setAction(action).putPolicies(name, policy).putPolicies(name2, policy2)
        .build();
  }
}
