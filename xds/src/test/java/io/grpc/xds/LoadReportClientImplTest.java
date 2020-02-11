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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.MoreExecutors;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * Unit tests for {@link LoadReportClientImpl}.
 */
@RunWith(JUnit4.class)
public class LoadReportClientImplTest {

  private static final String CLUSTER_NAME = "foo.blade.googleapis.com";
  private static final Node NODE = Node.newBuilder().setId("LRS test").build();
  private static final FakeClock.TaskFilter LOAD_REPORTING_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString()
              .contains(LoadReportClientImpl.LoadReportingTask.class.getSimpleName());
        }
      };
  private static final FakeClock.TaskFilter LRS_RPC_RETRY_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString()
              .contains(LoadReportClientImpl.LrsRpcRetryTask.class.getSimpleName());
        }
      };
  private static final LoadStatsRequest EXPECTED_INITIAL_REQ =
      LoadStatsRequest.newBuilder()
          .setNode(NODE)
          .addClusterStats(ClusterStats.newBuilder().setClusterName(CLUSTER_NAME))
          .build();

  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();
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
  private LoadReportClientImpl lrsClient;

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
        new LoadReportClientImpl(
            channel,
            CLUSTER_NAME,
            NODE, syncContext,
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
    InOrder inOrder = inOrder(requestObserver);
    inOrder.verify(requestObserver).onNext(EXPECTED_INITIAL_REQ);

    String service1 = "namespace-foo:service-blade";
    ClusterStats rawStats1 = generateServiceLoadStats();
    when(loadStatsStore1.generateLoadReport()).thenReturn(rawStats1);
    lrsClient.addLoadStatsStore(service1, loadStatsStore1);
    responseObserver.onNext(buildLrsResponse(ImmutableList.of(service1), 1000));

    ArgumentMatcher<LoadStatsRequest> expectedLoadReportMatcher =
        new LoadStatsRequestMatcher(ImmutableMap.of(service1, rawStats1), 1000);
    fakeClock.forwardNanos(999);
    inOrder.verifyNoMoreInteractions();
    fakeClock.forwardNanos(1);
    inOrder.verify(requestObserver).onNext(argThat(expectedLoadReportMatcher));

    fakeClock.forwardNanos(1000);
    inOrder.verify(requestObserver).onNext(argThat(expectedLoadReportMatcher));

    // Management server updates the interval of sending load reports.
    responseObserver.onNext(buildLrsResponse(ImmutableList.of(service1), 2000));

    fakeClock.forwardNanos(1000);
    inOrder.verifyNoMoreInteractions();

    fakeClock.forwardNanos(1000);
    inOrder.verify(requestObserver)
        .onNext(argThat(new LoadStatsRequestMatcher(ImmutableMap.of(service1, rawStats1), 2000)));

    String service2 = "namespace-bar:service-baz";
    ClusterStats rawStats2 = generateServiceLoadStats();
    when(loadStatsStore2.generateLoadReport()).thenReturn(rawStats2);
    lrsClient.addLoadStatsStore(service2, loadStatsStore2);

    // Management server asks to report loads for an extra cluster service.
    responseObserver.onNext(buildLrsResponse(ImmutableList.of(service1, service2), 2000));

    fakeClock.forwardNanos(2000);
    inOrder.verify(requestObserver)
        .onNext(
            argThat(
                new LoadStatsRequestMatcher(
                    ImmutableMap.of(service1, rawStats1, service2, rawStats2), 2000)));

    // Load reports for one of existing service is no longer wanted.
    responseObserver.onNext(buildLrsResponse(ImmutableList.of(service2), 2000));

    fakeClock.forwardNanos(2000);
    inOrder.verify(requestObserver)
        .onNext(argThat(new LoadStatsRequestMatcher(ImmutableMap.of(service2, rawStats2), 2000)));

    // Management server asks loads for a cluster service that client has no load data.
    responseObserver.onNext(buildLrsResponse(ImmutableList.of("namespace-ham:service-spam"), 2000));

    fakeClock.forwardNanos(2000);
    ArgumentCaptor<LoadStatsRequest> reportCaptor = ArgumentCaptor.forClass(null);
    inOrder.verify(requestObserver).onNext(reportCaptor.capture());
    assertThat(reportCaptor.getValue().getClusterStatsCount()).isEqualTo(0);
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
    verify(requestObserver).onNext(EXPECTED_INITIAL_REQ);
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
    verify(requestObserver).onNext(eq(EXPECTED_INITIAL_REQ));
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
    verify(requestObserver).onNext(eq(EXPECTED_INITIAL_REQ));
    assertEquals(0, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Balancer sends a response asking for loads of some cluster service.
    String serviceName = "namespace-foo:service-blade";
    responseObserver
        .onNext(buildLrsResponse(ImmutableList.of(serviceName), 0));

    // Then breaks the RPC
    responseObserver.onError(Status.UNAVAILABLE.asException());

    // Will reset the retry sequence and retry immediately, because balancer has responded.
    inOrder.verify(backoffPolicyProvider).get();
    inOrder.verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    responseObserver = lrsResponseObserverCaptor.getValue();
    assertThat(lrsRequestObservers).hasSize(1);
    requestObserver = lrsRequestObservers.poll();
    verify(requestObserver).onNext(eq(EXPECTED_INITIAL_REQ));

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
    verify(requestObserver).onNext(eq(EXPECTED_INITIAL_REQ));
    assertEquals(0, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Load reporting back to normal.
    responseObserver = lrsResponseObserverCaptor.getValue();
    ClusterStats stats = generateServiceLoadStats();
    when(loadStatsStore1.generateLoadReport()).thenReturn(stats);
    lrsClient.addLoadStatsStore(serviceName, loadStatsStore1);
    responseObserver
        .onNext(buildLrsResponse(ImmutableList.of(serviceName), 10));
    fakeClock.forwardNanos(10);
    verify(requestObserver)
        .onNext(argThat(new LoadStatsRequestMatcher(ImmutableMap.of(serviceName, stats), 10)));

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

    // First balancer RPC
    verify(requestObserver).onNext(EXPECTED_INITIAL_REQ);
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
      List<String> clusterServiceNames, long loadReportIntervalNanos) {
    return
        LoadStatsResponse
            .newBuilder()
            .addAllClusters(clusterServiceNames)
            .setLoadReportingInterval(Durations.fromNanos(loadReportIntervalNanos))
            .build();
  }

  /**
   * Generates a raw service load stats report with random data.
   */
  private static ClusterStats generateServiceLoadStats() {
    long callsInProgress = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long callsSucceeded = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long callsFailed = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long callsIssued = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long numLbDrops = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long numThrottleDrops = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);

    return
        ClusterStats.newBuilder()
            .addUpstreamLocalityStats(
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
            .setTotalDroppedRequests(numLbDrops + numThrottleDrops)
            .build();
  }

  /**
   * For comparing LoadStatsRequest based on a collection of raw service load stats.
   */
  private static class LoadStatsRequestMatcher implements ArgumentMatcher<LoadStatsRequest> {
    private final Map<String, ClusterStats> expectedStats = new HashMap<>();

    LoadStatsRequestMatcher(Map<String, ClusterStats> serviceStats, long expectedIntervalNano) {
      for (String serviceName : serviceStats.keySet()) {
        // TODO(chengyuanzhang): the field to be populated should be cluster_service_name.
        ClusterStats statsWithInterval =
            serviceStats.get(serviceName)
                .toBuilder()
                .setClusterName(serviceName)
                .setLoadReportInterval(Durations.fromNanos(expectedIntervalNano))
                .build();
        expectedStats.put(serviceName, statsWithInterval);
      }
    }

    @Override
    public boolean matches(LoadStatsRequest argument) {
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
