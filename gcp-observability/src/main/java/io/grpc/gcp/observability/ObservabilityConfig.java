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
import io.opencensus.trace.Sampler;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.concurrent.ThreadSafe;

@Internal
public interface ObservabilityConfig {
  /** Is Cloud Logging enabled. */
  boolean isEnableCloudLogging();

  /** Is Cloud Monitoring enabled. */
  boolean isEnableCloudMonitoring();

  /** Is Cloud Tracing enabled. */
  boolean isEnableCloudTracing();

  /** Get project ID - where logs will go. */
  String getProjectId();

  /** Get filters for client logging. */
  List<LogFilter> getClientLogFilters();

  /** Get filters for server logging. */
  List<LogFilter> getServerLogFilters();

  /** Get sampler for TraceConfig - when Cloud Tracing is enabled. */
  Sampler getSampler();

  /** Map of all custom tags used for logging, metrics and traces. */
  Map<String, String> getCustomTags();

  /**
   * POJO for representing a filter used in configuration.
   */
  @ThreadSafe
  class LogFilter {
    /** Set of services. */
    public final Set<String> services;

    /* Set of fullMethodNames. */
    public final Set<String> methods;

    /** Boolean to indicate all services and methods. */
    public final boolean matchAll;

    /** Number of bytes of header to log. */
    public final int headerBytes;

    /** Number of bytes of message to log. */
    public final int messageBytes;

    /** Boolean to indicate if services and methods matching pattern needs to be excluded. */
    public final boolean excludePattern;

    /**
     * Object used to represent filter used in configuration.
     * @param services Set of services derived from pattern
     * @param serviceMethods Set of fullMethodNames derived from pattern
     * @param matchAll If true, match all services and methods
     * @param headerBytes Total number of bytes of header to log
     * @param messageBytes Total number of bytes of  message to log
     * @param excludePattern If true, services and methods matching pattern be excluded
     */
    public LogFilter(Set<String> services, Set<String> serviceMethods, boolean matchAll,
        int headerBytes, int messageBytes,
        boolean excludePattern) {
      this.services = services;
      this.methods = serviceMethods;
      this.matchAll = matchAll;
      this.headerBytes = headerBytes;
      this.messageBytes = messageBytes;
      this.excludePattern = excludePattern;
    }
  }
}
