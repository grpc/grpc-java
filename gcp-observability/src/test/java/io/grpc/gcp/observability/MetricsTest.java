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

import com.google.cloud.monitoring.v3.MetricServiceClient;
import com.google.cloud.monitoring.v3.MetricServiceClient.ListTimeSeriesPagedResponse;
import com.google.monitoring.v3.ListTimeSeriesRequest;
import com.google.monitoring.v3.ProjectName;
import com.google.monitoring.v3.TimeInterval;
import com.google.monitoring.v3.TimeSeries;
import com.google.protobuf.util.Timestamps;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.StaticTestingClassLoader;
import io.grpc.gcp.observability.interceptors.InternalLoggingChannelInterceptor;
import io.grpc.gcp.observability.interceptors.InternalLoggingServerInterceptor;
import io.grpc.gcp.observability.logging.GcpLogSink;
import io.grpc.gcp.observability.logging.Sink;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MetricsTest {

  @ClassRule
  public static final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private static final String PROJECT_ID = "PROJECT";
  private static final String TEST_CLIENT_METHOD = "grpc.testing.SimpleService/UnaryRpc";
  private static final String CUSTOM_TAG_KEY = "Version";
  private static final String CUSTOM_TAG_VALUE =
      String.format("C67J9A-%s", String.valueOf(System.currentTimeMillis()));
  private static final Map<String, String> CUSTOM_TAGS = Collections.singletonMap(CUSTOM_TAG_KEY,
      CUSTOM_TAG_VALUE);

  private final StaticTestingClassLoader classLoader =
      new StaticTestingClassLoader(getClass().getClassLoader(),
          Pattern.compile("io\\.grpc\\..*|io\\.opencensus\\..*"));

  /**
   * End to end cloud monitoring test.
   *
   * <p>Ignoring test, because it calls external Cloud Monitoring APIs. To test cloud monitoring
   * setup locally,
   * 1. Set up Cloud auth credentials
   * 2. Assign permissions to service account to write metrics to project specified by variable
   * PROJECT_ID
   * 3. Comment @Ignore annotation
   * 4. This test is expected to pass when ran with above setup. This has been verified manually.
   */
  @Ignore
  @Test
  public void testMetricsExporter() throws Exception {
    Class<?> runnable =
        classLoader.loadClass(MetricsTest.StaticTestingClassTestMetricsExporter.class.getName());
    ((Runnable) runnable.getDeclaredConstructor().newInstance()).run();
  }

  public static final class StaticTestingClassTestMetricsExporter implements Runnable {

    @Override
    public void run() {
      Sink mockSink = mock(GcpLogSink.class);
      ObservabilityConfig mockConfig = mock(ObservabilityConfig.class);
      InternalLoggingChannelInterceptor.Factory mockChannelInterceptorFactory =
          mock(InternalLoggingChannelInterceptor.Factory.class);
      InternalLoggingServerInterceptor.Factory mockServerInterceptorFactory =
          mock(InternalLoggingServerInterceptor.Factory.class);

      when(mockConfig.isEnableCloudMonitoring()).thenReturn(true);
      when(mockConfig.getProjectId()).thenReturn(PROJECT_ID);

      try {
        GcpObservability observability =
            GcpObservability.grpcInit(
                mockSink, mockConfig, mockChannelInterceptorFactory, mockServerInterceptorFactory);
        observability.registerStackDriverExporter(PROJECT_ID, CUSTOM_TAGS);

        Server server =
            ServerBuilder.forPort(0)
                .addService(new ObservabilityTestHelper.SimpleServiceImpl())
                .build()
                .start();
        int port = cleanupRule.register(server).getPort();
        SimpleServiceGrpc.SimpleServiceBlockingStub stub =
            SimpleServiceGrpc.newBlockingStub(
                cleanupRule.register(
                    ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build()));
        assertThat(ObservabilityTestHelper.makeUnaryRpcViaClientStub("buddy", stub))
            .isEqualTo("Hello buddy");
        // Adding sleep to ensure metrics are exported before querying cloud monitoring backend
        TimeUnit.SECONDS.sleep(40);

        // This checks Cloud monitoring for the new metrics that was just exported.
        MetricServiceClient metricServiceClient = MetricServiceClient.create();
        // Restrict time to last 1 minute
        long startMillis = System.currentTimeMillis() - ((60 * 1) * 1000);
        TimeInterval interval =
            TimeInterval.newBuilder()
                .setStartTime(Timestamps.fromMillis(startMillis))
                .setEndTime(Timestamps.fromMillis(System.currentTimeMillis()))
                .build();
        // Timeseries data
        String metricsFilter =
            String.format(
                "metric.type=\"custom.googleapis.com/opencensus/grpc.io/client/completed_rpcs\""
                    + " AND metric.labels.grpc_client_method=\"%s\""
                    + " AND metric.labels.%s=%s",
                TEST_CLIENT_METHOD, CUSTOM_TAG_KEY, CUSTOM_TAG_VALUE);
        ListTimeSeriesRequest metricsRequest =
            ListTimeSeriesRequest.newBuilder()
                .setName(ProjectName.of(PROJECT_ID).toString())
                .setFilter(metricsFilter)
                .setInterval(interval)
                .build();
        ListTimeSeriesPagedResponse response = metricServiceClient.listTimeSeries(metricsRequest);
        assertThat(response.iterateAll()).isNotEmpty();
        for (TimeSeries ts : response.iterateAll()) {
          assertThat(ts.getMetric().getLabelsMap().get("grpc_client_method"))
              .isEqualTo(TEST_CLIENT_METHOD);
          assertThat(ts.getMetric().getLabelsMap().get("grpc_client_status")).isEqualTo("OK");
          assertThat(ts.getPoints(0).getValue().getInt64Value()).isEqualTo(1);
        }
        observability.close();
      } catch (IOException | InterruptedException e) {
        throw new AssertionError("Exception while testing metrics", e);
      }
    }
  }
}
