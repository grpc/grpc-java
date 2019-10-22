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
import static io.grpc.xds.XdsClientImpl.EDS_TYPE_URL;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
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
import io.grpc.xds.XdsClient.EndpointWatcher;
import io.grpc.xds.XdsClientImpl.EndpointWatchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link AdsLifecycleManager}.
 */
public class AdsLifecycleManagerTest {
  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private static final FakeClock.TaskFilter LB_RPC_RETRY_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains("AdsRpcRetryTask");
        }
      };
  private final FakeClock fakeClock = new FakeClock();
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });

  @Mock
  private BackoffPolicy.Provider backoffPolicyProvider;
  @Mock
  private BackoffPolicy backoffPolicy1;
  @Mock
  private BackoffPolicy backoffPolicy2;
  @Mock
  private EndpointWatcher endpointWatcher;

  private StreamRecorder<DiscoveryRequest> streamRecorder;
  private StreamObserver<DiscoveryResponse> responseWriter;
  private AdsLifecycleManager adsLifecycleManager;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    doReturn(backoffPolicy1, backoffPolicy2).when(backoffPolicyProvider).get();
    doReturn(10L, 100L).when(backoffPolicy1).nextBackoffNanos();
    doReturn(20L, 200L).when(backoffPolicy2).nextBackoffNanos();

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
    ManagedChannel channel =
        cleanupRule.register(InProcessChannelBuilder.forName(serverName).directExecutor().build());
    AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub stub =
        AggregatedDiscoveryServiceGrpc.newStub(channel);
    EndpointWatchers endpointWatchers =
        new EndpointWatchers(Node.newBuilder().setCluster("xdsNode").build());
    endpointWatchers.addWatcher("cluster1", endpointWatcher);
    adsLifecycleManager = new AdsLifecycleManager(
        stub, endpointWatchers, syncContext, fakeClock.getScheduledExecutorService(),
        fakeClock.getStopwatchSupplier(), backoffPolicyProvider);
  }

  @Test
  public void start_retries_shutdownCancelsRetry() {
    assertThat(streamRecorder).isNull();

    adsLifecycleManager.start();

    StreamRecorder<DiscoveryRequest> currentStreamRecorder = streamRecorder;
    assertThat(currentStreamRecorder.getValues()).hasSize(1);
    verify(backoffPolicyProvider).get();
    assertThat(fakeClock.getPendingTasks(LB_RPC_RETRY_TASK_FILTER)).isEmpty();

    // The 1st ADS RPC fails  without receiving initial response.
    responseWriter.onError(new Exception("fake error"));
    verify(endpointWatcher).onError(any(Status.class));

    // Will start backoff sequence 1 (10ns).
    verify(backoffPolicy1).nextBackoffNanos();
    assertThat(fakeClock.getPendingTasks(LB_RPC_RETRY_TASK_FILTER)).hasSize(1);

    // Fast-forward to a moment before the retry.
    fakeClock.forwardNanos(9);
    assertThat(fakeClock.getPendingTasks(LB_RPC_RETRY_TASK_FILTER)).hasSize(1);
    assertSame(streamRecorder, currentStreamRecorder);

    // Then time for retry
    fakeClock.forwardNanos(1);
    assertThat(fakeClock.getPendingTasks(LB_RPC_RETRY_TASK_FILTER)).isEmpty();
    assertNotSame(currentStreamRecorder, streamRecorder);
    currentStreamRecorder = streamRecorder;
    assertThat(currentStreamRecorder.getValues()).hasSize(1);

    // Fail the retry after spending 4ns.
    fakeClock.forwardNanos(4);
    // The 2nd RPC fails with response observer onError() without receiving initial response.
    responseWriter.onError(new Exception("fake error"));
    verify(endpointWatcher, times(2)).onError(any(Status.class));

    // Will start backoff sequence 2 (100ns)
    verify(backoffPolicy1, times(2)).nextBackoffNanos();
    assertThat(fakeClock.getPendingTasks(LB_RPC_RETRY_TASK_FILTER)).hasSize(1);
    // Fast-forward to a moment before the retry, the time spent in the last try is deducted.
    fakeClock.forwardNanos(100 - 4 - 1);
    assertThat(fakeClock.getPendingTasks(LB_RPC_RETRY_TASK_FILTER)).hasSize(1);
    assertSame(streamRecorder, currentStreamRecorder);

    // Then time for retry
    fakeClock.forwardNanos(1);
    assertThat(fakeClock.getPendingTasks(LB_RPC_RETRY_TASK_FILTER)).isEmpty();
    assertNotSame(currentStreamRecorder, streamRecorder);
    currentStreamRecorder = streamRecorder;
    assertThat(currentStreamRecorder.getValues()).hasSize(1);

    // Fail the retry after spending 5ns
    fakeClock.forwardNanos(5);
    // The 3rd PRC receives an initial response.
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
            .setClusterName("cluster1")
            .addEndpoints(LocalityLbEndpoints.newBuilder()
                .setLocality(localityProto1)
                .addLbEndpoints(endpoint11)
                .setLoadBalancingWeight(UInt32Value.of(1)))
            .build()))
        .setTypeUrl(EDS_TYPE_URL)
        .build();
    responseWriter.onNext(validEdsResponse);

    verify(backoffPolicyProvider, times(1)).get(); // still called once
    verify(backoffPolicy2, never()).nextBackoffNanos();
    assertThat(fakeClock.getPendingTasks(LB_RPC_RETRY_TASK_FILTER)).isEmpty();

    // The 3rd RPC then fails
    fakeClock.forwardNanos(7);
    responseWriter.onError(new Exception("fake error"));
    verify(endpointWatcher, times(3)).onError(any(Status.class));

    // Will reset the retry sequence and retry immediately, because balancer has responded.
    verify(backoffPolicyProvider, times(2)).get();
    fakeClock.runDueTasks();
    assertThat(fakeClock.getPendingTasks(LB_RPC_RETRY_TASK_FILTER)).isEmpty();
    assertNotSame(currentStreamRecorder, streamRecorder);
    currentStreamRecorder = streamRecorder;
    assertThat(currentStreamRecorder.getValues()).hasSize(1);

    // The 4th RPC fails with response observer onError() without receiving initial response
    fakeClock.forwardNanos(8);
    responseWriter.onError(new Exception("fake error"));
    verify(endpointWatcher, times(4)).onError(any(Status.class));

    // Will start backoff sequence 1 (20ns)
    verify(backoffPolicy2).nextBackoffNanos();
    assertThat(fakeClock.getPendingTasks(LB_RPC_RETRY_TASK_FILTER)).hasSize(1);
    // Fast-forward to a moment before the retry, the time spent in the last try is deducted.
    fakeClock.forwardNanos(20 - 8 - 1);
    assertThat(fakeClock.getPendingTasks(LB_RPC_RETRY_TASK_FILTER)).hasSize(1);
    assertSame(streamRecorder, currentStreamRecorder);

    // Then time for retry
    fakeClock.forwardNanos(1);
    assertThat(fakeClock.getPendingTasks(LB_RPC_RETRY_TASK_FILTER)).isEmpty();
    assertNotSame(currentStreamRecorder, streamRecorder);
    currentStreamRecorder = streamRecorder;
    assertThat(currentStreamRecorder.getValues()).hasSize(1);
    assertThat(currentStreamRecorder.getError()).isNull();

    // Wrapping up
    verify(backoffPolicyProvider, times(2)).get();
    verify(backoffPolicy1, times(2)).nextBackoffNanos(); // for 2nd, 3rd
    verify(backoffPolicy2, times(1)).nextBackoffNanos(); // for 5th RPC

    // The 5th RPC fails with response observer onError() without receiving initial response
    responseWriter.onError(new Exception("fake error"));
    verify(endpointWatcher, times(5)).onError(any(Status.class));

    // Retry is scheduled
    assertThat(fakeClock.getPendingTasks(LB_RPC_RETRY_TASK_FILTER)).hasSize(1);
    verify(backoffPolicy2, times(2)).nextBackoffNanos();

    // Shutdown cancels retry
    adsLifecycleManager.shutdown();
    assertThat(fakeClock.getPendingTasks(LB_RPC_RETRY_TASK_FILTER)).isEmpty();
  }

  @Test
  public void shutdownCancelsRpc() {
    adsLifecycleManager.start();

    StreamRecorder<DiscoveryRequest> currentStreamRecorder = streamRecorder;
    assertThat(currentStreamRecorder.getValues()).hasSize(1);

    adsLifecycleManager.shutdown();
    assertThat(streamRecorder).isSameInstanceAs(currentStreamRecorder);
    assertThat(streamRecorder.getError()).isNotNull();
    assertThat(Status.fromThrowable(streamRecorder.getError()).getCode())
        .isEqualTo(Status.Code.CANCELLED);
  }
}
