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

package io.grpc.binder.internal;

import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import androidx.test.core.app.ApplicationProvider;
import io.grpc.ServerStreamTracer;
import io.grpc.binder.AndroidComponentAddress;
import io.grpc.internal.AbstractTransportTest;
import io.grpc.internal.ClientTransportFactory.ClientTransportOptions;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourcePool;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.LooperMode.Mode;

/**
 * All of the AbstractTransportTest cases applied to {@link BinderTransport} running in a
 * Robolectric environment.
 *
 * <p>Runs much faster than BinderTransportTest and doesn't require an Android device/emulator.
 * Somewhat less realistic but allows simulating behavior that would be difficult or impossible with
 * real Android.
 *
 * <p>NB: Unlike most robolectric tests, we run in {@link LooperMode.Mode#INSTRUMENTATION_TEST},
 * meaning test cases don't run on the main thread. This supports the AbstractTransportTest approach
 * where the test thread frequently blocks waiting for transport state changes to take effect.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(Mode.INSTRUMENTATION_TEST)
public final class RobolectricBinderTransportTest extends AbstractTransportTest {

  private final Application application = ApplicationProvider.getApplicationContext();
  private final ObjectPool<ScheduledExecutorService> executorServicePool =
      SharedResourcePool.forResource(GrpcUtil.TIMER_SERVICE);
  private final ObjectPool<Executor> offloadExecutorPool =
      SharedResourcePool.forResource(GrpcUtil.SHARED_CHANNEL_EXECUTOR);
  private final ObjectPool<Executor> serverExecutorPool =
      SharedResourcePool.forResource(GrpcUtil.SHARED_CHANNEL_EXECUTOR);

  private int nextServerAddress;

  @Override
  protected InternalServer newServer(List<ServerStreamTracer.Factory> streamTracerFactories) {
    AndroidComponentAddress listenAddr = AndroidComponentAddress.forBindIntent(
        new Intent()
            .setClassName(application.getPackageName(), "HostService")
            .setAction("io.grpc.action.BIND." + nextServerAddress++));

    BinderServer binderServer =
        new BinderServer.Builder()
            .setListenAddress(listenAddr)
            .setExecutorPool(serverExecutorPool)
            .setExecutorServicePool(executorServicePool)
            .setStreamTracerFactories(streamTracerFactories)
            .build();

    shadowOf(application)
        .setComponentNameAndServiceForBindServiceForIntent(
            listenAddr.asBindIntent(), listenAddr.getComponent(), binderServer.getHostBinder());
    return binderServer;
  }

  @Override
  protected InternalServer newServer(
      int port, List<ServerStreamTracer.Factory> streamTracerFactories) {
    if (port > 0) {
      // TODO: TCP ports have no place in an *abstract* transport test. Replace with SocketAddress.
      throw new UnsupportedOperationException();
    }
    return newServer(streamTracerFactories);
  }

  @Override
  protected ManagedClientTransport newClientTransport(InternalServer server) {
    BinderClientTransportFactory.Builder builder =
        new BinderClientTransportFactory.Builder()
            .setSourceContext(application)
            .setScheduledExecutorPool(executorServicePool)
            .setOffloadExecutorPool(offloadExecutorPool);

    ClientTransportOptions options = new ClientTransportOptions();
    options.setEagAttributes(eagAttrs());
    options.setChannelLogger(transportLogger());

    return new BinderTransport.BinderClientTransport(
        builder.buildClientTransportFactory(),
        (AndroidComponentAddress) server.getListenSocketAddress(),
        options);
  }

  @Override
  protected String testAuthority(InternalServer server) {
    return ((AndroidComponentAddress) server.getListenSocketAddress()).getAuthority();
  }

  @Test
  @Ignore("See BinderTransportTest#socketStats.")
  @Override
  public void socketStats() {}

  @Test
  @Ignore("See BinderTransportTest#flowControlPushBack")
  @Override
  public void flowControlPushBack() {}

  @Test
  @Ignore("See BinderTransportTest#serverAlreadyListening")
  @Override
  public void serverAlreadyListening() {}
}
