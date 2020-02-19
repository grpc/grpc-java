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
import static io.grpc.xds.XdsClientTestHelper.buildDiscoveryRequest;
import static io.grpc.xds.XdsClientTestHelper.buildDiscoveryResponse;
import static io.grpc.xds.XdsClientTestHelper.buildListener;
import static io.grpc.xds.XdsClientTestHelper.buildRouteConfiguration;
import static io.grpc.xds.XdsClientTestHelper.buildVirtualHost;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Any;
import com.google.protobuf.Struct;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.Value;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.Listener;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.core.Address;
import io.envoyproxy.envoy.api.v2.core.CidrRange;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.api.v2.core.SocketAddress;
import io.envoyproxy.envoy.api.v2.listener.Filter;
import io.envoyproxy.envoy.api.v2.listener.FilterChain;
import io.envoyproxy.envoy.api.v2.listener.FilterChainMatch;
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManager;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.grpc.Context;
import io.grpc.Context.CancellationListener;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.internal.FakeClock.TaskFilter;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.Bootstrapper.ChannelCreds;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.XdsClient.ConfigUpdate;
import io.grpc.xds.XdsClient.ConfigWatcher;
import io.grpc.xds.XdsClient.XdsChannelFactory;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link XdsClientImplForListener}.
 */
@RunWith(JUnit4.class)
public class XdsClientImplForListenerTest {

  private static final String HOSTNAME = "foo.googleapis.com";
  private static final int PORT = 7000;
  private static final String LOCAL_IP = "192.168.3.5";
  private static final String DIFFERENT_IP = "192.168.3.6";
  private static final String TYPE_URL_HCM =
      "type.googleapis.com/"
          + "envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManager";

  private static final Node NODE = Node.getDefaultInstance();
  private static final TaskFilter LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString()
              .contains(XdsClientImpl.LdsResourceFetchTimeoutTask.class.getSimpleName());
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
  private ConfigWatcher configWatcher;

  private ManagedChannel channel;
  private XdsClientImplForListener xdsClient;

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

    List<ServerInfo> servers =
        ImmutableList.of(new ServerInfo(serverName, ImmutableList.<ChannelCreds>of()));
    XdsChannelFactory channelFactory = new XdsChannelFactory() {
      @Override
      ManagedChannel createChannel(List<ServerInfo> servers) {
        assertThat(Iterables.getOnlyElement(servers).getServerUri()).isEqualTo(serverName);
        assertThat(Iterables.getOnlyElement(servers).getChannelCredentials()).isEmpty();
        return channel;
      }
    };

    xdsClient =
        new XdsClientImplForListener(servers, channelFactory, NODE, syncContext,
            fakeClock.getScheduledExecutorService(), backoffPolicyProvider,
            fakeClock.getStopwatchSupplier(), LOCAL_IP);
    // Only the connection to management server is established, no RPC request is sent until at
    // least one watcher is registered.
    assertThat(responseObservers).isEmpty();
    assertThat(requestObservers).isEmpty();
  }

  @After
  public void tearDown() {
    xdsClient.shutdown();
    assertThat(callEnded.get()).isTrue();
    assertThat(channel.isShutdown()).isTrue();
    assertThat(fakeClock.getPendingTasks()).isEmpty();
  }

  private Node getNodeToVerify() {
    Struct newMetadata = NODE.getMetadata().toBuilder()
        .putFields("INSTANCE_IP",
            Value.newBuilder().setStringValue(LOCAL_IP).build())
        .putFields("TRAFFICDIRECTOR_INTERCEPTION_PORT",
            Value.newBuilder().setStringValue("15001").build())
        .putFields("TRAFFICDIRECTOR_INBOUND_BACKEND_PORTS",
            Value.newBuilder().setStringValue("" + PORT).build())
        .build();
    return NODE.toBuilder().setMetadata(newMetadata).build();
  }

  /**
   * Client receives an LDS response that contains listener with matching name
   * (i.e. "TRAFFICDIRECTOR_INTERCEPTION_LISTENER") but non matching filter.
   */
  @Test
  public void ldsResponse_nonMatchingFilterChain_notFoundError() {
    xdsClient.watchConfigData(HOSTNAME, PORT, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request with null in lds resource name
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "",
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener("bar.googleapis.com",
            Any.pack(HttpConnectionManager.newBuilder()
                .setRouteConfig(
                    buildRouteConfiguration("route-bar.googleapis.com",
                        ImmutableList.of(
                            buildVirtualHost(
                                ImmutableList.of("bar.googleapis.com"),
                                "cluster-bar.googleapis.com"))))
                .build()))),
        Any.pack(buildListener("TRAFFICDIRECTOR_INTERCEPTION_LISTENER",
            Any.pack(HttpConnectionManager.newBuilder()
                .setRouteConfig(
                    buildRouteConfiguration("route-baz.googleapis.com",
                        ImmutableList.of(
                            buildVirtualHost(
                                ImmutableList.of("baz.googleapis.com"),
                                "cluster-baz.googleapis.com"))))
                .build()))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "0",
            XdsClientImpl.ADS_TYPE_URL_LDS, "0000")));

    verify(configWatcher, never()).onConfigChanged(any(ConfigUpdate.class));
    verify(configWatcher, never()).onError(any(Status.class));
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    ArgumentCaptor<Status> errorStatusCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher).onError(errorStatusCaptor.capture());
    Status error = errorStatusCaptor.getValue();
    assertThat(error.getCode()).isEqualTo(Code.NOT_FOUND);
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  /**
   * Client receives an LDS response that contains requested listener where the name matches and
   * filterChain also matches.
   */
  @Test
  public void ldsResponseWith_matchingListenerFound() {
    xdsClient.watchConfigData(HOSTNAME, PORT, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request with null in lds resource name
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "",
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    final FilterChain filterChainOutbound = buildFilterChain(buildFilterChainMatch(8000), null);
    final FilterChain filterChainInbound = buildFilterChain(buildFilterChainMatch(PORT,
        CidrRange.newBuilder().setAddressPrefix(LOCAL_IP)
            .setPrefixLen(UInt32Value.of(32)).build()),
        CommonTlsContextTestsUtil.buildTestDownstreamTlsContext("google-sds-config-default",
            "ROOTCA"),
        buildTestFilter("envoy.http_connection_manager"));
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener("bar.googleapis.com",
            Any.pack(HttpConnectionManager.newBuilder()
                .setRouteConfig(
                    buildRouteConfiguration("route-bar.googleapis.com",
                        ImmutableList.of(
                            buildVirtualHost(
                                ImmutableList.of("bar.googleapis.com"),
                                "cluster-bar.googleapis.com"))))
                .build()))),
        Any.pack(buildListenerWithFilterChain("TRAFFICDIRECTOR_INTERCEPTION_LISTENER",
            filterChainOutbound,
            filterChainInbound
        )));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "0",
            XdsClientImpl.ADS_TYPE_URL_LDS, "0000")));

    verify(configWatcher, never()).onError(any(Status.class));
    ArgumentCaptor<ConfigUpdate> configUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher, times(1)).onConfigChanged(configUpdateCaptor.capture());
    ConfigUpdate configUpdate = configUpdateCaptor.getValue();
    assertThat(configUpdate.getClusterName()).isNull();
    EnvoyServerProtoData.Listener listener = configUpdate.getListener();
    assertThat(listener.getName()).isEqualTo("TRAFFICDIRECTOR_INTERCEPTION_LISTENER");
    assertThat(listener.getAddress()).isEqualTo("0.0.0.0:15001");
    EnvoyServerProtoData.FilterChain[] expected = new EnvoyServerProtoData.FilterChain[] {
        EnvoyServerProtoData.FilterChain.fromEnvoyProtoFilterChain(filterChainOutbound),
        EnvoyServerProtoData.FilterChain.fromEnvoyProtoFilterChain(filterChainInbound)
    };
    assertThat(listener.getFilterChains()).isEqualTo(Arrays.asList(expected));
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  /** Client receives LDS responses for updating Listener previously received. */
  @Test
  public void notifyUpdatedListener() {
    xdsClient.watchConfigData(HOSTNAME, PORT, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request with null in lds resource name
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "",
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    final FilterChain filterChainOutbound = buildFilterChain(buildFilterChainMatch(8000), null);
    final FilterChain filterChainInbound = buildFilterChain(buildFilterChainMatch(PORT,
        CidrRange.newBuilder().setAddressPrefix(LOCAL_IP)
            .setPrefixLen(UInt32Value.of(32)).build()),
        CommonTlsContextTestsUtil.buildTestDownstreamTlsContext("google-sds-config-default",
            "ROOTCA"),
        buildTestFilter("envoy.http_connection_manager"));
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener("bar.googleapis.com",
            Any.pack(HttpConnectionManager.newBuilder()
                .setRouteConfig(
                    buildRouteConfiguration("route-bar.googleapis.com",
                        ImmutableList.of(
                            buildVirtualHost(
                                ImmutableList.of("bar.googleapis.com"),
                                "cluster-bar.googleapis.com"))))
                .build()))),
        Any.pack(buildListenerWithFilterChain("TRAFFICDIRECTOR_INTERCEPTION_LISTENER",
            filterChainOutbound,
            filterChainInbound
        )));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "0",
            XdsClientImpl.ADS_TYPE_URL_LDS, "0000")));

    verify(configWatcher, never()).onError(any(Status.class));
    ArgumentCaptor<ConfigUpdate> configUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher, times(1)).onConfigChanged(configUpdateCaptor.capture());

    // Management sends back another LDS response containing updates for the requested Listener.
    final FilterChain filterChainNewInbound = buildFilterChain(buildFilterChainMatch(PORT,
        CidrRange.newBuilder().setAddressPrefix(LOCAL_IP)
            .setPrefixLen(UInt32Value.of(32)).build()),
        CommonTlsContextTestsUtil.buildTestDownstreamTlsContext("google-sds-config-default1",
            "ROOTCA2"),
        buildTestFilter("envoy.http_connection_manager"));
    List<Any> listeners1 = ImmutableList.of(
        Any.pack(buildListenerWithFilterChain("TRAFFICDIRECTOR_INTERCEPTION_LISTENER",
            filterChainNewInbound
        )));
    DiscoveryResponse response1 =
        buildDiscoveryResponse("1", listeners1, XdsClientImpl.ADS_TYPE_URL_LDS, "0001");
    responseObserver.onNext(response1);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "1",
            XdsClientImpl.ADS_TYPE_URL_LDS, "0001")));

    // Updated listener is notified to config watcher.
    configUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher, times(2)).onConfigChanged(configUpdateCaptor.capture());
    ConfigUpdate configUpdate = configUpdateCaptor.getValue();
    EnvoyServerProtoData.Listener listener = configUpdate.getListener();
    assertThat(listener.getName()).isEqualTo("TRAFFICDIRECTOR_INTERCEPTION_LISTENER");
    EnvoyServerProtoData.FilterChain[] expected = new EnvoyServerProtoData.FilterChain[] {
        EnvoyServerProtoData.FilterChain.fromEnvoyProtoFilterChain(filterChainNewInbound)
    };
    assertThat(listener.getFilterChains()).isEqualTo(Arrays.asList(expected));
  }

  /** Client receives LDS response containing matching name but non-matching IP address. */
  @Test
  public void ldsResponse_nonMatchingIpAddress() {
    xdsClient.watchConfigData(HOSTNAME, PORT, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request with null in lds resource name
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "",
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    final FilterChain filterChainInbound = buildFilterChain(buildFilterChainMatch(8000), null);
    final FilterChain filterChainOutbound = buildFilterChain(buildFilterChainMatch(PORT,
        CidrRange.newBuilder().setAddressPrefix(DIFFERENT_IP)
            .setPrefixLen(UInt32Value.of(32)).build()),
        CommonTlsContextTestsUtil.buildTestDownstreamTlsContext("google-sds-config-default",
            "ROOTCA"),
        buildTestFilter("envoy.http_connection_manager"));
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener("bar.googleapis.com",
            Any.pack(HttpConnectionManager.newBuilder()
                .setRouteConfig(
                    buildRouteConfiguration("route-bar.googleapis.com",
                        ImmutableList.of(
                            buildVirtualHost(
                                ImmutableList.of("bar.googleapis.com"),
                                "cluster-bar.googleapis.com"))))
                .build()))),
        Any.pack(buildListenerWithFilterChain("TRAFFICDIRECTOR_INTERCEPTION_LISTENER",
            filterChainInbound,
            filterChainOutbound
        )));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "0",
            XdsClientImpl.ADS_TYPE_URL_LDS, "0000")));

    verify(configWatcher, never()).onError(any(Status.class));
    verify(configWatcher, never()).onConfigChanged(any(ConfigUpdate.class));
  }

  /** Client receives LDS response containing matching name but non-matching port. */
  @Test
  public void ldsResponse_nonMatchingPort() {
    xdsClient.watchConfigData(HOSTNAME, PORT, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request with null in lds resource name
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "",
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    final FilterChain filterChainInbound = buildFilterChain(buildFilterChainMatch(8000), null);
    final FilterChain filterChainOutbound = buildFilterChain(buildFilterChainMatch(
        PORT + 1,  // add 1 to mismatch
        CidrRange.newBuilder().setAddressPrefix(LOCAL_IP)
            .setPrefixLen(UInt32Value.of(32)).build()),
        CommonTlsContextTestsUtil.buildTestDownstreamTlsContext("google-sds-config-default",
            "ROOTCA"),
        buildTestFilter("envoy.http_connection_manager"));
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener("bar.googleapis.com",
            Any.pack(HttpConnectionManager.newBuilder()
                .setRouteConfig(
                    buildRouteConfiguration("route-bar.googleapis.com",
                        ImmutableList.of(
                            buildVirtualHost(
                                ImmutableList.of("bar.googleapis.com"),
                                "cluster-bar.googleapis.com"))))
                .build()))),
        Any.pack(buildListenerWithFilterChain("TRAFFICDIRECTOR_INTERCEPTION_LISTENER",
            filterChainInbound,
            filterChainOutbound
        )));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(getNodeToVerify(), "0",
            XdsClientImpl.ADS_TYPE_URL_LDS, "0000")));

    verify(configWatcher, never()).onError(any(Status.class));
    verify(configWatcher, never()).onConfigChanged(any(ConfigUpdate.class));
  }

  static Listener buildListenerWithFilterChain(String name, FilterChain ...filterChains) {
    Address listenerAddress = Address.newBuilder()
        .setSocketAddress(SocketAddress.newBuilder()
            .setPortValue(15001).setAddress("0.0.0.0"))
        .build();
    return
        Listener.newBuilder()
            .setName(name)
            .setAddress(listenerAddress)
            .addAllFilterChains(Arrays.asList(filterChains))
            .build();
  }

  static FilterChain buildFilterChain(FilterChainMatch filterChainMatch,
      DownstreamTlsContext tlsContext, Filter ...filters) {
    return
        FilterChain.newBuilder()
            .setFilterChainMatch(filterChainMatch)
            .setTlsContext(tlsContext == null
                ? DownstreamTlsContext.getDefaultInstance() : tlsContext)
            .addAllFilters(Arrays.asList(filters))
            .build();
  }

  static FilterChainMatch buildFilterChainMatch(int destPort, CidrRange ...prefixRanges) {
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
