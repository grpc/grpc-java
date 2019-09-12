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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.github.udpa.udpa.data.orca.v1.OrcaLoadReport;
import com.github.udpa.udpa.service.orca.v1.OpenRcaServiceGrpc;
import com.github.udpa.udpa.service.orca.v1.OrcaLoadReportRequest;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.util.Durations;
import io.grpc.Attributes;
import io.grpc.Channel;
import io.grpc.ChannelLogger;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.Context;
import io.grpc.Context.CancellationListener;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.ManagedChannel;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.OrcaOobUtil.OrcaOobReportListener;
import io.grpc.xds.OrcaOobUtil.OrcaReportingConfig;
import io.grpc.xds.OrcaOobUtil.OrcaReportingHelperWrapper;
import io.grpc.xds.OrcaOobUtil.SubchannelImpl;
import java.net.SocketAddress;
import java.text.MessageFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link OrcaOobUtil} class.
 */
@RunWith(JUnit4.class)
public class OrcaOobUtilTest {

  private static final int NUM_SUBCHANNELS = 2;
  private static final Attributes.Key<String> SUBCHANNEL_ATTR_KEY =
      Attributes.Key.create("subchannel-attr-for-test");
  private static final OrcaReportingConfig SHORT_INTERVAL_CONFIG =
      OrcaReportingConfig.newBuilder().setReportInterval(5L, TimeUnit.NANOSECONDS).build();
  private static final OrcaReportingConfig MEDIUM_INTERVAL_CONFIG =
      OrcaReportingConfig.newBuilder().setReportInterval(543L, TimeUnit.MICROSECONDS).build();
  private static final OrcaReportingConfig LONG_INTERVAL_CONFIG =
      OrcaReportingConfig.newBuilder().setReportInterval(1232L, TimeUnit.MILLISECONDS).build();
  @Rule public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  @SuppressWarnings({"rawtypes", "unchecked"})
  private final List<EquivalentAddressGroup>[] eagLists = new List[NUM_SUBCHANNELS];
  private final SubchannelStateListener[] mockStateListeners =
      new SubchannelStateListener[NUM_SUBCHANNELS];
  private final ManagedChannel[] channels = new ManagedChannel[NUM_SUBCHANNELS];
  private final OpenRcaServiceImp[] orcaServiceImps = new OpenRcaServiceImp[NUM_SUBCHANNELS];
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });

  private final FakeClock fakeClock = new FakeClock();
  private final Helper origHelper = mock(Helper.class, delegatesTo(new FakeHelper()));
  @Mock
  private OrcaOobReportListener mockOrcaListener0;
  @Mock
  private OrcaOobReportListener mockOrcaListener1;
  @Mock
  private OrcaOobReportListener mockOrcaListener2;
  @Mock private BackoffPolicy.Provider backoffPolicyProvider;
  @Mock private BackoffPolicy backoffPolicy1;
  @Mock private BackoffPolicy backoffPolicy2;
  private FakeSubchannel[] subchannels = new FakeSubchannel[NUM_SUBCHANNELS];
  private OrcaReportingHelperWrapper orcaHelperWrapper;
  private OrcaReportingHelperWrapper parentHelperWrapper;
  private OrcaReportingHelperWrapper childHelperWrapper;

  private static FakeSubchannel unwrap(Subchannel s) {
    return (FakeSubchannel) ((SubchannelImpl) s).delegate();
  }

  private static OrcaLoadReportRequest buildOrcaRequestFromConfig(
      OrcaReportingConfig config) {
    return OrcaLoadReportRequest.newBuilder()
        .setReportInterval(Durations.fromNanos(config.getReportIntervalNanos()))
        .build();
  }

  private static void assertLog(List<String> logs, String expectedLog) {
    assertThat(logs).containsExactly(expectedLog);
    logs.clear();
  }

  @After
  public void tearDown() {
    for (int i = 0; i < NUM_SUBCHANNELS; i++) {
      if (subchannels[i] != null) {
        subchannels[i].shutdown();
      }
    }
  }

  @Test
  public void orcaReportingConfig_construct() {
    int interval = new Random().nextInt(Integer.MAX_VALUE);
    OrcaReportingConfig config =
        OrcaReportingConfig.newBuilder()
            .setReportInterval(interval, TimeUnit.MICROSECONDS)
            .build();
    assertThat(config.getReportIntervalNanos()).isEqualTo(TimeUnit.MICROSECONDS.toNanos(interval));
    String str = config.toString();
    assertThat(str).contains("reportIntervalNanos=");
    OrcaReportingConfig rebuildedConfig = config.toBuilder().build();
    assertThat(rebuildedConfig.getReportIntervalNanos())
        .isEqualTo(TimeUnit.MICROSECONDS.toNanos(interval));
  }

  @Before
  @SuppressWarnings("unchecked")
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);

    for (int i = 0; i < NUM_SUBCHANNELS; i++) {
      orcaServiceImps[i] = new OpenRcaServiceImp();
      cleanupRule.register(
          InProcessServerBuilder.forName("orca-reporting-test-" + i)
              .addService(orcaServiceImps[i])
              .directExecutor()
              .build()
              .start());
      ManagedChannel channel =
          cleanupRule.register(
              InProcessChannelBuilder.forName("orca-reporting-test-" + i).directExecutor().build());
      channels[i] = channel;
      EquivalentAddressGroup eag =
          new EquivalentAddressGroup(new FakeSocketAddress("address-" + i));
      List<EquivalentAddressGroup> eagList = Arrays.asList(eag);
      eagLists[i] = eagList;
      mockStateListeners[i] = mock(SubchannelStateListener.class);
    }

    when(backoffPolicyProvider.get()).thenReturn(backoffPolicy1, backoffPolicy2);
    when(backoffPolicy1.nextBackoffNanos()).thenReturn(11L, 21L);
    when(backoffPolicy2.nextBackoffNanos()).thenReturn(12L, 22L);

    orcaHelperWrapper =
        OrcaOobUtil.newOrcaReportingHelperWrapper(
            origHelper,
            mockOrcaListener0,
            backoffPolicyProvider,
            fakeClock.getStopwatchSupplier());
    parentHelperWrapper =
        OrcaOobUtil.newOrcaReportingHelperWrapper(
            origHelper,
            mockOrcaListener1,
            backoffPolicyProvider,
            fakeClock.getStopwatchSupplier());
    childHelperWrapper =
        OrcaOobUtil.newOrcaReportingHelperWrapper(
            parentHelperWrapper.asHelper(),
            mockOrcaListener2,
            backoffPolicyProvider,
            fakeClock.getStopwatchSupplier());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void singlePolicyTypicalWorkflow() {
    setOrcaReportConfig(orcaHelperWrapper, SHORT_INTERVAL_CONFIG);
    verify(origHelper, atLeast(0)).getSynchronizationContext();
    verifyNoMoreInteractions(origHelper);

    // Calling createSubchannel() on orcaHelper correctly passes augmented CreateSubchannelArgs
    // to origHelper.
    ArgumentCaptor<CreateSubchannelArgs> createArgsCaptor = ArgumentCaptor.forClass(null);
    for (int i = 0; i < NUM_SUBCHANNELS; i++) {
      String subchannelAttrValue = "eag attr " + i;
      Attributes attrs =
          Attributes.newBuilder().set(SUBCHANNEL_ATTR_KEY, subchannelAttrValue).build();
      assertThat(unwrap(createSubchannel(orcaHelperWrapper.asHelper(), i, attrs)))
          .isSameInstanceAs(subchannels[i]);
      verify(origHelper, times(i + 1)).createSubchannel(createArgsCaptor.capture());
      assertThat(createArgsCaptor.getValue().getAddresses()).isEqualTo(eagLists[i]);
      assertThat(createArgsCaptor.getValue().getAttributes().get(SUBCHANNEL_ATTR_KEY))
          .isEqualTo(subchannelAttrValue);
    }

    // ORCA reporting does not start until underlying Subchannel is READY.
    for (int i = 0; i < NUM_SUBCHANNELS; i++) {
      FakeSubchannel subchannel = subchannels[i];
      OpenRcaServiceImp orcaServiceImp = orcaServiceImps[i];
      SubchannelStateListener mockStateListener = mockStateListeners[i];
      InOrder inOrder = inOrder(mockStateListener);
      deliverSubchannelState(i, ConnectivityStateInfo.forNonError(IDLE));
      deliverSubchannelState(i, ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE));
      deliverSubchannelState(i, ConnectivityStateInfo.forNonError(CONNECTING));

      inOrder.verify(mockStateListener)
          .onSubchannelState(eq(ConnectivityStateInfo.forNonError(IDLE)));
      inOrder.verify(mockStateListener)
          .onSubchannelState(eq(ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE)));
      inOrder.verify(mockStateListener)
          .onSubchannelState(eq(ConnectivityStateInfo.forNonError(CONNECTING)));
      verifyNoMoreInteractions(mockStateListener);

      assertThat(subchannel.logs).isEmpty();
      assertThat(orcaServiceImp.calls).isEmpty();
      verifyNoMoreInteractions(mockOrcaListener0);
      deliverSubchannelState(i, ConnectivityStateInfo.forNonError(READY));
      verify(mockStateListener).onSubchannelState(eq(ConnectivityStateInfo.forNonError(READY)));
      assertThat(orcaServiceImp.calls).hasSize(1);
      ServerSideCall serverCall = orcaServiceImp.calls.peek();
      assertThat(serverCall.request).isEqualTo(buildOrcaRequestFromConfig(SHORT_INTERVAL_CONFIG));
      assertLog(subchannel.logs,
          "DEBUG: Starting ORCA reporting for " + subchannel.getAllAddresses());

      // Simulate an ORCA service response. Registered listener will receive an ORCA report for
      // each backend.
      OrcaLoadReport report = OrcaLoadReport.getDefaultInstance();
      serverCall.responseObserver.onNext(report);
      assertLog(subchannel.logs, "DEBUG: Received an ORCA report: " + report);
      verify(mockOrcaListener0, times(i + 1)).onLoadReport(eq(report));
    }

    for (int i = 0; i < NUM_SUBCHANNELS; i++) {
      FakeSubchannel subchannel = subchannels[i];
      SubchannelStateListener mockStateListener = mockStateListeners[i];

      ServerSideCall serverCall = orcaServiceImps[i].calls.peek();
      assertThat(serverCall.cancelled).isFalse();
      verifyNoMoreInteractions(mockStateListener);

      // Shutting down the subchannel will cancel the ORCA reporting RPC.
      subchannel.shutdown();
      verify(mockStateListener).onSubchannelState(eq(ConnectivityStateInfo.forNonError(SHUTDOWN)));
      assertThat(serverCall.cancelled).isTrue();
      assertThat(subchannel.logs).isEmpty();
      verifyNoMoreInteractions(mockOrcaListener0);
    }

    for (int i = 0; i < NUM_SUBCHANNELS; i++) {
      assertThat(orcaServiceImps[i].calls).hasSize(1);
    }

    verifyZeroInteractions(backoffPolicyProvider);
  }

  @Test
  public void twoLevelPoliciesTypicalWorkflow() {
    setOrcaReportConfig(childHelperWrapper, SHORT_INTERVAL_CONFIG);
    setOrcaReportConfig(parentHelperWrapper, SHORT_INTERVAL_CONFIG);
    verify(origHelper, atLeast(0)).getSynchronizationContext();
    verifyNoMoreInteractions(origHelper);

    // Calling createSubchannel() on child helper correctly passes augmented CreateSubchannelArgs
    // to origHelper.
    ArgumentCaptor<CreateSubchannelArgs> createArgsCaptor = ArgumentCaptor.forClass(null);
    for (int i = 0; i < NUM_SUBCHANNELS; i++) {
      String subchannelAttrValue = "eag attr " + i;
      Attributes attrs =
          Attributes.newBuilder().set(SUBCHANNEL_ATTR_KEY, subchannelAttrValue).build();
      assertThat(unwrap(createSubchannel(childHelperWrapper.asHelper(), i, attrs)))
          .isSameInstanceAs(subchannels[i]);
      verify(origHelper, times(i + 1)).createSubchannel(createArgsCaptor.capture());
      assertThat(createArgsCaptor.getValue().getAddresses()).isEqualTo(eagLists[i]);
      assertThat(createArgsCaptor.getValue().getAttributes().get(SUBCHANNEL_ATTR_KEY))
          .isEqualTo(subchannelAttrValue);
    }

    // ORCA reporting does not start until underlying Subchannel is READY.
    for (int i = 0; i < NUM_SUBCHANNELS; i++) {
      FakeSubchannel subchannel = subchannels[i];
      OpenRcaServiceImp orcaServiceImp = orcaServiceImps[i];
      SubchannelStateListener mockStateListener = mockStateListeners[i];
      InOrder inOrder = inOrder(mockStateListener);
      deliverSubchannelState(i, ConnectivityStateInfo.forNonError(IDLE));
      deliverSubchannelState(i, ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE));
      deliverSubchannelState(i, ConnectivityStateInfo.forNonError(CONNECTING));

      inOrder
          .verify(mockStateListener).onSubchannelState(eq(ConnectivityStateInfo.forNonError(IDLE)));
      inOrder
          .verify(mockStateListener)
          .onSubchannelState(eq(ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE)));
      inOrder
          .verify(mockStateListener)
          .onSubchannelState(eq(ConnectivityStateInfo.forNonError(CONNECTING)));
      verifyNoMoreInteractions(mockStateListener);

      assertThat(subchannel.logs).isEmpty();
      assertThat(orcaServiceImp.calls).isEmpty();
      verifyNoMoreInteractions(mockOrcaListener1);
      verifyNoMoreInteractions(mockOrcaListener2);
      deliverSubchannelState(i, ConnectivityStateInfo.forNonError(READY));
      verify(mockStateListener).onSubchannelState(eq(ConnectivityStateInfo.forNonError(READY)));
      assertThat(orcaServiceImp.calls).hasSize(1);
      ServerSideCall serverCall = orcaServiceImp.calls.peek();
      assertThat(serverCall.request).isEqualTo(buildOrcaRequestFromConfig(SHORT_INTERVAL_CONFIG));
      assertLog(subchannel.logs,
          "DEBUG: Starting ORCA reporting for " + subchannel.getAllAddresses());

      // Simulate an ORCA service response. Registered listener will receive an ORCA report for
      // each backend.
      OrcaLoadReport report = OrcaLoadReport.getDefaultInstance();
      serverCall.responseObserver.onNext(report);
      assertLog(subchannel.logs, "DEBUG: Received an ORCA report: " + report);
      verify(mockOrcaListener1, times(i + 1)).onLoadReport(eq(report));
      verify(mockOrcaListener2, times(i + 1)).onLoadReport(eq(report));
    }

    for (int i = 0; i < NUM_SUBCHANNELS; i++) {
      FakeSubchannel subchannel = subchannels[i];
      SubchannelStateListener mockStateListener = mockStateListeners[i];

      ServerSideCall serverCall = orcaServiceImps[i].calls.peek();
      assertThat(serverCall.cancelled).isFalse();
      verifyNoMoreInteractions(mockStateListener);

      // Shutting down the subchannel will cancel the ORCA reporting RPC.
      subchannel.shutdown();
      verify(mockStateListener).onSubchannelState(eq(ConnectivityStateInfo.forNonError(SHUTDOWN)));
      assertThat(serverCall.cancelled).isTrue();
      assertThat(subchannel.logs).isEmpty();
      verifyNoMoreInteractions(mockOrcaListener1, mockOrcaListener2);
    }

    for (int i = 0; i < NUM_SUBCHANNELS; i++) {
      assertThat(orcaServiceImps[i].calls).hasSize(1);
    }

    verifyZeroInteractions(backoffPolicyProvider);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void orcReportingDisabledWhenServiceNotImplemented() {
    setOrcaReportConfig(orcaHelperWrapper, SHORT_INTERVAL_CONFIG);
    createSubchannel(orcaHelperWrapper.asHelper(), 0, Attributes.EMPTY);
    FakeSubchannel subchannel = subchannels[0];
    OpenRcaServiceImp orcaServiceImp = orcaServiceImps[0];
    SubchannelStateListener mockStateListener = mockStateListeners[0];
    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(READY));
    verify(mockStateListener).onSubchannelState(eq(ConnectivityStateInfo.forNonError(READY)));
    assertThat(orcaServiceImp.calls).hasSize(1);

    ServerSideCall serverCall = orcaServiceImp.calls.poll();
    assertThat(serverCall.request).isEqualTo(buildOrcaRequestFromConfig(SHORT_INTERVAL_CONFIG));
    subchannel.logs.clear();
    serverCall.responseObserver.onError(Status.UNIMPLEMENTED.asException());
    assertLog(subchannel.logs,
        "ERROR: OpenRcaService disabled: " + Status.UNIMPLEMENTED);
    verifyNoMoreInteractions(mockOrcaListener0);

    // Re-connecting on Subchannel will reset the "disabled" flag and restart ORCA reporting.
    assertThat(orcaServiceImp.calls).hasSize(0);
    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(IDLE));
    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(READY));
    assertLog(subchannel.logs,
        "DEBUG: Starting ORCA reporting for " + subchannel.getAllAddresses());
    assertThat(orcaServiceImp.calls).hasSize(1);
    serverCall = orcaServiceImp.calls.poll();
    OrcaLoadReport report = OrcaLoadReport.getDefaultInstance();
    serverCall.responseObserver.onNext(report);
    assertLog(subchannel.logs, "DEBUG: Received an ORCA report: " + report);
    verify(mockOrcaListener0).onLoadReport(eq(report));

    verifyZeroInteractions(backoffPolicyProvider);
  }

  @Test
  public void orcaReportingStreamClosedAndRetried() {
    setOrcaReportConfig(orcaHelperWrapper, SHORT_INTERVAL_CONFIG);
    createSubchannel(orcaHelperWrapper.asHelper(), 0, Attributes.EMPTY);
    FakeSubchannel subchannel = subchannels[0];
    OpenRcaServiceImp orcaServiceImp = orcaServiceImps[0];
    SubchannelStateListener mockStateListener = mockStateListeners[0];
    InOrder inOrder = inOrder(mockStateListener, mockOrcaListener0, backoffPolicyProvider,
        backoffPolicy1, backoffPolicy2);

    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(READY));
    inOrder
        .verify(mockStateListener).onSubchannelState(eq(ConnectivityStateInfo.forNonError(READY)));
    assertLog(subchannel.logs,
        "DEBUG: Starting ORCA reporting for " + subchannel.getAllAddresses());

    // Server closes the ORCA reporting RPC without any response, will start backoff
    // sequence 1 (11ns).
    orcaServiceImp.calls.poll().responseObserver.onCompleted();
    assertLog(subchannel.logs,
        "DEBUG: ORCA reporting stream closed with " + Status.OK + ", backoff in 11" + " ns");
    inOrder.verify(backoffPolicyProvider).get();
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    verifyRetryAfterNanos(inOrder, orcaServiceImp, 11);
    assertLog(subchannel.logs,
        "DEBUG: Starting ORCA reporting for " + subchannel.getAllAddresses());

    // Server closes the ORCA reporting RPC with an error, will continue backoff sequence 1 (21ns).
    orcaServiceImp.calls.poll().responseObserver.onError(Status.UNAVAILABLE.asException());
    assertLog(subchannel.logs,
        "DEBUG: ORCA reporting stream closed with " + Status.UNAVAILABLE + ", backoff in 21"
            + " ns");
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    verifyRetryAfterNanos(inOrder, orcaServiceImp, 21);
    assertLog(subchannel.logs,
        "DEBUG: Starting ORCA reporting for " + subchannel.getAllAddresses());

    // Server responds normally.
    OrcaLoadReport report = OrcaLoadReport.getDefaultInstance();
    orcaServiceImp.calls.peek().responseObserver.onNext(report);
    assertLog(subchannel.logs, "DEBUG: Received an ORCA report: " + report);
    inOrder.verify(mockOrcaListener0).onLoadReport(eq(report));

    // Server closes the ORCA reporting RPC after a response, will restart immediately.
    orcaServiceImp.calls.poll().responseObserver.onCompleted();
    assertThat(subchannel.logs).containsExactly(
        "DEBUG: ORCA reporting stream closed with " + Status.OK + ", backoff in 0" + " ns",
        "DEBUG: Starting ORCA reporting for " + subchannel.getAllAddresses());
    subchannel.logs.clear();

    // Backoff policy is set to sequence 2 in previous retry.
    // Server closes the ORCA reporting RPC with an error, will start backoff sequence 2 (12ns).
    orcaServiceImp.calls.poll().responseObserver.onError(Status.UNAVAILABLE.asException());
    assertLog(subchannel.logs,
        "DEBUG: ORCA reporting stream closed with " + Status.UNAVAILABLE + ", backoff in 12"
            + " ns");
    inOrder.verify(backoffPolicyProvider).get();
    inOrder.verify(backoffPolicy2).nextBackoffNanos();
    verifyRetryAfterNanos(inOrder, orcaServiceImp, 12);
    assertLog(subchannel.logs,
        "DEBUG: Starting ORCA reporting for " + subchannel.getAllAddresses());

    verifyNoMoreInteractions(mockStateListener, mockOrcaListener0, backoffPolicyProvider,
        backoffPolicy1, backoffPolicy2);
  }

  @Test
  public void reportingNotStartedUntilConfigured() {
    createSubchannel(orcaHelperWrapper.asHelper(), 0, Attributes.EMPTY);
    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(READY));
    verify(mockStateListeners[0])
        .onSubchannelState(eq(ConnectivityStateInfo.forNonError(READY)));

    assertThat(orcaServiceImps[0].calls).isEmpty();
    assertThat(subchannels[0].logs).isEmpty();
    setOrcaReportConfig(orcaHelperWrapper, SHORT_INTERVAL_CONFIG);
    assertThat(orcaServiceImps[0].calls).hasSize(1);
    assertLog(subchannels[0].logs,
        "DEBUG: Starting ORCA reporting for " + subchannels[0].getAllAddresses());
    assertThat(orcaServiceImps[0].calls.peek().request)
        .isEqualTo(buildOrcaRequestFromConfig(SHORT_INTERVAL_CONFIG));
  }

  @Test
  public void updateReportingIntervalBeforeCreatingSubchannel() {
    setOrcaReportConfig(orcaHelperWrapper, SHORT_INTERVAL_CONFIG);
    createSubchannel(orcaHelperWrapper.asHelper(), 0, Attributes.EMPTY);
    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(READY));
    verify(mockStateListeners[0]).onSubchannelState(eq(ConnectivityStateInfo.forNonError(READY)));

    assertThat(orcaServiceImps[0].calls).hasSize(1);
    assertLog(subchannels[0].logs,
        "DEBUG: Starting ORCA reporting for " + subchannels[0].getAllAddresses());
    assertThat(orcaServiceImps[0].calls.poll().request)
        .isEqualTo(buildOrcaRequestFromConfig(SHORT_INTERVAL_CONFIG));
  }

  @Test
  public void updateReportingIntervalBeforeSubchannelReady() {
    createSubchannel(orcaHelperWrapper.asHelper(), 0, Attributes.EMPTY);
    setOrcaReportConfig(orcaHelperWrapper, SHORT_INTERVAL_CONFIG);
    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(READY));
    verify(mockStateListeners[0]).onSubchannelState(eq(ConnectivityStateInfo.forNonError(READY)));

    assertThat(orcaServiceImps[0].calls).hasSize(1);
    assertLog(subchannels[0].logs,
        "DEBUG: Starting ORCA reporting for " + subchannels[0].getAllAddresses());
    assertThat(orcaServiceImps[0].calls.poll().request)
        .isEqualTo(buildOrcaRequestFromConfig(SHORT_INTERVAL_CONFIG));
  }

  @Test
  public void updateReportingIntervalWhenRpcActive() {
    // Sets report interval before creating a Subchannel, reporting starts right after suchannel
    // state becomes READY.
    setOrcaReportConfig(orcaHelperWrapper, MEDIUM_INTERVAL_CONFIG);
    createSubchannel(orcaHelperWrapper.asHelper(), 0, Attributes.EMPTY);
    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(READY));
    verify(mockStateListeners[0]).onSubchannelState(eq(ConnectivityStateInfo.forNonError(READY)));

    assertThat(orcaServiceImps[0].calls).hasSize(1);
    assertLog(subchannels[0].logs,
        "DEBUG: Starting ORCA reporting for " + subchannels[0].getAllAddresses());
    assertThat(orcaServiceImps[0].calls.peek().request)
        .isEqualTo(buildOrcaRequestFromConfig(MEDIUM_INTERVAL_CONFIG));

    // Make reporting less frequent.
    setOrcaReportConfig(orcaHelperWrapper, LONG_INTERVAL_CONFIG);
    assertThat(orcaServiceImps[0].calls.poll().cancelled).isTrue();
    assertThat(orcaServiceImps[0].calls).hasSize(1);
    assertLog(subchannels[0].logs,
        "DEBUG: Starting ORCA reporting for " + subchannels[0].getAllAddresses());
    assertThat(orcaServiceImps[0].calls.peek().request)
        .isEqualTo(buildOrcaRequestFromConfig(LONG_INTERVAL_CONFIG));

    // Configuring with the same report interval again does not restart ORCA RPC.
    setOrcaReportConfig(orcaHelperWrapper, LONG_INTERVAL_CONFIG);
    assertThat(orcaServiceImps[0].calls.peek().cancelled).isFalse();
    assertThat(subchannels[0].logs).isEmpty();

    // Make reporting more frequent.
    setOrcaReportConfig(orcaHelperWrapper, SHORT_INTERVAL_CONFIG);
    assertThat(orcaServiceImps[0].calls.poll().cancelled).isTrue();
    assertThat(orcaServiceImps[0].calls).hasSize(1);
    assertLog(subchannels[0].logs,
        "DEBUG: Starting ORCA reporting for " + subchannels[0].getAllAddresses());
    assertThat(orcaServiceImps[0].calls.poll().request)
        .isEqualTo(buildOrcaRequestFromConfig(SHORT_INTERVAL_CONFIG));
  }

  @Test
  public void updateReportingIntervalWhenRpcPendingRetry() {
    createSubchannel(orcaHelperWrapper.asHelper(), 0, Attributes.EMPTY);
    setOrcaReportConfig(orcaHelperWrapper, SHORT_INTERVAL_CONFIG);
    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(READY));
    verify(mockStateListeners[0]).onSubchannelState(eq(ConnectivityStateInfo.forNonError(READY)));

    assertThat(orcaServiceImps[0].calls).hasSize(1);
    assertLog(subchannels[0].logs,
        "DEBUG: Starting ORCA reporting for " + subchannels[0].getAllAddresses());
    assertThat(orcaServiceImps[0].calls.peek().request)
        .isEqualTo(buildOrcaRequestFromConfig(SHORT_INTERVAL_CONFIG));

    // Server closes the RPC without response, client will retry with backoff.
    assertThat(fakeClock.getPendingTasks()).isEmpty();
    orcaServiceImps[0].calls.poll().responseObserver.onCompleted();
    assertLog(subchannels[0].logs,
        "DEBUG: ORCA reporting stream closed with " + Status.OK + ", backoff in 11"
            + " ns");
    assertThat(fakeClock.getPendingTasks()).hasSize(1);
    assertThat(orcaServiceImps[0].calls).isEmpty();

    // Make reporting less frequent.
    setOrcaReportConfig(orcaHelperWrapper, LONG_INTERVAL_CONFIG);
    // Retry task will be canceled and restarts new RPC immediately.
    assertThat(fakeClock.getPendingTasks()).isEmpty();
    assertThat(orcaServiceImps[0].calls).hasSize(1);
    assertLog(subchannels[0].logs,
        "DEBUG: Starting ORCA reporting for " + subchannels[0].getAllAddresses());
    assertThat(orcaServiceImps[0].calls.peek().request)
        .isEqualTo(buildOrcaRequestFromConfig(LONG_INTERVAL_CONFIG));
  }

  @Test
  public void policiesReceiveSameReportIndependently() {
    createSubchannel(childHelperWrapper.asHelper(), 0, Attributes.EMPTY);
    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(READY));

    // No helper sets ORCA reporting interval, so load reporting is not started.
    verify(mockStateListeners[0]).onSubchannelState(eq(ConnectivityStateInfo.forNonError(READY)));
    assertThat(orcaServiceImps[0].calls).isEmpty();
    assertThat(subchannels[0].logs).isEmpty();

    // Parent helper requests ORCA reports with a certain interval, load reporting starts.
    setOrcaReportConfig(parentHelperWrapper, SHORT_INTERVAL_CONFIG);
    assertThat(orcaServiceImps[0].calls).hasSize(1);
    assertLog(subchannels[0].logs,
        "DEBUG: Starting ORCA reporting for " + subchannels[0].getAllAddresses());

    OrcaLoadReport report = OrcaLoadReport.getDefaultInstance();
    assertThat(orcaServiceImps[0].calls).hasSize(1);
    orcaServiceImps[0].calls.peek().responseObserver.onNext(report);
    assertLog(subchannels[0].logs, "DEBUG: Received an ORCA report: " + report);
    // Only parent helper's listener receives the report.
    ArgumentCaptor<OrcaLoadReport> parentReportCaptor = ArgumentCaptor.forClass(null);
    verify(mockOrcaListener1).onLoadReport(parentReportCaptor.capture());
    assertThat(parentReportCaptor.getValue()).isEqualTo(report);
    verifyNoMoreInteractions(mockOrcaListener2);

    // Now child helper also wants to receive reports.
    setOrcaReportConfig(childHelperWrapper, SHORT_INTERVAL_CONFIG);
    orcaServiceImps[0].calls.peek().responseObserver.onNext(report);
    assertLog(subchannels[0].logs, "DEBUG: Received an ORCA report: " + report);
    // Both helper receives the same report instance.
    ArgumentCaptor<OrcaLoadReport> childReportCaptor = ArgumentCaptor.forClass(null);
    verify(mockOrcaListener1, times(2))
        .onLoadReport(parentReportCaptor.capture());
    verify(mockOrcaListener2)
        .onLoadReport(childReportCaptor.capture());
    assertThat(childReportCaptor.getValue()).isSameInstanceAs(parentReportCaptor.getValue());
  }

  @Test
  public void reportWithMostFrequentIntervalRequested() {
    setOrcaReportConfig(parentHelperWrapper, SHORT_INTERVAL_CONFIG);
    setOrcaReportConfig(childHelperWrapper, LONG_INTERVAL_CONFIG);
    createSubchannel(childHelperWrapper.asHelper(), 0, Attributes.EMPTY);
    deliverSubchannelState(0, ConnectivityStateInfo.forNonError(READY));
    verify(mockStateListeners[0]).onSubchannelState(eq(ConnectivityStateInfo.forNonError(READY)));
    assertThat(orcaServiceImps[0].calls).hasSize(1);
    assertLog(subchannels[0].logs,
        "DEBUG: Starting ORCA reporting for " + subchannels[0].getAllAddresses());

    // The real report interval to be requested is the minimum of intervals requested by helpers.
    assertThat(Durations.toNanos(orcaServiceImps[0].calls.peek().request.getReportInterval()))
        .isEqualTo(SHORT_INTERVAL_CONFIG.getReportIntervalNanos());

    // Child helper wants reporting to be more frequent than its current setting while it is still
    // less frequent than parent helper. Nothing should happen on existing RPC.
    setOrcaReportConfig(childHelperWrapper, MEDIUM_INTERVAL_CONFIG);
    assertThat(orcaServiceImps[0].calls.peek().cancelled).isFalse();
    assertThat(subchannels[0].logs).isEmpty();

    // Parent helper wants reporting to be less frequent.
    setOrcaReportConfig(parentHelperWrapper, MEDIUM_INTERVAL_CONFIG);
    assertThat(orcaServiceImps[0].calls.poll().cancelled).isTrue();
    assertThat(orcaServiceImps[0].calls).hasSize(1);
    assertLog(subchannels[0].logs,
        "DEBUG: Starting ORCA reporting for " + subchannels[0].getAllAddresses());
    // ORCA reporting RPC restarts and the the real report interval is adjusted.
    assertThat(Durations.toNanos(orcaServiceImps[0].calls.poll().request.getReportInterval()))
        .isEqualTo(MEDIUM_INTERVAL_CONFIG.getReportIntervalNanos());
  }

  private void verifyRetryAfterNanos(InOrder inOrder, OpenRcaServiceImp orcaServiceImp,
      long nanos) {
    assertThat(fakeClock.getPendingTasks()).hasSize(1);
    assertThat(orcaServiceImp.calls).isEmpty();
    fakeClock.forwardNanos(nanos - 1);
    assertThat(orcaServiceImp.calls).isEmpty();
    inOrder.verifyNoMoreInteractions();
    fakeClock.forwardNanos(1);
    assertThat(orcaServiceImp.calls).hasSize(1);
    assertThat(fakeClock.getPendingTasks()).isEmpty();
  }

  private void deliverSubchannelState(final int index, final ConnectivityStateInfo newState) {
    syncContext.execute(
        new Runnable() {
          @Override
          public void run() {
            subchannels[index].stateListener.onSubchannelState(newState);
          }
        });
  }

  private Subchannel createSubchannel(final Helper helper, final int index,
      final Attributes attrs) {
    final AtomicReference<Subchannel> newSubchannel = new AtomicReference<>();
    syncContext.execute(
        new Runnable() {
          @Override
          public void run() {
            Subchannel s =
                helper.createSubchannel(
                    CreateSubchannelArgs.newBuilder()
                        .setAddresses(eagLists[index])
                        .setAttributes(attrs)
                        .build());
            s.start(mockStateListeners[index]);
            newSubchannel.set(s);
          }
        });
    return newSubchannel.get();
  }

  private void setOrcaReportConfig(
      final OrcaReportingHelperWrapper helperWrapper, final OrcaReportingConfig config) {
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        helperWrapper.setReportingConfig(config);
      }
    });
  }

  private static final class OpenRcaServiceImp extends OpenRcaServiceGrpc.OpenRcaServiceImplBase {
    final Queue<ServerSideCall> calls = new ArrayDeque<>();

    @Override
    public void streamCoreMetrics(
        OrcaLoadReportRequest request, StreamObserver<OrcaLoadReport> responseObserver) {
      final ServerSideCall call = new ServerSideCall(request, responseObserver);
      Context.current()
          .addListener(
              new CancellationListener() {
                @Override
                public void cancelled(Context ctx) {
                  call.cancelled = true;
                }
              },
              MoreExecutors.directExecutor());
      calls.add(call);
    }
  }

  private static final class ServerSideCall {
    final OrcaLoadReportRequest request;
    final StreamObserver<OrcaLoadReport> responseObserver;
    boolean cancelled;

    ServerSideCall(OrcaLoadReportRequest request, StreamObserver<OrcaLoadReport> responseObserver) {
      this.request = request;
      this.responseObserver = responseObserver;
    }
  }

  private static final class FakeSocketAddress extends SocketAddress {
    final String name;

    FakeSocketAddress(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  private final class FakeSubchannel extends Subchannel {
    final List<EquivalentAddressGroup> eagList;
    final Attributes attrs;
    final Channel channel;
    final List<String> logs = new ArrayList<>();
    final int index;
    SubchannelStateListener stateListener;
    private final ChannelLogger logger =
        new ChannelLogger() {
          @Override
          public void log(ChannelLogLevel level, String msg) {
            logs.add(level + ": " + msg);
          }

          @Override
          public void log(ChannelLogLevel level, String template, Object... args) {
            log(level, MessageFormat.format(template, args));
          }
        };

    FakeSubchannel(int index, CreateSubchannelArgs args, Channel channel) {
      this.index = index;
      this.eagList = args.getAddresses();
      this.attrs = args.getAttributes();
      this.channel = checkNotNull(channel);
    }

    @Override
    public void start(SubchannelStateListener listener) {
      checkState(this.stateListener == null);
      this.stateListener = listener;
    }

    @Override
    public void shutdown() {
      deliverSubchannelState(index, ConnectivityStateInfo.forNonError(SHUTDOWN));
    }

    @Override
    public void requestConnection() {
      throw new AssertionError("Should not be called");
    }

    @Override
    public List<EquivalentAddressGroup> getAllAddresses() {
      return eagList;
    }

    @Override
    public Attributes getAttributes() {
      return attrs;
    }

    @Override
    public Channel asChannel() {
      return channel;
    }

    @Override
    public ChannelLogger getChannelLogger() {
      return logger;
    }
  }

  private final class FakeHelper extends Helper {
    @Override
    public Subchannel createSubchannel(CreateSubchannelArgs args) {
      int index = -1;
      for (int i = 0; i < NUM_SUBCHANNELS; i++) {
        if (eagLists[i].equals(args.getAddresses())) {
          index = i;
          break;
        }
      }
      checkState(index >= 0, "addrs " + args.getAddresses() + " not found");
      FakeSubchannel subchannel = new FakeSubchannel(index, args, channels[index]);
      checkState(subchannels[index] == null, "subchannels[" + index + "] already created");
      subchannels[index] = subchannel;
      return subchannel;
    }

    @Override
    public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
      throw new AssertionError("Should not be called");
    }

    @Override
    public SynchronizationContext getSynchronizationContext() {
      return syncContext;
    }

    @Override
    public ScheduledExecutorService getScheduledExecutorService() {
      return fakeClock.getScheduledExecutorService();
    }

    @Deprecated
    @Override
    public NameResolver.Factory getNameResolverFactory() {
      throw new AssertionError("Should not be called");
    }

    @Override
    public String getAuthority() {
      throw new AssertionError("Should not be called");
    }

    @Override
    public ManagedChannel createOobChannel(EquivalentAddressGroup eag, String authority) {
      throw new AssertionError("Should not be called");
    }
  }
}
