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

  public static class LogFilter {
    public String pattern;
    public Integer headerBytes;
    public Integer messageBytes;
  }

  ObservabilityConfig() {
  }

  void read() throws IOException {
    parse(System.getenv(CONFIG_ENV_VAR_NAME));
  }

  void parse(String config) throws IOException {
    checkArgument(config != null, CONFIG_ENV_VAR_NAME + " value is null!");
    Map<String, ?> map = (Map<String, ?>) JsonParser.parse(config);

    parseLoggingConfig(JsonUtil.getObject(map, "logging_config"));
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
        List<Map<String, ?>> logFilters = JsonUtil.checkObjectList(rawList);
        this.logFilters = new LogFilter[logFilters.size()];
        for (int i = 0; i < logFilters.size(); i++) {
          this.logFilters[i] = parseLogFilter(logFilters.get(i));
        }
      }
      rawList = JsonUtil.getList(loggingConfig, "event_types");
      if (rawList != null) {
        List<String> enumTypes = JsonUtil.checkStringList(rawList);
        this.eventTypes = new GrpcLogRecord.EventType[enumTypes.size()];
        for (int i = 0; i < enumTypes.size(); i++) {
          this.eventTypes[i] = convert(enumTypes.get(i));
        }
      }
    }
  }

  private GrpcLogRecord.EventType convert(String val) {
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

  private LogFilter parseLogFilter(Map<String,?> map) {
    LogFilter logFilter = new LogFilter();
    logFilter.pattern = JsonUtil.getString(map, "pattern");
    logFilter.headerBytes = JsonUtil.getNumberAsInteger(map, "header_bytes");
    logFilter.messageBytes = JsonUtil.getNumberAsInteger(map, "message_bytes");
    return logFilter;
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
