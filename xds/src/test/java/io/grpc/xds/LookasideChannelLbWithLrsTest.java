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
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.protobuf.Any;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.grpc.ChannelLogger;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy.Provider;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.testing.StreamRecorder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.LoadReportClient.LoadReportCallback;
import io.grpc.xds.LoadReportClientImpl.LoadReportClientFactory;
import io.grpc.xds.LookasideChannelLb.LocalityStoreFactory;
import io.grpc.xds.XdsComms.AdsStreamCallback;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for basic interactions between {@link LookasideChannelLb} and a real {@link
 * LoadReportClient} on a real channel, with no intention to cover all branches.
 */
public class LookasideChannelLbWithLrsTest {
  private static final String BALANCER_NAME = "fakeBalancerName";
  private static final String SERVICE_AUTHORITY = "test authority";

  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();
  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });

  private final StreamRecorder<DiscoveryRequest> streamRecorder = StreamRecorder.create();

  private final LoadReportClientFactory lrsClientFactory = new LoadReportClientFactory() {
    @Override
    LoadReportClient createLoadReportClient(ManagedChannel lrsChannel, Helper lrsHelper,
        Provider backoffPolicyProvider, LoadStatsStore loadStatsStore) {
      assertThat(lrsChannel).isSameInstanceAs(channel);
      assertThat(lrsHelper).isSameInstanceAs(helper);
      assertThat(loadStatsStore).isSameInstanceAs(loadStatsStore);

      lrsClient = mock(
          LoadReportClient.class,
          delegatesTo(new LoadReportClientImpl(
              channel, helper, GrpcUtil.STOPWATCH_SUPPLIER, new ExponentialBackoffPolicy.Provider(),
              loadStatsStore)));
      return lrsClient;
    }
  };

  @Mock
  private Helper helper;
  @Mock
  private AdsStreamCallback adsStreamCallback;

  @Mock
  private LocalityStoreFactory localityStoreFactory;
  @Mock
  private LocalityStore localityStore;
  @Mock
  private LoadStatsStore loadStatsStore;

  private ManagedChannel channel;
  private StreamObserver<DiscoveryResponse> serverResponseWriter;
  private LookasideChannelLb lookasideChannelLb;
  private LoadReportClient lrsClient;

  @Before
  public void setUp() throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    AggregatedDiscoveryServiceImplBase serviceImpl = new AggregatedDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamAggregatedResources(
          final StreamObserver<DiscoveryResponse> responseObserver) {
        serverResponseWriter = responseObserver;

        return new StreamObserver<DiscoveryRequest>() {

          @Override
          public void onNext(DiscoveryRequest value) {
            streamRecorder.onNext(value);
          }

          @Override
          public void onError(Throwable t) {
            streamRecorder.onError(t);
          }

          @Override
          public void onCompleted() {
            streamRecorder.onCompleted();
            responseObserver.onCompleted();
          }
        };
      }
    };

    cleanupRule.register(
        InProcessServerBuilder
            .forName(serverName)
            .directExecutor()
            .addService(serviceImpl)
            .build()
            .start());
    channel = cleanupRule.register(
        InProcessChannelBuilder
            .forName(serverName)
            .directExecutor()
            .build());

    doReturn(channel).when(helper).createResolvingOobChannel(BALANCER_NAME);
    doReturn(SERVICE_AUTHORITY).when(helper).getAuthority();
    doReturn(syncContext).when(helper).getSynchronizationContext();
    doReturn(mock(ChannelLogger.class)).when(helper).getChannelLogger();
    doReturn(new FakeClock().getScheduledExecutorService()).when(helper)
        .getScheduledExecutorService();

    LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();
    doReturn(localityStore).when(localityStoreFactory).newLocalityStore(helper, lbRegistry);
    doReturn(loadStatsStore).when(localityStore).getLoadStatsStore();
    assertThat(streamRecorder.getValues()).isEmpty();


    lookasideChannelLb = new LookasideChannelLb(
        helper, adsStreamCallback, BALANCER_NAME, lrsClientFactory, lbRegistry,
        localityStoreFactory);

    verify(helper).createResolvingOobChannel(BALANCER_NAME);
    verify(localityStoreFactory).newLocalityStore(helper, lbRegistry);
    assertThat(streamRecorder.getValues()).hasSize(1);
  }

  @After
  public void tearDown() {
    lookasideChannelLb.shutdown();
    verify(lrsClient).stopLoadReporting();
  }

  /**
   * Tests load reporting is initiated after receiving the first valid EDS response from the traffic
   * director, then its operation is independent of load balancing until xDS load balancer is
   * shutdown.
   */
  @Test
  public void reportLoadAfterReceivingFirstEdsResponseUntilShutdown() throws Exception {
    // Simulates a syntactically incorrect EDS response.
    serverResponseWriter.onNext(DiscoveryResponse.getDefaultInstance());
    verify(lrsClient, never()).startLoadReporting(any(LoadReportCallback.class));
    verify(adsStreamCallback, never()).onWorking();
    verify(adsStreamCallback, never()).onError();

    // Simulate a syntactically correct EDS response.
    DiscoveryResponse edsResponse =
        DiscoveryResponse.newBuilder()
            .addResources(Any.pack(ClusterLoadAssignment.getDefaultInstance()))
            .setTypeUrl("type.googleapis.com/envoy.api.v2.ClusterLoadAssignment")
            .build();
    serverResponseWriter.onNext(edsResponse);

    verify(adsStreamCallback).onWorking();

    ArgumentCaptor<LoadReportCallback> lrsCallbackCaptor = ArgumentCaptor.forClass(null);
    verify(lrsClient).startLoadReporting(lrsCallbackCaptor.capture());
    lrsCallbackCaptor.getValue().onReportResponse(19543);
    verify(localityStore).updateOobMetricsReportInterval(19543);

    // Simulate another EDS response from the same remote balancer.
    serverResponseWriter.onNext(edsResponse);
    verifyNoMoreInteractions(localityStoreFactory, adsStreamCallback, lrsClient);

    // Simulate an EDS error response.
    serverResponseWriter.onError(Status.ABORTED.asException());
    verify(adsStreamCallback).onError();

    verifyNoMoreInteractions(localityStoreFactory, adsStreamCallback, lrsClient);
  }
}
