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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.MetricInstrument;
import io.grpc.MetricRecorder;
import io.grpc.MetricRecorder.BatchCallback;
import io.grpc.MetricRecorder.BatchRecorder;
import io.grpc.MetricSink;
import io.grpc.xds.XdsClientMetricReporterImpl.CallbackMetricReporterImpl;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsClientMetricReporter.CallbackMetricReporter;
import java.util.Arrays;
import java.util.Collections;
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
  @Captor
  private ArgumentCaptor<BatchCallback> gaugeBatchCallbackCaptor;

  private XdsClientMetricReporterImpl reporter;

  @Before
  public void setUp() {
    reporter = new XdsClientMetricReporterImpl(mockMetricRecorder);
  }

  @Test
  public void reportResourceUpdates() {
    // TODO(dnvindhya): add the "authority" label once available.
    reporter.reportResourceUpdates(10, 5, target, server, resourceTypeUrl);
    verify(mockMetricRecorder).addLongCounter(
        eqMetricInstrumentName("grpc.xds_client.resource_updates_valid"), eq((long) 10),
        eq(Lists.newArrayList(target, server, resourceTypeUrl)),
        eq(Lists.newArrayList()));
    verify(mockMetricRecorder).addLongCounter(
        eqMetricInstrumentName("grpc.xds_client.resource_updates_invalid"),
        eq((long) 5),
        eq(Lists.newArrayList(target, server, resourceTypeUrl)),
        eq(Lists.newArrayList()));
  }

  @Test
  public void reportServerFailure() {
    reporter.reportServerFailure(1, target, server);
    verify(mockMetricRecorder).addLongCounter(
        eqMetricInstrumentName("grpc.xds_client.server_failure"), eq((long) 1),
        eq(Lists.newArrayList(target, server)),
        eq(Lists.newArrayList()));
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
    verify(mockMetricRecorder).registerBatchCallback(gaugeBatchCallbackCaptor.capture(),
        eqMetricInstrumentName("grpc.xds_client.connected"),
        eqMetricInstrumentName("grpc.xds_client.resources"));
    gaugeBatchCallbackCaptor.getValue().accept(mockBatchRecorder);
    verify(mockXdsClient).reportResourceCounts(any(CallbackMetricReporter.class));
    verify(mockXdsClient).reportServerConnections(any(CallbackMetricReporter.class));
  }

  @Test
  public void setXdsClient_reportCallbackMetrics_resourceCountsFails() {
    SettableFuture<Void> resourceCountsFuture = SettableFuture.create();
    resourceCountsFuture.setException(new Exception("test"));
    when(mockXdsClient.reportResourceCounts(any())).thenReturn(resourceCountsFuture);

    reporter.setXdsClient(mockXdsClient);
    verify(mockMetricRecorder)
        .registerBatchCallback(gaugeBatchCallbackCaptor.capture(), any(), any());
    gaugeBatchCallbackCaptor.getValue().accept(mockBatchRecorder);

    verify(mockXdsClient).reportResourceCounts(any(CallbackMetricReporter.class));
    verify(mockXdsClient, never()).reportServerConnections(any(CallbackMetricReporter.class));
  }

  @Test
  public void metricGauges() {
    XdsClientMetricReporterImpl spyReporter = spy(
        new XdsClientMetricReporterImpl(mockMetricRecorder));
    SettableFuture<Void> future = SettableFuture.create();
    future.set(null);
    when(mockXdsClient.reportResourceCounts(any(CallbackMetricReporter.class)))
        .thenReturn(future);
    when(mockXdsClient.reportServerConnections(any(CallbackMetricReporter.class)))
        .thenReturn(future);
    CallbackMetricReporter callbackMetricReporter = new CallbackMetricReporterImpl(
        mockBatchRecorder);
    when(spyReporter.createCallbackMetricReporter(mockBatchRecorder)).thenReturn(
        callbackMetricReporter);
    spyReporter.setXdsClient(mockXdsClient);
    verify(mockMetricRecorder).registerBatchCallback(gaugeBatchCallbackCaptor.capture(),
        eqMetricInstrumentName("grpc.xds_client.connected"),
        eqMetricInstrumentName("grpc.xds_client.resources"));
    BatchCallback gaugeBatchCallback = gaugeBatchCallbackCaptor.getValue();
    // Verify the correct resource gauge values when requested at this point.
    InOrder inOrder = inOrder(mockBatchRecorder);
    gaugeBatchCallback.accept(mockBatchRecorder);

    verify(mockXdsClient).reportResourceCounts(eq(callbackMetricReporter));
    verify(mockXdsClient).reportServerConnections(eq(callbackMetricReporter));

    callbackMetricReporter.reportResourceCounts(10, "acked", resourceTypeUrl, target);
    inOrder.verify(mockBatchRecorder)
        .recordLongGauge(eqMetricInstrumentName("grpc.xds_client.resources"), eq(10L), any(),
            any());

    callbackMetricReporter.reportServerConnections(1, target, "xdsServer");
    inOrder.verify(mockBatchRecorder)
        .recordLongGauge(eqMetricInstrumentName("grpc.xds_client.connected"), eq(1L), any(), any());

    inOrder.verifyNoMoreInteractions();
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

  @Test
  public void close_closesGaugeRegistration() {
    MetricSink.Registration mockRegistration = mock(MetricSink.Registration.class);
    when(mockMetricRecorder.registerBatchCallback(any(MetricRecorder.BatchCallback.class),
        eqMetricInstrumentName("grpc.xds_client.connected"),
        eqMetricInstrumentName("grpc.xds_client.resources"))).thenReturn(mockRegistration);

    // Sets XdsClient and register the gauges
    reporter.setXdsClient(mockXdsClient);
    // Closes registered gauges
    reporter.close();
    verify(mockRegistration, times(1)).close();
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
