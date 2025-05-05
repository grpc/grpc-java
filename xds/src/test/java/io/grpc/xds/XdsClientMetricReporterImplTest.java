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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.Any;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.grpc.MetricInstrument;
import io.grpc.MetricRecorder;
import io.grpc.MetricRecorder.BatchCallback;
import io.grpc.MetricRecorder.BatchRecorder;
import io.grpc.MetricSink;
import io.grpc.xds.XdsClientMetricReporterImpl.MetricReporterCallback;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsClient.ResourceMetadata;
import io.grpc.xds.client.XdsClient.ServerConnectionCallback;
import io.grpc.xds.client.XdsResourceType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
  private static final String authority = "test-authority";
  private static final String server = "trafficdirector.googleapis.com";
  private static final String resourceTypeUrl =
      "resourceTypeUrl.googleapis.com/envoy.config.cluster.v3.Cluster";

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  private XdsClient mockXdsClient;
  @Captor
  private ArgumentCaptor<BatchCallback> gaugeBatchCallbackCaptor;
  private MetricRecorder mockMetricRecorder = mock(MetricRecorder.class,
      delegatesTo(new MetricRecorderImpl()));
  private BatchRecorder mockBatchRecorder = mock(BatchRecorder.class,
      delegatesTo(new BatchRecorderImpl()));

  private XdsClientMetricReporterImpl reporter;

  @Before
  public void setUp() {
    reporter = new XdsClientMetricReporterImpl(mockMetricRecorder, target);
  }

  @Test
  public void reportResourceUpdates() {
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
    when(mockXdsClient.getSubscribedResourcesMetadataSnapshot()).thenReturn(Futures.immediateFuture(
        ImmutableMap.of()));
    when(mockXdsClient.reportServerConnections(any(ServerConnectionCallback.class)))
        .thenReturn(future);
    reporter.setXdsClient(mockXdsClient);
    verify(mockMetricRecorder).registerBatchCallback(gaugeBatchCallbackCaptor.capture(),
        eqMetricInstrumentName("grpc.xds_client.connected"),
        eqMetricInstrumentName("grpc.xds_client.resources"));
    gaugeBatchCallbackCaptor.getValue().accept(mockBatchRecorder);
    verify(mockXdsClient).reportServerConnections(any(ServerConnectionCallback.class));
  }

  @Test
  public void setXdsClient_reportCallbackMetrics_resourceCountsFails() {
    TestlogHandler testLogHandler = new TestlogHandler();
    Logger logger = Logger.getLogger(XdsClientMetricReporterImpl.class.getName());
    logger.addHandler(testLogHandler);

    // For reporting resource counts connections, return a normally completed future
    SettableFuture<Void> future = SettableFuture.create();
    future.set(null);
    when(mockXdsClient.getSubscribedResourcesMetadataSnapshot()).thenReturn(Futures.immediateFuture(
        ImmutableMap.of()));

    // Create a future that will throw an exception
    SettableFuture<Void> serverConnectionsFeature = SettableFuture.create();
    serverConnectionsFeature.setException(new Exception("test"));
    when(mockXdsClient.reportServerConnections(any())).thenReturn(serverConnectionsFeature);

    reporter.setXdsClient(mockXdsClient);
    verify(mockMetricRecorder)
        .registerBatchCallback(gaugeBatchCallbackCaptor.capture(), any(), any());
    gaugeBatchCallbackCaptor.getValue().accept(mockBatchRecorder);
    // Verify that the xdsClient methods were called
    // verify(mockXdsClient).reportResourceCounts(any());
    verify(mockXdsClient).reportServerConnections(any());

    assertThat(testLogHandler.getLogs().size()).isEqualTo(1);
    assertThat(testLogHandler.getLogs().get(0).getLevel()).isEqualTo(Level.WARNING);
    assertThat(testLogHandler.getLogs().get(0).getMessage()).isEqualTo(
        "Failed to report gauge metrics");
    logger.removeHandler(testLogHandler);
  }

  @Test
  public void metricGauges() {
    SettableFuture<Void> future = SettableFuture.create();
    future.set(null);
    when(mockXdsClient.getSubscribedResourcesMetadataSnapshot())
        .thenReturn(Futures.immediateFuture(ImmutableMap.of()));
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

    ArgumentCaptor<ServerConnectionCallback> serverConnectionCallbackCaptor =
        ArgumentCaptor.forClass(ServerConnectionCallback.class);
    // verify(mockXdsClient).reportResourceCounts(resourceCallbackCaptor.capture());
    verify(mockXdsClient).reportServerConnections(serverConnectionCallbackCaptor.capture());

    // Get the captured callback
    MetricReporterCallback callback = (MetricReporterCallback)
        serverConnectionCallbackCaptor.getValue();

    // Verify that reportResourceCounts and reportServerConnections were called
    // with the captured callback
    callback.reportResourceCountGauge(10, "MrPotatoHead",
        "acked", resourceTypeUrl);
    inOrder.verify(mockBatchRecorder)
        .recordLongGauge(eqMetricInstrumentName("grpc.xds_client.resources"), eq(10L), any(),
            any());
    callback.reportServerConnectionGauge(true, "xdsServer");
    inOrder.verify(mockBatchRecorder)
        .recordLongGauge(eqMetricInstrumentName("grpc.xds_client.connected"),
            eq(1L), any(), any());

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
    callback.reportResourceCountGauge(10, authority, cacheState, resourceTypeUrl);
    verify(mockBatchRecorder, times(1)).recordLongGauge(
        eqMetricInstrumentName("grpc.xds_client.resources"), eq(10L),
        eq(Arrays.asList(target, authority, cacheState, resourceTypeUrl)),
        eq(Collections.emptyList()));
  }

  @Test
  public void reportCallbackMetrics_computeAndReportResourceCounts() {
    Map<XdsResourceType<?>, Map<String, ResourceMetadata>> metadataByType = new HashMap<>();
    XdsResourceType<?> listenerResource = XdsListenerResource.getInstance();
    XdsResourceType<?> routeConfigResource = XdsRouteConfigureResource.getInstance();
    XdsResourceType<?> clusterResource = XdsClusterResource.getInstance();

    Any rawListener = Any.pack(Listener.newBuilder().setName("listener.googleapis.com").build());
    long nanosLastUpdate = 1577923199_606042047L;

    Map<String, ResourceMetadata> ldsResourceMetadataMap = new HashMap<>();
    ldsResourceMetadataMap.put("xdstp://authority1",
        ResourceMetadata.newResourceMetadataRequested());
    ResourceMetadata ackedLdsResource =
        ResourceMetadata.newResourceMetadataAcked(rawListener, "42", nanosLastUpdate);
    ldsResourceMetadataMap.put("resource2", ackedLdsResource);
    ldsResourceMetadataMap.put("resource3",
        ResourceMetadata.newResourceMetadataAcked(rawListener, "43", nanosLastUpdate));
    ldsResourceMetadataMap.put("xdstp:/no_authority",
        ResourceMetadata.newResourceMetadataNacked(ackedLdsResource, "44",
            nanosLastUpdate, "nacked after previous ack", true));

    Map<String, ResourceMetadata> rdsResourceMetadataMap = new HashMap<>();
    ResourceMetadata requestedRdsResourceMetadata = ResourceMetadata.newResourceMetadataRequested();
    rdsResourceMetadataMap.put("xdstp://authority5",
        ResourceMetadata.newResourceMetadataNacked(requestedRdsResourceMetadata, "24",
            nanosLastUpdate, "nacked after request", false));
    rdsResourceMetadataMap.put("xdstp://authority6",
        ResourceMetadata.newResourceMetadataDoesNotExist());

    Map<String, ResourceMetadata> cdsResourceMetadataMap = new HashMap<>();
    cdsResourceMetadataMap.put("xdstp://authority7", ResourceMetadata.newResourceMetadataUnknown());

    metadataByType.put(listenerResource, ldsResourceMetadataMap);
    metadataByType.put(routeConfigResource, rdsResourceMetadataMap);
    metadataByType.put(clusterResource, cdsResourceMetadataMap);

    SettableFuture<Void> reportServerConnectionsCompleted = SettableFuture.create();
    reportServerConnectionsCompleted.set(null);
    when(mockXdsClient.reportServerConnections(any(MetricReporterCallback.class)))
        .thenReturn(reportServerConnectionsCompleted);

    ListenableFuture<Map<XdsResourceType<?>, Map<String, ResourceMetadata>>>
        getResourceMetadataCompleted = Futures.immediateFuture(metadataByType);
    when(mockXdsClient.getSubscribedResourcesMetadataSnapshot())
        .thenReturn(getResourceMetadataCompleted);

    reporter.reportCallbackMetrics(mockBatchRecorder, mockXdsClient);

    // LDS resource requested
    verify(mockBatchRecorder).recordLongGauge(eqMetricInstrumentName("grpc.xds_client.resources"),
        eq(1L),
        eq(Arrays.asList(target, "authority1", "requested", listenerResource.typeUrl())), any());
    // LDS resources acked
    // authority = #old, for non-xdstp resource names
    verify(mockBatchRecorder).recordLongGauge(eqMetricInstrumentName("grpc.xds_client.resources"),
        eq(2L),
        eq(Arrays.asList(target, "#old", "acked", listenerResource.typeUrl())), any());
    // LDS resource nacked but cached
    // "" for missing authority in the resource name
    verify(mockBatchRecorder).recordLongGauge(eqMetricInstrumentName("grpc.xds_client.resources"),
        eq(1L),
        eq(Arrays.asList(target, "", "nacked_but_cached", listenerResource.typeUrl())), any());

    // RDS resource nacked
    verify(mockBatchRecorder).recordLongGauge(eqMetricInstrumentName("grpc.xds_client.resources"),
        eq(1L),
        eq(Arrays.asList(target, "authority5", "nacked", routeConfigResource.typeUrl())), any());
    // RDS resource does not exist
    verify(mockBatchRecorder).recordLongGauge(eqMetricInstrumentName("grpc.xds_client.resources"),
        eq(1L),
        eq(Arrays.asList(target, "authority6", "does_not_exist", routeConfigResource.typeUrl())),
        any());

    // CDS resource unknown
    verify(mockBatchRecorder).recordLongGauge(eqMetricInstrumentName("grpc.xds_client.resources"),
        eq(1L),
        eq(Arrays.asList(target, "authority7", "unknown", clusterResource.typeUrl())),
        any());
    verifyNoMoreInteractions(mockBatchRecorder);
  }

  @Test
  public void reportCallbackMetrics_computeAndReportResourceCounts_emptyResources() {
    Map<XdsResourceType<?>, Map<String, ResourceMetadata>> metadataByType = new HashMap<>();
    XdsResourceType<?> listenerResource = XdsListenerResource.getInstance();
    metadataByType.put(listenerResource, Collections.emptyMap());

    SettableFuture<Void> reportServerConnectionsCompleted = SettableFuture.create();
    reportServerConnectionsCompleted.set(null);
    when(mockXdsClient.reportServerConnections(any(MetricReporterCallback.class)))
        .thenReturn(reportServerConnectionsCompleted);

    ListenableFuture<Map<XdsResourceType<?>, Map<String, ResourceMetadata>>>
        getResourceMetadataCompleted = Futures.immediateFuture(metadataByType);
    when(mockXdsClient.getSubscribedResourcesMetadataSnapshot())
        .thenReturn(getResourceMetadataCompleted);

    reporter.reportCallbackMetrics(mockBatchRecorder, mockXdsClient);

    // Verify that reportResourceCountGauge is never called
    verifyNoInteractions(mockBatchRecorder);
  }

  @Test
  public void reportCallbackMetrics_computeAndReportResourceCounts_nullMetadata() {
    TestlogHandler testLogHandler = new TestlogHandler();
    Logger logger = Logger.getLogger(XdsClientMetricReporterImpl.class.getName());
    logger.addHandler(testLogHandler);

    SettableFuture<Void> reportServerConnectionsCompleted = SettableFuture.create();
    reportServerConnectionsCompleted.set(null);
    when(mockXdsClient.reportServerConnections(any(MetricReporterCallback.class)))
        .thenReturn(reportServerConnectionsCompleted);

    ListenableFuture<Map<XdsResourceType<?>, Map<String, ResourceMetadata>>>
        getResourceMetadataCompleted = Futures.immediateFailedFuture(
            new Exception("Error generating metadata snapshot"));
    when(mockXdsClient.getSubscribedResourcesMetadataSnapshot())
        .thenReturn(getResourceMetadataCompleted);

    reporter.reportCallbackMetrics(mockBatchRecorder, mockXdsClient);
    assertThat(testLogHandler.getLogs().size()).isEqualTo(1);
    assertThat(testLogHandler.getLogs().get(0).getLevel()).isEqualTo(Level.WARNING);
    assertThat(testLogHandler.getLogs().get(0).getMessage()).isEqualTo(
        "Failed to report gauge metrics");
    logger.removeHandler(testLogHandler);
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

  static class MetricRecorderImpl implements MetricRecorder {
  }

  static class BatchRecorderImpl implements BatchRecorder {
  }

  static class TestlogHandler extends Handler {
    List<LogRecord> logs = new ArrayList<>();

    @Override
    public void publish(LogRecord record) {
      logs.add(record);
    }

    @Override
    public void close() {}

    @Override
    public void flush() {}

    public List<LogRecord> getLogs() {
      return logs;
    }
  }

}
