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
import static io.grpc.xds.XdsTestUtils.getEdsNameForCluster;
import static io.grpc.xds.client.CommonBootstrapperTestUtils.SERVER_URI;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.ApiListener;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.clusters.aggregate.v3.ClusterConfig;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
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
import io.grpc.xds.client.CommonBootstrapperTestUtils;
import io.grpc.xds.client.XdsClientImpl;
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

    InOrder inOrder = org.mockito.Mockito.inOrder(xdsConfigWatcher);
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
    InOrder inOrder = org.mockito.Mockito.inOrder(xdsConfigWatcher);
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

    Map<String, StatusOr<XdsConfig.XdsClusterConfig>> lastConfigClusters =
        testWatcher.lastConfig.getClusters();
    assertThat(lastConfigClusters).hasSize(childNames.size() + 1);
    StatusOr<XdsConfig.XdsClusterConfig> rootC = lastConfigClusters.get(rootName);
    XdsClusterResource.CdsUpdate rootUpdate = rootC.getValue().getClusterResource();
    assertThat(rootUpdate.clusterType()).isEqualTo(AGGREGATE);
    assertThat(rootUpdate.prioritizedClusterNames()).isEqualTo(childNames);

    for (String childName : childNames) {
      assertThat(lastConfigClusters).containsKey(childName);
      XdsClusterResource.CdsUpdate childResource =
          lastConfigClusters.get(childName).getValue().getClusterResource();
      assertThat(childResource.clusterType()).isEqualTo(EDS);
      assertThat(childResource.edsServiceName()).isEqualTo(getEdsNameForCluster(childName));

      StatusOr<XdsEndpointResource.EdsUpdate> endpoint =
          lastConfigClusters.get(childName).getValue().getEndpoint();
      assertThat(endpoint.hasValue()).isTrue();
      assertThat(endpoint.getValue().clusterName).isEqualTo(getEdsNameForCluster(childName));
    }
  }

  @Test
  public void testComplexRegisteredAggregate() throws IOException {
    InOrder inOrder = org.mockito.Mockito.inOrder(xdsConfigWatcher);

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
    InOrder inOrder = org.mockito.Mockito.inOrder(xdsConfigWatcher);
    xdsDependencyManager = new XdsDependencyManager(
        xdsClient, xdsConfigWatcher, syncContext, serverName, serverName);
    inOrder.verify(xdsConfigWatcher, timeout(1000)).onUpdate(defaultXdsConfig);

    String rootName1 = "root_c";
    List<String> childNames = Arrays.asList("clusterC", "clusterB", "clusterA");

    Closeable subscription1 = xdsDependencyManager.subscribeToCluster(rootName1);
    assertThat(subscription1).isNotNull();
    fakeClock.forwardTime(16, TimeUnit.SECONDS);
    inOrder.verify(xdsConfigWatcher).onUpdate(xdsConfigCaptor.capture());
    assertThat(xdsConfigCaptor.getValue().getClusters().get(rootName1).toString()).isEqualTo(
        StatusOr.fromStatus(Status.UNAVAILABLE.withDescription(
            "No " + toContextStr(CLUSTER_TYPE_NAME, rootName1))).toString());

    XdsTestUtils.addAggregateToExistingConfig(controlPlaneService, rootName1, childNames);
    inOrder.verify(xdsConfigWatcher).onUpdate(xdsConfigCaptor.capture());
    assertThat(xdsConfigCaptor.getValue().getClusters().get(rootName1).hasValue()).isTrue();
  }

  @Test
  public void testMissingCdsAndEds() {
    // update config so that agg cluster references 2 existing & 1 non-existing cluster
    List<String> childNames = Arrays.asList("clusterC", "clusterB", "clusterA");
    ClusterConfig rootConfig = ClusterConfig.newBuilder().addAllClusters(childNames).build();
    Cluster.CustomClusterType type =
        Cluster.CustomClusterType.newBuilder()
            .setName(XdsClusterResource.AGGREGATE_CLUSTER_TYPE_NAME)
            .setTypedConfig(Any.pack(rootConfig))
            .build();
    Cluster.Builder builder =
        Cluster.newBuilder().setName(CLUSTER_NAME).setClusterType(type);
    builder.setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN);
    Cluster cluster = builder.build();
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

    List<StatusOr<XdsConfig.XdsClusterConfig>> returnedClusters = new ArrayList<>();
    for (String childName : childNames) {
      returnedClusters.add(xdsConfigCaptor.getValue().getClusters().get(childName));
    }

    // Check that missing cluster reported Status and the other 2 are present
    Status expectedClusterStatus = Status.UNAVAILABLE.withDescription(
        "No " + toContextStr(CLUSTER_TYPE_NAME , childNames.get(2)));
    StatusOr<XdsConfig.XdsClusterConfig> missingCluster = returnedClusters.get(2);
    assertThat(missingCluster.getStatus().toString()).isEqualTo(expectedClusterStatus.toString());
    assertThat(returnedClusters.get(0).hasValue()).isTrue();
    assertThat(returnedClusters.get(1).hasValue()).isTrue();

    // Check that missing EDS reported Status, the other one is present and the garbage EDS is not
    Status expectedEdsStatus = Status.UNAVAILABLE.withDescription(
        "No " + toContextStr(ENDPOINT_TYPE_NAME , XdsTestUtils.EDS_NAME + 1));
    assertThat(returnedClusters.get(0).getValue().getEndpoint().hasValue()).isTrue();
    assertThat(returnedClusters.get(1).getValue().getEndpoint().hasValue()).isFalse();
    assertThat(returnedClusters.get(1).getValue().getEndpoint().getStatus().toString())
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
        toContextStr(XdsListenerResource.getInstance().typeName(),"badLdsName"));

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
        toContextStr(XdsRouteConfigureResource.getInstance().typeName(),"badRdsName"));

    testWatcher.verifyStats(0, 0, 1);
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
    testWatcher.verifyStats(0,1, 0);
  }

  @Test
  public void testChangeRdsName_fromLds() {
    // TODO implement
    InOrder inOrder = org.mockito.Mockito.inOrder(xdsConfigWatcher);
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
  }

  @Test
  public void testChangeRdsName_notFromLds() {
    // TODO implement
  }

  @Test
  public void testMultipleParentsInCdsTree() {
    // TODO implement
  }

  @Test
  public void testMultipleCdsReferToSameEds() {
    // TODO implement
  }

  @Test
  public void testChangeRdsName_FromLds_complexTree() {
    // TODO implement
  }

  private Listener buildInlineClientListener(String rdsName, String clusterName) {
    HttpFilter
        httpFilter = HttpFilter.newBuilder()
        .setName(serverName)
        .setTypedConfig(Any.pack(Router.newBuilder().build()))
        .setIsOptional(true)
        .build();
    ApiListener.Builder clientListenerBuilder =
        ApiListener.newBuilder().setApiListener(Any.pack(
            io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3
                .HttpConnectionManager.newBuilder()
                .setRouteConfig(
                    XdsTestUtils.buildRouteConfiguration(serverName, rdsName, clusterName))
                .addAllHttpFilters(Collections.singletonList(httpFilter))
                .build(),
            XdsTestUtils.HTTP_CONNECTION_MANAGER_TYPE_URL));
    return Listener.newBuilder()
        .setName(serverName)
        .setApiListener(clientListenerBuilder.build()).build();

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
      log.fine(String.format("Error %s for %s: ",  status, resourceContext));
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
}
