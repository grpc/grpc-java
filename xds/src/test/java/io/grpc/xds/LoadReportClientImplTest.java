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
  private static final Locality TEST_LOCALITY =
      Locality.newBuilder()
          .setRegion("test_region")
          .setZone("test_zone")
          .setSubZone("test_subzone")
          .build();
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
  private LoadStatsStore mockLoadStatsStore;
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
  public void loadReportInitialRequest() {
    verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    assertThat(lrsRequestObservers).hasSize(1);
    StreamObserver<LoadStatsRequest> requestObserver = lrsRequestObservers.poll();
    verify(requestObserver).onNext(EXPECTED_INITIAL_REQ);
    // No more request should be sent until receiving initial response. No load reporting
    // should be scheduled.
    assertThat(fakeClock.getPendingTasks(LOAD_REPORTING_TASK_FILTER)).isEmpty();
    verifyNoMoreInteractions(requestObserver);
  }

  @Test
  public void startAndStopCanBeCalledMultipleTimes() {
    verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    assertThat(lrsRequestObservers).hasSize(1);
    StreamObserver<LoadStatsRequest> requestObserver = lrsRequestObservers.peek();
    verify(requestObserver).onNext(EXPECTED_INITIAL_REQ);
    lrsClient.startLoadReporting(callback);
    assertThat(lrsRequestObservers).hasSize(1);
    lrsClient.startLoadReporting(callback);
    assertThat(lrsRequestObservers).hasSize(1);
    verifyNoMoreInteractions(requestObserver);

    lrsClient.stopLoadReporting();
    assertThat(callEnded.get()).isTrue();
    assertThat(fakeClock.getPendingTasks(LRS_RPC_RETRY_TASK_FILTER)).isEmpty();
    lrsClient.stopLoadReporting();
    assertThat(callEnded.get()).isTrue();

    lrsClient.startLoadReporting(callback);
    verify(mockLoadReportingService, times(2)).streamLoadStats(lrsResponseObserverCaptor.capture());
    assertThat(lrsRequestObservers).hasSize(2);
  }

  // Currently we expect each gRPC client talks to a single service per cluster, so we test LRS
  // client reporting load for a single cluster service only.
  // TODO(chengyuanzhang): Existing test suites for LRS client implementation have poor behavior
  //  coverage and are not robust. Should improve once its usage is finalized without too much
  //  assumption.

  @Test
  public void loadReportActualIntervalAsSpecified() {
    verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    StreamObserver<LoadStatsResponse> responseObserver = lrsResponseObserverCaptor.getValue();
    assertThat(lrsRequestObservers).hasSize(1);
    StreamObserver<LoadStatsRequest> requestObserver = lrsRequestObservers.poll();

    // Add load stats source for some cluster service.
    when(mockLoadStatsStore.generateLoadReport()).thenReturn(ClusterStats.newBuilder().build());
    lrsClient.addLoadStatsStore("namespace-foo:service-blade", mockLoadStatsStore);

    InOrder inOrder = inOrder(requestObserver, mockLoadStatsStore);
    inOrder.verify(requestObserver).onNext(EXPECTED_INITIAL_REQ);

    responseObserver.onNext(buildLrsResponse("namespace-foo:service-blade", 1453));
    assertNextReport(inOrder, requestObserver, mockLoadStatsStore,
        buildEmptyClusterStats("namespace-foo:service-blade", 1453));
    verify(callback).onReportResponse(1453);
  }

  @Test
  public void loadReportIntervalUpdate() {
    verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    StreamObserver<LoadStatsResponse> responseObserver = lrsResponseObserverCaptor.getValue();
    assertThat(lrsRequestObservers).hasSize(1);
    StreamObserver<LoadStatsRequest> requestObserver = lrsRequestObservers.poll();

    // Add load stats source for some cluster service.
    when(mockLoadStatsStore.generateLoadReport()).thenReturn(ClusterStats.newBuilder().build());
    lrsClient.addLoadStatsStore("namespace-foo:service-blade", mockLoadStatsStore);

    InOrder inOrder = inOrder(requestObserver, mockLoadStatsStore);
    inOrder.verify(requestObserver).onNext(EXPECTED_INITIAL_REQ);

    responseObserver.onNext(buildLrsResponse("namespace-foo:service-blade", 1362));
    assertNextReport(inOrder, requestObserver, mockLoadStatsStore,
        buildEmptyClusterStats("namespace-foo:service-blade", 1362));
    verify(callback).onReportResponse(1362);

    responseObserver.onNext(buildLrsResponse("namespace-foo:service-blade", 2183345));
    // Updated load reporting interval becomes effective immediately.
    assertNextReport(inOrder, requestObserver, mockLoadStatsStore,
        buildEmptyClusterStats("namespace-foo:service-blade", 2183345));
    verify(callback).onReportResponse(2183345);
  }

  @Test
  public void reportNothingIfLoadStatsSourceNotAvailable() {
    verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    StreamObserver<LoadStatsResponse> responseObserver = lrsResponseObserverCaptor.getValue();
    assertThat(lrsRequestObservers).hasSize(1);
    StreamObserver<LoadStatsRequest> requestObserver = lrsRequestObservers.poll();
    verify(requestObserver).onNext(eq(EXPECTED_INITIAL_REQ));

    // Server asks to report load for some cluster service.
    responseObserver.onNext(buildLrsResponse("namespace-foo:service-blade", 1395));

    // Nothing to be reported as no load stats data is available.
    fakeClock.forwardNanos(1395);
    ArgumentCaptor<LoadStatsRequest> reportCaptor = ArgumentCaptor.forClass(null);
    verify(requestObserver, times(2)).onNext(reportCaptor.capture());
    assertThat(reportCaptor.getValue().getClusterStatsCount()).isEqualTo(0);

    // Add load stats source.
    ClusterStats clusterStats = ClusterStats.newBuilder()
        .setClusterName("namespace-foo:service-blade")
        .setLoadReportInterval(Durations.fromNanos(50))
        .addUpstreamLocalityStats(UpstreamLocalityStats.newBuilder()
            .setLocality(TEST_LOCALITY)
            .setTotalRequestsInProgress(542)
            .setTotalSuccessfulRequests(645)
            .setTotalErrorRequests(85)
            .setTotalIssuedRequests(27))
        .addDroppedRequests(DroppedRequests.newBuilder()
            .setCategory("lb")
            .setDroppedCount(0))
        .addDroppedRequests(DroppedRequests.newBuilder()
            .setCategory("throttle")
            .setDroppedCount(14))
        .setTotalDroppedRequests(14)
        .build();
    when(mockLoadStatsStore.generateLoadReport()).thenReturn(clusterStats);
    lrsClient.addLoadStatsStore("namespace-foo:service-blade", mockLoadStatsStore);

    // Loads reported.
    fakeClock.forwardNanos(1395);
    verify(requestObserver, times(3)).onNext(reportCaptor.capture());
    assertThat(reportCaptor.getValue().getClusterStatsCount()).isEqualTo(1);

    // Delete load stats source.
    lrsClient.removeLoadStatsStore("namespace-foo:service-blade");

    // Nothing to report as load stats data is not available.
    fakeClock.forwardNanos(1395);
    verify(requestObserver, times(4)).onNext(reportCaptor.capture());
    assertThat(reportCaptor.getValue().getClusterStatsCount()).isEqualTo(0);
  }

  @Test
  public void reportRecordedLoadData() {
    verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    StreamObserver<LoadStatsResponse> responseObserver = lrsResponseObserverCaptor.getValue();
    assertThat(lrsRequestObservers).hasSize(1);
    StreamObserver<LoadStatsRequest> requestObserver = lrsRequestObservers.poll();

    long callsInProgress = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long callsSucceeded = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long callsFailed = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long callsIssued = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long numLbDrops = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long numThrottleDrops = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);

    ClusterStats expectedStats1 = ClusterStats.newBuilder()
        .setClusterName("namespace-foo:service-blade")
        .setLoadReportInterval(Durations.fromNanos(1362))
        .addUpstreamLocalityStats(UpstreamLocalityStats.newBuilder()
            .setLocality(TEST_LOCALITY)
            .setTotalRequestsInProgress(callsInProgress)
            .setTotalSuccessfulRequests(callsSucceeded)
            .setTotalErrorRequests(callsFailed)
            .setTotalIssuedRequests(callsIssued))
        .addDroppedRequests(DroppedRequests.newBuilder()
            .setCategory("lb")
            .setDroppedCount(numLbDrops))
        .addDroppedRequests(DroppedRequests.newBuilder()
            .setCategory("throttle")
            .setDroppedCount(numThrottleDrops))
        .setTotalDroppedRequests(numLbDrops + numThrottleDrops)
        .build();
    ClusterStats expectedStats2 = ClusterStats.newBuilder()
        .setClusterName("namespace-foo:service-blade")
        .setLoadReportInterval(Durations.fromNanos(1362))
        .addUpstreamLocalityStats(UpstreamLocalityStats.newBuilder()
            .setLocality(TEST_LOCALITY)
            .setTotalRequestsInProgress(callsInProgress))
        .addDroppedRequests(DroppedRequests.newBuilder()
            .setCategory("lb")
            .setDroppedCount(0))
        .addDroppedRequests(DroppedRequests.newBuilder()
            .setCategory("throttle")
            .setDroppedCount(0))
        .setTotalDroppedRequests(0)
        .build();

    // Add load stats source for some cluster service.
    when(mockLoadStatsStore.generateLoadReport()).thenReturn(expectedStats1, expectedStats2);
    lrsClient.addLoadStatsStore("namespace-foo:service-blade", mockLoadStatsStore);

    InOrder inOrder = inOrder(requestObserver, mockLoadStatsStore);
    inOrder.verify(requestObserver).onNext(EXPECTED_INITIAL_REQ);

    responseObserver.onNext(buildLrsResponse("namespace-foo:service-blade", 1362));
    assertNextReport(inOrder, requestObserver, mockLoadStatsStore, expectedStats1);

    assertNextReport(inOrder, requestObserver, mockLoadStatsStore, expectedStats2);
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
    responseObserver.onNext(buildLrsResponse("namespace-foo:service-blade", 0));

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

    // Wrapping up
    verify(backoffPolicyProvider, times(2)).get();
    verify(backoffPolicy1, times(2)).nextBackoffNanos();
    verify(backoffPolicy2, times(1)).nextBackoffNanos();
  }

  @Test
  public void lrsStreamRetryAndRereport() {
    verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    StreamObserver<LoadStatsResponse> responseObserver = lrsResponseObserverCaptor.getValue();
    assertThat(lrsRequestObservers).hasSize(1);
    StreamObserver<LoadStatsRequest> requestObserver = lrsRequestObservers.poll();

    // Add load stats source for some cluster service.
    ClusterStats stats1 = ClusterStats.newBuilder()
        .setClusterName("namespace-foo:service-blade")
        .setLoadReportInterval(Durations.fromNanos(50))
        .addUpstreamLocalityStats(UpstreamLocalityStats.newBuilder()
            .setLocality(TEST_LOCALITY)
            .setTotalRequestsInProgress(542)
            .setTotalSuccessfulRequests(645)
            .setTotalErrorRequests(85)
            .setTotalIssuedRequests(27))
        .addDroppedRequests(DroppedRequests.newBuilder()
            .setCategory("lb")
            .setDroppedCount(0))
        .addDroppedRequests(DroppedRequests.newBuilder()
            .setCategory("throttle")
            .setDroppedCount(14))
        .setTotalDroppedRequests(14)
        .build();
    ClusterStats stats2 = ClusterStats.newBuilder()
        .setClusterName("namespace-foo:service-blade")
        .setLoadReportInterval(Durations.fromNanos(50))
        .addUpstreamLocalityStats(UpstreamLocalityStats.newBuilder()
            .setLocality(TEST_LOCALITY)
            .setTotalRequestsInProgress(89))
        .addDroppedRequests(DroppedRequests.newBuilder()
            .setCategory("lb")
            .setDroppedCount(0))
        .addDroppedRequests(DroppedRequests.newBuilder()
            .setCategory("throttle")
            .setDroppedCount(0))
        .setTotalDroppedRequests(0)
        .build();
    when(mockLoadStatsStore.generateLoadReport()).thenReturn(stats1, stats2);
    lrsClient.addLoadStatsStore("namespace-foo:service-blade", mockLoadStatsStore);

    // First LRS request sent.
    verify(requestObserver).onNext(EXPECTED_INITIAL_REQ);
    assertEquals(0, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Balancer sends a response asking for loads of some cluster service.
    responseObserver.onNext(buildLrsResponse("namespace-foo:service-blade", 100));

    // A load reporting task is scheduled.
    assertEquals(1, fakeClock.numPendingTasks(LOAD_REPORTING_TASK_FILTER));
    fakeClock.forwardNanos(99);
    verifyNoMoreInteractions(requestObserver);

    // Balancer closes the stream with error.
    responseObserver.onError(Status.UNKNOWN.asException());

    // The unsent load report is cancelled.
    assertEquals(0, fakeClock.numPendingTasks(LOAD_REPORTING_TASK_FILTER));
    // Will retry immediately as balancer has responded previously.
    verify(mockLoadReportingService, times(2)).streamLoadStats(lrsResponseObserverCaptor.capture());
    responseObserver = lrsResponseObserverCaptor.getValue();
    assertThat(lrsRequestObservers).hasSize(1);
    requestObserver = lrsRequestObservers.poll();
    InOrder inOrder = inOrder(requestObserver, mockLoadStatsStore);
    inOrder.verify(requestObserver).onNext(eq(EXPECTED_INITIAL_REQ));

    // Balancer sends another response with a different report interval.
    responseObserver.onNext(buildLrsResponse("namespace-foo:service-blade", 50));

    // Load reporting runs normally.
    assertNextReport(inOrder, requestObserver, mockLoadStatsStore, stats1);
    assertNextReport(inOrder, requestObserver, mockLoadStatsStore, stats2);
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
    responseObserver.onNext(buildLrsResponse("namespace-foo:service-blade", 1983));
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

  private static ClusterStats buildEmptyClusterStats(String clusterServiceName,
      long loadReportIntervalNanos) {
    return ClusterStats.newBuilder()
        .setClusterName(clusterServiceName)
        .setLoadReportInterval(Durations.fromNanos(loadReportIntervalNanos)).build();
  }

  private static LoadStatsResponse buildLrsResponse(String clusterServiceName,
      long loadReportIntervalNanos) {
    return LoadStatsResponse.newBuilder()
        .addClusters(clusterServiceName)
        .setLoadReportingInterval(Durations.fromNanos(loadReportIntervalNanos)).build();
  }

  private void assertNextReport(InOrder inOrder, StreamObserver<LoadStatsRequest> requestObserver,
      LoadStatsStore loadStatsStore, ClusterStats expectedStats) {
    long loadReportIntervalNanos = Durations.toNanos(expectedStats.getLoadReportInterval());
    assertEquals(0, fakeClock.forwardTime(loadReportIntervalNanos - 1, TimeUnit.NANOSECONDS));
    inOrder.verifyNoMoreInteractions();
    assertEquals(1, fakeClock.forwardTime(1, TimeUnit.NANOSECONDS));
    // A second load report is scheduled upon the first is sent.
    assertEquals(1, fakeClock.numPendingTasks(LOAD_REPORTING_TASK_FILTER));
    inOrder.verify(loadStatsStore).generateLoadReport();
    ArgumentCaptor<LoadStatsRequest> reportCaptor = ArgumentCaptor.forClass(null);
    inOrder.verify(requestObserver).onNext(reportCaptor.capture());
    LoadStatsRequest report = reportCaptor.getValue();
    assertEquals(report.getNode(), NODE);
    assertEquals(1, report.getClusterStatsCount());
    assertThat(report.getClusterStats(0)).isEqualTo(expectedStats);
  }
}
