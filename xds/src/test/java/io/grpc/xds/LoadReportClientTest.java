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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.Durations;
import io.envoyproxy.envoy.api.v2.core.Locality;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats.DroppedRequests;
import io.envoyproxy.envoy.api.v2.endpoint.UpstreamLocalityStats;
import io.envoyproxy.envoy.service.load_stats.v2.LoadReportingServiceGrpc;
import io.envoyproxy.envoy.service.load_stats.v2.LoadStatsRequest;
import io.envoyproxy.envoy.service.load_stats.v2.LoadStatsResponse;
import io.grpc.Context;
import io.grpc.Context.CancellationListener;
import io.grpc.InternalLogId;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.LoadReportClient.LoadReportCallback;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link LoadReportClient}.
 */
@RunWith(JUnit4.class)
public class LoadReportClientTest {
  private static final String TARGET_NAME = "lrs-test.example.com";
  // bootstrap node identifier
  private static final Node NODE =
      Node.newBuilder()
          .setId("LRS test")
          .setMetadata(
              Struct.newBuilder()
                  .putFields(
                      "TRAFFICDIRECTOR_NETWORK_HOSTNAME",
                      Value.newBuilder().setStringValue("default").build()))
          .build();
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
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final InternalLogId logId = InternalLogId.allocate("lrs-client-test", null);
  private final FakeClock fakeClock = new FakeClock();
  private final ArrayDeque<StreamObserver<LoadStatsRequest>> lrsRequestObservers =
      new ArrayDeque<>();
  private final AtomicBoolean callEnded = new AtomicBoolean(true);

  @Mock
  private BackoffPolicy.Provider backoffPolicyProvider;
  @Mock
  private BackoffPolicy backoffPolicy1;
  @Mock
  private BackoffPolicy backoffPolicy2;
  @Mock
  private LoadStatsStore loadStatsStore1;
  @Mock
  private LoadStatsStore loadStatsStore2;
  @Mock
  private LoadReportCallback callback;
  @Captor
  private ArgumentCaptor<StreamObserver<LoadStatsResponse>> lrsResponseObserverCaptor;

  private LoadReportingServiceGrpc.LoadReportingServiceImplBase mockLoadReportingService;
  private ManagedChannel channel;
  private LoadReportClient lrsClient;

  @SuppressWarnings("unchecked")
  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
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
        .thenReturn(TimeUnit.SECONDS.toNanos(1L), TimeUnit.SECONDS.toNanos(10L));
    lrsClient =
        new LoadReportClient(
            logId,
            TARGET_NAME,
            channel,
            NODE,
            syncContext,
            fakeClock.getScheduledExecutorService(),
            backoffPolicyProvider,
            fakeClock.getStopwatchSupplier());
    lrsClient.startLoadReporting(callback);
  }

  @After
  public void tearDown() {
    lrsClient.stopLoadReporting();
    assertThat(callEnded.get()).isTrue();
  }

  @Test
  public void typicalWorkflow() {
    verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    StreamObserver<LoadStatsResponse> responseObserver = lrsResponseObserverCaptor.getValue();
    StreamObserver<LoadStatsRequest> requestObserver =
        Iterables.getOnlyElement(lrsRequestObservers);
    InOrder inOrder = inOrder(requestObserver, callback);
    inOrder.verify(requestObserver).onNext(eq(buildInitialRequest()));

    String cluster1 = "cluster-foo.googleapis.com";
    ClusterStats rawStats1 = generateClusterLoadStats(cluster1, null);
    when(loadStatsStore1.generateLoadReport()).thenReturn(rawStats1);
    lrsClient.addLoadStatsStore(cluster1, null, loadStatsStore1);

    // Management server asks to report loads for cluster1.
    responseObserver.onNext(buildLrsResponse(ImmutableList.of(cluster1), 1000));
    inOrder.verify(callback).onReportResponse(1000);

    ArgumentMatcher<LoadStatsRequest> expectedLoadReportMatcher =
        new LoadStatsRequestMatcher(ImmutableList.of(rawStats1), 1000);
    fakeClock.forwardNanos(999);
    inOrder.verifyNoMoreInteractions();
    fakeClock.forwardNanos(1);
    inOrder.verify(requestObserver).onNext(argThat(expectedLoadReportMatcher));

    fakeClock.forwardNanos(1000);
    inOrder.verify(requestObserver).onNext(argThat(expectedLoadReportMatcher));

    String cluster2 = "cluster-bar.googleapis.com";
    ClusterStats rawStats2 = generateClusterLoadStats(cluster2, null);
    when(loadStatsStore2.generateLoadReport()).thenReturn(rawStats2);
    lrsClient.addLoadStatsStore(cluster2, null, loadStatsStore2);

    // Management server updates the interval of sending load reports, while still asking for
    // loads to cluster1 only.
    responseObserver.onNext(buildLrsResponse(ImmutableList.of(cluster1), 2000));
    inOrder.verify(callback).onReportResponse(2000);

    fakeClock.forwardNanos(1000);
    inOrder.verifyNoMoreInteractions();

    fakeClock.forwardNanos(1000);
    inOrder.verify(requestObserver)
        .onNext(argThat(new LoadStatsRequestMatcher(ImmutableList.of(rawStats1), 2000)));

    // Management server asks to report loads for cluster1 and cluster2.
    responseObserver.onNext(buildLrsResponse(ImmutableList.of(cluster1, cluster2), 2000));

    fakeClock.forwardNanos(2000);
    inOrder.verify(requestObserver)
        .onNext(
            argThat(
                new LoadStatsRequestMatcher(ImmutableList.of(rawStats1, rawStats2), 2000)));

    // Load reports for cluster1 is no longer wanted.
    responseObserver.onNext(buildLrsResponse(ImmutableList.of(cluster2), 2000));

    fakeClock.forwardNanos(2000);
    inOrder.verify(requestObserver)
        .onNext(argThat(new LoadStatsRequestMatcher(ImmutableList.of(rawStats2), 2000)));

    // Management server asks loads for a cluster that client has no load data.
    responseObserver
        .onNext(buildLrsResponse(ImmutableList.of("cluster-unknown.googleapis.com"), 2000));

    fakeClock.forwardNanos(2000);
    ArgumentCaptor<LoadStatsRequest> reportCaptor = ArgumentCaptor.forClass(null);
    inOrder.verify(requestObserver).onNext(reportCaptor.capture());
    assertThat(reportCaptor.getValue().getClusterStatsCount()).isEqualTo(0);

    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void lrsStreamClosedAndRetried() {
    InOrder inOrder = inOrder(mockLoadReportingService, backoffPolicyProvider, backoffPolicy1,
        backoffPolicy2);
    inOrder.verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    StreamObserver<LoadStatsResponse> responseObserver = lrsResponseObserverCaptor.getValue();
    assertThat(lrsRequestObservers).hasSize(1);
    StreamObserver<LoadStatsRequest> requestObserver = lrsRequestObservers.poll();

    String clusterName = "cluster-foo.googleapis.com";
    String clusterServiceName = "service-blade.googleapis.com";
    ClusterStats stats = generateClusterLoadStats(clusterName, clusterServiceName);
    when(loadStatsStore1.generateLoadReport()).thenReturn(stats);
    lrsClient.addLoadStatsStore(clusterName, null, loadStatsStore1);

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
    responseObserver
        .onNext(buildLrsResponse(ImmutableList.of(clusterName), 0));

    // Then breaks the RPC
    responseObserver.onError(Status.UNAVAILABLE.asException());

    // Will reset the retry sequence and retry immediately, because balancer has responded.
    inOrder.verify(backoffPolicyProvider).get();
    inOrder.verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    responseObserver = lrsResponseObserverCaptor.getValue();
    assertThat(lrsRequestObservers).hasSize(1);
    requestObserver = lrsRequestObservers.poll();
    verify(requestObserver).onNext(eq(buildInitialRequest()));

    // Fail the retry after spending 4ns
    fakeClock.forwardNanos(4);
    responseObserver.onError(Status.UNAVAILABLE.asException());

    // Will be on the first retry (1s) of backoff sequence 2.
    inOrder.verify(backoffPolicy2).nextBackoffNanos();
    assertEquals(1, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Fast-forward to a moment before the retry, the time spent in the last try is deducted.
    fakeClock.forwardNanos(TimeUnit.SECONDS.toNanos(1) - 4 - 1);
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
    responseObserver
        .onNext(buildLrsResponse(ImmutableList.of(clusterName), 10));
    fakeClock.forwardNanos(10);
    verify(requestObserver)
        .onNext(argThat(new LoadStatsRequestMatcher(ImmutableList.of(stats), 10)));

    // Wrapping up
    verify(backoffPolicyProvider, times(2)).get();
    verify(backoffPolicy1, times(2)).nextBackoffNanos();
    verify(backoffPolicy2, times(1)).nextBackoffNanos();
  }

  @Test
  public void raceBetweenLoadReportingAndLbStreamClosure() {
    verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    StreamObserver<LoadStatsResponse> responseObserver = lrsResponseObserverCaptor.getValue();
    assertThat(lrsRequestObservers).hasSize(1);
    StreamObserver<LoadStatsRequest> requestObserver = lrsRequestObservers.poll();

    String clusterName = "cluster-foo.googleapis.com";
    String clusterServiceName = "service-blade.googleapis.com";
    ClusterStats stats = generateClusterLoadStats(clusterName, clusterServiceName);
    when(loadStatsStore1.generateLoadReport()).thenReturn(stats);
    lrsClient.addLoadStatsStore(clusterName, null, loadStatsStore1);

    // First balancer RPC
    verify(requestObserver).onNext(eq(buildInitialRequest()));
    assertEquals(0, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Simulate receiving a response from traffic director.
    assertEquals(0, fakeClock.numPendingTasks(LOAD_REPORTING_TASK_FILTER));
    responseObserver
        .onNext(buildLrsResponse(ImmutableList.of("namespace-foo:service-blade"), 1983));
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

  private static LoadStatsResponse buildLrsResponse(
      List<String> clusterNames, long loadReportIntervalNanos) {
    return
        LoadStatsResponse
            .newBuilder()
            .addAllClusters(clusterNames)
            .setLoadReportingInterval(Durations.fromNanos(loadReportIntervalNanos))
            .build();
  }

  private static LoadStatsRequest buildInitialRequest() {
    return
        LoadStatsRequest.newBuilder()
            .setNode(
                Node.newBuilder()
                    .setId("LRS test")
                    .setMetadata(
                        Struct.newBuilder()
                            .putFields(
                                "TRAFFICDIRECTOR_NETWORK_HOSTNAME",
                                Value.newBuilder().setStringValue("default").build())
                            .putFields(
                                LoadReportClient.TARGET_NAME_METADATA_KEY,
                                Value.newBuilder().setStringValue(TARGET_NAME).build())))
            .build();
  }

  /**
   * Generates a raw service load stats report with random data.
   */
  private static ClusterStats generateClusterLoadStats(
      String clusterName, @Nullable String clusterServiceName) {
    long callsInProgress = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long callsSucceeded = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long callsFailed = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long callsIssued = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long numLbDrops = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long numThrottleDrops = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);

    ClusterStats.Builder clusterStatsBuilder = ClusterStats.newBuilder();
    clusterStatsBuilder.setClusterName(clusterName);
    if (clusterServiceName != null) {
      clusterStatsBuilder.setClusterServiceName(clusterServiceName);
    }
    clusterStatsBuilder.addUpstreamLocalityStats(
        UpstreamLocalityStats.newBuilder()
            .setLocality(
                Locality.newBuilder()
                    .setRegion("region-foo")
                    .setZone("zone-bar")
                    .setSubZone("subzone-baz"))
            .setTotalRequestsInProgress(callsInProgress)
            .setTotalSuccessfulRequests(callsSucceeded)
            .setTotalErrorRequests(callsFailed)
            .setTotalIssuedRequests(callsIssued))
        .addDroppedRequests(
            DroppedRequests.newBuilder()
                .setCategory("lb")
                .setDroppedCount(numLbDrops))
        .addDroppedRequests(
            DroppedRequests.newBuilder()
                .setCategory("throttle")
                .setDroppedCount(numThrottleDrops))
        .setTotalDroppedRequests(numLbDrops + numThrottleDrops);
    return clusterStatsBuilder.build();
  }

  /**
   * For comparing LoadStatsRequest stats data regardless of .
   */
  private static class LoadStatsRequestMatcher implements ArgumentMatcher<LoadStatsRequest> {
    private final Map<String, ClusterStats> expectedStats = new HashMap<>();

    LoadStatsRequestMatcher(Collection<ClusterStats> clusterStats, long expectedIntervalNano) {
      for (ClusterStats stats : clusterStats) {
        ClusterStats statsWithInterval =
            stats.toBuilder()
                .setLoadReportInterval(Durations.fromNanos(expectedIntervalNano))
                .build();
        expectedStats.put(statsWithInterval.getClusterName(), statsWithInterval);
      }
    }

    @Override
    public boolean matches(LoadStatsRequest argument) {
      if (!argument.getNode().getMetadata()
          .getFieldsOrThrow(LoadReportClient.TARGET_NAME_METADATA_KEY)
          .getStringValue().equals(TARGET_NAME)) {
        return false;
      }
      if (argument.getClusterStatsCount() != expectedStats.size()) {
        return false;
      }
      for (ClusterStats stats : argument.getClusterStatsList()) {
        if (!stats.equals(expectedStats.get(stats.getClusterName()))) {
          return false;
        }
      }
      return true;
    }
  }
}
