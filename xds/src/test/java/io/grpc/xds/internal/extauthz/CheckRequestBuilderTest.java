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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.Any;
import com.google.protobuf.Timestamp;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz;
import io.envoyproxy.envoy.extensions.grpc_service.call_credentials.access_token.v3.AccessTokenCredentials;
import io.envoyproxy.envoy.extensions.grpc_service.channel_credentials.google_default.v3.GoogleDefaultCredentials;
import io.envoyproxy.envoy.service.auth.v3.AttributeContext;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.envoyproxy.envoy.type.matcher.v3.ListStringMatcher;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.testing.TestMethodDescriptors;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
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
public class CheckRequestBuilderTest {
  @Rule
  public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock
  private ServerCall<Void, Void> serverCall;
  @Mock
  private SSLSession sslSession;
  @Mock
  private ExtAuthzCertificateProvider certificateProvider;

  private CheckRequestBuilder checkRequestBuilder;
  private MethodDescriptor<Void, Void> methodDescriptor;
  private Timestamp requestTime;

  @Before
  public void setUp() throws ExtAuthzParseException {
    ExtAuthzConfig config = buildExtAuthzConfig();
    checkRequestBuilder = CheckRequestBuilder.INSTANCE.create(config, certificateProvider);
    methodDescriptor = TestMethodDescriptors.voidMethod();
    requestTime = Timestamp.newBuilder().setSeconds(12345).setNanos(67890).build();
  }

  @Test
  public void buildRequest_forServer_happyPath() throws Exception {
    // Setup for addresses
    SocketAddress localAddress = new InetSocketAddress("10.0.0.2", 443);
    SocketAddress remoteAddress = new InetSocketAddress("192.168.1.1", 12345);

    // Setup for SSL and certificates
    X509Certificate peerCert = mock(X509Certificate.class);
    X509Certificate localCert = mock(X509Certificate.class);
    Certificate[] peerCerts = new Certificate[] {peerCert};
    Certificate[] localCerts = new Certificate[] {localCert};
    when(sslSession.getPeerCertificates()).thenReturn(peerCerts);
    when(sslSession.getLocalCertificates()).thenReturn(localCerts);
    when(certificateProvider.getPrincipal(peerCert)).thenReturn("peer-principal");
    when(certificateProvider.getPrincipal(localCert)).thenReturn("local-principal");
    when(certificateProvider.getUrlPemEncodedCertificate(peerCert)).thenReturn("encoded-peer-cert");

    // Setup for headers
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("allowed-header", Metadata.ASCII_STRING_MARSHALLER), "v1");
    headers.put(Metadata.Key.of("disallowed-header", Metadata.ASCII_STRING_MARSHALLER), "v2");
    headers.put(Metadata.Key.of("overridden-header", Metadata.ASCII_STRING_MARSHALLER), "v3");
    byte[] binaryValue = new byte[] {1, 2, 3};
    headers.put(Metadata.Key.of("bin-header-bin", Metadata.BINARY_BYTE_MARSHALLER), binaryValue);

    // Configure CheckRequestBuilder to allow specific headers
    ListStringMatcher allowedHeaders = ListStringMatcher.newBuilder()
        .addPatterns(StringMatcher.newBuilder().setExact("allowed-header").build())
        .addPatterns(StringMatcher.newBuilder().setExact("overridden-header").build()).build();
    ListStringMatcher disallowedHeaders = ListStringMatcher.newBuilder()
        .addPatterns(StringMatcher.newBuilder().setExact("disallowed-header").build())
        .addPatterns(StringMatcher.newBuilder().setExact("overridden-header").build()).build();
    ExtAuthzConfig config = buildExtAuthzConfig(allowedHeaders, disallowedHeaders, true);
    checkRequestBuilder = CheckRequestBuilder.INSTANCE.create(config, certificateProvider);

    // Setup server call attributes
    Attributes attributes =
        Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, localAddress)
            .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, remoteAddress)
            .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession).build();
    when(serverCall.getAttributes()).thenReturn(attributes);
    when(serverCall.getMethodDescriptor()).thenReturn(methodDescriptor);

    // Build and verify the request
    CheckRequest request = checkRequestBuilder.buildRequest(serverCall, headers, requestTime);

    AttributeContext attrContext = request.getAttributes();
    assertThat(attrContext.getSource().getAddress().getSocketAddress().getAddress())
        .isEqualTo("192.168.1.1");
    assertThat(attrContext.getSource().getPrincipal()).isEqualTo("peer-principal");
    assertThat(attrContext.getSource().getCertificate()).isEqualTo("encoded-peer-cert");
    assertThat(attrContext.getDestination().getAddress().getSocketAddress().getAddress())
        .isEqualTo("10.0.0.2");
    assertThat(attrContext.getDestination().getPrincipal()).isEqualTo("local-principal");

    AttributeContext.HttpRequest http = attrContext.getRequest().getHttp();
    assertThat(http.getHeadersMap()).containsEntry("allowed-header", "v1");
    assertThat(http.getHeadersMap()).doesNotContainKey("bin-header-bin");
    assertThat(http.getHeadersMap()).doesNotContainKey("disallowed-header");
    assertThat(http.getHeadersMap()).doesNotContainKey("overridden-header");
  }

  @Test
  public void buildRequest_forServer_noTransportAttrs() {
    when(serverCall.getAttributes()).thenReturn(Attributes.EMPTY);
    when(serverCall.getMethodDescriptor()).thenReturn(methodDescriptor);
    Metadata headers = new Metadata();

    CheckRequest request = checkRequestBuilder.buildRequest(serverCall, headers, requestTime);

    assertThat(request.getAttributes().getRequest().getTime()).isEqualTo(requestTime);
    assertThat(request.getAttributes().getRequest().getHttp().getPath())
        .isEqualTo(methodDescriptor.getFullMethodName());
    assertThat(request.getAttributes().getRequest().getHttp().getMethod()).isEqualTo("POST");
    assertThat(request.getAttributes().getRequest().getHttp().getProtocol()).isEqualTo("HTTP/2");
    assertThat(request.getAttributes().getRequest().getHttp().getSize()).isEqualTo(-1);
    assertThat(request.getAttributes().getRequest().getHttp().getHeadersMap()).isEmpty();
    assertThat(request.getAttributes().hasSource()).isFalse();
    assertThat(request.getAttributes().hasDestination()).isFalse();
  }


  @Test
  public void buildRequest_forClient_happyPath_emptyAllowedHeaders() throws Exception {
    // Setup for headers
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("some-header", Metadata.ASCII_STRING_MARSHALLER), "v1");
    headers.put(Metadata.Key.of("disallowed-header", Metadata.ASCII_STRING_MARSHALLER), "v2");
    byte[] binaryValue = new byte[] {1, 2, 3};
    headers.put(Metadata.Key.of("bin-header-bin", Metadata.BINARY_BYTE_MARSHALLER), binaryValue);

    // Configure CheckRequestBuilder with empty allowed headers
    ListStringMatcher allowedHeaders = ListStringMatcher.newBuilder().build(); // empty
    ListStringMatcher disallowedHeaders = ListStringMatcher.newBuilder()
        .addPatterns(StringMatcher.newBuilder().setExact("disallowed-header").build()).build();
    ExtAuthzConfig config = buildExtAuthzConfig(allowedHeaders, disallowedHeaders, true);
    checkRequestBuilder = CheckRequestBuilder.INSTANCE.create(config, certificateProvider);

    // Build and verify the request
    CheckRequest request = checkRequestBuilder.buildRequest(methodDescriptor, headers, requestTime);

    AttributeContext attrContext = request.getAttributes();
    assertThat(attrContext.hasSource()).isFalse();
    assertThat(attrContext.hasDestination()).isFalse();

    AttributeContext.HttpRequest http = attrContext.getRequest().getHttp();
    assertThat(http.getPath()).isEqualTo(methodDescriptor.getFullMethodName());
    assertThat(http.getHeadersMap()).containsEntry("some-header", "v1");
    assertThat(http.getHeadersMap()).containsEntry("bin-header-bin", "AQID");
    assertThat(http.getHeadersMap()).doesNotContainKey("disallowed-header");
  }

  @Test
  public void buildRequest_forServer_noSslSession() {
    SocketAddress localAddress = new InetSocketAddress("10.0.0.2", 443);
    SocketAddress remoteAddress = new InetSocketAddress("192.168.1.1", 12345);
    Attributes attributes =
        Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, localAddress)
            .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, remoteAddress).build();
    when(serverCall.getAttributes()).thenReturn(attributes);
    when(serverCall.getMethodDescriptor()).thenReturn(methodDescriptor);

    CheckRequest request =
        checkRequestBuilder.buildRequest(serverCall, new Metadata(), requestTime);

    AttributeContext attrContext = request.getAttributes();
    assertThat(attrContext.hasSource()).isTrue();
    Address sourceAddress = attrContext.getSource().getAddress();
    assertThat(sourceAddress.getSocketAddress().getAddress()).isEqualTo("192.168.1.1");
    assertThat(sourceAddress.getSocketAddress().getPortValue()).isEqualTo(12345);
    assertThat(attrContext.getSource().getPrincipal()).isEmpty();

    assertThat(attrContext.hasDestination()).isTrue();
    Address destAddress = attrContext.getDestination().getAddress();
    assertThat(destAddress.getSocketAddress().getAddress()).isEqualTo("10.0.0.2");
    assertThat(destAddress.getSocketAddress().getPortValue()).isEqualTo(443);
    assertThat(attrContext.getDestination().getPrincipal()).isEmpty();
  }

  @Test
  public void buildRequest_forServer_sslPeerUnverified() throws Exception {
    SocketAddress remoteAddress = new InetSocketAddress("192.168.1.1", 12345);
    when(sslSession.getPeerCertificates()).thenThrow(new SSLPeerUnverifiedException("unverified"));
    Attributes attributes =
        Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, remoteAddress)
            .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession).build();
    when(serverCall.getAttributes()).thenReturn(attributes);
    when(serverCall.getMethodDescriptor()).thenReturn(methodDescriptor);

    CheckRequest request =
        checkRequestBuilder.buildRequest(serverCall, new Metadata(), requestTime);

    AttributeContext.Peer source = request.getAttributes().getSource();
    assertThat(source.getPrincipal()).isEmpty();
    assertThat(source.getCertificate()).isEmpty();
  }

  @Test
  public void buildRequest_forServer_includePeerCertFalse() throws Exception {
    ExtAuthzConfig config = buildExtAuthzConfig(ListStringMatcher.newBuilder().build(),
        ListStringMatcher.newBuilder().build(), false);
    checkRequestBuilder = CheckRequestBuilder.INSTANCE.create(config, certificateProvider);
    SocketAddress remoteAddress = new InetSocketAddress("192.168.1.1", 12345);
    X509Certificate peerCert = mock(X509Certificate.class);
    Certificate[] peerCerts = new Certificate[] {peerCert};

    when(sslSession.getPeerCertificates()).thenReturn(peerCerts);
    when(certificateProvider.getPrincipal(peerCert)).thenReturn("peer-principal");

    Attributes attributes =
        Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, remoteAddress)
            .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession).build();
    when(serverCall.getAttributes()).thenReturn(attributes);
    when(serverCall.getMethodDescriptor()).thenReturn(methodDescriptor);

    CheckRequest request =
        checkRequestBuilder.buildRequest(serverCall, new Metadata(), requestTime);

    AttributeContext.Peer source = request.getAttributes().getSource();
    assertThat(source.getPrincipal()).isEqualTo("peer-principal");
    assertThat(source.getCertificate()).isEmpty();
  }

  @Test
  public void buildRequest_forServer_nullOrEmptyCertificates() throws Exception {
    SocketAddress localAddress = new InetSocketAddress("10.0.0.2", 443);
    SocketAddress remoteAddress = new InetSocketAddress("192.168.1.1", 12345);
    Attributes attributes =
        Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, localAddress)
            .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, remoteAddress)
            .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession).build();
    when(serverCall.getAttributes()).thenReturn(attributes);
    when(serverCall.getMethodDescriptor()).thenReturn(methodDescriptor);

    // Test with null certificates
    when(sslSession.getPeerCertificates()).thenReturn(null);
    when(sslSession.getLocalCertificates()).thenReturn(null);
    CheckRequest request =
        checkRequestBuilder.buildRequest(serverCall, new Metadata(), requestTime);
    AttributeContext.Peer source = request.getAttributes().getSource();
    assertThat(source.getPrincipal()).isEmpty();
    assertThat(source.getCertificate()).isEmpty();
    AttributeContext.Peer destination = request.getAttributes().getDestination();
    assertThat(destination.getPrincipal()).isEmpty();

    // Test with empty certificates
    when(sslSession.getPeerCertificates()).thenReturn(new Certificate[0]);
    when(sslSession.getLocalCertificates()).thenReturn(new Certificate[0]);
    request = checkRequestBuilder.buildRequest(serverCall, new Metadata(), requestTime);
    source = request.getAttributes().getSource();
    assertThat(source.getPrincipal()).isEmpty();
    assertThat(source.getCertificate()).isEmpty();
    destination = request.getAttributes().getDestination();
    assertThat(destination.getPrincipal()).isEmpty();
  }

  @Test
  public void buildRequest_forServer_nonX509Certificate() throws Exception {
    SocketAddress localAddress = new InetSocketAddress("10.0.0.2", 443);
    SocketAddress remoteAddress = new InetSocketAddress("192.168.1.1", 12345);
    Attributes attributes =
        Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, localAddress)
            .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, remoteAddress)
            .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession).build();
    when(serverCall.getAttributes()).thenReturn(attributes);
    when(serverCall.getMethodDescriptor()).thenReturn(methodDescriptor);
    Certificate nonX509Cert = mock(Certificate.class);
    Certificate[] certs = new Certificate[] {nonX509Cert};

    when(sslSession.getPeerCertificates()).thenReturn(certs);
    when(sslSession.getLocalCertificates()).thenReturn(certs);

    CheckRequest request =
        checkRequestBuilder.buildRequest(serverCall, new Metadata(), requestTime);

    AttributeContext.Peer source = request.getAttributes().getSource();
    assertThat(source.getPrincipal()).isEmpty();
    AttributeContext.Peer destination = request.getAttributes().getDestination();
    assertThat(destination.getPrincipal()).isEmpty();
  }

  @Test
  public void buildRequest_forServer_nonInetSocketAddress() {
    SocketAddress remoteAddress = mock(SocketAddress.class);
    when(serverCall.getAttributes()).thenReturn(
        Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, remoteAddress).build());
    when(serverCall.getMethodDescriptor()).thenReturn(methodDescriptor);
    CheckRequest request =
        checkRequestBuilder.buildRequest(serverCall, new Metadata(), requestTime);
    assertThat(request.getAttributes().getSource().hasAddress()).isFalse();
  }

  private ExtAuthzConfig buildExtAuthzConfig() throws ExtAuthzParseException {
    return buildExtAuthzConfig(ListStringMatcher.newBuilder().build(),
        ListStringMatcher.newBuilder().build(), true);
  }

  private ExtAuthzConfig buildExtAuthzConfig(ListStringMatcher allowed,
      ListStringMatcher disallowed, boolean includePeerCertificate) throws ExtAuthzParseException {
    Any googleDefaultChannelCreds = Any.pack(GoogleDefaultCredentials.newBuilder().build());
    Any fakeAccessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("fake-token").build());
    ExtAuthz.Builder builder = ExtAuthz.newBuilder()
        .setGrpcService(io.envoyproxy.envoy.config.core.v3.GrpcService.newBuilder()
            .setGoogleGrpc(io.envoyproxy.envoy.config.core.v3.GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("test-cluster").addChannelCredentialsPlugin(googleDefaultChannelCreds)
                .addCallCredentialsPlugin(fakeAccessTokenCreds).build())
            .build())
        .setIncludePeerCertificate(includePeerCertificate).setAllowedHeaders(allowed)
        .setDisallowedHeaders(disallowed);
    return ExtAuthzConfig.fromProto(builder.build());
  }
}
