/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.internal;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import java.util.concurrent.Executor;

/**
 * Common utilities for GRPC call executors.
 */
final class CallExecutors {

  private CallExecutors() {}

  /**
   * Wraps an executor with safeguarding (serialization) if not already safeguarded.
   */
  static Executor safeguard(Executor executor) {
    // If we know that the executor is a direct executor, we don't need to wrap it with a
    // SerializingExecutor. This is purely for performance reasons.
    // See https://github.com/grpc/grpc-java/issues/368
    if (executor instanceof SerializingExecutor
        || executor instanceof SerializeReentrantCallsDirectExecutor) {
      return executor;
    }
    if (executor == directExecutor()) {
      return new SerializeReentrantCallsDirectExecutor();
    }
    return new SerializingExecutor(executor);
  }
}
