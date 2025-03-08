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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.Metadata.ASCII_STRING_MARSHALLER;
import static org.mockito.Mockito.when;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.OAuth2Credentials;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.CallCredentials;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.Metadata;
import io.grpc.MetricRecorder;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.internal.ObjectPool;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.XdsListenerResource.LdsUpdate;
import io.grpc.xds.client.Bootstrapper.BootstrapInfo;
import io.grpc.xds.client.Bootstrapper.ServerInfo;
import io.grpc.xds.client.EnvoyProtoData.Node;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsClient.ResourceWatcher;
import io.grpc.xds.client.XdsInitializationException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class PerTargetXdsTransportCallCredentialsTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();
  static final Metadata.Key<String> AUTHORIZATION_METADATA_KEY =
      Metadata.Key.of("Authorization", ASCII_STRING_MARSHALLER);
  static final Context.Key<String> TOKEN_CONTEXT_KEY = Context.key("token");
  private final Node node = Node.newBuilder().setId("SharedXdsClientPoolProviderTest").build();
  private final MetricRecorder metricRecorder = new MetricRecorder() {};

  private FakeAdsService adsService;
  private Server xdsServer;
  private String xdsServerUri;
  // Used to notify the xDS client that the fake server has finished processing the request.
  private CountDownLatch handleDiscoveryRequest;

  @Mock private GrpcBootstrapperImpl bootstrapper;
  @Mock private ResourceWatcher<LdsUpdate> ldsResourceWatcher;

  @Before
  public void setup() throws Exception {
    adsService = new FakeAdsService();
    xdsServer =
        Grpc.newServerBuilderForPort(0, InsecureServerCredentials.create())
            .addService(adsService)
            .intercept(new CallCredsServerInterceptor())
            .build()
            .start();
    xdsServerUri = "localhost:" + xdsServer.getPort();
    handleDiscoveryRequest = new CountDownLatch(1);
  }

  @After
  public void tearDown() {
    xdsServer.shutdown();
  }

  private class CallCredsServerInterceptor implements ServerInterceptor {
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> serverCall,
        Metadata metadata,
        ServerCallHandler<ReqT, RespT> serverCallHandler) {
      String callCredsValue = metadata.get(AUTHORIZATION_METADATA_KEY);
      if (callCredsValue == null) {
        serverCall.close(
            Status.UNAUTHENTICATED.withDescription("Missing call credentials"), new Metadata());
        return new ServerCall.Listener<ReqT>() {
          // noop
        };
      }
      // set access tokenValue into current context, to be consumed by the server
      Context ctx = Context.current().withValue(TOKEN_CONTEXT_KEY, callCredsValue);
      return Contexts.interceptCall(ctx, serverCall, metadata, serverCallHandler);
    }
  }

  private class FakeAdsService
      extends AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase {
    private String token;

    @Override
    public StreamObserver<DiscoveryRequest> streamAggregatedResources(
        final StreamObserver<DiscoveryResponse> responseObserver) {
      StreamObserver<DiscoveryRequest> requestObserver =
          new StreamObserver<DiscoveryRequest>() {
            @Override
            public void onNext(DiscoveryRequest value) {
              token = TOKEN_CONTEXT_KEY.get().substring("Bearer".length()).trim();
              responseObserver.onNext(DiscoveryResponse.newBuilder().build());
              handleDiscoveryRequest.countDown();
            }

            @Override
            public void onError(Throwable t) {
              responseObserver.onError(t);
              handleDiscoveryRequest.countDown();
            }

            @Override
            public void onCompleted() {
              responseObserver.onCompleted();
              handleDiscoveryRequest.countDown();
            }
          };

      return requestObserver;
    }

    public boolean receivedToken(String expected) {
      return token.equals(expected);
    }
  }

  @Test
  public void usePerTargetXdsTransportCallCredentials() throws XdsInitializationException {
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

    // Create xDS client & transport, and verify that the custom CallCredentials were used
    ObjectPool<XdsClient> xdsClientPool =
        provider.getOrCreate("target", metricRecorder, sampleCreds);
    XdsClient xdsClient = xdsClientPool.getObject();
    xdsClient.watchXdsResource(
        XdsListenerResource.getInstance(), "someLDSresource", ldsResourceWatcher);
    assertThat(waitForXdsServerDone()).isTrue();
    assertThat(adsService.receivedToken("token")).isTrue();
  }

  private boolean waitForXdsServerDone() {
    try {
      return handleDiscoveryRequest.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      throw new AssertionError(
          "Interrupted while waiting for xDS server to finish handling request", e);
    }
  }
}
