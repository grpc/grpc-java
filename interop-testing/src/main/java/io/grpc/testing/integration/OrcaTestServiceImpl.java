/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.testing.integration;

import com.google.common.collect.ImmutableMap;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.services.CallMetricRecorder;
import io.grpc.xds.orca.OrcaOobService;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;

public class OrcaTestServiceImpl extends TestServiceImpl {
  private final OrcaOobService orcaOobService;

  /**
   * Constructs a controller using the given executor for scheduling response stream chunks.
   */
  public OrcaTestServiceImpl(ScheduledExecutorService executor,
                             @Nullable OrcaOobService orcaOobService) {
    super(executor);
    this.orcaOobService = orcaOobService;
  }

  /**
   * Update hardcoded backend metrics data for the call.
   */
  public ServerInterceptor reportQueryMetricsInterceptor() {
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers,
          ServerCallHandler<ReqT, RespT> next) {
        return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(
            call) {
          @Override
          public void request(int numMessages) {
            super.request(numMessages);
            CallMetricRecorder.getCurrent().recordCallMetric("queue", 2.0);
            if (orcaOobService != null) {
              orcaOobService.setAllUtilizationMetrics(ImmutableMap.of("util", 0.4875));
            }
          }
        }, headers);
      }
    };
  }
}
