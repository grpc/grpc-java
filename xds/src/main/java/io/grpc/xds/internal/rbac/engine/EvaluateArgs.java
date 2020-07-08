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

package io.grpc.xds.internal;

import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;

/** The EvaluateArgs class holds evaluate arguments used in Cel Evaluation Engine. */
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

  // TBD
  /** Extract the RequestUrlPath field. */
  public String getRequestUrlPath() throws IllegalArgumentException {
    String requestUrlPath = "placeholder";
    if (requestUrlPath == null || requestUrlPath.length() == 0) {
      throw new IllegalArgumentException("RequestUrlPath field is not found. ");
    }
    return requestUrlPath;
  }

  /** Extract the RequestHost field. */
  public String getRequestHost() throws IllegalArgumentException {
    String requestHost = this.getCall().getMethodDescriptor().getServiceName();
    if (requestHost == null || requestHost.length() == 0) {
      throw new IllegalArgumentException("RequestHost field is not found. ");
    }
    return requestHost;
  }

  /** Extract the RequestMethod field. */
  public String getRequestMethod() throws IllegalArgumentException {
    String requestMethod = this.getCall().getMethodDescriptor().getFullMethodName();
    if (requestMethod == null || requestMethod.length() == 0) {
      throw new IllegalArgumentException("RequestMethod field is not found. ");
    }
    return requestMethod;
  }

  /** Extract the RequestHeaders field. */
  public Metadata getRequestHeaders() throws IllegalArgumentException {
    Metadata requestHeaders = this.getHeaders();
    if (requestHeaders == null) {
      throw new IllegalArgumentException("RequestHeaders field is not found. ");
    }
    return requestHeaders;
  }

  /** Extract the SourceAddress field. */
  public String getSourceAddress() throws IllegalArgumentException {
    String sourceAddress = this.getCall().getAttributes()
        .get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR).toString();
    if (sourceAddress == null || sourceAddress.length() == 0) {
      throw new IllegalArgumentException("SourceAddress field is not found. ");
    }
    return sourceAddress;
  }

  // TBD
  /** Extract the SourcePort field. */
  public int getSourcePort() throws IllegalArgumentException {
    int sourcePort = 0;
    if (sourcePort < 0) {
      throw new IllegalArgumentException("SourcePort field is not found. ");
    }
    return sourcePort;
  }

  /** Extract the DestinationAddress field. */
  public String getDestinationAddress() throws IllegalArgumentException {
    String destinationAddress = this.getCall().getAttributes()
        .get(Grpc.TRANSPORT_ATTR_LOCAL_ADDR).toString();
    if (destinationAddress == null || destinationAddress.length() == 0) {
      throw new IllegalArgumentException("DestinationAddress field is not found. ");
    }
    return destinationAddress;
  }

  // TBD
  /** Extract the DestinationPort field. */
  public int getDestinationPort() throws IllegalArgumentException {
    int destinationPort = 0;
    if (destinationPort < 0) {
      throw new IllegalArgumentException("DestinationPort field is not found. ");
    }
    return destinationPort;
  }

  /** Extract the ConnectionRequestedServerName field. */
  public String getConnectionRequestedServerName() throws IllegalArgumentException {
    String connectionRequestedServerName = this.getCall().getAuthority();
    if (connectionRequestedServerName == null || connectionRequestedServerName.length() == 0) {
      throw new IllegalArgumentException("ConnectionRequestedServerName field is not found. ");
    }
    return connectionRequestedServerName;
  }

  // TBD
  /** Extract the ConnectionUriSanPeerCertificate field. */
  public String getConnectionUriSanPeerCertificate() throws IllegalArgumentException {
    String connectionUriSanPeerCertificate = "placeholder";
    if (connectionUriSanPeerCertificate == null || connectionUriSanPeerCertificate.length() == 0) {
      throw new IllegalArgumentException("ConnectionUriSanPeerCertificate field is not found. ");
    }
    return connectionUriSanPeerCertificate;
  }
}
