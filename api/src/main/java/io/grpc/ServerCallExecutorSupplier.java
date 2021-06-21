/*
 * Copyright 2021 The gRPC Authors
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

import java.util.concurrent.Executor;
import javax.annotation.Nullable;

/**
 * Defines what executor handles the server call, based on each RPC call information at runtime.
 * */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8274")
public interface ServerCallExecutorSupplier {

  /**
   * Returns an executor to handle the server call.
   * It should never throw. It should return null to fallback to the default executor.
   * */
  @Nullable
  <ReqT, RespT> Executor getExecutor(ServerCall<ReqT, RespT> call, Metadata metadata);
}
