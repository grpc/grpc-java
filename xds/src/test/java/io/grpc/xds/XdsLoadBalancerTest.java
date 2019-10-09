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
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.LoadBalancer.ATTR_LOAD_BALANCING_CONFIG;
import static io.grpc.xds.XdsSubchannelPickers.BUFFER_PICKER;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.protobuf.Any;
import com.google.protobuf.UInt32Value;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.core.Address;
import io.envoyproxy.envoy.api.v2.core.Locality;
import io.envoyproxy.envoy.api.v2.core.SocketAddress;
import io.envoyproxy.envoy.api.v2.endpoint.Endpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.envoyproxy.envoy.type.FractionalPercent;
import io.envoyproxy.envoy.type.FractionalPercent.DenominatorType;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ChannelLogger;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.internal.FakeClock.TaskFilter;
import io.grpc.internal.JsonParser;
import io.grpc.internal.testing.StreamRecorder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link XdsLoadBalancer}.
 */
@RunWith(JUnit4.class)
public class XdsLoadBalancerTest {
  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();
  @Mock
  private Helper helper;
  @Mock
  private LoadBalancer fallbackBalancer1;
  @Mock
  private LoadBalancer fakeBalancer2;
  @Mock
  private BackoffPolicy.Provider backoffPolicyProvider;
  private XdsLoadBalancer lb;

  private final FakeClock fakeClock = new FakeClock();
  private final StreamRecorder<DiscoveryRequest> streamRecorder = StreamRecorder.create();

  private final LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();

  private Helper fallbackHelper1;

  private final LoadBalancerProvider lbProvider1 = new LoadBalancerProvider() {
    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int getPriority() {
      return 5;
    }

    @Override
    public String getPolicyName() {
      return "fallback_1";
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      fallbackHelper1 = helper;
      return fallbackBalancer1;
    }
  };

  private final LoadBalancerProvider lbProvider2 = new LoadBalancerProvider() {
    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int getPriority() {
      return 5;
    }

    @Override
    public String getPolicyName() {
      return "supported_2";
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      return fakeBalancer2;
    }
  };

  private final Locality localityProto1 = Locality.newBuilder()
      .setRegion("region1").setZone("zone1").setSubZone("subzone1").build();
  private final LbEndpoint endpoint11 = LbEndpoint.newBuilder()
      .setEndpoint(Endpoint.newBuilder()
          .setAddress(Address.newBuilder()
              .setSocketAddress(SocketAddress.newBuilder()
                  .setAddress("addr11").setPortValue(11))))
      .setLoadBalancingWeight(UInt32Value.of(11))
      .build();
  private final DiscoveryResponse edsResponse = DiscoveryResponse.newBuilder()
      .addResources(Any.pack(ClusterLoadAssignment.newBuilder()
          .addEndpoints(LocalityLbEndpoints.newBuilder()
              .setLocality(localityProto1)
              .addLbEndpoints(endpoint11)
              .setLoadBalancingWeight(UInt32Value.of(1)))
          .build()))
      .setTypeUrl("type.googleapis.com/envoy.api.v2.ClusterLoadAssignment")
      .build();

  private Helper childHelper;
  @Mock
  private LoadBalancer childBalancer;

  private final LoadBalancerProvider roundRobin = new LoadBalancerProvider() {
    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int getPriority() {
      return 5;
    }

    @Override
    public String getPolicyName() {
      return "round_robin";
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      childHelper = helper;
      return childBalancer;
    }
  };

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });

  private final TaskFilter fallbackTaskFilter = new TaskFilter() {
    @Override
    public boolean shouldAccept(Runnable runnable) {
      return runnable.toString().contains("FallbackTask");
    }
  };

  private ManagedChannel oobChannel1;
  private ManagedChannel oobChannel2;
  private ManagedChannel oobChannel3;

  private StreamObserver<DiscoveryResponse> serverResponseWriter;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    lbRegistry.register(lbProvider1);
    lbRegistry.register(lbProvider2);
    lbRegistry.register(roundRobin);
    lb = new XdsLoadBalancer(helper, lbRegistry, backoffPolicyProvider);
    doReturn(syncContext).when(helper).getSynchronizationContext();
    doReturn(fakeClock.getScheduledExecutorService()).when(helper).getScheduledExecutorService();
    doReturn(mock(ChannelLogger.class)).when(helper).getChannelLogger();
    doReturn("fake_authority").when(helper).getAuthority();

    String serverName = InProcessServerBuilder.generateName();

    AggregatedDiscoveryServiceImplBase serviceImpl = new AggregatedDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamAggregatedResources(
          final StreamObserver<DiscoveryResponse> responseObserver) {
        serverResponseWriter = responseObserver;

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
            responseObserver.onCompleted();
          }
        };
      }
    };

    cleanupRule.register(
        InProcessServerBuilder
            .forName(serverName)
            .directExecutor()
            .addService(serviceImpl)
            .build()
            .start());

    InProcessChannelBuilder channelBuilder =
        InProcessChannelBuilder.forName(serverName).directExecutor();
    oobChannel1 = mock(
        ManagedChannel.class,
        delegatesTo(cleanupRule.register(channelBuilder.build())));
    oobChannel2 = mock(
        ManagedChannel.class,
        delegatesTo(cleanupRule.register(channelBuilder.build())));
    oobChannel3 = mock(
        ManagedChannel.class,
        delegatesTo(cleanupRule.register(channelBuilder.build())));

    doReturn(oobChannel1).doReturn(oobChannel2).doReturn(oobChannel3)
      .when(helper).createResolvingOobChannel(anyString());

    // To write less tedious code for tests, allow fallbackBalancer to handle empty address list.
    doReturn(true).when(fallbackBalancer1).canHandleEmptyAddressListFromNameResolution();
  }

  @After
  public void tearDown() {
    lb.shutdown();
  }

  @Test
  public void canHandleEmptyAddressListFromNameResolution() {
    assertTrue(lb.canHandleEmptyAddressListFromNameResolution());
  }

  @Test
  public void resolverEvent_standardModeToStandardMode() throws Exception {
    String lbConfigRaw = "{"
        + "\"balancerName\" : \"dns:///balancer.example.com:8080\","
        + "\"childPolicy\" : [{\"unsupported\" : {\"key\" : \"val\"}}, {\"unsupported_2\" : {}}],"
        + "\"fallbackPolicy\" : [{\"unsupported\" : {}}, {\"fallback_1\" : {\"key\" : \"val\"}}]"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    Attributes attrs = Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig).build();

    lb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(attrs)
            .build());

    XdsLbState xdsLbState1 = lb.getXdsLbStateForTest();
    assertThat(xdsLbState1.childPolicy).isNull();
    verify(helper).createResolvingOobChannel(anyString());
    verify(oobChannel1)
        .newCall(ArgumentMatchers.<MethodDescriptor<?, ?>>any(),
            ArgumentMatchers.<CallOptions>any());


    lbConfigRaw = "{"
        + "\"balancerName\" : \"dns:///balancer.example.com:8080\","
        + "\"fallbackPolicy\" : [{\"unsupported\" : {}}, {\"fallback_1\" : {\"key\" : \"val\"}}]"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig2 = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    attrs = Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig2).build();

    lb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(attrs)
            .build());

    XdsLbState xdsLbState2 = lb.getXdsLbStateForTest();
    assertThat(xdsLbState2.childPolicy).isNull();
    assertThat(xdsLbState2).isSameInstanceAs(xdsLbState1);

    // verify oobChannel is unchanged
    verify(helper).createResolvingOobChannel(anyString());
    // verify ADS stream is unchanged
    verify(oobChannel1)
        .newCall(ArgumentMatchers.<MethodDescriptor<?, ?>>any(),
            ArgumentMatchers.<CallOptions>any());
  }

  @Test
  public void resolverEvent_standardModeToCustomMode() throws Exception {
    String lbConfigRaw = "{"
        + "\"balancerName\" : \"dns:///balancer.example.com:8080\","
        + "\"childPolicy\" : [{\"unsupported\" : {\"key\" : \"val\"}}, {\"unsupported_2\" : {}}],"
        + "\"fallbackPolicy\" : [{\"unsupported\" : {}}, {\"fallback_1\" : {\"key\" : \"val\"}}]"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    Attributes attrs = Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig).build();

    lb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(attrs)
            .build());
    verify(helper).createResolvingOobChannel(anyString());
    verify(oobChannel1)
        .newCall(ArgumentMatchers.<MethodDescriptor<?, ?>>any(),
            ArgumentMatchers.<CallOptions>any());

    lbConfigRaw = "{"
        + "\"balancerName\" : \"dns:///balancer.example.com:8080\","
        + "\"childPolicy\" : [{\"supported_2\" : {\"key\" : \"val\"}}, {\"unsupported_2\" : {}}],"
        + "\"fallbackPolicy\" : [{\"unsupported\" : {}}, {\"fallback_1\" : {\"key\" : \"val\"}}]"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig2 = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    attrs = Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig2).build();

    lb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(attrs)
            .build());

    assertThat(lb.getXdsLbStateForTest().childPolicy).isNotNull();

    // verify oobChannel is unchanged
    verify(helper).createResolvingOobChannel(anyString());
    // verify ADS stream is reset
    verify(oobChannel1, times(2))
        .newCall(ArgumentMatchers.<MethodDescriptor<?, ?>>any(),
            ArgumentMatchers.<CallOptions>any());
  }

  @Test
  public void resolverEvent_customModeToStandardMode() throws Exception {
    String lbConfigRaw = "{"
        + "\"balancerName\" : \"dns:///balancer.example.com:8080\","
        + "\"childPolicy\" : [{\"supported_2\" : {\"key\" : \"val\"}}, {\"unsupported_2\" : {}}],"
        + "\"fallbackPolicy\" : [{\"unsupported\" : {}}, {\"fallback_1\" : {\"key\" : \"val\"}}]"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    Attributes attrs = Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig).build();

    lb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(attrs)
            .build());
    verify(helper).createResolvingOobChannel(anyString());
    verify(oobChannel1)
        .newCall(ArgumentMatchers.<MethodDescriptor<?, ?>>any(),
            ArgumentMatchers.<CallOptions>any());

    assertThat(lb.getXdsLbStateForTest().childPolicy).isNotNull();

    lbConfigRaw = "{"
        + "\"balancerName\" : \"dns:///balancer.example.com:8080\","
        + "\"childPolicy\" : [{\"unsupported\" : {\"key\" : \"val\"}}, {\"unsupported_2\" : {}}],"
        + "\"fallbackPolicy\" : [{\"unsupported\" : {}}, {\"fallback_1\" : {\"key\" : \"val\"}}]"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig2 = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    attrs = Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig2).build();

    lb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(attrs)
            .build());

    assertThat(lb.getXdsLbStateForTest().childPolicy).isNull();

    // verify oobChannel is unchanged
    verify(helper).createResolvingOobChannel(anyString());
    // verify ADS stream is reset
    verify(oobChannel1, times(2))
        .newCall(ArgumentMatchers.<MethodDescriptor<?, ?>>any(),
            ArgumentMatchers.<CallOptions>any());
  }

  @Test
  public void resolverEvent_customModeToCustomMode() throws Exception {
    String lbConfigRaw = "{"
        + "\"balancerName\" : \"dns:///balancer.example.com:8080\","
        + "\"childPolicy\" : [{\"supported_2\" : {\"key\" : \"val\"}}, {\"unsupported_2\" : {}}],"
        + "\"fallbackPolicy\" : [{\"unsupported\" : {}}, {\"fallback_1\" : {\"key\" : \"val\"}}]"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    Attributes attrs = Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig).build();
    lb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(attrs)
            .build());

    assertThat(lb.getXdsLbStateForTest().childPolicy).isNotNull();
    verify(helper).createResolvingOobChannel(anyString());
    verify(oobChannel1)
        .newCall(ArgumentMatchers.<MethodDescriptor<?, ?>>any(),
            ArgumentMatchers.<CallOptions>any());

    lbConfigRaw = "{"
        + "\"balancerName\" : \"dns:///balancer.example.com:8080\","
        + "\"childPolicy\" : [{\"fallback_1\" : {\"key\" : \"val\"}}, {\"unfallback_1\" : {}}],"
        + "\"fallbackPolicy\" : [{\"unsupported\" : {}}, {\"fallback_1\" : {\"key\" : \"val\"}}]"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig2 = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    attrs = Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig2).build();

    lb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(attrs)
            .build());

    assertThat(lb.getXdsLbStateForTest().childPolicy).isNotNull();
    // verify oobChannel is unchanged
    verify(helper).createResolvingOobChannel(anyString());
    // verify ADS stream is reset
    verify(oobChannel1, times(2))
        .newCall(ArgumentMatchers.<MethodDescriptor<?, ?>>any(),
            ArgumentMatchers.<CallOptions>any());
  }

  @Test
  public void resolverEvent_balancerNameChange() throws Exception {
    String lbConfigRaw = "{"
        + "\"balancerName\" : \"dns:///balancer.example.com:8080\","
        + "\"childPolicy\" : [{\"unsupported\" : {\"key\" : \"val\"}}, {\"unsupported_2\" : {}}],"
        + "\"fallbackPolicy\" : [{\"unsupported\" : {}}, {\"fallback_1\" : {\"key\" : \"val\"}}]"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    Attributes attrs = Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig).build();

    lb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(attrs)
            .build());
    verify(helper).createResolvingOobChannel(anyString());
    verify(oobChannel1)
        .newCall(ArgumentMatchers.<MethodDescriptor<?, ?>>any(),
            ArgumentMatchers.<CallOptions>any());

    lbConfigRaw = "{"
        + "\"balancerName\" : \"dns:///balancer.example.com:8443\","
        + "\"childPolicy\" : [{\"fallback_1\" : {\"key\" : \"val\"}}, {\"unsupported_2\" : {}}],"
        + "\"fallbackPolicy\" : [{\"unsupported\" : {}}, {\"fallback_1\" : {\"key\" : \"val\"}}]"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig2 = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    attrs = Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig2).build();

    lb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(attrs)
            .build());

    assertThat(lb.getXdsLbStateForTest().childPolicy).isNotNull();

    // verify oobChannel is unchanged
    verify(helper, times(2)).createResolvingOobChannel(anyString());
    verify(oobChannel1)
        .newCall(ArgumentMatchers.<MethodDescriptor<?, ?>>any(),
            ArgumentMatchers.<CallOptions>any());
    verify(oobChannel2)
        .newCall(ArgumentMatchers.<MethodDescriptor<?, ?>>any(),
            ArgumentMatchers.<CallOptions>any());
    verifyNoMoreInteractions(oobChannel3);
  }

  @Test
  public void resolutionErrorAtStartup() {
    lb.handleNameResolutionError(Status.UNAVAILABLE);

    assertNull(childHelper);
    assertNull(fallbackHelper1);
    verify(helper).updateBalancingState(same(TRANSIENT_FAILURE), isA(ErrorPicker.class));
  }

  @Test
  public void resolutionErrorAtFallback() throws Exception {
    lb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(standardModeWithFallback1Attributes())
            .build());
    // let fallback timer expire
    assertThat(fakeClock.forwardTime(10, TimeUnit.SECONDS)).isEqualTo(1);
    ArgumentCaptor<ResolvedAddresses> captor = ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(fallbackBalancer1).handleResolvedAddresses(captor.capture());
    assertThat(captor.getValue().getAttributes().get(ATTR_LOAD_BALANCING_CONFIG))
        .containsExactly("fallback_1_option", "yes");

    Status status = Status.UNAVAILABLE.withDescription("resolution error");
    lb.handleNameResolutionError(status);
    verify(fallbackBalancer1).handleNameResolutionError(status);
  }

  @Test
  public void fallback_AdsNotWorkingYetTimerExpired() throws Exception {
    lb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(standardModeWithFallback1Attributes())
            .build());

    assertNull(childHelper);
    assertNull(fallbackHelper1);

    assertThat(fakeClock.forwardTime(10, TimeUnit.SECONDS)).isEqualTo(1);

    assertThat(fakeClock.getPendingTasks(fallbackTaskFilter)).isEmpty();
    assertNull(childHelper);
    assertNotNull(fallbackHelper1);
    ArgumentCaptor<ResolvedAddresses> captor = ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(fallbackBalancer1).handleResolvedAddresses(captor.capture());
    assertThat(captor.getValue().getAttributes().get(ATTR_LOAD_BALANCING_CONFIG))
        .containsExactly("fallback_1_option", "yes");

    SubchannelPicker picker = mock(SubchannelPicker.class);
    fallbackHelper1.updateBalancingState(CONNECTING, picker);
    verify(helper).updateBalancingState(CONNECTING, picker);
  }

  @Test
  public void allDropCancelsFallbackTimer() throws Exception {
    lb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(standardModeWithFallback1Attributes())
            .build());
    DiscoveryResponse edsResponse = DiscoveryResponse.newBuilder()
        .addResources(Any.pack(ClusterLoadAssignment.newBuilder()
            .addEndpoints(LocalityLbEndpoints.newBuilder()
                .setLocality(localityProto1)
                .addLbEndpoints(endpoint11)
                .setLoadBalancingWeight(UInt32Value.of(1)))
            .setPolicy(Policy.newBuilder()
                .addDropOverloads(
                    io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy.DropOverload
                        .newBuilder()
                        .setCategory("throttle")
                        .setDropPercentage(FractionalPercent.newBuilder()
                            .setNumerator(100).setDenominator(DenominatorType.HUNDRED).build())
                        .build()))
            .build()))
        .setTypeUrl("type.googleapis.com/envoy.api.v2.ClusterLoadAssignment")
        .build();
    serverResponseWriter.onNext(edsResponse);
    assertThat(fakeClock.getPendingTasks(fallbackTaskFilter)).isEmpty();
    assertNotNull(childHelper);
    assertNull(fallbackHelper1);
    verify(fallbackBalancer1, never()).handleResolvedAddresses(any(ResolvedAddresses.class));

  }

  @Test
  public void allDropExitFallbackMode() throws Exception {
    lb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(standardModeWithFallback1Attributes())
            .build());

    // let the fallback timer expire
    assertThat(fakeClock.forwardTime(10, TimeUnit.SECONDS)).isEqualTo(1);
    assertThat(fakeClock.getPendingTasks(fallbackTaskFilter)).isEmpty();
    assertNull(childHelper);
    assertNotNull(fallbackHelper1);

    // receives EDS response with 100% drop
    DiscoveryResponse edsResponse = DiscoveryResponse.newBuilder()
        .addResources(Any.pack(ClusterLoadAssignment.newBuilder()
            .addEndpoints(LocalityLbEndpoints.newBuilder()
                .setLocality(localityProto1)
                .addLbEndpoints(endpoint11)
                .setLoadBalancingWeight(UInt32Value.of(1)))
            .setPolicy(Policy.newBuilder()
                .addDropOverloads(
                    io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy.DropOverload
                        .newBuilder()
                        .setCategory("throttle")
                        .setDropPercentage(FractionalPercent.newBuilder()
                            .setNumerator(100).setDenominator(DenominatorType.HUNDRED).build())
                        .build()))
            .build()))
        .setTypeUrl("type.googleapis.com/envoy.api.v2.ClusterLoadAssignment")
        .build();
    serverResponseWriter.onNext(edsResponse);
    verify(fallbackBalancer1).shutdown();
    assertNotNull(childHelper);

    ArgumentCaptor<SubchannelPicker> subchannelPickerCaptor =
        ArgumentCaptor.forClass(SubchannelPicker.class);
    verify(helper).updateBalancingState(same(CONNECTING), subchannelPickerCaptor.capture());
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class))
        .isDrop()).isTrue();
  }

  @Test
  public void fallback_ErrorWithoutReceivingEdsResponse() throws Exception {
    lb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(standardModeWithFallback1Attributes())
            .build());

    assertNull(childHelper);
    assertNull(fallbackHelper1);
    assertThat(fakeClock.getPendingTasks(fallbackTaskFilter)).hasSize(1);

    serverResponseWriter.onError(new Exception("fake error"));

    // goes to fallback-at-startup mode immediately
    assertThat(fakeClock.getPendingTasks(fallbackTaskFilter)).isEmpty();
    assertNull(childHelper);
    assertNotNull(fallbackHelper1);
    // verify fallback balancer is working
    ArgumentCaptor<ResolvedAddresses> captor = ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(fallbackBalancer1).handleResolvedAddresses(captor.capture());
    assertThat(captor.getValue().getAttributes().get(ATTR_LOAD_BALANCING_CONFIG))
        .containsExactly("fallback_1_option", "yes");

    SubchannelPicker picker = mock(SubchannelPicker.class);
    fallbackHelper1.updateBalancingState(CONNECTING, picker);
    verify(helper).updateBalancingState(CONNECTING, picker);
  }

  @Test
  public void fallback_EdsResponseReceivedThenErrorBeforeBackendReady() throws Exception {
    lb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(standardModeWithFallback1Attributes())
            .build());
    serverResponseWriter.onNext(edsResponse);
    assertNotNull(childHelper);
    assertNull(fallbackHelper1);
    verify(helper).updateBalancingState(CONNECTING, BUFFER_PICKER);

    serverResponseWriter.onError(new Exception("fake error"));
    assertThat(fakeClock.getPendingTasks(fallbackTaskFilter)).hasSize(1);
    // verify fallback balancer is not started
    assertNull(fallbackHelper1);
    verify(fallbackBalancer1, never()).handleResolvedAddresses(any(ResolvedAddresses.class));

    SubchannelPicker picker1 = mock(SubchannelPicker.class);
    childHelper.updateBalancingState(CONNECTING, picker1);
    verify(helper, times(2)).updateBalancingState(CONNECTING, BUFFER_PICKER);
    childHelper.updateBalancingState(TRANSIENT_FAILURE, picker1);
    verify(helper).updateBalancingState(same(TRANSIENT_FAILURE), isA(ErrorPicker.class));

    assertThat(fakeClock.forwardTime(10, TimeUnit.SECONDS)).isEqualTo(1);
    // verify fallback balancer is working
    ArgumentCaptor<ResolvedAddresses> captor = ArgumentCaptor.forClass(ResolvedAddresses.class);
    assertNotNull(fallbackHelper1);
    verify(fallbackBalancer1).handleResolvedAddresses(captor.capture());
    assertThat(captor.getValue().getAttributes().get(ATTR_LOAD_BALANCING_CONFIG))
        .containsExactly("fallback_1_option", "yes");

    SubchannelPicker picker2 = mock(SubchannelPicker.class);
    childHelper.updateBalancingState(CONNECTING, picker2);
    // verify childHelper no more delegates updateBalancingState to parent helper
    verify(helper, times(3)).updateBalancingState(
        any(ConnectivityState.class), any(SubchannelPicker.class));

    SubchannelPicker picker3 = mock(SubchannelPicker.class);
    fallbackHelper1.updateBalancingState(CONNECTING, picker3);
    verify(helper).updateBalancingState(CONNECTING, picker3);

    SubchannelPicker picker4 = mock(SubchannelPicker.class);
    childHelper.updateBalancingState(READY, picker4);
    verify(fallbackBalancer1).shutdown();
    verify(helper).updateBalancingState(same(READY), isA(InterLocalityPicker.class));
  }

  @Test
  public void fallback_AdsErrorWithActiveSubchannel() throws Exception {
    lb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(standardModeWithFallback1Attributes())
            .build());
    serverResponseWriter.onNext(edsResponse);
    assertNotNull(childHelper);
    assertThat(fakeClock.getPendingTasks(fallbackTaskFilter)).hasSize(1);
    assertNull(fallbackHelper1);
    verify(helper).updateBalancingState(CONNECTING, BUFFER_PICKER);

    childHelper.updateBalancingState(READY, mock(SubchannelPicker.class));
    verify(helper).updateBalancingState(same(READY), isA(InterLocalityPicker.class));
    assertThat(fakeClock.getPendingTasks(fallbackTaskFilter)).isEmpty();

    serverResponseWriter.onError(new Exception("fake error"));
    assertNull(fallbackHelper1);
    verify(fallbackBalancer1, never()).handleResolvedAddresses(
        any(ResolvedAddresses.class));

    // verify childHelper still delegates updateBalancingState to parent helper
    childHelper.updateBalancingState(CONNECTING, mock(SubchannelPicker.class));
    verify(helper, times(2)).updateBalancingState(CONNECTING, BUFFER_PICKER);
  }

  private static Attributes standardModeWithFallback1Attributes() throws Exception {
    String lbConfigRaw = "{"
        + "\"balancerName\" : \"dns:///balancer.example.com:8080\","
        + "\"fallbackPolicy\" : [{\"fallback_1\" : { \"fallback_1_option\" : \"yes\"}}]"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    return Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig).build();
  }

  @Test
  public void shutdown_cleanupTimers() throws Exception {
    String lbConfigRaw = "{ "
        + "\"balancerName\" : \"dns:///balancer.example.com:8080\","
        + "\"childPolicy\" : [{\"unsupported\" : {\"key\" : \"val\"}}, {\"unsupported_2\" : {}}],"
        + "\"fallbackPolicy\" : [{\"unsupported\" : {}}, {\"fallback_1\" : {\"key\" : \"val\"}}]"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    Attributes attrs = Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig).build();
    lb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(attrs)
            .build());

    assertThat(fakeClock.getPendingTasks()).isNotEmpty();
    lb.shutdown();
    assertThat(fakeClock.getPendingTasks()).isEmpty();
  }
}
