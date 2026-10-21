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

package io.grpc.gcp.observability.interceptors;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import io.grpc.Internal;
import io.grpc.gcp.observability.ObservabilityConfig;
import io.grpc.gcp.observability.ObservabilityConfig.LogFilter;
import java.util.List;

/**
 * Parses gRPC GcpObservability configuration filters for interceptors usage.
 */
@Internal
public class ConfigFilterHelper {
  public static final FilterParams NO_FILTER_PARAMS
      = FilterParams.create(false, 0, 0);

  private final ObservabilityConfig config;

  private ConfigFilterHelper(ObservabilityConfig config) {
    this.config = config;
  }

  /**
   * Creates and returns helper instance for log filtering.
   *
   * @param config processed ObservabilityConfig object
   * @return helper instance for filtering
   */
  public static ConfigFilterHelper getInstance(ObservabilityConfig config) {
    return new ConfigFilterHelper(config);
  }


  /**
   * Checks if the corresponding service/method passed needs to be logged according to user provided
   * observability configuration.
   * Filters are evaluated in text order, first match is used.
   *
   * @param fullMethodName the fully qualified name of the method
   * @param client set to true if method being checked is a client method; false otherwise
   * @return FilterParams object 1. specifies if the corresponding method needs to be logged
   *     (log field will be set to true) 2. values of payload limits retrieved from configuration
   */
  public FilterParams logRpcMethod(String fullMethodName, boolean client) {
    FilterParams params = NO_FILTER_PARAMS;

    int index = checkNotNull(fullMethodName, "fullMethodName").lastIndexOf('/');
    String serviceName = fullMethodName.substring(0, index);

    List<LogFilter> logFilters =
        client ? config.getClientLogFilters() : config.getServerLogFilters();

    // TODO (dnvindhya): Optimize by caching results for fullMethodName.
    for (LogFilter logFilter : logFilters) {
      if (logFilter.matchAll
          || logFilter.services.contains(serviceName)
          || logFilter.methods.contains(fullMethodName)) {
        if (logFilter.excludePattern) {
          return params;
        }
        int currentHeaderBytes = logFilter.headerBytes;
        int currentMessageBytes = logFilter.messageBytes;
        return FilterParams.create(true, currentHeaderBytes, currentMessageBytes);
      }
    }
    return params;
  }

  /**
   * Class containing results for method/service filter information, such as flag for logging
   * method/service and payload limits to be used for filtering.
   */
  @AutoValue
  public abstract static class FilterParams {

    abstract boolean log();

    abstract int headerBytes();

    abstract int messageBytes();

    @VisibleForTesting
    public static FilterParams create(boolean log, int headerBytes, int messageBytes) {
      return new AutoValue_ConfigFilterHelper_FilterParams(
          log, headerBytes, messageBytes);
    }
  }
}
