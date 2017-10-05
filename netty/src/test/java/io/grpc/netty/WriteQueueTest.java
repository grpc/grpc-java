/*
 * Copyright 2016, gRPC Authors All rights reserved.
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class WriteQueueTest {
  private final EmbeddedChannel channel = new EmbeddedChannel();

  private Recorder recorder;

  @Test
  public void singleWriteShouldWork() {
    channel.pipeline().addLast(recorder = new Recorder());
    CuteCommand cmd = new CuteCommand();
    WriteQueue queue = new WriteQueue(channel);

    queue.enqueue(cmd, true);
    channel.runPendingTasks();

    assertThat(recorder.msgs).containsExactly(cmd);
    assertThat(recorder.flushes).isEqualTo(1);
  }

  @Test
  public void multipleWritesShouldBeBatched() {
    channel.pipeline().addLast(recorder = new Recorder());
    WriteQueue queue = new WriteQueue(channel);
    List<CuteCommand> cmds = new ArrayList<CuteCommand>();
    for (int i = 0; i < 5; i++) {
      cmds.add(new CuteCommand());
      queue.enqueue(cmds.get(i), false);
    }

    queue.scheduleFlush();
    channel.runPendingTasks();

    assertThat(recorder.msgs).containsExactlyElementsIn(cmds).inOrder();
    assertThat(recorder.flushes).isEqualTo(1);
  }

  @Test
  public void maxWritesBeforeFlushShouldBeEnforced() {
    channel.pipeline().addLast(recorder = new Recorder());
    WriteQueue queue = new WriteQueue(channel);
    List<CuteCommand> cmds = new ArrayList<CuteCommand>();
    channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
    int writes = WriteQueue.FLUSH_LIMIT + 10;
    for (int i = 0; i < writes; i++) {
      cmds.add(new CuteCommand());
      queue.enqueue(cmds.get(i), false);
    }

    channel.isWritable();
    queue.scheduleFlush();
    channel.runPendingTasks();

    assertThat(recorder.msgs).containsExactlyElementsIn(cmds);
    assertThat(recorder.flushes).isEqualTo(2);
  }

  @Test
  public void concurrentWriteAndFlush() throws Throwable {
    final AtomicLong lastWriteNanos = new AtomicLong();
    final AtomicLong lastFlushNanos = new AtomicLong();

    channel.pipeline().addLast(recorder = new Recorder() {
      @Override
      public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
          throws Exception {
        lastWriteNanos.set(System.nanoTime());
        super.write(ctx, msg, promise);
      }

      @Override
      public void flush(ChannelHandlerContext ctx) throws Exception {
        lastFlushNanos.set(System.nanoTime());
        super.flush(ctx);
      }
    });
    channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
    final WriteQueue queue = new WriteQueue(channel);
    final CountDownLatch flusherStarted = new CountDownLatch(1);
    final AtomicBoolean doneWriting = new AtomicBoolean();
    Thread flusher = new Thread(new Runnable() {
      @Override
      public void run() {
        flusherStarted.countDown();
        while (!doneWriting.get()) {
          queue.scheduleFlush();
          channel.runPendingTasks();
          assertFlushCalledAfterWrites();
        }
        // No more writes, so this flush should drain all writes from the queue
        queue.scheduleFlush();
        channel.runPendingTasks();
        assertFlushCalledAfterWrites();
      }

      void assertFlushCalledAfterWrites() {
          if (lastFlushNanos.get() - lastWriteNanos.get() <= 0) {
            fail("flush must be called after all writes");
          }
      }
    });

    final class ExceptionHandler implements Thread.UncaughtExceptionHandler {
      private Throwable throwable;

      @Override
      public void uncaughtException(Thread t, Throwable e) {
        throwable = e;
      }

      void checkException() throws Throwable {
        if (throwable != null) {
          throw throwable;
        }
      }
    }

    ExceptionHandler exHandler = new ExceptionHandler();
    flusher.setUncaughtExceptionHandler(exHandler);

    flusher.start();
    assertTrue(flusherStarted.await(5, TimeUnit.SECONDS));
    int writes = 10 * WriteQueue.FLUSH_LIMIT;
    for (int i = 0; i < writes; i++) {
      queue.enqueue(new CuteCommand(), false);
    }
    doneWriting.set(true);
    flusher.join(TimeUnit.SECONDS.toMillis(5));
    assertFalse("couldn't join thread", flusher.isAlive());

    exHandler.checkException();
    assertThat(recorder.msgs).hasSize(writes);
  }

  static class CuteCommand extends WriteQueue.AbstractQueuedCommand {

  }

  private static class Recorder extends ChannelOutboundHandlerAdapter {
    final List<Object> msgs = new ArrayList<Object>();
    final List<Object> promises = new ArrayList<Object>();
    int flushes;

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
        throws Exception {
      super.write(ctx, msg, promise);
      msgs.add(msg);
      promises.add(promise);
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
      super.flush(ctx);
      flushes++;
    }
  }
}
