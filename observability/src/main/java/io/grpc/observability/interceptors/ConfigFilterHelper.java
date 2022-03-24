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

package io.grpc.observability.interceptors;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.UnmodifiableListIterator;
import io.grpc.MethodDescriptor;
import io.grpc.observability.ObservabilityConfig;
import io.grpc.observability.ObservabilityConfig.LogFilter;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ConfigFilterHelper {
  private static final Logger logger = Logger.getLogger(ConfigFilterHelper.class.getName());

  private static final MethodFilterParams NO_FILTER_PARAMS
      = new MethodFilterParams(false, 0, 0);

  private final ObservabilityConfig config;
  boolean methodOrServiceFilterPresent;
  boolean globalLog;
  MethodFilterParams globalParams;
  Map<String, MethodFilterParams> perServiceFilters;
  Map<String, MethodFilterParams> perMethodFilters;
  Set<EventType> logEventTypeSet;

  @VisibleForTesting
  ConfigFilterHelper(ObservabilityConfig config) {
    this.config = config;
  }

  /**
   * Creates and returns helper instance for log filtering.
   *
   * @param config processed ObservabilityConfig object
   * @return helper instance for filtering
   */
  public static ConfigFilterHelper getInstance(ObservabilityConfig config) {
    ConfigFilterHelper filterHelper = new ConfigFilterHelper(config);
    filterHelper.setEmptyFilter();
    if (config.isEnableCloudLogging()) {
      filterHelper.setMethodOrServiceFilterMaps();
      filterHelper.setEventFilterSet();
    }
    return filterHelper;
  }

  @VisibleForTesting
  void setEmptyFilter() {
    this.globalLog = false;
    this.globalParams = NO_FILTER_PARAMS;
    this.methodOrServiceFilterPresent = false;
    this.perServiceFilters = new HashMap<>();
    this.perMethodFilters = new HashMap<>();
    this.logEventTypeSet = ImmutableSet.of();
    return;
  }

  @VisibleForTesting
  void setMethodOrServiceFilterMaps() {
    ImmutableList<LogFilter> logFilters = config.getLogFilters();
    if (logFilters == null) {
      return;
    }

    boolean globalLog = false;
    MethodFilterParams globalParams = null;
    Map<String, MethodFilterParams> perServiceFilters = new HashMap<>();
    Map<String, MethodFilterParams> perMethodFilters = new HashMap<>();

    UnmodifiableListIterator<LogFilter> logFilterIterator = logFilters.listIterator();
    while (logFilterIterator.hasNext()) {
      // '*' for global, 'service/*' for service glob, or 'service/method' for fully qualified
      LogFilter currentFilter = logFilterIterator.next();
      String methodOrServicePattern = currentFilter.pattern;
      int currentHeaderBytes
          = currentFilter.headerBytes != null ? currentFilter.headerBytes : 0;
      int currentMessageBytes
          = currentFilter.messageBytes != null ? currentFilter.messageBytes : 0;
      if (methodOrServicePattern.equals("*")) {
        // parse config for global, e.g. "*"
        globalLog = true;
        globalParams = new MethodFilterParams(true,
            currentHeaderBytes, currentMessageBytes);
      } else if (isServiceGlob(methodOrServicePattern)) {
        // parse config for a service, e.g. "service/*"
        String service = MethodDescriptor.extractFullServiceName(methodOrServicePattern);
        if (perServiceFilters.containsKey(service)) {
          logger.log(Level.WARNING, "Duplicate entry : {0)", methodOrServicePattern);
          continue;
        }
        MethodFilterParams params = new MethodFilterParams(true,
            currentHeaderBytes, currentMessageBytes);
        perServiceFilters.put(service, params);
      } else {
        // parse pattern for a fully qualified method, e.g "service/method"
        if (perMethodFilters.containsKey(methodOrServicePattern)) {
          logger.log(Level.WARNING, "Duplicate entry : {0}", methodOrServicePattern);
          continue;
        }
        MethodFilterParams params = new MethodFilterParams(true,
            currentHeaderBytes, currentMessageBytes);
        perMethodFilters.put(methodOrServicePattern, params);
      }
    }
    this.globalLog = globalLog;
    this.globalParams = globalParams;
    this.perServiceFilters = ImmutableMap.copyOf(perServiceFilters);
    this.perMethodFilters = ImmutableMap.copyOf(perMethodFilters);
    this.methodOrServiceFilterPresent = true;
  }

  @VisibleForTesting
  void setEventFilterSet() {
    ImmutableList<EventType> eventFilters = config.getEventTypes();
    if (eventFilters == null) {
      return;
    }
    this.logEventTypeSet = ImmutableSet.copyOf(eventFilters);
  }

  static boolean isServiceGlob(String input) {
    return input.endsWith("/*");
  }

  public static final class MethodFilterParams {
    boolean log;
    int headerBytes;
    int messageBytes;

    /**
     * Object used to represent results for service/method filtering.
     *
     * @param log logs the corresponding method if set to true
     * @param headerBytes header bytes limit specified by the config for the method/pattern
     * @param messageBytes message bytes limit specified by the config for the method/pattern
     */
    public MethodFilterParams(boolean log, int headerBytes, int messageBytes) {
      this.log = log;
      this.headerBytes = headerBytes;
      this.messageBytes = messageBytes;
    }
  }

  /**
   * Checks if the corresponding service/method passed needs to be logged as per the user
   * provided configuration.
   *
   * @param fullMethodName the fully qualified name of the method
   * @return MethodFilterParams object
   *     1. specifies if the corresponding method needs to be logged (log field will be set to true)
   *     2. values of payload limits retrieved from configuration
   */
  public MethodFilterParams isMethodToBeLogged(String fullMethodName) {
    if (methodOrServiceFilterPresent) {
      if (!perMethodFilters.isEmpty() && perMethodFilters.containsKey(fullMethodName)) {
        return perMethodFilters.get(fullMethodName);
      }
      String serviceName = MethodDescriptor.extractFullServiceName(fullMethodName);
      if (!perServiceFilters.isEmpty() && perServiceFilters.containsKey(serviceName)) {
        return perServiceFilters.get(serviceName);
      }
      if (globalLog) {
        return globalParams;
      }
    }
    return NO_FILTER_PARAMS;
  }

  boolean isEventToBeLogged(EventType event) {
    return logEventTypeSet.contains(event);
  }
}
