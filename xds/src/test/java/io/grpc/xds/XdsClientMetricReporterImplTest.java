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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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
import io.grpc.xds.XdsClientMetricReporterImpl.MetricReporterCallback;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsClient.ResourceCallback;
import io.grpc.xds.client.XdsClient.ServerConnectionCallback;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
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
    reporter = new XdsClientMetricReporterImpl(mockMetricRecorder, target);
  }

  @Test
  public void reportResourceUpdates() {
    // TODO(dnvindhya): add the "authority" label once available.
    reporter.reportResourceUpdates(10, 5, server, resourceTypeUrl);
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
    reporter.reportServerFailure(1, server);
    verify(mockMetricRecorder).addLongCounter(
        eqMetricInstrumentName("grpc.xds_client.server_failure"), eq((long) 1),
        eq(Lists.newArrayList(target, server)),
        eq(Lists.newArrayList()));
  }

  @Test
  public void setXdsClient_reportMetrics() throws Exception {
    SettableFuture<Void> future = SettableFuture.create();
    future.set(null);
    when(mockXdsClient.reportResourceCounts(any(ResourceCallback.class)))
        .thenReturn(future);
    when(mockXdsClient.reportServerConnections(any(ServerConnectionCallback.class)))
        .thenReturn(future);
    reporter.setXdsClient(mockXdsClient);
    verify(mockMetricRecorder).registerBatchCallback(gaugeBatchCallbackCaptor.capture(),
        eqMetricInstrumentName("grpc.xds_client.connected"),
        eqMetricInstrumentName("grpc.xds_client.resources"));
    gaugeBatchCallbackCaptor.getValue().accept(mockBatchRecorder);
    verify(mockXdsClient).reportResourceCounts(any(ResourceCallback.class));
    verify(mockXdsClient).reportServerConnections(any(ServerConnectionCallback.class));
  }

  @Test
  public void setXdsClient_reportCallbackMetrics_resourceCountsFails() {
    List<LogRecord> logs = new ArrayList<>();
    Handler testLogHandler = new Handler() {
      @Override
      public void publish(LogRecord record) {
        logs.add(record);
      }

      @Override
      public void close() {}

      @Override
      public void flush() {}
    };
    Logger logger = Logger.getLogger(XdsClientMetricReporterImpl.class.getName());
    logger.addHandler(testLogHandler);

    // Create a future that will throw an exception
    SettableFuture<Void> resourceCountsFuture = SettableFuture.create();
    resourceCountsFuture.setException(new Exception("test"));
    when(mockXdsClient.reportResourceCounts(any())).thenReturn(resourceCountsFuture);

    // For server connections, return a normally completed future
    SettableFuture<Void> serverConnectionsFuture = SettableFuture.create();
    serverConnectionsFuture.set(null);
    when(mockXdsClient.reportServerConnections(any())).thenReturn(serverConnectionsFuture);

    reporter.setXdsClient(mockXdsClient);
    verify(mockMetricRecorder)
        .registerBatchCallback(gaugeBatchCallbackCaptor.capture(), any(), any());
    gaugeBatchCallbackCaptor.getValue().accept(mockBatchRecorder);
    // Verify that the xdsClient methods were called
    verify(mockXdsClient).reportResourceCounts(any());
    verify(mockXdsClient).reportServerConnections(any());

    assertThat(logs.size()).isEqualTo(1);
    assertThat(logs.get(0).getLevel()).isEqualTo(Level.WARNING);
    assertThat(logs.get(0).getMessage()).isEqualTo("Failed to report gauge metrics");
    logger.removeHandler(testLogHandler);
  }

  @Test
  public void metricGauges() {
    SettableFuture<Void> future = SettableFuture.create();
    future.set(null);
    when(mockXdsClient.reportResourceCounts(any(ResourceCallback.class)))
        .thenReturn(future);
    when(mockXdsClient.reportServerConnections(any(ServerConnectionCallback.class)))
        .thenReturn(future);
    reporter.setXdsClient(mockXdsClient);
    verify(mockMetricRecorder).registerBatchCallback(gaugeBatchCallbackCaptor.capture(),
        eqMetricInstrumentName("grpc.xds_client.connected"),
        eqMetricInstrumentName("grpc.xds_client.resources"));
    BatchCallback gaugeBatchCallback = gaugeBatchCallbackCaptor.getValue();
    InOrder inOrder = inOrder(mockBatchRecorder);
    // Trigger the internal call to reportCallbackMetrics()
    gaugeBatchCallback.accept(mockBatchRecorder);

    ArgumentCaptor<ResourceCallback> resourceCallbackCaptor =
        ArgumentCaptor.forClass(ResourceCallback.class);
    ArgumentCaptor<ServerConnectionCallback> serverConnectionCallbackCaptor =
        ArgumentCaptor.forClass(ServerConnectionCallback.class);
    verify(mockXdsClient).reportResourceCounts(resourceCallbackCaptor.capture());
    verify(mockXdsClient).reportServerConnections(serverConnectionCallbackCaptor.capture());

    // Get the captured callback
    MetricReporterCallback callback = (MetricReporterCallback) resourceCallbackCaptor.getValue();

    // Verify that reportResourceCounts and reportServerConnections were called
    // with the captured callback
    callback.reportResourceCountGauge(10, "acked", resourceTypeUrl);
    inOrder.verify(mockBatchRecorder)
        .recordLongGauge(eqMetricInstrumentName("grpc.xds_client.resources"), eq(10L), any(),
            any());
    callback.reportServerConnectionGauge(true, "xdsServer");
    inOrder.verify(mockBatchRecorder)
        .recordLongGauge(eqMetricInstrumentName("grpc.xds_client.connected"), eq(1L), any(), any());

    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void metricReporterCallback() {
    MetricReporterCallback callback =
        new MetricReporterCallback(mockBatchRecorder, target);

    callback.reportServerConnectionGauge(true, server);
    verify(mockBatchRecorder, times(1)).recordLongGauge(
        eqMetricInstrumentName("grpc.xds_client.connected"), eq(1L),
        eq(Lists.newArrayList(target, server)),
        eq(Lists.newArrayList()));

    String cacheState = "requested";
    callback.reportResourceCountGauge(10, cacheState, resourceTypeUrl);
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
