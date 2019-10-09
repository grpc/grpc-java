/*
 * Copyright 2019 The gRPC Authors
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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.protobuf.Any;
import com.google.protobuf.UInt32Value;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.core.Address;
import io.envoyproxy.envoy.api.v2.core.Locality;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.api.v2.core.SocketAddress;
import io.envoyproxy.envoy.api.v2.endpoint.Endpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.grpc.ChannelLogger;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.internal.testing.StreamRecorder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.XdsComms2.AdsStreamCallback;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link XdsComms2}.
 */
@RunWith(JUnit4.class)
public class XdsCommsTest {
  private static final String EDS_TYPE_URL =
      "type.googleapis.com/envoy.api.v2.ClusterLoadAssignment";
  private static final FakeClock.TaskFilter LB_RPC_RETRY_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains("AdsRpcRetryTask");
        }
      };

  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();
  @Mock
  private Helper helper;
  @Mock
  private AdsStreamCallback adsStreamCallback;
  @Mock
  private BackoffPolicy.Provider backoffPolicyProvider;
  @Mock
  private BackoffPolicy backoffPolicy1;
  @Mock
  private BackoffPolicy backoffPolicy2;

  private final FakeClock fakeClock = new FakeClock();
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();

  private StreamRecorder<DiscoveryRequest> streamRecorder;
  private StreamObserver<DiscoveryResponse> responseWriter;

  private ManagedChannel channel;
  private XdsComms2 xdsComms;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);

    String serverName = InProcessServerBuilder.generateName();

    AggregatedDiscoveryServiceImplBase serviceImpl = new AggregatedDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamAggregatedResources(
          final StreamObserver<DiscoveryResponse> responseObserver) {
        responseWriter = responseObserver;
        streamRecorder = StreamRecorder.create();

        return new StreamObserver<DiscoveryRequest>() {

          @Override
          public void onNext(DiscoveryRequest value) {
            streamRecorder.onNext(value);
          }

          @Override
          public void onError(Throwable t) {
            streamRecorder.onError(t);
          }

          @Override
          public void onCompleted() {
            streamRecorder.onCompleted();
          }
        };
      }
    };

    cleanupRule.register(
        InProcessServerBuilder
            .forName(serverName)
            .addService(serviceImpl)
            .directExecutor()
            .build()
            .start());
    channel =
        cleanupRule.register(InProcessChannelBuilder.forName(serverName).directExecutor().build());
    doReturn("fake_authority").when(helper).getAuthority();
    doReturn(syncContext).when(helper).getSynchronizationContext();
    doReturn(fakeClock.getScheduledExecutorService()).when(helper).getScheduledExecutorService();
    doReturn(mock(ChannelLogger.class)).when(helper).getChannelLogger();
    lbRegistry.register(new LoadBalancerProvider() {
      @Override
      public boolean isAvailable() {
        return true;
      }

      @Override
      public int getPriority() {
        return 0;
      }

      @Override
      public String getPolicyName() {
        return "round_robin";
      }

      @Override
      public LoadBalancer newLoadBalancer(Helper helper) {
        return null;
      }
    });
    doReturn(backoffPolicy1, backoffPolicy2).when(backoffPolicyProvider).get();
    doReturn(10L, 100L, 1000L).when(backoffPolicy1).nextBackoffNanos();
    doReturn(20L, 200L).when(backoffPolicy2).nextBackoffNanos();
    xdsComms = new XdsComms2(
        channel, helper, adsStreamCallback, backoffPolicyProvider,
        fakeClock.getStopwatchSupplier(), Node.getDefaultInstance());
  }

  @Test
  public void shutdownLbRpc_verifyChannelNotShutdown() throws Exception {
    xdsComms.shutdownLbRpc();
    assertTrue(streamRecorder.awaitCompletion(1, TimeUnit.SECONDS));
    assertEquals(Status.Code.CANCELLED, Status.fromThrowable(streamRecorder.getError()).getCode());
    assertFalse(channel.isShutdown());
  }

  @Test
  public void cancel() throws Exception {
    xdsComms.shutdownLbRpc();
    assertTrue(streamRecorder.awaitCompletion(1, TimeUnit.SECONDS));
    assertEquals(Status.Code.CANCELLED, Status.fromThrowable(streamRecorder.getError()).getCode());
  }

  @Test
  public void handleEdsResponse() {
    assertThat(streamRecorder.getValues()).hasSize(1);
    DiscoveryRequest request = streamRecorder.getValues().get(0);
    assertThat(request.getTypeUrl()).isEqualTo(EDS_TYPE_URL);
    assertThat(request.getResourceNamesList()).hasSize(1);

    Locality localityProto1 = Locality.newBuilder()
        .setRegion("region1").setZone("zone1").setSubZone("subzone1").build();
    LbEndpoint endpoint11 = LbEndpoint.newBuilder()
        .setEndpoint(Endpoint.newBuilder()
            .setAddress(Address.newBuilder()
                .setSocketAddress(SocketAddress.newBuilder()
                    .setAddress("addr11").setPortValue(11))))
        .setLoadBalancingWeight(UInt32Value.of(11))
        .build();
    LbEndpoint endpoint12 = LbEndpoint.newBuilder()
        .setEndpoint(Endpoint.newBuilder()
            .setAddress(Address.newBuilder()
                .setSocketAddress(SocketAddress.newBuilder()
                    .setAddress("addr12").setPortValue(12))))
        .setLoadBalancingWeight(UInt32Value.of(12))
        .build();
    Locality localityProto2 = Locality.newBuilder()
        .setRegion("region2").setZone("zone2").setSubZone("subzone2").build();
    LbEndpoint endpoint21 = LbEndpoint.newBuilder()
        .setEndpoint(Endpoint.newBuilder()
            .setAddress(Address.newBuilder()
                .setSocketAddress(SocketAddress.newBuilder()
                    .setAddress("addr21").setPortValue(21))))
        .setLoadBalancingWeight(UInt32Value.of(21))
        .build();
    LbEndpoint endpoint22 = LbEndpoint.newBuilder()
        .setEndpoint(Endpoint.newBuilder()
            .setAddress(Address.newBuilder()
                .setSocketAddress(SocketAddress.newBuilder()
                    .setAddress("addr22").setPortValue(22))))
        .setLoadBalancingWeight(UInt32Value.of(22))
        .build();
    Locality localityProto3 = Locality.newBuilder()
        .setRegion("region3").setZone("zone3").setSubZone("subzone3").build();
    LbEndpoint endpoint3 = LbEndpoint.newBuilder()
        .setEndpoint(Endpoint.newBuilder()
            .setAddress(Address.newBuilder()
                .setSocketAddress(SocketAddress.newBuilder()
                    .setAddress("addr31").setPortValue(31))))
        .setLoadBalancingWeight(UInt32Value.of(31))
        .build();
    ClusterLoadAssignment clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
        .addEndpoints(LocalityLbEndpoints.newBuilder()
            .setLocality(localityProto1)
            .addLbEndpoints(endpoint11)
            .addLbEndpoints(endpoint12)
            .setLoadBalancingWeight(UInt32Value.of(1)))
        .addEndpoints(LocalityLbEndpoints.newBuilder()
            .setLocality(localityProto2)
            .addLbEndpoints(endpoint21)
            .addLbEndpoints(endpoint22)
            .setLoadBalancingWeight(UInt32Value.of(2)))
        .addEndpoints(LocalityLbEndpoints.newBuilder()
            .setLocality(localityProto3)
            .addLbEndpoints(endpoint3)
            .setLoadBalancingWeight(UInt32Value.of(0)))
        .build();
    DiscoveryResponse edsResponse = DiscoveryResponse.newBuilder()
        .addResources(Any.pack(clusterLoadAssignment))
        .setTypeUrl(EDS_TYPE_URL)
        .build();
    responseWriter.onNext(edsResponse);

    verify(adsStreamCallback).onEdsResponse(clusterLoadAssignment);

    ClusterLoadAssignment clusterLoadAssignment2 = ClusterLoadAssignment.newBuilder()
        .addEndpoints(LocalityLbEndpoints.newBuilder()
            .setLocality(localityProto2)
            .addLbEndpoints(endpoint21)
            .addLbEndpoints(endpoint22)
            .setLoadBalancingWeight(UInt32Value.of(2)))
        .addEndpoints(LocalityLbEndpoints.newBuilder()
            .setLocality(localityProto1)
            .addLbEndpoints(endpoint11)
            .addLbEndpoints(endpoint12)
            .setLoadBalancingWeight(UInt32Value.of(1)))
        .build();
    edsResponse = DiscoveryResponse.newBuilder()
        .addResources(Any.pack(clusterLoadAssignment2))
        .setTypeUrl(EDS_TYPE_URL)
        .build();
    responseWriter.onNext(edsResponse);

    verify(adsStreamCallback).onEdsResponse(clusterLoadAssignment2);
    verifyNoMoreInteractions(adsStreamCallback);

    xdsComms.shutdownLbRpc();
  }

  @Test
  public void serverOnCompleteShouldFailClient() {
    responseWriter.onCompleted();

    verify(adsStreamCallback).onError();
    verifyNoMoreInteractions(adsStreamCallback);
  }

  /**
   * The 1st ADS RPC receives invalid response. Verify retry is scheduled.
   * Verify the 2nd RPC (retry) starts after backoff.
   *
   * <p>The 2nd RPC fails with response observer onError() without receiving initial response.
   * Verify retry is scheduled. Verify the 3rd PRC starts after backoff.
   *
   * <p>The 3rd PRC receives invalid initial response. Verify retry is scheduled.
   * Verify the 4th PRC starts after backoff.
   *
   * <p>The 4th RPC receives valid initial response and then fails with response observer
   * onError(). Verify retry is scheduled. Verify the backoff is reset. Verify the 5th PRC starts
   * immediately.
   *
   * <p>The 5th RPC fails with response observer onError() without receiving initial response.
   * Verify retry is scheduled. Verify the 6th PRC starts after backoff.
   *
   * <p>The 6th RPC fails with response observer onError() without receiving initial response.
   * Verify retry is scheduled. Call {@link XdsComms2#shutdownLbRpc()}, verify retry timer is
   * cancelled.
   */
  @Test
  public void adsRpcRetry() {
    StreamRecorder<DiscoveryRequest> currentStreamRecorder = streamRecorder;
    assertThat(currentStreamRecorder.getValues()).hasSize(1);
    InOrder inOrder =
        inOrder(backoffPolicyProvider, backoffPolicy1, backoffPolicy2, adsStreamCallback);
    inOrder.verify(backoffPolicyProvider).get();
    assertEquals(0, fakeClock.numPendingTasks(LB_RPC_RETRY_TASK_FILTER));

    DiscoveryResponse invalidResponse =
        DiscoveryResponse.newBuilder().setTypeUrl(EDS_TYPE_URL).build();
    // The 1st ADS RPC receives invalid response
    responseWriter.onNext(invalidResponse);
    inOrder.verify(adsStreamCallback).onError();
    assertThat(currentStreamRecorder.getError()).isNotNull();

    // Will start backoff sequence 1 (10ns)
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    assertEquals(1, fakeClock.numPendingTasks(LB_RPC_RETRY_TASK_FILTER));

    // Fast-forward to a moment before the retry
    fakeClock.forwardNanos(9);
    assertEquals(1, fakeClock.numPendingTasks(LB_RPC_RETRY_TASK_FILTER));
    assertSame(streamRecorder, currentStreamRecorder);

    // Then time for retry
    fakeClock.forwardNanos(1);
    assertEquals(0, fakeClock.numPendingTasks(LB_RPC_RETRY_TASK_FILTER));
    assertNotSame(currentStreamRecorder, streamRecorder);
    currentStreamRecorder = streamRecorder;
    assertThat(currentStreamRecorder.getValues()).hasSize(1);

    // Fail the retry after spending 4ns
    fakeClock.forwardNanos(4);
    // The 2nd RPC fails with response observer onError() without receiving initial response
    responseWriter.onError(new Exception("fake error"));
    inOrder.verify(adsStreamCallback).onError();

    // Will start backoff sequence 2 (100ns)
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    assertEquals(1, fakeClock.numPendingTasks(LB_RPC_RETRY_TASK_FILTER));
    // Fast-forward to a moment before the retry, the time spent in the last try is deducted.
    fakeClock.forwardNanos(100 - 4 - 1);
    assertEquals(1, fakeClock.numPendingTasks(LB_RPC_RETRY_TASK_FILTER));
    assertSame(streamRecorder, currentStreamRecorder);

    // Then time for retry
    fakeClock.forwardNanos(1);
    assertEquals(0, fakeClock.numPendingTasks(LB_RPC_RETRY_TASK_FILTER));
    assertNotSame(currentStreamRecorder, streamRecorder);
    currentStreamRecorder = streamRecorder;
    assertThat(currentStreamRecorder.getValues()).hasSize(1);
    assertThat(currentStreamRecorder.getError()).isNull();

    // Fail the retry after spending 5ns
    fakeClock.forwardNanos(5);
    // The 3rd PRC receives invalid initial response.
    responseWriter.onNext(invalidResponse);
    inOrder.verify(adsStreamCallback).onError();
    assertThat(currentStreamRecorder.getError()).isNotNull();

    // Will start backoff sequence 3 (1000ns)
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    assertEquals(1, fakeClock.numPendingTasks(LB_RPC_RETRY_TASK_FILTER));

    // Fast-forward to a moment before the retry, the time spent in the last try is deducted.
    fakeClock.forwardNanos(1000 - 5 - 1);
    assertEquals(1, fakeClock.numPendingTasks(LB_RPC_RETRY_TASK_FILTER));
    assertSame(streamRecorder, currentStreamRecorder);

    // Then time for retry
    fakeClock.forwardNanos(1);
    assertEquals(0, fakeClock.numPendingTasks(LB_RPC_RETRY_TASK_FILTER));
    assertNotSame(currentStreamRecorder, streamRecorder);
    currentStreamRecorder = streamRecorder;
    assertThat(currentStreamRecorder.getValues()).hasSize(1);
    assertThat(currentStreamRecorder.getError()).isNull();

    // The 4th RPC receives valid initial response
    fakeClock.forwardNanos(6);
    Locality localityProto1 = Locality.newBuilder()
        .setRegion("region1").setZone("zone1").setSubZone("subzone1").build();
    LbEndpoint endpoint11 = LbEndpoint.newBuilder()
        .setEndpoint(Endpoint.newBuilder()
            .setAddress(Address.newBuilder()
                .setSocketAddress(SocketAddress.newBuilder()
                    .setAddress("addr11").setPortValue(11))))
        .setLoadBalancingWeight(UInt32Value.of(11))
        .build();
    DiscoveryResponse validEdsResponse = DiscoveryResponse.newBuilder()
        .addResources(Any.pack(ClusterLoadAssignment.newBuilder()
            .addEndpoints(LocalityLbEndpoints.newBuilder()
                .setLocality(localityProto1)
                .addLbEndpoints(endpoint11)
                .setLoadBalancingWeight(UInt32Value.of(1)))
            .build()))
        .setTypeUrl(EDS_TYPE_URL)
        .build();
    responseWriter.onNext(validEdsResponse);

    inOrder.verify(backoffPolicyProvider, never()).get();
    inOrder.verify(backoffPolicy2, never()).nextBackoffNanos();
    assertEquals(0, fakeClock.numPendingTasks(LB_RPC_RETRY_TASK_FILTER));

    // The 4th RPC then fails with response observer onError()
    fakeClock.forwardNanos(7);
    responseWriter.onError(new Exception("fake error"));

    // Will reset the retry sequence and retry immediately, because balancer has responded.
    inOrder.verify(backoffPolicyProvider).get();
    assertEquals(0, fakeClock.numPendingTasks(LB_RPC_RETRY_TASK_FILTER));
    assertNotSame(currentStreamRecorder, streamRecorder);
    currentStreamRecorder = streamRecorder;
    assertThat(currentStreamRecorder.getValues()).hasSize(1);
    assertThat(currentStreamRecorder.getError()).isNull();

    // The 5th RPC fails with response observer onError() without receiving initial response
    fakeClock.forwardNanos(8);
    responseWriter.onError(new Exception("fake error"));
    inOrder.verify(adsStreamCallback).onError();

    // Will start backoff sequence 1 (20ns)
    inOrder.verify(backoffPolicy2).nextBackoffNanos();
    assertEquals(1, fakeClock.numPendingTasks(LB_RPC_RETRY_TASK_FILTER));
    // Fast-forward to a moment before the retry, the time spent in the last try is deducted.
    fakeClock.forwardNanos(20 - 8 - 1);
    assertEquals(1, fakeClock.numPendingTasks(LB_RPC_RETRY_TASK_FILTER));
    assertSame(streamRecorder, currentStreamRecorder);

    // Then time for retry
    fakeClock.forwardNanos(1);
    assertEquals(0, fakeClock.numPendingTasks(LB_RPC_RETRY_TASK_FILTER));
    assertNotSame(currentStreamRecorder, streamRecorder);
    currentStreamRecorder = streamRecorder;
    assertThat(currentStreamRecorder.getValues()).hasSize(1);
    assertThat(currentStreamRecorder.getError()).isNull();

    // Wrapping up
    verify(backoffPolicyProvider, times(2)).get();
    verify(backoffPolicy1, times(3)).nextBackoffNanos(); // for 2nd, 3rd, 4th RPC
    verify(backoffPolicy2, times(1)).nextBackoffNanos(); // for 6th RPC

    // The 6th RPC fails with response observer onError() without receiving initial response
    responseWriter.onError(new Exception("fake error"));
    inOrder.verify(adsStreamCallback).onError();

    // Retry is scheduled
    assertEquals(1, fakeClock.numPendingTasks(LB_RPC_RETRY_TASK_FILTER));

    // Shutdown cancels retry
    xdsComms.shutdownLbRpc();
    assertEquals(0, fakeClock.numPendingTasks(LB_RPC_RETRY_TASK_FILTER));
  }

  @Test
  public void refreshAdsStreamCancelsExistingRetry() {
    responseWriter.onError(new Exception("fake error"));
    verify(adsStreamCallback).onError();
    assertEquals(1, fakeClock.numPendingTasks(LB_RPC_RETRY_TASK_FILTER));

    xdsComms.refreshAdsStream();
    assertEquals(0, fakeClock.numPendingTasks(LB_RPC_RETRY_TASK_FILTER));

    xdsComms.shutdownLbRpc();
  }
}
