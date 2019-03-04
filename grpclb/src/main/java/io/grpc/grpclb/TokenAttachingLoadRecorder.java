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

package io.grpc.grpclb;

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.Attributes;
import io.grpc.ClientStreamTracer;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.internal.TimeProvider;

/**
 * A {@link GrpclbClientLoadRecorder} that also retrieve tokens from transport attributes and attach
 * them to headers.  This is only used in the PICK_FIRST mode.
 */
final class TokenAttachingLoadRecorder extends GrpclbClientLoadRecorder {
  TokenAttachingLoadRecorder(TimeProvider time) {
    super(time);
  }

  @Override
  public ClientStreamTracer newClientStreamTracer(
      ClientStreamTracer.StreamInfo info, Metadata headers) {
    Attributes transportAttrs = checkNotNull(info.getTransportAttrs(), "transportAttrs");
    Attributes eagAttrs =
        checkNotNull(transportAttrs.get(Grpc.TRANSPORT_ATTR_CLIENT_EAG_ATTRS), "eagAttrs");
    String token = eagAttrs.get(GrpclbConstants.TOKEN_ATTRIBUTE_KEY);
    if (token != null) {
      headers.discardAll(GrpclbConstants.TOKEN_METADATA_KEY);
      headers.put(GrpclbConstants.TOKEN_METADATA_KEY, token);
    }
    return super.newClientStreamTracer(info, headers);
  }
}
