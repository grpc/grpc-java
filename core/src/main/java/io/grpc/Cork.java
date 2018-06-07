/*
 * Copyright 2018 The gRPC Authors
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

import java.io.Closeable;

/**
 * Defers flushing the messages written using {@link ClientCall#sendMessage(Object)} and
 * {@link ServerCall#sendMessage(Object)} transport until the write buffer is full.
 * The amount of data buffered while corked is dependent on the transport implementation
 * so users of this API should not make strong assumptions about how long data is buffered while
 * corked.
 *
 * <p>Corking can provide throughput gains by allowing a transport to perform fewer writes to
 * the underlying network.
 */
public interface Cork extends Closeable {

  /**
   * No-Op implementation of {@link Cork} interface.
   */
  class NoopCork implements Cork {

    @Override
    public void close() {
      // noop
    }
  }
}
