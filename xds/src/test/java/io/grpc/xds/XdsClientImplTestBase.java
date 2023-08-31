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
import static com.google.common.truth.Truth.assertWithMessage;
import static io.grpc.xds.XdsClientImpl.XdsChannelFactory.DEFAULT_XDS_CHANNEL_FACTORY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.util.Durations;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.config.cluster.v3.OutlierDetection;
import io.envoyproxy.envoy.config.route.v3.FilterConfig;
import io.envoyproxy.envoy.config.route.v3.WeightedCluster;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateProviderPluginInstance;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.load_stats.v2.LoadReportingServiceGrpc;
import io.grpc.BindableService;
import io.grpc.ChannelCredentials;
import io.grpc.Context;
import io.grpc.Context.CancellableContext;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.internal.FakeClock.ScheduledTask;
import io.grpc.internal.FakeClock.TaskFilter;
import io.grpc.internal.JsonUtil;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.internal.TimeProvider;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.Bootstrapper.AuthorityInfo;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.Bootstrapper.CertificateProviderInfo;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.Endpoints.DropOverload;
import io.grpc.xds.Endpoints.LbEndpoint;
import io.grpc.xds.Endpoints.LocalityLbEndpoints;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.EnvoyServerProtoData.FailurePercentageEjection;
import io.grpc.xds.EnvoyServerProtoData.FilterChain;
import io.grpc.xds.EnvoyServerProtoData.SuccessRateEjection;
import io.grpc.xds.FaultConfig.FractionalPercent.DenominatorType;
import io.grpc.xds.LoadStatsManager2.ClusterDropStats;
import io.grpc.xds.XdsClient.ResourceMetadata;
import io.grpc.xds.XdsClient.ResourceMetadata.ResourceMetadataStatus;
import io.grpc.xds.XdsClient.ResourceMetadata.UpdateFailureState;
import io.grpc.xds.XdsClient.ResourceUpdate;
import io.grpc.xds.XdsClient.ResourceWatcher;
import io.grpc.xds.XdsClientImpl.ResourceInvalidException;
import io.grpc.xds.XdsClientImpl.XdsChannelFactory;
import io.grpc.xds.XdsClusterResource.CdsUpdate;
import io.grpc.xds.XdsClusterResource.CdsUpdate.ClusterType;
import io.grpc.xds.XdsEndpointResource.EdsUpdate;
import io.grpc.xds.XdsListenerResource.LdsUpdate;
import io.grpc.xds.XdsRouteConfigureResource.RdsUpdate;
import io.grpc.xds.internal.security.CommonTlsContextTestsUtil;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Assume;
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

/**
 * Tests for {@link XdsClientImpl}.
 */
@RunWith(JUnit4.class)
// The base class was used to test both xds v2 and v3. V2 is dropped now so the base class is not
// necessary. Still keep it for future version usage. Remove if too much trouble to maintain.
public abstract class XdsClientImplTestBase {
  private static final String SERVER_URI = "trafficdirector.googleapis.com";
  private static final String SERVER_URI_CUSTOME_AUTHORITY = "trafficdirector2.googleapis.com";
  private static final String SERVER_URI_EMPTY_AUTHORITY = "trafficdirector3.googleapis.com";
  private static final String LDS_RESOURCE = "listener.googleapis.com";
  private static final String RDS_RESOURCE = "route-configuration.googleapis.com";
  private static final String CDS_RESOURCE = "cluster.googleapis.com";
  private static final String EDS_RESOURCE = "cluster-load-assignment.googleapis.com";
  private static final String LISTENER_RESOURCE =
      "grpc/server?xds.resource.listening_address=0.0.0.0:7000";
  private static final String VERSION_1 = "42";
  private static final String VERSION_2 = "43";
  private static final String VERSION_3 = "44";
  private static final String NODE_ID = "cool-node-id";
  private static final Node NODE = Node.newBuilder().setId(NODE_ID).build();
  private static final Any FAILING_ANY = MessageFactory.FAILING_ANY;
  private static final ChannelCredentials CHANNEL_CREDENTIALS = InsecureChannelCredentials.create();
  private static final XdsResourceType<?> LDS = XdsListenerResource.getInstance();
  private static final XdsResourceType<?> CDS = XdsClusterResource.getInstance();
  private static final XdsResourceType<?> RDS = XdsRouteConfigureResource.getInstance();
  private static final XdsResourceType<?> EDS = XdsEndpointResource.getInstance();

  // xDS control plane server info.
  private ServerInfo xdsServerInfo;

  private static final FakeClock.TaskFilter RPC_RETRY_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(ControlPlaneClient.RpcRetryTask.class.getSimpleName());
        }
      };

  private static final FakeClock.TaskFilter LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(LDS.toString());
        }
      };

  private static final FakeClock.TaskFilter RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(RDS.toString());
        }
      };

  private static final FakeClock.TaskFilter CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(CDS.toString());
        }
      };

  private static final FakeClock.TaskFilter EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(EDS.toString());
        }
      };

  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  private final FakeClock fakeClock = new FakeClock();
  protected final BlockingDeque<DiscoveryRpcCall> resourceDiscoveryCalls =
      new LinkedBlockingDeque<>(1);
  protected final BlockingDeque<DiscoveryRpcCall> resourceDiscoveryCallsV2 =
      new LinkedBlockingDeque<>(1);
  protected final Queue<LrsRpcCall> loadReportCalls = new ArrayDeque<>();
  protected final Queue<LrsRpcCall> loadReportCallsV2 = new ArrayDeque<>();
  protected final AtomicBoolean adsEnded = new AtomicBoolean(true);
  protected final AtomicBoolean lrsEnded = new AtomicBoolean(true);
  private final MessageFactory mf = createMessageFactory();

  private static final long TIME_INCREMENT = TimeUnit.SECONDS.toNanos(1);
  /** Fake time provider increments time TIME_INCREMENT each call. */
  private final TimeProvider timeProvider = new TimeProvider() {
    private long count;
    @Override
    public long currentTimeNanos() {
      return ++count * TIME_INCREMENT;
    }
  };

  private static final int VHOST_SIZE = 2;
  // LDS test resources.
  private final Any testListenerVhosts = Any.pack(mf.buildListenerWithApiListener(LDS_RESOURCE,
      mf.buildRouteConfiguration("do not care", mf.buildOpaqueVirtualHosts(VHOST_SIZE))));
  private final Any testListenerRds =
      Any.pack(mf.buildListenerWithApiListenerForRds(LDS_RESOURCE, RDS_RESOURCE));

  // RDS test resources.
  private final Any testRouteConfig =
      Any.pack(mf.buildRouteConfiguration(RDS_RESOURCE, mf.buildOpaqueVirtualHosts(VHOST_SIZE)));

  // CDS test resources.
  private final Any testClusterRoundRobin =
      Any.pack(mf.buildEdsCluster(CDS_RESOURCE, null, "round_robin", null,
          null, false, null, "envoy.transport_sockets.tls", null, null
      ));

  // EDS test resources.
  private final Message lbEndpointHealthy =
      mf.buildLocalityLbEndpoints("region1", "zone1", "subzone1",
          mf.buildLbEndpoint("192.168.0.1", 8080, "healthy", 2), 1, 0);
  // Locality with 0 endpoints
  private final Message lbEndpointEmpty =
      mf.buildLocalityLbEndpoints("region3", "zone3", "subzone3",
          ImmutableList.<Message>of(), 2, 1);
  // Locality with 0-weight endpoint
  private final Message lbEndpointZeroWeight =
      mf.buildLocalityLbEndpoints("region4", "zone4", "subzone4",
          mf.buildLbEndpoint("192.168.142.5", 80, "unknown", 5), 0, 2);
  private final Any testClusterLoadAssignment = Any.pack(mf.buildClusterLoadAssignment(EDS_RESOURCE,
      ImmutableList.of(lbEndpointHealthy, lbEndpointEmpty, lbEndpointZeroWeight),
      ImmutableList.of(mf.buildDropOverload("lb", 200), mf.buildDropOverload("throttle", 1000))));

  @Captor
  private ArgumentCaptor<LdsUpdate> ldsUpdateCaptor;
  @Captor
  private ArgumentCaptor<RdsUpdate> rdsUpdateCaptor;
  @Captor
  private ArgumentCaptor<CdsUpdate> cdsUpdateCaptor;
  @Captor
  private ArgumentCaptor<EdsUpdate> edsUpdateCaptor;
  @Captor
  private ArgumentCaptor<Status> errorCaptor;

  @Mock
  private BackoffPolicy.Provider backoffPolicyProvider;
  @Mock
  private BackoffPolicy backoffPolicy1;
  @Mock
  private BackoffPolicy backoffPolicy2;
  @Mock
  private ResourceWatcher<LdsUpdate> ldsResourceWatcher;
  @Mock
  private ResourceWatcher<RdsUpdate> rdsResourceWatcher;
  @Mock
  private ResourceWatcher<CdsUpdate> cdsResourceWatcher;
  @Mock
  private ResourceWatcher<EdsUpdate> edsResourceWatcher;
  @Mock
  private TlsContextManager tlsContextManager;

  private ManagedChannel channel;
  private ManagedChannel channelForCustomAuthority;
  private ManagedChannel channelForEmptyAuthority;
  private XdsClientImpl xdsClient;
  private boolean originalEnableLeastRequest;
  private boolean originalEnableFederation;
  private Server xdsServer;
  private final String serverName = InProcessServerBuilder.generateName();
  private BindableService adsService = createAdsService();
  private BindableService lrsService = createLrsService();

  @Before
  public void setUp() throws IOException {
    when(backoffPolicyProvider.get()).thenReturn(backoffPolicy1, backoffPolicy2);
    when(backoffPolicy1.nextBackoffNanos()).thenReturn(10L, 100L);
    when(backoffPolicy2.nextBackoffNanos()).thenReturn(20L, 200L);

    // Start the server and the client.
    originalEnableLeastRequest = XdsResourceType.enableLeastRequest;
    XdsResourceType.enableLeastRequest = true;
    originalEnableFederation = BootstrapperImpl.enableFederation;
    xdsServer = cleanupRule.register(InProcessServerBuilder
        .forName(serverName)
        .addService(adsService)
        .addService(createAdsServiceV2())
        .addService(lrsService)
        .addService(createLrsServiceV2())
        .directExecutor()
        .build()
        .start());
    channel =
        cleanupRule.register(InProcessChannelBuilder.forName(serverName).directExecutor().build());
    XdsChannelFactory xdsChannelFactory = new XdsChannelFactory() {
      @Override
      ManagedChannel create(ServerInfo serverInfo) {
        if (serverInfo.target().equals(SERVER_URI)) {
          return channel;
        }
        if (serverInfo.target().equals(SERVER_URI_CUSTOME_AUTHORITY)) {
          if (channelForCustomAuthority == null) {
            channelForCustomAuthority = cleanupRule.register(
                InProcessChannelBuilder.forName(serverName).directExecutor().build());
          }
          return channelForCustomAuthority;
        }
        if (serverInfo.target().equals(SERVER_URI_EMPTY_AUTHORITY)) {
          if (channelForEmptyAuthority == null) {
            channelForEmptyAuthority = cleanupRule.register(
                InProcessChannelBuilder.forName(serverName).directExecutor().build());
          }
          return channelForEmptyAuthority;
        }
        throw new IllegalArgumentException("Can not create channel for " + serverInfo);
      }
    };

    xdsServerInfo = ServerInfo.create(SERVER_URI, CHANNEL_CREDENTIALS,
        ignoreResourceDeletion());
    Bootstrapper.BootstrapInfo bootstrapInfo =
        Bootstrapper.BootstrapInfo.builder()
            .servers(Collections.singletonList(xdsServerInfo))
            .node(NODE)
            .authorities(ImmutableMap.of(
                "authority.xds.com",
                AuthorityInfo.create(
                    "xdstp://authority.xds.com/envoy.config.listener.v3.Listener/%s",
                    ImmutableList.of(Bootstrapper.ServerInfo.create(
                        SERVER_URI_CUSTOME_AUTHORITY, CHANNEL_CREDENTIALS))),
                "",
                AuthorityInfo.create(
                    "xdstp:///envoy.config.listener.v3.Listener/%s",
                    ImmutableList.of(Bootstrapper.ServerInfo.create(
                        SERVER_URI_EMPTY_AUTHORITY, CHANNEL_CREDENTIALS)))))
            .certProviders(ImmutableMap.of("cert-instance-name",
                CertificateProviderInfo.create("file-watcher", ImmutableMap.<String, Object>of())))
            .build();
    xdsClient =
        new XdsClientImpl(
            xdsChannelFactory,
            bootstrapInfo,
            Context.ROOT,
            fakeClock.getScheduledExecutorService(),
            backoffPolicyProvider,
            fakeClock.getStopwatchSupplier(),
            timeProvider,
            tlsContextManager);

    assertThat(resourceDiscoveryCalls).isEmpty();
    assertThat(loadReportCalls).isEmpty();
  }

  @After
  public void tearDown() {
    XdsResourceType.enableLeastRequest = originalEnableLeastRequest;
    BootstrapperImpl.enableFederation = originalEnableFederation;
    xdsClient.shutdown();
    channel.shutdown();  // channel not owned by XdsClient
    assertThat(adsEnded.get()).isTrue();
    assertThat(lrsEnded.get()).isTrue();
    assertThat(fakeClock.getPendingTasks()).isEmpty();
  }

  protected abstract boolean useProtocolV3();

  /** Whether ignore_resource_deletion server feature is enabled for the given test. */
  protected abstract boolean ignoreResourceDeletion();

  protected abstract BindableService createAdsService();

  protected abstract BindableService createLrsService();

  protected abstract MessageFactory createMessageFactory();

  protected static boolean matchErrorDetail(
      com.google.rpc.Status errorDetail, int expectedCode, List<String> expectedMessages) {
    if (expectedCode != errorDetail.getCode()) {
      return false;
    }
    List<String> errors = Splitter.on('\n').splitToList(errorDetail.getMessage());
    if (errors.size() != expectedMessages.size()) {
      return false;
    }
    for (int i = 0; i < errors.size(); i++) {
      if (!errors.get(i).startsWith(expectedMessages.get(i))) {
        return false;
      }
    }
    return true;
  }

  private void verifySubscribedResourcesMetadataSizes(
      int ldsSize, int cdsSize, int rdsSize, int edsSize) {
    Map<XdsResourceType<?>, Map<String, ResourceMetadata>> subscribedResourcesMetadata =
        awaitSubscribedResourcesMetadata();
    Map<String, XdsResourceType<?>> subscribedTypeUrls =
        xdsClient.getSubscribedResourceTypesWithTypeUrl();
    verifyResourceCount(subscribedTypeUrls, subscribedResourcesMetadata, LDS, ldsSize);
    verifyResourceCount(subscribedTypeUrls, subscribedResourcesMetadata, CDS, cdsSize);
    verifyResourceCount(subscribedTypeUrls, subscribedResourcesMetadata, RDS, rdsSize);
    verifyResourceCount(subscribedTypeUrls, subscribedResourcesMetadata, EDS, edsSize);
  }

  private void verifyResourceCount(
      Map<String, XdsResourceType<?>> subscribedTypeUrls,
      Map<XdsResourceType<?>, Map<String, ResourceMetadata>> subscribedResourcesMetadata,
      XdsResourceType<?> type,
      int size) {
    if (size == 0) {
      assertThat(subscribedTypeUrls.containsKey(type.typeUrl())).isFalse();
      assertThat(subscribedResourcesMetadata.containsKey(type)).isFalse();
    } else {
      assertThat(subscribedTypeUrls.containsKey(type.typeUrl())).isTrue();
      assertThat(subscribedResourcesMetadata.get(type)).hasSize(size);
    }
  }

  private Map<XdsResourceType<?>, Map<String, ResourceMetadata>>
      awaitSubscribedResourcesMetadata() {
    try {
      return xdsClient.getSubscribedResourcesMetadataSnapshot().get(20, TimeUnit.SECONDS);
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new AssertionError(e);
    }
  }

  /** Verify the resource requested, but not updated. */
  private void verifyResourceMetadataRequested(XdsResourceType<?> type, String resourceName) {
    verifyResourceMetadata(
        type, resourceName, null, ResourceMetadataStatus.REQUESTED, "", 0, false);
  }

  /** Verify that the requested resource does not exist. */
  private void verifyResourceMetadataDoesNotExist(XdsResourceType<?> type, String resourceName) {
    verifyResourceMetadata(
        type, resourceName, null, ResourceMetadataStatus.DOES_NOT_EXIST, "", 0, false);
  }

  /** Verify the resource to be acked. */
  private void verifyResourceMetadataAcked(
      XdsResourceType<?> type, String resourceName, Any rawResource, String versionInfo,
      long updateTimeNanos) {
    verifyResourceMetadata(type, resourceName, rawResource, ResourceMetadataStatus.ACKED,
        versionInfo, updateTimeNanos, false);
  }

  /**
   * Verify the resource to be nacked, and every i-th line of error details to begin with
   * corresponding i-th element of {@code List<String> failedDetails}.
   */
  private void verifyResourceMetadataNacked(
      XdsResourceType<?> type, String resourceName, Any rawResource, String versionInfo,
      long updateTime, String failedVersion, long failedUpdateTimeNanos,
      List<String> failedDetails) {
    ResourceMetadata resourceMetadata =
        verifyResourceMetadata(type, resourceName, rawResource, ResourceMetadataStatus.NACKED,
            versionInfo, updateTime, true);

    UpdateFailureState errorState = resourceMetadata.getErrorState();
    assertThat(errorState).isNotNull();
    String name = type.toString() + " resource '" + resourceName + "' metadata error ";
    assertWithMessage(name + "failedVersion").that(errorState.getFailedVersion())
        .isEqualTo(failedVersion);
    assertWithMessage(name + "failedUpdateTimeNanos").that(errorState.getFailedUpdateTimeNanos())
        .isEqualTo(failedUpdateTimeNanos);
    List<String> errors = Splitter.on('\n').splitToList(errorState.getFailedDetails());
    for (int i = 0; i < errors.size(); i++) {
      assertWithMessage(name + "failedDetails line " + i).that(errors.get(i))
          .startsWith(failedDetails.get(i));
    }
  }

  private ResourceMetadata verifyResourceMetadata(
      XdsResourceType<?> type, String resourceName, Any rawResource, ResourceMetadataStatus status,
      String versionInfo, long updateTimeNanos, boolean hasErrorState) {
    ResourceMetadata metadata = awaitSubscribedResourcesMetadata().get(type).get(resourceName);
    assertThat(metadata).isNotNull();
    String name = type.toString() + " resource '" + resourceName + "' metadata field ";
    assertWithMessage(name + "status").that(metadata.getStatus()).isEqualTo(status);
    assertWithMessage(name + "version").that(metadata.getVersion()).isEqualTo(versionInfo);
    assertWithMessage(name + "rawResource").that(metadata.getRawResource()).isEqualTo(rawResource);
    assertWithMessage(name + "updateTimeNanos").that(metadata.getUpdateTimeNanos())
        .isEqualTo(updateTimeNanos);
    if (hasErrorState) {
      assertWithMessage(name + "errorState").that(metadata.getErrorState()).isNotNull();
    } else {
      assertWithMessage(name + "errorState").that(metadata.getErrorState()).isNull();
    }
    return metadata;
  }

  private void verifyStatusWithNodeId(Status status, Code expectedCode, String expectedMsg) {
    assertThat(status.getCode()).isEqualTo(expectedCode);
    assertThat(status.getCause()).isNull();
    // Watcher.onError propagates status description to the channel, and we want to
    // augment the description with the node id.
    String description = (expectedMsg.isEmpty() ? "" : expectedMsg + " ") + "nodeID: " + NODE_ID;
    assertThat(status.getDescription()).isEqualTo(description);
  }

  /**
   * Verifies the LDS update against the golden Listener with vhosts {@link #testListenerVhosts}.
   */
  private void verifyGoldenListenerVhosts(LdsUpdate ldsUpdate) {
    assertThat(ldsUpdate.listener()).isNull();
    HttpConnectionManager hcm = ldsUpdate.httpConnectionManager();
    assertThat(hcm.rdsName()).isNull();
    assertThat(hcm.virtualHosts()).hasSize(VHOST_SIZE);
    verifyGoldenHcm(hcm);
  }

  /**
   * Verifies the LDS update against the golden Listener with RDS name {@link #testListenerRds}.
   */
  private void verifyGoldenListenerRds(LdsUpdate ldsUpdate) {
    assertThat(ldsUpdate.listener()).isNull();
    HttpConnectionManager hcm = ldsUpdate.httpConnectionManager();
    assertThat(hcm.rdsName()).isEqualTo(RDS_RESOURCE);
    assertThat(hcm.virtualHosts()).isNull();
    verifyGoldenHcm(hcm);
  }

  private void verifyGoldenHcm(HttpConnectionManager hcm) {
    if (useProtocolV3()) {
      // The last configured filter has to be a terminal filter.
      assertThat(hcm.httpFilterConfigs()).isNotNull();
      assertThat(hcm.httpFilterConfigs()).hasSize(1);
      assertThat(hcm.httpFilterConfigs().get(0).name).isEqualTo("terminal");
      assertThat(hcm.httpFilterConfigs().get(0).filterConfig).isEqualTo(RouterFilter.ROUTER_CONFIG);
    } else {
      assertThat(hcm.httpFilterConfigs()).isNull();
    }
  }

  /**
   * Verifies the RDS update against the golden route config {@link #testRouteConfig}.
   */
  private void verifyGoldenRouteConfig(RdsUpdate rdsUpdate) {
    assertThat(rdsUpdate.virtualHosts).hasSize(VHOST_SIZE);
    for (VirtualHost vhost : rdsUpdate.virtualHosts) {
      assertThat(vhost.name()).contains("do not care");
      assertThat(vhost.domains()).hasSize(1);
      assertThat(vhost.routes()).hasSize(1);
    }
  }

  /**
   * Verifies the CDS update against the golden Round Robin Cluster {@link #testClusterRoundRobin}.
   */
  private void verifyGoldenClusterRoundRobin(CdsUpdate cdsUpdate) {
    assertThat(cdsUpdate.clusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.clusterType()).isEqualTo(ClusterType.EDS);
    assertThat(cdsUpdate.edsServiceName()).isNull();
    LbConfig lbConfig = ServiceConfigUtil.unwrapLoadBalancingConfig(cdsUpdate.lbPolicyConfig());
    assertThat(lbConfig.getPolicyName()).isEqualTo("wrr_locality_experimental");
    List<LbConfig> childConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(
        JsonUtil.getListOfObjects(lbConfig.getRawConfigValue(), "childPolicy"));
    assertThat(childConfigs.get(0).getPolicyName()).isEqualTo("round_robin");
    assertThat(cdsUpdate.lrsServerInfo()).isNull();
    assertThat(cdsUpdate.maxConcurrentRequests()).isNull();
    assertThat(cdsUpdate.upstreamTlsContext()).isNull();
  }

  /**
   * Verifies the EDS update against the golden Cluster with load assignment
   * {@link #testClusterLoadAssignment}.
   */
  private void validateGoldenClusterLoadAssignment(EdsUpdate edsUpdate) {
    assertThat(edsUpdate.clusterName).isEqualTo(EDS_RESOURCE);
    assertThat(edsUpdate.dropPolicies)
        .containsExactly(
            DropOverload.create("lb", 200),
            DropOverload.create("throttle", 1000));
    assertThat(edsUpdate.localityLbEndpointsMap)
        .containsExactly(
            Locality.create("region1", "zone1", "subzone1"),
            LocalityLbEndpoints.create(
                ImmutableList.of(LbEndpoint.create("192.168.0.1", 8080, 2, true)), 1, 0),
            Locality.create("region3", "zone3", "subzone3"),
            LocalityLbEndpoints.create(ImmutableList.<LbEndpoint>of(), 2, 1));
  }

  @Test
  public void ldsResourceNotFound() {
    DiscoveryRpcCall call = startResourceWatcher(XdsListenerResource.getInstance(), LDS_RESOURCE,
        ldsResourceWatcher);

    Any listener = Any.pack(mf.buildListenerWithApiListener("bar.googleapis.com",
        mf.buildRouteConfiguration("route-bar.googleapis.com", mf.buildOpaqueVirtualHosts(1))));
    call.sendResponse(LDS, listener, VERSION_1, "0000");

    // Client sends an ACK LDS request.
    call.verifyRequest(LDS, LDS_RESOURCE, VERSION_1, "0000", NODE);
    verifyNoInteractions(ldsResourceWatcher);
    verifyResourceMetadataRequested(LDS, LDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(1, 0, 0, 0);
    // Server failed to return subscribed resource within expected time window.
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(ldsResourceWatcher).onResourceDoesNotExist(LDS_RESOURCE);
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
    verifyResourceMetadataDoesNotExist(LDS, LDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(1, 0, 0, 0);
  }

  @Test
  public void ldsResourceUpdated_withXdstpResourceName_withUnknownAuthority() {
    BootstrapperImpl.enableFederation = true;
    String ldsResourceName = useProtocolV3()
        ? "xdstp://unknown.example.com/envoy.config.listener.v3.Listener/listener1"
        : "xdstp://unknown.example.com/envoy.api.v2.Listener/listener1";
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),ldsResourceName,
        ldsResourceWatcher);
    verify(ldsResourceWatcher).onError(errorCaptor.capture());
    Status error = errorCaptor.getValue();
    assertThat(error.getCode()).isEqualTo(Code.INVALID_ARGUMENT);
    assertThat(error.getDescription()).isEqualTo(
        "Wrong configuration: xds server does not exist for resource " + ldsResourceName);
    assertThat(resourceDiscoveryCalls.poll()).isNull();
    xdsClient.cancelXdsResourceWatch(XdsListenerResource.getInstance(),ldsResourceName,
        ldsResourceWatcher);
    assertThat(resourceDiscoveryCalls.poll()).isNull();
  }

  @Test
  public void ldsResponseErrorHandling_allResourcesFailedUnpack() {
    DiscoveryRpcCall call = startResourceWatcher(XdsListenerResource.getInstance(), LDS_RESOURCE,
        ldsResourceWatcher);
    verifyResourceMetadataRequested(LDS, LDS_RESOURCE);
    call.sendResponse(LDS, ImmutableList.of(FAILING_ANY, FAILING_ANY), VERSION_1, "0000");

    // Resulting metadata unchanged because the response has no identifiable subscribed resources.
    verifyResourceMetadataRequested(LDS, LDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(1, 0, 0, 0);
    // The response NACKed with errors indicating indices of the failed resources.
    call.verifyRequestNack(LDS, LDS_RESOURCE, "", "0000", NODE, ImmutableList.of(
        "LDS response Resource index 0 - can't decode Listener: ",
        "LDS response Resource index 1 - can't decode Listener: "));
    verifyNoInteractions(ldsResourceWatcher);
  }

  @Test
  public void ldsResponseErrorHandling_someResourcesFailedUnpack() {
    DiscoveryRpcCall call = startResourceWatcher(XdsListenerResource.getInstance(), LDS_RESOURCE,
        ldsResourceWatcher);
    verifyResourceMetadataRequested(LDS, LDS_RESOURCE);

    // Correct resource is in the middle to ensure processing continues on errors.
    List<Any> resources = ImmutableList.of(FAILING_ANY, testListenerRds, FAILING_ANY);
    call.sendResponse(LDS, resources, VERSION_1, "0000");

    // All errors recorded in the metadata of successfully unpacked subscribed resources.
    List<String> errors = ImmutableList.of(
        "LDS response Resource index 0 - can't decode Listener: ",
        "LDS response Resource index 2 - can't decode Listener: ");
    verifyResourceMetadataAcked(LDS, LDS_RESOURCE, testListenerRds, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(1, 0, 0, 0);
    // The response is NACKed with the same error message.
    call.verifyRequestNack(LDS, LDS_RESOURCE, "", "0000", NODE, errors);
    verify(ldsResourceWatcher).onChanged(any(LdsUpdate.class));
  }

  /**
   * Tests a subscribed LDS resource transitioned to and from the invalid state.
   *
   * @see <a href="https://github.com/grpc/proposal/blob/master/A40-csds-support.md#ads-parsing-logic-update-continue-after-first-error">
   * A40-csds-support.md</a>
   */
  @Test
  public void ldsResponseErrorHandling_subscribedResourceInvalid() {
    List<String> subscribedResourceNames = ImmutableList.of("A", "B", "C");
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),"A", ldsResourceWatcher);
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),"B", ldsResourceWatcher);
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),"C", ldsResourceWatcher);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    assertThat(call).isNotNull();
    verifyResourceMetadataRequested(LDS, "A");
    verifyResourceMetadataRequested(LDS, "B");
    verifyResourceMetadataRequested(LDS, "C");
    verifySubscribedResourcesMetadataSizes(3, 0, 0, 0);

    // LDS -> {A, B, C}, version 1
    ImmutableMap<String, Any> resourcesV1 = ImmutableMap.of(
        "A", Any.pack(mf.buildListenerWithApiListenerForRds("A", "A.1")),
        "B", Any.pack(mf.buildListenerWithApiListenerForRds("B", "B.1")),
        "C", Any.pack(mf.buildListenerWithApiListenerForRds("C", "C.1")));
    call.sendResponse(LDS, resourcesV1.values().asList(), VERSION_1, "0000");
    // {A, B, C} -> ACK, version 1
    verifyResourceMetadataAcked(LDS, "A", resourcesV1.get("A"), VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataAcked(LDS, "B", resourcesV1.get("B"), VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataAcked(LDS, "C", resourcesV1.get("C"), VERSION_1, TIME_INCREMENT);
    call.verifyRequest(LDS, subscribedResourceNames, VERSION_1, "0000", NODE);

    // LDS -> {A, B}, version 2
    // Failed to parse endpoint B
    ImmutableMap<String, Any> resourcesV2 = ImmutableMap.of(
        "A", Any.pack(mf.buildListenerWithApiListenerForRds("A", "A.2")),
        "B", Any.pack(mf.buildListenerWithApiListenerInvalid("B")));
    call.sendResponse(LDS, resourcesV2.values().asList(), VERSION_2, "0001");
    // {A} -> ACK, version 2
    // {B} -> NACK, version 1, rejected version 2, rejected reason: Failed to parse B
    // {C} -> does not exist
    List<String> errorsV2 = ImmutableList.of("LDS response Listener 'B' validation error: ");
    verifyResourceMetadataAcked(LDS, "A", resourcesV2.get("A"), VERSION_2, TIME_INCREMENT * 2);
    verifyResourceMetadataNacked(LDS, "B", resourcesV1.get("B"), VERSION_1, TIME_INCREMENT,
        VERSION_2, TIME_INCREMENT * 2, errorsV2);
    if (!ignoreResourceDeletion()) {
      verifyResourceMetadataDoesNotExist(LDS, "C");
    } else {
      // When resource deletion is disabled, {C} stays ACKed in the previous version VERSION_1.
      verifyResourceMetadataAcked(LDS, "C", resourcesV1.get("C"), VERSION_1, TIME_INCREMENT);
    }
    call.verifyRequestNack(LDS, subscribedResourceNames, VERSION_1, "0001", NODE, errorsV2);

    // LDS -> {B, C} version 3
    ImmutableMap<String, Any> resourcesV3 = ImmutableMap.of(
        "B", Any.pack(mf.buildListenerWithApiListenerForRds("B", "B.3")),
        "C", Any.pack(mf.buildListenerWithApiListenerForRds("C", "C.3")));
    call.sendResponse(LDS, resourcesV3.values().asList(), VERSION_3, "0002");
    // {A} -> does not exist
    // {B, C} -> ACK, version 3
    if (!ignoreResourceDeletion()) {
      verifyResourceMetadataDoesNotExist(LDS, "A");
    } else {
      // When resource deletion is disabled, {A} stays ACKed in the previous version VERSION_2.
      verifyResourceMetadataAcked(LDS, "A", resourcesV2.get("A"), VERSION_2, TIME_INCREMENT * 2);
    }
    verifyResourceMetadataAcked(LDS, "B", resourcesV3.get("B"), VERSION_3, TIME_INCREMENT * 3);
    verifyResourceMetadataAcked(LDS, "C", resourcesV3.get("C"), VERSION_3, TIME_INCREMENT * 3);
    call.verifyRequest(LDS, subscribedResourceNames, VERSION_3, "0002", NODE);
    verifySubscribedResourcesMetadataSizes(3, 0, 0, 0);
  }

  @Test
  public void ldsResponseErrorHandling_subscribedResourceInvalid_withRdsSubscription() {
    List<String> subscribedResourceNames = ImmutableList.of("A", "B", "C");
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),"A", ldsResourceWatcher);
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(),"A.1", rdsResourceWatcher);
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),"B", ldsResourceWatcher);
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(),"B.1", rdsResourceWatcher);
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),"C", ldsResourceWatcher);
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(),"C.1", rdsResourceWatcher);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    assertThat(call).isNotNull();
    verifyResourceMetadataRequested(LDS, "A");
    verifyResourceMetadataRequested(LDS, "B");
    verifyResourceMetadataRequested(LDS, "C");
    verifyResourceMetadataRequested(RDS, "A.1");
    verifyResourceMetadataRequested(RDS, "B.1");
    verifyResourceMetadataRequested(RDS, "C.1");
    verifySubscribedResourcesMetadataSizes(3, 0, 3, 0);

    // LDS -> {A, B, C}, version 1
    ImmutableMap<String, Any> resourcesV1 = ImmutableMap.of(
        "A", Any.pack(mf.buildListenerWithApiListenerForRds("A", "A.1")),
        "B", Any.pack(mf.buildListenerWithApiListenerForRds("B", "B.1")),
        "C", Any.pack(mf.buildListenerWithApiListenerForRds("C", "C.1")));
    call.sendResponse(LDS, resourcesV1.values().asList(), VERSION_1, "0000");
    // {A, B, C} -> ACK, version 1
    verifyResourceMetadataAcked(LDS, "A", resourcesV1.get("A"), VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataAcked(LDS, "B", resourcesV1.get("B"), VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataAcked(LDS, "C", resourcesV1.get("C"), VERSION_1, TIME_INCREMENT);
    call.verifyRequest(LDS, subscribedResourceNames, VERSION_1, "0000", NODE);

    // RDS -> {A.1, B.1, C.1}, version 1
    List<Message> vhostsV1 = mf.buildOpaqueVirtualHosts(1);
    ImmutableMap<String, Any> resourcesV11 = ImmutableMap.of(
        "A.1", Any.pack(mf.buildRouteConfiguration("A.1", vhostsV1)),
        "B.1", Any.pack(mf.buildRouteConfiguration("B.1", vhostsV1)),
        "C.1", Any.pack(mf.buildRouteConfiguration("C.1", vhostsV1)));
    call.sendResponse(RDS, resourcesV11.values().asList(), VERSION_1, "0000");
    // {A.1, B.1, C.1} -> ACK, version 1
    verifyResourceMetadataAcked(RDS, "A.1", resourcesV11.get("A.1"), VERSION_1, TIME_INCREMENT * 2);
    verifyResourceMetadataAcked(RDS, "B.1", resourcesV11.get("B.1"), VERSION_1, TIME_INCREMENT * 2);
    verifyResourceMetadataAcked(RDS, "C.1", resourcesV11.get("C.1"), VERSION_1, TIME_INCREMENT * 2);

    // LDS -> {A, B}, version 2
    // Failed to parse endpoint B
    ImmutableMap<String, Any> resourcesV2 = ImmutableMap.of(
        "A", Any.pack(mf.buildListenerWithApiListenerForRds("A", "A.2")),
        "B", Any.pack(mf.buildListenerWithApiListenerInvalid("B")));
    call.sendResponse(LDS, resourcesV2.values().asList(), VERSION_2, "0001");
    // {A} -> ACK, version 2
    // {B} -> NACK, version 1, rejected version 2, rejected reason: Failed to parse B
    // {C} -> does not exist
    List<String> errorsV2 = ImmutableList.of("LDS response Listener 'B' validation error: ");
    verifyResourceMetadataAcked(LDS, "A", resourcesV2.get("A"), VERSION_2, TIME_INCREMENT * 3);
    verifyResourceMetadataNacked(
        LDS, "B", resourcesV1.get("B"), VERSION_1, TIME_INCREMENT, VERSION_2, TIME_INCREMENT * 3,
        errorsV2);
    if (!ignoreResourceDeletion()) {
      verifyResourceMetadataDoesNotExist(LDS, "C");
    } else {
      // When resource deletion is disabled, {C} stays ACKed in the previous version VERSION_1.
      verifyResourceMetadataAcked(LDS, "C", resourcesV1.get("C"), VERSION_1, TIME_INCREMENT);
    }
    call.verifyRequestNack(LDS, subscribedResourceNames, VERSION_1, "0001", NODE, errorsV2);
    // {A.1} -> version 1
    // {B.1} -> version 1
    // {C.1} -> does not exist because {C} does not exist
    verifyResourceMetadataAcked(RDS, "A.1", resourcesV11.get("A.1"), VERSION_1, TIME_INCREMENT * 2);
    verifyResourceMetadataAcked(RDS, "B.1", resourcesV11.get("B.1"), VERSION_1, TIME_INCREMENT * 2);
    // Verify {C.1} stays in the previous version VERSION_1, no matter {C} is deleted or not.
    verifyResourceMetadataAcked(RDS, "C.1", resourcesV11.get("C.1"), VERSION_1,
        TIME_INCREMENT * 2);
  }

  @Test
  public void ldsResourceFound_containsVirtualHosts() {
    DiscoveryRpcCall call = startResourceWatcher(XdsListenerResource.getInstance(), LDS_RESOURCE,
        ldsResourceWatcher);

    // Client sends an ACK LDS request.
    call.sendResponse(LDS, testListenerVhosts, VERSION_1, "0000");
    call.verifyRequest(LDS, LDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    verifyGoldenListenerVhosts(ldsUpdateCaptor.getValue());
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
    verifyResourceMetadataAcked(LDS, LDS_RESOURCE, testListenerVhosts, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(1, 0, 0, 0);
  }

  @Test
  public void wrappedLdsResource() {
    DiscoveryRpcCall call = startResourceWatcher(XdsListenerResource.getInstance(), LDS_RESOURCE,
        ldsResourceWatcher);

    // Client sends an ACK LDS request.
    call.sendResponse(LDS, mf.buildWrappedResource(testListenerVhosts), VERSION_1, "0000");
    call.verifyRequest(LDS, LDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    verifyGoldenListenerVhosts(ldsUpdateCaptor.getValue());
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
    verifyResourceMetadataAcked(LDS, LDS_RESOURCE, testListenerVhosts, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(1, 0, 0, 0);
  }

  @Test
  public void ldsResourceFound_containsRdsName() {
    DiscoveryRpcCall call = startResourceWatcher(XdsListenerResource.getInstance(), LDS_RESOURCE,
        ldsResourceWatcher);
    call.sendResponse(LDS, testListenerRds, VERSION_1, "0000");

    // Client sends an ACK LDS request.
    call.verifyRequest(LDS, LDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    verifyGoldenListenerRds(ldsUpdateCaptor.getValue());
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
    verifyResourceMetadataAcked(LDS, LDS_RESOURCE, testListenerRds, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(1, 0, 0, 0);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void cachedLdsResource_data() {
    DiscoveryRpcCall call = startResourceWatcher(XdsListenerResource.getInstance(), LDS_RESOURCE,
        ldsResourceWatcher);

    // Client sends an ACK LDS request.
    call.sendResponse(LDS, testListenerRds, VERSION_1, "0000");
    call.verifyRequest(LDS, LDS_RESOURCE, VERSION_1, "0000", NODE);

    ResourceWatcher<LdsUpdate> watcher = mock(ResourceWatcher.class);
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),LDS_RESOURCE, watcher);
    verify(watcher).onChanged(ldsUpdateCaptor.capture());
    verifyGoldenListenerRds(ldsUpdateCaptor.getValue());
    call.verifyNoMoreRequest();
    verifyResourceMetadataAcked(LDS, LDS_RESOURCE, testListenerRds, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(1, 0, 0, 0);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void cachedLdsResource_absent() {
    DiscoveryRpcCall call = startResourceWatcher(XdsListenerResource.getInstance(), LDS_RESOURCE,
        ldsResourceWatcher);
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(ldsResourceWatcher).onResourceDoesNotExist(LDS_RESOURCE);
    // Add another watcher.
    ResourceWatcher<LdsUpdate> watcher = mock(ResourceWatcher.class);
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),LDS_RESOURCE, watcher);
    verify(watcher).onResourceDoesNotExist(LDS_RESOURCE);
    call.verifyNoMoreRequest();
    verifyResourceMetadataDoesNotExist(LDS, LDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(1, 0, 0, 0);
  }

  @Test
  public void ldsResourceUpdated() {
    DiscoveryRpcCall call = startResourceWatcher(XdsListenerResource.getInstance(), LDS_RESOURCE,
        ldsResourceWatcher);
    verifyResourceMetadataRequested(LDS, LDS_RESOURCE);

    // Initial LDS response.
    call.sendResponse(LDS, testListenerVhosts, VERSION_1, "0000");
    call.verifyRequest(LDS, LDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    verifyGoldenListenerVhosts(ldsUpdateCaptor.getValue());
    verifyResourceMetadataAcked(LDS, LDS_RESOURCE, testListenerVhosts, VERSION_1, TIME_INCREMENT);

    // Updated LDS response.
    call.sendResponse(LDS, testListenerRds, VERSION_2, "0001");
    call.verifyRequest(LDS, LDS_RESOURCE, VERSION_2, "0001", NODE);
    verify(ldsResourceWatcher, times(2)).onChanged(ldsUpdateCaptor.capture());
    verifyGoldenListenerRds(ldsUpdateCaptor.getValue());
    verifyResourceMetadataAcked(LDS, LDS_RESOURCE, testListenerRds, VERSION_2, TIME_INCREMENT * 2);
    verifySubscribedResourcesMetadataSizes(1, 0, 0, 0);
    assertThat(channelForCustomAuthority).isNull();
    assertThat(channelForEmptyAuthority).isNull();
  }

  @Test
  public void cancelResourceWatcherNotRemoveUrlSubscribers() {
    DiscoveryRpcCall call = startResourceWatcher(XdsListenerResource.getInstance(), LDS_RESOURCE,
        ldsResourceWatcher);
    verifyResourceMetadataRequested(LDS, LDS_RESOURCE);

    // Initial LDS response.
    call.sendResponse(LDS, testListenerVhosts, VERSION_1, "0000");
    call.verifyRequest(LDS, LDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    verifyGoldenListenerVhosts(ldsUpdateCaptor.getValue());
    verifyResourceMetadataAcked(LDS, LDS_RESOURCE, testListenerVhosts, VERSION_1, TIME_INCREMENT);

    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),
        LDS_RESOURCE + "1", ldsResourceWatcher);
    xdsClient.cancelXdsResourceWatch(XdsListenerResource.getInstance(), LDS_RESOURCE + "1",
        ldsResourceWatcher);

    // Updated LDS response.
    Any testListenerVhosts2 = Any.pack(mf.buildListenerWithApiListener(LDS_RESOURCE,
        mf.buildRouteConfiguration("new", mf.buildOpaqueVirtualHosts(VHOST_SIZE))));
    call.sendResponse(LDS, testListenerVhosts2, VERSION_2, "0001");
    call.verifyRequest(LDS, LDS_RESOURCE, VERSION_2, "0001", NODE);
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    verifyGoldenListenerVhosts(ldsUpdateCaptor.getValue());
    verifyResourceMetadataAcked(LDS, LDS_RESOURCE, testListenerVhosts2, VERSION_2,
        TIME_INCREMENT * 2);
  }

  @Test
  public void ldsResourceUpdated_withXdstpResourceName() {
    BootstrapperImpl.enableFederation = true;
    String ldsResourceName = useProtocolV3()
        ? "xdstp://authority.xds.com/envoy.config.listener.v3.Listener/listener1"
        : "xdstp://authority.xds.com/envoy.api.v2.Listener/listener1";
    DiscoveryRpcCall call = startResourceWatcher(XdsListenerResource.getInstance(), ldsResourceName,
        ldsResourceWatcher);
    assertThat(channelForCustomAuthority).isNotNull();
    verifyResourceMetadataRequested(LDS, ldsResourceName);

    Any testListenerVhosts = Any.pack(mf.buildListenerWithApiListener(ldsResourceName,
        mf.buildRouteConfiguration("do not care", mf.buildOpaqueVirtualHosts(VHOST_SIZE))));
    call.sendResponse(LDS, testListenerVhosts, VERSION_1, "0000");
    call.verifyRequest(LDS, ldsResourceName, VERSION_1, "0000", NODE);
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    verifyGoldenListenerVhosts(ldsUpdateCaptor.getValue());
    verifyResourceMetadataAcked(
        LDS, ldsResourceName, testListenerVhosts, VERSION_1, TIME_INCREMENT);
  }

  @Test
  public void ldsResourceUpdated_withXdstpResourceName_withEmptyAuthority() {
    BootstrapperImpl.enableFederation = true;
    String ldsResourceName = useProtocolV3()
        ? "xdstp:///envoy.config.listener.v3.Listener/listener1"
        : "xdstp:///envoy.api.v2.Listener/listener1";
    DiscoveryRpcCall call = startResourceWatcher(XdsListenerResource.getInstance(), ldsResourceName,
        ldsResourceWatcher);
    assertThat(channelForEmptyAuthority).isNotNull();
    verifyResourceMetadataRequested(LDS, ldsResourceName);

    Any testListenerVhosts = Any.pack(mf.buildListenerWithApiListener(ldsResourceName,
        mf.buildRouteConfiguration("do not care", mf.buildOpaqueVirtualHosts(VHOST_SIZE))));
    call.sendResponse(LDS, testListenerVhosts, VERSION_1, "0000");
    call.verifyRequest(LDS, ldsResourceName, VERSION_1, "0000", NODE);
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    verifyGoldenListenerVhosts(ldsUpdateCaptor.getValue());
    verifyResourceMetadataAcked(
        LDS, ldsResourceName, testListenerVhosts, VERSION_1, TIME_INCREMENT);
  }

  @Test
  public void ldsResourceUpdated_withXdstpResourceName_witUnorderedContextParams() {
    BootstrapperImpl.enableFederation = true;
    String ldsResourceName = useProtocolV3()
        ? "xdstp://authority.xds.com/envoy.config.listener.v3.Listener/listener1/a?bar=2&foo=1"
        : "xdstp://authority.xds.com/envoy.api.v2.Listener/listener1/a?bar=2&foo=1";
    DiscoveryRpcCall call = startResourceWatcher(XdsListenerResource.getInstance(), ldsResourceName,
        ldsResourceWatcher);
    assertThat(channelForCustomAuthority).isNotNull();

    String ldsResourceNameWithUnorderedContextParams = useProtocolV3()
        ? "xdstp://authority.xds.com/envoy.config.listener.v3.Listener/listener1/a?foo=1&bar=2"
        : "xdstp://authority.xds.com/envoy.api.v2.Listener/listener1/a?foo=1&bar=2";
    Any testListenerVhosts = Any.pack(mf.buildListenerWithApiListener(
        ldsResourceNameWithUnorderedContextParams,
        mf.buildRouteConfiguration("do not care", mf.buildOpaqueVirtualHosts(VHOST_SIZE))));
    call.sendResponse(LDS, testListenerVhosts, VERSION_1, "0000");
    call.verifyRequest(
        LDS, ldsResourceName, VERSION_1, "0000", NODE);
  }

  @Test
  public void ldsResourceUpdated_withXdstpResourceName_withWrongType() {
    BootstrapperImpl.enableFederation = true;
    String ldsResourceName = useProtocolV3()
        ? "xdstp://authority.xds.com/envoy.config.listener.v3.Listener/listener1"
        : "xdstp://authority.xds.com/envoy.api.v2.Listener/listener1";
    DiscoveryRpcCall call = startResourceWatcher(XdsListenerResource.getInstance(), ldsResourceName,
        ldsResourceWatcher);
    assertThat(channelForCustomAuthority).isNotNull();

    String ldsResourceNameWithWrongType =
        "xdstp://authority.xds.com/envoy.config.route.v3.RouteConfiguration/listener1";
    Any testListenerVhosts = Any.pack(mf.buildListenerWithApiListener(
        ldsResourceNameWithWrongType,
        mf.buildRouteConfiguration("do not care", mf.buildOpaqueVirtualHosts(VHOST_SIZE))));
    call.sendResponse(LDS, testListenerVhosts, VERSION_1, "0000");
    call.verifyRequestNack(
        LDS, ldsResourceName, "", "0000", NODE,
        ImmutableList.of(
            "Unsupported resource name: " +  ldsResourceNameWithWrongType + " for type: LDS"));
  }

  @Test
  public void rdsResourceUpdated_withXdstpResourceName_withWrongType() {
    BootstrapperImpl.enableFederation = true;
    String rdsResourceName = useProtocolV3()
        ? "xdstp://authority.xds.com/envoy.config.route.v3.RouteConfiguration/route1"
        : "xdstp://authority.xds.com/envoy.api.v2.RouteConfiguration/route1";
    DiscoveryRpcCall call = startResourceWatcher(XdsRouteConfigureResource.getInstance(),
        rdsResourceName, rdsResourceWatcher);
    assertThat(channelForCustomAuthority).isNotNull();

    String rdsResourceNameWithWrongType =
        "xdstp://authority.xds.com/envoy.config.listener.v3.Listener/route1";
    Any testRouteConfig = Any.pack(mf.buildRouteConfiguration(
        rdsResourceNameWithWrongType, mf.buildOpaqueVirtualHosts(VHOST_SIZE)));
    call.sendResponse(RDS, testRouteConfig, VERSION_1, "0000");
    call.verifyRequestNack(
        RDS, rdsResourceName, "", "0000", NODE,
        ImmutableList.of(
            "Unsupported resource name: " + rdsResourceNameWithWrongType + " for type: RDS"));
  }

  @Test
  public void rdsResourceUpdated_withXdstpResourceName_unknownAuthority() {
    BootstrapperImpl.enableFederation = true;
    String rdsResourceName = useProtocolV3()
        ? "xdstp://unknown.example.com/envoy.config.route.v3.RouteConfiguration/route1"
        : "xdstp://unknown.example.com/envoy.api.v2.RouteConfiguration/route1";
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(),rdsResourceName,
        rdsResourceWatcher);
    verify(rdsResourceWatcher).onError(errorCaptor.capture());
    Status error = errorCaptor.getValue();
    assertThat(error.getCode()).isEqualTo(Code.INVALID_ARGUMENT);
    assertThat(error.getDescription()).isEqualTo(
        "Wrong configuration: xds server does not exist for resource " + rdsResourceName);
    assertThat(resourceDiscoveryCalls.size()).isEqualTo(0);
    xdsClient.cancelXdsResourceWatch(
        XdsRouteConfigureResource.getInstance(),rdsResourceName, rdsResourceWatcher);
    assertThat(resourceDiscoveryCalls.size()).isEqualTo(0);
  }

  @Test
  public void cdsResourceUpdated_withXdstpResourceName_withWrongType() {
    BootstrapperImpl.enableFederation = true;
    String cdsResourceName = useProtocolV3()
        ? "xdstp://authority.xds.com/envoy.config.cluster.v3.Cluster/cluster1"
        : "xdstp://authority.xds.com/envoy.api.v2.Cluster/cluster1";
    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(), cdsResourceName,
        cdsResourceWatcher);
    assertThat(channelForCustomAuthority).isNotNull();

    String cdsResourceNameWithWrongType =
        "xdstp://authority.xds.com/envoy.config.listener.v3.Listener/cluster1";
    Any testClusterConfig = Any.pack(mf.buildEdsCluster(
        cdsResourceNameWithWrongType, null, "round_robin", null, null, false, null,
        "envoy.transport_sockets.tls", null, null));
    call.sendResponse(CDS, testClusterConfig, VERSION_1, "0000");
    call.verifyRequestNack(
        CDS, cdsResourceName, "", "0000", NODE,
        ImmutableList.of(
            "Unsupported resource name: " + cdsResourceNameWithWrongType + " for type: CDS"));
  }

  @Test
  public void cdsResourceUpdated_withXdstpResourceName_unknownAuthority() {
    BootstrapperImpl.enableFederation = true;
    String cdsResourceName = useProtocolV3()
        ? "xdstp://unknown.example.com/envoy.config.cluster.v3.Cluster/cluster1"
        : "xdstp://unknown.example.com/envoy.api.v2.Cluster/cluster1";
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(),cdsResourceName,
        cdsResourceWatcher);
    verify(cdsResourceWatcher).onError(errorCaptor.capture());
    Status error = errorCaptor.getValue();
    assertThat(error.getCode()).isEqualTo(Code.INVALID_ARGUMENT);
    assertThat(error.getDescription()).isEqualTo(
        "Wrong configuration: xds server does not exist for resource " + cdsResourceName);
    assertThat(resourceDiscoveryCalls.poll()).isNull();
    xdsClient.cancelXdsResourceWatch(XdsClusterResource.getInstance(),cdsResourceName,
        cdsResourceWatcher);
    assertThat(resourceDiscoveryCalls.poll()).isNull();
  }

  @Test
  public void edsResourceUpdated_withXdstpResourceName_withWrongType() {
    BootstrapperImpl.enableFederation = true;
    String edsResourceName = useProtocolV3()
        ? "xdstp://authority.xds.com/envoy.config.endpoint.v3.ClusterLoadAssignment/cluster1"
        : "xdstp://authority.xds.com/envoy.api.v2.ClusterLoadAssignment/cluster1";
    DiscoveryRpcCall call = startResourceWatcher(XdsEndpointResource.getInstance(), edsResourceName,
        edsResourceWatcher);
    assertThat(channelForCustomAuthority).isNotNull();

    String edsResourceNameWithWrongType =
        "xdstp://authority.xds.com/envoy.config.listener.v3.Listener/cluster1";
    Any testEdsConfig = Any.pack(mf.buildClusterLoadAssignment(
        edsResourceNameWithWrongType,
        ImmutableList.of(mf.buildLocalityLbEndpoints(
            "region2", "zone2", "subzone2",
            mf.buildLbEndpoint("172.44.2.2", 8000, "unknown", 3), 2, 0)),
        ImmutableList.of()));
    call.sendResponse(EDS, testEdsConfig, VERSION_1, "0000");
    call.verifyRequestNack(
        EDS, edsResourceName, "", "0000", NODE,
        ImmutableList.of(
            "Unsupported resource name: " + edsResourceNameWithWrongType + " for type: EDS"));
  }

  @Test
  public void edsResourceUpdated_withXdstpResourceName_unknownAuthority() {
    BootstrapperImpl.enableFederation = true;
    String edsResourceName = useProtocolV3()
        ? "xdstp://unknown.example.com/envoy.config.endpoint.v3.ClusterLoadAssignment/cluster1"
        : "xdstp://unknown.example.com/envoy.api.v2.ClusterLoadAssignment/cluster1";
    xdsClient.watchXdsResource(XdsEndpointResource.getInstance(),
        edsResourceName, edsResourceWatcher);
    verify(edsResourceWatcher).onError(errorCaptor.capture());
    Status error = errorCaptor.getValue();
    assertThat(error.getCode()).isEqualTo(Code.INVALID_ARGUMENT);
    assertThat(error.getDescription()).isEqualTo(
        "Wrong configuration: xds server does not exist for resource " + edsResourceName);
    assertThat(resourceDiscoveryCalls.poll()).isNull();
    xdsClient.cancelXdsResourceWatch(XdsEndpointResource.getInstance(),
        edsResourceName, edsResourceWatcher);
    assertThat(resourceDiscoveryCalls.poll()).isNull();
  }

  @Test
  public void ldsResourceUpdate_withFaultInjection() {
    Assume.assumeTrue(useProtocolV3());
    DiscoveryRpcCall call = startResourceWatcher(XdsListenerResource.getInstance(), LDS_RESOURCE,
        ldsResourceWatcher);
    Any listener = Any.pack(
        mf.buildListenerWithApiListener(
            LDS_RESOURCE,
            mf.buildRouteConfiguration(
                "do not care",
                ImmutableList.of(
                    mf.buildVirtualHost(
                        mf.buildOpaqueRoutes(1),
                        ImmutableMap.of(
                            "irrelevant",
                            Any.pack(FilterConfig.newBuilder().setIsOptional(true).build()),
                            "envoy.fault",
                            mf.buildHttpFaultTypedConfig(
                                300L, 1000, "cluster1", ImmutableList.<String>of(), 100, null, null,
                                null))),
                    mf.buildVirtualHost(
                        mf.buildOpaqueRoutes(2),
                        ImmutableMap.of(
                            "envoy.fault",
                            mf.buildHttpFaultTypedConfig(
                                null, null, "cluster2", ImmutableList.<String>of(), 101, null, 503,
                                2000))))),
            ImmutableList.of(
                mf.buildHttpFilter("irrelevant", null, true),
                mf.buildHttpFilter(
                    "envoy.fault",
                    mf.buildHttpFaultTypedConfig(
                        1L, 2, "cluster1", ImmutableList.<String>of(), 3, null, null,
                        null),
                    false),
                mf.buildHttpFilter("terminal", Any.pack(Router.newBuilder().build()), true))));
    call.sendResponse(LDS, listener, VERSION_1, "0000");

    // Client sends an ACK LDS request.
    call.verifyRequest(LDS, LDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    verifyResourceMetadataAcked(LDS, LDS_RESOURCE, listener, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(1, 0, 0, 0);

    LdsUpdate ldsUpdate = ldsUpdateCaptor.getValue();
    assertThat(ldsUpdate.httpConnectionManager().virtualHosts()).hasSize(2);
    assertThat(ldsUpdate.httpConnectionManager().httpFilterConfigs().get(0).name)
        .isEqualTo("envoy.fault");
    FaultConfig faultConfig = (FaultConfig) ldsUpdate.httpConnectionManager().virtualHosts().get(0)
        .filterConfigOverrides().get("envoy.fault");
    assertThat(faultConfig.faultDelay().delayNanos()).isEqualTo(300);
    assertThat(faultConfig.faultDelay().percent().numerator()).isEqualTo(1000);
    assertThat(faultConfig.faultDelay().percent().denominatorType())
        .isEqualTo(DenominatorType.MILLION);
    assertThat(faultConfig.faultAbort()).isNull();
    assertThat(faultConfig.maxActiveFaults()).isEqualTo(100);
    faultConfig = (FaultConfig) ldsUpdate.httpConnectionManager().virtualHosts().get(1)
        .filterConfigOverrides().get("envoy.fault");
    assertThat(faultConfig.faultDelay()).isNull();
    assertThat(faultConfig.faultAbort().status().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(faultConfig.faultAbort().percent().numerator()).isEqualTo(2000);
    assertThat(faultConfig.faultAbort().percent().denominatorType())
        .isEqualTo(DenominatorType.MILLION);
    assertThat(faultConfig.maxActiveFaults()).isEqualTo(101);
  }

  @Test
  public void ldsResourceDeleted() {
    Assume.assumeFalse(ignoreResourceDeletion());

    DiscoveryRpcCall call = startResourceWatcher(XdsListenerResource.getInstance(), LDS_RESOURCE,
        ldsResourceWatcher);
    verifyResourceMetadataRequested(LDS, LDS_RESOURCE);

    // Initial LDS response.
    call.sendResponse(LDS, testListenerVhosts, VERSION_1, "0000");
    call.verifyRequest(LDS, LDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    verifyGoldenListenerVhosts(ldsUpdateCaptor.getValue());
    verifyResourceMetadataAcked(LDS, LDS_RESOURCE, testListenerVhosts, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(1, 0, 0, 0);

    // Empty LDS response deletes the listener.
    call.sendResponse(LDS, Collections.<Any>emptyList(), VERSION_2, "0001");
    call.verifyRequest(LDS, LDS_RESOURCE, VERSION_2, "0001", NODE);
    verify(ldsResourceWatcher).onResourceDoesNotExist(LDS_RESOURCE);
    verifyResourceMetadataDoesNotExist(LDS, LDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(1, 0, 0, 0);
  }

  /**
   * When ignore_resource_deletion server feature is on, xDS client should keep the deleted listener
   * on empty response, and resume the normal work when LDS contains the listener again.
   * */
  @Test
  public void ldsResourceDeleted_ignoreResourceDeletion() {
    Assume.assumeTrue(ignoreResourceDeletion());

    DiscoveryRpcCall call = startResourceWatcher(XdsListenerResource.getInstance(), LDS_RESOURCE,
        ldsResourceWatcher);
    verifyResourceMetadataRequested(LDS, LDS_RESOURCE);

    // Initial LDS response.
    call.sendResponse(LDS, testListenerVhosts, VERSION_1, "0000");
    call.verifyRequest(LDS, LDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    verifyGoldenListenerVhosts(ldsUpdateCaptor.getValue());
    verifyResourceMetadataAcked(LDS, LDS_RESOURCE, testListenerVhosts, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(1, 0, 0, 0);

    // Empty LDS response does not delete the listener.
    call.sendResponse(LDS, Collections.emptyList(), VERSION_2, "0001");
    call.verifyRequest(LDS, LDS_RESOURCE, VERSION_2, "0001", NODE);
    // The resource is still ACKED at VERSION_1 (no changes).
    verifyResourceMetadataAcked(LDS, LDS_RESOURCE, testListenerVhosts, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(1, 0, 0, 0);
    // onResourceDoesNotExist not called
    verify(ldsResourceWatcher, never()).onResourceDoesNotExist(LDS_RESOURCE);

    // Next update is correct, and contains the listener again.
    call.sendResponse(LDS, testListenerVhosts, VERSION_3, "0003");
    call.verifyRequest(LDS, LDS_RESOURCE, VERSION_3, "0003", NODE);
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    verifyGoldenListenerVhosts(ldsUpdateCaptor.getValue());
    // LDS is now ACKEd at VERSION_3.
    verifyResourceMetadataAcked(LDS, LDS_RESOURCE, testListenerVhosts, VERSION_3,
        TIME_INCREMENT * 3);
    verifySubscribedResourcesMetadataSizes(1, 0, 0, 0);
    verifyNoMoreInteractions(ldsResourceWatcher);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void multipleLdsWatchers() {
    String ldsResourceTwo = "bar.googleapis.com";
    ResourceWatcher<LdsUpdate> watcher1 = mock(ResourceWatcher.class);
    ResourceWatcher<LdsUpdate> watcher2 = mock(ResourceWatcher.class);
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),LDS_RESOURCE, ldsResourceWatcher);
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),ldsResourceTwo, watcher1);
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),ldsResourceTwo, watcher2);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    call.verifyRequest(LDS, ImmutableList.of(LDS_RESOURCE, ldsResourceTwo), "", "", NODE);
    // Both LDS resources were requested.
    verifyResourceMetadataRequested(LDS, LDS_RESOURCE);
    verifyResourceMetadataRequested(LDS, ldsResourceTwo);
    verifySubscribedResourcesMetadataSizes(2, 0, 0, 0);

    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(ldsResourceWatcher).onResourceDoesNotExist(LDS_RESOURCE);
    verify(watcher1).onResourceDoesNotExist(ldsResourceTwo);
    verify(watcher2).onResourceDoesNotExist(ldsResourceTwo);
    verifyResourceMetadataDoesNotExist(LDS, LDS_RESOURCE);
    verifyResourceMetadataDoesNotExist(LDS, ldsResourceTwo);
    verifySubscribedResourcesMetadataSizes(2, 0, 0, 0);

    Any listenerTwo = Any.pack(mf.buildListenerWithApiListenerForRds(ldsResourceTwo, RDS_RESOURCE));
    call.sendResponse(LDS, ImmutableList.of(testListenerVhosts, listenerTwo), VERSION_1, "0000");
    // ResourceWatcher called with listenerVhosts.
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    verifyGoldenListenerVhosts(ldsUpdateCaptor.getValue());
    // watcher1 called with listenerTwo.
    verify(watcher1).onChanged(ldsUpdateCaptor.capture());
    verifyGoldenListenerRds(ldsUpdateCaptor.getValue());
    assertThat(ldsUpdateCaptor.getValue().httpConnectionManager().virtualHosts()).isNull();
    // watcher2 called with listenerTwo.
    verify(watcher2).onChanged(ldsUpdateCaptor.capture());
    verifyGoldenListenerRds(ldsUpdateCaptor.getValue());
    assertThat(ldsUpdateCaptor.getValue().httpConnectionManager().virtualHosts()).isNull();
    // Metadata of both listeners is stored.
    verifyResourceMetadataAcked(LDS, LDS_RESOURCE, testListenerVhosts, VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataAcked(LDS, ldsResourceTwo, listenerTwo, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(2, 0, 0, 0);
  }

  @Test
  public void rdsResourceNotFound() {
    DiscoveryRpcCall call = startResourceWatcher(XdsRouteConfigureResource.getInstance(),
        RDS_RESOURCE, rdsResourceWatcher);
    Any routeConfig = Any.pack(mf.buildRouteConfiguration("route-bar.googleapis.com",
            mf.buildOpaqueVirtualHosts(2)));
    call.sendResponse(RDS, routeConfig, VERSION_1, "0000");

    // Client sends an ACK RDS request.
    call.verifyRequest(RDS, RDS_RESOURCE, VERSION_1, "0000", NODE);
    verifyNoInteractions(rdsResourceWatcher);
    verifyResourceMetadataRequested(RDS, RDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(0, 0, 1, 0);
    // Server failed to return subscribed resource within expected time window.
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(rdsResourceWatcher).onResourceDoesNotExist(RDS_RESOURCE);
    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
    verifyResourceMetadataDoesNotExist(RDS, RDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(0, 0, 1, 0);
  }

  @Test
  public void rdsResponseErrorHandling_allResourcesFailedUnpack() {
    DiscoveryRpcCall call = startResourceWatcher(XdsRouteConfigureResource.getInstance(),
        RDS_RESOURCE, rdsResourceWatcher);
    verifyResourceMetadataRequested(RDS, RDS_RESOURCE);
    call.sendResponse(RDS, ImmutableList.of(FAILING_ANY, FAILING_ANY), VERSION_1, "0000");

    // Resulting metadata unchanged because the response has no identifiable subscribed resources.
    verifyResourceMetadataRequested(RDS, RDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(0, 0, 1, 0);
    // The response NACKed with errors indicating indices of the failed resources.
    call.verifyRequestNack(RDS, RDS_RESOURCE, "", "0000", NODE, ImmutableList.of(
        "RDS response Resource index 0 - can't decode RouteConfiguration: ",
        "RDS response Resource index 1 - can't decode RouteConfiguration: "));
    verifyNoInteractions(rdsResourceWatcher);
  }

  @Test
  public void rdsResponseErrorHandling_someResourcesFailedUnpack() {
    DiscoveryRpcCall call = startResourceWatcher(XdsRouteConfigureResource.getInstance(),
        RDS_RESOURCE, rdsResourceWatcher);
    verifyResourceMetadataRequested(RDS, RDS_RESOURCE);

    // Correct resource is in the middle to ensure processing continues on errors.
    List<Any> resources = ImmutableList.of(FAILING_ANY, testRouteConfig, FAILING_ANY);
    call.sendResponse(RDS, resources, VERSION_1, "0000");

    // All errors recorded in the metadata of successfully unpacked subscribed resources.
    List<String> errors = ImmutableList.of(
        "RDS response Resource index 0 - can't decode RouteConfiguration: ",
        "RDS response Resource index 2 - can't decode RouteConfiguration: ");
    verifyResourceMetadataAcked(RDS, RDS_RESOURCE, testRouteConfig, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 0, 1, 0);
    // The response is NACKed with the same error message.
    call.verifyRequestNack(RDS, RDS_RESOURCE, "", "0000", NODE, errors);
    verify(rdsResourceWatcher).onChanged(any(RdsUpdate.class));
  }

  @Test
  public void rdsResponseErrorHandling_nackWeightedSumZero() {
    DiscoveryRpcCall call = startResourceWatcher(XdsRouteConfigureResource.getInstance(),
        RDS_RESOURCE, rdsResourceWatcher);
    verifyResourceMetadataRequested(RDS, RDS_RESOURCE);

    io.envoyproxy.envoy.config.route.v3.RouteAction routeAction =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setWeightedClusters(
                WeightedCluster.newBuilder()
                    .addClusters(
                        WeightedCluster.ClusterWeight
                            .newBuilder()
                            .setName("cluster-foo")
                            .setWeight(UInt32Value.newBuilder().setValue(0)))
                    .addClusters(WeightedCluster.ClusterWeight
                        .newBuilder()
                        .setName("cluster-bar")
                        .setWeight(UInt32Value.newBuilder().setValue(0))))
            .build();
    io.envoyproxy.envoy.config.route.v3.Route route =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-blade")
            .setMatch(
                io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
                    .setPath("/service/method"))
            .setRoute(routeAction)
            .build();

    Any zeroWeightSum = Any.pack(mf.buildRouteConfiguration(RDS_RESOURCE,
        Arrays.asList(mf.buildVirtualHost(Arrays.asList(route), ImmutableMap.of()))));
    List<Any> resources = ImmutableList.of(zeroWeightSum);
    call.sendResponse(RDS, resources, VERSION_1, "0000");

    List<String> errors = ImmutableList.of(
        "RDS response RouteConfiguration \'route-configuration.googleapis.com\' validation error: "
            + "RouteConfiguration contains invalid virtual host: Virtual host [do not care] "
            + "contains invalid route : Route [route-blade] contains invalid RouteAction: "
            + "Sum of cluster weights should be above 0.");
    verifySubscribedResourcesMetadataSizes(0, 0, 1, 0);
    // The response is NACKed with the same error message.
    call.verifyRequestNack(RDS, RDS_RESOURCE, "", "0000", NODE, errors);
    verify(rdsResourceWatcher, never()).onChanged(any(RdsUpdate.class));
  }

  /**
   * Tests a subscribed RDS resource transitioned to and from the invalid state.
   *
   * @see <a href="https://github.com/grpc/proposal/blob/master/A40-csds-support.md#ads-parsing-logic-update-continue-after-first-error">
   * A40-csds-support.md</a>
   */
  @Test
  public void rdsResponseErrorHandling_subscribedResourceInvalid() {
    List<String> subscribedResourceNames = ImmutableList.of("A", "B", "C");
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(),"A", rdsResourceWatcher);
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(),"B", rdsResourceWatcher);
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(),"C", rdsResourceWatcher);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    assertThat(call).isNotNull();
    verifyResourceMetadataRequested(RDS, "A");
    verifyResourceMetadataRequested(RDS, "B");
    verifyResourceMetadataRequested(RDS, "C");
    verifySubscribedResourcesMetadataSizes(0, 0, 3, 0);

    // RDS -> {A, B, C}, version 1
    List<Message> vhostsV1 = mf.buildOpaqueVirtualHosts(1);
    ImmutableMap<String, Any> resourcesV1 = ImmutableMap.of(
        "A", Any.pack(mf.buildRouteConfiguration("A", vhostsV1)),
        "B", Any.pack(mf.buildRouteConfiguration("B", vhostsV1)),
        "C", Any.pack(mf.buildRouteConfiguration("C", vhostsV1)));
    call.sendResponse(RDS, resourcesV1.values().asList(), VERSION_1, "0000");
    // {A, B, C} -> ACK, version 1
    verifyResourceMetadataAcked(RDS, "A", resourcesV1.get("A"), VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataAcked(RDS, "B", resourcesV1.get("B"), VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataAcked(RDS, "C", resourcesV1.get("C"), VERSION_1, TIME_INCREMENT);
    call.verifyRequest(RDS, subscribedResourceNames, VERSION_1, "0000", NODE);

    // RDS -> {A, B}, version 2
    // Failed to parse endpoint B
    ImmutableMap<String, Any> resourcesV2 = ImmutableMap.of(
        "A", Any.pack(mf.buildRouteConfiguration("A", mf.buildOpaqueVirtualHosts(2))),
        "B", Any.pack(mf.buildRouteConfigurationInvalid("B")));
    call.sendResponse(RDS, resourcesV2.values().asList(), VERSION_2, "0001");
    // {A} -> ACK, version 2
    // {B} -> NACK, version 1, rejected version 2, rejected reason: Failed to parse B
    // {C} -> ACK, version 1
    List<String> errorsV2 =
        ImmutableList.of("RDS response RouteConfiguration 'B' validation error: ");
    verifyResourceMetadataAcked(RDS, "A", resourcesV2.get("A"), VERSION_2, TIME_INCREMENT * 2);
    verifyResourceMetadataNacked(RDS, "B", resourcesV1.get("B"), VERSION_1, TIME_INCREMENT,
        VERSION_2, TIME_INCREMENT * 2, errorsV2);
    verifyResourceMetadataAcked(RDS, "C", resourcesV1.get("C"), VERSION_1, TIME_INCREMENT);
    call.verifyRequestNack(RDS, subscribedResourceNames, VERSION_1, "0001", NODE, errorsV2);

    // RDS -> {B, C} version 3
    List<Message> vhostsV3 = mf.buildOpaqueVirtualHosts(3);
    ImmutableMap<String, Any> resourcesV3 = ImmutableMap.of(
        "B", Any.pack(mf.buildRouteConfiguration("B", vhostsV3)),
        "C", Any.pack(mf.buildRouteConfiguration("C", vhostsV3)));
    call.sendResponse(RDS, resourcesV3.values().asList(), VERSION_3, "0002");
    // {A} -> ACK, version 2
    // {B, C} -> ACK, version 3
    verifyResourceMetadataAcked(RDS, "A", resourcesV2.get("A"), VERSION_2, TIME_INCREMENT * 2);
    verifyResourceMetadataAcked(RDS, "B", resourcesV3.get("B"), VERSION_3, TIME_INCREMENT * 3);
    verifyResourceMetadataAcked(RDS, "C", resourcesV3.get("C"), VERSION_3, TIME_INCREMENT * 3);
    call.verifyRequest(RDS, subscribedResourceNames, VERSION_3, "0002", NODE);
    verifySubscribedResourcesMetadataSizes(0, 0, 3, 0);
  }

  @Test
  public void rdsResourceFound() {
    DiscoveryRpcCall call = startResourceWatcher(XdsRouteConfigureResource.getInstance(),
        RDS_RESOURCE, rdsResourceWatcher);
    call.sendResponse(RDS, testRouteConfig, VERSION_1, "0000");

    // Client sends an ACK RDS request.
    call.verifyRequest(RDS, RDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(rdsResourceWatcher).onChanged(rdsUpdateCaptor.capture());
    verifyGoldenRouteConfig(rdsUpdateCaptor.getValue());
    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
    verifyResourceMetadataAcked(RDS, RDS_RESOURCE, testRouteConfig, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 0, 1, 0);
  }

  @Test
  public void wrappedRdsResource() {
    DiscoveryRpcCall call = startResourceWatcher(XdsRouteConfigureResource.getInstance(),
        RDS_RESOURCE, rdsResourceWatcher);
    call.sendResponse(RDS, mf.buildWrappedResource(testRouteConfig), VERSION_1, "0000");

    // Client sends an ACK RDS request.
    call.verifyRequest(RDS, RDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(rdsResourceWatcher).onChanged(rdsUpdateCaptor.capture());
    verifyGoldenRouteConfig(rdsUpdateCaptor.getValue());
    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
    verifyResourceMetadataAcked(RDS, RDS_RESOURCE, testRouteConfig, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 0, 1, 0);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void cachedRdsResource_data() {
    DiscoveryRpcCall call = startResourceWatcher(XdsRouteConfigureResource.getInstance(),
        RDS_RESOURCE, rdsResourceWatcher);
    call.sendResponse(RDS, testRouteConfig, VERSION_1, "0000");

    // Client sends an ACK RDS request.
    call.verifyRequest(RDS, RDS_RESOURCE, VERSION_1, "0000", NODE);

    ResourceWatcher<RdsUpdate> watcher = mock(ResourceWatcher.class);
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(),RDS_RESOURCE, watcher);
    verify(watcher).onChanged(rdsUpdateCaptor.capture());
    verifyGoldenRouteConfig(rdsUpdateCaptor.getValue());
    call.verifyNoMoreRequest();
    verifyResourceMetadataAcked(RDS, RDS_RESOURCE, testRouteConfig, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 0, 1, 0);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void cachedRdsResource_absent() {
    DiscoveryRpcCall call = startResourceWatcher(XdsRouteConfigureResource.getInstance(),
        RDS_RESOURCE, rdsResourceWatcher);
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(rdsResourceWatcher).onResourceDoesNotExist(RDS_RESOURCE);
    // Add another watcher.
    ResourceWatcher<RdsUpdate> watcher = mock(ResourceWatcher.class);
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(),RDS_RESOURCE, watcher);
    verify(watcher).onResourceDoesNotExist(RDS_RESOURCE);
    call.verifyNoMoreRequest();
    verifyResourceMetadataDoesNotExist(RDS, RDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(0, 0, 1, 0);
  }

  @Test
  public void rdsResourceUpdated() {
    DiscoveryRpcCall call = startResourceWatcher(XdsRouteConfigureResource.getInstance(),
        RDS_RESOURCE, rdsResourceWatcher);
    verifyResourceMetadataRequested(RDS, RDS_RESOURCE);

    // Initial RDS response.
    call.sendResponse(RDS, testRouteConfig, VERSION_1, "0000");
    call.verifyRequest(RDS, RDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(rdsResourceWatcher).onChanged(rdsUpdateCaptor.capture());
    verifyGoldenRouteConfig(rdsUpdateCaptor.getValue());
    verifyResourceMetadataAcked(RDS, RDS_RESOURCE, testRouteConfig, VERSION_1, TIME_INCREMENT);

    // Updated RDS response.
    Any routeConfigUpdated =
        Any.pack(mf.buildRouteConfiguration(RDS_RESOURCE, mf.buildOpaqueVirtualHosts(4)));
    call.sendResponse(RDS, routeConfigUpdated, VERSION_2, "0001");

    // Client sends an ACK RDS request.
    call.verifyRequest(RDS, RDS_RESOURCE, VERSION_2, "0001", NODE);
    verify(rdsResourceWatcher, times(2)).onChanged(rdsUpdateCaptor.capture());
    assertThat(rdsUpdateCaptor.getValue().virtualHosts).hasSize(4);
    verifyResourceMetadataAcked(RDS, RDS_RESOURCE, routeConfigUpdated, VERSION_2,
        TIME_INCREMENT * 2);
    verifySubscribedResourcesMetadataSizes(0, 0, 1, 0);
  }

  @Test
  public void rdsResourceDeletedByLdsApiListener() {
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),LDS_RESOURCE,
        ldsResourceWatcher);
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(),RDS_RESOURCE,
        rdsResourceWatcher);
    verifyResourceMetadataRequested(LDS, LDS_RESOURCE);
    verifyResourceMetadataRequested(RDS, RDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(1, 0, 1, 0);

    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    call.sendResponse(LDS, testListenerRds, VERSION_1, "0000");
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    verifyGoldenListenerRds(ldsUpdateCaptor.getValue());
    verifyResourceMetadataAcked(LDS, LDS_RESOURCE, testListenerRds, VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataRequested(RDS, RDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(1, 0, 1, 0);

    call.sendResponse(RDS, testRouteConfig, VERSION_1, "0000");
    verify(rdsResourceWatcher).onChanged(rdsUpdateCaptor.capture());
    verifyGoldenRouteConfig(rdsUpdateCaptor.getValue());
    verifyResourceMetadataAcked(LDS, LDS_RESOURCE, testListenerRds, VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataAcked(RDS, RDS_RESOURCE, testRouteConfig, VERSION_1, TIME_INCREMENT * 2);
    verifySubscribedResourcesMetadataSizes(1, 0, 1, 0);

    // The Listener is getting replaced configured with an RDS name, to the one configured with
    // vhosts. Expect the RDS resources to be discarded.
    // Note that this must work the same despite the ignore_resource_deletion feature is on.
    // This happens because the Listener is getting replaced, and not deleted.
    call.sendResponse(LDS, testListenerVhosts, VERSION_2, "0001");
    verify(ldsResourceWatcher, times(2)).onChanged(ldsUpdateCaptor.capture());
    verifyGoldenListenerVhosts(ldsUpdateCaptor.getValue());
    verifyNoMoreInteractions(rdsResourceWatcher);
    verifyResourceMetadataAcked(RDS, RDS_RESOURCE, testRouteConfig, VERSION_1, TIME_INCREMENT * 2);
    verifyResourceMetadataAcked(
        LDS, LDS_RESOURCE, testListenerVhosts, VERSION_2, TIME_INCREMENT * 3);
    verifySubscribedResourcesMetadataSizes(1, 0, 1, 0);
  }

  @Test
  public void rdsResourcesDeletedByLdsTcpListener() {
    Assume.assumeTrue(useProtocolV3());
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), LISTENER_RESOURCE,
        ldsResourceWatcher);
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(), RDS_RESOURCE,
        rdsResourceWatcher);
    verifyResourceMetadataRequested(LDS, LISTENER_RESOURCE);
    verifyResourceMetadataRequested(RDS, RDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(1, 0, 1, 0);

    Message hcmFilter = mf.buildHttpConnectionManagerFilter(
        RDS_RESOURCE, null, Collections.singletonList(mf.buildTerminalFilter()));
    Message downstreamTlsContext = CommonTlsContextTestsUtil.buildTestDownstreamTlsContext(
        "google-sds-config-default", "ROOTCA", false);
    Message filterChain = mf.buildFilterChain(
        Collections.<String>emptyList(), downstreamTlsContext, "envoy.transport_sockets.tls",
        hcmFilter);
    Any packedListener =
        Any.pack(mf.buildListenerWithFilterChain(LISTENER_RESOURCE, 7000, "0.0.0.0", filterChain));

    // Simulates receiving the requested LDS resource as a TCP listener with a filter chain
    // referencing RDS_RESOURCE.
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    call.sendResponse(LDS, packedListener, VERSION_1, "0000");
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());

    assertThat(ldsUpdateCaptor.getValue().listener().filterChains()).hasSize(1);
    FilterChain parsedFilterChain = Iterables.getOnlyElement(
        ldsUpdateCaptor.getValue().listener().filterChains());
    assertThat(parsedFilterChain.httpConnectionManager().rdsName()).isEqualTo(RDS_RESOURCE);
    verifyResourceMetadataAcked(LDS, LISTENER_RESOURCE, packedListener, VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataRequested(RDS, RDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(1, 0, 1, 0);

    // Simulates receiving the requested RDS resource.
    call.sendResponse(RDS, testRouteConfig, VERSION_1, "0000");
    verify(rdsResourceWatcher).onChanged(rdsUpdateCaptor.capture());
    verifyGoldenRouteConfig(rdsUpdateCaptor.getValue());
    verifyResourceMetadataAcked(RDS, RDS_RESOURCE, testRouteConfig, VERSION_1, TIME_INCREMENT * 2);

    // Simulates receiving an updated version of the requested LDS resource as a TCP listener
    // with a filter chain containing inlined RouteConfiguration.
    // Note that this must work the same despite the ignore_resource_deletion feature is on.
    // This happens because the Listener is getting replaced, and not deleted.
    hcmFilter = mf.buildHttpConnectionManagerFilter(
        null,
        mf.buildRouteConfiguration(
            "route-bar.googleapis.com", mf.buildOpaqueVirtualHosts(VHOST_SIZE)),
        Collections.singletonList(mf.buildTerminalFilter()));
    filterChain = mf.buildFilterChain(
        Collections.<String>emptyList(), downstreamTlsContext, "envoy.transport_sockets.tls",
        hcmFilter);
    packedListener =
        Any.pack(mf.buildListenerWithFilterChain(LISTENER_RESOURCE, 7000, "0.0.0.0", filterChain));
    call.sendResponse(LDS, packedListener, VERSION_2, "0001");
    verify(ldsResourceWatcher, times(2)).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().listener().filterChains()).hasSize(1);
    parsedFilterChain = Iterables.getOnlyElement(
        ldsUpdateCaptor.getValue().listener().filterChains());
    assertThat(parsedFilterChain.httpConnectionManager().virtualHosts()).hasSize(VHOST_SIZE);
    verify(rdsResourceWatcher, never()).onResourceDoesNotExist(RDS_RESOURCE);
    verifyResourceMetadataAcked(RDS, RDS_RESOURCE, testRouteConfig, VERSION_1, TIME_INCREMENT * 2);
    verifyResourceMetadataAcked(
        LDS, LISTENER_RESOURCE, packedListener, VERSION_2, TIME_INCREMENT * 3);
    verifySubscribedResourcesMetadataSizes(1, 0, 1, 0);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void multipleRdsWatchers() {
    String rdsResourceTwo = "route-bar.googleapis.com";
    ResourceWatcher<RdsUpdate> watcher1 = mock(ResourceWatcher.class);
    ResourceWatcher<RdsUpdate> watcher2 = mock(ResourceWatcher.class);
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(),RDS_RESOURCE,
        rdsResourceWatcher);
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(),rdsResourceTwo, watcher1);
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(),rdsResourceTwo, watcher2);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    call.verifyRequest(RDS, Arrays.asList(RDS_RESOURCE, rdsResourceTwo), "", "", NODE);
    // Both RDS resources were requested.
    verifyResourceMetadataRequested(RDS, RDS_RESOURCE);
    verifyResourceMetadataRequested(RDS, rdsResourceTwo);
    verifySubscribedResourcesMetadataSizes(0, 0, 2, 0);

    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(rdsResourceWatcher).onResourceDoesNotExist(RDS_RESOURCE);
    verify(watcher1).onResourceDoesNotExist(rdsResourceTwo);
    verify(watcher2).onResourceDoesNotExist(rdsResourceTwo);
    verifyResourceMetadataDoesNotExist(RDS, RDS_RESOURCE);
    verifyResourceMetadataDoesNotExist(RDS, rdsResourceTwo);
    verifySubscribedResourcesMetadataSizes(0, 0, 2, 0);

    call.sendResponse(RDS, testRouteConfig, VERSION_1, "0000");
    verify(rdsResourceWatcher).onChanged(rdsUpdateCaptor.capture());
    verifyGoldenRouteConfig(rdsUpdateCaptor.getValue());
    verifyNoMoreInteractions(watcher1, watcher2);
    verifyResourceMetadataAcked(RDS, RDS_RESOURCE, testRouteConfig, VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataDoesNotExist(RDS, rdsResourceTwo);
    verifySubscribedResourcesMetadataSizes(0, 0, 2, 0);

    Any routeConfigTwo =
        Any.pack(mf.buildRouteConfiguration(rdsResourceTwo, mf.buildOpaqueVirtualHosts(4)));
    call.sendResponse(RDS, routeConfigTwo, VERSION_2, "0002");
    verify(watcher1).onChanged(rdsUpdateCaptor.capture());
    assertThat(rdsUpdateCaptor.getValue().virtualHosts).hasSize(4);
    verify(watcher2).onChanged(rdsUpdateCaptor.capture());
    assertThat(rdsUpdateCaptor.getValue().virtualHosts).hasSize(4);
    verifyNoMoreInteractions(rdsResourceWatcher);
    verifyResourceMetadataAcked(RDS, RDS_RESOURCE, testRouteConfig, VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataAcked(RDS, rdsResourceTwo, routeConfigTwo, VERSION_2, TIME_INCREMENT * 2);
    verifySubscribedResourcesMetadataSizes(0, 0, 2, 0);
  }

  @Test
  public void cdsResourceNotFound() {
    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(), CDS_RESOURCE,
        cdsResourceWatcher);

    List<Any> clusters = ImmutableList.of(
        Any.pack(mf.buildEdsCluster("cluster-bar.googleapis.com", null, "round_robin", null,
            null, false, null, "envoy.transport_sockets.tls", null, null)),
        Any.pack(mf.buildEdsCluster("cluster-baz.googleapis.com", null, "round_robin", null,
            null, false, null, "envoy.transport_sockets.tls", null, null)));
    call.sendResponse(CDS, clusters, VERSION_1, "0000");

    // Client sent an ACK CDS request.
    call.verifyRequest(CDS, CDS_RESOURCE, VERSION_1, "0000", NODE);
    verifyNoInteractions(ldsResourceWatcher);
    verifyResourceMetadataRequested(CDS, CDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);
    // Server failed to return subscribed resource within expected time window.
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(cdsResourceWatcher).onResourceDoesNotExist(CDS_RESOURCE);
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
    verifyResourceMetadataDoesNotExist(CDS, CDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);
  }

  @Test
  public void cdsResponseErrorHandling_allResourcesFailedUnpack() {
    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(), CDS_RESOURCE,
        cdsResourceWatcher);
    verifyResourceMetadataRequested(CDS, CDS_RESOURCE);
    call.sendResponse(CDS, ImmutableList.of(FAILING_ANY, FAILING_ANY), VERSION_1, "0000");

    // Resulting metadata unchanged because the response has no identifiable subscribed resources.
    verifyResourceMetadataRequested(CDS, CDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);
    // The response NACKed with errors indicating indices of the failed resources.
    call.verifyRequestNack(CDS, CDS_RESOURCE, "", "0000", NODE, ImmutableList.of(
        "CDS response Resource index 0 - can't decode Cluster: ",
        "CDS response Resource index 1 - can't decode Cluster: "));
    verifyNoInteractions(ldsResourceWatcher);
  }

  @Test
  public void cdsResponseErrorHandling_someResourcesFailedUnpack() {
    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(), CDS_RESOURCE,
        cdsResourceWatcher);
    verifyResourceMetadataRequested(CDS, CDS_RESOURCE);

    // Correct resource is in the middle to ensure processing continues on errors.
    List<Any> resources = ImmutableList.of(FAILING_ANY, testClusterRoundRobin, FAILING_ANY);
    call.sendResponse(CDS, resources, VERSION_1, "0000");

    // All errors recorded in the metadata of successfully unpacked subscribed resources.
    List<String> errors = ImmutableList.of(
        "CDS response Resource index 0 - can't decode Cluster: ",
        "CDS response Resource index 2 - can't decode Cluster: ");
    verifyResourceMetadataAcked(
        CDS, CDS_RESOURCE, testClusterRoundRobin, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);
    // The response is NACKed with the same error message.
    call.verifyRequestNack(CDS, CDS_RESOURCE, "", "0000", NODE, errors);
    verify(cdsResourceWatcher).onChanged(any(CdsUpdate.class));
  }

  /**
   * Tests a subscribed CDS resource transitioned to and from the invalid state.
   *
   * @see <a href="https://github.com/grpc/proposal/blob/master/A40-csds-support.md#ads-parsing-logic-update-continue-after-first-error">
   * A40-csds-support.md</a>
   */
  @Test
  public void cdsResponseErrorHandling_subscribedResourceInvalid() {
    List<String> subscribedResourceNames = ImmutableList.of("A", "B", "C");
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(),"A", cdsResourceWatcher);
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(),"B", cdsResourceWatcher);
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(),"C", cdsResourceWatcher);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    assertThat(call).isNotNull();
    verifyResourceMetadataRequested(CDS, "A");
    verifyResourceMetadataRequested(CDS, "B");
    verifyResourceMetadataRequested(CDS, "C");
    verifySubscribedResourcesMetadataSizes(0, 3, 0, 0);

    // CDS -> {A, B, C}, version 1
    ImmutableMap<String, Any> resourcesV1 = ImmutableMap.of(
        "A", Any.pack(mf.buildEdsCluster("A", "A.1", "round_robin", null, null, false, null,
            "envoy.transport_sockets.tls", null, null
        )),
        "B", Any.pack(mf.buildEdsCluster("B", "B.1", "round_robin", null, null, false, null,
            "envoy.transport_sockets.tls", null, null
        )),
        "C", Any.pack(mf.buildEdsCluster("C", "C.1", "round_robin", null, null, false, null,
            "envoy.transport_sockets.tls", null, null
        )));
    call.sendResponse(CDS, resourcesV1.values().asList(), VERSION_1, "0000");
    // {A, B, C} -> ACK, version 1
    verifyResourceMetadataAcked(CDS, "A", resourcesV1.get("A"), VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataAcked(CDS, "B", resourcesV1.get("B"), VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataAcked(CDS, "C", resourcesV1.get("C"), VERSION_1, TIME_INCREMENT);
    call.verifyRequest(CDS, subscribedResourceNames, VERSION_1, "0000", NODE);

    // CDS -> {A, B}, version 2
    // Failed to parse endpoint B
    ImmutableMap<String, Any> resourcesV2 = ImmutableMap.of(
        "A", Any.pack(mf.buildEdsCluster("A", "A.2", "round_robin", null, null, false, null,
            "envoy.transport_sockets.tls", null, null
        )),
        "B", Any.pack(mf.buildClusterInvalid("B")));
    call.sendResponse(CDS, resourcesV2.values().asList(), VERSION_2, "0001");
    // {A} -> ACK, version 2
    // {B} -> NACK, version 1, rejected version 2, rejected reason: Failed to parse B
    // {C} -> does not exist
    List<String> errorsV2 = ImmutableList.of("CDS response Cluster 'B' validation error: ");
    verifyResourceMetadataAcked(CDS, "A", resourcesV2.get("A"), VERSION_2, TIME_INCREMENT * 2);
    verifyResourceMetadataNacked(CDS, "B", resourcesV1.get("B"), VERSION_1, TIME_INCREMENT,
        VERSION_2, TIME_INCREMENT * 2, errorsV2);
    if (!ignoreResourceDeletion()) {
      verifyResourceMetadataDoesNotExist(CDS, "C");
    } else {
      // When resource deletion is disabled, {C} stays ACKed in the previous version VERSION_1.
      verifyResourceMetadataAcked(CDS, "C", resourcesV1.get("C"), VERSION_1, TIME_INCREMENT);
    }
    call.verifyRequestNack(CDS, subscribedResourceNames, VERSION_1, "0001", NODE, errorsV2);

    // CDS -> {B, C} version 3
    ImmutableMap<String, Any> resourcesV3 = ImmutableMap.of(
        "B", Any.pack(mf.buildEdsCluster("B", "B.3", "round_robin", null, null, false, null,
            "envoy.transport_sockets.tls", null, null
        )),
        "C", Any.pack(mf.buildEdsCluster("C", "C.3", "round_robin", null, null, false, null,
            "envoy.transport_sockets.tls", null, null
        )));
    call.sendResponse(CDS, resourcesV3.values().asList(), VERSION_3, "0002");
    // {A} -> does not exit
    // {B, C} -> ACK, version 3
    if (!ignoreResourceDeletion()) {
      verifyResourceMetadataDoesNotExist(CDS, "A");
    } else {
      // When resource deletion is disabled, {A} stays ACKed in the previous version VERSION_2.
      verifyResourceMetadataAcked(CDS, "A", resourcesV2.get("A"), VERSION_2, TIME_INCREMENT * 2);
    }
    verifyResourceMetadataAcked(CDS, "B", resourcesV3.get("B"), VERSION_3, TIME_INCREMENT * 3);
    verifyResourceMetadataAcked(CDS, "C", resourcesV3.get("C"), VERSION_3, TIME_INCREMENT * 3);
    call.verifyRequest(CDS, subscribedResourceNames, VERSION_3, "0002", NODE);
  }

  @Test
  public void cdsResponseErrorHandling_subscribedResourceInvalid_withEdsSubscription() {
    List<String> subscribedResourceNames = ImmutableList.of("A", "B", "C");
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(),"A", cdsResourceWatcher);
    xdsClient.watchXdsResource(XdsEndpointResource.getInstance(),"A.1", edsResourceWatcher);
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(),"B", cdsResourceWatcher);
    xdsClient.watchXdsResource(XdsEndpointResource.getInstance(),"B.1", edsResourceWatcher);
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(),"C", cdsResourceWatcher);
    xdsClient.watchXdsResource(XdsEndpointResource.getInstance(),"C.1", edsResourceWatcher);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    assertThat(call).isNotNull();
    verifyResourceMetadataRequested(CDS, "A");
    verifyResourceMetadataRequested(CDS, "B");
    verifyResourceMetadataRequested(CDS, "C");
    verifyResourceMetadataRequested(EDS, "A.1");
    verifyResourceMetadataRequested(EDS, "B.1");
    verifyResourceMetadataRequested(EDS, "C.1");
    verifySubscribedResourcesMetadataSizes(0, 3, 0, 3);

    // CDS -> {A, B, C}, version 1
    ImmutableMap<String, Any> resourcesV1 = ImmutableMap.of(
        "A", Any.pack(mf.buildEdsCluster("A", "A.1", "round_robin", null, null, false, null,
            "envoy.transport_sockets.tls", null, null
        )),
        "B", Any.pack(mf.buildEdsCluster("B", "B.1", "round_robin", null, null, false, null,
            "envoy.transport_sockets.tls", null, null
        )),
        "C", Any.pack(mf.buildEdsCluster("C", "C.1", "round_robin", null, null, false, null,
            "envoy.transport_sockets.tls", null, null
        )));
    call.sendResponse(CDS, resourcesV1.values().asList(), VERSION_1, "0000");
    // {A, B, C} -> ACK, version 1
    verifyResourceMetadataAcked(CDS, "A", resourcesV1.get("A"), VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataAcked(CDS, "B", resourcesV1.get("B"), VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataAcked(CDS, "C", resourcesV1.get("C"), VERSION_1, TIME_INCREMENT);
    call.verifyRequest(CDS, subscribedResourceNames, VERSION_1, "0000", NODE);

    // EDS -> {A.1, B.1, C.1}, version 1
    List<Message> dropOverloads = ImmutableList.of();
    List<Message> endpointsV1 = ImmutableList.of(lbEndpointHealthy);
    ImmutableMap<String, Any> resourcesV11 = ImmutableMap.of(
        "A.1", Any.pack(mf.buildClusterLoadAssignment("A.1", endpointsV1, dropOverloads)),
        "B.1", Any.pack(mf.buildClusterLoadAssignment("B.1", endpointsV1, dropOverloads)),
        "C.1", Any.pack(mf.buildClusterLoadAssignment("C.1", endpointsV1, dropOverloads)));
    call.sendResponse(EDS, resourcesV11.values().asList(), VERSION_1, "0000");
    // {A.1, B.1, C.1} -> ACK, version 1
    verifyResourceMetadataAcked(EDS, "A.1", resourcesV11.get("A.1"), VERSION_1, TIME_INCREMENT * 2);
    verifyResourceMetadataAcked(EDS, "B.1", resourcesV11.get("B.1"), VERSION_1, TIME_INCREMENT * 2);
    verifyResourceMetadataAcked(EDS, "C.1", resourcesV11.get("C.1"), VERSION_1, TIME_INCREMENT * 2);

    // CDS -> {A, B}, version 2
    // Failed to parse endpoint B
    ImmutableMap<String, Any> resourcesV2 = ImmutableMap.of(
        "A", Any.pack(mf.buildEdsCluster("A", "A.2", "round_robin", null, null, false, null,
            "envoy.transport_sockets.tls", null, null
        )),
        "B", Any.pack(mf.buildClusterInvalid("B")));
    call.sendResponse(CDS, resourcesV2.values().asList(), VERSION_2, "0001");
    // {A} -> ACK, version 2
    // {B} -> NACK, version 1, rejected version 2, rejected reason: Failed to parse B
    // {C} -> does not exist
    List<String> errorsV2 = ImmutableList.of("CDS response Cluster 'B' validation error: ");
    verifyResourceMetadataAcked(CDS, "A", resourcesV2.get("A"), VERSION_2, TIME_INCREMENT * 3);
    verifyResourceMetadataNacked(
        CDS, "B", resourcesV1.get("B"), VERSION_1, TIME_INCREMENT, VERSION_2, TIME_INCREMENT * 3,
        errorsV2);
    if (!ignoreResourceDeletion()) {
      verifyResourceMetadataDoesNotExist(CDS, "C");
    } else {
      // When resource deletion is disabled, {C} stays ACKed in the previous version VERSION_1.
      verifyResourceMetadataAcked(CDS, "C", resourcesV1.get("C"), VERSION_1, TIME_INCREMENT);
    }
    call.verifyRequestNack(CDS, subscribedResourceNames, VERSION_1, "0001", NODE, errorsV2);
    // {A.1} -> version 1
    // {B.1} -> version 1
    // {C.1} -> does not exist because {C} does not exist
    verifyResourceMetadataAcked(EDS, "A.1", resourcesV11.get("A.1"), VERSION_1, TIME_INCREMENT * 2);
    verifyResourceMetadataAcked(EDS, "B.1", resourcesV11.get("B.1"), VERSION_1, TIME_INCREMENT * 2);
    // Verify {C.1} stays in the previous version VERSION_1. {C1} deleted or not does not matter.
    verifyResourceMetadataAcked(EDS, "C.1", resourcesV11.get("C.1"), VERSION_1,
        TIME_INCREMENT * 2);
  }

  @Test
  public void cdsResourceFound() {
    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(),
        CDS_RESOURCE, cdsResourceWatcher);
    call.sendResponse(CDS, testClusterRoundRobin, VERSION_1, "0000");

    // Client sent an ACK CDS request.
    call.verifyRequest(CDS, CDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    verifyGoldenClusterRoundRobin(cdsUpdateCaptor.getValue());
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
    verifyResourceMetadataAcked(CDS, CDS_RESOURCE, testClusterRoundRobin, VERSION_1,
        TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);
  }

  @Test
  public void wrappedCdsResource() {
    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(), CDS_RESOURCE,
        cdsResourceWatcher);
    call.sendResponse(CDS, mf.buildWrappedResource(testClusterRoundRobin), VERSION_1, "0000");

    // Client sent an ACK CDS request.
    call.verifyRequest(CDS, CDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    verifyGoldenClusterRoundRobin(cdsUpdateCaptor.getValue());
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
    verifyResourceMetadataAcked(CDS, CDS_RESOURCE, testClusterRoundRobin, VERSION_1,
        TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);
  }

  @Test
  public void cdsResourceFound_leastRequestLbPolicy() {
    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(), CDS_RESOURCE,
        cdsResourceWatcher);
    Message leastRequestConfig = mf.buildLeastRequestLbConfig(3);
    Any clusterRingHash = Any.pack(
        mf.buildEdsCluster(CDS_RESOURCE, null, "least_request_experimental", null,
            leastRequestConfig, false, null, "envoy.transport_sockets.tls", null, null
        ));
    call.sendResponse(CDS, clusterRingHash, VERSION_1, "0000");

    // Client sent an ACK CDS request.
    call.verifyRequest(CDS, CDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.clusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.clusterType()).isEqualTo(ClusterType.EDS);
    assertThat(cdsUpdate.edsServiceName()).isNull();
    LbConfig lbConfig = ServiceConfigUtil.unwrapLoadBalancingConfig(cdsUpdate.lbPolicyConfig());
    assertThat(lbConfig.getPolicyName()).isEqualTo("wrr_locality_experimental");
    List<LbConfig> childConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(
        JsonUtil.getListOfObjects(lbConfig.getRawConfigValue(), "childPolicy"));
    assertThat(childConfigs.get(0).getPolicyName()).isEqualTo("least_request_experimental");
    assertThat(childConfigs.get(0).getRawConfigValue().get("choiceCount")).isEqualTo(3);
    assertThat(cdsUpdate.lrsServerInfo()).isNull();
    assertThat(cdsUpdate.maxConcurrentRequests()).isNull();
    assertThat(cdsUpdate.upstreamTlsContext()).isNull();
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
    verifyResourceMetadataAcked(CDS, CDS_RESOURCE, clusterRingHash, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);
  }

  @Test
  public void cdsResourceFound_ringHashLbPolicy() {
    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(), CDS_RESOURCE,
        cdsResourceWatcher);
    Message ringHashConfig = mf.buildRingHashLbConfig("xx_hash", 10L, 100L);
    Any clusterRingHash = Any.pack(
        mf.buildEdsCluster(CDS_RESOURCE, null, "ring_hash_experimental", ringHashConfig, null,
            false, null, "envoy.transport_sockets.tls", null, null
        ));
    call.sendResponse(CDS, clusterRingHash, VERSION_1, "0000");

    // Client sent an ACK CDS request.
    call.verifyRequest(CDS, CDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.clusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.clusterType()).isEqualTo(ClusterType.EDS);
    assertThat(cdsUpdate.edsServiceName()).isNull();
    LbConfig lbConfig = ServiceConfigUtil.unwrapLoadBalancingConfig(cdsUpdate.lbPolicyConfig());
    assertThat(lbConfig.getPolicyName()).isEqualTo("ring_hash_experimental");
    assertThat(JsonUtil.getNumberAsLong(lbConfig.getRawConfigValue(), "minRingSize")).isEqualTo(
        10L);
    assertThat(JsonUtil.getNumberAsLong(lbConfig.getRawConfigValue(), "maxRingSize")).isEqualTo(
        100L);
    assertThat(cdsUpdate.lrsServerInfo()).isNull();
    assertThat(cdsUpdate.maxConcurrentRequests()).isNull();
    assertThat(cdsUpdate.upstreamTlsContext()).isNull();
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
    verifyResourceMetadataAcked(CDS, CDS_RESOURCE, clusterRingHash, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);
  }

  @Test
  public void cdsResponseWithAggregateCluster() {
    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(), CDS_RESOURCE,
        cdsResourceWatcher);
    List<String> candidates = Arrays.asList(
        "cluster1.googleapis.com", "cluster2.googleapis.com", "cluster3.googleapis.com");
    Any clusterAggregate =
        Any.pack(mf.buildAggregateCluster(CDS_RESOURCE, "round_robin", null, null, candidates));
    call.sendResponse(CDS, clusterAggregate, VERSION_1, "0000");

    // Client sent an ACK CDS request.
    call.verifyRequest(CDS, CDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.clusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.clusterType()).isEqualTo(ClusterType.AGGREGATE);
    LbConfig lbConfig = ServiceConfigUtil.unwrapLoadBalancingConfig(cdsUpdate.lbPolicyConfig());
    assertThat(lbConfig.getPolicyName()).isEqualTo("wrr_locality_experimental");
    List<LbConfig> childConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(
        JsonUtil.getListOfObjects(lbConfig.getRawConfigValue(), "childPolicy"));
    assertThat(childConfigs.get(0).getPolicyName()).isEqualTo("round_robin");
    assertThat(cdsUpdate.prioritizedClusterNames()).containsExactlyElementsIn(candidates).inOrder();
    verifyResourceMetadataAcked(CDS, CDS_RESOURCE, clusterAggregate, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);
  }

  @Test
  public void cdsResponseWithCircuitBreakers() {
    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(), CDS_RESOURCE,
        cdsResourceWatcher);
    Any clusterCircuitBreakers = Any.pack(
        mf.buildEdsCluster(CDS_RESOURCE, null, "round_robin", null, null, false, null,
            "envoy.transport_sockets.tls", mf.buildCircuitBreakers(50, 200), null));
    call.sendResponse(CDS, clusterCircuitBreakers, VERSION_1, "0000");

    // Client sent an ACK CDS request.
    call.verifyRequest(CDS, CDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.clusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.clusterType()).isEqualTo(ClusterType.EDS);
    assertThat(cdsUpdate.edsServiceName()).isNull();
    LbConfig lbConfig = ServiceConfigUtil.unwrapLoadBalancingConfig(cdsUpdate.lbPolicyConfig());
    assertThat(lbConfig.getPolicyName()).isEqualTo("wrr_locality_experimental");
    List<LbConfig> childConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(
        JsonUtil.getListOfObjects(lbConfig.getRawConfigValue(), "childPolicy"));
    assertThat(childConfigs.get(0).getPolicyName()).isEqualTo("round_robin");
    assertThat(cdsUpdate.lrsServerInfo()).isNull();
    assertThat(cdsUpdate.maxConcurrentRequests()).isEqualTo(200L);
    assertThat(cdsUpdate.upstreamTlsContext()).isNull();
    verifyResourceMetadataAcked(CDS, CDS_RESOURCE, clusterCircuitBreakers, VERSION_1,
        TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);
  }

  /**
   * CDS response containing UpstreamTlsContext for a cluster.
   */
  @Test
  @SuppressWarnings("deprecation")
  public void cdsResponseWithUpstreamTlsContext() {
    Assume.assumeTrue(useProtocolV3());
    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(), CDS_RESOURCE,
        cdsResourceWatcher);

    // Management server sends back CDS response with UpstreamTlsContext.
    Any clusterEds =
        Any.pack(mf.buildEdsCluster(CDS_RESOURCE, "eds-cluster-foo.googleapis.com", "round_robin",
            null, null, true,
            mf.buildUpstreamTlsContext("cert-instance-name", "cert1"),
            "envoy.transport_sockets.tls", null, null));
    List<Any> clusters = ImmutableList.of(
        Any.pack(mf.buildLogicalDnsCluster("cluster-bar.googleapis.com",
            "dns-service-bar.googleapis.com", 443, "round_robin", null, null,false, null, null)),
        clusterEds,
        Any.pack(mf.buildEdsCluster("cluster-baz.googleapis.com", null, "round_robin", null, null,
            false, null, "envoy.transport_sockets.tls", null, null)));
    call.sendResponse(CDS, clusters, VERSION_1, "0000");

    // Client sent an ACK CDS request.
    call.verifyRequest(CDS, CDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(cdsResourceWatcher, times(1))
        .onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    CommonTlsContext.CertificateProviderInstance certificateProviderInstance =
        cdsUpdate.upstreamTlsContext().getCommonTlsContext().getCombinedValidationContext()
            .getValidationContextCertificateProviderInstance();
    assertThat(certificateProviderInstance.getInstanceName()).isEqualTo("cert-instance-name");
    assertThat(certificateProviderInstance.getCertificateName()).isEqualTo("cert1");
    verifyResourceMetadataAcked(CDS, CDS_RESOURCE, clusterEds, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);
  }

  /**
   * CDS response containing new UpstreamTlsContext for a cluster.
   */
  @Test
  @SuppressWarnings("deprecation")
  public void cdsResponseWithNewUpstreamTlsContext() {
    Assume.assumeTrue(useProtocolV3());
    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(), CDS_RESOURCE,
        cdsResourceWatcher);

    // Management server sends back CDS response with UpstreamTlsContext.
    Any clusterEds =
        Any.pack(mf.buildEdsCluster(CDS_RESOURCE, "eds-cluster-foo.googleapis.com", "round_robin",
            null, null,true,
            mf.buildNewUpstreamTlsContext("cert-instance-name", "cert1"),
            "envoy.transport_sockets.tls", null, null));
    List<Any> clusters = ImmutableList.of(
        Any.pack(mf.buildLogicalDnsCluster("cluster-bar.googleapis.com",
            "dns-service-bar.googleapis.com", 443, "round_robin", null, null, false, null, null)),
        clusterEds,
        Any.pack(mf.buildEdsCluster("cluster-baz.googleapis.com", null, "round_robin", null, null,
            false, null, "envoy.transport_sockets.tls", null, null)));
    call.sendResponse(CDS, clusters, VERSION_1, "0000");

    // Client sent an ACK CDS request.
    call.verifyRequest(CDS, CDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(cdsResourceWatcher, times(1)).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    CertificateProviderPluginInstance certificateProviderInstance =
        cdsUpdate.upstreamTlsContext().getCommonTlsContext().getValidationContext()
            .getCaCertificateProviderInstance();
    assertThat(certificateProviderInstance.getInstanceName()).isEqualTo("cert-instance-name");
    assertThat(certificateProviderInstance.getCertificateName()).isEqualTo("cert1");
    verifyResourceMetadataAcked(CDS, CDS_RESOURCE, clusterEds, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);
  }

  /**
   * CDS response containing bad UpstreamTlsContext for a cluster.
   */
  @Test
  public void cdsResponseErrorHandling_badUpstreamTlsContext() {
    Assume.assumeTrue(useProtocolV3());
    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(), CDS_RESOURCE,
        cdsResourceWatcher);

    // Management server sends back CDS response with UpstreamTlsContext.
    List<Any> clusters = ImmutableList.of(Any
        .pack(mf.buildEdsCluster(CDS_RESOURCE, "eds-cluster-foo.googleapis.com", "round_robin",
            null, null, true,
            mf.buildUpstreamTlsContext(null, null), "envoy.transport_sockets.tls", null, null)));
    call.sendResponse(CDS, clusters, VERSION_1, "0000");

    // The response NACKed with errors indicating indices of the failed resources.
    String errorMsg =  "CDS response Cluster 'cluster.googleapis.com' validation error: "
            + "Cluster cluster.googleapis.com: malformed UpstreamTlsContext: "
            + "io.grpc.xds.XdsClientImpl$ResourceInvalidException: "
            + "ca_certificate_provider_instance is required in upstream-tls-context";
    call.verifyRequestNack(CDS, CDS_RESOURCE, "", "0000", NODE, ImmutableList.of(errorMsg));
    verify(cdsResourceWatcher).onError(errorCaptor.capture());
    verifyStatusWithNodeId(errorCaptor.getValue(), Code.UNAVAILABLE, errorMsg);
  }

  /**
   * CDS response containing OutlierDetection for a cluster.
   */
  @Test
  @SuppressWarnings("deprecation")
  public void cdsResponseWithOutlierDetection() {
    Assume.assumeTrue(useProtocolV3());
    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(), CDS_RESOURCE,
        cdsResourceWatcher);

    OutlierDetection outlierDetectionXds = OutlierDetection.newBuilder()
        .setInterval(Durations.fromNanos(100))
        .setBaseEjectionTime(Durations.fromNanos(100))
        .setMaxEjectionTime(Durations.fromNanos(100))
        .setMaxEjectionPercent(UInt32Value.of(100))
        .setSuccessRateStdevFactor(UInt32Value.of(100))
        .setEnforcingSuccessRate(UInt32Value.of(100))
        .setSuccessRateMinimumHosts(UInt32Value.of(100))
        .setSuccessRateRequestVolume(UInt32Value.of(100))
        .setFailurePercentageThreshold(UInt32Value.of(100))
        .setEnforcingFailurePercentage(UInt32Value.of(100))
        .setFailurePercentageMinimumHosts(UInt32Value.of(100))
        .setFailurePercentageRequestVolume(UInt32Value.of(100)).build();

    // Management server sends back CDS response with UpstreamTlsContext.
    Any clusterEds =
        Any.pack(mf.buildEdsCluster(CDS_RESOURCE, "eds-cluster-foo.googleapis.com", "round_robin",
            null, null, true,
            mf.buildUpstreamTlsContext("cert-instance-name", "cert1"),
            "envoy.transport_sockets.tls", null, outlierDetectionXds));
    List<Any> clusters = ImmutableList.of(
        Any.pack(mf.buildLogicalDnsCluster("cluster-bar.googleapis.com",
            "dns-service-bar.googleapis.com", 443, "round_robin", null, null,false, null, null)),
        clusterEds,
        Any.pack(mf.buildEdsCluster("cluster-baz.googleapis.com", null, "round_robin", null, null,
            false, null, "envoy.transport_sockets.tls", null, outlierDetectionXds)));
    call.sendResponse(CDS, clusters, VERSION_1, "0000");

    // Client sent an ACK CDS request.
    call.verifyRequest(CDS, CDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(cdsResourceWatcher, times(1)).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();

    // The outlier detection config in CdsUpdate should match what we get from xDS.
    EnvoyServerProtoData.OutlierDetection outlierDetection = cdsUpdate.outlierDetection();
    assertThat(outlierDetection).isNotNull();
    assertThat(outlierDetection.intervalNanos()).isEqualTo(100);
    assertThat(outlierDetection.baseEjectionTimeNanos()).isEqualTo(100);
    assertThat(outlierDetection.maxEjectionTimeNanos()).isEqualTo(100);
    assertThat(outlierDetection.maxEjectionPercent()).isEqualTo(100);

    SuccessRateEjection successRateEjection = outlierDetection.successRateEjection();
    assertThat(successRateEjection).isNotNull();
    assertThat(successRateEjection.stdevFactor()).isEqualTo(100);
    assertThat(successRateEjection.enforcementPercentage()).isEqualTo(100);
    assertThat(successRateEjection.minimumHosts()).isEqualTo(100);
    assertThat(successRateEjection.requestVolume()).isEqualTo(100);

    FailurePercentageEjection failurePercentageEjection
        = outlierDetection.failurePercentageEjection();
    assertThat(failurePercentageEjection).isNotNull();
    assertThat(failurePercentageEjection.threshold()).isEqualTo(100);
    assertThat(failurePercentageEjection.enforcementPercentage()).isEqualTo(100);
    assertThat(failurePercentageEjection.minimumHosts()).isEqualTo(100);
    assertThat(failurePercentageEjection.requestVolume()).isEqualTo(100);

    verifyResourceMetadataAcked(CDS, CDS_RESOURCE, clusterEds, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);
  }

  /**
   * CDS response containing OutlierDetection for a cluster.
   */
  @Test
  @SuppressWarnings("deprecation")
  public void cdsResponseWithInvalidOutlierDetectionNacks() {
    Assume.assumeTrue(useProtocolV3());

    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(), CDS_RESOURCE,
        cdsResourceWatcher);

    OutlierDetection outlierDetectionXds = OutlierDetection.newBuilder()
        .setMaxEjectionPercent(UInt32Value.of(101)).build();

    // Management server sends back CDS response with UpstreamTlsContext.
    Any clusterEds =
        Any.pack(mf.buildEdsCluster(CDS_RESOURCE, "eds-cluster-foo.googleapis.com", "round_robin",
            null, null, true,
            mf.buildUpstreamTlsContext("cert-instance-name", "cert1"),
            "envoy.transport_sockets.tls", null, outlierDetectionXds));
    List<Any> clusters = ImmutableList.of(
        Any.pack(mf.buildLogicalDnsCluster("cluster-bar.googleapis.com",
            "dns-service-bar.googleapis.com", 443, "round_robin", null, null,false, null, null)),
        clusterEds,
        Any.pack(mf.buildEdsCluster("cluster-baz.googleapis.com", null, "round_robin", null, null,
            false, null, "envoy.transport_sockets.tls", null, outlierDetectionXds)));
    call.sendResponse(CDS, clusters, VERSION_1, "0000");

    String errorMsg = "CDS response Cluster 'cluster.googleapis.com' validation error: "
        + "Cluster cluster.googleapis.com: malformed outlier_detection: "
        + "io.grpc.xds.XdsClientImpl$ResourceInvalidException: outlier_detection "
        + "max_ejection_percent is > 100";
    call.verifyRequestNack(CDS, CDS_RESOURCE, "", "0000", NODE, ImmutableList.of(errorMsg));
    verify(cdsResourceWatcher).onError(errorCaptor.capture());
    verifyStatusWithNodeId(errorCaptor.getValue(), Code.UNAVAILABLE, errorMsg);
  }

  @Test(expected = ResourceInvalidException.class)
  public void validateOutlierDetection_invalidInterval() throws ResourceInvalidException {
    XdsClusterResource.validateOutlierDetection(
        OutlierDetection.newBuilder().setInterval(Duration.newBuilder().setSeconds(Long.MAX_VALUE))
            .build());
  }

  @Test(expected = ResourceInvalidException.class)
  public void validateOutlierDetection_negativeInterval() throws ResourceInvalidException {
    XdsClusterResource.validateOutlierDetection(
        OutlierDetection.newBuilder().setInterval(Duration.newBuilder().setSeconds(-1))
            .build());
  }

  @Test(expected = ResourceInvalidException.class)
  public void validateOutlierDetection_invalidBaseEjectionTime() throws ResourceInvalidException {
    XdsClusterResource.validateOutlierDetection(
        OutlierDetection.newBuilder()
            .setBaseEjectionTime(Duration.newBuilder().setSeconds(Long.MAX_VALUE))
            .build());
  }

  @Test(expected = ResourceInvalidException.class)
  public void validateOutlierDetection_negativeBaseEjectionTime() throws ResourceInvalidException {
    XdsClusterResource.validateOutlierDetection(
        OutlierDetection.newBuilder().setBaseEjectionTime(Duration.newBuilder().setSeconds(-1))
            .build());
  }

  @Test(expected = ResourceInvalidException.class)
  public void validateOutlierDetection_invalidMaxEjectionTime() throws ResourceInvalidException {
    XdsClusterResource.validateOutlierDetection(
        OutlierDetection.newBuilder()
            .setMaxEjectionTime(Duration.newBuilder().setSeconds(Long.MAX_VALUE))
            .build());
  }

  @Test(expected = ResourceInvalidException.class)
  public void validateOutlierDetection_negativeMaxEjectionTime() throws ResourceInvalidException {
    XdsClusterResource.validateOutlierDetection(
        OutlierDetection.newBuilder().setMaxEjectionTime(Duration.newBuilder().setSeconds(-1))
            .build());
  }

  @Test(expected = ResourceInvalidException.class)
  public void validateOutlierDetection_maxEjectionPercentTooHigh() throws ResourceInvalidException {
    XdsClusterResource.validateOutlierDetection(
        OutlierDetection.newBuilder().setMaxEjectionPercent(UInt32Value.of(101)).build());
  }

  @Test(expected = ResourceInvalidException.class)
  public void validateOutlierDetection_enforcingSuccessRateTooHigh()
      throws ResourceInvalidException {
    XdsClusterResource.validateOutlierDetection(
        OutlierDetection.newBuilder().setEnforcingSuccessRate(UInt32Value.of(101)).build());
  }

  @Test(expected = ResourceInvalidException.class)
  public void validateOutlierDetection_failurePercentageThresholdTooHigh()
      throws ResourceInvalidException {
    XdsClusterResource.validateOutlierDetection(
        OutlierDetection.newBuilder().setFailurePercentageThreshold(UInt32Value.of(101)).build());
  }

  @Test(expected = ResourceInvalidException.class)
  public void validateOutlierDetection_enforcingFailurePercentageTooHigh()
      throws ResourceInvalidException {
    XdsClusterResource.validateOutlierDetection(
        OutlierDetection.newBuilder().setEnforcingFailurePercentage(UInt32Value.of(101)).build());
  }

  /**
   * CDS response containing UpstreamTlsContext with bad transportSocketName for a cluster.
   */
  @Test
  public void cdsResponseErrorHandling_badTransportSocketName() {
    Assume.assumeTrue(useProtocolV3());
    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(), CDS_RESOURCE,
        cdsResourceWatcher);

    // Management server sends back CDS response with UpstreamTlsContext.
    List<Any> clusters = ImmutableList.of(Any
        .pack(mf.buildEdsCluster(CDS_RESOURCE, "eds-cluster-foo.googleapis.com", "round_robin",
            null, null, true,
            mf.buildUpstreamTlsContext("secret1", "cert1"), "envoy.transport_sockets.bad", null,
            null)));
    call.sendResponse(CDS, clusters, VERSION_1, "0000");

    // The response NACKed with errors indicating indices of the failed resources.
    String errorMsg = "CDS response Cluster 'cluster.googleapis.com' validation error: "
        + "transport-socket with name envoy.transport_sockets.bad not supported.";
    call.verifyRequestNack(CDS, CDS_RESOURCE, "", "0000", NODE, ImmutableList.of(errorMsg));
    verify(cdsResourceWatcher).onError(errorCaptor.capture());
    verifyStatusWithNodeId(errorCaptor.getValue(), Code.UNAVAILABLE, errorMsg);
  }

  @Test
  public void cdsResponseErrorHandling_xdstpWithoutEdsConfig() {
    String cdsResourceName = "xdstp://authority.xds.com/envoy.config.cluster.v3.Cluster/cluster1";

    final Any testClusterRoundRobin =
        Any.pack(mf.buildEdsCluster(cdsResourceName, null, "round_robin", null,
            null, false, null, "envoy.transport_sockets.tls", null, null
        ));
    final Any okClusterRoundRobin =
        Any.pack(mf.buildEdsCluster(cdsResourceName, "eds-service-bar.googleapis.com",
            "round_robin", null,null, false, null, "envoy.transport_sockets.tls", null, null));


    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(),
        cdsResourceName, cdsResourceWatcher);
    call.sendResponse(CDS, testClusterRoundRobin, VERSION_1, "0000");

    List<String> errors = ImmutableList.of("CDS response Cluster "
        + "\'xdstp://authority.xds.com/envoy.config.cluster.v3.Cluster/cluster1\' "
        + "validation error: EDS service_name must be set when Cluster resource has an xdstp name");
    call.verifyRequest(CDS, cdsResourceName, "", "", NODE); // get this out of the way
    call.verifyRequestNack(CDS, cdsResourceName, "", "0000", NODE, errors);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);

    call.sendResponse(CDS, okClusterRoundRobin, VERSION_1, "0001");
    call.verifyRequest(CDS, cdsResourceName, VERSION_1, "0001", NODE);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void cachedCdsResource_data() {
    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(), CDS_RESOURCE,
        cdsResourceWatcher);
    call.sendResponse(CDS, testClusterRoundRobin, VERSION_1, "0000");

    // Client sends an ACK CDS request.
    call.verifyRequest(CDS, CDS_RESOURCE, VERSION_1, "0000", NODE);

    ResourceWatcher<CdsUpdate> watcher = mock(ResourceWatcher.class);
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(), CDS_RESOURCE, watcher);
    verify(watcher).onChanged(cdsUpdateCaptor.capture());
    verifyGoldenClusterRoundRobin(cdsUpdateCaptor.getValue());
    call.verifyNoMoreRequest();
    verifyResourceMetadataAcked(CDS, CDS_RESOURCE, testClusterRoundRobin, VERSION_1,
        TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);

  }

  @Test
  @SuppressWarnings("unchecked")
  public void cachedCdsResource_absent() {
    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(), CDS_RESOURCE,
        cdsResourceWatcher);
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(cdsResourceWatcher).onResourceDoesNotExist(CDS_RESOURCE);
    ResourceWatcher<CdsUpdate> watcher = mock(ResourceWatcher.class);
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(),CDS_RESOURCE, watcher);
    verify(watcher).onResourceDoesNotExist(CDS_RESOURCE);
    call.verifyNoMoreRequest();
    verifyResourceMetadataDoesNotExist(CDS, CDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);
  }

  @Test
  public void cdsResourceUpdated() {
    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(), CDS_RESOURCE,
        cdsResourceWatcher);
    verifyResourceMetadataRequested(CDS, CDS_RESOURCE);

    // Initial CDS response.
    String dnsHostAddr = "dns-service-bar.googleapis.com";
    int dnsHostPort = 443;
    Any clusterDns =
        Any.pack(mf.buildLogicalDnsCluster(CDS_RESOURCE, dnsHostAddr, dnsHostPort, "round_robin",
            null, null, false, null, null));
    call.sendResponse(CDS, clusterDns, VERSION_1, "0000");
    call.verifyRequest(CDS, CDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.clusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.clusterType()).isEqualTo(ClusterType.LOGICAL_DNS);
    assertThat(cdsUpdate.dnsHostName()).isEqualTo(dnsHostAddr + ":" + dnsHostPort);
    LbConfig lbConfig = ServiceConfigUtil.unwrapLoadBalancingConfig(cdsUpdate.lbPolicyConfig());
    assertThat(lbConfig.getPolicyName()).isEqualTo("wrr_locality_experimental");
    List<LbConfig> childConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(
        JsonUtil.getListOfObjects(lbConfig.getRawConfigValue(), "childPolicy"));
    assertThat(childConfigs.get(0).getPolicyName()).isEqualTo("round_robin");
    assertThat(cdsUpdate.lrsServerInfo()).isNull();
    assertThat(cdsUpdate.maxConcurrentRequests()).isNull();
    assertThat(cdsUpdate.upstreamTlsContext()).isNull();
    verifyResourceMetadataAcked(CDS, CDS_RESOURCE, clusterDns, VERSION_1, TIME_INCREMENT);

    // Updated CDS response.
    String edsService = "eds-service-bar.googleapis.com";
    Any clusterEds = Any.pack(
        mf.buildEdsCluster(CDS_RESOURCE, edsService, "round_robin", null, null, true, null,
            "envoy.transport_sockets.tls", null, null
        ));
    call.sendResponse(CDS, clusterEds, VERSION_2, "0001");
    call.verifyRequest(CDS, CDS_RESOURCE, VERSION_2, "0001", NODE);
    verify(cdsResourceWatcher, times(2)).onChanged(cdsUpdateCaptor.capture());
    cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.clusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.clusterType()).isEqualTo(ClusterType.EDS);
    assertThat(cdsUpdate.edsServiceName()).isEqualTo(edsService);
    lbConfig = ServiceConfigUtil.unwrapLoadBalancingConfig(cdsUpdate.lbPolicyConfig());
    assertThat(lbConfig.getPolicyName()).isEqualTo("wrr_locality_experimental");
    childConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(
        JsonUtil.getListOfObjects(lbConfig.getRawConfigValue(), "childPolicy"));
    assertThat(childConfigs.get(0).getPolicyName()).isEqualTo("round_robin");
    assertThat(cdsUpdate.lrsServerInfo()).isEqualTo(xdsServerInfo);
    assertThat(cdsUpdate.maxConcurrentRequests()).isNull();
    assertThat(cdsUpdate.upstreamTlsContext()).isNull();
    verifyResourceMetadataAcked(CDS, CDS_RESOURCE, clusterEds, VERSION_2, TIME_INCREMENT * 2);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);
  }

  // Assures that CDS updates identical to the current config are ignored.
  @Test
  public void cdsResourceUpdatedWithDuplicate() {
    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(), CDS_RESOURCE,
        cdsResourceWatcher);

    String edsService = "eds-service-bar.googleapis.com";
    String transportSocketName = "envoy.transport_sockets.tls";
    Any roundRobinConfig = Any.pack(
        mf.buildEdsCluster(CDS_RESOURCE, edsService, "round_robin", null, null, true, null,
            transportSocketName, null, null
        ));
    Any ringHashConfig = Any.pack(
        mf.buildEdsCluster(CDS_RESOURCE, edsService, "ring_hash_experimental",
            mf.buildRingHashLbConfig("xx_hash", 1, 2), null, true, null,
            transportSocketName, null, null
        ));
    Any leastRequestConfig = Any.pack(
        mf.buildEdsCluster(CDS_RESOURCE, edsService, "least_request_experimental",
            null, mf.buildLeastRequestLbConfig(2), true, null,
            transportSocketName, null, null
        ));

    // Configure with round robin, the update should be sent to the watcher.
    call.sendResponse(CDS, roundRobinConfig, VERSION_2, "0001");
    verify(cdsResourceWatcher, times(1)).onChanged(isA(CdsUpdate.class));

    // Second update is identical, watcher should not get an additional update.
    call.sendResponse(CDS, roundRobinConfig, VERSION_2, "0002");
    verify(cdsResourceWatcher, times(1)).onChanged(isA(CdsUpdate.class));

    // Now we switch to ring hash so the watcher should be notified.
    call.sendResponse(CDS, ringHashConfig, VERSION_2, "0003");
    verify(cdsResourceWatcher, times(2)).onChanged(isA(CdsUpdate.class));

    // Second update to ring hash should not result in watcher being notified.
    call.sendResponse(CDS, ringHashConfig, VERSION_2, "0004");
    verify(cdsResourceWatcher, times(2)).onChanged(isA(CdsUpdate.class));

    // Now we switch to least request so the watcher should be notified.
    call.sendResponse(CDS, leastRequestConfig, VERSION_2, "0005");
    verify(cdsResourceWatcher, times(3)).onChanged(isA(CdsUpdate.class));

    // Second update to least request should not result in watcher being notified.
    call.sendResponse(CDS, leastRequestConfig, VERSION_2, "0006");
    verify(cdsResourceWatcher, times(3)).onChanged(isA(CdsUpdate.class));
  }

  @Test
  public void cdsResourceDeleted() {
    Assume.assumeFalse(ignoreResourceDeletion());

    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(), CDS_RESOURCE,
        cdsResourceWatcher);
    verifyResourceMetadataRequested(CDS, CDS_RESOURCE);

    // Initial CDS response.
    call.sendResponse(CDS, testClusterRoundRobin, VERSION_1, "0000");
    call.verifyRequest(CDS, CDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    verifyGoldenClusterRoundRobin(cdsUpdateCaptor.getValue());
    verifyResourceMetadataAcked(CDS, CDS_RESOURCE, testClusterRoundRobin, VERSION_1,
        TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);

    // Empty CDS response deletes the cluster.
    call.sendResponse(CDS, Collections.<Any>emptyList(), VERSION_2, "0001");
    call.verifyRequest(CDS, CDS_RESOURCE, VERSION_2, "0001", NODE);
    verify(cdsResourceWatcher).onResourceDoesNotExist(CDS_RESOURCE);
    verifyResourceMetadataDoesNotExist(CDS, CDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);
  }

  /**
   * When ignore_resource_deletion server feature is on, xDS client should keep the deleted cluster
   * on empty response, and resume the normal work when CDS contains the cluster again.
   * */
  @Test
  public void cdsResourceDeleted_ignoreResourceDeletion() {
    Assume.assumeTrue(ignoreResourceDeletion());

    DiscoveryRpcCall call = startResourceWatcher(XdsClusterResource.getInstance(), CDS_RESOURCE,
        cdsResourceWatcher);
    verifyResourceMetadataRequested(CDS, CDS_RESOURCE);

    // Initial CDS response.
    call.sendResponse(CDS, testClusterRoundRobin, VERSION_1, "0000");
    call.verifyRequest(CDS, CDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    verifyGoldenClusterRoundRobin(cdsUpdateCaptor.getValue());
    verifyResourceMetadataAcked(CDS, CDS_RESOURCE, testClusterRoundRobin, VERSION_1,
        TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);

    // Empty LDS response does not delete the cluster.
    call.sendResponse(CDS, Collections.emptyList(), VERSION_2, "0001");
    call.verifyRequest(CDS, CDS_RESOURCE, VERSION_2, "0001", NODE);

    // The resource is still ACKED at VERSION_1 (no changes).
    verifyResourceMetadataAcked(CDS, CDS_RESOURCE, testClusterRoundRobin, VERSION_1,
        TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);
    // onResourceDoesNotExist must not be called.
    verify(ldsResourceWatcher, never()).onResourceDoesNotExist(CDS_RESOURCE);

    // Next update is correct, and contains the cluster again.
    call.sendResponse(CDS, testClusterRoundRobin, VERSION_3, "0003");
    call.verifyRequest(CDS, CDS_RESOURCE, VERSION_3, "0003", NODE);
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    verifyGoldenClusterRoundRobin(cdsUpdateCaptor.getValue());
    verifyResourceMetadataAcked(CDS, CDS_RESOURCE, testClusterRoundRobin, VERSION_3,
        TIME_INCREMENT * 3);
    verifySubscribedResourcesMetadataSizes(0, 1, 0, 0);
    verifyNoMoreInteractions(ldsResourceWatcher);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void multipleCdsWatchers() {
    String cdsResourceTwo = "cluster-bar.googleapis.com";
    ResourceWatcher<CdsUpdate> watcher1 = mock(ResourceWatcher.class);
    ResourceWatcher<CdsUpdate> watcher2 = mock(ResourceWatcher.class);
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(),CDS_RESOURCE, cdsResourceWatcher);
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(),cdsResourceTwo, watcher1);
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(),cdsResourceTwo, watcher2);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    call.verifyRequest(CDS, Arrays.asList(CDS_RESOURCE, cdsResourceTwo), "", "", NODE);
    verifyResourceMetadataRequested(CDS, CDS_RESOURCE);
    verifyResourceMetadataRequested(CDS, cdsResourceTwo);
    verifySubscribedResourcesMetadataSizes(0, 2, 0, 0);

    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(cdsResourceWatcher).onResourceDoesNotExist(CDS_RESOURCE);
    verify(watcher1).onResourceDoesNotExist(cdsResourceTwo);
    verify(watcher2).onResourceDoesNotExist(cdsResourceTwo);
    verifyResourceMetadataDoesNotExist(CDS, CDS_RESOURCE);
    verifyResourceMetadataDoesNotExist(CDS, cdsResourceTwo);
    verifySubscribedResourcesMetadataSizes(0, 2, 0, 0);

    String dnsHostAddr = "dns-service-bar.googleapis.com";
    int dnsHostPort = 443;
    String edsService = "eds-service-bar.googleapis.com";
    List<Any> clusters = ImmutableList.of(
        Any.pack(mf.buildLogicalDnsCluster(CDS_RESOURCE, dnsHostAddr, dnsHostPort, "round_robin",
            null, null, false, null, null)),
        Any.pack(mf.buildEdsCluster(cdsResourceTwo, edsService, "round_robin", null, null, true,
            null, "envoy.transport_sockets.tls", null, null)));
    call.sendResponse(CDS, clusters, VERSION_1, "0000");
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.clusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.clusterType()).isEqualTo(ClusterType.LOGICAL_DNS);
    assertThat(cdsUpdate.dnsHostName()).isEqualTo(dnsHostAddr + ":" + dnsHostPort);
    LbConfig lbConfig = ServiceConfigUtil.unwrapLoadBalancingConfig(cdsUpdate.lbPolicyConfig());
    assertThat(lbConfig.getPolicyName()).isEqualTo("wrr_locality_experimental");
    List<LbConfig> childConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(
        JsonUtil.getListOfObjects(lbConfig.getRawConfigValue(), "childPolicy"));
    assertThat(childConfigs.get(0).getPolicyName()).isEqualTo("round_robin");
    assertThat(cdsUpdate.lrsServerInfo()).isNull();
    assertThat(cdsUpdate.maxConcurrentRequests()).isNull();
    assertThat(cdsUpdate.upstreamTlsContext()).isNull();
    verify(watcher1).onChanged(cdsUpdateCaptor.capture());
    cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.clusterName()).isEqualTo(cdsResourceTwo);
    assertThat(cdsUpdate.clusterType()).isEqualTo(ClusterType.EDS);
    assertThat(cdsUpdate.edsServiceName()).isEqualTo(edsService);
    lbConfig = ServiceConfigUtil.unwrapLoadBalancingConfig(cdsUpdate.lbPolicyConfig());
    assertThat(lbConfig.getPolicyName()).isEqualTo("wrr_locality_experimental");
    childConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(
        JsonUtil.getListOfObjects(lbConfig.getRawConfigValue(), "childPolicy"));
    assertThat(childConfigs.get(0).getPolicyName()).isEqualTo("round_robin");
    assertThat(cdsUpdate.lrsServerInfo()).isEqualTo(xdsServerInfo);
    assertThat(cdsUpdate.maxConcurrentRequests()).isNull();
    assertThat(cdsUpdate.upstreamTlsContext()).isNull();
    verify(watcher2).onChanged(cdsUpdateCaptor.capture());
    cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.clusterName()).isEqualTo(cdsResourceTwo);
    assertThat(cdsUpdate.clusterType()).isEqualTo(ClusterType.EDS);
    assertThat(cdsUpdate.edsServiceName()).isEqualTo(edsService);
    lbConfig = ServiceConfigUtil.unwrapLoadBalancingConfig(cdsUpdate.lbPolicyConfig());
    assertThat(lbConfig.getPolicyName()).isEqualTo("wrr_locality_experimental");
    childConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(
        JsonUtil.getListOfObjects(lbConfig.getRawConfigValue(), "childPolicy"));
    assertThat(childConfigs.get(0).getPolicyName()).isEqualTo("round_robin");
    assertThat(cdsUpdate.lrsServerInfo()).isEqualTo(xdsServerInfo);
    assertThat(cdsUpdate.maxConcurrentRequests()).isNull();
    assertThat(cdsUpdate.upstreamTlsContext()).isNull();
    // Metadata of both clusters is stored.
    verifyResourceMetadataAcked(CDS, CDS_RESOURCE, clusters.get(0), VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataAcked(CDS, cdsResourceTwo, clusters.get(1), VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 2, 0, 0);
  }

  @Test
  public void edsResourceNotFound() {
    DiscoveryRpcCall call = startResourceWatcher(XdsEndpointResource.getInstance(), EDS_RESOURCE,
        edsResourceWatcher);
    Any clusterLoadAssignment = Any.pack(mf.buildClusterLoadAssignment(
        "cluster-bar.googleapis.com",
        ImmutableList.of(lbEndpointHealthy),
        ImmutableList.<Message>of()));
    call.sendResponse(EDS, clusterLoadAssignment, VERSION_1, "0000");

    // Client sent an ACK EDS request.
    call.verifyRequest(EDS, EDS_RESOURCE, VERSION_1, "0000", NODE);
    verifyNoInteractions(edsResourceWatcher);
    verifyResourceMetadataRequested(EDS, EDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(0, 0, 0, 1);
    // Server failed to return subscribed resource within expected time window.
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(edsResourceWatcher).onResourceDoesNotExist(EDS_RESOURCE);
    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
    verifyResourceMetadataDoesNotExist(EDS, EDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(0, 0, 0, 1);
  }

  @Test
  public void edsResponseErrorHandling_allResourcesFailedUnpack() {
    DiscoveryRpcCall call = startResourceWatcher(XdsEndpointResource.getInstance(), EDS_RESOURCE,
        edsResourceWatcher);
    verifyResourceMetadataRequested(EDS, EDS_RESOURCE);
    call.sendResponse(EDS, ImmutableList.of(FAILING_ANY, FAILING_ANY), VERSION_1, "0000");

    // Resulting metadata unchanged because the response has no identifiable subscribed resources.
    verifyResourceMetadataRequested(EDS, EDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(0, 0, 0, 1);
    // The response NACKed with errors indicating indices of the failed resources.
    call.verifyRequestNack(EDS, EDS_RESOURCE, "", "0000", NODE, ImmutableList.of(
        "EDS response Resource index 0 - can't decode ClusterLoadAssignment: ",
        "EDS response Resource index 1 - can't decode ClusterLoadAssignment: "));
    verifyNoInteractions(edsResourceWatcher);
  }

  @Test
  public void edsResponseErrorHandling_someResourcesFailedUnpack() {
    DiscoveryRpcCall call = startResourceWatcher(XdsEndpointResource.getInstance(), EDS_RESOURCE,
        edsResourceWatcher);
    verifyResourceMetadataRequested(EDS, EDS_RESOURCE);

    // Correct resource is in the middle to ensure processing continues on errors.
    List<Any> resources = ImmutableList.of(FAILING_ANY, testClusterLoadAssignment, FAILING_ANY);
    call.sendResponse(EDS, resources, VERSION_1, "0000");

    // All errors recorded in the metadata of successfully unpacked subscribed resources.
    List<String> errors = ImmutableList.of(
        "EDS response Resource index 0 - can't decode ClusterLoadAssignment: ",
        "EDS response Resource index 2 - can't decode ClusterLoadAssignment: ");
    verifyResourceMetadataAcked(
        EDS, EDS_RESOURCE, testClusterLoadAssignment, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 0, 0, 1);
    // The response is NACKed with the same error message.
    call.verifyRequestNack(EDS, EDS_RESOURCE, "", "0000", NODE, errors);
    verify(edsResourceWatcher).onChanged(edsUpdateCaptor.capture());
    EdsUpdate edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.clusterName).isEqualTo(EDS_RESOURCE);
  }

  /**
   * Tests a subscribed EDS resource transitioned to and from the invalid state.
   *
   * @see <a href="https://github.com/grpc/proposal/blob/master/A40-csds-support.md#ads-parsing-logic-update-continue-after-first-error">
   * A40-csds-support.md</a>
   */
  @Test
  public void edsResponseErrorHandling_subscribedResourceInvalid() {
    List<String> subscribedResourceNames = ImmutableList.of("A", "B", "C");
    xdsClient.watchXdsResource(XdsEndpointResource.getInstance(),"A", edsResourceWatcher);
    xdsClient.watchXdsResource(XdsEndpointResource.getInstance(),"B", edsResourceWatcher);
    xdsClient.watchXdsResource(XdsEndpointResource.getInstance(),"C", edsResourceWatcher);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    assertThat(call).isNotNull();
    verifyResourceMetadataRequested(EDS, "A");
    verifyResourceMetadataRequested(EDS, "B");
    verifyResourceMetadataRequested(EDS, "C");
    verifySubscribedResourcesMetadataSizes(0, 0, 0, 3);

    // EDS -> {A, B, C}, version 1
    List<Message> dropOverloads = ImmutableList.of(mf.buildDropOverload("lb", 200));
    List<Message> endpointsV1 = ImmutableList.of(lbEndpointHealthy);
    ImmutableMap<String, Any> resourcesV1 = ImmutableMap.of(
        "A", Any.pack(mf.buildClusterLoadAssignment("A", endpointsV1, dropOverloads)),
        "B", Any.pack(mf.buildClusterLoadAssignment("B", endpointsV1, dropOverloads)),
        "C", Any.pack(mf.buildClusterLoadAssignment("C", endpointsV1, dropOverloads)));
    call.sendResponse(EDS, resourcesV1.values().asList(), VERSION_1, "0000");
    // {A, B, C} -> ACK, version 1
    verifyResourceMetadataAcked(EDS, "A", resourcesV1.get("A"), VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataAcked(EDS, "B", resourcesV1.get("B"), VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataAcked(EDS, "C", resourcesV1.get("C"), VERSION_1, TIME_INCREMENT);
    call.verifyRequest(EDS, subscribedResourceNames, VERSION_1, "0000", NODE);

    // EDS -> {A, B}, version 2
    // Failed to parse endpoint B
    List<Message> endpointsV2 = ImmutableList.of(lbEndpointHealthy, lbEndpointEmpty);
    ImmutableMap<String, Any> resourcesV2 = ImmutableMap.of(
        "A", Any.pack(mf.buildClusterLoadAssignment("A", endpointsV2, dropOverloads)),
        "B", Any.pack(mf.buildClusterLoadAssignmentInvalid("B")));
    call.sendResponse(EDS, resourcesV2.values().asList(), VERSION_2, "0001");
    // {A} -> ACK, version 2
    // {B} -> NACK, version 1, rejected version 2, rejected reason: Failed to parse B
    // {C} -> ACK, version 1
    List<String> errorsV2 =
        ImmutableList.of("EDS response ClusterLoadAssignment 'B' validation error: ");
    verifyResourceMetadataAcked(EDS, "A", resourcesV2.get("A"), VERSION_2, TIME_INCREMENT * 2);
    verifyResourceMetadataNacked(EDS, "B", resourcesV1.get("B"), VERSION_1, TIME_INCREMENT,
        VERSION_2, TIME_INCREMENT * 2, errorsV2);
    verifyResourceMetadataAcked(EDS, "C", resourcesV1.get("C"), VERSION_1, TIME_INCREMENT);
    call.verifyRequestNack(EDS, subscribedResourceNames, VERSION_1, "0001", NODE, errorsV2);

    // EDS -> {B, C} version 3
    List<Message> endpointsV3 =
        ImmutableList.of(lbEndpointHealthy, lbEndpointEmpty, lbEndpointZeroWeight);
    ImmutableMap<String, Any> resourcesV3 = ImmutableMap.of(
        "B", Any.pack(mf.buildClusterLoadAssignment("B", endpointsV3, dropOverloads)),
        "C", Any.pack(mf.buildClusterLoadAssignment("C", endpointsV3, dropOverloads)));
    call.sendResponse(EDS, resourcesV3.values().asList(), VERSION_3, "0002");
    // {A} -> ACK, version 2
    // {B, C} -> ACK, version 3
    verifyResourceMetadataAcked(EDS, "A", resourcesV2.get("A"), VERSION_2, TIME_INCREMENT * 2);
    verifyResourceMetadataAcked(EDS, "B", resourcesV3.get("B"), VERSION_3, TIME_INCREMENT * 3);
    verifyResourceMetadataAcked(EDS, "C", resourcesV3.get("C"), VERSION_3, TIME_INCREMENT * 3);
    call.verifyRequest(EDS, subscribedResourceNames, VERSION_3, "0002", NODE);
    verifySubscribedResourcesMetadataSizes(0, 0, 0, 3);
  }

  @Test
  public void edsResourceFound() {
    DiscoveryRpcCall call = startResourceWatcher(XdsEndpointResource.getInstance(), EDS_RESOURCE,
        edsResourceWatcher);
    call.sendResponse(EDS, testClusterLoadAssignment, VERSION_1, "0000");

    // Client sent an ACK EDS request.
    call.verifyRequest(EDS, EDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(edsResourceWatcher).onChanged(edsUpdateCaptor.capture());
    validateGoldenClusterLoadAssignment(edsUpdateCaptor.getValue());
    verifyResourceMetadataAcked(EDS, EDS_RESOURCE, testClusterLoadAssignment, VERSION_1,
        TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 0, 0, 1);
  }

  @Test
  public void wrappedEdsResourceFound() {
    DiscoveryRpcCall call = startResourceWatcher(XdsEndpointResource.getInstance(), EDS_RESOURCE,
        edsResourceWatcher);
    call.sendResponse(EDS, mf.buildWrappedResource(testClusterLoadAssignment), VERSION_1, "0000");

    // Client sent an ACK EDS request.
    call.verifyRequest(EDS, EDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(edsResourceWatcher).onChanged(edsUpdateCaptor.capture());
    validateGoldenClusterLoadAssignment(edsUpdateCaptor.getValue());
    verifyResourceMetadataAcked(EDS, EDS_RESOURCE, testClusterLoadAssignment, VERSION_1,
        TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 0, 0, 1);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void cachedEdsResource_data() {
    DiscoveryRpcCall call = startResourceWatcher(XdsEndpointResource.getInstance(), EDS_RESOURCE,
        edsResourceWatcher);
    call.sendResponse(EDS, testClusterLoadAssignment, VERSION_1, "0000");

    // Client sent an ACK EDS request.
    call.verifyRequest(EDS, EDS_RESOURCE, VERSION_1, "0000", NODE);
    // Add another watcher.
    ResourceWatcher<EdsUpdate> watcher = mock(ResourceWatcher.class);
    xdsClient.watchXdsResource(XdsEndpointResource.getInstance(),EDS_RESOURCE, watcher);
    verify(watcher).onChanged(edsUpdateCaptor.capture());
    validateGoldenClusterLoadAssignment(edsUpdateCaptor.getValue());
    call.verifyNoMoreRequest();
    verifyResourceMetadataAcked(EDS, EDS_RESOURCE, testClusterLoadAssignment, VERSION_1,
        TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 0, 0, 1);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void cachedEdsResource_absent() {
    DiscoveryRpcCall call = startResourceWatcher(XdsEndpointResource.getInstance(), EDS_RESOURCE,
        edsResourceWatcher);
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(edsResourceWatcher).onResourceDoesNotExist(EDS_RESOURCE);
    ResourceWatcher<EdsUpdate> watcher = mock(ResourceWatcher.class);
    xdsClient.watchXdsResource(XdsEndpointResource.getInstance(),EDS_RESOURCE, watcher);
    verify(watcher).onResourceDoesNotExist(EDS_RESOURCE);
    call.verifyNoMoreRequest();
    verifyResourceMetadataDoesNotExist(EDS, EDS_RESOURCE);
    verifySubscribedResourcesMetadataSizes(0, 0, 0, 1);
  }

  @Test
  public void edsResourceUpdated() {
    DiscoveryRpcCall call = startResourceWatcher(XdsEndpointResource.getInstance(), EDS_RESOURCE,
        edsResourceWatcher);
    verifyResourceMetadataRequested(EDS, EDS_RESOURCE);

    // Initial EDS response.
    call.sendResponse(EDS, testClusterLoadAssignment, VERSION_1, "0000");
    call.verifyRequest(EDS, EDS_RESOURCE, VERSION_1, "0000", NODE);
    verify(edsResourceWatcher).onChanged(edsUpdateCaptor.capture());
    EdsUpdate edsUpdate = edsUpdateCaptor.getValue();
    validateGoldenClusterLoadAssignment(edsUpdate);
    verifyResourceMetadataAcked(EDS, EDS_RESOURCE, testClusterLoadAssignment, VERSION_1,
        TIME_INCREMENT);

    // Updated EDS response.
    Any updatedClusterLoadAssignment = Any.pack(mf.buildClusterLoadAssignment(EDS_RESOURCE,
        ImmutableList.of(mf.buildLocalityLbEndpoints("region2", "zone2", "subzone2",
            mf.buildLbEndpoint("172.44.2.2", 8000, "unknown", 3), 2, 0)),
        ImmutableList.<Message>of()));
    call.sendResponse(EDS, updatedClusterLoadAssignment, VERSION_2, "0001");

    verify(edsResourceWatcher, times(2)).onChanged(edsUpdateCaptor.capture());
    edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.clusterName).isEqualTo(EDS_RESOURCE);
    assertThat(edsUpdate.dropPolicies).isEmpty();
    assertThat(edsUpdate.localityLbEndpointsMap)
        .containsExactly(
            Locality.create("region2", "zone2", "subzone2"),
            LocalityLbEndpoints.create(
                ImmutableList.of(
                    LbEndpoint.create("172.44.2.2", 8000, 3, true)), 2, 0));
    verifyResourceMetadataAcked(EDS, EDS_RESOURCE, updatedClusterLoadAssignment, VERSION_2,
        TIME_INCREMENT * 2);
    verifySubscribedResourcesMetadataSizes(0, 0, 0, 1);
  }

  @Test
  public void edsDuplicateLocalityInTheSamePriority() {
    DiscoveryRpcCall call = startResourceWatcher(XdsEndpointResource.getInstance(), EDS_RESOURCE,
        edsResourceWatcher);
    verifyResourceMetadataRequested(EDS, EDS_RESOURCE);

    // Updated EDS response.
    Any updatedClusterLoadAssignment = Any.pack(mf.buildClusterLoadAssignment(EDS_RESOURCE,
        ImmutableList.of(
            mf.buildLocalityLbEndpoints("region2", "zone2", "subzone2",
              mf.buildLbEndpoint("172.44.2.2", 8000, "unknown", 3), 2, 1),
            mf.buildLocalityLbEndpoints("region2", "zone2", "subzone2",
                mf.buildLbEndpoint("172.44.2.3", 8080, "healthy", 10), 2, 1)
            ),
        ImmutableList.<Message>of()));
    call.sendResponse(EDS, updatedClusterLoadAssignment, "0", "0001");
    String errorMsg = "EDS response ClusterLoadAssignment"
        + " \'cluster-load-assignment.googleapis.com\' "
        + "validation error: ClusterLoadAssignment has duplicate "
        + "locality:Locality{region=region2, zone=zone2, subZone=subzone2} for priority:1";
    call.verifyRequestNack(EDS, EDS_RESOURCE, "", "0001", NODE, ImmutableList.of(
        errorMsg));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void edsResourceDeletedByCds() {
    String resource = "backend-service.googleapis.com";
    ResourceWatcher<CdsUpdate> cdsWatcher = mock(ResourceWatcher.class);
    ResourceWatcher<EdsUpdate> edsWatcher = mock(ResourceWatcher.class);
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(),resource, cdsWatcher);
    xdsClient.watchXdsResource(XdsEndpointResource.getInstance(),resource, edsWatcher);
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(),CDS_RESOURCE, cdsResourceWatcher);
    xdsClient.watchXdsResource(XdsEndpointResource.getInstance(),EDS_RESOURCE, edsResourceWatcher);
    verifyResourceMetadataRequested(CDS, CDS_RESOURCE);
    verifyResourceMetadataRequested(CDS, resource);
    verifyResourceMetadataRequested(EDS, EDS_RESOURCE);
    verifyResourceMetadataRequested(EDS, resource);
    verifySubscribedResourcesMetadataSizes(0, 2, 0, 2);

    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    List<Any> clusters = ImmutableList.of(
        Any.pack(mf.buildEdsCluster(resource, null, "round_robin", null, null, true, null,
            "envoy.transport_sockets.tls", null, null
        )),
        Any.pack(mf.buildEdsCluster(CDS_RESOURCE, EDS_RESOURCE, "round_robin", null, null, false,
            null, "envoy.transport_sockets.tls", null, null)));
    call.sendResponse(CDS, clusters, VERSION_1, "0000");
    verify(cdsWatcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.edsServiceName()).isEqualTo(null);
    assertThat(cdsUpdate.lrsServerInfo()).isEqualTo(xdsServerInfo);
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.edsServiceName()).isEqualTo(EDS_RESOURCE);
    assertThat(cdsUpdate.lrsServerInfo()).isNull();
    verifyResourceMetadataAcked(CDS, resource, clusters.get(0), VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataAcked(CDS, CDS_RESOURCE, clusters.get(1), VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataRequested(EDS, EDS_RESOURCE);
    verifyResourceMetadataRequested(EDS, resource);

    List<Any> clusterLoadAssignments =
        ImmutableList.of(
            Any.pack(
                mf.buildClusterLoadAssignment(EDS_RESOURCE,
                    ImmutableList.of(lbEndpointHealthy),
                    ImmutableList.of(
                        mf.buildDropOverload("lb", 200),
                        mf.buildDropOverload("throttle", 1000)))),
            Any.pack(
                mf.buildClusterLoadAssignment(resource,
                    ImmutableList.of(
                        mf.buildLocalityLbEndpoints("region2", "zone2", "subzone2",
                            mf.buildLbEndpoint("192.168.0.2", 9090, "healthy", 3), 1, 0)),
                    ImmutableList.of(mf.buildDropOverload("lb", 100)))));
    call.sendResponse(EDS, clusterLoadAssignments, VERSION_1, "0000");
    verify(edsWatcher).onChanged(edsUpdateCaptor.capture());
    assertThat(edsUpdateCaptor.getValue().clusterName).isEqualTo(resource);
    verify(edsResourceWatcher).onChanged(edsUpdateCaptor.capture());
    assertThat(edsUpdateCaptor.getValue().clusterName).isEqualTo(EDS_RESOURCE);

    verifyResourceMetadataAcked(
        EDS, EDS_RESOURCE, clusterLoadAssignments.get(0), VERSION_1, TIME_INCREMENT * 2);
    verifyResourceMetadataAcked(
        EDS, resource, clusterLoadAssignments.get(1), VERSION_1, TIME_INCREMENT * 2);
    // CDS not changed.
    verifyResourceMetadataAcked(CDS, resource, clusters.get(0), VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataAcked(CDS, CDS_RESOURCE, clusters.get(1), VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 2, 0, 2);

    clusters = ImmutableList.of(
        Any.pack(mf.buildEdsCluster(resource, null, "round_robin", null, null, true, null,
            "envoy.transport_sockets.tls", null, null)),  // no change
        Any.pack(mf.buildEdsCluster(CDS_RESOURCE, null, "round_robin", null, null, false, null,
            "envoy.transport_sockets.tls", null, null
        )));
    call.sendResponse(CDS, clusters, VERSION_2, "0001");
    verify(cdsResourceWatcher, times(2)).onChanged(cdsUpdateCaptor.capture());
    assertThat(cdsUpdateCaptor.getValue().edsServiceName()).isNull();
    // Note that the endpoint must be deleted even if the ignore_resource_deletion feature.
    // This happens because the cluster CDS_RESOURCE is getting replaced, and not deleted.
    verify(edsResourceWatcher, never()).onResourceDoesNotExist(EDS_RESOURCE);
    verify(edsResourceWatcher, never()).onResourceDoesNotExist(resource);
    verifyNoMoreInteractions(cdsWatcher, edsWatcher);
    verifyResourceMetadataAcked(
        EDS, EDS_RESOURCE, clusterLoadAssignments.get(0), VERSION_1, TIME_INCREMENT * 2);
    verifyResourceMetadataAcked(
        EDS, resource, clusterLoadAssignments.get(1), VERSION_1, TIME_INCREMENT * 2);  // no change
    verifyResourceMetadataAcked(CDS, resource, clusters.get(0), VERSION_2, TIME_INCREMENT * 3);
    verifyResourceMetadataAcked(CDS, CDS_RESOURCE, clusters.get(1), VERSION_2, TIME_INCREMENT * 3);
    verifySubscribedResourcesMetadataSizes(0, 2, 0, 2);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void multipleEdsWatchers() {
    String edsResourceTwo = "cluster-load-assignment-bar.googleapis.com";
    ResourceWatcher<EdsUpdate> watcher1 = mock(ResourceWatcher.class);
    ResourceWatcher<EdsUpdate> watcher2 = mock(ResourceWatcher.class);
    xdsClient.watchXdsResource(XdsEndpointResource.getInstance(),EDS_RESOURCE, edsResourceWatcher);
    xdsClient.watchXdsResource(XdsEndpointResource.getInstance(),edsResourceTwo, watcher1);
    xdsClient.watchXdsResource(XdsEndpointResource.getInstance(),edsResourceTwo, watcher2);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    call.verifyRequest(EDS, Arrays.asList(EDS_RESOURCE, edsResourceTwo), "", "", NODE);
    verifyResourceMetadataRequested(EDS, EDS_RESOURCE);
    verifyResourceMetadataRequested(EDS, edsResourceTwo);
    verifySubscribedResourcesMetadataSizes(0, 0, 0, 2);

    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(edsResourceWatcher).onResourceDoesNotExist(EDS_RESOURCE);
    verify(watcher1).onResourceDoesNotExist(edsResourceTwo);
    verify(watcher2).onResourceDoesNotExist(edsResourceTwo);
    verifyResourceMetadataDoesNotExist(EDS, EDS_RESOURCE);
    verifyResourceMetadataDoesNotExist(EDS, edsResourceTwo);
    verifySubscribedResourcesMetadataSizes(0, 0, 0, 2);

    call.sendResponse(EDS, testClusterLoadAssignment, VERSION_1, "0000");
    verify(edsResourceWatcher).onChanged(edsUpdateCaptor.capture());
    EdsUpdate edsUpdate = edsUpdateCaptor.getValue();
    validateGoldenClusterLoadAssignment(edsUpdate);
    verifyNoMoreInteractions(watcher1, watcher2);
    verifyResourceMetadataAcked(
        EDS, EDS_RESOURCE, testClusterLoadAssignment, VERSION_1, TIME_INCREMENT);
    verifyResourceMetadataDoesNotExist(EDS, edsResourceTwo);
    verifySubscribedResourcesMetadataSizes(0, 0, 0, 2);

    Any clusterLoadAssignmentTwo = Any.pack(
        mf.buildClusterLoadAssignment(edsResourceTwo,
            ImmutableList.of(
                mf.buildLocalityLbEndpoints("region2", "zone2", "subzone2",
                    mf.buildLbEndpoint("172.44.2.2", 8000, "healthy", 3), 2, 0)),
            ImmutableList.<Message>of()));
    call.sendResponse(EDS, clusterLoadAssignmentTwo, VERSION_2, "0001");

    verify(watcher1).onChanged(edsUpdateCaptor.capture());
    edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.clusterName).isEqualTo(edsResourceTwo);
    assertThat(edsUpdate.dropPolicies).isEmpty();
    assertThat(edsUpdate.localityLbEndpointsMap)
        .containsExactly(
            Locality.create("region2", "zone2", "subzone2"),
            LocalityLbEndpoints.create(
                ImmutableList.of(
                    LbEndpoint.create("172.44.2.2", 8000, 3, true)), 2, 0));
    verify(watcher2).onChanged(edsUpdateCaptor.capture());
    edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.clusterName).isEqualTo(edsResourceTwo);
    assertThat(edsUpdate.dropPolicies).isEmpty();
    assertThat(edsUpdate.localityLbEndpointsMap)
        .containsExactly(
            Locality.create("region2", "zone2", "subzone2"),
            LocalityLbEndpoints.create(
                ImmutableList.of(
                    LbEndpoint.create("172.44.2.2", 8000, 3, true)), 2, 0));
    verifyNoMoreInteractions(edsResourceWatcher);
    verifyResourceMetadataAcked(
        EDS, edsResourceTwo, clusterLoadAssignmentTwo, VERSION_2, TIME_INCREMENT * 2);
    verifyResourceMetadataAcked(
        EDS, EDS_RESOURCE, testClusterLoadAssignment, VERSION_1, TIME_INCREMENT);
    verifySubscribedResourcesMetadataSizes(0, 0, 0, 2);
  }

  @Test
  public void useIndependentRpcContext() {
    // Simulates making RPCs within the context of an inbound RPC.
    CancellableContext cancellableContext = Context.current().withCancellation();
    Context prevContext = cancellableContext.attach();
    try {
      DiscoveryRpcCall call = startResourceWatcher(XdsListenerResource.getInstance(), LDS_RESOURCE,
          ldsResourceWatcher);

      // The inbound RPC finishes and closes its context. The outbound RPC's control plane RPC
      // should not be impacted.
      cancellableContext.close();
      verify(ldsResourceWatcher, never()).onError(any(Status.class));

      call.sendResponse(LDS, testListenerRds, VERSION_1, "0000");
      verify(ldsResourceWatcher).onChanged(any(LdsUpdate.class));
    } finally {
      cancellableContext.detach(prevContext);
    }
  }

  @Test
  public void streamClosedAndRetryWithBackoff() {
    InOrder inOrder = Mockito.inOrder(backoffPolicyProvider, backoffPolicy1, backoffPolicy2);
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),LDS_RESOURCE, ldsResourceWatcher);
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(),RDS_RESOURCE,
        rdsResourceWatcher);
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(),CDS_RESOURCE, cdsResourceWatcher);
    xdsClient.watchXdsResource(XdsEndpointResource.getInstance(),EDS_RESOURCE, edsResourceWatcher);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    call.verifyRequest(LDS, LDS_RESOURCE, "", "", NODE);
    call.verifyRequest(RDS, RDS_RESOURCE, "", "", NODE);
    call.verifyRequest(CDS, CDS_RESOURCE, "", "", NODE);
    call.verifyRequest(EDS, EDS_RESOURCE, "", "", NODE);

    // Management server closes the RPC stream with an error.
    call.sendError(Status.UNKNOWN.asException());
    verify(ldsResourceWatcher, Mockito.timeout(1000).times(1))
        .onError(errorCaptor.capture());
    verifyStatusWithNodeId(errorCaptor.getValue(), Code.UNKNOWN, "");
    verify(rdsResourceWatcher).onError(errorCaptor.capture());
    verifyStatusWithNodeId(errorCaptor.getValue(), Code.UNKNOWN, "");
    verify(cdsResourceWatcher).onError(errorCaptor.capture());
    verifyStatusWithNodeId(errorCaptor.getValue(), Code.UNKNOWN, "");
    verify(edsResourceWatcher).onError(errorCaptor.capture());
    verifyStatusWithNodeId(errorCaptor.getValue(), Code.UNKNOWN, "");

    // Retry after backoff.
    inOrder.verify(backoffPolicyProvider).get();
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    ScheduledTask retryTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER));
    assertThat(retryTask.getDelay(TimeUnit.NANOSECONDS)).isEqualTo(10L);
    fakeClock.forwardNanos(10L);
    call = resourceDiscoveryCalls.poll();
    call.verifyRequest(LDS, LDS_RESOURCE, "", "", NODE);
    call.verifyRequest(RDS, RDS_RESOURCE, "", "", NODE);
    call.verifyRequest(CDS, CDS_RESOURCE, "", "", NODE);
    call.verifyRequest(EDS, EDS_RESOURCE, "", "", NODE);

    // Management server becomes unreachable.
    String errorMsg = "my fault";
    call.sendError(Status.UNAVAILABLE.withDescription(errorMsg).asException());
    verify(ldsResourceWatcher, times(2)).onError(errorCaptor.capture());
    verifyStatusWithNodeId(errorCaptor.getValue(), Code.UNAVAILABLE, errorMsg);
    verify(rdsResourceWatcher, times(2)).onError(errorCaptor.capture());
    verifyStatusWithNodeId(errorCaptor.getValue(), Code.UNAVAILABLE, errorMsg);
    verify(cdsResourceWatcher, times(2)).onError(errorCaptor.capture());
    verifyStatusWithNodeId(errorCaptor.getValue(), Code.UNAVAILABLE, errorMsg);
    verify(edsResourceWatcher, times(2)).onError(errorCaptor.capture());
    verifyStatusWithNodeId(errorCaptor.getValue(), Code.UNAVAILABLE, errorMsg);

    // Retry after backoff.
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    retryTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER));
    assertThat(retryTask.getDelay(TimeUnit.NANOSECONDS)).isEqualTo(100L);
    fakeClock.forwardNanos(100L);
    call = resourceDiscoveryCalls.poll();
    call.verifyRequest(LDS, LDS_RESOURCE, "", "", NODE);
    call.verifyRequest(RDS, RDS_RESOURCE, "", "", NODE);
    call.verifyRequest(CDS, CDS_RESOURCE, "", "", NODE);
    call.verifyRequest(EDS, EDS_RESOURCE, "", "", NODE);

    List<Any> listeners = ImmutableList.of(
        Any.pack(mf.buildListenerWithApiListener(LDS_RESOURCE,
            mf.buildRouteConfiguration("do not care", mf.buildOpaqueVirtualHosts(2)))));
    call.sendResponse(LDS, listeners, "63", "3242");
    call.verifyRequest(LDS, LDS_RESOURCE, "63", "3242", NODE);

    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(mf.buildRouteConfiguration(RDS_RESOURCE, mf.buildOpaqueVirtualHosts(2))));
    call.sendResponse(RDS, routeConfigs, "5", "6764");
    call.verifyRequest(RDS, RDS_RESOURCE, "5", "6764", NODE);

    call.sendError(Status.DEADLINE_EXCEEDED.asException());
    verify(ldsResourceWatcher, times(2)).onError(errorCaptor.capture());
    verify(rdsResourceWatcher, times(2)).onError(errorCaptor.capture());
    verify(cdsResourceWatcher, times(3)).onError(errorCaptor.capture());
    verifyStatusWithNodeId(errorCaptor.getValue(), Code.DEADLINE_EXCEEDED, "");
    verify(edsResourceWatcher, times(3)).onError(errorCaptor.capture());
    verifyStatusWithNodeId(errorCaptor.getValue(), Code.DEADLINE_EXCEEDED, "");

    // Reset backoff sequence and retry after backoff.
    inOrder.verify(backoffPolicyProvider).get();
    inOrder.verify(backoffPolicy2).nextBackoffNanos();
    retryTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER));
    assertThat(retryTask.getDelay(TimeUnit.NANOSECONDS)).isEqualTo(20L);
    fakeClock.forwardNanos(20L);
    call = resourceDiscoveryCalls.poll();
    call.verifyRequest(LDS, LDS_RESOURCE, "63", "", NODE);
    call.verifyRequest(RDS, RDS_RESOURCE, "5", "", NODE);
    call.verifyRequest(CDS, CDS_RESOURCE, "", "", NODE);
    call.verifyRequest(EDS, EDS_RESOURCE, "", "", NODE);

    // Management server becomes unreachable again.
    call.sendError(Status.UNAVAILABLE.asException());
    verify(ldsResourceWatcher, times(2)).onError(errorCaptor.capture());
    verify(rdsResourceWatcher, times(2)).onError(errorCaptor.capture());
    verify(cdsResourceWatcher, times(4)).onError(errorCaptor.capture());
    verifyStatusWithNodeId(errorCaptor.getValue(), Code.UNAVAILABLE, "");
    verify(edsResourceWatcher, times(4)).onError(errorCaptor.capture());
    verifyStatusWithNodeId(errorCaptor.getValue(), Code.UNAVAILABLE, "");

    // Retry after backoff.
    inOrder.verify(backoffPolicy2).nextBackoffNanos();
    retryTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER));
    assertThat(retryTask.getDelay(TimeUnit.NANOSECONDS)).isEqualTo(200L);
    fakeClock.forwardNanos(200L);
    call = resourceDiscoveryCalls.poll();
    call.verifyRequest(LDS, LDS_RESOURCE, "63", "", NODE);
    call.verifyRequest(RDS, RDS_RESOURCE, "5", "", NODE);
    call.verifyRequest(CDS, CDS_RESOURCE, "", "", NODE);
    call.verifyRequest(EDS, EDS_RESOURCE, "", "", NODE);

    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void streamClosedAndRetryRaceWithAddRemoveWatchers() {
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(),
        LDS_RESOURCE, ldsResourceWatcher);
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(),
        RDS_RESOURCE, rdsResourceWatcher);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    call.sendError(Status.UNAVAILABLE.asException());
    verify(ldsResourceWatcher, Mockito.timeout(1000).times(1))
        .onError(errorCaptor.capture());
    verifyStatusWithNodeId(errorCaptor.getValue(), Code.UNAVAILABLE, "");
    verify(rdsResourceWatcher).onError(errorCaptor.capture());
    verifyStatusWithNodeId(errorCaptor.getValue(), Code.UNAVAILABLE, "");
    ScheduledTask retryTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER));
    assertThat(retryTask.getDelay(TimeUnit.NANOSECONDS)).isEqualTo(10L);

    xdsClient.cancelXdsResourceWatch(XdsListenerResource.getInstance(),
        LDS_RESOURCE, ldsResourceWatcher);
    xdsClient.cancelXdsResourceWatch(XdsRouteConfigureResource.getInstance(),
        RDS_RESOURCE, rdsResourceWatcher);
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(),
        CDS_RESOURCE, cdsResourceWatcher);
    xdsClient.watchXdsResource(XdsEndpointResource.getInstance(),
        EDS_RESOURCE, edsResourceWatcher);
    fakeClock.forwardNanos(10L);
    call = resourceDiscoveryCalls.poll();
    call.verifyRequest(CDS, CDS_RESOURCE, "", "", NODE);
    call.verifyRequest(EDS, EDS_RESOURCE, "", "", NODE);
    call.verifyNoMoreRequest();

    call.sendResponse(LDS, testListenerRds, VERSION_1, "0000");
    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(mf.buildRouteConfiguration(RDS_RESOURCE, mf.buildOpaqueVirtualHosts(VHOST_SIZE))));
    call.sendResponse(RDS, routeConfigs, VERSION_1, "0000");

    verifyNoMoreInteractions(ldsResourceWatcher, rdsResourceWatcher);
  }

  @Test
  public void streamClosedAndRetryRestartsResourceInitialFetchTimerForUnresolvedResources() {
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), LDS_RESOURCE, ldsResourceWatcher);
    xdsClient.watchXdsResource(XdsRouteConfigureResource.getInstance(), RDS_RESOURCE,
        rdsResourceWatcher);
    xdsClient.watchXdsResource(XdsClusterResource.getInstance(), CDS_RESOURCE, cdsResourceWatcher);
    xdsClient.watchXdsResource(XdsEndpointResource.getInstance(), EDS_RESOURCE, edsResourceWatcher);
    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    ScheduledTask ldsResourceTimeout =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    ScheduledTask rdsResourceTimeout =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    ScheduledTask cdsResourceTimeout =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    ScheduledTask edsResourceTimeout =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    call.sendResponse(LDS, testListenerRds, VERSION_1, "0000");
    assertThat(ldsResourceTimeout.isCancelled()).isTrue();

    call.sendResponse(RDS, testRouteConfig, VERSION_1, "0000");
    assertThat(rdsResourceTimeout.isCancelled()).isTrue();

    call.sendError(Status.UNAVAILABLE.asException());
    assertThat(cdsResourceTimeout.isCancelled()).isTrue();
    assertThat(edsResourceTimeout.isCancelled()).isTrue();
    verify(ldsResourceWatcher, never()).onError(errorCaptor.capture());
    verify(rdsResourceWatcher, never()).onError(errorCaptor.capture());
    verify(cdsResourceWatcher).onError(errorCaptor.capture());
    verifyStatusWithNodeId(errorCaptor.getValue(), Code.UNAVAILABLE, "");
    verify(edsResourceWatcher).onError(errorCaptor.capture());
    verifyStatusWithNodeId(errorCaptor.getValue(), Code.UNAVAILABLE, "");

    fakeClock.forwardNanos(10L);
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(0);
    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(0);
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);
    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);
  }

  @Test
  public void reportLoadStatsToServer() {
    xdsClient.watchXdsResource(XdsListenerResource.getInstance(), LDS_RESOURCE, ldsResourceWatcher);
    String clusterName = "cluster-foo.googleapis.com";
    ClusterDropStats dropStats = xdsClient.addClusterDropStats(xdsServerInfo, clusterName, null);
    LrsRpcCall lrsCall = loadReportCalls.poll();
    lrsCall.verifyNextReportClusters(Collections.<String[]>emptyList()); // initial LRS request

    lrsCall.sendResponse(Collections.singletonList(clusterName), 1000L);
    fakeClock.forwardNanos(1000L);
    lrsCall.verifyNextReportClusters(Collections.singletonList(new String[] {clusterName, null}));

    dropStats.release();
    fakeClock.forwardNanos(1000L);
    // In case of having unreported cluster stats, one last report will be sent after corresponding
    // stats object released.
    lrsCall.verifyNextReportClusters(Collections.singletonList(new String[] {clusterName, null}));

    fakeClock.forwardNanos(1000L);
    // Currently load reporting continues (with empty stats) even if all stats objects have been
    // released.
    lrsCall.verifyNextReportClusters(Collections.<String[]>emptyList());  // no more stats reported

    // See more test on LoadReportClientTest.java
  }

  @Test
  public void serverSideListenerFound() {
    Assume.assumeTrue(useProtocolV3());
    XdsClientImplTestBase.DiscoveryRpcCall call =
        startResourceWatcher(XdsListenerResource.getInstance(), LISTENER_RESOURCE,
            ldsResourceWatcher);
    Message hcmFilter = mf.buildHttpConnectionManagerFilter(
        "route-foo.googleapis.com", null,
        Collections.singletonList(mf.buildTerminalFilter()));
    Message downstreamTlsContext = CommonTlsContextTestsUtil.buildTestDownstreamTlsContext(
        "google-sds-config-default", "ROOTCA", false);
    Message filterChain = mf.buildFilterChain(
        Collections.<String>emptyList(), downstreamTlsContext, "envoy.transport_sockets.tls",
        hcmFilter);
    Message listener =
        mf.buildListenerWithFilterChain(LISTENER_RESOURCE, 7000, "0.0.0.0", filterChain);
    List<Any> listeners = ImmutableList.of(Any.pack(listener));
    call.sendResponse(LDS, listeners, "0", "0000");
    // Client sends an ACK LDS request.
    call.verifyRequest(LDS, Collections.singletonList(LISTENER_RESOURCE), "0", "0000", NODE);
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    EnvoyServerProtoData.Listener parsedListener = ldsUpdateCaptor.getValue().listener();
    assertThat(parsedListener.name()).isEqualTo(LISTENER_RESOURCE);
    assertThat(parsedListener.address()).isEqualTo("0.0.0.0:7000");
    assertThat(parsedListener.defaultFilterChain()).isNull();
    assertThat(parsedListener.filterChains()).hasSize(1);
    FilterChain parsedFilterChain = Iterables.getOnlyElement(parsedListener.filterChains());
    assertThat(parsedFilterChain.filterChainMatch().applicationProtocols()).isEmpty();
    assertThat(parsedFilterChain.httpConnectionManager().rdsName())
        .isEqualTo("route-foo.googleapis.com");
    assertThat(parsedFilterChain.httpConnectionManager().httpFilterConfigs().get(0).filterConfig)
        .isEqualTo(RouterFilter.ROUTER_CONFIG);

    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  @Test
  public void serverSideListenerNotFound() {
    Assume.assumeTrue(useProtocolV3());
    XdsClientImplTestBase.DiscoveryRpcCall call =
        startResourceWatcher(XdsListenerResource.getInstance(), LISTENER_RESOURCE,
            ldsResourceWatcher);
    Message hcmFilter = mf.buildHttpConnectionManagerFilter(
        "route-foo.googleapis.com", null,
        Collections.singletonList(mf.buildTerminalFilter()));
    Message downstreamTlsContext = CommonTlsContextTestsUtil.buildTestDownstreamTlsContext(
        "google-sds-config-default", "ROOTCA", false);
    Message filterChain = mf.buildFilterChain(
        Collections.singletonList("managed-mtls"), downstreamTlsContext,
        "envoy.transport_sockets.tls", hcmFilter);
    Message listener = mf.buildListenerWithFilterChain(
        "grpc/server?xds.resource.listening_address=0.0.0.0:8000", 7000, "0.0.0.0", filterChain);
    List<Any> listeners = ImmutableList.of(Any.pack(listener));
    call.sendResponse(LDS, listeners, "0", "0000");
    // Client sends an ACK LDS request.
    call.verifyRequest(LDS, Collections.singletonList(LISTENER_RESOURCE), "0", "0000", NODE);

    verifyNoInteractions(ldsResourceWatcher);
    fakeClock.forwardTime(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(ldsResourceWatcher).onResourceDoesNotExist(LISTENER_RESOURCE);
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  @Test
  public void serverSideListenerResponseErrorHandling_badDownstreamTlsContext() {
    Assume.assumeTrue(useProtocolV3());
    XdsClientImplTestBase.DiscoveryRpcCall call =
            startResourceWatcher(XdsListenerResource.getInstance(), LISTENER_RESOURCE,
                ldsResourceWatcher);
    Message hcmFilter = mf.buildHttpConnectionManagerFilter(
            "route-foo.googleapis.com", null,
        Collections.singletonList(mf.buildTerminalFilter()));
    Message downstreamTlsContext = CommonTlsContextTestsUtil.buildTestDownstreamTlsContext(
            null, null,false);
    Message filterChain = mf.buildFilterChain(
            Collections.<String>emptyList(), downstreamTlsContext, "envoy.transport_sockets.tls",
        hcmFilter);
    Message listener =
            mf.buildListenerWithFilterChain(LISTENER_RESOURCE, 7000, "0.0.0.0", filterChain);
    List<Any> listeners = ImmutableList.of(Any.pack(listener));
    call.sendResponse(LDS, listeners, "0", "0000");
    // The response NACKed with errors indicating indices of the failed resources.
    String errorMsg = "LDS response Listener \'grpc/server?xds.resource.listening_address="
        + "0.0.0.0:7000\' validation error: "
        + "common-tls-context is required in downstream-tls-context";
    call.verifyRequestNack(LDS, LISTENER_RESOURCE, "", "0000", NODE, ImmutableList.of(errorMsg));
    verify(ldsResourceWatcher).onError(errorCaptor.capture());
    verifyStatusWithNodeId(errorCaptor.getValue(), Code.UNAVAILABLE, errorMsg);
  }

  @Test
  public void serverSideListenerResponseErrorHandling_badTransportSocketName() {
    Assume.assumeTrue(useProtocolV3());
    XdsClientImplTestBase.DiscoveryRpcCall call =
        startResourceWatcher(XdsListenerResource.getInstance(), LISTENER_RESOURCE,
            ldsResourceWatcher);
    Message hcmFilter = mf.buildHttpConnectionManagerFilter(
        "route-foo.googleapis.com", null,
        Collections.singletonList(mf.buildTerminalFilter()));
    Message downstreamTlsContext = CommonTlsContextTestsUtil.buildTestDownstreamTlsContext(
        "cert1", "cert2",false);
    Message filterChain = mf.buildFilterChain(
        Collections.<String>emptyList(), downstreamTlsContext, "envoy.transport_sockets.bad1",
        hcmFilter);
    Message listener =
        mf.buildListenerWithFilterChain(LISTENER_RESOURCE, 7000, "0.0.0.0", filterChain);
    List<Any> listeners = ImmutableList.of(Any.pack(listener));
    call.sendResponse(LDS, listeners, "0", "0000");
    // The response NACKed with errors indicating indices of the failed resources.
    String errorMsg = "LDS response Listener \'grpc/server?xds.resource.listening_address="
        + "0.0.0.0:7000\' validation error: "
        + "transport-socket with name envoy.transport_sockets.bad1 not supported.";
    call.verifyRequestNack(LDS, LISTENER_RESOURCE, "", "0000", NODE, ImmutableList.of(
        errorMsg));
    verify(ldsResourceWatcher).onError(errorCaptor.capture());
    verifyStatusWithNodeId(errorCaptor.getValue(), Code.UNAVAILABLE, errorMsg);
  }

  @Test
  public void sendingToStoppedServer() throws Exception {
    try {
      // Establish the adsStream object
      xdsClient.watchXdsResource(XdsClusterResource.getInstance(), CDS_RESOURCE,
          cdsResourceWatcher);
      DiscoveryRpcCall unused = resourceDiscoveryCalls.take(); // clear this entry

      // Shutdown server and initiate a request
      xdsServer.shutdownNow();
      xdsClient.watchXdsResource(XdsListenerResource.getInstance(), LDS_RESOURCE,
          ldsResourceWatcher);
      fakeClock.forwardTime(14, TimeUnit.SECONDS);

      // Restart the server
      xdsServer = cleanupRule.register(
          InProcessServerBuilder
              .forName(serverName)
              .addService(adsService)
              .addService(lrsService)
              .directExecutor()
              .build()
              .start());
      fakeClock.forwardTime(5, TimeUnit.SECONDS);
      verify(ldsResourceWatcher, never()).onResourceDoesNotExist(LDS_RESOURCE);
      fakeClock.forwardTime(20, TimeUnit.SECONDS); // Trigger rpcRetryTimer
      DiscoveryRpcCall call = resourceDiscoveryCalls.poll(3, TimeUnit.SECONDS);
      if (call == null) { // The first rpcRetry may have happened before the channel was ready
        fakeClock.forwardTime(50, TimeUnit.SECONDS);
        call = resourceDiscoveryCalls.poll(3, TimeUnit.SECONDS);
      }

      // NOTE:  There is a ScheduledExecutorService that may get involved due to the reconnect
      // so you cannot rely on the logic being single threaded.  The timeout() in verifyRequest
      // is therefore necessary to avoid flakiness.
      // Send a response and do verifications
      call.sendResponse(LDS, mf.buildWrappedResource(testListenerVhosts), VERSION_1, "0001");
      call.verifyRequest(LDS, LDS_RESOURCE, VERSION_1, "0001", NODE);
      verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
      verifyGoldenListenerVhosts(ldsUpdateCaptor.getValue());
      assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
      verifyResourceMetadataAcked(LDS, LDS_RESOURCE, testListenerVhosts, VERSION_1, TIME_INCREMENT);
      verifySubscribedResourcesMetadataSizes(1, 1, 0, 0);
    } catch (Throwable t) {
      throw t; // This allows putting a breakpoint here for debugging
    }
  }

  @Test
  public void sendToBadUrl() throws Exception {
    // Setup xdsClient to fail on stream creation
    XdsClientImpl client = createXdsClient("some. garbage");

    client.watchXdsResource(XdsListenerResource.getInstance(), LDS_RESOURCE, ldsResourceWatcher);
    fakeClock.forwardTime(20, TimeUnit.SECONDS);
    verify(ldsResourceWatcher, Mockito.timeout(5000).times(1)).onError(ArgumentMatchers.any());
    client.shutdown();
  }

  @Test
  public void sendToNonexistentHost() throws Exception {
    // Setup xdsClient to fail on stream creation
    XdsClientImpl client = createXdsClient("some.garbage");
    client.watchXdsResource(XdsListenerResource.getInstance(), LDS_RESOURCE, ldsResourceWatcher);
    verify(ldsResourceWatcher, Mockito.timeout(5000).times(1)).onError(ArgumentMatchers.any());
    assertThat(fakeClock.numPendingTasks()).isEqualTo(1); //retry
    assertThat(fakeClock.getPendingTasks().iterator().next().toString().contains("RpcRetryTask"))
        .isTrue();
    client.shutdown();
  }

  private XdsClientImpl createXdsClient(String serverUri) {
    BootstrapInfo bootstrapInfo = buildBootStrap(serverUri);
    return new XdsClientImpl(
        DEFAULT_XDS_CHANNEL_FACTORY,
        bootstrapInfo,
        Context.ROOT,
        fakeClock.getScheduledExecutorService(),
        backoffPolicyProvider,
        fakeClock.getStopwatchSupplier(),
        timeProvider,
        tlsContextManager);
  }

  private  BootstrapInfo buildBootStrap(String serverUri) {

    ServerInfo xdsServerInfo = ServerInfo.create(serverUri, CHANNEL_CREDENTIALS,
        ignoreResourceDeletion());

    return Bootstrapper.BootstrapInfo.builder()
        .servers(Collections.singletonList(xdsServerInfo))
        .node(NODE)
        .authorities(ImmutableMap.of(
            "authority.xds.com",
            AuthorityInfo.create(
                "xdstp://authority.xds.com/envoy.config.listener.v3.Listener/%s",
                ImmutableList.of(Bootstrapper.ServerInfo.create(
                    SERVER_URI_CUSTOME_AUTHORITY, CHANNEL_CREDENTIALS))),
            "",
            AuthorityInfo.create(
                "xdstp:///envoy.config.listener.v3.Listener/%s",
                ImmutableList.of(Bootstrapper.ServerInfo.create(
                    SERVER_URI_EMPTY_AUTHORITY, CHANNEL_CREDENTIALS)))))
        .certProviders(ImmutableMap.of("cert-instance-name",
            CertificateProviderInfo.create("file-watcher", ImmutableMap.<String, Object>of())))
        .build();
  }

  private <T extends ResourceUpdate> DiscoveryRpcCall startResourceWatcher(
      XdsResourceType<T> type, String name, ResourceWatcher<T> watcher) {
    FakeClock.TaskFilter timeoutTaskFilter;
    switch (type.typeName()) {
      case "LDS":
        timeoutTaskFilter = LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER;
        xdsClient.watchXdsResource(type, name, watcher);
        break;
      case "RDS":
        timeoutTaskFilter = RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER;
        xdsClient.watchXdsResource(type, name, watcher);
        break;
      case "CDS":
        timeoutTaskFilter = CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER;
        xdsClient.watchXdsResource(type, name, watcher);
        break;
      case "EDS":
        timeoutTaskFilter = EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER;
        xdsClient.watchXdsResource(type, name, watcher);
        break;
      default:
        throw new AssertionError("should never be here");
    }

    DiscoveryRpcCall call = resourceDiscoveryCalls.poll();
    call.verifyRequest(type, Collections.singletonList(name), "", "", NODE);
    ScheduledTask timeoutTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(timeoutTaskFilter));
    assertThat(timeoutTask.getDelay(TimeUnit.SECONDS))
        .isEqualTo(XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC);
    return call;
  }

  protected abstract static class DiscoveryRpcCall {

    protected void verifyRequest(
        XdsResourceType<?> type, List<String> resources, String versionInfo, String nonce,
        Node node) {
      throw new UnsupportedOperationException();
    }

    protected void verifyRequest(
        XdsResourceType<?> type, String resource, String versionInfo, String nonce, Node node) {
      verifyRequest(type, ImmutableList.of(resource), versionInfo, nonce, node);
    }

    protected void verifyRequestNack(
        XdsResourceType<?> type, List<String> resources, String versionInfo, String nonce,
        Node node, List<String> errorMessages) {
      throw new UnsupportedOperationException();
    }

    protected void verifyRequestNack(
        XdsResourceType<?> type, String resource, String versionInfo, String nonce, Node node,
        List<String> errorMessages) {
      verifyRequestNack(type, ImmutableList.of(resource), versionInfo, nonce, node, errorMessages);
    }

    protected void verifyNoMoreRequest() {
      throw new UnsupportedOperationException();
    }

    protected void sendResponse(
        XdsResourceType<?> type, List<Any> resources, String versionInfo, String nonce) {
      throw new UnsupportedOperationException();
    }

    protected void sendResponse(XdsResourceType<?> type, Any resource, String versionInfo,
                                String nonce) {
      sendResponse(type, ImmutableList.of(resource), versionInfo, nonce);
    }

    protected void sendError(Throwable t) {
      throw new UnsupportedOperationException();
    }

    protected void sendCompleted() {
      throw new UnsupportedOperationException();
    }
  }

  protected abstract static class LrsRpcCall {

    /**
     * Verifies a LRS request has been sent with ClusterStats of the given list of clusters.
     */
    protected void verifyNextReportClusters(List<String[]> clusters) {
      throw new UnsupportedOperationException();
    }

    protected void sendResponse(List<String> clusters, long loadReportIntervalNano) {
      throw new UnsupportedOperationException();
    }
  }

  protected abstract static class MessageFactory {
    /** Throws {@link InvalidProtocolBufferException} on {@link Any#unpack(Class)}. */
    protected static final Any FAILING_ANY = Any.newBuilder().setTypeUrl("fake").build();

    protected abstract Any buildWrappedResource(Any originalResource);

    protected Message buildListenerWithApiListener(String name, Message routeConfiguration) {
      return buildListenerWithApiListener(
          name, routeConfiguration, Collections.<Message>emptyList());
    }

    protected abstract Message buildListenerWithApiListener(
        String name, Message routeConfiguration, List<? extends Message> httpFilters);

    protected abstract Message buildListenerWithApiListenerForRds(
        String name, String rdsResourceName);

    protected abstract Message buildListenerWithApiListenerInvalid(String name);

    protected abstract Message buildHttpFilter(
        String name, @Nullable Any typedConfig, boolean isOptional);

    protected abstract Any buildHttpFaultTypedConfig(
        @Nullable Long delayNanos, @Nullable Integer delayRate, String upstreamCluster,
        List<String> downstreamNodes, @Nullable Integer maxActiveFaults, @Nullable Status status,
        @Nullable Integer httpCode, @Nullable Integer abortRate);

    protected abstract Message buildRouteConfiguration(String name,
        List<Message> virtualHostList);

    protected abstract Message buildRouteConfigurationInvalid(String name);

    protected abstract List<Message> buildOpaqueVirtualHosts(int num);

    protected abstract Message buildVirtualHost(
        List<? extends Message> routes, Map<String, Any> typedConfigMap);

    protected abstract List<? extends Message> buildOpaqueRoutes(int num);

    protected abstract Message buildClusterInvalid(String name);

    protected abstract Message buildEdsCluster(String clusterName, @Nullable String edsServiceName,
        String lbPolicy, @Nullable Message ringHashLbConfig, @Nullable Message leastRequestLbConfig,
        boolean enableLrs, @Nullable Message upstreamTlsContext, String transportSocketName,
        @Nullable Message circuitBreakers, @Nullable Message outlierDetection);

    protected abstract Message buildLogicalDnsCluster(String clusterName, String dnsHostAddr,
        int dnsHostPort, String lbPolicy, @Nullable Message ringHashLbConfig,
        @Nullable Message leastRequestLbConfig, boolean enableLrs,
        @Nullable Message upstreamTlsContext, @Nullable Message circuitBreakers);

    protected abstract Message buildAggregateCluster(String clusterName, String lbPolicy,
        @Nullable Message ringHashLbConfig, @Nullable Message leastRequestLbConfig,
        List<String> clusters);

    protected abstract Message buildRingHashLbConfig(String hashFunction, long minRingSize,
        long maxRingSize);

    protected abstract Message buildLeastRequestLbConfig(int choiceCount);

    protected abstract Message buildUpstreamTlsContext(String instanceName, String certName);

    protected abstract Message buildNewUpstreamTlsContext(String instanceName, String certName);

    protected abstract Message buildCircuitBreakers(int highPriorityMaxRequests,
        int defaultPriorityMaxRequests);

    protected abstract Message buildClusterLoadAssignment(String cluster,
        List<Message> localityLbEndpoints, List<Message> dropOverloads);

    protected abstract Message buildClusterLoadAssignmentInvalid(String cluster);

    protected abstract Message buildLocalityLbEndpoints(String region, String zone, String subZone,
        List<Message> lbEndpointList, int loadBalancingWeight, int priority);

    protected Message buildLocalityLbEndpoints(String region, String zone, String subZone,
        Message lbEndpoint, int loadBalancingWeight, int priority) {
      return buildLocalityLbEndpoints(region, zone, subZone, ImmutableList.of(lbEndpoint),
          loadBalancingWeight, priority);
    }

    protected abstract Message buildLbEndpoint(String address, int port, String healthStatus,
        int lbWeight);

    protected abstract Message buildDropOverload(String category, int dropPerMillion);

    protected abstract Message buildFilterChain(
        List<String> alpn, Message tlsContext, String transportSocketName,
        Message... filters);

    protected abstract Message buildListenerWithFilterChain(
        String name, int portValue, String address, Message... filterChains);

    protected abstract Message buildHttpConnectionManagerFilter(
        @Nullable String rdsName, @Nullable Message routeConfig, List<Message> httpFilters);

    protected abstract Message buildTerminalFilter();
  }

  @Test
  public void dropXdsV2Lds() {
    startResourceWatcher(XdsListenerResource.getInstance(), LDS_RESOURCE, ldsResourceWatcher);
    assertThat(resourceDiscoveryCallsV2).isEmpty();
    assertThat(loadReportCallsV2).isEmpty();
  }

  @Test
  public void dropXdsV2Cds() {
    startResourceWatcher(XdsClusterResource.getInstance(), CDS_RESOURCE, cdsResourceWatcher);
    assertThat(resourceDiscoveryCallsV2).isEmpty();
    assertThat(loadReportCallsV2).isEmpty();
  }

  @Test
  public void dropXdsV2Rds() {
    startResourceWatcher(XdsRouteConfigureResource.getInstance(), RDS_RESOURCE, rdsResourceWatcher);
    assertThat(resourceDiscoveryCallsV2).isEmpty();
    assertThat(loadReportCallsV2).isEmpty();
  }

  @Test
  public void dropXdsV2Eds() {
    startResourceWatcher(XdsEndpointResource.getInstance(), EDS_RESOURCE, edsResourceWatcher);
    assertThat(resourceDiscoveryCallsV2).isEmpty();
    assertThat(loadReportCallsV2).isEmpty();
  }

  protected BindableService createAdsServiceV2() {
    return new AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamAggregatedResources(
          final StreamObserver<io.envoyproxy.envoy.api.v2.DiscoveryResponse> responseObserver) {
        assertThat(adsEnded.get()).isTrue();  // ensure previous call was ended
        adsEnded.set(false);
        @SuppressWarnings("unchecked")
        StreamObserver<io.envoyproxy.envoy.api.v2.DiscoveryRequest> requestObserver =
            mock(StreamObserver.class);
        DiscoveryRpcCall call = new DiscoveryRpcCall() {};
        resourceDiscoveryCallsV2.offer(call);
        Context.current().addListener(
            new Context.CancellationListener() {
              @Override
              public void cancelled(Context context) {
                adsEnded.set(true);
              }
            }, MoreExecutors.directExecutor());
        return requestObserver;
      }
    };
  }

  protected BindableService createLrsServiceV2() {
    return new LoadReportingServiceGrpc.LoadReportingServiceImplBase() {
      @Override
      public
          StreamObserver<io.envoyproxy.envoy.service.load_stats.v2.LoadStatsRequest>
          streamLoadStats(
              StreamObserver<io.envoyproxy.envoy.service.load_stats.v2.LoadStatsResponse>
                  responseObserver) {
        assertThat(lrsEnded.get()).isTrue();
        lrsEnded.set(false);
        @SuppressWarnings("unchecked")
        StreamObserver<io.envoyproxy.envoy.service.load_stats.v2.LoadStatsRequest> requestObserver =
            mock(StreamObserver.class);
        LrsRpcCall call = new LrsRpcCall() {};
        Context.current().addListener(
            new Context.CancellationListener() {
              @Override
              public void cancelled(Context context) {
                lrsEnded.set(true);
              }
            }, MoreExecutors.directExecutor());
        loadReportCallsV2.offer(call);
        return requestObserver;
      }
    };
  }
}
