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
 * An {@link java.io.InputStream} or alike that supports the operation of reading its content into
 * {@link ByteBuffer}s.
 *
 * <p>Usually used for implementations (directly or indirectly) backed by {@link ByteBuffer}s so
 * that read operations avoid making an extra copy by returning {@link ByteBuffer}s sharing content
 * with the backing {@link ByteBuffer}s.
 */
public interface ByteBufferReadable {


  /**
   * Reads up to a total of {@code length} bytes as {@link ByteBuffer}s.
   *
   * @param length the maximum number of bytes to be read.
   * @return {@link ByteBuffer}s that contains the bytes being read or {@code null} if no more
   *     bytes can be read.
   */
  @Nullable
  Iterable<ByteBuffer> readByteBuffers(int length);
}
