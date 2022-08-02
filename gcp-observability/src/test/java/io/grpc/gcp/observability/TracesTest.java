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

package io.grpc.gcp.observability;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.cloud.trace.v1.TraceServiceClient;
import com.google.cloud.trace.v1.TraceServiceClient.ListTracesPagedResponse;
import com.google.devtools.cloudtrace.v1.GetTraceRequest;
import com.google.devtools.cloudtrace.v1.ListTracesRequest;
import com.google.devtools.cloudtrace.v1.Trace;
import com.google.devtools.cloudtrace.v1.TraceSpan;
import com.google.protobuf.util.Timestamps;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.gcp.observability.interceptors.InternalLoggingChannelInterceptor;
import io.grpc.gcp.observability.interceptors.InternalLoggingServerInterceptor;
import io.grpc.gcp.observability.logging.GcpLogSink;
import io.grpc.gcp.observability.logging.Sink;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.opencensus.trace.samplers.Samplers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TracesTest {

  private static final String PROJECT_ID = "PROJECT";
  private String customTagKey = "service";
  private String customTagValue =
      String.format("payment-%s", String.valueOf(System.currentTimeMillis()));
  private Map<String, String> customTags = Collections.singletonMap(customTagKey, customTagValue);

  @Rule public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  /**
   * Cloud Trace test using GlobalInterceptors.
   *
   * <p>Ignoring test, because it calls external Cloud Tracing APIs. To test cloud trace setup
   * locally, 1. Set up Cloud auth credentials 2. Assign permissions to service account to write
   * traces to project specified by variable PROJECT_ID 3. Comment @Ignore annotation
   */
  @Ignore
  @Test
  public void testTracesExporter() throws IOException, InterruptedException {
    Sink mockSink = mock(GcpLogSink.class);
    ObservabilityConfig mockConfig = mock(ObservabilityConfig.class);
    InternalLoggingChannelInterceptor.Factory mockChannelInterceptorFactory =
        mock(InternalLoggingChannelInterceptor.Factory.class);
    InternalLoggingServerInterceptor.Factory mockServerInterceptorFactory =
        mock(InternalLoggingServerInterceptor.Factory.class);

    when(mockConfig.isEnableCloudTracing()).thenReturn(true);
    when(mockConfig.getSampler()).thenReturn(Samplers.alwaysSample());
    when(mockConfig.getDestinationProjectId()).thenReturn(PROJECT_ID);

    GcpObservability observability =
        GcpObservability.grpcInit(
            mockSink, mockConfig, mockChannelInterceptorFactory, mockServerInterceptorFactory);
    observability.registerStackDriverExporter(PROJECT_ID, customTags);

    Server server =
        ServerBuilder.forPort(0)
            .addService(new LoggingTestHelper.SimpleServiceImpl())
            .build()
            .start();
    int port = cleanupRule.register(server).getPort();
    SimpleServiceGrpc.SimpleServiceBlockingStub stub =
        SimpleServiceGrpc.newBlockingStub(
            cleanupRule.register(
                ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build()));
    assertThat(LoggingTestHelper.makeUnaryRpcViaClientStub("buddy", stub)).isEqualTo("Hello buddy");
    // Adding sleep to ensure traces are exported before querying cloud monitoring backend
    TimeUnit.SECONDS.sleep(5);

    TraceServiceClient traceServiceClient = TraceServiceClient.create();
    String traceFilter =
        String.format("span:Sent.grpc.testing.SimpleService +%s:%s", customTagKey, customTagValue);
    String traceOrder = "start";
    // Restrict time to last 1 minute
    long startMillis = System.currentTimeMillis() - ((60 * 1) * 1000);
    ListTracesRequest traceRequest =
        ListTracesRequest.newBuilder()
            .setProjectId(PROJECT_ID)
            .setStartTime(Timestamps.fromMillis(startMillis))
            .setEndTime(Timestamps.fromMillis(System.currentTimeMillis()))
            .setFilter(traceFilter)
            .setOrderBy(traceOrder)
            .build();
    ListTracesPagedResponse traceResponse = traceServiceClient.listTraces(traceRequest);
    assertThat(traceResponse.iterateAll()).isNotEmpty();
    List<String> traceIdList = new ArrayList<>();
    for (Trace t : traceResponse.iterateAll()) {
      traceIdList.add(t.getTraceId());
    }

    for (String traceId : traceIdList) {
      // This checks Cloud trace for the new trace that was just created.
      GetTraceRequest getTraceRequest =
          GetTraceRequest.newBuilder().setProjectId(PROJECT_ID).setTraceId(traceId).build();
      Trace trace = traceServiceClient.getTrace(getTraceRequest);
      assertThat(trace.getSpansList()).hasSize(3);
      for (TraceSpan span : trace.getSpansList()) {
        assertThat(span.getName()).contains("grpc.testing.SimpleService.UnaryRpc");
        assertThat(span.getLabelsMap().get(customTagKey)).isEqualTo(customTagValue);
      }
    }
    observability.close();
  }
}
