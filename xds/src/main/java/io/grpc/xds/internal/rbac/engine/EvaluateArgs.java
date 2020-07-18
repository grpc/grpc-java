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
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/** The EvaluateArgs class holds evaluate arguments used in CEL Evaluation Engine. */
public class EvaluateArgs<ReqT, RespT> {
  private Metadata headers;
  private ServerCall<ReqT, RespT> call;

  /**
   * Creates a new evaluate argument using the input {@code headers} for resolving headers
   * and {@code call} for resolving gRPC call.
   */
  public EvaluateArgs(Metadata headers, ServerCall<ReqT, RespT> call) {
    this.headers = headers;
    this.call = call;
  }

  /** Returns the headers. */
  public Metadata getHeaders() {
    return headers;
  }

  /** Returns the gRPC call. */
  public ServerCall<ReqT, RespT> getCall() {
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

  // Uncertain
  /** Extract the request.method field. */
  public String getRequestMethod() {
    String requestMethod = this.getCall().getMethodDescriptor().getServiceName();
    return requestMethod;
  }

  // Uncertain
  /** Extract the request.headers field. */
  public Metadata getRequestHeaders() {
    Metadata requestHeaders = this.getHeaders();
    return requestHeaders;
  }

  /** Extract the source.address field. */
  public String getSourceAddress() {
    String sourceAddress = 
        this.getCall().getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR).toString();
    return sourceAddress;
  }

  // TBD
  /** Extract the source.port field. */
  public int getSourcePort() {
    int sourcePort = 0;
    return sourcePort;
  }

  /** Extract the destination.address field. */
  public String getDestinationAddress() {
    String destinationAddress = 
        this.getCall().getAttributes().get(Grpc.TRANSPORT_ATTR_LOCAL_ADDR).toString();
    return destinationAddress;
  }

  // TBD
  /** Extract the destination.port field. */
  public int getDestinationPort() {
    int destinationPort = 0;
    return destinationPort;
  }

  // TBD
  /** Extract the connection.uri_san_peer_certificate field. */
  public String getConnectionUriSanPeerCertificate() {
    SSLSession sslSession = 
        this.getCall().getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
    @SuppressWarnings("unused")
    List<Certificate> certificates; 
    try {
      certificates = Arrays.asList(sslSession.getPeerCertificates());
    } catch (SSLPeerUnverifiedException e) {
      return null;
    }
    String connectionUriSanPeerCertificate = "placeholder";
    return connectionUriSanPeerCertificate;
  }
}
