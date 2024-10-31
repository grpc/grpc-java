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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.MetricInstrument;
import io.grpc.MetricRecorder;
import io.grpc.MetricRecorder.BatchCallback;
import io.grpc.MetricRecorder.BatchRecorder;
import io.grpc.MetricRecorder.Registration;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.XdsClientMetricReporter.CallbackMetricReporter;
import io.grpc.xds.client.XdsClient;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
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
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Unit tests for {@link XdsClientMetricReporterImpl}.
 */
@RunWith(JUnit4.class)
public class XdsClientMetricReporterImplTest {

  private static final String target = "test-target";
  private static final String server = "trafficdirector.googleapis.com";
  private static final String resourceTypeUrl =
      "resourceTypeUrl.googleapis.com/envoy.config.cluster.v3.Cluster";

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  private MetricRecorder mockMetricRecorder;
  @Mock
  private XdsClient mockXdsClient;
  @Mock
  private BatchRecorder mockBatchRecorder;
  @Mock
  private Registration mockGaugeRegistration;
  @Captor
  private ArgumentCaptor<BatchCallback> gaugeBatchCallbackCaptor;

  private XdsClientMetricReporterImpl reporter;

  @Before
  public void setUp() {
    when(mockMetricRecorder.registerBatchCallback(any(), any())).thenReturn(mockGaugeRegistration);
    reporter = new XdsClientMetricReporterImpl(mockMetricRecorder);
  }

  @Test
  public void reportResourceUpdates() {
    reporter.reportResourceUpdates(10, 5, target, server, resourceTypeUrl);
    verifyValidInvalidResourceCounterAdd(10, 5, target, server, resourceTypeUrl);
  }

  @Test
  public void reportServerFailure() {
    reporter.reportServerFailure(1, target, server);
    verifyServerFailureCounterAdd("grpc.xds_client.server_failure", 1, target, server);
  }

  @Test
  public void setXdsClient_reportMetrics() throws Exception {
    SettableFuture<Void> future = SettableFuture.create();
    future.set(null);
    when(mockXdsClient.reportResourceCounts(any(CallbackMetricReporter.class)))
        .thenReturn(future);
    when(mockXdsClient.reportServerConnections(any(CallbackMetricReporter.class)))
        .thenReturn(future);
    reporter.setXdsClient(mockXdsClient);
    verify(mockMetricRecorder).registerBatchCallback(any(MetricRecorder.BatchCallback.class),
        eqMetricInstrumentName("grpc.xds_client.connected"),
        eqMetricInstrumentName("grpc.xds_client.resources"));

    reporter.reportCallbackMetrics(mockBatchRecorder);
    verify(mockXdsClient).reportResourceCounts(any(CallbackMetricReporter.class));
    verify(mockXdsClient).reportServerConnections(any(CallbackMetricReporter.class));
  }

  @Test
  public void setXdsClient_reportMetrics_exception() throws Exception {
    SettableFuture<Void> future = SettableFuture.create();
    future.setException(new Exception("test"));
    when(mockXdsClient.reportResourceCounts(any(CallbackMetricReporter.class)))
        .thenReturn(future);
    reporter.setXdsClient(mockXdsClient);
    verify(mockMetricRecorder).registerBatchCallback(any(MetricRecorder.BatchCallback.class),
        eqMetricInstrumentName("grpc.xds_client.connected"),
        eqMetricInstrumentName("grpc.xds_client.resources"));

    reporter.reportCallbackMetrics(mockBatchRecorder);
    verify(mockXdsClient).reportResourceCounts(any(CallbackMetricReporter.class));
    verify(mockXdsClient, never()).reportServerConnections(
        any(CallbackMetricReporter.class));
  }

  // @Test
  // public void metricGauges() throws ExecutionException, InterruptedException, TimeoutException {
  //   reporter.setXdsClient(mockXdsClient);
  //   verify(mockMetricRecorder).registerBatchCallback(gaugeBatchCallbackCaptor.capture(),
  //       any());
  //   BatchCallback gaugeBatchCallback = gaugeBatchCallbackCaptor.getValue();
  //   // Verify the correct resource gauge values when requested at this point.
  //   InOrder inOrder = inOrder(mockBatchRecorder);
  //   gaugeBatchCallback.accept(mockBatchRecorder);
  //
  //   verify(mockXdsClient).reportResourceCounts(any(CallbackMetricReporter.class));
  //   verify(mockXdsClient).reportServerConnections(any(CallbackMetricReporter.class));
  //
  //   inOrder.verify(mockBatchRecorder).recordLongGauge(
  //       argThat(new LongGaugeInstrumentArgumentMatcher("grpc.lb.rls.cache_entries")), eq(0L),
  //       any(), any());
  //   inOrder.verify(mockBatchRecorder)
  //       .recordLongGauge(argThat(new LongGaugeInstrumentArgumentMatcher(
  //       "grpc.lb.rls.cache_size")),
  //           eq(0L), any(), any());
  // }

  @Test
  public void close() {
    reporter.setXdsClient(mockXdsClient);
    verify(mockMetricRecorder).registerBatchCallback(any(MetricRecorder.BatchCallback.class),
        eqMetricInstrumentName("grpc.xds_client.connected"),
        eqMetricInstrumentName("grpc.xds_client.resources"));
    reporter.close();
  }

  @Test
  public void callbackMetricReporter() {
    XdsClientMetricReporterImpl.CallbackMetricReporterImpl callback =
        new XdsClientMetricReporterImpl.CallbackMetricReporterImpl(mockBatchRecorder);

    callback.reportServerConnections(1, target, server);
    verify(mockBatchRecorder, times(1)).recordLongGauge(
        eqMetricInstrumentName("grpc.xds_client.connected"), eq(1L),
        eq(Lists.newArrayList(target, server)),
        eq(Lists.newArrayList()));

    String cacheState = "requested";
    callback.reportResourceCounts(10, cacheState, resourceTypeUrl, target);
    verify(mockBatchRecorder, times(1)).recordLongGauge(
        eqMetricInstrumentName("grpc.xds_client.resources"), eq(10L),
        eq(Arrays.asList(target, cacheState, resourceTypeUrl)),
        eq(Collections.emptyList()));
  }

  private void verifyServerFailureCounterAdd(String name, long value,
      String dataPlaneTargetLabel, String xdsServer) {
    verify(mockMetricRecorder).addLongCounter(
        eqMetricInstrumentName(name), eq(value),
        eq(Lists.newArrayList(dataPlaneTargetLabel, xdsServer)),
        eq(Lists.newArrayList()));
  }

  private void verifyValidInvalidResourceCounterAdd(long validResourceCount,
      long invalidResourceCount,
      String target, String xdsServer, String resourceTypeUrl) {
    // TODO(dnvindhya): support the "authority" label once available.
    verify(mockMetricRecorder).addLongCounter(
        eqMetricInstrumentName("grpc.xds_client.resource_updates_valid"), eq(validResourceCount),
        eq(Lists.newArrayList(target, xdsServer, resourceTypeUrl)),
        eq(Lists.newArrayList()));
    // TODO(dnvindhya): support the "authority" label once available.
    verify(mockMetricRecorder).addLongCounter(
        eqMetricInstrumentName("grpc.xds_client.resource_updates_invalid"),
        eq(invalidResourceCount),
        eq(Lists.newArrayList(target, xdsServer, resourceTypeUrl)),
        eq(Lists.newArrayList()));
  }

  @SuppressWarnings("TypeParameterUnusedInFormals")
  private <T extends MetricInstrument> T eqMetricInstrumentName(String name) {
    return argThat(new ArgumentMatcher<T>() {
      @Override
      public boolean matches(T instrument) {
        return instrument.getName().equals(name);
      }
    });
  }
}
