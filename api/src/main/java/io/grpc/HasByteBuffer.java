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
// TODO(chengyuanzhang): add ExperimentalApi annotation.
public interface HasByteBuffer {

  /**
   * Gets a {@link ByteBuffer} containing up to {@code length} bytes of the content, or {@code
   * null} if has reached end of the content.
   * @param length the maximum number of bytes to contain in returned {@link ByteBuffer}.
   */
  @Nullable
  ByteBuffer getByteBuffer(int length);
}
