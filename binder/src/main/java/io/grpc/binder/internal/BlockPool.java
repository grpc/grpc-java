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

package io.grpc.binder.internal;

import io.grpc.internal.GrpcUtil;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Manages a pool of byte-array blocks.
 *
 * <p>Unfortunately, the Android Parcel api only allws us to read a block of N bytes when we have a
 * byte array of size N. This means we can't simply read into a large block and be done with it, we
 * need to allocate a new buffer specifically. Boo, Android.
 *
 * <p>When writing data though, we can use a fixed-size buffer, so when large messages are
 * split into standard-sized blocks, we only need a byte array allocation to read the last
 * block.
 *
 * <p>This class maintains a pool of blocks of standard size, but also provides smaller blocks when
 * requested. Currently, blocks of standard size are retained in the pool, when released, but we
 * could chose to change this strategy.
 */
final class BlockPool {

  /**
   * The size of each standard block. (Currently 16k)
   * The block size must be at least as large as the maximum header list size.
   */
  private static final int BLOCK_SIZE = Math.max(16 * 1024, GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE);

  /**
   * Maximum number of blocks to keep around. (Max 128k). This limit is a judgement call. 128k is
   * small enough that it shouldn't significantly affect the memory usage of a large app, but large
   * enough that it should to reduce allocation churn while gRPC is in use.
   */
  private static final int BLOCK_POOL_SIZE = 128 * 1024 / BLOCK_SIZE;

  /** A pool of byte arrays of standard size. We don't use any blocking methods of this instance. */
  private static final Queue<byte[]> blockPool = new LinkedBlockingQueue<>(BLOCK_POOL_SIZE);

  private BlockPool() {}

  /** Acquire a block of standard size. */
  static byte[] acquireBlock() {
    return acquireBlock(BLOCK_SIZE);
  }

  /** Acquire a block of the specified size. */
  static byte[] acquireBlock(int size) {
    if (size == BLOCK_SIZE) {
      byte[] block = blockPool.poll();
      if (block != null) {
        return block;
      }
    }
    return new byte[size];
  }

  /** Release a now-unused block. */
  static void releaseBlock(byte[] block) {
    if (block.length == BLOCK_SIZE) {
      blockPool.offer(block);
    }
  }
}
