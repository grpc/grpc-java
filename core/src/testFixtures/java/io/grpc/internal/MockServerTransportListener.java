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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
import io.grpc.Metadata;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class MockServerTransportListener implements ServerTransportListener {
  public final ServerTransport transport;
  public final BlockingQueue<StreamCreation> streams = new LinkedBlockingQueue<>();
  private final SettableFuture<?> terminated = SettableFuture.create();

  public MockServerTransportListener(ServerTransport transport) {
    this.transport = transport;
  }

  @Override
  public void streamCreated(ServerStream stream, String method, Metadata headers) {
    ServerStreamListenerBase listener = new ServerStreamListenerBase();
    streams.add(new StreamCreation(stream, method, headers, listener));
    stream.setListener(listener);
  }

  @Override
  public Attributes transportReady(Attributes attributes) {
    assertFalse(terminated.isDone());
    return attributes;
  }

  @Override
  public void transportTerminated() {
    assertTrue(terminated.set(null));
  }

  public boolean waitForTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return AbstractTransportTest.waitForFuture(terminated, timeout, unit);
  }

  public boolean isTerminated() {
    return terminated.isDone();
  }

  public StreamCreation takeStreamOrFail(long timeout, TimeUnit unit) throws InterruptedException {
    StreamCreation stream = streams.poll(timeout, unit);
    if (stream == null) {
      fail("Timed out waiting for server stream");
    }
    return stream;
  }

  public static class StreamCreation {
    public final ServerStream stream;
    public final String method;
    public final Metadata headers;
    public final ServerStreamListenerBase listener;

    public StreamCreation(
        ServerStream stream, String method, Metadata headers, ServerStreamListenerBase listener) {
      this.stream = stream;
      this.method = method;
      this.headers = headers;
      this.listener = listener;
    }
  }
}
