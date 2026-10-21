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

package io.grpc.internal;

/**
 * A {@code Deframer} that optimizations by taking over part of the thread safety.
 */
public interface ThreadOptimizedDeframer extends Deframer {
  /**
   * Behaves like {@link Deframer#request(int)} except it can be called from any thread. Must not
   * throw exceptions in case of deframer error.
   */
  @Override
  void request(int numMessages);
}
