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

package io.grpc.observability.logging;

/*import com.google.api.gax.paging.Page;*/
import com.google.cloud.MonitoredResource;
import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Logging;
import com.google.cloud.logging.LoggingOptions;
import com.google.cloud.logging.Payload.JsonPayload;
import com.google.cloud.logging.Severity;
import com.google.common.base.Strings;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.Internal;
import io.grpc.internal.JsonParser;
import io.grpc.observabilitylog.v1.GrpcLogRecord;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.logging.ErrorManager;
/*import java.util.logging.Filter;*/
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Custom logging handler that outputs logs generated using {@link java.util.logging.Logger} to
 * Cloud Logging.
 */
@Internal
public class CloudLoggingHandler extends Handler {
  private LoggingOptions loggingOptions;
  private Logging logging;
  private Level baseLevel;
  private String logName;

  /** Initialize CloudLoggingHandler. */
  public CloudLoggingHandler(Level level, String log, String projectId) {
    baseLevel = level.equals(Level.ALL) ? Level.FINEST : level;
    setLevel(baseLevel);

    String default_log_name = "o11y-test-log";
    logName = log != null ? log : default_log_name;

    try {
      /*      String overrideProjectId = config.getProjectId();*/
      String overrideProjectId = projectId != null ? projectId : null;

      if (!Strings.isNullOrEmpty(overrideProjectId)) {
        loggingOptions = LoggingOptions.newBuilder().setProjectId(overrideProjectId).build();
      } else {
        loggingOptions = LoggingOptions.getDefaultInstance();
      }

      logging = loggingOptions.getService();
    } catch (Exception ex) {
      reportError(null, ex, ErrorManager.OPEN_FAILURE);
      throw ex;
    }
  }

  @Override
  public void publish(LogRecord record) {
    if (!(record instanceof LogRecordExtension)) {
      throw new AssertionError("Incorrect log record");
    }

    GrpcLogRecord logRecord = ((LogRecordExtension) record).getGrpcLogRecord();
    Level logLevel = record.getLevel();
    writeLog(logRecord, logLevel);
  }

  private void writeLog(GrpcLogRecord logProto, Level logLevel) {
    try {
      Severity cloudLogLevel = getCloudloggingLevel(logLevel);
      Map<String, Object> payload = protoToMapConverter(logProto);

      LogEntry grpcLogEntry =
          LogEntry.newBuilder(JsonPayload.of(payload))
              .setSeverity(cloudLogLevel)
              .setLogName(logName)
              .setResource(MonitoredResource.newBuilder("global").build())
              .build();

      logging.write(Collections.singleton(grpcLogEntry));
/*      Page<LogEntry> logEntries = logging.listLogEntries();*/
    } catch (Exception ex) {
      reportError(null, ex, ErrorManager.WRITE_FAILURE);
    }
  }

  /* Google Cloud Logging API only accepts String payload or Json Payload
  Adding all the grpc o11y logging values as string makes it difficult to look up or index
  Hence we are going with the Json Payload approach
   */
  private Map<String, Object> protoToMapConverter(GrpcLogRecord logProto)
      throws InvalidProtocolBufferException, IOException {
    JsonFormat.Printer printer = JsonFormat.printer().preservingProtoFieldNames();
    String recordJson = printer.print(logProto);

    Map<String, Object> recordMap = (Map<String, Object>) JsonParser.parse(recordJson);
    return recordMap;
  }

  @Override
  public void flush() {
    logging.flush();
  }

  @Override
  public synchronized void close() throws SecurityException {
    if (logging != null) {
      try {
        logging.close();
      } catch (Exception ex) {
        reportError(null, ex, ErrorManager.CLOSE_FAILURE);
      }
    }
  }

  private Severity getCloudloggingLevel(Level recordLevel) {
    switch (recordLevel.intValue()) {
      // FINEST
      case 300:
        return Severity.DEBUG;
        // FINER
      case 400:
        return Severity.DEBUG;
        // FINE
      case 500:
        return Severity.DEBUG;
        // CONFIG
      case 700:
        return Severity.INFO;
        // INFO
      case 800:
        return Severity.INFO;
        // WARNING
      case 900:
        return Severity.WARNING;
        // SEVERE
      case 1000:
        return Severity.ERROR;
      default:
        return Severity.DEFAULT;
    }
  }
}
