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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A {@link ServerListener} that helps you write blocking unit tests.
 *
 * <p>TODO: Rename, since this is not actually a mock:
 * https://testing.googleblog.com/2013/07/testing-on-toilet-know-your-test-doubles.html
 */
public class MockServerListener implements ServerListener {
  private final BlockingQueue<MockServerTransportListener> listeners = new LinkedBlockingQueue<>();
  private final SettableFuture<?> shutdown = SettableFuture.create();
  private final ServerTransportListenerFactory serverTransportListenerFactory;

  public interface ServerTransportListenerFactory {
    MockServerTransportListener create(ServerTransport transport);
  }

  public MockServerListener(ServerTransportListenerFactory serverTransportListenerFactory) {
    this.serverTransportListenerFactory = serverTransportListenerFactory;
  }

  public MockServerListener() {
    this(MockServerTransportListener::new);
  }

  @Override
  public ServerTransportListener transportCreated(ServerTransport transport) {
    MockServerTransportListener listener = serverTransportListenerFactory.create(transport);
    listeners.add(listener);
    return listener;
  }

  @Override
  public void serverShutdown() {
    assertTrue(shutdown.set(null));
  }

  public boolean waitForShutdown(long timeout, TimeUnit unit) throws InterruptedException {
    return AbstractTransportTest.waitForFuture(shutdown, timeout, unit);
  }

  public MockServerTransportListener takeListenerOrFail(long timeout, TimeUnit unit)
      throws InterruptedException {
    MockServerTransportListener listener = listeners.poll(timeout, unit);
    if (listener == null) {
      fail("Timed out waiting for server transport");
    }
    return listener;
  }
}
