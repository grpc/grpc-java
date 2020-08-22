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

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

/**
 * Extension to an {@link java.io.InputStream} or alike by adding a method that
 * allows transferring directly to an underlying {@link ByteBuffer}
 */
public interface BufferDrainable {

  /**
   * Transfers all content to a target {@link ByteBuffer}
   *
   * @param dest the destination buffer to receive the bytes.
   * @throws BufferOverflowException if destination buffer has insufficient remaining bytes
   */
  int drainTo(ByteBuffer dest) throws IOException;
}
