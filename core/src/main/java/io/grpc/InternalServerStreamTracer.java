/*
 * Copyright 2018, gRPC Authors All rights reserved.
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

import io.grpc.ServerStreamTracer.ServerCallInfo;
import javax.annotation.Nullable;

/**
 * Accessor for {@link ServerStreamTracer}.
 */
@Internal
public class InternalServerStreamTracer {
  public static <ReqT, RespT> ServerCallInfo<ReqT, RespT> createServerCallInfo(
      MethodDescriptor<ReqT, RespT> methodDescriptor,
      Attributes attributes,
      @Nullable String authority) {
    return new ServerCallInfo<ReqT, RespT>(methodDescriptor, attributes, authority);
  }

  private InternalServerStreamTracer() {
  }
}
