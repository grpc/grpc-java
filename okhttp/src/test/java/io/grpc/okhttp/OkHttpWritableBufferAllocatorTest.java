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

import static io.grpc.okhttp.OkHttpWritableBufferAllocator.SEGMENT_SIZE_COPY;
import static org.junit.Assert.assertEquals;

import io.grpc.internal.WritableBuffer;
import io.grpc.internal.WritableBufferAllocator;
import io.grpc.internal.WritableBufferAllocatorTestBase;
import okio.Segment;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link OkHttpWritableBufferAllocator}.
 */
@RunWith(JUnit4.class)
public class OkHttpWritableBufferAllocatorTest extends WritableBufferAllocatorTestBase {

  private final OkHttpWritableBufferAllocator allocator = new OkHttpWritableBufferAllocator();

  @Override
  protected WritableBufferAllocator allocator() {
    return allocator;
  }

  @SuppressWarnings("KotlinInternal")
  @Test
  public void testCapacity() {
    WritableBuffer buffer = allocator().allocate(4096);
    assertEquals(0, buffer.readableBytes());
    assertEquals(SEGMENT_SIZE_COPY, buffer.writableBytes());
  }

  @Test
  public void testInitialCapacityHasMaximum() {
    WritableBuffer buffer = allocator().allocate(1024 * 1025);
    assertEquals(0, buffer.readableBytes());
    assertEquals(1024 * 1024, buffer.writableBytes());
  }

  @Test
  public void testIsExactBelowMaxCapacity() {
    WritableBuffer buffer = allocator().allocate(SEGMENT_SIZE_COPY + 1);
    assertEquals(0, buffer.readableBytes());
    assertEquals(SEGMENT_SIZE_COPY * 2, buffer.writableBytes());
  }

  @SuppressWarnings("KotlinInternal")
  @Test
  public void testSegmentSizeMatchesKotlin() {
    assertEquals(Segment.SIZE, SEGMENT_SIZE_COPY);
  }
}
