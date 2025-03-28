/*
 * Copyright 2020 The gRPC Authors
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
import static io.grpc.xds.FaultFilter.HEADER_ABORT_GRPC_STATUS_KEY;
import static io.grpc.xds.FaultFilter.HEADER_ABORT_HTTP_STATUS_KEY;
import static io.grpc.xds.FaultFilter.HEADER_ABORT_PERCENTAGE_KEY;
import static io.grpc.xds.FaultFilter.HEADER_DELAY_KEY;
import static io.grpc.xds.FaultFilter.HEADER_DELAY_PERCENTAGE_KEY;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.protobuf.util.Durations;
import com.google.re2j.Pattern;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ChannelLogger;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.Deadline;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InternalConfigSelector;
import io.grpc.InternalConfigSelector.Result;
import io.grpc.LoadBalancer.PickDetailsConsumer;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.MetricRecorder;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.NameResolver.ResolutionResult;
import io.grpc.NameResolver.ServiceConfigParser;
import io.grpc.NoopClientCall;
import io.grpc.NoopClientCall.NoopClientCallListener;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.internal.AutoConfiguredLoadBalancerFactory;
import io.grpc.internal.FakeClock;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.JsonParser;
import io.grpc.internal.JsonUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.PickSubchannelArgsImpl;
import io.grpc.internal.ScParser;
import io.grpc.testing.TestMethodDescriptors;
import io.grpc.xds.ClusterSpecifierPlugin.NamedPluginConfig;
import io.grpc.xds.FaultConfig.FaultAbort;
import io.grpc.xds.FaultConfig.FaultDelay;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.Filter.NamedFilterConfig;
import io.grpc.xds.RouteLookupServiceClusterSpecifierPlugin.RlsPluginConfig;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.VirtualHost.Route.RouteAction;
import io.grpc.xds.VirtualHost.Route.RouteAction.ClusterWeight;
import io.grpc.xds.VirtualHost.Route.RouteAction.HashPolicy;
import io.grpc.xds.VirtualHost.Route.RouteAction.RetryPolicy;
import io.grpc.xds.VirtualHost.Route.RouteMatch;
import io.grpc.xds.VirtualHost.Route.RouteMatch.PathMatcher;
import io.grpc.xds.XdsClusterResource.CdsUpdate;
import io.grpc.xds.XdsEndpointResource.EdsUpdate;
import io.grpc.xds.XdsListenerResource.LdsUpdate;
import io.grpc.xds.XdsRouteConfigureResource.RdsUpdate;
import io.grpc.xds.client.Bootstrapper.AuthorityInfo;
import io.grpc.xds.client.Bootstrapper.BootstrapInfo;
import io.grpc.xds.client.Bootstrapper.ServerInfo;
import io.grpc.xds.client.EnvoyProtoData.Node;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsInitializationException;
import io.grpc.xds.client.XdsResourceType;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
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

/** Unit tests for {@link XdsNameResolver}. */
// TODO(chengyuanzhang): should do tests with ManagedChannelImpl.
@RunWith(JUnit4.class)
public class XdsNameResolverTest {
  private static final String AUTHORITY = "foo.googleapis.com:80";
  private static final String RDS_RESOURCE_NAME = "route-configuration.googleapis.com";
  private static final String FAULT_FILTER_INSTANCE_NAME = "envoy.fault";
  private static final String ROUTER_FILTER_INSTANCE_NAME = "envoy.router";
  private static final FaultFilter.Provider FAULT_FILTER_PROVIDER = new FaultFilter.Provider();
  private static final RouterFilter.Provider ROUTER_FILTER_PROVIDER = new RouterFilter.Provider();

  // Readability: makes it simpler to distinguish resource parameters.
  private static final ImmutableMap<String, FilterConfig> NO_FILTER_OVERRIDES = ImmutableMap.of();
  private static final ImmutableList<HashPolicy> NO_HASH_POLICIES = ImmutableList.of();

  // Stateful instance filter names.
  private static final String STATEFUL_1 = "test.stateful.filter.1";
  private static final String STATEFUL_2 = "test.stateful.filter.2";

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final FakeClock fakeClock = new FakeClock();
  private final ScheduledExecutorService scheduler = fakeClock.getScheduledExecutorService();
  private final ServiceConfigParser serviceConfigParser = new ServiceConfigParser() {
    @Override
    public ConfigOrError parseServiceConfig(Map<String, ?> rawServiceConfig) {
      return ConfigOrError.fromConfig(rawServiceConfig);
    }
  };
  private final FakeXdsClientPoolFactory xdsClientPoolFactory = new FakeXdsClientPoolFactory();
  private final String cluster1 = "cluster-foo.googleapis.com";
  private final String cluster2 = "cluster-bar.googleapis.com";
  private final CallInfo call1 = new CallInfo("HelloService", "hi");
  private final CallInfo call2 = new CallInfo("GreetService", "bye");
  private final TestChannel channel = new TestChannel();
  private final MetricRecorder metricRecorder = new MetricRecorder() {};
  private BootstrapInfo bootstrapInfo = BootstrapInfo.builder()
      .servers(ImmutableList.of(ServerInfo.create(
          "td.googleapis.com", InsecureChannelCredentials.create())))
      .node(Node.newBuilder().build())
      .build();
  private String expectedLdsResourceName = AUTHORITY;

  @Mock
  private ThreadSafeRandom mockRandom;
  @Mock
  private NameResolver.Listener2 mockListener;
  @Captor
  private ArgumentCaptor<ResolutionResult> resolutionResultCaptor;
  @Captor
  ArgumentCaptor<Status> errorCaptor;
  private XdsNameResolver resolver;
  private TestCall<?, ?> testCall;
  private boolean originalEnableTimeout;
  private URI targetUri;
  private final NameResolver.Args nameResolverArgs = NameResolver.Args.newBuilder()
      .setDefaultPort(8080)
      .setProxyDetector(GrpcUtil.DEFAULT_PROXY_DETECTOR)
      .setSynchronizationContext(syncContext)
      .setServiceConfigParser(mock(NameResolver.ServiceConfigParser.class))
      .setChannelLogger(mock(ChannelLogger.class))
      .setScheduledExecutorService(fakeClock.getScheduledExecutorService())
      .build();


  @Before
  public void setUp() {
    try {
      targetUri = new URI(AUTHORITY);
    } catch (URISyntaxException e) {
      targetUri = null;
    }

    originalEnableTimeout = XdsNameResolver.enableTimeout;
    XdsNameResolver.enableTimeout = true;

    // Replace FaultFilter.Provider with the one returning FaultFilter injected with mockRandom.
    Filter.Provider faultFilterProvider =
        mock(Filter.Provider.class, delegatesTo(FAULT_FILTER_PROVIDER));
    // Lenient: suppress [MockitoHint] Unused warning, only used in resolved_fault* tests.
    lenient()
        .doReturn(new FaultFilter(mockRandom, new AtomicLong()))
        .when(faultFilterProvider).newInstance(any(String.class));

    FilterRegistry filterRegistry = FilterRegistry.newRegistry().register(
        ROUTER_FILTER_PROVIDER,
        faultFilterProvider);

    resolver = new XdsNameResolver(targetUri, null, AUTHORITY, null,
        serviceConfigParser, syncContext, scheduler,
        xdsClientPoolFactory, mockRandom, filterRegistry, null, metricRecorder, nameResolverArgs);
  }

  @After
  public void tearDown() {
    XdsNameResolver.enableTimeout = originalEnableTimeout;
    if (resolver == null) {
      // Allow tests to test shutdown.
      return;
    }
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    resolver.shutdown();
    if (xdsClient != null) {
      assertThat(xdsClient.ldsWatcher).isNull();
      assertThat(xdsClient.rdsWatcher).isNull();
    }
  }

  @Test
  public void resolving_failToCreateXdsClientPool() {
    XdsClientPoolFactory xdsClientPoolFactory = new XdsClientPoolFactory() {
      @Override
      public void setBootstrapOverride(Map<String, ?> bootstrap) {
      }

      @Override
      @Nullable
      public ObjectPool<XdsClient> get(String target) {
        throw new UnsupportedOperationException("Should not be called");
      }

      @Override
      public ObjectPool<XdsClient> getOrCreate(String target, MetricRecorder metricRecorder)
          throws XdsInitializationException {
        throw new XdsInitializationException("Fail to read bootstrap file");
      }

      @Override
      public List<String> getTargets() {
        return null;
      }
    };

    resolver = new XdsNameResolver(targetUri, null, AUTHORITY, null,
        serviceConfigParser, syncContext, scheduler,
        xdsClientPoolFactory, mockRandom, FilterRegistry.getDefaultRegistry(), null,
        metricRecorder, nameResolverArgs);
    resolver.start(mockListener);
    verify(mockListener).onError(errorCaptor.capture());
    Status error = errorCaptor.getValue();
    assertThat(error.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(error.getDescription()).isEqualTo("Failed to initialize xDS");
    assertThat(error.getCause()).hasMessageThat().isEqualTo("Fail to read bootstrap file");
  }

  @Test
  public void resolving_withTargetAuthorityNotFound() {
    resolver = new XdsNameResolver(targetUri,
        "notfound.google.com", AUTHORITY, null, serviceConfigParser, syncContext, scheduler,
        xdsClientPoolFactory, mockRandom, FilterRegistry.getDefaultRegistry(), null,
        metricRecorder, nameResolverArgs);
    resolver.start(mockListener);
    verify(mockListener).onError(errorCaptor.capture());
    Status error = errorCaptor.getValue();
    assertThat(error.getCode()).isEqualTo(Code.INVALID_ARGUMENT);
    assertThat(error.getDescription()).isEqualTo(
        "invalid target URI: target authority not found in the bootstrap");
  }

  @Test
  public void resolving_noTargetAuthority_templateWithoutXdstp() {
    bootstrapInfo = BootstrapInfo.builder()
        .servers(ImmutableList.of(ServerInfo.create(
            "td.googleapis.com", InsecureChannelCredentials.create())))
        .node(Node.newBuilder().build())
        .clientDefaultListenerResourceNameTemplate("%s/id=1")
        .build();
    String serviceAuthority = "[::FFFF:129.144.52.38]:80";
    expectedLdsResourceName = "[::FFFF:129.144.52.38]:80/id=1";
    resolver = new XdsNameResolver(
        targetUri, null, serviceAuthority, null, serviceConfigParser, syncContext,
        scheduler, xdsClientPoolFactory,
        mockRandom, FilterRegistry.getDefaultRegistry(), null, metricRecorder, nameResolverArgs);
    resolver.start(mockListener);
    verify(mockListener, never()).onError(any(Status.class));
  }

  @Test
  public void resolving_noTargetAuthority_templateWithXdstp() {
    bootstrapInfo = BootstrapInfo.builder()
        .servers(ImmutableList.of(ServerInfo.create(
            "td.googleapis.com", InsecureChannelCredentials.create())))
        .node(Node.newBuilder().build())
        .clientDefaultListenerResourceNameTemplate(
            "xdstp://xds.authority.com/envoy.config.listener.v3.Listener/%s?id=1")
        .build();
    String serviceAuthority = "[::FFFF:129.144.52.38]:80";
    expectedLdsResourceName =
        "xdstp://xds.authority.com/envoy.config.listener.v3.Listener/"
            + "%5B::FFFF:129.144.52.38%5D:80?id=1";
    resolver = new XdsNameResolver(
        targetUri, null, serviceAuthority, null, serviceConfigParser, syncContext, scheduler,
        xdsClientPoolFactory, mockRandom, FilterRegistry.getDefaultRegistry(), null,
        metricRecorder, nameResolverArgs);
    resolver.start(mockListener);
    verify(mockListener, never()).onError(any(Status.class));
  }

  @Test
  public void resolving_noTargetAuthority_xdstpWithMultipleSlashes() {
    bootstrapInfo = BootstrapInfo.builder()
        .servers(ImmutableList.of(ServerInfo.create(
            "td.googleapis.com", InsecureChannelCredentials.create())))
        .node(Node.newBuilder().build())
        .clientDefaultListenerResourceNameTemplate(
            "xdstp://xds.authority.com/envoy.config.listener.v3.Listener/%s?id=1")
        .build();
    String serviceAuthority = "path/to/service";
    expectedLdsResourceName =
        "xdstp://xds.authority.com/envoy.config.listener.v3.Listener/"
            + "path/to/service?id=1";
    resolver = new XdsNameResolver(
        targetUri, null, serviceAuthority, null, serviceConfigParser, syncContext, scheduler,
        xdsClientPoolFactory, mockRandom, FilterRegistry.getDefaultRegistry(), null,
        metricRecorder, nameResolverArgs);


    // The Service Authority must be URL encoded, but unlike the LDS resource name.
    assertThat(resolver.getServiceAuthority()).isEqualTo("path%2Fto%2Fservice");

    resolver.start(mockListener);
    verify(mockListener, never()).onError(any(Status.class));
  }

  @Test
  public void resolving_targetAuthorityInAuthoritiesMap() {
    String targetAuthority = "xds.authority.com";
    String serviceAuthority = "[::FFFF:129.144.52.38]:80";
    bootstrapInfo = BootstrapInfo.builder()
        .servers(ImmutableList.of(ServerInfo.create(
            "td.googleapis.com", InsecureChannelCredentials.create(), true, true)))
        .node(Node.newBuilder().build())
        .authorities(
            ImmutableMap.of(targetAuthority, AuthorityInfo.create(
                "xdstp://" + targetAuthority + "/envoy.config.listener.v3.Listener/%s?foo=1&bar=2",
                ImmutableList.of(ServerInfo.create(
                    "td.googleapis.com", InsecureChannelCredentials.create(), true, true)))))
        .build();
    expectedLdsResourceName = "xdstp://xds.authority.com/envoy.config.listener.v3.Listener/"
        + "%5B::FFFF:129.144.52.38%5D:80?bar=2&foo=1"; // query param canonified
    resolver = new XdsNameResolver(targetUri,
        "xds.authority.com", serviceAuthority, null, serviceConfigParser, syncContext, scheduler,
        xdsClientPoolFactory, mockRandom, FilterRegistry.getDefaultRegistry(), null,
        metricRecorder, nameResolverArgs);
    resolver.start(mockListener);
    verify(mockListener, never()).onError(any(Status.class));
  }

  @Test
  public void resolving_ldsResourceNotFound() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsResourceNotFound();
    assertEmptyResolutionResult(expectedLdsResourceName);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolving_ldsResourceUpdateRdsName() {
    Route route1 = Route.forAction(RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
        RouteAction.forCluster(
            cluster1, Collections.emptyList(), TimeUnit.SECONDS.toNanos(15L), null, false),
        ImmutableMap.of());
    Route route2 = Route.forAction(RouteMatch.withPathExactOnly(call2.getFullMethodNameForPath()),
        RouteAction.forCluster(
            cluster2, Collections.emptyList(), TimeUnit.SECONDS.toNanos(20L), null, false),
        ImmutableMap.of());
    bootstrapInfo = BootstrapInfo.builder()
        .servers(ImmutableList.of(ServerInfo.create(
            "td.googleapis.com", InsecureChannelCredentials.create())))
        .clientDefaultListenerResourceNameTemplate("test-%s")
        .node(Node.newBuilder().build())
        .build();
    resolver = new XdsNameResolver(targetUri, null, AUTHORITY, null,
        serviceConfigParser, syncContext, scheduler,
        xdsClientPoolFactory, mockRandom, FilterRegistry.getDefaultRegistry(), null,
        metricRecorder, nameResolverArgs);
    // use different ldsResourceName and service authority. The virtualhost lookup should use
    // service authority.
    expectedLdsResourceName = "test-" + expectedLdsResourceName;

    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdateForRdsName(RDS_RESOURCE_NAME);
    assertThat(xdsClient.rdsResource).isEqualTo(RDS_RESOURCE_NAME);
    VirtualHost virtualHost =
        VirtualHost.create("virtualhost", Collections.singletonList(AUTHORITY),
            Collections.singletonList(route1),
            ImmutableMap.of());
    xdsClient.deliverRdsUpdate(RDS_RESOURCE_NAME, Collections.singletonList(virtualHost));
    createAndDeliverClusterUpdates(xdsClient, cluster1);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    assertServiceConfigForLoadBalancingConfig(
        Collections.singletonList(cluster1),
        (Map<String, ?>) resolutionResultCaptor.getValue().getServiceConfig().getConfig());

    reset(mockListener);
    ArgumentCaptor<ResolutionResult> resultCaptor =
        ArgumentCaptor.forClass(ResolutionResult.class);
    String alternativeRdsResource = "route-configuration-alter.googleapis.com";
    xdsClient.deliverLdsUpdateForRdsName(alternativeRdsResource);
    assertThat(xdsClient.rdsResource).isEqualTo(alternativeRdsResource);
    virtualHost =
        VirtualHost.create("virtualhost-alter", Collections.singletonList(AUTHORITY),
            Collections.singletonList(route2),
            ImmutableMap.of());
    xdsClient.deliverRdsUpdate(alternativeRdsResource, Collections.singletonList(virtualHost));
    createAndDeliverClusterUpdates(xdsClient, cluster2);
    // Two new service config updates triggered:
    //  - with load balancing config being able to select cluster1 and cluster2
    //  - with load balancing config being able to select cluster2 only
    verify(mockListener, times(2)).onResult2(resultCaptor.capture());
    assertServiceConfigForLoadBalancingConfig(
        Arrays.asList(cluster1, cluster2),
        (Map<String, ?>) resultCaptor.getAllValues().get(0).getServiceConfig().getConfig());
    assertServiceConfigForLoadBalancingConfig(
        Collections.singletonList(cluster2),
        (Map<String, ?>) resultCaptor.getValue().getServiceConfig().getConfig());
  }

  @Test
  public void resolving_rdsResourceNotFound() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdateForRdsName(RDS_RESOURCE_NAME);
    xdsClient.deliverRdsResourceNotFound(RDS_RESOURCE_NAME);
    assertEmptyResolutionResult(RDS_RESOURCE_NAME);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolving_ldsResourceRevokedAndAddedBack() {
    Route route = Route.forAction(RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
        RouteAction.forCluster(
            cluster1, Collections.emptyList(), TimeUnit.SECONDS.toNanos(15L), null, false),
        ImmutableMap.of());

    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdateForRdsName(RDS_RESOURCE_NAME);
    assertThat(xdsClient.rdsResource).isEqualTo(RDS_RESOURCE_NAME);
    VirtualHost virtualHost =
        VirtualHost.create("virtualhost", Collections.singletonList(AUTHORITY),
            Collections.singletonList(route),
            ImmutableMap.of());
    xdsClient.deliverRdsUpdate(RDS_RESOURCE_NAME, Collections.singletonList(virtualHost));
    createAndDeliverClusterUpdates(xdsClient, cluster1);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    assertServiceConfigForLoadBalancingConfig(
        Collections.singletonList(cluster1),
        (Map<String, ?>) resolutionResultCaptor.getValue().getServiceConfig().getConfig());

    reset(mockListener);
    xdsClient.deliverLdsResourceNotFound();  // revoke LDS resource
    assertThat(xdsClient.rdsResource).isNull();  // stop subscribing to stale RDS resource
    assertEmptyResolutionResult(expectedLdsResourceName);

    reset(mockListener);
    xdsClient.deliverLdsUpdateForRdsName(RDS_RESOURCE_NAME);
    // No name resolution result until new RDS resource update is received. Do not use stale config
    verifyNoInteractions(mockListener);
    assertThat(xdsClient.rdsResource).isEqualTo(RDS_RESOURCE_NAME);
    xdsClient.deliverRdsUpdate(RDS_RESOURCE_NAME, Collections.singletonList(virtualHost));
    createAndDeliverClusterUpdates(xdsClient, cluster1);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    assertServiceConfigForLoadBalancingConfig(
        Collections.singletonList(cluster1),
        (Map<String, ?>) resolutionResultCaptor.getValue().getServiceConfig().getConfig());
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolving_rdsResourceRevokedAndAddedBack() {
    Route route = Route.forAction(RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
        RouteAction.forCluster(
            cluster1, Collections.emptyList(), TimeUnit.SECONDS.toNanos(15L), null, false),
        ImmutableMap.of());

    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdateForRdsName(RDS_RESOURCE_NAME);
    assertThat(xdsClient.rdsResource).isEqualTo(RDS_RESOURCE_NAME);
    VirtualHost virtualHost =
        VirtualHost.create("virtualhost", Collections.singletonList(AUTHORITY),
            Collections.singletonList(route),
            ImmutableMap.of());
    xdsClient.deliverRdsUpdate(RDS_RESOURCE_NAME, Collections.singletonList(virtualHost));
    createAndDeliverClusterUpdates(xdsClient, cluster1);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    assertServiceConfigForLoadBalancingConfig(
        Collections.singletonList(cluster1),
        (Map<String, ?>) resolutionResultCaptor.getValue().getServiceConfig().getConfig());

    reset(mockListener);
    xdsClient.deliverRdsResourceNotFound(RDS_RESOURCE_NAME);  // revoke RDS resource
    assertEmptyResolutionResult(RDS_RESOURCE_NAME);

    // Simulate management server adds back the previously used RDS resource.
    reset(mockListener);
    xdsClient.deliverRdsUpdate(RDS_RESOURCE_NAME, Collections.singletonList(virtualHost));
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    assertServiceConfigForLoadBalancingConfig(
        Collections.singletonList(cluster1),
        (Map<String, ?>) resolutionResultCaptor.getValue().getServiceConfig().getConfig());
  }

  @Test
  public void resolving_encounterErrorLdsWatcherOnly() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverError(Status.UNAVAILABLE.withDescription("server unreachable"));
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    InternalConfigSelector configSelector = resolutionResultCaptor.getValue()
        .getAttributes().get(InternalConfigSelector.KEY);
    Result selectResult = configSelector.selectConfig(
        newPickSubchannelArgs(call1.methodDescriptor, new Metadata(), CallOptions.DEFAULT));
    Status error = selectResult.getStatus();
    assertThat(error.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(error.getDescription()).contains(AUTHORITY);
    assertThat(error.getDescription()).contains("UNAVAILABLE: server unreachable");
  }

  @Test
  public void resolving_translateErrorLds() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverError(Status.NOT_FOUND.withDescription("server unreachable"));
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    InternalConfigSelector configSelector = resolutionResultCaptor.getValue()
        .getAttributes().get(InternalConfigSelector.KEY);
    Result selectResult = configSelector.selectConfig(
        newPickSubchannelArgs(call1.methodDescriptor, new Metadata(), CallOptions.DEFAULT));
    Status error = selectResult.getStatus();
    assertThat(error.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(error.getDescription()).contains(AUTHORITY);
    assertThat(error.getDescription()).contains("NOT_FOUND: server unreachable");
    assertThat(error.getCause()).isNull();
  }

  @Test
  public void resolving_encounterErrorLdsAndRdsWatchers() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdateForRdsName(RDS_RESOURCE_NAME);
    xdsClient.deliverError(Status.UNAVAILABLE.withDescription("server unreachable"));
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    InternalConfigSelector configSelector = resolutionResultCaptor.getValue()
        .getAttributes().get(InternalConfigSelector.KEY);
    Result selectResult = configSelector.selectConfig(
        newPickSubchannelArgs(call1.methodDescriptor, new Metadata(), CallOptions.DEFAULT));
    Status error = selectResult.getStatus();
    assertThat(error.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(error.getDescription()).contains(RDS_RESOURCE_NAME);
    assertThat(error.getDescription()).contains("UNAVAILABLE: server unreachable");
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolving_matchingVirtualHostNotFound_matchingOverrideAuthority() {
    Route route = Route.forAction(RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
        RouteAction.forCluster(
            cluster1, Collections.emptyList(), TimeUnit.SECONDS.toNanos(15L), null, false),
        ImmutableMap.of());
    VirtualHost virtualHost =
        VirtualHost.create("virtualhost", Collections.singletonList("random"),
            Collections.singletonList(route),
            ImmutableMap.of());

    resolver = new XdsNameResolver(targetUri, null, AUTHORITY, "random",
        serviceConfigParser, syncContext, scheduler,
        xdsClientPoolFactory, mockRandom, FilterRegistry.getDefaultRegistry(), null,
        metricRecorder, nameResolverArgs);
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(0L, Arrays.asList(virtualHost));
    createAndDeliverClusterUpdates(xdsClient, cluster1);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    assertServiceConfigForLoadBalancingConfig(
        Collections.singletonList(cluster1),
        (Map<String, ?>) resolutionResultCaptor.getValue().getServiceConfig().getConfig());
  }

  @Test
  public void resolving_matchingVirtualHostNotFound_notMatchingOverrideAuthority() {
    Route route = Route.forAction(RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
        RouteAction.forCluster(
            cluster1, Collections.emptyList(), TimeUnit.SECONDS.toNanos(15L), null, false),
        ImmutableMap.of());
    VirtualHost virtualHost =
        VirtualHost.create("virtualhost", Collections.singletonList(AUTHORITY),
            Collections.singletonList(route),
            ImmutableMap.of());

    resolver = new XdsNameResolver(targetUri, null, AUTHORITY, "random",
        serviceConfigParser, syncContext, scheduler,
        xdsClientPoolFactory, mockRandom, FilterRegistry.getDefaultRegistry(), null,
        metricRecorder, nameResolverArgs);
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdateOnly(0L, Arrays.asList(virtualHost));
    fakeClock.forwardTime(15, TimeUnit.SECONDS);
    assertEmptyResolutionResult("random");
  }

  @Test
  public void resolving_matchingVirtualHostNotFoundForOverrideAuthority() {
    resolver = new XdsNameResolver(targetUri, null, AUTHORITY, AUTHORITY,
        serviceConfigParser, syncContext, scheduler,
        xdsClientPoolFactory, mockRandom, FilterRegistry.getDefaultRegistry(), null,
        metricRecorder, nameResolverArgs);
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(0L, buildUnmatchedVirtualHosts());
    assertEmptyResolutionResult(expectedLdsResourceName);
  }

  @Test
  public void resolving_matchingVirtualHostNotFoundInLdsResource() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(0L, buildUnmatchedVirtualHosts());
    assertEmptyResolutionResult(expectedLdsResourceName);
  }

  @Test
  public void resolving_matchingVirtualHostNotFoundInRdsResource() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdateForRdsName(RDS_RESOURCE_NAME);
    xdsClient.deliverRdsUpdate(RDS_RESOURCE_NAME, buildUnmatchedVirtualHosts());
    assertEmptyResolutionResult(expectedLdsResourceName);
  }

  private List<VirtualHost> buildUnmatchedVirtualHosts() {
    Route route1 = Route.forAction(RouteMatch.withPathExactOnly(call2.getFullMethodNameForPath()),
        RouteAction.forCluster(
            cluster2, Collections.emptyList(), TimeUnit.SECONDS.toNanos(15L), null, false),
        ImmutableMap.of());
    Route route2 = Route.forAction(RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
        RouteAction.forCluster(
            cluster1, Collections.emptyList(), TimeUnit.SECONDS.toNanos(15L), null, false),
        ImmutableMap.of());
    return Arrays.asList(
        VirtualHost.create("virtualhost-foo", Collections.singletonList("hello.googleapis.com"),
            Collections.singletonList(route1),
            ImmutableMap.of()),
        VirtualHost.create("virtualhost-bar", Collections.singletonList("hi.googleapis.com"),
            Collections.singletonList(route2),
            ImmutableMap.of()));
  }

  @Test
  public void resolved_noTimeout() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    Route route = Route.forAction(RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
        RouteAction.forCluster(
            cluster1, Collections.emptyList(), null, null, false), // per-route timeout unset
        ImmutableMap.of());
    VirtualHost virtualHost = VirtualHost.create("does not matter",
        Collections.singletonList(AUTHORITY), Collections.singletonList(route),
        ImmutableMap.of());
    xdsClient.deliverLdsUpdate(0L, Collections.singletonList(virtualHost));
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    assertCallSelectClusterResult(call1, configSelector, cluster1, null);
  }

  @Test
  public void resolved_fallbackToHttpMaxStreamDurationAsTimeout() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    Route route = Route.forAction(RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
        RouteAction.forCluster(
            cluster1, Collections.emptyList(), null, null, false), // per-route timeout unset
        ImmutableMap.of());
    VirtualHost virtualHost = VirtualHost.create("does not matter",
        Collections.singletonList(AUTHORITY), Collections.singletonList(route),
        ImmutableMap.of());
    xdsClient.deliverLdsUpdate(TimeUnit.SECONDS.toNanos(5L),
        Collections.singletonList(virtualHost));
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    assertCallSelectClusterResult(call1, configSelector, cluster1, 5.0);
  }

  @Test
  public void retryPolicyInPerMethodConfigGeneratedByResolverIsValid() {
    ServiceConfigParser realParser = new ScParser(
        true, 5, 5, new AutoConfiguredLoadBalancerFactory("pick-first"));
    resolver = new XdsNameResolver(targetUri, null, AUTHORITY, null, realParser, syncContext,
        scheduler, xdsClientPoolFactory, mockRandom, FilterRegistry.getDefaultRegistry(), null,
        metricRecorder, nameResolverArgs);
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    RetryPolicy retryPolicy = RetryPolicy.create(
        4, ImmutableList.of(Code.UNAVAILABLE), Durations.fromMillis(100),
        Durations.fromMillis(200), null);
    xdsClient.deliverLdsUpdate(
        Collections.singletonList(
            Route.forAction(
                RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
                RouteAction.forCluster(
                    cluster1,
                    Collections.emptyList(),
                    null,
                    retryPolicy,
                    false),
                ImmutableMap.of())));
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    Result selectResult = configSelector.selectConfig(
        newPickSubchannelArgs(call1.methodDescriptor, new Metadata(), CallOptions.DEFAULT));
    Object config = selectResult.getConfig();

    // Purely validating the data (io.grpc.internal.RetryPolicy).
    // However, there's no public accessor methods the data object.
    assertThat(config.getClass().getName())
        .isEqualTo("io.grpc.internal.ManagedChannelServiceConfig");
    assertThat(config.toString()).contains(
        MoreObjects.toStringHelper("RetryPolicy")
            .add("maxAttempts", 4)
            .add("initialBackoffNanos", TimeUnit.MILLISECONDS.toNanos(100))
            .add("maxBackoffNanos", TimeUnit.MILLISECONDS.toNanos(200))
            .add("backoffMultiplier", 2D)
            .add("perAttemptRecvTimeoutNanos", null)
            .add("retryableStatusCodes", ImmutableList.of(Code.UNAVAILABLE))
            .toString());
  }

  @Test
  public void resolved_simpleCallSucceeds() {
    InternalConfigSelector configSelector = resolveToClusters();
    assertCallSelectClusterResult(call1, configSelector, cluster1, 15.0);
    testCall.deliverResponseHeaders();
    verifyNoMoreInteractions(mockListener);
  }

  @Test
  public void resolved_simpleCallFailedToRoute_noMatchingRoute() {
    InternalConfigSelector configSelector = resolveToClusters();
    CallInfo call = new CallInfo("FooService", "barMethod");
    Result selectResult = configSelector.selectConfig(
        newPickSubchannelArgs(call.methodDescriptor, new Metadata(), CallOptions.DEFAULT));
    Status status = selectResult.getStatus();
    assertThat(status.isOk()).isFalse();
    assertThat(status.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(status.getDescription()).isEqualTo("Could not find xDS route matching RPC");
    verifyNoMoreInteractions(mockListener);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolved_simpleCallFailedToRoute_routeWithNonForwardingAction() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(
        Arrays.asList(
            Route.forNonForwardingAction(
                RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
                ImmutableMap.of()),
            Route.forAction(
                RouteMatch.withPathExactOnly(call2.getFullMethodNameForPath()),
                RouteAction.forCluster(cluster2, Collections.emptyList(),
                    TimeUnit.SECONDS.toNanos(15L), null, false),
                ImmutableMap.of())));
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    assertThat(result.getAddressesOrError().getValue()).isEmpty();
    assertServiceConfigForLoadBalancingConfig(
        Collections.singletonList(cluster2),
        (Map<String, ?>) result.getServiceConfig().getConfig());
    assertThat(result.getAttributes().get(XdsAttributes.XDS_CLIENT_POOL)).isNotNull();
    assertThat(result.getAttributes().get(XdsAttributes.CALL_COUNTER_PROVIDER)).isNotNull();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    // Simulates making a call1 RPC.
    Result selectResult = configSelector.selectConfig(
        newPickSubchannelArgs(call1.methodDescriptor, new Metadata(), CallOptions.DEFAULT));
    Status status = selectResult.getStatus();
    assertThat(status.isOk()).isFalse();
    assertThat(status.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(status.getDescription())
        .isEqualTo("Could not route RPC to Route with non-forwarding action");
    verifyNoMoreInteractions(mockListener);
  }

  @Test
  public void resolved_rpcHashingByHeader_withoutSubstitution() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(
        Collections.singletonList(
            Route.forAction(
                RouteMatch.withPathExactOnly(
                    "/" + TestMethodDescriptors.voidMethod().getFullMethodName()),
                RouteAction.forCluster(
                    cluster1,
                    Collections.singletonList(
                        HashPolicy.forHeader(false, "custom-key", null, null)),
                    null,
                    null,
                    false),
                ImmutableMap.of())));
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    InternalConfigSelector configSelector =
        resolutionResultCaptor.getValue().getAttributes().get(InternalConfigSelector.KEY);

    // First call, with header "custom-key": "custom-value".
    startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of("custom-key", "custom-value"), CallOptions.DEFAULT);
    long hash1 = testCall.callOptions.getOption(XdsNameResolver.RPC_HASH_KEY);

    // Second call, with header "custom-key": "custom-val".
    startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of("custom-key", "custom-val"),
        CallOptions.DEFAULT);
    long hash2 = testCall.callOptions.getOption(XdsNameResolver.RPC_HASH_KEY);

    assertThat(hash2).isNotEqualTo(hash1);
  }

  @Test
  public void resolved_rpcHashingByHeader_withSubstitution() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(
        Collections.singletonList(
            Route.forAction(
                RouteMatch.withPathExactOnly(
                    "/" + TestMethodDescriptors.voidMethod().getFullMethodName()),
                RouteAction.forCluster(
                    cluster1,
                    Collections.singletonList(
                        HashPolicy.forHeader(false, "custom-key", Pattern.compile("value"),
                            "val")),
                    null,
                    null,
                    false),
                ImmutableMap.of())));
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    InternalConfigSelector configSelector =
        resolutionResultCaptor.getValue().getAttributes().get(InternalConfigSelector.KEY);

    // First call, with header "custom-key": "custom-value".
    startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of("custom-key", "custom-value"), CallOptions.DEFAULT);
    long hash1 = testCall.callOptions.getOption(XdsNameResolver.RPC_HASH_KEY);

    // Second call, with header "custom-key": "custom-val", "another-key": "another-value".
    startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of("custom-key", "custom-val", "another-key", "another-value"),
        CallOptions.DEFAULT);
    long hash2 = testCall.callOptions.getOption(XdsNameResolver.RPC_HASH_KEY);

    // Third call, with header "custom-key": "value".
    startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of("custom-key", "value"), CallOptions.DEFAULT);
    long hash3 = testCall.callOptions.getOption(XdsNameResolver.RPC_HASH_KEY);

    assertThat(hash2).isEqualTo(hash1);
    assertThat(hash3).isNotEqualTo(hash1);
  }

  @Test
  public void resolved_rpcHashingByChannelId() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(
        Collections.singletonList(
            Route.forAction(
                RouteMatch.withPathExactOnly(
                    "/" + TestMethodDescriptors.voidMethod().getFullMethodName()),
                RouteAction.forCluster(
                    cluster1,
                    Collections.singletonList(HashPolicy.forChannelId(false)),
                    null,
                    null,
                    false),
                ImmutableMap.of())));
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    InternalConfigSelector configSelector =
        resolutionResultCaptor.getValue().getAttributes().get(InternalConfigSelector.KEY);

    // First call, with header "custom-key": "value1".
    startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of("custom-key", "value1"),
        CallOptions.DEFAULT);
    long hash1 = testCall.callOptions.getOption(XdsNameResolver.RPC_HASH_KEY);

    // Second call, with no custom header.
    startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        Collections.emptyMap(),
        CallOptions.DEFAULT);
    long hash2 = testCall.callOptions.getOption(XdsNameResolver.RPC_HASH_KEY);

    // A different resolver/Channel.
    resolver.shutdown();
    reset(mockListener);
    when(mockRandom.nextLong()).thenReturn(123L);
    resolver = new XdsNameResolver(targetUri, null, AUTHORITY, null, serviceConfigParser,
        syncContext, scheduler,
        xdsClientPoolFactory, mockRandom, FilterRegistry.getDefaultRegistry(), null,
        metricRecorder, nameResolverArgs);
    resolver.start(mockListener);
    xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(
        Collections.singletonList(
            Route.forAction(
                RouteMatch.withPathExactOnly(
                    "/" + TestMethodDescriptors.voidMethod().getFullMethodName()),
                RouteAction.forCluster(
                    cluster1,
                    Collections.singletonList(HashPolicy.forChannelId(false)),
                    null,
                    null,
                    false),
                ImmutableMap.of())));
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    configSelector = resolutionResultCaptor.getValue().getAttributes().get(
        InternalConfigSelector.KEY);

    // Third call, with no custom header.
    startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        Collections.emptyMap(),
        CallOptions.DEFAULT);
    long hash3 = testCall.callOptions.getOption(XdsNameResolver.RPC_HASH_KEY);

    assertThat(hash2).isEqualTo(hash1);
    assertThat(hash3).isNotEqualTo(hash1);
  }

  @Test
  public void resolved_routeActionHasAutoHostRewrite_emitsCallOptionForTheSame() {
    resolver = new XdsNameResolver(targetUri, null, AUTHORITY, null, serviceConfigParser,
        syncContext, scheduler, xdsClientPoolFactory, mockRandom,
        FilterRegistry.getDefaultRegistry(), null, metricRecorder, nameResolverArgs);
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(
        Collections.singletonList(
            Route.forAction(
                RouteMatch.withPathExactOnly(
                    "/" + TestMethodDescriptors.voidMethod().getFullMethodName()),
                RouteAction.forCluster(
                    cluster1,
                    Collections.singletonList(
                        HashPolicy.forHeader(false, "custom-key", null, null)),
                    null,
                    null,
                    true),
                ImmutableMap.of())));
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    InternalConfigSelector configSelector =
        resolutionResultCaptor.getValue().getAttributes().get(InternalConfigSelector.KEY);

    // First call, with header "custom-key": "custom-value".
    startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of("custom-key", "custom-value"), CallOptions.DEFAULT);

    assertThat(testCall.callOptions.getOption(XdsNameResolver.AUTO_HOST_REWRITE_KEY)).isTrue();
  }

  @Test
  public void resolved_routeActionNoAutoHostRewrite_doesntEmitCallOptionForTheSame() {
    resolver = new XdsNameResolver(targetUri, null, AUTHORITY, null, serviceConfigParser,
        syncContext, scheduler, xdsClientPoolFactory, mockRandom,
        FilterRegistry.getDefaultRegistry(), null, metricRecorder, nameResolverArgs);
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(
        Collections.singletonList(
            Route.forAction(
                RouteMatch.withPathExactOnly(
                    "/" + TestMethodDescriptors.voidMethod().getFullMethodName()),
                RouteAction.forCluster(
                    cluster1,
                    Collections.singletonList(
                        HashPolicy.forHeader(false, "custom-key", null, null)),
                    null,
                    null,
                    false),
                ImmutableMap.of())));
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    InternalConfigSelector configSelector =
        resolutionResultCaptor.getValue().getAttributes().get(InternalConfigSelector.KEY);

    // First call, with header "custom-key": "custom-value".
    startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of("custom-key", "custom-value"), CallOptions.DEFAULT);

    assertThat(testCall.callOptions.getOption(XdsNameResolver.AUTO_HOST_REWRITE_KEY)).isNull();
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolved_resourceUpdateAfterCallStarted() {
    InternalConfigSelector configSelector = resolveToClusters();
    assertCallSelectClusterResult(call1, configSelector, cluster1, 15.0);
    TestCall<?, ?> firstCall = testCall;

    reset(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(
        Arrays.asList(
            Route.forAction(
                RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
                RouteAction.forCluster(
                    "another-cluster", Collections.emptyList(),
                    TimeUnit.SECONDS.toNanos(20L), null, false),
                ImmutableMap.of()),
            Route.forAction(
                RouteMatch.withPathExactOnly(call2.getFullMethodNameForPath()),
                RouteAction.forCluster(
                    cluster2, Collections.emptyList(), TimeUnit.SECONDS.toNanos(15L),
                    null, false),
                ImmutableMap.of())));
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    // Updated service config still contains cluster1 while it is removed resource. New calls no
    // longer routed to cluster1.
    assertServiceConfigForLoadBalancingConfig(
        Arrays.asList(cluster1, cluster2, "another-cluster"),
        (Map<String, ?>) result.getServiceConfig().getConfig());
    assertThat(result.getAttributes().get(InternalConfigSelector.KEY))
        .isSameInstanceAs(configSelector);
    assertCallSelectClusterResult(call1, configSelector, "another-cluster", 20.0);

    firstCall.deliverErrorStatus();  // completes previous call
    verify(mockListener, times(2)).onResult2(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    assertServiceConfigForLoadBalancingConfig(
        Arrays.asList(cluster2, "another-cluster"),
        (Map<String, ?>) result.getServiceConfig().getConfig());
    verifyNoMoreInteractions(mockListener);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolved_resourceUpdatedBeforeCallStarted() {
    InternalConfigSelector configSelector = resolveToClusters();
    reset(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(
        Arrays.asList(
            Route.forAction(
                RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
                RouteAction.forCluster(
                    "another-cluster", Collections.emptyList(),
                    TimeUnit.SECONDS.toNanos(20L), null, false),
                ImmutableMap.of()),
            Route.forAction(
                RouteMatch.withPathExactOnly(call2.getFullMethodNameForPath()),
                RouteAction.forCluster(
                    cluster2, Collections.emptyList(), TimeUnit.SECONDS.toNanos(15L),
                    null, false),
                ImmutableMap.of())));
    // Two consecutive service config updates: one for removing clcuster1,
    // one for adding "another=cluster".
    verify(mockListener, times(2)).onResult2(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    assertServiceConfigForLoadBalancingConfig(
        Arrays.asList(cluster2, "another-cluster"),
        (Map<String, ?>) result.getServiceConfig().getConfig());
    assertThat(result.getAttributes().get(InternalConfigSelector.KEY))
        .isSameInstanceAs(configSelector);
    assertCallSelectClusterResult(call1, configSelector, "another-cluster", 20.0);

    verifyNoMoreInteractions(mockListener);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolved_raceBetweenCallAndRepeatedResourceUpdate() {
    InternalConfigSelector configSelector = resolveToClusters();
    assertCallSelectClusterResult(call1, configSelector, cluster1, 15.0);

    reset(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(
        Arrays.asList(
            Route.forAction(
                RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
                RouteAction.forCluster(
                    "another-cluster", Collections.emptyList(),
                    TimeUnit.SECONDS.toNanos(20L), null, false),
                ImmutableMap.of()),
            Route.forAction(
                RouteMatch.withPathExactOnly(call2.getFullMethodNameForPath()),
                RouteAction.forCluster(
                    cluster2, Collections.emptyList(),
                    TimeUnit.SECONDS.toNanos(15L), null, false),
                ImmutableMap.of())));

    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    assertServiceConfigForLoadBalancingConfig(
        Arrays.asList(cluster1, cluster2, "another-cluster"),
        (Map<String, ?>) result.getServiceConfig().getConfig());

    xdsClient.deliverLdsUpdate(
        Arrays.asList(
            Route.forAction(
                RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
                RouteAction.forCluster(
                    "another-cluster", Collections.emptyList(),
                    TimeUnit.SECONDS.toNanos(15L), null, false),
                ImmutableMap.of()),
            Route.forAction(
                RouteMatch.withPathExactOnly(call2.getFullMethodNameForPath()),
                RouteAction.forCluster(
                    cluster2, Collections.emptyList(),
                    TimeUnit.SECONDS.toNanos(15L), null, false),
                ImmutableMap.of())));
    verifyNoMoreInteractions(mockListener);  // no cluster added/deleted
    assertCallSelectClusterResult(call1, configSelector, "another-cluster", 15.0);
  }

  @Test
  public void resolved_raceBetweenClusterReleasedAndResourceUpdateAddBackAgain() {
    InternalConfigSelector configSelector = resolveToClusters();
    assertCallSelectClusterResult(call1, configSelector, cluster1, 15.0);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(
        Collections.singletonList(
            Route.forAction(
                RouteMatch.withPathExactOnly(call2.getFullMethodNameForPath()),
                RouteAction.forCluster(
                    cluster2, Collections.emptyList(), TimeUnit.SECONDS.toNanos(15L),
                    null, false),
                ImmutableMap.of())));
    xdsClient.deliverLdsUpdate(
        Arrays.asList(
            Route.forAction(
                RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
                RouteAction.forCluster(
                    cluster1, Collections.emptyList(), TimeUnit.SECONDS.toNanos(15L),
                    null, false),
                ImmutableMap.of()),
            Route.forAction(
                RouteMatch.withPathExactOnly(call2.getFullMethodNameForPath()),
                RouteAction.forCluster(
                    cluster2, Collections.emptyList(), TimeUnit.SECONDS.toNanos(15L),
                    null, false),
                ImmutableMap.of())));
    testCall.deliverErrorStatus();
    verifyNoMoreInteractions(mockListener);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolved_simpleCallSucceeds_routeToWeightedCluster() {
    when(mockRandom.nextInt(anyInt())).thenReturn(90, 10);
    when(mockRandom.nextLong(anyLong())).thenReturn(90L, 10L);
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(
        Collections.singletonList(
            Route.forAction(
                RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
                RouteAction.forWeightedClusters(
                    Arrays.asList(
                        ClusterWeight.create(cluster1, 20, ImmutableMap.of()),
                        ClusterWeight.create(
                            cluster2, 80, ImmutableMap.of())),
                    Collections.emptyList(),
                    TimeUnit.SECONDS.toNanos(20L),
                    null, false),
                ImmutableMap.of())));
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    assertThat(result.getAddressesOrError().getValue()).isEmpty();
    assertServiceConfigForLoadBalancingConfig(
        Arrays.asList(cluster1, cluster2), (Map<String, ?>) result.getServiceConfig().getConfig());
    assertThat(result.getAttributes().get(XdsAttributes.XDS_CLIENT_POOL)).isNotNull();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    assertCallSelectClusterResult(call1, configSelector, cluster2, 20.0);
    assertCallSelectClusterResult(call1, configSelector, cluster1, 20.0);
  }

  /** Creates and delivers both CDS and EDS updates for the given clusters. */
  private static void createAndDeliverClusterUpdates(
      FakeXdsClient xdsClient, String... clusterNames) {
    for (String clusterName : clusterNames) {
      CdsUpdate.Builder forEds = CdsUpdate
          .forEds(clusterName, clusterName, null, null, null, null, false)
              .roundRobinLbPolicy();
      xdsClient.deliverCdsUpdate(clusterName, forEds.build());
      EdsUpdate edsUpdate = new EdsUpdate(clusterName,
          XdsTestUtils.createMinimalLbEndpointsMap("host"), Collections.emptyList());
      xdsClient.deliverEdsUpdate(clusterName, edsUpdate);
    }
  }

  @Test
  public void resolved_simpleCallSucceeds_routeToRls() {
    when(mockRandom.nextInt(anyInt())).thenReturn(90, 10);
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(
        Collections.singletonList(
            Route.forAction(
                RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
                RouteAction.forClusterSpecifierPlugin(
                    NamedPluginConfig.create(
                        "rls-plugin-foo",
                        RlsPluginConfig.create(
                            ImmutableMap.of("lookupService", "rls-cbt.googleapis.com"))),
                    Collections.emptyList(),
                    TimeUnit.SECONDS.toNanos(20L),
                    null, false),
                ImmutableMap.of())));
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    assertThat(result.getAddressesOrError().getValue()).isEmpty();
    @SuppressWarnings("unchecked")
    Map<String, ?> resultServiceConfig = (Map<String, ?>) result.getServiceConfig().getConfig();
    List<Map<String, ?>> rawLbConfigs =
        JsonUtil.getListOfObjects(resultServiceConfig, "loadBalancingConfig");
    Map<String, ?> lbConfig = Iterables.getOnlyElement(rawLbConfigs);
    assertThat(lbConfig.keySet()).containsExactly("cluster_manager_experimental");
    Map<String, ?> clusterManagerLbConfig =
        JsonUtil.getObject(lbConfig, "cluster_manager_experimental");
    Map<String, ?> expectedRlsLbConfig = ImmutableMap.of(
        "routeLookupConfig",
        ImmutableMap.of("lookupService", "rls-cbt.googleapis.com"),
        "childPolicy",
        ImmutableList.of(ImmutableMap.of("cds_experimental", ImmutableMap.of())),
        "childPolicyConfigTargetFieldName",
        "cluster");
    Map<String, ?> expectedClusterManagerLbConfig = ImmutableMap.of(
        "childPolicy",
        ImmutableMap.of(
            "cluster_specifier_plugin:rls-plugin-foo",
            ImmutableMap.of(
                "lbPolicy",
                ImmutableList.of(ImmutableMap.of("rls_experimental", expectedRlsLbConfig)))));
    assertThat(clusterManagerLbConfig).isEqualTo(expectedClusterManagerLbConfig);

    assertThat(result.getAttributes().get(XdsAttributes.XDS_CLIENT_POOL)).isNotNull();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    assertCallSelectRlsPluginResult(
        call1, configSelector, "rls-plugin-foo", 20.0);

    // config changed
    xdsClient.deliverLdsUpdate(
        Collections.singletonList(
            Route.forAction(
                RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
                RouteAction.forClusterSpecifierPlugin(
                    NamedPluginConfig.create(
                        "rls-plugin-foo",
                        RlsPluginConfig.create(
                            // changed
                            ImmutableMap.of("lookupService", "rls-cbt-2.googleapis.com"))),
                    Collections.emptyList(),
                    // changed
                    TimeUnit.SECONDS.toNanos(30L),
                    null, false),
                ImmutableMap.of())));
    verify(mockListener, times(2)).onResult2(resolutionResultCaptor.capture());
    ResolutionResult result2 = resolutionResultCaptor.getValue();
    @SuppressWarnings("unchecked")
    Map<String, ?> resultServiceConfig2 = (Map<String, ?>) result2.getServiceConfig().getConfig();
    List<Map<String, ?>> rawLbConfigs2 =
        JsonUtil.getListOfObjects(resultServiceConfig2, "loadBalancingConfig");
    Map<String, ?> lbConfig2 = Iterables.getOnlyElement(rawLbConfigs2);
    assertThat(lbConfig2.keySet()).containsExactly("cluster_manager_experimental");
    Map<String, ?> clusterManagerLbConfig2 =
        JsonUtil.getObject(lbConfig2, "cluster_manager_experimental");
    Map<String, ?> expectedRlsLbConfig2 = ImmutableMap.of(
        "routeLookupConfig",
        ImmutableMap.of("lookupService", "rls-cbt-2.googleapis.com"),
        "childPolicy",
        ImmutableList.of(ImmutableMap.of("cds_experimental", ImmutableMap.of())),
        "childPolicyConfigTargetFieldName",
        "cluster");
    Map<String, ?> expectedClusterManagerLbConfig2 = ImmutableMap.of(
        "childPolicy",
        ImmutableMap.of(
            "cluster_specifier_plugin:rls-plugin-foo",
            ImmutableMap.of(
                "lbPolicy",
                ImmutableList.of(ImmutableMap.of("rls_experimental", expectedRlsLbConfig2)))));
    assertThat(clusterManagerLbConfig2).isEqualTo(expectedClusterManagerLbConfig2);

    InternalConfigSelector configSelector2 = result.getAttributes().get(InternalConfigSelector.KEY);
    assertCallSelectRlsPluginResult(
        call1, configSelector2, "rls-plugin-foo", 30.0);
  }

  // Begin filter state tests.

  /**
   * Verifies the lifecycle of HCM filter instances across LDS updates.
   *
   * <p>Filter instances:
   *   1. Must have one unique instance per HCM filter name.
   *   2. Must be reused when an LDS update with HCM contains a filter with the same name.
   *   3. Must be shutdown (closed) when an HCM in a LDS update doesn't a filter with the same name.
   */
  @Test
  public void filterState_survivesLds() {
    StatefulFilter.Provider statefulFilterProvider = filterStateTestSetupResolver();
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    VirtualHost vhost = filterStateTestVhost();

    // LDS 1.
    xdsClient.deliverLdsUpdateWithFilters(vhost, filterStateTestConfigs(STATEFUL_1, STATEFUL_2));
    createAndDeliverClusterUpdates(xdsClient, cluster1);
    assertClusterResolutionResult(call1, cluster1);
    ImmutableList<StatefulFilter> lds1Snapshot = statefulFilterProvider.getAllInstances();
    // Verify that StatefulFilter with different filter names result in different Filter instances.
    assertWithMessage("LDS 1: expected to create filter instances").that(lds1Snapshot).hasSize(2);
    // Naming: lds<LDS#>Filter<name#>
    StatefulFilter lds1Filter1 = lds1Snapshot.get(0);
    StatefulFilter lds1Filter2 = lds1Snapshot.get(1);
    assertThat(lds1Filter1).isNotSameInstanceAs(lds1Filter2);
    // Redundant check just in case StatefulFilter synchronization is broken.
    assertThat(lds1Filter1.idx).isEqualTo(0);
    assertThat(lds1Filter2.idx).isEqualTo(1);

    // LDS 2: filter configs with the same names.
    xdsClient.deliverLdsUpdateWithFilters(vhost, filterStateTestConfigs(STATEFUL_1, STATEFUL_2));
    assertClusterResolutionResult(call1, cluster1);
    ImmutableList<StatefulFilter> lds2Snapshot = statefulFilterProvider.getAllInstances();
    // Filter names hasn't changed, so expecting no new StatefulFilter instances.
    assertWithMessage("LDS 2: Expected Filter instances to be reused across LDS updates")
        .that(lds2Snapshot).isEqualTo(lds1Snapshot);

    // LDS 3: Filter "STATEFUL_2" removed.
    xdsClient.deliverLdsUpdateWithFilters(vhost, filterStateTestConfigs(STATEFUL_1));
    assertClusterResolutionResult(call1, cluster1);
    ImmutableList<StatefulFilter> lds3Snapshot = statefulFilterProvider.getAllInstances();
    // Again, no new StatefulFilter instances should be created.
    assertWithMessage("LDS 3: Expected Filter instances to be reused across LDS updates")
        .that(lds3Snapshot).isEqualTo(lds1Snapshot);
    // Verify the shutdown state.
    assertThat(lds1Filter1.isShutdown()).isFalse();
    assertWithMessage("LDS 3: Expected %s to be shut down", lds1Filter2)
        .that(lds1Filter2.isShutdown()).isTrue();

    // LDS 4: Filter "STATEFUL_2" added back.
    xdsClient.deliverLdsUpdateWithFilters(vhost, filterStateTestConfigs(STATEFUL_1, STATEFUL_2));
    assertClusterResolutionResult(call1, cluster1);
    ImmutableList<StatefulFilter> lds4Snapshot = statefulFilterProvider.getAllInstances();
    // Filter "STATEFUL_2" should be treated as any other new filter name in an LDS update:
    // a new instance should be created.
    assertWithMessage("LDS 4: Expected a new filter instance for %s", STATEFUL_2)
        .that(lds4Snapshot).hasSize(3);
    StatefulFilter lds4Filter2 = lds4Snapshot.get(2);
    assertThat(lds4Filter2.idx).isEqualTo(2);
    assertThat(lds4Filter2).isNotSameInstanceAs(lds1Filter2);
    assertThat(lds4Snapshot).containsAtLeastElementsIn(lds1Snapshot);
    // Verify the shutdown state.
    assertThat(lds1Filter1.isShutdown()).isFalse();
    assertThat(lds1Filter2.isShutdown()).isTrue();
    assertThat(lds4Filter2.isShutdown()).isFalse();
  }

  /**
   * Verifies the lifecycle of HCM filter instances across RDS updates.
   *
   * <p>Filter instances:
   *   1. Must have instantiated by the initial LDS/RDS.
   *   2. Must be reused by all subsequent RDS updates.
   *   3. Must be not shutdown (closed) by valid RDS updates.
   */
  @Test
  public void filterState_survivesRds() {
    StatefulFilter.Provider statefulFilterProvider = filterStateTestSetupResolver();
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();

    // LDS 1.
    xdsClient.deliverLdsUpdateForRdsNameWithFilters(RDS_RESOURCE_NAME,
        filterStateTestConfigs(STATEFUL_1, STATEFUL_2));
    // RDS 1.
    VirtualHost vhost1 = filterStateTestVhost();
    xdsClient.deliverRdsUpdate(RDS_RESOURCE_NAME, vhost1);
    createAndDeliverClusterUpdates(xdsClient, cluster1);
    assertClusterResolutionResult(call1, cluster1);
    // Initial RDS update should not generate Filter instances.
    ImmutableList<StatefulFilter> rds1Snapshot = statefulFilterProvider.getAllInstances();
    // Verify that StatefulFilter with different filter names result in different Filter instances.
    assertWithMessage("RDS 1: expected to create filter instances").that(rds1Snapshot).hasSize(2);
    // Naming: lds<LDS#>Filter<name#>
    StatefulFilter lds1Filter1 = rds1Snapshot.get(0);
    StatefulFilter lds1Filter2 = rds1Snapshot.get(1);
    assertThat(lds1Filter1).isNotSameInstanceAs(lds1Filter2);

    // RDS 2: exactly the same as RDS 1.
    xdsClient.deliverRdsUpdate(RDS_RESOURCE_NAME, vhost1);
    assertClusterResolutionResult(call1, cluster1);
    ImmutableList<StatefulFilter> rds2Snapshot = statefulFilterProvider.getAllInstances();
    // Neither should any subsequent RDS updates.
    assertWithMessage("RDS 2: Expected Filter instances to be reused across RDS route updates")
        .that(rds2Snapshot).isEqualTo(rds1Snapshot);

    // RDS 3: Contains a per-route override for STATEFUL_1.
    VirtualHost vhost3 = filterStateTestVhost(ImmutableMap.of(
        STATEFUL_1, new StatefulFilter.Config("RDS3")
    ));
    xdsClient.deliverRdsUpdate(RDS_RESOURCE_NAME, vhost3);
    assertClusterResolutionResult(call1, cluster1);
    ImmutableList<StatefulFilter> rds3Snapshot = statefulFilterProvider.getAllInstances();
    // As with any other Route update, typed_per_filter_config overrides should not result in
    // creating new filter instances.
    assertWithMessage("RDS 3: Expected Filter instances to be reused on per-route filter overrides")
        .that(rds3Snapshot).isEqualTo(rds1Snapshot);
  }

  /**
   * Verifies a special case where an existing filter is has a different typeUrl in a subsequent
   * LDS update.
   *
   * <p>Expectations:
   *   1. The old filter instance must be shutdown.
   *   2. A new filter instance must be created for the new filter with different typeUrl.
   */
  @Test
  public void filterState_specialCase_sameNameDifferentTypeUrl() {
    // Prepare filter registry with StatefulFilter of different typeUrl.
    StatefulFilter.Provider statefulFilterProvider = new StatefulFilter.Provider();
    String altTypeUrl = "type.googleapis.com/grpc.test.AltStatefulFilter";
    StatefulFilter.Provider altStatefulFilterProvider = new StatefulFilter.Provider(altTypeUrl);
    FilterRegistry filterRegistry = FilterRegistry.newRegistry()
        .register(statefulFilterProvider, altStatefulFilterProvider, ROUTER_FILTER_PROVIDER);
    resolver = new XdsNameResolver(targetUri, null, AUTHORITY, null, serviceConfigParser,
        syncContext, scheduler, xdsClientPoolFactory, mockRandom, filterRegistry, null,
        metricRecorder, nameResolverArgs);
    resolver.start(mockListener);

    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    VirtualHost vhost = filterStateTestVhost();

    // LDS 1.
    xdsClient.deliverLdsUpdateWithFilters(vhost, filterStateTestConfigs(STATEFUL_1, STATEFUL_2));
    createAndDeliverClusterUpdates(xdsClient, cluster1);
    assertClusterResolutionResult(call1, cluster1);
    ImmutableList<StatefulFilter> lds1Snapshot = statefulFilterProvider.getAllInstances();
    ImmutableList<StatefulFilter> lds1SnapshotAlt = altStatefulFilterProvider.getAllInstances();
    // Verify that StatefulFilter with different filter names result in different Filter instances.
    assertWithMessage("LDS 1: expected to create filter instances").that(lds1Snapshot).hasSize(2);
    // Naming: lds<LDS#>Filter<name#>
    StatefulFilter lds1Filter1 = lds1Snapshot.get(0);
    StatefulFilter lds1Filter2 = lds1Snapshot.get(1);
    assertThat(lds1Filter1).isNotSameInstanceAs(lds1Filter2);
    // Nothing in the alternative provider.
    assertThat(lds1SnapshotAlt).isEmpty();

    // LDS 2: Filter STATEFUL_2 present, but with a different typeUrl: altTypeUrl.
    ImmutableList<NamedFilterConfig> filterConfigs = ImmutableList.of(
        new NamedFilterConfig(STATEFUL_1, new StatefulFilter.Config(STATEFUL_1)),
        new NamedFilterConfig(STATEFUL_2, new StatefulFilter.Config(STATEFUL_2, altTypeUrl)),
        new NamedFilterConfig(ROUTER_FILTER_INSTANCE_NAME, RouterFilter.ROUTER_CONFIG)
    );
    xdsClient.deliverLdsUpdateWithFilters(vhost, filterConfigs);
    assertClusterResolutionResult(call1, cluster1);
    ImmutableList<StatefulFilter> lds2Snapshot = statefulFilterProvider.getAllInstances();
    ImmutableList<StatefulFilter> lds2SnapshotAlt = altStatefulFilterProvider.getAllInstances();
    // Filter "STATEFUL_2" has different typeUrl, and should be treated as a new filter.
    // No changes in the snapshot of normal stateful filters.
    assertWithMessage("LDS 2: expected a new filter instance of different type")
        .that(lds2Snapshot).isEqualTo(lds1Snapshot);
    // A new filter instance is created by altStatefulFilterProvider.
    assertWithMessage("LDS 2: expected a new filter instance for type %s", altTypeUrl)
        .that(lds2SnapshotAlt).hasSize(1);
    StatefulFilter lds2Filter2Alt = lds2SnapshotAlt.get(0);
    assertThat(lds2Filter2Alt).isNotSameInstanceAs(lds1Filter2);
    // Verify the shutdown state.
    assertThat(lds1Filter1.isShutdown()).isFalse();
    assertThat(lds1Filter2.isShutdown()).isTrue();
    assertThat(lds2Filter2Alt.isShutdown()).isFalse();
  }

  /**
   * Verifies that all filter instances are shutdown (closed) on LDS resource not found.
   */
  @Test
  public void filterState_shutdown_onLdsNotFound() {
    StatefulFilter.Provider statefulFilterProvider = filterStateTestSetupResolver();
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    VirtualHost vhost = filterStateTestVhost();

    // LDS 1.
    xdsClient.deliverLdsUpdateWithFilters(vhost, filterStateTestConfigs(STATEFUL_1, STATEFUL_2));
    createAndDeliverClusterUpdates(xdsClient, cluster1);
    assertClusterResolutionResult(call1, cluster1);
    ImmutableList<StatefulFilter> lds1Snapshot = statefulFilterProvider.getAllInstances();
    assertWithMessage("LDS 1: expected to create filter instances").that(lds1Snapshot).hasSize(2);
    // Naming: lds<LDS#>Filter<name#>
    StatefulFilter lds1Filter1 = lds1Snapshot.get(0);
    StatefulFilter lds1Filter2 = lds1Snapshot.get(1);

    // LDS 2: resource not found.
    reset(mockListener);
    xdsClient.deliverLdsResourceNotFound();
    assertEmptyResolutionResult(expectedLdsResourceName);
    // Verify shutdown.
    assertThat(lds1Filter1.isShutdown()).isTrue();
    assertThat(lds1Filter2.isShutdown()).isTrue();
  }

  /**
   * Verifies that all filter instances are shutdown (closed) on LDS ResourceWatcher shutdown.
   */
  @Test
  public void filterState_shutdown_onResolverShutdown() {
    StatefulFilter.Provider statefulFilterProvider = filterStateTestSetupResolver();
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    VirtualHost vhost = filterStateTestVhost();

    // LDS 1.
    xdsClient.deliverLdsUpdateWithFilters(vhost, filterStateTestConfigs(STATEFUL_1, STATEFUL_2));
    createAndDeliverClusterUpdates(xdsClient, cluster1);
    assertClusterResolutionResult(call1, cluster1);
    ImmutableList<StatefulFilter> lds1Snapshot = statefulFilterProvider.getAllInstances();
    assertWithMessage("LDS 1: expected to create filter instances").that(lds1Snapshot).hasSize(2);
    // Naming: lds<LDS#>Filter<name#>
    StatefulFilter lds1Filter1 = lds1Snapshot.get(0);
    StatefulFilter lds1Filter2 = lds1Snapshot.get(1);

    // Shutdown.
    resolver.shutdown();
    resolver = null;  // no need to shutdown again in the teardown.
    // Verify shutdown.
    assertThat(lds1Filter1.isShutdown()).isTrue();
    assertThat(lds1Filter2.isShutdown()).isTrue();
  }

  /**
   * Verifies that all filter instances are shutdown (closed) on RDS resource not found.
   */
  @Test
  public void filterState_shutdown_onRdsNotFound() {
    StatefulFilter.Provider statefulFilterProvider = filterStateTestSetupResolver();
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();

    // LDS 1.
    xdsClient.deliverLdsUpdateForRdsNameWithFilters(RDS_RESOURCE_NAME,
        filterStateTestConfigs(STATEFUL_1, STATEFUL_2));
    // RDS 1: Standard vhost with a route.
    xdsClient.deliverRdsUpdate(RDS_RESOURCE_NAME, filterStateTestVhost());
    createAndDeliverClusterUpdates(xdsClient, cluster1);
    assertClusterResolutionResult(call1, cluster1);
    ImmutableList<StatefulFilter> rds1Snapshot = statefulFilterProvider.getAllInstances();
    assertWithMessage("RDS 1: expected to create filter instances").that(rds1Snapshot).hasSize(2);
    // Naming: lds<LDS#>Filter<name#>
    StatefulFilter lds1Filter1 = rds1Snapshot.get(0);
    StatefulFilter lds1Filter2 = rds1Snapshot.get(1);

    // RDS 2: RDS_RESOURCE_NAME not found.
    reset(mockListener);
    xdsClient.deliverRdsResourceNotFound(RDS_RESOURCE_NAME);
    assertEmptyResolutionResult(RDS_RESOURCE_NAME);
    assertThat(lds1Filter1.isShutdown()).isTrue();
    assertThat(lds1Filter2.isShutdown()).isTrue();
  }

  private StatefulFilter.Provider filterStateTestSetupResolver() {
    StatefulFilter.Provider statefulFilterProvider = new StatefulFilter.Provider();
    FilterRegistry filterRegistry = FilterRegistry.newRegistry()
        .register(statefulFilterProvider, ROUTER_FILTER_PROVIDER);
    resolver = new XdsNameResolver(targetUri, null, AUTHORITY, null, serviceConfigParser,
        syncContext, scheduler, xdsClientPoolFactory, mockRandom, filterRegistry, null,
        metricRecorder, nameResolverArgs);
    resolver.start(mockListener);
    return statefulFilterProvider;
  }

  private ImmutableList<NamedFilterConfig> filterStateTestConfigs(String... names) {
    ImmutableList.Builder<NamedFilterConfig> result = ImmutableList.builder();
    for (String name : names) {
      result.add(new NamedFilterConfig(name, new StatefulFilter.Config(name)));
    }
    result.add(new NamedFilterConfig(ROUTER_FILTER_INSTANCE_NAME, RouterFilter.ROUTER_CONFIG));
    return result.build();
  }

  private Route filterStateTestRoute(ImmutableMap<String, FilterConfig> perRouteOverrides) {
    // Standard basic route for filterState tests.
    return Route.forAction(
        RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
        RouteAction.forCluster(cluster1, NO_HASH_POLICIES, null, null, true),
        perRouteOverrides);
  }

  private VirtualHost filterStateTestVhost() {
    return filterStateTestVhost(NO_FILTER_OVERRIDES);
  }

  private VirtualHost filterStateTestVhost(ImmutableMap<String, FilterConfig> perRouteOverrides) {
    return VirtualHost.create(
        "stateful-vhost",
        ImmutableList.of(expectedLdsResourceName),
        ImmutableList.of(filterStateTestRoute(perRouteOverrides)),
        NO_FILTER_OVERRIDES);
  }

  // End filter state tests.

  @SuppressWarnings("unchecked")
  private void assertEmptyResolutionResult(String resource) {
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    assertThat(result.getAddressesOrError().getValue()).isEmpty();
    assertThat((Map<String, ?>) result.getServiceConfig().getConfig()).isEmpty();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    Result configResult = configSelector.selectConfig(
        newPickSubchannelArgs(call1.methodDescriptor, new Metadata(), CallOptions.DEFAULT));
    assertThat(configResult.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(configResult.getStatus().getDescription()).contains(resource);
  }

  private void assertClusterResolutionResult(CallInfo call, String expectedCluster) {
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    assertCallSelectClusterResult(call, configSelector, expectedCluster, null);
  }

  private void assertCallSelectClusterResult(
      CallInfo call, InternalConfigSelector configSelector, String expectedCluster,
      @Nullable Double expectedTimeoutSec) {
    Result result = configSelector.selectConfig(
        newPickSubchannelArgs(call.methodDescriptor, new Metadata(), CallOptions.DEFAULT));
    assertThat(result.getStatus().isOk()).isTrue();
    ClientInterceptor interceptor = result.getInterceptor();
    ClientCall<Void, Void> clientCall = interceptor.interceptCall(
        call.methodDescriptor, CallOptions.DEFAULT, channel);
    clientCall.start(new NoopClientCallListener<>(), new Metadata());
    assertThat(testCall.callOptions.getOption(XdsNameResolver.CLUSTER_SELECTION_KEY))
        .isEqualTo("cluster:" + expectedCluster);
    XdsConfig xdsConfig =
        testCall.callOptions.getOption(XdsNameResolver.XDS_CONFIG_CALL_OPTION_KEY);
    assertThat(xdsConfig).isNotNull();
    assertThat(xdsConfig.getClusters()).containsKey(expectedCluster); // Without "cluster:" prefix
    @SuppressWarnings("unchecked")
    Map<String, ?> config = (Map<String, ?>) result.getConfig();
    if (expectedTimeoutSec != null) {
      // Verify the raw service config contains a single method config for method with the
      // specified timeout.
      List<Map<String, ?>> rawMethodConfigs =
          JsonUtil.getListOfObjects(config, "methodConfig");
      Map<String, ?> methodConfig = Iterables.getOnlyElement(rawMethodConfigs);
      List<Map<String, ?>> methods = JsonUtil.getListOfObjects(methodConfig, "name");
      assertThat(Iterables.getOnlyElement(methods)).isEmpty();
      assertThat(JsonUtil.getString(methodConfig, "timeout")).isEqualTo(expectedTimeoutSec + "s");
    } else {
      assertThat(config).isEmpty();
    }
  }

  private void assertCallSelectRlsPluginResult(
      CallInfo call, InternalConfigSelector configSelector, String expectedPluginName,
      Double expectedTimeoutSec) {
    Result result = configSelector.selectConfig(
        newPickSubchannelArgs(call.methodDescriptor, new Metadata(), CallOptions.DEFAULT));
    assertThat(result.getStatus().isOk()).isTrue();
    ClientInterceptor interceptor = result.getInterceptor();
    ClientCall<Void, Void> clientCall = interceptor.interceptCall(
        call.methodDescriptor, CallOptions.DEFAULT, channel);
    clientCall.start(new NoopClientCallListener<>(), new Metadata());
    assertThat(testCall.callOptions.getOption(XdsNameResolver.CLUSTER_SELECTION_KEY))
        .isEqualTo("cluster_specifier_plugin:" + expectedPluginName);
    @SuppressWarnings("unchecked")
    Map<String, ?> config = (Map<String, ?>) result.getConfig();
    List<Map<String, ?>> rawMethodConfigs =
        JsonUtil.getListOfObjects(config, "methodConfig");
    Map<String, ?> methodConfig = Iterables.getOnlyElement(rawMethodConfigs);
    List<Map<String, ?>> methods = JsonUtil.getListOfObjects(methodConfig, "name");
    assertThat(Iterables.getOnlyElement(methods)).isEmpty();
    assertThat(JsonUtil.getString(methodConfig, "timeout")).isEqualTo(expectedTimeoutSec + "s");
  }

  @SuppressWarnings("unchecked")
  private InternalConfigSelector resolveToClusters() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(
        Arrays.asList(
            Route.forAction(
                RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
                RouteAction.forCluster(
                    cluster1, Collections.emptyList(), TimeUnit.SECONDS.toNanos(15L),
                    null, false),
                ImmutableMap.of()),
            Route.forAction(
                RouteMatch.withPathExactOnly(call2.getFullMethodNameForPath()),
                RouteAction.forCluster(
                    cluster2, Collections.emptyList(), TimeUnit.SECONDS.toNanos(15L),
                    null, false),
                ImmutableMap.of())));
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    assertThat(result.getAddressesOrError().getValue()).isEmpty();
    assertServiceConfigForLoadBalancingConfig(
        Arrays.asList(cluster1, cluster2), (Map<String, ?>) result.getServiceConfig().getConfig());
    assertThat(result.getAttributes().get(XdsAttributes.XDS_CLIENT_POOL)).isNotNull();
    assertThat(result.getAttributes().get(XdsAttributes.CALL_COUNTER_PROVIDER)).isNotNull();
    return result.getAttributes().get(InternalConfigSelector.KEY);
  }

  /**
   * Verifies the raw service config contains an xDS load balancing config for the given clusters.
   */
  private static void assertServiceConfigForLoadBalancingConfig(
      List<String> clusters, Map<String, ?> actualServiceConfig) {
    List<Map<String, ?>> rawLbConfigs =
        JsonUtil.getListOfObjects(actualServiceConfig, "loadBalancingConfig");
    Map<String, ?> lbConfig = Iterables.getOnlyElement(rawLbConfigs);
    assertThat(lbConfig.keySet()).containsExactly("cluster_manager_experimental");
    Map<String, ?> clusterManagerLbConfig =
        JsonUtil.getObject(lbConfig, "cluster_manager_experimental");
    Map<String, ?> clusterManagerChildLbPolicies =
        JsonUtil.getObject(clusterManagerLbConfig, "childPolicy");
    List<String> expectedChildLbClusterNames = new ArrayList<>(clusters.size());
    for (String cluster : clusters) {
      expectedChildLbClusterNames.add("cluster:" + cluster);
    }
    assertThat(clusterManagerChildLbPolicies.keySet())
        .containsExactlyElementsIn(expectedChildLbClusterNames);
    for (int i = 0; i < clusters.size(); i++) {
      Map<String, ?> childLbConfig =
          JsonUtil.getObject(clusterManagerChildLbPolicies, expectedChildLbClusterNames.get(i));
      assertThat(childLbConfig.keySet()).containsExactly("lbPolicy");
      List<Map<String, ?>> childLbConfigValues =
          JsonUtil.getListOfObjects(childLbConfig, "lbPolicy");
      Map<String, ?> cdsLbPolicy = Iterables.getOnlyElement(childLbConfigValues);
      assertThat(cdsLbPolicy.keySet()).containsExactly("cds_experimental");
      assertThat(JsonUtil.getObject(cdsLbPolicy, "cds_experimental"))
          .containsExactly("cluster", clusters.get(i));
    }
  }

  @Test
  public void generateServiceConfig_forClusterManagerLoadBalancingConfig() throws IOException {
    Route route1 = Route.forAction(
        RouteMatch.withPathExactOnly("HelloService/hi"),
        RouteAction.forCluster(
            "cluster-foo", Collections.emptyList(), TimeUnit.SECONDS.toNanos(15L), null, false),
        ImmutableMap.of());
    Route route2 = Route.forAction(
        RouteMatch.withPathExactOnly("HelloService/hello"),
        RouteAction.forWeightedClusters(
            ImmutableList.of(
                ClusterWeight.create("cluster-bar", 50, ImmutableMap.of()),
                ClusterWeight.create("cluster-baz", 50, ImmutableMap.of())),
            ImmutableList.of(),
            TimeUnit.SECONDS.toNanos(15L),
            null, false),
        ImmutableMap.of());
    Map<String, ?> rlsConfig = ImmutableMap.of("lookupService", "rls.bigtable.google.com");
    Route route3 = Route.forAction(
        RouteMatch.withPathExactOnly("HelloService/greetings"),
        RouteAction.forClusterSpecifierPlugin(
            NamedPluginConfig.create("plugin-foo", RlsPluginConfig.create(rlsConfig)),
            Collections.emptyList(),
            TimeUnit.SECONDS.toNanos(20L),
            null, false),
        ImmutableMap.of());

    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdateForRdsName(RDS_RESOURCE_NAME);
    VirtualHost virtualHost =
        VirtualHost.create("virtualhost", Collections.singletonList(AUTHORITY),
            ImmutableList.of(route1, route2, route3),
            ImmutableMap.of());
    xdsClient.deliverRdsUpdate(RDS_RESOURCE_NAME, Collections.singletonList(virtualHost));
    createAndDeliverClusterUpdates(xdsClient, "cluster-foo", "cluster-bar", "cluster-baz");

    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    String expectedServiceConfigJson =
        "{\n"
            + "  \"loadBalancingConfig\": [{\n"
            + "    \"cluster_manager_experimental\": {\n"
            + "      \"childPolicy\": {\n"
            + "        \"cluster:cluster-foo\": {\n"
            + "          \"lbPolicy\": [{\n"
            + "            \"cds_experimental\": {\n"
            + "              \"cluster\": \"cluster-foo\"\n"
            + "            }\n"
            + "          }]\n"
            + "        },\n"
            + "        \"cluster:cluster-bar\": {\n"
            + "          \"lbPolicy\": [{\n"
            + "            \"cds_experimental\": {\n"
            + "              \"cluster\": \"cluster-bar\"\n"
            + "            }\n"
            + "          }]\n"
            + "        },\n"
            + "        \"cluster:cluster-baz\": {\n"
            + "          \"lbPolicy\": [{\n"
            + "            \"cds_experimental\": {\n"
            + "              \"cluster\": \"cluster-baz\"\n"
            + "            }\n"
            + "          }]\n"
            + "        },\n"
            + "        \"cluster_specifier_plugin:plugin-foo\": {\n"
            + "          \"lbPolicy\": [{\n"
            + "            \"rls_experimental\": {\n"
            + "              \"routeLookupConfig\": {\n"
            + "                \"lookupService\": \"rls.bigtable.google.com\"\n"
            + "              },\n"
            + "              \"childPolicy\": [\n"
            + "                {\"cds_experimental\": {}}\n"
            + "              ],\n"
            + "              \"childPolicyConfigTargetFieldName\": \"cluster\"\n"
            + "            }\n"
            + "          }]\n"
            + "        }\n"
            + "      }\n"
            + "    }\n"
            + "  }]\n"
            + "}";
    assertThat(resolutionResultCaptor.getValue().getServiceConfig().getConfig())
        .isEqualTo(JsonParser.parse(expectedServiceConfigJson));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void generateServiceConfig_forPerMethodConfig() throws IOException {
    long timeoutNano = TimeUnit.SECONDS.toNanos(1L) + 1L; // 1.0000000001s
    RetryPolicy retryPolicy = RetryPolicy.create(
        4, ImmutableList.of(Code.UNAVAILABLE, Code.CANCELLED), Durations.fromMillis(100),
        Durations.fromMillis(200), null);
    RetryPolicy retryPolicyWithEmptyStatusCodes = RetryPolicy.create(
        4, ImmutableList.of(), Durations.fromMillis(100), Durations.fromMillis(200), null);

    // timeout only
    String expectedServiceConfigJson = "{\n"
        + "  \"methodConfig\": [{\n"
        + "    \"name\": [ {} ],\n"
        + "    \"timeout\": \"1.000000001s\"\n"
        + "  }]\n"
        + "}";
    Map<String, ?> expectedServiceConfig =
        (Map<String, ?>) JsonParser.parse(expectedServiceConfigJson);
    assertThat(XdsNameResolver.generateServiceConfigWithMethodConfig(timeoutNano, null))
        .isEqualTo(expectedServiceConfig);

    // timeout and retry with empty retriable status codes
    assertThat(XdsNameResolver.generateServiceConfigWithMethodConfig(
            timeoutNano, retryPolicyWithEmptyStatusCodes))
        .isEqualTo(expectedServiceConfig);

    // retry only
    expectedServiceConfigJson = "{\n"
        + "  \"methodConfig\": [{\n"
        + "    \"name\": [ {} ],\n"
        + "    \"retryPolicy\": {\n"
        + "      \"maxAttempts\" : 4,\n"
        + "      \"initialBackoff\": \"0.100s\",\n"
        + "      \"maxBackoff\": \"0.200s\",\n"
        + "      \"backoffMultiplier\": 2,\n"
        + "      \"retryableStatusCodes\": [\n"
        + "        \"UNAVAILABLE\", \"CANCELLED\"\n"
        + "      ]\n"
        + "    }\n"
        + "  }]\n"
        + "}";
    expectedServiceConfig =
        (Map<String, ?>) JsonParser.parse(expectedServiceConfigJson);
    assertThat(XdsNameResolver.generateServiceConfigWithMethodConfig(null, retryPolicy))
        .isEqualTo(expectedServiceConfig);

    // timeout and retry
    expectedServiceConfigJson = "{\n"
        + "  \"methodConfig\": [{\n"
        + "    \"name\": [ {} ],\n"
        + "    \"retryPolicy\": {\n"
        + "      \"maxAttempts\" : 4,\n"
        + "      \"initialBackoff\": \"0.100s\",\n"
        + "      \"maxBackoff\": \"0.200s\",\n"
        + "      \"backoffMultiplier\": 2,\n"
        + "      \"retryableStatusCodes\": [\n"
        + "        \"UNAVAILABLE\", \"CANCELLED\"\n"
        + "      ]\n"
        + "    },\n"
        + "    \"timeout\": \"1.000000001s\"\n"
        + "  }]\n"
        + "}";
    expectedServiceConfig =
        (Map<String, ?>) JsonParser.parse(expectedServiceConfigJson);
    assertThat(XdsNameResolver.generateServiceConfigWithMethodConfig(timeoutNano, retryPolicy))
        .isEqualTo(expectedServiceConfig);

    // no timeout and no retry
    expectedServiceConfigJson = "{}";
    expectedServiceConfig =
        (Map<String, ?>) JsonParser.parse(expectedServiceConfigJson);
    assertThat(XdsNameResolver.generateServiceConfigWithMethodConfig(null, null))
        .isEqualTo(expectedServiceConfig);

    // retry with emtry retriable status codes only
    assertThat(XdsNameResolver.generateServiceConfigWithMethodConfig(
            null, retryPolicyWithEmptyStatusCodes))
        .isEqualTo(expectedServiceConfig);
  }

  @Test
  public void matchHostName_exactlyMatch() {
    String pattern = "foo.googleapis.com";
    assertThat(XdsNameResolver.matchHostName("bar.googleapis.com", pattern)).isFalse();
    assertThat(XdsNameResolver.matchHostName("fo.googleapis.com", pattern)).isFalse();
    assertThat(XdsNameResolver.matchHostName("oo.googleapis.com", pattern)).isFalse();
    assertThat(XdsNameResolver.matchHostName("googleapis.com", pattern)).isFalse();
    assertThat(XdsNameResolver.matchHostName("foo.googleapis", pattern)).isFalse();
    assertThat(XdsNameResolver.matchHostName("foo.googleapis.com", pattern)).isTrue();
  }

  @Test
  public void matchHostName_prefixWildcard() {
    String pattern = "*.foo.googleapis.com";
    assertThat(XdsNameResolver.matchHostName("foo.googleapis.com", pattern)).isFalse();
    assertThat(XdsNameResolver.matchHostName("bar-baz.foo.googleapis", pattern)).isFalse();
    assertThat(XdsNameResolver.matchHostName("bar.foo.googleapis.com", pattern)).isTrue();
    pattern = "*-bar.foo.googleapis.com";
    assertThat(XdsNameResolver.matchHostName("bar.foo.googleapis.com", pattern)).isFalse();
    assertThat(XdsNameResolver.matchHostName("baz-bar.foo.googleapis", pattern)).isFalse();
    assertThat(XdsNameResolver.matchHostName("-bar.foo.googleapis.com", pattern)).isFalse();
    assertThat(XdsNameResolver.matchHostName("baz-bar.foo.googleapis.com", pattern))
        .isTrue();
  }

  @Test
  public void matchHostName_postfixWildCard() {
    String pattern = "foo.*";
    assertThat(XdsNameResolver.matchHostName("bar.googleapis.com", pattern)).isFalse();
    assertThat(XdsNameResolver.matchHostName("bar.foo.googleapis.com", pattern)).isFalse();
    assertThat(XdsNameResolver.matchHostName("foo.googleapis.com", pattern)).isTrue();
    assertThat(XdsNameResolver.matchHostName("foo.com", pattern)).isTrue();
    pattern = "foo-*";
    assertThat(XdsNameResolver.matchHostName("bar-.googleapis.com", pattern)).isFalse();
    assertThat(XdsNameResolver.matchHostName("foo.googleapis.com", pattern)).isFalse();
    assertThat(XdsNameResolver.matchHostName("foo.googleapis.com", pattern)).isFalse();
    assertThat(XdsNameResolver.matchHostName("foo-", pattern)).isFalse();
    assertThat(XdsNameResolver.matchHostName("foo-bar.com", pattern)).isTrue();
    assertThat(XdsNameResolver.matchHostName("foo-.com", pattern)).isTrue();
    assertThat(XdsNameResolver.matchHostName("foo-bar", pattern)).isTrue();
  }

  @Test
  public void resolved_faultAbortInLdsUpdate() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    when(mockRandom.nextInt(1000_000)).thenReturn(500_000); // 50%

    // header abort, header abort rate = 60 %
    FaultConfig httpFilterFaultConfig = FaultConfig.create(
        null,
        FaultAbort.forHeader(FaultConfig.FractionalPercent.perHundred(70)),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    // no header abort key provided in metadata, rpc should succeed
    ClientCall.Listener<Void> observer = startNewCall(TestMethodDescriptors.voidMethod(),
        configSelector, Collections.emptyMap(), CallOptions.DEFAULT);
    verifyRpcSucceeded(observer);
    // header abort http status key provided, rpc should fail
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of(HEADER_ABORT_HTTP_STATUS_KEY.name(), "404",
            HEADER_ABORT_PERCENTAGE_KEY.name(), "60"), CallOptions.DEFAULT);
    verifyRpcFailed(
        observer,
        Status.UNIMPLEMENTED.withDescription(
            "RPC terminated due to fault injection: HTTP status code 404"));
    // header abort grpc status key provided, rpc should fail
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of(HEADER_ABORT_GRPC_STATUS_KEY.name(),
            String.valueOf(Status.UNAUTHENTICATED.getCode().value()),
            HEADER_ABORT_PERCENTAGE_KEY.name(), "60"), CallOptions.DEFAULT);
    verifyRpcFailed(
        observer, Status.UNAUTHENTICATED.withDescription("RPC terminated due to fault injection"));
    // header abort, both http and grpc code keys provided, rpc should fail with http code
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of(HEADER_ABORT_HTTP_STATUS_KEY.name(), "404",
            HEADER_ABORT_GRPC_STATUS_KEY.name(),
            String.valueOf(Status.UNAUTHENTICATED.getCode().value()),
            HEADER_ABORT_PERCENTAGE_KEY.name(), "60"), CallOptions.DEFAULT);
    verifyRpcFailed(
        observer,
        Status.UNIMPLEMENTED.withDescription(
            "RPC terminated due to fault injection: HTTP status code 404"));

    // header abort, no header rate, fix rate = 60 %
    httpFilterFaultConfig = FaultConfig.create(
        null,
        FaultAbort.forHeader(FaultConfig.FractionalPercent.perMillion(600_000)),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of(HEADER_ABORT_HTTP_STATUS_KEY.name(), "404"), CallOptions.DEFAULT);
    verifyRpcFailed(
        observer,
        Status.UNIMPLEMENTED.withDescription(
            "RPC terminated due to fault injection: HTTP status code 404"));

    // header abort, no header rate, fix rate = 0
    httpFilterFaultConfig = FaultConfig.create(
        null,
        FaultAbort.forHeader(FaultConfig.FractionalPercent.perMillion(0)),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of(HEADER_ABORT_HTTP_STATUS_KEY.name(), "404"), CallOptions.DEFAULT);
    verifyRpcSucceeded(observer);

    // fixed abort, fix rate = 60%
    httpFilterFaultConfig = FaultConfig.create(
        null,
        FaultAbort.forStatus(
            Status.UNAUTHENTICATED.withDescription("unauthenticated"),
            FaultConfig.FractionalPercent.perMillion(600_000)),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        Collections.emptyMap(), CallOptions.DEFAULT);
    verifyRpcFailed(
        observer,
        Status.UNAUTHENTICATED.withDescription(
            "RPC terminated due to fault injection: unauthenticated"));

    // fixed abort, fix rate = 40%
    httpFilterFaultConfig = FaultConfig.create(
        null,
        FaultAbort.forStatus(
            Status.UNAUTHENTICATED.withDescription("unauthenticated"),
            FaultConfig.FractionalPercent.perMillion(400_000)),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        Collections.emptyMap(), CallOptions.DEFAULT);
    verifyRpcSucceeded(observer);
  }

  @Test
  public void resolved_faultDelayInLdsUpdate() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    when(mockRandom.nextInt(1000_000)).thenReturn(500_000); // 50%

    // header delay, header delay rate = 60 %
    FaultConfig httpFilterFaultConfig = FaultConfig.create(
        FaultDelay.forHeader(FaultConfig.FractionalPercent.perHundred(70)), null, null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    // no header delay key provided in metadata, rpc should succeed immediately
    ClientCall.Listener<Void> observer = startNewCall(TestMethodDescriptors.voidMethod(),
        configSelector, Collections.emptyMap(), CallOptions.DEFAULT);
    verifyRpcSucceeded(observer);
    // header delay key provided, rpc should be delayed
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of(HEADER_DELAY_KEY.name(), "1000", HEADER_DELAY_PERCENTAGE_KEY.name(), "60"),
        CallOptions.DEFAULT);
    verifyRpcDelayed(observer, TimeUnit.MILLISECONDS.toNanos(1000));

    // header delay, no header rate, fix rate = 60 %
    httpFilterFaultConfig = FaultConfig.create(
        FaultDelay.forHeader(FaultConfig.FractionalPercent.perMillion(600_000)), null, null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of(HEADER_DELAY_KEY.name(), "1000"), CallOptions.DEFAULT);
    verifyRpcDelayed(observer, TimeUnit.MILLISECONDS.toNanos(1000));

    // header delay, no header rate, fix rate = 0
    httpFilterFaultConfig = FaultConfig.create(
        FaultDelay.forHeader(FaultConfig.FractionalPercent.perMillion(0)), null, null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of(HEADER_DELAY_KEY.name(), "1000"), CallOptions.DEFAULT);
    verifyRpcSucceeded(observer);

    // fixed delay, fix rate = 60%
    httpFilterFaultConfig = FaultConfig.create(
        FaultDelay.forFixedDelay(5000L, FaultConfig.FractionalPercent.perMillion(600_000)),
        null,
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        Collections.emptyMap(), CallOptions.DEFAULT);
    verifyRpcDelayed(observer, 5000L);

    // fixed delay, fix rate = 40%
    httpFilterFaultConfig = FaultConfig.create(
        FaultDelay.forFixedDelay(5000L, FaultConfig.FractionalPercent.perMillion(400_000)),
        null,
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        Collections.emptyMap(), CallOptions.DEFAULT);
    verifyRpcSucceeded(observer);
  }

  @Test
  public void resolved_faultDelayWithMaxActiveStreamsInLdsUpdate() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    when(mockRandom.nextInt(1000_000)).thenReturn(500_000); // 50%

    FaultConfig httpFilterFaultConfig = FaultConfig.create(
        FaultDelay.forFixedDelay(5000L, FaultConfig.FractionalPercent.perMillion(1000_000)),
        null,
        /* maxActiveFaults= */ 1);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);

    // Send two calls, then the first call should delayed and the second call should not be delayed
    // because maxActiveFaults is exceeded.
    ClientCall.Listener<Void> observer1 = startNewCall(TestMethodDescriptors.voidMethod(),
        configSelector, Collections.emptyMap(), CallOptions.DEFAULT);
    assertThat(testCall).isNull();
    ClientCall.Listener<Void> observer2 = startNewCall(TestMethodDescriptors.voidMethod(),
        configSelector, Collections.emptyMap(), CallOptions.DEFAULT);
    verifyRpcSucceeded(observer2);
    verifyRpcDelayed(observer1, 5000L);
    // Once all calls are finished, new call should be delayed.
    ClientCall.Listener<Void> observer3 = startNewCall(TestMethodDescriptors.voidMethod(),
        configSelector, Collections.emptyMap(), CallOptions.DEFAULT);
    verifyRpcDelayed(observer3, 5000L);
  }

  @Test
  public void resolved_faultDelayInLdsUpdate_callWithEarlyDeadline() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    when(mockRandom.nextInt(1000_000)).thenReturn(500_000); // 50%

    FaultConfig httpFilterFaultConfig = FaultConfig.create(
        FaultDelay.forFixedDelay(5000L, FaultConfig.FractionalPercent.perMillion(1000_000)),
        null,
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);

    Deadline.Ticker fakeTicker = new Deadline.Ticker() {
      @Override
      public long nanoTime() {
        return fakeClock.getTicker().read();
      }
    };
    ClientCall.Listener<Void> observer = startNewCall(TestMethodDescriptors.voidMethod(),
        configSelector, Collections.emptyMap(), CallOptions.DEFAULT.withDeadline(
            Deadline.after(4000, TimeUnit.NANOSECONDS, fakeTicker)));
    assertThat(testCall).isNull();
    verifyRpcDelayedThenAborted(observer, 4000L, Status.DEADLINE_EXCEEDED.withDescription(
        "Deadline exceeded after up to 5000 ns of fault-injected delay:"
            + " Deadline CallOptions will be exceeded in 0.000004000s. "));
  }

  @Test
  public void resolved_faultAbortAndDelayInLdsUpdateInLdsUpdate() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    when(mockRandom.nextInt(1000_000)).thenReturn(500_000); // 50%

    FaultConfig httpFilterFaultConfig = FaultConfig.create(
        FaultDelay.forFixedDelay(5000L, FaultConfig.FractionalPercent.perMillion(1000_000)),
        FaultAbort.forStatus(
            Status.UNAUTHENTICATED.withDescription("unauthenticated"),
            FaultConfig.FractionalPercent.perMillion(1000_000)),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    ClientCall.Listener<Void> observer = startNewCall(TestMethodDescriptors.voidMethod(),
        configSelector, Collections.emptyMap(), CallOptions.DEFAULT);
    verifyRpcDelayedThenAborted(
        observer, 5000L,
        Status.UNAUTHENTICATED.withDescription(
            "RPC terminated due to fault injection: unauthenticated"));
  }

  @Test
  public void resolved_faultConfigOverrideInLdsUpdate() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    when(mockRandom.nextInt(1000_000)).thenReturn(500_000); // 50%

    FaultConfig httpFilterFaultConfig = FaultConfig.create(
        null,
        FaultAbort.forStatus(
            Status.UNAUTHENTICATED, FaultConfig.FractionalPercent.perMillion(1000_000)),
        null);
    // VirtualHost fault config override
    FaultConfig virtualHostFaultConfig = FaultConfig.create(
        null,
        FaultAbort.forStatus(Status.INTERNAL, FaultConfig.FractionalPercent.perMillion(1000_000)),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(
        cluster1, httpFilterFaultConfig, virtualHostFaultConfig, null, null);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    ClientCall.Listener<Void> observer = startNewCall(TestMethodDescriptors.voidMethod(),
        configSelector, Collections.emptyMap(), CallOptions.DEFAULT);
    verifyRpcFailed(
        observer, Status.INTERNAL.withDescription("RPC terminated due to fault injection"));

    // Route fault config override
    FaultConfig routeFaultConfig = FaultConfig.create(
        null,
        FaultAbort.forStatus(Status.UNKNOWN, FaultConfig.FractionalPercent.perMillion(1000_000)),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(
        cluster1, httpFilterFaultConfig, virtualHostFaultConfig, routeFaultConfig, null);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        Collections.emptyMap(), CallOptions.DEFAULT);
    verifyRpcFailed(
        observer, Status.UNKNOWN.withDescription("RPC terminated due to fault injection"));

    // WeightedCluster fault config override
    FaultConfig weightedClusterFaultConfig = FaultConfig.create(
        null,
        FaultAbort.forStatus(
            Status.UNAVAILABLE, FaultConfig.FractionalPercent.perMillion(1000_000)),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(
        cluster1, httpFilterFaultConfig, virtualHostFaultConfig, routeFaultConfig,
        weightedClusterFaultConfig);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        Collections.emptyMap(), CallOptions.DEFAULT);
    verifyRpcFailed(
        observer, Status.UNAVAILABLE.withDescription("RPC terminated due to fault injection"));
  }

  @Test
  public void resolved_faultConfigOverrideInLdsAndInRdsUpdate() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    when(mockRandom.nextInt(1000_000)).thenReturn(500_000); // 50%

    FaultConfig httpFilterFaultConfig = FaultConfig.create(
        null,
        FaultAbort.forStatus(
            Status.UNAUTHENTICATED, FaultConfig.FractionalPercent.perMillion(1000_000)),
        null);
    xdsClient.deliverLdsUpdateForRdsNameWithFaultInjection(
        RDS_RESOURCE_NAME, httpFilterFaultConfig);

    // Route fault config override
    FaultConfig routeFaultConfig = FaultConfig.create(
        null,
        FaultAbort.forStatus(Status.UNKNOWN, FaultConfig.FractionalPercent.perMillion(1000_000)),
        null);
    xdsClient.deliverRdsUpdateWithFaultInjection(RDS_RESOURCE_NAME, null, routeFaultConfig, null);
    verify(mockListener).onResult2(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    ClientCall.Listener<Void> observer = startNewCall(TestMethodDescriptors.voidMethod(),
        configSelector, Collections.emptyMap(), CallOptions.DEFAULT);
    verifyRpcFailed(
        observer, Status.UNKNOWN.withDescription("RPC terminated due to fault injection"));
  }

  private <ReqT, RespT> ClientCall.Listener<RespT> startNewCall(
      MethodDescriptor<ReqT, RespT> method, InternalConfigSelector selector,
      Map<String, String> headers, CallOptions callOptions) {
    Metadata metadata = new Metadata();
    for (String key : headers.keySet()) {
      metadata.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), headers.get(key));
    }
    @SuppressWarnings("unchecked")
    ClientCall.Listener<RespT> listener = mock(ClientCall.Listener.class);
    Result result = selector.selectConfig(newPickSubchannelArgs(method, metadata, callOptions));
    ClientCall<ReqT, RespT> call = ClientInterceptors.intercept(channel,
        result.getInterceptor()).newCall(method, callOptions);
    call.start(listener, metadata);
    return listener;
  }

  private void verifyRpcSucceeded(ClientCall.Listener<Void> observer) {
    assertThat(testCall).isNotNull();
    testCall.deliverResponseHeaders();
    testCall.deliverCompleted();
    verify(observer).onClose(eq(Status.OK), any(Metadata.class));
    testCall = null;
  }

  private void verifyRpcFailed(
      ClientCall.Listener<Void> listener, Status expectedStatus) {
    verify(listener).onClose(errorCaptor.capture(), any(Metadata.class));
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(expectedStatus.getCode());
    assertThat(errorCaptor.getValue().getDescription())
        .isEqualTo(expectedStatus.getDescription());
    assertThat(testCall).isNull();
  }

  private void verifyRpcDelayed(ClientCall.Listener<Void> observer, long expectedDelayNanos) {
    assertThat(testCall).isNull();
    verifyNoInteractions(observer);
    fakeClock.forwardNanos(expectedDelayNanos);
    verifyRpcSucceeded(observer);
  }

  private void verifyRpcDelayedThenAborted(
      ClientCall.Listener<Void> listener, long expectedDelayNanos, Status expectedStatus) {
    verifyNoInteractions(listener);
    fakeClock.forwardNanos(expectedDelayNanos);
    verifyRpcFailed(listener, expectedStatus);
  }

  private PickSubchannelArgs newPickSubchannelArgs(
      MethodDescriptor<?, ?> method, Metadata headers, CallOptions callOptions) {
    return new PickSubchannelArgsImpl(method, headers, callOptions, new PickDetailsConsumer() {});
  }

  private final class FakeXdsClientPoolFactory implements XdsClientPoolFactory {
    Set<String> targets = new HashSet<>();
    XdsClient xdsClient = new FakeXdsClient();

    @Override
    public void setBootstrapOverride(Map<String, ?> bootstrap) {}

    @Override
    @Nullable
    public ObjectPool<XdsClient> get(String target) {
      throw new UnsupportedOperationException("Should not be called");
    }

    @Override
    public ObjectPool<XdsClient> getOrCreate(String target, MetricRecorder metricRecorder)
        throws XdsInitializationException {
      targets.add(target);
      return new ObjectPool<XdsClient>() {
        @Override
        public XdsClient getObject() {
          return xdsClient;
        }

        @Override
        public XdsClient returnObject(Object object) {
          return null;
        }
      };
    }

    @Override
    public List<String> getTargets() {
      if (targets.isEmpty()) {
        List<String> targetList = new ArrayList<>();
        targetList.add(targetUri.toString());
        return targetList;
      }
      return new ArrayList<>(targets);
    }
  }

  private class FakeXdsClient extends XdsClient {
    // Should never be subscribing to more than one LDS and RDS resource at any point of time.
    private String ldsResource;  // should always be AUTHORITY
    private String rdsResource;
    private ResourceWatcher<LdsUpdate> ldsWatcher;
    private ResourceWatcher<RdsUpdate> rdsWatcher;
    private final Map<String, List<ResourceWatcher<CdsUpdate>>> cdsWatchers = new HashMap<>();
    private final Map<String, List<ResourceWatcher<EdsUpdate>>> edsWatchers = new HashMap<>();

    @Override
    public BootstrapInfo getBootstrapInfo() {
      return bootstrapInfo;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends ResourceUpdate> void watchXdsResource(XdsResourceType<T> resourceType,
            String resourceName,
            ResourceWatcher<T> watcher,
            Executor syncContext) {

      switch (resourceType.typeName()) {
        case "LDS":
          assertThat(ldsResource).isNull();
          assertThat(ldsWatcher).isNull();
          assertThat(resourceName).isEqualTo(expectedLdsResourceName);
          ldsResource = resourceName;
          ldsWatcher = (ResourceWatcher<LdsUpdate>) watcher;
          break;
        case "RDS":
          assertThat(rdsResource).isNull();
          assertThat(rdsWatcher).isNull();
          rdsResource = resourceName;
          rdsWatcher = (ResourceWatcher<RdsUpdate>) watcher;
          break;
        case "CDS":
          cdsWatchers.computeIfAbsent(resourceName, k -> new ArrayList<>())
              .add((ResourceWatcher<CdsUpdate>) watcher);
          break;
        case "EDS":
          edsWatchers.computeIfAbsent(resourceName, k -> new ArrayList<>())
              .add((ResourceWatcher<EdsUpdate>) watcher);
          break;
        default:
      }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends ResourceUpdate> void cancelXdsResourceWatch(XdsResourceType<T> type,
                                                                  String resourceName,
                                                                  ResourceWatcher<T> watcher) {
      switch (type.typeName()) {
        case "LDS":
          assertThat(ldsResource).isNotNull();
          assertThat(ldsWatcher).isNotNull();
          assertThat(resourceName).isEqualTo(expectedLdsResourceName);
          ldsResource = null;
          ldsWatcher = null;
          break;
        case "RDS":
          assertThat(rdsResource).isNotNull();
          assertThat(rdsWatcher).isNotNull();
          rdsResource = null;
          rdsWatcher = null;
          break;
        case "CDS":
          assertThat(cdsWatchers).containsKey(resourceName);
          assertThat(cdsWatchers.get(resourceName)).contains(watcher);
          cdsWatchers.get(resourceName).remove((ResourceWatcher<CdsUpdate>) watcher);
          break;
        case "EDS":
          assertThat(edsWatchers).containsKey(resourceName);
          assertThat(edsWatchers.get(resourceName)).contains(watcher);
          edsWatchers.get(resourceName).remove((ResourceWatcher<EdsUpdate>) watcher);
          break;
        default:
      }
    }

    void deliverLdsUpdateOnly(long httpMaxStreamDurationNano, List<VirtualHost> virtualHosts) {
      syncContext.execute(() -> {
        ldsWatcher.onChanged(LdsUpdate.forApiListener(HttpConnectionManager.forVirtualHosts(
            httpMaxStreamDurationNano, virtualHosts, null)));
      });
    }

    void deliverLdsUpdate(long httpMaxStreamDurationNano, List<VirtualHost> virtualHosts) {
      List<String> clusterNames = new ArrayList<>();
      for (VirtualHost vh : virtualHosts) {
        clusterNames.addAll(getClusterNames(vh.routes()));
      }

      syncContext.execute(() -> {
        ldsWatcher.onChanged(LdsUpdate.forApiListener(HttpConnectionManager.forVirtualHosts(
            httpMaxStreamDurationNano, virtualHosts, null)));
        createAndDeliverClusterUpdates(this, clusterNames.toArray(new String[0]));
      });
    }

    void deliverLdsUpdate(final List<Route> routes) {
      VirtualHost virtualHost =
          VirtualHost.create(
              "virtual-host", Collections.singletonList(expectedLdsResourceName), routes,
              ImmutableMap.of());
      List<String> clusterNames = getClusterNames(routes);

      syncContext.execute(() -> {
        ldsWatcher.onChanged(LdsUpdate.forApiListener(HttpConnectionManager.forVirtualHosts(
            0L, Collections.singletonList(virtualHost), null)));
        if (!clusterNames.isEmpty()) {
          createAndDeliverClusterUpdates(this, clusterNames.toArray(new String[0]));
        }
      });
    }

    void deliverLdsUpdateWithFilters(VirtualHost vhost, List<NamedFilterConfig> filterConfigs) {
      syncContext.execute(() -> {
        ldsWatcher.onChanged(LdsUpdate.forApiListener(HttpConnectionManager.forVirtualHosts(
            0L, Collections.singletonList(vhost), filterConfigs)));
      });
    }

    void deliverLdsUpdateWithFaultInjection(
        final String cluster,
        FaultConfig httpFilterFaultConfig,
        final FaultConfig virtualHostFaultConfig,
        final FaultConfig routeFaultConfig,
        final FaultConfig weightedClusterFaultConfig) {
      if (httpFilterFaultConfig == null) {
        httpFilterFaultConfig = FaultConfig.create(null, null, null);
      }
      List<NamedFilterConfig> filterChain = ImmutableList.of(
          new NamedFilterConfig(FAULT_FILTER_INSTANCE_NAME, httpFilterFaultConfig),
          new NamedFilterConfig(ROUTER_FILTER_INSTANCE_NAME, RouterFilter.ROUTER_CONFIG));
      ImmutableMap<String, FilterConfig> overrideConfig = weightedClusterFaultConfig == null
          ? ImmutableMap.of()
          : ImmutableMap.of(
              FAULT_FILTER_INSTANCE_NAME, weightedClusterFaultConfig);
      ClusterWeight clusterWeight =
          ClusterWeight.create(
              cluster, 100,
              overrideConfig);
      overrideConfig = routeFaultConfig == null
          ? ImmutableMap.of()
          : ImmutableMap.of(FAULT_FILTER_INSTANCE_NAME, routeFaultConfig);
      Route route = Route.forAction(
          RouteMatch.create(
              PathMatcher.fromPrefix("/", false), Collections.emptyList(), null),
          RouteAction.forWeightedClusters(
              Collections.singletonList(clusterWeight),
              Collections.emptyList(),
              null,
              null,
              false),
          overrideConfig);
      overrideConfig = virtualHostFaultConfig == null
          ? ImmutableMap.of()
          : ImmutableMap.of(
              FAULT_FILTER_INSTANCE_NAME, virtualHostFaultConfig);
      VirtualHost virtualHost = VirtualHost.create(
          "virtual-host",
          Collections.singletonList(expectedLdsResourceName),
          Collections.singletonList(route),
          overrideConfig);
      syncContext.execute(() -> {
        ldsWatcher.onChanged(LdsUpdate.forApiListener(HttpConnectionManager.forVirtualHosts(
            0L, Collections.singletonList(virtualHost), filterChain)));
        createAndDeliverClusterUpdates(this, cluster);
      });
    }

    void deliverLdsUpdateForRdsNameWithFaultInjection(
        final String rdsName, @Nullable FaultConfig httpFilterFaultConfig) {
      if (httpFilterFaultConfig == null) {
        httpFilterFaultConfig = FaultConfig.create(
            null, null, null);
      }
      ImmutableList<NamedFilterConfig> filterChain = ImmutableList.of(
          new NamedFilterConfig(FAULT_FILTER_INSTANCE_NAME, httpFilterFaultConfig),
          new NamedFilterConfig(ROUTER_FILTER_INSTANCE_NAME, RouterFilter.ROUTER_CONFIG));
      syncContext.execute(() -> {
        ldsWatcher.onChanged(LdsUpdate.forApiListener(HttpConnectionManager.forRdsName(
            0L, rdsName, filterChain)));
      });
    }

    void deliverLdsUpdateForRdsName(String rdsName) {
      deliverLdsUpdateForRdsNameWithFilters(rdsName, null);
    }

    void deliverLdsUpdateForRdsNameWithFilters(
        String rdsName,
        @Nullable List<NamedFilterConfig> filterConfigs) {
      syncContext.execute(() -> {
        ldsWatcher.onChanged(LdsUpdate.forApiListener(HttpConnectionManager.forRdsName(
            0, rdsName, filterConfigs)));
      });
    }

    void deliverLdsResourceNotFound() {
      syncContext.execute(() -> {
        ldsWatcher.onResourceDoesNotExist(expectedLdsResourceName);
      });
    }

    private List<String> getClusterNames(List<Route> routes) {
      List<String> clusterNames = new ArrayList<>();
      for (Route r : routes) {
        if (r.routeAction() == null) {
          continue;
        }
        String cluster = r.routeAction().cluster();
        if (cluster != null) {
          clusterNames.add(cluster);
        } else {
          List<ClusterWeight> weightedClusters = r.routeAction().weightedClusters();
          if (weightedClusters == null) {
            continue;
          }
          for (ClusterWeight wc : weightedClusters) {
            clusterNames.add(wc.name());
          }
        }
      }

      return clusterNames;
    }

    void deliverRdsUpdateWithFaultInjection(
        String resourceName, @Nullable FaultConfig virtualHostFaultConfig,
        @Nullable FaultConfig routFaultConfig, @Nullable FaultConfig weightedClusterFaultConfig) {
      if (!resourceName.equals(rdsResource)) {
        return;
      }
      ImmutableMap<String, FilterConfig> overrideConfig = weightedClusterFaultConfig == null
          ? ImmutableMap.of()
          : ImmutableMap.of(
              FAULT_FILTER_INSTANCE_NAME, weightedClusterFaultConfig);
      ClusterWeight clusterWeight =
          ClusterWeight.create(cluster1, 100, overrideConfig);
      overrideConfig = routFaultConfig == null
          ? ImmutableMap.of()
          : ImmutableMap.of(FAULT_FILTER_INSTANCE_NAME, routFaultConfig);
      Route route = Route.forAction(
          RouteMatch.create(
              PathMatcher.fromPrefix("/", false), Collections.emptyList(), null),
          RouteAction.forWeightedClusters(
              Collections.singletonList(clusterWeight),
              Collections.emptyList(),
              null,
              null,
              false),
          overrideConfig);
      overrideConfig = virtualHostFaultConfig == null
          ? ImmutableMap.of()
          : ImmutableMap.of(
              FAULT_FILTER_INSTANCE_NAME, virtualHostFaultConfig);
      VirtualHost virtualHost = VirtualHost.create(
          "virtual-host",
          Collections.singletonList(expectedLdsResourceName),
          Collections.singletonList(route),
          overrideConfig);
      syncContext.execute(() -> {
        rdsWatcher.onChanged(new RdsUpdate(Collections.singletonList(virtualHost)));
        createAndDeliverClusterUpdates(this, cluster1);
      });
    }

    void deliverRdsUpdate(String resourceName, List<VirtualHost> virtualHosts) {
      if (!resourceName.equals(rdsResource)) {
        return;
      }
      syncContext.execute(() -> {
        rdsWatcher.onChanged(new RdsUpdate(virtualHosts));
      });
    }

    void deliverRdsUpdate(String resourceName, VirtualHost virtualHost) {
      deliverRdsUpdate(resourceName, ImmutableList.of(virtualHost));
    }

    void deliverRdsResourceNotFound(String resourceName) {
      if (!resourceName.equals(rdsResource)) {
        return;
      }
      syncContext.execute(() -> {
        rdsWatcher.onResourceDoesNotExist(rdsResource);
      });
    }

    private void deliverCdsUpdate(String clusterName, CdsUpdate update) {
      if (!cdsWatchers.containsKey(clusterName)) {
        return;
      }
      syncContext.execute(() -> {
        List<ResourceWatcher<CdsUpdate>> resourceWatchers =
            ImmutableList.copyOf(cdsWatchers.get(clusterName));
        resourceWatchers.forEach(w -> w.onChanged(update));
      });
    }

    private void deliverEdsUpdate(String name, EdsUpdate update) {
      syncContext.execute(() -> {
        if (!edsWatchers.containsKey(name)) {
          return;
        }
        List<ResourceWatcher<EdsUpdate>> resourceWatchers =
            ImmutableList.copyOf(edsWatchers.get(name));
        resourceWatchers.forEach(w -> w.onChanged(update));
      });
    }


    void deliverError(final Status error) {
      if (ldsWatcher != null) {
        syncContext.execute(() -> {
          ldsWatcher.onError(error);
        });
      }
      if (rdsWatcher != null) {
        syncContext.execute(() -> {
          rdsWatcher.onError(error);
        });
      }
      syncContext.execute(() -> {
        cdsWatchers.values().stream()
            .flatMap(List::stream)
            .forEach(w -> w.onError(error));
      });
    }
  }

  private static final class CallInfo {
    private final String service;
    private final String method;
    private final MethodDescriptor<Void, Void> methodDescriptor;

    private CallInfo(String service, String method) {
      this.service = service;
      this.method = method;
      methodDescriptor =
          MethodDescriptor.<Void, Void>newBuilder()
              .setType(MethodType.UNARY).setFullMethodName(service + "/" + method)
              .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
              .setResponseMarshaller(TestMethodDescriptors.voidMarshaller())
              .build();
    }

    private String getFullMethodNameForPath() {
      return "/" + service + "/" + method;
    }
  }

  private final class TestChannel extends Channel {

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions callOptions) {
      TestCall<ReqT, RespT> call = new TestCall<>(callOptions);
      testCall = call;
      return call;
    }

    @Override
    public String authority() {
      return "foo.authority";
    }
  }

  private static final class TestCall<ReqT, RespT> extends NoopClientCall<ReqT, RespT> {
    // CallOptions actually received from the channel when the call is created.
    final CallOptions callOptions;
    ClientCall.Listener<RespT> listener;

    TestCall(CallOptions callOptions) {
      this.callOptions = callOptions;
    }

    @Override
    public void start(ClientCall.Listener<RespT> listener, Metadata headers) {
      this.listener = listener;
    }

    void deliverResponseHeaders() {
      listener.onHeaders(new Metadata());
    }

    void deliverCompleted() {
      listener.onClose(Status.OK, new Metadata());
    }

    void deliverErrorStatus() {
      listener.onClose(Status.UNAVAILABLE, new Metadata());
    }
  }
}
