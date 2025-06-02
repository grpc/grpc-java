/*
 * Copyright 2025 The gRPC Authors
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

import static org.junit.Assert.fail;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Status;
import java.io.InputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A {@link ServerStreamListener} that helps you write unit tests.
 */
public class ServerStreamListenerBase implements ServerStreamListener {
  public final BlockingQueue<InputStream> messageQueue = new LinkedBlockingQueue<>();
  // Would have used Void instead of Object, but null elements are not allowed
  private final BlockingQueue<Object> readyQueue = new LinkedBlockingQueue<>();
  private final CountDownLatch halfClosedLatch = new CountDownLatch(1);
  private final SettableFuture<Status> status = SettableFuture.create();

  public boolean awaitOnReady(int timeout, TimeUnit unit) throws Exception {
    return readyQueue.poll(timeout, unit) != null;
  }

  public boolean awaitOnReadyAndDrain(int timeout, TimeUnit unit) throws Exception {
    if (!awaitOnReady(timeout, unit)) {
      return false;
    }
    // Throw the rest away
    readyQueue.drainTo(Lists.newArrayList());
    return true;
  }

  public boolean awaitHalfClosed(int timeout, TimeUnit unit) throws Exception {
    return halfClosedLatch.await(timeout, unit);
  }

  public Status awaitClose(int timeout, TimeUnit unit) throws Exception {
    return status.get(timeout, unit);
  }

  @Override
  public void messagesAvailable(MessageProducer producer) {
    if (status.isDone()) {
      fail("messagesAvailable invoked after closed");
    }
    InputStream message;
    while ((message = producer.next()) != null) {
      messageQueue.add(message);
    }
  }

  @Override
  public void onReady() {
    if (status.isDone()) {
      fail("onReady invoked after closed");
    }
    readyQueue.add(new Object());
  }

  @Override
  public void halfClosed() {
    if (status.isDone()) {
      fail("halfClosed invoked after closed");
    }
    halfClosedLatch.countDown();
  }

  @Override
  public void closed(Status status) {
    if (this.status.isDone()) {
      fail("closed invoked more than once");
    }
    this.status.set(status);
  }
}
