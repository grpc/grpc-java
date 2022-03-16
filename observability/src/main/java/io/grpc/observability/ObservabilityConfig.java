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

package io.grpc.observability;

import static com.google.common.base.Preconditions.checkArgument;

import io.grpc.internal.JsonParser;
import io.grpc.internal.JsonUtil;
import io.grpc.observabilitylog.v1.GrpcLogRecord;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** gRPC Observability configuration processor. */
final class ObservabilityConfig {
  private static final String CONFIG_ENV_VAR_NAME = "GRPC_CONFIG_OBSERVABILITY";

  private boolean enableCloudLogging = true;
  private String destinationProjectId = null;
  private LogFilter[] logFilters;
  private GrpcLogRecord.EventType[] eventTypes;

  /** POJO for representing a filter used in configuration. */
  static class LogFilter {
    /** Pattern indicating which service/method to log. */
    public final String pattern;

    /** Number of bytes of each header to log.  */
    public final Integer headerBytes;

    /** Number of bytes of each header to log. */
    public final Integer messageBytes;

    LogFilter(String pattern, Integer headerBytes, Integer messageBytes) {
      this.pattern = pattern;
      this.headerBytes = headerBytes;
      this.messageBytes = messageBytes;
    }
  }

  static ObservabilityConfig getInstance() throws IOException {
    ObservabilityConfig config = new ObservabilityConfig();
    config.parse(System.getenv(CONFIG_ENV_VAR_NAME));
    return config;
  }

  @SuppressWarnings("unchecked")
  void parse(String config) throws IOException {
    checkArgument(config != null, CONFIG_ENV_VAR_NAME + " value is null!");
    parseLoggingConfig(
        JsonUtil.getObject((Map<String, ?>) JsonParser.parse(config), "logging_config"));
  }

  private void parseLoggingConfig(Map<String,?> loggingConfig) {
    if (loggingConfig != null) {
      Boolean value = JsonUtil.getBoolean(loggingConfig, "enable_cloud_logging");
      if (value != null) {
        enableCloudLogging = value;
      }
      destinationProjectId = JsonUtil.getString(loggingConfig, "destination_project_id");
      List<?> rawList = JsonUtil.getList(loggingConfig, "log_filters");
      if (rawList != null) {
        List<Map<String, ?>> jsonLogFilters = JsonUtil.checkObjectList(rawList);
        this.logFilters = new LogFilter[jsonLogFilters.size()];
        for (int i = 0; i < jsonLogFilters.size(); i++) {
          this.logFilters[i] = parseJsonLogFilter(jsonLogFilters.get(i));
        }
      }
      rawList = JsonUtil.getList(loggingConfig, "event_types");
      if (rawList != null) {
        List<String> jsonEventTypes = JsonUtil.checkStringList(rawList);
        this.eventTypes = new GrpcLogRecord.EventType[jsonEventTypes.size()];
        for (int i = 0; i < jsonEventTypes.size(); i++) {
          this.eventTypes[i] = convertEventType(jsonEventTypes.get(i));
        }
      }
    }
  }

  private GrpcLogRecord.EventType convertEventType(String val) {
    switch (val) {
      case "GRPC_CALL_UNKNOWN":
        return GrpcLogRecord.EventType.GRPC_CALL_UNKNOWN;
      case "GRPC_CALL_REQUEST_HEADER":
        return GrpcLogRecord.EventType.GRPC_CALL_REQUEST_HEADER;
      case "GRPC_CALL_RESPONSE_HEADER":
        return GrpcLogRecord.EventType.GRPC_CALL_RESPONSE_HEADER;
      case"GRPC_CALL_REQUEST_MESSAGE":
        return GrpcLogRecord.EventType.GRPC_CALL_REQUEST_MESSAGE;
      case "GRPC_CALL_RESPONSE_MESSAGE":
        return GrpcLogRecord.EventType.GRPC_CALL_RESPONSE_MESSAGE;
      case "GRPC_CALL_TRAILER":
        return GrpcLogRecord.EventType.GRPC_CALL_TRAILER;
      case "GRPC_CALL_HALF_CLOSE":
        return GrpcLogRecord.EventType.GRPC_CALL_HALF_CLOSE;
      case "GRPC_CALL_CANCEL":
        return GrpcLogRecord.EventType.GRPC_CALL_CANCEL;
      default:
        throw new IllegalArgumentException("Unknown event type value:" + val);
    }
  }

  private LogFilter parseJsonLogFilter(Map<String,?> logFilterMap) {
    return new LogFilter(JsonUtil.getString(logFilterMap, "pattern"),
        JsonUtil.getNumberAsInteger(logFilterMap, "header_bytes"),
        JsonUtil.getNumberAsInteger(logFilterMap, "message_bytes"));
  }

  public boolean isEnableCloudLogging() {
    return enableCloudLogging;
  }

  public String getDestinationProjectId() {
    return destinationProjectId;
  }

  public LogFilter[] getLogFilters() {
    return logFilters;
  }

  public EventType[] getEventTypes() {
    return eventTypes;
  }
}
