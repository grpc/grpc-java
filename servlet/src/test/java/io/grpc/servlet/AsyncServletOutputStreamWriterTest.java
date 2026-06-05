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
import static org.junit.Assert.assertThrows;

import io.grpc.servlet.AsyncServletOutputStreamWriter.ActionItem;
import io.grpc.servlet.AsyncServletOutputStreamWriter.Log;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit test for {@link AsyncServletOutputStreamWriter} with a mock isReady supplier. */
@RunWith(JUnit4.class)
public class AsyncServletOutputStreamWriterTest {

  @Test
  public void writeBytes_notReadyException_buffersUntilOnWritePossible() throws IOException {
    List<String> actions = new ArrayList<>();
    AtomicBoolean rejectWrites = new AtomicBoolean(true);

    BiFunction<byte[], Integer, ActionItem> writeAction =
        (bytes, numBytes) -> () -> {
          if (rejectWrites.get()) {
            throw new IllegalStateException("not ready");
          }
          actions.add("write");
        };
    ActionItem flushAction = () -> { };
    ActionItem completeAction = () -> { };

    AsyncServletOutputStreamWriter writer =
        new AsyncServletOutputStreamWriter(
            writeAction, flushAction, completeAction, () -> true, new Log() {});

    writer.onWritePossible();

    writer.writeBytes(new byte[]{1}, 1);

    assertEquals("Write should be buffered until onWritePossible", 0, actions.size());

    rejectWrites.set(false);
    writer.onWritePossible();
    assertEquals("Buffered write should drain after onWritePossible", 1, actions.size());
  }

  @Test
  public void writeBytes_consecutiveWithIsReadyTrue_allGoDirect() throws IOException {
    List<byte[]> writtenData = new ArrayList<>();

    BiFunction<byte[], Integer, ActionItem> writeAction =
        (bytes, numBytes) -> () -> writtenData.add(Arrays.copyOf(bytes, numBytes));
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
  public void writeBytes_isReadyFalseAfterWrite_buffersNextWrite() throws IOException {
    List<byte[]> writtenData = new ArrayList<>();
    AtomicBoolean isReady = new AtomicBoolean(true);

    BiFunction<byte[], Integer, ActionItem> writeAction =
        (bytes, numBytes) -> () -> {
          writtenData.add(Arrays.copyOf(bytes, numBytes));
          isReady.set(false);
        };
    ActionItem flushAction = () -> { };
    ActionItem completeAction = () -> { };

    AsyncServletOutputStreamWriter writer =
        new AsyncServletOutputStreamWriter(
            writeAction, flushAction, completeAction, isReady::get, new Log() {});

    writer.onWritePossible();

    writer.writeBytes(new byte[]{1}, 1);
    writer.writeBytes(new byte[]{2}, 1);
    assertEquals("Second write should be buffered", 1, writtenData.size());

    isReady.set(true);
    writer.onWritePossible();
    assertEquals("Buffered write should drain", 2, writtenData.size());
  }

  @Test
  public void flush_isReadyFalse_buffersUntilOnWritePossible() throws IOException {
    List<String> actions = new ArrayList<>();
    AtomicBoolean isReady = new AtomicBoolean(true);

    BiFunction<byte[], Integer, ActionItem> writeAction =
        (bytes, numBytes) -> () -> actions.add("write");
    ActionItem flushAction = () -> {
      actions.add("flush");
      isReady.set(false);
    };
    ActionItem completeAction = () -> { };

    AsyncServletOutputStreamWriter writer =
        new AsyncServletOutputStreamWriter(
            writeAction, flushAction, completeAction, isReady::get, new Log() {});

    writer.onWritePossible();

    writer.flush();
    assertEquals("First flush should execute directly", 1, actions.size());

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
        (bytes, numBytes) -> () -> actions.add("write");
    ActionItem flushAction = () -> actions.add("flush");
    ActionItem completeAction = () -> { };

    AsyncServletOutputStreamWriter writer =
        new AsyncServletOutputStreamWriter(
            writeAction, flushAction, completeAction, () -> true, new Log() {});

    writer.onWritePossible();

    writer.flush();
    writer.flush();

    assertEquals("Both flushes should execute directly", 2, actions.size());
  }

  @Test
  public void complete_readyAndDrained_runsDirectly() throws IOException {
    AtomicInteger completeCount = new AtomicInteger();

    AsyncServletOutputStreamWriter writer =
        new AsyncServletOutputStreamWriter(
            (bytes, numBytes) -> () -> { },
            () -> { },
            completeCount::incrementAndGet,
            () -> true,
            new Log() {});

    writer.onWritePossible();

    writer.complete();

    assertEquals(1, completeCount.get());
  }

  @Test
  public void complete_notReadyAndDrained_buffersUntilOnWritePossible() throws IOException {
    AtomicInteger completeCount = new AtomicInteger();

    AsyncServletOutputStreamWriter writer =
        new AsyncServletOutputStreamWriter(
            (bytes, numBytes) -> () -> { },
            () -> { },
            completeCount::incrementAndGet,
            () -> true,
            new Log() {});

    writer.complete();
    assertEquals(0, completeCount.get());

    writer.onWritePossible();
    assertEquals(1, completeCount.get());
  }

  @Test
  public void writeBytes_onWritePossibleWinsRace_drainsBufferedWrite() throws Exception {
    List<String> actions = new ArrayList<>();
    AsyncServletOutputStreamWriter writer =
        new AsyncServletOutputStreamWriter(
            (bytes, numBytes) -> () -> actions.add("write"),
            () -> {
            },
            () -> {
            },
            () -> true,
            new Log() {
            });
    replaceWriteChain(writer, new ConcurrentLinkedQueue<ActionItem>() {
      @Override
      public boolean offer(ActionItem actionItem) {
        boolean offered = super.offer(actionItem);
        try {
          writer.onWritePossible();
        } catch (IOException e) {
          throw new AssertionError(e);
        }
        return offered;
      }
    });

    writer.writeBytes(new byte[]{1}, 1);

    assertEquals(1, actions.size());
  }

  @Test
  public void writeBytes_readyStateWinsRace_retriesWrite() throws Exception {
    List<String> actions = new ArrayList<>();
    AsyncServletOutputStreamWriter writer =
        new AsyncServletOutputStreamWriter(
            (bytes, numBytes) -> () -> actions.add("write"),
            () -> {
            },
            () -> {
            },
            () -> true,
            new Log() {
            });
    replaceWriteChain(writer, new ConcurrentLinkedQueue<ActionItem>() {
      @Override
      public boolean offer(ActionItem actionItem) {
        boolean offered = super.offer(actionItem);
        try {
          forceReadyAndDrained(writer);
        } catch (ReflectiveOperationException e) {
          throw new AssertionError(e);
        }
        return offered;
      }
    });

    writer.writeBytes(new byte[]{1}, 1);

    assertEquals(1, actions.size());
  }

  private static void replaceWriteChain(
      AsyncServletOutputStreamWriter writer, ConcurrentLinkedQueue<ActionItem> writeChain)
      throws ReflectiveOperationException {
    Field writeChainField = AsyncServletOutputStreamWriter.class.getDeclaredField("writeChain");
    writeChainField.setAccessible(true);
    writeChainField.set(writer, writeChain);
  }

  private static void forceReadyAndDrained(AsyncServletOutputStreamWriter writer)
      throws ReflectiveOperationException {
    Field writeStateField = AsyncServletOutputStreamWriter.class.getDeclaredField("writeState");
    writeStateField.setAccessible(true);
    @SuppressWarnings("unchecked")
    AtomicReference<Object> writeState =
        (AtomicReference<Object>) writeStateField.get(writer);
    Object curState = writeState.get();
    Method withReadyAndDrained = curState.getClass().getDeclaredMethod(
        "withReadyAndDrained", boolean.class);
    withReadyAndDrained.setAccessible(true);
    writeState.set(withReadyAndDrained.invoke(curState, true));
  }

  @Test
  public void flush_notReadyException_isPropagated() throws IOException {
    AsyncServletOutputStreamWriter writer =
        new AsyncServletOutputStreamWriter(
            (bytes, numBytes) -> () -> { },
            () -> {
              throw new IllegalStateException("not ready");
            },
            () -> { },
            () -> true,
            new Log() {});

    writer.onWritePossible();

    assertThrows(IllegalStateException.class, writer::flush);
  }
}
