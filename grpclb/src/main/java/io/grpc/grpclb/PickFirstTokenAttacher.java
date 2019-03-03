/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc;

/**
 * Adds to {@link GrpclbClientLoadRecorder} and attach token to headers, based on the mapping
 * between server address and token.  This is only used in the PICK_FIRST mode.
 */
final class PickFirstTokenAttacher extends ClientStreamTracer.Factory {
  private final GrpclbClientLoadRecorder loadRecorder;
  private final Map<SocketAddress, String> tokens;

  PickFirstTokenAttacher(
      GrpclbClientLoadRecorder loadRecorder, Map<SocketAddress, String> tokens) {
    this.loadRecorder = checkNotNull(loadRecorder, "loadRecorder");
    this.tokens = checkNotNull(tokens, "tokens");
  }

  @Override
  public ClientStreamTracer newClientStreamTracer(
      ClientStreamTracer.StreamInfo info, Metadata headers) {
    Attributes transportAttrs = info.getTransportAttrs();
    SocketAddress addr = transportAttrs.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
    checkNotNull(addr, "Remote address is not available");
    String token = tokens.get(addr);
    checkNotNull(token, "Token not found for %s", addr);
    headers.discardAll(GrpclbConstants.TOKEN_METADATA_KEY);
    headers.put(GrpclbConstants.TOKEN_METADATA_KEY, token);
    return loadRecorder.newClientStreamTracer(info, headers);
  }
}
