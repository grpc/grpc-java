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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.XdsClientTestHelper.buildDiscoveryResponseV2;
import static io.grpc.xds.XdsClientTestHelper.buildListenerV2;
import static io.grpc.xds.XdsClientTestHelper.buildRouteConfigurationV2;
import static io.grpc.xds.XdsClientTestHelper.buildVirtualHostV2;
import static org.junit.Assert.fail;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.UInt32Value;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.Listener;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.core.CidrRange;
import io.envoyproxy.envoy.api.v2.core.SocketAddress;
import io.envoyproxy.envoy.api.v2.core.TransportSocket;
import io.envoyproxy.envoy.api.v2.listener.Filter;
import io.envoyproxy.envoy.api.v2.listener.FilterChain;
import io.envoyproxy.envoy.api.v2.listener.FilterChainMatch;
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.grpc.Context;
import io.grpc.Context.CancellationListener;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.internal.FakeClock.TaskFilter;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.AbstractXdsClient.ResourceType;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.XdsClient.ListenerUpdate;
import io.grpc.xds.XdsClient.ListenerWatcher;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link ServerXdsClient}.
 */
@RunWith(JUnit4.class)
public class ServerXdsClientTest {

  private static final int PORT = 7000;
  private static final String LOCAL_IP = "192.168.3.5";
  private static final String DIFFERENT_IP = "192.168.3.6";
  private static final String INSTANCE_IP = "192.168.3.7";
  private static final String TYPE_URL_HCM =
      "type.googleapis.com/"
          + "envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManager";

  private static final Node NODE = Node.newBuilder().build();
  private static final FakeClock.TaskFilter RPC_RETRY_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(AbstractXdsClient.RpcRetryTask.class.getSimpleName());
        }
      };
  private static final TaskFilter LISTENER_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString()
              .contains(ServerXdsClient.ListenerResourceFetchTimeoutTask.class.getSimpleName());
        }
      };
  private static final String LISTENER_NAME = "INBOUND_LISTENER";

  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private final FakeClock fakeClock = new FakeClock();

  private final Queue<StreamObserver<DiscoveryResponse>> responseObservers = new ArrayDeque<>();
  private final Queue<StreamObserver<DiscoveryRequest>> requestObservers = new ArrayDeque<>();
  private final AtomicBoolean callEnded = new AtomicBoolean(true);

  @Mock
  private AggregatedDiscoveryServiceImplBase mockedDiscoveryService;
  @Mock
  private BackoffPolicy.Provider backoffPolicyProvider;
  @Mock
  private BackoffPolicy backoffPolicy1;
  @Mock
  private BackoffPolicy backoffPolicy2;
  @Mock
  private ListenerWatcher listenerWatcher;

  private ManagedChannel channel;
  private ServerXdsClient xdsClient;

  @Before
  public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);
    when(backoffPolicyProvider.get()).thenReturn(backoffPolicy1, backoffPolicy2);
    when(backoffPolicy1.nextBackoffNanos()).thenReturn(10L, 100L);
    when(backoffPolicy2.nextBackoffNanos()).thenReturn(20L, 200L);

    final String serverName = InProcessServerBuilder.generateName();
    AggregatedDiscoveryServiceImplBase adsServiceImpl = new AggregatedDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamAggregatedResources(
          final StreamObserver<DiscoveryResponse> responseObserver) {
        assertThat(callEnded.get()).isTrue();  // ensure previous call was ended
        callEnded.set(false);
        Context.current().addListener(
            new CancellationListener() {
              @Override
              public void cancelled(Context context) {
                callEnded.set(true);
              }
            }, MoreExecutors.directExecutor());
        responseObservers.offer(responseObserver);
        @SuppressWarnings("unchecked")
        StreamObserver<DiscoveryRequest> requestObserver = mock(StreamObserver.class);
        requestObservers.offer(requestObserver);
        return requestObserver;
      }
    };
    mockedDiscoveryService =
        mock(AggregatedDiscoveryServiceImplBase.class, delegatesTo(adsServiceImpl));

    cleanupRule.register(
        InProcessServerBuilder
            .forName(serverName)
            .addService(mockedDiscoveryService)
            .directExecutor()
            .build()
            .start());
    channel =
        cleanupRule.register(InProcessChannelBuilder.forName(serverName).directExecutor().build());

    xdsClient =
        new ServerXdsClient(channel, /* useProtocolV3= */ false, NODE,
            fakeClock.getScheduledExecutorService(), backoffPolicyProvider,
            fakeClock.getStopwatchSupplier(), false, INSTANCE_IP, "grpc/server");
    // Only the connection to management server is established, no RPC request is sent until at
    // least one watcher is registered.
    assertThat(responseObservers).isEmpty();
    assertThat(requestObservers).isEmpty();
  }

  @After
  public void tearDown() {
    xdsClient.shutdown();
    channel.shutdown();
    assertThat(callEnded.get()).isTrue();
    assertThat(fakeClock.getPendingTasks()).isEmpty();
  }

  private static Node getNodeToVerify() {
    Map<String, Object> newMetadata = new HashMap<>();
    if (NODE.getMetadata() != null) {
      newMetadata.putAll(NODE.getMetadata());
    }
    newMetadata.put("TRAFFICDIRECTOR_INBOUND_INTERCEPTION_PORT", "15001");
    newMetadata.put("TRAFFICDIRECTOR_INBOUND_BACKEND_PORTS", "" + PORT);
    newMetadata.put("INSTANCE_IP", INSTANCE_IP);
    return NODE.toBuilder()
        .setMetadata(newMetadata)
        .build();
  }

  private static DiscoveryRequest buildDiscoveryRequest(
      Node node, String versionInfo, String typeUrl, String nonce) {
    return DiscoveryRequest.newBuilder()
        .setVersionInfo(versionInfo)
        .setNode(node.toEnvoyProtoNodeV2())
        .setTypeUrl(typeUrl)
        .setResponseNonce(nonce)
        .build();
  }

  @Test
  public void ldsResponse_2listenerWatchers_expectError() {
    xdsClient.watchListenerData(PORT, listenerWatcher);
    try {
      xdsClient.watchListenerData(80, listenerWatcher);
      fail("expected exception");
    } catch (IllegalStateException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("ListenerWatcher already registered");
    }
  }

  /**
   * Client receives an LDS response that contains listener with no match i.e. no port match.
   */
  @Test
  public void ldsResponse_nonMatchingFilterChain_notFoundError() {
    xdsClient.watchListenerData(PORT, listenerWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request with null in lds resource name
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "",
            ResourceType.LDS.typeUrlV2(), "")));
    assertThat(fakeClock.getPendingTasks(LISTENER_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListenerV2("bar.googleapis.com",
            Any.pack(HttpConnectionManager.newBuilder()
                .setRouteConfig(
                    buildRouteConfigurationV2("route-bar.googleapis.com",
                        ImmutableList.of(
                            buildVirtualHostV2(
                                ImmutableList.of("bar.googleapis.com"),
                                "cluster-bar.googleapis.com"))))
                .build()))),
        Any.pack(buildListenerV2(LISTENER_NAME,
            Any.pack(HttpConnectionManager.newBuilder()
                .setRouteConfig(
                    buildRouteConfigurationV2("route-baz.googleapis.com",
                        ImmutableList.of(
                            buildVirtualHostV2(
                                ImmutableList.of("baz.googleapis.com"),
                                "cluster-baz.googleapis.com"))))
                .build()))));
    DiscoveryResponse response =
        buildDiscoveryResponseV2("0", listeners, ResourceType.LDS.typeUrlV2(), "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "0",
            ResourceType.LDS.typeUrlV2(), "0000")));

    verify(listenerWatcher, never()).onListenerChanged(any(ListenerUpdate.class));
    verify(listenerWatcher, never()).onResourceDoesNotExist(":" + PORT);
    verify(listenerWatcher, never()).onError(any(Status.class));
    fakeClock.forwardTime(ServerXdsClient.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(listenerWatcher).onResourceDoesNotExist(":" + PORT);
    assertThat(fakeClock.getPendingTasks(LISTENER_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  /** Client receives a Listener with listener address and mismatched port. */
  @Test
  public void ldsResponseWith_listenerAddressPortMismatch() {
    xdsClient.watchListenerData(PORT, listenerWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request with null in lds resource name
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "",
            ResourceType.LDS.typeUrlV2(), "")));
    assertThat(fakeClock.getPendingTasks(LISTENER_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    final FilterChain filterChainOutbound = buildFilterChain(buildFilterChainMatch(8000), null);
    final FilterChain filterChainInbound = buildFilterChain(buildFilterChainMatch(PORT,
        CidrRange.newBuilder().setAddressPrefix(LOCAL_IP)
            .setPrefixLen(UInt32Value.of(32)).build()),
        // Server is still speaking xds v2.
        CommonTlsContextTestsUtil.buildTestDownstreamTlsContextV2("google-sds-config-default",
            "ROOTCA"),
        buildTestFilter("envoy.http_connection_manager"));
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListenerV2("bar.googleapis.com",
            Any.pack(HttpConnectionManager.newBuilder()
                .setRouteConfig(
                    buildRouteConfigurationV2("route-bar.googleapis.com",
                        ImmutableList.of(
                            buildVirtualHostV2(
                                ImmutableList.of("bar.googleapis.com"),
                                "cluster-bar.googleapis.com"))))
                .build()))),
        Any.pack(buildListenerWithFilterChain(LISTENER_NAME, 15002, "0.0.0.0",
            filterChainOutbound,
            filterChainInbound
        )));
    DiscoveryResponse response =
        buildDiscoveryResponseV2("0", listeners, ResourceType.LDS.typeUrlV2(), "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "0",
            ResourceType.LDS.typeUrlV2(), "0000")));

    verify(listenerWatcher, never()).onListenerChanged(any(ListenerUpdate.class));
    verify(listenerWatcher, never()).onResourceDoesNotExist(":" + PORT);
    verify(listenerWatcher, never()).onError(any(Status.class));
    fakeClock.forwardTime(ServerXdsClient.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(listenerWatcher).onResourceDoesNotExist(":" + PORT);
    assertThat(fakeClock.getPendingTasks(LISTENER_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  /** Client receives a Listener with all match. */
  @Test
  public void ldsResponseWith_matchingListenerFound() throws InvalidProtocolBufferException {
    xdsClient.watchListenerData(PORT, listenerWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request with null in lds resource name
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "",
            ResourceType.LDS.typeUrlV2(), "")));
    assertThat(fakeClock.getPendingTasks(LISTENER_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    final FilterChain filterChainOutbound = buildFilterChain(buildFilterChainMatch(8000), null);
    final FilterChain filterChainInbound = buildFilterChain(buildFilterChainMatch(PORT,
        CidrRange.newBuilder().setAddressPrefix(LOCAL_IP)
            .setPrefixLen(UInt32Value.of(32)).build()),
        // Server is still speaking xds v2.
        CommonTlsContextTestsUtil.buildTestDownstreamTlsContextV2("google-sds-config-default",
            "ROOTCA"),
        buildTestFilter("envoy.http_connection_manager"));
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListenerV2("bar.googleapis.com",
            Any.pack(HttpConnectionManager.newBuilder()
                .setRouteConfig(
                    buildRouteConfigurationV2("route-bar.googleapis.com",
                        ImmutableList.of(
                            buildVirtualHostV2(
                                ImmutableList.of("bar.googleapis.com"),
                                "cluster-bar.googleapis.com"))))
                .build()))),
        Any.pack(buildListenerWithFilterChain(LISTENER_NAME, 15001, "0.0.0.0",
            filterChainOutbound,
            filterChainInbound
        )));
    DiscoveryResponse response =
        buildDiscoveryResponseV2("0", listeners, ResourceType.LDS.typeUrlV2(), "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "0",
            ResourceType.LDS.typeUrlV2(), "0000")));

    ArgumentCaptor<ListenerUpdate> listenerUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(listenerWatcher, times(1)).onListenerChanged(listenerUpdateCaptor.capture());
    ListenerUpdate configUpdate = listenerUpdateCaptor.getValue();
    EnvoyServerProtoData.Listener listener = configUpdate.getListener();
    assertThat(listener.getName()).isEqualTo(LISTENER_NAME);
    assertThat(listener.getAddress()).isEqualTo("0.0.0.0:15001");
    assertThat(listener.getFilterChains()).hasSize(2);
    EnvoyServerProtoData.FilterChain filterChainOutboundInListenerUpdate
        = listener.getFilterChains().get(0);
    assertThat(filterChainOutboundInListenerUpdate.getFilterChainMatch().getDestinationPort())
        .isEqualTo(8000);
    EnvoyServerProtoData.FilterChain filterChainInboundInListenerUpdate
        = listener.getFilterChains().get(1);
    EnvoyServerProtoData.FilterChainMatch inBoundfilterChainMatch =
        filterChainInboundInListenerUpdate.getFilterChainMatch();
    assertThat(inBoundfilterChainMatch.getDestinationPort()).isEqualTo(PORT);
    assertThat(inBoundfilterChainMatch.getPrefixRanges()).containsExactly(
        new EnvoyServerProtoData.CidrRange(LOCAL_IP, 32));
    CommonTlsContext downstreamCommonTlsContext =
        filterChainInboundInListenerUpdate.getDownstreamTlsContext().getCommonTlsContext();
    assertThat(downstreamCommonTlsContext.getTlsCertificateSdsSecretConfigs(0).getName())
        .isEqualTo("google-sds-config-default");
    assertThat(
        downstreamCommonTlsContext
            .getCombinedValidationContext()
            .getValidationContextSdsSecretConfig()
            .getName())
        .isEqualTo("ROOTCA");
    assertThat(fakeClock.getPendingTasks(LISTENER_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  /** Client receives LDS responses for updating Listener previously received. */
  @Test
  public void notifyUpdatedListener() throws InvalidProtocolBufferException {
    xdsClient.watchListenerData(PORT, listenerWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request with null in lds resource name
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "",
            ResourceType.LDS.typeUrlV2(), "")));
    assertThat(fakeClock.getPendingTasks(LISTENER_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    final FilterChain filterChainOutbound = buildFilterChain(buildFilterChainMatch(8000), null);
    final FilterChain filterChainInbound = buildFilterChain(buildFilterChainMatch(PORT,
        CidrRange.newBuilder().setAddressPrefix(LOCAL_IP)
            .setPrefixLen(UInt32Value.of(32)).build()),
        // Server is still speaking xds v2.
        CommonTlsContextTestsUtil.buildTestDownstreamTlsContextV2("google-sds-config-default",
            "ROOTCA"),
        buildTestFilter("envoy.http_connection_manager"));
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListenerV2("bar.googleapis.com",
            Any.pack(HttpConnectionManager.newBuilder()
                .setRouteConfig(
                    buildRouteConfigurationV2("route-bar.googleapis.com",
                        ImmutableList.of(
                            buildVirtualHostV2(
                                ImmutableList.of("bar.googleapis.com"),
                                "cluster-bar.googleapis.com"))))
                .build()))),
        Any.pack(buildListenerWithFilterChain(LISTENER_NAME, 15001, "0.0.0.0",
            filterChainOutbound,
            filterChainInbound
        )));
    DiscoveryResponse response =
        buildDiscoveryResponseV2("0", listeners, ResourceType.LDS.typeUrlV2(), "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "0",
            ResourceType.LDS.typeUrlV2(), "0000")));

    ArgumentCaptor<ListenerUpdate> listenerUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(listenerWatcher, times(1)).onListenerChanged(listenerUpdateCaptor.capture());

    // Management sends back another LDS response containing updates for the requested Listener.
    final FilterChain filterChainNewInbound = buildFilterChain(buildFilterChainMatch(PORT,
        CidrRange.newBuilder().setAddressPrefix(LOCAL_IP)
            .setPrefixLen(UInt32Value.of(32)).build()),
        CommonTlsContextTestsUtil.buildTestDownstreamTlsContextV2("google-sds-config-default1",
            "ROOTCA2"),
        buildTestFilter("envoy.http_connection_manager"));
    List<Any> listeners1 = ImmutableList.of(
        Any.pack(buildListenerWithFilterChain(LISTENER_NAME, 15001, "0.0.0.0",
            filterChainNewInbound
        )));
    DiscoveryResponse response1 =
        buildDiscoveryResponseV2("1", listeners1, ResourceType.LDS.typeUrlV2(), "0001");
    responseObserver.onNext(response1);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "1",
            ResourceType.LDS.typeUrlV2(), "0001")));

    // Updated listener is notified to config watcher.
    listenerUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(listenerWatcher, times(2)).onListenerChanged(listenerUpdateCaptor.capture());
    ListenerUpdate configUpdate = listenerUpdateCaptor.getValue();
    EnvoyServerProtoData.Listener listener = configUpdate.getListener();
    assertThat(listener.getName()).isEqualTo(LISTENER_NAME);
    assertThat(listener.getFilterChains()).hasSize(1);
    EnvoyServerProtoData.FilterChain filterChain =
        Iterables.getOnlyElement(listener.getFilterChains());
    EnvoyServerProtoData.FilterChainMatch filterChainMatch = filterChain.getFilterChainMatch();
    assertThat(filterChainMatch.getDestinationPort()).isEqualTo(PORT);
    assertThat(filterChainMatch.getPrefixRanges()).containsExactly(
        new EnvoyServerProtoData.CidrRange(LOCAL_IP, 32));
    CommonTlsContext downstreamCommonTlsContext =
        filterChain.getDownstreamTlsContext().getCommonTlsContext();
    assertThat(downstreamCommonTlsContext.getTlsCertificateSdsSecretConfigs(0).getName())
        .isEqualTo("google-sds-config-default1");
    assertThat(
        downstreamCommonTlsContext
            .getCombinedValidationContext()
            .getValidationContextSdsSecretConfig()
            .getName())
        .isEqualTo("ROOTCA2");
  }

  /**
   * Client receives LDS response containing matching name but non-matching IP address. Test
   * disabled until IP matching logic implemented.
   */
  @Ignore
  @Test
  public void ldsResponse_nonMatchingIpAddress() {
    xdsClient.watchListenerData(PORT, listenerWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request with null in lds resource name
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "",
            ResourceType.LDS.typeUrlV2(), "")));
    assertThat(fakeClock.getPendingTasks(LISTENER_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    final FilterChain filterChainInbound = buildFilterChain(buildFilterChainMatch(8000), null);
    final FilterChain filterChainOutbound = buildFilterChain(buildFilterChainMatch(PORT,
        CidrRange.newBuilder().setAddressPrefix(DIFFERENT_IP)
            .setPrefixLen(UInt32Value.of(32)).build()),
        // Server is still speaking xds v2.
        CommonTlsContextTestsUtil.buildTestDownstreamTlsContextV2("google-sds-config-default",
            "ROOTCA"),
        buildTestFilter("envoy.http_connection_manager"));
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListenerV2("bar.googleapis.com",
            Any.pack(HttpConnectionManager.newBuilder()
                .setRouteConfig(
                    buildRouteConfigurationV2("route-bar.googleapis.com",
                        ImmutableList.of(
                            buildVirtualHostV2(
                                ImmutableList.of("bar.googleapis.com"),
                                "cluster-bar.googleapis.com"))))
                .build()))),
        Any.pack(buildListenerWithFilterChain(LISTENER_NAME, 15001, "0.0.0.0",
            filterChainInbound,
            filterChainOutbound
        )));
    DiscoveryResponse response =
        buildDiscoveryResponseV2("0", listeners, ResourceType.LDS.typeUrlV2(), "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "0",
            ResourceType.LDS.typeUrlV2(), "0000")));

    verify(listenerWatcher, never()).onError(any(Status.class));
    verify(listenerWatcher, never()).onListenerChanged(any(ListenerUpdate.class));
  }

  /** Client receives LDS response containing non-matching port in the filterMatch. */
  @Test
  public void ldsResponse_nonMatchingPort() {
    xdsClient.watchListenerData(PORT, listenerWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request with null in lds resource name
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "",
            ResourceType.LDS.typeUrlV2(), "")));
    assertThat(fakeClock.getPendingTasks(LISTENER_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    final FilterChain filterChainInbound = buildFilterChain(buildFilterChainMatch(8000), null);
    final FilterChain filterChainOutbound = buildFilterChain(buildFilterChainMatch(
        PORT + 1,  // add 1 to mismatch
        CidrRange.newBuilder().setAddressPrefix(LOCAL_IP)
            .setPrefixLen(UInt32Value.of(32)).build()),
        // Server is still speaking xds v2.
        CommonTlsContextTestsUtil.buildTestDownstreamTlsContextV2("google-sds-config-default",
            "ROOTCA"),
        buildTestFilter("envoy.http_connection_manager"));
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListenerV2("bar.googleapis.com",
            Any.pack(HttpConnectionManager.newBuilder()
                .setRouteConfig(
                    buildRouteConfigurationV2("route-bar.googleapis.com",
                        ImmutableList.of(
                            buildVirtualHostV2(
                                ImmutableList.of("bar.googleapis.com"),
                                "cluster-bar.googleapis.com"))))
                .build()))),
        Any.pack(buildListenerWithFilterChain(LISTENER_NAME, PORT, "0.0.0.0",
            filterChainInbound,
            filterChainOutbound
        )));
    DiscoveryResponse response =
        buildDiscoveryResponseV2("0", listeners, ResourceType.LDS.typeUrlV2(), "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "0",
            ResourceType.LDS.typeUrlV2(), "0000")));

    verify(listenerWatcher, never()).onListenerChanged(any(ListenerUpdate.class));
    verify(listenerWatcher, never()).onResourceDoesNotExist(":" + PORT);
    verify(listenerWatcher, never()).onError(any(Status.class));
    fakeClock.forwardTime(ServerXdsClient.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(listenerWatcher).onResourceDoesNotExist(":" + PORT);
    assertThat(fakeClock.getPendingTasks(LISTENER_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  /**
   * RPC stream close and retry while there is listener watcher registered.
   */
  @Test
  public void streamClosedAndRetry() {
    InOrder inOrder =
        Mockito.inOrder(mockedDiscoveryService, backoffPolicyProvider, backoffPolicy1,
            backoffPolicy2);
    xdsClient.watchListenerData(PORT, listenerWatcher);

    ArgumentCaptor<StreamObserver<DiscoveryResponse>> responseObserverCaptor =
        ArgumentCaptor.forClass(null);
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    StreamObserver<DiscoveryResponse> responseObserver =
        responseObserverCaptor.getValue();  // same as responseObservers.poll()
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "",
            ResourceType.LDS.typeUrlV2(), "")));
    verifyNoMoreInteractions(requestObserver);

    final FilterChain filterChainOutbound = buildFilterChain(buildFilterChainMatch(8000), null);
    final FilterChain filterChainInbound = buildFilterChain(buildFilterChainMatch(PORT,
        CidrRange.newBuilder().setAddressPrefix(LOCAL_IP)
            .setPrefixLen(UInt32Value.of(32)).build()),
        // Server is still speaking xds v2.
        CommonTlsContextTestsUtil.buildTestDownstreamTlsContextV2("google-sds-config-default",
            "ROOTCA"),
        buildTestFilter("envoy.http_connection_manager"));
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListenerWithFilterChain(LISTENER_NAME, 15001, "0.0.0.0",
            filterChainOutbound,
            filterChainInbound
        )));
    DiscoveryResponse response =
        buildDiscoveryResponseV2("0", listeners, ResourceType.LDS.typeUrlV2(), "0000");
    responseObserver.onNext(response);

    // Client sent an ACK CDS request (Omitted).

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(null);

    // Management server closes the RPC stream with an error.
    responseObserver.onError(Status.UNKNOWN.asException());
    verify(listenerWatcher).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.UNKNOWN);

    // Resets backoff and retry immediately.
    inOrder.verify(backoffPolicyProvider).get();
    fakeClock.runDueTasks();
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    responseObserver = responseObserverCaptor.getValue();
    requestObserver = requestObservers.poll();

    // Retry resumes requests for all wanted resources.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "0",
            ResourceType.LDS.typeUrlV2(), "")));
    verifyNoMoreInteractions(requestObserver);

    // Management server becomes unreachable.
    responseObserver.onError(Status.UNAVAILABLE.asException());
    verify(listenerWatcher, times(2)).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    assertThat(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER)).hasSize(1);

    // Retry after backoff.
    fakeClock.forwardNanos(9L);
    assertThat(requestObservers).isEmpty();
    fakeClock.forwardNanos(1L);
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    responseObserver = responseObserverCaptor.getValue();
    requestObserver = requestObservers.poll();
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "0",
            ResourceType.LDS.typeUrlV2(), "")));
    verifyNoMoreInteractions(requestObserver);

    // Management server is still not reachable.
    responseObserver.onError(Status.UNAVAILABLE.asException());
    verify(listenerWatcher, times(3)).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    assertThat(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER)).hasSize(1);

    // Retry after backoff.
    fakeClock.forwardNanos(99L);
    assertThat(requestObservers).isEmpty();
    fakeClock.forwardNanos(1L);
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    responseObserver = responseObserverCaptor.getValue();
    requestObserver = requestObservers.poll();
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "0",
            ResourceType.LDS.typeUrlV2(), "")));
    verifyNoMoreInteractions(requestObserver);

    // Management server sends back a LDS response.
    response = buildDiscoveryResponseV2("1", listeners,
        ResourceType.LDS.typeUrlV2(), "0001");
    responseObserver.onNext(response);

    // Client sent an LDS ACK request (Omitted).

    // Management server closes the RPC stream.
    responseObserver.onCompleted();
    verify(listenerWatcher, times(4)).onError(any(Status.class));

    // Resets backoff and retry immediately
    inOrder.verify(backoffPolicyProvider).get();
    fakeClock.runDueTasks();
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    responseObserver = responseObserverCaptor.getValue();
    requestObserver = requestObservers.poll();

    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "1",
            ResourceType.LDS.typeUrlV2(), "")));
    verifyNoMoreInteractions(requestObserver);

    // Management server becomes unreachable again.
    responseObserver.onError(Status.UNAVAILABLE.asException());
    verify(listenerWatcher, times(5)).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    inOrder.verify(backoffPolicy2).nextBackoffNanos();
    assertThat(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER)).hasSize(1);

    // Retry after backoff.
    fakeClock.forwardNanos(19L);
    assertThat(requestObservers).isEmpty();
    fakeClock.forwardNanos(1L);
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    requestObserver = requestObservers.poll();
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "1",
            ResourceType.LDS.typeUrlV2(), "")));

    verifyNoMoreInteractions(mockedDiscoveryService, backoffPolicyProvider, backoffPolicy1,
        backoffPolicy2, requestObserver);
  }

  static Listener buildListenerWithFilterChain(String name, int portValue, String address,
      FilterChain... filterChains) {
    io.envoyproxy.envoy.api.v2.core.Address listenerAddress =
        io.envoyproxy.envoy.api.v2.core.Address.newBuilder()
            .setSocketAddress(
                SocketAddress.newBuilder().setPortValue(portValue).setAddress(address))
            .build();
    return
        Listener.newBuilder()
            .setName(name)
            .setAddress(listenerAddress)
            .addAllFilterChains(Arrays.asList(filterChains))
            .build();
  }

  @SuppressWarnings("deprecation")
  static FilterChain buildFilterChain(FilterChainMatch filterChainMatch,
      DownstreamTlsContext tlsContext, Filter...filters) {
    return FilterChain.newBuilder()
        .setFilterChainMatch(filterChainMatch)
        .setTransportSocket(
            tlsContext == null
                ? TransportSocket.getDefaultInstance()
                : TransportSocket.newBuilder()
                    .setName("envoy.transport_sockets.tls")
                    .setTypedConfig(Any.pack(tlsContext))
                    .build())
        .addAllFilters(Arrays.asList(filters))
        .build();
  }

  static FilterChainMatch buildFilterChainMatch(int destPort, CidrRange...prefixRanges) {
    return
        FilterChainMatch.newBuilder()
            .setDestinationPort(UInt32Value.of(destPort))
            .addAllPrefixRanges(Arrays.asList(prefixRanges))
            .build();
  }

  static Filter buildTestFilter(String name) {
    return
        Filter.newBuilder()
            .setName(name)
            .setTypedConfig(
                Any.newBuilder()
                    .setTypeUrl(TYPE_URL_HCM))
            .build();
  }
}
