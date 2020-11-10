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

package io.grpc;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Interface for intercepting server calls by modifying the {@link ServerMethodDefinition} after
 * method lookup and before call dispatch. This provides a superset of the functionality offered by
 * {@link ServerInterceptor}.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/????")
@ThreadSafe
public interface ServerInterceptor2 {
  /**
   * Intercept and modify the {@link ServerMethodDefinition}.
   *
   * <p>The returned ServerMethodDefinition cannot be null and implementations must not throw.
   */
  <ReqT, RespT> ServerMethodDefinition<ReqT, RespT> interceptMethodDefinition(
      ServerMethodDefinition<ReqT, RespT> method);
}
