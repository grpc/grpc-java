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

import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.internal.verification.VerificationModeFactory.times;

import io.grpc.internal.SerializingExecutor;
import io.grpc.okhttp.internal.framed.FrameWriter;
import java.io.IOException;
import java.net.Socket;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class AsyncFrameWriterTest {

  @Mock private OkHttpClientTransport transport;
  @Mock private Socket socket;
  @Mock private FrameWriter frameWriter;

  private QueueingExecutor queueingExecutor;
  private AsyncFrameWriter asyncFrameWriter;

  @Before
  public void setUp() throws Exception {
    queueingExecutor = new QueueingExecutor();
    asyncFrameWriter = new AsyncFrameWriter(transport, new SerializingExecutor(queueingExecutor));
    asyncFrameWriter.becomeConnected(frameWriter, socket);
  }

  @After
  public void tearDown() throws Exception {
    asyncFrameWriter.close();
  }

  @Test
  public void noCoalesceRequired() throws IOException {
    asyncFrameWriter.ping(true, 0, 1);
    asyncFrameWriter.flush();
    queueingExecutor.runAll();

    verify(frameWriter, times(1)).ping(anyBoolean(), anyInt(), anyInt());
    verify(frameWriter, times(1)).flush();
  }

  @Test
  public void flushCoalescing_shouldNotMergeTwoDistinctFlushes() throws IOException {
    asyncFrameWriter.ping(true, 0, 1);
    asyncFrameWriter.flush();
    queueingExecutor.runAll();

    asyncFrameWriter.ping(true, 0, 2);
    asyncFrameWriter.flush();
    queueingExecutor.runAll();

    verify(frameWriter, times(2)).ping(anyBoolean(), anyInt(), anyInt());
    verify(frameWriter, times(2)).flush();
  }

  @Test
  public void flushCoalescing_shouldMergeTwoQueuedFlushes() throws IOException {
    asyncFrameWriter.ping(true, 0, 1);
    asyncFrameWriter.flush();
    asyncFrameWriter.ping(true, 0, 2);
    asyncFrameWriter.flush();

    queueingExecutor.runAll();

    InOrder inOrder = inOrder(frameWriter);
    inOrder.verify(frameWriter, times(2)).ping(anyBoolean(), anyInt(), anyInt());
    inOrder.verify(frameWriter).flush();
  }

  /**
   * Executor queues incoming runnables instead of running it. Runnables can be invoked via {@link
   * QueueingExecutor#runAll} in serial order.
   */
  private static class QueueingExecutor implements Executor {

    private final Queue<Runnable> runnables = new ConcurrentLinkedQueue<Runnable>();

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
}
