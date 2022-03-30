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

package io.grpc.gcp.observability.logging;

import com.google.cloud.MonitoredResource;
import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Logging;
import com.google.cloud.logging.LoggingOptions;
import com.google.cloud.logging.Payload.JsonPayload;
import com.google.cloud.logging.Severity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.util.JsonFormat;
import io.grpc.internal.JsonParser;
import io.grpc.observabilitylog.v1.GrpcLogRecord;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sink for Google Cloud Logging.
 */
public class GcpLogSink implements Sink {
  private final Logger logger = Logger.getLogger(GcpLogSink.class.getName());

  // TODO(DNVindhya): Make cloud logging service a configurable value
  private static final String SERVICE_TO_EXCLUDE = "google.logging.v2.LoggingServiceV2";
  private static final String DEFAULT_LOG_NAME = "grpc";
  private static final String K8S_MONITORED_RESOURCE_TYPE = "k8s_container";
  private static final Set<String> kubernetesResourceLabelSet
      = ImmutableSet.of("project_id", "location", "cluster_name", "namespace_name",
      "pod_name", "container_name");
  private static final int FALLBACK_FLUSH_LIMIT = 100;
  private final Map<String, String> customTags;
  private final Logging gcpLoggingClient;
  private final MonitoredResource kubernetesResource;
  private final int flushLimit;
  private int flushCounter;

  private static Logging createLoggingClient(String projectId) {
    LoggingOptions.Builder builder = LoggingOptions.newBuilder();
    if (!Strings.isNullOrEmpty(projectId)) {
      builder.setProjectId(projectId);
    }
    return builder.build().getService();
  }

  /**
   * Retrieves a single instance of GcpLogSink.
   *
   * @param destinationProjectId cloud project id to write logs
   */
  public GcpLogSink(String destinationProjectId, Map<String, String> locationTags,
      Map<String, String> customTags, int flushLimit) {
    this(createLoggingClient(destinationProjectId), locationTags, customTags, flushLimit);

  }

  @VisibleForTesting
  GcpLogSink(Logging client, Map<String, String> locationTags, Map<String, String> customTags,
      int flushLimit) {
    this.gcpLoggingClient = client;
    this.customTags = customTags != null ? customTags : new HashMap<>();
    this.kubernetesResource = getResource(locationTags);
    this.flushLimit = flushLimit != 0 ? flushLimit : FALLBACK_FLUSH_LIMIT;
    this.flushCounter = 0;
  }

  /**
   * Writes logs to GCP Cloud Logging.
   *
   * @param logProto gRPC logging proto containing the message to be logged
   */
  @Override
  public void write(GrpcLogRecord logProto) {
    if (gcpLoggingClient == null) {
      logger.log(Level.SEVERE, "Attempt to write after GcpLogSink is closed.");
      return;
    }
    if (SERVICE_TO_EXCLUDE.equals(logProto.getServiceName())) {
      return;
    }
    try {
      GrpcLogRecord.EventType event = logProto.getEventType();
      Severity logEntrySeverity = getCloudLoggingLevel(logProto.getLogLevel());
      // TODO(DNVindhya): make sure all (int, long) values are not displayed as double
      // For now, every value is being converted as string because of JsonFormat.printer().print
      LogEntry.Builder grpcLogEntryBuilder =
          LogEntry.newBuilder(JsonPayload.of(protoToMapConverter(logProto)))
              .setSeverity(logEntrySeverity)
              .setLogName(DEFAULT_LOG_NAME)
              .setResource(kubernetesResource);
      if (!customTags.isEmpty()) {
        grpcLogEntryBuilder.setLabels(customTags);
      }
      LogEntry grpcLogEntry = grpcLogEntryBuilder.build();
      synchronized (this) {
        logger.log(Level.FINEST, "Writing gRPC event : {0} to Cloud Logging", event);
        gcpLoggingClient.write(Collections.singleton(grpcLogEntry));
        flushCounter += 1;
        if (flushCounter >= flushLimit) {
          gcpLoggingClient.flush();
          flushCounter = 0;
        }
      }
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Caught exception while writing to Cloud Logging", e);
    }
  }

  @VisibleForTesting
  static MonitoredResource getResource(Map<String, String> resourceTags) {
    MonitoredResource.Builder builder = MonitoredResource.newBuilder(K8S_MONITORED_RESOURCE_TYPE);
    if ((resourceTags != null) && !resourceTags.isEmpty()) {
      for (Map.Entry<String, String> entry : resourceTags.entrySet()) {
        String resourceKey = entry.getKey();
        if (kubernetesResourceLabelSet.contains(resourceKey)) {
          builder.addLabel(resourceKey, entry.getValue());
        }
      }
    }
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> protoToMapConverter(GrpcLogRecord logProto)
      throws IOException {
    JsonFormat.Printer printer = JsonFormat.printer().preservingProtoFieldNames();
    String recordJson = printer.print(logProto);
    return (Map<String, Object>) JsonParser.parse(recordJson);
  }

  private Severity getCloudLoggingLevel(GrpcLogRecord.LogLevel recordLevel) {
    switch (recordLevel.getNumber()) {
      case 1: // GrpcLogRecord.LogLevel.LOG_LEVEL_TRACE
      case 2: // GrpcLogRecord.LogLevel.LOG_LEVEL_DEBUG
        return Severity.DEBUG;
      case 3: // GrpcLogRecord.LogLevel.LOG_LEVEL_INFO
        return Severity.INFO;
      case 4: // GrpcLogRecord.LogLevel.LOG_LEVEL_WARN
        return Severity.WARNING;
      case 5: // GrpcLogRecord.LogLevel.LOG_LEVEL_ERROR
        return Severity.ERROR;
      case 6: // GrpcLogRecord.LogLevel.LOG_LEVEL_CRITICAL
        return Severity.CRITICAL;
      default:
        return Severity.DEFAULT;
    }
  }

  /**
   * Closes Cloud Logging Client.
   */
  @Override
  public synchronized void close() {
    if (gcpLoggingClient == null) {
      logger.log(Level.WARNING, "Attempt to close after GcpLogSink is closed.");
      return;
    }
    try {
      gcpLoggingClient.close();
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Caught exception while closing", e);
    }
  }
}