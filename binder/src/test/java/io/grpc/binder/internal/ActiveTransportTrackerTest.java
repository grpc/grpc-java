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

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.internal.ServerListener;
import io.grpc.internal.ServerTransport;
import io.grpc.internal.ServerTransportListener;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class ActiveTransportTrackerTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private ActiveTransportTracker tracker;

  @Mock Runnable mockShutdownListener;
  @Mock ServerListener mockServerListener;
  @Mock ServerTransportListener mockServerTransportListener;
  @Mock ServerTransport mockServerTransport;

  @Before
  public void setUp() {
    when(mockServerListener.transportCreated(any())).thenReturn(mockServerTransportListener);
    tracker = new ActiveTransportTracker(mockServerListener, mockShutdownListener);
  }

  @Test
  public void testServerShutdown_onlyNotifiesAfterAllTransportAreTerminated() {
    ServerTransportListener wrapperListener1 = registerNewTransport();
    ServerTransportListener wrapperListener2 = registerNewTransport();

    tracker.serverShutdown();
    // 2 active transports, notification scheduled
    verifyNoInteractions(mockShutdownListener);

    wrapperListener1.transportTerminated();
    // 1 active transport remaining, notification still pending
    verifyNoInteractions(mockShutdownListener);

    wrapperListener2.transportTerminated();
    // No more active transports, shutdown notified
    verify(mockShutdownListener).run();
  }

  @Test
  public void testServerShutdown_noActiveTransport_notifiesTerminationImmediately() {
    verifyNoInteractions(mockShutdownListener);

    tracker.serverShutdown();

    verify(mockShutdownListener).run();
  }

  @Test
  public void testLastTransportTerminated_serverNotShutdownYet_doesNotNotify() {
    ServerTransportListener wrapperListener = registerNewTransport();
    verifyNoInteractions(mockShutdownListener);

    wrapperListener.transportTerminated();

    verifyNoInteractions(mockShutdownListener);
  }

  @Test
  public void testTransportCreation_afterServerShutdown_throws() {
    tracker.serverShutdown();

    assertThrows(IllegalStateException.class, this::registerNewTransport);
  }

  @Test
  public void testServerListenerCallbacks_invokesDelegates() {
    ServerTransportListener listener = tracker.transportCreated(mockServerTransport);
    verify(mockServerListener).transportCreated(mockServerTransport);

    listener.transportTerminated();
    verify(mockServerTransportListener).transportTerminated();

    tracker.serverShutdown();
    verify(mockServerListener).serverShutdown();
  }

  private ServerTransportListener registerNewTransport() {
    return tracker.transportCreated(mockServerTransport);
  }
}
