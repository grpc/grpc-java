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
import java.util.List;
import javax.annotation.Nullable;

/**
 * A logical representation of bytes with the responsibility of managing the resource lifecycle
 * used to hold the data. Usually used for transferring data by directly hand over the ownership
 * of the backing resources.
 */
public abstract class ManagedBytes {

  /**
   * Returns the data in a list of {@link ByteBuffer}s.
   */
  public abstract List<ByteBuffer> asByteBuffers();

  /**
   * Release the usage of the data. The underlying resource used to hold the data may be released
   * and no more access should be attempted.
   */
  public void release() {
    // no-op
  }

  /**
   * A readable data source that supports the operation of reading its content into
   * {@link ManagedBytes}.
   */
  public interface ManagedBytesReadable {

    /**
     * Reads up to a total of {@code length} bytes as {@link ManagedBytes}.
     *
     * @param length the maximum number of bytes to be read.
     * @return {@link ManagedBytes} that contains the bytes being read or {@code null}
     *     if no more bytes can be read.
     */
    @Nullable
    ManagedBytes readManagedBytes(int length);
  }
}
