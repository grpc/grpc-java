/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.binder.internal;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.os.Binder;
import android.os.IBinder;
import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.internal.FixedObjectPool;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServerListener;
import io.grpc.internal.ServerStream;
import io.grpc.internal.ServerTransport;
import io.grpc.internal.ServerTransportListener;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class ActiveTransportTrackerTest {

  private static final OneWayBinderProxy.Decorator NO_OP_BINDER_DECORATOR = input -> input;

  private final ObjectPool<ScheduledExecutorService> executorServicePool =
      new FixedObjectPool<>(Executors.newSingleThreadScheduledExecutor());
  private final IBinder callbackBinder = new Binder();

  private boolean serverShutdownNotified = false;
  private final Runnable testShutdownListener = () -> serverShutdownNotified = true;
  private ActiveTransportTracker tracker;

  @Before
  public void setUp() {
    tracker = new ActiveTransportTracker(new NoOpServerListener(), testShutdownListener);
  }

  @Test
  public void testServerShutdown_onlyNotifiesAfterAllTransportAreTerminated() {
    ServerTransportListener wrapperListener1 = registerNewTransport();
    ServerTransportListener wrapperListener2 = registerNewTransport();

    tracker.serverShutdown();
    // 2 active transports, notification scheduled
    assertThat(serverShutdownNotified).isFalse();

    wrapperListener1.transportTerminated();
    // 1 active transport remaining, notification still pending
    assertThat(serverShutdownNotified).isFalse();

    wrapperListener2.transportTerminated();
    // No more active transports, shutdown notified
    assertThat(serverShutdownNotified).isTrue();
  }

  @Test
  public void testServerShutdown_noActiveTransport_notifiesTerminationImmediately() {
    checkState(!serverShutdownNotified);

    tracker.serverShutdown();

    assertThat(serverShutdownNotified).isTrue();
  }

  @Test
  public void testLastTransportTerminated_serverNotShutdownYet_doesNotNotify() {
    ServerTransportListener wrapperListener = registerNewTransport();
    assertThat(serverShutdownNotified).isFalse();

    wrapperListener.transportTerminated();

    assertThat(serverShutdownNotified).isFalse();
  }

  @Test
  public void testTransportCreation_afterServerShutdown_throws() {
    tracker.serverShutdown();

    assertThrows(IllegalStateException.class, this::registerNewTransport);
  }

  private ServerTransportListener registerNewTransport() {
    BinderTransport.BinderServerTransport transport =
        new BinderTransport.BinderServerTransport(
          executorServicePool,
          Attributes.EMPTY,
          /* streamTracerFactories= */ ImmutableList.of(),
          NO_OP_BINDER_DECORATOR,
          callbackBinder);

    return tracker.transportCreated(transport);
  }

  private static final class NoOpTransportListener implements ServerTransportListener {
    @Override
    public void streamCreated(ServerStream stream, String method, Metadata headers) {}

    @Override
    public Attributes transportReady(Attributes attributes) {
      return attributes;
    }

    @Override
    public void transportTerminated() {}
  }

  private static final class NoOpServerListener implements ServerListener {
    @Override
    public ServerTransportListener transportCreated(ServerTransport transport) {
      return new NoOpTransportListener();
    }

    @Override
    public void serverShutdown() {}
  }
}
