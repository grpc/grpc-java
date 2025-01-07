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
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_RDS;
import static io.grpc.xds.XdsTestUtils.getEdsNameForCluster;
import static io.grpc.xds.client.CommonBootstrapperTestUtils.SERVER_URI;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableMap;
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
import io.grpc.xds.client.CommonBootstrapperTestUtils;
import io.grpc.xds.client.XdsClientImpl;
import io.grpc.xds.client.XdsClientMetricReporter;
import io.grpc.xds.client.XdsTransportFactory;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link XdsNameResolverProvider}. */
@RunWith(JUnit4.class)
public class XdsDependencyManagerTest {
  private static final Logger log = Logger.getLogger(XdsDependencyManagerTest.class.getName());

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
        XdsTestUtils.ENDPOINT_HOSTNAME + "2", XdsTestUtils.ENDPOINT_PORT + 2);
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
        testWatcher.lastConfig.clusters;
    assertThat(lastConfigClusters).hasSize(childNames.size() + 1);
    StatusOr<XdsConfig.XdsClusterConfig> rootC = lastConfigClusters.get(rootName);
    XdsClusterResource.CdsUpdate rootUpdate = rootC.getValue().clusterResource;
    assertThat(rootUpdate.clusterType()).isEqualTo(AGGREGATE);
    assertThat(rootUpdate.prioritizedClusterNames()).isEqualTo(childNames);

    for (String childName : childNames) {
      assertThat(lastConfigClusters).containsKey(childName);
      XdsClusterResource.CdsUpdate childResource =
          lastConfigClusters.get(childName).getValue().clusterResource;
      assertThat(childResource.clusterType()).isEqualTo(EDS);
      assertThat(childResource.edsServiceName()).isEqualTo(getEdsNameForCluster(childName));

      StatusOr<XdsEndpointResource.EdsUpdate> endpoint =
          lastConfigClusters.get(childName).getValue().getEndpoint();
      assertThat(endpoint.hasValue()).isTrue();
      assertThat(endpoint.getValue().clusterName).isEqualTo(getEdsNameForCluster(childName));
    }
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
