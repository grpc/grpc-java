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

import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;

/** The EvaluateArgs class holds evaluate arguments used in CEL Evaluation Engine. */
public class EvaluateArgs {
  private Metadata headers;
  private ServerCall<?, ?> call;

  /**
   * Creates a new evaluate argument using the input {@code headers} for resolving headers
   * and {@code call} for resolving gRPC call.
   */
  public EvaluateArgs(Metadata headers, ServerCall<?, ?> call) {
    this.headers = headers;
    this.call = call;
  }

  /** Return the headers. */
  public Metadata getHeaders() {
    return headers;
  }

  /** Return the gRPC call. */
  public ServerCall<?, ?> getCall() {
    return call;
  }

  /** Extract the request.url_path field. */
  public String getRequestUrlPath() {
    String requestUrlPath = this.getCall().getMethodDescriptor().getFullMethodName();
    return requestUrlPath;
  }

  /** Extract the request.host field. */
  public String getRequestHost() {
    String requestHost = this.getCall().getAuthority();
    return requestHost;
  }

  /** Extract the request.method field. */
  public String getRequestMethod() {
    // TODO(@zhenlian): confirm extraction for request.method.
    String requestMethod = this.getCall().getMethodDescriptor().getServiceName();
    return requestMethod;
  }

  /** Extract the request.headers field. */
  public Metadata getRequestHeaders() {
    // TODO(@zhenlian): convert request.headers from Metadata to a String Map.
    Metadata requestHeaders = this.getHeaders();
    return requestHeaders;
  }

  /** Extract the source.address field. */
  public String getSourceAddress() {
    String sourceAddress = 
        this.getCall().getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR).toString();
    return sourceAddress;
  }

  /** Extract the source.port field. */
  public int getSourcePort() {
    // TODO(@zhenlian): fill out extraction for source.port.
    int sourcePort = 0;
    return sourcePort;
  }

  /** Extract the destination.address field. */
  public String getDestinationAddress() {
    String destinationAddress = 
        this.getCall().getAttributes().get(Grpc.TRANSPORT_ATTR_LOCAL_ADDR).toString();
    return destinationAddress;
  }

  /** Extract the destination.port field. */
  public int getDestinationPort() {
    // TODO(@zhenlian): fill out extraction for destination.port.
    int destinationPort = 0;
    return destinationPort;
  }

  /** Extract the connection.uri_san_peer_certificate field. */
  public String getConnectionUriSanPeerCertificate() {
    // TODO(@zhenlian): fill out extraction for connection.uri_san_peer_certificate.
    String connectionUriSanPeerCertificate = "placeholder";
    return connectionUriSanPeerCertificate;
  }

  /** Extract the source.principal field. */
  public String getSourcePrincipal() {
    // TODO(@zhenlian): fill out extraction for source.principal.
    String sourcePrincipal = "placeholder";
    return sourcePrincipal;
  }
}
