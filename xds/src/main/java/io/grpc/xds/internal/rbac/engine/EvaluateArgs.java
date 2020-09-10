/*
 * Copyright 2020 The gRPC Authors
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

import com.google.common.collect.ImmutableMap;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;

/** The EvaluateArgs class holds evaluate arguments used in CEL-based Authorization Engine. */
public class EvaluateArgs {
  private final Metadata headers;
  private final ServerCall<?, ?> call;

  /**
   * Creates a new EvaluateArgs using the input {@code headers} for resolving headers
   * and {@code call} for resolving gRPC call.
   */
  public EvaluateArgs(Metadata headers, ServerCall<?, ?> call) {
    this.headers = headers;
    this.call = call;
  }

  /** Extract the request.url_path field. */
  protected String getRequestUrlPath() {
    String requestUrlPath = this.call.getMethodDescriptor().getFullMethodName();
    return requestUrlPath;
  }

  /** Extract the request.host field. */
  protected String getRequestHost() {
    String requestHost = this.call.getAuthority();
    return requestHost;
  }

  /** Extract the request.method field. */
  protected String getRequestMethod() {
    // TODO(@zhenlian): confirm extraction for request.method.
    String requestMethod = this.call.getMethodDescriptor().getServiceName();
    return requestMethod;
  }

  /** Extract the request.headers field. */
  protected Metadata getRequestHeaders() {
    // TODO(@zhenlian): convert request.headers from Metadata to a String Map.
    Metadata requestHeaders = this.headers;
    return requestHeaders;
  }

  /** Extract the source.address field. */
  protected String getSourceAddress() {
    String sourceAddress = 
        this.call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR).toString();
    return sourceAddress;
  }

  /** Extract the source.port field. */
  protected int getSourcePort() {
    // TODO(@zhenlian): fill out extraction for source.port.
    int sourcePort = 0;
    return sourcePort;
  }

  /** Extract the destination.address field. */
  protected String getDestinationAddress() {
    String destinationAddress = 
        this.call.getAttributes().get(Grpc.TRANSPORT_ATTR_LOCAL_ADDR).toString();
    return destinationAddress;
  }

  /** Extract the destination.port field. */
  protected int getDestinationPort() {
    // TODO(@zhenlian): fill out extraction for destination.port.
    int destinationPort = 0;
    return destinationPort;
  }

  /** Extract the connection.uri_san_peer_certificate field. */
  protected String getConnectionUriSanPeerCertificate() {
    // TODO(@zhenlian): fill out extraction for connection.uri_san_peer_certificate.
    String connectionUriSanPeerCertificate = "placeholder";
    return connectionUriSanPeerCertificate;
  }

  /** Extract the source.principal field. */
  protected String getSourcePrincipal() {
    // TODO(@zhenlian): fill out extraction for source.principal.
    String sourcePrincipal = "placeholder";
    return sourcePrincipal;
  }

  /** Extract Envoy Attributes from EvaluateArgs. */
  public ImmutableMap<String, Object> generateEnvoyAttributes() {
    ImmutableMap<String, Object> attributes = ImmutableMap.<String, Object>builder()
        .put("request.url_path", this.getRequestUrlPath())
        .put("request.host", this.getRequestHost())
        .put("request.method", this.getRequestMethod())
        .put("request.headers", this.getRequestHeaders())
        .put("source.address", this.getSourceAddress())
        .put("source.port", this.getSourcePort())
        .put("destination.address", this.getDestinationAddress())
        .put("destination.port", this.getDestinationPort())
        .put("connection.uri_san_peer_certificate", 
            this.getConnectionUriSanPeerCertificate())
        .put("source.principal", this.getSourcePrincipal())
        .build();
    return attributes;
  }
}
