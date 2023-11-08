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

import static com.google.common.base.Charsets.US_ASCII;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;
import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerCall;
import io.grpc.internal.testing.TestUtils;
import io.grpc.testing.TestMethodDescriptors;
import io.grpc.xds.internal.Matchers;
import io.grpc.xds.internal.Matchers.CidrMatcher;
import io.grpc.xds.internal.Matchers.StringMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.Action;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AlwaysTrueMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AndMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AuthConfig;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AuthDecision;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AuthHeaderMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.AuthenticatedMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.DestinationIpMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.DestinationPortMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.InvertMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.OrMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.PathMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.PolicyMatcher;
import io.grpc.xds.internal.rbac.engine.GrpcAuthorizationEngine.SourceIpMatcher;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class GrpcAuthorizationEngineTest {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  private static final String POLICY_NAME = "policy-name";
  private static final String HEADER_KEY = "header-key";
  private static final String HEADER_VALUE = "header-val";
  private static final String IP_ADDR1 = "10.10.10.0";
  private static final String IP_ADDR2 = "68.36.0.19";
  private static final int PORT = 100;
  private static final String PATH = "/auth/engine";
  private static final StringMatcher STRING_MATCHER = StringMatcher.forExact("/" + PATH, false);
  private static final Metadata HEADER = metadata(HEADER_KEY, HEADER_VALUE);

  @Mock
  private ServerCall<Void,Void> serverCall;
  @Mock
  private SSLSession sslSession;

  @Before
  public void setUp() throws Exception {
    X509Certificate[] certs = {TestUtils.loadX509Cert("server1.pem")};
    when(sslSession.getPeerCertificates()).thenReturn(certs);
    Attributes attributes = Attributes.newBuilder()
        .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, new InetSocketAddress(IP_ADDR2, PORT))
        .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, new InetSocketAddress(IP_ADDR1, PORT))
        .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession)
        .build();
    when(serverCall.getAttributes()).thenReturn(attributes);
    when(serverCall.getMethodDescriptor()).thenReturn(method().build());
  }

  @Test
  public void ipMatcher() throws Exception {
    CidrMatcher ip1 = CidrMatcher.create(InetAddress.getByName(IP_ADDR1), 24);
    DestinationIpMatcher destIpMatcher = DestinationIpMatcher.create(ip1);
    CidrMatcher ip2 = CidrMatcher.create(InetAddress.getByName(IP_ADDR2), 24);
    SourceIpMatcher sourceIpMatcher = SourceIpMatcher.create(ip2);
    DestinationPortMatcher portMatcher = DestinationPortMatcher.create(PORT);
    OrMatcher permission = OrMatcher.create(AndMatcher.create(portMatcher, destIpMatcher));
    OrMatcher principal = OrMatcher.create(sourceIpMatcher);
    PolicyMatcher policyMatcher = PolicyMatcher.create(POLICY_NAME, permission, principal);

    GrpcAuthorizationEngine engine = new GrpcAuthorizationEngine(
        AuthConfig.create(Collections.singletonList(policyMatcher), Action.ALLOW));
    AuthDecision decision = engine.evaluate(HEADER, serverCall);
    assertThat(decision.decision()).isEqualTo(Action.ALLOW);
    assertThat(decision.matchingPolicyName()).isEqualTo(POLICY_NAME);

    Attributes attributes = Attributes.newBuilder()
        .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, new InetSocketAddress(IP_ADDR2, PORT))
        .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, new InetSocketAddress(IP_ADDR1, 2))
        .build();
    when(serverCall.getAttributes()).thenReturn(attributes);
    decision = engine.evaluate(HEADER, serverCall);
    assertThat(decision.decision()).isEqualTo(Action.DENY);
    assertThat(decision.matchingPolicyName()).isEqualTo(null);

    attributes = Attributes.newBuilder()
        .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, null)
        .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, new InetSocketAddress("1.1.1.1", PORT))
        .build();
    when(serverCall.getAttributes()).thenReturn(attributes);
    decision = engine.evaluate(HEADER, serverCall);
    assertThat(decision.decision()).isEqualTo(Action.DENY);
    assertThat(decision.matchingPolicyName()).isEqualTo(null);

    engine = new GrpcAuthorizationEngine(
        AuthConfig.create(Collections.singletonList(policyMatcher), Action.DENY));
    decision = engine.evaluate(HEADER, serverCall);
    assertThat(decision.decision()).isEqualTo(Action.ALLOW);
    assertThat(decision.matchingPolicyName()).isEqualTo(null);
  }

  @Test
  public void headerMatcher() {
    AuthHeaderMatcher headerMatcher = AuthHeaderMatcher.create(Matchers.HeaderMatcher
        .forExactValue(HEADER_KEY, HEADER_VALUE, false));
    OrMatcher principal = OrMatcher.create(headerMatcher);
    OrMatcher permission = OrMatcher.create(
        InvertMatcher.create(DestinationPortMatcher.create(PORT + 1)));
    PolicyMatcher policyMatcher = PolicyMatcher.create(POLICY_NAME, permission, principal);
    GrpcAuthorizationEngine engine = new GrpcAuthorizationEngine(
        AuthConfig.create(Collections.singletonList(policyMatcher), Action.ALLOW));
    AuthDecision decision = engine.evaluate(HEADER, serverCall);
    assertThat(decision.decision()).isEqualTo(Action.ALLOW);
    assertThat(decision.matchingPolicyName()).isEqualTo(POLICY_NAME);

    HEADER.put(Metadata.Key.of(HEADER_KEY, Metadata.ASCII_STRING_MARSHALLER), HEADER_VALUE);
    headerMatcher = AuthHeaderMatcher.create(Matchers.HeaderMatcher
            .forExactValue(HEADER_KEY, HEADER_VALUE + "," + HEADER_VALUE, false));
    principal = OrMatcher.create(headerMatcher);
    policyMatcher = PolicyMatcher.create(POLICY_NAME,
            OrMatcher.create(AlwaysTrueMatcher.INSTANCE), principal);
    engine = new GrpcAuthorizationEngine(
            AuthConfig.create(Collections.singletonList(policyMatcher), Action.ALLOW));
    decision = engine.evaluate(HEADER, serverCall);
    assertThat(decision.decision()).isEqualTo(Action.ALLOW);

    headerMatcher = AuthHeaderMatcher.create(Matchers.HeaderMatcher
            .forExactValue(HEADER_KEY + Metadata.BINARY_HEADER_SUFFIX, HEADER_VALUE, false));
    principal = OrMatcher.create(headerMatcher);
    policyMatcher = PolicyMatcher.create(POLICY_NAME,
            OrMatcher.create(AlwaysTrueMatcher.INSTANCE), principal);
    engine = new GrpcAuthorizationEngine(
            AuthConfig.create(Collections.singletonList(policyMatcher), Action.ALLOW));
    decision = engine.evaluate(HEADER, serverCall);
    assertThat(decision.decision()).isEqualTo(Action.DENY);
  }

  @Test
  public void headerMatcher_binaryHeader() {
    AuthHeaderMatcher headerMatcher = AuthHeaderMatcher.create(Matchers.HeaderMatcher
        .forExactValue(HEADER_KEY + Metadata.BINARY_HEADER_SUFFIX,
            BaseEncoding.base64().omitPadding().encode(HEADER_VALUE.getBytes(US_ASCII)), false));
    OrMatcher principal = OrMatcher.create(headerMatcher);
    OrMatcher permission = OrMatcher.create(
        InvertMatcher.create(DestinationPortMatcher.create(PORT + 1)));
    PolicyMatcher policyMatcher = PolicyMatcher.create(POLICY_NAME, permission, principal);
    GrpcAuthorizationEngine engine = new GrpcAuthorizationEngine(
        AuthConfig.create(Collections.singletonList(policyMatcher), Action.ALLOW));
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of(HEADER_KEY + Metadata.BINARY_HEADER_SUFFIX,
        Metadata.BINARY_BYTE_MARSHALLER), HEADER_VALUE.getBytes(US_ASCII));
    AuthDecision decision = engine.evaluate(metadata, serverCall);
    assertThat(decision.decision()).isEqualTo(Action.ALLOW);
    assertThat(decision.matchingPolicyName()).isEqualTo(POLICY_NAME);
  }

  @Test
  public void headerMatcher_hardcodePostMethod() {
    AuthHeaderMatcher headerMatcher = AuthHeaderMatcher.create(Matchers.HeaderMatcher
        .forExactValue(":method", "POST", false));
    OrMatcher principal = OrMatcher.create(headerMatcher);
    OrMatcher permission = OrMatcher.create(
        InvertMatcher.create(DestinationPortMatcher.create(PORT + 1)));
    PolicyMatcher policyMatcher = PolicyMatcher.create(POLICY_NAME, permission, principal);
    GrpcAuthorizationEngine engine = new GrpcAuthorizationEngine(
        AuthConfig.create(Collections.singletonList(policyMatcher), Action.ALLOW));
    AuthDecision decision = engine.evaluate(new Metadata(), serverCall);
    assertThat(decision.decision()).isEqualTo(Action.ALLOW);
    assertThat(decision.matchingPolicyName()).isEqualTo(POLICY_NAME);
  }

  @Test
  public void headerMatcher_pathHeader() {
    AuthHeaderMatcher headerMatcher = AuthHeaderMatcher.create(Matchers.HeaderMatcher
        .forExactValue(":path", "/" + PATH, false));
    OrMatcher principal = OrMatcher.create(headerMatcher);
    OrMatcher permission = OrMatcher.create(
        InvertMatcher.create(DestinationPortMatcher.create(PORT + 1)));
    PolicyMatcher policyMatcher = PolicyMatcher.create(POLICY_NAME, permission, principal);
    GrpcAuthorizationEngine engine = new GrpcAuthorizationEngine(
        AuthConfig.create(Collections.singletonList(policyMatcher), Action.ALLOW));
    AuthDecision decision = engine.evaluate(HEADER, serverCall);
    assertThat(decision.decision()).isEqualTo(Action.ALLOW);
    assertThat(decision.matchingPolicyName()).isEqualTo(POLICY_NAME);
  }

  @Test
  public void headerMatcher_aliasAuthorityAndHost() {
    AuthHeaderMatcher headerMatcher = AuthHeaderMatcher.create(Matchers.HeaderMatcher
        .forExactValue("Host", "google.com", false));
    OrMatcher principal = OrMatcher.create(headerMatcher);
    OrMatcher permission = OrMatcher.create(
        InvertMatcher.create(DestinationPortMatcher.create(PORT + 1)));
    PolicyMatcher policyMatcher = PolicyMatcher.create(POLICY_NAME, permission, principal);
    GrpcAuthorizationEngine engine = new GrpcAuthorizationEngine(
        AuthConfig.create(Collections.singletonList(policyMatcher), Action.ALLOW));
    when(serverCall.getAuthority()).thenReturn("google.com");
    AuthDecision decision = engine.evaluate(new Metadata(), serverCall);
    assertThat(decision.decision()).isEqualTo(Action.ALLOW);
    assertThat(decision.matchingPolicyName()).isEqualTo(POLICY_NAME);
  }

  @Test
  public void pathMatcher() {
    PathMatcher pathMatcher = PathMatcher.create(STRING_MATCHER);
    OrMatcher permission = OrMatcher.create(AlwaysTrueMatcher.INSTANCE);
    OrMatcher principal = OrMatcher.create(pathMatcher);
    PolicyMatcher policyMatcher = PolicyMatcher.create(POLICY_NAME, permission, principal);
    GrpcAuthorizationEngine engine = new GrpcAuthorizationEngine(
        AuthConfig.create(Collections.singletonList(policyMatcher), Action.DENY));
    AuthDecision decision = engine.evaluate(HEADER, serverCall);
    assertThat(decision.decision()).isEqualTo(Action.DENY);
    assertThat(decision.matchingPolicyName()).isEqualTo(POLICY_NAME);
  }

  @Test
  public void authenticatedMatcher() throws Exception {
    AuthenticatedMatcher authMatcher = AuthenticatedMatcher.create(
        StringMatcher.forExact("*.test.google.fr", false));
    PathMatcher pathMatcher = PathMatcher.create(STRING_MATCHER);
    OrMatcher permission = OrMatcher.create(authMatcher);
    OrMatcher principal = OrMatcher.create(pathMatcher);
    PolicyMatcher policyMatcher = PolicyMatcher.create(POLICY_NAME, permission, principal);
    GrpcAuthorizationEngine engine = new GrpcAuthorizationEngine(
        AuthConfig.create(Collections.singletonList(policyMatcher), Action.ALLOW));
    AuthDecision decision = engine.evaluate(HEADER, serverCall);
    assertThat(decision.decision()).isEqualTo(Action.ALLOW);
    assertThat(decision.matchingPolicyName()).isEqualTo(POLICY_NAME);

    X509Certificate[] certs = {TestUtils.loadX509Cert("badserver.pem")};
    when(sslSession.getPeerCertificates()).thenReturn(certs);
    decision = engine.evaluate(HEADER, serverCall);
    assertThat(decision.decision()).isEqualTo(Action.DENY);
    assertThat(decision.matchingPolicyName()).isEqualTo(null);

    X509Certificate mockCert = mock(X509Certificate.class);
    when(sslSession.getPeerCertificates()).thenReturn(new X509Certificate[]{mockCert});
    assertThat(engine.evaluate(HEADER, serverCall).decision()).isEqualTo(Action.DENY);
    when(mockCert.getSubjectDN()).thenReturn(mock(Principal.class));
    assertThat(engine.evaluate(HEADER, serverCall).decision()).isEqualTo(Action.DENY);
    when(mockCert.getSubjectAlternativeNames()).thenReturn(Arrays.<List<?>>asList(
        Arrays.asList(2, "*.test.google.fr")));
    assertThat(engine.evaluate(HEADER, serverCall).decision()).isEqualTo(Action.ALLOW);
    when(mockCert.getSubjectAlternativeNames()).thenReturn(Arrays.<List<?>>asList(
        Arrays.asList(6, "*.test.google.fr")));
    assertThat(engine.evaluate(HEADER, serverCall).decision()).isEqualTo(Action.ALLOW);
    when(mockCert.getSubjectAlternativeNames()).thenReturn(Arrays.<List<?>>asList(
        Arrays.asList(10, "*.test.google.fr")));
    assertThat(engine.evaluate(HEADER, serverCall).decision()).isEqualTo(Action.DENY);
    when(mockCert.getSubjectAlternativeNames()).thenReturn(Arrays.<List<?>>asList(
        Arrays.asList(2, "google.com"), Arrays.asList(6, "*.test.google.fr")));
    assertThat(engine.evaluate(HEADER, serverCall).decision()).isEqualTo(Action.ALLOW);
    when(mockCert.getSubjectAlternativeNames()).thenReturn(Arrays.<List<?>>asList(
        Arrays.asList(6, "*.test.google.fr"), Arrays.asList(2, "google.com")));
    assertThat(engine.evaluate(HEADER, serverCall).decision()).isEqualTo(Action.ALLOW);
    when(mockCert.getSubjectAlternativeNames()).thenReturn(Arrays.<List<?>>asList(
        Arrays.asList(2, "*.test.google.fr"), Arrays.asList(6, "google.com")));
    assertThat(engine.evaluate(HEADER, serverCall).decision()).isEqualTo(Action.DENY);
    when(mockCert.getSubjectAlternativeNames()).thenReturn(Arrays.<List<?>>asList(
        Arrays.asList(2, "*.test.google.fr"), Arrays.asList(6, "google.com"),
        Arrays.asList(6, "*.test.google.fr")));
    assertThat(engine.evaluate(HEADER, serverCall).decision()).isEqualTo(Action.ALLOW);

    // match any authenticated connection if StringMatcher not set in AuthenticatedMatcher
    permission = OrMatcher.create(AuthenticatedMatcher.create(null));
    policyMatcher = PolicyMatcher.create(POLICY_NAME, permission, principal);
    when(mockCert.getSubjectAlternativeNames()).thenReturn(
            Arrays.<List<?>>asList(Arrays.asList(6, "random")));
    engine = new GrpcAuthorizationEngine(AuthConfig.create(Collections.singletonList(policyMatcher),
            Action.ALLOW));
    assertThat(engine.evaluate(HEADER, serverCall).decision()).isEqualTo(Action.ALLOW);

    // not match any unauthenticated connection
    Attributes attributes = Attributes.newBuilder()
            .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, new InetSocketAddress(IP_ADDR2, PORT))
            .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, new InetSocketAddress(IP_ADDR1, PORT))
            .build();
    when(serverCall.getAttributes()).thenReturn(attributes);
    assertThat(engine.evaluate(HEADER, serverCall).decision()).isEqualTo(Action.DENY);

    doThrow(new SSLPeerUnverifiedException("bad")).when(sslSession).getPeerCertificates();
    decision = engine.evaluate(HEADER, serverCall);
    assertThat(decision.decision()).isEqualTo(Action.DENY);
    assertThat(decision.matchingPolicyName()).isEqualTo(null);
  }

  @Test
  public void multiplePolicies() throws Exception {
    AuthenticatedMatcher authMatcher = AuthenticatedMatcher.create(
        StringMatcher.forSuffix("TEST.google.fr", true));
    PathMatcher pathMatcher = PathMatcher.create(STRING_MATCHER);
    OrMatcher principal = OrMatcher.create(AndMatcher.create(authMatcher, pathMatcher));
    OrMatcher permission = OrMatcher.create(AndMatcher.create(pathMatcher,
        InvertMatcher.create(DestinationPortMatcher.create(PORT + 1))));
    PolicyMatcher policyMatcher1 = PolicyMatcher.create(POLICY_NAME, permission, principal);

    AuthHeaderMatcher headerMatcher = AuthHeaderMatcher.create(Matchers.HeaderMatcher
        .forExactValue(HEADER_KEY, HEADER_VALUE + 1, false));
    authMatcher = AuthenticatedMatcher.create(
        StringMatcher.forContains("TEST.google.fr"));
    principal = OrMatcher.create(headerMatcher, authMatcher);
    CidrMatcher ip1 = CidrMatcher.create(InetAddress.getByName(IP_ADDR1), 24);
    DestinationIpMatcher destIpMatcher = DestinationIpMatcher.create(ip1);
    permission = OrMatcher.create(destIpMatcher, pathMatcher);
    PolicyMatcher policyMatcher2 = PolicyMatcher.create(POLICY_NAME + "-2", permission, principal);

    GrpcAuthorizationEngine engine = new GrpcAuthorizationEngine(
        AuthConfig.create(ImmutableList.of(policyMatcher1, policyMatcher2), Action.DENY));
    AuthDecision decision = engine.evaluate(HEADER, serverCall);
    assertThat(decision.decision()).isEqualTo(Action.DENY);
    assertThat(decision.matchingPolicyName()).isEqualTo(POLICY_NAME);
  }

  @Test
  public void matchersEqualHashcode() throws Exception {
    PathMatcher pathMatcher = PathMatcher.create(STRING_MATCHER);
    AuthHeaderMatcher headerMatcher = AuthHeaderMatcher.create(
        Matchers.HeaderMatcher.forExactValue("foo", "bar", true));
    DestinationIpMatcher destinationIpMatcher = DestinationIpMatcher.create(
        CidrMatcher.create(InetAddress.getByName(IP_ADDR1), 24));
    DestinationPortMatcher destinationPortMatcher = DestinationPortMatcher.create(PORT);
    GrpcAuthorizationEngine.DestinationPortRangeMatcher portRangeMatcher =
        GrpcAuthorizationEngine.DestinationPortRangeMatcher.create(PORT, PORT + 1);
    InvertMatcher invertMatcher = InvertMatcher.create(portRangeMatcher);
    GrpcAuthorizationEngine.RequestedServerNameMatcher requestedServerNameMatcher =
        GrpcAuthorizationEngine.RequestedServerNameMatcher.create(STRING_MATCHER);
    OrMatcher permission = OrMatcher.create(pathMatcher, headerMatcher, destinationIpMatcher,
        destinationPortMatcher, invertMatcher, requestedServerNameMatcher);
    AuthenticatedMatcher authenticatedMatcher = AuthenticatedMatcher.create(STRING_MATCHER);
    SourceIpMatcher sourceIpMatcher1 = SourceIpMatcher.create(
        CidrMatcher.create(InetAddress.getByName(IP_ADDR1), 24));
    OrMatcher principal = OrMatcher.create(authenticatedMatcher,
        AndMatcher.create(sourceIpMatcher1, AlwaysTrueMatcher.INSTANCE));
    PolicyMatcher policyMatcher1 = PolicyMatcher.create("match", permission, principal);
    AuthConfig config1 = AuthConfig.create(Collections.singletonList(policyMatcher1), Action.ALLOW);

    PathMatcher pathMatcher2 = PathMatcher.create(STRING_MATCHER);
    AuthHeaderMatcher headerMatcher2 = AuthHeaderMatcher.create(
        Matchers.HeaderMatcher.forExactValue("foo", "bar", true));
    DestinationIpMatcher destinationIpMatcher2 = DestinationIpMatcher.create(
        CidrMatcher.create(InetAddress.getByName(IP_ADDR1), 24));
    DestinationPortMatcher destinationPortMatcher2 = DestinationPortMatcher.create(PORT);
    GrpcAuthorizationEngine.DestinationPortRangeMatcher portRangeMatcher2 =
        GrpcAuthorizationEngine.DestinationPortRangeMatcher.create(PORT, PORT + 1);
    InvertMatcher invertMatcher2 = InvertMatcher.create(portRangeMatcher2);
    GrpcAuthorizationEngine.RequestedServerNameMatcher requestedServerNameMatcher2 =
        GrpcAuthorizationEngine.RequestedServerNameMatcher.create(STRING_MATCHER);
    OrMatcher permission2 = OrMatcher.create(pathMatcher2, headerMatcher2, destinationIpMatcher2,
        destinationPortMatcher2, invertMatcher2, requestedServerNameMatcher2);
    AuthenticatedMatcher authenticatedMatcher2 = AuthenticatedMatcher.create(STRING_MATCHER);
    SourceIpMatcher sourceIpMatcher2 = SourceIpMatcher.create(
        CidrMatcher.create(InetAddress.getByName(IP_ADDR1), 24));
    OrMatcher principal2 = OrMatcher.create(authenticatedMatcher2,
        AndMatcher.create(sourceIpMatcher2, AlwaysTrueMatcher.INSTANCE));
    PolicyMatcher policyMatcher2 = PolicyMatcher.create("match", permission2, principal2);
    AuthConfig config2 = AuthConfig.create(Collections.singletonList(policyMatcher2), Action.ALLOW);
    assertThat(config1).isEqualTo(config2);
    assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
  }

  private MethodDescriptor.Builder<Void, Void> method() {
    return MethodDescriptor.<Void,Void>newBuilder()
            .setType(MethodType.BIDI_STREAMING)
            .setFullMethodName(PATH)
            .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
            .setResponseMarshaller(TestMethodDescriptors.voidMarshaller());
  }

  private static Metadata metadata(String key, String value) {
    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value);
    return metadata;
  }
}
