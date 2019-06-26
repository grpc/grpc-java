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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.Iterables;
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
import io.grpc.ChannelLogger;
import io.grpc.LoadBalancer.Helper;
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
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
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
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link LoadReportClientImpl}.
 */
@RunWith(JUnit4.class)
public class LoadReportClientImplTest {

  private static final String SERVICE_AUTHORITY = "api.google.com";
  private static final String CLUSTER_NAME = "gslb-namespace:gslb-service-name";
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
  private static final LoadStatsRequest EXPECTED_INITIAL_REQ = LoadStatsRequest.newBuilder()
      .setNode(Node.newBuilder()
          .setMetadata(Struct.newBuilder()
              .putFields(
                  LoadReportClientImpl.TRAFFICDIRECTOR_GRPC_HOSTNAME_FIELD,
                  Value.newBuilder().setStringValue(SERVICE_AUTHORITY).build())))
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
  private final ArrayDeque<String> logs = new ArrayDeque<>();
  private final ChannelLogger channelLogger = new ChannelLogger() {
    @Override
    public void log(ChannelLogLevel level, String msg) {
      logs.add(level + ": " + msg);
    }

    @Override
    public void log(ChannelLogLevel level, String template, Object... args) {
      log(level, MessageFormat.format(template, args));
    }
  };
  private final FakeClock fakeClock = new FakeClock();
  private final ArrayDeque<StreamObserver<LoadStatsRequest>> lrsRequestObservers =
      new ArrayDeque<>();

  @Mock
  private Helper helper;
  @Mock
  private BackoffPolicy.Provider backoffPolicyProvider;
  @Mock
  private BackoffPolicy backoffPolicy1;
  @Mock
  private BackoffPolicy backoffPolicy2;
  @Mock
  private LoadStatsStore loadStatsStore;
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
                StreamObserver<LoadStatsRequest> requestObserver =
                    mock(StreamObserver.class);
                Answer<Void> closeRpc = new Answer<Void>() {
                  @Override
                  public Void answer(InvocationOnMock invocation) {
                    responseObserver.onCompleted();
                    return null;
                  }
                };
                doAnswer(closeRpc).when(requestObserver).onCompleted();
                lrsRequestObservers.add(requestObserver);
                return requestObserver;
              }
            }
        ));
    cleanupRule.register(InProcessServerBuilder.forName("fakeLoadReportingServer").directExecutor()
        .addService(mockLoadReportingService).build().start());
    channel = cleanupRule.register(
        InProcessChannelBuilder.forName("fakeLoadReportingServer").directExecutor().build());
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    when(helper.getScheduledExecutorService()).thenReturn(fakeClock.getScheduledExecutorService());
    when(helper.getChannelLogger()).thenReturn(channelLogger);
    when(helper.getAuthority()).thenReturn(SERVICE_AUTHORITY);
    when(backoffPolicyProvider.get()).thenReturn(backoffPolicy1, backoffPolicy2);
    when(backoffPolicy1.nextBackoffNanos())
        .thenReturn(TimeUnit.SECONDS.toNanos(1L), TimeUnit.SECONDS.toNanos(10L));
    when(backoffPolicy2.nextBackoffNanos())
        .thenReturn(TimeUnit.SECONDS.toNanos(1L), TimeUnit.SECONDS.toNanos(10L));
    lrsClient =
        new LoadReportClientImpl(channel, helper, fakeClock.getStopwatchSupplier(),
            backoffPolicyProvider,
            loadStatsStore);
    lrsClient.startLoadReporting(callback);
  }

  @After
  public void tearDown() {
    lrsClient.stopLoadReporting();
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
    verify(requestObserver).onCompleted();
    assertThat(fakeClock.getPendingTasks(LRS_RPC_RETRY_TASK_FILTER)).isEmpty();
    lrsClient.stopLoadReporting();
    verifyNoMoreInteractions(requestObserver);

    lrsClient.startLoadReporting(callback);
    verify(mockLoadReportingService, times(2)).streamLoadStats(lrsResponseObserverCaptor.capture());
    assertThat(lrsRequestObservers).hasSize(2);
  }

  @Test
  public void loadReportActualIntervalAsSpecified() {
    verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    StreamObserver<LoadStatsResponse> responseObserver = lrsResponseObserverCaptor.getValue();
    assertThat(lrsRequestObservers).hasSize(1);
    StreamObserver<LoadStatsRequest> requestObserver = lrsRequestObservers.poll();
    when(loadStatsStore.generateLoadReport()).thenReturn(ClusterStats.newBuilder().build());
    InOrder inOrder = inOrder(requestObserver, loadStatsStore);
    inOrder.verify(requestObserver).onNext(EXPECTED_INITIAL_REQ);
    assertThat(logs).containsExactly("DEBUG: Initial LRS request sent: " + EXPECTED_INITIAL_REQ);
    logs.poll();

    responseObserver.onNext(buildLrsResponse(1453));
    assertThat(logs).containsExactly(
        "DEBUG: Received LRS initial response: " + buildLrsResponse(1453));
    assertNextReport(inOrder, requestObserver, buildEmptyClusterStats(1453));
    verify(callback).onReportResponse(1453);
  }

  @Test
  public void loadReportIntervalUpdate() {
    verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    StreamObserver<LoadStatsResponse> responseObserver = lrsResponseObserverCaptor.getValue();
    assertThat(lrsRequestObservers).hasSize(1);
    StreamObserver<LoadStatsRequest> requestObserver = lrsRequestObservers.poll();

    when(loadStatsStore.generateLoadReport()).thenReturn(ClusterStats.newBuilder().build());

    InOrder inOrder = inOrder(requestObserver, loadStatsStore);
    inOrder.verify(requestObserver).onNext(EXPECTED_INITIAL_REQ);
    assertThat(logs).containsExactly("DEBUG: Initial LRS request sent: " + EXPECTED_INITIAL_REQ);
    logs.poll();

    responseObserver.onNext(buildLrsResponse(1362));
    assertThat(logs).containsExactly(
        "DEBUG: Received LRS initial response: " + buildLrsResponse(1362));
    logs.poll();
    assertNextReport(inOrder, requestObserver, buildEmptyClusterStats(1362));
    verify(callback).onReportResponse(1362);

    responseObserver.onNext(buildLrsResponse(2183345));
    assertThat(logs).containsExactly(
        "DEBUG: Received an LRS response: " + buildLrsResponse(2183345));
    // Updated load reporting interval becomes effective immediately.
    assertNextReport(inOrder, requestObserver, buildEmptyClusterStats(2183345));
    verify(callback).onReportResponse(2183345);
  }

  @Test
  public void reportRecordedLoadData() {
    verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    StreamObserver<LoadStatsResponse> responseObserver = lrsResponseObserverCaptor.getValue();
    assertThat(lrsRequestObservers).hasSize(1);
    StreamObserver<LoadStatsRequest> requestObserver = lrsRequestObservers.poll();
    InOrder inOrder = inOrder(requestObserver, loadStatsStore);
    inOrder.verify(requestObserver).onNext(EXPECTED_INITIAL_REQ);

    long callsInProgress = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long callsSucceeded = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long callsFailed = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long callsIssued = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long numLbDrops = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    long numThrottleDrops = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);

    ClusterStats expectedStats1 = ClusterStats.newBuilder()
        .setClusterName(CLUSTER_NAME)
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
        .setClusterName(CLUSTER_NAME)
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
    when(loadStatsStore.generateLoadReport()).thenReturn(expectedStats1, expectedStats2);

    responseObserver.onNext(buildLrsResponse(1362));
    assertNextReport(inOrder, requestObserver, expectedStats1);

    assertNextReport(inOrder, requestObserver, expectedStats2);
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
    assertThat(logs).containsExactly("DEBUG: Initial LRS request sent: " + EXPECTED_INITIAL_REQ);
    logs.poll();
    assertEquals(0, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Balancer closes it immediately (erroneously)
    responseObserver.onCompleted();

    // Will start backoff sequence 1 (1s)
    inOrder.verify(backoffPolicyProvider).get();
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    assertThat(logs).containsExactly("DEBUG: LRS stream closed, backoff in 1 second(s)");
    logs.poll();
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
    assertThat(logs).containsExactly("DEBUG: Initial LRS request sent: " + EXPECTED_INITIAL_REQ);
    logs.poll();
    assertEquals(0, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Balancer closes it with an error.
    responseObserver.onError(Status.UNAVAILABLE.asException());
    // Will continue the backoff sequence 1 (10s)
    verifyNoMoreInteractions(backoffPolicyProvider);
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    assertThat(logs).containsExactly("DEBUG: LRS stream closed, backoff in 10 second(s)");
    logs.poll();
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
    assertThat(logs).containsExactly("DEBUG: Initial LRS request sent: " + EXPECTED_INITIAL_REQ);
    logs.poll();
    assertEquals(0, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Balancer sends initial response.
    responseObserver.onNext(buildLrsResponse(0));
    assertThat(logs).containsExactly(
        "DEBUG: Received LRS initial response: " + buildLrsResponse(0));
    logs.poll();

    // Then breaks the RPC
    responseObserver.onError(Status.UNAVAILABLE.asException());

    // Will reset the retry sequence and retry immediately, because balancer has responded.
    inOrder.verify(backoffPolicyProvider).get();
    inOrder.verify(mockLoadReportingService).streamLoadStats(lrsResponseObserverCaptor.capture());
    assertThat(logs).containsExactly("DEBUG: LRS stream closed, backoff in 0 second(s)",
        "DEBUG: Initial LRS request sent: " + EXPECTED_INITIAL_REQ);
    logs.clear();
    responseObserver = lrsResponseObserverCaptor.getValue();
    assertThat(lrsRequestObservers).hasSize(1);
    requestObserver = lrsRequestObservers.poll();
    verify(requestObserver).onNext(eq(EXPECTED_INITIAL_REQ));

    // Fail the retry after spending 4ns
    fakeClock.forwardNanos(4);
    responseObserver.onError(Status.UNAVAILABLE.asException());

    // Will be on the first retry (1s) of backoff sequence 2.
    inOrder.verify(backoffPolicy2).nextBackoffNanos();
    // The logged backoff time will be 0 seconds as it is in granularity of seconds.
    assertThat(logs).containsExactly("DEBUG: LRS stream closed, backoff in 0 second(s)");
    logs.poll();
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
    assertThat(logs).containsExactly("DEBUG: Initial LRS request sent: " + EXPECTED_INITIAL_REQ);
    assertEquals(0, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

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
    InOrder inOrder = inOrder(requestObserver);

    // First balancer RPC
    inOrder.verify(requestObserver).onNext(EXPECTED_INITIAL_REQ);
    assertEquals(0, fakeClock.numPendingTasks(LRS_RPC_RETRY_TASK_FILTER));

    // Simulate receiving LB response
    assertEquals(0, fakeClock.numPendingTasks(LOAD_REPORTING_TASK_FILTER));
    responseObserver.onNext(buildLrsResponse(1983));
    // Load reporting task is scheduled
    assertEquals(1, fakeClock.numPendingTasks(LOAD_REPORTING_TASK_FILTER));
    FakeClock.ScheduledTask scheduledTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(LOAD_REPORTING_TASK_FILTER));
    assertEquals(1983, scheduledTask.getDelay(TimeUnit.NANOSECONDS));

    // Close lbStream
    requestObserver.onCompleted();

    // Reporting task cancelled
    assertEquals(0, fakeClock.numPendingTasks(LOAD_REPORTING_TASK_FILTER));

    // Simulate a race condition where the task has just started when its cancelled
    scheduledTask.command.run();

    // No report sent. No new task scheduled
    inOrder.verify(requestObserver, never()).onNext(any(LoadStatsRequest.class));
    assertEquals(0, fakeClock.numPendingTasks(LOAD_REPORTING_TASK_FILTER));
  }

  private static ClusterStats buildEmptyClusterStats(long loadReportIntervalNanos) {
    return ClusterStats.newBuilder()
        .setClusterName(CLUSTER_NAME)
        .setLoadReportInterval(Durations.fromNanos(loadReportIntervalNanos)).build();
  }

  private static LoadStatsResponse buildLrsResponse(long loadReportIntervalNanos) {
    return LoadStatsResponse.newBuilder()
        .addClusters(CLUSTER_NAME)
        .setLoadReportingInterval(Durations.fromNanos(loadReportIntervalNanos)).build();
  }

  private void assertNextReport(InOrder inOrder, StreamObserver<LoadStatsRequest> requestObserver,
      ClusterStats expectedStats) {
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
    assertEquals(report.getNode(), Node.newBuilder()
        .setMetadata(Struct.newBuilder()
            .putFields(
                LoadReportClientImpl.TRAFFICDIRECTOR_GRPC_HOSTNAME_FIELD,
                Value.newBuilder().setStringValue(SERVICE_AUTHORITY).build()))
        .build());
    assertEquals(1, report.getClusterStatsCount());
    assertThat(report.getClusterStats(0)).isEqualTo(expectedStats);
  }
}
