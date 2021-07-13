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

/** API for tracing retry events. */
@Internal
public interface InternalRetryTracer {
  /** A CallOptions Key to populate the InternalRetryTracer. */
  CallOptions.Key<InternalRetryTracer> KEY = CallOptions.Key.create("io.grpc.InternalRetryTracer");

  /** Determines if a failed attempt can be finally retried. */
  boolean shouldRetry();

  /** The overall RPC is committed. */
  void commit();
}
