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
import static io.grpc.xds.XdsClientTestHelper.buildCluster;
import static io.grpc.xds.XdsClientTestHelper.buildClusterLoadAssignment;
import static io.grpc.xds.XdsClientTestHelper.buildDiscoveryRequest;
import static io.grpc.xds.XdsClientTestHelper.buildDiscoveryResponse;
import static io.grpc.xds.XdsClientTestHelper.buildDropOverload;
import static io.grpc.xds.XdsClientTestHelper.buildLbEndpoint;
import static io.grpc.xds.XdsClientTestHelper.buildListener;
import static io.grpc.xds.XdsClientTestHelper.buildLocalityLbEndpoints;
import static io.grpc.xds.XdsClientTestHelper.buildRouteConfiguration;
import static io.grpc.xds.XdsClientTestHelper.buildSecureCluster;
import static io.grpc.xds.XdsClientTestHelper.buildUpstreamTlsContext;
import static io.grpc.xds.XdsClientTestHelper.buildVirtualHost;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.util.Durations;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.HealthStatus;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterStats;
import io.envoyproxy.envoy.config.route.v3.QueryParameterMatcher;
import io.envoyproxy.envoy.config.route.v3.RedirectAction;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.config.route.v3.WeightedCluster;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.envoyproxy.envoy.service.load_stats.v3.LoadReportingServiceGrpc.LoadReportingServiceImplBase;
import io.envoyproxy.envoy.service.load_stats.v3.LoadStatsRequest;
import io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse;
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
import io.grpc.internal.FakeClock.ScheduledTask;
import io.grpc.internal.FakeClock.TaskFilter;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.LbEndpoint;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.XdsClient.CdsResourceWatcher;
import io.grpc.xds.XdsClient.CdsUpdate;
import io.grpc.xds.XdsClient.ConfigUpdate;
import io.grpc.xds.XdsClient.ConfigWatcher;
import io.grpc.xds.XdsClient.EdsResourceWatcher;
import io.grpc.xds.XdsClient.EdsUpdate;
import io.grpc.xds.XdsClient.XdsChannel;
import io.grpc.xds.XdsClientImpl.MessagePrinter;
import io.grpc.xds.XdsClientImpl.ResourceType;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link XdsClientImpl} with xDS v3 protocol. However, the test xDS server still sends
 * update with v2 resources for testing compatibility.
 */
@RunWith(JUnit4.class)
public class XdsClientImplTest {

  private static final String TARGET_AUTHORITY = "foo.googleapis.com:8080";

  private static final Node NODE = Node.newBuilder().build();
  private static final FakeClock.TaskFilter RPC_RETRY_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(XdsClientImpl.RpcRetryTask.class.getSimpleName());
        }
      };

  private static final FakeClock.TaskFilter LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString()
              .contains(XdsClientImpl.LdsResourceFetchTimeoutTask.class.getSimpleName());
        }
      };

  private static final FakeClock.TaskFilter RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString()
              .contains(XdsClientImpl.RdsResourceFetchTimeoutTask.class.getSimpleName());
        }
      };

  private static final FakeClock.TaskFilter CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(ResourceType.CDS.toString());
        }
      };

  private static final FakeClock.TaskFilter EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(ResourceType.EDS.toString());
        }
      };

  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();
  @SuppressWarnings("deprecation") // https://github.com/grpc/grpc-java/issues/7467
  @Rule
  public ExpectedException thrown = ExpectedException.none();

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
  private final AtomicBoolean adsEnded = new AtomicBoolean(true);
  private final Queue<LoadReportCall> loadReportCalls = new ArrayDeque<>();
  private final AtomicBoolean lrsEnded = new AtomicBoolean(true);

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
  @Mock
  private CdsResourceWatcher cdsResourceWatcher;
  @Mock
  private EdsResourceWatcher edsResourceWatcher;

  private ManagedChannel channel;
  private XdsClientImpl xdsClient;

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
        assertThat(adsEnded.get()).isTrue();  // ensure previous call was ended
        adsEnded.set(false);
        Context.current().addListener(
            new CancellationListener() {
              @Override
              public void cancelled(Context context) {
                adsEnded.set(true);
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

    LoadReportingServiceImplBase lrsServiceImpl = new LoadReportingServiceImplBase() {
      @Override
      public StreamObserver<LoadStatsRequest> streamLoadStats(
          StreamObserver<LoadStatsResponse> responseObserver) {
        assertThat(lrsEnded.get()).isTrue();
        lrsEnded.set(false);
        @SuppressWarnings("unchecked")
        StreamObserver<LoadStatsRequest> requestObserver = mock(StreamObserver.class);
        final LoadReportCall call = new LoadReportCall(requestObserver, responseObserver);
        Context.current().addListener(
            new CancellationListener() {
              @Override
              public void cancelled(Context context) {
                lrsEnded.set(true);
              }
            }, MoreExecutors.directExecutor());
        loadReportCalls.offer(call);
        return requestObserver;
      }
    };

    cleanupRule.register(
        InProcessServerBuilder
            .forName(serverName)
            .addService(mockedDiscoveryService)
            .addService(lrsServiceImpl)
            .directExecutor()
            .build()
            .start());
    channel =
        cleanupRule.register(InProcessChannelBuilder.forName(serverName).directExecutor().build());

    xdsClient =
        new XdsClientImpl(
            TARGET_AUTHORITY,
            new XdsChannel(channel, /* useProtocolV3= */ true),
            EnvoyProtoData.Node.newBuilder().build(),
            syncContext,
            fakeClock.getScheduledExecutorService(),
            backoffPolicyProvider,
            fakeClock.getStopwatchSupplier());
    // Only the connection to management server is established, no RPC request is sent until at
    // least one watcher is registered.
    assertThat(responseObservers).isEmpty();
    assertThat(requestObservers).isEmpty();
  }

  @After
  public void tearDown() {
    xdsClient.shutdown();
    assertThat(adsEnded.get()).isTrue();
    assertThat(lrsEnded.get()).isTrue();
    assertThat(channel.isShutdown()).isTrue();
    assertThat(fakeClock.getPendingTasks()).isEmpty();
  }

  // Always test the real workflow and integrity of XdsClient: RDS protocol should always followed
  // after at least one LDS request-response, from which the RDS resource name comes. CDS and EDS
  // can be tested separately as they are used in a standalone way.

  // Discovery responses should follow management server spec and xDS protocol. See
  // https://www.envoyproxy.io/docs/envoy/latest/api-docs/xds_protocol.

  /**
   * Client receives an LDS response that does not contain a Listener for the requested resource.
   * The LDS response is ACKed.
   * The config watcher is notified with resource unavailable after its response timer expires.
   */
  @Test
  public void ldsResponseWithoutMatchingResource() {
    xdsClient.watchConfigData(TARGET_AUTHORITY, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request for the host name (with port) to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", TARGET_AUTHORITY,
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
        Any.pack(buildListener("baz.googleapis.com",
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
        .onNext(eq(buildDiscoveryRequest(NODE, "0", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "0000")));

    verify(configWatcher, never()).onConfigChanged(any(ConfigUpdate.class));
    verify(configWatcher, never()).onResourceDoesNotExist(TARGET_AUTHORITY);
    verify(configWatcher, never()).onError(any(Status.class));
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(configWatcher).onResourceDoesNotExist(TARGET_AUTHORITY);
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  /**
   * An LDS response contains the requested listener and an in-lined RouteConfiguration message for
   * that listener. But the RouteConfiguration message is invalid as it does not contain any
   * VirtualHost with domains matching the requested hostname.
   * The LDS response is NACKed, as if the XdsClient has not received this response.
   * The config watcher is notified with an error after its response timer expires..
   */
  @Test
  public void failToFindVirtualHostInLdsResponseInLineRouteConfig() {
    xdsClient.watchConfigData(TARGET_AUTHORITY, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request for the host name (with port) to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    io.envoyproxy.envoy.config.route.v3.RouteConfiguration routeConfig =
        buildRouteConfiguration(
            "route.googleapis.com",
            ImmutableList.of(
                buildVirtualHost(ImmutableList.of("something does not match"),
                    "some cluster"),
                buildVirtualHost(ImmutableList.of("something else does not match"),
                    "some other cluster")));

    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(TARGET_AUTHORITY, /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRouteConfig(routeConfig).build()))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    // Client sends an NACK LDS request.
    verify(requestObserver)
        .onNext(
            argThat(new DiscoveryRequestMatcher("", TARGET_AUTHORITY,
                XdsClientImpl.ADS_TYPE_URL_LDS, "0000")));

    verify(configWatcher, never()).onConfigChanged(any(ConfigUpdate.class));
    verify(configWatcher, never()).onResourceDoesNotExist(TARGET_AUTHORITY);
    verify(configWatcher, never()).onError(any(Status.class));

    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(configWatcher).onResourceDoesNotExist(TARGET_AUTHORITY);
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  /**
   * Client resolves the virtual host config from an LDS response that contains a
   * RouteConfiguration message directly in-line for the requested resource. No RDS is needed.
   * The LDS response is ACKed.
   * The config watcher is notified with an update.
   */
  @Test
  public void resolveVirtualHostInLdsResponse() {
    xdsClient.watchConfigData(TARGET_AUTHORITY, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request for the host name (with port) to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    ScheduledTask ldsRespTimer =
        Iterables.getOnlyElement(
            fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    assertThat(ldsRespTimer.isCancelled()).isFalse();

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
        Any.pack(buildListener("baz.googleapis.com",
            Any.pack(HttpConnectionManager.newBuilder()
                .setRouteConfig(
                    buildRouteConfiguration("route-baz.googleapis.com",
                        ImmutableList.of(
                            buildVirtualHost(
                                ImmutableList.of("baz.googleapis.com"),
                                "cluster-baz.googleapis.com"))))
                .build()))),
        Any.pack(buildListener(TARGET_AUTHORITY, /* matching resource */
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRouteConfig( // target route configuration
                        buildRouteConfiguration("route-foo.googleapis.com",
                            ImmutableList.of(
                                buildVirtualHost( // matching virtual host
                                    ImmutableList.of(TARGET_AUTHORITY, "bar.googleapis.com"),
                                    "cluster.googleapis.com"),
                                buildVirtualHost(
                                    ImmutableList.of("something does not match"),
                                    "some cluster"))))
                    .build()))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    assertThat(ldsRespTimer.isCancelled()).isTrue();

    // Client sends an ACK request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "0000")));

    ArgumentCaptor<ConfigUpdate> configUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher).onConfigChanged(configUpdateCaptor.capture());
    assertConfigUpdateContainsSingleClusterRoute(
        configUpdateCaptor.getValue(), "cluster.googleapis.com");

    verifyNoMoreInteractions(requestObserver);
  }

  /**
   * Client receives an RDS response (after a previous LDS request-response) that does not contain a
   * RouteConfiguration for the requested resource while each received RouteConfiguration is valid.
   * The RDS response is ACKed.
   * After the resource fetch timeout expires, watcher waiting for the resource is notified
   * with resource unavailable.
   */
  @Test
  public void rdsResponseWithoutMatchingResource() {
    xdsClient.watchConfigData(TARGET_AUTHORITY, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request for the host name (with port) to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));

    Rds rdsConfig =
        Rds.newBuilder()
            // Must set to use ADS.
            .setConfigSource(
                ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance()))
            .setRouteConfigName("route-foo.googleapis.com")
            .build();
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(TARGET_AUTHORITY, /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRds(rdsConfig).build())))
    );
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "0000")));

    // Client sends an (first) RDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "route-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_RDS, "")));

    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    // Management server should only sends RouteConfiguration messages with at least one
    // VirtualHost with domains matching requested hostname. Otherwise, it is invalid data.
    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(
            buildRouteConfiguration(
                "some resource name does not match route-foo.googleapis.com",
                ImmutableList.of(
                    buildVirtualHost(
                        ImmutableList.of(TARGET_AUTHORITY),
                        "whatever cluster")))),
        Any.pack(
            buildRouteConfiguration(
                "some other resource name does not match route-foo.googleapis.com",
                ImmutableList.of(
                    buildVirtualHost(
                        ImmutableList.of(TARGET_AUTHORITY),
                        "some more whatever cluster")))));
    response = buildDiscoveryResponse("0", routeConfigs, XdsClientImpl.ADS_TYPE_URL_RDS, "0000");
    responseObserver.onNext(response);

    // Client sends an ACK RDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "route-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_RDS, "0000")));

    verify(configWatcher, never()).onConfigChanged(any(ConfigUpdate.class));
    verify(configWatcher, never()).onResourceDoesNotExist(anyString());
    verify(configWatcher, never()).onError(any(Status.class));
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(configWatcher).onResourceDoesNotExist("route-foo.googleapis.com");
    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  /**
   * Client resolves the virtual host config from an RDS response for the requested resource. The
   * RDS response is ACKed.
   * The config watcher is notified with an update.
   */
  @Test
  public void resolveVirtualHostInRdsResponse() {
    xdsClient.watchConfigData(TARGET_AUTHORITY, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    Rds rdsConfig =
        Rds.newBuilder()
            // Must set to use ADS.
            .setConfigSource(
                ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance()))
            .setRouteConfigName("route-foo.googleapis.com")
            .build();

    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(TARGET_AUTHORITY, /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRds(rdsConfig).build())))
    );
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request and an RDS request for "route-foo.googleapis.com". (Omitted)

    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    // Management server should only sends RouteConfiguration messages with at least one
    // VirtualHost with domains matching requested hostname. Otherwise, it is invalid data.
    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(
            buildRouteConfiguration(
                "route-foo.googleapis.com", // target route configuration
                ImmutableList.of(
                    buildVirtualHost(ImmutableList.of("something does not match"),
                        "some cluster"),
                    buildVirtualHost(ImmutableList.of(TARGET_AUTHORITY, "bar.googleapis.com:443"),
                        "cluster.googleapis.com")))),  // matching virtual host
        Any.pack(
            buildRouteConfiguration(
                "some resource name does not match route-foo.googleapis.com",
                ImmutableList.of(
                    buildVirtualHost(ImmutableList.of("foo.googleapis.com"),
                        "some more cluster")))));
    response = buildDiscoveryResponse("0", routeConfigs, XdsClientImpl.ADS_TYPE_URL_RDS, "0000");
    responseObserver.onNext(response);

    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();

    // Client sent an ACK RDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "route-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_RDS, "0000")));

    ArgumentCaptor<ConfigUpdate> configUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher).onConfigChanged(configUpdateCaptor.capture());
    assertConfigUpdateContainsSingleClusterRoute(
        configUpdateCaptor.getValue(), "cluster.googleapis.com");
  }

  /**
   * Client resolves the virtual host config with path matching from an RDS response for the
   * requested resource. The RDS response is ACKed.
   * The config watcher is notified with an update.
   */
  @Test
  public void resolveVirtualHostWithPathMatchingInRdsResponse() {
    xdsClient.watchConfigData(TARGET_AUTHORITY, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    Rds rdsConfig =
        Rds.newBuilder()
            // Must set to use ADS.
            .setConfigSource(
                ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance()))
            .setRouteConfigName("route-foo.googleapis.com")
            .build();

    List<Any> listeners = ImmutableList.of(
            Any.pack(buildListener(TARGET_AUTHORITY, /* matching resource */
                    Any.pack(HttpConnectionManager.newBuilder().setRds(rdsConfig).build())))
    );
    DiscoveryResponse response =
            buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request and an RDS request for "route-foo.googleapis.com". (Omitted)

    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    // Management server should only sends RouteConfiguration messages with at least one
    // VirtualHost with domains matching requested hostname. Otherwise, it is invalid data.
    List<Any> routeConfigs =
        ImmutableList.of(
            Any.pack(
                buildRouteConfiguration(
                    "route-foo.googleapis.com",
                    ImmutableList.of(
                        VirtualHost.newBuilder()
                            .setName("virtualhost00.googleapis.com") // don't care
                            // domains wit a match.
                            .addAllDomains(ImmutableList.of(TARGET_AUTHORITY, "bar.googleapis.com"))
                            .addRoutes(
                                Route.newBuilder()
                                    // path match with cluster route
                                    .setRoute(
                                        RouteAction.newBuilder()
                                            .setCluster("cl1.googleapis.com"))
                                    .setMatch(
                                        RouteMatch.newBuilder()
                                            .setPath("/service1/method1")))
                            .addRoutes(
                                Route.newBuilder()
                                    // path match with weighted cluster route
                                    .setRoute(
                                        RouteAction.newBuilder()
                                            .setWeightedClusters(
                                                WeightedCluster.newBuilder()
                                                    .addClusters(
                                                        WeightedCluster.ClusterWeight.newBuilder()
                                                            .setWeight(UInt32Value.of(30))
                                                            .setName("cl21.googleapis.com"))
                                                    .addClusters(
                                                        WeightedCluster.ClusterWeight.newBuilder()
                                                            .setWeight(UInt32Value.of(70))
                                                            .setName("cl22.googleapis.com"))))
                                    .setMatch(
                                        RouteMatch.newBuilder()
                                            .setPath("/service2/method2")))
                            .addRoutes(
                                Route.newBuilder()
                                    // prefix match with cluster route
                                    .setRoute(
                                        RouteAction.newBuilder()
                                            .setCluster("cl1.googleapis.com"))
                                    .setMatch(
                                        RouteMatch.newBuilder()
                                            .setPrefix("/service1/")))
                            .addRoutes(
                                Route.newBuilder()
                                    // default match with cluster route
                                    .setRoute(
                                        RouteAction.newBuilder()
                                            .setCluster("cluster.googleapis.com"))
                                    .setMatch(
                                        RouteMatch.newBuilder()
                                            .setPrefix("")))
                            .build()))));
    response = buildDiscoveryResponse("0", routeConfigs, XdsClientImpl.ADS_TYPE_URL_RDS, "0000");
    responseObserver.onNext(response);

    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();

    // Client sent an ACK RDS request.
    verify(requestObserver)
            .onNext(eq(buildDiscoveryRequest(NODE, "0", "route-foo.googleapis.com",
                    XdsClientImpl.ADS_TYPE_URL_RDS, "0000")));

    ArgumentCaptor<ConfigUpdate> configUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher).onConfigChanged(configUpdateCaptor.capture());
    List<EnvoyProtoData.Route> routes = configUpdateCaptor.getValue().getRoutes();
    assertThat(routes).hasSize(4);
    assertThat(routes.get(0))
        .isEqualTo(
            new EnvoyProtoData.Route(
                // path match with cluster route
                new io.grpc.xds.RouteMatch(
                    /* pathPrefixMatch= */ null, /* pathExactMatch= */ "/service1/method1"),
                new EnvoyProtoData.RouteAction(
                    TimeUnit.SECONDS.toNanos(15L), "cl1.googleapis.com", null)));
    assertThat(routes.get(1))
        .isEqualTo(
            new EnvoyProtoData.Route(
                // path match with weighted cluster route
                new io.grpc.xds.RouteMatch(
                    /* pathPrefixMatch= */ null, /* pathExactMatch= */ "/service2/method2"),
                new EnvoyProtoData.RouteAction(
                    TimeUnit.SECONDS.toNanos(15L),
                    null,
                    ImmutableList.of(
                        new EnvoyProtoData.ClusterWeight("cl21.googleapis.com", 30),
                        new EnvoyProtoData.ClusterWeight("cl22.googleapis.com", 70)))));
    assertThat(routes.get(2))
        .isEqualTo(
            new EnvoyProtoData.Route(
                // prefix match with cluster route
                new io.grpc.xds.RouteMatch(
                    /* pathPrefixMatch= */ "/service1/", /* pathExactMatch= */ null),
                new EnvoyProtoData.RouteAction(
                    TimeUnit.SECONDS.toNanos(15L), "cl1.googleapis.com", null)));
    assertThat(routes.get(3))
        .isEqualTo(
            new EnvoyProtoData.Route(
                // default match with cluster route
                new io.grpc.xds.RouteMatch(
                    /* pathPrefixMatch= */ "", /* pathExactMatch= */ null),
                new EnvoyProtoData.RouteAction(
                    TimeUnit.SECONDS.toNanos(15L), "cluster.googleapis.com", null)));
  }

  /**
   * Client receives an RDS response (after a previous LDS request-response) containing a
   * RouteConfiguration message for the requested resource. But the RouteConfiguration message
   * is invalid as it does not contain any VirtualHost with domains matching the requested
   * hostname.
   * The RDS response is NACKed, as if the XdsClient has not received this response.
   */
  @Test
  public void failToFindVirtualHostInRdsResponse() {
    xdsClient.watchConfigData(TARGET_AUTHORITY, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    Rds rdsConfig =
        Rds.newBuilder()
            // Must set to use ADS.
            .setConfigSource(
                ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance()))
            .setRouteConfigName("route-foo.googleapis.com")
            .build();

    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(TARGET_AUTHORITY, /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRds(rdsConfig).build())))
    );
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request and an RDS request for "route-foo.googleapis.com". (Omitted)

    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(
            buildRouteConfiguration(
                "route-foo.googleapis.com",
                ImmutableList.of(
                    buildVirtualHost(ImmutableList.of("something does not match"),
                        "some cluster"),
                    buildVirtualHost(
                        ImmutableList.of("something else does not match", "also does not match"),
                        "cluster.googleapis.com")))),
        Any.pack(
            buildRouteConfiguration(
                "some resource name does not match route-foo.googleapis.com",
                ImmutableList.of(
                    buildVirtualHost(ImmutableList.of("one more does not match"),
                        "some more cluster")))));
    response = buildDiscoveryResponse("0", routeConfigs, XdsClientImpl.ADS_TYPE_URL_RDS, "0000");
    responseObserver.onNext(response);

    // Client sent an NACK RDS request.
    verify(requestObserver)
        .onNext(
            argThat(new DiscoveryRequestMatcher("", "route-foo.googleapis.com",
                XdsClientImpl.ADS_TYPE_URL_RDS, "0000")));

    verify(configWatcher, never()).onConfigChanged(any(ConfigUpdate.class));
    verify(configWatcher, never()).onResourceDoesNotExist(anyString());
    verify(configWatcher, never()).onError(any(Status.class));
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(configWatcher).onResourceDoesNotExist("route-foo.googleapis.com");
    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  /**
   * Client receives an RDS response (after a previous LDS request-response) containing a
   * RouteConfiguration message for the requested resource. But the RouteConfiguration message
   * is invalid as the VirtualHost with domains matching the requested hostname contains invalid
   * data, its RouteAction message is absent.
   * The RDS response is NACKed, as if the XdsClient has not received this response.
   */
  @Test
  public void matchingVirtualHostDoesNotContainRouteAction() {
    xdsClient.watchConfigData(TARGET_AUTHORITY, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    Rds rdsConfig =
        Rds.newBuilder()
            // Must set to use ADS.
            .setConfigSource(
                ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance()))
            .setRouteConfigName("route-foo.googleapis.com")
            .build();

    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(TARGET_AUTHORITY, /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRds(rdsConfig).build())))
    );
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request and an RDS request for "route-foo.googleapis.com". (Omitted)

    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    // A VirtualHost with a Route that contains only redirect configuration.
    VirtualHost virtualHost =
        VirtualHost.newBuilder()
            .setName("virtualhost00.googleapis.com") // don't care
            .addDomains(TARGET_AUTHORITY)
            .addRoutes(
                Route.newBuilder()
                    .setRedirect(
                        RedirectAction.newBuilder()
                            .setHostRedirect("bar.googleapis.com")
                            .setPortRedirect(443)))
            .build();

    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(
            buildRouteConfiguration("route-foo.googleapis.com",
                ImmutableList.of(virtualHost))));
    response = buildDiscoveryResponse("0", routeConfigs, XdsClientImpl.ADS_TYPE_URL_RDS, "0000");
    responseObserver.onNext(response);

    // Client sent an NACK RDS request.
    verify(requestObserver)
        .onNext(
            argThat(new DiscoveryRequestMatcher("", "route-foo.googleapis.com",
                XdsClientImpl.ADS_TYPE_URL_RDS, "0000")));

    verify(configWatcher, never()).onConfigChanged(any(ConfigUpdate.class));
    verify(configWatcher, never()).onResourceDoesNotExist(anyString());
    verify(configWatcher, never()).onError(any(Status.class));
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(configWatcher).onResourceDoesNotExist("route-foo.googleapis.com");
    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  /**
   * Client receives LDS/RDS responses for updating resources previously received.
   *
   * <p>Tests for streaming behavior.
   */
  @Test
  public void notifyUpdatedResources() {
    xdsClient.watchConfigData(TARGET_AUTHORITY, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request for the host name (with port) to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));

    // Management server sends back an LDS response containing a RouteConfiguration for the
    // requested Listener directly in-line.
    RouteConfiguration routeConfig =
        buildRouteConfiguration(
            "route-foo.googleapis.com", // target route configuration
            ImmutableList.of(
                buildVirtualHost( // matching virtual host
                    ImmutableList.of(TARGET_AUTHORITY, "bar.googleapis.com:443"),
                    "cluster.googleapis.com"),
                buildVirtualHost(ImmutableList.of("something does not match"),
                    "some cluster")));

    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(TARGET_AUTHORITY, /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRouteConfig(routeConfig).build())))
    );
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "0000")));

    // Cluster name is resolved and notified to config watcher.
    ArgumentCaptor<ConfigUpdate> configUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher).onConfigChanged(configUpdateCaptor.capture());
    assertConfigUpdateContainsSingleClusterRoute(
        configUpdateCaptor.getValue(), "cluster.googleapis.com");

    // Management sends back another LDS response containing updates for the requested Listener.
    routeConfig =
        buildRouteConfiguration(
            "another-route-foo.googleapis.com",
            ImmutableList.of(
                buildVirtualHost(ImmutableList.of(TARGET_AUTHORITY, "bar.googleapis.com:443"),
                    "another-cluster.googleapis.com"),
                buildVirtualHost(ImmutableList.of("something does not match"),
                    "some cluster")));

    listeners = ImmutableList.of(
        Any.pack(buildListener(TARGET_AUTHORITY, /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRouteConfig(routeConfig).build())))
    );
    response =
        buildDiscoveryResponse("1", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0001");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "1", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "0001")));

    // Updated cluster name is notified to config watcher.
    configUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher, times(2)).onConfigChanged(configUpdateCaptor.capture());
    assertConfigUpdateContainsSingleClusterRoute(
        configUpdateCaptor.getValue(), "another-cluster.googleapis.com");

    // Management server sends back another LDS response containing updates for the requested
    // Listener and telling client to do RDS.
    Rds rdsConfig =
        Rds.newBuilder()
            // Must set to use ADS.
            .setConfigSource(
                ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance()))
            .setRouteConfigName("some-route-to-foo.googleapis.com")
            .build();

    listeners = ImmutableList.of(
        Any.pack(buildListener(TARGET_AUTHORITY, /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRds(rdsConfig).build())))
    );
    response =
        buildDiscoveryResponse("2", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0002");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "2", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "0002")));

    // Client sends an (first) RDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "some-route-to-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_RDS, "")));

    // Management server sends back an RDS response containing the RouteConfiguration
    // for the requested resource.
    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(
            buildRouteConfiguration(
                "some-route-to-foo.googleapis.com",
                ImmutableList.of(
                    buildVirtualHost(ImmutableList.of("something does not match"),
                        "some cluster"),
                    buildVirtualHost(ImmutableList.of(TARGET_AUTHORITY, "bar.googleapis.com:443"),
                        "some-other-cluster.googleapis.com")))));
    response = buildDiscoveryResponse("0", routeConfigs, XdsClientImpl.ADS_TYPE_URL_RDS, "0000");
    responseObserver.onNext(response);

    // Client sent an ACK RDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "some-route-to-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_RDS, "0000")));

    // Updated cluster name is notified to config watcher again.
    configUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher, times(3)).onConfigChanged(configUpdateCaptor.capture());
    assertConfigUpdateContainsSingleClusterRoute(
        configUpdateCaptor.getValue(), "some-other-cluster.googleapis.com");

    // Management server sends back another RDS response containing updated information for the
    // RouteConfiguration currently in-use by client.
    routeConfigs = ImmutableList.of(
        Any.pack(
            buildRouteConfiguration(
                "some-route-to-foo.googleapis.com",
                ImmutableList.of(
                    buildVirtualHost(ImmutableList.of(TARGET_AUTHORITY, "bar.googleapis.com:443"),
                        "an-updated-cluster.googleapis.com")))));
    response = buildDiscoveryResponse("1", routeConfigs, XdsClientImpl.ADS_TYPE_URL_RDS, "0001");
    responseObserver.onNext(response);

    // Client sent an ACK RDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "1", "some-route-to-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_RDS, "0001")));

    // Updated cluster name is notified to config watcher again.
    configUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher, times(4)).onConfigChanged(configUpdateCaptor.capture());
    assertConfigUpdateContainsSingleClusterRoute(
        configUpdateCaptor.getValue(), "an-updated-cluster.googleapis.com");

    // Management server sends back an LDS response indicating all Listener resources are removed.
    response =
        buildDiscoveryResponse("3", ImmutableList.<Any>of(),
            XdsClientImpl.ADS_TYPE_URL_LDS, "0003");
    responseObserver.onNext(response);

    verify(configWatcher).onResourceDoesNotExist(TARGET_AUTHORITY);
  }

  // TODO(chengyuanzhang): tests for timeout waiting for responses for incremental
  //  protocols (RDS/EDS).

  /**
   * Client receives multiple RDS responses without RouteConfiguration for the requested
   * resource. It should continue waiting until such an RDS response arrives, as RDS
   * protocol is incremental.
   *
   * <p>Tests for RDS incremental protocol behavior.
   */
  @Test
  public void waitRdsResponsesForRequestedResource() {
    xdsClient.watchConfigData(TARGET_AUTHORITY, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request for the host name (with port) to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));

    // Management sends back an LDS response telling client to do RDS.
    Rds rdsConfig =
        Rds.newBuilder()
            // Must set to use ADS.
            .setConfigSource(
                ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance()))
            .setRouteConfigName("route-foo.googleapis.com")
            .build();

    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(TARGET_AUTHORITY, /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRds(rdsConfig).build())))
    );
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "0000")));

    // Client sends an (first) RDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "route-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_RDS, "")));

    ScheduledTask rdsRespTimer =
        Iterables.getOnlyElement(
            fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    assertThat(rdsRespTimer.isCancelled()).isFalse();

    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC - 2, TimeUnit.SECONDS);

    // Management server sends back an RDS response that does not contain RouteConfiguration
    // for the requested resource.
    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(
            buildRouteConfiguration(
                "some resource name does not match route-foo.googleapis.com",
                ImmutableList.of(
                    buildVirtualHost(
                        ImmutableList.of(TARGET_AUTHORITY),
                        "some more cluster")))));
    response = buildDiscoveryResponse("0", routeConfigs, XdsClientImpl.ADS_TYPE_URL_RDS, "0000");
    responseObserver.onNext(response);

    // Client sent an ACK RDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "route-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_RDS, "0000")));

    // Client waits for future RDS responses silently.
    verifyNoMoreInteractions(configWatcher);
    assertThat(rdsRespTimer.isCancelled()).isFalse();

    fakeClock.forwardTime(1, TimeUnit.SECONDS);

    // Management server sends back another RDS response containing the RouteConfiguration
    // for the requested resource.
    routeConfigs = ImmutableList.of(
        Any.pack(
            buildRouteConfiguration(
                "route-foo.googleapis.com", // target route configuration
                ImmutableList.of(
                    buildVirtualHost(
                        ImmutableList.of("something does not match"),
                        "some cluster"),
                    buildVirtualHost( // matching virtual host
                        ImmutableList.of(TARGET_AUTHORITY, "bar.googleapis.com:443"),
                        "another-cluster.googleapis.com")))));
    response = buildDiscoveryResponse("1", routeConfigs, XdsClientImpl.ADS_TYPE_URL_RDS, "0001");
    responseObserver.onNext(response);

    // Client sent an ACK RDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "1", "route-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_RDS, "0001")));

    // Updated cluster name is notified to config watcher.
    ArgumentCaptor<ConfigUpdate> configUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher).onConfigChanged(configUpdateCaptor.capture());
    assertConfigUpdateContainsSingleClusterRoute(
        configUpdateCaptor.getValue(), "another-cluster.googleapis.com");
    assertThat(rdsRespTimer.isCancelled()).isTrue();
  }

  /**
   * An RouteConfiguration is removed by server by sending client an LDS response removing the
   * corresponding Listener.
   */
  @Test
  public void routeConfigurationRemovedNotifiedToWatcher() {
    xdsClient.watchConfigData(TARGET_AUTHORITY, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request for the host name (with port) to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));

    // Management sends back an LDS response telling client to do RDS.
    Rds rdsConfig =
        Rds.newBuilder()
            // Must set to use ADS.
            .setConfigSource(
                ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance()))
            .setRouteConfigName("route-foo.googleapis.com")
            .build();

    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(TARGET_AUTHORITY, /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRds(rdsConfig).build())))
    );
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "0000")));

    // Client sends an (first) RDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "route-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_RDS, "")));

    // Management server sends back an RDS response containing RouteConfiguration requested.
    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(
            buildRouteConfiguration(
                "route-foo.googleapis.com", // target route configuration
                ImmutableList.of(
                    buildVirtualHost(
                        ImmutableList.of(TARGET_AUTHORITY), // matching virtual host
                        "cluster.googleapis.com")))));
    response = buildDiscoveryResponse("0", routeConfigs, XdsClientImpl.ADS_TYPE_URL_RDS, "0000");
    responseObserver.onNext(response);

    // Client sent an ACK RDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "route-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_RDS, "0000")));

    // Resolved cluster name is notified to config watcher.
    ArgumentCaptor<ConfigUpdate> configUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher).onConfigChanged(configUpdateCaptor.capture());
    assertConfigUpdateContainsSingleClusterRoute(
        configUpdateCaptor.getValue(), "cluster.googleapis.com");

    // Management server sends back another LDS response with the previous Listener (currently
    // in-use by client) removed as the RouteConfiguration it references to is absent.
    response =
        buildDiscoveryResponse("1", ImmutableList.<com.google.protobuf.Any>of(), // empty
            XdsClientImpl.ADS_TYPE_URL_LDS, "0001");
    responseObserver.onNext(response);

    // Client sent an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "1", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "0001")));

    verify(configWatcher).onResourceDoesNotExist(TARGET_AUTHORITY);
  }

  /**
   * Management server sends another LDS response for updating the RDS resource to be requested
   * while client is currently requesting for a previously given RDS resource name.
   */
  @Test
  public void updateRdsRequestResourceWhileInitialResourceFetchInProgress() {
    xdsClient.watchConfigData(TARGET_AUTHORITY, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Management sends back an LDS response telling client to do RDS.
    Rds rdsConfig =
        Rds.newBuilder()
            // Must set to use ADS.
            .setConfigSource(
                ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance()))
            .setRouteConfigName("route-foo.googleapis.com")
            .build();

    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(TARGET_AUTHORITY, /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRds(rdsConfig).build())))
    );
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    // Client sends an (first) RDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "route-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_RDS, "")));

    ScheduledTask rdsRespTimer =
        Iterables.getOnlyElement(
            fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    assertThat(rdsRespTimer.isCancelled()).isFalse();

    // Management sends back another LDS response updating the Listener information to use
    // another resource name for doing RDS.
    rdsConfig =
        Rds.newBuilder()
            // Must set to use ADS.
            .setConfigSource(
                ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance()))
            .setRouteConfigName("route-bar.googleapis.com")
            .build();

    listeners = ImmutableList.of(
        Any.pack(
            buildListener(
                TARGET_AUTHORITY, /* matching resource */
                Any.pack(HttpConnectionManager.newBuilder().setRds(rdsConfig).build())))
    );
    response = buildDiscoveryResponse("1", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0001");
    responseObserver.onNext(response);

    // Client sent a new RDS request with updated resource name.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "route-bar.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_RDS, "")));

    assertThat(rdsRespTimer.isCancelled()).isTrue();
    rdsRespTimer =
        Iterables.getOnlyElement(
            fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    assertThat(rdsRespTimer.isCancelled()).isFalse();

    // Management server sends back an RDS response containing RouteConfiguration requested.
    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(
            buildRouteConfiguration(
                "route-bar.googleapis.com", // target route configuration
                ImmutableList.of(
                    buildVirtualHost(
                        ImmutableList.of(TARGET_AUTHORITY), // matching virtual host
                        "cluster.googleapis.com")))));
    response = buildDiscoveryResponse("0", routeConfigs, XdsClientImpl.ADS_TYPE_URL_RDS, "0000");
    responseObserver.onNext(response);

    assertThat(rdsRespTimer.isCancelled()).isTrue();
  }

  /**
   * Client receives an CDS response that does not contain a Cluster for the requested resource
   * while each received Cluster is valid. The CDS response is ACKed. Cluster watchers are notified
   * with resource unavailable after initial resource fetch timeout has expired.
   */
  @Test
  public void cdsResponseWithoutMatchingResource() {
    xdsClient.watchCdsResource("cluster-foo.googleapis.com", cdsResourceWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends a CDS request for the only cluster being watched to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "")));
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    // Management server sends back a CDS response without Cluster for the requested resource.
    List<Any> clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster-bar.googleapis.com", null, false)),
        Any.pack(buildCluster("cluster-baz.googleapis.com", null, false)));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusters, XdsClientImpl.ADS_TYPE_URL_CDS, "0000");
    responseObserver.onNext(response);

    // Client sent an ACK CDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "0000")));
    verify(cdsResourceWatcher, never()).onChanged(any(CdsUpdate.class));
    verify(cdsResourceWatcher, never()).onResourceDoesNotExist("cluster-foo.googleapis.com");
    verify(cdsResourceWatcher, never()).onError(any(Status.class));

    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(cdsResourceWatcher).onResourceDoesNotExist("cluster-foo.googleapis.com");
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  /**
   * Normal workflow of receiving a CDS response containing Cluster message for a requested
   * cluster.
   */
  @Test
  public void cdsResponseWithMatchingResource() {
    xdsClient.watchCdsResource("cluster-foo.googleapis.com", cdsResourceWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends a CDS request for the only cluster being watched to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "")));
    ScheduledTask cdsRespTimer =
        Iterables.getOnlyElement(
            fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));

    // Management server sends back a CDS response without Cluster for the requested resource.
    List<Any> clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster-bar.googleapis.com", null, false)),
        Any.pack(buildCluster("cluster-foo.googleapis.com", null, false)),
        Any.pack(buildCluster("cluster-baz.googleapis.com", null, false)));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusters, XdsClientImpl.ADS_TYPE_URL_CDS, "0000");
    responseObserver.onNext(response);

    // Client sent an ACK CDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "0000")));
    assertThat(cdsRespTimer.isCancelled()).isTrue();

    ArgumentCaptor<CdsUpdate> cdsUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(cdsUpdate.getEdsServiceName()).isNull();
    assertThat(cdsUpdate.getLbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate.getLrsServerName()).isNull();

    // Management server sends back another CDS response updating the requested Cluster.
    clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster-bar.googleapis.com", null, false)),
        Any.pack(
            buildCluster("cluster-foo.googleapis.com", "eds-cluster-foo.googleapis.com", true)),
        Any.pack(buildCluster("cluster-baz.googleapis.com", null, false)));
    response =
        buildDiscoveryResponse("1", clusters, XdsClientImpl.ADS_TYPE_URL_CDS, "0001");
    responseObserver.onNext(response);

    // Client sent an ACK CDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "1", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "0001")));

    verify(cdsResourceWatcher, times(2)).onChanged(cdsUpdateCaptor.capture());
    cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(cdsUpdate.getEdsServiceName())
        .isEqualTo("eds-cluster-foo.googleapis.com");
    assertThat(cdsUpdate.getLbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate.getLrsServerName()).isEqualTo("");
  }

  /**
   * CDS response containing UpstreamTlsContext for a cluster.
   */
  @Test
  public void cdsResponseWithUpstreamTlsContext() {
    xdsClient.watchCdsResource("cluster-foo.googleapis.com", cdsResourceWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Management server sends back CDS response with UpstreamTlsContext.
    UpstreamTlsContext testUpstreamTlsContext =
        buildUpstreamTlsContext("secret1", "unix:/var/uds2");
    List<Any> clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster-bar.googleapis.com", null, false)),
        Any.pack(buildSecureCluster("cluster-foo.googleapis.com",
            "eds-cluster-foo.googleapis.com", true, testUpstreamTlsContext)),
        Any.pack(buildCluster("cluster-baz.googleapis.com", null, false)));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusters, XdsClientImpl.ADS_TYPE_URL_CDS, "0000");
    responseObserver.onNext(response);

    // Client sent an ACK CDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "0000")));
    ArgumentCaptor<CdsUpdate> cdsUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(cdsResourceWatcher, times(1)).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    EnvoyServerProtoData.UpstreamTlsContext upstreamTlsContext = cdsUpdate
        .getUpstreamTlsContext();
    SdsSecretConfig validationContextSdsSecretConfig = upstreamTlsContext.getCommonTlsContext()
        .getValidationContextSdsSecretConfig();
    assertThat(validationContextSdsSecretConfig.getName()).isEqualTo("secret1");
    assertThat(
            Iterables.getOnlyElement(
                    validationContextSdsSecretConfig
                        .getSdsConfig()
                        .getApiConfigSource()
                        .getGrpcServicesList())
                .getGoogleGrpc()
                .getTargetUri())
        .isEqualTo("unix:/var/uds2");
  }

  @Test
  public void multipleCdsWatchers() {
    CdsResourceWatcher watcher1 = mock(CdsResourceWatcher.class);
    CdsResourceWatcher watcher2 = mock(CdsResourceWatcher.class);
    CdsResourceWatcher watcher3 = mock(CdsResourceWatcher.class);
    xdsClient.watchCdsResource("cluster-foo.googleapis.com", watcher1);
    xdsClient.watchCdsResource("cluster-foo.googleapis.com", watcher2);
    xdsClient.watchCdsResource("cluster-bar.googleapis.com", watcher3);

    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends a CDS request containing all clusters being watched to management server.
    verify(requestObserver)
        .onNext(
            argThat(
                new DiscoveryRequestMatcher("",
                    ImmutableList.of("cluster-foo.googleapis.com", "cluster-bar.googleapis.com"),
                    XdsClientImpl.ADS_TYPE_URL_CDS, "")));
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(2);

    // Management server sends back a CDS response contains Cluster for only one of
    // requested cluster.
    List<Any> clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster-foo.googleapis.com", null, false)));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusters, XdsClientImpl.ADS_TYPE_URL_CDS, "0000");
    responseObserver.onNext(response);

    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);
    // Client sent an ACK CDS request.
    verify(requestObserver)
        .onNext(
            argThat(
                new DiscoveryRequestMatcher("0",
                    ImmutableList.of("cluster-foo.googleapis.com", "cluster-bar.googleapis.com"),
                    XdsClientImpl.ADS_TYPE_URL_CDS, "0000")));

    // Two watchers get notification of cluster update for the cluster they are interested in.
    ArgumentCaptor<CdsUpdate> cdsUpdateCaptor1 = ArgumentCaptor.forClass(null);
    verify(watcher1).onChanged(cdsUpdateCaptor1.capture());
    CdsUpdate cdsUpdate1 = cdsUpdateCaptor1.getValue();
    assertThat(cdsUpdate1.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(cdsUpdate1.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(cdsUpdate1.getEdsServiceName()).isNull();
    assertThat(cdsUpdate1.getLbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate1.getLrsServerName()).isNull();

    ArgumentCaptor<CdsUpdate> cdsUpdateCaptor2 = ArgumentCaptor.forClass(null);
    verify(watcher2).onChanged(cdsUpdateCaptor2.capture());
    CdsUpdate cdsUpdate2 = cdsUpdateCaptor2.getValue();
    assertThat(cdsUpdate2.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(cdsUpdate2.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(cdsUpdate2.getEdsServiceName()).isNull();
    assertThat(cdsUpdate2.getLbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate2.getLrsServerName()).isNull();

    verify(watcher3, never()).onChanged(any(CdsUpdate.class));
    verify(watcher3, never()).onResourceDoesNotExist("cluster-bar.googleapis.com");
    verify(watcher3, never()).onError(any(Status.class));

    // The other watcher gets an error notification for cluster not found after its timer expired.
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(watcher3).onResourceDoesNotExist("cluster-bar.googleapis.com");
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();

    // Management server sends back another CDS response contains Clusters for all
    // requested clusters.
    clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster-foo.googleapis.com", null, false)),
        Any.pack(
            buildCluster("cluster-bar.googleapis.com",
                "eds-cluster-bar.googleapis.com", true)));
    response = buildDiscoveryResponse("1", clusters,
        XdsClientImpl.ADS_TYPE_URL_CDS, "0001");
    responseObserver.onNext(response);

    // Client sent an ACK CDS request.
    verify(requestObserver)
        .onNext(
            argThat(
                new DiscoveryRequestMatcher("1",
                    ImmutableList.of("cluster-foo.googleapis.com", "cluster-bar.googleapis.com"),
                    XdsClientImpl.ADS_TYPE_URL_CDS, "0001")));

    verifyNoMoreInteractions(watcher1, watcher2); // resource has no change
    ArgumentCaptor<CdsUpdate> cdsUpdateCaptor3 = ArgumentCaptor.forClass(null);
    verify(watcher3).onChanged(cdsUpdateCaptor3.capture());
    CdsUpdate cdsUpdate3 = cdsUpdateCaptor3.getValue();
    assertThat(cdsUpdate3.getClusterName()).isEqualTo("cluster-bar.googleapis.com");
    assertThat(cdsUpdate3.getEdsServiceName())
        .isEqualTo("eds-cluster-bar.googleapis.com");
    assertThat(cdsUpdate3.getLbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate3.getLrsServerName()).isEqualTo("");
  }

  /**
   * (CDS response caching behavior) Adding cluster watchers interested in some cluster that
   * some other endpoint watcher had already been watching on will result in cluster update
   * notified to the newly added watcher immediately, without sending new CDS requests.
   */
  @Test
  public void watchClusterAlreadyBeingWatched() {
    CdsResourceWatcher watcher1 = mock(CdsResourceWatcher.class);
    xdsClient.watchCdsResource("cluster-foo.googleapis.com", watcher1);

    // Streaming RPC starts after a first watcher is added.
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an CDS request to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "")));
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    // Management server sends back an CDS response with Cluster for the requested
    // cluster.
    List<Any> clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster-foo.googleapis.com", null, false)));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusters, XdsClientImpl.ADS_TYPE_URL_CDS, "0000");
    responseObserver.onNext(response);

    // Client sent an ACK CDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "0000")));

    ArgumentCaptor<CdsUpdate> cdsUpdateCaptor1 = ArgumentCaptor.forClass(null);
    verify(watcher1).onChanged(cdsUpdateCaptor1.capture());
    CdsUpdate cdsUpdate1 = cdsUpdateCaptor1.getValue();
    assertThat(cdsUpdate1.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(cdsUpdate1.getEdsServiceName()).isNull();
    assertThat(cdsUpdate1.getLbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate1.getLrsServerName()).isNull();
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();

    // Another cluster watcher interested in the same cluster is added.
    CdsResourceWatcher watcher2 = mock(CdsResourceWatcher.class);
    xdsClient.watchCdsResource("cluster-foo.googleapis.com", watcher2);

    // Since the client has received cluster update for this cluster before, cached result is
    // notified to the newly added watcher immediately.
    ArgumentCaptor<CdsUpdate> cdsUpdateCaptor2 = ArgumentCaptor.forClass(null);
    verify(watcher2).onChanged(cdsUpdateCaptor2.capture());
    CdsUpdate cdsUpdate2 = cdsUpdateCaptor2.getValue();
    assertThat(cdsUpdate2.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(cdsUpdate2.getEdsServiceName()).isNull();
    assertThat(cdsUpdate2.getLbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate2.getLrsServerName()).isNull();

    verifyNoMoreInteractions(requestObserver);
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  /**
   * Basic operations of adding/canceling cluster data watchers.
   */
  @Test
  public void addRemoveCdsWatchers() {
    CdsResourceWatcher watcher1 = mock(CdsResourceWatcher.class);
    xdsClient.watchCdsResource("cluster-foo.googleapis.com", watcher1);

    // Streaming RPC starts after a first watcher is added.
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an CDS request to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "")));

    // Management server sends back a CDS response with Cluster for the requested
    // cluster.
    List<Any> clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster-foo.googleapis.com", null, false)));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusters, XdsClientImpl.ADS_TYPE_URL_CDS, "0000");
    responseObserver.onNext(response);

    // Client sent an ACK CDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "0000")));

    ArgumentCaptor<CdsUpdate> cdsUpdateCaptor1 = ArgumentCaptor.forClass(null);
    verify(watcher1).onChanged(cdsUpdateCaptor1.capture());
    CdsUpdate cdsUpdate1 = cdsUpdateCaptor1.getValue();
    assertThat(cdsUpdate1.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(cdsUpdate1.getEdsServiceName()).isNull();
    assertThat(cdsUpdate1.getLbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate1.getLrsServerName()).isNull();

    // Add another cluster watcher for a different cluster.
    CdsResourceWatcher watcher2 = mock(CdsResourceWatcher.class);
    xdsClient.watchCdsResource("cluster-bar.googleapis.com", watcher2);

    // Client sent a new CDS request for all interested resources.
    verify(requestObserver)
        .onNext(
            argThat(
                new DiscoveryRequestMatcher("0",
                    ImmutableList.of("cluster-foo.googleapis.com", "cluster-bar.googleapis.com"),
                    XdsClientImpl.ADS_TYPE_URL_CDS, "0000")));

    // Management server sends back a CDS response with Cluster for all requested cluster.
    clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster-foo.googleapis.com", null, false)),
        Any.pack(
            buildCluster("cluster-bar.googleapis.com",
                "eds-cluster-bar.googleapis.com", true)));
    response = buildDiscoveryResponse("1", clusters,
        XdsClientImpl.ADS_TYPE_URL_CDS, "0001");
    responseObserver.onNext(response);

    // Client sent an ACK CDS request for all interested resources.
    verify(requestObserver)
        .onNext(
            argThat(
                new DiscoveryRequestMatcher("1",
                    ImmutableList.of("cluster-foo.googleapis.com", "cluster-bar.googleapis.com"),
                    XdsClientImpl.ADS_TYPE_URL_CDS, "0001")));
    verifyNoMoreInteractions(watcher1);  // resource has no change
    ArgumentCaptor<CdsUpdate> cdsUpdateCaptor2 = ArgumentCaptor.forClass(null);
    verify(watcher2).onChanged(cdsUpdateCaptor2.capture());
    CdsUpdate cdsUpdate2 = cdsUpdateCaptor2.getValue();
    assertThat(cdsUpdate2.getClusterName()).isEqualTo("cluster-bar.googleapis.com");
    assertThat(cdsUpdate2.getEdsServiceName())
        .isEqualTo("eds-cluster-bar.googleapis.com");
    assertThat(cdsUpdate2.getLbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate2.getLrsServerName()).isEqualTo("");

    // Cancel one of the watcher.
    xdsClient.cancelCdsResourceWatch("cluster-foo.googleapis.com", watcher1);

    // Since the cancelled watcher was the last watcher interested in that cluster (but there
    // is still interested resource), client sent an new CDS request to unsubscribe from
    // that cluster.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "1", "cluster-bar.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "0001")));

    // Management server has nothing to respond.

    // Cancel the other watcher. All resources have been unsubscribed.
    xdsClient.cancelCdsResourceWatch("cluster-bar.googleapis.com", watcher2);

    verify(requestObserver)
        .onNext(
            argThat(
                new DiscoveryRequestMatcher("1", ImmutableList.<String>of(),
                    XdsClientImpl.ADS_TYPE_URL_CDS, "0001")));

    // Management server sends back a new CDS response.
    clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster-foo.googleapis.com", null, true)),
        Any.pack(
            buildCluster("cluster-bar.googleapis.com", null, false)));
    response =
        buildDiscoveryResponse("2", clusters, XdsClientImpl.ADS_TYPE_URL_CDS, "0002");
    responseObserver.onNext(response);

    verify(requestObserver)
        .onNext(
            argThat(
                new DiscoveryRequestMatcher("2", ImmutableList.<String>of(),
                    XdsClientImpl.ADS_TYPE_URL_CDS, "0002")));

    // Cancelled watchers do not receive notification.
    verifyNoMoreInteractions(watcher1, watcher2);

    // A new cluster watcher is added to watch cluster foo again.
    CdsResourceWatcher watcher3 = mock(CdsResourceWatcher.class);
    xdsClient.watchCdsResource("cluster-foo.googleapis.com", watcher3);
    verify(watcher3, never()).onChanged(any(CdsUpdate.class));

    // A CDS request is sent to indicate subscription of "cluster-foo.googleapis.com" only.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "2", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "0002")));

    // Management server sends back a new CDS response for at least newly requested resources
    // (it is required to do so).
    clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster-foo.googleapis.com", null, true)),
        Any.pack(
            buildCluster("cluster-bar.googleapis.com", null, false)));
    response =
        buildDiscoveryResponse("3", clusters, XdsClientImpl.ADS_TYPE_URL_CDS, "0003");
    responseObserver.onNext(response);

    // Notified with cached data immediately.
    ArgumentCaptor<CdsUpdate> cdsUpdateCaptor3 = ArgumentCaptor.forClass(null);
    verify(watcher3).onChanged(cdsUpdateCaptor3.capture());
    CdsUpdate cdsUpdate3 = cdsUpdateCaptor3.getValue();
    assertThat(cdsUpdate3.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(cdsUpdate3.getEdsServiceName()).isNull();
    assertThat(cdsUpdate3.getLbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate2.getLrsServerName()).isEqualTo("");

    verifyNoMoreInteractions(watcher1, watcher2);

    // A CDS request is sent to re-subscribe the cluster again.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "3", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "0003")));
  }

  @Test
  public void addRemoveCdsWatcherWhileInitialResourceFetchInProgress() {
    CdsResourceWatcher watcher1 = mock(CdsResourceWatcher.class);
    xdsClient.watchCdsResource("cluster-foo.googleapis.com", watcher1);

    // Streaming RPC starts after a first watcher is added.
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    verify(requestObserver)
        .onNext(
            argThat(
                new DiscoveryRequestMatcher("", "cluster-foo.googleapis.com",
                    XdsClientImpl.ADS_TYPE_URL_CDS, "")));
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC - 1, TimeUnit.SECONDS);

    CdsResourceWatcher watcher2 = mock(CdsResourceWatcher.class);
    CdsResourceWatcher watcher3 = mock(CdsResourceWatcher.class);
    CdsResourceWatcher watcher4 = mock(CdsResourceWatcher.class);
    xdsClient.watchCdsResource("cluster-foo.googleapis.com", watcher2);
    xdsClient.watchCdsResource("cluster-bar.googleapis.com", watcher3);
    xdsClient.watchCdsResource("cluster-bar.googleapis.com", watcher4);

    // Client sends a new CDS request for updating the latest resource subscription.
    verify(requestObserver)
        .onNext(
            argThat(
                new DiscoveryRequestMatcher("",
                    ImmutableList.of("cluster-foo.googleapis.com", "cluster-bar.googleapis.com"),
                    XdsClientImpl.ADS_TYPE_URL_CDS, "")));
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(2);

    fakeClock.forwardTime(1, TimeUnit.SECONDS);
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    // CDS resource "cluster-foo.googleapis.com" is known to be absent.
    verify(watcher1).onResourceDoesNotExist("cluster-foo.googleapis.com");
    verify(watcher2).onResourceDoesNotExist("cluster-foo.googleapis.com");

    // The absence result is known immediately.
    CdsResourceWatcher watcher5 = mock(CdsResourceWatcher.class);
    xdsClient.watchCdsResource("cluster-foo.googleapis.com", watcher5);
    verify(watcher5).onResourceDoesNotExist("cluster-foo.googleapis.com");

    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);
    ScheduledTask timeoutTask = Iterables.getOnlyElement(fakeClock.getPendingTasks());

    // Cancel watchers while discovery for resource "cluster-bar.googleapis.com" is still
    // in progress.
    xdsClient.cancelCdsResourceWatch("cluster-bar.googleapis.com", watcher3);
    assertThat(timeoutTask.isCancelled()).isFalse();
    xdsClient.cancelCdsResourceWatch("cluster-bar.googleapis.com", watcher4);

    // Client sends a CDS request for resource subscription update (Omitted).

    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);

    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
    assertThat(timeoutTask.isCancelled()).isTrue();

    verifyNoInteractions(watcher3, watcher4);
  }

  @Test
  public void cdsUpdateForClusterBeingRemoved() {
    xdsClient.watchCdsResource("cluster-foo.googleapis.com", cdsResourceWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "")));
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    // Management server sends back a CDS response containing requested resource.
    List<Any> clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster-foo.googleapis.com", null, true)));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusters, XdsClientImpl.ADS_TYPE_URL_CDS, "0000");
    responseObserver.onNext(response);

    // Client sent an ACK CDS request (Omitted).

    ArgumentCaptor<CdsUpdate> cdsUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(cdsUpdate.getEdsServiceName()).isNull();
    assertThat(cdsUpdate.getLbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate.getLrsServerName()).isEqualTo("");
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();

    // No cluster is available.
    response =
        buildDiscoveryResponse("1", ImmutableList.<Any>of(),
            XdsClientImpl.ADS_TYPE_URL_CDS, "0001");
    responseObserver.onNext(response);

    verify(cdsResourceWatcher).onResourceDoesNotExist("cluster-foo.googleapis.com");
  }

  /**
   * Client receives an EDS response that does not contain a ClusterLoadAssignment for the
   * requested resource while each received ClusterLoadAssignment is valid.
   * The EDS response is ACKed.
   * After the resource fetch timeout expires, watchers waiting for the resource is notified
   * with resource unavailable.
   */
  @Test
  public void edsResponseWithoutMatchingResource() {
    xdsClient.watchEdsResource("cluster-foo.googleapis.com", edsResourceWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an EDS request for the only cluster being watched to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "")));
    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    // Management server sends back an EDS response without ClusterLoadAssignment for the requested
    // cluster.
    List<Any> clusterLoadAssignments = ImmutableList.of(
        Any.pack(buildClusterLoadAssignment("cluster-bar.googleapis.com",
            ImmutableList.of(
                buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2)),
                    1, 0)),
            ImmutableList.<ClusterLoadAssignment.Policy.DropOverload>of())),
        Any.pack(buildClusterLoadAssignment("cluster-baz.googleapis.com",
            ImmutableList.of(
                buildLocalityLbEndpoints("region2", "zone2", "subzone2",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.234.52", 8888, HealthStatus.UNKNOWN, 5)),
                    6, 1)),
            ImmutableList.<ClusterLoadAssignment.Policy.DropOverload>of())));

    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusterLoadAssignments,
            XdsClientImpl.ADS_TYPE_URL_EDS, "0000");
    responseObserver.onNext(response);

    // Client sent an ACK EDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "0000")));

    verify(edsResourceWatcher, never()).onChanged(any(EdsUpdate.class));
    verify(edsResourceWatcher, never()).onResourceDoesNotExist("cluster-foo.googleapis.com");
    verify(edsResourceWatcher, never()).onError(any(Status.class));
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(edsResourceWatcher).onResourceDoesNotExist("cluster-foo.googleapis.com");
    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  /**
   * Normal workflow of receiving an EDS response containing ClusterLoadAssignment message for
   * a requested cluster.
   */
  @Test
  public void edsResponseWithMatchingResource() {
    xdsClient.watchEdsResource("cluster-foo.googleapis.com", edsResourceWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an EDS request for the only cluster being watched to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "")));
    ScheduledTask edsRespTimeoutTask =
        Iterables.getOnlyElement(
            fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    assertThat(edsRespTimeoutTask.isCancelled()).isFalse();

    // Management server sends back an EDS response with ClusterLoadAssignment for the requested
    // cluster.
    List<Any> clusterLoadAssignments = ImmutableList.of(
        Any.pack(buildClusterLoadAssignment("cluster-foo.googleapis.com",
            ImmutableList.of(
                buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2)),
                    1, 0),
                buildLocalityLbEndpoints("region3", "zone3", "subzone3",
                    ImmutableList.<io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint>of(),
                    2, 1), /* locality with 0 endpoint */
                buildLocalityLbEndpoints("region4", "zone4", "subzone4",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.142.5", 80, HealthStatus.UNKNOWN, 5)),
                    0, 2) /* locality with 0 weight */),
            ImmutableList.of(
                buildDropOverload("lb", 200),
                buildDropOverload("throttle", 1000)))),
        Any.pack(buildClusterLoadAssignment("cluster-baz.googleapis.com",
            ImmutableList.of(
                buildLocalityLbEndpoints("region2", "zone2", "subzone2",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.234.52", 8888, HealthStatus.UNKNOWN, 5)),
                    6, 1)),
            ImmutableList.<ClusterLoadAssignment.Policy.DropOverload>of())));

    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusterLoadAssignments,
            XdsClientImpl.ADS_TYPE_URL_EDS, "0000");
    responseObserver.onNext(response);

    assertThat(edsRespTimeoutTask.isCancelled()).isTrue();

    // Client sent an ACK EDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "0000")));

    ArgumentCaptor<EdsUpdate> edsUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(edsResourceWatcher).onChanged(edsUpdateCaptor.capture());
    EdsUpdate edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(edsUpdate.getDropPolicies())
        .containsExactly(
            new DropOverload("lb", 200),
            new DropOverload("throttle", 1000));
    assertThat(edsUpdate.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region1", "zone1", "subzone1"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.0.1", 8080,
                        2, true)), 1, 0),
            new Locality("region3", "zone3", "subzone3"),
            new LocalityLbEndpoints(ImmutableList.<LbEndpoint>of(), 2, 1));

    clusterLoadAssignments = ImmutableList.of(
        Any.pack(buildClusterLoadAssignment("cluster-foo.googleapis.com",
            // 0 locality
            ImmutableList.<io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints>of(),
            ImmutableList.<ClusterLoadAssignment.Policy.DropOverload>of())));
    response =
        buildDiscoveryResponse(
            "1", clusterLoadAssignments, XdsClientImpl.ADS_TYPE_URL_EDS, "0001");
    responseObserver.onNext(response);

    // Client sent an ACK EDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "1", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "0001")));

    verify(edsResourceWatcher, times(2)).onChanged(edsUpdateCaptor.capture());
    edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(edsUpdate.getDropPolicies()).isEmpty();
    assertThat(edsUpdate.getLocalityLbEndpointsMap()).isEmpty();
  }

  @Test
  public void multipleEdsWatchers() {
    EdsResourceWatcher watcher1 = mock(EdsResourceWatcher.class);
    EdsResourceWatcher watcher2 = mock(EdsResourceWatcher.class);
    EdsResourceWatcher watcher3 = mock(EdsResourceWatcher.class);
    xdsClient.watchEdsResource("cluster-foo.googleapis.com", watcher1);
    xdsClient.watchEdsResource("cluster-foo.googleapis.com", watcher2);
    xdsClient.watchEdsResource("cluster-bar.googleapis.com", watcher3);

    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an EDS request containing all clusters being watched to management server.
    verify(requestObserver)
        .onNext(
            argThat(
                new DiscoveryRequestMatcher("",
                    ImmutableList.of("cluster-foo.googleapis.com", "cluster-bar.googleapis.com"),
                    XdsClientImpl.ADS_TYPE_URL_EDS, "")));

    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(2);

    // Management server sends back an EDS response contains ClusterLoadAssignment for only one of
    // requested cluster.
    List<Any> clusterLoadAssignments = ImmutableList.of(
        Any.pack(buildClusterLoadAssignment("cluster-foo.googleapis.com",
            ImmutableList.of(
                buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2)),
                    1, 0)),
            ImmutableList.<ClusterLoadAssignment.Policy.DropOverload>of())));

    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusterLoadAssignments,
            XdsClientImpl.ADS_TYPE_URL_EDS, "0000");
    responseObserver.onNext(response);

    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    // Client sent an ACK EDS request.
    verify(requestObserver)
        .onNext(
            argThat(
                new DiscoveryRequestMatcher("0",
                    ImmutableList.of("cluster-foo.googleapis.com", "cluster-bar.googleapis.com"),
                    XdsClientImpl.ADS_TYPE_URL_EDS, "0000")));

    // Two watchers get notification of endpoint update for the cluster they are interested in.
    ArgumentCaptor<EdsUpdate> edsUpdateCaptor1 = ArgumentCaptor.forClass(null);
    verify(watcher1).onChanged(edsUpdateCaptor1.capture());
    EdsUpdate edsUpdate1 = edsUpdateCaptor1.getValue();
    assertThat(edsUpdate1.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(edsUpdate1.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region1", "zone1", "subzone1"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.0.1", 8080,
                        2, true)), 1, 0));

    ArgumentCaptor<EdsUpdate> edsUpdateCaptor2 = ArgumentCaptor.forClass(null);
    verify(watcher1).onChanged(edsUpdateCaptor2.capture());
    EdsUpdate edsUpdate2 = edsUpdateCaptor2.getValue();
    assertThat(edsUpdate2.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(edsUpdate2.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region1", "zone1", "subzone1"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.0.1", 8080,
                        2, true)), 1, 0));

    verifyNoInteractions(watcher3);

    // Management server sends back another EDS response contains ClusterLoadAssignment for the
    // other requested cluster.
    clusterLoadAssignments = ImmutableList.of(
        Any.pack(buildClusterLoadAssignment("cluster-bar.googleapis.com",
            ImmutableList.of(
                buildLocalityLbEndpoints("region2", "zone2", "subzone2",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.234.52", 8888, HealthStatus.UNKNOWN, 5)),
                    6, 0)),
            ImmutableList.<ClusterLoadAssignment.Policy.DropOverload>of())));

    response = buildDiscoveryResponse("1", clusterLoadAssignments,
        XdsClientImpl.ADS_TYPE_URL_EDS, "0001");
    responseObserver.onNext(response);

    // Client sent an ACK EDS request.
    verify(requestObserver)
        .onNext(
            argThat(
                new DiscoveryRequestMatcher("1",
                    ImmutableList.of("cluster-foo.googleapis.com", "cluster-bar.googleapis.com"),
                    XdsClientImpl.ADS_TYPE_URL_EDS, "0001")));

    // The corresponding watcher gets notified.
    ArgumentCaptor<EdsUpdate> edsUpdateCaptor3 = ArgumentCaptor.forClass(null);
    verify(watcher3).onChanged(edsUpdateCaptor3.capture());
    EdsUpdate edsUpdate3 = edsUpdateCaptor3.getValue();
    assertThat(edsUpdate3.getClusterName()).isEqualTo("cluster-bar.googleapis.com");
    assertThat(edsUpdate3.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region2", "zone2", "subzone2"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.234.52", 8888,
                        5, true)), 6, 0));
  }

  /**
   * (EDS response caching behavior) An endpoint watcher is registered for a cluster that already
   * has some other endpoint watchers watching on. Endpoint information received previously is
   * in local cache and notified to the new watcher immediately.
   */
  @Test
  public void watchEndpointsForClusterAlreadyBeingWatched() {
    EdsResourceWatcher watcher1 = mock(EdsResourceWatcher.class);
    xdsClient.watchEdsResource("cluster-foo.googleapis.com", watcher1);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends first EDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "")));

    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    // Management server sends back an EDS response containing ClusterLoadAssignments for
    // some cluster not requested.
    List<Any> clusterLoadAssignments = ImmutableList.of(
        Any.pack(buildClusterLoadAssignment("cluster-foo.googleapis.com",
            ImmutableList.of(
                buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2)),
                    1, 0)),
            ImmutableList.<ClusterLoadAssignment.Policy.DropOverload>of())));

    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusterLoadAssignments,
            XdsClientImpl.ADS_TYPE_URL_EDS, "0000");
    responseObserver.onNext(response);

    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();

    // Client sent an ACK EDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "0000")));

    ArgumentCaptor<EdsUpdate> edsUpdateCaptor1 = ArgumentCaptor.forClass(null);
    verify(watcher1).onChanged(edsUpdateCaptor1.capture());
    EdsUpdate edsUpdate1 = edsUpdateCaptor1.getValue();
    assertThat(edsUpdate1.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(edsUpdate1.getDropPolicies()).isEmpty();
    assertThat(edsUpdate1.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region1", "zone1", "subzone1"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.0.1", 8080,
                        2, true)), 1, 0));

    // A second endpoint watcher is registered for endpoints in the same cluster.
    EdsResourceWatcher watcher2 = mock(EdsResourceWatcher.class);
    xdsClient.watchEdsResource("cluster-foo.googleapis.com", watcher2);

    // Cached endpoint information is notified to the new watcher immediately, without sending
    // another EDS request.
    ArgumentCaptor<EdsUpdate> edsUpdateCaptor2 = ArgumentCaptor.forClass(null);
    verify(watcher2).onChanged(edsUpdateCaptor2.capture());
    EdsUpdate edsUpdate2 = edsUpdateCaptor2.getValue();
    assertThat(edsUpdate2.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(edsUpdate2.getDropPolicies()).isEmpty();
    assertThat(edsUpdate2.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region1", "zone1", "subzone1"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.0.1", 8080,
                        2, true)), 1, 0));

    verifyNoMoreInteractions(requestObserver);
    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  /**
   * Basic operations of adding/canceling endpoint data watchers.
   */
  @Test
  public void addRemoveEdsWatchers() {
    EdsResourceWatcher watcher1 = mock(EdsResourceWatcher.class);
    xdsClient.watchEdsResource("cluster-foo.googleapis.com", watcher1);

    // Streaming RPC starts after a first watcher is added.
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an EDS request to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "")));

    // Management server sends back an EDS response with ClusterLoadAssignment for the requested
    // cluster.
    List<Any> clusterLoadAssignments = ImmutableList.of(
        Any.pack(buildClusterLoadAssignment("cluster-foo.googleapis.com",
            ImmutableList.of(
                buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2),
                        buildLbEndpoint("192.132.53.5", 80, HealthStatus.UNHEALTHY, 5)),
                    1, 0)),
            ImmutableList.<ClusterLoadAssignment.Policy.DropOverload>of())));

    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusterLoadAssignments,
            XdsClientImpl.ADS_TYPE_URL_EDS, "0000");
    responseObserver.onNext(response);

    // Client sent an ACK EDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "0000")));

    ArgumentCaptor<EdsUpdate> edsUpdateCaptor1 = ArgumentCaptor.forClass(null);
    verify(watcher1).onChanged(edsUpdateCaptor1.capture());
    EdsUpdate edsUpdate1 = edsUpdateCaptor1.getValue();
    assertThat(edsUpdate1.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(edsUpdate1.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region1", "zone1", "subzone1"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.0.1", 8080, 2, true),
                    new LbEndpoint("192.132.53.5", 80,5, false)),
                1, 0));

    // Add another endpoint watcher for a different cluster.
    EdsResourceWatcher watcher2 = mock(EdsResourceWatcher.class);
    xdsClient.watchEdsResource("cluster-bar.googleapis.com", watcher2);

    // Client sent a new EDS request for all interested resources.
    verify(requestObserver)
        .onNext(
            argThat(
                new DiscoveryRequestMatcher("0",
                    ImmutableList.of("cluster-foo.googleapis.com", "cluster-bar.googleapis.com"),
                    XdsClientImpl.ADS_TYPE_URL_EDS, "0000")));

    // Management server sends back an EDS response with ClusterLoadAssignment for one of requested
    // cluster.
    clusterLoadAssignments = ImmutableList.of(
        Any.pack(buildClusterLoadAssignment("cluster-bar.googleapis.com",
            ImmutableList.of(
                buildLocalityLbEndpoints("region2", "zone2", "subzone2",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.312.6", 443, HealthStatus.HEALTHY, 1)),
                    6, 0)),
            ImmutableList.<ClusterLoadAssignment.Policy.DropOverload>of())));

    response = buildDiscoveryResponse("1", clusterLoadAssignments,
        XdsClientImpl.ADS_TYPE_URL_EDS, "0001");
    responseObserver.onNext(response);

    // Client sent an ACK EDS request for all interested resources.
    verify(requestObserver)
        .onNext(
            argThat(
                new DiscoveryRequestMatcher("1",
                    ImmutableList.of("cluster-foo.googleapis.com", "cluster-bar.googleapis.com"),
                    XdsClientImpl.ADS_TYPE_URL_EDS, "0001")));

    ArgumentCaptor<EdsUpdate> edsUpdateCaptor2 = ArgumentCaptor.forClass(null);
    verify(watcher2).onChanged(edsUpdateCaptor2.capture());
    EdsUpdate edsUpdate2 = edsUpdateCaptor2.getValue();
    assertThat(edsUpdate2.getClusterName()).isEqualTo("cluster-bar.googleapis.com");
    assertThat(edsUpdate2.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region2", "zone2", "subzone2"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.312.6", 443, 1, true)),
                6, 0));

    // Cancel one of the watcher.
    xdsClient.cancelEdsResourceWatch("cluster-foo.googleapis.com", watcher1);

    // Since the cancelled watcher was the last watcher interested in that cluster, client
    // sent an new EDS request to unsubscribe from that cluster.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "1", "cluster-bar.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "0001")));

    // Management server should not respond as it had previously sent the requested resource.

    // Cancel the other watcher.
    xdsClient.cancelEdsResourceWatch("cluster-bar.googleapis.com", watcher2);

    // Since the cancelled watcher was the last watcher interested in that cluster, client
    // sent an new EDS request to unsubscribe from that cluster.
    verify(requestObserver)
        .onNext(
            argThat(
                new DiscoveryRequestMatcher("1",
                    ImmutableList.<String>of(),  // empty resources
                    XdsClientImpl.ADS_TYPE_URL_EDS, "0001")));

    // All endpoint watchers have been cancelled.

    // Management server sends back an EDS response for updating previously sent resources.
    clusterLoadAssignments = ImmutableList.of(
        Any.pack(buildClusterLoadAssignment("cluster-foo.googleapis.com",
            ImmutableList.of(
                buildLocalityLbEndpoints("region3", "zone3", "subzone3",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.432.6", 80, HealthStatus.HEALTHY, 2)),
                    3, 0)),
            ImmutableList.<ClusterLoadAssignment.Policy.DropOverload>of())),
        Any.pack(buildClusterLoadAssignment("cluster-bar.googleapis.com",
            ImmutableList.of(
                buildLocalityLbEndpoints("region4", "zone4", "subzone4",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.75.6", 8888, HealthStatus.HEALTHY, 2)),
                    3, 0)),
            ImmutableList.<ClusterLoadAssignment.Policy.DropOverload>of())));

    response = buildDiscoveryResponse("2", clusterLoadAssignments,
        XdsClientImpl.ADS_TYPE_URL_EDS, "0002");
    responseObserver.onNext(response);

    // Client sent an ACK EDS request.
    verify(requestObserver)
        .onNext(
            argThat(
                new DiscoveryRequestMatcher("2",
                    ImmutableList.<String>of(),  // empty resources
                    XdsClientImpl.ADS_TYPE_URL_EDS, "0002")));

    // Cancelled watchers do not receive notification.
    verifyNoMoreInteractions(watcher1, watcher2);

    // A new endpoint watcher is added to watch an old but was no longer interested in cluster.
    EdsResourceWatcher watcher3 = mock(EdsResourceWatcher.class);
    xdsClient.watchEdsResource("cluster-bar.googleapis.com", watcher3);

    // Nothing should be notified to the new watcher as we are still waiting management server's
    // latest response.
    // Cached endpoint data should have been purged.
    verify(watcher3, never()).onChanged(any(EdsUpdate.class));

    // An EDS request is sent to re-subscribe the cluster again.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "2", "cluster-bar.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "0002")));

    // Management server sends back an EDS response for re-subscribed resource.
    clusterLoadAssignments = ImmutableList.of(
        Any.pack(buildClusterLoadAssignment("cluster-bar.googleapis.com",
            ImmutableList.of(
                buildLocalityLbEndpoints("region4", "zone4", "subzone4",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.75.6", 8888, HealthStatus.HEALTHY, 2)),
                    3, 0)),
            ImmutableList.<ClusterLoadAssignment.Policy.DropOverload>of())));

    response = buildDiscoveryResponse("3", clusterLoadAssignments,
        XdsClientImpl.ADS_TYPE_URL_EDS, "0003");
    responseObserver.onNext(response);

    ArgumentCaptor<EdsUpdate> edsUpdateCaptor3 = ArgumentCaptor.forClass(null);
    verify(watcher3).onChanged(edsUpdateCaptor3.capture());
    EdsUpdate edsUpdate3 = edsUpdateCaptor3.getValue();
    assertThat(edsUpdate3.getClusterName()).isEqualTo("cluster-bar.googleapis.com");
    assertThat(edsUpdate3.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region4", "zone4", "subzone4"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.75.6", 8888, 2, true)),
                3, 0));

    // Client sent an ACK EDS request.
    verify(requestObserver)
        .onNext(
            argThat(
                new DiscoveryRequestMatcher("3",
                    ImmutableList.of("cluster-bar.googleapis.com"),
                    XdsClientImpl.ADS_TYPE_URL_EDS, "0003")));
  }

  @Test
  public void addRemoveEdsWatcherWhileInitialResourceFetchInProgress() {
    EdsResourceWatcher watcher1 = mock(EdsResourceWatcher.class);
    xdsClient.watchEdsResource("cluster-foo.googleapis.com", watcher1);

    // Streaming RPC starts after a first watcher is added.
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an EDS request to management server.
    verify(requestObserver)
        .onNext(
            argThat(
                new DiscoveryRequestMatcher("", "cluster-foo.googleapis.com",
                    XdsClientImpl.ADS_TYPE_URL_EDS, "")));
    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC - 1, TimeUnit.SECONDS);

    EdsResourceWatcher watcher2 = mock(EdsResourceWatcher.class);
    EdsResourceWatcher watcher3 = mock(EdsResourceWatcher.class);
    EdsResourceWatcher watcher4 = mock(EdsResourceWatcher.class);
    xdsClient.watchEdsResource("cluster-foo.googleapis.com", watcher2);
    xdsClient.watchEdsResource("cluster-bar.googleapis.com", watcher3);
    xdsClient.watchEdsResource("cluster-bar.googleapis.com", watcher4);

    // Client sends a new EDS request for updating the latest resource subscription.
    verify(requestObserver)
        .onNext(
            argThat(
                new DiscoveryRequestMatcher("",
                    ImmutableList.of("cluster-foo.googleapis.com", "cluster-bar.googleapis.com"),
                    XdsClientImpl.ADS_TYPE_URL_EDS, "")));
    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(2);

    fakeClock.forwardTime(1, TimeUnit.SECONDS);
    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    // EDS resource "cluster-foo.googleapis.com" is known to be absent.
    verify(watcher1).onResourceDoesNotExist("cluster-foo.googleapis.com");
    verify(watcher2).onResourceDoesNotExist("cluster-foo.googleapis.com");

    // The absence result is known immediately.
    EdsResourceWatcher watcher5 = mock(EdsResourceWatcher.class);
    xdsClient.watchEdsResource("cluster-foo.googleapis.com", watcher5);
    verify(watcher5).onResourceDoesNotExist("cluster-foo.googleapis.com");

    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);
    ScheduledTask timeoutTask = Iterables.getOnlyElement(fakeClock.getPendingTasks());

    // Cancel watchers while discovery for resource "cluster-bar.googleapis.com" is still
    // in progress.
    xdsClient.cancelEdsResourceWatch("cluster-bar.googleapis.com", watcher3);
    assertThat(timeoutTask.isCancelled()).isFalse();
    xdsClient.cancelEdsResourceWatch("cluster-bar.googleapis.com", watcher4);

    // Client sends an EDS request for resource subscription update (Omitted).

    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);

    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
    assertThat(timeoutTask.isCancelled()).isTrue();

    verifyNoInteractions(watcher3, watcher4);
  }

  @Test
  public void cdsUpdateForEdsServiceNameChange() {
    xdsClient.watchCdsResource("cluster-foo.googleapis.com", cdsResourceWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();

    // Management server sends back a CDS response containing requested resource.
    List<Any> clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster-foo.googleapis.com", "cluster-foo:service-bar", false)));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusters, XdsClientImpl.ADS_TYPE_URL_CDS, "0000");
    responseObserver.onNext(response);

    xdsClient.watchEdsResource("cluster-foo:service-bar", edsResourceWatcher);

    // Management server sends back an EDS response for resource "cluster-foo:service-bar".
    List<Any> clusterLoadAssignments = ImmutableList.of(
        Any.pack(buildClusterLoadAssignment("cluster-foo:service-bar",
            ImmutableList.of(
                buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2)),
                    1, 0)),
            ImmutableList.<ClusterLoadAssignment.Policy.DropOverload>of())));
    response =
        buildDiscoveryResponse("0", clusterLoadAssignments,
            XdsClientImpl.ADS_TYPE_URL_EDS, "0000");
    responseObserver.onNext(response);

    ArgumentCaptor<EdsUpdate> edsUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(edsResourceWatcher).onChanged(edsUpdateCaptor.capture());
    EdsUpdate edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.getClusterName()).isEqualTo("cluster-foo:service-bar");
    assertThat(edsUpdate.getDropPolicies()).isEmpty();
    assertThat(edsUpdate.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region1", "zone1", "subzone1"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.0.1", 8080,
                        2, true)), 1, 0));

    // Management server sends another CDS response for removing cluster service
    // "cluster-foo:service-blade" with replacement of "cluster-foo:service-blade".
    clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster-foo.googleapis.com", "cluster-foo:service-blade", false)));
    response =
        buildDiscoveryResponse("1", clusters, XdsClientImpl.ADS_TYPE_URL_CDS, "0001");
    responseObserver.onNext(response);

    // Watcher get notification for endpoint resource "cluster-foo:service-bar" being deleted.
    verify(edsResourceWatcher).onResourceDoesNotExist("cluster-foo:service-bar");
  }

  /**
   * RPC stream closed and retry during the period of first time resolving service config
   * (LDS/RDS only).
   */
  @Test
  public void streamClosedAndRetryWhenResolvingConfig() {
    InOrder inOrder =
        Mockito.inOrder(mockedDiscoveryService, backoffPolicyProvider, backoffPolicy1,
            backoffPolicy2);
    xdsClient.watchConfigData(TARGET_AUTHORITY, configWatcher);

    ArgumentCaptor<StreamObserver<DiscoveryResponse>> responseObserverCaptor =
        ArgumentCaptor.forClass(null);
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    StreamObserver<DiscoveryResponse> responseObserver =
        responseObserverCaptor.getValue();  // same as responseObservers.poll()
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request for the host name (with port) to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));

    // Management server closes the RPC stream immediately.
    responseObserver.onCompleted();
    inOrder.verify(backoffPolicyProvider).get();
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

    // Client retried by sending an LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));

    // Management server closes the RPC stream with an error.
    responseObserver.onError(Status.UNAVAILABLE.asException());
    verifyNoMoreInteractions(backoffPolicyProvider);
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

    // Client retried again by sending an LDS.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));

    // Management server responses with a listener for the requested resource.
    Rds rdsConfig =
        Rds.newBuilder()
            .setConfigSource(
                ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance()))
            .setRouteConfigName("route-foo.googleapis.com")
            .build();

    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(TARGET_AUTHORITY, /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRds(rdsConfig).build())))
    );
    DiscoveryResponse ldsResponse =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(ldsResponse);

    // Client sent back an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "0000")));

    // Client sent an RDS request based on the received listener.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "route-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_RDS, "")));

    // Management server encounters an error and closes the stream.
    responseObserver.onError(Status.UNKNOWN.asException());

    // Reset backoff and retry immediately.
    inOrder.verify(backoffPolicyProvider).get();
    fakeClock.runDueTasks();
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    responseObserver = responseObserverCaptor.getValue();
    requestObserver = requestObservers.poll();
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));

    // RPC stream closed immediately
    responseObserver.onError(Status.UNKNOWN.asException());
    inOrder.verify(backoffPolicy2).nextBackoffNanos();
    assertThat(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER)).hasSize(1);

    // Retry after backoff.
    fakeClock.forwardNanos(19L);
    assertThat(requestObservers).isEmpty();
    fakeClock.forwardNanos(1L);
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    responseObserver = responseObserverCaptor.getValue();
    requestObserver = requestObservers.poll();
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));

    // Management server sends an LDS response.
    ldsResponse = buildDiscoveryResponse("1", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0001");
    responseObserver.onNext(ldsResponse);

    // Client sends an ACK LDS request and an RDS request for "route-foo.googleapis.com". (Omitted)

    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(
            buildRouteConfiguration(
                "route-foo.googleapis.com", // target route configuration
                ImmutableList.of(
                    buildVirtualHost(
                        ImmutableList.of(TARGET_AUTHORITY), // matching virtual host
                        "cluster.googleapis.com")))));
    DiscoveryResponse rdsResponse =
        buildDiscoveryResponse("0", routeConfigs, XdsClientImpl.ADS_TYPE_URL_RDS, "0000");
    // Management server sends an RDS response.
    responseObserver.onNext(rdsResponse);

    // Client has resolved the cluster based on the RDS response.
    ArgumentCaptor<ConfigUpdate> configUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher).onConfigChanged(configUpdateCaptor.capture());
    assertConfigUpdateContainsSingleClusterRoute(
        configUpdateCaptor.getValue(), "cluster.googleapis.com");

    // RPC stream closed with an error again.
    responseObserver.onError(Status.UNKNOWN.asException());

    // Reset backoff and retry immediately.
    inOrder.verify(backoffPolicyProvider).get();
    fakeClock.runDueTasks();
    requestObserver = requestObservers.poll();
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "1", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));

    verifyNoMoreInteractions(backoffPolicyProvider, backoffPolicy1, backoffPolicy2);
  }

  /**
   * RPC stream close and retry while there are config/cluster/endpoint watchers registered.
   */
  @Test
  public void streamClosedAndRetry() {
    InOrder inOrder =
        Mockito.inOrder(mockedDiscoveryService, backoffPolicyProvider, backoffPolicy1,
            backoffPolicy2);
    xdsClient.watchConfigData(TARGET_AUTHORITY, configWatcher);

    ArgumentCaptor<StreamObserver<DiscoveryResponse>> responseObserverCaptor =
        ArgumentCaptor.forClass(null);
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    StreamObserver<DiscoveryResponse> responseObserver =
        responseObserverCaptor.getValue();  // same as responseObservers.poll()
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    waitUntilConfigResolved(responseObserver);
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(null);

    // Start watching cluster information.
    xdsClient.watchCdsResource("cluster.googleapis.com", cdsResourceWatcher);

    // Client sent first CDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "")));

    // Start watching endpoint information.
    xdsClient.watchEdsResource("cluster.googleapis.com", edsResourceWatcher);

    // Client sent first EDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "")));

    // Management server closes the RPC stream with an error.
    responseObserver.onError(Status.UNKNOWN.asException());
    verify(configWatcher).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.UNKNOWN);
    verify(cdsResourceWatcher).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.UNKNOWN);
    verify(edsResourceWatcher).onError(statusCaptor.capture());
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
        .onNext(eq(buildDiscoveryRequest(NODE, "0", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "")));

    // Management server becomes unreachable.
    responseObserver.onError(Status.UNAVAILABLE.asException());
    verify(configWatcher, times(2)).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    verify(cdsResourceWatcher, times(2)).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    verify(edsResourceWatcher, times(2)).onError(statusCaptor.capture());
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
        .onNext(eq(buildDiscoveryRequest(NODE, "0", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "")));

    // Management server is still not reachable.
    responseObserver.onError(Status.UNAVAILABLE.asException());
    verify(configWatcher, times(3)).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    verify(cdsResourceWatcher, times(3)).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    verify(edsResourceWatcher, times(3)).onError(statusCaptor.capture());
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
        .onNext(eq(buildDiscoveryRequest(NODE, "0", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "")));

    // Management server sends back a CDS response.
    List<Any> clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster.googleapis.com", null, false)));
    DiscoveryResponse cdsResponse =
        buildDiscoveryResponse("0", clusters, XdsClientImpl.ADS_TYPE_URL_CDS, "0000");
    responseObserver.onNext(cdsResponse);

    // Client sent an CDS ACK request (Omitted).

    // Management server closes the RPC stream.
    responseObserver.onCompleted();
    verify(configWatcher, times(4)).onError(any(Status.class));
    verify(cdsResourceWatcher, times(4)).onError(any(Status.class));
    verify(edsResourceWatcher, times(4)).onError(any(Status.class));

    // Resets backoff and retry immediately
    inOrder.verify(backoffPolicyProvider).get();
    fakeClock.runDueTasks();
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    responseObserver = responseObserverCaptor.getValue();
    requestObserver = requestObservers.poll();

    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "")));

    // Management server becomes unreachable again.
    responseObserver.onError(Status.UNAVAILABLE.asException());
    verify(configWatcher, times(5)).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    verify(cdsResourceWatcher, times(5)).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    verify(edsResourceWatcher, times(5)).onError(statusCaptor.capture());
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
        .onNext(eq(buildDiscoveryRequest(NODE, "0", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "")));

    verifyNoMoreInteractions(mockedDiscoveryService, backoffPolicyProvider, backoffPolicy1,
        backoffPolicy2);
  }

  /**
   * RPC stream closed and retry while some cluster/endpoint watchers have changed (added/removed).
   */
  @Test
  public void streamClosedAndRetryRaceWithAddingAndRemovingWatchers() {
    InOrder inOrder =
        Mockito.inOrder(mockedDiscoveryService, backoffPolicyProvider, backoffPolicy1,
            backoffPolicy2);
    xdsClient.watchConfigData(TARGET_AUTHORITY, configWatcher);

    ArgumentCaptor<StreamObserver<DiscoveryResponse>> responseObserverCaptor =
        ArgumentCaptor.forClass(null);
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    StreamObserver<DiscoveryResponse> responseObserver =
        responseObserverCaptor.getValue();  // same as responseObservers.poll()
    requestObservers.poll();

    waitUntilConfigResolved(responseObserver);

    // Management server closes RPC stream.
    responseObserver.onCompleted();

    // Resets backoff and retry immediately.
    inOrder.verify(backoffPolicyProvider).get();
    fakeClock.runDueTasks();
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    responseObserver = responseObserverCaptor.getValue();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));

    // Management server becomes unreachable.
    responseObserver.onError(Status.UNAVAILABLE.asException());
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    assertThat(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER)).hasSize(1);

    // Start watching cluster information while RPC stream is still in retry backoff.
    xdsClient.watchCdsResource("cluster.googleapis.com", cdsResourceWatcher);

    // Retry after backoff.
    fakeClock.forwardNanos(9L);
    assertThat(requestObservers).isEmpty();
    fakeClock.forwardNanos(1L);
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    responseObserver = responseObserverCaptor.getValue();
    requestObserver = requestObservers.poll();

    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "")));

    // Management server is still unreachable.
    responseObserver.onError(Status.UNAVAILABLE.asException());
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    assertThat(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER)).hasSize(1);

    // Start watching endpoint information while RPC stream is still in retry backoff.
    xdsClient.watchEdsResource("cluster.googleapis.com", edsResourceWatcher);

    // Retry after backoff.
    fakeClock.forwardNanos(99L);
    assertThat(requestObservers).isEmpty();
    fakeClock.forwardNanos(1L);
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    responseObserver = responseObserverCaptor.getValue();
    requestObserver = requestObservers.poll();

    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "")));

    // Management server sends back a CDS response.
    List<Any> clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster.googleapis.com", null, false)));
    DiscoveryResponse cdsResponse =
        buildDiscoveryResponse("0", clusters, XdsClientImpl.ADS_TYPE_URL_CDS, "0000");
    responseObserver.onNext(cdsResponse);

    // Client sent an CDS ACK request (Omitted).

    // No longer interested in endpoint information after RPC resumes.
    xdsClient.cancelEdsResourceWatch("cluster.googleapis.com", edsResourceWatcher);
    // Client updates EDS resource subscription immediately.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", ImmutableList.<String>of(),
            XdsClientImpl.ADS_TYPE_URL_EDS, "")));

    // Become interested in endpoints of another cluster.
    xdsClient.watchEdsResource("cluster2.googleapis.com", edsResourceWatcher);
    // Client updates EDS resource subscription immediately.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster2.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "")));

    // Management server closes the RPC stream again.
    responseObserver.onCompleted();

    // Resets backoff and retry immediately.
    inOrder.verify(backoffPolicyProvider).get();
    fakeClock.runDueTasks();
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    responseObserver = responseObserverCaptor.getValue();
    requestObserver = requestObservers.poll();
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster2.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "")));

    // Management server becomes unreachable again.
    responseObserver.onError(Status.UNAVAILABLE.asException());
    inOrder.verify(backoffPolicy2).nextBackoffNanos();
    assertThat(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER)).hasSize(1);

    // No longer interested in previous cluster and endpoints in that cluster.
    xdsClient.cancelCdsResourceWatch("cluster.googleapis.com", cdsResourceWatcher);
    xdsClient.cancelEdsResourceWatch("cluster2.googleapis.com", edsResourceWatcher);

    // Retry after backoff.
    fakeClock.forwardNanos(19L);
    assertThat(requestObservers).isEmpty();
    fakeClock.forwardNanos(1L);
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    requestObserver = requestObservers.poll();

    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    verify(requestObserver, never())
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "")));
    verify(requestObserver, never())
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster2.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "")));

    verifyNoMoreInteractions(mockedDiscoveryService, backoffPolicyProvider, backoffPolicy1,
        backoffPolicy2);
  }

  @Test
  public void streamClosedAndRetryReschedulesAllResourceFetchTimer() {
    InOrder inOrder =
        Mockito.inOrder(mockedDiscoveryService, backoffPolicyProvider, backoffPolicy1,
            backoffPolicy2);
    xdsClient.watchConfigData(TARGET_AUTHORITY, configWatcher);

    ArgumentCaptor<StreamObserver<DiscoveryResponse>> responseObserverCaptor =
        ArgumentCaptor.forClass(null);
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    StreamObserver<DiscoveryResponse> responseObserver =
        responseObserverCaptor.getValue();  // same as responseObservers.poll()

    // Management server sends back an LDS response telling client to do RDS.
    Rds rdsConfig =
        Rds.newBuilder()
            // Must set to use ADS.
            .setConfigSource(
                ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance()))
            .setRouteConfigName("route-foo.googleapis.com")
            .build();

    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(TARGET_AUTHORITY, /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRds(rdsConfig).build())))
    );
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    // Client sent an RDS request for resource "route-foo.googleapis.com" (Omitted).

    ScheduledTask rdsRespTimer =
        Iterables.getOnlyElement(
            fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    assertThat(rdsRespTimer.isCancelled()).isFalse();
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC - 1, TimeUnit.SECONDS);

    // RPC stream is broken while the initial fetch for the resource is not complete.
    responseObserver.onError(Status.UNAVAILABLE.asException());
    assertThat(rdsRespTimer.isCancelled()).isTrue();

    // Reset backoff and retry immediately.
    inOrder.verify(backoffPolicyProvider).get();
    fakeClock.runDueTasks();
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    responseObserver = responseObserverCaptor.getValue();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    ScheduledTask ldsRespTimer =
        Iterables.getOnlyElement(
            fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    assertThat(ldsRespTimer.getDelay(TimeUnit.SECONDS))
        .isEqualTo(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC);

    // Client resumed requests and management server sends back LDS resources again.
    verify(requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "", TARGET_AUTHORITY,
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    responseObserver.onNext(response);

    // Client sent an RDS request for resource "route-foo.googleapis.com" (Omitted).

    assertThat(ldsRespTimer.isCancelled()).isTrue();
    rdsRespTimer =
        Iterables.getOnlyElement(
            fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    assertThat(rdsRespTimer.getDelay(TimeUnit.SECONDS))
        .isEqualTo(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC);

    // Management server sends back an RDS response containing the RouteConfiguration
    // for the requested resource.
    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(
            buildRouteConfiguration(
                "route-foo.googleapis.com", // target route configuration
                ImmutableList.of(
                    buildVirtualHost(
                        ImmutableList.of(TARGET_AUTHORITY), // matching virtual host
                        "cluster-foo.googleapis.com")))));
    response = buildDiscoveryResponse("0", routeConfigs, XdsClientImpl.ADS_TYPE_URL_RDS, "0000");
    responseObserver.onNext(response);

    assertThat(rdsRespTimer.isCancelled()).isTrue();

    // Resets RPC stream again.
    responseObserver.onError(Status.UNAVAILABLE.asException());
    // Reset backoff and retry immediately.
    inOrder.verify(backoffPolicyProvider).get();
    fakeClock.runDueTasks();
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    responseObserver = responseObserverCaptor.getValue();

    // Client/server resumed LDS/RDS request/response (Omitted).

    // Start watching cluster data.
    xdsClient.watchCdsResource("cluster-foo.googleapis.com", cdsResourceWatcher);
    ScheduledTask cdsRespTimeoutTask =
        Iterables.getOnlyElement(
            fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    assertThat(cdsRespTimeoutTask.isCancelled()).isFalse();
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC - 1, TimeUnit.SECONDS);

    // RPC stream is broken while the initial fetch for the resource is not complete.
    responseObserver.onError(Status.UNAVAILABLE.asException());
    assertThat(cdsRespTimeoutTask.isCancelled()).isTrue();
    inOrder.verify(backoffPolicy2).nextBackoffNanos();
    assertThat(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER)).hasSize(1);

    // Retry after backoff.
    fakeClock.forwardNanos(20L);
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    responseObserver = responseObserverCaptor.getValue();

    // Timer is rescheduled as the client restarts the resource fetch.
    cdsRespTimeoutTask =
        Iterables.getOnlyElement(
            fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    assertThat(cdsRespTimeoutTask.isCancelled()).isFalse();
    assertThat(cdsRespTimeoutTask.getDelay(TimeUnit.SECONDS))
        .isEqualTo(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC);

    // Start watching endpoint data.
    xdsClient.watchEdsResource("cluster-foo.googleapis.com", edsResourceWatcher);
    ScheduledTask edsTimeoutTask =
        Iterables.getOnlyElement(
            fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    assertThat(edsTimeoutTask.getDelay(TimeUnit.SECONDS))
        .isEqualTo(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC);

    // RPC stream is broken again.
    responseObserver.onError(Status.UNAVAILABLE.asException());

    assertThat(edsTimeoutTask.isCancelled()).isTrue();
    inOrder.verify(backoffPolicy2).nextBackoffNanos();
    assertThat(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER)).hasSize(1);

    fakeClock.forwardNanos(200L);
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());

    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);
    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);
  }

  /**
   * Tests sending a streaming LRS RPC for each cluster to report loads for.
   */
  @Test
  public void reportLoadStatsToServer() {
    String clusterName = "cluster-foo.googleapis.com";
    xdsClient.addClientStats(clusterName, null);
    ArgumentCaptor<LoadStatsRequest> requestCaptor = ArgumentCaptor.forClass(null);
    xdsClient.reportClientStats();
    LoadReportCall lrsCall = loadReportCalls.poll();
    verify(lrsCall.requestObserver).onNext(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getClusterStatsCount())
        .isEqualTo(0);  // initial request

    lrsCall.responseObserver.onNext(
        LoadStatsResponse.newBuilder()
            .addClusters(clusterName)
            .setLoadReportingInterval(Durations.fromNanos(1000L))
            .build());
    fakeClock.forwardNanos(1000L);
    verify(lrsCall.requestObserver, times(2)).onNext(requestCaptor.capture());
    ClusterStats report = Iterables.getOnlyElement(requestCaptor.getValue().getClusterStatsList());
    assertThat(report.getClusterName()).isEqualTo(clusterName);

    xdsClient.removeClientStats(clusterName, null);
    fakeClock.forwardNanos(1000L);
    verify(lrsCall.requestObserver, times(3)).onNext(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getClusterStatsCount())
        .isEqualTo(0);  // no more stats reported

    xdsClient.cancelClientStatsReport();
    assertThat(lrsEnded.get()).isTrue();
    // See more test on LoadReportClientTest.java
  }

  // Simulates the use case of watching clusters/endpoints based on service config resolved by
  // LDS/RDS.
  private void waitUntilConfigResolved(StreamObserver<DiscoveryResponse> responseObserver) {
    // Client sent an LDS request for resource TARGET_AUTHORITY (Omitted).

    // Management server responses with a listener telling client to do RDS.
    Rds rdsConfig =
        Rds.newBuilder()
            .setConfigSource(
                ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance()))
            .setRouteConfigName("route-foo.googleapis.com")
            .build();

    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(TARGET_AUTHORITY, /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRds(rdsConfig).build())))
    );
    DiscoveryResponse ldsResponse =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(ldsResponse);

    // Client sent an LDS ACK request and an RDS request for resource
    // "route-foo.googleapis.com" (Omitted).

    // Management server sends an RDS response.
    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(
            buildRouteConfiguration(
                "route-foo.googleapis.com", // target route configuration
                ImmutableList.of(
                    buildVirtualHost(
                        ImmutableList.of(TARGET_AUTHORITY), // matching virtual host
                        "cluster.googleapis.com")))));
    DiscoveryResponse rdsResponse =
        buildDiscoveryResponse("0", routeConfigs, XdsClientImpl.ADS_TYPE_URL_RDS, "0000");
    responseObserver.onNext(rdsResponse);
  }

  @Test
  public void matchHostName_exactlyMatch() {
    String pattern = "foo.googleapis.com";
    assertThat(XdsClientImpl.matchHostName("bar.googleapis.com", pattern)).isFalse();
    assertThat(XdsClientImpl.matchHostName("fo.googleapis.com", pattern)).isFalse();
    assertThat(XdsClientImpl.matchHostName("oo.googleapis.com", pattern)).isFalse();
    assertThat(XdsClientImpl.matchHostName("googleapis.com", pattern)).isFalse();
    assertThat(XdsClientImpl.matchHostName("foo.googleapis", pattern)).isFalse();
    assertThat(XdsClientImpl.matchHostName("foo.googleapis.com", pattern)).isTrue();
  }

  @Test
  public void matchHostName_prefixWildcard() {
    String pattern = "*.foo.googleapis.com";
    assertThat(XdsClientImpl.matchHostName("foo.googleapis.com", pattern)).isFalse();
    assertThat(XdsClientImpl.matchHostName("bar-baz.foo.googleapis", pattern)).isFalse();
    assertThat(XdsClientImpl.matchHostName("bar.foo.googleapis.com", pattern)).isTrue();
    pattern = "*-bar.foo.googleapis.com";
    assertThat(XdsClientImpl.matchHostName("bar.foo.googleapis.com", pattern)).isFalse();
    assertThat(XdsClientImpl.matchHostName("baz-bar.foo.googleapis", pattern)).isFalse();
    assertThat(XdsClientImpl.matchHostName("-bar.foo.googleapis.com", pattern)).isFalse();
    assertThat(XdsClientImpl.matchHostName("baz-bar.foo.googleapis.com", pattern))
        .isTrue();
  }

  @Test
  public void matchHostName_postfixWildCard() {
    String pattern = "foo.*";
    assertThat(XdsClientImpl.matchHostName("bar.googleapis.com", pattern)).isFalse();
    assertThat(XdsClientImpl.matchHostName("bar.foo.googleapis.com", pattern)).isFalse();
    assertThat(XdsClientImpl.matchHostName("foo.googleapis.com", pattern)).isTrue();
    assertThat(XdsClientImpl.matchHostName("foo.com", pattern)).isTrue();
    pattern = "foo-*";
    assertThat(XdsClientImpl.matchHostName("bar-.googleapis.com", pattern)).isFalse();
    assertThat(XdsClientImpl.matchHostName("foo.googleapis.com", pattern)).isFalse();
    assertThat(XdsClientImpl.matchHostName("foo.googleapis.com", pattern)).isFalse();
    assertThat(XdsClientImpl.matchHostName("foo-", pattern)).isFalse();
    assertThat(XdsClientImpl.matchHostName("foo-bar.com", pattern)).isTrue();
    assertThat(XdsClientImpl.matchHostName("foo-.com", pattern)).isTrue();
    assertThat(XdsClientImpl.matchHostName("foo-bar", pattern)).isTrue();
  }

  @Test
  public void findVirtualHostForHostName_exactMatchFirst() {
    String hostname = "a.googleapis.com";
    VirtualHost vHost1 =
        VirtualHost.newBuilder()
            .setName("virtualhost01.googleapis.com")  // don't care
            .addAllDomains(ImmutableList.of("a.googleapis.com", "b.googleapis.com"))
            .build();
    VirtualHost vHost2 =
        VirtualHost.newBuilder()
            .setName("virtualhost02.googleapis.com")  // don't care
            .addAllDomains(ImmutableList.of("*.googleapis.com"))
            .build();
    VirtualHost vHost3 =
        VirtualHost.newBuilder()
            .setName("virtualhost03.googleapis.com")  // don't care
            .addAllDomains(ImmutableList.of("*"))
            .build();
    RouteConfiguration routeConfig =
        RouteConfiguration.newBuilder()
            .setName("route-foo.googleapis.com")
            .addAllVirtualHosts(ImmutableList.of(vHost1, vHost2, vHost3))
            .build();
    assertThat(XdsClientImpl.findVirtualHostForHostName(routeConfig, hostname)).isEqualTo(vHost1);
  }

  @Test
  public void findVirtualHostForHostName_preferSuffixDomainOverPrefixDomain() {
    String hostname = "a.googleapis.com";
    VirtualHost vHost1 =
        VirtualHost.newBuilder()
            .setName("virtualhost01.googleapis.com")  // don't care
            .addAllDomains(ImmutableList.of("*.googleapis.com", "b.googleapis.com"))
            .build();
    VirtualHost vHost2 =
        VirtualHost.newBuilder()
            .setName("virtualhost02.googleapis.com")  // don't care
            .addAllDomains(ImmutableList.of("a.googleapis.*"))
            .build();
    VirtualHost vHost3 =
        VirtualHost.newBuilder()
            .setName("virtualhost03.googleapis.com")  // don't care
            .addAllDomains(ImmutableList.of("*"))
            .build();
    RouteConfiguration routeConfig =
        RouteConfiguration.newBuilder()
            .setName("route-foo.googleapis.com")
            .addAllVirtualHosts(ImmutableList.of(vHost1, vHost2, vHost3))
            .build();
    assertThat(XdsClientImpl.findVirtualHostForHostName(routeConfig, hostname)).isEqualTo(vHost1);
  }

  @Test
  public void findVirtualHostForHostName_asteriskMatchAnyDomain() {
    String hostname = "a.googleapis.com";
    VirtualHost vHost1 =
        VirtualHost.newBuilder()
            .setName("virtualhost01.googleapis.com")  // don't care
            .addAllDomains(ImmutableList.of("*"))
            .build();
    VirtualHost vHost2 =
        VirtualHost.newBuilder()
            .setName("virtualhost02.googleapis.com")  // don't care
            .addAllDomains(ImmutableList.of("b.googleapis.com"))
            .build();
    RouteConfiguration routeConfig =
        RouteConfiguration.newBuilder()
            .setName("route-foo.googleapis.com")
            .addAllVirtualHosts(ImmutableList.of(vHost1, vHost2))
            .build();
    assertThat(XdsClientImpl.findVirtualHostForHostName(routeConfig, hostname)).isEqualTo(vHost1);
  }

  @Test
  public void populateRoutesInVirtualHost_routeWithCaseInsensitiveMatch() {
    VirtualHost virtualHost =
        VirtualHost.newBuilder()
            .setName("virtualhost00.googleapis.com")  // don't care
            .addDomains(TARGET_AUTHORITY)
            .addRoutes(
                Route.newBuilder()
                    .setRoute(RouteAction.newBuilder().setCluster("cluster.googleapis.com"))
                    .setMatch(
                        RouteMatch.newBuilder()
                            .setPrefix("")
                            .setCaseSensitive(BoolValue.newBuilder().setValue(false))))
            .build();

    thrown.expect(XdsClientImpl.InvalidProtoDataException.class);
    XdsClientImpl.populateRoutesInVirtualHost(virtualHost);
  }

  @Test
  public void populateRoutesInVirtualHost_NoUsableRoute() {
    VirtualHost virtualHost =
        VirtualHost.newBuilder()
            .setName("virtualhost00.googleapis.com")  // don't care
            .addDomains(TARGET_AUTHORITY)
            .addRoutes(
                // route with unsupported action
                Route.newBuilder()
                    .setRoute(RouteAction.newBuilder().setClusterHeader("cluster header string"))
                    .setMatch(RouteMatch.newBuilder().setPrefix("/")))
            .addRoutes(
                // route with unsupported matcher type
                Route.newBuilder()
                    .setRoute(RouteAction.newBuilder().setCluster("cluster.googleapis.com"))
                    .setMatch(
                        RouteMatch.newBuilder()
                            .setPrefix("/")
                            .addQueryParameters(QueryParameterMatcher.getDefaultInstance())))
            .build();

    thrown.expect(XdsClientImpl.InvalidProtoDataException.class);
    XdsClientImpl.populateRoutesInVirtualHost(virtualHost);
  }

  @Test
  public void messagePrinter_printLdsResponse() {
    MessagePrinter printer = new MessagePrinter();
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener("foo.googleapis.com:8080",
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRouteConfig(
                        buildRouteConfiguration("route-foo.googleapis.com",
                            ImmutableList.of(
                                buildVirtualHost(
                                    ImmutableList.of("foo.googleapis.com", "bar.googleapis.com"),
                                    "cluster.googleapis.com"))))
                    .build()))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");

    String expectedString = "{\n"
        + "  \"versionInfo\": \"0\",\n"
        + "  \"resources\": [{\n"
        + "    \"@type\": \"type.googleapis.com/envoy.config.listener.v3.Listener\",\n"
        + "    \"name\": \"foo.googleapis.com:8080\",\n"
        + "    \"address\": {\n"
        + "    },\n"
        + "    \"filterChains\": [{\n"
        + "    }],\n"
        + "    \"apiListener\": {\n"
        + "      \"apiListener\": {\n"
        + "        \"@type\": \"type.googleapis.com/envoy.extensions.filters.network"
        + ".http_connection_manager.v3.HttpConnectionManager\",\n"
        + "        \"routeConfig\": {\n"
        + "          \"name\": \"route-foo.googleapis.com\",\n"
        + "          \"virtualHosts\": [{\n"
        + "            \"name\": \"virtualhost00.googleapis.com\",\n"
        + "            \"domains\": [\"foo.googleapis.com\", \"bar.googleapis.com\"],\n"
        + "            \"routes\": [{\n"
        + "              \"match\": {\n"
        + "                \"prefix\": \"\"\n"
        + "              },\n"
        + "              \"route\": {\n"
        + "                \"cluster\": \"cluster.googleapis.com\"\n"
        + "              }\n"
        + "            }]\n"
        + "          }]\n"
        + "        }\n"
        + "      }\n"
        + "    }\n"
        + "  }],\n"
        + "  \"typeUrl\": \"type.googleapis.com/envoy.config.listener.v3.Listener\",\n"
        + "  \"nonce\": \"0000\"\n"
        + "}";
    String res = printer.print(response);
    assertThat(res).isEqualTo(expectedString);
  }

  @Test
  public void messagePrinter_printRdsResponse() {
    MessagePrinter printer = new MessagePrinter();
    List<Any> routeConfigs =
        ImmutableList.of(
            Any.pack(
                buildRouteConfiguration(
                    "route-foo.googleapis.com",
                    ImmutableList.of(
                        buildVirtualHost(
                            ImmutableList.of("foo.googleapis.com", "bar.googleapis.com"),
                            "cluster.googleapis.com")))));
    DiscoveryResponse response =
        buildDiscoveryResponse("213", routeConfigs, XdsClientImpl.ADS_TYPE_URL_RDS, "0052");

    String expectedString = "{\n"
        + "  \"versionInfo\": \"213\",\n"
        + "  \"resources\": [{\n"
        + "    \"@type\": \"type.googleapis.com/envoy.config.route.v3.RouteConfiguration\",\n"
        + "    \"name\": \"route-foo.googleapis.com\",\n"
        + "    \"virtualHosts\": [{\n"
        + "      \"name\": \"virtualhost00.googleapis.com\",\n"
        + "      \"domains\": [\"foo.googleapis.com\", \"bar.googleapis.com\"],\n"
        + "      \"routes\": [{\n"
        + "        \"match\": {\n"
        + "          \"prefix\": \"\"\n"
        + "        },\n"
        + "        \"route\": {\n"
        + "          \"cluster\": \"cluster.googleapis.com\"\n"
        + "        }\n"
        + "      }]\n"
        + "    }]\n"
        + "  }],\n"
        + "  \"typeUrl\": \"type.googleapis.com/envoy.config.route.v3.RouteConfiguration\",\n"
        + "  \"nonce\": \"0052\"\n"
        + "}";
    String res = printer.print(response);
    assertThat(res).isEqualTo(expectedString);
  }

  @Test
  public void messagePrinter_printCdsResponse() {
    MessagePrinter printer = new MessagePrinter();
    List<Any> clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster-bar.googleapis.com", "service-blaze:cluster-bar", true)),
        Any.pack(buildCluster("cluster-foo.googleapis.com", null, false)));
    DiscoveryResponse response =
        buildDiscoveryResponse("14", clusters, XdsClientImpl.ADS_TYPE_URL_CDS, "8");

    String expectedString = "{\n"
        + "  \"versionInfo\": \"14\",\n"
        + "  \"resources\": [{\n"
        + "    \"@type\": \"type.googleapis.com/envoy.config.cluster.v3.Cluster\",\n"
        + "    \"name\": \"cluster-bar.googleapis.com\",\n"
        + "    \"type\": \"EDS\",\n"
        + "    \"edsClusterConfig\": {\n"
        + "      \"edsConfig\": {\n"
        + "        \"ads\": {\n"
        + "        }\n"
        + "      },\n"
        + "      \"serviceName\": \"service-blaze:cluster-bar\"\n"
        + "    },\n"
        + "    \"lrsServer\": {\n"
        + "      \"self\": {\n"
        + "      }\n"
        + "    }\n"
        + "  }, {\n"
        + "    \"@type\": \"type.googleapis.com/envoy.config.cluster.v3.Cluster\",\n"
        + "    \"name\": \"cluster-foo.googleapis.com\",\n"
        + "    \"type\": \"EDS\",\n"
        + "    \"edsClusterConfig\": {\n"
        + "      \"edsConfig\": {\n"
        + "        \"ads\": {\n"
        + "        }\n"
        + "      }\n"
        + "    }\n"
        + "  }],\n"
        + "  \"typeUrl\": \"type.googleapis.com/envoy.config.cluster.v3.Cluster\",\n"
        + "  \"nonce\": \"8\"\n"
        + "}";
    String res = printer.print(response);
    assertThat(res).isEqualTo(expectedString);
  }

  @Test
  public void messagePrinter_printEdsResponse() {
    MessagePrinter printer = new MessagePrinter();
    List<Any> clusterLoadAssignments = ImmutableList.of(
        Any.pack(buildClusterLoadAssignment("cluster-foo.googleapis.com",
            ImmutableList.of(
                buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2)),
                    1, 0),
                buildLocalityLbEndpoints("region3", "zone3", "subzone3",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.142.5", 80, HealthStatus.UNHEALTHY, 5)),
                    2, 1)),
            ImmutableList.of(
                buildDropOverload("lb", 200),
                buildDropOverload("throttle", 1000)))));

    DiscoveryResponse response =
        buildDiscoveryResponse("5", clusterLoadAssignments,
            XdsClientImpl.ADS_TYPE_URL_EDS, "004");

    String expectedString = "{\n"
        + "  \"versionInfo\": \"5\",\n"
        + "  \"resources\": [{\n"
        + "    \"@type\": \"type.googleapis.com/envoy.config.endpoint.v3.ClusterLoadAssignment\",\n"
        + "    \"clusterName\": \"cluster-foo.googleapis.com\",\n"
        + "    \"endpoints\": [{\n"
        + "      \"locality\": {\n"
        + "        \"region\": \"region1\",\n"
        + "        \"zone\": \"zone1\",\n"
        + "        \"subZone\": \"subzone1\"\n"
        + "      },\n"
        + "      \"lbEndpoints\": [{\n"
        + "        \"endpoint\": {\n"
        + "          \"address\": {\n"
        + "            \"socketAddress\": {\n"
        + "              \"address\": \"192.168.0.1\",\n"
        + "              \"portValue\": 8080\n"
        + "            }\n"
        + "          }\n"
        + "        },\n"
        + "        \"healthStatus\": \"HEALTHY\",\n"
        + "        \"loadBalancingWeight\": 2\n"
        + "      }],\n"
        + "      \"loadBalancingWeight\": 1\n"
        + "    }, {\n"
        + "      \"locality\": {\n"
        + "        \"region\": \"region3\",\n"
        + "        \"zone\": \"zone3\",\n"
        + "        \"subZone\": \"subzone3\"\n"
        + "      },\n"
        + "      \"lbEndpoints\": [{\n"
        + "        \"endpoint\": {\n"
        + "          \"address\": {\n"
        + "            \"socketAddress\": {\n"
        + "              \"address\": \"192.168.142.5\",\n"
        + "              \"portValue\": 80\n"
        + "            }\n"
        + "          }\n"
        + "        },\n"
        + "        \"healthStatus\": \"UNHEALTHY\",\n"
        + "        \"loadBalancingWeight\": 5\n"
        + "      }],\n"
        + "      \"loadBalancingWeight\": 2,\n"
        + "      \"priority\": 1\n"
        + "    }],\n"
        + "    \"policy\": {\n"
        + "      \"dropOverloads\": [{\n"
        + "        \"category\": \"lb\",\n"
        + "        \"dropPercentage\": {\n"
        + "          \"numerator\": 200,\n"
        + "          \"denominator\": \"MILLION\"\n"
        + "        }\n"
        + "      }, {\n"
        + "        \"category\": \"throttle\",\n"
        + "        \"dropPercentage\": {\n"
        + "          \"numerator\": 1000,\n"
        + "          \"denominator\": \"MILLION\"\n"
        + "        }\n"
        + "      }]\n"
        + "    }\n"
        + "  }],\n"
        + "  \"typeUrl\": \"type.googleapis.com/envoy.config.endpoint.v3.ClusterLoadAssignment\",\n"
        + "  \"nonce\": \"004\"\n"
        + "}";
    String res = printer.print(response);
    assertThat(res).isEqualTo(expectedString);
  }

  private static void assertConfigUpdateContainsSingleClusterRoute(
      ConfigUpdate configUpdate, String expectedClusterName) {
    List<EnvoyProtoData.Route> routes = configUpdate.getRoutes();
    assertThat(routes).hasSize(1);
    assertThat(Iterables.getOnlyElement(routes).getRouteAction().getCluster())
        .isEqualTo(expectedClusterName);
  }

  /**
   * Matcher for DiscoveryRequest without the comparison of error_details field, which is used for
   * management server debugging purposes.
   *
   * <p>In general, if you are sure error_details field should not be set in a DiscoveryRequest,
   * compare with message equality. Otherwise, this matcher is handy for comparing other fields
   * only.
   */
  private static class DiscoveryRequestMatcher implements ArgumentMatcher<DiscoveryRequest> {
    private final String versionInfo;
    private final String typeUrl;
    private final Set<String> resourceNames;
    private final String responseNonce;

    private DiscoveryRequestMatcher(String versionInfo, String resourceName, String typeUrl,
        String responseNonce) {
      this(versionInfo, ImmutableList.of(resourceName), typeUrl, responseNonce);
    }

    private DiscoveryRequestMatcher(
        String versionInfo, List<String> resourceNames, String typeUrl, String responseNonce) {
      this.versionInfo = versionInfo;
      this.resourceNames = new HashSet<>(resourceNames);
      this.typeUrl = typeUrl;
      this.responseNonce = responseNonce;
    }

    @Override
    public boolean matches(DiscoveryRequest argument) {
      if (!typeUrl.equals(argument.getTypeUrl())) {
        return false;
      }
      if (!versionInfo.equals(argument.getVersionInfo())) {
        return false;
      }
      if (!responseNonce.equals(argument.getResponseNonce())) {
        return false;
      }
      if (!resourceNames.equals(new HashSet<>(argument.getResourceNamesList()))) {
        return false;
      }
      return argument.getNode().equals(NODE.toEnvoyProtoNode());
    }
  }

  private static class LoadReportCall {
    private final StreamObserver<LoadStatsRequest> requestObserver;
    @SuppressWarnings("unused")
    private final StreamObserver<LoadStatsResponse> responseObserver;

    LoadReportCall(StreamObserver<LoadStatsRequest> requestObserver,
        StreamObserver<LoadStatsResponse> responseObserver) {
      this.requestObserver = requestObserver;
      this.responseObserver = responseObserver;
    }
  }
}
