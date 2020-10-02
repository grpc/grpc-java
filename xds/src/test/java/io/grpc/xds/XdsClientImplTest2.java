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
import static io.grpc.xds.XdsClientTestHelper.buildCluster;
import static io.grpc.xds.XdsClientTestHelper.buildClusterLoadAssignment;
import static io.grpc.xds.XdsClientTestHelper.buildDiscoveryRequest;
import static io.grpc.xds.XdsClientTestHelper.buildDiscoveryResponse;
import static io.grpc.xds.XdsClientTestHelper.buildDropOverload;
import static io.grpc.xds.XdsClientTestHelper.buildLbEndpoint;
import static io.grpc.xds.XdsClientTestHelper.buildListener;
import static io.grpc.xds.XdsClientTestHelper.buildLocalityLbEndpoints;
import static io.grpc.xds.XdsClientTestHelper.buildRouteConfiguration;
import static io.grpc.xds.XdsClientTestHelper.buildSecureCluster;
import static io.grpc.xds.XdsClientTestHelper.buildUpstreamTlsContext;
import static io.grpc.xds.XdsClientTestHelper.buildVirtualHost;
import static io.grpc.xds.XdsClientTestHelper.buildVirtualHosts;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Any;
import com.google.protobuf.util.Durations;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.HealthStatus;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.Policy;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterStats;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.envoyproxy.envoy.service.load_stats.v3.LoadReportingServiceGrpc.LoadReportingServiceImplBase;
import io.envoyproxy.envoy.service.load_stats.v3.LoadStatsRequest;
import io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse;
import io.grpc.Context;
import io.grpc.Context.CancellationListener;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.internal.FakeClock.ScheduledTask;
import io.grpc.internal.FakeClock.TaskFilter;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.LbEndpoint;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.XdsClient.CdsResourceWatcher;
import io.grpc.xds.XdsClient.CdsUpdate;
import io.grpc.xds.XdsClient.EdsResourceWatcher;
import io.grpc.xds.XdsClient.EdsUpdate;
import io.grpc.xds.XdsClient.LdsResourceWatcher;
import io.grpc.xds.XdsClient.LdsUpdate;
import io.grpc.xds.XdsClient.RdsResourceWatcher;
import io.grpc.xds.XdsClient.RdsUpdate;
import io.grpc.xds.XdsClient.ResourceWatcher;
import io.grpc.xds.XdsClient.XdsChannel;
import io.grpc.xds.XdsClientImpl2.MessagePrinter;
import io.grpc.xds.XdsClientImpl2.ResourceType;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link XdsClientImpl2}.
 */
@RunWith(JUnit4.class)
public class XdsClientImplTest2 {
  private static final String TARGET_NAME = "hello.googleapis.com";
  private static final String LDS_RESOURCE = "listener.googleapis.com";
  private static final String RDS_RESOURCE = "route-configuration.googleapis.com";
  private static final String CDS_RESOURCE = "cluster.googleapis.com";
  private static final String EDS_RESOURCE = "cluster-load-assignment.googleapis.com";
  private static final Node NODE = Node.newBuilder().build();
  private static final FakeClock.TaskFilter RPC_RETRY_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(XdsClientImpl2.RpcRetryTask.class.getSimpleName());
        }
      };

  private static final FakeClock.TaskFilter LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(ResourceType.LDS.toString());
        }
      };

  private static final FakeClock.TaskFilter RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(ResourceType.RDS.toString());
        }
      };

  private static final FakeClock.TaskFilter CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(ResourceType.CDS.toString());
        }
      };

  private static final FakeClock.TaskFilter EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(ResourceType.EDS.toString());
        }
      };

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
  private final Queue<RpcCall<DiscoveryRequest, DiscoveryResponse>> resourceDiscoveryCalls =
      new ArrayDeque<>();
  private final Queue<RpcCall<LoadStatsRequest, LoadStatsResponse>> loadReportCalls =
      new ArrayDeque<>();
  private final AtomicBoolean adsEnded = new AtomicBoolean(true);
  private final AtomicBoolean lrsEnded = new AtomicBoolean(true);

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
  private LdsResourceWatcher ldsResourceWatcher;
  @Mock
  private RdsResourceWatcher rdsResourceWatcher;
  @Mock
  private CdsResourceWatcher cdsResourceWatcher;
  @Mock
  private EdsResourceWatcher edsResourceWatcher;

  private ManagedChannel channel;
  private XdsClientImpl2 xdsClient;

  @Before
  public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);
    when(backoffPolicyProvider.get()).thenReturn(backoffPolicy1, backoffPolicy2);
    when(backoffPolicy1.nextBackoffNanos()).thenReturn(10L, 100L);
    when(backoffPolicy2.nextBackoffNanos()).thenReturn(20L, 200L);

    final String serverName = InProcessServerBuilder.generateName();
    AggregatedDiscoveryServiceImplBase adsServiceImpl = new AggregatedDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamAggregatedResources(
          final StreamObserver<DiscoveryResponse> responseObserver) {
        assertThat(adsEnded.get()).isTrue();  // ensure previous call was ended
        adsEnded.set(false);
        @SuppressWarnings("unchecked")
        StreamObserver<DiscoveryRequest> requestObserver = mock(StreamObserver.class);
        RpcCall<DiscoveryRequest, DiscoveryResponse> call =
            new RpcCall<>(requestObserver, responseObserver);
        resourceDiscoveryCalls.offer(call);
        Context.current().addListener(
            new CancellationListener() {
              @Override
              public void cancelled(Context context) {
                adsEnded.set(true);
              }
            }, MoreExecutors.directExecutor());
        return requestObserver;
      }
    };

    LoadReportingServiceImplBase lrsServiceImpl = new LoadReportingServiceImplBase() {
      @Override
      public StreamObserver<LoadStatsRequest> streamLoadStats(
          StreamObserver<LoadStatsResponse> responseObserver) {
        assertThat(lrsEnded.get()).isTrue();
        lrsEnded.set(false);
        @SuppressWarnings("unchecked")
        StreamObserver<LoadStatsRequest> requestObserver = mock(StreamObserver.class);
        RpcCall<LoadStatsRequest, LoadStatsResponse> call =
            new RpcCall<>(requestObserver, responseObserver);
        Context.current().addListener(
            new CancellationListener() {
              @Override
              public void cancelled(Context context) {
                lrsEnded.set(true);
              }
            }, MoreExecutors.directExecutor());
        loadReportCalls.offer(call);
        return requestObserver;
      }
    };

    cleanupRule.register(
        InProcessServerBuilder
            .forName(serverName)
            .addService(adsServiceImpl)
            .addService(lrsServiceImpl)
            .directExecutor()
            .build()
            .start());
    channel =
        cleanupRule.register(InProcessChannelBuilder.forName(serverName).directExecutor().build());

    xdsClient =
        new XdsClientImpl2(
            TARGET_NAME,
            new XdsChannel(channel, /* useProtocolV3= */ true),
            EnvoyProtoData.Node.newBuilder().build(),
            syncContext,
            fakeClock.getScheduledExecutorService(),
            backoffPolicyProvider,
            fakeClock.getStopwatchSupplier());

    assertThat(resourceDiscoveryCalls).isEmpty();
    assertThat(loadReportCalls).isEmpty();
  }

  @After
  public void tearDown() {
    xdsClient.shutdown();
    assertThat(adsEnded.get()).isTrue();
    assertThat(lrsEnded.get()).isTrue();
    assertThat(channel.isShutdown()).isTrue();
    assertThat(fakeClock.getPendingTasks()).isEmpty();
  }

  @Test
  public void ldsResourceNotFound() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.LDS, LDS_RESOURCE, ldsResourceWatcher);
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener("bar.googleapis.com",
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRouteConfig(
                        buildRouteConfiguration("route-bar.googleapis.com", buildVirtualHosts(1)))
                    .build()))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, ResourceType.LDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "0", LDS_RESOURCE, ResourceType.LDS.typeUrl(), "0000")));

    verifyNoInteractions(ldsResourceWatcher);
    fakeClock.forwardTime(XdsClientImpl2.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(ldsResourceWatcher).onResourceDoesNotExist(LDS_RESOURCE);
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  @Test
  public void ldsResourceFound_containsVirtualHosts() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.LDS, LDS_RESOURCE, ldsResourceWatcher);
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(LDS_RESOURCE,
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRouteConfig(buildRouteConfiguration("do not care", buildVirtualHosts(2)))
                    .build()))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, ResourceType.LDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "0", LDS_RESOURCE, ResourceType.LDS.typeUrl(), "0000")));
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().getVirtualHosts()).hasSize(2);
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  @Test
  public void ldsResourceFound_containsRdsName() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.LDS, LDS_RESOURCE, ldsResourceWatcher);
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(LDS_RESOURCE,
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRds(
                        Rds.newBuilder()
                            .setRouteConfigName(RDS_RESOURCE)
                            .setConfigSource(
                                ConfigSource.newBuilder()
                                    .setAds(AggregatedConfigSource.getDefaultInstance())))
                    .build()))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, ResourceType.LDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "0", LDS_RESOURCE, ResourceType.LDS.typeUrl(), "0000")));
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().getRdsName()).isEqualTo(RDS_RESOURCE);
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  @Test
  public void cachedLdsResource_data() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.LDS, LDS_RESOURCE, ldsResourceWatcher);
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(LDS_RESOURCE,
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRds(
                        Rds.newBuilder()
                            .setRouteConfigName(RDS_RESOURCE)
                            .setConfigSource(
                                ConfigSource.newBuilder()
                                    .setAds(AggregatedConfigSource.getDefaultInstance())))
                    .build()))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, ResourceType.LDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "0", LDS_RESOURCE, ResourceType.LDS.typeUrl(), "0000")));
    LdsResourceWatcher watcher = mock(LdsResourceWatcher.class);
    xdsClient.watchLdsResource(LDS_RESOURCE, watcher);
    verify(watcher).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().getRdsName()).isEqualTo(RDS_RESOURCE);
    verifyNoMoreInteractions(call.requestObserver);
  }

  @Test
  public void cachedLdsResource_absent() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.LDS, LDS_RESOURCE, ldsResourceWatcher);
    fakeClock.forwardTime(XdsClientImpl2.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(ldsResourceWatcher).onResourceDoesNotExist(LDS_RESOURCE);
    LdsResourceWatcher watcher = mock(LdsResourceWatcher.class);
    xdsClient.watchLdsResource(LDS_RESOURCE, watcher);
    verify(watcher).onResourceDoesNotExist(LDS_RESOURCE);
    verifyNoMoreInteractions(call.requestObserver);
  }

  @Test
  public void ldsResourceUpdated() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.LDS, LDS_RESOURCE, ldsResourceWatcher);
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(LDS_RESOURCE,
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRouteConfig(buildRouteConfiguration("do not care", buildVirtualHosts(2)))
                    .build()))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, ResourceType.LDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "0", LDS_RESOURCE, ResourceType.LDS.typeUrl(), "0000")));
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().getVirtualHosts()).hasSize(2);

    listeners = ImmutableList.of(
        Any.pack(buildListener(LDS_RESOURCE,
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRds(
                        Rds.newBuilder()
                            .setRouteConfigName(RDS_RESOURCE)
                            .setConfigSource(
                                ConfigSource.newBuilder()
                                    .setAds(AggregatedConfigSource.getDefaultInstance())))
                    .build()))));
    response =
        buildDiscoveryResponse("1", listeners, ResourceType.LDS.typeUrl(), "0001");
    call.responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "1", LDS_RESOURCE, ResourceType.LDS.typeUrl(), "0001")));
    verify(ldsResourceWatcher, times(2)).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().getRdsName()).isEqualTo(RDS_RESOURCE);
  }

  @Test
  public void ldsResourceDeleted() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.LDS, LDS_RESOURCE, ldsResourceWatcher);
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(LDS_RESOURCE,
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRouteConfig(buildRouteConfiguration("do not care", buildVirtualHosts(2)))
                    .build()))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, ResourceType.LDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "0", LDS_RESOURCE, ResourceType.LDS.typeUrl(), "0000")));
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().getVirtualHosts()).hasSize(2);

    response = buildDiscoveryResponse("1", Collections.<Any>emptyList(),
        ResourceType.LDS.typeUrl(), "0001");
    call.responseObserver.onNext(response);

    // Client sends an ACK LDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "1", LDS_RESOURCE, ResourceType.LDS.typeUrl(), "0001")));
    verify(ldsResourceWatcher).onResourceDoesNotExist(LDS_RESOURCE);
  }

  @Test
  public void multipleLdsWatchers() {
    String ldsResource = "bar.googleapis.com";
    LdsResourceWatcher watcher1 = mock(LdsResourceWatcher.class);
    LdsResourceWatcher watcher2 = mock(LdsResourceWatcher.class);
    xdsClient.watchLdsResource(LDS_RESOURCE, ldsResourceWatcher);
    xdsClient.watchLdsResource(ldsResource, watcher1);
    xdsClient.watchLdsResource(ldsResource, watcher2);
    RpcCall<DiscoveryRequest, DiscoveryResponse> call = resourceDiscoveryCalls.poll();
    verify(call.requestObserver).onNext(
        argThat(new DiscoveryRequestMatcher(NODE, "", Arrays.asList(LDS_RESOURCE, ldsResource),
            ResourceType.LDS.typeUrl(), "")));

    fakeClock.forwardTime(XdsClientImpl2.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(ldsResourceWatcher).onResourceDoesNotExist(LDS_RESOURCE);
    verify(watcher1).onResourceDoesNotExist(ldsResource);
    verify(watcher2).onResourceDoesNotExist(ldsResource);

    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(LDS_RESOURCE,
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRouteConfig(buildRouteConfiguration("do not care", buildVirtualHosts(2)))
                    .build()))),
        Any.pack(buildListener(ldsResource,
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRouteConfig(buildRouteConfiguration("do not care", buildVirtualHosts(4)))
                    .build()))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, ResourceType.LDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().getVirtualHosts()).hasSize(2);
    verify(watcher1).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().getVirtualHosts()).hasSize(4);
    verify(watcher2).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().getVirtualHosts()).hasSize(4);
  }

  @Test
  public void rdsResourceNotFound() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.RDS, RDS_RESOURCE, rdsResourceWatcher);
    List<Any> routeConfigs = ImmutableList.of(
        Any.pack(buildRouteConfiguration("route-bar.googleapis.com", buildVirtualHosts(2))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", routeConfigs, ResourceType.RDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    // Client sends an ACK RDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "0", RDS_RESOURCE, ResourceType.RDS.typeUrl(), "0000")));

    verifyNoInteractions(rdsResourceWatcher);
    fakeClock.forwardTime(XdsClientImpl2.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(rdsResourceWatcher).onResourceDoesNotExist(RDS_RESOURCE);
    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  @Test
  public void rdsResourceFound() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.RDS, RDS_RESOURCE, rdsResourceWatcher);
    List<Any> routeConfigs =
        ImmutableList.of(Any.pack(buildRouteConfiguration(RDS_RESOURCE, buildVirtualHosts(2))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", routeConfigs, ResourceType.RDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    // Client sends an ACK RDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "0", RDS_RESOURCE, ResourceType.RDS.typeUrl(), "0000")));
    verify(rdsResourceWatcher).onChanged(rdsUpdateCaptor.capture());
    assertThat(rdsUpdateCaptor.getValue().getVirtualHosts()).hasSize(2);
    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  @Test
  public void cachedRdsResource_data() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.RDS, RDS_RESOURCE, rdsResourceWatcher);
    List<Any> routeConfigs =
        ImmutableList.of(Any.pack(buildRouteConfiguration(RDS_RESOURCE, buildVirtualHosts(2))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", routeConfigs, ResourceType.RDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    // Client sends an ACK RDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "0", RDS_RESOURCE, ResourceType.RDS.typeUrl(), "0000")));

    RdsResourceWatcher watcher = mock(RdsResourceWatcher.class);
    xdsClient.watchRdsResource(RDS_RESOURCE, watcher);
    verify(watcher).onChanged(rdsUpdateCaptor.capture());
    assertThat(rdsUpdateCaptor.getValue().getVirtualHosts()).hasSize(2);
    verifyNoMoreInteractions(call.requestObserver);
  }

  @Test
  public void cachedRdsResource_absent() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.RDS, RDS_RESOURCE, rdsResourceWatcher);
    fakeClock.forwardTime(XdsClientImpl2.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(rdsResourceWatcher).onResourceDoesNotExist(RDS_RESOURCE);
    RdsResourceWatcher watcher = mock(RdsResourceWatcher.class);
    xdsClient.watchRdsResource(RDS_RESOURCE, watcher);
    verify(watcher).onResourceDoesNotExist(RDS_RESOURCE);
    verifyNoMoreInteractions(call.requestObserver);
  }

  @Test
  public void rdsResourceUpdated() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.RDS, RDS_RESOURCE, rdsResourceWatcher);
    List<Any> routeConfigs =
        ImmutableList.of(Any.pack(buildRouteConfiguration(RDS_RESOURCE, buildVirtualHosts(2))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", routeConfigs, ResourceType.RDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    // Client sends an ACK RDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "0", RDS_RESOURCE, ResourceType.RDS.typeUrl(), "0000")));
    verify(rdsResourceWatcher).onChanged(rdsUpdateCaptor.capture());
    assertThat(rdsUpdateCaptor.getValue().getVirtualHosts()).hasSize(2);

    routeConfigs =
        ImmutableList.of(Any.pack(buildRouteConfiguration(RDS_RESOURCE, buildVirtualHosts(4))));
    response =
        buildDiscoveryResponse("1", routeConfigs, ResourceType.RDS.typeUrl(), "0001");
    call.responseObserver.onNext(response);

    // Client sends an ACK RDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "1", RDS_RESOURCE, ResourceType.RDS.typeUrl(), "0001")));
    verify(rdsResourceWatcher, times(2)).onChanged(rdsUpdateCaptor.capture());
    assertThat(rdsUpdateCaptor.getValue().getVirtualHosts()).hasSize(4);
  }

  @Test
  public void rdsResourceDeletedByLds() {
    xdsClient.watchLdsResource(LDS_RESOURCE, ldsResourceWatcher);
    xdsClient.watchRdsResource(RDS_RESOURCE, rdsResourceWatcher);
    RpcCall<DiscoveryRequest, DiscoveryResponse> call = resourceDiscoveryCalls.poll();
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(LDS_RESOURCE,
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRds(
                        Rds.newBuilder()
                            .setRouteConfigName(RDS_RESOURCE)
                            .setConfigSource(
                                ConfigSource.newBuilder()
                                    .setAds(AggregatedConfigSource.getDefaultInstance())))
                    .build()))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, ResourceType.LDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);
    verify(ldsResourceWatcher).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().getRdsName()).isEqualTo(RDS_RESOURCE);

    List<Any> routeConfigs =
        ImmutableList.of(Any.pack(buildRouteConfiguration(RDS_RESOURCE, buildVirtualHosts(2))));
    response = buildDiscoveryResponse("0", routeConfigs, ResourceType.RDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);
    verify(rdsResourceWatcher).onChanged(rdsUpdateCaptor.capture());
    assertThat(rdsUpdateCaptor.getValue().getVirtualHosts()).hasSize(2);

    listeners = ImmutableList.of(
        Any.pack(buildListener(LDS_RESOURCE,
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRouteConfig(buildRouteConfiguration("do not care", buildVirtualHosts(5)))
                    .build()))));
    response = buildDiscoveryResponse("1", listeners, ResourceType.LDS.typeUrl(), "0001");
    call.responseObserver.onNext(response);
    verify(ldsResourceWatcher, times(2)).onChanged(ldsUpdateCaptor.capture());
    assertThat(ldsUpdateCaptor.getValue().getVirtualHosts()).hasSize(5);
    verify(rdsResourceWatcher).onResourceDoesNotExist(RDS_RESOURCE);
  }

  @Test
  public void multipleRdsWatchers() {
    String rdsResource = "route-bar.googleapis.com";
    RdsResourceWatcher watcher1 = mock(RdsResourceWatcher.class);
    RdsResourceWatcher watcher2 = mock(RdsResourceWatcher.class);
    xdsClient.watchRdsResource(RDS_RESOURCE, rdsResourceWatcher);
    xdsClient.watchRdsResource(rdsResource, watcher1);
    xdsClient.watchRdsResource(rdsResource, watcher2);
    RpcCall<DiscoveryRequest, DiscoveryResponse> call = resourceDiscoveryCalls.poll();
    verify(call.requestObserver).onNext(
        argThat(new DiscoveryRequestMatcher(NODE, "", Arrays.asList(RDS_RESOURCE, rdsResource),
            ResourceType.RDS.typeUrl(), "")));

    fakeClock.forwardTime(XdsClientImpl2.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(rdsResourceWatcher).onResourceDoesNotExist(RDS_RESOURCE);
    verify(watcher1).onResourceDoesNotExist(rdsResource);
    verify(watcher2).onResourceDoesNotExist(rdsResource);

    List<Any> routeConfigs =
        ImmutableList.of(Any.pack(buildRouteConfiguration(RDS_RESOURCE, buildVirtualHosts(2))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", routeConfigs, ResourceType.RDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    verify(rdsResourceWatcher).onChanged(rdsUpdateCaptor.capture());
    assertThat(rdsUpdateCaptor.getValue().getVirtualHosts()).hasSize(2);
    verifyNoMoreInteractions(watcher1, watcher2);

    routeConfigs =
        ImmutableList.of(Any.pack(buildRouteConfiguration(rdsResource, buildVirtualHosts(4))));
    response =
        buildDiscoveryResponse("2", routeConfigs, ResourceType.RDS.typeUrl(), "0002");
    call.responseObserver.onNext(response);

    verify(watcher1).onChanged(rdsUpdateCaptor.capture());
    assertThat(rdsUpdateCaptor.getValue().getVirtualHosts()).hasSize(4);
    verify(watcher2).onChanged(rdsUpdateCaptor.capture());
    assertThat(rdsUpdateCaptor.getValue().getVirtualHosts()).hasSize(4);
    verifyNoMoreInteractions(rdsResourceWatcher);
  }

  @Test
  public void cdsResourceNotFound() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.CDS, CDS_RESOURCE, cdsResourceWatcher);

    List<Any> clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster-bar.googleapis.com", null, false)),
        Any.pack(buildCluster("cluster-baz.googleapis.com", null, false)));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusters, ResourceType.CDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    // Client sent an ACK CDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "0", CDS_RESOURCE, ResourceType.CDS.typeUrl(), "0000")));
    verifyNoInteractions(cdsResourceWatcher);

    fakeClock.forwardTime(XdsClientImpl2.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(cdsResourceWatcher).onResourceDoesNotExist(CDS_RESOURCE);
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  @Test
  public void cdsResourceFound() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.CDS, CDS_RESOURCE, cdsResourceWatcher);
    List<Any> clusters = ImmutableList.of(Any.pack(buildCluster(CDS_RESOURCE, null, false)));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusters, ResourceType.CDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    // Client sent an ACK CDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "0", CDS_RESOURCE, ResourceType.CDS.typeUrl(), "0000")));
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.getClusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.getEdsServiceName()).isNull();
    assertThat(cdsUpdate.getLbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate.getLrsServerName()).isNull();
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  /**
   * CDS response containing UpstreamTlsContext for a cluster.
   */
  @Test
  public void cdsResponseWithUpstreamTlsContext() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.CDS, CDS_RESOURCE, cdsResourceWatcher);

    // Management server sends back CDS response with UpstreamTlsContext.
    UpstreamTlsContext testUpstreamTlsContext =
        buildUpstreamTlsContext("secret1", "unix:/var/uds2");
    List<Any> clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster-bar.googleapis.com", null, false)),
        Any.pack(buildSecureCluster(CDS_RESOURCE,
            "eds-cluster-foo.googleapis.com", true, testUpstreamTlsContext)),
        Any.pack(buildCluster("cluster-baz.googleapis.com", null, false)));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusters, ResourceType.CDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    // Client sent an ACK CDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "0", CDS_RESOURCE, ResourceType.CDS.typeUrl(), "0000")));
    verify(cdsResourceWatcher, times(1)).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    EnvoyServerProtoData.UpstreamTlsContext upstreamTlsContext = cdsUpdate
        .getUpstreamTlsContext();
    SdsSecretConfig validationContextSdsSecretConfig = upstreamTlsContext.getCommonTlsContext()
        .getValidationContextSdsSecretConfig();
    assertThat(validationContextSdsSecretConfig.getName()).isEqualTo("secret1");
    assertThat(
        Iterables.getOnlyElement(
            validationContextSdsSecretConfig
                .getSdsConfig()
                .getApiConfigSource()
                .getGrpcServicesList())
            .getGoogleGrpc()
            .getTargetUri())
        .isEqualTo("unix:/var/uds2");
  }

  @Test
  public void cachedCdsResource_data() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.CDS, CDS_RESOURCE, cdsResourceWatcher);
    List<Any> clusters = ImmutableList.of(Any.pack(buildCluster(CDS_RESOURCE, null, false)));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusters, ResourceType.CDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    // Client sends an ACK CDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "0", CDS_RESOURCE, ResourceType.CDS.typeUrl(), "0000")));

    CdsResourceWatcher watcher = mock(CdsResourceWatcher.class);
    xdsClient.watchCdsResource(CDS_RESOURCE, watcher);
    verify(watcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.getClusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.getEdsServiceName()).isNull();
    assertThat(cdsUpdate.getLbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate.getLrsServerName()).isNull();
    verifyNoMoreInteractions(call.requestObserver);
  }

  @Test
  public void cachedCdsResource_absent() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.CDS, CDS_RESOURCE, cdsResourceWatcher);
    fakeClock.forwardTime(XdsClientImpl2.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(cdsResourceWatcher).onResourceDoesNotExist(CDS_RESOURCE);
    CdsResourceWatcher watcher = mock(CdsResourceWatcher.class);
    xdsClient.watchCdsResource(CDS_RESOURCE, watcher);
    verify(watcher).onResourceDoesNotExist(CDS_RESOURCE);
    verifyNoMoreInteractions(call.requestObserver);
  }

  @Test
  public void cdsResourceUpdated() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.CDS, CDS_RESOURCE, cdsResourceWatcher);
    List<Any> clusters = ImmutableList.of(Any.pack(buildCluster(CDS_RESOURCE, null, false)));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusters, ResourceType.CDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    // Client sends an ACK CDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "0", CDS_RESOURCE, ResourceType.CDS.typeUrl(), "0000")));
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.getClusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.getEdsServiceName()).isNull();
    assertThat(cdsUpdate.getLbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate.getLrsServerName()).isNull();

    String edsService = "eds-service-bar.googleapis.com";
    clusters = ImmutableList.of(Any.pack(buildCluster(CDS_RESOURCE, edsService, true)));
    response = buildDiscoveryResponse("1", clusters, ResourceType.CDS.typeUrl(), "0001");
    call.responseObserver.onNext(response);

    // Client sends an ACK CDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "1", CDS_RESOURCE, ResourceType.CDS.typeUrl(), "0001")));
    verify(cdsResourceWatcher, times(2)).onChanged(cdsUpdateCaptor.capture());
    cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.getClusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.getEdsServiceName()).isEqualTo(edsService);
    assertThat(cdsUpdate.getLbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate.getLrsServerName()).isEqualTo("");
  }

  @Test
  public void cdsResourceDeleted() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.CDS, CDS_RESOURCE, cdsResourceWatcher);
    List<Any> clusters = ImmutableList.of(Any.pack(buildCluster(CDS_RESOURCE, null, false)));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusters, ResourceType.CDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    // Client sends an ACK CDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "0", CDS_RESOURCE, ResourceType.CDS.typeUrl(), "0000")));
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.getClusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.getEdsServiceName()).isNull();
    assertThat(cdsUpdate.getLbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate.getLrsServerName()).isNull();

    response = buildDiscoveryResponse("1", Collections.<Any>emptyList(),
        ResourceType.CDS.typeUrl(), "0001");
    call.responseObserver.onNext(response);

    // Client sends an ACK CDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "1", CDS_RESOURCE, ResourceType.CDS.typeUrl(), "0001")));
    verify(cdsResourceWatcher).onResourceDoesNotExist(CDS_RESOURCE);
  }

  @Test
  public void multipleCdsWatchers() {
    String cdsResource = "cluster-bar.googleapis.com";
    CdsResourceWatcher watcher1 = mock(CdsResourceWatcher.class);
    CdsResourceWatcher watcher2 = mock(CdsResourceWatcher.class);
    xdsClient.watchCdsResource(CDS_RESOURCE, cdsResourceWatcher);
    xdsClient.watchCdsResource(cdsResource, watcher1);
    xdsClient.watchCdsResource(cdsResource, watcher2);
    RpcCall<DiscoveryRequest, DiscoveryResponse> call = resourceDiscoveryCalls.poll();
    verify(call.requestObserver).onNext(
        argThat(new DiscoveryRequestMatcher(NODE, "", Arrays.asList(CDS_RESOURCE, cdsResource),
            ResourceType.CDS.typeUrl(), "")));

    fakeClock.forwardTime(XdsClientImpl2.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(cdsResourceWatcher).onResourceDoesNotExist(CDS_RESOURCE);
    verify(watcher1).onResourceDoesNotExist(cdsResource);
    verify(watcher2).onResourceDoesNotExist(cdsResource);

    String edsService = "eds-service-bar.googleapis.com";
    List<Any> clusters = ImmutableList.of(
        Any.pack(buildCluster(CDS_RESOURCE, null, false)),
        Any.pack(buildCluster(cdsResource, edsService, true)));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusters, ResourceType.CDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    CdsUpdate cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.getClusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdate.getEdsServiceName()).isNull();
    assertThat(cdsUpdate.getLbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate.getLrsServerName()).isNull();
    verify(watcher1).onChanged(cdsUpdateCaptor.capture());
    cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.getClusterName()).isEqualTo(cdsResource);
    assertThat(cdsUpdate.getEdsServiceName()).isEqualTo(edsService);
    assertThat(cdsUpdate.getLbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate.getLrsServerName()).isEqualTo("");
    verify(watcher2).onChanged(cdsUpdateCaptor.capture());
    cdsUpdate = cdsUpdateCaptor.getValue();
    assertThat(cdsUpdate.getClusterName()).isEqualTo(cdsResource);
    assertThat(cdsUpdate.getEdsServiceName()).isEqualTo(edsService);
    assertThat(cdsUpdate.getLbPolicy()).isEqualTo("round_robin");
    assertThat(cdsUpdate.getLrsServerName()).isEqualTo("");
  }

  @Test
  public void edsResourceNotFound() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.EDS, EDS_RESOURCE, edsResourceWatcher);
    List<Any> clusterLoadAssignments =
        ImmutableList.of(
            Any.pack(
                buildClusterLoadAssignment("cluster-bar.googleapis.com",
                    ImmutableList.of(
                        buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2)),
                    1, 0)),
                    ImmutableList.<ClusterLoadAssignment.Policy.DropOverload>of())));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusterLoadAssignments, ResourceType.EDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    // Client sent an ACK EDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "0", EDS_RESOURCE, ResourceType.EDS.typeUrl(), "0000")));
    verifyNoInteractions(edsResourceWatcher);

    fakeClock.forwardTime(XdsClientImpl2.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(edsResourceWatcher).onResourceDoesNotExist(EDS_RESOURCE);
    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).isEmpty();
  }

  @Test
  public void edsResourceFound() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.EDS, EDS_RESOURCE, edsResourceWatcher);
    List<Any> clusterLoadAssignments =
        ImmutableList.of(
            Any.pack(
                buildClusterLoadAssignment(EDS_RESOURCE,
                    ImmutableList.of(
                        buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                            ImmutableList.of(
                                buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2)),
                            1, 0),
                        buildLocalityLbEndpoints("region3", "zone3", "subzone3",
                            ImmutableList.<io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint>of(),
                            2, 1), /* locality with 0 endpoint */
                        buildLocalityLbEndpoints("region4", "zone4", "subzone4",
                            ImmutableList.of(
                                buildLbEndpoint("192.168.142.5", 80, HealthStatus.UNKNOWN, 5)),
                            0, 2) /* locality with 0 weight */),
                    ImmutableList.of(
                        buildDropOverload("lb", 200),
                        buildDropOverload("throttle", 1000)))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusterLoadAssignments, ResourceType.EDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    // Client sent an ACK EDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "0", EDS_RESOURCE, ResourceType.EDS.typeUrl(), "0000")));
    verify(edsResourceWatcher).onChanged(edsUpdateCaptor.capture());
    EdsUpdate edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.getClusterName()).isEqualTo(EDS_RESOURCE);
    assertThat(edsUpdate.getDropPolicies())
        .containsExactly(
            new DropOverload("lb", 200),
            new DropOverload("throttle", 1000));
    assertThat(edsUpdate.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region1", "zone1", "subzone1"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.0.1", 8080,
                        2, true)), 1, 0),
            new Locality("region3", "zone3", "subzone3"),
            new LocalityLbEndpoints(ImmutableList.<LbEndpoint>of(), 2, 1));
  }

  @Test
  public void cachedEdsResource_data() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.EDS, EDS_RESOURCE, edsResourceWatcher);
    List<Any> clusterLoadAssignments =
        ImmutableList.of(
            Any.pack(
                buildClusterLoadAssignment(EDS_RESOURCE,
                    ImmutableList.of(
                        buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                            ImmutableList.of(
                                buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2)),
                            1, 0),
                        buildLocalityLbEndpoints("region3", "zone3", "subzone3",
                            ImmutableList.<io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint>of(),
                            2, 1), /* locality with 0 endpoint */
                        buildLocalityLbEndpoints("region4", "zone4", "subzone4",
                            ImmutableList.of(
                                buildLbEndpoint("192.168.142.5", 80, HealthStatus.UNKNOWN, 5)),
                            0, 2) /* locality with 0 weight */),
                    ImmutableList.of(
                        buildDropOverload("lb", 200),
                        buildDropOverload("throttle", 1000)))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusterLoadAssignments, ResourceType.EDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    // Client sends an ACK EDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "0", EDS_RESOURCE, ResourceType.EDS.typeUrl(), "0000")));

    EdsResourceWatcher watcher = mock(EdsResourceWatcher.class);
    xdsClient.watchEdsResource(EDS_RESOURCE, watcher);
    verify(watcher).onChanged(edsUpdateCaptor.capture());
    EdsUpdate edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.getClusterName()).isEqualTo(EDS_RESOURCE);
    assertThat(edsUpdate.getDropPolicies())
        .containsExactly(
            new DropOverload("lb", 200),
            new DropOverload("throttle", 1000));
    assertThat(edsUpdate.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region1", "zone1", "subzone1"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.0.1", 8080,
                        2, true)), 1, 0),
            new Locality("region3", "zone3", "subzone3"),
            new LocalityLbEndpoints(ImmutableList.<LbEndpoint>of(), 2, 1));
    verifyNoMoreInteractions(call.requestObserver);
  }

  @Test
  public void cachedEdsResource_absent() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.EDS, EDS_RESOURCE, edsResourceWatcher);
    fakeClock.forwardTime(XdsClientImpl2.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(edsResourceWatcher).onResourceDoesNotExist(EDS_RESOURCE);
    EdsResourceWatcher watcher = mock(EdsResourceWatcher.class);
    xdsClient.watchEdsResource(EDS_RESOURCE, watcher);
    verify(watcher).onResourceDoesNotExist(EDS_RESOURCE);
    verifyNoMoreInteractions(call.requestObserver);
  }

  @Test
  public void edsResourceUpdated() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.EDS, EDS_RESOURCE, edsResourceWatcher);
    List<Any> clusterLoadAssignments =
        ImmutableList.of(
            Any.pack(
                buildClusterLoadAssignment(EDS_RESOURCE,
                    ImmutableList.of(
                        buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                            ImmutableList.of(
                                buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2)),
                            1, 0),
                        buildLocalityLbEndpoints("region3", "zone3", "subzone3",
                            ImmutableList.<io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint>of(),
                            2, 1), /* locality with 0 endpoint */
                        buildLocalityLbEndpoints("region4", "zone4", "subzone4",
                            ImmutableList.of(
                                buildLbEndpoint("192.168.142.5", 80, HealthStatus.UNKNOWN, 5)),
                            0, 2) /* locality with 0 weight */),
                    ImmutableList.of(
                        buildDropOverload("lb", 200),
                        buildDropOverload("throttle", 1000)))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusterLoadAssignments, ResourceType.EDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    // Client sent an ACK EDS request.
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "0", EDS_RESOURCE, ResourceType.EDS.typeUrl(), "0000")));
    verify(edsResourceWatcher).onChanged(edsUpdateCaptor.capture());
    EdsUpdate edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.getClusterName()).isEqualTo(EDS_RESOURCE);
    assertThat(edsUpdate.getDropPolicies())
        .containsExactly(
            new DropOverload("lb", 200),
            new DropOverload("throttle", 1000));
    assertThat(edsUpdate.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region1", "zone1", "subzone1"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.0.1", 8080, 2, true)), 1, 0),
            new Locality("region3", "zone3", "subzone3"),
            new LocalityLbEndpoints(ImmutableList.<LbEndpoint>of(), 2, 1));

    clusterLoadAssignments =
        ImmutableList.of(
            Any.pack(
                buildClusterLoadAssignment(EDS_RESOURCE,
                    ImmutableList.of(
                        buildLocalityLbEndpoints("region2", "zone2", "subzone2",
                            ImmutableList.of(
                                buildLbEndpoint("172.44.2.2", 8000, HealthStatus.HEALTHY, 3)),
                            2, 0)),
                    ImmutableList.<Policy.DropOverload>of())));
    response =
        buildDiscoveryResponse("1", clusterLoadAssignments, ResourceType.EDS.typeUrl(), "0001");
    call.responseObserver.onNext(response);

    verify(edsResourceWatcher, times(2)).onChanged(edsUpdateCaptor.capture());
    edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.getClusterName()).isEqualTo(EDS_RESOURCE);
    assertThat(edsUpdate.getDropPolicies()).isEmpty();
    assertThat(edsUpdate.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region2", "zone2", "subzone2"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("172.44.2.2", 8000, 3, true)), 2, 0));
  }

  @Test
  public void edsResourceDeletedByCds() {
    xdsClient.watchCdsResource(CDS_RESOURCE, cdsResourceWatcher);
    xdsClient.watchEdsResource(EDS_RESOURCE, edsResourceWatcher);
    RpcCall<DiscoveryRequest, DiscoveryResponse> call = resourceDiscoveryCalls.poll();
    List<Any> clusters =
        ImmutableList.of(Any.pack(buildCluster(CDS_RESOURCE, EDS_RESOURCE, false)));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusters, ResourceType.CDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);
    verify(cdsResourceWatcher).onChanged(cdsUpdateCaptor.capture());
    assertThat(cdsUpdateCaptor.getValue().getClusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdateCaptor.getValue().getEdsServiceName()).isEqualTo(EDS_RESOURCE);

    List<Any> clusterLoadAssignments =
        ImmutableList.of(
            Any.pack(
                buildClusterLoadAssignment(EDS_RESOURCE,
                    ImmutableList.of(
                        buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                            ImmutableList.of(
                                buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2)),
                            1, 0)),
                    ImmutableList.of(
                        buildDropOverload("lb", 200),
                        buildDropOverload("throttle", 1000)))));
    response =
        buildDiscoveryResponse("0", clusterLoadAssignments, ResourceType.EDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);
    verify(edsResourceWatcher).onChanged(edsUpdateCaptor.capture());
    EdsUpdate edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.getClusterName()).isEqualTo(EDS_RESOURCE);

    clusters = ImmutableList.of(Any.pack(buildCluster(CDS_RESOURCE, null, false)));
    response =
        buildDiscoveryResponse("1", clusters, ResourceType.CDS.typeUrl(), "0001");
    call.responseObserver.onNext(response);
    verify(cdsResourceWatcher, times(2)).onChanged(cdsUpdateCaptor.capture());
    assertThat(cdsUpdateCaptor.getValue().getClusterName()).isEqualTo(CDS_RESOURCE);
    assertThat(cdsUpdateCaptor.getValue().getEdsServiceName()).isNull();
    verify(edsResourceWatcher).onResourceDoesNotExist(EDS_RESOURCE);
  }

  @Test
  public void multipleEdsWatchers() {
    String edsResource = "cluster-load-assignment-bar.googleapis.com";
    EdsResourceWatcher watcher1 = mock(EdsResourceWatcher.class);
    EdsResourceWatcher watcher2 = mock(EdsResourceWatcher.class);
    xdsClient.watchEdsResource(EDS_RESOURCE, edsResourceWatcher);
    xdsClient.watchEdsResource(edsResource, watcher1);
    xdsClient.watchEdsResource(edsResource, watcher2);
    RpcCall<DiscoveryRequest, DiscoveryResponse> call = resourceDiscoveryCalls.poll();
    verify(call.requestObserver).onNext(
        argThat(new DiscoveryRequestMatcher(NODE, "", Arrays.asList(EDS_RESOURCE, edsResource),
            ResourceType.EDS.typeUrl(), "")));

    fakeClock.forwardTime(XdsClientImpl2.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS);
    verify(edsResourceWatcher).onResourceDoesNotExist(EDS_RESOURCE);
    verify(watcher1).onResourceDoesNotExist(edsResource);
    verify(watcher2).onResourceDoesNotExist(edsResource);

    List<Any> clusterLoadAssignments =
        ImmutableList.of(
            Any.pack(
                buildClusterLoadAssignment(EDS_RESOURCE,
                    ImmutableList.of(
                        buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                            ImmutableList.of(
                                buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2)),
                            1, 0),
                        buildLocalityLbEndpoints("region3", "zone3", "subzone3",
                            ImmutableList.<io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint>of(),
                            2, 1), /* locality with 0 endpoint */
                        buildLocalityLbEndpoints("region4", "zone4", "subzone4",
                            ImmutableList.of(
                                buildLbEndpoint("192.168.142.5", 80, HealthStatus.UNKNOWN, 5)),
                            0, 2) /* locality with 0 weight */),
                    ImmutableList.of(
                        buildDropOverload("lb", 200),
                        buildDropOverload("throttle", 1000)))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", clusterLoadAssignments, ResourceType.EDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);
    verify(edsResourceWatcher).onChanged(edsUpdateCaptor.capture());
    EdsUpdate edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.getClusterName()).isEqualTo(EDS_RESOURCE);
    assertThat(edsUpdate.getDropPolicies())
        .containsExactly(
            new DropOverload("lb", 200),
            new DropOverload("throttle", 1000));
    assertThat(edsUpdate.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region1", "zone1", "subzone1"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("192.168.0.1", 8080, 2, true)), 1, 0),
            new Locality("region3", "zone3", "subzone3"),
            new LocalityLbEndpoints(ImmutableList.<LbEndpoint>of(), 2, 1));
    verifyNoMoreInteractions(watcher1, watcher2);

    clusterLoadAssignments =
        ImmutableList.of(
            Any.pack(
                buildClusterLoadAssignment(edsResource,
                    ImmutableList.of(
                        buildLocalityLbEndpoints("region2", "zone2", "subzone2",
                            ImmutableList.of(
                                buildLbEndpoint("172.44.2.2", 8000, HealthStatus.HEALTHY, 3)),
                            2, 0)),
                    ImmutableList.<Policy.DropOverload>of())));
    response =
        buildDiscoveryResponse("1", clusterLoadAssignments, ResourceType.EDS.typeUrl(), "0001");
    call.responseObserver.onNext(response);

    verify(watcher1).onChanged(edsUpdateCaptor.capture());
    edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.getClusterName()).isEqualTo(edsResource);
    assertThat(edsUpdate.getDropPolicies()).isEmpty();
    assertThat(edsUpdate.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region2", "zone2", "subzone2"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("172.44.2.2", 8000, 3, true)), 2, 0));
    verify(watcher2).onChanged(edsUpdateCaptor.capture());
    edsUpdate = edsUpdateCaptor.getValue();
    assertThat(edsUpdate.getClusterName()).isEqualTo(edsResource);
    assertThat(edsUpdate.getDropPolicies()).isEmpty();
    assertThat(edsUpdate.getLocalityLbEndpointsMap())
        .containsExactly(
            new Locality("region2", "zone2", "subzone2"),
            new LocalityLbEndpoints(
                ImmutableList.of(
                    new LbEndpoint("172.44.2.2", 8000, 3, true)), 2, 0));
    verifyNoMoreInteractions(edsResourceWatcher);
  }

  @Test
  public void streamClosedAndRetryWithBackoff() {
    InOrder inOrder = Mockito.inOrder(backoffPolicyProvider, backoffPolicy1, backoffPolicy2);
    xdsClient.watchLdsResource(LDS_RESOURCE, ldsResourceWatcher);
    xdsClient.watchRdsResource(RDS_RESOURCE, rdsResourceWatcher);
    xdsClient.watchCdsResource(CDS_RESOURCE, cdsResourceWatcher);
    xdsClient.watchEdsResource(EDS_RESOURCE, edsResourceWatcher);
    RpcCall<DiscoveryRequest, DiscoveryResponse> call = resourceDiscoveryCalls.poll();
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "", LDS_RESOURCE, ResourceType.LDS.typeUrl(), "")));
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "", RDS_RESOURCE, ResourceType.RDS.typeUrl(), "")));
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "", CDS_RESOURCE, ResourceType.CDS.typeUrl(), "")));
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "", EDS_RESOURCE, ResourceType.EDS.typeUrl(), "")));

    // Management server closes the RPC stream with an error.
    call.responseObserver.onError(Status.UNKNOWN.asException());
    verify(ldsResourceWatcher).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNKNOWN);
    verify(rdsResourceWatcher).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNKNOWN);
    verify(cdsResourceWatcher).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNKNOWN);
    verify(edsResourceWatcher).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNKNOWN);

    // Retry after backoff.
    inOrder.verify(backoffPolicyProvider).get();
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    ScheduledTask retryTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER));
    assertThat(retryTask.getDelay(TimeUnit.NANOSECONDS)).isEqualTo(10L);
    fakeClock.forwardNanos(10L);
    call = resourceDiscoveryCalls.poll();
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "", LDS_RESOURCE, ResourceType.LDS.typeUrl(), "")));
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "", RDS_RESOURCE, ResourceType.RDS.typeUrl(), "")));
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "", CDS_RESOURCE, ResourceType.CDS.typeUrl(), "")));
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "", EDS_RESOURCE, ResourceType.EDS.typeUrl(), "")));

    // Management server becomes unreachable.
    call.responseObserver.onError(Status.UNAVAILABLE.asException());
    verify(ldsResourceWatcher, times(2)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    verify(rdsResourceWatcher, times(2)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    verify(cdsResourceWatcher, times(2)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    verify(edsResourceWatcher, times(2)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);

    // Retry after backoff.
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    retryTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER));
    assertThat(retryTask.getDelay(TimeUnit.NANOSECONDS)).isEqualTo(100L);
    fakeClock.forwardNanos(100L);
    call = resourceDiscoveryCalls.poll();
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "", LDS_RESOURCE, ResourceType.LDS.typeUrl(), "")));
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "", RDS_RESOURCE, ResourceType.RDS.typeUrl(), "")));
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "", CDS_RESOURCE, ResourceType.CDS.typeUrl(), "")));
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "", EDS_RESOURCE, ResourceType.EDS.typeUrl(), "")));

    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(LDS_RESOURCE,
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRouteConfig(buildRouteConfiguration("do not care", buildVirtualHosts(2)))
                    .build()))));
    DiscoveryResponse response =
        buildDiscoveryResponse("63", listeners, ResourceType.LDS.typeUrl(), "3242");
    call.responseObserver.onNext(response);
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "63", LDS_RESOURCE, ResourceType.LDS.typeUrl(), "3242")));

    List<Any> routeConfigs =
        ImmutableList.of(Any.pack(buildRouteConfiguration(RDS_RESOURCE, buildVirtualHosts(2))));
    response =
        buildDiscoveryResponse("5", routeConfigs, ResourceType.RDS.typeUrl(), "6764");
    call.responseObserver.onNext(response);
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "5", RDS_RESOURCE, ResourceType.RDS.typeUrl(), "6764")));

    call.responseObserver.onError(Status.DEADLINE_EXCEEDED.asException());
    verify(ldsResourceWatcher, times(3)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);
    verify(rdsResourceWatcher, times(3)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);
    verify(cdsResourceWatcher, times(3)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);
    verify(edsResourceWatcher, times(3)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);

    // Reset backoff sequence and retry immediately.
    inOrder.verify(backoffPolicyProvider).get();
    fakeClock.runDueTasks();
    call = resourceDiscoveryCalls.poll();
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "63", LDS_RESOURCE, ResourceType.LDS.typeUrl(), "")));
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "5", RDS_RESOURCE, ResourceType.RDS.typeUrl(), "")));
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "", CDS_RESOURCE, ResourceType.CDS.typeUrl(), "")));
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "", EDS_RESOURCE, ResourceType.EDS.typeUrl(), "")));

    // Management server becomes unreachable again.
    call.responseObserver.onError(Status.UNAVAILABLE.asException());
    verify(ldsResourceWatcher, times(4)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    verify(rdsResourceWatcher, times(4)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    verify(cdsResourceWatcher, times(4)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    verify(edsResourceWatcher, times(4)).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);

    // Retry after backoff.
    inOrder.verify(backoffPolicy2).nextBackoffNanos();
    retryTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER));
    assertThat(retryTask.getDelay(TimeUnit.NANOSECONDS)).isEqualTo(20L);
    fakeClock.forwardNanos(20L);
    call = resourceDiscoveryCalls.poll();
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "63", LDS_RESOURCE, ResourceType.LDS.typeUrl(), "")));
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "5", RDS_RESOURCE, ResourceType.RDS.typeUrl(), "")));
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "", CDS_RESOURCE, ResourceType.CDS.typeUrl(), "")));
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "", EDS_RESOURCE, ResourceType.EDS.typeUrl(), "")));

    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void streamClosedAndRetryRaceWithAddRemoveWatchers() {
    xdsClient.watchLdsResource(LDS_RESOURCE, ldsResourceWatcher);
    xdsClient.watchRdsResource(RDS_RESOURCE, rdsResourceWatcher);
    RpcCall<DiscoveryRequest, DiscoveryResponse> call = resourceDiscoveryCalls.poll();
    call.responseObserver.onError(Status.UNAVAILABLE.asException());
    verify(ldsResourceWatcher).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    verify(rdsResourceWatcher).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
    ScheduledTask retryTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(RPC_RETRY_TASK_FILTER));
    assertThat(retryTask.getDelay(TimeUnit.NANOSECONDS)).isEqualTo(10L);

    xdsClient.cancelLdsResourceWatch(LDS_RESOURCE, ldsResourceWatcher);
    xdsClient.cancelRdsResourceWatch(RDS_RESOURCE, rdsResourceWatcher);
    xdsClient.watchCdsResource(CDS_RESOURCE, cdsResourceWatcher);
    xdsClient.watchEdsResource(EDS_RESOURCE, edsResourceWatcher);
    fakeClock.forwardNanos(10L);
    call = resourceDiscoveryCalls.poll();
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "", CDS_RESOURCE, ResourceType.CDS.typeUrl(), "")));
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "", EDS_RESOURCE, ResourceType.EDS.typeUrl(), "")));
    verifyNoMoreInteractions(call.requestObserver);

    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener(LDS_RESOURCE,
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRouteConfig(buildRouteConfiguration("do not care", buildVirtualHosts(2)))
                    .build()))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, ResourceType.LDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);
    List<Any> routeConfigs =
        ImmutableList.of(Any.pack(buildRouteConfiguration(RDS_RESOURCE, buildVirtualHosts(2))));
    response =
        buildDiscoveryResponse("0", routeConfigs, ResourceType.RDS.typeUrl(), "0000");
    call.responseObserver.onNext(response);

    verifyNoMoreInteractions(ldsResourceWatcher, rdsResourceWatcher);
  }

  @Test
  public void streamClosedAndRetryRestartResourceInitialFetchTimers() {
    xdsClient.watchLdsResource(LDS_RESOURCE, ldsResourceWatcher);
    xdsClient.watchRdsResource(RDS_RESOURCE, rdsResourceWatcher);
    xdsClient.watchCdsResource(CDS_RESOURCE, cdsResourceWatcher);
    xdsClient.watchEdsResource(EDS_RESOURCE, edsResourceWatcher);
    RpcCall<DiscoveryRequest, DiscoveryResponse> call = resourceDiscoveryCalls.poll();
    ScheduledTask ldsResourceTimeout =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    ScheduledTask rdsResourceTimeout =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    ScheduledTask cdsResourceTimeout =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    ScheduledTask edsResourceTimeout =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER));
    call.responseObserver.onError(Status.UNAVAILABLE.asException());
    assertThat(ldsResourceTimeout.isCancelled()).isTrue();
    assertThat(rdsResourceTimeout.isCancelled()).isTrue();
    assertThat(cdsResourceTimeout.isCancelled()).isTrue();
    assertThat(edsResourceTimeout.isCancelled()).isTrue();

    fakeClock.forwardNanos(10L);
    assertThat(fakeClock.getPendingTasks(LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);
    assertThat(fakeClock.getPendingTasks(RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);
    assertThat(fakeClock.getPendingTasks(CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);
    assertThat(fakeClock.getPendingTasks(EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER)).hasSize(1);
  }

  /**
   * Tests sending a streaming LRS RPC for each cluster to report loads for.
   */
  @Test
  public void reportLoadStatsToServer() {
    String clusterName = "cluster-foo.googleapis.com";
    xdsClient.addClientStats(clusterName, null);
    ArgumentCaptor<LoadStatsRequest> requestCaptor = ArgumentCaptor.forClass(null);
    xdsClient.reportClientStats();
    RpcCall<LoadStatsRequest, LoadStatsResponse> lrsCall = loadReportCalls.poll();
    verify(lrsCall.requestObserver).onNext(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getClusterStatsCount())
        .isEqualTo(0);  // initial request

    lrsCall.responseObserver.onNext(
        LoadStatsResponse.newBuilder()
            .addClusters(clusterName)
            .setLoadReportingInterval(Durations.fromNanos(1000L))
            .build());
    fakeClock.forwardNanos(1000L);
    verify(lrsCall.requestObserver, times(2)).onNext(requestCaptor.capture());
    ClusterStats report = Iterables.getOnlyElement(requestCaptor.getValue().getClusterStatsList());
    assertThat(report.getClusterName()).isEqualTo(clusterName);

    xdsClient.removeClientStats(clusterName, null);
    fakeClock.forwardNanos(1000L);
    verify(lrsCall.requestObserver, times(3)).onNext(requestCaptor.capture());
    assertThat(requestCaptor.getValue().getClusterStatsCount())
        .isEqualTo(0);  // no more stats reported

    xdsClient.cancelClientStatsReport();
    assertThat(lrsEnded.get()).isTrue();
    // See more test on LoadReportClientTest.java
  }

  @Test
  public void messagePrinter_printLdsResponse() {
    MessagePrinter printer = new MessagePrinter();
    List<Any> listeners = ImmutableList.of(
        Any.pack(buildListener("foo.googleapis.com:8080",
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRouteConfig(
                        buildRouteConfiguration("route-foo.googleapis.com",
                            ImmutableList.of(
                                buildVirtualHost(
                                    ImmutableList.of("foo.googleapis.com", "bar.googleapis.com"),
                                    "cluster.googleapis.com"))))
                    .build()))));
    DiscoveryResponse response =
        buildDiscoveryResponse("0", listeners, ResourceType.LDS.typeUrl(), "0000");

    String expectedString = "{\n"
        + "  \"versionInfo\": \"0\",\n"
        + "  \"resources\": [{\n"
        + "    \"@type\": \"type.googleapis.com/envoy.config.listener.v3.Listener\",\n"
        + "    \"name\": \"foo.googleapis.com:8080\",\n"
        + "    \"address\": {\n"
        + "    },\n"
        + "    \"filterChains\": [{\n"
        + "    }],\n"
        + "    \"apiListener\": {\n"
        + "      \"apiListener\": {\n"
        + "        \"@type\": \"type.googleapis.com/envoy.extensions.filters.network"
        + ".http_connection_manager.v3.HttpConnectionManager\",\n"
        + "        \"routeConfig\": {\n"
        + "          \"name\": \"route-foo.googleapis.com\",\n"
        + "          \"virtualHosts\": [{\n"
        + "            \"name\": \"virtualhost00.googleapis.com\",\n"
        + "            \"domains\": [\"foo.googleapis.com\", \"bar.googleapis.com\"],\n"
        + "            \"routes\": [{\n"
        + "              \"match\": {\n"
        + "                \"prefix\": \"\"\n"
        + "              },\n"
        + "              \"route\": {\n"
        + "                \"cluster\": \"cluster.googleapis.com\"\n"
        + "              }\n"
        + "            }]\n"
        + "          }]\n"
        + "        }\n"
        + "      }\n"
        + "    }\n"
        + "  }],\n"
        + "  \"typeUrl\": \"type.googleapis.com/envoy.config.listener.v3.Listener\",\n"
        + "  \"nonce\": \"0000\"\n"
        + "}";
    String res = printer.print(response);
    assertThat(res).isEqualTo(expectedString);
  }

  @Test
  public void messagePrinter_printRdsResponse() {
    MessagePrinter printer = new MessagePrinter();
    List<Any> routeConfigs =
        ImmutableList.of(
            Any.pack(
                buildRouteConfiguration(
                    "route-foo.googleapis.com",
                    ImmutableList.of(
                        buildVirtualHost(
                            ImmutableList.of("foo.googleapis.com", "bar.googleapis.com"),
                            "cluster.googleapis.com")))));
    DiscoveryResponse response =
        buildDiscoveryResponse("213", routeConfigs, ResourceType.RDS.typeUrl(), "0052");

    String expectedString = "{\n"
        + "  \"versionInfo\": \"213\",\n"
        + "  \"resources\": [{\n"
        + "    \"@type\": \"type.googleapis.com/envoy.config.route.v3.RouteConfiguration\",\n"
        + "    \"name\": \"route-foo.googleapis.com\",\n"
        + "    \"virtualHosts\": [{\n"
        + "      \"name\": \"virtualhost00.googleapis.com\",\n"
        + "      \"domains\": [\"foo.googleapis.com\", \"bar.googleapis.com\"],\n"
        + "      \"routes\": [{\n"
        + "        \"match\": {\n"
        + "          \"prefix\": \"\"\n"
        + "        },\n"
        + "        \"route\": {\n"
        + "          \"cluster\": \"cluster.googleapis.com\"\n"
        + "        }\n"
        + "      }]\n"
        + "    }]\n"
        + "  }],\n"
        + "  \"typeUrl\": \"type.googleapis.com/envoy.config.route.v3.RouteConfiguration\",\n"
        + "  \"nonce\": \"0052\"\n"
        + "}";
    String res = printer.print(response);
    assertThat(res).isEqualTo(expectedString);
  }

  @Test
  public void messagePrinter_printCdsResponse() {
    MessagePrinter printer = new MessagePrinter();
    List<Any> clusters = ImmutableList.of(
        Any.pack(buildCluster("cluster-bar.googleapis.com", "service-blaze:cluster-bar", true)),
        Any.pack(buildCluster("cluster-foo.googleapis.com", null, false)));
    DiscoveryResponse response =
        buildDiscoveryResponse("14", clusters, ResourceType.CDS.typeUrl(), "8");

    String expectedString = "{\n"
        + "  \"versionInfo\": \"14\",\n"
        + "  \"resources\": [{\n"
        + "    \"@type\": \"type.googleapis.com/envoy.config.cluster.v3.Cluster\",\n"
        + "    \"name\": \"cluster-bar.googleapis.com\",\n"
        + "    \"type\": \"EDS\",\n"
        + "    \"edsClusterConfig\": {\n"
        + "      \"edsConfig\": {\n"
        + "        \"ads\": {\n"
        + "        }\n"
        + "      },\n"
        + "      \"serviceName\": \"service-blaze:cluster-bar\"\n"
        + "    },\n"
        + "    \"lrsServer\": {\n"
        + "      \"self\": {\n"
        + "      }\n"
        + "    }\n"
        + "  }, {\n"
        + "    \"@type\": \"type.googleapis.com/envoy.config.cluster.v3.Cluster\",\n"
        + "    \"name\": \"cluster-foo.googleapis.com\",\n"
        + "    \"type\": \"EDS\",\n"
        + "    \"edsClusterConfig\": {\n"
        + "      \"edsConfig\": {\n"
        + "        \"ads\": {\n"
        + "        }\n"
        + "      }\n"
        + "    }\n"
        + "  }],\n"
        + "  \"typeUrl\": \"type.googleapis.com/envoy.config.cluster.v3.Cluster\",\n"
        + "  \"nonce\": \"8\"\n"
        + "}";
    String res = printer.print(response);
    assertThat(res).isEqualTo(expectedString);
  }

  @Test
  public void messagePrinter_printEdsResponse() {
    MessagePrinter printer = new MessagePrinter();
    List<Any> clusterLoadAssignments = ImmutableList.of(
        Any.pack(buildClusterLoadAssignment("cluster-foo.googleapis.com",
            ImmutableList.of(
                buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2)),
                    1, 0),
                buildLocalityLbEndpoints("region3", "zone3", "subzone3",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.142.5", 80, HealthStatus.UNHEALTHY, 5)),
                    2, 1)),
            ImmutableList.of(
                buildDropOverload("lb", 200),
                buildDropOverload("throttle", 1000)))));

    DiscoveryResponse response =
        buildDiscoveryResponse("5", clusterLoadAssignments,
            ResourceType.EDS.typeUrl(), "004");

    String expectedString = "{\n"
        + "  \"versionInfo\": \"5\",\n"
        + "  \"resources\": [{\n"
        + "    \"@type\": \"type.googleapis.com/envoy.config.endpoint.v3.ClusterLoadAssignment\",\n"
        + "    \"clusterName\": \"cluster-foo.googleapis.com\",\n"
        + "    \"endpoints\": [{\n"
        + "      \"locality\": {\n"
        + "        \"region\": \"region1\",\n"
        + "        \"zone\": \"zone1\",\n"
        + "        \"subZone\": \"subzone1\"\n"
        + "      },\n"
        + "      \"lbEndpoints\": [{\n"
        + "        \"endpoint\": {\n"
        + "          \"address\": {\n"
        + "            \"socketAddress\": {\n"
        + "              \"address\": \"192.168.0.1\",\n"
        + "              \"portValue\": 8080\n"
        + "            }\n"
        + "          }\n"
        + "        },\n"
        + "        \"healthStatus\": \"HEALTHY\",\n"
        + "        \"loadBalancingWeight\": 2\n"
        + "      }],\n"
        + "      \"loadBalancingWeight\": 1\n"
        + "    }, {\n"
        + "      \"locality\": {\n"
        + "        \"region\": \"region3\",\n"
        + "        \"zone\": \"zone3\",\n"
        + "        \"subZone\": \"subzone3\"\n"
        + "      },\n"
        + "      \"lbEndpoints\": [{\n"
        + "        \"endpoint\": {\n"
        + "          \"address\": {\n"
        + "            \"socketAddress\": {\n"
        + "              \"address\": \"192.168.142.5\",\n"
        + "              \"portValue\": 80\n"
        + "            }\n"
        + "          }\n"
        + "        },\n"
        + "        \"healthStatus\": \"UNHEALTHY\",\n"
        + "        \"loadBalancingWeight\": 5\n"
        + "      }],\n"
        + "      \"loadBalancingWeight\": 2,\n"
        + "      \"priority\": 1\n"
        + "    }],\n"
        + "    \"policy\": {\n"
        + "      \"dropOverloads\": [{\n"
        + "        \"category\": \"lb\",\n"
        + "        \"dropPercentage\": {\n"
        + "          \"numerator\": 200,\n"
        + "          \"denominator\": \"MILLION\"\n"
        + "        }\n"
        + "      }, {\n"
        + "        \"category\": \"throttle\",\n"
        + "        \"dropPercentage\": {\n"
        + "          \"numerator\": 1000,\n"
        + "          \"denominator\": \"MILLION\"\n"
        + "        }\n"
        + "      }]\n"
        + "    }\n"
        + "  }],\n"
        + "  \"typeUrl\": \"type.googleapis.com/envoy.config.endpoint.v3.ClusterLoadAssignment\",\n"
        + "  \"nonce\": \"004\"\n"
        + "}";
    String res = printer.print(response);
    assertThat(res).isEqualTo(expectedString);
  }

  private RpcCall<DiscoveryRequest, DiscoveryResponse> startResourceWatcher(
      ResourceType type, String name, ResourceWatcher watcher) {
    FakeClock.TaskFilter timeoutTaskFilter;
    switch (type) {
      case LDS:
        timeoutTaskFilter = LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER;
        xdsClient.watchLdsResource(name, (LdsResourceWatcher) watcher);
        break;
      case RDS:
        timeoutTaskFilter = RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER;
        xdsClient.watchRdsResource(name, (RdsResourceWatcher) watcher);
        break;
      case CDS:
        timeoutTaskFilter = CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER;
        xdsClient.watchCdsResource(name, (CdsResourceWatcher) watcher);
        break;
      case EDS:
        timeoutTaskFilter = EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER;
        xdsClient.watchEdsResource(name, (EdsResourceWatcher) watcher);
        break;
      case UNKNOWN:
      default:
        throw new AssertionError("should never be here");
    }
    RpcCall<DiscoveryRequest, DiscoveryResponse> call = resourceDiscoveryCalls.poll();
    verify(call.requestObserver).onNext(
        eq(buildDiscoveryRequest(NODE, "", name, type.typeUrl(), "")));
    ScheduledTask timeoutTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(timeoutTaskFilter));
    assertThat(timeoutTask.getDelay(TimeUnit.SECONDS))
        .isEqualTo(XdsClientImpl2.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC);
    return call;
  }

  /**
   * Matcher for DiscoveryRequest without the comparison of error_details field, which is used for
   * management server debugging purposes.
   *
   * <p>In general, if you are sure error_details field should not be set in a DiscoveryRequest,
   * compare with message equality. Otherwise, this matcher is handy for comparing other fields
   * only.
   */
  private static class DiscoveryRequestMatcher implements ArgumentMatcher<DiscoveryRequest> {
    private final Node node;
    private final String versionInfo;
    private final String typeUrl;
    private final Set<String> resourceNames;
    private final String responseNonce;

    private DiscoveryRequestMatcher(
        Node node, String versionInfo, List<String> resourceNames, String typeUrl,
        String responseNonce) {
      this.node = node;
      this.versionInfo = versionInfo;
      this.resourceNames = new HashSet<>(resourceNames);
      this.typeUrl = typeUrl;
      this.responseNonce = responseNonce;
    }

    @Override
    public boolean matches(DiscoveryRequest argument) {
      if (!typeUrl.equals(argument.getTypeUrl())) {
        return false;
      }
      if (!versionInfo.equals(argument.getVersionInfo())) {
        return false;
      }
      if (!responseNonce.equals(argument.getResponseNonce())) {
        return false;
      }
      if (!node.toEnvoyProtoNode().equals(argument.getNode())) {
        return false;
      }
      if (!resourceNames.equals(new HashSet<>(argument.getResourceNamesList()))) {
        return false;
      }
      return argument.getNode().equals(NODE.toEnvoyProtoNode());
    }
  }

  private static class RpcCall<ReqT, RespT> {
    private final StreamObserver<ReqT> requestObserver;
    private final StreamObserver<RespT> responseObserver;

    RpcCall(StreamObserver<ReqT> requestObserver, StreamObserver<RespT> responseObserver) {
      this.requestObserver = requestObserver;
      this.responseObserver = responseObserver;
    }
  }
}
