/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.rls;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Converter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ChannelCredentials;
import io.grpc.ChannelLogger;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ForwardingChannelBuilder2;
import io.grpc.InternalManagedChannelBuilder;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickDetailsConsumer;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.MetricInstrument;
import io.grpc.MetricRecorder;
import io.grpc.MetricRecorder.Registration;
import io.grpc.MetricSink;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.NoopMetricSink;
import io.grpc.ServerCall;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.FakeClock;
import io.grpc.internal.JsonParser;
import io.grpc.internal.PickFirstLoadBalancerProvider;
import io.grpc.internal.PickSubchannelArgsImpl;
import io.grpc.internal.testing.StreamRecorder;
import io.grpc.lookup.v1.RouteLookupServiceGrpc;
import io.grpc.rls.RlsLoadBalancer.CachingRlsLbClientBuilderProvider;
import io.grpc.rls.RlsProtoConverters.RouteLookupResponseConverter;
import io.grpc.rls.RlsProtoData.RouteLookupRequest;
import io.grpc.rls.RlsProtoData.RouteLookupResponse;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.TestMethodDescriptors;
import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class RlsLoadBalancerTest {

  @Rule
  public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  private final RlsLoadBalancerProvider provider = new RlsLoadBalancerProvider();
  private final FakeClock fakeClock = new FakeClock();
  private final SynchronizationContext syncContext =
      new SynchronizationContext(new UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new RuntimeException(e);
        }
      });
  @Mock
  private MetricRecorder mockMetricRecorder;
  @Mock
  private Registration mockGaugeRegistration;
  private final FakeHelper helperDelegate = new FakeHelper();
  private final Helper helper =
      mock(Helper.class, AdditionalAnswers.delegatesTo(helperDelegate));
  private final FakeRlsServerImpl fakeRlsServerImpl = new FakeRlsServerImpl(
      fakeClock.getScheduledExecutorService());
  private final Deque<FakeSubchannel> subchannels = new LinkedList<>();
  private final FakeThrottler fakeThrottler = new FakeThrottler();
  private final String channelTarget = "channelTarget";
  @Captor
  private ArgumentCaptor<SubchannelPicker> pickerCaptor;
  private MethodDescriptor<Void, Void> fakeSearchMethod;
  private MethodDescriptor<Void, Void> fakeRescueMethod;
  private RlsLoadBalancer rlsLb;
  private String defaultTarget = "defaultTarget";
  private PickSubchannelArgs searchSubchannelArgs;
  private PickSubchannelArgs rescueSubchannelArgs;

  @Before
  public void setUp() {
    fakeSearchMethod =
        MethodDescriptor.<Void, Void>newBuilder()
            .setFullMethodName("com.google/Search")
            .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
            .setResponseMarshaller(TestMethodDescriptors.voidMarshaller())
            .setType(MethodType.CLIENT_STREAMING)
            .build();
    fakeRescueMethod =
        MethodDescriptor.<Void, Void>newBuilder()
            .setFullMethodName("com.google/Rescue")
            .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
            .setResponseMarshaller(TestMethodDescriptors.voidMarshaller())
            .setType(MethodType.UNARY)
            .build();
    fakeRlsServerImpl.setLookupTable(
        ImmutableMap.of(
            RouteLookupRequest.create(ImmutableMap.of(
                "server", "fake-bigtable.googleapis.com",
                "service-key", "com.google",
                "method-key", "Search")),
            RouteLookupResponse.create(ImmutableList.of("wilderness"), "where are you?"),
            RouteLookupRequest.create(ImmutableMap.of(
                "server", "fake-bigtable.googleapis.com",
                "service-key", "com.google",
                "method-key", "Rescue")),
            RouteLookupResponse.create(ImmutableList.of("civilization"), "you are safe")));

    rlsLb = (RlsLoadBalancer) provider.newLoadBalancer(helper);
    rlsLb.cachingRlsLbClientBuilderProvider = new CachingRlsLbClientBuilderProvider() {
      @Override
      public CachingRlsLbClient.Builder get() {
        // using fake throttler to allow enablement of throttler
        return CachingRlsLbClient.newBuilder()
            .setThrottler(fakeThrottler)
            .setTicker(fakeClock.getTicker());
      }
    };

    searchSubchannelArgs = newPickSubchannelArgs(fakeSearchMethod);
    rescueSubchannelArgs = newPickSubchannelArgs(fakeRescueMethod);

    when(mockMetricRecorder.registerBatchCallback(any(), any())).thenReturn(mockGaugeRegistration);
  }

  @After
  public void tearDown() {
    rlsLb.shutdown();
  }

  @Test
  public void lb_serverStatusCodeConversion() throws Exception {
    deliverResolvedAddresses();
    InOrder inOrder = inOrder(helper);
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.CONNECTING), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    PickSubchannelArgs fakeSearchMethodArgs = newPickSubchannelArgs(fakeSearchMethod);
    // Warm-up pick; will be queued
    PickResult res = picker.pickSubchannel(fakeSearchMethodArgs);
    assertThat(res.getStatus().isOk()).isTrue();
    assertThat(res.getSubchannel()).isNull();
    // Cache is warm, but still unconnected
    picker.pickSubchannel(fakeSearchMethodArgs); // Will create the subchannel
    FakeSubchannel subchannel = subchannels.peek();
    assertThat(subchannel).isNotNull();

    // Ensure happy path is unaffected
    subchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    res = picker.pickSubchannel(fakeSearchMethodArgs);
    assertThat(res.getStatus().getCode()).isEqualTo(Status.Code.OK);
    int expectedTimes = PickFirstLoadBalancerProvider.isEnabledNewPickFirst() ? 1 : 2;
    verifyLongCounterAdd("grpc.lb.rls.target_picks", expectedTimes, 1, "wilderness", "complete");

    // Check on conversion
    Throwable cause = new Throwable("cause");
    Status aborted = Status.ABORTED.withCause(cause).withDescription("base desc");
    Status serverStatus = CachingRlsLbClient.convertRlsServerStatus(aborted, "conv.test");
    assertThat(serverStatus.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(serverStatus.getCause()).isEqualTo(cause);
    assertThat(serverStatus.getDescription()).contains("RLS server returned: ");
    assertThat(serverStatus.getDescription()).endsWith("ABORTED: base desc");
    assertThat(serverStatus.getDescription()).contains("RLS server conv.test");
  }

  @Test
  public void lb_working_withDefaultTarget_rlsResponding() throws Exception {
    deliverResolvedAddresses();
    InOrder inOrder = inOrder(helper);
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.CONNECTING), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    // Warm-up pick; will be queued
    PickResult res = picker.pickSubchannel(searchSubchannelArgs);
    assertThat(res.getStatus().isOk()).isTrue();
    assertThat(res.getSubchannel()).isNull();
    // Cache is warm, but still unconnected
    res = picker.pickSubchannel(searchSubchannelArgs);
    inOrder.verify(helper).createSubchannel(any(CreateSubchannelArgs.class));
    inOrder.verify(helper, atLeast(0))
        .updateBalancingState(eq(ConnectivityState.CONNECTING), any(SubchannelPicker.class));
    inOrder.verify(helper, atLeast(0)).getSynchronizationContext();
    inOrder.verify(helper, atLeast(0)).getScheduledExecutorService();
    inOrder.verify(helper, atLeast(0)).getMetricRecorder();
    inOrder.verify(helper, atLeast(0)).getChannelTarget();
    inOrder.verifyNoMoreInteractions();

    assertThat(res.getStatus().isOk()).isTrue();
    assertThat(subchannels).hasSize(1);
    FakeSubchannel searchSubchannel = subchannels.getLast();
    assertThat(subchannelIsReady(searchSubchannel)).isFalse();

    searchSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());
    inOrder.verifyNoMoreInteractions();
    res = picker.pickSubchannel(searchSubchannelArgs);
    assertThat(subchannelIsReady(res.getSubchannel())).isTrue();
    assertThat(res.getSubchannel()).isSameInstanceAs(searchSubchannel);
    int expectedTimes = PickFirstLoadBalancerProvider.isEnabledNewPickFirst() ? 1 : 2;
    verifyLongCounterAdd("grpc.lb.rls.target_picks", expectedTimes, 1, "wilderness", "complete");

    // rescue should be pending status although the overall channel state is READY
    res = picker.pickSubchannel(rescueSubchannelArgs);
    inOrder.verify(helper).createSubchannel(any(CreateSubchannelArgs.class));
    // other rls picker itself is ready due to first channel.
    assertThat(res.getStatus().isOk()).isTrue();
    assertThat(subchannelIsReady(res.getSubchannel())).isFalse();
    assertThat(subchannels).hasSize(2);
    FakeSubchannel rescueSubchannel = subchannels.getLast();

    // search subchannel is down, rescue subchannel is connecting
    searchSubchannel.updateState(ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE));
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.CONNECTING), pickerCaptor.capture());

    rescueSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());

    // search again, verify that it doesn't use fallback, since RLS server responded, even though
    // subchannel is in failure mode
    res = picker.pickSubchannel(searchSubchannelArgs);
    assertThat(res.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(subchannelIsReady(res.getSubchannel())).isFalse();
    verifyLongCounterAdd("grpc.lb.rls.target_picks", 1, 1, "wilderness", "fail");
  }

  @Test
  public void fallbackWithDelay_succeeds() throws Exception {
    fakeRlsServerImpl.setResponseDelay(100, TimeUnit.MILLISECONDS);
    grpcCleanupRule.register(
        InProcessServerBuilder.forName("fake-bigtable.googleapis.com")
        .addService(ServerServiceDefinition.builder("com.google")
          .addMethod(fakeSearchMethod, (call, headers) -> {
            call.sendHeaders(new Metadata());
            call.sendMessage(null);
            call.close(Status.OK, new Metadata());
            return new ServerCall.Listener<Void>() {};
          })
          .build())
        .addService(fakeRlsServerImpl)
        .directExecutor()
        .build()
        .start());
    ManagedChannel channel = grpcCleanupRule.register(
        InProcessChannelBuilder.forName("fake-bigtable.googleapis.com")
        .defaultServiceConfig(parseJson(getServiceConfigJsonStr()))
        .directExecutor()
        .build());

    StreamRecorder<Void> recorder = StreamRecorder.create();
    StreamObserver<Void> requestObserver = ClientCalls.asyncClientStreamingCall(
        channel.newCall(fakeSearchMethod, CallOptions.DEFAULT), recorder);
    requestObserver.onCompleted();
    fakeClock.forwardTime(100, TimeUnit.MILLISECONDS);
    assertThat(recorder.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    assertThat(recorder.getError()).isNull();
  }

  @Test
  public void metricsWithRealChannel() throws Exception {
    grpcCleanupRule.register(
        InProcessServerBuilder.forName("fake-bigtable.googleapis.com")
        .addService(ServerServiceDefinition.builder("com.google")
          .addMethod(fakeSearchMethod, (call, headers) -> {
            call.sendHeaders(new Metadata());
            call.sendMessage(null);
            call.close(Status.OK, new Metadata());
            return new ServerCall.Listener<Void>() {};
          })
          .build())
        .addService(fakeRlsServerImpl)
        .directExecutor()
        .build()
        .start());
    MetricSink metrics = mock(MetricSink.class, delegatesTo(new NoopMetricSink()));
    ManagedChannel channel = grpcCleanupRule.register(
        InternalManagedChannelBuilder.addMetricSink(
            InProcessChannelBuilder.forName("fake-bigtable.googleapis.com")
            .defaultServiceConfig(parseJson(getServiceConfigJsonStr()))
            .directExecutor(),
        metrics)
        .build());

    StreamRecorder<Void> recorder = StreamRecorder.create();
    StreamObserver<Void> requestObserver = ClientCalls.asyncClientStreamingCall(
        channel.newCall(fakeSearchMethod, CallOptions.DEFAULT), recorder);
    requestObserver.onCompleted();
    assertThat(recorder.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    assertThat(recorder.getError()).isNull();

    verify(metrics).addLongCounter(
        eqMetricInstrumentName("grpc.lb.rls.default_target_picks"),
        eq(1L),
        eq(Arrays.asList("directaddress:///fake-bigtable.googleapis.com", "localhost:8972",
            "defaultTarget", "complete")),
        eq(Arrays.asList()));
  }

  @Test
  public void lb_working_withoutDefaultTarget_noRlsResponse() throws Exception {
    defaultTarget = "";
    fakeThrottler.nextResult = true;

    deliverResolvedAddresses();
    InOrder inOrder = inOrder(helper);
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.CONNECTING), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();

    // With no RLS response and no fallback, we should see a failure
    PickResult res = picker.pickSubchannel(searchSubchannelArgs); // create subchannel
    assertThat(res.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    inOrder.verify(helper).getMetricRecorder();
    inOrder.verify(helper).getChannelTarget();
    inOrder.verifyNoMoreInteractions();
    verifyFailedPicksCounterAdd(1, 1);
  }

  @Test
  public void lb_working_withDefaultTarget_noRlsResponse() throws Exception {
    fakeThrottler.nextResult = true;

    deliverResolvedAddresses();
    InOrder inOrder = inOrder(helper);
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.CONNECTING), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();

    // Search that when the RLS server doesn't respond, that fallback is used
    PickResult res = picker.pickSubchannel(searchSubchannelArgs); // create subchannel
    assertThat(res.getStatus().getCode()).isEqualTo(Status.Code.OK);
    FakeSubchannel fallbackSubchannel =
        (FakeSubchannel) markReadyAndGetPickResult(inOrder, searchSubchannelArgs).getSubchannel();
    assertThat(fallbackSubchannel).isNotNull();
    assertThat(subchannelIsReady(fallbackSubchannel)).isTrue();
    inOrder.verify(helper).getMetricRecorder();
    inOrder.verify(helper).getChannelTarget();
    inOrder.verifyNoMoreInteractions();
    int times = PickFirstLoadBalancerProvider.isEnabledNewPickFirst() ? 1 : 2;
    verifyLongCounterAdd("grpc.lb.rls.default_target_picks", times, 1,
        "defaultTarget", "complete");

    Subchannel subchannel = picker.pickSubchannel(searchSubchannelArgs).getSubchannel();
    assertThat(subchannelIsReady(subchannel)).isTrue();
    assertThat(subchannel).isSameInstanceAs(fallbackSubchannel);
    verifyLongCounterAdd("grpc.lb.rls.default_target_picks", ++times, 1, "defaultTarget",
        "complete");

    subchannel = picker.pickSubchannel(searchSubchannelArgs).getSubchannel();
    assertThat(subchannelIsReady(subchannel)).isTrue();
    assertThat(subchannel).isSameInstanceAs(fallbackSubchannel);
    verifyLongCounterAdd("grpc.lb.rls.default_target_picks", ++times, 1, "defaultTarget",
        "complete");

    // Make sure that when RLS starts communicating that default stops being used
    fakeThrottler.nextResult = false;
    fakeClock.forwardTime(2, TimeUnit.SECONDS); // Expires backoff cache entries

    picker.pickSubchannel(searchSubchannelArgs);// Create search subchannel
    FakeSubchannel searchSubchannel =
        (FakeSubchannel) markReadyAndGetPickResult(inOrder, searchSubchannelArgs).getSubchannel();
    assertThat(searchSubchannel).isNotNull();
    assertThat(searchSubchannel).isNotSameInstanceAs(fallbackSubchannel);
    times = PickFirstLoadBalancerProvider.isEnabledNewPickFirst() ? 1 : 2;
    verifyLongCounterAdd("grpc.lb.rls.target_picks", times, 1, "wilderness", "complete");

    // create rescue subchannel
    picker.pickSubchannel(rescueSubchannelArgs);
    FakeSubchannel rescueSubchannel =
        (FakeSubchannel) markReadyAndGetPickResult(inOrder, rescueSubchannelArgs).getSubchannel();
    assertThat(rescueSubchannel).isNotNull();
    assertThat(rescueSubchannel).isNotSameInstanceAs(fallbackSubchannel);
    assertThat(rescueSubchannel).isNotSameInstanceAs(searchSubchannel);
    verifyLongCounterAdd("grpc.lb.rls.target_picks", 1, 1, "civilization", "complete");

    // all channels are failed
    rescueSubchannel.updateState(ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE));
    searchSubchannel.updateState(ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE));
    fallbackSubchannel.updateState(ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE));

    res = picker.pickSubchannel(
        searchSubchannelArgs);
    assertThat(res.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(res.getSubchannel()).isNull();
    verifyLongCounterAdd("grpc.lb.rls.target_picks", 1, 1, "wilderness", "fail");
  }

  @Test
  public void lb_working_withoutDefaultTarget() throws Exception {
    defaultTarget = "";
    deliverResolvedAddresses();
    InOrder inOrder = inOrder(helper);
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.CONNECTING), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    // Warm-up pick; will be queued
    PickResult res = picker.pickSubchannel(searchSubchannelArgs);
    assertThat(res.getStatus().isOk()).isTrue();
    assertThat(res.getSubchannel()).isNull();
    // Cache is warm, but still unconnected
    res = picker.pickSubchannel(searchSubchannelArgs);
    inOrder.verify(helper).createSubchannel(any(CreateSubchannelArgs.class));
    inOrder.verify(helper, atLeast(0))
        .updateBalancingState(eq(ConnectivityState.CONNECTING), any(SubchannelPicker.class));
    inOrder.verify(helper, atLeast(0)).getSynchronizationContext();
    inOrder.verify(helper, atLeast(0)).getScheduledExecutorService();
    inOrder.verify(helper, atLeast(0)).getMetricRecorder();
    inOrder.verify(helper, atLeast(0)).getChannelTarget();
    inOrder.verifyNoMoreInteractions();
    assertThat(res.getStatus().isOk()).isTrue();

    assertThat(subchannels).hasSize(1);
    FakeSubchannel searchSubchannel =
        (FakeSubchannel) markReadyAndGetPickResult(inOrder, searchSubchannelArgs).getSubchannel();
    inOrder.verify(helper).getMetricRecorder();
    inOrder.verify(helper).getChannelTarget();
    inOrder.verifyNoMoreInteractions();
    assertThat(subchannelIsReady(searchSubchannel)).isTrue();
    assertThat(subchannels.getLast()).isSameInstanceAs(searchSubchannel);

    // rescue should be pending status although the overall channel state is READY
    picker = pickerCaptor.getValue();
    res = picker.pickSubchannel(rescueSubchannelArgs);
    inOrder.verify(helper).createSubchannel(any(CreateSubchannelArgs.class));
    // other rls picker itself is ready due to first channel.
    assertThat(res.getStatus().isOk()).isTrue();
    assertThat(subchannels).hasSize(2);
    FakeSubchannel rescueSubchannel = subchannels.getLast();
    assertThat(subchannelIsReady(rescueSubchannel)).isFalse();

    // search subchannel is down, rescue subchannel is still connecting
    searchSubchannel.updateState(ConnectivityStateInfo.forTransientFailure(Status.NOT_FOUND));
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.CONNECTING), pickerCaptor.capture());

    rescueSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());

    // search method will fail because there is no fallback target.
    picker = pickerCaptor.getValue();
    res = picker.pickSubchannel(newPickSubchannelArgs(fakeSearchMethod));
    assertThat(res.getStatus().isOk()).isFalse();
    assertThat(subchannelIsReady(res.getSubchannel())).isFalse();
    int expectedTimes = PickFirstLoadBalancerProvider.isEnabledNewPickFirst() ? 1 : 2;
    verifyLongCounterAdd("grpc.lb.rls.target_picks", expectedTimes, 1, "wilderness", "complete");

    res = picker.pickSubchannel(newPickSubchannelArgs(fakeRescueMethod));
    assertThat(subchannelIsReady(res.getSubchannel())).isTrue();
    assertThat(res.getSubchannel().getAddresses()).isEqualTo(rescueSubchannel.getAddresses());
    assertThat(res.getSubchannel().getAttributes()).isEqualTo(rescueSubchannel.getAttributes());
    verifyLongCounterAdd("grpc.lb.rls.target_picks", 1, 1, "civilization", "complete");

    // all channels are failed
    rescueSubchannel.updateState(ConnectivityStateInfo.forTransientFailure(Status.NOT_FOUND));
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    inOrder.verify(helper, atLeast(0)).refreshNameResolution();
    inOrder.verifyNoMoreInteractions();
    verifyLongCounterAdd("grpc.lb.rls.target_picks", 1, 1, "wilderness", "fail");
  }

  @Test
  public void lb_nameResolutionFailed() throws Exception {
    deliverResolvedAddresses();
    InOrder inOrder = inOrder(helper);
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.CONNECTING), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    PickResult res = picker.pickSubchannel(newPickSubchannelArgs(fakeSearchMethod));
    assertThat(res.getStatus().isOk()).isTrue();
    assertThat(subchannelIsReady(res.getSubchannel())).isFalse();

    inOrder.verify(helper).createSubchannel(any(CreateSubchannelArgs.class));
    assertThat(subchannels).hasSize(1);

    FakeSubchannel searchSubchannel = subchannels.getLast();
    searchSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());

    SubchannelPicker picker2 = pickerCaptor.getValue();
    assertThat(picker2).isEqualTo(picker);
    res = picker2.pickSubchannel(newPickSubchannelArgs(fakeSearchMethod));
    // verify success. Subchannel is wrapped, so checking attributes.
    assertThat(subchannelIsReady(res.getSubchannel())).isTrue();
    assertThat(res.getSubchannel().getAddresses()).isEqualTo(searchSubchannel.getAddresses());
    assertThat(res.getSubchannel().getAttributes()).isEqualTo(searchSubchannel.getAttributes());
    verifyLongCounterAdd("grpc.lb.rls.target_picks", 1, 1, "wilderness", "complete");

    inOrder.verify(helper).getMetricRecorder();
    inOrder.verify(helper).getChannelTarget();
    inOrder.verifyNoMoreInteractions();

    rlsLb.handleNameResolutionError(Status.UNAVAILABLE);

    verify(helper)
        .updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    SubchannelPicker failedPicker = pickerCaptor.getValue();
    res = failedPicker.pickSubchannel(newPickSubchannelArgs(fakeSearchMethod));
    assertThat(res.getStatus().isOk()).isFalse();
    assertThat(subchannelIsReady(res.getSubchannel())).isFalse();
  }

  private PickResult markReadyAndGetPickResult(InOrder inOrder,
                                               PickSubchannelArgs pickSubchannelArgs) {
    subchannels.getLast().updateState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    inOrder.verify(helper, atLeast(1))
        .updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());
    PickResult pickResult = pickerCaptor.getValue().pickSubchannel(pickSubchannelArgs);
    inOrder.verify(helper, atLeast(0)).getChannelLogger();
    return pickResult;
  }

  private void deliverResolvedAddresses() throws Exception {
    ConfigOrError parsedConfigOrError =
        provider.parseLoadBalancingPolicyConfig(parseJson(getLbConfigJsonStr()));
    assertThat(parsedConfigOrError.getConfig()).isNotNull();
    rlsLb.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.of(new EquivalentAddressGroup(mock(SocketAddress.class))))
        .setLoadBalancingPolicyConfig(parsedConfigOrError.getConfig())
        .build());
    verify(helper).createResolvingOobChannelBuilder(anyString(), any(ChannelCredentials.class));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseJson(String json) throws IOException {
    return (Map<String, Object>) JsonParser.parse(json);
  }

  private String getServiceConfigJsonStr() {
    return "{"
        + "  \"loadBalancingConfig\": [{"
        + "    \"rls_experimental\": " + getLbConfigJsonStr()
        + "  }]"
        + "}";
  }

  private String getLbConfigJsonStr() {
    return "{"
        + "  \"routeLookupConfig\": " + getRlsConfigJsonStr() + ", "
        + "  \"childPolicy\": [{\"pick_first\": {}}],"
        + "  \"childPolicyConfigTargetFieldName\": \"serviceName\""
        + "}";
  }

  private String getRlsConfigJsonStr() {
    return "{\n"
        + "  \"grpcKeybuilders\": [\n"
        + "    {\n"
        + "      \"names\": [\n"
        + "        {\n"
        + "          \"service\": \"com.google\",\n"
        + "          \"method\": \"*\"\n"
        + "        }\n"
        + "      ],\n"
        + "      \"headers\": [\n"
        + "        {\n"
        + "          \"key\": \"permit\","
        + "          \"names\": [\"PermitId\"],\n"
        + "          \"optional\": true\n"
        + "        }\n"
        + "      ],\n"
        + "      \"extraKeys\": {\n"
        + "        \"host\": \"server\",\n"
        + "        \"service\": \"service-key\",\n"
        + "        \"method\": \"method-key\"\n"
        + "      }\n"
        + "    }\n"
        + "  ],\n"
        + "  \"lookupService\": \"localhost:8972\",\n"
        + "  \"lookupServiceTimeout\": \"2s\",\n"
        + "  \"maxAge\": \"300s\",\n"
        + "  \"staleAge\": \"240s\",\n"
        + "  \"validTargets\": [\"localhost:9001\", \"localhost:9002\"],"
        + "  \"cacheSizeBytes\": \"1000\",\n"
        + "  \"defaultTarget\": \"" + defaultTarget + "\",\n"
        + "  \"requestProcessingStrategy\": \"SYNC_LOOKUP_DEFAULT_TARGET_ON_ERROR\"\n"
        + "}";
  }

  // Verifies that the MetricRecorder has been called to record a long counter value of 1 for the
  // given metric name, the given number of times
  private void verifyLongCounterAdd(String name, int times, long value,
      String dataPlaneTargetLabel, String pickResult) {
    // TODO: support the "grpc.target" label once available.
    verify(mockMetricRecorder, times(times)).addLongCounter(
        eqMetricInstrumentName(name), eq(value),
        eq(Lists.newArrayList(channelTarget, "localhost:8972", dataPlaneTargetLabel, pickResult)),
        eq(Lists.newArrayList()));
  }

  // This one is for verifying the failed_pick metric specifically.
  private void verifyFailedPicksCounterAdd(int times, long value) {
    // TODO: support the "grpc.target" label once available.
    verify(mockMetricRecorder, times(times)).addLongCounter(
        eqMetricInstrumentName("grpc.lb.rls.failed_picks"), eq(value),
        eq(Lists.newArrayList(channelTarget, "localhost:8972")),
        eq(Lists.newArrayList()));
  }

  @SuppressWarnings("TypeParameterUnusedInFormals")
  private <T extends MetricInstrument> T eqMetricInstrumentName(String name) {
    return argThat(new ArgumentMatcher<T>() {
      @Override
      public boolean matches(T instrument) {
        return instrument.getName().equals(name);
      }
    });
  }

  private PickSubchannelArgs newPickSubchannelArgs(MethodDescriptor<?, ?> method) {
    return new PickSubchannelArgsImpl(
        method, new Metadata(), CallOptions.DEFAULT, new PickDetailsConsumer() {});
  }

  private final class FakeHelper extends Helper {

    @Override
    public Subchannel createSubchannel(CreateSubchannelArgs args) {
      FakeSubchannel subchannel = new FakeSubchannel(args.getAddresses(), args.getAttributes());
      subchannels.add(subchannel);
      return subchannel;
    }

    @Override
    public ManagedChannelBuilder<?> createResolvingOobChannelBuilder(
        String target, ChannelCredentials creds) {
      try {
        grpcCleanupRule.register(
            InProcessServerBuilder.forName(target)
                .addService(fakeRlsServerImpl)
                .directExecutor()
                .build()
                .start());
      } catch (IOException e) {
        throw new RuntimeException("cannot create server: " + target, e);
      }
      final InProcessChannelBuilder builder =
          InProcessChannelBuilder.forName(target).directExecutor();

      class CleaningChannelBuilder extends ForwardingChannelBuilder2<CleaningChannelBuilder> {

        @Override
        protected ManagedChannelBuilder<?> delegate() {
          return builder;
        }

        @Override
        public ManagedChannel build() {
          return grpcCleanupRule.register(super.build());
        }
      }

      return new CleaningChannelBuilder();
    }

    @Override
    public ManagedChannel createOobChannel(EquivalentAddressGroup eag, String authority) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void updateBalancingState(
        @Nonnull ConnectivityState newState, @Nonnull SubchannelPicker newPicker) {
      // no-op
    }

    @Override
    public void refreshNameResolution() {
      // no-op
    }

    @Override
    public String getAuthority() {
      return "fake-bigtable.googleapis.com";
    }

    @Override
    public ChannelCredentials getUnsafeChannelCredentials() {
      // In test we don't do any authentication.
      return new ChannelCredentials() {
        @Override
        public ChannelCredentials withoutBearerTokens() {
          return this;
        }
      };
    }


    @Override
    public ScheduledExecutorService getScheduledExecutorService() {
      return fakeClock.getScheduledExecutorService();
    }

    @Override
    public SynchronizationContext getSynchronizationContext() {
      return syncContext;
    }

    @Override
    public ChannelLogger getChannelLogger() {
      return mock(ChannelLogger.class);
    }

    @Override
    public MetricRecorder getMetricRecorder() {
      return mockMetricRecorder;
    }

    @Override
    public String getChannelTarget() {
      return channelTarget;
    }
  }

  private static final class FakeRlsServerImpl
      extends RouteLookupServiceGrpc.RouteLookupServiceImplBase {

    private static final Converter<io.grpc.lookup.v1.RouteLookupRequest, RouteLookupRequest>
        REQUEST_CONVERTER = new RlsProtoConverters.RouteLookupRequestConverter();
    private static final Converter<RouteLookupResponse, io.grpc.lookup.v1.RouteLookupResponse>
        RESPONSE_CONVERTER = new RouteLookupResponseConverter().reverse();

    private final ScheduledExecutorService scheduler;
    private long delay;
    private TimeUnit delayUnit;

    public FakeRlsServerImpl(ScheduledExecutorService scheduler) {
      this.scheduler = scheduler;
    }

    private Map<RouteLookupRequest, RouteLookupResponse> lookupTable = ImmutableMap.of();

    private void setLookupTable(Map<RouteLookupRequest, RouteLookupResponse> lookupTable) {
      this.lookupTable = checkNotNull(lookupTable, "lookupTable");
    }

    void setResponseDelay(long delay, TimeUnit unit) {
      this.delay = delay;
      this.delayUnit = unit;
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void routeLookup(io.grpc.lookup.v1.RouteLookupRequest request,
        StreamObserver<io.grpc.lookup.v1.RouteLookupResponse> responseObserver) {
      RouteLookupResponse response =
          lookupTable.get(REQUEST_CONVERTER.convert(request));
      Runnable sendResponse = () -> sendResponse(response, responseObserver);
      if (delay != 0) {
        scheduler.schedule(sendResponse, delay, delayUnit);
      } else {
        sendResponse.run();
      }
    }

    private void sendResponse(RouteLookupResponse response,
        StreamObserver<io.grpc.lookup.v1.RouteLookupResponse> responseObserver) {
      if (response == null) {
        responseObserver.onError(new RuntimeException("not found"));
      } else {
        responseObserver.onNext(RESPONSE_CONVERTER.convert(response));
        responseObserver.onCompleted();
      }
    }
  }

  private static final class FakeSubchannel extends Subchannel {
    private final Attributes attributes;
    private List<EquivalentAddressGroup> eags;
    private SubchannelStateListener listener;
    private volatile boolean isReady;

    public FakeSubchannel(List<EquivalentAddressGroup> eags, Attributes attributes) {
      this.eags = Collections.unmodifiableList(eags);
      this.attributes = attributes;
    }

    @Override
    public List<EquivalentAddressGroup> getAllAddresses() {
      return eags;
    }

    @Override
    public Attributes getAttributes() {
      return attributes;
    }

    @Override
    public void start(SubchannelStateListener listener) {
      this.listener = checkNotNull(listener, "listener");
    }

    @Override
    public void updateAddresses(List<EquivalentAddressGroup> addrs) {
      this.eags = Collections.unmodifiableList(addrs);
    }

    @Override
    public void shutdown() {
    }

    @Override
    public void requestConnection() {
    }

    public void updateState(ConnectivityStateInfo newState) {
      listener.onSubchannelState(newState);
      isReady = newState.getState().equals(ConnectivityState.READY);
    }
  }

  private static boolean subchannelIsReady(Subchannel subchannel) {
    return subchannel instanceof FakeSubchannel && ((FakeSubchannel) subchannel).isReady;
  }

  private static final class FakeThrottler implements Throttler {

    private boolean nextResult = false;

    @Override
    public boolean shouldThrottle() {
      return nextResult;
    }

    @Override
    public void registerBackendResponse(boolean throttled) {
      // no-op
    }
  }

}
