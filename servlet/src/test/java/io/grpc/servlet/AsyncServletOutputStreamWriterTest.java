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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit test for {@link AsyncServletOutputStreamWriter} with a mock isReady supplier.
 * Tests three scenarios:
 * (1) A write without intervening onWritePossible() is buffered.
 * (2) Consecutive writes with onWritePossible() between them succeed.
 * (3) Flush goes direct when readyAndDrained=true, buffers when readyAndDrained=false.
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
    AtomicBoolean isReady = new AtomicBoolean(true);
    List<String> actions = new ArrayList<>();

    BiFunction<byte[], Integer, ActionItem> writeAction =
        (bytes, numBytes) -> () -> {
          actions.add("write");
        };

    ActionItem flushAction = () -> {};

    ActionItem completeAction = () -> {};

    BooleanSupplier isReadySupplier = () -> isReady.get();

    AsyncServletOutputStreamWriter writer =
        new AsyncServletOutputStreamWriter(writeAction, flushAction, completeAction, isReadySupplier, new Log() {});

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
    AtomicBoolean isReady = new AtomicBoolean(true);
    List<byte[]> writtenData = new ArrayList<>();

    BiFunction<byte[], Integer, ActionItem> writeAction =
        (bytes, numBytes) -> () -> {
          writtenData.add(bytes);
        };

    ActionItem flushAction = () -> {};

    ActionItem completeAction = () -> {};

    BooleanSupplier isReadySupplier = () -> isReady.get();

    AsyncServletOutputStreamWriter writer =
        new AsyncServletOutputStreamWriter(writeAction, flushAction, completeAction, isReadySupplier, new Log() {});

    // Initial onWritePossible to set readyAndDrained=true
    writer.onWritePossible();

    // Write 5 times, calling onWritePossible after each write.
    // With isReady staying true, each onWritePossible should set readyAndDrained back to true,
    // allowing the next write to go direct again.
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
    AtomicBoolean isReady = new AtomicBoolean(true);
    List<String> actions = new ArrayList<>();

    BiFunction<byte[], Integer, ActionItem> writeAction =
        (bytes, numBytes) -> () -> {
          actions.add("write");
        };

    ActionItem flushAction = () -> {
      actions.add("flush");
    };

    ActionItem completeAction = () -> {};

    BooleanSupplier isReadySupplier = () -> isReady.get();

    AsyncServletOutputStreamWriter writer =
        new AsyncServletOutputStreamWriter(writeAction, flushAction, completeAction, isReadySupplier, new Log() {});

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

    // Only the first flush should have executed
    assertEquals("Second flush should be buffered", 1, actions.size());

    // Container calls onWritePossible to drain buffered flush
    writer.onWritePossible();

    // Both flushes should have completed
    assertEquals("Both flushes should complete after onWritePossible", 2, actions.size());
  }
}