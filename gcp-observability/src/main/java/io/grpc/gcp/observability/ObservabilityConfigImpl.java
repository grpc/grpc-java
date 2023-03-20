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

import com.google.cloud.ServiceOptions;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.grpc.internal.JsonParser;
import io.grpc.internal.JsonUtil;
import io.opencensus.trace.Sampler;
import io.opencensus.trace.samplers.Samplers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * gRPC GcpObservability configuration processor.
 */
final class ObservabilityConfigImpl implements ObservabilityConfig {
  private static final Logger logger = Logger
      .getLogger(ObservabilityConfigImpl.class.getName());
  private static final String CONFIG_ENV_VAR_NAME = "GRPC_GCP_OBSERVABILITY_CONFIG";
  private static final String CONFIG_FILE_ENV_VAR_NAME = "GRPC_GCP_OBSERVABILITY_CONFIG_FILE";
  // Tolerance for floating-point comparisons.
  private static final double EPSILON = 1e-6;

  private static final Pattern METHOD_NAME_REGEX =
      Pattern.compile("^([*])|((([\\w.]+)/((?:\\w+)|[*])))$");

  private boolean enableCloudLogging = false;
  private boolean enableCloudMonitoring = false;
  private boolean enableCloudTracing = false;
  private String projectId = null;

  private List<LogFilter> clientLogFilters;
  private List<LogFilter> serverLogFilters;
  private Sampler sampler;
  private Map<String, String> customTags;

  static ObservabilityConfigImpl getInstance() throws IOException {
    ObservabilityConfigImpl config = new ObservabilityConfigImpl();
    String configFile = System.getenv(CONFIG_FILE_ENV_VAR_NAME);
    if (configFile != null) {
      config.parseFile(configFile);
    } else {
      config.parse(System.getenv(CONFIG_ENV_VAR_NAME));
    }
    return config;
  }

  void parseFile(String configFile) throws IOException {
    String configFileContent =
        new String(Files.readAllBytes(Paths.get(configFile)), Charsets.UTF_8);
    checkArgument(!configFileContent.isEmpty(), CONFIG_FILE_ENV_VAR_NAME + " is empty!");
    parse(configFileContent);
  }

  @SuppressWarnings("unchecked")
  void parse(String config) throws IOException {
    checkArgument(config != null, CONFIG_ENV_VAR_NAME + " value is null!");
    parseConfig((Map<String, ?>) JsonParser.parse(config));
  }

  private void parseConfig(Map<String, ?> config) {
    checkArgument(config != null, "Invalid configuration");
    if (config.isEmpty()) {
      clientLogFilters = Collections.emptyList();
      serverLogFilters = Collections.emptyList();
      customTags = Collections.emptyMap();
      return;
    }
    projectId = fetchProjectId(JsonUtil.getString(config, "project_id"));

    Map<String, ?> rawCloudLoggingObject = JsonUtil.getObject(config, "cloud_logging");
    if (rawCloudLoggingObject != null) {
      enableCloudLogging = true;
      ImmutableList.Builder<LogFilter> clientFiltersBuilder = new ImmutableList.Builder<>();
      ImmutableList.Builder<LogFilter> serverFiltersBuilder = new ImmutableList.Builder<>();
      parseLoggingObject(rawCloudLoggingObject, clientFiltersBuilder, serverFiltersBuilder);
      clientLogFilters = clientFiltersBuilder.build();
      serverLogFilters = serverFiltersBuilder.build();
    }

    Map<String, ?> rawCloudMonitoringObject = JsonUtil.getObject(config, "cloud_monitoring");
    if (rawCloudMonitoringObject != null) {
      enableCloudMonitoring = true;
    }

    Map<String, ?> rawCloudTracingObject = JsonUtil.getObject(config, "cloud_trace");
    if (rawCloudTracingObject != null) {
      enableCloudTracing = true;
      sampler = parseTracingObject(rawCloudTracingObject);
    }

    Map<String, ?> rawCustomTagsObject = JsonUtil.getObject(config, "labels");
    if (rawCustomTagsObject != null) {
      customTags = parseCustomTags(rawCustomTagsObject);
    }

    if (clientLogFilters == null) {
      clientLogFilters = Collections.emptyList();
    }
    if (serverLogFilters == null) {
      serverLogFilters = Collections.emptyList();
    }
    if (customTags == null) {
      customTags = Collections.emptyMap();
    }
  }

  private static String fetchProjectId(String configProjectId) {
    // If project_id is not specified in config, get default GCP project id from the environment
    String projectId = configProjectId != null ? configProjectId : getDefaultGcpProjectId();
    checkArgument(projectId != null, "Unable to detect project_id");
    logger.log(Level.FINEST, "Found project ID : ", projectId);
    return projectId;
  }

  private static String getDefaultGcpProjectId() {
    return ServiceOptions.getDefaultProjectId();
  }

  private static void parseLoggingObject(
      Map<String, ?> rawLoggingConfig,
      ImmutableList.Builder<LogFilter> clientFilters,
      ImmutableList.Builder<LogFilter> serverFilters) {
    parseRpcEvents(JsonUtil.getList(rawLoggingConfig, "client_rpc_events"), clientFilters);
    parseRpcEvents(JsonUtil.getList(rawLoggingConfig, "server_rpc_events"), serverFilters);
  }

  private static Sampler parseTracingObject(Map<String, ?> rawCloudTracingConfig) {
    Sampler defaultSampler = Samplers.probabilitySampler(0.0);
    Double samplingRate = JsonUtil.getNumberAsDouble(rawCloudTracingConfig, "sampling_rate");
    if (samplingRate == null) {
      return defaultSampler;
    }
    checkArgument(samplingRate >= 0.0 && samplingRate <= 1.0,
        "'sampling_rate' needs to be between [0.0, 1.0]");
    // Using alwaysSample() instead of probabilitySampler() because according to
    // {@link io.opencensus.trace.samplers.ProbabilitySampler#shouldSample}
    // there is a (very) small chance of *not* sampling if probability = 1.00.
    return 1 - samplingRate < EPSILON ? Samplers.alwaysSample()
        : Samplers.probabilitySampler(samplingRate);
  }

  private static Map<String, String> parseCustomTags(Map<String, ?> rawCustomTags) {
    ImmutableMap.Builder<String, String> builder = new ImmutableMap.Builder<>();
    for (Map.Entry<String, ?> entry: rawCustomTags.entrySet()) {
      checkArgument(
          entry.getValue() instanceof String,
          "'labels' needs to be a map of <string, string>");
      builder.put(entry.getKey(), (String) entry.getValue());
    }
    return builder.build();
  }

  private static void parseRpcEvents(List<?> rpcEvents, ImmutableList.Builder<LogFilter> filters) {
    if (rpcEvents == null) {
      return;
    }
    List<Map<String, ?>> jsonRpcEvents = JsonUtil.checkObjectList(rpcEvents);
    for (Map<String, ?> jsonClientRpcEvent : jsonRpcEvents) {
      filters.add(parseJsonLogFilter(jsonClientRpcEvent));
    }
  }

  private static LogFilter parseJsonLogFilter(Map<String, ?> logFilterMap) {
    ImmutableSet.Builder<String> servicesSetBuilder = new ImmutableSet.Builder<>();
    ImmutableSet.Builder<String> methodsSetBuilder = new ImmutableSet.Builder<>();
    boolean wildCardFilter = false;

    boolean excludeFilter =
        Boolean.TRUE.equals(JsonUtil.getBoolean(logFilterMap, "exclude"));
    List<String> methodsList = JsonUtil.getListOfStrings(logFilterMap, "methods");
    if (methodsList != null) {
      wildCardFilter = extractMethodOrServicePattern(
              methodsList, excludeFilter, servicesSetBuilder, methodsSetBuilder);
    }
    Integer maxHeaderBytes = JsonUtil.getNumberAsInteger(logFilterMap, "max_metadata_bytes");
    Integer maxMessageBytes = JsonUtil.getNumberAsInteger(logFilterMap, "max_message_bytes");

    return new LogFilter(
        servicesSetBuilder.build(),
        methodsSetBuilder.build(),
        wildCardFilter,
        maxHeaderBytes != null ? maxHeaderBytes.intValue() : 0,
        maxMessageBytes != null ? maxMessageBytes.intValue() : 0,
        excludeFilter);
  }

  private static boolean extractMethodOrServicePattern(List<String> patternList, boolean exclude,
      ImmutableSet.Builder<String> servicesSetBuilder,
      ImmutableSet.Builder<String> methodsSetBuilder) {
    boolean globalFilter = false;
    for (String methodOrServicePattern : patternList) {
      Matcher matcher = METHOD_NAME_REGEX.matcher(methodOrServicePattern);
      checkArgument(
          matcher.matches(), "invalid service or method filter : " + methodOrServicePattern);
      if ("*".equals(methodOrServicePattern)) {
        checkArgument(!exclude, "cannot have 'exclude' and '*' wildcard in the same filter");
        globalFilter = true;
      } else if ("*".equals(matcher.group(5))) {
        String service = matcher.group(4);
        servicesSetBuilder.add(service);
      } else {
        methodsSetBuilder.add(methodOrServicePattern);
      }
    }
    return globalFilter;
  }

  @Override
  public boolean isEnableCloudLogging() {
    return enableCloudLogging;
  }

  @Override
  public boolean isEnableCloudMonitoring() {
    return enableCloudMonitoring;
  }

  @Override
  public boolean isEnableCloudTracing() {
    return enableCloudTracing;
  }

  @Override
  public String getProjectId() {
    return projectId;
  }

  @Override
  public List<LogFilter> getClientLogFilters() {
    return clientLogFilters;
  }

  @Override
  public List<LogFilter> getServerLogFilters() {
    return serverLogFilters;
  }

  @Override
  public Sampler getSampler() {
    return sampler;
  }

  @Override
  public Map<String, String> getCustomTags() {
    return customTags;
  }
}
