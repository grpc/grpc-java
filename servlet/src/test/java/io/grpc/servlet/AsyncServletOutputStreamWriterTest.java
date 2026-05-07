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
 * Tests two scenarios: (1) writes with isReady always true, where onWritePossible()
 * is called between writes; (2) writes with isReady becoming false, where buffered
 * writes are only drained when onWritePossible() is called.
 */
@RunWith(JUnit4.class)
public class AsyncServletOutputStreamWriterTest {

  /**
   * Test that multiple consecutive writes succeed when isReady() always returns true
   * and the container calls onWritePossible() between writes.
   *
   * <p>Each writeBytes() call is followed by onWritePossible() to drain any buffered
   * writes. This validates that the writer can handle the Tomcat-style callback pattern
   * where isReady() stays true but onWritePossible() fires between writes.
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
   * Test that when isReady() returns false, writes are buffered and only drain
   * when onWritePossible() is called.
   */
  @Test
  public void writeBytes_isReadyFalse_buffersUntilOnWritePossible() throws IOException {
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

    // First write - isReady is true, goes direct, readyAndDrained=false
    byte[] data1 = new byte[]{1};
    writer.writeBytes(data1, 1);

    // Simulate isReady becoming false (container buffer full)
    isReady.set(false);

    // Second write - should be buffered since isReady=false
    byte[] data2 = new byte[]{2};
    writer.writeBytes(data2, 1);

    // Only the first write should have executed
    assertEquals("First write should complete, second buffered", 1, actions.size());

    // Container calls onWritePossible to signal readiness
    isReady.set(true);
    writer.onWritePossible();

    // Now both writes should have completed
    assertEquals("Both writes should complete after onWritePossible", 2, actions.size());
  }
}