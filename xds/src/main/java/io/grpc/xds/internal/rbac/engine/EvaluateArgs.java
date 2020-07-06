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
}
