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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.robolectric.Shadows.shadowOf;

import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.internal.FixedObjectPool;
import io.grpc.internal.MockServerTransportListener;
import io.grpc.internal.ObjectPool;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

/**
 * Low-level server-side transport tests for binder channel. Like BinderChannelSmokeTest, this
 * convers edge cases not exercised by AbstractTransportTest, but it deals with the
 * binderTransport.BinderServerTransport directly.
 */
@RunWith(RobolectricTestRunner.class)
public final class BinderServerTransportTest {

  @Rule public MockitoRule mocks = MockitoJUnit.rule();

  private final ScheduledExecutorService executorService = new MainThreadScheduledExecutorService();
  private MockServerTransportListener transportListener;

  @Mock IBinder mockBinder;

  BinderServerTransport transport;

  @Before
  public void setUp() throws Exception {
    transportListener = new MockServerTransportListener(transport);
  }

  // Provide defaults so that we can "include only relevant details in tests."
  BinderServerTransportBuilder newBinderServerTransportBuilder() {
    return new BinderServerTransportBuilder()
        .setExecutorServicePool(new FixedObjectPool<>(executorService))
        .setAttributes(Attributes.EMPTY)
        .setStreamTracerFactories(ImmutableList.of())
        .setBinderDecorator(OneWayBinderProxy.IDENTITY_DECORATOR)
        .setCallbackBinder(mockBinder);
  }

  @Test
  public void testSetupTransactionFailureReportsMultipleTerminations_b153460678() throws Exception {
    // Make the binder fail the setup transaction.
    doThrow(new RemoteException())
        .when(mockBinder)
        .transact(anyInt(), any(Parcel.class), isNull(), anyInt());
    transport = newBinderServerTransportBuilder().setCallbackBinder(mockBinder).build();
    shadowOf(Looper.getMainLooper()).idle();
    transport.start(transportListener);

    // Now shut it down externally *before* executing Runnables scheduled on the executor.
    transport.shutdownNow(Status.UNKNOWN.withDescription("reasons"));
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(transportListener.isTerminated()).isTrue();
  }

  @Test
  public void testClientBinderIsDeadOnArrival() throws Exception {
    transport = newBinderServerTransportBuilder()
        .setCallbackBinder(new FakeDeadBinder())
        .build();
    transport.start(transportListener);
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(transportListener.isTerminated()).isTrue();
  }

  @Test
  public void testStartAfterShutdownAndIdle() throws Exception {
    transport = newBinderServerTransportBuilder().build();
    transport.shutdownNow(Status.UNKNOWN.withDescription("reasons"));
    shadowOf(Looper.getMainLooper()).idle();
    transport.start(transportListener);
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(transportListener.isTerminated()).isTrue();
  }

  @Test
  public void testStartAfterShutdownNoIdle() throws Exception {
    transport = newBinderServerTransportBuilder().build();
    transport.shutdownNow(Status.UNKNOWN.withDescription("reasons"));
    transport.start(transportListener);
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(transportListener.isTerminated()).isTrue();
  }

  static class BinderServerTransportBuilder {
    ObjectPool<ScheduledExecutorService> executorServicePool;
    Attributes attributes;
    List<ServerStreamTracer.Factory> streamTracerFactories;
    OneWayBinderProxy.Decorator binderDecorator;
    IBinder callbackBinder;

    public BinderServerTransport build() {
      return BinderServerTransport.create(
          executorServicePool, attributes, streamTracerFactories, binderDecorator, callbackBinder);
    }

    public BinderServerTransportBuilder setExecutorServicePool(
        ObjectPool<ScheduledExecutorService> executorServicePool) {
      this.executorServicePool = executorServicePool;
      return this;
    }

    public BinderServerTransportBuilder setAttributes(Attributes attributes) {
      this.attributes = attributes;
      return this;
    }

    public BinderServerTransportBuilder setStreamTracerFactories(
        List<ServerStreamTracer.Factory> streamTracerFactories) {
      this.streamTracerFactories = streamTracerFactories;
      return this;
    }

    public BinderServerTransportBuilder setBinderDecorator(
        OneWayBinderProxy.Decorator binderDecorator) {
      this.binderDecorator = binderDecorator;
      return this;
    }

    public BinderServerTransportBuilder setCallbackBinder(IBinder callbackBinder) {
      this.callbackBinder = callbackBinder;
      return this;
    }
  }
}
