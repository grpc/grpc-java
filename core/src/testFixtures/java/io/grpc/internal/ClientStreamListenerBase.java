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
import io.grpc.Metadata;
import io.grpc.Status;
import java.io.InputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class ClientStreamListenerBase implements ClientStreamListener {
  public final BlockingQueue<InputStream> messageQueue = new LinkedBlockingQueue<>();
  // Would have used Void instead of Object, but null elements are not allowed
  private final BlockingQueue<Object> readyQueue = new LinkedBlockingQueue<>();
  private final SettableFuture<Metadata> headers = SettableFuture.create();
  private final SettableFuture<Metadata> trailers = SettableFuture.create();
  private final SettableFuture<Status> status = SettableFuture.create();

  /**
   * Returns the stream's status or throws {@link java.util.concurrent.TimeoutException} if it isn't
   * closed before the timeout.
   */
  public Status awaitClose(int timeout, TimeUnit unit) throws Exception {
    return status.get(timeout, unit);
  }

  /**
   * Return {@code true} if {@code #awaitClose} would return immediately with a status.
   */
  public boolean isClosed() {
    return status.isDone();
  }

  /**
   * Returns response headers from the server or throws {@link
   * java.util.concurrent.TimeoutException} if they aren't delivered before the timeout.
   *
   * <p>Callers must not modify the returned object.
   */
  public Metadata awaitHeaders(int timeout, TimeUnit unit) throws Exception {
    return headers.get(timeout, unit);
  }

  /**
   * Returns response trailers from the server or throws {@link
   * java.util.concurrent.TimeoutException} if they aren't delivered before the timeout.
   *
   * <p>Callers must not modify the returned object.
   */
  public Metadata awaitTrailers(int timeout, TimeUnit unit) throws Exception {
    return trailers.get(timeout, unit);
  }

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
  public void headersRead(Metadata headers) {
    if (status.isDone()) {
      fail("headersRead invoked after closed");
    }
    this.headers.set(headers);
  }

  @Override
  public void closed(Status status, RpcProgress rpcProgress, Metadata trailers) {
    if (this.status.isDone()) {
      fail("headersRead invoked after closed");
    }
    this.status.set(status);
    this.trailers.set(trailers);
  }

  /** Returns true iff response headers have been received from the server. */
  public boolean hasHeaders() {
    return headers.isDone();
  }
}
