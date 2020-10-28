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
import static io.grpc.xds.XdsClientTestHelper.buildClusterV2;
import static io.grpc.xds.XdsClientTestHelper.buildDiscoveryRequestV2;
import static io.grpc.xds.XdsClientTestHelper.buildDiscoveryResponseV2;
import static io.grpc.xds.XdsClientTestHelper.buildSecureClusterV2;
import static io.grpc.xds.XdsClientTestHelper.buildUpstreamTlsContextV2;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Any;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.envoyproxy.envoy.service.load_stats.v3.LoadReportingServiceGrpc.LoadReportingServiceImplBase;
import io.envoyproxy.envoy.service.load_stats.v3.LoadStatsRequest;
import io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse;
import io.grpc.Context;
import io.grpc.Context.CancellationListener;
import io.grpc.ManagedChannel;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.internal.FakeClock.ScheduledTask;
import io.grpc.internal.FakeClock.TaskFilter;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.XdsClient.CdsResourceWatcher;
import io.grpc.xds.XdsClient.CdsUpdate;
import io.grpc.xds.XdsClient.EdsResourceWatcher;
import io.grpc.xds.XdsClient.LdsResourceWatcher;
import io.grpc.xds.XdsClient.RdsResourceWatcher;
import io.grpc.xds.XdsClient.ResourceWatcher;
import io.grpc.xds.XdsClient.XdsChannel;
import io.grpc.xds.XdsClientImpl2.ResourceType;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link XdsClientImpl2} for xDS v2.
 */
@RunWith(JUnit4.class)
public class XdsClientImplTest2V2 {
  private static final String CDS_RESOURCE = "cluster.googleapis.com";
  private static final Node NODE = Node.newBuilder().build();

  private static final TaskFilter LDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(ResourceType.LDS.toString());
        }
      };

  private static final TaskFilter RDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(ResourceType.RDS.toString());
        }
      };

  private static final TaskFilter CDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(ResourceType.CDS.toString());
        }
      };

  private static final TaskFilter EDS_RESOURCE_FETCH_TIMEOUT_TASK_FILTER =
      new TaskFilter() {
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
  private ArgumentCaptor<CdsUpdate> cdsUpdateCaptor;
  @Mock
  private BackoffPolicy.Provider backoffPolicyProvider;
  @Mock
  private BackoffPolicy backoffPolicy1;
  @Mock
  private BackoffPolicy backoffPolicy2;
  @Mock
  private CdsResourceWatcher cdsResourceWatcher;

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
            new XdsChannel(channel, /* useProtocolV3= */ false),
            Node.newBuilder().build(),
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

  /**
   * CDS response containing UpstreamTlsContext for a cluster.
   */
  @Test
  public void cdsResponseV2WithUpstreamTlsContext() {
    RpcCall<DiscoveryRequest, DiscoveryResponse> call =
        startResourceWatcher(ResourceType.CDS, CDS_RESOURCE, cdsResourceWatcher);

    // Management server sends back CDS response with UpstreamTlsContext.
    io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext testUpstreamTlsContext =
        buildUpstreamTlsContextV2("secret1", "unix:/var/uds2");
    List<Any> clusters = ImmutableList.of(
        Any.pack(buildClusterV2("cluster-bar.googleapis.com", null, false)),
        Any.pack(buildSecureClusterV2(CDS_RESOURCE,
            "eds-cluster-foo.googleapis.com", true, testUpstreamTlsContext)),
        Any.pack(buildClusterV2("cluster-baz.googleapis.com", null, false)));
    io.envoyproxy.envoy.api.v2.DiscoveryResponse response =
        buildDiscoveryResponseV2("0", clusters, XdsClientImpl2.ADS_TYPE_URL_CDS_V2, "0000");
    call.responseObserver.onNext(response);

    // Client sent an ACK CDS request.
    verify(call.requestObserver)
        .onNext(eq(buildDiscoveryRequestV2(NODE, "0", CDS_RESOURCE,
            XdsClientImpl2.ADS_TYPE_URL_CDS_V2, "0000")));
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

  private RpcCall<DiscoveryRequest, DiscoveryResponse> startResourceWatcher(
      ResourceType type, String name, ResourceWatcher watcher) {
    TaskFilter timeoutTaskFilter;
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
        eq(buildDiscoveryRequestV2(NODE, "", name, type.typeUrlV2(), "")));
    ScheduledTask timeoutTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(timeoutTaskFilter));
    assertThat(timeoutTask.getDelay(TimeUnit.SECONDS))
        .isEqualTo(XdsClientImpl2.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC);
    return call;
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
