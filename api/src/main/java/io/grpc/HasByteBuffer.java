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
import javax.annotation.Nullable;

/**
 * Extension to an {@link java.io.InputStream} whose content can be accessed as {@link
 * ByteBuffer}s.
 *
 * <p>This can be used for optimizing the case for the consumer of a {@link ByteBuffer}-backed
 * input stream supports efficient reading from {@link ByteBuffer}s directly. This turns the reader
 * interface from an {@link java.io.InputStream} to {@link ByteBuffer}s, without copying the
 * content to a byte array and read from it.
 */
public interface HasByteBuffer {

  /**
   * Indicates whether or not {@link #getByteBuffer} operation is supported.
   */
  boolean byteBufferSupported();

  /**
   * Gets a {@link ByteBuffer} containing some bytes of the content next to be read, or {@code
   * null} if has reached end of the content. The number of bytes contained in the returned buffer
   * is implementation specific. Calling this method does not change the position of the input
   * stream. The returned buffer's content should not be modified, but the position, limit, and
   * mark may be changed. Operations for changing the position, limit, and mark of the returned
   * buffer does not affect the position, limit, and mark of this input stream. This is an optional
   * method, so callers should first check {@link #byteBufferSupported}.
   *
   * @throws UnsupportedOperationException if this operation is not supported.
   */
  @Nullable
  ByteBuffer getByteBuffer();
}
