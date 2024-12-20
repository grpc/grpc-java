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
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Any;
import com.google.protobuf.util.Durations;
import com.google.rpc.Code;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterStats;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.envoyproxy.envoy.service.load_stats.v3.LoadReportingServiceGrpc;
import io.envoyproxy.envoy.service.load_stats.v3.LoadStatsRequest;
import io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse;
import io.grpc.BindableService;
import io.grpc.Context;
import io.grpc.Context.CancellationListener;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.client.EnvoyProtoData;
import io.grpc.xds.client.XdsResourceType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.verification.VerificationMode;

public class XdsTestUtils {
  static BindableService createLrsService(AtomicBoolean lrsEnded,
                                          Queue<LrsRpcCall> loadReportCalls) {
    return new LoadReportingServiceGrpc.LoadReportingServiceImplBase() {
      @Override
      public StreamObserver<LoadStatsRequest> streamLoadStats(
          StreamObserver<LoadStatsResponse> responseObserver) {
        assertThat(lrsEnded.get()).isTrue();
        lrsEnded.set(false);
        @SuppressWarnings("unchecked")
        StreamObserver<LoadStatsRequest> requestObserver = mock(StreamObserver.class);
        LrsRpcCall call = new LrsRpcCall(requestObserver, responseObserver);
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
  }

  static boolean matchErrorDetail(
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

  static BindableService createAdsService(
      AtomicBoolean adsEnded, Queue<DiscoveryRpcCall> resourceDiscoveryCalls) {
    return new AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamAggregatedResources(
          final StreamObserver<DiscoveryResponse> responseObserver) {
        assertThat(adsEnded.get()).isTrue();  // ensure previous call was ended
        adsEnded.set(false);
        @SuppressWarnings("unchecked")
        StreamObserver<DiscoveryRequest> requestObserver =
            mock(StreamObserver.class, delegatesTo(new MockStreamObserver()));
        DiscoveryRpcCall call = new DiscoveryRpcCall(requestObserver, responseObserver);
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
  }

  static class MockStreamObserver implements StreamObserver<DiscoveryRequest> {
    private final List<DiscoveryRequest> requests = new ArrayList<>();

    @Override
    public void onNext(DiscoveryRequest value) {
      requests.add(value);
    }

    @Override
    public void onError(Throwable t) {
      // Ignore
    }

    @Override
    public void onCompleted() {
      // Ignore
    }
  }

  /**
   * Matches a {@link DiscoveryRequest} with the same node metadata, versionInfo, typeUrl,
   * response nonce and collection of resource names regardless of order.
   */
  static class DiscoveryRequestMatcher implements ArgumentMatcher<DiscoveryRequest> {
    private final Node node;
    private final String versionInfo;
    private final String typeUrl;
    private final Set<String> resources;
    private final String responseNonce;
    @Nullable
    private final Integer errorCode;
    private final List<String> errorMessages;

    private DiscoveryRequestMatcher(
        Node node, String versionInfo, List<String> resources,
        String typeUrl, String responseNonce, @Nullable Integer errorCode,
        @Nullable List<String> errorMessages) {
      this.node = node;
      this.versionInfo = versionInfo;
      this.resources = new HashSet<>(resources);
      this.typeUrl = typeUrl;
      this.responseNonce = responseNonce;
      this.errorCode = errorCode;
      this.errorMessages = errorMessages != null ? errorMessages : ImmutableList.<String>of();
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
      if (!resources.equals(new HashSet<>(argument.getResourceNamesList()))) {
        return false;
      }
      if (errorCode == null && argument.hasErrorDetail()) {
        return false;
      }
      if (errorCode != null
          && !matchErrorDetail(argument.getErrorDetail(), errorCode, errorMessages)) {
        return false;
      }
      return node.equals(argument.getNode());
    }

    @Override
    public String toString() {
      return "DiscoveryRequestMatcher{"
          + "node=" + node
          + ", versionInfo='" + versionInfo + '\''
          + ", typeUrl='" + typeUrl + '\''
          + ", resources=" + resources
          + ", responseNonce='" + responseNonce + '\''
          + ", errorCode=" + errorCode
          + ", errorMessages=" + errorMessages
          + '}';
    }
  }

  /**
   * Matches a {@link LoadStatsRequest} containing a collection of {@link ClusterStats} with
   * the same list of clusterName:clusterServiceName pair.
   */
  static class LrsRequestMatcher implements ArgumentMatcher<LoadStatsRequest> {
    private final List<String> expected;

    private LrsRequestMatcher(List<String[]> clusterNames) {
      expected = new ArrayList<>();
      for (String[] pair : clusterNames) {
        expected.add(pair[0] + ":" + (pair[1] == null ? "" : pair[1]));
      }
      Collections.sort(expected);
    }

    @Override
    public boolean matches(LoadStatsRequest argument) {
      List<String> actual = new ArrayList<>();
      for (ClusterStats clusterStats : argument.getClusterStatsList()) {
        actual.add(clusterStats.getClusterName() + ":" + clusterStats.getClusterServiceName());
      }
      Collections.sort(actual);
      return actual.equals(expected);
    }
  }

  static class DiscoveryRpcCall  {
    StreamObserver<DiscoveryRequest> requestObserver;
    StreamObserver<DiscoveryResponse> responseObserver;

    private DiscoveryRpcCall(StreamObserver<DiscoveryRequest> requestObserver,
                               StreamObserver<DiscoveryResponse> responseObserver) {
      this.requestObserver = requestObserver;
      this.responseObserver = responseObserver;
    }

    protected void verifyRequest(
        XdsResourceType<?> type, List<String> resources, String versionInfo, String nonce,
        EnvoyProtoData.Node node, VerificationMode verificationMode) {
      verify(requestObserver, verificationMode).onNext(argThat(new DiscoveryRequestMatcher(
          node.toEnvoyProtoNode(), versionInfo, resources, type.typeUrl(), nonce, null, null)));
    }

    protected void verifyRequestNack(
        XdsResourceType<?> type, List<String> resources, String versionInfo, String nonce,
        EnvoyProtoData.Node node, List<String> errorMessages) {
      verify(requestObserver, Mockito.timeout(2000)).onNext(argThat(new DiscoveryRequestMatcher(
          node.toEnvoyProtoNode(), versionInfo, resources, type.typeUrl(), nonce,
          Code.INVALID_ARGUMENT_VALUE, errorMessages)));
    }

    protected void verifyNoMoreRequest() {
      verifyNoMoreInteractions(requestObserver);
    }

    protected void sendResponse(
        XdsResourceType<?> type, List<Any> resources, String versionInfo, String nonce) {
      DiscoveryResponse response =
          DiscoveryResponse.newBuilder()
              .setVersionInfo(versionInfo)
              .addAllResources(resources)
              .setTypeUrl(type.typeUrl())
              .setNonce(nonce)
              .build();
      responseObserver.onNext(response);
    }

    protected void sendError(Throwable t) {
      responseObserver.onError(t);
    }

    protected void sendCompleted() {
      responseObserver.onCompleted();
    }

    protected boolean isReady() {
      return ((ServerCallStreamObserver)responseObserver).isReady();
    }
  }

  static class LrsRpcCall  {
    private final StreamObserver<LoadStatsRequest> requestObserver;
    private final StreamObserver<LoadStatsResponse> responseObserver;
    private final InOrder inOrder;

    private LrsRpcCall(StreamObserver<LoadStatsRequest> requestObserver,
                         StreamObserver<LoadStatsResponse> responseObserver) {
      this.requestObserver = requestObserver;
      this.responseObserver = responseObserver;
      inOrder = inOrder(requestObserver);
    }

    protected void verifyNextReportClusters(List<String[]> clusters) {
      inOrder.verify(requestObserver).onNext(argThat(new LrsRequestMatcher(clusters)));
    }

    protected void sendResponse(List<String> clusters, long loadReportIntervalNano) {
      LoadStatsResponse response =
          LoadStatsResponse.newBuilder()
              .addAllClusters(clusters)
              .setLoadReportingInterval(Durations.fromNanos(loadReportIntervalNano))
              .build();
      responseObserver.onNext(response);
    }
  }
}
