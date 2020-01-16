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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Any;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.RouteConfiguration;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.envoyproxy.envoy.api.v2.core.AggregatedConfigSource;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import io.envoyproxy.envoy.api.v2.core.HealthStatus;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats;
import io.envoyproxy.envoy.api.v2.route.RedirectAction;
import io.envoyproxy.envoy.api.v2.route.Route;
import io.envoyproxy.envoy.api.v2.route.VirtualHost;
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManager;
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.Rds;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.envoyproxy.envoy.service.load_stats.v2.LoadReportingServiceGrpc.LoadReportingServiceImplBase;
import io.envoyproxy.envoy.service.load_stats.v2.LoadStatsRequest;
import io.envoyproxy.envoy.service.load_stats.v2.LoadStatsResponse;
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
import io.grpc.xds.Bootstrapper.ChannelCreds;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.LbEndpoint;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.XdsClient.ClusterUpdate;
import io.grpc.xds.XdsClient.ClusterWatcher;
import io.grpc.xds.XdsClient.ConfigUpdate;
import io.grpc.xds.XdsClient.ConfigWatcher;
import io.grpc.xds.XdsClient.EndpointUpdate;
import io.grpc.xds.XdsClient.EndpointWatcher;
import io.grpc.xds.XdsClient.XdsChannelFactory;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link XdsClientImpl}.
 */
@RunWith(JUnit4.class)
public class XdsClientImplTest {

  private static final String HOSTNAME = "foo.googleapis.com";
  private static final int PORT = 8080;

  private static final Node NODE = Node.getDefaultInstance();
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
          return command.toString()
              .contains(XdsClientImpl.CdsResourceFetchTimeoutTask.class.getSimpleName());
        }
      };

  private static final FakeClock.TaskFilter EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString()
              .contains(XdsClientImpl.EdsResourceFetchTimeoutTask.class.getSimpleName());
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

  private final Queue<LoadReportCall> loadReportCalls = new ArrayDeque<>();
  private final AtomicInteger runningLrsCalls = new AtomicInteger();

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
  private ClusterWatcher clusterWatcher;
  @Mock
  private EndpointWatcher endpointWatcher;

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

    LoadReportingServiceImplBase lrsServiceImpl = new LoadReportingServiceImplBase() {
      @Override
      public StreamObserver<LoadStatsRequest> streamLoadStats(
          StreamObserver<LoadStatsResponse> responseObserver) {
        runningLrsCalls.getAndIncrement();
        @SuppressWarnings("unchecked")
        StreamObserver<LoadStatsRequest> requestObserver = mock(StreamObserver.class);
        final LoadReportCall call = new LoadReportCall(requestObserver, responseObserver);
        Context.current().addListener(
            new CancellationListener() {
              @Override
              public void cancelled(Context context) {
                call.cancelled = true;
                runningLrsCalls.getAndDecrement();
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
        new XdsClientImpl(servers, channelFactory, NODE, syncContext,
            fakeClock.getScheduledExecutorService(), backoffPolicyProvider,
            fakeClock.getStopwatchSupplier());
    // Only the connection to management server is established, no RPC request is sent until at
    // least one watcher is registered.
    assertThat(responseObservers).isEmpty();
    assertThat(requestObservers).isEmpty();

    // Load reporting is not initiated until being invoked to do so.
    assertThat(loadReportCalls).isEmpty();
    assertThat(runningLrsCalls.get()).isEqualTo(0);
  }

  @After
  public void tearDown() {
    xdsClient.shutdown();
    assertThat(callEnded.get()).isTrue();
    assertThat(runningLrsCalls.get()).isEqualTo(0);
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
   * The config watcher is notified with an error after its response timer expires.
   */
  @Test
  public void ldsResponseWithoutMatchingResource() {
    xdsClient.watchConfigData(HOSTNAME, PORT, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request for the host name (with port) to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
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
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "foo.googleapis.com:8080",
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
   * An LDS response contains the requested listener and an in-lined RouteConfiguration message for
   * that listener. But the RouteConfiguration message is invalid as it does not contain any
   * VirtualHost with domains matching the requested hostname.
   * The LDS response is NACKed, as if the XdsClient has not received this response.
   * The config watcher is notified with an error after its response timer expires..
   */
  @Test
  public void failToFindVirtualHostInLdsResponseInLineRouteConfig() {
    xdsClient.watchConfigData(HOSTNAME, PORT, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request for the host name (with port) to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    RouteConfiguration routeConfig =
        buildRouteConfiguration(
            "route.googleapis.com",
            ImmutableList.of(
                buildVirtualHost(ImmutableList.of("something does not match"),
                    "some cluster"),
                buildVirtualHost(ImmutableList.of("something else does not match"),
                    "some other cluster")));

    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener("foo.googleapis.com:8080", /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRouteConfig(routeConfig).build()))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    // Client sends an NACK LDS request.
    verify(requestObserver)
        .onNext(
            argThat(new DiscoveryRequestMatcher("", "foo.googleapis.com:8080",
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
   * Client resolves the virtual host config from an LDS response that contains a
   * RouteConfiguration message directly in-line for the requested resource. No RDS is needed.
   * The LDS response is ACKed.
   * The config watcher is notified with an update.
   */
  @Test
  public void resolveVirtualHostInLdsResponse() {
    xdsClient.watchConfigData(HOSTNAME, PORT, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request for the host name (with port) to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
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
        Any.pack(buildListener("foo.googleapis.com:8080", /* matching resource */
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRouteConfig(
                        buildRouteConfiguration("route-foo.googleapis.com",
                            ImmutableList.of(
                                buildVirtualHost(
                                    ImmutableList.of("foo.googleapis.com", "bar.googleapis.com"),
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
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "foo.googleapis.com:8080",
            XdsClientImpl.ADS_TYPE_URL_LDS, "0000")));

    ArgumentCaptor<ConfigUpdate> configUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher).onConfigChanged(configUpdateCaptor.capture());
    assertThat(configUpdateCaptor.getValue().getClusterName()).isEqualTo("cluster.googleapis.com");

    verifyNoMoreInteractions(requestObserver);
  }

  /**
   * Client receives an RDS response (after a previous LDS request-response) that does not contain a
   * RouteConfiguration for the requested resource while each received RouteConfiguration is valid.
   * The RDS response is ACKed.
   * After the resource fetch timeout expires, watcher waiting for the resource is notified
   * with a resource not found error.
   */
  @Test
  public void rdsResponseWithoutMatchingResource() {
    xdsClient.watchConfigData(HOSTNAME, PORT, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request for the host name (with port) to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));

    Rds rdsConfig =
        Rds.newBuilder()
            // Must set to use ADS.
            .setConfigSource(
                ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance()))
            .setRouteConfigName("route-foo.googleapis.com")
            .build();
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener("foo.googleapis.com:8080", /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRds(rdsConfig).build())))
    );
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "foo.googleapis.com:8080",
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
                    buildVirtualHost(ImmutableList.of("foo.googleapis.com"),
                        "whatever cluster")))),
        Any.pack(
            buildRouteConfiguration(
                "some other resource name does not match route-foo.googleapis.com",
                ImmutableList.of(
                    buildVirtualHost(ImmutableList.of("foo.googleapis.com"),
                        "some more whatever cluster")))));
    response = buildDiscoveryResponse("0", routeConfigs, XdsClientImpl.ADS_TYPE_URL_RDS, "0000");
    responseObserver.onNext(response);

    // Client sends an ACK RDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "route-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_RDS, "0000")));

    verify(configWatcher, never()).onConfigChanged(any(ConfigUpdate.class));
    verify(configWatcher, never()).onError(any(Status.class));
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.NOT_FOUND);
    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  /**
   * Client resolves the virtual host config from an RDS response for the requested resource. The
   * RDS response is ACKed.
   * The config watcher is notified with an update.
   */
  @Test
  public void resolveVirtualHostInRdsResponse() {
    xdsClient.watchConfigData(HOSTNAME, PORT, configWatcher);
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
        Any.pack(buildListener("foo.googleapis.com:8080", /* matching resource */
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
                "route-foo.googleapis.com",
                ImmutableList.of(
                    buildVirtualHost(ImmutableList.of("something does not match"),
                        "some cluster"),
                    buildVirtualHost(ImmutableList.of("foo.googleapis.com", "bar.googleapis.com"),
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
    assertThat(configUpdateCaptor.getValue().getClusterName()).isEqualTo("cluster.googleapis.com");
  }

  /**
   * Client receives an RDS response (after a previous LDS request-response) containing a
   * RouteConfiguration message for the requested resource. But the RouteConfiguration message
   * is invalid as it does not contain any VirtualHost with domains matching the requested
   * hostname.
   * The RDS response is NACKed, as if the XdsClient has not received this response.
   * The config watcher is NOT notified with an error.
   */
  @Test
  public void failToFindVirtualHostInRdsResponse() {
    xdsClient.watchConfigData(HOSTNAME, PORT, configWatcher);
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
        Any.pack(buildListener("foo.googleapis.com:8080", /* matching resource */
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
    verify(configWatcher, never()).onError(any(Status.class));
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.NOT_FOUND);
    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  /**
   * Client receives an RDS response (after a previous LDS request-response) containing a
   * RouteConfiguration message for the requested resource. But the RouteConfiguration message
   * is invalid as the VirtualHost with domains matching the requested hostname contains invalid
   * data, its RouteAction message is absent.
   * The RDS response is NACKed, as if the XdsClient has not received this response.
   * The config watcher is NOT notified with an error.
   */
  @Test
  public void matchingVirtualHostDoesNotContainRouteAction() {
    xdsClient.watchConfigData(HOSTNAME, PORT, configWatcher);
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
        Any.pack(buildListener("foo.googleapis.com:8080", /* matching resource */
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
            .setName("virtualhost00.googleapis.com")  // don't care
            .addDomains("foo.googleapis.com")
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
    verify(configWatcher, never()).onError(any(Status.class));
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.NOT_FOUND);
    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  /**
   * Client receives LDS/RDS responses for updating resources previously received.
   *
   * <p>Tests for streaming behavior.
   */
  @Test
  public void notifyUpdatedResources() {
    xdsClient.watchConfigData(HOSTNAME, PORT, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request for the host name (with port) to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));

    // Management server sends back an LDS response containing a RouteConfiguration for the
    // requested Listener directly in-line.
    RouteConfiguration routeConfig =
        buildRouteConfiguration(
            "route-foo.googleapis.com",
            ImmutableList.of(
                buildVirtualHost(ImmutableList.of("foo.googleapis.com", "bar.googleapis.com"),
                    "cluster.googleapis.com"),
                buildVirtualHost(ImmutableList.of("something does not match"),
                    "some cluster")));

    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener("foo.googleapis.com:8080", /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRouteConfig(routeConfig).build())))
    );
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "foo.googleapis.com:8080",
            XdsClientImpl.ADS_TYPE_URL_LDS, "0000")));

    // Cluster name is resolved and notified to config watcher.
    ArgumentCaptor<ConfigUpdate> configUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher).onConfigChanged(configUpdateCaptor.capture());
    assertThat(configUpdateCaptor.getValue().getClusterName()).isEqualTo("cluster.googleapis.com");

    // Management sends back another LDS response containing updates for the requested Listener.
    routeConfig =
        buildRouteConfiguration(
            "another-route-foo.googleapis.com",
            ImmutableList.of(
                buildVirtualHost(ImmutableList.of("foo.googleapis.com", "bar.googleapis.com"),
                    "another-cluster.googleapis.com"),
                buildVirtualHost(ImmutableList.of("something does not match"),
                    "some cluster")));

    listeners = ImmutableList.of(
        Any.pack(buildListener("foo.googleapis.com:8080", /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRouteConfig(routeConfig).build())))
    );
    response =
        buildDiscoveryResponse("1", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0001");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "1", "foo.googleapis.com:8080",
            XdsClientImpl.ADS_TYPE_URL_LDS, "0001")));

    // Updated cluster name is notified to config watcher.
    configUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher, times(2)).onConfigChanged(configUpdateCaptor.capture());
    assertThat(configUpdateCaptor.getValue().getClusterName())
        .isEqualTo("another-cluster.googleapis.com");

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
        Any.pack(buildListener("foo.googleapis.com:8080", /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRds(rdsConfig).build())))
    );
    response =
        buildDiscoveryResponse("2", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0002");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "2", "foo.googleapis.com:8080",
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
                    buildVirtualHost(ImmutableList.of("foo.googleapis.com", "bar.googleapis.com"),
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
    assertThat(configUpdateCaptor.getValue().getClusterName())
        .isEqualTo("some-other-cluster.googleapis.com");

    // Management server sends back another RDS response containing updated information for the
    // RouteConfiguration currently in-use by client.
    routeConfigs = ImmutableList.of(
        Any.pack(
            buildRouteConfiguration(
                "some-route-to-foo.googleapis.com",
                ImmutableList.of(
                    buildVirtualHost(ImmutableList.of("foo.googleapis.com", "bar.googleapis.com"),
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
    assertThat(configUpdateCaptor.getValue().getClusterName())
        .isEqualTo("an-updated-cluster.googleapis.com");

    // Management server sends back an LDS response indicating all Listener resources are removed.
    response =
        buildDiscoveryResponse("3", ImmutableList.<Any>of(),
            XdsClientImpl.ADS_TYPE_URL_LDS, "0003");
    responseObserver.onNext(response);

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.NOT_FOUND);
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
    xdsClient.watchConfigData(HOSTNAME, PORT, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request for the host name (with port) to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
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
        Any.pack(buildListener("foo.googleapis.com:8080", /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRds(rdsConfig).build())))
    );
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "foo.googleapis.com:8080",
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
                    buildVirtualHost(ImmutableList.of("foo.googleapis.com"),
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
                "route-foo.googleapis.com",
                ImmutableList.of(
                    buildVirtualHost(ImmutableList.of("something does not match"),
                        "some cluster"),
                    buildVirtualHost(ImmutableList.of("foo.googleapis.com", "bar.googleapis.com"),
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
    assertThat(configUpdateCaptor.getValue().getClusterName())
        .isEqualTo("another-cluster.googleapis.com");
    assertThat(rdsRespTimer.isCancelled()).isTrue();
  }

  /**
   * An RouteConfiguration is removed by server by sending client an LDS response removing the
   * corresponding Listener.
   */
  @Test
  public void routeConfigurationRemovedNotifiedToWatcher() {
    xdsClient.watchConfigData(HOSTNAME, PORT, configWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request for the host name (with port) to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
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
        Any.pack(buildListener("foo.googleapis.com:8080", /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRds(rdsConfig).build())))
    );
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "foo.googleapis.com:8080",
            XdsClientImpl.ADS_TYPE_URL_LDS, "0000")));

    // Client sends an (first) RDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "route-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_RDS, "")));

    // Management server sends back an RDS response containing RouteConfiguration requested.
    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(
            buildRouteConfiguration(
                "route-foo.googleapis.com",
                ImmutableList.of(
                    buildVirtualHost(ImmutableList.of("foo.googleapis.com"),
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
    assertThat(configUpdateCaptor.getValue().getClusterName()).isEqualTo("cluster.googleapis.com");

    // Management server sends back another LDS response with the previous Listener (currently
    // in-use by client) removed as the RouteConfiguration it references to is absent.
    response =
        buildDiscoveryResponse("1", ImmutableList.<com.google.protobuf.Any>of(), // empty
            XdsClientImpl.ADS_TYPE_URL_LDS, "0001");
    responseObserver.onNext(response);

    // Client sent an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "1", "foo.googleapis.com:8080",
            XdsClientImpl.ADS_TYPE_URL_LDS, "0001")));

    // Notify config watcher with an error.
    ArgumentCaptor<Status> errorStatusCaptor = ArgumentCaptor.forClass(null);
    verify(configWatcher).onError(errorStatusCaptor.capture());
    Status error = errorStatusCaptor.getValue();
    assertThat(error.getCode()).isEqualTo(Code.NOT_FOUND);
  }

  /**
   * Management server sends another LDS response for updating the RDS resource to be requested
   * while client is currently requesting for a previously given RDS resource name.
   */
  @Test
  public void updateRdsRequestResourceWhileInitialResourceFetchInProgress() {
    xdsClient.watchConfigData(HOSTNAME, PORT, configWatcher);
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
        Any.pack(buildListener("foo.googleapis.com:8080", /* matching resource */
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
        Any.pack(buildListener("foo.googleapis.com:8080", /* matching resource */
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
                "route-bar.googleapis.com",
                ImmutableList.of(
                    buildVirtualHost(ImmutableList.of("foo.googleapis.com"),
                        "cluster.googleapis.com")))));
    response = buildDiscoveryResponse("0", routeConfigs, XdsClientImpl.ADS_TYPE_URL_RDS, "0000");
    responseObserver.onNext(response);

    assertThat(rdsRespTimer.isCancelled()).isTrue();
  }

  /**
   * Client receives an CDS response that does not contain a Cluster for the requested resource
   * while each received Cluster is valid. The CDS response is ACKed. Cluster watchers are notified
   * with an error for resource not found after initial resource fetch timeout has expired.
   */
  @Test
  public void cdsResponseWithoutMatchingResource() {
    xdsClient.watchClusterData("cluster-foo.googleapis.com", clusterWatcher);
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
    verify(clusterWatcher, never()).onClusterChanged(any(ClusterUpdate.class));
    verify(clusterWatcher, never()).onError(any(Status.class));

    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    ArgumentCaptor<Status> errorStatusCaptor = ArgumentCaptor.forClass(null);
    verify(clusterWatcher).onError(errorStatusCaptor.capture());
    Status error = errorStatusCaptor.getValue();
    assertThat(error.getCode()).isEqualTo(Code.NOT_FOUND);
  }

  /**
   * Normal workflow of receiving a CDS response containing Cluster message for a requested
   * cluster.
   */
  @Test
  public void cdsResponseWithMatchingResource() {
    xdsClient.watchClusterData("cluster-foo.googleapis.com", clusterWatcher);
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

    ArgumentCaptor<ClusterUpdate> clusterUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(clusterWatcher).onClusterChanged(clusterUpdateCaptor.capture());
    ClusterUpdate clusterUpdate = clusterUpdateCaptor.getValue();
    assertThat(clusterUpdate.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(clusterUpdate.getEdsServiceName())
        .isEqualTo("cluster-foo.googleapis.com");  // default to cluster name
    assertThat(clusterUpdate.getLbPolicy()).isEqualTo("round_robin");
    assertThat(clusterUpdate.isEnableLrs()).isEqualTo(false);

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

    verify(clusterWatcher, times(2)).onClusterChanged(clusterUpdateCaptor.capture());
    clusterUpdate = clusterUpdateCaptor.getValue();
    assertThat(clusterUpdate.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(clusterUpdate.getEdsServiceName())
        .isEqualTo("eds-cluster-foo.googleapis.com");
    assertThat(clusterUpdate.getLbPolicy()).isEqualTo("round_robin");
    assertThat(clusterUpdate.isEnableLrs()).isEqualTo(true);
    assertThat(clusterUpdate.getLrsServerName()).isEqualTo("");
  }

  /**
   * CDS response containing UpstreamTlsContext for a cluster.
   */
  @Test
  public void cdsResponseWithUpstreamTlsContext() {
    xdsClient.watchClusterData("cluster-foo.googleapis.com", clusterWatcher);
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
    ArgumentCaptor<ClusterUpdate> clusterUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(clusterWatcher, times(1)).onClusterChanged(clusterUpdateCaptor.capture());
    ClusterUpdate clusterUpdate = clusterUpdateCaptor.getValue();
    assertThat(clusterUpdate.getUpstreamTlsContext()).isEqualTo(testUpstreamTlsContext);
  }

  @Test
  public void multipleClusterWatchers() {
    ClusterWatcher watcher1 = mock(ClusterWatcher.class);
    ClusterWatcher watcher2 = mock(ClusterWatcher.class);
    ClusterWatcher watcher3 = mock(ClusterWatcher.class);
    xdsClient.watchClusterData("cluster-foo.googleapis.com", watcher1);
    xdsClient.watchClusterData("cluster-foo.googleapis.com", watcher2);
    xdsClient.watchClusterData("cluster-bar.googleapis.com", watcher3);

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
    ArgumentCaptor<ClusterUpdate> clusterUpdateCaptor1 = ArgumentCaptor.forClass(null);
    verify(watcher1).onClusterChanged(clusterUpdateCaptor1.capture());
    ClusterUpdate clusterUpdate1 = clusterUpdateCaptor1.getValue();
    assertThat(clusterUpdate1.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(clusterUpdate1.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(clusterUpdate1.getEdsServiceName())
        .isEqualTo("cluster-foo.googleapis.com");  // default to cluster name
    assertThat(clusterUpdate1.getLbPolicy()).isEqualTo("round_robin");
    assertThat(clusterUpdate1.isEnableLrs()).isEqualTo(false);

    ArgumentCaptor<ClusterUpdate> clusterUpdateCaptor2 = ArgumentCaptor.forClass(null);
    verify(watcher2).onClusterChanged(clusterUpdateCaptor2.capture());
    ClusterUpdate clusterUpdate2 = clusterUpdateCaptor2.getValue();
    assertThat(clusterUpdate2.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(clusterUpdate2.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(clusterUpdate2.getEdsServiceName())
        .isEqualTo("cluster-foo.googleapis.com");  // default to cluster name
    assertThat(clusterUpdate2.getLbPolicy()).isEqualTo("round_robin");
    assertThat(clusterUpdate2.isEnableLrs()).isEqualTo(false);

    verify(watcher3, never()).onClusterChanged(any(ClusterUpdate.class));
    verify(watcher3, never()).onError(any(Status.class));

    // The other watcher gets an error notification for cluster not found after its timer expired.
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    ArgumentCaptor<Status> errorStatusCaptor = ArgumentCaptor.forClass(null);
    verify(watcher3).onError(errorStatusCaptor.capture());
    Status error = errorStatusCaptor.getValue();
    assertThat(error.getCode()).isEqualTo(Code.NOT_FOUND);
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

    // All watchers received notification for cluster update.
    verify(watcher1, times(2)).onClusterChanged(clusterUpdateCaptor1.capture());
    clusterUpdate1 = clusterUpdateCaptor1.getValue();
    assertThat(clusterUpdate1.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(clusterUpdate1.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(clusterUpdate1.getEdsServiceName())
        .isEqualTo("cluster-foo.googleapis.com");  // default to cluster name
    assertThat(clusterUpdate1.getLbPolicy()).isEqualTo("round_robin");
    assertThat(clusterUpdate1.isEnableLrs()).isEqualTo(false);

    clusterUpdateCaptor2 = ArgumentCaptor.forClass(null);
    verify(watcher2, times(2)).onClusterChanged(clusterUpdateCaptor2.capture());
    clusterUpdate2 = clusterUpdateCaptor2.getValue();
    assertThat(clusterUpdate2.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(clusterUpdate2.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(clusterUpdate2.getEdsServiceName())
        .isEqualTo("cluster-foo.googleapis.com");  // default to cluster name
    assertThat(clusterUpdate2.getLbPolicy()).isEqualTo("round_robin");
    assertThat(clusterUpdate2.isEnableLrs()).isEqualTo(false);

    ArgumentCaptor<ClusterUpdate> clusterUpdateCaptor3 = ArgumentCaptor.forClass(null);
    verify(watcher3).onClusterChanged(clusterUpdateCaptor3.capture());
    ClusterUpdate clusterUpdate3 = clusterUpdateCaptor3.getValue();
    assertThat(clusterUpdate3.getClusterName()).isEqualTo("cluster-bar.googleapis.com");
    assertThat(clusterUpdate3.getEdsServiceName())
        .isEqualTo("eds-cluster-bar.googleapis.com");
    assertThat(clusterUpdate3.getLbPolicy()).isEqualTo("round_robin");
    assertThat(clusterUpdate3.isEnableLrs()).isEqualTo(true);
    assertThat(clusterUpdate3.getLrsServerName()).isEqualTo("");
  }

  /**
   * (CDS response caching behavior) Adding cluster watchers interested in some cluster that
   * some other endpoint watcher had already been watching on will result in cluster update
   * notified to the newly added watcher immediately, without sending new CDS requests.
   */
  @Test
  public void watchClusterAlreadyBeingWatched() {
    ClusterWatcher watcher1 = mock(ClusterWatcher.class);
    xdsClient.watchClusterData("cluster-foo.googleapis.com", watcher1);

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

    ArgumentCaptor<ClusterUpdate> clusterUpdateCaptor1 = ArgumentCaptor.forClass(null);
    verify(watcher1).onClusterChanged(clusterUpdateCaptor1.capture());
    ClusterUpdate clusterUpdate1 = clusterUpdateCaptor1.getValue();
    assertThat(clusterUpdate1.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(clusterUpdate1.getEdsServiceName())
        .isEqualTo("cluster-foo.googleapis.com");  // default to cluster name
    assertThat(clusterUpdate1.getLbPolicy()).isEqualTo("round_robin");
    assertThat(clusterUpdate1.isEnableLrs()).isEqualTo(false);
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();

    // Another cluster watcher interested in the same cluster is added.
    ClusterWatcher watcher2 = mock(ClusterWatcher.class);
    xdsClient.watchClusterData("cluster-foo.googleapis.com", watcher2);

    // Since the client has received cluster update for this cluster before, cached result is
    // notified to the newly added watcher immediately.
    ArgumentCaptor<ClusterUpdate> clusterUpdateCaptor2 = ArgumentCaptor.forClass(null);
    verify(watcher2).onClusterChanged(clusterUpdateCaptor2.capture());
    ClusterUpdate clusterUpdate2 = clusterUpdateCaptor2.getValue();
    assertThat(clusterUpdate2.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(clusterUpdate2.getEdsServiceName())
        .isEqualTo("cluster-foo.googleapis.com");  // default to cluster name
    assertThat(clusterUpdate2.getLbPolicy()).isEqualTo("round_robin");
    assertThat(clusterUpdate2.isEnableLrs()).isEqualTo(false);

    verifyNoMoreInteractions(requestObserver);
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  /**
   * Basic operations of adding/canceling cluster data watchers.
   */
  @Test
  public void addRemoveClusterWatchers() {
    ClusterWatcher watcher1 = mock(ClusterWatcher.class);
    xdsClient.watchClusterData("cluster-foo.googleapis.com", watcher1);

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

    ArgumentCaptor<ClusterUpdate> clusterUpdateCaptor1 = ArgumentCaptor.forClass(null);
    verify(watcher1).onClusterChanged(clusterUpdateCaptor1.capture());
    ClusterUpdate clusterUpdate1 = clusterUpdateCaptor1.getValue();
    assertThat(clusterUpdate1.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(clusterUpdate1.getEdsServiceName())
        .isEqualTo("cluster-foo.googleapis.com");  // default to cluster name
    assertThat(clusterUpdate1.getLbPolicy()).isEqualTo("round_robin");
    assertThat(clusterUpdate1.isEnableLrs()).isEqualTo(false);

    // Add another cluster watcher for a different cluster.
    ClusterWatcher watcher2 = mock(ClusterWatcher.class);
    xdsClient.watchClusterData("cluster-bar.googleapis.com", watcher2);

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

    verify(watcher1, times(2)).onClusterChanged(clusterUpdateCaptor1.capture());
    clusterUpdate1 = clusterUpdateCaptor1.getValue();
    assertThat(clusterUpdate1.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(clusterUpdate1.getEdsServiceName())
        .isEqualTo("cluster-foo.googleapis.com");  // default to cluster name
    assertThat(clusterUpdate1.getLbPolicy()).isEqualTo("round_robin");
    assertThat(clusterUpdate1.isEnableLrs()).isEqualTo(false);

    ArgumentCaptor<ClusterUpdate> clusterUpdateCaptor2 = ArgumentCaptor.forClass(null);
    verify(watcher2).onClusterChanged(clusterUpdateCaptor2.capture());
    ClusterUpdate clusterUpdate2 = clusterUpdateCaptor2.getValue();
    assertThat(clusterUpdate2.getClusterName()).isEqualTo("cluster-bar.googleapis.com");
    assertThat(clusterUpdate2.getEdsServiceName())
        .isEqualTo("eds-cluster-bar.googleapis.com");
    assertThat(clusterUpdate2.getLbPolicy()).isEqualTo("round_robin");
    assertThat(clusterUpdate2.isEnableLrs()).isEqualTo(true);
    assertThat(clusterUpdate2.getLrsServerName()).isEqualTo("");

    // Cancel one of the watcher.
    xdsClient.cancelClusterDataWatch("cluster-foo.googleapis.com", watcher1);

    // Since the cancelled watcher was the last watcher interested in that cluster (but there
    // is still interested resource), client sent an new CDS request to unsubscribe from
    // that cluster.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "1", "cluster-bar.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "0001")));

    // Management server has nothing to respond.

    // Cancel the other watcher. All resources have been unsubscribed.
    xdsClient.cancelClusterDataWatch("cluster-bar.googleapis.com", watcher2);

    // All endpoint watchers have been cancelled. Due to protocol limitation, we do not send
    // a CDS request for updated resource names (empty) when canceling the last resource.
    verifyNoMoreInteractions(requestObserver);

    // Management server sends back a new CDS response.
    clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster-foo.googleapis.com", null, true)),
        Any.pack(
            buildCluster("cluster-bar.googleapis.com", null, false)));
    response =
        buildDiscoveryResponse("2", clusters, XdsClientImpl.ADS_TYPE_URL_CDS, "0002");
    responseObserver.onNext(response);

    // Due to protocol limitation, client sent an ACK CDS request, with resource_names containing
    // the last unsubscribed resource.
    verify(requestObserver)
        .onNext(
            argThat(
                new DiscoveryRequestMatcher("2",
                    ImmutableList.of("cluster-bar.googleapis.com"),
                    XdsClientImpl.ADS_TYPE_URL_CDS, "0002")));

    // Cancelled watchers do not receive notification.
    verifyNoMoreInteractions(watcher1, watcher2);

    // A new cluster watcher is added to watch cluster foo again.
    ClusterWatcher watcher3 = mock(ClusterWatcher.class);
    xdsClient.watchClusterData("cluster-foo.googleapis.com", watcher3);
    verify(watcher3, never()).onClusterChanged(any(ClusterUpdate.class));

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
    ArgumentCaptor<ClusterUpdate> clusterUpdateCaptor3 = ArgumentCaptor.forClass(null);
    verify(watcher3).onClusterChanged(clusterUpdateCaptor3.capture());
    ClusterUpdate clusterUpdate3 = clusterUpdateCaptor3.getValue();
    assertThat(clusterUpdate3.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(clusterUpdate3.getEdsServiceName())
        .isEqualTo("cluster-foo.googleapis.com");  // default to cluster name
    assertThat(clusterUpdate3.getLbPolicy()).isEqualTo("round_robin");
    assertThat(clusterUpdate3.isEnableLrs()).isEqualTo(true);
    assertThat(clusterUpdate2.getLrsServerName()).isEqualTo("");

    verifyNoMoreInteractions(watcher1, watcher2);

    // A CDS request is sent to re-subscribe the cluster again.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "3", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "0003")));
  }

  @Test
  public void addRemoveClusterWatcherWhileInitialResourceFetchInProgress() {
    ClusterWatcher watcher1 = mock(ClusterWatcher.class);
    xdsClient.watchClusterData("cluster-foo.googleapis.com", watcher1);

    // Streaming RPC starts after a first watcher is added.
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an EDS request to management server.
    verify(requestObserver)
        .onNext(
            argThat(
                new DiscoveryRequestMatcher("", "cluster-foo.googleapis.com",
                    XdsClientImpl.ADS_TYPE_URL_CDS, "")));
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);

    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC - 1, TimeUnit.SECONDS);

    ClusterWatcher watcher2 = mock(ClusterWatcher.class);
    ClusterWatcher watcher3 = mock(ClusterWatcher.class);
    ClusterWatcher watcher4 = mock(ClusterWatcher.class);
    xdsClient.watchClusterData("cluster-foo.googleapis.com", watcher2);
    xdsClient.watchClusterData("cluster-bar.googleapis.com", watcher3);
    xdsClient.watchClusterData("cluster-bar.googleapis.com", watcher4);

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
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(null);
    verify(watcher1).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.NOT_FOUND);
    verify(watcher2).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.NOT_FOUND);

    // The absence result is known immediately.
    ClusterWatcher watcher5 = mock(ClusterWatcher.class);
    xdsClient.watchClusterData("cluster-foo.googleapis.com", watcher5);

    verify(watcher5).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.NOT_FOUND);

    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);
    ScheduledTask timeoutTask = Iterables.getOnlyElement(fakeClock.getPendingTasks());

    // Cancel watchers while discovery for resource "cluster-bar.googleapis.com" is still
    // in progress.
    xdsClient.cancelClusterDataWatch("cluster-bar.googleapis.com", watcher3);
    assertThat(timeoutTask.isCancelled()).isFalse();
    xdsClient.cancelClusterDataWatch("cluster-bar.googleapis.com", watcher4);

    // Client sends a CDS request for resource subscription update (Omitted).

    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);

    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
    assertThat(timeoutTask.isCancelled()).isTrue();

    verifyZeroInteractions(watcher3, watcher4);
  }

  @Test
  public void cdsUpdateForClusterBeingRemoved() {
    xdsClient.watchClusterData("cluster-foo.googleapis.com", clusterWatcher);
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

    ArgumentCaptor<ClusterUpdate> clusterUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(clusterWatcher).onClusterChanged(clusterUpdateCaptor.capture());
    ClusterUpdate clusterUpdate = clusterUpdateCaptor.getValue();
    assertThat(clusterUpdate.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(clusterUpdate.getEdsServiceName())
        .isEqualTo("cluster-foo.googleapis.com");  // default to cluster name
    assertThat(clusterUpdate.getLbPolicy()).isEqualTo("round_robin");
    assertThat(clusterUpdate.isEnableLrs()).isEqualTo(true);
    assertThat(clusterUpdate.getLrsServerName()).isEqualTo("");
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();

    // No cluster is available.
    response =
        buildDiscoveryResponse("1", ImmutableList.<Any>of(),
            XdsClientImpl.ADS_TYPE_URL_CDS, "0001");
    responseObserver.onNext(response);

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(null);
    verify(clusterWatcher).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.NOT_FOUND);
  }

  /**
   * Client receives an EDS response that does not contain a ClusterLoadAssignment for the
   * requested resource while each received ClusterLoadAssignment is valid.
   * The EDS response is ACKed.
   * After the resource fetch timeout expires, watchers waiting for the resource is notified
   * with a resource not found error.
   */
  @Test
  public void edsResponseWithoutMatchingResource() {
    xdsClient.watchEndpointData("cluster-foo.googleapis.com", endpointWatcher);
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

    verify(endpointWatcher, never()).onEndpointChanged(any(EndpointUpdate.class));
    verify(endpointWatcher, never()).onError(any(Status.class));
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(null);
    verify(endpointWatcher).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.NOT_FOUND);
    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  /**
   * Normal workflow of receiving an EDS response containing ClusterLoadAssignment message for
   * a requested cluster.
   */
  @Test
  public void edsResponseWithMatchingResource() {
    xdsClient.watchEndpointData("cluster-foo.googleapis.com", endpointWatcher);
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
                    ImmutableList.of(
                        buildLbEndpoint("192.168.142.5", 80, HealthStatus.UNKNOWN, 5)),
                    2, 1)),
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

    ArgumentCaptor<EndpointUpdate> endpointUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(endpointWatcher).onEndpointChanged(endpointUpdateCaptor.capture());
    EndpointUpdate endpointUpdate = endpointUpdateCaptor.getValue();
    assertThat(endpointUpdate.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(endpointUpdate.getDropPolicies())
        .containsExactly(
            new DropOverload("lb", 200),
            new DropOverload("throttle", 1000));
    assertThat(endpointUpdate.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region1", "zone1", "subzone1"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.0.1", 8080,
                        2, true)), 1, 0),
            new Locality("region3", "zone3", "subzone3"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.142.5", 80,
                        5, true)), 2, 1));
  }

  @Test
  public void multipleEndpointWatchers() {
    EndpointWatcher watcher1 = mock(EndpointWatcher.class);
    EndpointWatcher watcher2 = mock(EndpointWatcher.class);
    EndpointWatcher watcher3 = mock(EndpointWatcher.class);
    xdsClient.watchEndpointData("cluster-foo.googleapis.com", watcher1);
    xdsClient.watchEndpointData("cluster-foo.googleapis.com", watcher2);
    xdsClient.watchEndpointData("cluster-bar.googleapis.com", watcher3);

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
            ImmutableList.<Policy.DropOverload>of())));

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
    ArgumentCaptor<EndpointUpdate> endpointUpdateCaptor1 = ArgumentCaptor.forClass(null);
    verify(watcher1).onEndpointChanged(endpointUpdateCaptor1.capture());
    EndpointUpdate endpointUpdate1 = endpointUpdateCaptor1.getValue();
    assertThat(endpointUpdate1.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(endpointUpdate1.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region1", "zone1", "subzone1"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.0.1", 8080,
                        2, true)), 1, 0));

    ArgumentCaptor<EndpointUpdate> endpointUpdateCaptor2 = ArgumentCaptor.forClass(null);
    verify(watcher1).onEndpointChanged(endpointUpdateCaptor2.capture());
    EndpointUpdate endpointUpdate2 = endpointUpdateCaptor2.getValue();
    assertThat(endpointUpdate2.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(endpointUpdate2.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region1", "zone1", "subzone1"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.0.1", 8080,
                        2, true)), 1, 0));

    verifyZeroInteractions(watcher3);

    // Management server sends back another EDS response contains ClusterLoadAssignment for the
    // other requested cluster.
    clusterLoadAssignments = ImmutableList.of(
        Any.pack(buildClusterLoadAssignment("cluster-bar.googleapis.com",
            ImmutableList.of(
                buildLocalityLbEndpoints("region2", "zone2", "subzone2",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.234.52", 8888, HealthStatus.UNKNOWN, 5)),
                    6, 1)),
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
    ArgumentCaptor<EndpointUpdate> endpointUpdateCaptor3 = ArgumentCaptor.forClass(null);
    verify(watcher3).onEndpointChanged(endpointUpdateCaptor3.capture());
    EndpointUpdate endpointUpdate3 = endpointUpdateCaptor3.getValue();
    assertThat(endpointUpdate3.getClusterName()).isEqualTo("cluster-bar.googleapis.com");
    assertThat(endpointUpdate3.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region2", "zone2", "subzone2"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.234.52", 8888,
                        5, true)), 6, 1));
  }

  /**
   * (EDS response caching behavior) An endpoint watcher is registered for a cluster that already
   * has some other endpoint watchers watching on. Endpoint information received previously is
   * in local cache and notified to the new watcher immediately.
   */
  @Test
  public void watchEndpointsForClusterAlreadyBeingWatched() {
    EndpointWatcher watcher1 = mock(EndpointWatcher.class);
    xdsClient.watchEndpointData("cluster-foo.googleapis.com", watcher1);
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
            ImmutableList.<Policy.DropOverload>of())));

    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusterLoadAssignments,
            XdsClientImpl.ADS_TYPE_URL_EDS, "0000");
    responseObserver.onNext(response);

    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();

    // Client sent an ACK EDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "0000")));

    ArgumentCaptor<EndpointUpdate> endpointUpdateCaptor1 = ArgumentCaptor.forClass(null);
    verify(watcher1).onEndpointChanged(endpointUpdateCaptor1.capture());
    EndpointUpdate endpointUpdate1 = endpointUpdateCaptor1.getValue();
    assertThat(endpointUpdate1.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(endpointUpdate1.getDropPolicies()).isEmpty();
    assertThat(endpointUpdate1.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region1", "zone1", "subzone1"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.0.1", 8080,
                        2, true)), 1, 0));

    // A second endpoint watcher is registered for endpoints in the same cluster.
    EndpointWatcher watcher2 = mock(EndpointWatcher.class);
    xdsClient.watchEndpointData("cluster-foo.googleapis.com", watcher2);

    // Cached endpoint information is notified to the new watcher immediately, without sending
    // another EDS request.
    ArgumentCaptor<EndpointUpdate> endpointUpdateCaptor2 = ArgumentCaptor.forClass(null);
    verify(watcher2).onEndpointChanged(endpointUpdateCaptor2.capture());
    EndpointUpdate endpointUpdate2 = endpointUpdateCaptor2.getValue();
    assertThat(endpointUpdate2.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(endpointUpdate2.getDropPolicies()).isEmpty();
    assertThat(endpointUpdate2.getLocalityLbEndpointsMap())
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
  public void addRemoveEndpointWatchers() {
    EndpointWatcher watcher1 = mock(EndpointWatcher.class);
    xdsClient.watchEndpointData("cluster-foo.googleapis.com", watcher1);

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
            ImmutableList.<Policy.DropOverload>of())));

    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusterLoadAssignments,
            XdsClientImpl.ADS_TYPE_URL_EDS, "0000");
    responseObserver.onNext(response);

    // Client sent an ACK EDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "cluster-foo.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "0000")));

    ArgumentCaptor<EndpointUpdate> endpointUpdateCaptor1 = ArgumentCaptor.forClass(null);
    verify(watcher1).onEndpointChanged(endpointUpdateCaptor1.capture());
    EndpointUpdate endpointUpdate1 = endpointUpdateCaptor1.getValue();
    assertThat(endpointUpdate1.getClusterName()).isEqualTo("cluster-foo.googleapis.com");
    assertThat(endpointUpdate1.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region1", "zone1", "subzone1"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.0.1", 8080, 2, true),
                    new LbEndpoint("192.132.53.5", 80,5, false)),
                1, 0));

    // Add another endpoint watcher for a different cluster.
    EndpointWatcher watcher2 = mock(EndpointWatcher.class);
    xdsClient.watchEndpointData("cluster-bar.googleapis.com", watcher2);

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
                    6, 1)),
            ImmutableList.<Policy.DropOverload>of())));

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

    ArgumentCaptor<EndpointUpdate> endpointUpdateCaptor2 = ArgumentCaptor.forClass(null);
    verify(watcher2).onEndpointChanged(endpointUpdateCaptor2.capture());
    EndpointUpdate endpointUpdate2 = endpointUpdateCaptor2.getValue();
    assertThat(endpointUpdate2.getClusterName()).isEqualTo("cluster-bar.googleapis.com");
    assertThat(endpointUpdate2.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region2", "zone2", "subzone2"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.312.6", 443, 1, true)),
                6, 1));

    // Cancel one of the watcher.
    xdsClient.cancelEndpointDataWatch("cluster-foo.googleapis.com", watcher1);

    // Since the cancelled watcher was the last watcher interested in that cluster, client
    // sent an new EDS request to unsubscribe from that cluster.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "1", "cluster-bar.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "0001")));

    // Management server should not respond as it had previously sent the requested resource.

    // Cancel the other watcher.
    xdsClient.cancelEndpointDataWatch("cluster-bar.googleapis.com", watcher2);

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
            ImmutableList.<Policy.DropOverload>of())),
        Any.pack(buildClusterLoadAssignment("cluster-bar.googleapis.com",
            ImmutableList.of(
                buildLocalityLbEndpoints("region4", "zone4", "subzone4",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.75.6", 8888, HealthStatus.HEALTHY, 2)),
                    3, 0)),
            ImmutableList.<Policy.DropOverload>of())));

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
    EndpointWatcher watcher3 = mock(EndpointWatcher.class);
    xdsClient.watchEndpointData("cluster-bar.googleapis.com", watcher3);

    // Nothing should be notified to the new watcher as we are still waiting management server's
    // latest response.
    // Cached endpoint data should have been purged.
    verify(watcher3, never()).onEndpointChanged(any(EndpointUpdate.class));

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
            ImmutableList.<Policy.DropOverload>of())));

    response = buildDiscoveryResponse("3", clusterLoadAssignments,
        XdsClientImpl.ADS_TYPE_URL_EDS, "0003");
    responseObserver.onNext(response);

    ArgumentCaptor<EndpointUpdate> endpointUpdateCaptor3 = ArgumentCaptor.forClass(null);
    verify(watcher3).onEndpointChanged(endpointUpdateCaptor3.capture());
    EndpointUpdate endpointUpdate3 = endpointUpdateCaptor3.getValue();
    assertThat(endpointUpdate3.getClusterName()).isEqualTo("cluster-bar.googleapis.com");
    assertThat(endpointUpdate3.getLocalityLbEndpointsMap())
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
  public void addRemoveEndpointWatcherWhileInitialResourceFetchInProgress() {
    EndpointWatcher watcher1 = mock(EndpointWatcher.class);
    xdsClient.watchEndpointData("cluster-foo.googleapis.com", watcher1);

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

    EndpointWatcher watcher2 = mock(EndpointWatcher.class);
    EndpointWatcher watcher3 = mock(EndpointWatcher.class);
    EndpointWatcher watcher4 = mock(EndpointWatcher.class);
    xdsClient.watchEndpointData("cluster-foo.googleapis.com", watcher2);
    xdsClient.watchEndpointData("cluster-bar.googleapis.com", watcher3);
    xdsClient.watchEndpointData("cluster-bar.googleapis.com", watcher4);

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
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(null);
    verify(watcher1).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.NOT_FOUND);
    verify(watcher2).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.NOT_FOUND);

    // The absence result is known immediately.
    EndpointWatcher watcher5 = mock(EndpointWatcher.class);
    xdsClient.watchEndpointData("cluster-foo.googleapis.com", watcher5);

    verify(watcher5).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.NOT_FOUND);

    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);
    ScheduledTask timeoutTask = Iterables.getOnlyElement(fakeClock.getPendingTasks());

    // Cancel watchers while discovery for resource "cluster-bar.googleapis.com" is still
    // in progress.
    xdsClient.cancelEndpointDataWatch("cluster-bar.googleapis.com", watcher3);
    assertThat(timeoutTask.isCancelled()).isFalse();
    xdsClient.cancelEndpointDataWatch("cluster-bar.googleapis.com", watcher4);

    // Client sends an EDS request for resource subscription update (Omitted).

    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);

    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
    assertThat(timeoutTask.isCancelled()).isTrue();

    verifyZeroInteractions(watcher3, watcher4);
  }

  @Test
  public void cdsUpdateForEdsServiceNameChange() {
    xdsClient.watchClusterData("cluster-foo.googleapis.com", clusterWatcher);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();

    // Management server sends back a CDS response containing requested resource.
    List<Any> clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster-foo.googleapis.com", "cluster-foo:service-bar", false)));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusters, XdsClientImpl.ADS_TYPE_URL_CDS, "0000");
    responseObserver.onNext(response);

    xdsClient.watchEndpointData("cluster-foo:service-bar", endpointWatcher);

    // Management server sends back an EDS response for resource "cluster-foo:service-bar".
    List<Any> clusterLoadAssignments = ImmutableList.of(
        Any.pack(buildClusterLoadAssignment("cluster-foo:service-bar",
            ImmutableList.of(
                buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2)),
                    1, 0)),
            ImmutableList.<Policy.DropOverload>of())));
    response =
        buildDiscoveryResponse("0", clusterLoadAssignments,
            XdsClientImpl.ADS_TYPE_URL_EDS, "0000");
    responseObserver.onNext(response);

    ArgumentCaptor<EndpointUpdate> endpointUpdateCaptor = ArgumentCaptor.forClass(null);
    verify(endpointWatcher).onEndpointChanged(endpointUpdateCaptor.capture());
    EndpointUpdate endpointUpdate = endpointUpdateCaptor.getValue();
    assertThat(endpointUpdate.getClusterName()).isEqualTo("cluster-foo:service-bar");
    assertThat(endpointUpdate.getDropPolicies()).isEmpty();
    assertThat(endpointUpdate.getLocalityLbEndpointsMap())
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
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(null);
    verify(endpointWatcher).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.NOT_FOUND);
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
    xdsClient.watchConfigData(HOSTNAME, PORT, configWatcher);

    ArgumentCaptor<StreamObserver<DiscoveryResponse>> responseObserverCaptor =
        ArgumentCaptor.forClass(null);
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    StreamObserver<DiscoveryResponse> responseObserver =
        responseObserverCaptor.getValue();  // same as responseObservers.poll()
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    // Client sends an LDS request for the host name (with port) to management server.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
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
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
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
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));

    // Management server responses with a listener for the requested resource.
    Rds rdsConfig =
        Rds.newBuilder()
            .setConfigSource(
                ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance()))
            .setRouteConfigName("route-foo.googleapis.com")
            .build();

    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener("foo.googleapis.com:8080", /* matching resource */
            Any.pack(HttpConnectionManager.newBuilder().setRds(rdsConfig).build())))
    );
    DiscoveryResponse ldsResponse =
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS, "0000");
    responseObserver.onNext(ldsResponse);

    // Client sent back an ACK LDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "0", "foo.googleapis.com:8080",
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
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
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
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));

    // Management server sends an LDS response.
    responseObserver.onNext(ldsResponse);

    // Client sends an ACK LDS request and an RDS request for "route-foo.googleapis.com". (Omitted)

    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(
            buildRouteConfiguration(
                "route-foo.googleapis.com",
                ImmutableList.of(
                    buildVirtualHost(ImmutableList.of("foo.googleapis.com"),
                        "cluster.googleapis.com")))));
    DiscoveryResponse rdsResponse =
        buildDiscoveryResponse("0", routeConfigs, XdsClientImpl.ADS_TYPE_URL_RDS, "0000");
    // Management server sends an RDS response.
    responseObserver.onNext(rdsResponse);

    // Client has resolved the cluster based on the RDS response.
    configWatcher
        .onConfigChanged(
            eq(ConfigUpdate.newBuilder().setClusterName("cluster.googleapis.com").build()));

    // RPC stream closed with an error again.
    responseObserver.onError(Status.UNKNOWN.asException());

    // Reset backoff and retry immediately.
    inOrder.verify(backoffPolicyProvider).get();
    fakeClock.runDueTasks();
    requestObserver = requestObservers.poll();
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
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
    xdsClient.watchConfigData(HOSTNAME, PORT, configWatcher);

    ArgumentCaptor<StreamObserver<DiscoveryResponse>> responseObserverCaptor =
        ArgumentCaptor.forClass(null);
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    StreamObserver<DiscoveryResponse> responseObserver =
        responseObserverCaptor.getValue();  // same as responseObservers.poll()
    StreamObserver<DiscoveryRequest> requestObserver = requestObservers.poll();

    waitUntilConfigResolved(responseObserver);

    // Start watching cluster information.
    xdsClient.watchClusterData("cluster.googleapis.com", clusterWatcher);

    // Client sent first CDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "")));

    // Start watching endpoint information.
    xdsClient.watchEndpointData("cluster.googleapis.com", endpointWatcher);

    // Client sent first EDS request.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "")));

    // Management server closes the RPC stream with an error.
    responseObserver.onError(Status.UNKNOWN.asException());

    // Resets backoff and retry immediately.
    inOrder.verify(backoffPolicyProvider).get();
    fakeClock.runDueTasks();
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    responseObserver = responseObserverCaptor.getValue();
    requestObserver = requestObservers.poll();

    // Retry resumes requests for all wanted resources.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "")));

    // Management server becomes unreachable.
    responseObserver.onError(Status.UNAVAILABLE.asException());
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
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "")));

    // Management server is still not reachable.
    responseObserver.onError(Status.UNAVAILABLE.asException());
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
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
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

    // Resets backoff and retry immediately
    inOrder.verify(backoffPolicyProvider).get();
    fakeClock.runDueTasks();
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    responseObserver = responseObserverCaptor.getValue();
    requestObserver = requestObservers.poll();

    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "")));

    // Management server becomes unreachable again.
    responseObserver.onError(Status.UNAVAILABLE.asException());
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
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
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
    xdsClient.watchConfigData(HOSTNAME, PORT, configWatcher);

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
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));

    // Management server becomes unreachable.
    responseObserver.onError(Status.UNAVAILABLE.asException());
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    assertThat(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER)).hasSize(1);

    // Start watching cluster information while RPC stream is still in retry backoff.
    xdsClient.watchClusterData("cluster.googleapis.com", clusterWatcher);

    // Retry after backoff.
    fakeClock.forwardNanos(9L);
    assertThat(requestObservers).isEmpty();
    fakeClock.forwardNanos(1L);
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    responseObserver = responseObserverCaptor.getValue();
    requestObserver = requestObservers.poll();

    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "")));

    // Management server is still unreachable.
    responseObserver.onError(Status.UNAVAILABLE.asException());
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    assertThat(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER)).hasSize(1);

    // Start watching endpoint information while RPC stream is still in retry backoff.
    xdsClient.watchEndpointData("cluster.googleapis.com", endpointWatcher);

    // Retry after backoff.
    fakeClock.forwardNanos(99L);
    assertThat(requestObservers).isEmpty();
    fakeClock.forwardNanos(1L);
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    responseObserver = responseObserverCaptor.getValue();
    requestObserver = requestObservers.poll();

    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
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
    xdsClient.cancelEndpointDataWatch("cluster.googleapis.com", endpointWatcher);
    // Client updates EDS resource subscription immediately.
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", ImmutableList.<String>of(),
            XdsClientImpl.ADS_TYPE_URL_EDS, "")));

    // Become interested in endpoints of another cluster.
    xdsClient.watchEndpointData("cluster2.googleapis.com", endpointWatcher);
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
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_CDS, "")));
    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster2.googleapis.com",
            XdsClientImpl.ADS_TYPE_URL_EDS, "")));

    // Management server becomes unreachable again.
    responseObserver.onError(Status.UNAVAILABLE.asException());
    inOrder.verify(backoffPolicy2).nextBackoffNanos();
    assertThat(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER)).hasSize(1);

    // No longer interested in previous cluster and endpoints in that cluster.
    xdsClient.cancelClusterDataWatch("cluster.googleapis.com", clusterWatcher);
    xdsClient.cancelEndpointDataWatch("cluster2.googleapis.com", endpointWatcher);

    // Retry after backoff.
    fakeClock.forwardNanos(19L);
    assertThat(requestObservers).isEmpty();
    fakeClock.forwardNanos(1L);
    inOrder.verify(mockedDiscoveryService)
        .streamAggregatedResources(responseObserverCaptor.capture());
    requestObserver = requestObservers.poll();

    verify(requestObserver)
        .onNext(eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
            XdsClientImpl.ADS_TYPE_URL_LDS, "")));
    verify(requestObserver, never())
        .onNext(eq(buildDiscoveryRequest(NODE, "", "cluster.googleapis.com",
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
    xdsClient.watchConfigData(HOSTNAME, PORT, configWatcher);

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
        Any.pack(buildListener("foo.googleapis.com:8080", /* matching resource */
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
        eq(buildDiscoveryRequest(NODE, "", "foo.googleapis.com:8080",
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
                "route-foo.googleapis.com",
                ImmutableList.of(
                    buildVirtualHost(ImmutableList.of("foo.googleapis.com"),
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
    xdsClient.watchClusterData("cluster-foo.googleapis.com", clusterWatcher);
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

    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(null);
    verify(clusterWatcher).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.NOT_FOUND);

    // Start watching endpoint data.
    xdsClient.watchEndpointData("cluster-foo.googleapis.com", endpointWatcher);
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
    xdsClient.reportClientStats("cluster-foo.googleapis.com", "");
    LoadReportCall lrsCall1 = loadReportCalls.poll();
    verify(lrsCall1.requestObserver)
        .onNext(eq(buildInitialLoadStatsRequest("cluster-foo.googleapis.com")));
    assertThat(lrsCall1.cancelled).isFalse();

    xdsClient.reportClientStats("cluster-bar.googleapis.com", "");
    LoadReportCall lrsCall2 = loadReportCalls.poll();
    verify(lrsCall2.requestObserver)
        .onNext(eq(buildInitialLoadStatsRequest("cluster-bar.googleapis.com")));
    assertThat(lrsCall2.cancelled).isFalse();

    xdsClient.cancelClientStatsReport("cluster-bar.googleapis.com");
    assertThat(lrsCall2.cancelled).isTrue();
    assertThat(runningLrsCalls.get()).isEqualTo(1);
  }

  // Simulates the use case of watching clusters/endpoints based on service config resolved by
  // LDS/RDS.
  private void waitUntilConfigResolved(StreamObserver<DiscoveryResponse> responseObserver) {
    // Client sent an LDS request for resource "foo.googleapis.com:8080" (Omitted).

    // Management server responses with a listener telling client to do RDS.
    Rds rdsConfig =
        Rds.newBuilder()
            .setConfigSource(
                ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance()))
            .setRouteConfigName("route-foo.googleapis.com")
            .build();

    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener("foo.googleapis.com:8080", /* matching resource */
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
                "route-foo.googleapis.com",
                ImmutableList.of(
                    buildVirtualHost(ImmutableList.of("foo.googleapis.com"),
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

  private static LoadStatsRequest buildInitialLoadStatsRequest(String clusterName) {
    return
        LoadStatsRequest.newBuilder()
            .setNode(NODE)
            .addClusterStats(ClusterStats.newBuilder().setClusterName(clusterName))
            .build();
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

    private DiscoveryRequestMatcher(String versionInfo, List<String> resourceNames, String typeUrl,
        String responseNonce) {
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
      return NODE.equals(argument.getNode());
    }
  }

  private static class LoadReportCall {
    private final StreamObserver<LoadStatsRequest> requestObserver;
    @SuppressWarnings("unused")
    private final StreamObserver<LoadStatsResponse> responseObserver;
    private boolean cancelled;

    LoadReportCall(StreamObserver<LoadStatsRequest> requestObserver,
        StreamObserver<LoadStatsResponse> responseObserver) {
      this.requestObserver = requestObserver;
      this.responseObserver = responseObserver;
    }
  }
}
