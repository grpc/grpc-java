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

import io.grpc.Internal;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import java.util.List;

@Internal
public interface ObservabilityConfig {
  /** Is Cloud Logging enabled. */
  boolean isEnableCloudLogging();

  /** Get destination project ID - where logs will go. */
  String getDestinationProjectId();

  /** Get message count threshold to flush - flush once message count is reached. */
  Long getFlushMessageCount();

  /** Get filters set for logging. */
  List<LogFilter> getLogFilters();

  /** Get event types to log. */
  List<EventType> getEventTypes();

  /**
   * POJO for representing a filter used in configuration.
   */
  public static class LogFilter {
    /** Pattern indicating which service/method to log. */
    public final String pattern;

    /** Number of bytes of each header to log. */
    public final Integer headerBytes;

    /** Number of bytes of each header to log. */
    public final Integer messageBytes;

    /**
     * Object used to represent filter used in configuration.
     *
     * @param pattern Pattern indicating which service/method to log
     * @param headerBytes Number of bytes of each header to log
     * @param messageBytes Number of bytes of each header to log
     */
    public LogFilter(String pattern, Integer headerBytes, Integer messageBytes) {
      this.pattern = pattern;
      this.headerBytes = headerBytes;
      this.messageBytes = messageBytes;
    }
  }
}
