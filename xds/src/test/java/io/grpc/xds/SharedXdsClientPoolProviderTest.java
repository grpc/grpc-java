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

package io.grpc.xds;


import static com.google.common.truth.Truth.assertThat;
import static io.grpc.Metadata.ASCII_STRING_MARSHALLER;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.OAuth2Credentials;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.CallCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.Metadata;
import io.grpc.MetricRecorder;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.SharedXdsClientPoolProvider.RefCountedXdsClientObjectPool;
import io.grpc.xds.XdsListenerResource.LdsUpdate;
import io.grpc.xds.client.Bootstrapper.BootstrapInfo;
import io.grpc.xds.client.Bootstrapper.ServerInfo;
import io.grpc.xds.client.EnvoyProtoData.Node;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsClient.ResourceWatcher;
import io.grpc.xds.client.XdsInitializationException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link SharedXdsClientPoolProvider}. */
@RunWith(JUnit4.class)
public class SharedXdsClientPoolProviderTest {

  private static final String SERVER_URI = "trafficdirector.googleapis.com";
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  @SuppressWarnings("deprecation") // https://github.com/grpc/grpc-java/issues/7467
  @Rule
  public final ExpectedException thrown = ExpectedException.none();
  private final Node node = Node.newBuilder().setId("SharedXdsClientPoolProviderTest").build();
  private final MetricRecorder metricRecorder = new MetricRecorder() {};
  private static final String DUMMY_TARGET = "dummy";
  static final Metadata.Key<String> AUTHORIZATION_METADATA_KEY =
      Metadata.Key.of("Authorization", ASCII_STRING_MARSHALLER);

  @Mock
  private GrpcBootstrapperImpl bootstrapper;
  @Mock private ResourceWatcher<LdsUpdate> ldsResourceWatcher;

  @Test
  public void noServer() throws XdsInitializationException {
    BootstrapInfo bootstrapInfo =
        BootstrapInfo.builder().servers(Collections.<ServerInfo>emptyList()).node(node).build();
    when(bootstrapper.bootstrap()).thenReturn(bootstrapInfo);
    SharedXdsClientPoolProvider provider = new SharedXdsClientPoolProvider(bootstrapper);
    thrown.expect(XdsInitializationException.class);
    thrown.expectMessage("No xDS server provided");
    provider.getOrCreate(DUMMY_TARGET, metricRecorder);
    assertThat(provider.get(DUMMY_TARGET)).isNull();
  }

  @Test
  public void sharedXdsClientObjectPool() throws XdsInitializationException {
    ServerInfo server = ServerInfo.create(SERVER_URI, InsecureChannelCredentials.create());
    BootstrapInfo bootstrapInfo =
        BootstrapInfo.builder().servers(Collections.singletonList(server)).node(node).build();
    when(bootstrapper.bootstrap()).thenReturn(bootstrapInfo);

    SharedXdsClientPoolProvider provider = new SharedXdsClientPoolProvider(bootstrapper);
    assertThat(provider.get(DUMMY_TARGET)).isNull();
    ObjectPool<XdsClient> xdsClientPool = provider.getOrCreate(DUMMY_TARGET, metricRecorder);
    verify(bootstrapper).bootstrap();
    assertThat(provider.getOrCreate(DUMMY_TARGET, metricRecorder)).isSameInstanceAs(xdsClientPool);
    assertThat(provider.get(DUMMY_TARGET)).isNotNull();
    assertThat(provider.get(DUMMY_TARGET)).isSameInstanceAs(xdsClientPool);
    verifyNoMoreInteractions(bootstrapper);
  }

  @Test
  public void refCountedXdsClientObjectPool_delayedCreation() {
    ServerInfo server = ServerInfo.create(SERVER_URI, InsecureChannelCredentials.create());
    BootstrapInfo bootstrapInfo =
        BootstrapInfo.builder().servers(Collections.singletonList(server)).node(node).build();
    SharedXdsClientPoolProvider provider = new SharedXdsClientPoolProvider(bootstrapper);
    RefCountedXdsClientObjectPool xdsClientPool =
        provider.new RefCountedXdsClientObjectPool(bootstrapInfo, DUMMY_TARGET, metricRecorder);
    assertThat(xdsClientPool.getXdsClientForTest()).isNull();
    XdsClient xdsClient = xdsClientPool.getObject();
    assertThat(xdsClientPool.getXdsClientForTest()).isNotNull();
    xdsClientPool.returnObject(xdsClient);
  }

  @Test
  public void refCountedXdsClientObjectPool_refCounted() {
    ServerInfo server = ServerInfo.create(SERVER_URI, InsecureChannelCredentials.create());
    BootstrapInfo bootstrapInfo =
        BootstrapInfo.builder().servers(Collections.singletonList(server)).node(node).build();
    SharedXdsClientPoolProvider provider = new SharedXdsClientPoolProvider(bootstrapper);
    RefCountedXdsClientObjectPool xdsClientPool =
        provider.new RefCountedXdsClientObjectPool(bootstrapInfo, DUMMY_TARGET, metricRecorder);
    // getObject once
    XdsClient xdsClient = xdsClientPool.getObject();
    assertThat(xdsClient).isNotNull();
    // getObject twice
    assertThat(xdsClientPool.getObject()).isSameInstanceAs(xdsClient);
    // returnObject once
    assertThat(xdsClientPool.returnObject(xdsClient)).isNull();
    assertThat(xdsClient.isShutDown()).isFalse();
    // returnObject twice
    assertThat(xdsClientPool.returnObject(xdsClient)).isNull();
    assertThat(xdsClient.isShutDown()).isTrue();
  }

  @Test
  public void refCountedXdsClientObjectPool_getObjectCreatesNewInstanceIfAlreadyShutdown() {
    ServerInfo server = ServerInfo.create(SERVER_URI, InsecureChannelCredentials.create());
    BootstrapInfo bootstrapInfo =
        BootstrapInfo.builder().servers(Collections.singletonList(server)).node(node).build();
    SharedXdsClientPoolProvider provider = new SharedXdsClientPoolProvider(bootstrapper);
    RefCountedXdsClientObjectPool xdsClientPool =
        provider.new RefCountedXdsClientObjectPool(bootstrapInfo, DUMMY_TARGET, metricRecorder);
    XdsClient xdsClient1 = xdsClientPool.getObject();
    assertThat(xdsClientPool.returnObject(xdsClient1)).isNull();
    assertThat(xdsClient1.isShutDown()).isTrue();

    XdsClient xdsClient2 = xdsClientPool.getObject();
    assertThat(xdsClient2).isNotSameInstanceAs(xdsClient1);
    xdsClientPool.returnObject(xdsClient2);
  }

  private class CallCredsServerInterceptor implements ServerInterceptor {
    private SettableFuture<String> tokenFuture = SettableFuture.create();

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> serverCall,
        Metadata metadata,
        ServerCallHandler<ReqT, RespT> next) {
      tokenFuture.set(metadata.get(AUTHORIZATION_METADATA_KEY));
      return next.startCall(serverCall, metadata);
    }

    public String getTokenWithTimeout(long timeout, TimeUnit unit) throws Exception {
      return tokenFuture.get(timeout, unit);
    }
  }

  @Test
  public void xdsClient_usesCallCredentials() throws Exception {
    // Set up fake xDS server
    XdsTestControlPlaneService fakeXdsService = new XdsTestControlPlaneService();
    CallCredsServerInterceptor callCredentialsInterceptor = new CallCredsServerInterceptor();
    Server xdsServer =
        Grpc.newServerBuilderForPort(0, InsecureServerCredentials.create())
            .addService(fakeXdsService)
            .intercept(callCredentialsInterceptor)
            .build()
            .start();
    String xdsServerUri = "localhost:" + xdsServer.getPort();

    // Set up bootstrap & xDS client pool provider
    ServerInfo server = ServerInfo.create(xdsServerUri, InsecureChannelCredentials.create());
    BootstrapInfo bootstrapInfo =
        BootstrapInfo.builder().servers(Collections.singletonList(server)).node(node).build();
    when(bootstrapper.bootstrap()).thenReturn(bootstrapInfo);
    SharedXdsClientPoolProvider provider = new SharedXdsClientPoolProvider(bootstrapper);

    // Create custom xDS transport CallCredentials
    CallCredentials sampleCreds =
        MoreCallCredentials.from(
            OAuth2Credentials.create(new AccessToken("token", /* expirationTime= */ null)));

    // Create xDS client that uses the CallCredentials on the transport
    ObjectPool<XdsClient> xdsClientPool =
        provider.getOrCreate("target", metricRecorder, sampleCreds);
    XdsClient xdsClient = xdsClientPool.getObject();
    xdsClient.watchXdsResource(
        XdsListenerResource.getInstance(), "someLDSresource", ldsResourceWatcher);

    // Wait for xDS server to get the request and verify that it received the CallCredentials
    assertThat(callCredentialsInterceptor.getTokenWithTimeout(5, TimeUnit.SECONDS))
        .isEqualTo("Bearer token");

    // Clean up
    xdsClientPool.returnObject(xdsClient);
    xdsServer.shutdownNow();
  }
}
