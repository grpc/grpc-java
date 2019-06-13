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
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.ManagedChannel;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.FakeClock;
import io.grpc.internal.testing.StreamRecorder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.XdsComms.AdsStreamCallback;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link XdsLbState}.
 */
@RunWith(JUnit4.class)
public class XdsLbStateTest {
  private static final String BALANCER_NAME = "balancerName";
  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();
  @Mock
  private Helper helper;
  @Mock
  private AdsStreamCallback adsStreamCallback;
  @Mock
  private StatsStore statsStore;
  @Mock
  private LocalityStore localityStore;

  private final FakeClock fakeClock = new FakeClock();

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });

  private final StreamRecorder<DiscoveryRequest> streamRecorder = StreamRecorder.create();
  private ManagedChannel channel;
  private XdsLbState xdsLbState;


  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    doReturn(syncContext).when(helper).getSynchronizationContext();
    doReturn(fakeClock.getScheduledExecutorService()).when(helper).getScheduledExecutorService();
    doReturn("fake_authority").when(helper).getAuthority();
    doReturn(mock(ChannelLogger.class)).when(helper).getChannelLogger();
    doAnswer(returnsFirstArg())
        .when(statsStore).interceptPickResult(any(PickResult.class), any(XdsLocality.class));

    String serverName = InProcessServerBuilder.generateName();

    AggregatedDiscoveryServiceImplBase serviceImpl = new AggregatedDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamAggregatedResources(
          final StreamObserver<DiscoveryResponse> responseObserver) {
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
            .addService(serviceImpl)
            .directExecutor()
            .build()
            .start());
    channel =
        cleanupRule.register(InProcessChannelBuilder.forName(serverName).directExecutor().build());
    doReturn(channel).when(helper).createResolvingOobChannel(BALANCER_NAME);

    xdsLbState =
        new XdsLbState(BALANCER_NAME, null, helper, localityStore, channel, adsStreamCallback);
  }

  @Test
  public void shutdownResetsLocalityStore() {
    xdsLbState.shutdownAndReleaseChannel("Client shutdown");
    verify(localityStore).reset();
  }

  @Test
  public void shutdownDoesNotTearDownChannel() {
    ManagedChannel lbChannel = xdsLbState.shutdownAndReleaseChannel("Client shutdown");
    assertThat(lbChannel).isSameInstanceAs(channel);
    assertThat(channel.isShutdown()).isFalse();
  }

  @Test
  public void handleResolvedAddressGroupsTriggerEds() throws Exception {
    xdsLbState.handleResolvedAddressGroups(
        ImmutableList.<EquivalentAddressGroup>of(), Attributes.EMPTY);

    assertThat(streamRecorder.firstValue().get().getTypeUrl())
        .isEqualTo("type.googleapis.com/envoy.api.v2.ClusterLoadAssignment");

    xdsLbState.shutdownAndReleaseChannel("End test");
  }
}
