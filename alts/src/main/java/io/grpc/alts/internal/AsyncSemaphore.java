/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.alts.internal;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.util.LinkedList;
import java.util.Queue;
import javax.annotation.concurrent.GuardedBy;

/** Provides a semaphore primitive, without blocking waiting on permits. */
final class AsyncSemaphore {
  private final Object lock = new Object();
  @SuppressWarnings("JdkObsolete") // LinkedList avoids high watermark memory issues
  private final Queue<ChannelPromise> queue = new LinkedList<>();
  @GuardedBy("lock")
  private int permits;

  public AsyncSemaphore(int permits) {
    this.permits = permits;
  }

  public ChannelFuture acquire(ChannelHandlerContext ctx) {
    synchronized (lock) {
      if (permits > 0) {
        permits--;
        return ctx.newSucceededFuture();
      }
      ChannelPromise promise = ctx.newPromise();
      queue.add(promise);
      return promise;
    }
  }

  public void release() {
    ChannelPromise next;
    synchronized (lock) {
      next = queue.poll();
      if (next == null) {
        permits++;
        return;
      }
    }
    next.setSuccess();
  }
}
