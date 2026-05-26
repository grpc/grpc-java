/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.servlet;

import static org.junit.Assert.assertEquals;

import io.grpc.servlet.AsyncServletOutputStreamWriter.ActionItem;
import io.grpc.servlet.AsyncServletOutputStreamWriter.Log;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit test for {@link AsyncServletOutputStreamWriter} with a mock isReady supplier.
 * Tests four scenarios:
 * (1) A write without intervening onWritePossible() is buffered.
 * (2) Consecutive writes succeed with onWritePossible() between them.
 * (3) Flush behavior: goes direct when readyAndDrained=true, buffers when readyAndDrained=false.
 * (4) Consecutive flushes go direct when isReady() stays true.
 */
@RunWith(JUnit4.class)
public class AsyncServletOutputStreamWriterTest {

  /**
   * Test that a write without an intervening onWritePossible() is buffered and
   * does not execute immediately. This validates the Tomcat fix: writeBytes sets
   * readyAndDrained=false so subsequent writes go through the container callback.
   */
  @Test
  public void writeBytes_withoutOnWritePossible_isBuffered() throws IOException {
    List<String> actions = new ArrayList<>();

    BiFunction<byte[], Integer, ActionItem> writeAction =
        (bytes, numBytes) -> () -> {
          actions.add("write");
        };

    ActionItem flushAction = () -> {};

    ActionItem completeAction = () -> {};

    BooleanSupplier isReadySupplier = () -> true;

    AsyncServletOutputStreamWriter writer =
        new AsyncServletOutputStreamWriter(
            writeAction, flushAction, completeAction, isReadySupplier, new Log() {});

    // Initial onWritePossible to set readyAndDrained=true
    writer.onWritePossible();

    // First write - goes direct, readyAndDrained becomes false
    byte[] data1 = new byte[]{1};
    writer.writeBytes(data1, 1);
    assertEquals("First write should execute", 1, actions.size());

    // Second write without onWritePossible - should be buffered since readyAndDrained=false
    byte[] data2 = new byte[]{2};
    writer.writeBytes(data2, 1);

    // Without onWritePossible, second write should be buffered, not executed
    assertEquals("Second write should be buffered until onWritePossible", 1, actions.size());

    // onWritePossible drains the buffered write
    writer.onWritePossible();
    assertEquals("Buffered write should drain after onWritePossible", 2, actions.size());
  }

  /**
   * Test that multiple consecutive writes succeed when isReady() always returns true
   * and the container calls onWritePossible() between writes.
   */
  @Test
  public void writeBytes_onWritePossibleBetweenWrites_succeeds() throws IOException {
    List<byte[]> writtenData = new ArrayList<>();

    BiFunction<byte[], Integer, ActionItem> writeAction =
        (bytes, numBytes) -> () -> {
          writtenData.add(Arrays.copyOf(bytes, numBytes));
        };

    ActionItem flushAction = () -> {};

    ActionItem completeAction = () -> {};

    BooleanSupplier isReadySupplier = () -> true;

    AsyncServletOutputStreamWriter writer =
        new AsyncServletOutputStreamWriter(
            writeAction, flushAction, completeAction, isReadySupplier, new Log() {});

    // Initial onWritePossible to set readyAndDrained=true
    writer.onWritePossible();

    // Write 5 times, calling onWritePossible after each write.
    // With isReady staying true, each onWritePossible sets readyAndDrained
    // back to true so the next write goes direct again.
    for (int i = 0; i < 5; i++) {
      byte[] data = new byte[]{(byte) i};
      writer.writeBytes(data, 1);
      writer.onWritePossible();
    }

    // Verify all 5 writes completed
    assertEquals("All writes should complete", 5, writtenData.size());
  }

  /**
   * Test flush behavior: flush goes direct when readyAndDrained is true,
   * but is buffered when readyAndDrained is false (regardless of isReady state).
   * This covers the flush-specific path in runOrBuffer().
   */
  @Test
  public void flush_withReadyAndDrainedFalse_isBuffered() throws IOException {
    List<String> actions = new ArrayList<>();

    BiFunction<byte[], Integer, ActionItem> writeAction =
        (bytes, numBytes) -> () -> {
          actions.add("write");
        };

    ActionItem flushAction = () -> {
      actions.add("flush");
    };

    ActionItem completeAction = () -> {};

    BooleanSupplier isReadySupplier = () -> true;

    AsyncServletOutputStreamWriter writer =
        new AsyncServletOutputStreamWriter(
            writeAction, flushAction, completeAction, isReadySupplier, new Log() {});

    // Initial onWritePossible to set readyAndDrained=true
    writer.onWritePossible();

    // First flush - readyAndDrained=true, isReady=true -> goes direct
    writer.flush();
    assertEquals("First flush should execute directly", 1, actions.size());

    // Write a byte to set readyAndDrained=false (writeBytes always clears it)
    writer.writeBytes(new byte[]{1}, 1);
    // The write goes direct (readyAndDrained was true) and readyAndDrained becomes false.
    // Now readyAndDrained=false.

    // Flush with readyAndDrained=false -> should be buffered (isReady doesn't matter)
    writer.flush();

    // Only the first flush should have executed (the write was also executed, making it 2)
    assertEquals("Second flush should be buffered", 2, actions.size());

    // Container calls onWritePossible to drain buffered flush
    writer.onWritePossible();

    // Both flushes should have completed (3 total: write + 2 flushes)
    assertEquals("Both flushes should complete after onWritePossible", 3, actions.size());
  }

  /**
   * Test that two consecutive flushes both go direct when isReady() stays true.
   * This validates the new flush behavior: flush keeps readyAndDrained=true when
   * isReady() is still true, allowing subsequent flushes to go direct without buffering.
   */
  @Test
  public void flush_consecutiveWithIsReadyTrue_bothGoDirect() throws IOException {
    List<String> actions = new ArrayList<>();

    BiFunction<byte[], Integer, ActionItem> writeAction =
        (bytes, numBytes) -> () -> {
          actions.add("write");
        };

    ActionItem flushAction = () -> {
      actions.add("flush");
    };

    ActionItem completeAction = () -> {};

    BooleanSupplier isReadySupplier = () -> true;

    AsyncServletOutputStreamWriter writer =
        new AsyncServletOutputStreamWriter(
            writeAction, flushAction, completeAction, isReadySupplier, new Log() {});

    // Initial onWritePossible to set readyAndDrained=true
    writer.onWritePossible();

    // Two consecutive flushes when isReady stays true - both should go direct
    writer.flush();
    writer.flush();

    // Both flushes should execute directly (no buffering)
    assertEquals("Both flushes should execute directly", 2, actions.size());
  }
}