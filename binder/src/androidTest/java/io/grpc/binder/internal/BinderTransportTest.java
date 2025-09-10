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

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.grpc.ServerStreamTracer;
import io.grpc.binder.AndroidComponentAddress;
import io.grpc.binder.HostServices;
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
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * A test for the Android binder based transport.
 *
 * <p>This class really just sets up the test environment. All of the actual tests are defined in
 * AbstractTransportTest.
 */
@RunWith(AndroidJUnit4.class)
public final class BinderTransportTest extends AbstractTransportTest {

  private final Context appContext = ApplicationProvider.getApplicationContext();
  private final ObjectPool<ScheduledExecutorService> executorServicePool =
      SharedResourcePool.forResource(GrpcUtil.TIMER_SERVICE);
  private final ObjectPool<Executor> offloadExecutorPool =
      SharedResourcePool.forResource(GrpcUtil.SHARED_CHANNEL_EXECUTOR);
  private final ObjectPool<Executor> serverExecutorPool =
      SharedResourcePool.forResource(GrpcUtil.SHARED_CHANNEL_EXECUTOR);

  @Override
  @After
  public void tearDown() throws InterruptedException {
    super.tearDown();
    HostServices.awaitServiceShutdown();
  }

  @Override
  protected InternalServer newServer(List<ServerStreamTracer.Factory> streamTracerFactories) {
    AndroidComponentAddress addr = HostServices.allocateService(appContext);

    BinderServer binderServer =
        new BinderServer.Builder()
            .setListenAddress(addr)
            .setExecutorPool(serverExecutorPool)
            .setExecutorServicePool(executorServicePool)
            .setStreamTracerFactories(streamTracerFactories)
            .build();

    HostServices.configureService(
        addr,
        HostServices.serviceParamsBuilder()
            .setRawBinderSupplier(() -> binderServer.getHostBinder())
            .build());

    return binderServer;
  }

  @Override
  protected InternalServer newServer(
      int port, List<ServerStreamTracer.Factory> streamTracerFactories) {
    return newServer(streamTracerFactories);
  }

  @Override
  protected String testAuthority(InternalServer server) {
    return ((AndroidComponentAddress) server.getListenSocketAddress()).getAuthority();
  }

  @Override
  protected ManagedClientTransport newClientTransport(InternalServer server) {
    AndroidComponentAddress addr = (AndroidComponentAddress) server.getListenSocketAddress();
    BinderClientTransportFactory.Builder builder =
        new BinderClientTransportFactory.Builder()
            .setSourceContext(appContext)
            .setScheduledExecutorPool(executorServicePool)
            .setOffloadExecutorPool(offloadExecutorPool);

    ClientTransportOptions options = new ClientTransportOptions();
    options.setEagAttributes(eagAttrs());
    options.setChannelLogger(transportLogger());

    return new BinderClientTransport(builder.buildClientTransportFactory(), addr, options);
  }

  @Test
  @Ignore("BinderTransport doesn't report socket stats yet.")
  @Override
  public void socketStats() throws Exception {}

  @Test
  @Ignore("BinderTransport doesn't do message-level flow control yet.")
  @Override
  public void flowControlPushBack() throws Exception {}

  @Test
  @Ignore("This test isn't appropriate for BinderTransport.")
  @Override
  public void serverAlreadyListening() throws Exception {
    // This test asserts that two Servers can't listen on the same SocketAddress. For a regular
    // network server, that address refers to a network port, and for a BinderServer it
    // refers to an Android Service class declared in an applications manifest.
    //
    // However, unlike a regular network server, which is responsible for listening on its port, a
    // BinderServer is not responsible for the creation of its host Service. The opposite is
    // the case, with the host Android Service (itself created by the Android platform in
    // response to a connection) building the gRPC server.
    //
    // Passing this test would require us to manually check that two Server instances aren't,
    // created with the same Android Service class, but due to the "inversion of control" described
    // above, we would actually be testing (and making assumptions about) the precise lifecycle of
    // Android Services, which is arguably not our concern.
  }
}
