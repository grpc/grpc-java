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

import java.nio.ByteBuffer;

/**
 * Extension to an {@link java.io.OutputStream} or alike by adding methods that
 * allow writing directly to an underlying {@link ByteBuffer}
 */
public interface ByteBufferBacked {

  /**
   * If available, returns a {@link ByteBuffer} backing this writable
   * object whose position corresponds to this object's current
   * writing position and with at least {@code size} remaining bytes.
   *
   * @param size minimum required size
   * @return null if not supported or writable buffer of insufficient size
   */
  ByteBuffer getWritableBuffer(int size);

  /**
   * This must be called to notify that data has been written to the
   * buffer previously returned from {@link #getWritableBuffer(int)},
   * prior to calling any other methods.
   *
   * @param written number of bytes written
   */
  void bufferBytesWritten(int written);
}
