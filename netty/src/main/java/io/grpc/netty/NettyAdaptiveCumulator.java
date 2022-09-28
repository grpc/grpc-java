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

package io.grpc.netty;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.handler.codec.ByteToMessageDecoder.Cumulator;

class NettyAdaptiveCumulator implements Cumulator {
  private final int composeMinSize;

  /**
   * "Adaptive" cumulator: cumulate {@link ByteBuf}s by dynamically switching between merge and
   * compose strategies.
   *
   * @param composeMinSize Determines the minimal size of the buffer that should be composed (added
   *                       as a new component of the {@link CompositeByteBuf}). If the total size
   *                       of the last component (tail) and the incoming buffer is below this value,
   *                       the incoming buffer is appended to the tail, and the new component is not
   *                       added.
   */
  NettyAdaptiveCumulator(int composeMinSize) {
    Preconditions.checkArgument(composeMinSize >= 0, "composeMinSize must be non-negative");
    this.composeMinSize = composeMinSize;
  }

  /**
   * "Adaptive" cumulator: cumulate {@link ByteBuf}s by dynamically switching between merge and
   * compose strategies.
   *
   * <p>This cumulator applies a heuristic to make a decision whether to track a reference to the
   * buffer with bytes received from the network stack in an array ("zero-copy"), or to merge into
   * the last component (the tail) by performing a memory copy.
   *
   * <p>It is necessary as a protection from a potential attack on the {@link
   * io.netty.handler.codec.ByteToMessageDecoder#COMPOSITE_CUMULATOR}. Consider a pathological case
   * when an attacker sends TCP packages containing a single byte of data, and forcing the cumulator
   * to track each one in a separate buffer. The cost is memory overhead for each buffer, and extra
   * compute to read the cumulation.
   *
   * <p>Implemented heuristic establishes a minimal threshold for the total size of the tail and
   * incoming buffer, below which they are merged. The sum of the tail and the incoming buffer is
   * used to avoid a case where attacker alternates the size of data packets to trick the cumulator
   * into always selecting compose strategy.
   *
   * <p>Merging strategy attempts to minimize unnecessary memory writes. When possible, it expands
   * the tail capacity and only copies the incoming buffer into available memory. Otherwise, when
   * both tail and the buffer must be copied, the tail is reallocated (or fully replaced) with a new
   * buffer of exponentially increasing capacity (bounded to {@link #composeMinSize}) to ensure
   * runtime {@code O(n^2)} is amortized to {@code O(n)}.
   */
  @Override
  @SuppressWarnings("ReferenceEquality")
  public final ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
    if (!cumulation.isReadable()) {
      cumulation.release();
      return in;
    }
    CompositeByteBuf composite = null;
    try {
      if (cumulation instanceof CompositeByteBuf && cumulation.refCnt() == 1) {
        composite = (CompositeByteBuf) cumulation;
        // Writer index must equal capacity if we are going to "write"
        // new components to the end
        if (composite.writerIndex() != composite.capacity()) {
          composite.capacity(composite.writerIndex());
        }
      } else {
        composite = alloc.compositeBuffer(Integer.MAX_VALUE)
            .addFlattenedComponents(true, cumulation);
      }
      addInput(alloc, composite, in);
      in = null;
      return composite;
    } finally {
      if (in != null) {
        // We must release if the ownership was not transferred as otherwise it may produce a leak
        in.release();
        // Also release any new buffer allocated if we're not returning it
        if (composite != null && composite != cumulation) {
          composite.release();
        }
      }
    }
  }

  @VisibleForTesting
  void addInput(ByteBufAllocator alloc, CompositeByteBuf composite, ByteBuf in) {
    if (shouldCompose(composite, in, composeMinSize)) {
      composite.addFlattenedComponents(true, in);
    } else {
      // The total size of the new data and the last component are below the threshold. Merge them.
      mergeWithCompositeTail(alloc, composite, in);
    }
  }

  @VisibleForTesting
  static boolean shouldCompose(CompositeByteBuf composite, ByteBuf in, int composeMinSize) {
    int componentCount = composite.numComponents();
    if (composite.numComponents() == 0) {
      return true;
    }
    int inputSize = in.readableBytes();
    int tailStart = composite.toByteIndex(componentCount - 1);
    int tailSize = composite.writerIndex() - tailStart;
    return tailSize + inputSize >= composeMinSize;
  }

  /**
   * Append the given {@link ByteBuf} {@code in} to {@link CompositeByteBuf} {@code composite} by
   * expanding or replacing the tail component of the {@link CompositeByteBuf}.
   *
   * <p>The goal is to prevent {@code O(n^2)} runtime in a pathological case, that forces copying
   * the tail component into a new buffer, for each incoming single-byte buffer. We append the new
   * bytes to the tail, when a write (or a fast write) is possible.
   *
   * <p>Otherwise, the tail is replaced with a new buffer, with the capacity increased enough to
   * achieve runtime amortization.
   *
   * <p>We assume that implementations of {@link ByteBufAllocator#calculateNewCapacity(int, int)},
   * are similar to {@link io.netty.buffer.AbstractByteBufAllocator#calculateNewCapacity(int, int)},
   * which doubles buffer capacity by normalizing it to the closest power of two. This assumption
   * is verified in unit tests for this method.
   */
  @VisibleForTesting
  static void mergeWithCompositeTail(
      ByteBufAllocator alloc, CompositeByteBuf composite, ByteBuf in) {
    int inputSize = in.readableBytes();
    int tailComponentIndex = composite.numComponents() - 1;
    int tailStart = composite.toByteIndex(tailComponentIndex);
    int tailSize = composite.writerIndex() - tailStart;
    int newTailSize = inputSize + tailSize;
    ByteBuf tail = composite.component(tailComponentIndex);
    ByteBuf newTail = null;
    try {
      if (tail.refCnt() == 1 && !tail.isReadOnly() && newTailSize <= tail.maxCapacity()) {
        // Ideal case: the tail isn't shared, and can be expanded to the required capacity.
        // Take ownership of the tail.
        newTail = tail.retain();

        // TODO(https://github.com/netty/netty/issues/12844): remove when we use Netty with
        //   the issue fixed.
        // In certain cases, removing the CompositeByteBuf component, and then adding it back
        // isn't idempotent. An example is provided in https://github.com/netty/netty/issues/12844.
        // This happens because the buffer returned by composite.component() has out-of-sync
        // indexes. Under the hood the CompositeByteBuf returns a duplicate() of the underlying
        // buffer, but doesn't set the indexes.
        //
        // To get the right indexes we use the fact that composite.internalComponent() returns
        // the slice() into the readable portion of the underlying buffer.
        // We use this implementation detail (internalComponent() returning a *SlicedByteBuf),
        // and combine it with the fact that SlicedByteBuf duplicates have their indexes
        // adjusted so they correspond to the to the readable portion of the slice.
        //
        // Hence composite.internalComponent().duplicate() returns a buffer with the
        // indexes that should've been on the composite.component() in the first place.
        // Until the issue is fixed, we manually adjust the indexes of the removed component.
        ByteBuf sliceDuplicate = composite.internalComponent(tailComponentIndex).duplicate();
        newTail.setIndex(sliceDuplicate.readerIndex(), sliceDuplicate.writerIndex());

        /*
         * The tail is a readable non-composite buffer, so writeBytes() handles everything for us.
         *
         * - ensureWritable() performs a fast resize when possible (f.e. PooledByteBuf simply
         *   updates its boundary to the end of consecutive memory run assigned to this buffer)
         * - when the required size doesn't fit into writableBytes(), a new buffer is
         *   allocated, and the capacity calculated with alloc.calculateNewCapacity()
         * - note that maxFastWritableBytes() would normally allow a fast expansion of PooledByteBuf
         *   is not called because CompositeByteBuf.component() returns a duplicate, wrapped buffer.
         *   Unwrapping buffers is unsafe, and potential benefit of fast writes may not be
         *   as pronounced because the capacity is doubled with each reallocation.
         */
        newTail.writeBytes(in);
      } else {
        // The tail is shared, or not expandable. Replace it with a new buffer of desired capacity.
        newTail = alloc.buffer(alloc.calculateNewCapacity(newTailSize, Integer.MAX_VALUE));
        newTail.setBytes(0, composite, tailStart, tailSize)
            .setBytes(tailSize, in, in.readerIndex(), inputSize)
            .writerIndex(newTailSize);
        in.readerIndex(in.writerIndex());
      }
      // Store readerIndex to avoid out of bounds writerIndex during component replacement.
      int prevReader = composite.readerIndex();
      // Remove the old tail, reset writer index.
      composite.removeComponent(tailComponentIndex).setIndex(0, tailStart);
      // Add back the new tail.
      composite.addFlattenedComponents(true, newTail);
      // New tail's ownership transferred to the composite buf.
      newTail = null;
      in.release();
      in = null;
      // Restore the reader. In case it fails we restore the reader after releasing/forgetting
      // the input and the new tail so that finally block can handles them properly.
      composite.readerIndex(prevReader);
    } finally {
      // Input buffer was merged with the tail.
      if (in != null) {
        in.release();
      }
      // If new tail's ownership isn't transferred to the composite buf.
      // Release it to prevent a leak.
      if (newTail != null) {
        newTail.release();
      }
    }
  }
}
