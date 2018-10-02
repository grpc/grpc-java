/*
 * Copyright 2018 The gRPC Authors
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

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.inOrder;

import com.google.common.base.Charsets;
import io.grpc.internal.SerializingExecutor;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import okio.Buffer;
import okio.Sink;
import okio.Timeout;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;
import org.mockito.Mockito;

/** Tests for {@link AsyncSink}. */
@RunWith(JUnit4.class)
public class AsyncSinkTest {

  private Sink mockedSink;
  private QueueingExecutor queueingExecutor;
  private Sink sink;

  @Before
  public void setUp() throws Exception {
    mockedSink = Mockito.mock(VoidSink.class, CALLS_REAL_METHODS);
    queueingExecutor = new QueueingExecutor();
    sink = AsyncSink.sink(mockedSink, new SerializingExecutor(queueingExecutor));
  }

  @Test
  public void noCoalesceRequired() throws IOException {
    Buffer buffer = new Buffer();
    sink.write(buffer.writeUtf8("hello"), buffer.size());
    sink.flush();
    queueingExecutor.runAll();

    InOrder inOrder = inOrder(mockedSink);
    inOrder.verify(mockedSink).write(any(Buffer.class), anyInt());
    inOrder.verify(mockedSink).flush();
  }

  @Test
  public void flushCoalescing_shouldNotMergeTwoDistinctFlushes() throws IOException {
    byte[] firstData = "a string".getBytes(Charsets.UTF_8);
    byte[] secondData = "a longer string".getBytes(Charsets.UTF_8);

    Buffer buffer = new Buffer();
    sink.write(buffer.write(firstData), buffer.size());
    sink.flush();
    queueingExecutor.runAll();

    sink.write(buffer.write(secondData), buffer.size());
    sink.flush();
    queueingExecutor.runAll();

    InOrder inOrder = inOrder(mockedSink);
    inOrder.verify(mockedSink).write(any(Buffer.class), anyInt());
    inOrder.verify(mockedSink).flush();
    inOrder.verify(mockedSink).write(any(Buffer.class), anyInt());
    inOrder.verify(mockedSink).flush();
  }

  @Test
  public void flushCoalescing_shouldMergeTwoQueuedFlushesAndWrites() throws IOException {
    byte[] firstData = "a string".getBytes(Charsets.UTF_8);
    byte[] secondData = "a longer string".getBytes(Charsets.UTF_8);
    Buffer buffer = new Buffer().write(firstData);
    sink.write(buffer, buffer.size());
    sink.flush();
    buffer = new Buffer().write(secondData);
    sink.write(buffer, buffer.size());
    sink.flush();

    queueingExecutor.runAll();

    InOrder inOrder = inOrder(mockedSink);
    inOrder.verify(mockedSink)
        .write(any(Buffer.class), eq((long) firstData.length + secondData.length));
    inOrder.verify(mockedSink).flush();
  }

  @Test
  public void flushCoalescing_shouldMergeWrites() throws IOException {
    byte[] firstData = "a string".getBytes(Charsets.UTF_8);
    byte[] secondData = "a longer string".getBytes(Charsets.UTF_8);
    Buffer buffer = new Buffer();
    sink.write(buffer.write(firstData), buffer.size());
    sink.write(buffer.write(secondData), buffer.size());
    sink.flush();
    queueingExecutor.runAll();

    InOrder inOrder = inOrder(mockedSink);
    inOrder.verify(mockedSink)
        .write(any(Buffer.class), eq((long) firstData.length + secondData.length));
    inOrder.verify(mockedSink).flush();
  }

  /**
   * Executor queues incoming runnables instead of running it. Runnables can be invoked via {@link
   * QueueingExecutor#runAll} in serial order.
   */
  private static class QueueingExecutor implements Executor {

    private final Queue<Runnable> runnables = new ConcurrentLinkedQueue<>();

    @Override
    public void execute(Runnable command) {
      runnables.add(command);
    }

    public void runAll() {
      Runnable r;
      while ((r = runnables.poll()) != null) {
        r.run();
      }
    }
  }

  /** Test sink to mimic real Sink behavior since write has a side effect. */
  private static class VoidSink implements Sink {

    @Override
    public void write(Buffer source, long byteCount) throws IOException {
      // removes byteCount bytes from source.
      source.read(new byte[(int) byteCount], 0, (int) byteCount);
    }

    @Override
    public void flush() throws IOException {
      // do nothing
    }

    @Override
    public Timeout timeout() {
      return Timeout.NONE;
    }

    @Override
    public void close() throws IOException {
      // do nothing
    }
  }
}