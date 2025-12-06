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

import com.google.auto.value.AutoValue;
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
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * Interface for building external authorization check requests.
 */
public interface CheckRequestBuilder {

  /**
   * A factory for creating {@link CheckRequestBuilder} instances.
   */
  @FunctionalInterface
  interface Factory {
    /**
     * Creates a new instance of the CheckRequestBuilder.
     *
     * @param config The external authorization configuration.
     * @param certificateProvider The provider for certificate information.
     * @return A new CheckRequestBuilder instance.
     */
    CheckRequestBuilder create(ExtAuthzConfig config,
        ExtAuthzCertificateProvider certificateProvider);
  }

  /** The default factory for creating {@link CheckRequestBuilder} instances. */
  Factory INSTANCE = CheckRequestBuilderImpl::new;

  /**
   * Builds a CheckRequest for a server-side call.
   *
   * @param serverCall The server call.
   * @param headers The request headers.
   * @param requestTime The time of the request.
   * @return A new CheckRequest.
   */
  CheckRequest buildRequest(ServerCall<?, ?> serverCall, Metadata headers, Timestamp requestTime);

  /**
   * Builds a CheckRequest for a client-side call.
   *
   * @param methodDescriptor The method descriptor of the call.
   * @param headers The request headers.
   * @param requestTime The time of the request.
   * @return A new CheckRequest.
   */
  CheckRequest buildRequest(MethodDescriptor<?, ?> methodDescriptor, Metadata headers,
      Timestamp requestTime);

  /**
   * Implementation of the CheckRequestBuilder interface.
   */
  final class CheckRequestBuilderImpl implements CheckRequestBuilder {
    private static final Logger logger = Logger.getLogger(CheckRequestBuilderImpl.class.getName());

    private static final String METHOD = "POST";
    private static final String PROTOCOL = "HTTP/2";
    private static final long SIZE = -1;

    private final ExtAuthzConfig config;
    private final ExtAuthzCertificateProvider certificateProvider;

    CheckRequestBuilderImpl(ExtAuthzConfig config,
        ExtAuthzCertificateProvider certificateProvider) {
      this.config = config;
      this.certificateProvider = certificateProvider;
    }

    @Override
    public CheckRequest buildRequest(MethodDescriptor<?, ?> methodDescriptor, Metadata headers,
        Timestamp requestTime) {
      return build(CheckRequestParams.builder().setMethodDescriptor(methodDescriptor)
          .setHeaders(headers).setRequestTime(requestTime).build());
    }

    @Override
    public CheckRequest buildRequest(ServerCall<?, ?> serverCall, Metadata headers,
        Timestamp requestTime) {
      CheckRequestParams.Builder paramsBuilder =
          CheckRequestParams.builder().setMethodDescriptor(serverCall.getMethodDescriptor())
              .setHeaders(headers).setRequestTime(requestTime);
      java.net.SocketAddress localAddress =
          serverCall.getAttributes().get(Grpc.TRANSPORT_ATTR_LOCAL_ADDR);
      if (localAddress != null) {
        paramsBuilder.setLocalAddress(localAddress);
      }
      java.net.SocketAddress remoteAddress =
          serverCall.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
      if (remoteAddress != null) {
        paramsBuilder.setRemoteAddress(remoteAddress);
      }
      SSLSession sslSession = serverCall.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
      if (sslSession != null) {
        paramsBuilder.setSslSession(sslSession);
      }
      return build(paramsBuilder.build());
    }

    private CheckRequest build(CheckRequestParams params) {
      AttributeContext.Builder attrBuilder = AttributeContext.newBuilder();
      if (params.remoteAddress().isPresent()) {
        attrBuilder.setSource(buildSource(params.remoteAddress().get(), params.sslSession()));
      }
      if (params.localAddress().isPresent()) {
        attrBuilder
            .setDestination(buildDestination(params.localAddress().get(), params.sslSession()));
      }
      attrBuilder.setRequest(buildAttributeRequest(params.headers(),
          params.methodDescriptor().getFullMethodName(), params.requestTime()));
      return CheckRequest.newBuilder().setAttributes(attrBuilder).build();
    }

    private AttributeContext.Peer buildSource(java.net.SocketAddress socketAddress,
        Optional<SSLSession> sslSession) {
      AttributeContext.Peer.Builder peerBuilder = buildPeer(socketAddress).toBuilder();
      if (sslSession.isPresent()) {
        try {
          Certificate[] certs = sslSession.get().getPeerCertificates();
          if (certs != null && certs.length > 0 && certs[0] instanceof X509Certificate) {
            X509Certificate cert = (X509Certificate) certs[0];
            peerBuilder.setPrincipal(certificateProvider.getPrincipal(cert));
            if (config.includePeerCertificate()) {
              try {
                peerBuilder.setCertificate(certificateProvider.getUrlPemEncodedCertificate(cert));
              } catch (UnsupportedEncodingException | CertificateEncodingException e) {
                logger.log(Level.WARNING,
                    "Error encoding peer certificate. "
                        + "This is not expected, but if it happens, the certificate should not "
                        + "be set according to the spec.",
                    e);
              }
            }
          }
        } catch (SSLPeerUnverifiedException e) {
          logger.log(Level.FINE,
              "Peer is not authenticated. "
                  + "This is expected, principal and certificate should not be set "
                  + "according to the spec.",
              e);
        }
      }
      return peerBuilder.build();
    }

    private AttributeContext.Peer buildDestination(java.net.SocketAddress socketAddress,
        Optional<SSLSession> sslSession) {
      AttributeContext.Peer.Builder peerBuilder = buildPeer(socketAddress).toBuilder();
      if (sslSession.isPresent()) {
        Certificate[] certs = sslSession.get().getLocalCertificates();
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
        peerBuilder.setAddress(Address.newBuilder()
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
      AttributeContext.HttpRequest.Builder httpReqBuilder =
          AttributeContext.HttpRequest.newBuilder();
      httpReqBuilder.setPath(fullMethodName);
      httpReqBuilder.setMethod(METHOD);
      httpReqBuilder.setProtocol(PROTOCOL);
      httpReqBuilder.setSize(SIZE);
      for (String key : headers.keys()) {
        if (!isAllowed(key)) {
          continue;
        }
        Optional<String> value;
        if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
          value = getBinaryHeaderValue(headers, key);
        } else {
          value = getAsciiHeaderValue(headers, key);
        }
        value.ifPresent(
            headerValue -> httpReqBuilder.putHeaders(key.toLowerCase(Locale.ROOT), headerValue));
      }
      reqBuilder.setHttp(httpReqBuilder);
      return reqBuilder.build();
    }

    private Optional<String> getBinaryHeaderValue(Metadata headers, String key) {
      Iterable<byte[]> binaryValues =
          headers.getAll(Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER));
      if (binaryValues == null) {
        // Unreachable code, since we iterate over the keys. Exists for defensive programming.
        return Optional.empty();
      }
      List<String> base64Values = new ArrayList<>();
      for (byte[] value : binaryValues) {
        base64Values.add(BaseEncoding.base64().encode(value));
      }
      return Optional.of(String.join(",", base64Values));
    }

    private Optional<String> getAsciiHeaderValue(Metadata headers, String key) {
      Iterable<String> stringValues =
          headers.getAll(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
      if (stringValues == null) {
        // Unreachable code, since we iterate over the keys. Exists for defensive programming.
        return Optional.empty();
      }
      return Optional.of(String.join(",", stringValues));
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

    @AutoValue
    abstract static class CheckRequestParams {
      abstract Metadata headers();

      abstract MethodDescriptor<?, ?> methodDescriptor();

      abstract Timestamp requestTime();

      abstract Optional<java.net.SocketAddress> localAddress();

      abstract Optional<java.net.SocketAddress> remoteAddress();

      abstract Optional<SSLSession> sslSession();

      static Builder builder() {
        Builder builder =
            new AutoValue_CheckRequestBuilder_CheckRequestBuilderImpl_CheckRequestParams.Builder();
        return builder;
      }

      @AutoValue.Builder
      abstract static class Builder {
        abstract Builder setHeaders(Metadata headers);

        abstract Builder setMethodDescriptor(MethodDescriptor<?, ?> method);

        abstract Builder setRequestTime(Timestamp time);

        abstract Builder setLocalAddress(java.net.SocketAddress localAddress);

        abstract Builder setRemoteAddress(java.net.SocketAddress remoteAddress);

        abstract Builder setSslSession(SSLSession sslSession);

        abstract CheckRequestParams build();
      }
    }
  }
}
