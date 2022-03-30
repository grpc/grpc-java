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

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.grpc.Internal;
import io.grpc.MethodDescriptor;
import io.grpc.gcp.observability.ObservabilityConfig;
import io.grpc.gcp.observability.ObservabilityConfig.LogFilter;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Parses gRPC GcpObservability configuration filters for interceptors usage.
 */
@Internal
public class ConfigFilterHelper {

  private static final Logger logger = Logger.getLogger(ConfigFilterHelper.class.getName());

  public static final FilterParams NO_FILTER_PARAMS
      = FilterParams.create(false, 0, 0);
  public static final String globalPattern = "*";

  private final ObservabilityConfig config;
  @VisibleForTesting
  boolean methodOrServiceFilterPresent;
  // Flag to log every service and method
  @VisibleForTesting
  Map<String, FilterParams> perServiceFilters;
  @VisibleForTesting
  Map<String, FilterParams> perMethodFilters;
  @VisibleForTesting
  Set<EventType> logEventTypeSet;

  @VisibleForTesting
  ConfigFilterHelper(ObservabilityConfig config) {
    this.config = config;
    this.methodOrServiceFilterPresent = false;
    this.perServiceFilters = new HashMap<>();
    this.perMethodFilters = new HashMap<>();
  }

  /**
   * Creates and returns helper instance for log filtering.
   *
   * @param config processed ObservabilityConfig object
   * @return helper instance for filtering
   */
  public static ConfigFilterHelper factory(ObservabilityConfig config) {
    ConfigFilterHelper filterHelper = new ConfigFilterHelper(config);
    if (config.isEnableCloudLogging()) {
      filterHelper.setMethodOrServiceFilterMaps();
      filterHelper.setEventFilterSet();
    }
    return filterHelper;
  }

  @VisibleForTesting
  void setMethodOrServiceFilterMaps() {
    List<LogFilter> logFilters = config.getLogFilters();
    if (logFilters == null) {
      return;
    }

    Map<String, FilterParams> perServiceFilters = new HashMap<>();
    Map<String, FilterParams> perMethodFilters = new HashMap<>();

    for (LogFilter currentFilter : logFilters) {
      // '*' for global, 'service/*' for service glob, or 'service/method' for fully qualified
      String methodOrServicePattern = currentFilter.pattern;
      int currentHeaderBytes
          = currentFilter.headerBytes != null ? currentFilter.headerBytes : 0;
      int currentMessageBytes
          = currentFilter.messageBytes != null ? currentFilter.messageBytes : 0;
      if (methodOrServicePattern.equals("*")) {
        // parse config for global, e.g. "*"
        if (perServiceFilters.containsKey(globalPattern)) {
          logger.log(Level.WARNING, "Duplicate entry : {0}", methodOrServicePattern);
          continue;
        }
        FilterParams params = FilterParams.create(true,
            currentHeaderBytes, currentMessageBytes);
        perServiceFilters.put(globalPattern, params);
      } else if (methodOrServicePattern.endsWith("/*")) {
        // TODO(DNVindhya): check if service name is a valid string for a service name
        // parse config for a service, e.g. "service/*"
        String service = MethodDescriptor.extractFullServiceName(methodOrServicePattern);
        if (perServiceFilters.containsKey(service)) {
          logger.log(Level.WARNING, "Duplicate entry : {0)", methodOrServicePattern);
          continue;
        }
        FilterParams params = FilterParams.create(true,
            currentHeaderBytes, currentMessageBytes);
        perServiceFilters.put(service, params);
      } else {
        // TODO(DNVVindhya): check if methodOrServicePattern is a valid full qualified method name
        // parse pattern for a fully qualified method, e.g "service/method"
        if (perMethodFilters.containsKey(methodOrServicePattern)) {
          logger.log(Level.WARNING, "Duplicate entry : {0}", methodOrServicePattern);
          continue;
        }
        FilterParams params = FilterParams.create(true,
            currentHeaderBytes, currentMessageBytes);
        perMethodFilters.put(methodOrServicePattern, params);
      }
    }
    this.perServiceFilters = ImmutableMap.copyOf(perServiceFilters);
    this.perMethodFilters = ImmutableMap.copyOf(perMethodFilters);
    if (!perServiceFilters.isEmpty() || !perMethodFilters.isEmpty()) {
      this.methodOrServiceFilterPresent = true;
    }
  }

  @VisibleForTesting
  void setEventFilterSet() {
    List<EventType> eventFilters = config.getEventTypes();
    if (eventFilters == null) {
      return;
    }
    if (eventFilters.isEmpty()) {
      this.logEventTypeSet = ImmutableSet.of();
      return;
    }
    this.logEventTypeSet = ImmutableSet.copyOf(eventFilters);
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

  /**
   * Checks if the corresponding service/method passed needs to be logged as per the user provided
   * configuration.
   *
   * @param method the fully qualified name of the method
   * @return MethodFilterParams object 1. specifies if the corresponding method needs to be logged
   *     (log field will be set to true) 2. values of payload limits retrieved from configuration
   */
  public FilterParams isMethodToBeLogged(MethodDescriptor<?, ?> method) {
    FilterParams params = NO_FILTER_PARAMS;
    if (methodOrServiceFilterPresent) {
      String fullMethodName = method.getFullMethodName();
      if (perMethodFilters.containsKey(fullMethodName)) {
        params = perMethodFilters.get(fullMethodName);
      } else {
        String serviceName = method.getServiceName();
        if (perServiceFilters.containsKey(serviceName)) {
          params = perServiceFilters.get(serviceName);
        } else if (perServiceFilters.containsKey(globalPattern)) {
          params = perServiceFilters.get(globalPattern);
        }
      }
    }
    return params;
  }

  /**
   * Checks if the corresponding event passed needs to be logged as per the user provided
   * configuration.
   *
   * <p> All events are logged by default if event_types is not specified or {} in configuration.
   * If event_types is specified as [], no events will be logged.
   * If events types is specified as a non-empty list, only the events specified in the
   * list will be logged.
   * </p>
   *
   * @param event gRPC observability event
   * @return true if event needs to be logged, false otherwise
   */
  public boolean isEventToBeLogged(EventType event) {
    if (logEventTypeSet == null) {
      return true;
    }
    boolean logEvent;
    if (logEventTypeSet.isEmpty()) {
      logEvent = false;
    } else {
      logEvent = logEventTypeSet.contains(event);
    }
    return logEvent;
  }
}
