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

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Timestamp;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.HeaderMap;
import io.envoyproxy.envoy.service.auth.v3.AttributeContext;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.testing.TestMethodDescriptors;
import io.grpc.xds.internal.Matchers;
import io.grpc.xds.internal.extauthz.ExtAuthzTestHelper.TestServerCall;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfig;
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

  private TestServerCall<Void, Void> serverCall;
  @Mock
  private SSLSession sslSession;
  @Mock
  private CheckRequestBuilder.CertificateProvider certificateProvider;

  private CheckRequestBuilder checkRequestBuilder;
  private MethodDescriptor<Void, Void> methodDescriptor;
  private Timestamp requestTime;

  @Before
  public void setUp() throws ExtAuthzParseException {
    ExtAuthzConfig config = buildExtAuthzConfig();
    checkRequestBuilder =
        new CheckRequestBuilder(config, certificateProvider);
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
    ImmutableList<Matchers.StringMatcher> allowedHeaders = ImmutableList.of(
        Matchers.StringMatcher.forExact("allowed-header", false),
        Matchers.StringMatcher.forExact("overridden-header", false));
    ImmutableList<Matchers.StringMatcher> disallowedHeaders = ImmutableList.of(
        Matchers.StringMatcher.forExact("disallowed-header", false),
        Matchers.StringMatcher.forExact("overridden-header", false));
    ExtAuthzConfig config = buildExtAuthzConfig(allowedHeaders, disallowedHeaders, true);
    checkRequestBuilder =
        new CheckRequestBuilder(config, certificateProvider);

    // Setup server call attributes
    Attributes attributes =
        Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, localAddress)
            .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, remoteAddress)
            .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession).build();
    serverCall = new TestServerCall<>(attributes, methodDescriptor);

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
    assertThat(http.getHeaderMap().getHeadersList()).containsExactly(
        io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder().setKey("allowed-header")
            .setRawValue(com.google.protobuf.ByteString.copyFromUtf8("v1")).build());
  }

  @Test
  public void buildRequest_forServer_noTransportAttrs() {
    serverCall = new TestServerCall<>(Attributes.EMPTY, methodDescriptor);
    Metadata headers = new Metadata();

    CheckRequest request = checkRequestBuilder.buildRequest(serverCall, headers, requestTime);

    assertThat(request.getAttributes().getRequest().getTime()).isEqualTo(requestTime);
    assertThat(request.getAttributes().getRequest().getHttp().getPath())
        .isEqualTo("/" + methodDescriptor.getFullMethodName());

    assertThat(request.getAttributes().getRequest().getHttp().getMethod()).isEqualTo("POST");
    assertThat(request.getAttributes().getRequest().getHttp().getProtocol()).isEqualTo("HTTP/2");
    assertThat(request.getAttributes().getRequest().getHttp().getSize()).isEqualTo(-1);
    assertThat(request.getAttributes().getRequest().getHttp().getHeaderMap().getHeadersList())
        .isEmpty();
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
    ImmutableList<Matchers.StringMatcher> allowedHeaders = ImmutableList.of();
    ImmutableList<Matchers.StringMatcher> disallowedHeaders = ImmutableList.of(
        Matchers.StringMatcher.forExact("disallowed-header", false));
    ExtAuthzConfig config = buildExtAuthzConfig(allowedHeaders, disallowedHeaders, true);
    checkRequestBuilder =
        new CheckRequestBuilder(config, certificateProvider);

    // Build and verify the request
    CheckRequest request = checkRequestBuilder.buildRequest(methodDescriptor, headers, requestTime);

    AttributeContext attrContext = request.getAttributes();
    assertThat(attrContext.hasSource()).isFalse();
    assertThat(attrContext.hasDestination()).isFalse();

    AttributeContext.HttpRequest http = attrContext.getRequest().getHttp();
    assertThat(http.getPath()).isEqualTo("/" + methodDescriptor.getFullMethodName());

    assertThat(http.getHeaderMap().getHeadersList()).containsExactly(
        io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder().setKey("some-header")
            .setRawValue(com.google.protobuf.ByteString.copyFromUtf8("v1")).build(),
        io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder().setKey("bin-header-bin")
            .setRawValue(com.google.protobuf.ByteString.copyFromUtf8("AQID")).build());
  }

  @Test
  public void buildRequest_forServer_noSslSession() {
    SocketAddress localAddress = new InetSocketAddress("10.0.0.2", 443);
    SocketAddress remoteAddress = new InetSocketAddress("192.168.1.1", 12345);
    Attributes attributes =
        Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, localAddress)
            .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, remoteAddress).build();
    serverCall = new TestServerCall<>(attributes, methodDescriptor);

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
    serverCall = new TestServerCall<>(attributes, methodDescriptor);

    CheckRequest request =
        checkRequestBuilder.buildRequest(serverCall, new Metadata(), requestTime);

    AttributeContext.Peer source = request.getAttributes().getSource();
    assertThat(source.getPrincipal()).isEmpty();
    assertThat(source.getCertificate()).isEmpty();
  }

  @Test
  public void buildRequest_forServer_includePeerCertFalse() throws Exception {
    ExtAuthzConfig config = buildExtAuthzConfig(ImmutableList.of(),
        ImmutableList.of(), false);
    checkRequestBuilder =
        new CheckRequestBuilder(config, certificateProvider);
    SocketAddress remoteAddress = new InetSocketAddress("192.168.1.1", 12345);
    X509Certificate peerCert = mock(X509Certificate.class);
    Certificate[] peerCerts = new Certificate[] {peerCert};

    when(sslSession.getPeerCertificates()).thenReturn(peerCerts);
    when(certificateProvider.getPrincipal(peerCert)).thenReturn("peer-principal");

    Attributes attributes =
        Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, remoteAddress)
            .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession).build();
    serverCall = new TestServerCall<>(attributes, methodDescriptor);

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
    serverCall = new TestServerCall<>(attributes, methodDescriptor);

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
    serverCall = new TestServerCall<>(attributes, methodDescriptor);
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
    serverCall = new TestServerCall<>(
        Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, remoteAddress).build(),
        methodDescriptor);
    CheckRequest request =
        checkRequestBuilder.buildRequest(serverCall, new Metadata(), requestTime);
    assertThat(request.getAttributes().getSource().hasAddress()).isFalse();
  }

  @Test
  public void buildRequest_forServer_unresolvedInetSocketAddress() {
    SocketAddress localAddress =
        InetSocketAddress.createUnresolved("local-hostname", 443);
    SocketAddress remoteAddress =
        InetSocketAddress.createUnresolved("remote-hostname", 8080);
    Attributes attributes =
        Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, localAddress)
            .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, remoteAddress).build();
    serverCall = new TestServerCall<>(attributes, methodDescriptor);

    CheckRequest request =
        checkRequestBuilder.buildRequest(serverCall, new Metadata(), requestTime);

    AttributeContext attrContext = request.getAttributes();
    assertThat(attrContext.hasSource()).isTrue();
    assertThat(attrContext.getSource().getAddress().getSocketAddress().getAddress())
        .isEqualTo("remote-hostname");
    assertThat(attrContext.getSource().getAddress().getSocketAddress().getPortValue())
        .isEqualTo(8080);
    assertThat(attrContext.hasDestination()).isTrue();
    assertThat(attrContext.getDestination().getAddress().getSocketAddress().getAddress())
        .isEqualTo("local-hostname");
    assertThat(attrContext.getDestination().getAddress().getSocketAddress().getPortValue())
        .isEqualTo(443);
  }

  @Test
  public void buildRequest_forServer_handlesCertificateEncodingException() throws Exception {
    SocketAddress localAddress = new InetSocketAddress("10.0.0.2", 443);
    SocketAddress remoteAddress = new InetSocketAddress("192.168.1.1", 12345);
    Attributes attributes =
        Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, localAddress)
            .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, remoteAddress)
            .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSession).build();
    serverCall = new TestServerCall<>(attributes, methodDescriptor);

    X509Certificate peerCert = mock(X509Certificate.class);
    Certificate[] peerCerts = new Certificate[] {peerCert};
    when(sslSession.getPeerCertificates()).thenReturn(peerCerts);
    when(certificateProvider.getPrincipal(peerCert)).thenReturn("peer-principal");
    when(certificateProvider.getUrlPemEncodedCertificate(peerCert))
        .thenThrow(new java.security.cert.CertificateEncodingException("encoding error"));

    CheckRequest request =
        checkRequestBuilder.buildRequest(serverCall, new Metadata(), requestTime);

    AttributeContext.Peer source = request.getAttributes().getSource();
    assertThat(source.getPrincipal()).isEqualTo("peer-principal");
    assertThat(source.getCertificate()).isEmpty();
  }

  @Test
  public void buildRequest_forClient_allowedHeadersFiltering() {
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("allowed-header", Metadata.ASCII_STRING_MARSHALLER), "v1");
    headers.put(Metadata.Key.of("not-allowed-header", Metadata.ASCII_STRING_MARSHALLER), "v2");

    // Configure with non-empty allowed headers — only "allowed-header" should pass
    ImmutableList<Matchers.StringMatcher> allowedHeaders = ImmutableList.of(
        Matchers.StringMatcher.forExact("allowed-header", false));
    ExtAuthzConfig config = buildExtAuthzConfig(allowedHeaders, ImmutableList.of(), true);
    CheckRequestBuilder builder = new CheckRequestBuilder(config, certificateProvider);

    CheckRequest request = builder.buildRequest(methodDescriptor, headers, requestTime);

    HeaderMap headerMap = request.getAttributes().getRequest().getHttp().getHeaderMap();
    assertThat(headerMap.getHeadersList()).hasSize(1);
    assertThat(headerMap.getHeadersList().get(0).getKey()).isEqualTo("allowed-header");
    assertThat(headerMap.getHeadersList().get(0).getRawValue().toStringUtf8()).isEqualTo("v1");
  }

  @Test
  public void buildRequest_forClient_emptyMetadata() {
    CheckRequest request =
        checkRequestBuilder.buildRequest(methodDescriptor, new Metadata(), requestTime);

    AttributeContext attrContext = request.getAttributes();
    assertThat(attrContext.hasSource()).isFalse();
    assertThat(attrContext.hasDestination()).isFalse();
    assertThat(attrContext.getRequest().getHttp().getPath())
        .isEqualTo("/" + methodDescriptor.getFullMethodName());
    assertThat(attrContext.getRequest().getHttp().getMethod()).isEqualTo("POST");
    assertThat(attrContext.getRequest().getHttp().getProtocol()).isEqualTo("HTTP/2");
    assertThat(attrContext.getRequest().getHttp().getHeaderMap().getHeadersList()).isEmpty();
  }

  @Test
  public void buildRequest_multiValuedHeaders() throws Exception {
    Metadata headers = new Metadata();
    Metadata.Key<String> asciiKey = Metadata.Key.of("x-custom", Metadata.ASCII_STRING_MARSHALLER);
    headers.put(asciiKey, "value1");
    headers.put(asciiKey, "value2");

    Metadata.Key<byte[]> binaryKey =
        Metadata.Key.of("x-custom-bin", Metadata.BINARY_BYTE_MARSHALLER);
    headers.put(binaryKey, new byte[]{1, 2});
    headers.put(binaryKey, new byte[]{3, 4});

    ExtAuthzConfig configWithAllowedHeaders = buildExtAuthzConfig(
        ImmutableList.of(Matchers.StringMatcher.forExact("x-custom", false),
            Matchers.StringMatcher.forExact("x-custom-bin", false)),
        ImmutableList.of(), true);
    CheckRequestBuilder builderWithConfig =
        new CheckRequestBuilder(configWithAllowedHeaders, certificateProvider);

    CheckRequest request =
        builderWithConfig.buildRequest(methodDescriptor, headers, requestTime);

    HeaderMap headerMap = request.getAttributes().getRequest().getHttp().getHeaderMap();
    assertThat(headerMap.getHeadersList()).hasSize(4);
    assertThat(headerMap.getHeadersList().get(0).getKey()).isEqualTo("x-custom-bin");
    assertThat(headerMap.getHeadersList().get(0).getRawValue().toStringUtf8()).isEqualTo("AQI");
    assertThat(headerMap.getHeadersList().get(1).getKey()).isEqualTo("x-custom-bin");
    assertThat(headerMap.getHeadersList().get(1).getRawValue().toStringUtf8()).isEqualTo("AwQ");
    assertThat(headerMap.getHeadersList().get(2).getKey()).isEqualTo("x-custom");
    assertThat(headerMap.getHeadersList().get(2).getRawValue().toStringUtf8()).isEqualTo("value1");
    assertThat(headerMap.getHeadersList().get(3).getKey()).isEqualTo("x-custom");
    assertThat(headerMap.getHeadersList().get(3).getRawValue().toStringUtf8()).isEqualTo("value2");
  }

  private ExtAuthzConfig buildExtAuthzConfig() {
    return buildExtAuthzConfig(ImmutableList.of(), ImmutableList.of(), true);
  }

  private ExtAuthzConfig buildExtAuthzConfig(
      ImmutableList<Matchers.StringMatcher> allowed,
      ImmutableList<Matchers.StringMatcher> disallowed,
      boolean includePeerCertificate) {
    GrpcServiceConfig.GoogleGrpcConfig googleGrpc = GrpcServiceConfig.GoogleGrpcConfig.builder()
        .target("test-cluster")
        .configuredChannelCredentials(io.grpc.xds.client.ConfiguredChannelCredentials.create(
            mock(io.grpc.ChannelCredentials.class),
            mock(io.grpc.xds.client.ConfiguredChannelCredentials.ChannelCredsConfig.class)))
        .build();

    GrpcServiceConfig dummyServiceConfig = GrpcServiceConfig.builder()
        .googleGrpc(googleGrpc)
        .initialMetadata(ImmutableList.of())
        .build();

    return ExtAuthzConfig.builder()
        .grpcService(dummyServiceConfig)
        .includePeerCertificate(includePeerCertificate)
        .allowedHeaders(allowed)
        .disallowedHeaders(disallowed)
        .failureModeAllow(true)
        .failureModeAllowHeaderAdd(false)
        .denyAtDisable(false)
        .filterEnabled(Matchers.FractionMatcher.create(100, 100))
        .statusOnError(io.grpc.Status.INTERNAL)
        .build();
  }
}
