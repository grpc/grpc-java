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


import com.google.common.io.BaseEncoding;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.HeaderMap;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.service.auth.v3.AttributeContext;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.xds.internal.Matchers;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * Builds external authorization check requests from gRPC call metadata.
 */
@ThreadSafe
public class CheckRequestBuilder {

  /**
   * An interface for providing certificate-related information.
   */
  interface CertificateProvider {
    /**
     * Gets the principal from a certificate.
     *
     * @param cert The certificate.
     * @return The principal.
     */
    String getPrincipal(X509Certificate cert);

    /**
     * Gets the URL PEM encoded certificate.
     *
     * @param cert The certificate.
     * @return The URL PEM encoded certificate.
     * @throws CertificateEncodingException If an error occurs while encoding the certificate.
     * @throws UnsupportedEncodingException If an error occurs while encoding the URL.
     */
    String getUrlPemEncodedCertificate(X509Certificate cert)
        throws CertificateEncodingException, UnsupportedEncodingException;
  }

  private static final Logger logger = Logger.getLogger(CheckRequestBuilder.class.getName());
  private static final BaseEncoding BASE64_NO_PAD = BaseEncoding.base64().omitPadding();


  private final ExtAuthzConfig config;
  private final CertificateProvider certificateProvider;

  /**
   * Constructs a new {@link CheckRequestBuilder} with the default certificate provider.
   *
   * @param config The external authorization configuration.
   */
  public CheckRequestBuilder(ExtAuthzConfig config) {
    this(config, new CertificateProvider() {
      @Override
      public String getPrincipal(X509Certificate cert) {
        return CertificateUtils.getPrincipal(cert);
      }

      @Override
      public String getUrlPemEncodedCertificate(X509Certificate cert)
          throws CertificateEncodingException, UnsupportedEncodingException {
        return CertificateUtils.getUrlPemEncodedCertificate(cert);
      }
    });
  }

  /**
   * Constructs a new {@link CheckRequestBuilder} with a custom certificate provider.
   *
   * @param config The external authorization configuration.
   * @param certificateProvider The certificate provider.
   */
  CheckRequestBuilder(ExtAuthzConfig config, CertificateProvider certificateProvider) {
    this.config = config;
    this.certificateProvider = certificateProvider;
  }


  /**
   * Builds a check request for a client-side call.
   *
   * @param methodDescriptor The method descriptor of the RPC.
   * @param headers The initial metadata headers.
   * @param requestTime The timestamp when the request was initiated.
   * @return The constructed {@link CheckRequest}.
   */
  public CheckRequest buildRequest(MethodDescriptor<?, ?> methodDescriptor, Metadata headers,
      Timestamp requestTime) {
    return build(methodDescriptor, headers, requestTime, null, null, null);
  }


  /**
   * Builds a check request for a server-side call.
   *
   * @param serverCall The server call.
   * @param headers The initial metadata headers.
   * @param requestTime The timestamp when the request was initiated.
   * @return The constructed {@link CheckRequest}.
   */
  public CheckRequest buildRequest(ServerCall<?, ?> serverCall, Metadata headers,
      Timestamp requestTime) {
    java.net.SocketAddress localAddress =
        serverCall.getAttributes().get(Grpc.TRANSPORT_ATTR_LOCAL_ADDR);
    java.net.SocketAddress remoteAddress =
        serverCall.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
    SSLSession sslSession = serverCall.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
    return build(serverCall.getMethodDescriptor(), headers, requestTime, localAddress,
        remoteAddress, sslSession);
  }

  private CheckRequest build(MethodDescriptor<?, ?> methodDescriptor, Metadata headers,
      Timestamp requestTime, @Nullable java.net.SocketAddress localAddress,
      @Nullable java.net.SocketAddress remoteAddress, @Nullable SSLSession sslSession) {
    AttributeContext.Builder attrBuilder = AttributeContext.newBuilder();
    if (remoteAddress != null) {
      attrBuilder.setSource(buildSource(remoteAddress, sslSession));
    }
    if (localAddress != null) {
      attrBuilder.setDestination(buildDestination(localAddress, sslSession));
    }
    attrBuilder.setRequest(
        buildAttributeRequest(headers, methodDescriptor.getFullMethodName(), requestTime));
    return CheckRequest.newBuilder().setAttributes(attrBuilder).build();
  }

  private AttributeContext.Peer buildSource(java.net.SocketAddress socketAddress,
      @Nullable SSLSession sslSession) {
    AttributeContext.Peer.Builder peerBuilder = buildPeer(socketAddress).toBuilder();
    if (sslSession != null) {
      Certificate[] certs = null;
      try {
        certs = sslSession.getPeerCertificates();
      } catch (SSLPeerUnverifiedException e) {
        logger.log(Level.FINE, "Peer is not authenticated; omitting principal and certificate.", e);
      }
      if (certs != null && certs.length > 0 && certs[0] instanceof X509Certificate) {
        X509Certificate cert = (X509Certificate) certs[0];
        peerBuilder.setPrincipal(certificateProvider.getPrincipal(cert));
        if (config.includePeerCertificate()) {
          try {
            peerBuilder.setCertificate(certificateProvider.getUrlPemEncodedCertificate(cert));
          } catch (UnsupportedEncodingException | CertificateEncodingException e) {
            logger.log(Level.FINE, "Error encoding peer certificate; omitting from request.", e);
          }
        }
      }
    }
    return peerBuilder.build();
  }

  private AttributeContext.Peer buildDestination(java.net.SocketAddress socketAddress,
      @Nullable SSLSession sslSession) {
    AttributeContext.Peer.Builder peerBuilder = buildPeer(socketAddress).toBuilder();
    if (sslSession != null) {
      Certificate[] certs = sslSession.getLocalCertificates();
      if (certs != null && certs.length > 0 && certs[0] instanceof X509Certificate) {
        peerBuilder.setPrincipal(certificateProvider.getPrincipal((X509Certificate) certs[0]));
      }
    }
    return peerBuilder.build();
  }

  private AttributeContext.Peer buildPeer(java.net.SocketAddress socketAddress) {
    AttributeContext.Peer.Builder peerBuilder = AttributeContext.Peer.newBuilder();
    if (socketAddress instanceof InetSocketAddress) {
      InetSocketAddress inetSocketAddress = (InetSocketAddress) socketAddress;
      // Prefer the resolved IP address, but fall back to the hostname string for
      // unresolved addresses. In practice, Netty transports always provide resolved
      // InetSocketAddress instances for active connections, and other gRPC
      // implementations (C++, Go) always produce IP addresses because they operate
      // on real TCP sockets. However, Envoy's address.proto permits hostnames (the
      // only constraint is a non-empty string), so we gracefully fall back to
      // getHostString() for robustness. See also TcpMetrics.java for precedent:
      // https://github.com/grpc/grpc-java/blob/master/netty/src/main/java/io/grpc/netty/TcpMetrics.java
      String address;
      if (inetSocketAddress.getAddress() != null) {
        address = inetSocketAddress.getAddress().getHostAddress();
      } else {
        address = inetSocketAddress.getHostString();
      }
      peerBuilder
          .setAddress(Address.newBuilder()
              .setSocketAddress(SocketAddress.newBuilder()
                  .setAddress(address)
                  .setPortValue(inetSocketAddress.getPort()))
              .build());
    }
    return peerBuilder.build();
  }

  private AttributeContext.Request buildAttributeRequest(Metadata headers, String fullMethodName,
      Timestamp requestTime) {
    AttributeContext.Request.Builder reqBuilder = AttributeContext.Request.newBuilder();
    reqBuilder.setTime(requestTime);
    AttributeContext.HttpRequest.Builder httpReqBuilder = AttributeContext.HttpRequest.newBuilder();
    httpReqBuilder.setPath("/" + fullMethodName);
    httpReqBuilder.setMethod("POST");
    httpReqBuilder.setProtocol("HTTP/2");
    httpReqBuilder.setSize(-1);

    HeaderMap.Builder headerMapBuilder = HeaderMap.newBuilder();
    for (String key : headers.keys()) {
      if (!isAllowed(key)) {
        continue;
      }
      String lowerCaseKey = key.toLowerCase(Locale.ROOT);
      if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
        populateBinaryHeaderValues(headers, key, lowerCaseKey, headerMapBuilder);
      } else {
        populateAsciiHeaderValues(headers, key, lowerCaseKey, headerMapBuilder);
      }
    }
    httpReqBuilder.setHeaderMap(headerMapBuilder);
    reqBuilder.setHttp(httpReqBuilder);
    return reqBuilder.build();
  }

  private void populateBinaryHeaderValues(Metadata headers, String key, String lowerCaseKey,
      HeaderMap.Builder headerMapBuilder) {
    Iterable<byte[]> binaryValues =
        headers.getAll(Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER));
    if (binaryValues != null) {
      for (byte[] value : binaryValues) {
        // Binary header values are base64-encoded before storing in rawValue,
        // matching Envoy's behavior for CheckRequest header serialization.
        String base64Value = BASE64_NO_PAD.encode(value);
        headerMapBuilder.addHeaders(
            HeaderValue.newBuilder()
                .setKey(lowerCaseKey)
                .setRawValue(ByteString.copyFromUtf8(base64Value))
                .build());
      }
    }
  }

  private void populateAsciiHeaderValues(Metadata headers, String key, String lowerCaseKey,
      HeaderMap.Builder headerMapBuilder) {
    Iterable<String> stringValues =
        headers.getAll(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
    if (stringValues != null) {
      for (String value : stringValues) {
        headerMapBuilder.addHeaders(
            HeaderValue.newBuilder()
                .setKey(lowerCaseKey)
                .setRawValue(ByteString.copyFromUtf8(value))
                .build());
      }
    }
  }

  private boolean isAllowed(String header) {
    for (Matchers.StringMatcher matcher : config.disallowedHeaders()) {
      if (matcher.matches(header)) {
        return false;
      }
    }
    if (config.allowedHeaders().isEmpty()) {
      return true;
    }
    for (Matchers.StringMatcher matcher : config.allowedHeaders()) {
      if (matcher.matches(header)) {
        return true;
      }
    }
    return false;
  }
}
