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
import static io.grpc.xds.XdsClientImpl.EDS_TYPE_URL;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.common.collect.Iterables;
import com.google.protobuf.Any;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.internal.testing.StreamRecorder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.XdsClient.EndpointUpdate;
import io.grpc.xds.XdsClient.EndpointWatcher;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link XdsClientImpl}.
 */
@RunWith(JUnit4.class)
public class XdsClientImplTest {

  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private final FakeClock fakeClock = new FakeClock();
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final Node xdsNode = Node.newBuilder().setCluster("xdsNode").build();

  private final BackoffPolicy.Provider backoffPolicyProvider = new BackoffPolicy.Provider() {
    final BackoffPolicy backoffPolicy = new BackoffPolicy() {
      @Override
      public long nextBackoffNanos() {
        return retryBackOff;
      }
    };

    @Override
    public BackoffPolicy get() {
      return backoffPolicy;
    }
  };

  @Mock
  private EndpointWatcher endpointWatcher1;
  @Mock
  private EndpointWatcher endpointWatcher2;

  private long retryBackOff = 1234567;
  private StreamRecorder<DiscoveryRequest> streamRecorder;
  private StreamObserver<DiscoveryResponse> responseWriter;
  private XdsClientImpl xdsClient;
  private ManagedChannel channel;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);

    String serverName = InProcessServerBuilder.generateName();

    AggregatedDiscoveryServiceImplBase serviceImpl = new AggregatedDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamAggregatedResources(
          final StreamObserver<DiscoveryResponse> responseObserver) {
        responseWriter = responseObserver;
        streamRecorder = StreamRecorder.create();

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

    xdsClient = new XdsClientImpl(
        channel, syncContext, fakeClock.getScheduledExecutorService(), backoffPolicyProvider,
        fakeClock.getStopwatchSupplier(), xdsNode, "fakeAuthority");

    assertThat(streamRecorder).isNull();
    xdsClient.start();
    assertThat(streamRecorder).isNotNull();
  }

  @After
  public void tearDown() {
    xdsClient.shutdown();
    assertThat(fakeClock.getPendingTasks()).isEmpty();
    assertThat(channel.isShutdown()).isTrue();
  }

  @Test
  public void addEndpointWatcher_edsRequestSent_endpointWatcherUpdateOnEdsResponse() {
    StreamRecorder<DiscoveryRequest> currentStreamRecorder = streamRecorder;
    assertThat(currentStreamRecorder.getValues()).isEmpty();

    xdsClient.watchEndpointData("cluster1", endpointWatcher1);
    assertThat(currentStreamRecorder.getValues()).hasSize(1);
    DiscoveryRequest requestReceived = Iterables.getOnlyElement(currentStreamRecorder.getValues());
    assertThat(requestReceived.getTypeUrl()).isEqualTo(EDS_TYPE_URL);
    assertThat(requestReceived.getResourceNamesList()).containsExactly("cluster1");

    ClusterLoadAssignment clusterLoadAssignment1A =
        ClusterLoadAssignment.newBuilder().setClusterName("cluster1").build();
    ClusterLoadAssignment clusterLoadAssignment2A =
        ClusterLoadAssignment.newBuilder().setClusterName("cluster2").build();
    ClusterLoadAssignment clusterLoadAssignment3 =
        ClusterLoadAssignment.newBuilder().setClusterName("cluster3").build();
    DiscoveryResponse edsResponse = DiscoveryResponse.newBuilder()
        .addAllResources(Arrays.asList(
            Any.pack(clusterLoadAssignment1A),
            Any.pack(clusterLoadAssignment2A),
            Any.pack(clusterLoadAssignment3)))
        .setTypeUrl(EDS_TYPE_URL)
        .setNonce("A")
        .build();
    responseWriter.onNext(edsResponse);
    verify(endpointWatcher1).onEndpointChanged(
        EndpointUpdate.newBuilder().setClusterLoadAssignment(clusterLoadAssignment1A).build());
    verify(endpointWatcher2, never()).onEndpointChanged(any(EndpointUpdate.class));

    xdsClient.watchEndpointData("cluster2", endpointWatcher2);
    assertThat(currentStreamRecorder.getValues()).hasSize(2);
    DiscoveryRequest requestReceived2 = Iterables.getLast(currentStreamRecorder.getValues());
    assertThat(requestReceived2.getTypeUrl()).isEqualTo(EDS_TYPE_URL);
    assertThat(requestReceived2.getResourceNamesList()).containsExactly("cluster1", "cluster2");

    ClusterLoadAssignment clusterLoadAssignment1B = ClusterLoadAssignment
        .newBuilder().setClusterName("cluster1").setPolicy(Policy.getDefaultInstance()).build();
    ClusterLoadAssignment clusterLoadAssignment2B = ClusterLoadAssignment
        .newBuilder().setClusterName("cluster2").setPolicy(Policy.getDefaultInstance()).build();
    edsResponse = DiscoveryResponse.newBuilder()
        .addAllResources(Arrays.asList(
            Any.pack(clusterLoadAssignment1B),
            Any.pack(clusterLoadAssignment2B),
            Any.pack(clusterLoadAssignment3)))
        .setTypeUrl(EDS_TYPE_URL)
        .setNonce("B")
        .build();
    responseWriter.onNext(edsResponse);
    verify(endpointWatcher1).onEndpointChanged(
        eq(EndpointUpdate.newBuilder().setClusterLoadAssignment(clusterLoadAssignment1B).build()));
    verify(endpointWatcher2).onEndpointChanged(
        eq(EndpointUpdate.newBuilder().setClusterLoadAssignment(clusterLoadAssignment2B).build()));
  }

  @Test
  public void addEndpointWatcher_rpcFailure_endpointWatcherOnError_retryAfterBackoff() {
    FakeClock.TaskFilter lbRpcRetryTaskFilter =
        new FakeClock.TaskFilter() {
          @Override
          public boolean shouldAccept(Runnable command) {
            return command.toString().contains("AdsRpcRetryTask");
          }
        };

    xdsClient.watchEndpointData("cluster2", endpointWatcher2);

    assertThat(fakeClock.getPendingTasks(lbRpcRetryTaskFilter)).isEmpty();
    responseWriter.onError(Status.UNAVAILABLE.asException());

    verify(endpointWatcher1, never()).onError(any(Status.class));
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(null);
    verify(endpointWatcher2).onError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.Code.UNAVAILABLE);

    StreamObserver<DiscoveryResponse> oldResponseWriter = responseWriter;
    assertThat(fakeClock.getPendingTasks(lbRpcRetryTaskFilter)).hasSize(1);
    fakeClock.forwardNanos(retryBackOff);
    assertThat(fakeClock.getPendingTasks(lbRpcRetryTaskFilter)).isEmpty();
    assertThat(responseWriter).isNotSameInstanceAs(oldResponseWriter);

    ClusterLoadAssignment clusterLoadAssignment =
        ClusterLoadAssignment.newBuilder().setClusterName("cluster2").build();
    DiscoveryResponse edsResponse = DiscoveryResponse.newBuilder()
        .addAllResources(Arrays.asList(Any.pack(clusterLoadAssignment)))
        .setTypeUrl(EDS_TYPE_URL)
        .setNonce("A")
        .build();
    responseWriter.onNext(edsResponse);
    verify(endpointWatcher2).onEndpointChanged(
        EndpointUpdate.newBuilder().setClusterLoadAssignment(clusterLoadAssignment).build());
  }
}
