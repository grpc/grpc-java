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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableList;
import io.grpc.internal.JsonParser;
import io.grpc.internal.JsonUtil;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * gRPC GcpObservability configuration processor.
 */
final class ObservabilityConfigImpl implements ObservabilityConfig {
  private static final String CONFIG_ENV_VAR_NAME = "GRPC_CONFIG_OBSERVABILITY";

  private boolean enableCloudLogging = true;
  private String destinationProjectId = null;
  private Long flushMessageCount = null;
  private List<LogFilter> logFilters;
  private List<EventType> eventTypes;

  static ObservabilityConfigImpl getInstance() throws IOException {
    ObservabilityConfigImpl config = new ObservabilityConfigImpl();
    config.parse(System.getenv(CONFIG_ENV_VAR_NAME));
    return config;
  }

  @SuppressWarnings("unchecked")
  void parse(String config) throws IOException {
    checkArgument(config != null, CONFIG_ENV_VAR_NAME + " value is null!");
    parseLoggingConfig((Map<String, ?>) JsonParser.parse(config));
  }

  private void parseLoggingConfig(Map<String, ?> loggingConfig) {
    if (loggingConfig != null) {
      Boolean value = JsonUtil.getBoolean(loggingConfig, "enable_cloud_logging");
      if (value != null) {
        enableCloudLogging = value;
      }
      destinationProjectId = JsonUtil.getString(loggingConfig, "destination_project_id");
      flushMessageCount = JsonUtil.getNumberAsLong(loggingConfig, "flush_message_count");
      List<?> rawList = JsonUtil.getList(loggingConfig, "log_filters");
      if (rawList != null) {
        List<Map<String, ?>> jsonLogFilters = JsonUtil.checkObjectList(rawList);
        ImmutableList.Builder<LogFilter> logFiltersBuilder = new ImmutableList.Builder<>();
        for (Map<String, ?> jsonLogFilter : jsonLogFilters) {
          logFiltersBuilder.add(parseJsonLogFilter(jsonLogFilter));
        }
        this.logFilters = logFiltersBuilder.build();
      }
      rawList = JsonUtil.getList(loggingConfig, "event_types");
      if (rawList != null) {
        List<String> jsonEventTypes = JsonUtil.checkStringList(rawList);
        ImmutableList.Builder<EventType> eventTypesBuilder = new ImmutableList.Builder<>();
        for (String jsonEventType : jsonEventTypes) {
          eventTypesBuilder.add(convertEventType(jsonEventType));
        }
        this.eventTypes = eventTypesBuilder.build();
      }
    }
  }

  private EventType convertEventType(String val) {
    switch (val) {
      case "GRPC_CALL_UNKNOWN":
        return EventType.GRPC_CALL_UNKNOWN;
      case "GRPC_CALL_REQUEST_HEADER":
        return EventType.GRPC_CALL_REQUEST_HEADER;
      case "GRPC_CALL_RESPONSE_HEADER":
        return EventType.GRPC_CALL_RESPONSE_HEADER;
      case "GRPC_CALL_REQUEST_MESSAGE":
        return EventType.GRPC_CALL_REQUEST_MESSAGE;
      case "GRPC_CALL_RESPONSE_MESSAGE":
        return EventType.GRPC_CALL_RESPONSE_MESSAGE;
      case "GRPC_CALL_TRAILER":
        return EventType.GRPC_CALL_TRAILER;
      case "GRPC_CALL_HALF_CLOSE":
        return EventType.GRPC_CALL_HALF_CLOSE;
      case "GRPC_CALL_CANCEL":
        return EventType.GRPC_CALL_CANCEL;
      default:
        throw new IllegalArgumentException("Unknown event type value:" + val);
    }
  }

  private LogFilter parseJsonLogFilter(Map<String, ?> logFilterMap) {
    return new LogFilter(JsonUtil.getString(logFilterMap, "pattern"),
        JsonUtil.getNumberAsInteger(logFilterMap, "header_bytes"),
        JsonUtil.getNumberAsInteger(logFilterMap, "message_bytes"));
  }

  @Override
  public boolean isEnableCloudLogging() {
    return enableCloudLogging;
  }

  @Override
  public String getDestinationProjectId() {
    return destinationProjectId;
  }

  @Override
  public Long getFlushMessageCount() {
    return flushMessageCount;
  }

  @Override
  public List<LogFilter> getLogFilters() {
    return logFilters;
  }

  @Override
  public List<EventType> getEventTypes() {
    return eventTypes;
  }
}
