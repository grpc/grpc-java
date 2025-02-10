/*
 * Copyright 2024 The gRPC Authors
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
import static io.grpc.xds.XdsClusterResource.CdsUpdate.ClusterType.AGGREGATE;
import static io.grpc.xds.XdsClusterResource.CdsUpdate.ClusterType.EDS;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_CDS;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_EDS;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_LDS;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_RDS;
import static io.grpc.xds.XdsTestUtils.CLUSTER_NAME;
import static io.grpc.xds.XdsTestUtils.ENDPOINT_HOSTNAME;
import static io.grpc.xds.XdsTestUtils.ENDPOINT_PORT;
import static io.grpc.xds.XdsTestUtils.RDS_NAME;
import static io.grpc.xds.XdsTestUtils.getEdsNameForCluster;
import static io.grpc.xds.client.CommonBootstrapperTestUtils.SERVER_URI;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.XdsConfig.XdsClusterConfig;
import io.grpc.xds.XdsEndpointResource.EdsUpdate;
import io.grpc.xds.XdsListenerResource.LdsUpdate;
import io.grpc.xds.client.CommonBootstrapperTestUtils;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsClientImpl;
import io.grpc.xds.client.XdsClientMetricReporter;
import io.grpc.xds.client.XdsResourceType;
import io.grpc.xds.client.XdsTransportFactory;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link XdsDependencyManager}. */
@RunWith(JUnit4.class)
public class XdsDependencyManagerTest {
  private static final Logger log = Logger.getLogger(XdsDependencyManagerTest.class.getName());
  public static final String CLUSTER_TYPE_NAME = XdsClusterResource.getInstance().typeName();
  public static final String ENDPOINT_TYPE_NAME = XdsEndpointResource.getInstance().typeName();

  @Mock
  private XdsClientMetricReporter xdsClientMetricReporter;

  private final SynchronizationContext syncContext =
      new SynchronizationContext(mock(Thread.UncaughtExceptionHandler.class));

  private ManagedChannel channel;
  private XdsClientImpl xdsClient;
  private XdsDependencyManager xdsDependencyManager;
  private TestWatcher xdsConfigWatcher;
  private Server xdsServer;

  private final FakeClock fakeClock = new FakeClock();
  private final String serverName = InProcessServerBuilder.generateName();
  private final Queue<XdsTestUtils.LrsRpcCall> loadReportCalls = new ArrayDeque<>();
  private final AtomicBoolean adsEnded = new AtomicBoolean(true);
  private final AtomicBoolean lrsEnded = new AtomicBoolean(true);
  private final XdsTestControlPlaneService controlPlaneService = new XdsTestControlPlaneService();
  private final BindableService lrsService =
      XdsTestUtils.createLrsService(lrsEnded, loadReportCalls);

  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  private TestWatcher testWatcher;
  private XdsConfig defaultXdsConfig; // set in setUp()

  @Captor
  private ArgumentCaptor<XdsConfig> xdsConfigCaptor;
  @Captor
  private ArgumentCaptor<Status> statusCaptor;

  @Before
  public void setUp() throws Exception {
    xdsServer = cleanupRule.register(InProcessServerBuilder
        .forName(serverName)
        .addService(controlPlaneService)
        .addService(lrsService)
        .directExecutor()
        .build()
        .start());

    XdsTestUtils.setAdsConfig(controlPlaneService, serverName);

    channel = cleanupRule.register(
        InProcessChannelBuilder.forName(serverName).directExecutor().build());
    XdsTransportFactory xdsTransportFactory =
        ignore -> new GrpcXdsTransportFactory.GrpcXdsTransport(channel);

    xdsClient = CommonBootstrapperTestUtils.createXdsClient(
        Collections.singletonList(SERVER_URI), xdsTransportFactory, fakeClock,
        new ExponentialBackoffPolicy.Provider(), MessagePrinter.INSTANCE, xdsClientMetricReporter);

    testWatcher = new TestWatcher();
    xdsConfigWatcher = mock(TestWatcher.class, delegatesTo(testWatcher));
    defaultXdsConfig = XdsTestUtils.getDefaultXdsConfig(serverName);
  }

  @After
  public void tearDown() throws InterruptedException {
    if (xdsDependencyManager != null) {
      xdsDependencyManager.shutdown();
    }
    xdsClient.shutdown();
    channel.shutdown();  // channel not owned by XdsClient

    xdsServer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);

    assertThat(adsEnded.get()).isTrue();
    assertThat(lrsEnded.get()).isTrue();
    assertThat(fakeClock.getPendingTasks()).isEmpty();
  }

  @Test
  public void verify_basic_config() {
    xdsDependencyManager = new XdsDependencyManager(
        xdsClient, xdsConfigWatcher, syncContext, serverName, serverName);

    verify(xdsConfigWatcher, timeout(1000)).onUpdate(defaultXdsConfig);
    testWatcher.verifyStats(1, 0, 0);
  }

  @Test
  public void verify_config_update() {
    xdsDependencyManager = new XdsDependencyManager(
        xdsClient, xdsConfigWatcher, syncContext, serverName, serverName);

    InOrder inOrder = Mockito.inOrder(xdsConfigWatcher);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(defaultXdsConfig);
    testWatcher.verifyStats(1, 0, 0);
    assertThat(testWatcher.lastConfig).isEqualTo(defaultXdsConfig);

    XdsTestUtils.setAdsConfig(controlPlaneService, serverName, "RDS2", "CDS2", "EDS2",
        ENDPOINT_HOSTNAME + "2", ENDPOINT_PORT + 2);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(ArgumentMatchers.notNull());
    testWatcher.verifyStats(2, 0, 0);
    assertThat(testWatcher.lastConfig).isNotEqualTo(defaultXdsConfig);
  }

  @Test
  public void verify_simple_aggregate() {
    InOrder inOrder = Mockito.inOrder(xdsConfigWatcher);
    xdsDependencyManager = new XdsDependencyManager(
        xdsClient, xdsConfigWatcher, syncContext, serverName, serverName);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(defaultXdsConfig);

    List<String> childNames = Arrays.asList("clusterC", "clusterB", "clusterA");
    String rootName = "root_c";

    RouteConfiguration routeConfig =
        XdsTestUtils.buildRouteConfiguration(serverName, XdsTestUtils.RDS_NAME, rootName);
    controlPlaneService.setXdsConfig(
        ADS_TYPE_URL_RDS, ImmutableMap.of(XdsTestUtils.RDS_NAME, routeConfig));

    XdsTestUtils.setAggregateCdsConfig(controlPlaneService, serverName, rootName, childNames);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(any());

    Map<String, StatusOr<XdsClusterConfig>> lastConfigClusters =
        testWatcher.lastConfig.getClusters();
    assertThat(lastConfigClusters).hasSize(childNames.size() + 1);
    StatusOr<XdsClusterConfig> rootC = lastConfigClusters.get(rootName);
    XdsClusterResource.CdsUpdate rootUpdate = rootC.getValue().getClusterResource();
    assertThat(rootUpdate.clusterType()).isEqualTo(AGGREGATE);
    assertThat(rootUpdate.prioritizedClusterNames()).isEqualTo(childNames);

    for (String childName : childNames) {
      assertThat(lastConfigClusters).containsKey(childName);
      StatusOr<XdsClusterConfig> childConfigOr = lastConfigClusters.get(childName);
      XdsClusterResource.CdsUpdate childResource =
          childConfigOr.getValue().getClusterResource();
      assertThat(childResource.clusterType()).isEqualTo(EDS);
      assertThat(childResource.edsServiceName()).isEqualTo(getEdsNameForCluster(childName));

      StatusOr<EdsUpdate> endpoint = getEndpoint(childConfigOr);
      assertThat(endpoint.hasValue()).isTrue();
      assertThat(endpoint.getValue().clusterName).isEqualTo(getEdsNameForCluster(childName));
    }
  }

  private static StatusOr<EdsUpdate> getEndpoint(StatusOr<XdsClusterConfig> childConfigOr) {
    XdsClusterConfig.ClusterChild clusterChild = childConfigOr.getValue()
        .getChildren();
    assertThat(clusterChild).isInstanceOf(XdsClusterConfig.EndpointConfig.class);
    StatusOr<EdsUpdate> endpoint = ((XdsClusterConfig.EndpointConfig) clusterChild).getEndpoint();
    assertThat(endpoint).isNotNull();
    return endpoint;
  }

  @Test
  public void testComplexRegisteredAggregate() throws IOException {
    InOrder inOrder = Mockito.inOrder(xdsConfigWatcher);

    // Do initialization
    String rootName1 = "root_c";
    List<String> childNames = Arrays.asList("clusterC", "clusterB", "clusterA");
    XdsTestUtils.addAggregateToExistingConfig(controlPlaneService, rootName1, childNames);

    String rootName2 = "root_2";
    List<String> childNames2 = Arrays.asList("clusterA", "clusterX");
    XdsTestUtils.addAggregateToExistingConfig(controlPlaneService, rootName2, childNames2);

    xdsDependencyManager = new XdsDependencyManager(
        xdsClient, xdsConfigWatcher, syncContext, serverName, serverName);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(any());

    Closeable subscription1 = xdsDependencyManager.subscribeToCluster(rootName1);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(any());

    Closeable subscription2 = xdsDependencyManager.subscribeToCluster(rootName2);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(xdsConfigCaptor.capture());
    testWatcher.verifyStats(3, 0, 0);
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();
    Set<String> expectedClusters = builder.add(rootName1).add(rootName2).add(CLUSTER_NAME)
        .addAll(childNames).addAll(childNames2).build();
    assertThat(xdsConfigCaptor.getValue().getClusters().keySet()).isEqualTo(expectedClusters);

    // Close 1 subscription shouldn't affect the other or RDS subscriptions
    subscription1.close();
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(xdsConfigCaptor.capture());
    builder = ImmutableSet.builder();
    Set<String> expectedClusters2 =
        builder.add(rootName2).add(CLUSTER_NAME).addAll(childNames2).build();
    assertThat(xdsConfigCaptor.getValue().getClusters().keySet()).isEqualTo(expectedClusters2);

    subscription2.close();
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(defaultXdsConfig);
  }

  @Test
  public void testDelayedSubscription() {
    InOrder inOrder = Mockito.inOrder(xdsConfigWatcher);
    xdsDependencyManager = new XdsDependencyManager(
        xdsClient, xdsConfigWatcher, syncContext, serverName, serverName);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(defaultXdsConfig);

    String rootName1 = "root_c";

    Closeable subscription1 = xdsDependencyManager.subscribeToCluster(rootName1);
    assertThat(subscription1).isNotNull();
    fakeClock.forwardTime(16, TimeUnit.SECONDS);
    inOrder.verify(xdsConfigWatcher).onUpdate(xdsConfigCaptor.capture());
    assertThat(xdsConfigCaptor.getValue().getClusters().get(rootName1).toString()).isEqualTo(
        StatusOr.fromStatus(Status.UNAVAILABLE.withDescription(
            "No " + toContextStr(CLUSTER_TYPE_NAME, rootName1))).toString());

    List<String> childNames = Arrays.asList("clusterC", "clusterB", "clusterA");
    XdsTestUtils.addAggregateToExistingConfig(controlPlaneService, rootName1, childNames);
    inOrder.verify(xdsConfigWatcher).onUpdate(xdsConfigCaptor.capture());
    assertThat(xdsConfigCaptor.getValue().getClusters().get(rootName1).hasValue()).isTrue();
  }

  @Test
  public void testMissingCdsAndEds() {
    // update config so that agg cluster references 2 existing & 1 non-existing cluster
    List<String> childNames = Arrays.asList("clusterC", "clusterB", "clusterA");
    Cluster cluster = XdsTestUtils.buildAggCluster(CLUSTER_NAME, childNames);
    Map<String, Message> clusterMap = new HashMap<>();
    Map<String, Message> edsMap = new HashMap<>();

    clusterMap.put(CLUSTER_NAME, cluster);
    for (int i = 0; i < childNames.size() - 1; i++) {
      String edsName = XdsTestUtils.EDS_NAME + i;
      Cluster child = ControlPlaneRule.buildCluster(childNames.get(i), edsName);
      clusterMap.put(childNames.get(i), child);
    }
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, clusterMap);

    // Update config so that one of the 2 "valid" clusters has an EDS resource, the other does not
    // and there is an EDS that doesn't have matching clusters
    ClusterLoadAssignment clusterLoadAssignment = ControlPlaneRule.buildClusterLoadAssignment(
        serverName, ENDPOINT_HOSTNAME, ENDPOINT_PORT, XdsTestUtils.EDS_NAME + 0);
    edsMap.put(XdsTestUtils.EDS_NAME + 0, clusterLoadAssignment);
    clusterLoadAssignment = ControlPlaneRule.buildClusterLoadAssignment(
        serverName, ENDPOINT_HOSTNAME, ENDPOINT_PORT, "garbageEds");
    edsMap.put("garbageEds", clusterLoadAssignment);
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, edsMap);

    xdsDependencyManager = new XdsDependencyManager(
        xdsClient, xdsConfigWatcher, syncContext, serverName, serverName);

    fakeClock.forwardTime(16, TimeUnit.SECONDS);
    verify(xdsConfigWatcher, timeout(1000)).onUpdate(xdsConfigCaptor.capture());

    List<StatusOr<XdsClusterConfig>> returnedClusters = new ArrayList<>();
    for (String childName : childNames) {
      returnedClusters.add(xdsConfigCaptor.getValue().getClusters().get(childName));
    }

    // Check that missing cluster reported Status and the other 2 are present
    Status expectedClusterStatus = Status.UNAVAILABLE.withDescription(
        "No " + toContextStr(CLUSTER_TYPE_NAME, childNames.get(2)));
    StatusOr<XdsClusterConfig> missingCluster = returnedClusters.get(2);
    assertThat(missingCluster.getStatus().toString()).isEqualTo(expectedClusterStatus.toString());
    assertThat(returnedClusters.get(0).hasValue()).isTrue();
    assertThat(returnedClusters.get(1).hasValue()).isTrue();

    // Check that missing EDS reported Status, the other one is present and the garbage EDS is not
    Status expectedEdsStatus = Status.UNAVAILABLE.withDescription(
        "No " + toContextStr(ENDPOINT_TYPE_NAME, XdsTestUtils.EDS_NAME + 1));
    assertThat(getEndpoint(returnedClusters.get(0)).hasValue()).isTrue();
    assertThat(getEndpoint(returnedClusters.get(1)).hasValue()).isFalse();
    assertThat(getEndpoint(returnedClusters.get(1)).getStatus().toString())
        .isEqualTo(expectedEdsStatus.toString());

    verify(xdsConfigWatcher, never()).onResourceDoesNotExist(any());
    testWatcher.verifyStats(1, 0, 0);
  }

  @Test
  public void testMissingLds() {
    xdsDependencyManager = new XdsDependencyManager(
        xdsClient, xdsConfigWatcher, syncContext, serverName, "badLdsName");

    fakeClock.forwardTime(16, TimeUnit.SECONDS);
    verify(xdsConfigWatcher, timeout(1000)).onResourceDoesNotExist(
        toContextStr(XdsListenerResource.getInstance().typeName(), "badLdsName"));

    testWatcher.verifyStats(0, 0, 1);
  }

  @Test
  public void testMissingRds() {
    Listener serverListener = ControlPlaneRule.buildServerListener();
    Listener clientListener =
        ControlPlaneRule.buildClientListener(serverName, serverName, "badRdsName");
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_LDS,
        ImmutableMap.of(XdsTestUtils.SERVER_LISTENER, serverListener, serverName, clientListener));

    xdsDependencyManager = new XdsDependencyManager(
        xdsClient, xdsConfigWatcher, syncContext, serverName, serverName);

    fakeClock.forwardTime(16, TimeUnit.SECONDS);
    verify(xdsConfigWatcher, timeout(1000)).onResourceDoesNotExist(
        toContextStr(XdsRouteConfigureResource.getInstance().typeName(), "badRdsName"));

    testWatcher.verifyStats(0, 0, 1);
  }

  @Test
  public void testUpdateToMissingVirtualHost() {
    InOrder inOrder = Mockito.inOrder(xdsConfigWatcher);
    WrappedXdsClient wrappedXdsClient = new WrappedXdsClient(xdsClient, syncContext);
    xdsDependencyManager = new XdsDependencyManager(
        wrappedXdsClient, xdsConfigWatcher, syncContext, serverName, serverName);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(defaultXdsConfig);

    // Update with a config that has a virtual host that doesn't match the server name
    wrappedXdsClient.deliverLdsUpdate(0L, buildUnmatchedVirtualHosts());
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onError(any(), statusCaptor.capture());
    assertThat(statusCaptor.getValue().getDescription())
        .isEqualTo("Failed to find virtual host matching hostname: " + serverName);

    testWatcher.verifyStats(1, 1, 0);

    wrappedXdsClient.shutdown();
  }

  private List<io.grpc.xds.VirtualHost> buildUnmatchedVirtualHosts() {
    io.grpc.xds.VirtualHost.Route route1 =
        io.grpc.xds.VirtualHost.Route.forAction(
            io.grpc.xds.VirtualHost.Route.RouteMatch.withPathExactOnly("/GreetService/bye"),
        io.grpc.xds.VirtualHost.Route.RouteAction.forCluster(
            "cluster-bar.googleapis.com", Collections.emptyList(),
            TimeUnit.SECONDS.toNanos(15L), null, false), ImmutableMap.of());
    io.grpc.xds.VirtualHost.Route route2 =
        io.grpc.xds.VirtualHost.Route.forAction(
            io.grpc.xds.VirtualHost.Route.RouteMatch.withPathExactOnly("/HelloService/hi"),
        io.grpc.xds.VirtualHost.Route.RouteAction.forCluster(
            "cluster-foo.googleapis.com", Collections.emptyList(),
            TimeUnit.SECONDS.toNanos(15L), null, false),
        ImmutableMap.of());
    return Arrays.asList(
        io.grpc.xds.VirtualHost.create("virtualhost-foo", Collections.singletonList("hello"
                + ".googleapis.com"),
            Collections.singletonList(route1),
            ImmutableMap.of()),
        io.grpc.xds.VirtualHost.create("virtualhost-bar", Collections.singletonList("hi"
                + ".googleapis.com"),
            Collections.singletonList(route2),
            ImmutableMap.of()));
  }

  @Test
  public void testCorruptLds() {
    String ldsResourceName =
        "xdstp://unknown.example.com/envoy.config.listener.v3.Listener/listener1";

    xdsDependencyManager = new XdsDependencyManager(
        xdsClient, xdsConfigWatcher, syncContext, serverName, ldsResourceName);

    Status expectedStatus = Status.INVALID_ARGUMENT.withDescription(
        "Wrong configuration: xds server does not exist for resource " + ldsResourceName);
    String context = toContextStr(XdsListenerResource.getInstance().typeName(), ldsResourceName);
    verify(xdsConfigWatcher, timeout(1000))
        .onError(eq(context), argThat(new XdsTestUtils.StatusMatcher(expectedStatus)));

    fakeClock.forwardTime(16, TimeUnit.SECONDS);
    testWatcher.verifyStats(0, 1, 0);
  }

  @Test
  public void testChangeRdsName_fromLds() {
    // TODO implement
    InOrder inOrder = Mockito.inOrder(xdsConfigWatcher);
    Listener serverListener = ControlPlaneRule.buildServerListener();

    xdsDependencyManager = new XdsDependencyManager(
        xdsClient, xdsConfigWatcher, syncContext, serverName, serverName);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(defaultXdsConfig);

    String newRdsName = "newRdsName1";

    Listener clientListener = buildInlineClientListener(newRdsName, CLUSTER_NAME);
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_LDS,
        ImmutableMap.of(XdsTestUtils.SERVER_LISTENER, serverListener, serverName, clientListener));
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(xdsConfigCaptor.capture());
    assertThat(xdsConfigCaptor.getValue()).isNotEqualTo(defaultXdsConfig);
    assertThat(xdsConfigCaptor.getValue().getVirtualHost().name()).isEqualTo(newRdsName);
  }

  @Test
  public void testMultipleParentsInCdsTree() throws IOException {
    /*
     * Configure Xds server with the following cluster tree and point RDS to root:
      2 aggregates under root A & B
       B has EDS Cluster B1 && shared agg AB1; A has agg A1 && shared agg AB1
        A1 has shared EDS Cluster A11 && shared agg AB1
         AB1 has shared EDS Clusters A11 && AB11

      As an alternate visualization, parents are:
        A -> root, B -> root, A1 -> A, AB1 -> A|B|A1, B1 -> B, A11 -> A1|AB1, AB11 -> AB1
     */
    Cluster rootCluster =
        XdsTestUtils.buildAggCluster("root", Arrays.asList("clusterA", "clusterB"));
    Cluster clusterA =
        XdsTestUtils.buildAggCluster("clusterA", Arrays.asList("clusterA1", "clusterAB1"));
    Cluster clusterB =
        XdsTestUtils.buildAggCluster("clusterB", Arrays.asList("clusterB1", "clusterAB1"));
    Cluster clusterA1 =
        XdsTestUtils.buildAggCluster("clusterA1", Arrays.asList("clusterA11", "clusterAB1"));
    Cluster clusterAB1 =
        XdsTestUtils.buildAggCluster("clusterAB1", Arrays.asList("clusterA11", "clusterAB11"));

    Map<String, Message> clusterMap = new HashMap<>();
    Map<String, Message> edsMap = new HashMap<>();

    clusterMap.put("root", rootCluster);
    clusterMap.put("clusterA", clusterA);
    clusterMap.put("clusterB", clusterB);
    clusterMap.put("clusterA1", clusterA1);
    clusterMap.put("clusterAB1", clusterAB1);

    XdsTestUtils.addEdsClusters(clusterMap, edsMap, "clusterA11", "clusterAB11", "clusterB1");
    RouteConfiguration routeConfig =
        XdsTestUtils.buildRouteConfiguration(serverName, XdsTestUtils.RDS_NAME, "root");
    controlPlaneService.setXdsConfig(
        ADS_TYPE_URL_RDS, ImmutableMap.of(XdsTestUtils.RDS_NAME, routeConfig));
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, clusterMap);
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, edsMap);

    // Start the actual test
    InOrder inOrder = Mockito.inOrder(xdsConfigWatcher);
    xdsDependencyManager = new XdsDependencyManager(
        xdsClient, xdsConfigWatcher, syncContext, serverName, serverName);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(xdsConfigCaptor.capture());
    XdsConfig initialConfig = xdsConfigCaptor.getValue();

    // Make sure that adding subscriptions that rds points at doesn't change the config
    Closeable rootSub = xdsDependencyManager.subscribeToCluster("root");
    assertThat(xdsDependencyManager.buildConfig()).isEqualTo(initialConfig);
    Closeable clusterAB11Sub = xdsDependencyManager.subscribeToCluster("clusterAB11");
    assertThat(xdsDependencyManager.buildConfig()).isEqualTo(initialConfig);

    // Make sure that closing subscriptions that rds points at doesn't change the config
    rootSub.close();
    assertThat(xdsDependencyManager.buildConfig()).isEqualTo(initialConfig);
    clusterAB11Sub.close();
    assertThat(xdsDependencyManager.buildConfig()).isEqualTo(initialConfig);

    // Make an explicit root subscription and then change RDS to point to A11
    rootSub = xdsDependencyManager.subscribeToCluster("root");
    RouteConfiguration newRouteConfig =
        XdsTestUtils.buildRouteConfiguration(serverName, XdsTestUtils.RDS_NAME, "clusterA11");
    controlPlaneService.setXdsConfig(
        ADS_TYPE_URL_RDS, ImmutableMap.of(XdsTestUtils.RDS_NAME, newRouteConfig));
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(xdsConfigCaptor.capture());
    assertThat(xdsConfigCaptor.getValue().getClusters().keySet().size()).isEqualTo(4);

    // Now that it is released, we should only have A11
    rootSub.close();
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(xdsConfigCaptor.capture());
    assertThat(xdsConfigCaptor.getValue().getClusters().keySet()).containsExactly("clusterA11");
  }

  @Test
  public void testMultipleCdsReferToSameEds() {
    // Create the maps and Update the config to have 2 clusters that refer to the same EDS resource
    String edsName = "sharedEds";

    Cluster rootCluster =
        XdsTestUtils.buildAggCluster("root", Arrays.asList("clusterA", "clusterB"));
    Cluster clusterA = ControlPlaneRule.buildCluster("clusterA", edsName);
    Cluster clusterB = ControlPlaneRule.buildCluster("clusterB", edsName);

    Map<String, Message> clusterMap = new HashMap<>();
    clusterMap.put("root", rootCluster);
    clusterMap.put("clusterA", clusterA);
    clusterMap.put("clusterB", clusterB);

    Map<String, Message> edsMap = new HashMap<>();
    ClusterLoadAssignment clusterLoadAssignment = ControlPlaneRule.buildClusterLoadAssignment(
        serverName, ENDPOINT_HOSTNAME, ENDPOINT_PORT, edsName);
    edsMap.put(edsName, clusterLoadAssignment);

    RouteConfiguration routeConfig =
        XdsTestUtils.buildRouteConfiguration(serverName, XdsTestUtils.RDS_NAME, "root");
    controlPlaneService.setXdsConfig(
        ADS_TYPE_URL_RDS, ImmutableMap.of(XdsTestUtils.RDS_NAME, routeConfig));
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, clusterMap);
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, edsMap);

    // Start the actual test
    xdsDependencyManager = new XdsDependencyManager(
        xdsClient, xdsConfigWatcher, syncContext, serverName, serverName);
    verify(xdsConfigWatcher, timeout(1000)).onUpdate(xdsConfigCaptor.capture());
    XdsConfig initialConfig = xdsConfigCaptor.getValue();
    assertThat(initialConfig.getClusters().keySet())
        .containsExactly("root", "clusterA", "clusterB");

    EdsUpdate edsForA = getEndpoint(initialConfig.getClusters().get("clusterA")).getValue();
    assertThat(edsForA.clusterName).isEqualTo(edsName);
    EdsUpdate edsForB = getEndpoint(initialConfig.getClusters().get("clusterB")).getValue();
    assertThat(edsForB.clusterName).isEqualTo(edsName);
    assertThat(edsForA).isEqualTo(edsForB);
    edsForA.localityLbEndpointsMap.values().forEach(
        localityLbEndpoints -> assertThat(localityLbEndpoints.endpoints()).hasSize(1));
  }

  @Test
  public void testChangeRdsName_FromLds_complexTree() {
    xdsDependencyManager = new XdsDependencyManager(
        xdsClient, xdsConfigWatcher, syncContext, serverName, serverName);

    // Create the same tree as in testMultipleParentsInCdsTree
    Cluster rootCluster =
        XdsTestUtils.buildAggCluster("root", Arrays.asList("clusterA", "clusterB"));
    Cluster clusterA =
        XdsTestUtils.buildAggCluster("clusterA", Arrays.asList("clusterA1", "clusterAB1"));
    Cluster clusterB =
        XdsTestUtils.buildAggCluster("clusterB", Arrays.asList("clusterB1", "clusterAB1"));
    Cluster clusterA1 =
        XdsTestUtils.buildAggCluster("clusterA1", Arrays.asList("clusterA11", "clusterAB1"));
    Cluster clusterAB1 =
        XdsTestUtils.buildAggCluster("clusterAB1", Arrays.asList("clusterA11", "clusterAB11"));

    Map<String, Message> clusterMap = new HashMap<>();
    Map<String, Message> edsMap = new HashMap<>();

    clusterMap.put("root", rootCluster);
    clusterMap.put("clusterA", clusterA);
    clusterMap.put("clusterB", clusterB);
    clusterMap.put("clusterA1", clusterA1);
    clusterMap.put("clusterAB1", clusterAB1);

    XdsTestUtils.addEdsClusters(clusterMap, edsMap, "clusterA11", "clusterAB11", "clusterB1");
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, clusterMap);
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, edsMap);

    InOrder inOrder = Mockito.inOrder(xdsConfigWatcher);
    inOrder.verify(xdsConfigWatcher, atLeastOnce()).onUpdate(any());

    // Do the test
    String newRdsName = "newRdsName1";
    Listener clientListener = buildInlineClientListener(newRdsName, "root");
    Listener serverListener = ControlPlaneRule.buildServerListener();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_LDS,
        ImmutableMap.of(XdsTestUtils.SERVER_LISTENER, serverListener, serverName, clientListener));
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(xdsConfigCaptor.capture());
    XdsConfig config = xdsConfigCaptor.getValue();
    assertThat(config.getVirtualHost().name()).isEqualTo(newRdsName);
    assertThat(config.getClusters().size()).isEqualTo(4);
  }

  @Test
  public void testChangeAggCluster() {
    InOrder inOrder = Mockito.inOrder(xdsConfigWatcher);

    xdsDependencyManager = new XdsDependencyManager(
        xdsClient, xdsConfigWatcher, syncContext, serverName, serverName);
    inOrder.verify(xdsConfigWatcher, atLeastOnce()).onUpdate(any());

    // Setup initial config A -> A1 -> (A11, A12)
    Cluster rootCluster =
        XdsTestUtils.buildAggCluster("root", Arrays.asList("clusterA"));
    Cluster clusterA =
        XdsTestUtils.buildAggCluster("clusterA", Arrays.asList("clusterA1"));
    Cluster clusterA1 =
        XdsTestUtils.buildAggCluster("clusterA1", Arrays.asList("clusterA11", "clusterA12"));

    Map<String, Message> clusterMap = new HashMap<>();
    Map<String, Message> edsMap = new HashMap<>();

    clusterMap.put("root", rootCluster);
    clusterMap.put("clusterA", clusterA);
    clusterMap.put("clusterA1", clusterA1);

    XdsTestUtils.addEdsClusters(clusterMap, edsMap, "clusterA11", "clusterA12");
    Listener clientListener = buildInlineClientListener(RDS_NAME, "root");
    Listener serverListener = ControlPlaneRule.buildServerListener();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_LDS,
        ImmutableMap.of(XdsTestUtils.SERVER_LISTENER, serverListener, serverName, clientListener));

    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, clusterMap);
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, edsMap);

    inOrder.verify(xdsConfigWatcher).onUpdate(any());

    // Update the cluster to A -> A2 -> (A21, A22)
    Cluster clusterA2 =
        XdsTestUtils.buildAggCluster("clusterA2", Arrays.asList("clusterA21", "clusterA22"));
    clusterA =
        XdsTestUtils.buildAggCluster("clusterA", Arrays.asList("clusterA2"));
    clusterMap.clear();
    edsMap.clear();
    clusterMap.put("root", rootCluster);
    clusterMap.put("clusterA", clusterA);
    clusterMap.put("clusterA2", clusterA2);
    XdsTestUtils.addEdsClusters(clusterMap, edsMap, "clusterA21", "clusterA22");
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, clusterMap);
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, edsMap);

    // Verify that the config is updated as expected
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(xdsConfigCaptor.capture());
    XdsConfig config = xdsConfigCaptor.getValue();
    assertThat(config.getClusters().keySet()).containsExactly("root", "clusterA21", "clusterA22");
  }

  private Listener buildInlineClientListener(String rdsName, String clusterName) {
    return XdsTestUtils.buildInlineClientListener(rdsName, clusterName, serverName);
  }


  private static String toContextStr(String type, String resourceName) {
    return type + " resource: " + resourceName;
  }

  private static class TestWatcher implements XdsDependencyManager.XdsConfigWatcher {
    XdsConfig lastConfig;
    int numUpdates = 0;
    int numError = 0;
    int numDoesNotExist = 0;

    @Override
    public void onUpdate(XdsConfig config) {
      log.fine("Config changed: " + config);
      lastConfig = config;
      numUpdates++;
    }

    @Override
    public void onError(String resourceContext, Status status) {
      log.fine(String.format("Error %s for %s: ", status, resourceContext));
      numError++;
    }

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      log.fine("Resource does not exist: " + resourceName);
      numDoesNotExist++;
    }

    private List<Integer> getStats() {
      return Arrays.asList(numUpdates, numError, numDoesNotExist);
    }

    private void verifyStats(int updt, int err, int notExist) {
      assertThat(getStats()).isEqualTo(Arrays.asList(updt, err, notExist));
    }
  }

  private static class WrappedXdsClient extends XdsClient {
    private final XdsClient delegate;
    private final SynchronizationContext syncContext;
    private ResourceWatcher<LdsUpdate> ldsWatcher;

    WrappedXdsClient(XdsClient delegate, SynchronizationContext syncContext) {
      this.delegate = delegate;
      this.syncContext = syncContext;
    }

    @Override
    public void shutdown() {
      delegate.shutdown();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends ResourceUpdate> void watchXdsResource(
        XdsResourceType<T> type, String resourceName, ResourceWatcher<T> watcher,
        Executor executor) {
      if (type.equals(XdsListenerResource.getInstance())) {
        ldsWatcher = (ResourceWatcher<LdsUpdate>) watcher;
      }
      delegate.watchXdsResource(type, resourceName, watcher, executor);
    }



    @Override
    public <T extends ResourceUpdate> void cancelXdsResourceWatch(XdsResourceType<T> type,
                                                                  String resourceName,
                                                                  ResourceWatcher<T> watcher) {
      delegate.cancelXdsResourceWatch(type, resourceName, watcher);
    }

    void deliverLdsUpdate(long httpMaxStreamDurationNano,
                          List<io.grpc.xds.VirtualHost> virtualHosts) {
      syncContext.execute(() -> {
        LdsUpdate ldsUpdate = LdsUpdate.forApiListener(
            io.grpc.xds.HttpConnectionManager.forVirtualHosts(
            httpMaxStreamDurationNano, virtualHosts, null));
        ldsWatcher.onChanged(ldsUpdate);
      });
    }
  }
}
