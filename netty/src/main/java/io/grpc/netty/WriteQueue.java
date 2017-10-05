/*
 * Copyright 2015, gRPC Authors All rights reserved.
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A queue of pending writes to a {@link Channel} that is flushed as a single unit.
 */
class WriteQueue implements Runnable {

  // Dequeue in chunks, so we don't have to acquire the queue's log too often.
  @VisibleForTesting
  static final int FLUSH_LIMIT = 128;

  private final Channel channel;
  private final Queue<QueuedCommand> queue = new ConcurrentLinkedQueue<QueuedCommand>();
  private final AtomicBoolean scheduled = new AtomicBoolean();

  public WriteQueue(Channel channel) {
    this.channel = Preconditions.checkNotNull(channel, "channel");
  }

  /**
   * Schedule a flush on the channel.
   */
  void scheduleFlush() {
    if (scheduled.compareAndSet(false, true)) {
      // Add the queue to the tail of the event loop so writes will be executed immediately
      // inside the event loop. Note DO NOT do channel.write outside the event loop as
      // it will not wake up immediately without a flush.
      channel.eventLoop().execute(this);
    }
  }

  /**
   * Enqueue a write command on the channel.
   *
   * @param command a write to be executed on the channel.
   * @param flush true if a flush of the write should be schedule, false if a later call to
   *              enqueue will schedule the flush.
   */
  @CanIgnoreReturnValue
  ChannelFuture enqueue(QueuedCommand command, boolean flush) {
    return enqueue(command, channel.newPromise(), flush);
  }

  /**
   * Enqueue a write command with a completion listener.
   *
   * @param command a write to be executed on the channel.
   * @param promise to be marked on the completion of the write.
   * @param flush true if a flush of the write should be schedule, false if a later call to
   *              enqueue will schedule the flush.
   */
  @CanIgnoreReturnValue
  ChannelFuture enqueue(QueuedCommand command, ChannelPromise promise, boolean flush) {
    // Detect erroneous code that tries to reuse command objects.
    Preconditions.checkArgument(command.promise() == null, "promise must not be set on command");

    command.promise(promise);
    queue.add(command);
    if (flush) {
      scheduleFlush();
    }
    return promise;

  }

  /**
   * Enqueue the runnable. It is not safe for another thread to queue an Runnable directly to the
   * event loop, because it will be out-of-order with writes. This method allows the Runnable to be
   * processed in-order with writes.
   */
  void enqueue(Runnable runnable, boolean flush) {
    queue.add(new RunnableCommand(runnable));
    if (flush) {
      scheduleFlush();
    }
  }

  /**
   * Process the queue of commands and dispatch them to the stream. This method is only
   * called in the event loop
   */
  @Override
  @SuppressWarnings("ShortCircuitBoolean")
  public void run() {
    int writes = 0;
    QueuedCommand cmd;
    boolean wasWritable = channel.isWritable();
    try {
      do {
        while ((cmd = queue.poll()) != null) {
          cmd.run(channel);
          // There are two reasons to leave the loop:
          // 1.  The channel's writability changed.
          // 2.  We've done a lot of writes.
          // Either way, we want to try another flush.  Use the non shortcircuiting | operator to
          // make sure
          if ((wasWritable != (wasWritable = channel.isWritable())) | (++writes == FLUSH_LIMIT)) {
            // Always reset buffered writes, even in the good case.  We may have just exited the
            // bad flush state, so we need to reset the counter.
            writes = 0;
            break;
          }
        }
        channel.flush();
        wasWritable = channel.isWritable();
      } while (cmd != null);
    } finally {
      // Mark the write as done, if the queue is non-empty after marking trigger a new write.
      scheduled.set(false);
      if (!queue.isEmpty()) {
        scheduleFlush();
      }
    }
  }

  private static class RunnableCommand implements QueuedCommand {
    private final Runnable runnable;

    public RunnableCommand(Runnable runnable) {
      this.runnable = runnable;
    }

    @Override
    public final void promise(ChannelPromise promise) {
      throw new UnsupportedOperationException();
    }

    @Override
    public final ChannelPromise promise() {
      throw new UnsupportedOperationException();
    }

    @Override
    public final void run(Channel channel) {
      runnable.run();
    }
  }

  abstract static class AbstractQueuedCommand implements QueuedCommand {

    private ChannelPromise promise;

    @Override
    public final void promise(ChannelPromise promise) {
      this.promise = promise;
    }

    @Override
    public final ChannelPromise promise() {
      return promise;
    }

    @Override
    public final void run(Channel channel) {
      channel.write(this, promise);
    }
  }

  /**
   * Simple wrapper type around a command and its optional completion listener.
   */
  interface QueuedCommand {
    /**
     * Returns the promise beeing notified of the success/failure of the write.
     */
    ChannelPromise promise();

    /**
     * Sets the promise.
     */
    void promise(ChannelPromise promise);

    void run(Channel channel);
  }
}
