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
import static io.grpc.StatusMatcher.statusHasCode;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.grpc.BindableService;
import io.grpc.ChannelLogger;
import io.grpc.ManagedChannel;
import io.grpc.NameResolver;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.StatusOrMatcher;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.internal.GrpcUtil;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.XdsClusterResource.CdsUpdate;
import io.grpc.xds.XdsConfig.XdsClusterConfig;
import io.grpc.xds.XdsEndpointResource.EdsUpdate;
import io.grpc.xds.client.CommonBootstrapperTestUtils;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsClientMetricReporter;
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
import java.util.concurrent.ScheduledExecutorService;
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
import org.mockito.ArgumentMatcher;
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
      new SynchronizationContext((t, e) -> {
        throw new AssertionError(e);
      });

  private ManagedChannel channel;
  private XdsClient xdsClient;
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
  private ArgumentCaptor<StatusOr<XdsConfig>> xdsUpdateCaptor;
  private final NameResolver.Args nameResolverArgs = NameResolver.Args.newBuilder()
      .setDefaultPort(8080)
      .setProxyDetector(GrpcUtil.DEFAULT_PROXY_DETECTOR)
      .setSynchronizationContext(syncContext)
      .setServiceConfigParser(mock(NameResolver.ServiceConfigParser.class))
      .setChannelLogger(mock(ChannelLogger.class))
      .setScheduledExecutorService(fakeClock.getScheduledExecutorService())
      .build();

  private final ScheduledExecutorService scheduler = fakeClock.getScheduledExecutorService();

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
    xdsDependencyManager = new XdsDependencyManager(xdsClient, xdsConfigWatcher, syncContext,
        serverName, serverName, nameResolverArgs, scheduler);

    verify(xdsConfigWatcher, timeout(1000)).onUpdate(StatusOr.fromValue(defaultXdsConfig));
    testWatcher.verifyStats(1, 0);
  }

  @Test
  public void verify_config_update() {
    xdsDependencyManager = new XdsDependencyManager(xdsClient, xdsConfigWatcher, syncContext,
        serverName, serverName, nameResolverArgs, scheduler);

    InOrder inOrder = Mockito.inOrder(xdsConfigWatcher);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(StatusOr.fromValue(defaultXdsConfig));
    testWatcher.verifyStats(1, 0);
    assertThat(testWatcher.lastConfig).isEqualTo(defaultXdsConfig);

    XdsTestUtils.setAdsConfig(controlPlaneService, serverName, "RDS2", "CDS2", "EDS2",
        ENDPOINT_HOSTNAME + "2", ENDPOINT_PORT + 2);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(ArgumentMatchers.notNull());
    testWatcher.verifyStats(2, 0);
    assertThat(testWatcher.lastConfig).isNotEqualTo(defaultXdsConfig);
  }

  @Test
  public void verify_simple_aggregate() {
    InOrder inOrder = Mockito.inOrder(xdsConfigWatcher);
    xdsDependencyManager = new XdsDependencyManager(xdsClient, xdsConfigWatcher, syncContext,
        serverName, serverName, nameResolverArgs, scheduler);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(StatusOr.fromValue(defaultXdsConfig));

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
    CdsUpdate rootUpdate = rootC.getValue().getClusterResource();
    assertThat(rootUpdate.clusterType()).isEqualTo(AGGREGATE);
    assertThat(rootUpdate.prioritizedClusterNames()).isEqualTo(childNames);

    for (String childName : childNames) {
      assertThat(lastConfigClusters).containsKey(childName);
      StatusOr<XdsClusterConfig> childConfigOr = lastConfigClusters.get(childName);
      CdsUpdate childResource =
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

    xdsDependencyManager = new XdsDependencyManager(xdsClient, xdsConfigWatcher, syncContext,
        serverName, serverName, nameResolverArgs, scheduler);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(any());

    Closeable subscription1 = xdsDependencyManager.subscribeToCluster(rootName1);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(any());

    Closeable subscription2 = xdsDependencyManager.subscribeToCluster(rootName2);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(xdsUpdateCaptor.capture());
    testWatcher.verifyStats(3, 0);
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();
    Set<String> expectedClusters = builder.add(rootName1).add(rootName2).add(CLUSTER_NAME)
        .addAll(childNames).addAll(childNames2).build();
    assertThat(xdsUpdateCaptor.getValue().getValue().getClusters().keySet())
        .isEqualTo(expectedClusters);

    // Close 1 subscription shouldn't affect the other or RDS subscriptions
    subscription1.close();
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(xdsUpdateCaptor.capture());
    builder = ImmutableSet.builder();
    Set<String> expectedClusters2 =
        builder.add(rootName2).add(CLUSTER_NAME).addAll(childNames2).build();
    assertThat(xdsUpdateCaptor.getValue().getValue().getClusters().keySet())
        .isEqualTo(expectedClusters2);

    subscription2.close();
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(StatusOr.fromValue(defaultXdsConfig));
  }

  @Test
  public void testDelayedSubscription() {
    InOrder inOrder = Mockito.inOrder(xdsConfigWatcher);
    xdsDependencyManager = new XdsDependencyManager(xdsClient, xdsConfigWatcher, syncContext,
        serverName, serverName, nameResolverArgs, scheduler);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(StatusOr.fromValue(defaultXdsConfig));

    String rootName1 = "root_c";

    Closeable subscription1 = xdsDependencyManager.subscribeToCluster(rootName1);
    assertThat(subscription1).isNotNull();
    fakeClock.forwardTime(16, TimeUnit.SECONDS);
    inOrder.verify(xdsConfigWatcher).onUpdate(xdsUpdateCaptor.capture());
    Status status = xdsUpdateCaptor.getValue().getValue().getClusters().get(rootName1).getStatus();
    assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(status.getDescription()).contains(rootName1);

    List<String> childNames = Arrays.asList("clusterC", "clusterB", "clusterA");
    XdsTestUtils.addAggregateToExistingConfig(controlPlaneService, rootName1, childNames);
    inOrder.verify(xdsConfigWatcher).onUpdate(xdsUpdateCaptor.capture());
    assertThat(xdsUpdateCaptor.getValue().getValue().getClusters().get(rootName1).hasValue())
        .isTrue();
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

    xdsDependencyManager = new XdsDependencyManager(xdsClient, xdsConfigWatcher, syncContext,
        serverName, serverName, nameResolverArgs, scheduler);

    fakeClock.forwardTime(16, TimeUnit.SECONDS);
    verify(xdsConfigWatcher, timeout(1000)).onUpdate(xdsUpdateCaptor.capture());

    List<StatusOr<XdsClusterConfig>> returnedClusters = new ArrayList<>();
    for (String childName : childNames) {
      returnedClusters.add(xdsUpdateCaptor.getValue().getValue().getClusters().get(childName));
    }

    // Check that missing cluster reported Status and the other 2 are present
    StatusOr<XdsClusterConfig> missingCluster = returnedClusters.get(2);
    assertThat(missingCluster.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(missingCluster.getStatus().getDescription()).contains(childNames.get(2));
    assertThat(returnedClusters.get(0).hasValue()).isTrue();
    assertThat(returnedClusters.get(1).hasValue()).isTrue();

    // Check that missing EDS reported Status, the other one is present and the garbage EDS is not
    assertThat(getEndpoint(returnedClusters.get(0)).hasValue()).isTrue();
    assertThat(getEndpoint(returnedClusters.get(1)).getStatus().getCode())
        .isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(getEndpoint(returnedClusters.get(1)).getStatus().getDescription())
        .contains(XdsTestUtils.EDS_NAME + 1);

    verify(xdsConfigWatcher, never()).onUpdate(
        argThat(StatusOrMatcher.hasStatus(statusHasCode(Status.Code.UNAVAILABLE))));
    testWatcher.verifyStats(1, 0);
  }

  @Test
  public void testMissingLds() {
    String ldsName = "badLdsName";
    xdsDependencyManager = new XdsDependencyManager(xdsClient, xdsConfigWatcher, syncContext,
        serverName, ldsName, nameResolverArgs, scheduler);

    fakeClock.forwardTime(16, TimeUnit.SECONDS);
    verify(xdsConfigWatcher, timeout(1000)).onUpdate(
        argThat(StatusOrMatcher.hasStatus(statusHasCode(Status.Code.UNAVAILABLE)
            .andDescriptionContains(ldsName))));

    testWatcher.verifyStats(0, 1);
  }

  @Test
  public void testTcpListenerErrors() {
    Listener serverListener =
        ControlPlaneRule.buildServerListener().toBuilder().setName(serverName).build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_LDS, ImmutableMap.of(serverName, serverListener));
    xdsDependencyManager = new XdsDependencyManager(xdsClient, xdsConfigWatcher, syncContext,
        serverName, serverName, nameResolverArgs, scheduler);

    fakeClock.forwardTime(16, TimeUnit.SECONDS);
    verify(xdsConfigWatcher, timeout(1000)).onUpdate(
        argThat(StatusOrMatcher.hasStatus(
            statusHasCode(Status.Code.UNAVAILABLE).andDescriptionContains("Not an API listener"))));

    testWatcher.verifyStats(0, 1);
  }

  @Test
  public void testMissingRds() {
    String rdsName = "badRdsName";
    Listener clientListener = ControlPlaneRule.buildClientListener(serverName, serverName, rdsName);
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_LDS,
        ImmutableMap.of(serverName, clientListener));

    xdsDependencyManager = new XdsDependencyManager(xdsClient, xdsConfigWatcher, syncContext,
        serverName, serverName, nameResolverArgs, scheduler);

    fakeClock.forwardTime(16, TimeUnit.SECONDS);
    verify(xdsConfigWatcher, timeout(1000)).onUpdate(
        argThat(StatusOrMatcher.hasStatus(statusHasCode(Status.Code.UNAVAILABLE)
            .andDescriptionContains(rdsName))));

    testWatcher.verifyStats(0, 1);
  }

  @Test
  public void testUpdateToMissingVirtualHost() {
    RouteConfiguration routeConfig = XdsTestUtils.buildRouteConfiguration(
        "wrong-virtual-host", XdsTestUtils.RDS_NAME, XdsTestUtils.CLUSTER_NAME);
    controlPlaneService.setXdsConfig(
        ADS_TYPE_URL_RDS, ImmutableMap.of(XdsTestUtils.RDS_NAME, routeConfig));
    xdsDependencyManager = new XdsDependencyManager(xdsClient, xdsConfigWatcher, syncContext,
        serverName, serverName, nameResolverArgs, scheduler);

    // Update with a config that has a virtual host that doesn't match the server name
    verify(xdsConfigWatcher, timeout(1000)).onUpdate(xdsUpdateCaptor.capture());
    assertThat(xdsUpdateCaptor.getValue().getStatus().getDescription())
        .contains("Failed to find virtual host matching hostname: " + serverName);

    testWatcher.verifyStats(0, 1);
  }

  @Test
  public void testCorruptLds() {
    String ldsResourceName =
        "xdstp://unknown.example.com/envoy.config.listener.v3.Listener/listener1";

    xdsDependencyManager = new XdsDependencyManager(xdsClient, xdsConfigWatcher, syncContext,
        serverName, ldsResourceName, nameResolverArgs, scheduler);

    verify(xdsConfigWatcher, timeout(1000)).onUpdate(
        argThat(StatusOrMatcher.hasStatus(
            statusHasCode(Status.Code.UNAVAILABLE).andDescriptionContains(ldsResourceName))));

    fakeClock.forwardTime(16, TimeUnit.SECONDS);
    testWatcher.verifyStats(0, 1);
  }

  @Test
  public void testChangeRdsName_fromLds() {
    InOrder inOrder = Mockito.inOrder(xdsConfigWatcher);
    xdsDependencyManager = new XdsDependencyManager(xdsClient, xdsConfigWatcher, syncContext,
        serverName, serverName, nameResolverArgs, scheduler);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(StatusOr.fromValue(defaultXdsConfig));

    String newRdsName = "newRdsName1";

    Listener clientListener = buildInlineClientListener(newRdsName, CLUSTER_NAME);
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_LDS,
        ImmutableMap.of(serverName, clientListener));
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(xdsUpdateCaptor.capture());
    assertThat(xdsUpdateCaptor.getValue().getValue()).isNotEqualTo(defaultXdsConfig);
    assertThat(xdsUpdateCaptor.getValue().getValue().getVirtualHost().name()).isEqualTo(newRdsName);
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
    xdsDependencyManager = new XdsDependencyManager(xdsClient, xdsConfigWatcher, syncContext,
        serverName, serverName, nameResolverArgs, scheduler);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(xdsUpdateCaptor.capture());
    XdsConfig initialConfig = xdsUpdateCaptor.getValue().getValue();

    // Make sure that adding subscriptions that rds points at doesn't change the config
    Closeable rootSub = xdsDependencyManager.subscribeToCluster("root");
    assertThat(xdsDependencyManager.buildUpdate().getValue()).isEqualTo(initialConfig);
    Closeable clusterAB11Sub = xdsDependencyManager.subscribeToCluster("clusterAB11");
    assertThat(xdsDependencyManager.buildUpdate().getValue()).isEqualTo(initialConfig);

    // Make sure that closing subscriptions that rds points at doesn't change the config
    rootSub.close();
    assertThat(xdsDependencyManager.buildUpdate().getValue()).isEqualTo(initialConfig);
    clusterAB11Sub.close();
    assertThat(xdsDependencyManager.buildUpdate().getValue()).isEqualTo(initialConfig);

    // Make an explicit root subscription and then change RDS to point to A11
    rootSub = xdsDependencyManager.subscribeToCluster("root");
    RouteConfiguration newRouteConfig =
        XdsTestUtils.buildRouteConfiguration(serverName, XdsTestUtils.RDS_NAME, "clusterA11");
    controlPlaneService.setXdsConfig(
        ADS_TYPE_URL_RDS, ImmutableMap.of(XdsTestUtils.RDS_NAME, newRouteConfig));
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(xdsUpdateCaptor.capture());
    assertThat(xdsUpdateCaptor.getValue().getValue().getClusters().keySet().size()).isEqualTo(4);

    // Now that it is released, we should only have A11
    rootSub.close();
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(xdsUpdateCaptor.capture());
    assertThat(xdsUpdateCaptor.getValue().getValue().getClusters().keySet())
        .containsExactly("clusterA11");
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
    xdsDependencyManager = new XdsDependencyManager(xdsClient, xdsConfigWatcher, syncContext,
        serverName, serverName, nameResolverArgs, scheduler);
    verify(xdsConfigWatcher, timeout(1000)).onUpdate(xdsUpdateCaptor.capture());
    XdsConfig initialConfig = xdsUpdateCaptor.getValue().getValue();
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
    xdsDependencyManager = new XdsDependencyManager(xdsClient, xdsConfigWatcher, syncContext,
        serverName, serverName, nameResolverArgs, scheduler);

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
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_LDS,
        ImmutableMap.of(serverName, clientListener));
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(xdsUpdateCaptor.capture());
    XdsConfig config = xdsUpdateCaptor.getValue().getValue();
    assertThat(config.getVirtualHost().name()).isEqualTo(newRdsName);
    assertThat(config.getClusters().size()).isEqualTo(4);
  }

  @Test
  public void testChangeAggCluster() {
    InOrder inOrder = Mockito.inOrder(xdsConfigWatcher);

    xdsDependencyManager = new XdsDependencyManager(xdsClient, xdsConfigWatcher, syncContext,
        serverName, serverName, nameResolverArgs, scheduler);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(any());

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
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_LDS,
        ImmutableMap.of(serverName, clientListener));

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
    ClusterNameMatcher nameMatcher
        = new ClusterNameMatcher(Arrays.asList("root", "clusterA21", "clusterA22"));
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(argThat(nameMatcher));
  }

  @Test
  public void testCdsError() throws IOException {
    controlPlaneService.setXdsConfig(
        ADS_TYPE_URL_CDS, ImmutableMap.of(XdsTestUtils.CLUSTER_NAME,
          Cluster.newBuilder().setName(XdsTestUtils.CLUSTER_NAME).build()));
    xdsDependencyManager = new XdsDependencyManager(xdsClient, xdsConfigWatcher, syncContext,
        serverName, serverName, nameResolverArgs, scheduler);

    verify(xdsConfigWatcher, timeout(1000)).onUpdate(xdsUpdateCaptor.capture());
    Status status = xdsUpdateCaptor.getValue().getValue()
        .getClusters().get(CLUSTER_NAME).getStatus();
    assertThat(status.getDescription()).contains(XdsTestUtils.CLUSTER_NAME);
  }

  @Test
  public void ldsUpdateAfterShutdown() {
    XdsTestUtils.setAdsConfig(controlPlaneService, serverName, "RDS", "CDS", "EDS",
        ENDPOINT_HOSTNAME, ENDPOINT_PORT);

    xdsDependencyManager = new XdsDependencyManager(xdsClient, xdsConfigWatcher, syncContext,
        serverName, serverName, nameResolverArgs, scheduler);

    verify(xdsConfigWatcher, timeout(1000)).onUpdate(any());

    @SuppressWarnings("unchecked")
    XdsClient.ResourceWatcher<XdsListenerResource.LdsUpdate> resourceWatcher =
        mock(XdsClient.ResourceWatcher.class);
    xdsClient.watchXdsResource(
        XdsListenerResource.getInstance(),
        serverName,
        resourceWatcher,
        MoreExecutors.directExecutor());
    verify(resourceWatcher, timeout(5000)).onChanged(any());

    syncContext.execute(() -> {
      // Shutdown before any updates. This will unsubscribe from XdsClient, but only after this
      // Runnable returns
      xdsDependencyManager.shutdown();

      XdsTestUtils.setAdsConfig(controlPlaneService, serverName, "RDS2", "CDS", "EDS",
          ENDPOINT_HOSTNAME, ENDPOINT_PORT);
      verify(resourceWatcher, timeout(5000).times(2)).onChanged(any());
      xdsClient.cancelXdsResourceWatch(
          XdsListenerResource.getInstance(), serverName, resourceWatcher);
    });
  }

  @Test
  public void rdsUpdateAfterShutdown() {
    XdsTestUtils.setAdsConfig(controlPlaneService, serverName, "RDS", "CDS", "EDS",
        ENDPOINT_HOSTNAME, ENDPOINT_PORT);

    xdsDependencyManager = new XdsDependencyManager(xdsClient, xdsConfigWatcher, syncContext,
        serverName, serverName, nameResolverArgs, scheduler);

    verify(xdsConfigWatcher, timeout(1000)).onUpdate(any());

    @SuppressWarnings("unchecked")
    XdsClient.ResourceWatcher<XdsRouteConfigureResource.RdsUpdate> resourceWatcher =
        mock(XdsClient.ResourceWatcher.class);
    xdsClient.watchXdsResource(
        XdsRouteConfigureResource.getInstance(),
        "RDS",
        resourceWatcher,
        MoreExecutors.directExecutor());
    verify(resourceWatcher, timeout(5000)).onChanged(any());

    syncContext.execute(() -> {
      // Shutdown before any updates. This will unsubscribe from XdsClient, but only after this
      // Runnable returns
      xdsDependencyManager.shutdown();

      XdsTestUtils.setAdsConfig(controlPlaneService, serverName, "RDS", "CDS2", "EDS",
          ENDPOINT_HOSTNAME, ENDPOINT_PORT);
      verify(resourceWatcher, timeout(5000).times(2)).onChanged(any());
      xdsClient.cancelXdsResourceWatch(
          XdsRouteConfigureResource.getInstance(), serverName, resourceWatcher);
    });
  }

  @Test
  public void cdsUpdateAfterShutdown() {
    XdsTestUtils.setAdsConfig(controlPlaneService, serverName, "RDS", "CDS", "EDS",
        ENDPOINT_HOSTNAME, ENDPOINT_PORT);

    xdsDependencyManager = new XdsDependencyManager(xdsClient, xdsConfigWatcher, syncContext,
        serverName, serverName, nameResolverArgs, scheduler);

    verify(xdsConfigWatcher, timeout(1000)).onUpdate(any());

    @SuppressWarnings("unchecked")
    XdsClient.ResourceWatcher<XdsClusterResource.CdsUpdate> resourceWatcher =
        mock(XdsClient.ResourceWatcher.class);
    xdsClient.watchXdsResource(
        XdsClusterResource.getInstance(),
        "CDS",
        resourceWatcher,
        MoreExecutors.directExecutor());
    verify(resourceWatcher, timeout(5000)).onChanged(any());

    syncContext.execute(() -> {
      // Shutdown before any updates. This will unsubscribe from XdsClient, but only after this
      // Runnable returns
      xdsDependencyManager.shutdown();

      XdsTestUtils.setAdsConfig(controlPlaneService, serverName, "RDS", "CDS", "EDS2",
          ENDPOINT_HOSTNAME, ENDPOINT_PORT);
      verify(resourceWatcher, timeout(5000).times(2)).onChanged(any());
      xdsClient.cancelXdsResourceWatch(
          XdsClusterResource.getInstance(), serverName, resourceWatcher);
    });
  }

  @Test
  public void edsUpdateAfterShutdown() {
    XdsTestUtils.setAdsConfig(controlPlaneService, serverName, "RDS", "CDS", "EDS",
        ENDPOINT_HOSTNAME, ENDPOINT_PORT);

    xdsDependencyManager = new XdsDependencyManager(xdsClient, xdsConfigWatcher, syncContext,
        serverName, serverName, nameResolverArgs, scheduler);

    verify(xdsConfigWatcher, timeout(1000)).onUpdate(any());

    @SuppressWarnings("unchecked")
    XdsClient.ResourceWatcher<XdsEndpointResource.EdsUpdate> resourceWatcher =
        mock(XdsClient.ResourceWatcher.class);
    xdsClient.watchXdsResource(
        XdsEndpointResource.getInstance(),
        "EDS",
        resourceWatcher,
        MoreExecutors.directExecutor());
    verify(resourceWatcher, timeout(5000)).onChanged(any());

    syncContext.execute(() -> {
      // Shutdown before any updates. This will unsubscribe from XdsClient, but only after this
      // Runnable returns
      xdsDependencyManager.shutdown();

      XdsTestUtils.setAdsConfig(controlPlaneService, serverName, "RDS", "CDS", "EDS",
          ENDPOINT_HOSTNAME + "2", ENDPOINT_PORT);
      verify(resourceWatcher, timeout(5000).times(2)).onChanged(any());
      xdsClient.cancelXdsResourceWatch(
          XdsEndpointResource.getInstance(), serverName, resourceWatcher);
    });
  }

  private Listener buildInlineClientListener(String rdsName, String clusterName) {
    return XdsTestUtils.buildInlineClientListener(rdsName, clusterName, serverName);
  }

  private static class TestWatcher implements XdsDependencyManager.XdsConfigWatcher {
    XdsConfig lastConfig;
    int numUpdates = 0;
    int numError = 0;

    @Override
    public void onUpdate(StatusOr<XdsConfig> update) {
      log.fine("Config update: " + update);
      if (update.hasValue()) {
        lastConfig = update.getValue();
        numUpdates++;
      } else {
        numError++;
      }
    }

    private List<Integer> getStats() {
      return Arrays.asList(numUpdates, numError);
    }

    private void verifyStats(int updt, int err) {
      assertThat(getStats()).isEqualTo(Arrays.asList(updt, err));
    }
  }

  static class ClusterNameMatcher implements ArgumentMatcher<StatusOr<XdsConfig>> {
    private final List<String> expectedNames;

    ClusterNameMatcher(List<String> expectedNames) {
      this.expectedNames = expectedNames;
    }

    @Override
    public boolean matches(StatusOr<XdsConfig> update) {
      if (!update.hasValue()) {
        return false;
      }
      XdsConfig xdsConfig = update.getValue();
      if (xdsConfig == null || xdsConfig.getClusters() == null) {
        return false;
      }
      return xdsConfig.getClusters().size() == expectedNames.size()
          && xdsConfig.getClusters().keySet().containsAll(expectedNames);
    }
  }
}
