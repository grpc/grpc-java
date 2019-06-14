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
import static io.grpc.LoadBalancer.ATTR_LOAD_BALANCING_CONFIG;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.Any;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.internal.JsonParser;
import io.grpc.internal.testing.StreamRecorder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.XdsLoadBalancer.FallbackManager;
import io.grpc.xds.XdsLoadReportClientImpl.XdsLoadReportClientFactory;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link XdsLoadBalancer}, especially for interactions between
 * {@link XdsLoadBalancer} and {@link XdsLoadReportClient}.
 */
@RunWith(JUnit4.class)
public class XdsLoadBalancerWithLrsTest {
  private static final String SERVICE_AUTHORITY = "test authority";

  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });

  @Mock
  private Helper helper;
  @Mock
  private BackoffPolicy.Provider backoffPolicyProvider;
  @Mock
  private LocalityStore localityStore;
  @Mock
  private XdsLoadReportClientFactory lrsClientFactory;
  @Mock
  private XdsLoadReportClient lrsClient;
  @Mock
  private StatsStore statsStore;
  @Mock
  private LoadBalancer fallbackBalancer;
  @Mock
  private LoadBalancer mockBalancer;

  private final FakeClock fakeClock = new FakeClock();
  private final LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();
  private final StreamRecorder<DiscoveryRequest> streamRecorder = StreamRecorder.create();
  private final LoadBalancerProvider fallBackLbProvider = new LoadBalancerProvider() {
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
      return "fallback";
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      fallBackLbHelper = helper;
      return fallbackBalancer;
    }
  };
  private final LoadBalancerProvider lbProvider = new LoadBalancerProvider() {
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
      return "supported";
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      return mockBalancer;
    }
  };

  private Helper fallBackLbHelper;
  private StreamObserver<DiscoveryResponse> serverResponseWriter;
  private ManagedChannel oobChannel1;
  private ManagedChannel oobChannel2;
  private ManagedChannel oobChannel3;
  private LoadBalancer xdsLoadBalancer;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
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

    lbRegistry.register(fallBackLbProvider);
    lbRegistry.register(lbProvider);
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    when(helper.getScheduledExecutorService()).thenReturn(fakeClock.getScheduledExecutorService());
    when(helper.getAuthority()).thenReturn(SERVICE_AUTHORITY);
    when(helper.getChannelLogger()).thenReturn(mock(ChannelLogger.class));
    when(helper.createResolvingOobChannel(anyString()))
        .thenReturn(oobChannel1, oobChannel2, oobChannel3);
    when(localityStore.getStatsStore()).thenReturn(statsStore);
    when(lrsClientFactory.createLoadReportClient(any(ManagedChannel.class), any(Helper.class),
        any(BackoffPolicy.Provider.class), any(StatsStore.class))).thenReturn(lrsClient);

    xdsLoadBalancer =
        new XdsLoadBalancer(helper, lbRegistry, backoffPolicyProvider, lrsClientFactory,
            new FallbackManager(helper, lbRegistry), localityStore);
  }

  @After
  public void tearDown() {
    xdsLoadBalancer.shutdown();
  }

  /**
   * Tests load reporting is initiated after receiving the first valid EDS response from the traffic
   * director, then its operation is independent of load balancing until xDS load balancer is
   * shutdown.
   */
  @Test
  public void reportLoadAfterReceivingFirstEdsResponseUntilShutdown() throws Exception {
    xdsLoadBalancer.handleResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
        .setAttributes(standardModeWithFallbackAttributes())
        .build());

    verify(lrsClientFactory)
        .createLoadReportClient(same(oobChannel1), same(helper), same(backoffPolicyProvider),
            same(statsStore));
    assertThat(streamRecorder.getValues()).hasSize(1);

    // Let fallback timer elapse and xDS load balancer enters fallback mode on startup.
    assertThat(fakeClock.getPendingTasks()).hasSize(1);
    assertThat(fallBackLbHelper).isNull();
    fakeClock.forwardTime(10, TimeUnit.SECONDS);
    assertThat(fallBackLbHelper).isNotNull();

    verify(lrsClient, never()).startLoadReporting();

    // Simulates a syntactically incorrect EDS response.
    serverResponseWriter.onNext(DiscoveryResponse.getDefaultInstance());
    verify(lrsClient, never()).startLoadReporting();

    // Simulate a syntactically correct EDS response.
    DiscoveryResponse edsResponse =
        DiscoveryResponse.newBuilder()
            .addResources(Any.pack(ClusterLoadAssignment.getDefaultInstance()))
            .setTypeUrl("type.googleapis.com/envoy.api.v2.ClusterLoadAssignment")
            .build();
    serverResponseWriter.onNext(edsResponse);
    verify(lrsClient).startLoadReporting();

    // Simulate another EDS response from the same remote balancer.
    serverResponseWriter.onNext(edsResponse);

    // Simulate an EDS error response.
    serverResponseWriter.onError(Status.ABORTED.asException());

    // Shutdown xDS load balancer.
    xdsLoadBalancer.shutdown();
    verify(lrsClient).stopLoadReporting();

    verifyNoMoreInteractions(lrsClientFactory, lrsClient);
  }

  /**
   * Tests load report client sends load to new traffic director when xDS load balancer talks to
   * the remote balancer.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void reportLoadToNewTrafficDirectorAfterBalancerNameChange() throws Exception {
    InOrder inOrder = inOrder(lrsClientFactory, lrsClient);
    xdsLoadBalancer.handleResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
        .setAttributes(standardModeWithFallbackAttributes())
        .build());

    inOrder.verify(lrsClientFactory)
        .createLoadReportClient(same(oobChannel1), same(helper), same(backoffPolicyProvider),
            same(statsStore));
    assertThat(streamRecorder.getValues()).hasSize(1);
    inOrder.verify(lrsClient, never()).startLoadReporting();

    // Simulate receiving a new service config with balancer name changed before xDS protocol is
    // established.
    Map<String, ?> newLbConfig =
        (Map<String, ?>) JsonParser.parse(
            "{\"balancerName\" : \"dns:///another.balancer.example.com:8080\","
                + "\"fallbackPolicy\" : [{\"fallback\" : { \"fallback_option\" : \"yes\"}}]}");

    xdsLoadBalancer.handleResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
        .setAttributes(Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, newLbConfig).build())
        .build());

    assertThat(oobChannel1.isShutdown()).isTrue();
    assertThat(streamRecorder.getValues()).hasSize(2);
    inOrder.verify(lrsClientFactory)
        .createLoadReportClient(same(oobChannel2), same(helper), same(backoffPolicyProvider),
            same(statsStore));

    // Simulate a syntactically correct EDS response.
    DiscoveryResponse edsResponse =
        DiscoveryResponse.newBuilder()
            .addResources(Any.pack(ClusterLoadAssignment.getDefaultInstance()))
            .setTypeUrl("type.googleapis.com/envoy.api.v2.ClusterLoadAssignment")
            .build();
    serverResponseWriter.onNext(edsResponse);
    inOrder.verify(lrsClient).startLoadReporting();

    // Simulate receiving a new service config with balancer name changed.
    newLbConfig = (Map<String, ?>) JsonParser.parse(
        "{\"balancerName\" : \"dns:///third.balancer.example.com:8080\","
            + "\"fallbackPolicy\" : [{\"fallback\" : { \"fallback_option\" : \"yes\"}}]}");

    xdsLoadBalancer.handleResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
        .setAttributes(Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, newLbConfig).build())
        .build());

    assertThat(oobChannel2.isShutdown()).isTrue();
    assertThat(streamRecorder.getValues()).hasSize(3);
    inOrder.verify(lrsClient).stopLoadReporting();
    inOrder.verify(lrsClientFactory)
        .createLoadReportClient(same(oobChannel3), same(helper), same(backoffPolicyProvider),
            same(statsStore));

    serverResponseWriter.onNext(edsResponse);
    inOrder.verify(lrsClient).startLoadReporting();

    inOrder.verifyNoMoreInteractions();
  }

  /**
   * Tests the case that load reporting is not interrupted when child balancing policy changes,
   * even though xDS balancer refreshes discovery RPC with the traffic director.
   */
  @Test
  public void loadReportNotAffectedWhenChildPolicyChanges() throws Exception {
    xdsLoadBalancer.handleResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
        .setAttributes(standardModeWithFallbackAttributes())
        .build());

    verify(lrsClientFactory)
        .createLoadReportClient(same(oobChannel1), same(helper), same(backoffPolicyProvider),
            same(statsStore));
    assertThat(streamRecorder.getValues()).hasSize(1);

    // Simulate a syntactically correct EDS response.
    DiscoveryResponse edsResponse =
        DiscoveryResponse.newBuilder()
            .addResources(Any.pack(ClusterLoadAssignment.getDefaultInstance()))
            .setTypeUrl("type.googleapis.com/envoy.api.v2.ClusterLoadAssignment")
            .build();
    serverResponseWriter.onNext(edsResponse);
    verify(lrsClient).startLoadReporting();

    // Simulate receiving a new service config with child policy changed.
    @SuppressWarnings("unchecked")
    Map<String, ?> newLbConfig =
        (Map<String, ?>) JsonParser.parse(
            "{\"balancerName\" : \"dns:///balancer.example.com:8080\","
                + "\"childPolicy\" : [{\"supported\" : {\"key\" : \"val\"}}],"
                + "\"fallbackPolicy\" : [{\"fallback\" : { \"fallback_option\" : \"yes\"}}]}");

    xdsLoadBalancer.handleResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
        .setAttributes(Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, newLbConfig).build())
        .build());

    assertThat(oobChannel1.isShutdown()).isFalse();
    assertThat(Status.fromThrowable(streamRecorder.getError()).getCode())
        .isEqualTo(Code.CANCELLED);
    assertThat(streamRecorder.getValues()).hasSize(2);
    verify(lrsClient, never()).stopLoadReporting();

    verifyNoMoreInteractions(lrsClientFactory, lrsClient);
  }

  private static Attributes standardModeWithFallbackAttributes() throws Exception {
    String lbConfigRaw = "{"
        + "\"balancerName\" : \"dns:///balancer.example.com:8080\","
        + "\"fallbackPolicy\" : [{\"fallback\" : { \"fallback_option\" : \"yes\"}}]"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    return Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig).build();
  }
}
