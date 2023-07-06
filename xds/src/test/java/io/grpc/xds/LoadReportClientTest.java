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
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.Durations;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterStats;
import io.envoyproxy.envoy.config.endpoint.v3.EndpointLoadMetricStats;
import io.envoyproxy.envoy.config.endpoint.v3.UpstreamLocalityStats;
import io.envoyproxy.envoy.service.load_stats.v3.LoadReportingServiceGrpc;
import io.envoyproxy.envoy.service.load_stats.v3.LoadStatsRequest;
import io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse;
import io.grpc.Context;
import io.grpc.Context.CancellationListener;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.LoadStatsManager2.ClusterDropStats;
import io.grpc.xds.LoadStatsManager2.ClusterLocalityStats;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Unit tests for {@link LoadReportClient}.
 */
@RunWith(JUnit4.class)
public class LoadReportClientTest {
  // bootstrap node identifier
  private static final EnvoyProtoData.Node NODE =
      EnvoyProtoData.Node.newBuilder()
          .setId("LRS test")
          .setMetadata(ImmutableMap.of("TRAFFICDIRECTOR_NETWORK_HOSTNAME", "default"))
          .build();
  private static final String CLUSTER1 = "cluster-foo.googleapis.com";
  private static final String CLUSTER2 = "cluster-bar.googleapis.com";
  private static final String EDS_SERVICE_NAME1 = "backend-service-foo.googleapis.com";
  private static final String EDS_SERVICE_NAME2 = "backend-service-bar.googleapis.com";
  private static final Locality LOCALITY1 = Locality.create("region1", "zone1", "subZone1");
  private static final Locality LOCALITY2 = Locality.create("region2", "zone2", "subZone2");
  private static final FakeClock.TaskFilter LOAD_REPORTING_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString()
              .contains(LoadReportClient.LoadReportingTask.class.getSimpleName());
        }
      };
  private static final FakeClock.TaskFilter LRS_RPC_RETRY_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString()
              .contains(LoadReportClient.LrsRpcRetryTask.class.getSimpleName());
        }
      };

  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final FakeClock fakeClock = new FakeClock();
  private final ArrayDeque<StreamObserver<LoadStatsRequest>> lrsRequestObservers =
      new ArrayDeque<>();
  private final AtomicBoolean callEnded = new AtomicBoolean(true);
  private final LoadStatsManager2 loadStatsManager =
      new LoadStatsManager2(fakeClock.getStopwatchSupplier());

  @Mock
  private BackoffPolicy.Provider backoffPolicyProvider;
  @Mock
  private BackoffPolicy backoffPolicy1;
  @Mock
  private BackoffPolicy backoffPolicy2;
  @Captor
  private ArgumentCaptor<StreamObserver<LoadStatsResponse>> lrsResponseObserverCaptor;
  @Captor
  private ArgumentCaptor<LoadStatsRequest> requestCaptor;
  @Captor
  private ArgumentCaptor<Throwable> errorCaptor;

  private LoadReportingServiceGrpc.LoadReportingServiceImplBase mockLoadReportingService;
  private ManagedChannel channel;
  private LoadReportClient lrsClient;

  @SuppressWarnings("unchecked")
  @Before
  public void setUp() throws Exception {
    mockLoadReportingService = mock(LoadReportingServiceGrpc.LoadReportingServiceImplBase.class,
        delegatesTo(
            new LoadReportingServiceGrpc.LoadReportingServiceImplBase() {
              @Override
              public StreamObserver<LoadStatsRequest> streamLoadStats(
                  final StreamObserver<LoadStatsResponse> responseObserver) {
                assertThat(callEnded.get()).isTrue();  // ensure previous call was ended
                callEnded.set(false);
                Context.current().addListener(
                    new CancellationListener() {
                      @Override
                      public void cancelled(Context context) {
                        callEnded.set(true);
                      }
                    }, MoreExecutors.directExecutor());
                StreamObserver<LoadStatsRequest> requestObserver =
                    mock(StreamObserver.class);
                lrsRequestObservers.add(requestObserver);
                return requestObserver;
              }
            }
        ));
    cleanupRule.register(InProcessServerBuilder.forName("fakeLoadReportingServer").directExecutor()
        .addService(mockLoadReportingService).build().start());
    channel = cleanupRule.register(
        InProcessChannelBuilder.forName("fakeLoadReportingServer").directExecutor().build());
    when(backoffPolicyProvider.get()).thenReturn(backoffPolicy1, backoffPolicy2);
    when(backoffPolicy1.nextBackoffNanos())
        .thenReturn(TimeUnit.SECONDS.toNanos(1L), TimeUnit.SECONDS.toNanos(10L));
    when(backoffPolicy2.nextBackoffNanos())
        .thenReturn(TimeUnit.SECONDS.toNanos(2L), TimeUnit.SECONDS.toNanos(20L));
    addFakeStatsData();
    lrsClient = new LoadReportClient(loadStatsManager, channel, Context.ROOT, NODE,
        syncContext, fakeClock.getScheduledExecutorService(), backoffPolicyProvider,
        fakeClock.getStopwatchSupplier());
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        lrsClient.startLoadReporting();
      }
    });
  }

  private void addFakeStatsData() {
    ClusterDropStats dropStats1 = loadStatsManager.getClusterDropStats(CLUSTER1, EDS_SERVICE_NAME1);
    for (int i = 0; i < 52; i++) {
      dropStats1.recordDroppedRequest("lb");
    }
    ClusterDropStats dropStats2 = loadStatsManager.getClusterDropStats(CLUSTER2, EDS_SERVICE_NAME2);
    for (int i = 0; i < 23; i++) {
      dropStats2.recordDroppedRequest("throttle");
    }
    ClusterLocalityStats localityStats1 =
        loadStatsManager.getClusterLocalityStats(CLUSTER1, EDS_SERVICE_NAME1, LOCALITY1);
    for (int i = 0; i < 31; i++) {
      localityStats1.recordCallStarted();
    }
    localityStats1.recordBackendLoadMetricStats(ImmutableMap.of("named1", 3.14159));
    localityStats1.recordBackendLoadMetricStats(ImmutableMap.of("named1", 1.618));
    localityStats1.recordBackendLoadMetricStats(ImmutableMap.of("named1", -2.718));
    ClusterLocalityStats localityStats2 =
        loadStatsManager.getClusterLocalityStats(CLUSTER2, EDS_SERVICE_NAME2, LOCALITY2);
    for (int i = 0; i < 45; i++) {
      localityStats2.recordCallStarted();
    }
    localityStats2.recordBackendLoadMetricStats(ImmutableMap.of("named2", 1.414));
    localityStats2.recordCallFinished(Status.OK);
  }

  @After
  public void tearDown() {
    stopLoadReportingInSyncContext();
    assertThat(callEnded.get()).isTrue();
  }

  @Test
  public void periodicLoadReporting() {
    verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    StreamObserver<LoadStatsResponse> responseObserver = lrsResponseObserverCaptor.getValue();
    StreamObserver<LoadStatsRequest> requestObserver =
        Iterables.getOnlyElement(lrsRequestObservers);
    verify(requestObserver).onNext(eq(buildInitialRequest()));

    // Management server asks to report loads for cluster1.
    responseObserver.onNext(LoadStatsResponse.newBuilder().addClusters(CLUSTER1)
        .setLoadReportingInterval(Durations.fromSeconds(10L)).build());

    fakeClock.forwardTime(10L, TimeUnit.SECONDS);
    verify(requestObserver, times(2)).onNext(requestCaptor.capture());
    LoadStatsRequest request = requestCaptor.getValue();
    ClusterStats clusterStats = Iterables.getOnlyElement(request.getClusterStatsList());
    assertThat(clusterStats.getClusterName()).isEqualTo(CLUSTER1);
    assertThat(clusterStats.getClusterServiceName()).isEqualTo(EDS_SERVICE_NAME1);
    assertThat(Durations.toSeconds(clusterStats.getLoadReportInterval())).isEqualTo(10L);
    assertThat(Iterables.getOnlyElement(clusterStats.getDroppedRequestsList()).getCategory())
        .isEqualTo("lb");
    assertThat(Iterables.getOnlyElement(clusterStats.getDroppedRequestsList()).getDroppedCount())
        .isEqualTo(52L);
    assertThat(clusterStats.getTotalDroppedRequests()).isEqualTo(52L);
    UpstreamLocalityStats localityStats =
        Iterables.getOnlyElement(clusterStats.getUpstreamLocalityStatsList());
    assertThat(localityStats.getLocality().getRegion()).isEqualTo("region1");
    assertThat(localityStats.getLocality().getZone()).isEqualTo("zone1");
    assertThat(localityStats.getLocality().getSubZone()).isEqualTo("subZone1");
    assertThat(localityStats.getTotalIssuedRequests()).isEqualTo(31L);
    assertThat(localityStats.getTotalSuccessfulRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalErrorRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalRequestsInProgress()).isEqualTo(31L);
    assertThat(localityStats.getLoadMetricStatsCount()).isEqualTo(1);
    EndpointLoadMetricStats loadMetricStats = Iterables.getOnlyElement(
        localityStats.getLoadMetricStatsList());
    assertThat(loadMetricStats.getMetricName()).isEqualTo("named1");
    assertThat(loadMetricStats.getNumRequestsFinishedWithMetric()).isEqualTo(3L);
    assertThat(loadMetricStats.getTotalMetricValue()).isEqualTo(3.14159 + 1.618 - 2.718);

    fakeClock.forwardTime(10L, TimeUnit.SECONDS);
    verify(requestObserver, times(3)).onNext(requestCaptor.capture());
    request = requestCaptor.getValue();
    clusterStats = Iterables.getOnlyElement(request.getClusterStatsList());
    assertThat(clusterStats.getClusterName()).isEqualTo(CLUSTER1);
    assertThat(clusterStats.getClusterServiceName()).isEqualTo(EDS_SERVICE_NAME1);
    assertThat(Durations.toSeconds(clusterStats.getLoadReportInterval())).isEqualTo(10L);
    assertThat(clusterStats.getDroppedRequestsCount()).isEqualTo(0L);
    assertThat(clusterStats.getTotalDroppedRequests()).isEqualTo(0L);
    localityStats = Iterables.getOnlyElement(clusterStats.getUpstreamLocalityStatsList());
    assertThat(localityStats.getLocality().getRegion()).isEqualTo("region1");
    assertThat(localityStats.getLocality().getZone()).isEqualTo("zone1");
    assertThat(localityStats.getLocality().getSubZone()).isEqualTo("subZone1");
    assertThat(localityStats.getTotalIssuedRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalSuccessfulRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalErrorRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalRequestsInProgress()).isEqualTo(31L);
    assertThat(localityStats.getLoadMetricStatsList()).isEmpty();

    // Management server updates the interval of sending load reports, while still asking for
    // loads to cluster1 only.
    responseObserver.onNext(LoadStatsResponse.newBuilder().addClusters(CLUSTER1)
        .setLoadReportingInterval(Durations.fromSeconds(20L)).build());

    fakeClock.forwardTime(10L, TimeUnit.SECONDS);
    verifyNoMoreInteractions(requestObserver);
    fakeClock.forwardTime(10L, TimeUnit.SECONDS);
    verify(requestObserver, times(4)).onNext(requestCaptor.capture());
    request = requestCaptor.getValue();
    clusterStats = Iterables.getOnlyElement(request.getClusterStatsList());
    assertThat(clusterStats.getClusterName()).isEqualTo(CLUSTER1);
    assertThat(clusterStats.getClusterServiceName()).isEqualTo(EDS_SERVICE_NAME1);
    assertThat(Durations.toSeconds(clusterStats.getLoadReportInterval())).isEqualTo(20L);
    assertThat(clusterStats.getDroppedRequestsCount()).isEqualTo(0);
    localityStats = Iterables.getOnlyElement(clusterStats.getUpstreamLocalityStatsList());
    assertThat(localityStats.getLocality().getRegion()).isEqualTo("region1");
    assertThat(localityStats.getLocality().getZone()).isEqualTo("zone1");
    assertThat(localityStats.getLocality().getSubZone()).isEqualTo("subZone1");
    assertThat(localityStats.getTotalIssuedRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalSuccessfulRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalErrorRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalRequestsInProgress()).isEqualTo(31L);
    assertThat(localityStats.getLoadMetricStatsList()).isEmpty();

    // Management server asks to report loads for all clusters.
    responseObserver.onNext(LoadStatsResponse.newBuilder().setSendAllClusters(true)
        .setLoadReportingInterval(Durations.fromSeconds(20L)).build());

    fakeClock.forwardTime(20L, TimeUnit.SECONDS);
    verify(requestObserver, times(5)).onNext(requestCaptor.capture());
    request = requestCaptor.getValue();
    assertThat(request.getClusterStatsCount()).isEqualTo(2);
    ClusterStats clusterStats1 = findClusterStats(request.getClusterStatsList(), CLUSTER1);
    assertThat(Durations.toSeconds(clusterStats1.getLoadReportInterval())).isEqualTo(20L);
    assertThat(clusterStats1.getDroppedRequestsCount()).isEqualTo(0L);
    assertThat(clusterStats1.getTotalDroppedRequests()).isEqualTo(0L);
    UpstreamLocalityStats localityStats1 =
        Iterables.getOnlyElement(clusterStats1.getUpstreamLocalityStatsList());
    assertThat(localityStats1.getLocality().getRegion()).isEqualTo("region1");
    assertThat(localityStats1.getLocality().getZone()).isEqualTo("zone1");
    assertThat(localityStats1.getLocality().getSubZone()).isEqualTo("subZone1");
    assertThat(localityStats1.getTotalIssuedRequests()).isEqualTo(0L);
    assertThat(localityStats1.getTotalSuccessfulRequests()).isEqualTo(0L);
    assertThat(localityStats1.getTotalErrorRequests()).isEqualTo(0L);
    assertThat(localityStats1.getTotalRequestsInProgress()).isEqualTo(31L);
    assertThat(localityStats1.getLoadMetricStatsList()).isEmpty();
    ClusterStats clusterStats2 = findClusterStats(request.getClusterStatsList(), CLUSTER2);
    assertThat(Durations.toSeconds(clusterStats2.getLoadReportInterval()))
        .isEqualTo(10L + 10L + 20L + 20L);
    assertThat(Iterables.getOnlyElement(clusterStats2.getDroppedRequestsList()).getCategory())
        .isEqualTo("throttle");
    assertThat(Iterables.getOnlyElement(clusterStats2.getDroppedRequestsList()).getDroppedCount())
        .isEqualTo(23L);
    assertThat(clusterStats2.getTotalDroppedRequests()).isEqualTo(23L);
    UpstreamLocalityStats localityStats2 =
        Iterables.getOnlyElement(clusterStats2.getUpstreamLocalityStatsList());
    assertThat(localityStats2.getLocality().getRegion()).isEqualTo("region2");
    assertThat(localityStats2.getLocality().getZone()).isEqualTo("zone2");
    assertThat(localityStats2.getLocality().getSubZone()).isEqualTo("subZone2");
    assertThat(localityStats2.getTotalIssuedRequests()).isEqualTo(45L);
    assertThat(localityStats2.getTotalSuccessfulRequests()).isEqualTo(1L);
    assertThat(localityStats2.getTotalErrorRequests()).isEqualTo(0L);
    assertThat(localityStats2.getTotalRequestsInProgress()).isEqualTo(45L - 1L);
    assertThat(localityStats2.getLoadMetricStatsCount()).isEqualTo(1);
    EndpointLoadMetricStats loadMetricStats2 = Iterables.getOnlyElement(
        localityStats2.getLoadMetricStatsList());
    assertThat(loadMetricStats2.getMetricName()).isEqualTo("named2");
    assertThat(loadMetricStats2.getNumRequestsFinishedWithMetric()).isEqualTo(1L);
    assertThat(loadMetricStats2.getTotalMetricValue()).isEqualTo(1.414);

    // Load reports for cluster1 is no longer wanted.
    responseObserver.onNext(LoadStatsResponse.newBuilder().addClusters(CLUSTER2)
        .setLoadReportingInterval(Durations.fromSeconds(10L)).build());

    fakeClock.forwardTime(10L, TimeUnit.SECONDS);
    verify(requestObserver, times(6)).onNext(requestCaptor.capture());
    request = requestCaptor.getValue();
    clusterStats = Iterables.getOnlyElement(request.getClusterStatsList());
    assertThat(clusterStats.getClusterName()).isEqualTo(CLUSTER2);
    assertThat(clusterStats.getClusterServiceName()).isEqualTo(EDS_SERVICE_NAME2);
    assertThat(Durations.toSeconds(clusterStats.getLoadReportInterval())).isEqualTo(10L);
    assertThat(clusterStats.getDroppedRequestsCount()).isEqualTo(0L);
    assertThat(clusterStats.getTotalDroppedRequests()).isEqualTo(0L);
    localityStats = Iterables.getOnlyElement(clusterStats.getUpstreamLocalityStatsList());
    assertThat(localityStats.getLocality().getRegion()).isEqualTo("region2");
    assertThat(localityStats.getLocality().getZone()).isEqualTo("zone2");
    assertThat(localityStats.getLocality().getSubZone()).isEqualTo("subZone2");
    assertThat(localityStats.getTotalIssuedRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalSuccessfulRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalErrorRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalRequestsInProgress()).isEqualTo(44L);
    assertThat(localityStats.getLoadMetricStatsList()).isEmpty();

    fakeClock.forwardTime(10L, TimeUnit.SECONDS);
    verify(requestObserver, times(7)).onNext(requestCaptor.capture());
    request = requestCaptor.getValue();
    clusterStats = Iterables.getOnlyElement(request.getClusterStatsList());
    assertThat(clusterStats.getClusterName()).isEqualTo(CLUSTER2);
    assertThat(clusterStats.getClusterServiceName()).isEqualTo(EDS_SERVICE_NAME2);
    assertThat(Durations.toSeconds(clusterStats.getLoadReportInterval())).isEqualTo(10L);
    assertThat(clusterStats.getDroppedRequestsCount()).isEqualTo(0L);
    assertThat(clusterStats.getTotalDroppedRequests()).isEqualTo(0L);
    localityStats = Iterables.getOnlyElement(clusterStats.getUpstreamLocalityStatsList());
    assertThat(localityStats.getLocality().getRegion()).isEqualTo("region2");
    assertThat(localityStats.getLocality().getZone()).isEqualTo("zone2");
    assertThat(localityStats.getLocality().getSubZone()).isEqualTo("subZone2");
    assertThat(localityStats.getTotalIssuedRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalSuccessfulRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalErrorRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalRequestsInProgress()).isEqualTo(44L);
    assertThat(localityStats.getLoadMetricStatsList()).isEmpty();

    // Management server asks loads for a cluster that client has no load data.
    responseObserver.onNext(LoadStatsResponse.newBuilder().addClusters("unknown.googleapis.com")
        .setLoadReportingInterval(Durations.fromSeconds(20L)).build());

    fakeClock.forwardTime(20L, TimeUnit.SECONDS);
    verify(requestObserver, times(8)).onNext(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getClusterStatsCount()).isEqualTo(0);
  }

  @Test
  public void lrsStreamClosedAndRetried() {
    InOrder inOrder = inOrder(mockLoadReportingService, backoffPolicyProvider, backoffPolicy1,
        backoffPolicy2);
    inOrder.verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    StreamObserver<LoadStatsResponse> responseObserver = lrsResponseObserverCaptor.getValue();
    assertThat(lrsRequestObservers).hasSize(1);
    StreamObserver<LoadStatsRequest> requestObserver = lrsRequestObservers.poll();

    // First balancer RPC
    verify(requestObserver).onNext(eq(buildInitialRequest()));
    assertEquals(0, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Balancer closes it immediately (erroneously)
    responseObserver.onCompleted();

    // Will start backoff sequence 1 (1s)
    inOrder.verify(backoffPolicyProvider).get();
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    assertEquals(1, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Fast-forward to a moment before the retry
    fakeClock.forwardNanos(TimeUnit.SECONDS.toNanos(1) - 1);
    verifyNoMoreInteractions(mockLoadReportingService);
    // Then time for retry
    fakeClock.forwardNanos(1);
    inOrder.verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    responseObserver = lrsResponseObserverCaptor.getValue();
    assertThat(lrsRequestObservers).hasSize(1);
    requestObserver = lrsRequestObservers.poll();
    verify(requestObserver).onNext(eq(buildInitialRequest()));
    assertEquals(0, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Balancer closes it with an error.
    responseObserver.onError(Status.UNAVAILABLE.asException());
    // Will continue the backoff sequence 1 (10s)
    verifyNoMoreInteractions(backoffPolicyProvider);
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    assertEquals(1, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Fast-forward to a moment before the retry
    fakeClock.forwardNanos(TimeUnit.SECONDS.toNanos(10) - 1);
    verifyNoMoreInteractions(mockLoadReportingService);
    // Then time for retry
    fakeClock.forwardNanos(1);
    inOrder.verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    responseObserver = lrsResponseObserverCaptor.getValue();
    assertThat(lrsRequestObservers).hasSize(1);
    requestObserver = lrsRequestObservers.poll();
    verify(requestObserver).onNext(eq(buildInitialRequest()));
    assertEquals(0, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Balancer sends a response asking for loads of the cluster.
    responseObserver.onNext(LoadStatsResponse.newBuilder().addClusters(CLUSTER1)
        .setLoadReportingInterval(Durations.fromNanos(5L)).build());

    // Then breaks the RPC
    responseObserver.onError(Status.UNAVAILABLE.asException());

    // Will reset the retry sequence retry after a delay. We want to always delay, to restrict any
    // accidental closed loop of retries to 1 QPS.
    inOrder.verify(backoffPolicyProvider).get();
    inOrder.verify(backoffPolicy2).nextBackoffNanos();
    // Fast-forward to a moment before the retry of backoff sequence 2 (2s)
    fakeClock.forwardNanos(TimeUnit.SECONDS.toNanos(2) - 1);
    verifyNoMoreInteractions(mockLoadReportingService);
    // Then time for retry
    fakeClock.forwardNanos(1);
    inOrder.verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    responseObserver = lrsResponseObserverCaptor.getValue();
    assertThat(lrsRequestObservers).hasSize(1);
    requestObserver = lrsRequestObservers.poll();
    verify(requestObserver).onNext(eq(buildInitialRequest()));

    // Fail the retry after spending 4ns
    fakeClock.forwardNanos(4);
    responseObserver.onError(Status.UNAVAILABLE.asException());

    // Will be on the second retry (20s) of backoff sequence 2.
    inOrder.verify(backoffPolicy2).nextBackoffNanos();
    assertEquals(1, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Fast-forward to a moment before the retry, the time spent in the last try is deducted.
    fakeClock.forwardNanos(TimeUnit.SECONDS.toNanos(20) - 4 - 1);
    verifyNoMoreInteractions(mockLoadReportingService);
    // Then time for retry
    fakeClock.forwardNanos(1);
    inOrder.verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    assertThat(lrsRequestObservers).hasSize(1);
    requestObserver = lrsRequestObservers.poll();
    verify(requestObserver).onNext(eq(buildInitialRequest()));
    assertEquals(0, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Load reporting back to normal.
    responseObserver = lrsResponseObserverCaptor.getValue();
    responseObserver.onNext(LoadStatsResponse.newBuilder().addClusters(CLUSTER1)
        .setLoadReportingInterval(Durations.fromNanos(10L)).build());
    fakeClock.forwardNanos(10);
    verify(requestObserver, times(2)).onNext(requestCaptor.capture());
    LoadStatsRequest request = requestCaptor.getValue();
    ClusterStats clusterStats = Iterables.getOnlyElement(request.getClusterStatsList());
    assertThat(clusterStats.getClusterName()).isEqualTo(CLUSTER1);
    assertThat(clusterStats.getClusterServiceName()).isEqualTo(EDS_SERVICE_NAME1);
    assertThat(Durations.toSeconds(clusterStats.getLoadReportInterval()))
        .isEqualTo(1L + 10L + 2L + 20L);
    assertThat(Iterables.getOnlyElement(clusterStats.getDroppedRequestsList()).getCategory())
        .isEqualTo("lb");
    assertThat(Iterables.getOnlyElement(clusterStats.getDroppedRequestsList()).getDroppedCount())
        .isEqualTo(52L);
    assertThat(clusterStats.getTotalDroppedRequests()).isEqualTo(52L);
    UpstreamLocalityStats localityStats =
        Iterables.getOnlyElement(clusterStats.getUpstreamLocalityStatsList());
    assertThat(localityStats.getLocality().getRegion()).isEqualTo("region1");
    assertThat(localityStats.getLocality().getZone()).isEqualTo("zone1");
    assertThat(localityStats.getLocality().getSubZone()).isEqualTo("subZone1");
    assertThat(localityStats.getTotalIssuedRequests()).isEqualTo(31L);
    assertThat(localityStats.getTotalSuccessfulRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalErrorRequests()).isEqualTo(0L);
    assertThat(localityStats.getTotalRequestsInProgress()).isEqualTo(31L);
    assertThat(localityStats.getLoadMetricStatsCount()).isEqualTo(1);
    EndpointLoadMetricStats loadMetricStats = Iterables.getOnlyElement(
        localityStats.getLoadMetricStatsList());
    assertThat(loadMetricStats.getMetricName()).isEqualTo("named1");
    assertThat(loadMetricStats.getNumRequestsFinishedWithMetric()).isEqualTo(3L);
    assertThat(loadMetricStats.getTotalMetricValue()).isEqualTo(3.14159 + 1.618 - 2.718);

    // Wrapping up
    verify(backoffPolicyProvider, times(2)).get();
    verify(backoffPolicy1, times(2)).nextBackoffNanos();
    verify(backoffPolicy2, times(2)).nextBackoffNanos();
  }

  @Test
  public void raceBetweenStopAndLoadReporting() {
    verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    StreamObserver<LoadStatsResponse> responseObserver = lrsResponseObserverCaptor.getValue();
    StreamObserver<LoadStatsRequest> requestObserver =
        Iterables.getOnlyElement(lrsRequestObservers);
    verify(requestObserver).onNext(eq(buildInitialRequest()));

    responseObserver.onNext(LoadStatsResponse.newBuilder().addClusters(CLUSTER1)
        .setLoadReportingInterval(Durations.fromNanos(1234L)).build());
    assertEquals(1, fakeClock.numPendingTasks(LOAD_REPORTING_TASK_FILTER));
    FakeClock.ScheduledTask scheduledTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(LOAD_REPORTING_TASK_FILTER));
    assertEquals(1234, scheduledTask.getDelay(TimeUnit.NANOSECONDS));

    fakeClock.forwardNanos(1233);
    stopLoadReportingInSyncContext();
    verify(requestObserver).onError(errorCaptor.capture());
    assertEquals("CANCELLED: client cancelled", errorCaptor.getValue().getMessage());
    assertThat(scheduledTask.isCancelled()).isTrue();
    fakeClock.forwardNanos(1);
    assertEquals(0, fakeClock.numPendingTasks(LOAD_REPORTING_TASK_FILTER));
    fakeClock.forwardNanos(1234);
    verifyNoMoreInteractions(requestObserver);
  }

  @Test
  public void raceBetweenStopAndLrsStreamRetry() {
    verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    StreamObserver<LoadStatsResponse> responseObserver = lrsResponseObserverCaptor.getValue();
    StreamObserver<LoadStatsRequest> requestObserver =
        Iterables.getOnlyElement(lrsRequestObservers);
    verify(requestObserver).onNext(eq(buildInitialRequest()));

    responseObserver.onCompleted();
    assertEquals(1, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));
    FakeClock.ScheduledTask scheduledTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(LRS_RPC_RETRY_TASK_FILTER));
    assertEquals(1, scheduledTask.getDelay(TimeUnit.SECONDS));

    fakeClock.forwardTime(999, TimeUnit.MILLISECONDS);
    stopLoadReportingInSyncContext();
    assertThat(scheduledTask.isCancelled()).isTrue();
    fakeClock.forwardTime(1, TimeUnit.MILLISECONDS);
    assertEquals(0, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));
    fakeClock.forwardTime(10, TimeUnit.SECONDS);
    verifyNoMoreInteractions(requestObserver);
  }

  @Test
  public void raceBetweenLoadReportingAndLrsStreamClosure() {
    verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    StreamObserver<LoadStatsResponse> responseObserver = lrsResponseObserverCaptor.getValue();
    assertThat(lrsRequestObservers).hasSize(1);
    StreamObserver<LoadStatsRequest> requestObserver = lrsRequestObservers.poll();

    // First balancer RPC
    verify(requestObserver).onNext(eq(buildInitialRequest()));
    assertEquals(0, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Simulate receiving a response from traffic director.
    assertEquals(0, fakeClock.numPendingTasks(LOAD_REPORTING_TASK_FILTER));
    responseObserver.onNext(LoadStatsResponse.newBuilder().addClusters(CLUSTER1)
        .setLoadReportingInterval(Durations.fromNanos(1983L)).build());
    // Load reporting task is scheduled
    assertEquals(1, fakeClock.numPendingTasks(LOAD_REPORTING_TASK_FILTER));
    FakeClock.ScheduledTask scheduledTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(LOAD_REPORTING_TASK_FILTER));
    assertEquals(1983, scheduledTask.getDelay(TimeUnit.NANOSECONDS));

    // Close RPC stream.
    responseObserver.onCompleted();

    // Reporting task cancelled
    assertEquals(0, fakeClock.numPendingTasks(LOAD_REPORTING_TASK_FILTER));

    // Simulate a race condition where the task has just started when its cancelled
    scheduledTask.command.run();

    // No report sent. No new task scheduled
    verifyNoMoreInteractions(requestObserver);
    assertEquals(0, fakeClock.numPendingTasks(LOAD_REPORTING_TASK_FILTER));
  }

  private void stopLoadReportingInSyncContext() {
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        lrsClient.stopLoadReporting();
      }
    });
  }

  private static LoadStatsRequest buildInitialRequest() {
    return
        LoadStatsRequest.newBuilder()
            .setNode(
                Node.newBuilder()
                    .setId("LRS test")
                    .addClientFeatures("envoy.lrs.supports_send_all_clusters")
                    .setMetadata(
                        Struct.newBuilder()
                            .putFields(
                                "TRAFFICDIRECTOR_NETWORK_HOSTNAME",
                                Value.newBuilder().setStringValue("default").build())))
            .build();
  }

  private ClusterStats findClusterStats(List<ClusterStats> clusterStatsList, String clusterName) {
    for (ClusterStats stats : clusterStatsList) {
      if (stats.getClusterName().equals(clusterName)) {
        return stats;
      }
    }
    return null;
  }
}
