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
import com.google.protobuf.Timestamp;
import io.envoyproxy.envoy.config.core.v3.Address;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * Interface for building external authorization check requests.
 */
public class CheckRequestBuilder {

  /**
   * An interface for providing certificate-related information.
   */
  public interface CertificateProvider {
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


  private final ExtAuthzConfig config;
  private final CertificateProvider certificateProvider;

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

  public CheckRequestBuilder(ExtAuthzConfig config, CertificateProvider certificateProvider) {
    this.config = config;
    this.certificateProvider = certificateProvider;
  }


  public CheckRequest buildRequest(MethodDescriptor<?, ?> methodDescriptor, Metadata headers,
      Timestamp requestTime) {
    return build(methodDescriptor, headers, requestTime, null, null, null);
  }


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
      peerBuilder
          .setAddress(Address.newBuilder()
              .setSocketAddress(SocketAddress.newBuilder()
                  .setAddress(inetSocketAddress.getAddress().getHostAddress())
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
    for (String key : headers.keys()) {
      if (!isAllowed(key)) {
        continue;
      }
      String value;
      if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
        value = getBinaryHeaderValue(headers, key);
      } else {
        value = getAsciiHeaderValue(headers, key);
      }
      httpReqBuilder.putHeaders(key.toLowerCase(Locale.ROOT), value);
    }
    reqBuilder.setHttp(httpReqBuilder);
    return reqBuilder.build();
  }

  private String getBinaryHeaderValue(Metadata headers, String key) {
    Iterable<byte[]> binaryValues =
        headers.getAll(Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER));
    List<String> base64Values = new ArrayList<>();
    for (byte[] value : binaryValues) {
      base64Values.add(BaseEncoding.base64().encode(value));
    }
    return String.join(",", base64Values);
  }

  private String getAsciiHeaderValue(Metadata headers, String key) {
    Iterable<String> stringValues =
        headers.getAll(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
    return String.join(",", stringValues);
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
