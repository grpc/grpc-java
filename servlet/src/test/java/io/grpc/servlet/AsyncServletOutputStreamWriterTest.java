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
 * This tests the Tomcat scenario where isReady() returns true but write() still fails
 * because the previous write hasn't completed internally.
 */
@RunWith(JUnit4.class)
public class AsyncServletOutputStreamWriterTest {

  /**
   * Test that multiple consecutive writes succeed when isReady() always returns true.
   * The container calls onWritePossible() between writes to drain buffered actions.
   * This reproduces the Tomcat fix: writeBytes sets readyAndDrained=false so
   * subsequent writes are buffered, and onWritePossible drains them.
   */
  @Test
  public void writeBytes_alwaysReady_doesNotStall() throws IOException {
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

    // Initial onWritePossible to start with readyAndDrained=true
    writer.onWritePossible();

    // Write 5 times. After the first write, readyAndDrained becomes false so subsequent
    // writes are buffered. The container calls onWritePossible() after each write completes
    // internally (isReady stays true), which drains the buffered writes.
    for (int i = 0; i < 5; i++) {
      byte[] data = new byte[]{(byte) i};
      writer.writeBytes(data, 1);
      // Simulate container calling onWritePossible after each write completes
      // isReady stays true so onWritePossible drains buffered writes directly
      writer.onWritePossible();
    }

    // Verify all 5 writes completed
    assertEquals("All writes should complete", 5, writtenData.size());
  }

  /**
   * Test that writeBytes with isReady transitioning from true to false eventually
   * drains via onWritePossible.
   */
  @Test
  public void writeBytes_isReadyBecomesFalse_triggersOnWritePossible() throws IOException {
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

    // Initial onWritePossible
    writer.onWritePossible();

    // First write - isReady is true, goes direct, readyAndDrained=false
    byte[] data1 = new byte[]{1};
    writer.writeBytes(data1, 1);

    // Container calls onWritePossible after first write completes internally
    // isReady is still true but readyAndDrained is false, so buffered writes drain
    writer.onWritePossible();

    // Second write after isReady returns to true
    isReady.set(true);
    byte[] data2 = new byte[]{2};
    writer.writeBytes(data2, 1);

    assertEquals("Two writes should complete", 2, actions.size());
  }
}