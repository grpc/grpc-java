/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Created by notcarl on 9/1/17.
 */
final class ShardingSerializingExecutor implements SerializingExecutor.SerializingExecutorFactory {
  static ShardingSerializingExecutor newInstance(Executor delegate) {
    return newInstance(delegate, 1);
  }

  static ShardingSerializingExecutor newInstance(Executor delegate, int shardCount) {
    return new ShardingSerializingExecutor(delegate, shardCount);
  }

  private final ThreadLocalShard shardCycler;

  private ShardingSerializingExecutor(Executor delegate, int shardCount) {
    checkNotNull(delegate, "delegate");
    checkArgument(shardCount > 0, "Invalid shard count %s", shardCount);
    SingleSerializingExecutor[] shards = new SingleSerializingExecutor[shardCount];
    for (int i = 0; i < shardCount; i++) {
      shards[i] = new SingleSerializingExecutor(delegate);
    }
    shardCycler = new ThreadLocalShard(shards);
  }

  @Override
  public SingleSerializingExecutor getExecutor() {
    return shardCycler.getNext();
  }

  /**
   * Conveniently avoids boxing (Integer) and synchronization (AtomicInteger).  Also we don't have
   * to call set on the ThreadLocal.
   */
  private static final class Int {
    int value;
  }

  private static final class ThreadLocalShard extends ThreadLocal<Int> {
    private final SingleSerializingExecutor[] shards;

    @Override
    protected Int initialValue() {
      return new Int();
    }

    ThreadLocalShard(SingleSerializingExecutor[] shards) {
      this.shards = shards;
    }

    SingleSerializingExecutor getNext() {
      Int shardIdHolder = get();
      int shardId = shardIdHolder.value++;
      if (shardIdHolder.value >= shards.length) {
        shardIdHolder.value -= shards.length;
      }
      return shards[shardId];
    }
  }

  @SuppressWarnings("serial")
  static final class SingleSerializingExecutor extends ConcurrentLinkedQueue<Runnable>
      implements SerializingExecutor, Runnable {
    private static final Logger logger =
        Logger.getLogger(SingleSerializingExecutor.class.getName());
    private static final AtomicIntegerFieldUpdater<SingleSerializingExecutor> runStateUpdater =
        AtomicIntegerFieldUpdater.newUpdater(SingleSerializingExecutor.class, "runState");
    private static final int STOPPED = 0;
    private static final int RUNNING = -1;

    private final Executor delegate;
    private volatile int runState;

    SingleSerializingExecutor(Executor delegate) {
      this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
      add(checkNotNull(command, "command"));
      schedule(command);
    }

    void schedule(@Nullable Runnable command) {
      if (!runStateUpdater.compareAndSet(this, STOPPED, RUNNING)) {
        return;
      }
      boolean executed = false;
      try {
        delegate.execute(this);
        executed = true;
      } finally {
        // It is possible that at this point that there are still tasks in
        // the queue, it would be nice to keep trying but the error may not
        // be recoverable.  So we update our state and propagate so that if
        // our caller deems it recoverable we won't be stuck.
        if (!executed) {
          if (command != null) {
            // This case can only be reached if 'this' was not currently running, and we failed to
            // reschedule.  The item should still be in the queue for removal.
            // ConcurrentLinkedQueue claims that null elements are not allowed, but seems to not
            // throw if the item to remove is null.  If removable is present in the queue twice,
            // the wrong one may be removed.  It doesn't seem possible for this case to exist today.
            // This is important to run in case of RejectedExectuionException, so that future calls
            // to execute don't succeed and accidentally run a previous runnable.
            remove(command);
          }
          runStateUpdater.set(this, STOPPED);
        }
      }
    }

    @Override
    public void run() {
      Runnable r;
      try {
        while ((r = poll()) != null) {
          try {
            r.run();
          } catch (RuntimeException e) {
            // Log it and keep going.
            logger.log(Level.SEVERE, "Exception while executing runnable " + r, e);
          }
        }
      } finally {
        runStateUpdater.set(this, STOPPED);
      }
      if (!isEmpty()) {
        // we didn't enqueue anything but someone else did.
        schedule(null);
      }
    }
  }
}
