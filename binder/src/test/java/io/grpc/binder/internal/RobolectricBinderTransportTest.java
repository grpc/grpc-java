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

import static android.os.Process.myUid;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static io.grpc.binder.internal.BinderTransport.REMOTE_UID;
import static io.grpc.binder.internal.BinderTransport.SETUP_TRANSPORT;
import static io.grpc.binder.internal.BinderTransport.WIRE_FORMAT_VERSION;

import android.app.Application;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Parcel;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.core.content.pm.ApplicationInfoBuilder;
import androidx.test.core.content.pm.PackageInfoBuilder;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.LooperMode.Mode;
import org.robolectric.shadows.ShadowBinder;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import io.grpc.Attributes;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.binder.AndroidComponentAddress;
import io.grpc.binder.ApiConstants;
import io.grpc.binder.AsyncSecurityPolicy;
import io.grpc.binder.SecurityPolicies;
import io.grpc.binder.internal.SettableAsyncSecurityPolicy.AuthRequest;
import io.grpc.internal.AbstractTransportTest;
import io.grpc.internal.ClientTransportFactory.ClientTransportOptions;
import io.grpc.internal.ConnectionClientTransport;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourcePool;

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
@RunWith(ParameterizedRobolectricTestRunner.class)
@LooperMode(Mode.INSTRUMENTATION_TEST)
public final class RobolectricBinderTransportTest extends AbstractTransportTest {

  private final Application application = ApplicationProvider.getApplicationContext();
  private final ObjectPool<ScheduledExecutorService> executorServicePool =
      SharedResourcePool.forResource(GrpcUtil.TIMER_SERVICE);
  private final ObjectPool<Executor> offloadExecutorPool =
      SharedResourcePool.forResource(GrpcUtil.SHARED_CHANNEL_EXECUTOR);
  private final ObjectPool<Executor> serverExecutorPool =
      SharedResourcePool.forResource(GrpcUtil.SHARED_CHANNEL_EXECUTOR);

  @Rule public MockitoRule mocks = MockitoJUnit.rule();

  @Mock AsyncSecurityPolicy mockClientSecurityPolicy;

  ApplicationInfo serverAppInfo;
  PackageInfo serverPkgInfo;
  ServiceInfo serviceInfo;

  private int nextServerAddress;

  @Parameter public boolean preAuthServersParam;

  @Parameters(name = "preAuthServersParam={0}")
  public static ImmutableList<Boolean> data() {
    return ImmutableList.of(true, false);
  }

  @Override
  public void setUp() {
    serverAppInfo =
        ApplicationInfoBuilder.newBuilder().setPackageName("the.server.package").build();
    serverAppInfo.uid = myUid();
    serverPkgInfo =
        PackageInfoBuilder.newBuilder()
            .setPackageName(serverAppInfo.packageName)
            .setApplicationInfo(serverAppInfo)
            .build();
    shadowOf(application.getPackageManager()).installPackage(serverPkgInfo);

    serviceInfo = new ServiceInfo();
    serviceInfo.name = "SomeService";
    serviceInfo.packageName = serverAppInfo.packageName;
    serviceInfo.applicationInfo = serverAppInfo;
    shadowOf(application.getPackageManager()).addOrUpdateService(serviceInfo);

    super.setUp();
  }

  @Before
  public void requestRealisticBindServiceBehavior() {
    shadowOf(application).setBindServiceCallsOnServiceConnectedDirectly(false);
    shadowOf(application).setUnbindServiceCallsOnServiceDisconnected(false);
  }

  @Override
  protected InternalServer newServer(List<ServerStreamTracer.Factory> streamTracerFactories) {
    AndroidComponentAddress listenAddr =
        AndroidComponentAddress.forBindIntent(
            new Intent()
                .setClassName(serviceInfo.packageName, serviceInfo.name)
                .setAction("io.grpc.action.BIND." + nextServerAddress++));

    BinderServer binderServer =
        new BinderServer.Builder()
            .setListenAddress(listenAddr)
            .setExecutorPool(serverExecutorPool)
            .setExecutorServicePool(executorServicePool)
            .setStreamTracerFactories(streamTracerFactories)
            .build();

    shadowOf(application.getPackageManager()).addServiceIfNotPresent(listenAddr.getComponent());
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

  BinderClientTransportFactory.Builder newClientTransportFactoryBuilder() {
    return new BinderClientTransportFactory.Builder()
        .setPreAuthorizeServers(preAuthServersParam)
        .setSourceContext(application)
        .setScheduledExecutorPool(executorServicePool)
        .setOffloadExecutorPool(offloadExecutorPool);
  }

  BinderClientTransportBuilder newClientTransportBuilder() {
    return new BinderClientTransportBuilder()
        .setFactory(newClientTransportFactoryBuilder().buildClientTransportFactory())
        .setServerAddress(server.getListenSocketAddress());
  }

  @Override
  protected ManagedClientTransport newClientTransport(InternalServer server) {
    ClientTransportOptions options = new ClientTransportOptions();
    options.setEagAttributes(eagAttrs());
    options.setChannelLogger(transportLogger());

    return newClientTransportBuilder()
        .setServerAddress(server.getListenSocketAddress())
        .setOptions(options)
        .build();
  }

  @Override
  protected String testAuthority(InternalServer server) {
    return ((AndroidComponentAddress) server.getListenSocketAddress()).getAuthority();
  }

  @Test
  public void clientAuthorizesServerUidsInOrder() throws Exception {
    // TODO(jdcormie): In real Android, Binder#getCallingUid is thread-local but Robolectric only
    //  lets us fake value this *globally*. So the ShadowBinder#setCallingUid() here unrealistically
    //  affects the server's view of the client's uid too. For now this doesn't matter because this
    //  test never exercises server SecurityPolicy.
    ShadowBinder.setCallingUid(11111); // UID of the server *process*.

    serverPkgInfo.applicationInfo.uid = 22222; // UID of the server *app*, which can be different.
    shadowOf(application.getPackageManager()).installPackage(serverPkgInfo);
    shadowOf(application.getPackageManager()).addOrUpdateService(serviceInfo);
    server = newServer(ImmutableList.of());
    server.start(serverListener);

    SettableAsyncSecurityPolicy securityPolicy = new SettableAsyncSecurityPolicy();
    client =
        newClientTransportBuilder()
            .setFactory(
                newClientTransportFactoryBuilder()
                    .setSecurityPolicy(securityPolicy)
                    .buildClientTransportFactory())
            .build();
    runIfNotNull(client.start(mockClientTransportListener));

    if (preAuthServersParam) {
      AuthRequest preAuthRequest = securityPolicy.takeNextAuthRequest(TIMEOUT_MS, MILLISECONDS);
      assertThat(preAuthRequest.uid).isEqualTo(22222);
      verify(mockClientTransportListener, never()).transportReady();
      preAuthRequest.setResult(Status.OK);
    }

    AuthRequest authRequest = securityPolicy.takeNextAuthRequest(TIMEOUT_MS, MILLISECONDS);
    assertThat(authRequest.uid).isEqualTo(11111);
    verify(mockClientTransportListener, never()).transportReady();
    authRequest.setResult(Status.OK);

    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportReady();
  }

  @Test
  public void eagAttributeCanOverrideChannelPreAuthServerSetting() throws Exception {
    server.start(serverListener);
    SettableAsyncSecurityPolicy securityPolicy = new SettableAsyncSecurityPolicy();
    ClientTransportOptions options = new ClientTransportOptions();
    options.setEagAttributes(
        Attributes.newBuilder().set(ApiConstants.PRE_AUTH_SERVER_OVERRIDE, true).build());
    client =
        newClientTransportBuilder()
            .setOptions(options)
            .setFactory(
                newClientTransportFactoryBuilder()
                    .setPreAuthorizeServers(preAuthServersParam) // To be overridden.
                    .setSecurityPolicy(securityPolicy)
                    .buildClientTransportFactory())
            .build();
    runIfNotNull(client.start(mockClientTransportListener));

    AuthRequest preAuthRequest = securityPolicy.takeNextAuthRequest(TIMEOUT_MS, MILLISECONDS);
    verify(mockClientTransportListener, never()).transportReady();
    preAuthRequest.setResult(Status.OK);

    AuthRequest authRequest = securityPolicy.takeNextAuthRequest(TIMEOUT_MS, MILLISECONDS);
    verify(mockClientTransportListener, never()).transportReady();
    authRequest.setResult(Status.OK);

    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportReady();
  }

  @Test
  public void clientIgnoresDuplicateSetupTransaction() throws Exception {
    server.start(serverListener);
    client =
        newClientTransportBuilder()
            .setFactory(
                newClientTransportFactoryBuilder()
                    .setSecurityPolicy(SecurityPolicies.internalOnly())
                    .buildClientTransportFactory())
            .build();
    runIfNotNull(client.start(mockClientTransportListener));
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportReady();

    assertThat(((ConnectionClientTransport) client).getAttributes().get(REMOTE_UID))
        .isEqualTo(myUid());

    Parcel setupParcel = Parcel.obtain();
    try {
      setupParcel.writeInt(WIRE_FORMAT_VERSION);
      setupParcel.writeStrongBinder(new Binder());
      setupParcel.setDataPosition(0);
      ShadowBinder.setCallingUid(1 + myUid());
      ((BinderClientTransport) client).handleTransaction(SETUP_TRANSPORT, setupParcel);
    } finally {
      ShadowBinder.setCallingUid(myUid());
      setupParcel.recycle();
    }

    assertThat(((ConnectionClientTransport) client).getAttributes().get(REMOTE_UID))
            .isEqualTo(myUid());

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(mockClientTransportListener, timeout(TIMEOUT_MS)).transportShutdown(statusCaptor.capture());
    assertCodeEquals(null, Status.UNAVAILABLE, statusCaptor.getValue());
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
