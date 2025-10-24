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
import static com.google.common.truth.Truth.assertWithMessage;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.MetricRecorder;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.XdsClusterResource.CdsUpdate;
import io.grpc.xds.XdsListenerResource.LdsUpdate;
import io.grpc.xds.XdsRouteConfigureResource.RdsUpdate;
import io.grpc.xds.client.Bootstrapper;
import io.grpc.xds.client.CommonBootstrapperTestUtils;
import io.grpc.xds.client.LoadReportClient;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsClientImpl;
import io.grpc.xds.client.XdsClientMetricReporter;
import io.grpc.xds.client.XdsInitializationException;
import io.grpc.xds.client.XdsTransportFactory;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class XdsClientFallbackTest {
  private static final Logger log = Logger.getLogger(XdsClientFallbackTest.class.getName());

  private static final String MAIN_SERVER = "main-server";
  private static final String FALLBACK_SERVER = "fallback-server";
  private static final String DUMMY_TARGET = "TEST_TARGET";
  private static final String RDS_NAME = "route-config.googleapis.com";
  private static final String FALLBACK_RDS_NAME = "fallback-" + RDS_NAME;
  private static final String CLUSTER_NAME = "cluster0";
  private static final String FALLBACK_CLUSTER_NAME = "fallback-" + CLUSTER_NAME;
  private static final String EDS_NAME = "eds-service-0";
  private static final String FALLBACK_EDS_NAME = "fallback-" + EDS_NAME;
  private static final HttpConnectionManager MAIN_HTTP_CONNECTION_MANAGER =
      HttpConnectionManager.forRdsName(0, RDS_NAME, ImmutableList.of(
          new Filter.NamedFilterConfig("terminal-filter", RouterFilter.ROUTER_CONFIG)));
  private static final HttpConnectionManager FALLBACK_HTTP_CONNECTION_MANAGER =
      HttpConnectionManager.forRdsName(0, FALLBACK_RDS_NAME, ImmutableList.of(
          new Filter.NamedFilterConfig("terminal-filter", RouterFilter.ROUTER_CONFIG)));
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  private boolean originalEnableXdsFallback;
  private final FakeClock fakeClock = new FakeClock();
  private final MetricRecorder metricRecorder = new MetricRecorder() {};

  @Mock
  private XdsClientMetricReporter xdsClientMetricReporter;

  @Captor
  private ArgumentCaptor<StatusOr<LdsUpdate>> ldsUpdateCaptor;
  @Captor
  private ArgumentCaptor<StatusOr<RdsUpdate>> rdsUpdateCaptor;

  private final XdsClient.ResourceWatcher<XdsListenerResource.LdsUpdate> raalLdsWatcher =
      new XdsClient.ResourceWatcher<LdsUpdate>() {

        @Override
        public void onResourceChanged(StatusOr<LdsUpdate> update) {
          if (update.hasValue()) {
            log.log(Level.FINE, "LDS update: " + update.getValue());
          } else {
            log.log(Level.FINE, "LDS resource error: " + update.getStatus().getDescription());
          }
        }

        @Override
        public void onAmbientError(Status error) {
          log.log(Level.FINE, "LDS ambient error: " + error.getDescription());
        }
      };

  @SuppressWarnings("unchecked")
  private final XdsClient.ResourceWatcher<XdsListenerResource.LdsUpdate> ldsWatcher =
      mock(XdsClient.ResourceWatcher.class, delegatesTo(raalLdsWatcher));
  @Mock
  private XdsClient.ResourceWatcher<XdsListenerResource.LdsUpdate> ldsWatcher2;

  @Mock
  private XdsClient.ResourceWatcher<XdsRouteConfigureResource.RdsUpdate> rdsWatcher;
  @Mock
  private XdsClient.ResourceWatcher<XdsRouteConfigureResource.RdsUpdate> rdsWatcher2;
  @Mock
  private XdsClient.ResourceWatcher<XdsRouteConfigureResource.RdsUpdate> rdsWatcher3;

  private final XdsClient.ResourceWatcher<CdsUpdate> raalCdsWatcher =
      new XdsClient.ResourceWatcher<CdsUpdate>() {

        @Override
        public void onResourceChanged(StatusOr<CdsUpdate> update) {
          if (update.hasValue()) {
            log.log(Level.FINE, "CDS update: " + update.getValue());
          } else {
            log.log(Level.FINE, "CDS resource error: " + update.getStatus().getDescription());
          }
        }

        @Override
        public void onAmbientError(Status error) {
          // Logic from the old onError method for transient errors.
          log.log(Level.FINE, "CDS ambient error: " + error.getDescription());
        }
      };

  @SuppressWarnings("unchecked")
  private final XdsClient.ResourceWatcher<CdsUpdate> cdsWatcher =
      mock(XdsClient.ResourceWatcher.class, delegatesTo(raalCdsWatcher));
  @Mock
  private XdsClient.ResourceWatcher<CdsUpdate> cdsWatcher2;

  @Rule(order = 0)
  public ControlPlaneRule mainXdsServer =
      new ControlPlaneRule().setServerHostName(MAIN_SERVER);

  @Rule(order = 1)
  public ControlPlaneRule fallbackServer =
      new ControlPlaneRule().setServerHostName(MAIN_SERVER);

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Before
  public void setUp() throws XdsInitializationException {
    originalEnableXdsFallback = CommonBootstrapperTestUtils.setEnableXdsFallback(true);
    if (mainXdsServer == null) {
      throw new XdsInitializationException("Failed to create ControlPlaneRule for main TD server");
    }
    setAdsConfig(mainXdsServer, MAIN_SERVER);
    setAdsConfig(fallbackServer, FALLBACK_SERVER);

    SharedXdsClientPoolProvider clientPoolProvider = new SharedXdsClientPoolProvider();
    clientPoolProvider.setBootstrapOverride(defaultBootstrapOverride());
    xdsClientPool = clientPoolProvider.getOrCreate(DUMMY_TARGET, metricRecorder);
  }

  @After
  public void cleanUp() {
    if (xdsClientPool != null) {
      xdsClientPool.returnObject(xdsClient);
    }
    CommonBootstrapperTestUtils.setEnableXdsFallback(originalEnableXdsFallback);
  }

  private static void setAdsConfig(ControlPlaneRule controlPlane, String serverName) {
    InetSocketAddress edsInetSocketAddress =
        (InetSocketAddress) controlPlane.getServer().getListenSockets().get(0);
    boolean isMainServer = serverName.equals(MAIN_SERVER);
    String rdsName = isMainServer
                     ? RDS_NAME
                     : FALLBACK_RDS_NAME;
    String clusterName = isMainServer ? CLUSTER_NAME : FALLBACK_CLUSTER_NAME;
    String edsName = isMainServer ? EDS_NAME : FALLBACK_EDS_NAME;

    controlPlane.setLdsConfig(ControlPlaneRule.buildServerListener(),
        ControlPlaneRule.buildClientListener(MAIN_SERVER, rdsName));

    controlPlane.setRdsConfig(rdsName,
        XdsTestUtils.buildRouteConfiguration(MAIN_SERVER, rdsName, clusterName));
    controlPlane.setCdsConfig(clusterName, ControlPlaneRule.buildCluster(clusterName, edsName));

    controlPlane.setEdsConfig(edsName,
        ControlPlaneRule.buildClusterLoadAssignment(edsInetSocketAddress.getHostName(),
            DataPlaneRule.ENDPOINT_HOST_NAME, edsInetSocketAddress.getPort(), edsName));
    log.log(Level.FINE,
        String.format("Set ADS config for %s with address %s", serverName, edsInetSocketAddress));
  }

  // This is basically a control test to make sure everything is set up correctly.
  @Test
  public void everything_okay() {
    mainXdsServer.restartXdsServer();
    fallbackServer.restartXdsServer();
    xdsClient = xdsClientPool.getObject();
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher);
    verify(ldsWatcher, timeout(5000)).onResourceChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().hasValue()).isTrue();
    assertThat(ldsUpdateCaptor.getValue().getValue()).isEqualTo(
        LdsUpdate.forApiListener(MAIN_HTTP_CONNECTION_MANAGER));

    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(), RDS_NAME, rdsWatcher);
    verify(rdsWatcher, timeout(5000)).onResourceChanged(rdsUpdateCaptor.capture());
    assertThat(rdsUpdateCaptor.getValue().hasValue()).isTrue();
  }

  @Test
  public void mainServerDown_fallbackServerUp() {
    mainXdsServer.getServer().shutdownNow();
    fallbackServer.restartXdsServer();
    xdsClient = xdsClientPool.getObject();
    log.log(Level.FINE, "Fallback port = " + fallbackServer.getServer().getPort());

    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher);

    verify(ldsWatcher, timeout(5000)).onResourceChanged(
        StatusOr.fromValue(XdsListenerResource.LdsUpdate.forApiListener(
            FALLBACK_HTTP_CONNECTION_MANAGER)));
  }

  @Test
  public void useBadAuthority() {
    xdsClient = xdsClientPool.getObject();
    InOrder inOrder = inOrder(ldsWatcher, rdsWatcher, rdsWatcher2, rdsWatcher3);

    String badPrefix = "xdstp://authority.xds.bad/envoy.config.listener.v3.Listener/";
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),
        badPrefix + "listener.googleapis.com", ldsWatcher);
    inOrder.verify(ldsWatcher, timeout(5000)).onResourceChanged(
        argThat(statusOr -> !statusOr.hasValue()));

    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(),
        badPrefix + "route-config.googleapis.bad", rdsWatcher);
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(),
        badPrefix + "route-config2.googleapis.bad", rdsWatcher2);
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(),
        badPrefix + "route-config3.googleapis.bad", rdsWatcher3);
    inOrder.verify(rdsWatcher, timeout(5000)).onResourceChanged(
        argThat(statusOr -> !statusOr.hasValue()));
    inOrder.verify(rdsWatcher2, timeout(5000)).onResourceChanged(
        argThat(statusOr -> !statusOr.hasValue()));
    inOrder.verify(rdsWatcher3, timeout(5000)).onResourceChanged(
        argThat(statusOr -> !statusOr.hasValue()));
    verify(rdsWatcher, never()).onResourceChanged(argThat(StatusOr::hasValue));

    // even after an error, a valid one will still work
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher2);
    verify(ldsWatcher2, timeout(5000)).onResourceChanged(ldsUpdateCaptor.capture());
    StatusOr<LdsUpdate> statusOr = ldsUpdateCaptor.getValue();
    assertThat(statusOr.hasValue()).isTrue();
    assertThat(statusOr.getValue()).isEqualTo(
        XdsListenerResource.LdsUpdate.forApiListener(MAIN_HTTP_CONNECTION_MANAGER));
  }

  @Test
  public void both_down_restart_main() {
    mainXdsServer.getServer().shutdownNow();
    fallbackServer.getServer().shutdownNow();
    xdsClient = xdsClientPool.getObject();

    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher);
    verify(ldsWatcher, timeout(5000).atLeastOnce())
        .onResourceChanged(argThat(statusOr -> !statusOr.hasValue()));
    verify(ldsWatcher, never()).onResourceChanged(argThat(StatusOr::hasValue));
    xdsClient.watchXdsResource(
        XdsRouteConfigureResource.getInstance(), RDS_NAME, rdsWatcher2);
    verify(rdsWatcher2, timeout(5000).atLeastOnce())
        .onResourceChanged(argThat(statusOr -> !statusOr.hasValue()));

    mainXdsServer.restartXdsServer();

    xdsClient.watchXdsResource(
        XdsRouteConfigureResource.getInstance(), RDS_NAME, rdsWatcher);

    verify(ldsWatcher, timeout(16000)).onResourceChanged(
        argThat(statusOr -> statusOr.hasValue() && statusOr.getValue().equals(
            XdsListenerResource.LdsUpdate.forApiListener(MAIN_HTTP_CONNECTION_MANAGER))));
    verify(rdsWatcher, timeout(5000)).onResourceChanged(argThat(StatusOr::hasValue));
    verify(rdsWatcher2, timeout(5000)).onResourceChanged(argThat(StatusOr::hasValue));
  }

  @Test
  public void mainDown_fallbackUp_restart_main() {
    mainXdsServer.getServer().shutdownNow();
    fallbackServer.restartXdsServer();
    xdsClient = xdsClientPool.getObject();
    InOrder inOrder = inOrder(ldsWatcher, rdsWatcher, cdsWatcher, cdsWatcher2);

    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher);
    inOrder.verify(ldsWatcher, timeout(5000)).onResourceChanged(
        StatusOr.fromValue(XdsListenerResource.LdsUpdate.forApiListener(
            FALLBACK_HTTP_CONNECTION_MANAGER)));

    // Watch another resource, also from the fallback server.
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(), FALLBACK_CLUSTER_NAME, cdsWatcher);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<StatusOr<CdsUpdate>> cdsUpdateCaptor1 = ArgumentCaptor.forClass(StatusOr.class);
    inOrder.verify(cdsWatcher, timeout(5000)).onResourceChanged(cdsUpdateCaptor1.capture());
    assertThat(cdsUpdateCaptor1.getValue().getStatus().isOk()).isTrue();

    assertThat(fallbackServer.getService().getSubscriberCounts()
        .get("type.googleapis.com/envoy.config.listener.v3.Listener")).isEqualTo(1);
    verifyNoSubscribers(mainXdsServer);

    mainXdsServer.restartXdsServer();

    // The existing ldsWatcher should receive a new update from the main server.
    // Note: This is not an inOrder verification because the timing of the switchover
    // can vary. We just need to verify it happens.
    verify(ldsWatcher, timeout(5000)).onResourceChanged(
        StatusOr.fromValue(XdsListenerResource.LdsUpdate.forApiListener(
            MAIN_HTTP_CONNECTION_MANAGER)));

    // Watch a new resource; should now come from the main server.
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(), RDS_NAME, rdsWatcher);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<StatusOr<RdsUpdate>> rdsUpdateCaptor = ArgumentCaptor.forClass(StatusOr.class);
    inOrder.verify(rdsWatcher, timeout(5000)).onResourceChanged(rdsUpdateCaptor.capture());
    assertThat(rdsUpdateCaptor.getValue().getStatus().isOk()).isTrue();
    verifyNoSubscribers(fallbackServer);

    xdsClient.watchXdsResource(XdsClusterResource.getInstance(), CLUSTER_NAME, cdsWatcher2);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<StatusOr<CdsUpdate>> cdsUpdateCaptor2 = ArgumentCaptor.forClass(StatusOr.class);
    inOrder.verify(cdsWatcher2, timeout(5000)).onResourceChanged(cdsUpdateCaptor2.capture());
    assertThat(cdsUpdateCaptor2.getValue().getStatus().isOk()).isTrue();

    verifyNoSubscribers(fallbackServer);
    assertThat(mainXdsServer.getService().getSubscriberCounts()
        .get("type.googleapis.com/envoy.config.listener.v3.Listener")).isEqualTo(1);
  }

  private static void verifyNoSubscribers(ControlPlaneRule rule) {
    for (Map.Entry<String, Integer> me : rule.getService().getSubscriberCounts().entrySet()) {
      String type = me.getKey();
      Integer count = me.getValue();
      assertWithMessage("Type with non-zero subscribers is: %s", type)
          .that(count).isEqualTo(0);
    }
  }

  // This test takes a long time because of the 16 sec timeout for non-existent resource
  @Test
  public void connect_then_mainServerDown_fallbackServerUp() throws Exception {
    mainXdsServer.restartXdsServer();
    fallbackServer.restartXdsServer();
    ExecutorService executor = Executors.newFixedThreadPool(1);
    XdsTransportFactory xdsTransportFactory = new XdsTransportFactory() {
      @Override
      public XdsTransport create(Bootstrapper.ServerInfo serverInfo) {
        ChannelCredentials channelCredentials =
            (ChannelCredentials) serverInfo.implSpecificConfig();
        return new GrpcXdsTransportFactory.GrpcXdsTransport(
            Grpc.newChannelBuilder(serverInfo.target(), channelCredentials)
              .executor(executor)
              .build());
      }
    };
    XdsClientImpl xdsClient = CommonBootstrapperTestUtils.createXdsClient(
        new GrpcBootstrapperImpl().bootstrap(defaultBootstrapOverride()),
        xdsTransportFactory, fakeClock, new ExponentialBackoffPolicy.Provider(),
        MessagePrinter.INSTANCE, xdsClientMetricReporter);

    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher);

    // Initial resource fetch from the main server
    verify(ldsWatcher, timeout(5000)).onResourceChanged(
        argThat(statusOr -> statusOr.hasValue() && statusOr.getValue().equals(
            XdsListenerResource.LdsUpdate.forApiListener(MAIN_HTTP_CONNECTION_MANAGER))));

    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(), RDS_NAME, rdsWatcher);
    verify(rdsWatcher, timeout(5000)).onResourceChanged(argThat(StatusOr::hasValue));

    mainXdsServer.getServer().shutdownNow();
    fakeClock.forwardTime(5, TimeUnit.SECONDS); // Let the client detect the disconnection

    // The stream is down, so we should get an ambient error. No fallback yet.
    verify(ldsWatcher, timeout(5000).atLeastOnce()).onAmbientError(any());
    verify(ldsWatcher, never()).onResourceChanged(
        argThat(statusOr -> statusOr.hasValue() && statusOr.getValue().equals(
            XdsListenerResource.LdsUpdate.forApiListener(FALLBACK_HTTP_CONNECTION_MANAGER))));

    // Watching a cached resource should still work and not trigger a fallback.
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher2);
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(), RDS_NAME, rdsWatcher2);

    verify(ldsWatcher2, timeout(5000)).onResourceChanged(
        argThat(statusOr -> statusOr.hasValue() && statusOr.getValue().equals(
            XdsListenerResource.LdsUpdate.forApiListener(MAIN_HTTP_CONNECTION_MANAGER))));
    verify(rdsWatcher2, timeout(5000)).onResourceChanged(argThat(StatusOr::hasValue));

    // No new updates should have been received by the original watchers.
    verify(ldsWatcher, times(1)).onResourceChanged(any());
    verify(rdsWatcher, times(1)).onResourceChanged(any());

    // Now, ask for a new resource. This SHOULD trigger a fallback.
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(), FALLBACK_CLUSTER_NAME, cdsWatcher);

    // The existing watchers should get updates from the fallback server.
    verify(ldsWatcher, timeout(5000)).onResourceChanged(
        argThat(statusOr -> statusOr.hasValue() && statusOr.getValue().equals(
            XdsListenerResource.LdsUpdate.forApiListener(FALLBACK_HTTP_CONNECTION_MANAGER))));
    verify(ldsWatcher2, timeout(5000)).onResourceChanged(
        argThat(statusOr -> statusOr.hasValue() && statusOr.getValue().equals(
            XdsListenerResource.LdsUpdate.forApiListener(FALLBACK_HTTP_CONNECTION_MANAGER))));

    // And the new watcher should get its resource.
    verify(cdsWatcher, timeout(5000)).onResourceChanged(argThat(StatusOr::hasValue));

    xdsClient.watchXdsResource(
        XdsRouteConfigureResource.getInstance(), FALLBACK_RDS_NAME, rdsWatcher3);

    verify(rdsWatcher3, timeout(5000)).onResourceChanged(argThat(StatusOr::hasValue));

    // Test for a resource that exists on the main server but not the fallback.
    xdsClient.watchXdsResource(
        XdsClusterResource.getInstance(), CLUSTER_NAME, cdsWatcher2);
    // Initially, no error should be reported.
    verify(cdsWatcher2, never()).onResourceChanged(argThat(statusOr -> !statusOr.hasValue()));

    fakeClock.forwardTime(16, TimeUnit.SECONDS); // Let the resource timeout expire.
    @SuppressWarnings("unchecked")
    ArgumentCaptor<StatusOr<CdsUpdate>> captor = ArgumentCaptor.forClass(StatusOr.class);
    verify(cdsWatcher2, timeout(5000)).onResourceChanged(captor.capture());
    StatusOr<CdsUpdate> statusOr = captor.getValue();
    assertThat(statusOr.hasValue()).isFalse();
    assertThat(statusOr.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);

    xdsClient.shutdown();
  }

  @Test
  public void connect_then_mainServerRestart_fallbackServerdown() {
    mainXdsServer.restartXdsServer();
    xdsClient = xdsClientPool.getObject();

    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher);

    verify(ldsWatcher, timeout(5000)).onResourceChanged(
        argThat(statusOr -> statusOr.hasValue() && statusOr.getValue().equals(
            LdsUpdate.forApiListener(MAIN_HTTP_CONNECTION_MANAGER))));

    mainXdsServer.getServer().shutdownNow();
    fallbackServer.getServer().shutdownNow();

    xdsClient.watchXdsResource(XdsClusterResource.getInstance(), CLUSTER_NAME, cdsWatcher);

    mainXdsServer.restartXdsServer();

    verify(cdsWatcher, timeout(5000)).onResourceChanged(
        argThat(statusOr -> statusOr.hasValue()));
    verify(ldsWatcher, timeout(5000).atLeastOnce()).onResourceChanged(
        argThat(statusOr -> statusOr.hasValue() && statusOr.getValue().equals(
            LdsUpdate.forApiListener(MAIN_HTTP_CONNECTION_MANAGER))));
  }

  @Test
  public void fallbackFromBadUrlToGoodOne() {
    // Setup xdsClient to fail on stream creation
    String garbageUri = "some. garbage";

    String validUri = "localhost:" + mainXdsServer.getServer().getPort();
    XdsClientImpl client =
        CommonBootstrapperTestUtils.createXdsClient(
            Arrays.asList(garbageUri, validUri),
            new GrpcXdsTransportFactory(null),
            fakeClock,
            new ExponentialBackoffPolicy.Provider(),
            MessagePrinter.INSTANCE,
            xdsClientMetricReporter);

    client.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher);
    fakeClock.forwardTime(20, TimeUnit.SECONDS);
    verify(ldsWatcher, timeout(5000)).onResourceChanged(
        StatusOr.fromValue(XdsListenerResource.LdsUpdate.forApiListener(
            MAIN_HTTP_CONNECTION_MANAGER)));
    verify(ldsWatcher, never()).onAmbientError(any(Status.class));

    client.shutdown();
  }

  @Test
  public void testGoodUrlFollowedByBadUrl() {
    // xdsClient should succeed in stream creation as it doesn't need to use the bad url
    String garbageUri = "some. garbage";
    String validUri = "localhost:" + mainXdsServer.getServer().getPort();

    XdsClientImpl client =
        CommonBootstrapperTestUtils.createXdsClient(
            Arrays.asList(validUri, garbageUri),
            new GrpcXdsTransportFactory(null),
            fakeClock,
            new ExponentialBackoffPolicy.Provider(),
            MessagePrinter.INSTANCE,
            xdsClientMetricReporter);

    client.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher);
    verify(ldsWatcher, timeout(5000)).onResourceChanged(ldsUpdateCaptor.capture());
    StatusOr<LdsUpdate> statusOr = ldsUpdateCaptor.getValue();
    assertThat(statusOr.hasValue()).isTrue();
    assertThat(statusOr.getValue()).isEqualTo(
        XdsListenerResource.LdsUpdate.forApiListener(MAIN_HTTP_CONNECTION_MANAGER));
    verify(ldsWatcher, never()).onAmbientError(any());
    verify(ldsWatcher, times(1)).onResourceChanged(any());

    client.shutdown();
  }

  @Test
  public void testTwoBadUrl()  {
    // Setup xdsClient to fail on stream creation
    String garbageUri1 = "some. garbage";
    String garbageUri2 = "other garbage";

    XdsClientImpl client =
        CommonBootstrapperTestUtils.createXdsClient(
            Arrays.asList(garbageUri1, garbageUri2),
            new GrpcXdsTransportFactory(null),
            fakeClock,
            new ExponentialBackoffPolicy.Provider(),
            MessagePrinter.INSTANCE,
            xdsClientMetricReporter);

    client.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher);
    fakeClock.forwardTime(20, TimeUnit.SECONDS);
    verify(ldsWatcher, Mockito.timeout(5000).atLeastOnce())
        .onResourceChanged(ldsUpdateCaptor.capture());
    StatusOr<LdsUpdate> statusOr = ldsUpdateCaptor.getValue();
    assertThat(statusOr.hasValue()).isFalse();
    assertThat(statusOr.getStatus().getDescription()).contains(garbageUri2);
    verify(ldsWatcher, never()).onResourceChanged(argThat(StatusOr::hasValue));
    client.shutdown();
  }

  private Bootstrapper.ServerInfo getLrsServerInfo(String target) {
    for (Map.Entry<Bootstrapper.ServerInfo, LoadReportClient> entry
        : xdsClient.getServerLrsClientMap().entrySet()) {
      if (entry.getKey().target().equals(target)) {
        return entry.getKey();
      }
    }
    return null;
  }

  @Test
  public void used_then_mainServerRestart_fallbackServerUp() {
    xdsClient = xdsClientPool.getObject();

    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), MAIN_SERVER, ldsWatcher);

    verify(ldsWatcher, timeout(5000)).onResourceChanged(
        StatusOr.fromValue(LdsUpdate.forApiListener(MAIN_HTTP_CONNECTION_MANAGER)));

    mainXdsServer.restartXdsServer();

    assertThat(getLrsServerInfo("localhost:" + fallbackServer.getServer().getPort())).isNull();
    assertThat(getLrsServerInfo("localhost:" + mainXdsServer.getServer().getPort())).isNotNull();

    xdsClient.watchXdsResource(XdsClusterResource.getInstance(), CLUSTER_NAME, cdsWatcher);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<StatusOr<CdsUpdate>> cdsUpdateCaptor = ArgumentCaptor.forClass(StatusOr.class);
    verify(cdsWatcher, timeout(5000)).onResourceChanged(cdsUpdateCaptor.capture());
    // okshiva: flaky
    // assertThat(cdsUpdateCaptor.getValue().getStatus().isOk()).isTrue();
    // okshiva: I'm skeptical about this behaviour(commented in next line)
    // assertThat(getLrsServerInfo("localhost:" + fallbackServer.getServer().getPort())).isNull();
  }

  private Map<String, ?> defaultBootstrapOverride() {
    return ImmutableMap.of(
        "node", ImmutableMap.of(
            "id", UUID.randomUUID().toString(),
            "cluster", CLUSTER_NAME),
         "xds_servers", ImmutableList.of(
            ImmutableMap.of(
                "server_uri", "localhost:" + mainXdsServer.getServer().getPort(),
                "channel_creds", Collections.singletonList(
                    ImmutableMap.of("type", "insecure")
                ),
                "server_features", Collections.singletonList("xds_v3")
            ),
            ImmutableMap.of(
                "server_uri", "localhost:" + fallbackServer.getServer().getPort(),
                "channel_creds", Collections.singletonList(
                    ImmutableMap.of("type", "insecure")
                ),
                "server_features", Collections.singletonList("xds_v3")
            )
        ),
        "fallback-policy", "fallback"
      );
  }

}
