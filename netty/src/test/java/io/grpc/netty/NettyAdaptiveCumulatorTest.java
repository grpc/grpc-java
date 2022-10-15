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
import static com.google.common.truth.Truth.assertWithMessage;
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
import java.util.stream.Collectors;
import org.junit.After;
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

  private static Collection<Object[]> cartesianProductParams(List<?>... lists) {
    return Lists.cartesianProduct(lists).stream().map(List::toArray).collect(Collectors.toList());
  }

  @RunWith(JUnit4.class)
  public static class CumulateTests {
    // Represent data as immutable ASCII Strings for easy and readable ByteBuf equality assertions.
    private static final String DATA_INITIAL = "0123";
    private static final String DATA_INCOMING = "456789";
    private static final String DATA_CUMULATED = "0123456789";

    private static final ByteBufAllocator alloc = new UnpooledByteBufAllocator(false);
    private NettyAdaptiveCumulator cumulator;
    private NettyAdaptiveCumulator throwingCumulator;
    private final UnsupportedOperationException throwingCumulatorError =
        new UnsupportedOperationException();

    // Buffers for testing
    private final ByteBuf contiguous = ByteBufUtil.writeAscii(alloc, DATA_INITIAL);
    private final ByteBuf in = ByteBufUtil.writeAscii(alloc, DATA_INCOMING);

    @Before
    public void setUp() {
      cumulator = new NettyAdaptiveCumulator(0) {
        @Override
        void addInput(ByteBufAllocator alloc, CompositeByteBuf composite, ByteBuf in) {
          // To limit the testing scope to NettyAdaptiveCumulator.cumulate(), always compose
          composite.addFlattenedComponents(true, in);
        }
      };

      // Throws an error on adding incoming buffer.
      throwingCumulator = new NettyAdaptiveCumulator(0) {
        @Override
        void addInput(ByteBufAllocator alloc, CompositeByteBuf composite, ByteBuf in) {
          throw throwingCumulatorError;
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
      // In retained by cumulation.
      assertEquals(1, in.refCnt());
      assertEquals(1, cumulation.refCnt());
      cumulation.release();
    }

    @Test
    public void cumulate_contiguousCumulation_newCompositeFromContiguousAndInput() {
      CompositeByteBuf cumulation = (CompositeByteBuf) cumulator.cumulate(alloc, contiguous, in);
      assertEquals(DATA_INITIAL, cumulation.component(0).toString(US_ASCII));
      assertEquals(DATA_INCOMING, cumulation.component(1).toString(US_ASCII));
      assertEquals(DATA_CUMULATED, cumulation.toString(US_ASCII));
      // Both in and contiguous are retained by cumulation.
      assertEquals(1, contiguous.refCnt());
      assertEquals(1, in.refCnt());
      assertEquals(1, cumulation.refCnt());
      cumulation.release();
    }

    @Test
    public void cumulate_compositeCumulation_inputAppendedAsANewComponent() {
      CompositeByteBuf composite = alloc.compositeBuffer().addComponent(true, contiguous);
      assertSame(composite, cumulator.cumulate(alloc, composite, in));
      assertEquals(DATA_INITIAL, composite.component(0).toString(US_ASCII));
      assertEquals(DATA_INCOMING, composite.component(1).toString(US_ASCII));
      assertEquals(DATA_CUMULATED, composite.toString(US_ASCII));
      // Both in and contiguous are retained by cumulation.
      assertEquals(1, contiguous.refCnt());
      assertEquals(1, in.refCnt());
      assertEquals(1, composite.refCnt());
      composite.release();
    }

    @Test
    public void cumulate_compositeCumulation_inputReleasedOnError() {
      CompositeByteBuf composite = alloc.compositeBuffer().addComponent(true, contiguous);
      try {
        throwingCumulator.cumulate(alloc, composite, in);
        fail("Cumulator didn't throw");
      } catch (UnsupportedOperationException actualError) {
        assertSame(throwingCumulatorError, actualError);
        // Input must be released unless its ownership has been to the composite cumulation.
        assertEquals(0, in.refCnt());
        // Initial composite cumulation owned by the caller in this case, so it isn't released.
        assertEquals(1, composite.refCnt());
        // Contiguous still managed by the cumulation
        assertEquals(1, contiguous.refCnt());
      } finally {
        composite.release();
      }
    }

    @Test
    public void cumulate_contiguousCumulation_inputAndNewCompositeReleasedOnError() {
      // Return our instance of new composite to ensure it's released.
      CompositeByteBuf newComposite = alloc.compositeBuffer(Integer.MAX_VALUE);
      ByteBufAllocator mockAlloc = mock(ByteBufAllocator.class);
      when(mockAlloc.compositeBuffer(anyInt())).thenReturn(newComposite);

      try {
        // Previous cumulation is non-composite, so cumulator will create anew composite and add
        // both buffers to it.
        throwingCumulator.cumulate(mockAlloc, contiguous, in);
        fail("Cumulator didn't throw");
      } catch (UnsupportedOperationException actualError) {
        assertSame(throwingCumulatorError, actualError);
        // Input must be released unless its ownership has been to the composite cumulation.
        assertEquals(0, in.refCnt());
        // New composite cumulation hasn't been returned to the caller, so it must be released.
        assertEquals(0, newComposite.refCnt());
        // Previous cumulation released because it was owned by the new composite cumulation.
        assertEquals(0, contiguous.refCnt());
      }
    }
  }

  @RunWith(Parameterized.class)
  public static class ShouldComposeTests {
    // Represent data as immutable ASCII Strings for easy and readable ByteBuf equality assertions.
    private static final String DATA_INITIAL = "0123";
    private static final String DATA_INCOMING = "456789";

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

    @Parameter public int composeMinSize;
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

    @After
    public void tearDown() {
      in.release();
      composite.release();
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
  public static class MergeWithCompositeTailTests {
    private static final String INCOMING_DATA_READABLE = "+incoming";
    private static final String INCOMING_DATA_DISCARDABLE = "discard";

    private static final String TAIL_DATA_DISCARDABLE = "---";
    private static final String TAIL_DATA_READABLE = "tail";
    private static final String TAIL_DATA =  TAIL_DATA_DISCARDABLE + TAIL_DATA_READABLE;
    private static final int TAIL_READER_INDEX = TAIL_DATA_DISCARDABLE.length();
    private static final int TAIL_MAX_CAPACITY = 128;

    // DRY sacrificed to improve readability.
    private static final String EXPECTED_TAIL_DATA = "tail+incoming";

    /**
     * Cartesian product of the test values.
     *
     * <p>Test cases when the cumulation contains components, other than tail, and could be
     * partially read. This is needed to verify the correctness if reader and writer indexes of the
     * composite cumulation after the merge.
     */
    @Parameters(name = "compositeHeadData=\"{0}\", compositeReaderIndex={1}")
    public static Collection<Object[]> params() {
      String headData = "head";

      List<?> compositeHeadData = ImmutableList.of(
          // Test without the "head" component. Empty string is equivalent of fully read buffer,
          // so it's not added to the composite byte buf. The tail is added as the first component.
          "",
          // Test with the "head" component, so the tail is added as the second component.
          headData
      );

      // After the tail is added to the composite cumulator, advance the reader index to
      // cover different cases.
      // The reader index only looks at what's readable in the composite byte buf, so
      // discardable bytes of head and tail doesn't count.
      List<?> compositeReaderIndex = ImmutableList.of(
          // Reader in the beginning
          0,
          // Within the head (when present) or the tail
          headData.length() - 2,
          // Within the tail, even if the head is present
          headData.length() + 2
      );
      return cartesianProductParams(compositeHeadData, compositeReaderIndex);
    }

    @Parameter public String compositeHeadData;
    @Parameter(1) public int compositeReaderIndex;

    // Use pooled allocator to have maxFastWritableBytes() behave differently than writableBytes().
    private final ByteBufAllocator alloc = new PooledByteBufAllocator();

    // Composite buffer to be used in tests.
    private CompositeByteBuf composite;
    private ByteBuf tail;
    private ByteBuf in;

    @Before
    public void setUp() {
      composite = alloc.compositeBuffer();

      // The "head" component. It represents existing data in the cumulator.
      // Note that addFlattenedComponents() does not add completely read buffer, which covers
      // the case when compositeHeadData parameter is an empty string.
      ByteBuf head = alloc.buffer().writeBytes(compositeHeadData.getBytes(US_ASCII));
      composite.addFlattenedComponents(true, head);

      // The "tail" component. It also represents existing data in the cumulator, but it's
      // not added to the cumulator during setUp() stage. It is to be manipulated by tests to
      // produce different buffer write scenarios based on different tail's capacity.
      // After tail is changes for each test scenario, it's added to the composite buffer.
      //
      // The default state of the tail before each test: tail is full, but expandable (the data uses
      // all initial capacity, but not maximum capacity).
      // Tail data and indexes:
      // ----tail
      //     r   w
      tail = alloc.buffer(TAIL_DATA.length(), TAIL_MAX_CAPACITY)
          .writeBytes(TAIL_DATA.getBytes(US_ASCII))
          .readerIndex(TAIL_READER_INDEX);

      // Incoming data and indexes:
      // discard+incoming
      //        r        w
      in = alloc.buffer()
          .writeBytes(INCOMING_DATA_DISCARDABLE.getBytes(US_ASCII))
          .writeBytes(INCOMING_DATA_READABLE.getBytes(US_ASCII))
          .readerIndex(INCOMING_DATA_DISCARDABLE.length());
    }

    @After
    public void tearDown() {
      composite.release();
    }

    @Test
    public void mergeWithCompositeTail_tailExpandable_write() {
      // Make incoming data fit into tail capacity.
      int fitCapacity = tail.capacity() + INCOMING_DATA_READABLE.length();
      tail.capacity(fitCapacity);
      // Confirm it fits.
      assertThat(in.readableBytes()).isAtMost(tail.writableBytes());

      // All fits, so tail capacity must stay the same.
      composite.addFlattenedComponents(true, tail);
      assertTailExpanded(EXPECTED_TAIL_DATA, fitCapacity);
    }

    @Test
    public void mergeWithCompositeTail_tailExpandable_fastWrite() {
      // Confirm that the tail can be expanded fast to fit the incoming data.
      assertThat(in.readableBytes()).isAtMost(tail.maxFastWritableBytes());

      // To avoid undesirable buffer unwrapping, at the moment adaptive cumulator is set not
      // apply fastWrite technique. Even when fast write is possible, it will fall back to
      // reallocating a larger buffer.
      // int tailFastCapacity = tail.writerIndex() + tail.maxFastWritableBytes();
      int tailFastCapacity =
          alloc.calculateNewCapacity(EXPECTED_TAIL_DATA.length(), Integer.MAX_VALUE);

      // Tail capacity is extended to its fast capacity.
      composite.addFlattenedComponents(true, tail);
      assertTailExpanded(EXPECTED_TAIL_DATA, tailFastCapacity);
    }

    @Test
    public void mergeWithCompositeTail_tailExpandable_reallocateInMemory() {
      int tailFastCapacity = tail.writerIndex() + tail.maxFastWritableBytes();
      String inSuffixOverFastBytes = Strings.repeat("a", tailFastCapacity + 1);
      int newTailSize =  tail.readableBytes() + inSuffixOverFastBytes.length();
      composite.addFlattenedComponents(true, tail);

      // Make input larger than tailFastCapacity
      in.writeCharSequence(inSuffixOverFastBytes, US_ASCII);
      // Confirm that the tail can only fit incoming data via reallocation.
      assertThat(in.readableBytes()).isGreaterThan(tail.maxFastWritableBytes());
      assertThat(in.readableBytes()).isAtMost(tail.maxWritableBytes());

      // Confirm the assumption that new capacity is produced by alloc.calculateNewCapacity().
      int expectedTailCapacity = alloc.calculateNewCapacity(newTailSize, Integer.MAX_VALUE);
      assertTailExpanded(EXPECTED_TAIL_DATA.concat(inSuffixOverFastBytes), expectedTailCapacity);
    }

    private void assertTailExpanded(String expectedTailReadableData, int expectedNewTailCapacity) {
      int originalNumComponents = composite.numComponents();

      // Handle the case when reader index is beyond all readable bytes of the cumulation.
      int compositeReaderIndexBounded = Math.min(compositeReaderIndex, composite.writerIndex());
      composite.readerIndex(compositeReaderIndexBounded);

      // Execute the merge logic.
      NettyAdaptiveCumulator.mergeWithCompositeTail(alloc, composite, in);

      // Composite component count shouldn't change.
      assertWithMessage(
          "When tail is expanded, the number of components in the cumulation must not change")
          .that(composite.numComponents()).isEqualTo(originalNumComponents);

      ByteBuf newTail = composite.component(composite.numComponents() - 1);

      // Verify the readable part of the expanded tail:
      // 1. Initial readable bytes of the tail not changed
      // 2. Discardable bytes (0 < discardable < readerIndex) of the incoming buffer are discarded.
      // 3. Readable bytes of the incoming buffer are fully read and appended to the tail.
      assertEquals(expectedTailReadableData, newTail.toString(US_ASCII));
      // Verify expanded capacity.
      assertEquals(expectedNewTailCapacity, newTail.capacity());

      // Discardable bytes (0 < discardable < readerIndex) of the tail are kept as is.
      String newTailDataDiscardable = newTail.toString(0, newTail.readerIndex(), US_ASCII);
      assertWithMessage("After tail expansion, its discardable bytes should be unchanged")
          .that(newTailDataDiscardable).isEqualTo(TAIL_DATA_DISCARDABLE);

      // Reader index must stay where it was
      assertEquals(TAIL_READER_INDEX, newTail.readerIndex());
      // Writer index at the end
      assertEquals(TAIL_READER_INDEX + expectedTailReadableData.length(),
          newTail.writerIndex());

      // Verify resulting cumulation.
      assertExpectedCumulation(newTail, expectedTailReadableData, compositeReaderIndexBounded);

      // Verify incoming buffer.
      assertWithMessage("Incoming buffer is fully read").that(in.isReadable()).isFalse();
      assertWithMessage("Incoming buffer is released").that(in.refCnt()).isEqualTo(0);
    }

    @Test
    public void mergeWithCompositeTail_tailNotExpandable_maxCapacityReached() {
      // Fill in tail to the maxCapacity.
      String tailSuffixFullCapacity = Strings.repeat("a", tail.maxWritableBytes());
      tail.writeCharSequence(tailSuffixFullCapacity, US_ASCII);
      composite.addFlattenedComponents(true, tail);
      assertTailReplaced();
    }

    @Test
    public void mergeWithCompositeTail_tailNotExpandable_shared() {
      tail.retain();
      composite.addFlattenedComponents(true, tail);
      assertTailReplaced();
      tail.release();
    }

    @Test
    public void mergeWithCompositeTail_tailNotExpandable_readOnly() {
      composite.addFlattenedComponents(true, tail.asReadOnly());
      assertTailReplaced();
    }

    private void assertTailReplaced() {
      int cumulationOriginalComponentsNum = composite.numComponents();
      int taiOriginalRefCount = tail.refCnt();
      String expectedTailReadable = tail.toString(US_ASCII) + in.toString(US_ASCII);
      int expectedReallocatedTailCapacity = alloc
          .calculateNewCapacity(expectedTailReadable.length(), Integer.MAX_VALUE);

      int compositeReaderIndexBounded = Math.min(compositeReaderIndex, composite.writerIndex());
      composite.readerIndex(compositeReaderIndexBounded);
      NettyAdaptiveCumulator.mergeWithCompositeTail(alloc, composite, in);

      // Composite component count shouldn't change.
      assertEquals(cumulationOriginalComponentsNum, composite.numComponents());
      ByteBuf replacedTail = composite.component(composite.numComponents() - 1);

      // Verify the readable part of the expanded tail:
      // 1. Discardable bytes (0 < discardable < readerIndex) of the tail are discarded.
      // 2. Readable bytes of the tail are kept as is
      // 3. Discardable bytes (0 < discardable < readerIndex) of the incoming buffer are discarded.
      // 4. Readable bytes of the incoming buffer are fully read and appended to the tail.
      assertEquals(0, in.readableBytes());
      assertEquals(expectedTailReadable, replacedTail.toString(US_ASCII));

      // Since tail discardable bytes are discarded, new reader index must be reset to 0.
      assertEquals(0, replacedTail.readerIndex());
      // And new writer index at the new data's length.
      assertEquals(expectedTailReadable.length(), replacedTail.writerIndex());
      // Verify the capacity of reallocated tail.
      assertEquals(expectedReallocatedTailCapacity, replacedTail.capacity());

      // Verify resulting cumulation.
      assertExpectedCumulation(replacedTail, expectedTailReadable, compositeReaderIndexBounded);

      // Verify incoming buffer.
      assertWithMessage("Incoming buffer is fully read").that(in.isReadable()).isFalse();
      assertWithMessage("Incoming buffer is released").that(in.refCnt()).isEqualTo(0);

      // The old tail must be released once (have one less reference).
      assertWithMessage("Replaced tail released once.")
          .that(tail.refCnt()).isEqualTo(taiOriginalRefCount - 1);
    }

    private void assertExpectedCumulation(
        ByteBuf newTail, String expectedTailReadable, int expectedReaderIndex) {
      // Verify the readable part of the cumulation:
      // 1. Readable composite head (initial) data
      // 2. Readable part of the tail
      // 3. Readable part of the incoming data
      String expectedCumulationData =
          compositeHeadData.concat(expectedTailReadable).substring(expectedReaderIndex);
      assertEquals(expectedCumulationData, composite.toString(US_ASCII));

      // Cumulation capacity includes:
      // 1. Full composite head, including discardable bytes
      // 2. Expanded tail readable bytes
      int expectedCumulationCapacity = compositeHeadData.length() + expectedTailReadable.length();
      assertEquals(expectedCumulationCapacity, composite.capacity());

      // Composite Reader index must stay where it was.
      assertEquals(expectedReaderIndex, composite.readerIndex());
      // Composite writer index must be at the end.
      assertEquals(expectedCumulationCapacity, composite.writerIndex());

      // Composite cumulation is retained and owns the new tail.
      assertEquals(1, composite.refCnt());
      assertEquals(1, newTail.refCnt());
    }

    @Test
    public void mergeWithCompositeTail_tailExpandable_mergedReleaseOnThrow() {
      final UnsupportedOperationException expectedError = new UnsupportedOperationException();
      CompositeByteBuf compositeThrows = new CompositeByteBuf(alloc, false, Integer.MAX_VALUE,
          tail) {
        @Override
        public CompositeByteBuf addFlattenedComponents(boolean increaseWriterIndex,
            ByteBuf buffer) {
          throw expectedError;
        }
      };

      try {
        NettyAdaptiveCumulator.mergeWithCompositeTail(alloc, compositeThrows, in);
        fail("Cumulator didn't throw");
      } catch (UnsupportedOperationException actualError) {
        assertSame(expectedError, actualError);
        // Input must be released unless its ownership has been to the composite cumulation.
        assertEquals(0, in.refCnt());
        // Tail released
        assertEquals(0, tail.refCnt());
        // Composite cumulation is retained
        assertEquals(1, compositeThrows.refCnt());
        // Composite cumulation loses the tail
        assertEquals(0, compositeThrows.numComponents());
      } finally {
        compositeThrows.release();
      }
    }

    @Test
    public void mergeWithCompositeTail_tailNotExpandable_mergedReleaseOnThrow() {
      final UnsupportedOperationException expectedError = new UnsupportedOperationException();
      CompositeByteBuf compositeRo = new CompositeByteBuf(alloc, false, Integer.MAX_VALUE,
          tail.asReadOnly()) {
        @Override
        public CompositeByteBuf addFlattenedComponents(boolean increaseWriterIndex,
            ByteBuf buffer) {
          throw expectedError;
        }
      };

      // Return our instance of the new buffer to ensure it's released.
      int newTailSize = tail.readableBytes() + in.readableBytes();
      ByteBuf newTail = alloc.buffer(alloc.calculateNewCapacity(newTailSize, Integer.MAX_VALUE));
      ByteBufAllocator mockAlloc = mock(ByteBufAllocator.class);
      when(mockAlloc.buffer(anyInt())).thenReturn(newTail);

      try {
        NettyAdaptiveCumulator.mergeWithCompositeTail(mockAlloc, compositeRo, in);
        fail("Cumulator didn't throw");
      } catch (UnsupportedOperationException actualError) {
        assertSame(expectedError, actualError);
        // Input must be released unless its ownership has been to the composite cumulation.
        assertEquals(0, in.refCnt());
        // New buffer released
        assertEquals(0, newTail.refCnt());
        // Composite cumulation is retained
        assertEquals(1, compositeRo.refCnt());
        // Composite cumulation loses the tail
        assertEquals(0, compositeRo.numComponents());
      } finally {
        compositeRo.release();
      }
    }
  }

  /**
   * Miscellaneous tests for {@link NettyAdaptiveCumulator#mergeWithCompositeTail} that don't
   * fit into {@link MergeWithCompositeTailTests}, and require custom-crafted scenarios.
   */
  @RunWith(JUnit4.class)
  public static class MergeWithCompositeTailMiscTests {
    private final ByteBufAllocator alloc = new PooledByteBufAllocator();

    /**
     * Test the issue with {@link CompositeByteBuf#component(int)} returning a ByteBuf with
     * the indexes out-of-sync with {@code CompositeByteBuf.Component} offsets.
     */
    @Test
    public void mergeWithCompositeTail_outOfSyncComposite() {
      NettyAdaptiveCumulator cumulator = new NettyAdaptiveCumulator(1024);

      // Create underlying buffer spacious enough for the test data.
      ByteBuf buf = alloc.buffer(32).writeBytes("---01234".getBytes(US_ASCII));

      // Start with a regular cumulation and add the buf as the only component.
      CompositeByteBuf composite1 = alloc.compositeBuffer(8).addFlattenedComponents(true, buf);
      // Read composite1 buf to the beginning of the numbers.
      assertThat(composite1.readCharSequence(3, US_ASCII).toString()).isEqualTo("---");

      // Wrap composite1 into another cumulation. This is similar to
      // what NettyAdaptiveCumulator.cumulate() does in the case the cumulation has refCnt != 1.
      CompositeByteBuf composite2 =
          alloc.compositeBuffer(8).addFlattenedComponents(true, composite1);
      assertThat(composite2.toString(US_ASCII)).isEqualTo("01234");

      // The previous operation does not adjust the read indexes of the underlying buffers,
      // only the internal Component offsets. When the cumulator attempts to append the input to
      // the tail buffer, it extracts it from the cumulation, writes to it, and then adds it back.
      // Because the readerIndex on the tail buffer is not adjusted during the read operation
      // on the CompositeByteBuf, adding the tail back results in the discarded bytes of the tail
      // to be added back to the cumulator as if they were never read.
      //
      // If the reader index of the tail is not manually corrected, the resulting
      // cumulation will contain the discarded part of the tail: "---".
      // If it's corrected, it will only contain the numbers.
      CompositeByteBuf cumulation = (CompositeByteBuf) cumulator.cumulate(alloc, composite2,
          ByteBufUtil.writeAscii(alloc, "56789"));
      assertThat(cumulation.toString(US_ASCII)).isEqualTo("0123456789");

      // Correctness check: we still have a single component, and this component is still the
      // original underlying buffer.
      assertThat(cumulation.numComponents()).isEqualTo(1);
      // Replace '2' with '*', and '8' with '$'.
      buf.setByte(5, '*').setByte(11, '$');
      assertThat(cumulation.toString(US_ASCII)).isEqualTo("01*34567$9");
    }
  }
}
