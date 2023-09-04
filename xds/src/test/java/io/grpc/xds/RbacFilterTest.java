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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.api.expr.v1alpha1.Expr;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import com.google.protobuf.UInt32Value;
import io.envoyproxy.envoy.config.core.v3.CidrRange;
import io.envoyproxy.envoy.config.rbac.v3.Permission;
import io.envoyproxy.envoy.config.rbac.v3.Policy;
import io.envoyproxy.envoy.config.rbac.v3.Principal;
import io.envoyproxy.envoy.config.rbac.v3.Principal.Authenticated;
import io.envoyproxy.envoy.config.rbac.v3.RBAC;
import io.envoyproxy.envoy.config.rbac.v3.RBAC.Action;
import io.envoyproxy.envoy.config.route.v3.HeaderMatcher;
import io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBACPerRoute;
import io.envoyproxy.envoy.type.matcher.v3.MetadataMatcher;
import io.envoyproxy.envoy.type.matcher.v3.PathMatcher;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import io.envoyproxy.envoy.type.v3.Int32Range;
import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.testing.TestMethodDescriptors;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AlwaysTrueMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AuthConfig;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AuthDecision;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.DestinationPortMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.OrMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.PolicyMatcher;
import java.net.InetSocketAddress;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.SSLSession;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

/** Tests for {@link RbacFilter}. */
@RunWith(JUnit4.class)
public class RbacFilterTest {
  private static final String PATH = "auth";
  private static final StringMatcher STRING_MATCHER =
          StringMatcher.newBuilder().setExact("/" + PATH).setIgnoreCase(true).build();

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void ipPortParser() {
    CidrRange cidrRange = CidrRange.newBuilder().setAddressPrefix("10.10.10.0")
            .setPrefixLen(UInt32Value.of(24)).build();
    List<Permission> permissionList = Arrays.asList(
            Permission.newBuilder().setAndRules(Permission.Set.newBuilder()
                    .addRules(Permission.newBuilder().setDestinationIp(cidrRange).build())
                    .addRules(Permission.newBuilder().setDestinationPort(9090).build()).build()
            ).build());
    List<Principal> principalList = Arrays.asList(
            Principal.newBuilder().setAndIds(Principal.Set.newBuilder()
                    .addIds(Principal.newBuilder().setDirectRemoteIp(cidrRange).build())
                    .addIds(Principal.newBuilder().setRemoteIp(cidrRange).build())
                    .addIds(Principal.newBuilder().setSourceIp(cidrRange).build())
                    .build()).build());
    ConfigOrError<?> result = parseRaw(permissionList, principalList);
    assertThat(result.errorDetail).isNull();
    ServerCall<Void,Void> serverCall = mock(ServerCall.class);
    Attributes attributes = Attributes.newBuilder()
            .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, new InetSocketAddress("10.10.10.0", 1))
            .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, new InetSocketAddress("10.10.10.0",9090))
            .build();
    when(serverCall.getAttributes()).thenReturn(attributes);
    when(serverCall.getMethodDescriptor()).thenReturn(method().build());
    GrpcAuthorizationEngine engine =
            new GrpcAuthorizationEngine(((RbacConfig)result.config).authConfig());
    AuthDecision decision = engine.evaluate(new Metadata(), serverCall);
    assertThat(decision.decision()).isEqualTo(GrpcAuthorizationEngine.Action.DENY);
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void portRangeParser() {
    List<Permission> permissionList = Arrays.asList(
        Permission.newBuilder().setDestinationPortRange(
            Int32Range.newBuilder().setStart(1010).setEnd(65535).build()
        ).build());
    List<Principal> principalList = Arrays.asList(
        Principal.newBuilder().setRemoteIp(
            CidrRange.newBuilder().setAddressPrefix("10.10.10.0")
                .setPrefixLen(UInt32Value.of(24)).build()
        ).build());
    ConfigOrError<?> result = parse(permissionList, principalList);
    assertThat(result.errorDetail).isNull();
    ServerCall<Void,Void> serverCall = mock(ServerCall.class);
    Attributes attributes = Attributes.newBuilder()
        .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, new InetSocketAddress("10.10.10.0", 1))
        .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, new InetSocketAddress("10.10.10.0",9090))
        .build();
    when(serverCall.getAttributes()).thenReturn(attributes);
    when(serverCall.getMethodDescriptor()).thenReturn(method().build());
    GrpcAuthorizationEngine engine =
        new GrpcAuthorizationEngine(((RbacConfig)result.config).authConfig());
    AuthDecision decision = engine.evaluate(new Metadata(), serverCall);
    assertThat(decision.decision()).isEqualTo(GrpcAuthorizationEngine.Action.DENY);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void pathParser() {
    PathMatcher pathMatcher = PathMatcher.newBuilder().setPath(STRING_MATCHER).build();
    List<Permission> permissionList = Arrays.asList(
            Permission.newBuilder().setUrlPath(pathMatcher).build());
    List<Principal> principalList = Arrays.asList(
            Principal.newBuilder().setUrlPath(pathMatcher).build());
    ConfigOrError<RbacConfig> result = parse(permissionList, principalList);
    assertThat(result.errorDetail).isNull();
    ServerCall<Void,Void> serverCall = mock(ServerCall.class);
    when(serverCall.getMethodDescriptor()).thenReturn(method().build());
    GrpcAuthorizationEngine engine =
            new GrpcAuthorizationEngine(result.config.authConfig());
    AuthDecision decision = engine.evaluate(new Metadata(), serverCall);
    assertThat(decision.decision()).isEqualTo(GrpcAuthorizationEngine.Action.DENY);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void authenticatedParser() throws Exception {
    List<Permission> permissionList = Arrays.asList(
            Permission.newBuilder().setNotRule(
               Permission.newBuilder().setRequestedServerName(STRING_MATCHER).build()).build());
    List<Principal> principalList = Arrays.asList(
            Principal.newBuilder().setAuthenticated(Authenticated.newBuilder()
                    .setPrincipalName(STRING_MATCHER).build()).build());
    ConfigOrError<?> result = parse(permissionList, principalList);
    assertThat(result.errorDetail).isNull();
    SSLSession sslSession = mock(SSLSession.class);
    X509Certificate mockCert = mock(X509Certificate.class);
    when(sslSession.getPeerCertificates()).thenReturn(new X509Certificate[]{mockCert});
    when(mockCert.getSubjectAlternativeNames()).thenReturn(
            Arrays.<List<?>>asList(Arrays.asList(2, "/" + PATH)));
    Attributes attributes = Attributes.newBuilder()
            .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession)
            .build();
    ServerCall<Void,Void> serverCall = mock(ServerCall.class);
    when(serverCall.getAttributes()).thenReturn(attributes);
    GrpcAuthorizationEngine engine =
            new GrpcAuthorizationEngine(((RbacConfig)result.config).authConfig());
    AuthDecision decision = engine.evaluate(new Metadata(), serverCall);
    assertThat(decision.decision()).isEqualTo(GrpcAuthorizationEngine.Action.DENY);
  }

  @Test
  @SuppressWarnings({"unchecked", "deprecation"})
  public void headerParser() {
    HeaderMatcher headerMatcher = HeaderMatcher.newBuilder()
            .setName("party").setExactMatch("win").build();
    List<Permission> permissionList = Arrays.asList(
            Permission.newBuilder().setHeader(headerMatcher).build());
    List<Principal> principalList = Arrays.asList(
            Principal.newBuilder().setHeader(headerMatcher).build());
    ConfigOrError<RbacConfig> result = parseOverride(permissionList, principalList);
    assertThat(result.errorDetail).isNull();
    ServerCall<Void,Void> serverCall = mock(ServerCall.class);
    GrpcAuthorizationEngine engine =
            new GrpcAuthorizationEngine(result.config.authConfig());
    AuthDecision decision = engine.evaluate(metadata("party", "win"), serverCall);
    assertThat(decision.decision()).isEqualTo(GrpcAuthorizationEngine.Action.DENY);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void headerParser_headerName() {
    HeaderMatcher headerMatcher = HeaderMatcher.newBuilder()
        .setName("grpc--feature").setExactMatch("win").build();
    List<Permission> permissionList = Arrays.asList(
        Permission.newBuilder().setHeader(headerMatcher).build());
    HeaderMatcher headerMatcher2 = HeaderMatcher.newBuilder()
        .setName(":scheme").setExactMatch("win").build();
    List<Principal> principalList = Arrays.asList(
        Principal.newBuilder().setHeader(headerMatcher2).build());
    ConfigOrError<RbacConfig> result = parseOverride(permissionList, principalList);
    assertThat(result.errorDetail).isNotNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void compositeRules() {
    MetadataMatcher metadataMatcher = MetadataMatcher.newBuilder().build();
    List<Permission> permissionList = Arrays.asList(
            Permission.newBuilder().setOrRules(Permission.Set.newBuilder().addRules(
                    Permission.newBuilder().setMetadata(metadataMatcher).build()
            ).build()).build());
    List<Principal> principalList = Arrays.asList(
            Principal.newBuilder().setNotId(
                    Principal.newBuilder().setMetadata(metadataMatcher).build()
            ).build());
    ConfigOrError<? extends FilterConfig> result = parse(permissionList, principalList);
    assertThat(result.errorDetail).isNull();
    assertThat(result.config).isInstanceOf(RbacConfig.class);
    ServerCall<Void,Void> serverCall = mock(ServerCall.class);
    GrpcAuthorizationEngine engine =
            new GrpcAuthorizationEngine(((RbacConfig)result.config).authConfig());
    AuthDecision decision = engine.evaluate(new Metadata(), serverCall);
    assertThat(decision.decision()).isEqualTo(GrpcAuthorizationEngine.Action.ALLOW);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void testAuthorizationInterceptor() {
    ServerCallHandler<Void, Void> mockHandler = mock(ServerCallHandler.class);
    ServerCall<Void, Void> mockServerCall = mock(ServerCall.class);
    Attributes attr = Attributes.newBuilder()
            .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, new InetSocketAddress("1::", 20))
            .build();
    when(mockServerCall.getAttributes()).thenReturn(attr);
    PolicyMatcher policyMatcher = PolicyMatcher.create("policy-matcher",
            OrMatcher.create(DestinationPortMatcher.create(99999)),
            OrMatcher.create(AlwaysTrueMatcher.INSTANCE));
    AuthConfig authconfig = AuthConfig.create(Collections.singletonList(policyMatcher),
            GrpcAuthorizationEngine.Action.ALLOW);
    new RbacFilter().buildServerInterceptor(RbacConfig.create(authconfig), null)
            .interceptCall(mockServerCall, new Metadata(), mockHandler);
    verify(mockHandler, never()).startCall(eq(mockServerCall), any(Metadata.class));
    ArgumentCaptor<Status> captor = ArgumentCaptor.forClass(Status.class);
    verify(mockServerCall).close(captor.capture(), any(Metadata.class));
    assertThat(captor.getValue().getCode()).isEqualTo(Status.PERMISSION_DENIED.getCode());
    assertThat(captor.getValue().getDescription()).isEqualTo("Access Denied");
    verify(mockServerCall).getAttributes();
    verifyNoMoreInteractions(mockServerCall);

    authconfig = AuthConfig.create(Collections.singletonList(policyMatcher),
            GrpcAuthorizationEngine.Action.DENY);
    new RbacFilter().buildServerInterceptor(RbacConfig.create(authconfig), null)
            .interceptCall(mockServerCall, new Metadata(), mockHandler);
    verify(mockHandler).startCall(eq(mockServerCall), any(Metadata.class));
  }

  @Test
  public void handleException() {
    PathMatcher pathMatcher = PathMatcher.newBuilder()
            .setPath(StringMatcher.newBuilder().build()).build();
    List<Permission> permissionList = Arrays.asList(
            Permission.newBuilder().setUrlPath(pathMatcher).build());
    List<Principal> principalList = Arrays.asList(
            Principal.newBuilder().setUrlPath(pathMatcher).build());
    ConfigOrError<?> result = parse(permissionList, principalList);
    assertThat(result.errorDetail).isNotNull();

    permissionList = Arrays.asList(Permission.newBuilder().build());
    principalList = Arrays.asList(Principal.newBuilder().build());
    result = parse(permissionList, principalList);
    assertThat(result.errorDetail).isNotNull();

    Message rawProto = io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC.newBuilder()
            .setRules(RBAC.newBuilder().setAction(Action.DENY)
                    .putPolicies("policy-name",
                            Policy.newBuilder().setCondition(Expr.newBuilder().build()).build())
                    .build()).build();
    result = new RbacFilter().parseFilterConfig(Any.pack(rawProto));
    assertThat(result.errorDetail).isNotNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void overrideConfig() {
    ServerCallHandler<Void, Void> mockHandler = mock(ServerCallHandler.class);
    ServerCall<Void, Void> mockServerCall = mock(ServerCall.class);
    Attributes attr = Attributes.newBuilder()
            .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, new InetSocketAddress("1::", 20))
            .build();
    when(mockServerCall.getAttributes()).thenReturn(attr);

    PolicyMatcher policyMatcher = PolicyMatcher.create("policy-matcher",
            OrMatcher.create(DestinationPortMatcher.create(99999)),
            OrMatcher.create(AlwaysTrueMatcher.INSTANCE));
    AuthConfig authconfig =  AuthConfig.create(Collections.singletonList(policyMatcher),
            GrpcAuthorizationEngine.Action.ALLOW);
    RbacConfig original = RbacConfig.create(authconfig);

    RBACPerRoute rbacPerRoute = RBACPerRoute.newBuilder().build();
    RbacConfig override =
            new RbacFilter().parseFilterConfigOverride(Any.pack(rbacPerRoute)).config;
    assertThat(override).isEqualTo(RbacConfig.create(null));
    ServerInterceptor interceptor = new RbacFilter().buildServerInterceptor(original, override);
    assertThat(interceptor).isNull();

    policyMatcher = PolicyMatcher.create("policy-matcher-override",
            OrMatcher.create(DestinationPortMatcher.create(20)),
            OrMatcher.create(AlwaysTrueMatcher.INSTANCE));
    authconfig =  AuthConfig.create(Collections.singletonList(policyMatcher),
            GrpcAuthorizationEngine.Action.ALLOW);
    override = RbacConfig.create(authconfig);

    new RbacFilter().buildServerInterceptor(original, override)
            .interceptCall(mockServerCall, new Metadata(), mockHandler);
    verify(mockHandler).startCall(eq(mockServerCall), any(Metadata.class));
    verify(mockServerCall).getAttributes();
    verifyNoMoreInteractions(mockServerCall);
  }

  @Test
  public void ignoredConfig() {
    Message rawProto = io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC.newBuilder()
            .setRules(RBAC.newBuilder().setAction(Action.LOG)
                    .putPolicies("policy-name", Policy.newBuilder().build()).build()).build();
    ConfigOrError<RbacConfig> result = new RbacFilter().parseFilterConfig(Any.pack(rawProto));
    assertThat(result.config).isEqualTo(RbacConfig.create(null));
  }

  @Test
  public void testOrderIndependenceOfPolicies() {
    Message rawProto = buildComplexRbac(ImmutableList.of(1, 2, 3, 4, 5, 6), true);
    ConfigOrError<RbacConfig> ascFirst = new RbacFilter().parseFilterConfig(Any.pack(rawProto));

    rawProto = buildComplexRbac(ImmutableList.of(1, 2, 3, 4, 5, 6), false);
    ConfigOrError<RbacConfig> ascLast = new RbacFilter().parseFilterConfig(Any.pack(rawProto));

    assertThat(ascFirst.config).isEqualTo(ascLast.config);

    rawProto = buildComplexRbac(ImmutableList.of(6, 5, 4, 3, 2, 1), true);
    ConfigOrError<RbacConfig> decFirst = new RbacFilter().parseFilterConfig(Any.pack(rawProto));

    assertThat(ascFirst.config).isEqualTo(decFirst.config);
  }

  private static Metadata metadata(String key, String value) {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value);
    return metadata;
  }

  private MethodDescriptor.Builder<Void, Void> method() {
    return MethodDescriptor.<Void,Void>newBuilder()
            .setType(MethodType.BIDI_STREAMING)
            .setFullMethodName(PATH)
            .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
            .setResponseMarshaller(TestMethodDescriptors.voidMarshaller());
  }

  private ConfigOrError<RbacConfig> parse(List<Permission> permissionList,
                                                      List<Principal> principalList) {

    return RbacFilter.parseRbacConfig(buildRbac(permissionList, principalList));
  }

  private ConfigOrError<RbacConfig> parseRaw(List<Permission> permissionList,
                                                      List<Principal> principalList) {
    Message rawProto = buildRbac(permissionList, principalList);
    Any proto = Any.pack(rawProto);
    return new RbacFilter().parseFilterConfig(proto);
  }

  private io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC buildRbac(
          List<Permission> permissionList, List<Principal> principalList) {
    return io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC.newBuilder()
            .setRules(buildRbacRule("policy-name", Action.DENY,
                permissionList, principalList)).build();
  }

  private static RBAC buildRbacRule(String policyName, Action action,
      List<Permission> permissionList, List<Principal> principalList) {
    return RBAC.newBuilder().setAction(action)
        .putPolicies(policyName, Policy.newBuilder()
            .addAllPermissions(permissionList)
            .addAllPrincipals(principalList).build())
        .build();
  }

  private io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC buildComplexRbac(
      List<Integer> ids, boolean listsFirst) {
    Policy policy1 = createSimplePolicyUsingLists(0);

    RBAC.Builder ruleBuilder = RBAC.newBuilder().setAction(Action.DENY);

    if (listsFirst) {
      ruleBuilder.putPolicies("list-policy", policy1);
    }

    String base = "filterConfig\\u003dRbacConfig{authConfig\\u003dAuthConfig{policies\\u003d[Poli"
        + "cyMatcher{name\\u003dpsm-interop-authz-policy-20230514-0917-er2uh_td_rbac_rule_";

    for (Integer id : ids) {
      ruleBuilder.putPolicies(base + id, createSimplePolicyUsingLists(id));
    }

    if (!listsFirst) {
      ruleBuilder.putPolicies("list-policy", policy1);
    }

    return io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC.newBuilder()
        .setRules(ruleBuilder.build()).build();
  }

  private static Policy createSimplePolicyUsingLists(int id) {
    CidrRange cidrRange = CidrRange.newBuilder().setAddressPrefix("10.10." + id + ".0")
        .setPrefixLen(UInt32Value.of(24)).build();
    List<Permission> permissionList = Arrays.asList(
        Permission.newBuilder().setAndRules(Permission.Set.newBuilder()
            .addRules(Permission.newBuilder().setDestinationIp(cidrRange).build())
            .addRules(Permission.newBuilder().setDestinationPort(9090).build()).build()
        ).build());
    List<Principal> principalList = Arrays.asList(
        Principal.newBuilder().setAndIds(Principal.Set.newBuilder()
            .addIds(Principal.newBuilder().setDirectRemoteIp(cidrRange).build())
            .addIds(Principal.newBuilder().setRemoteIp(cidrRange).build())
            .build()).build());

    return Policy.newBuilder()
        .addAllPermissions(permissionList)
        .addAllPrincipals(principalList).build();
  }

  private ConfigOrError<RbacConfig> parseOverride(List<Permission> permissionList,
                                             List<Principal> principalList) {
    RBACPerRoute rbacPerRoute = RBACPerRoute.newBuilder().setRbac(
            buildRbac(permissionList, principalList)).build();
    Any proto = Any.pack(rbacPerRoute);
    return new RbacFilter().parseFilterConfigOverride(proto);
  }
}
