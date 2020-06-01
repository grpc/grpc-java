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
import static io.grpc.xds.XdsClientTestHelper.buildDiscoveryResponse;
import static io.grpc.xds.XdsClientTestHelper.buildListener;
import static io.grpc.xds.XdsClientTestHelper.buildRouteConfiguration;
import static io.grpc.xds.XdsClientTestHelper.buildVirtualHost;
import static io.grpc.xds.XdsLbPolicies.CDS_POLICY_NAME;
import static io.grpc.xds.XdsLbPolicies.WEIGHTED_TARGET_POLICY_NAME;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.protobuf.Any;
import com.google.protobuf.UInt32Value;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.core.AggregatedConfigSource;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.api.v2.route.Route;
import io.envoyproxy.envoy.api.v2.route.RouteAction;
import io.envoyproxy.envoy.api.v2.route.RouteMatch;
import io.envoyproxy.envoy.api.v2.route.VirtualHost;
import io.envoyproxy.envoy.api.v2.route.WeightedCluster;
import io.envoyproxy.envoy.api.v2.route.WeightedCluster.ClusterWeight;
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManager;
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.Rds;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.grpc.ChannelLogger;
import io.grpc.ManagedChannel;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.NameResolver.ResolutionResult;
import io.grpc.NameResolver.ServiceConfigParser;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.XdsClient.XdsChannelFactory;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link XdsNameResolver}. */
@RunWith(JUnit4.class)
// TODO(creamsoup) use parsed service config
public class XdsNameResolverTest {
  private static final String AUTHORITY = "foo.googleapis.com:80";
  private static final Node FAKE_BOOTSTRAP_NODE =
      Node.newBuilder().setId("XdsNameResolverTest").build();

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
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
  private final ServiceConfigParser serviceConfigParser = new ServiceConfigParser() {
    @Override
    public ConfigOrError parseServiceConfig(Map<String, ?> rawServiceConfig) {
      return ConfigOrError.fromConfig(rawServiceConfig);
    }
  };

  private final NameResolver.Args args =
      NameResolver.Args.newBuilder()
          .setDefaultPort(8080)
          .setProxyDetector(GrpcUtil.NOOP_PROXY_DETECTOR)
          .setSynchronizationContext(syncContext)
          .setServiceConfigParser(serviceConfigParser)
          .setScheduledExecutorService(fakeClock.getScheduledExecutorService())
          .setChannelLogger(mock(ChannelLogger.class))
          .build();

  @Mock
  private BackoffPolicy.Provider backoffPolicyProvider;
  @Mock
  private NameResolver.Listener2 mockListener;

  private XdsChannelFactory channelFactory;
  private XdsNameResolver xdsNameResolver;

  @Before
  public void setUp() throws IOException {
    final String serverName = InProcessServerBuilder.generateName();
    AggregatedDiscoveryServiceImplBase serviceImpl = new AggregatedDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamAggregatedResources(
          final StreamObserver<DiscoveryResponse> responseObserver) {
        responseObservers.offer(responseObserver);
        @SuppressWarnings("unchecked")
        StreamObserver<DiscoveryRequest> requestObserver = mock(StreamObserver.class);
        return requestObserver;
      }
    };

    cleanupRule.register(
        InProcessServerBuilder
            .forName(serverName)
            .addService(serviceImpl)
            .directExecutor()
            .build()
            .start());
    final ManagedChannel channel =
        cleanupRule.register(InProcessChannelBuilder.forName(serverName).directExecutor().build());

    channelFactory = new XdsChannelFactory() {
      @Override
      ManagedChannel createChannel(List<ServerInfo> servers) {
        assertThat(Iterables.getOnlyElement(servers).getServerUri()).isEqualTo(serverName);
        return channel;
      }
    };
    Bootstrapper bootstrapper = new Bootstrapper() {
      @Override
      public BootstrapInfo readBootstrap() {
        List<ServerInfo> serverList =
            ImmutableList.of(
                new ServerInfo(serverName,
                    ImmutableList.<ChannelCreds>of()));
        return new BootstrapInfo(serverList, FAKE_BOOTSTRAP_NODE);
      }
    };
    xdsNameResolver =
        new XdsNameResolver(
            AUTHORITY,
            args,
            backoffPolicyProvider,
            fakeClock.getStopwatchSupplier(),
            channelFactory,
            bootstrapper);
    assertThat(responseObservers).isEmpty();
  }

  @After
  public void tearDown() {
    xdsNameResolver.shutdown();
    XdsClientImpl.enableExperimentalRouting = false;
  }

  @Test
  public void resolve_bootstrapProvidesNoTrafficDirectorInfo() {
    Bootstrapper bootstrapper = new Bootstrapper() {
      @Override
      public BootstrapInfo readBootstrap() {
        return new BootstrapInfo(ImmutableList.<ServerInfo>of(), FAKE_BOOTSTRAP_NODE);
      }
    };

    XdsNameResolver resolver =
        new XdsNameResolver(
            AUTHORITY,
            args,
            backoffPolicyProvider,
            fakeClock.getStopwatchSupplier(),
            channelFactory,
            bootstrapper);
    resolver.start(mockListener);
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(null);
    verify(mockListener).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(statusCaptor.getValue().getDescription())
        .isEqualTo("No management server provided by bootstrap");
  }

  @Test
  public void resolve_failToBootstrap() {
    Bootstrapper bootstrapper = new Bootstrapper() {
      @Override
      public BootstrapInfo readBootstrap() throws IOException {
        throw new IOException("Fail to read bootstrap file");
      }
    };

    XdsNameResolver resolver =
        new XdsNameResolver(
            AUTHORITY,
            args,
            backoffPolicyProvider,
            fakeClock.getStopwatchSupplier(),
            channelFactory,
            bootstrapper);
    resolver.start(mockListener);
    ArgumentCaptor<Status> errorCaptor = ArgumentCaptor.forClass(null);
    verify(mockListener).onError(errorCaptor.capture());
    Status error = errorCaptor.getValue();
    assertThat(error.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(error.getDescription()).isEqualTo("Failed to bootstrap");
    assertThat(error.getCause()).hasMessageThat().isEqualTo("Fail to read bootstrap file");
  }

  @Test
  public void resolve_passXdsClientPoolInResult() {
    xdsNameResolver.start(mockListener);
    assertThat(responseObservers).hasSize(1);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();

    // Simulate receiving an LDS response that contains cluster resolution directly in-line.
    String clusterName = "cluster-foo.googleapis.com";
    responseObserver.onNext(
        buildLdsResponseForCluster("0", AUTHORITY, clusterName, "0000"));

    ArgumentCaptor<ResolutionResult> resolutionResultCaptor = ArgumentCaptor.forClass(null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    ObjectPool<XdsClient> xdsClientPool = result.getAttributes().get(XdsAttributes.XDS_CLIENT_POOL);
    assertThat(xdsClientPool).isNotNull();
  }

  @Test
  public void resolve_foundResource() {
    xdsNameResolver.start(mockListener);
    assertThat(responseObservers).hasSize(1);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();

    // Simulate receiving an LDS response that contains cluster resolution directly in-line.
    String clusterName = "cluster-foo.googleapis.com";
    responseObserver.onNext(
        buildLdsResponseForCluster("0", AUTHORITY, clusterName, "0000"));

    ArgumentCaptor<ResolutionResult> resolutionResultCaptor = ArgumentCaptor.forClass(null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    assertThat(result.getAddresses()).isEmpty();
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolve_ResourceNotFound() {
    xdsNameResolver.start(mockListener);
    assertThat(responseObservers).hasSize(1);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();

    // Simulate receiving an LDS response that does not contain requested resource.
    String clusterName = "cluster-bar.googleapis.com";
    responseObserver.onNext(
        buildLdsResponseForCluster("0", "bar.googleapis.com", clusterName, "0000"));

    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    ArgumentCaptor<ResolutionResult> resolutionResultCaptor = ArgumentCaptor.forClass(null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    assertThat(result.getAddresses()).isEmpty();
    assertThat((Map<String, ?>) result.getServiceConfig().getConfig()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void resolve_resourceUpdated() {
    xdsNameResolver.start(mockListener);
    assertThat(responseObservers).hasSize(1);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();

    // Simulate receiving an LDS response that contains cluster resolution directly in-line.
    responseObserver.onNext(
        buildLdsResponseForCluster("0", AUTHORITY, "cluster-foo.googleapis.com", "0000"));

    ArgumentCaptor<ResolutionResult> resolutionResultCaptor = ArgumentCaptor.forClass(null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    assertThat(result.getAddresses()).isEmpty();
    Map<String, ?> serviceConfig = (Map<String, ?>) result.getServiceConfig().getConfig();

    List<Map<String, ?>> rawLbConfigs =
        (List<Map<String, ?>>) serviceConfig.get("loadBalancingConfig");
    Map<String, ?> lbConfig = Iterables.getOnlyElement(rawLbConfigs);
    assertThat(lbConfig.keySet()).containsExactly("cds_experimental");
    Map<String, ?> rawConfigValues = (Map<String, ?>) lbConfig.get("cds_experimental");
    assertThat(rawConfigValues).containsExactly("cluster", "cluster-foo.googleapis.com");

    // Simulate receiving another LDS response that tells client to do RDS.
    String routeConfigName = "route-foo.googleapis.com";
    responseObserver.onNext(
        buildLdsResponseForRdsResource("1", AUTHORITY, routeConfigName, "0001"));

    // Client sent an RDS request for resource "route-foo.googleapis.com" (Omitted in this test).

    // Simulate receiving an RDS response that contains the resource "route-foo.googleapis.com"
    // with cluster resolution for "foo.googleapis.com".
    responseObserver.onNext(
        buildRdsResponseForCluster("0", routeConfigName, AUTHORITY,
            "cluster-blade.googleapis.com", "0000"));

    verify(mockListener, times(2)).onResult(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    assertThat(result.getAddresses()).isEmpty();
    serviceConfig = (Map<String, ?>) result.getServiceConfig().getConfig();
    rawLbConfigs = (List<Map<String, ?>>) serviceConfig.get("loadBalancingConfig");
    lbConfig = Iterables.getOnlyElement(rawLbConfigs);
    assertThat(lbConfig.keySet()).containsExactly("cds_experimental");
    rawConfigValues = (Map<String, ?>) lbConfig.get("cds_experimental");
    assertThat(rawConfigValues).containsExactly("cluster", "cluster-blade.googleapis.com");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void resolve_resourceUpdated_multipleRoutes() {
    XdsClientImpl.enableExperimentalRouting = true;
    xdsNameResolver.start(mockListener);
    assertThat(responseObservers).hasSize(1);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();

    // Simulate receiving an LDS response that contains routes resolution directly in-line.
    List<Route> protoRoutes =
        ImmutableList.of(
            // path match, routed to cluster
            Route.newBuilder()
                .setMatch(buildPathExactMatch("fooSvc", "hello"))
                .setRoute(buildClusterRoute("cluster-hello.googleapis.com"))
                .build(),
            // prefix match, routed to cluster
            Route.newBuilder()
                .setMatch(buildPathPrefixMatch("fooSvc"))
                .setRoute(buildClusterRoute("cluster-foo.googleapis.com"))
                .build(),
            // path match, routed to weighted clusters
            Route.newBuilder()
                .setMatch(buildPathExactMatch("barSvc", "hello"))
                .setRoute(buildWeightedClusterRoute(ImmutableMap.of(
                    "cluster-hello.googleapis.com", 40,  "cluster-hello2.googleapis.com", 60)))
                .build(),
            // prefix match, routed to weighted clusters
            Route.newBuilder()
                .setMatch(buildPathPrefixMatch("barSvc"))
                .setRoute(
                    buildWeightedClusterRoute(
                        ImmutableMap.of(
                            "cluster-bar.googleapis.com", 30, "cluster-bar2.googleapis.com", 70)))
                .build(),
            // default with prefix = "/", routed to cluster
            Route.newBuilder()
                .setMatch(RouteMatch.newBuilder().setPrefix("/"))
                .setRoute(buildClusterRoute("cluster-hello.googleapis.com"))
                .build());
    HttpConnectionManager httpConnectionManager =
        HttpConnectionManager.newBuilder()
            .setRouteConfig(
                buildRouteConfiguration(
                    "route-foo.googleapis.com", // doesn't matter
                    ImmutableList.of(buildVirtualHostForRoutes(AUTHORITY, protoRoutes))))
            .build();
    List<Any> listeners =
        ImmutableList.of(Any.pack(buildListener(AUTHORITY, Any.pack(httpConnectionManager))));
    responseObserver.onNext(
        buildDiscoveryResponse("0", listeners, XdsClientImpl.ADS_TYPE_URL_LDS,  "0000"));

    ArgumentCaptor<ResolutionResult> resolutionResultCaptor = ArgumentCaptor.forClass(null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    assertThat(result.getAddresses()).isEmpty();
    Map<String, ?> serviceConfig = (Map<String, ?>) result.getServiceConfig().getConfig();

    List<Map<String, ?>> rawLbConfigs =
        (List<Map<String, ?>>) serviceConfig.get("loadBalancingConfig");
    Map<String, ?> lbConfig = Iterables.getOnlyElement(rawLbConfigs);
    assertThat(lbConfig.keySet()).containsExactly("xds_routing_experimental");
    Map<String, ?> rawConfigValues = (Map<String, ?>) lbConfig.get("xds_routing_experimental");
    assertThat(rawConfigValues.keySet()).containsExactly("action", "route");
    Map<String, Map<String, ?>> actions =
        (Map<String, Map<String, ?>>) rawConfigValues.get("action");
    List<Map<String, ?>> routes = (List<Map<String, ?>>) rawConfigValues.get("route");
    assertThat(routes).hasSize(5);
    for (Map<String, ?> route : routes) {
      assertThat(route.keySet()).containsExactly("methodName", "action");
    }
    assertThat((Map<String, ?>) routes.get(0).get("methodName"))
        .containsExactly("service", "fooSvc", "method", "hello");
    String action0 = (String) routes.get(0).get("action");
    assertThat((Map<String, ?>) routes.get(1).get("methodName"))
        .containsExactly("service", "fooSvc", "method", "");
    String action1 = (String) routes.get(1).get("action");
    assertThat((Map<String, ?>) routes.get(2).get("methodName"))
        .containsExactly("service", "barSvc", "method", "hello");
    String action2 = (String) routes.get(2).get("action");
    assertThat((Map<String, ?>) routes.get(3).get("methodName"))
        .containsExactly("service", "barSvc", "method", "");
    String action3 = (String) routes.get(3).get("action");
    assertThat((Map<String, ?>) routes.get(4).get("methodName"))
        .containsExactly("service", "", "method", "");
    String action4 = (String) routes.get(4).get("action");
    assertCdsPolicy(actions.get(action0), "cluster-hello.googleapis.com");
    assertCdsPolicy(actions.get(action1), "cluster-foo.googleapis.com");
    assertWeightedTargetPolicy(
        actions.get(action2),
        ImmutableMap.of(
            "cluster-hello.googleapis.com", 40,  "cluster-hello2.googleapis.com", 60));
    assertWeightedTargetPolicy(
        actions.get(action3),
        ImmutableMap.of(
            "cluster-bar.googleapis.com", 30, "cluster-bar2.googleapis.com", 70));
    assertThat(action4).isEqualTo(action0);

    // Simulate receiving another LDS response that tells client to do RDS.
    String routeConfigName = "route-foo.googleapis.com";
    responseObserver.onNext(
        buildLdsResponseForRdsResource("1", AUTHORITY, routeConfigName, "0001"));

    // Client sent an RDS request for resource "route-foo.googleapis.com" (Omitted in this test).

    // Simulate receiving an RDS response that contains the resource "route-foo.googleapis.com"
    // with a route resolution for a single weighted cluster route.
    Route weightedClustersDefaultRoute =
        Route.newBuilder()
            .setMatch(RouteMatch.newBuilder().setPrefix(""))
            .setRoute(buildWeightedClusterRoute(
                ImmutableMap.of(
                    "cluster-foo.googleapis.com", 20, "cluster-bar.googleapis.com", 80)))
            .build();
    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(
            buildRouteConfiguration(
                routeConfigName,
                ImmutableList.of(
                    buildVirtualHostForRoutes(
                        AUTHORITY, ImmutableList.of(weightedClustersDefaultRoute))))));
    responseObserver.onNext(
        buildDiscoveryResponse("0", routeConfigs, XdsClientImpl.ADS_TYPE_URL_RDS, "0000"));

    verify(mockListener, times(2)).onResult(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    assertThat(result.getAddresses()).isEmpty();
    serviceConfig = (Map<String, ?>) result.getServiceConfig().getConfig();
    rawLbConfigs = (List<Map<String, ?>>) serviceConfig.get("loadBalancingConfig");
    lbConfig = Iterables.getOnlyElement(rawLbConfigs);
    assertThat(lbConfig.keySet()).containsExactly(WEIGHTED_TARGET_POLICY_NAME);
    rawConfigValues = (Map<String, ?>) lbConfig.get(WEIGHTED_TARGET_POLICY_NAME);
    assertWeightedTargetConfigClusterWeights(
        rawConfigValues,
        ImmutableMap.of(
            "cluster-foo.googleapis.com", 20, "cluster-bar.googleapis.com", 80));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void resolve_resourceUpdated_allowDuplicateMatchers() {
    XdsClientImpl.enableExperimentalRouting = true;
    xdsNameResolver.start(mockListener);
    assertThat(responseObservers).hasSize(1);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();
    // Simulate receiving another LDS response that tells client to do RDS.
    String routeConfigName = "route-foo.googleapis.com";
    responseObserver.onNext(
        buildLdsResponseForRdsResource("1", AUTHORITY, routeConfigName, "0001"));

    // Client sent an RDS request for resource "route-foo.googleapis.com" (Omitted in this test).
    List<Route> protoRoutes =
        ImmutableList.of(
            // path match, routed to cluster
            Route.newBuilder()
                .setMatch(buildPathExactMatch("fooSvc", "hello"))
                .setRoute(buildClusterRoute("cluster-hello.googleapis.com"))
                .build(),
            // prefix match, routed to cluster
            Route.newBuilder()
                .setMatch(buildPathPrefixMatch("fooSvc"))
                .setRoute(buildClusterRoute("cluster-foo.googleapis.com"))
                .build(),
            // duplicate path match, routed to weighted clusters
            Route.newBuilder()
                .setMatch(buildPathExactMatch("fooSvc", "hello"))
                .setRoute(buildWeightedClusterRoute(ImmutableMap.of(
                    "cluster-hello.googleapis.com", 40,  "cluster-hello2.googleapis.com", 60)))
                .build(),
            // duplicate prefix match, routed to weighted clusters
            Route.newBuilder()
                .setMatch(buildPathPrefixMatch("fooSvc"))
                .setRoute(
                    buildWeightedClusterRoute(
                        ImmutableMap.of(
                            "cluster-bar.googleapis.com", 30, "cluster-bar2.googleapis.com", 70)))
                .build(),
            // default, routed to cluster
            Route.newBuilder()
                .setMatch(RouteMatch.newBuilder().setPrefix(""))
                .setRoute(buildClusterRoute("cluster-hello.googleapis.com"))
                .build());
    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(
            buildRouteConfiguration(
                routeConfigName,
                ImmutableList.of(buildVirtualHostForRoutes(AUTHORITY, protoRoutes)))));
    responseObserver.onNext(
        buildDiscoveryResponse("0", routeConfigs, XdsClientImpl.ADS_TYPE_URL_RDS, "0000"));

    ArgumentCaptor<ResolutionResult> resolutionResultCaptor = ArgumentCaptor.forClass(null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    assertThat(result.getAddresses()).isEmpty();
    Map<String, ?> serviceConfig = (Map<String, ?>) result.getServiceConfig().getConfig();

    List<Map<String, ?>> rawLbConfigs =
        (List<Map<String, ?>>) serviceConfig.get("loadBalancingConfig");
    Map<String, ?> lbConfig = Iterables.getOnlyElement(rawLbConfigs);
    assertThat(lbConfig.keySet()).containsExactly("xds_routing_experimental");
    Map<String, ?> rawConfigValues = (Map<String, ?>) lbConfig.get("xds_routing_experimental");
    assertThat(rawConfigValues.keySet()).containsExactly("action", "route");
    Map<String, Map<String, ?>> actions =
        (Map<String, Map<String, ?>>) rawConfigValues.get("action");
    List<Map<String, ?>> routes = (List<Map<String, ?>>) rawConfigValues.get("route");
    assertThat(routes).hasSize(5);
    for (Map<String, ?> route : routes) {
      assertThat(route.keySet()).containsExactly("methodName", "action");
    }
    assertThat((Map<String, ?>) routes.get(0).get("methodName"))
        .containsExactly("service", "fooSvc", "method", "hello");
    String action0 = (String) routes.get(0).get("action");
    assertThat((Map<String, ?>) routes.get(1).get("methodName"))
        .containsExactly("service", "fooSvc", "method", "");
    String action1 = (String) routes.get(1).get("action");
    assertThat((Map<String, ?>) routes.get(2).get("methodName"))
        .containsExactly("service", "fooSvc", "method", "hello");
    String action2 = (String) routes.get(2).get("action");
    assertThat((Map<String, ?>) routes.get(3).get("methodName"))
        .containsExactly("service", "fooSvc", "method", "");
    String action3 = (String) routes.get(3).get("action");
    assertThat((Map<String, ?>) routes.get(4).get("methodName"))
        .containsExactly("service", "", "method", "");
    String action4 = (String) routes.get(4).get("action");
    assertCdsPolicy(actions.get(action0), "cluster-hello.googleapis.com");
    assertCdsPolicy(actions.get(action1), "cluster-foo.googleapis.com");
    assertWeightedTargetPolicy(
        actions.get(action2),
        ImmutableMap.of(
            "cluster-hello.googleapis.com", 40,  "cluster-hello2.googleapis.com", 60));
    assertWeightedTargetPolicy(
        actions.get(action3),
        ImmutableMap.of(
            "cluster-bar.googleapis.com", 30, "cluster-bar2.googleapis.com", 70));
    assertThat(action4).isEqualTo(action0);
  }

  /** Asserts that the given action contains a single CDS policy with the given cluster name. */
  @SuppressWarnings("unchecked")
  private static void assertCdsPolicy(Map<String, ?> action, String clusterName) {
    assertThat(action.keySet()).containsExactly("childPolicy");
    Map<String, ?> lbConfig =
        Iterables.getOnlyElement((List<Map<String, ?>>) action.get("childPolicy"));
    assertThat(lbConfig.keySet()).containsExactly(CDS_POLICY_NAME);
    Map<String, ?> rawConfigValues = (Map<String, ?>) lbConfig.get(CDS_POLICY_NAME);
    assertThat(rawConfigValues).containsExactly("cluster", clusterName);
  }

  /**
   * Asserts that the given action contains a single weighted-target policy with the given cluster
   * to weight mapping.
   */
  @SuppressWarnings("unchecked")
  private static void assertWeightedTargetPolicy(
      Map<String, ?> action, Map<String, Integer> clusterWeights) {
    assertThat(action.keySet()).containsExactly("childPolicy");
    Map<String, ?> lbConfig =
        Iterables.getOnlyElement((List<Map<String, ?>>) action.get("childPolicy"));
    assertThat(lbConfig.keySet()).containsExactly(WEIGHTED_TARGET_POLICY_NAME);
    Map<String, ?> rawConfigValues = (Map<String, ?>) lbConfig.get(WEIGHTED_TARGET_POLICY_NAME);
    assertWeightedTargetConfigClusterWeights(rawConfigValues, clusterWeights);
  }

  /**
   * Asserts that the given raw config is a weighted-target config with the given cluster to weight
   * mapping.
   */
  @SuppressWarnings("unchecked")
  private static void assertWeightedTargetConfigClusterWeights(
      Map<String, ?> rawConfigValues, Map<String, Integer> clusterWeight) {
    assertThat(rawConfigValues.keySet()).containsExactly("targets");
    Map<String, ?> targets = (Map<String, ?>) rawConfigValues.get("targets");
    assertThat(targets.keySet()).isEqualTo(clusterWeight.keySet());
    for (String targetName : targets.keySet()) {
      Map<String, ?> target = (Map<String, ?>) targets.get(targetName);
      assertThat(target.keySet()).containsExactly("childPolicy", "weight");
      Map<String, ?> lbConfig =
          Iterables.getOnlyElement((List<Map<String, ?>>) target.get("childPolicy"));
      assertThat(lbConfig.keySet()).containsExactly(CDS_POLICY_NAME);
      Map<String, ?> rawClusterConfigValues = (Map<String, ?>) lbConfig.get(CDS_POLICY_NAME);
      assertThat(rawClusterConfigValues).containsExactly("cluster", targetName);
      assertThat(target.get("weight")).isEqualTo(clusterWeight.get(targetName));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void resolve_resourceNewlyAdded() {
    xdsNameResolver.start(mockListener);
    assertThat(responseObservers).hasSize(1);
    StreamObserver<DiscoveryResponse> responseObserver = responseObservers.poll();

    // Simulate receiving an LDS response that does not contain requested resource.
    responseObserver.onNext(
        buildLdsResponseForCluster("0", "bar.googleapis.com",
            "cluster-bar.googleapis.com", "0000"));

    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    ArgumentCaptor<ResolutionResult> resolutionResultCaptor = ArgumentCaptor.forClass(null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    assertThat(result.getAddresses()).isEmpty();

    // Simulate receiving another LDS response that contains cluster resolution directly in-line.
    responseObserver.onNext(
        buildLdsResponseForCluster("1", AUTHORITY, "cluster-foo.googleapis.com",
            "0001"));

    verify(mockListener, times(2)).onResult(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    assertThat(result.getAddresses()).isEmpty();
    Map<String, ?> serviceConfig = (Map<String, ?>) result.getServiceConfig().getConfig();
    List<Map<String, ?>> rawLbConfigs =
        (List<Map<String, ?>>) serviceConfig.get("loadBalancingConfig");
    Map<String, ?> lbConfig = Iterables.getOnlyElement(rawLbConfigs);
    assertThat(lbConfig.keySet()).containsExactly("cds_experimental");
    Map<String, ?> rawConfigValues = (Map<String, ?>) lbConfig.get("cds_experimental");
    assertThat(rawConfigValues).containsExactly("cluster", "cluster-foo.googleapis.com");
  }

  /**
   * Builds an LDS DiscoveryResponse containing the mapping of given host to
   * the given cluster name directly in-line. Clients receiving this response is
   * able to resolve cluster name for the given host immediately.
   */
  private static DiscoveryResponse buildLdsResponseForCluster(
      String versionInfo, String host, String clusterName, String nonce) {
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(host, // target Listener resource
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRouteConfig(
                        buildRouteConfiguration("route-foo.googleapis.com", // doesn't matter
                            ImmutableList.of(
                                buildVirtualHost(
                                    ImmutableList.of(host), // exact match
                                    clusterName))))
                    .build()))));
    return buildDiscoveryResponse(versionInfo, listeners, XdsClientImpl.ADS_TYPE_URL_LDS, nonce);
  }

  /**
   * Builds an LDS DiscoveryResponse containing the mapping of given host to
   * the given RDS resource name. Clients receiving this response is able to
   * send an RDS request for resolving the cluster name for the given host.
   */
  private static DiscoveryResponse buildLdsResponseForRdsResource(
      String versionInfo, String host, String routeConfigName, String nonce) {
    Rds rdsConfig =
        Rds.newBuilder()
            // Must set to use ADS.
            .setConfigSource(
                ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance()))
            .setRouteConfigName(routeConfigName)
            .build();

    List<Any> listeners = ImmutableList.of(
        Any.pack(
            buildListener(
                host, Any.pack(HttpConnectionManager.newBuilder().setRds(rdsConfig).build()))));
    return buildDiscoveryResponse(versionInfo, listeners, XdsClientImpl.ADS_TYPE_URL_LDS, nonce);
  }

  /**
   * Builds an RDS DiscoveryResponse containing route configuration with the given name and a
   * virtual host that matches the given host to the given cluster name.
   */
  private static DiscoveryResponse buildRdsResponseForCluster(
      String versionInfo,
      String routeConfigName,
      String host,
      String clusterName,
      String nonce) {
    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(
            buildRouteConfiguration(
                routeConfigName,
                ImmutableList.of(
                    buildVirtualHost(ImmutableList.of(host), clusterName)))));
    return buildDiscoveryResponse(versionInfo, routeConfigs, XdsClientImpl.ADS_TYPE_URL_RDS, nonce);
  }

  private static RouteMatch buildPathPrefixMatch(String service) {
    return RouteMatch.newBuilder().setPrefix("/" + service + "/").build();
  }

  private static RouteMatch buildPathExactMatch(String service, String method) {
    return RouteMatch.newBuilder().setPath("/" + service + "/" + method).build();
  }

  private static RouteAction buildClusterRoute(String clusterName) {
    return RouteAction.newBuilder().setCluster(clusterName).build();
  }

  /**
   * Builds a RouteAction for a weighted cluster route. The given map is keyed by cluster name and
   * valued by the weight of the cluster.
   */
  private static RouteAction buildWeightedClusterRoute(Map<String, Integer> clusterWeights) {
    WeightedCluster.Builder builder = WeightedCluster.newBuilder();
    for (Map.Entry<String, Integer> entry : clusterWeights.entrySet()) {
      builder.addClusters(
          ClusterWeight.newBuilder()
            .setName(entry.getKey())
            .setWeight(UInt32Value.of(entry.getValue())));
    }
    return RouteAction.newBuilder()
        .setWeightedClusters(builder)
        .build();
  }

  private static VirtualHost buildVirtualHostForRoutes(String domain, List<Route> routes) {
    return VirtualHost.newBuilder()
        .setName("virtualhost00.googleapis.com") // don't care
        .addAllDomains(ImmutableList.of(domain))
        .addAllRoutes(routes)
        .build();
  }
}
