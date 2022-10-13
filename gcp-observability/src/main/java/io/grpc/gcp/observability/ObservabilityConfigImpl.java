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

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.grpc.MethodDescriptor;
import io.grpc.internal.JsonParser;
import io.grpc.internal.JsonUtil;
import io.opencensus.trace.Sampler;
import io.opencensus.trace.samplers.Samplers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * gRPC GcpObservability configuration processor.
 */
final class ObservabilityConfigImpl implements ObservabilityConfig {
  private static final String CONFIG_ENV_VAR_NAME = "GRPC_GCP_OBSERVABILITY_CONFIG";
  private static final String CONFIG_FILE_ENV_VAR_NAME = "GRPC_GCP_OBSERVABILITY_CONFIG_FILE";
  private static final String CONFIG_LOGGING_VAR_NAME = "cloud_logging";
  private static final String CONFIG_MONITORING_VAR_NAME = "cloud_monitoring";
  private static final String CONFIG_TRACING_VAR_NAME = "cloud_tracing";
  private static final String CONFIG_LABELS_VAR_NAME = "labels";
  // Tolerance for floating-point comparisons.
  private static final double EPSILON = 1e-6;

  private static final Pattern METHOD_NAME_REGEX =
      Pattern.compile("^([\\w./]+)/((?:\\w+)|[*])$");

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
    if (config != null) {
      projectId = JsonUtil.getString(config, "project_id");
      parseLoggingObject(config, CONFIG_LOGGING_VAR_NAME);
      parseMonitoringObject(config, CONFIG_MONITORING_VAR_NAME);
      parseTracingObject(config, CONFIG_TRACING_VAR_NAME);
      parseCustomTags(config, CONFIG_LABELS_VAR_NAME);
    }
  }

  private void parseLoggingObject(Map<String, ?> config,  String jsonObjectName) {
    Map<String, ?> rawCloudLogging = JsonUtil.getObject(config, jsonObjectName);
    if (rawCloudLogging != null) {
      enableCloudLogging = true;
      List<?> rawClientRpcEvents = JsonUtil.getList(rawCloudLogging, "client_rpc_events");
      clientLogFilters =
          rawClientRpcEvents != null ? parseRpcEvents(rawClientRpcEvents) : clientLogFilters;
      List<?> rawServerRpcEvents = JsonUtil.getList(rawCloudLogging, "server_rpc_events");
      serverLogFilters =
          rawServerRpcEvents != null ? parseRpcEvents(rawServerRpcEvents) : serverLogFilters;
    }
  }

  private void parseMonitoringObject(Map<String, ?> config, String jsonObjectName) {
    Map<String, ?> rawCloudMonitoring = JsonUtil.getObject(config, jsonObjectName);
    if (rawCloudMonitoring != null) {
      if (rawCloudMonitoring.isEmpty()) {
        enableCloudMonitoring = true;
      }
    }
  }

  private void parseTracingObject(Map<String, ?> config, String jsonObjectName) {
    Map<String, ?> rawCloudTracing = JsonUtil.getObject(config, jsonObjectName);
    if (rawCloudTracing != null) {
      enableCloudTracing = true;
      this.sampler = Samplers.probabilitySampler(0.0);
      if (!rawCloudTracing.isEmpty()) {
        Double samplingRate = JsonUtil.getNumberAsDouble(rawCloudTracing, "sampling_rate");
        if (samplingRate != null) {
          checkArgument(
              samplingRate >= 0.0 && samplingRate <= 1.0,
              "'sampling_rate' needs to be between [0.0, 1.0]");
          // Using alwaysSample() instead of probabilitySampler() because according to
          // {@link io.opencensus.trace.samplers.ProbabilitySampler#shouldSample}
          // there is a (very) small chance of *not* sampling if probability = 1.00.
          this.sampler =
              1 - samplingRate < EPSILON
                  ? Samplers.alwaysSample()
                  : Samplers.probabilitySampler(samplingRate);
        }
      }
    }
  }

  private void parseCustomTags(Map<String, ?> config, String jsonObjectName) {
    Map<String, ?> rawCustomTags = JsonUtil.getObject(config, jsonObjectName);
    if (rawCustomTags != null) {
      ImmutableMap.Builder<String, String> builder = new ImmutableMap.Builder<>();
      for (Map.Entry<String, ?> entry: rawCustomTags.entrySet()) {
        checkArgument(
            entry.getValue() instanceof String,
            "'labels' needs to be a map of <string, string>");
        builder.put(entry.getKey(), (String) entry.getValue());
      }
      customTags = builder.build();
    }
  }

  private List<LogFilter> parseRpcEvents(List<?> rpcEvents) {
    List<Map<String, ?>> jsonRpcEvents = JsonUtil.checkObjectList(rpcEvents);
    ImmutableList.Builder<LogFilter> rpcEventsListBuilder =
        new ImmutableList.Builder<>();
    for (Map<String, ?> jsonClientRpcEvent : jsonRpcEvents) {
      rpcEventsListBuilder.add(parseJsonLogFilter(jsonClientRpcEvent));
    }
    return rpcEventsListBuilder.build();
  }


  private LogFilter parseJsonLogFilter(Map<String, ?> logFilterMap) {
    Boolean exclude = JsonUtil.getBoolean(logFilterMap, "exclude");
    Boolean global = false;
    ImmutableList.Builder<String> patternListBuilder =
        new ImmutableList.Builder<>();
    ImmutableSet.Builder<String> servicesSetBuilder =
        new ImmutableSet.Builder<>();
    ImmutableSet.Builder<String> methodsSetBuilder =
        new ImmutableSet.Builder<>();
    List<?> methodsList = JsonUtil.getList(logFilterMap, "methods");
    if (methodsList != null) {
      for (Object pattern : methodsList) {
        String methodOrServicePattern = String.valueOf(pattern);
        if (methodOrServicePattern != null) {
          if (methodOrServicePattern.equals("*")) {
            if (exclude != null) {
              checkArgument(
                  !exclude,
                  "cannot have 'exclude' and '*' wildcard in the same filter");
            }
            global = true;
          } else {
            checkArgument(
                METHOD_NAME_REGEX.matcher(methodOrServicePattern).matches(),
                "invalid service or method string : " + methodOrServicePattern);
            if (methodOrServicePattern.endsWith("/*")) {
              String service = MethodDescriptor.extractFullServiceName(methodOrServicePattern);
              servicesSetBuilder.add(service);
            } else {
              methodsSetBuilder.add(methodOrServicePattern);
            }
          }
        }
      }
      patternListBuilder.addAll(methodsList.stream()
              .filter(element -> element instanceof String)
              .map(element -> (String) element)
              .collect(Collectors.toList()));
    }
    return new LogFilter(patternListBuilder.build(),
        global, servicesSetBuilder.build(), methodsSetBuilder.build(),
        JsonUtil.getNumberAsInteger(logFilterMap, "max_metadata_bytes"),
        JsonUtil.getNumberAsInteger(logFilterMap, "max_message_bytes"),
        exclude);
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
