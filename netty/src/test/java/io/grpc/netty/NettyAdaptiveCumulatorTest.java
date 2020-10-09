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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.TruthJUnit.assume;
import static io.netty.util.CharsetUtil.US_ASCII;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.util.Collection;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Enclosed.class)
public class NettyAdaptiveCumulatorTest {
  // Represent data as immutable ASCII Strings for easy and readable ByteBuf equality assertions.
  private static final String DATA_INITIAL = "0123";
  private static final String DATA_INCOMING = "456789";
  private static final String DATA_CUMULATED = "0123456789";

  private static Collection<Object[]> cartesianProductParams(List<?>... lists) {
    return Lists.transform(Lists.cartesianProduct(lists), new Function<List<Object>, Object[]>() {
      @Override public Object[] apply(List<Object> input) {
        return input.toArray();
      }
    });
  }

  @RunWith(JUnit4.class)
  public static class CumulateTests {
    private static final ByteBufAllocator alloc = new UnpooledByteBufAllocator(false);
    private NettyAdaptiveCumulator cumulator;

    // Buffers for testing
    private ByteBuf contiguous = ByteBufUtil.writeAscii(alloc, DATA_INITIAL);
    private CompositeByteBuf composite = alloc.compositeBuffer().addComponent(true, contiguous);
    private ByteBuf in = ByteBufUtil.writeAscii(alloc, DATA_INCOMING);

    @Before
    public void setUp() {
      cumulator = new NettyAdaptiveCumulator(0) {
        @Override
        void addInput(ByteBufAllocator alloc, CompositeByteBuf composite, ByteBuf in) {
          // To limit the testing scope to NettyAdaptiveCumulator.cumulate(), always compose
          composite.addFlattenedComponents(true, in);
        }
      };
    }

    @Test
    public void cumulate_notReadableCumulation_replacedWithInputAndReleased() {
      contiguous.readerIndex(contiguous.writerIndex());
      assertFalse(contiguous.isReadable());
      ByteBuf cumulation = cumulator.cumulate(alloc, contiguous, in);
      assertEquals(DATA_INCOMING, cumulation.toString(US_ASCII));
      assertEquals(0, contiguous.refCnt());
    }

    @Test
    public void cumulate_contiguousCumulation_newCompositeFromContiguousAndInput() {
      CompositeByteBuf cumulation = (CompositeByteBuf) cumulator.cumulate(alloc, contiguous, in);
      assertEquals(DATA_INITIAL, cumulation.component(0).toString(US_ASCII));
      assertEquals(DATA_INCOMING, cumulation.component(1).toString(US_ASCII));
      assertEquals(DATA_CUMULATED, cumulation.toString(US_ASCII));
    }

    @Test
    public void cumulate_compositeCumulation_inputAppendedAsANewComponent() {
      assertSame(composite, cumulator.cumulate(alloc, composite, in));
      assertEquals(DATA_INITIAL, composite.component(0).toString(US_ASCII));
      assertEquals(DATA_INCOMING, composite.component(1).toString(US_ASCII));
      assertEquals(DATA_CUMULATED, composite.toString(US_ASCII));
    }

    @Test
    public void cumulate_compositeCumulation_inputReleasedOnError() {
      final UnsupportedOperationException expectedError = new UnsupportedOperationException();
      composite = new CompositeByteBuf(alloc, false, Integer.MAX_VALUE, contiguous) {
        @Override
        public CompositeByteBuf addFlattenedComponents(boolean increaseWriterIndex,
            ByteBuf buffer) {
          throw expectedError;
        }
      };
      try {
        cumulator.cumulate(alloc, composite, in);
        fail("Cumulator didn't throw");
      } catch (UnsupportedOperationException actualError) {
        assertSame(expectedError, actualError);
        // Input must be released unless its ownership has been to the composite cumulation.
        assertEquals(0, in.refCnt());
        // Initial composite cumulation owned by the caller in this case, so it isn't released.
        assertThat(composite.refCnt()).isAtLeast(1);
      }
    }

    @Test
    public void cumulate_contiguousCumulation_inputAndNewCompositeReleasedOnError() {
      final UnsupportedOperationException expectedError = new UnsupportedOperationException();
      composite = new CompositeByteBuf(alloc, false, Integer.MAX_VALUE) {
        @Override
        @SuppressWarnings("ReferenceEquality")
        public CompositeByteBuf addFlattenedComponents(boolean increaseWriterIndex,
            ByteBuf buffer) {
          if (this.numComponents() == 0) {
            // Add the initial cumulation to a new composite cumulation.
            return super.addFlattenedComponents(increaseWriterIndex, buffer);
          }
          // Emulate an error on any other attempts to add a component.
          throw expectedError;
        }
      };
      // Return our instance of new composite to ensure it's released.
      ByteBufAllocator mockAlloc = mock(ByteBufAllocator.class);
      when(mockAlloc.compositeBuffer(anyInt())).thenReturn(composite);
      try {
        cumulator.cumulate(mockAlloc, contiguous, in);
        fail("Cumulator didn't throw");
      } catch (UnsupportedOperationException actualError) {
        assertSame(expectedError, actualError);
        // Input must be released unless its ownership has been to the composite cumulation.
        assertEquals(0, in.refCnt());
        // New composite cumulation hasn't been returned to the caller, so it must be released.
        assertEquals(0, composite.refCnt());
      }
    }
  }

  @RunWith(Parameterized.class)
  public static class ShouldComposeTests {
    /**
     * Cartesian product of the test values.
     */
    @Parameters(name = "composeMinSize={0}, tailData=\"{1}\", inData=\"{2}\"")
    public static Collection<Object[]> params() {
      List<?> composeMinSize = ImmutableList.of(0, 9, 10, 11, Integer.MAX_VALUE);
      List<?> tailData = ImmutableList.of("", DATA_INITIAL);
      List<?> inData = ImmutableList.of("", DATA_INCOMING);
      return cartesianProductParams(composeMinSize, tailData, inData);
    }

    @Parameter(0) public int composeMinSize;
    @Parameter(1) public String tailData;
    @Parameter(2) public String inData;

    private CompositeByteBuf composite;
    private ByteBuf tail;
    private ByteBuf in;

    @Before
    public void setUp() {
      ByteBufAllocator alloc = new UnpooledByteBufAllocator(false);
      in = ByteBufUtil.writeAscii(alloc, inData);
      tail = ByteBufUtil.writeAscii(alloc, tailData);
      composite = alloc.compositeBuffer(Integer.MAX_VALUE);
      // Note that addFlattenedComponents() will not add a new component when tail is not readable.
      composite.addFlattenedComponents(true, tail);
    }

    @Test
    public void shouldCompose_emptyComposite() {
      assume().that(composite.numComponents()).isEqualTo(0);
      assertTrue(NettyAdaptiveCumulator.shouldCompose(composite, in, composeMinSize));
    }

    @Test
    public void shouldCompose_composeMinSizeReached() {
      assume().that(composite.numComponents()).isGreaterThan(0);
      assume().that(tail.readableBytes() + in.readableBytes()).isAtLeast(composeMinSize);
      assertTrue(NettyAdaptiveCumulator.shouldCompose(composite, in, composeMinSize));
    }

    @Test
    public void shouldCompose_composeMinSizeNotReached() {
      assume().that(composite.numComponents()).isGreaterThan(0);
      assume().that(tail.readableBytes() + in.readableBytes()).isLessThan(composeMinSize);
      assertFalse(NettyAdaptiveCumulator.shouldCompose(composite, in, composeMinSize));
    }
  }

  @RunWith(Parameterized.class)
  public static class MergeWithCompositeTail {
    private static final String DATA_INCOMING_DISCARDABLE = "xxxxx";
    private static final String DATA_COMPOSITE_HEAD = "hhhhh";
    private static final int TAIL_READER_INDEX = 1;
    private static final int TAIL_MAX_CAPACITY = 128;

    /**
     * Cartesian product of the test values.
     *
     * <p>Test cases when the cumulation contains components, other than tail, and could be
     * partially read. This is needed to verify the correctness if reader and writer indexes of the
     * composite cumulation after the merge.
     */
    @Parameters(name = "compositeHeadData=\"{0}\", compositeReaderIndex={1}")
    public static Collection<Object[]> params() {
      List<?> compositeHeadData = ImmutableList.of("", DATA_COMPOSITE_HEAD);
      // From the start, or within of head/tail.
      List<?> compositeReaderIndex = ImmutableList.of(0, 3);
      return cartesianProductParams(compositeHeadData, compositeReaderIndex);
    }

    @Parameter(0) public String compositeHeadData;
    @Parameter(1) public int compositeReaderIndex;

    // Use pooled allocator to have maxFastWritableBytes() behave differently than writableBytes().
    private final ByteBufAllocator alloc = new PooledByteBufAllocator();
    private CompositeByteBuf composite;
    private ByteBuf tail;
    private ByteBuf in;

    @Before
    public void setUp() {
      in = alloc.buffer()
          .writeBytes(DATA_INCOMING_DISCARDABLE.getBytes(US_ASCII))
          .writeBytes(DATA_INCOMING.getBytes(US_ASCII))
          .readerIndex(DATA_INCOMING_DISCARDABLE.length());
      // Tail's using full initial capacity by default.
      tail = alloc.buffer(DATA_INITIAL.length(), TAIL_MAX_CAPACITY)
          .writeBytes(DATA_INITIAL.getBytes(US_ASCII))
          .readerIndex(TAIL_READER_INDEX);
      composite = alloc.compositeBuffer();
      composite.addFlattenedComponents(true,
          alloc.buffer().writeBytes(compositeHeadData.getBytes(US_ASCII)));
    }

    @Test
    public void mergeWithCompositeTail_tailExpandable_write() {
      // Make incoming data fit into tail capacity.
      tail.capacity(DATA_CUMULATED.length());
      composite.addFlattenedComponents(true, tail);
      // Confirm it fits.
      assertThat(in.readableBytes()).isAtMost(tail.writableBytes());

      // Capacity must not change.
      testTailExpansion(DATA_CUMULATED.substring(TAIL_READER_INDEX), DATA_CUMULATED.length());
    }

    @Test
    public void mergeWithCompositeTail_tailExpandable_fastWrite() {
      // Confirm that the tail can be expanded fast to fit the incoming data.
      assertThat(in.readableBytes()).isAtMost(tail.maxFastWritableBytes());
      int tailFastCapacity = tail.writerIndex() + tail.maxFastWritableBytes();
      composite.addFlattenedComponents(true, tail);

      // Tail capacity is extended to its fast capacity.
      testTailExpansion(DATA_CUMULATED.substring(TAIL_READER_INDEX), tailFastCapacity);
    }

    @Test
    public void mergeWithCompositeTail_tailExpandable_reallocateInMemory() {
      int tailFastCapacity = tail.writerIndex() + tail.maxFastWritableBytes();
      String inSuffixOverFastBytes = Strings.repeat("a", tailFastCapacity + 1);
      int totalBytes =  tail.readableBytes() + inSuffixOverFastBytes.length();
      composite.addFlattenedComponents(true, tail);

      // Make input larger than tailFastCapacity
      in.writeCharSequence(inSuffixOverFastBytes, US_ASCII);
      // Confirm that the tail can only fit incoming data via reallocation.
      assertThat(in.readableBytes()).isGreaterThan(tail.maxFastWritableBytes());
      assertThat(in.readableBytes()).isAtMost(tail.maxWritableBytes());

      // Confirm the assumption that new capacity is produced by alloc.calculateNewCapacity().
      int expectedTailCapacity = alloc.calculateNewCapacity(totalBytes, Integer.MAX_VALUE);
      testTailExpansion(DATA_CUMULATED.substring(TAIL_READER_INDEX).concat(inSuffixOverFastBytes),
          expectedTailCapacity);
    }

    private void testTailExpansion(String expectedReadable, int expectedNewTailCapacity) {
      int composeOriginalComponentsNum = composite.numComponents();
      composite.readerIndex(compositeReaderIndex);
      NettyAdaptiveCumulator.mergeWithCompositeTail(alloc, composite, in);
      // Composite component count shouldn't change.
      assertEquals(composeOriginalComponentsNum, composite.numComponents());
      ByteBuf expandedTail = composite.component(composite.numComponents() - 1);
      // Expanded tail could be wrapped, so assertSame() is not safe.
      assertEquals(tail, expandedTail);

      // Read (discardable) bytes of the tail must stay as is.
      // Read (discardable) bytes of the input must be discarded.
      // Readable part of the input must be appended to the tail.
      assertEquals(compositeHeadData.concat(expectedReadable).substring(compositeReaderIndex),
          composite.toString(US_ASCII));
      assertEquals(DATA_INITIAL.substring(0, TAIL_READER_INDEX),
          expandedTail.toString(0, expandedTail.readerIndex(), US_ASCII));
      assertEquals(expectedReadable, expandedTail.toString(US_ASCII));
      assertEquals(expectedNewTailCapacity, expandedTail.capacity());

      // Only composite buf owns the tail.
      assertEquals(1, expandedTail.refCnt());
      // Incoming data buf must be fully read and released.
      assertEquals(0, in.readableBytes());
      assertEquals(0, in.refCnt());
    }

    @Test
    public void mergeWithCompositeTail_tailNotExpandable_maxCapacityReached() {
      // Fill in tail to the maxCapacity.
      String tailSuffixFullCapacity = Strings.repeat("a", tail.maxWritableBytes());
      tail.writeCharSequence(tailSuffixFullCapacity, US_ASCII);
      composite.addFlattenedComponents(true, tail);
      testTailReplaced();
    }

    @Test
    public void mergeWithCompositeTail_tailNotExpandable_shared() {
      tail.retain();
      composite.addFlattenedComponents(true, tail);
      testTailReplaced();
    }

    @Test
    public void mergeWithCompositeTail_tailNotExpandable_readOnly() {
      composite.addFlattenedComponents(true, tail.asReadOnly());
      testTailReplaced();
    }

    private void testTailReplaced() {
      int composeOriginalComponentsNum = composite.numComponents();
      int initialTailRefCount = tail.refCnt();
      composite.readerIndex(compositeReaderIndex);
      String expectedReadable = tail.toString(US_ASCII) + in.toString(US_ASCII);
      int expectedCapacity = alloc
          .calculateNewCapacity(expectedReadable.length(), Integer.MAX_VALUE);

      NettyAdaptiveCumulator.mergeWithCompositeTail(alloc, composite, in);
      // Composite component count shouldn't change.
      assertEquals(composeOriginalComponentsNum, composite.numComponents());
      ByteBuf replacedTail = composite.component(composite.numComponents() - 1);

      // Read (discardable) bytes of the tail and the input must be discarded.
      // Readable parts of the tail and the input must be merged.
      assertEquals(expectedReadable, replacedTail.toString(US_ASCII));
      assertEquals(expectedCapacity, replacedTail.capacity());
      assertEquals(0, replacedTail.readerIndex());
      assertEquals(expectedReadable.length(), replacedTail.writerIndex());
      // Check the composite buf
      assertEquals(compositeHeadData.concat(expectedReadable).substring(compositeReaderIndex),
          composite.toString(US_ASCII));
      assertEquals(compositeHeadData.length() + expectedReadable.length(), composite.capacity());
      assertEquals(compositeReaderIndex, composite.readerIndex());
      assertEquals(compositeHeadData.length() + expectedReadable.length(),
          composite.writerIndex());

      // Old tail is must be released at least once
      assertThat(tail.refCnt()).isLessThan(initialTailRefCount);
      // Composite buf owns the new tail.
      assertEquals(1, replacedTail.refCnt());
      // Incoming data buf must be fully read and released.
      assertEquals(0, in.readableBytes());
      assertEquals(0, in.refCnt());
    }

    @Test
    public void mergeWithCompositeTail_tailExpandable_mergedReleaseOnThrow() {
      final UnsupportedOperationException expectedError = new UnsupportedOperationException();
      composite = new CompositeByteBuf(alloc, false, Integer.MAX_VALUE, tail) {
        @Override
        public CompositeByteBuf addFlattenedComponents(boolean increaseWriterIndex,
            ByteBuf buffer) {
          throw expectedError;
        }
      };

      try {
        NettyAdaptiveCumulator.mergeWithCompositeTail(alloc, composite, in);
        fail("Cumulator didn't throw");
      } catch (UnsupportedOperationException actualError) {
        assertSame(expectedError, actualError);
        // Input must be released unless its ownership has been to the composite cumulation.
        assertEquals(0, in.refCnt());
        // Tail released
        assertEquals(0, tail.refCnt());
        // Composite loses the tail
        assertEquals(0, composite.numComponents());
      }
    }

    @Test
    public void mergeWithCompositeTail_tailNotExpandable_mergedReleaseOnThrow() {
      final UnsupportedOperationException expectedError = new UnsupportedOperationException();
      composite = new CompositeByteBuf(alloc, false, Integer.MAX_VALUE, tail.asReadOnly()) {
        @Override
        public CompositeByteBuf addFlattenedComponents(boolean increaseWriterIndex,
            ByteBuf buffer) {
          throw expectedError;
        }
      };

      // Return our instance of the new buffer to ensure it's released.
      int totalBytes = tail.readableBytes() + in.readableBytes();
      ByteBuf merged = alloc.buffer(alloc.calculateNewCapacity(totalBytes, Integer.MAX_VALUE));
      ByteBufAllocator mockAlloc = mock(ByteBufAllocator.class);
      when(mockAlloc.buffer(anyInt())).thenReturn(merged);

      try {
        NettyAdaptiveCumulator.mergeWithCompositeTail(mockAlloc, composite, in);
        fail("Cumulator didn't throw");
      } catch (UnsupportedOperationException actualError) {
        assertSame(expectedError, actualError);
        // Input must be released unless its ownership has been to the composite cumulation.
        assertEquals(0, in.refCnt());
        // New buffer released
        assertEquals(0, merged.refCnt());
        // Composite loses the tail
        assertEquals(0, composite.numComponents());
      }
    }
  }
}
