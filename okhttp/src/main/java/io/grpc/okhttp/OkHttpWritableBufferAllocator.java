/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc.okhttp;

import io.grpc.internal.WritableBuffer;
import io.grpc.internal.WritableBufferAllocator;
import okio.Buffer;
import okio.Segment;

/**
 * The default allocator for {@link OkHttpWritableBuffer}s used by the OkHttp transport. OkHttp
 * cannot receive buffers larger than the max DATA frame size - 1 so we must set an upper bound on
 * the allocated buffer size here.
 */
class OkHttpWritableBufferAllocator implements WritableBufferAllocator {

  // Set the maximum buffer size to 1MB
  private static final int MAX_BUFFER = 1024 * 1024;

  /**
   * Construct a new instance.
   */
  OkHttpWritableBufferAllocator() {
  }

  /**
   * For OkHttp we will often return a buffer smaller than the requested capacity as this is the
   * mechanism for chunking a large GRPC message over many DATA frames.
   */
  @Override
  public WritableBuffer allocate(int capacityHint) {
    // okio buffer uses fixed size Segments, round capacityHint up
    return allocateKnownLength((capacityHint + Segment.SIZE - 1) / Segment.SIZE * Segment.SIZE);
  }

  @Override
  public WritableBuffer allocateKnownLength(int capacityHint) {
    capacityHint = Math.min(MAX_BUFFER, capacityHint);
    return new OkHttpWritableBuffer(new Buffer(), capacityHint);
  }
}
