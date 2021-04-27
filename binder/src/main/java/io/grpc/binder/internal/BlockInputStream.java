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

import com.google.common.primitives.Ints;
import io.grpc.Drainable;
import io.grpc.KnownLength;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * A simple InputStream from a 2-dimensional byte array.
 *
 * Used to provide message data from incoming blocks of data. It is assumed that
 * all byte arrays passed in the constructor of this this class are owned by the new
 * instance.
 *
 * This also assumes byte arrays are created by the BlockPool class, and should
 * be returned to it when this class is closed.
 */
@NotThreadSafe
final class BlockInputStream extends InputStream implements KnownLength, Drainable {

  @Nullable
  private byte[][] blocks;
  @Nullable
  private byte[] currentBlock;
  private int blockIndex;
  private int blockOffset;
  private int available;
  private boolean closed;

  /**
   * Creates a new stream with a single block.
   *
   * @param block The single byte array block, ownership of which is
   * passed to this instance.
   */
  BlockInputStream(byte[] block) {
    this.blocks = null;
    currentBlock = block.length > 0 ? block : null;
    available = block.length;
  }

  /**
   * Creates a new stream from a sequence of blocks.
   *
   * @param blocks A two dimensional byte array containing the data. Ownership
   * of all blocks is passed to this instance.
   * @param available The number of bytes available in total. This may be
   * less than (but never more than) the total size of all byte arrays in blocks.
   */
  BlockInputStream(byte[][] blocks, int available) {
    this.blocks = blocks;
    this.available = available;
    if (blocks.length > 0) {
      currentBlock = blocks[0];
    }
  }

  @Override
  public int read() throws IOException {
    if (currentBlock != null) {
      int res = currentBlock[blockOffset++];
      available -= 1;
      if (blockOffset == currentBlock.length) {
        nextBlock();
      }
      return res;
    }
    return -1;
  }

  @Override
  public int read(byte[] data, int off, int len) throws IOException {
    int stillToRead = len;
    while (currentBlock != null) {
      int n = Ints.min(stillToRead, currentBlock.length - blockOffset, available);
      System.arraycopy(currentBlock, blockOffset, data, off, n);
      off += n;
      stillToRead -= n;
      available -= n;
      if (stillToRead == 0) {
        blockOffset += n;
        if (blockOffset == currentBlock.length) {
          nextBlock();
        }
        break;
      } else {
        nextBlock();
      }
    }
    int bytesRead = len - stillToRead;
    if (bytesRead > 0 || available > 0) {
      return bytesRead;
    }
    return -1;
  }

  private void nextBlock() {
    blockIndex += 1;
    blockOffset = 0;
    if (blocks != null && blockIndex < blocks.length) {
      currentBlock = blocks[blockIndex];
    } else {
      currentBlock = null;
    }
  }

  @Override
  public int available() {
    return available;
  }

  @Override
  public int drainTo(OutputStream output) throws IOException {
    int res = available;
    while (available > 0) {
      int n = Math.min(currentBlock.length - blockOffset, available);
      output.write(currentBlock, blockOffset, n);
      available -= n;
      nextBlock();
    }
    return res;
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      if (blocks != null) {
        for (byte[] block : blocks) {
          BlockPool.releaseBlock(block);
        }
      } else if (currentBlock != null) {
        BlockPool.releaseBlock(currentBlock);
      }
      currentBlock = null;
      blocks = null;
    }
  }
}
