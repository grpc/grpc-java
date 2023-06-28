/*
 * Copyright 2023 The gRPC Authors
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

import io.envoyproxy.envoy.service.load_stats.v3.LoadReportingServiceGrpc;
import io.envoyproxy.envoy.service.load_stats.v3.LoadStatsRequest;
import io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse;
import io.grpc.stub.StreamObserver;

/**
 * This dummy implementation is just used to allow tests that utilize load reporting to successfully
 * connect to a test control plane server. It can be later expanded to e.g. store the requests it
 * receives for tests to verify.
 */
final class XdsTestLoadReportingService extends
    LoadReportingServiceGrpc.LoadReportingServiceImplBase {

  @Override
  public StreamObserver<LoadStatsRequest> streamLoadStats(
      StreamObserver<LoadStatsResponse> responseObserver) {
    return new StreamObserver<LoadStatsRequest>() {
      @Override
      public void onNext(LoadStatsRequest request) {
        responseObserver.onNext(LoadStatsResponse.newBuilder().build());
      }

      @Override
      public void onError(Throwable t) {
        responseObserver.onError(t);
      }

      @Override
      public void onCompleted() {
        responseObserver.onCompleted();
      }
    };
  }
}
