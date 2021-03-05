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
import static io.grpc.xds.XdsNameResolver.DOWNSTREAM_NODE_KEY;
import static io.grpc.xds.XdsNameResolver.HEADER_ABORT_GRPC_STATUS_KEY;
import static io.grpc.xds.XdsNameResolver.HEADER_ABORT_HTTP_STATUS_KEY;
import static io.grpc.xds.XdsNameResolver.HEADER_ABORT_PERCENTAGE_KEY;
import static io.grpc.xds.XdsNameResolver.HEADER_DELAY_KEY;
import static io.grpc.xds.XdsNameResolver.HEADER_DELAY_PERCENTAGE_KEY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.re2j.Pattern;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.InternalConfigSelector;
import io.grpc.InternalConfigSelector.Result;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.NameResolver.ResolutionResult;
import io.grpc.NameResolver.ServiceConfigParser;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.internal.FakeClock;
import io.grpc.internal.JsonParser;
import io.grpc.internal.JsonUtil;
import io.grpc.internal.NoopClientCall;
import io.grpc.internal.NoopClientCall.NoopClientCallListener;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.PickSubchannelArgsImpl;
import io.grpc.testing.TestMethodDescriptors;
import io.grpc.xds.HttpFault.FaultAbort;
import io.grpc.xds.HttpFault.FaultDelay;
import io.grpc.xds.Matchers.HeaderMatcher;
import io.grpc.xds.Matchers.PathMatcher;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.VirtualHost.Route.RouteAction;
import io.grpc.xds.VirtualHost.Route.RouteAction.ClusterWeight;
import io.grpc.xds.VirtualHost.Route.RouteAction.HashPolicy;
import io.grpc.xds.VirtualHost.Route.RouteMatch;
import io.grpc.xds.XdsClient.RdsResourceWatcher;
import io.grpc.xds.XdsNameResolverProvider.XdsClientPoolFactory;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
  private AtomicLong originalFaultCounter;

  @Before
  public void setUp() {
    originalEnableTimeout = XdsNameResolver.enableTimeout;
    originalFaultCounter = XdsNameResolver.activeFaultInjectedStreamCounter;
    XdsNameResolver.enableTimeout = true;
    XdsNameResolver.activeFaultInjectedStreamCounter = new AtomicLong();
    resolver = new XdsNameResolver(AUTHORITY, serviceConfigParser, syncContext, scheduler,
        xdsClientPoolFactory, mockRandom);
  }

  @After
  public void tearDown() {
    XdsNameResolver.enableTimeout = originalEnableTimeout;
    XdsNameResolver.activeFaultInjectedStreamCounter = originalFaultCounter;
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
        throw new UnsupportedOperationException("Should not be called");
      }

      @Override
      public ObjectPool<XdsClient> getXdsClientPool() throws XdsInitializationException {
        throw new XdsInitializationException("Fail to read bootstrap file");
      }
    };
    resolver = new XdsNameResolver(AUTHORITY, serviceConfigParser, syncContext, scheduler,
        xdsClientPoolFactory, mockRandom);
    resolver.start(mockListener);
    verify(mockListener).onError(errorCaptor.capture());
    Status error = errorCaptor.getValue();
    assertThat(error.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(error.getDescription()).isEqualTo("Failed to initialize xDS");
    assertThat(error.getCause()).hasMessageThat().isEqualTo("Fail to read bootstrap file");
  }

  @Test
  public void resolving_ldsResourceNotFound() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    assertThat(xdsClient.ldsResource).isEqualTo(AUTHORITY);
    assertThat(xdsClient.ldsWatcher).isNotNull();
    xdsClient.deliverLdsResourceNotFound(AUTHORITY);
    assertEmptyResolutionResult();
  }

  @Test
  public void resolving_ldsResourceUpdateRdsName() {
    String rdsResource = "route-configuration-foo.googleapis.com";
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverRdsName(AUTHORITY, rdsResource);
    RdsResourceWatcher rdsWatcher = xdsClient.rdsWatcher;
    assertThat(rdsWatcher).isNotNull();
    assertThat(xdsClient.rdsResource).isEqualTo(rdsResource);
    rdsResource = "route-configuration-bar.googleapis.com";
    xdsClient.deliverRdsName(AUTHORITY, rdsResource);
    assertThat(xdsClient.rdsResource).isEqualTo(rdsResource);
    assertThat(xdsClient.rdsWatcher).isNotSameInstanceAs(rdsWatcher);
  }

  @Test
  public void resolving_rdsResourceNotFound() {
    String rdsResource = "route-configuration.googleapis.com";
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    assertThat(xdsClient.ldsResource).isEqualTo(AUTHORITY);
    assertThat(xdsClient.ldsWatcher).isNotNull();
    xdsClient.deliverRdsName(AUTHORITY, rdsResource);
    assertThat(xdsClient.rdsResource).isEqualTo(rdsResource);
    assertThat(xdsClient.rdsWatcher).isNotNull();
    xdsClient.deliverRdsResourceNotFound(rdsResource);
    assertEmptyResolutionResult();
  }

  @Test
  public void resolving_encounterErrorLdsWatcherOnly() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverError(Status.UNAVAILABLE.withDescription("server unreachable"));
    verify(mockListener).onError(errorCaptor.capture());
    Status error = errorCaptor.getValue();
    assertThat(error.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(error.getDescription()).isEqualTo("server unreachable");
  }

  @Test
  public void resolving_encounterErrorLdsAndRdsWatchers() {
    String rdsResource = "route-configuration.googleapis.com";
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverRdsName(AUTHORITY, rdsResource);
    assertThat(xdsClient.rdsWatcher).isNotNull();
    xdsClient.deliverError(Status.UNAVAILABLE.withDescription("server unreachable"));
    verify(mockListener, times(2)).onError(errorCaptor.capture());
    for (Status error : errorCaptor.getAllValues()) {
      assertThat(error.getCode()).isEqualTo(Code.UNAVAILABLE);
      assertThat(error.getDescription()).isEqualTo("server unreachable");
    }
  }

  @Test
  public void resolving_matchingVirtualHostNotFoundInLdsResource() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(AUTHORITY, 0L, buildUnmatchedVirtualHosts());
    assertEmptyResolutionResult();
  }

  @Test
  public void resolving_matchingVirtualHostNotFoundInRdsResource() {
    String rdsResource = "route-configuration.googleapis.com";
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverRdsName(AUTHORITY, rdsResource);
    assertThat(xdsClient.rdsWatcher).isNotNull();
    xdsClient.deliverRdsUpdate(rdsResource, buildUnmatchedVirtualHosts());
    assertEmptyResolutionResult();
  }

  private List<VirtualHost> buildUnmatchedVirtualHosts() {
    Route route1 = Route.create(RouteMatch.withPathExactOnly(call2.getFullMethodNameForPath()),
        RouteAction.forCluster(
            cluster2, Collections.<HashPolicy>emptyList(), TimeUnit.SECONDS.toNanos(15L)), null);
    Route route2 = Route.create(RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
        RouteAction.forCluster(
            cluster1, Collections.<HashPolicy>emptyList(), TimeUnit.SECONDS.toNanos(15L)), null);
    return Arrays.asList(
        VirtualHost.create("virtualhost-foo", Collections.singletonList("hello.googleapis.com"),
            Collections.singletonList(route1), null),
        VirtualHost.create("virtualhost-bar", Collections.singletonList("hi.googleapis.com"),
            Collections.singletonList(route2), null));
  }

  @Test
  public void resolved_noTimeout() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    Route route = Route.create(RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
        RouteAction.forCluster(
            cluster1, Collections.<HashPolicy>emptyList(), null), null); // per-route timeout unset
    VirtualHost virtualHost = VirtualHost.create("does not matter",
        Collections.singletonList(AUTHORITY), Collections.singletonList(route), null);
    xdsClient.deliverLdsUpdate(AUTHORITY, 0L, Collections.singletonList(virtualHost));
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    assertCallSelectResult(call1, configSelector, cluster1, null);
  }

  @Test
  public void resolved_fallbackToHttpMaxStreamDurationAsTimeout() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    Route route = Route.create(RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
        RouteAction.forCluster(
            cluster1, Collections.<HashPolicy>emptyList(), null), null); // per-route timeout unset
    VirtualHost virtualHost = VirtualHost.create("does not matter",
        Collections.singletonList(AUTHORITY), Collections.singletonList(route), null);
    xdsClient.deliverLdsUpdate(AUTHORITY, TimeUnit.SECONDS.toNanos(5L),
        Collections.singletonList(virtualHost));
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    assertCallSelectResult(call1, configSelector, cluster1, 5.0);
  }

  @Test
  public void resolved_simpleCallSucceeds() {
    InternalConfigSelector configSelector = resolveToClusters();
    assertCallSelectResult(call1, configSelector, cluster1, 15.0);
    testCall.deliverResponseHeaders();
    verifyNoMoreInteractions(mockListener);
  }

  @Test
  public void resolved_simpleCallFailedToRoute() {
    InternalConfigSelector configSelector = resolveToClusters();
    CallInfo call = new CallInfo("FooService", "barMethod");
    Result selectResult = configSelector.selectConfig(
        new PickSubchannelArgsImpl(call.methodDescriptor, new Metadata(), CallOptions.DEFAULT));
    Status status = selectResult.getStatus();
    assertThat(status.isOk()).isFalse();
    assertThat(status.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(status.getDescription()).isEqualTo("Could not find xDS route matching RPC");
    verifyNoMoreInteractions(mockListener);
  }

  @Test
  public void resolved_rpcHashingByHeader() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(
        AUTHORITY,
        Collections.singletonList(
            Route.create(
                RouteMatch.withPathExactOnly(
                    "/" + TestMethodDescriptors.voidMethod().getFullMethodName()),
                RouteAction.forCluster(cluster1, Collections.singletonList(HashPolicy.forHeader(
                    false, "custom-key", Pattern.compile("value"), "val")),
                    null), null)));
    verify(mockListener).onResult(resolutionResultCaptor.capture());
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
        AUTHORITY,
        Collections.singletonList(
            Route.create(
                RouteMatch.withPathExactOnly(
                    "/" + TestMethodDescriptors.voidMethod().getFullMethodName()),
                RouteAction.forCluster(cluster1, Collections.singletonList(
                    HashPolicy.forChannelId(false)), null), null)));
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    InternalConfigSelector configSelector =
        resolutionResultCaptor.getValue().getAttributes().get(InternalConfigSelector.KEY);

    // First call, with header "custom-key": "value1".
    startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of("custom-key", "value1"),
        CallOptions.DEFAULT);
    long hash1 = testCall.callOptions.getOption(XdsNameResolver.RPC_HASH_KEY);

    // Second call, with no custom header.
    startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        Collections.<String, String>emptyMap(),
        CallOptions.DEFAULT);
    long hash2 = testCall.callOptions.getOption(XdsNameResolver.RPC_HASH_KEY);

    // A different resolver/Channel.
    resolver.shutdown();
    reset(mockListener);
    resolver = new XdsNameResolver(AUTHORITY, serviceConfigParser, syncContext, scheduler,
        xdsClientPoolFactory, mockRandom);
    resolver.start(mockListener);
    xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(
        AUTHORITY,
        Collections.singletonList(
            Route.create(
                RouteMatch.withPathExactOnly(
                    "/" + TestMethodDescriptors.voidMethod().getFullMethodName()),
                RouteAction.forCluster(cluster1, Collections.singletonList(
                    HashPolicy.forChannelId(false)), null), null)));
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    configSelector = resolutionResultCaptor.getValue().getAttributes().get(
        InternalConfigSelector.KEY);

    // Third call, with no custom header.
    startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        Collections.<String, String>emptyMap(),
        CallOptions.DEFAULT);
    long hash3 = testCall.callOptions.getOption(XdsNameResolver.RPC_HASH_KEY);

    assertThat(hash2).isEqualTo(hash1);
    assertThat(hash3).isNotEqualTo(hash1);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolved_resourceUpdateAfterCallStarted() {
    InternalConfigSelector configSelector = resolveToClusters();
    assertCallSelectResult(call1, configSelector, cluster1, 15.0);
    TestCall<?, ?> firstCall = testCall;

    reset(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(
        AUTHORITY,
        Arrays.asList(
            Route.create(
                RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
                RouteAction.forCluster(
                    "another-cluster", Collections.<HashPolicy>emptyList(),
                    TimeUnit.SECONDS.toNanos(20L)), null),
            Route.create(
                RouteMatch.withPathExactOnly(call2.getFullMethodNameForPath()),
                RouteAction.forCluster(
                    cluster2, Collections.<HashPolicy>emptyList(), TimeUnit.SECONDS.toNanos(15L)),
                null)));
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    // Updated service config still contains cluster1 while it is removed resource. New calls no
    // longer routed to cluster1.
    assertServiceConfigForLoadBalancingConfig(
        Arrays.asList(cluster1, cluster2, "another-cluster"),
        (Map<String, ?>) result.getServiceConfig().getConfig());
    assertThat(result.getAttributes().get(InternalConfigSelector.KEY))
        .isSameInstanceAs(configSelector);
    assertCallSelectResult(call1, configSelector, "another-cluster", 20.0);

    firstCall.deliverErrorStatus();  // completes previous call
    verify(mockListener, times(2)).onResult(resolutionResultCaptor.capture());
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
        AUTHORITY,
        Arrays.asList(
            Route.create(
                RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
                RouteAction.forCluster(
                    "another-cluster", Collections.<HashPolicy>emptyList(),
                    TimeUnit.SECONDS.toNanos(20L)), null),
            Route.create(
                RouteMatch.withPathExactOnly(call2.getFullMethodNameForPath()),
                RouteAction.forCluster(cluster2, Collections.<HashPolicy>emptyList(),
                    TimeUnit.SECONDS.toNanos(15L)), null)));
    // Two consecutive service config updates: one for removing clcuster1,
    // one for adding "another=cluster".
    verify(mockListener, times(2)).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    assertServiceConfigForLoadBalancingConfig(
        Arrays.asList(cluster2, "another-cluster"),
        (Map<String, ?>) result.getServiceConfig().getConfig());
    assertThat(result.getAttributes().get(InternalConfigSelector.KEY))
        .isSameInstanceAs(configSelector);
    assertCallSelectResult(call1, configSelector, "another-cluster", 20.0);

    verifyNoMoreInteractions(mockListener);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolved_raceBetweenCallAndRepeatedResourceUpdate() {
    InternalConfigSelector configSelector = resolveToClusters();
    assertCallSelectResult(call1, configSelector, cluster1, 15.0);

    reset(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(
        AUTHORITY,
        Arrays.asList(
            Route.create(
                RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
                RouteAction.forCluster("another-cluster", Collections.<HashPolicy>emptyList(),
                    TimeUnit.SECONDS.toNanos(20L)), null),
            Route.create(
                RouteMatch.withPathExactOnly(call2.getFullMethodNameForPath()),
                RouteAction.forCluster(cluster2, Collections.<HashPolicy>emptyList(),
                    TimeUnit.SECONDS.toNanos(15L)), null)));

    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    assertServiceConfigForLoadBalancingConfig(
        Arrays.asList(cluster1, cluster2, "another-cluster"),
        (Map<String, ?>) result.getServiceConfig().getConfig());

    xdsClient.deliverLdsUpdate(
        AUTHORITY,
        Arrays.asList(
            Route.create(
                RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
                RouteAction.forCluster("another-cluster", Collections.<HashPolicy>emptyList(),
                    TimeUnit.SECONDS.toNanos(15L)), null),
            Route.create(
                RouteMatch.withPathExactOnly(call2.getFullMethodNameForPath()),
                RouteAction.forCluster(cluster2, Collections.<HashPolicy>emptyList(),
                    TimeUnit.SECONDS.toNanos(15L)), null)));
    verifyNoMoreInteractions(mockListener);  // no cluster added/deleted
    assertCallSelectResult(call1, configSelector, "another-cluster", 15.0);
  }

  @Test
  public void resolved_raceBetweenClusterReleasedAndResourceUpdateAddBackAgain() {
    InternalConfigSelector configSelector = resolveToClusters();
    assertCallSelectResult(call1, configSelector, cluster1, 15.0);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(
        AUTHORITY,
        Collections.singletonList(
            Route.create(
                RouteMatch.withPathExactOnly(call2.getFullMethodNameForPath()),
                RouteAction.forCluster(cluster2, Collections.<HashPolicy>emptyList(),
                    TimeUnit.SECONDS.toNanos(15L)), null)));
    xdsClient.deliverLdsUpdate(
        AUTHORITY,
        Arrays.asList(
            Route.create(
                RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
                RouteAction.forCluster(cluster1, Collections.<HashPolicy>emptyList(),
                    TimeUnit.SECONDS.toNanos(15L)), null),
            Route.create(
                RouteMatch.withPathExactOnly(call2.getFullMethodNameForPath()),
                RouteAction.forCluster(cluster2, Collections.<HashPolicy>emptyList(),
                    TimeUnit.SECONDS.toNanos(15L)), null)));
    testCall.deliverErrorStatus();
    verifyNoMoreInteractions(mockListener);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void resolved_simpleCallSucceeds_routeToWeightedCluster() {
    when(mockRandom.nextInt(anyInt())).thenReturn(90, 10);
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(
        AUTHORITY,
        Collections.singletonList(
            Route.create(
                RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
                RouteAction.forWeightedClusters(
                    Arrays.asList(
                        ClusterWeight.create(cluster1, 20, null),
                        ClusterWeight.create(cluster2, 80, null)),
                    Collections.<HashPolicy>emptyList(),
                    TimeUnit.SECONDS.toNanos(20L)), null)));
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    assertThat(result.getAddresses()).isEmpty();
    assertServiceConfigForLoadBalancingConfig(
        Arrays.asList(cluster1, cluster2), (Map<String, ?>) result.getServiceConfig().getConfig());
    assertThat(result.getAttributes().get(InternalXdsAttributes.XDS_CLIENT_POOL)).isNotNull();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    assertCallSelectResult(call1, configSelector, cluster2, 20.0);
    assertCallSelectResult(call1, configSelector, cluster1, 20.0);
  }

  @SuppressWarnings("unchecked")
  private void assertEmptyResolutionResult() {
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    assertThat(result.getAddresses()).isEmpty();
    assertThat((Map<String, ?>) result.getServiceConfig().getConfig()).isEmpty();
  }

  private void assertCallSelectResult(
      CallInfo call, InternalConfigSelector configSelector, String expectedCluster,
      @Nullable Double expectedTimeoutSec) {
    Result result = configSelector.selectConfig(
        new PickSubchannelArgsImpl(call.methodDescriptor, new Metadata(), CallOptions.DEFAULT));
    assertThat(result.getStatus().isOk()).isTrue();
    ClientInterceptor interceptor = result.getInterceptor();
    ClientCall<Void, Void> clientCall = interceptor.interceptCall(
        call.methodDescriptor, CallOptions.DEFAULT, channel);
    clientCall.start(new NoopClientCallListener<Void>(), new Metadata());
    assertThat(testCall.callOptions.getOption(XdsNameResolver.CLUSTER_SELECTION_KEY))
        .isEqualTo(expectedCluster);
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

  @SuppressWarnings("unchecked")
  private InternalConfigSelector resolveToClusters() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    xdsClient.deliverLdsUpdate(
        AUTHORITY,
        Arrays.asList(
            Route.create(
                RouteMatch.withPathExactOnly(call1.getFullMethodNameForPath()),
                RouteAction.forCluster(cluster1, Collections.<HashPolicy>emptyList(),
                    TimeUnit.SECONDS.toNanos(15L)), null),
            Route.create(
                RouteMatch.withPathExactOnly(call2.getFullMethodNameForPath()),
                RouteAction.forCluster(cluster2, Collections.<HashPolicy>emptyList(),
                    TimeUnit.SECONDS.toNanos(15L)), null)));
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    assertThat(result.getAddresses()).isEmpty();
    assertServiceConfigForLoadBalancingConfig(
        Arrays.asList(cluster1, cluster2), (Map<String, ?>) result.getServiceConfig().getConfig());
    assertThat(result.getAttributes().get(InternalXdsAttributes.XDS_CLIENT_POOL)).isNotNull();
    assertThat(result.getAttributes().get(InternalXdsAttributes.CALL_COUNTER_PROVIDER)).isNotNull();
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
    assertThat(clusterManagerChildLbPolicies.keySet()).containsExactlyElementsIn(clusters);
    for (String cluster : clusters) {
      Map<String, ?> childLbConfig = JsonUtil.getObject(clusterManagerChildLbPolicies, cluster);
      assertThat(childLbConfig.keySet()).containsExactly("lbPolicy");
      List<Map<String, ?>> childLbConfigValues =
          JsonUtil.getListOfObjects(childLbConfig, "lbPolicy");
      Map<String, ?> cdsLbPolicy = Iterables.getOnlyElement(childLbConfigValues);
      assertThat(cdsLbPolicy.keySet()).containsExactly("cds_experimental");
      assertThat(JsonUtil.getObject(cdsLbPolicy, "cds_experimental"))
          .containsExactly("cluster", cluster);
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  public void generateServiceConfig_forLoadBalancingConfig() throws IOException {
    List<String> clusters = Arrays.asList("cluster-foo", "cluster-bar", "cluster-baz");
    String expectedServiceConfigJson = "{\n"
        + "  \"loadBalancingConfig\": [{\n"
        + "    \"cluster_manager_experimental\": {\n"
        + "      \"childPolicy\": {\n"
        + "        \"cluster-foo\": {\n"
        + "          \"lbPolicy\": [{\n"
        + "            \"cds_experimental\": {\n"
        + "              \"cluster\": \"cluster-foo\"\n"
        + "            }\n"
        + "          }]\n"
        + "        },\n"
        + "        \"cluster-bar\": {\n"
        + "          \"lbPolicy\": [{\n"
        + "            \"cds_experimental\": {\n"
        + "              \"cluster\": \"cluster-bar\"\n"
        + "            }\n"
        + "          }]\n"
        + "        },\n"
        + "        \"cluster-baz\": {\n"
        + "          \"lbPolicy\": [{\n"
        + "            \"cds_experimental\": {\n"
        + "              \"cluster\": \"cluster-baz\"\n"
        + "            }\n"
        + "          }]\n"
        + "        }\n"
        + "      }\n"
        + "    }\n"
        + "  }]\n"
        + "}";
    Map<String, ?> expectedServiceConfig =
        (Map<String, ?>) JsonParser.parse(expectedServiceConfigJson);
    assertThat(XdsNameResolver.generateServiceConfigWithLoadBalancingConfig(clusters))
        .isEqualTo(expectedServiceConfig);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void generateServiceConfig_forMethodTimeoutConfig() throws IOException {
    long timeoutNano = TimeUnit.SECONDS.toNanos(1L) + 1L; // 1.0000000001s
    String expectedServiceConfigJson = "{\n"
        + "  \"methodConfig\": [{\n"
        + "    \"name\": [ {} ],\n"
        + "    \"timeout\": \"1.000000001s\"\n"
        + "  }]\n"
        + "}";
    Map<String, ?> expectedServiceConfig =
        (Map<String, ?>) JsonParser.parse(expectedServiceConfigJson);
    assertThat(XdsNameResolver.generateServiceConfigWithMethodTimeoutConfig(timeoutNano))
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
  public void findVirtualHostForHostName_exactMatchFirst() {
    String hostname = "a.googleapis.com";
    List<Route> routes = Collections.emptyList();
    VirtualHost vHost1 = VirtualHost.create("virtualhost01.googleapis.com",
        Arrays.asList("a.googleapis.com", "b.googleapis.com"), routes, null);
    VirtualHost vHost2 = VirtualHost.create("virtualhost02.googleapis.com",
        Collections.singletonList("*.googleapis.com"), routes, null);
    VirtualHost vHost3 = VirtualHost.create("virtualhost03.googleapis.com",
        Collections.singletonList("*"), routes, null);
    List<VirtualHost> virtualHosts = Arrays.asList(vHost1, vHost2, vHost3);
    assertThat(XdsNameResolver.findVirtualHostForHostName(virtualHosts, hostname))
        .isEqualTo(vHost1);
  }

  @Test
  public void findVirtualHostForHostName_preferSuffixDomainOverPrefixDomain() {
    String hostname = "a.googleapis.com";
    List<Route> routes = Collections.emptyList();
    VirtualHost vHost1 = VirtualHost.create("virtualhost01.googleapis.com",
        Arrays.asList("*.googleapis.com", "b.googleapis.com"), routes, null);
    VirtualHost vHost2 = VirtualHost.create("virtualhost02.googleapis.com",
        Collections.singletonList("a.googleapis.*"), routes, null);
    VirtualHost vHost3 = VirtualHost.create("virtualhost03.googleapis.com",
        Collections.singletonList("*"), routes, null);
    List<VirtualHost> virtualHosts = Arrays.asList(vHost1, vHost2, vHost3);
    assertThat(XdsNameResolver.findVirtualHostForHostName(virtualHosts, hostname))
        .isEqualTo(vHost1);
  }

  @Test
  public void findVirtualHostForHostName_asteriskMatchAnyDomain() {
    String hostname = "a.googleapis.com";
    List<Route> routes = Collections.emptyList();
    VirtualHost vHost1 = VirtualHost.create("virtualhost01.googleapis.com",
        Collections.singletonList("*"), routes, null);
    VirtualHost vHost2 = VirtualHost.create("virtualhost02.googleapis.com",
            Collections.singletonList("b.googleapis.com"), routes, null);
    List<VirtualHost> virtualHosts = Arrays.asList(vHost1, vHost2);
    assertThat(XdsNameResolver.findVirtualHostForHostName(virtualHosts, hostname))
        .isEqualTo(vHost1);;
  }

  @Test
  public void resolved_faultAbortInLdsUpdate() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    when(mockRandom.nextInt(1000_000)).thenReturn(500_000); // 50%

    // header abort, header abort rate = 60 %
    HttpFault httpFilterFaultConfig = HttpFault.create(
        null,
        FaultAbort.forHeader(HttpFault.FractionalPercent.perHundred(70)),
        cluster1,
        Collections.<String>emptyList(),
        Collections.<HeaderMatcher>emptyList(),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    // no header abort key provided in metadata, rpc should succeed
    ClientCall.Listener<Void> observer = startNewCall(TestMethodDescriptors.voidMethod(),
        configSelector, Collections.<String, String>emptyMap(), CallOptions.DEFAULT);
    verifyRpcSucceeded(observer);
    // header abort http status key provided, rpc should fail
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of(HEADER_ABORT_HTTP_STATUS_KEY.name(), "404",
            HEADER_ABORT_PERCENTAGE_KEY.name(), "60"), CallOptions.DEFAULT);
    verifyRpcFailed(observer, Status.UNIMPLEMENTED.withDescription("HTTP status code 404"));
    // header abort grpc status key provided, rpc should fail
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of(HEADER_ABORT_GRPC_STATUS_KEY.name(),
            String.valueOf(Status.UNAUTHENTICATED.getCode().value()),
            HEADER_ABORT_PERCENTAGE_KEY.name(), "60"), CallOptions.DEFAULT);
    verifyRpcFailed(observer, Status.UNAUTHENTICATED);
    // header abort, both http and grpc code keys provided, rpc should fail with http code
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of(HEADER_ABORT_HTTP_STATUS_KEY.name(), "404",
            HEADER_ABORT_GRPC_STATUS_KEY.name(),
            String.valueOf(Status.UNAUTHENTICATED.getCode().value()),
            HEADER_ABORT_PERCENTAGE_KEY.name(), "60"), CallOptions.DEFAULT);
    verifyRpcFailed(observer, Status.UNIMPLEMENTED.withDescription("HTTP status code 404"));

    // header abort, no header rate, fix rate = 60 %
    httpFilterFaultConfig = HttpFault.create(
        null,
        FaultAbort.forHeader(HttpFault.FractionalPercent.perMillion(600_000)),
        cluster1,
        Collections.<String>emptyList(),
        Collections.<HeaderMatcher>emptyList(),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of(HEADER_ABORT_HTTP_STATUS_KEY.name(), "404"), CallOptions.DEFAULT);
    verifyRpcFailed(observer, Status.UNIMPLEMENTED.withDescription("HTTP status code 404"));

    // header abort, no header rate, fix rate = 0
    httpFilterFaultConfig = HttpFault.create(
        null,
        FaultAbort.forHeader(HttpFault.FractionalPercent.perMillion(0)),
        cluster1,
        Collections.<String>emptyList(),
        Collections.<HeaderMatcher>emptyList(),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of(HEADER_ABORT_HTTP_STATUS_KEY.name(), "404"), CallOptions.DEFAULT);
    verifyRpcSucceeded(observer);

    // fixed abort, fix rate = 60%
    httpFilterFaultConfig = HttpFault.create(
        null,
        FaultAbort.forStatus(
            Status.UNAUTHENTICATED.withDescription("unauthenticated"),
            HttpFault.FractionalPercent.perMillion(600_000)),
        cluster1,
        Collections.<String>emptyList(),
        Collections.<HeaderMatcher>emptyList(),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        Collections.<String, String>emptyMap(), CallOptions.DEFAULT);
    verifyRpcFailed(observer, Status.UNAUTHENTICATED.withDescription("unauthenticated"));

    // fixed abort, fix rate = 40%
    httpFilterFaultConfig = HttpFault.create(
        null,
        FaultAbort.forStatus(
            Status.UNAUTHENTICATED.withDescription("unauthenticated"),
            HttpFault.FractionalPercent.perMillion(400_000)),
        cluster1,
        Collections.<String>emptyList(),
        Collections.<HeaderMatcher>emptyList(),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        Collections.<String, String>emptyMap(), CallOptions.DEFAULT);
    verifyRpcSucceeded(observer);
  }

  @Test
  public void resolved_faultDelayInLdsUpdate() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    when(mockRandom.nextInt(1000_000)).thenReturn(500_000); // 50%

    // header delay, header delay rate = 60 %
    HttpFault httpFilterFaultConfig = HttpFault.create(
        FaultDelay.forHeader(HttpFault.FractionalPercent.perHundred(70)),
        null,
        cluster1,
        Collections.<String>emptyList(),
        Collections.<HeaderMatcher>emptyList(),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    // no header delay key provided in metadata, rpc should succeed immediately
    ClientCall.Listener<Void> observer = startNewCall(TestMethodDescriptors.voidMethod(),
        configSelector, Collections.<String, String>emptyMap(), CallOptions.DEFAULT);
    verifyRpcSucceeded(observer);
    // header delay key provided, rpc should be delayed
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of(HEADER_DELAY_KEY.name(), "1000", HEADER_DELAY_PERCENTAGE_KEY.name(), "60"),
        CallOptions.DEFAULT);
    verifyRpcDelayed(observer, TimeUnit.MILLISECONDS.toNanos(1000));

    // header delay, no header rate, fix rate = 60 %
    httpFilterFaultConfig = HttpFault.create(
        FaultDelay.forHeader(HttpFault.FractionalPercent.perMillion(600_000)),
        null,
        cluster1,
        Collections.<String>emptyList(),
        Collections.<HeaderMatcher>emptyList(),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of(HEADER_DELAY_KEY.name(), "1000"), CallOptions.DEFAULT);
    verifyRpcDelayed(observer, TimeUnit.MILLISECONDS.toNanos(1000));

    // header delay, no header rate, fix rate = 0
    httpFilterFaultConfig = HttpFault.create(
        FaultDelay.forHeader(HttpFault.FractionalPercent.perMillion(0)),
        null,
        cluster1,
        Collections.<String>emptyList(),
        Collections.<HeaderMatcher>emptyList(),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of(HEADER_DELAY_KEY.name(), "1000"), CallOptions.DEFAULT);
    verifyRpcSucceeded(observer);

    // fixed delay, fix rate = 60%
    httpFilterFaultConfig = HttpFault.create(
        FaultDelay.forFixedDelay(5000L, HttpFault.FractionalPercent.perMillion(600_000)),
        null,
        cluster1,
        Collections.<String>emptyList(),
        Collections.<HeaderMatcher>emptyList(),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        Collections.<String, String>emptyMap(), CallOptions.DEFAULT);
    verifyRpcDelayed(observer, 5000L);

    // fixed delay, fix rate = 40%
    httpFilterFaultConfig = HttpFault.create(
        FaultDelay.forFixedDelay(5000L, HttpFault.FractionalPercent.perMillion(400_000)),
        null,
        cluster1,
        Collections.<String>emptyList(),
        Collections.<HeaderMatcher>emptyList(),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        Collections.<String, String>emptyMap(), CallOptions.DEFAULT);
    verifyRpcSucceeded(observer);
  }

  @Test
  public void resolved_faultAbortWithUpstreamClusterMismatchInLdsUpdate() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    when(mockRandom.nextInt(1000_000)).thenReturn(500_000); // 50%

    HttpFault httpFilterFaultConfig = HttpFault.create(
        null,
        FaultAbort.forStatus(
            Status.UNAUTHENTICATED.withDescription("unauthenticated"),
            HttpFault.FractionalPercent.perMillion(1000_000)),
        cluster1,
        Collections.<String>emptyList(),
        Collections.<HeaderMatcher>emptyList(),
        null);
    // cluster mismatch with fault config
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster2, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    ClientCall.Listener<Void> observer = startNewCall(TestMethodDescriptors.voidMethod(),
        configSelector, Collections.<String, String>emptyMap(), CallOptions.DEFAULT);
    verifyRpcSucceeded(observer);
  }

  @Test
  public void resolved_faultAbortWithDownstreamNodesInLdsUpdate() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    when(mockRandom.nextInt(1000_000)).thenReturn(500_000); // 50%

    // downstream node match
    HttpFault httpFilterFaultConfig = HttpFault.create(
        null,
        FaultAbort.forStatus(
            Status.UNAUTHENTICATED.withDescription("unauthenticated"),
            HttpFault.FractionalPercent.perMillion(1000_000)),
        cluster1,
        Collections.singletonList("node1.example.com"),
        Collections.<HeaderMatcher>emptyList(),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    ClientCall.Listener<Void> observer = startNewCall(TestMethodDescriptors.voidMethod(),
        configSelector, ImmutableMap.of(DOWNSTREAM_NODE_KEY.name(), "node1.example.com"),
        CallOptions.DEFAULT);
    verifyRpcFailed(observer, Status.UNAUTHENTICATED.withDescription("unauthenticated"));

    // downstream node mismatch
    httpFilterFaultConfig = HttpFault.create(
        null,
        FaultAbort.forStatus(
            Status.UNAUTHENTICATED.withDescription("unauthenticated"),
            HttpFault.FractionalPercent.perMillion(1000_000)),
        cluster1,
        Collections.singletonList("node1.example.com"),
        Collections.<HeaderMatcher>emptyList(),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of(DOWNSTREAM_NODE_KEY.name(), "node2.example.com"), CallOptions.DEFAULT);
    verifyRpcSucceeded(observer);

    // downstream node absent in headers
    httpFilterFaultConfig = HttpFault.create(
        null,
        FaultAbort.forStatus(
            Status.UNAUTHENTICATED.withDescription("unauthenticated"),
            HttpFault.FractionalPercent.perMillion(1000_000)),
        cluster1,
        Collections.singletonList("node1.example.com"),
        Collections.<HeaderMatcher>emptyList(),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        Collections.<String, String>emptyMap(), CallOptions.DEFAULT);
    verifyRpcSucceeded(observer);
  }

  @Test
  public void resolved_faultAbortWithHeaderMatcherInLdsUpdate() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    when(mockRandom.nextInt(1000_000)).thenReturn(500_000); // 50%

    // headers match
    HttpFault httpFilterFaultConfig = HttpFault.create(
        null,
        FaultAbort.forStatus(
            Status.UNAUTHENTICATED, HttpFault.FractionalPercent.perMillion(1000_000)),
        cluster1,
        Collections.<String>emptyList(),
        Collections.singletonList(HeaderMatcher.forExactValue("fault_key", "fault_value", false)),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    ClientCall.Listener<Void> observer = startNewCall(TestMethodDescriptors.voidMethod(),
        configSelector, ImmutableMap.of("fault_key", "fault_value"), CallOptions.DEFAULT);
    verifyRpcFailed(observer, Status.UNAUTHENTICATED);

    // headers mismatch
    httpFilterFaultConfig = HttpFault.create(
        null,
        FaultAbort.forStatus(
            Status.UNAUTHENTICATED.withDescription("unauthenticated"),
            HttpFault.FractionalPercent.perMillion(1000_000)),
        cluster1,
        Collections.<String>emptyList(),
        Collections.singletonList(HeaderMatcher.forExactValue("fault_key", "fault_value", false)),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        ImmutableMap.of("fault_key", "value_not_match"), CallOptions.DEFAULT);
    verifyRpcSucceeded(observer);

    // headers absent
    httpFilterFaultConfig = HttpFault.create(
        null,
        FaultAbort.forStatus(
            Status.UNAUTHENTICATED.withDescription("unauthenticated"),
            HttpFault.FractionalPercent.perMillion(1000_000)),
        cluster1,
        Collections.<String>emptyList(),
        Collections.singletonList(HeaderMatcher.forExactValue("fault_key", "fault_value", false)),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        Collections.<String, String>emptyMap(), CallOptions.DEFAULT);
    verifyRpcSucceeded(observer);
  }

  @Test
  public void resolved_faultDelayWithMaxActiveStreamsInLdsUpdate() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    when(mockRandom.nextInt(1000_000)).thenReturn(500_000); // 50%

    HttpFault httpFilterFaultConfig = HttpFault.create(
        FaultDelay.forFixedDelay(5000L, HttpFault.FractionalPercent.perMillion(1000_000)),
        null,
        cluster1,
        Collections.<String>emptyList(),
        Collections.<HeaderMatcher>emptyList(),
        /* maxActiveFaults= */ 1);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);

    // Send two calls, then the first call should delayed and the second call should not be delayed
    // because maxActiveFaults is exceeded.
    ClientCall.Listener<Void> observer1 = startNewCall(TestMethodDescriptors.voidMethod(),
        configSelector, Collections.<String, String>emptyMap(), CallOptions.DEFAULT);
    assertThat(testCall).isNull();
    ClientCall.Listener<Void> observer2 = startNewCall(TestMethodDescriptors.voidMethod(),
        configSelector, Collections.<String, String>emptyMap(), CallOptions.DEFAULT);
    verifyRpcSucceeded(observer2);
    verifyRpcDelayed(observer1, 5000L);
    // Once all calls are finished, new call should be delayed.
    ClientCall.Listener<Void> observer3 = startNewCall(TestMethodDescriptors.voidMethod(),
        configSelector, Collections.<String, String>emptyMap(), CallOptions.DEFAULT);
    verifyRpcDelayed(observer3, 5000L);
  }

  @Test
  public void resolved_faultAbortAndDelayInLdsUpdateInLdsUpdate() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    when(mockRandom.nextInt(1000_000)).thenReturn(500_000); // 50%

    HttpFault httpFilterFaultConfig = HttpFault.create(
        FaultDelay.forFixedDelay(5000L, HttpFault.FractionalPercent.perMillion(1000_000)),
        FaultAbort.forStatus(
            Status.UNAUTHENTICATED.withDescription("unauthenticated"),
            HttpFault.FractionalPercent.perMillion(1000_000)),
        cluster1,
        Collections.<String>emptyList(),
        Collections.<HeaderMatcher>emptyList(),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(cluster1, httpFilterFaultConfig, null, null, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    ClientCall.Listener<Void> observer = startNewCall(TestMethodDescriptors.voidMethod(),
        configSelector, Collections.<String, String>emptyMap(), CallOptions.DEFAULT);
    verifyRpcDelayedThenAborted(
        observer, 5000L, Status.UNAUTHENTICATED.withDescription("unauthenticated"));
  }

  @Test
  public void resolved_faultConfigOverrideInLdsUpdate() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    when(mockRandom.nextInt(1000_000)).thenReturn(500_000); // 50%

    HttpFault httpFilterFaultConfig = HttpFault.create(
        null,
        FaultAbort.forStatus(
            Status.UNAUTHENTICATED, HttpFault.FractionalPercent.perMillion(1000_000)),
        cluster1,
        Collections.<String>emptyList(),
        Collections.<HeaderMatcher>emptyList(),
        null);
    // VirtualHost fault config override
    HttpFault virtualHostFaultConfig = HttpFault.create(
        null,
        FaultAbort.forStatus(Status.INTERNAL, HttpFault.FractionalPercent.perMillion(1000_000)),
        cluster1,
        Collections.<String>emptyList(),
        Collections.<HeaderMatcher>emptyList(),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(
        cluster1, httpFilterFaultConfig, virtualHostFaultConfig, null, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    ClientCall.Listener<Void> observer = startNewCall(TestMethodDescriptors.voidMethod(),
        configSelector, Collections.<String, String>emptyMap(), CallOptions.DEFAULT);
    verifyRpcFailed(observer, Status.INTERNAL);

    // Route fault config override
    HttpFault routeFaultConfig = HttpFault.create(
        null,
        FaultAbort.forStatus(Status.UNKNOWN, HttpFault.FractionalPercent.perMillion(1000_000)),
        cluster1,
        Collections.<String>emptyList(),
        Collections.<HeaderMatcher>emptyList(),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(
        cluster1, httpFilterFaultConfig, virtualHostFaultConfig, routeFaultConfig, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        Collections.<String, String>emptyMap(), CallOptions.DEFAULT);
    verifyRpcFailed(observer, Status.UNKNOWN);

    // WeightedCluster fault config override
    HttpFault weightedClusterFaultConfig = HttpFault.create(
        null,
        FaultAbort.forStatus(Status.UNAVAILABLE, HttpFault.FractionalPercent.perMillion(1000_000)),
        cluster1,
        Collections.<String>emptyList(),
        Collections.<HeaderMatcher>emptyList(),
        null);
    xdsClient.deliverLdsUpdateWithFaultInjection(
        cluster1, httpFilterFaultConfig, virtualHostFaultConfig, routeFaultConfig,
        weightedClusterFaultConfig);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    result = resolutionResultCaptor.getValue();
    configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    observer = startNewCall(TestMethodDescriptors.voidMethod(), configSelector,
        Collections.<String, String>emptyMap(), CallOptions.DEFAULT);
    verifyRpcFailed(observer, Status.UNAVAILABLE);
  }

  @Test
  public void resolved_faultConfigOverrideInLdsAndInRdsUpdate() {
    resolver.start(mockListener);
    FakeXdsClient xdsClient = (FakeXdsClient) resolver.getXdsClient();
    when(mockRandom.nextInt(1000_000)).thenReturn(500_000); // 50%

    HttpFault httpFilterFaultConfig = HttpFault.create(
        null,
        FaultAbort.forStatus(
            Status.UNAUTHENTICATED, HttpFault.FractionalPercent.perMillion(1000_000)),
        cluster1,
        Collections.<String>emptyList(),
        Collections.<HeaderMatcher>emptyList(),
        null);
    xdsClient.deliverRdsNameWithFaultInjection(AUTHORITY, httpFilterFaultConfig);


    // Route fault config override
    HttpFault routeFaultConfig = HttpFault.create(
        null,
        FaultAbort.forStatus(Status.UNKNOWN, HttpFault.FractionalPercent.perMillion(1000_000)),
        cluster1,
        Collections.<String>emptyList(),
        Collections.<HeaderMatcher>emptyList(),
        null);
    xdsClient.deliverRdsUpdateWithFaultInjection(null, routeFaultConfig, null);
    verify(mockListener).onResult(resolutionResultCaptor.capture());
    ResolutionResult result = resolutionResultCaptor.getValue();
    InternalConfigSelector configSelector = result.getAttributes().get(InternalConfigSelector.KEY);
    ClientCall.Listener<Void> observer = startNewCall(TestMethodDescriptors.voidMethod(),
        configSelector, Collections.<String, String>emptyMap(), CallOptions.DEFAULT);;
    verifyRpcFailed(observer, Status.UNKNOWN);
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
    Result result = selector.selectConfig(new PickSubchannelArgsImpl(
        method, metadata, callOptions));
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

  @Test
  public void routeMatching_pathOnly() {
    Map<String, String> headers = Collections.emptyMap();
    ThreadSafeRandom random = mock(ThreadSafeRandom.class);

    RouteMatch routeMatch1 =
        RouteMatch.create(
            PathMatcher.fromPath("/FooService/barMethod", true),
            Collections.<HeaderMatcher>emptyList(), null);
    assertThat(XdsNameResolver.matchRoute(routeMatch1, "/FooService/barMethod", headers, random))
        .isTrue();
    assertThat(XdsNameResolver.matchRoute(routeMatch1, "/FooService/bazMethod", headers, random))
        .isFalse();

    RouteMatch routeMatch2 =
        RouteMatch.create(
            PathMatcher.fromPrefix("/FooService/", true),
            Collections.<HeaderMatcher>emptyList(), null);
    assertThat(XdsNameResolver.matchRoute(routeMatch2, "/FooService/barMethod", headers, random))
        .isTrue();
    assertThat(XdsNameResolver.matchRoute(routeMatch2, "/FooService/bazMethod", headers, random))
        .isTrue();
    assertThat(XdsNameResolver.matchRoute(routeMatch2, "/BarService/bazMethod", headers, random))
        .isFalse();

    RouteMatch routeMatch3 =
        RouteMatch.create(
            PathMatcher.fromRegEx(Pattern.compile(".*Foo.*")),
            Collections.<HeaderMatcher>emptyList(), null);
    assertThat(XdsNameResolver.matchRoute(routeMatch3, "/FooService/barMethod", headers, random))
        .isTrue();
  }

  @Test
  public void routeMatching_withHeaders() {
    Map<String, String> headers = new HashMap<>();
    headers.put("authority", "foo.googleapis.com");
    headers.put("grpc-encoding", "gzip");
    headers.put("user-agent", "gRPC-Java");
    headers.put("content-length", "1000");
    headers.put("custom-key", "custom-value1,custom-value2");
    ThreadSafeRandom random = mock(ThreadSafeRandom.class);

    PathMatcher pathMatcher = PathMatcher.fromPath("/FooService/barMethod", true);
    RouteMatch routeMatch1 = RouteMatch.create(
        pathMatcher,
        Arrays.asList(
            HeaderMatcher.forExactValue("grpc-encoding", "gzip", false),
            HeaderMatcher.forSafeRegEx("authority", Pattern.compile(".*googleapis.*"), false),
            HeaderMatcher.forRange(
                "content-length", HeaderMatcher.Range.create(100, 10000), false),
            HeaderMatcher.forPresent("user-agent", true, false),
            HeaderMatcher.forPrefix("custom-key", "custom-", false),
            HeaderMatcher.forSuffix("custom-key", "value2", false)),
        null);
    assertThat(XdsNameResolver.matchRoute(routeMatch1, "/FooService/barMethod", headers, random))
        .isTrue();

    RouteMatch routeMatch2 = RouteMatch.create(
        pathMatcher,
        Collections.singletonList(
            HeaderMatcher.forSafeRegEx("authority", Pattern.compile(".*googleapis.*"), true)),
        null);
    assertThat(XdsNameResolver.matchRoute(routeMatch2, "/FooService/barMethod", headers, random))
        .isFalse();

    RouteMatch routeMatch3 = RouteMatch.create(
        pathMatcher,
        Collections.singletonList(
            HeaderMatcher.forExactValue("user-agent", "gRPC-Go", false)), null);
    assertThat(XdsNameResolver.matchRoute(routeMatch3, "/FooService/barMethod", headers, random))
        .isFalse();

    RouteMatch routeMatch4 = RouteMatch.create(
        pathMatcher,
        Collections.singletonList(HeaderMatcher.forPresent("user-agent", false, false)),
        null);
    assertThat(XdsNameResolver.matchRoute(routeMatch4, "/FooService/barMethod", headers, random))
        .isFalse();

    RouteMatch routeMatch5 = RouteMatch.create(
        pathMatcher,
        Collections.singletonList(HeaderMatcher.forPresent("user-agent", false, true)), // inverted
        null);
    assertThat(XdsNameResolver.matchRoute(routeMatch5, "/FooService/barMethod", headers, random))
        .isTrue();

    RouteMatch routeMatch6 = RouteMatch.create(
        pathMatcher,
        Collections.singletonList(HeaderMatcher.forPresent("user-agent", true, true)),
        null);
    assertThat(XdsNameResolver.matchRoute(routeMatch6, "/FooService/barMethod", headers, random))
        .isFalse();

    RouteMatch routeMatch7 = RouteMatch.create(
        pathMatcher,
        Collections.singletonList(
            HeaderMatcher.forExactValue("custom-key", "custom-value1,custom-value2", false)),
        null);
    assertThat(XdsNameResolver.matchRoute(routeMatch7, "/FooService/barMethod", headers, random))
        .isTrue();
  }

  @Test
  public void pathMatching_caseInsensitive() {
    PathMatcher pathMatcher1 = PathMatcher.fromPath("/FooService/barMethod", false);
    assertThat(XdsNameResolver.matchPath(pathMatcher1, "/fooservice/barmethod")).isTrue();

    PathMatcher pathMatcher2 = PathMatcher.fromPrefix("/FooService", false);
    assertThat(XdsNameResolver.matchPath(pathMatcher2, "/fooservice/barmethod")).isTrue();
  }

  private final class FakeXdsClientPoolFactory implements XdsClientPoolFactory {

    @Override
    public void setBootstrapOverride(Map<String, ?> bootstrap) {
      throw new UnsupportedOperationException("Should not be called");
    }

    @Override
    public ObjectPool<XdsClient> getXdsClientPool() throws XdsInitializationException {
      return new ObjectPool<XdsClient>() {
        @Override
        public XdsClient getObject() {
          return new FakeXdsClient();
        }

        @Override
        public XdsClient returnObject(Object object) {
          return null;
        }
      };
    }
  }

  private class FakeXdsClient extends XdsClient {
    private String ldsResource;
    private String rdsResource;
    private LdsResourceWatcher ldsWatcher;
    private RdsResourceWatcher rdsWatcher;

    @Override
    void watchLdsResource(String resourceName, LdsResourceWatcher watcher) {
      Preconditions.checkArgument(ldsResource == null && ldsWatcher == null, "already watched");
      ldsResource = resourceName;
      ldsWatcher = watcher;
    }

    @Override
    void cancelLdsResourceWatch(String resourceName, LdsResourceWatcher watcher) {
      Preconditions.checkArgument(resourceName.equals(ldsResource), "unknown resource");
      Preconditions.checkArgument(watcher.equals(ldsWatcher), "unknown watcher");
      ldsResource = null;
      ldsWatcher = null;
    }

    @Override
    void watchRdsResource(String resourceName, RdsResourceWatcher watcher) {
      Preconditions.checkArgument(rdsResource == null && rdsWatcher == null, "already watched");
      rdsResource = resourceName;
      rdsWatcher = watcher;
    }

    @Override
    void cancelRdsResourceWatch(String resourceName, RdsResourceWatcher watcher) {
      Preconditions.checkArgument(resourceName.equals(rdsResource), "unknown resource");
      Preconditions.checkArgument(watcher.equals(rdsWatcher), "unknown watcher");
      rdsResource = null;
      rdsWatcher = null;
    }

    @Override
    void shutdown() {
      // no-op
    }

    void deliverLdsUpdate(final String resourceName, final long httpMaxStreamDurationNano,
        final List<VirtualHost> virtualHosts) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (!resourceName.equals(ldsResource)) {
            return;
          }
          ldsWatcher.onChanged(new LdsUpdate(httpMaxStreamDurationNano, virtualHosts, false, null));
        }
      });
    }

    void deliverLdsUpdate(final String resourceName, final List<Route> routes) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (!resourceName.equals(ldsResource)) {
            return;
          }
          VirtualHost virtualHost = VirtualHost.create("virtual-host",
              Collections.singletonList(AUTHORITY), routes, null);
          ldsWatcher.onChanged(
              new LdsUpdate(0, Collections.singletonList(virtualHost), false, null));
        }
      });
    }

    void deliverLdsUpdateWithFaultInjection(
        final String cluster,
        final HttpFault httpFilterFaultConfig,
        final HttpFault virtualHostFaultConfig,
        final HttpFault routeFaultConfig,
        final HttpFault weightedClusterFaultConfig) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          ClusterWeight clusterWeight =
              ClusterWeight.create(cluster, 100, weightedClusterFaultConfig);
          Route route = Route.create(
              RouteMatch.create(
                  PathMatcher.fromPrefix("/", false), Collections.<HeaderMatcher>emptyList(), null),
              RouteAction.forWeightedClusters(
                  Collections.singletonList(clusterWeight),
                  Collections.<HashPolicy>emptyList(),
                  null),
              routeFaultConfig);
          VirtualHost virtualHost = VirtualHost.create(
              "virtual-host",
              Collections.singletonList(AUTHORITY),
              Collections.singletonList(route), virtualHostFaultConfig);
          ldsWatcher.onChanged(
              new LdsUpdate(
                  0, Collections.singletonList(virtualHost), true, httpFilterFaultConfig));
        }
      });
    }

    void deliverRdsNameWithFaultInjection(
        final String rdsName, final HttpFault httpFilterFaultConfig) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          ldsWatcher.onChanged(new LdsUpdate(0, rdsName, true, httpFilterFaultConfig));
        }
      });
    }

    void deliverRdsName(final String resourceName, final String rdsName) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (!resourceName.equals(ldsResource)) {
            return;
          }
          ldsWatcher.onChanged(new LdsUpdate(0, rdsName, false, null));
        }
      });
    }

    void deliverLdsResourceNotFound(final String resourceName) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (!resourceName.equals(ldsResource)) {
            return;
          }
          ldsWatcher.onResourceDoesNotExist(ldsResource);
        }
      });
    }

    void deliverRdsUpdateWithFaultInjection(
        final HttpFault virtualHostFaultConfig, final HttpFault routFaultConfig,
        final HttpFault weightedClusterFaultConfig) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          ClusterWeight clusterWeight =
              ClusterWeight.create(cluster1, 100, weightedClusterFaultConfig);
          Route route = Route.create(
              RouteMatch.create(
                  PathMatcher.fromPrefix("/", false), Collections.<HeaderMatcher>emptyList(), null),
              RouteAction.forWeightedClusters(
                  Collections.singletonList(clusterWeight),
                  Collections.<HashPolicy>emptyList(),
                  null),
              routFaultConfig);
          VirtualHost virtualHost = VirtualHost.create(
              "virtual-host",
              Collections.singletonList(AUTHORITY),
              Collections.singletonList(route), virtualHostFaultConfig);
          rdsWatcher.onChanged(new RdsUpdate(Collections.singletonList(virtualHost)));
        }
      });
    }

    void deliverRdsUpdate(final String resourceName, final List<VirtualHost> virtualHosts) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          rdsWatcher.onChanged(new RdsUpdate(virtualHosts));
        }
      });
    }

    void deliverRdsResourceNotFound(final String resourceName) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (!resourceName.equals(rdsResource)) {
            return;
          }
          rdsWatcher.onResourceDoesNotExist(rdsResource);
        }
      });
    }

    void deliverError(final Status error) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (ldsWatcher != null) {
            ldsWatcher.onError(error);
          }
          if (rdsWatcher != null) {
            rdsWatcher.onError(error);
          }
        }
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
