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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit test for {@link AsyncServletOutputStreamWriter} with a mock isReady supplier. */
@RunWith(JUnit4.class)
public class AsyncServletOutputStreamWriterTest {

  @Test
  public void writeBytes_isReadyFalse_buffersUntilOnWritePossible() throws IOException {
    List<String> actions = new ArrayList<>();

    BiFunction<byte[], Integer, ActionItem> writeAction =
        (bytes, numBytes) -> () -> {
          actions.add("write");
        };
    ActionItem flushAction = () -> { };
    ActionItem completeAction = () -> { };
    AtomicBoolean isReady = new AtomicBoolean(true);

    AsyncServletOutputStreamWriter writer =
        new AsyncServletOutputStreamWriter(
            writeAction, flushAction, completeAction, isReady::get, new Log() {});

    writer.onWritePossible();

    isReady.set(false);
    writer.writeBytes(new byte[]{1}, 1);

    assertEquals("Write should be buffered until onWritePossible", 0, actions.size());

    isReady.set(true);
    writer.onWritePossible();
    assertEquals("Buffered write should drain after onWritePossible", 1, actions.size());
  }

  @Test
  public void writeBytes_consecutiveWithIsReadyTrue_allGoDirect() throws IOException {
    List<byte[]> writtenData = new ArrayList<>();

    BiFunction<byte[], Integer, ActionItem> writeAction =
        (bytes, numBytes) -> () -> {
          writtenData.add(Arrays.copyOf(bytes, numBytes));
        };
    ActionItem flushAction = () -> { };
    ActionItem completeAction = () -> { };

    AsyncServletOutputStreamWriter writer =
        new AsyncServletOutputStreamWriter(
            writeAction, flushAction, completeAction, () -> true, new Log() {});

    writer.onWritePossible();

    for (int i = 0; i < 5; i++) {
      writer.writeBytes(new byte[]{(byte) i}, 1);
    }

    assertEquals("All writes should complete", 5, writtenData.size());
  }

  @Test
  public void flush_isReadyFalse_buffersUntilOnWritePossible() throws IOException {
    List<String> actions = new ArrayList<>();

    BiFunction<byte[], Integer, ActionItem> writeAction =
        (bytes, numBytes) -> () -> {
          actions.add("write");
        };
    ActionItem flushAction = () -> {
      actions.add("flush");
    };
    ActionItem completeAction = () -> { };
    AtomicBoolean isReady = new AtomicBoolean(true);

    AsyncServletOutputStreamWriter writer =
        new AsyncServletOutputStreamWriter(
            writeAction, flushAction, completeAction, isReady::get, new Log() {});

    writer.onWritePossible();

    writer.flush();
    assertEquals("First flush should execute directly", 1, actions.size());

    isReady.set(false);
    writer.flush();
    assertEquals("Second flush should be buffered", 1, actions.size());

    isReady.set(true);
    writer.onWritePossible();
    assertEquals("Both flushes should complete after onWritePossible", 2, actions.size());
  }

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
    ActionItem completeAction = () -> { };

    AsyncServletOutputStreamWriter writer =
        new AsyncServletOutputStreamWriter(
            writeAction, flushAction, completeAction, () -> true, new Log() {});

    writer.onWritePossible();

    writer.flush();
    writer.flush();

    assertEquals("Both flushes should execute directly", 2, actions.size());
  }
}
