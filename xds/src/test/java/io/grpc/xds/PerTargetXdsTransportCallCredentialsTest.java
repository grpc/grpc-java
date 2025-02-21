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
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
  // Used to synchronize waiting between xDS client & server for each request, to verify that the
  // correct CallCredentials was used.
  private CyclicBarrier handleDiscoveryRequest;

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
    handleDiscoveryRequest = new CyclicBarrier(2);
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
              token = TOKEN_CONTEXT_KEY.get();
              responseObserver.onNext(DiscoveryResponse.newBuilder().build());
              notifyRequestDone();
            }

            @Override
            public void onError(Throwable t) {
              responseObserver.onError(t);
              notifyRequestDone();
            }

            @Override
            public void onCompleted() {
              responseObserver.onCompleted();
              notifyRequestDone();
            }
          };

      return requestObserver;
    }

    public boolean receivedToken(String expected) {
      return token.contains(expected);
    }

    public void notifyRequestDone() {
      try {
        handleDiscoveryRequest.await();
      } catch (BrokenBarrierException e) {
        throw new AssertionError("Did not wait for xDS client to consume response", e);
      } catch (InterruptedException e) {
        throw new AssertionError(
            "Interrupted while waiting for xDS client to consume response.", e);
      }
      handleDiscoveryRequest.reset();
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

    // Create & register per-target custom xDS transport CallCredentials
    CallCredentials sampleCreds1 =
        MoreCallCredentials.from(
            OAuth2Credentials.create(new AccessToken("token1", /* expirationTime= */ null)));
    XdsTransportCallCredentialsProvider.setTransportCallCredentials("target1", sampleCreds1);
    CallCredentials sampleCreds2 =
        MoreCallCredentials.from(
            OAuth2Credentials.create(new AccessToken("token2", /* expirationTime= */ null)));
    XdsTransportCallCredentialsProvider.setTransportCallCredentials("target2", sampleCreds2);

    // Create xDS clients & transports, and verify that the appropriate custom CallCredentials were
    // used for each target
    ObjectPool<XdsClient> xdsClientPool1 = provider.getOrCreate("target1", metricRecorder);
    XdsClient xdsClient1 = xdsClientPool1.getObject();
    xdsClient1.watchXdsResource(
        XdsListenerResource.getInstance(), "someLDSresource1", ldsResourceWatcher);
    waitForXdsServerDone();
    assertThat(adsService.receivedToken("token1")).isTrue();

    ObjectPool<XdsClient> xdsClientPool2 = provider.getOrCreate("target2", metricRecorder);
    XdsClient xdsClient2 = xdsClientPool2.getObject();
    xdsClient2.watchXdsResource(
        XdsListenerResource.getInstance(), "someLDSresource2", ldsResourceWatcher);
    waitForXdsServerDone();
    assertThat(adsService.receivedToken("token2")).isTrue();
  }

  private void waitForXdsServerDone() {
    try {
      handleDiscoveryRequest.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      throw new AssertionError(
          "Interrupted while waiting for xDS server to finish handling request", e);
    } catch (BrokenBarrierException e) {
      throw new AssertionError("Did not wait for xDS server to finish handling request", e);
    } catch (TimeoutException e) {
      throw new AssertionError(
          "Timed out while waiting for xDS server to finish handling request", e);
    }
  }
}
