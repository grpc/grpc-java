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
import static io.grpc.xds.client.CommonBootstrapperTestUtils.LDS_RESOURCE;
import static io.grpc.xds.client.CommonBootstrapperTestUtils.SERVER_URI;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.grpc.BindableService;
import io.grpc.ChannelCredentials;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
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
import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
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
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link XdsNameResolverProvider}. */
@RunWith(JUnit4.class)
public class XdsDependencyManagerTest {
  private static final Logger log = Logger.getLogger(XdsDependencyManagerTest.class.getName());
  private static final ChannelCredentials CHANNEL_CREDENTIALS = InsecureChannelCredentials.create();

  //  private ControlPlaneRule xdsServerRule=new ControlPlaneRule().setServerHostName("xds-server");

  @Mock
  private XdsClientMetricReporter xdsClientMetricReporter;

  @Captor
  private ArgumentCaptor<Status> errorCaptor;

  private final SynchronizationContext syncContext =
      new SynchronizationContext(mock(Thread.UncaughtExceptionHandler.class));

  private ManagedChannel channel;
  private XdsClientImpl xdsClient;
  private XdsDependencyManager xdsDependencyManager;
  private TestWatcher xdsConfigWatcher;
  private Server xdsServer;

  private final FakeClock fakeClock = new FakeClock();
  private final BlockingDeque<XdsTestUtils.DiscoveryRpcCall> resourceDiscoveryCalls =
      new LinkedBlockingDeque<>(1);
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
        xdsClient, xdsConfigWatcher, syncContext, null, LDS_RESOURCE);

    verify(xdsConfigWatcher, timeout(1000)).onUpdate(getDefaultXdsConfig());

    xdsDependencyManager.shutdown();
  }

  private static XdsConfig getDefaultXdsConfig() {
    return null; // TODO replace with actual config
  }

  private static class TestWatcher implements XdsDependencyManager.XdsConfigWatcher {
    XdsConfig lastConfig;
    int numUpdates = 0;
    int numError = 0;
    int numDoesNotExist = 0;

    @Override
    public void onUpdate(XdsConfig config) {
      log.info("Config changed: " + config);
      lastConfig = config;
      numUpdates++;
    }

    @Override
    public void onError(String resourceContext, Status status) {
      log.info(String.format("Error %s for %s: ",  status, resourceContext));
      numError++;
    }

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      log.info("Resource does not exist: " + resourceName);
      numDoesNotExist++;
    }
  }
}
