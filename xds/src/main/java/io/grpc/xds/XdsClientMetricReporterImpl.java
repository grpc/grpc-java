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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.LongCounterMetricInstrument;
import io.grpc.LongGaugeMetricInstrument;
import io.grpc.MetricInstrumentRegistry;
import io.grpc.MetricRecorder;
import io.grpc.MetricRecorder.BatchCallback;
import io.grpc.MetricRecorder.BatchRecorder;
import io.grpc.MetricRecorder.Registration;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsClient.ResourceMetadata;
import io.grpc.xds.client.XdsClient.ResourceMetadata.ResourceMetadataStatus;
import io.grpc.xds.client.XdsClient.ServerConnectionCallback;
import io.grpc.xds.client.XdsClientMetricReporter;
import io.grpc.xds.client.XdsResourceType;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * XdsClientMetricReporter implementation.
 */
final class XdsClientMetricReporterImpl implements XdsClientMetricReporter {

  private static final Logger logger = Logger.getLogger(
      XdsClientMetricReporterImpl.class.getName());
  private static final LongCounterMetricInstrument SERVER_FAILURE_COUNTER;
  private static final LongCounterMetricInstrument RESOURCE_UPDATES_VALID_COUNTER;
  private static final LongCounterMetricInstrument RESOURCE_UPDATES_INVALID_COUNTER;
  private static final LongGaugeMetricInstrument CONNECTED_GAUGE;
  private static final LongGaugeMetricInstrument RESOURCES_GAUGE;

  private final MetricRecorder metricRecorder;
  private final String target;
  @Nullable
  private Registration gaugeRegistration = null;

  static {
    MetricInstrumentRegistry metricInstrumentRegistry
        = MetricInstrumentRegistry.getDefaultRegistry();
    SERVER_FAILURE_COUNTER = metricInstrumentRegistry.registerLongCounter(
       "grpc.xds_client.server_failure",
        "EXPERIMENTAL. A counter of xDS servers going from healthy to unhealthy. A server goes"
            + " unhealthy when we have a connectivity failure or when the ADS stream fails without"
            + " seeing a response message, as per gRFC A57.", "{failure}",
        Arrays.asList("grpc.target", "grpc.xds.server"), Collections.emptyList(), false);
    RESOURCE_UPDATES_VALID_COUNTER = metricInstrumentRegistry.registerLongCounter(
        "grpc.xds_client.resource_updates_valid",
        "EXPERIMENTAL. A counter of resources received that were considered valid. The counter will"
            + " be incremented even for resources that have not changed.", "{resource}",
        Arrays.asList("grpc.target", "grpc.xds.server", "grpc.xds.resource_type"),
        Collections.emptyList(), false);
    RESOURCE_UPDATES_INVALID_COUNTER = metricInstrumentRegistry.registerLongCounter(
        "grpc.xds_client.resource_updates_invalid",
        "EXPERIMENTAL. A counter of resources received that were considered invalid.", "{resource}",
        Arrays.asList("grpc.target", "grpc.xds.server", "grpc.xds.resource_type"),
        Collections.emptyList(), false);
    CONNECTED_GAUGE = metricInstrumentRegistry.registerLongGauge("grpc.xds_client.connected",
        "EXPERIMENTAL. Whether or not the xDS client currently has a working ADS stream to the xDS"
            + " server. For a given server, this will be set to 1 when the stream is initially"
            + " created. It will be set to 0 when we have a connectivity failure or when the ADS"
            + " stream fails without seeing a response message, as per gRFC A57. Once set to 0, it"
            + " will be reset to 1 when we receive the first response on an ADS stream.", "{bool}",
        Arrays.asList("grpc.target", "grpc.xds.server"), Collections.emptyList(), false);
    RESOURCES_GAUGE = metricInstrumentRegistry.registerLongGauge("grpc.xds_client.resources",
        "EXPERIMENTAL.  Number of xDS resources.", "{resource}",
        Arrays.asList("grpc.target", "grpc.xds.authority", "grpc.xds.cache_state",
            "grpc.xds.resource_type"), Collections.emptyList(), false);
  }

  XdsClientMetricReporterImpl(MetricRecorder metricRecorder, String target) {
    this.metricRecorder = metricRecorder;
    this.target = target;
  }

  @Override
  public void reportResourceUpdates(long validResourceCount, long invalidResourceCount,
      String xdsServer, String resourceType) {
    metricRecorder.addLongCounter(RESOURCE_UPDATES_VALID_COUNTER, validResourceCount,
        Arrays.asList(target, xdsServer, resourceType), Collections.emptyList());
    metricRecorder.addLongCounter(RESOURCE_UPDATES_INVALID_COUNTER, invalidResourceCount,
        Arrays.asList(target, xdsServer, resourceType), Collections.emptyList());
  }

  @Override
  public void reportServerFailure(long serverFailure, String xdsServer) {
    metricRecorder.addLongCounter(SERVER_FAILURE_COUNTER, serverFailure,
        Arrays.asList(target, xdsServer), Collections.emptyList());
  }

  void setXdsClient(XdsClient xdsClient) {
    assert gaugeRegistration == null;
    // register gauge here
    this.gaugeRegistration = metricRecorder.registerBatchCallback(new BatchCallback() {
      @Override
      public void accept(BatchRecorder recorder) {
        reportCallbackMetrics(recorder, xdsClient);
      }
    }, CONNECTED_GAUGE, RESOURCES_GAUGE);
  }

  void close() {
    if (gaugeRegistration != null) {
      gaugeRegistration.close();
      gaugeRegistration = null;
    }
  }

  void reportCallbackMetrics(BatchRecorder recorder, XdsClient xdsClient) {
    MetricReporterCallback callback = new MetricReporterCallback(recorder, target);
    try {
      Future<Void> reportServerConnectionsCompleted = xdsClient.reportServerConnections(callback);

      ListenableFuture<Map<XdsResourceType<?>, Map<String, ResourceMetadata>>>
          getResourceMetadataCompleted = xdsClient.getSubscribedResourcesMetadataSnapshot();

      Map<XdsResourceType<?>, Map<String, ResourceMetadata>> metadataByType =
          getResourceMetadataCompleted.get(10, TimeUnit.SECONDS);

      computeAndReportResourceCounts(metadataByType, callback);

      // Normally this shouldn't take long, but adding a timeout to avoid indefinite blocking
      Void unused = reportServerConnectionsCompleted.get(5, TimeUnit.SECONDS);
    } catch (ExecutionException | TimeoutException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt(); // re-set the current thread's interruption state
      }
      logger.log(Level.WARNING, "Failed to report gauge metrics", e);
    }
  }

  private void computeAndReportResourceCounts(
      Map<XdsResourceType<?>, Map<String, ResourceMetadata>> metadataByType,
      MetricReporterCallback callback) {
    for (Map.Entry<XdsResourceType<?>, Map<String, ResourceMetadata>> metadataByTypeEntry :
        metadataByType.entrySet()) {
      XdsResourceType<?> type = metadataByTypeEntry.getKey();
      Map<String, ResourceMetadata> resources = metadataByTypeEntry.getValue();

      Map<String, Map<String, Long>> resourceCountsByAuthorityAndState = new HashMap<>();
      for (Map.Entry<String, ResourceMetadata> resourceEntry : resources.entrySet()) {
        String resourceName = resourceEntry.getKey();
        ResourceMetadata metadata = resourceEntry.getValue();
        String authority = XdsClient.getAuthorityFromResourceName(resourceName);
        String cacheState = cacheStateFromResourceStatus(metadata.getStatus(), metadata.isCached());
        resourceCountsByAuthorityAndState
            .computeIfAbsent(authority, k -> new HashMap<>())
            .merge(cacheState, 1L, Long::sum);
      }

      // Report metrics
      for (Map.Entry<String, Map<String, Long>> authorityEntry
            : resourceCountsByAuthorityAndState.entrySet()) {
        String authority = authorityEntry.getKey();
        Map<String, Long> stateCounts = authorityEntry.getValue();

        for (Map.Entry<String, Long> stateEntry : stateCounts.entrySet()) {
          String cacheState = stateEntry.getKey();
          Long count = stateEntry.getValue();

          callback.reportResourceCountGauge(count, authority, cacheState, type.typeUrl());
        }
      }
    }
  }

  private static String cacheStateFromResourceStatus(ResourceMetadataStatus metadataStatus,
      boolean isResourceCached) {
    switch (metadataStatus) {
      case REQUESTED:
        return "requested";
      case DOES_NOT_EXIST:
        return "does_not_exist";
      case ACKED:
        return "acked";
      case NACKED:
        return isResourceCached ? "nacked_but_cached" : "nacked";
      default:
        return "unknown";
    }
  }

  @VisibleForTesting
  static final class MetricReporterCallback implements ServerConnectionCallback {
    private final BatchRecorder recorder;
    private final String target;

    MetricReporterCallback(BatchRecorder recorder, String target) {
      this.recorder = recorder;
      this.target = target;
    }

    void reportResourceCountGauge(long resourceCount, String authority, String cacheState,
        String resourceType) {
      // authority = #old, for non-xdstp resource names
      recorder.recordLongGauge(RESOURCES_GAUGE, resourceCount,
          Arrays.asList(target, authority == null ? "#old" : authority, cacheState, resourceType),
          Collections.emptyList());
    }

    @Override
    public void reportServerConnectionGauge(boolean isConnected, String xdsServer) {
      recorder.recordLongGauge(CONNECTED_GAUGE, isConnected ? 1 : 0,
          Arrays.asList(target, xdsServer), Collections.emptyList());
    }
  }
}
