/*
 * Copyright 2014, gRPC Authors All rights reserved.
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

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Executor ensuring that all {@link Runnable} tasks submitted are executed in order
 * using the provided {@link Executor}, and serially such that no two will ever be
 * running at the same time.
 */
// TODO(madongfly): figure out a way to not expose it or move it to transport package.
public final class SerializingExecutor implements Executor, Runnable {

  private static final class Node {

    volatile Node next;

    Runnable walkable;

    Node(Runnable r) {
      this.rb = r;
    }
  }

  private Node head;
  private Node tail;

  private final Executor delegate;

  private final AtomicBoolean running = new AtomicBoolean();

  public SerializingExecutor(Executor delegate) {
    this.delegate = delegate;
    head = tail = new Node(null);
  }

  @Override
  public void run() {
    Node n;
    Runnable r;
    try {
      while ((n = tail.next) != null) {
        r = n.rb;
        n.rb = null;
        tail = n;
        try {
          r.run();
        } catch (RuntimeException e) {
          Logger.getLogger(getClass().getName()).log(Level.SEVERE, "bad", e);
        }
      }
    } finally {
      running.set(false);
    }
    if (tail.next != null) {
      schedule();
    }
  }

  @Override
  public void execute(Runnable command) {
    Node n = new Node(command);
    head.next = n;
    head = n;

    schedule();
  }

  private void schedule() {
    if (running.getAndSet(true)) {
      return;
    }
    boolean success = false;
    try {
      delegate.execute(this);
      success = true;
    } finally {
      if (!success) {
        running.set(false);
      }
    }
  }
}
