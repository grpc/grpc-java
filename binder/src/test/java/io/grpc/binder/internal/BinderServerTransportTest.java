/*
 * Copyright 2020 The gRPC Authors
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.robolectric.annotation.LooperMode.Mode.PAUSED;

import android.os.IBinder;
import android.os.Parcel;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.testing.TestingExecutors;
import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.FixedObjectPool;
import io.grpc.internal.ServerStream;
import io.grpc.internal.ServerTransportListener;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

/**
 * Low-level server-side transport tests for binder channel. Like BinderChannelSmokeTest, this
 * convers edge cases not exercised by AbstractTransportTest, but it deals with the
 * binderTransport.BinderServerTransport directly.
 */
@LooperMode(PAUSED)
@RunWith(RobolectricTestRunner.class)
public final class BinderServerTransportTest {

  @Rule public MockitoRule mocks = MockitoJUnit.rule();

  private final ScheduledExecutorService executorService =
      TestingExecutors.sameThreadScheduledExecutor();
  private final TestTransportListener transportListener = new TestTransportListener();

  @Mock IBinder mockBinder;

  BinderTransport.BinderServerTransport transport;

  @Before
  public void setUp() throws Exception {
    transport =
        new BinderTransport.BinderServerTransport(
            new FixedObjectPool<>(executorService),
            Attributes.EMPTY,
            ImmutableList.of(),
            mockBinder);
  }

  @Test
  public void testSetupTransactionFailureCausesMultipleShutdowns_b153460678() throws Exception {
    // Make the binder fail the setup transaction.
    when(mockBinder.transact(anyInt(), any(Parcel.class), isNull(), anyInt())).thenReturn(false);
    transport.setServerTransportListener(transportListener);

    // Now shut it down.
    transport.shutdownNow(Status.UNKNOWN.withDescription("reasons"));

    assertThat(transportListener.terminated).isTrue();
  }

  private static final class TestTransportListener implements ServerTransportListener {

    public boolean ready;
    public boolean terminated;

    /**
     * Called when a new stream was created by the remote client.
     *
     * @param stream the newly created stream.
     * @param method the fully qualified method name being called on the server.
     * @param headers containing metadata for the call.
     */
    @Override
    public void streamCreated(ServerStream stream, String method, Metadata headers) {}

    @Override
    public Attributes transportReady(Attributes attributes) {
      ready = true;
      return attributes;
    }

    @Override
    public void transportTerminated() {
      checkState(!terminated, "Terminated twice");
      terminated = true;
    }
  }
}
