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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.base.Charsets;
import io.grpc.gcp.observability.ObservabilityConfig.LogFilter;
import io.opencensus.trace.Sampler;
import io.opencensus.trace.samplers.Samplers;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ObservabilityConfigImplTest {
  private static final String LOG_FILTERS = "{\n"
      + "    \"project_id\": \"grpc-testing\",\n"
      + "    \"cloud_logging\": {\n"
      + "    \"client_rpc_events\": [{\n"
      + "        \"methods\": [\"*\"],\n"
      + "        \"max_metadata_bytes\": 4096\n"
      + "    }"
      + "    ],\n"
      + "    \"server_rpc_events\": [{\n"
      + "        \"methods\": [\"*\"],\n"
      + "        \"max_metadata_bytes\": 32,\n"
      + "        \"max_message_bytes\": 64\n"
      + "    }"
      + "    ]\n"
      + "    }\n"
      + "}";

  private static final String CLIENT_LOG_FILTERS = "{\n"
      + "    \"project_id\": \"grpc-testing\",\n"
      + "    \"cloud_logging\": {\n"
      + "    \"client_rpc_events\": [{\n"
      + "        \"methods\": [\"*\"],\n"
      + "        \"max_metadata_bytes\": 4096,\n"
      + "        \"max_message_bytes\": 2048\n"
      + "    },"
      + "   {\n"
      + "        \"methods\": [\"service1/Method2\", \"Service2/*\"],\n"
      + "        \"exclude\": true\n"
      + "    }"
      + "    ]\n"
      + "    }\n"
      + "}";

  private static final String SERVER_LOG_FILTERS = "{\n"
          + "    \"project_id\": \"grpc-testing\",\n"
          + "    \"cloud_logging\": {\n"
          + "    \"server_rpc_events\": [{\n"
          + "        \"methods\": [\"service1/method4\", \"service2/method234\"],\n"
          + "        \"max_metadata_bytes\": 32,\n"
          + "        \"max_message_bytes\": 64\n"
          + "    },"
          + "   {\n"
          + "        \"methods\": [\"service4/*\", \"Service2/*\"],\n"
          + "        \"exclude\": true\n"
          + "    }"
          + "    ]\n"
          + "    }\n"
          + "}";

  private static final String VALID_LOG_FILTERS = "{\n"
      + "    \"project_id\": \"grpc-testing\",\n"
      + "    \"cloud_logging\": {\n"
      + "    \"server_rpc_events\": [{\n"
      + "        \"methods\": [\"service.Service1/*\", \"service2.Service4/method4\"],\n"
      + "        \"max_metadata_bytes\": 16,\n"
      + "        \"max_message_bytes\": 64\n"
      + "    }"
      + "    ]\n"
      + "    }\n"
      + "}";


  private static final String PROJECT_ID = "{\n"
      + "    \"project_id\": \"grpc-testing\",\n"
      + "    \"cloud_logging\": {},\n"
      + "    \"project_id\": \"grpc-testing\"\n"
      + "}";

  private static final String EMPTY_CONFIG = "{}";

  private static final String ENABLE_CLOUD_MONITORING_AND_TRACING = "{\n"
      + "    \"project_id\": \"grpc-testing\",\n"
      + "    \"cloud_monitoring\": {},\n"
      + "    \"cloud_trace\": {}\n"
      + "}";

  private static final String ENABLE_CLOUD_MONITORING = "{\n"
      + "    \"project_id\": \"grpc-testing\",\n"
      + "    \"cloud_monitoring\": {}\n"
      + "}";

  private static final String ENABLE_CLOUD_TRACE = "{\n"
      + "    \"project_id\": \"grpc-testing\",\n"
      + "    \"cloud_trace\": {}\n"
      + "}";

  private static final String TRACING_ALWAYS_SAMPLER = "{\n"
      + "    \"project_id\": \"grpc-testing\",\n"
      + "    \"cloud_trace\": {\n"
      + "      \"sampling_rate\": 1.00\n"
      + "    }\n"
      + "}";

  private static final String TRACING_NEVER_SAMPLER = "{\n"
      + "    \"project_id\": \"grpc-testing\",\n"
      + "    \"cloud_trace\": {\n"
      + "      \"sampling_rate\": 0.00\n"
      + "    }\n"
      + "}";

  private static final String TRACING_PROBABILISTIC_SAMPLER = "{\n"
      + "    \"project_id\": \"grpc-testing\",\n"
      + "    \"cloud_trace\": {\n"
      + "      \"sampling_rate\": 0.75\n"
      + "    }\n"
      + "}";

  private static final String TRACING_DEFAULT_SAMPLER = "{\n"
      + "    \"project_id\": \"grpc-testing\",\n"
      + "    \"cloud_trace\": {}\n"
      + "}";

  private static final String GLOBAL_TRACING_BAD_PROBABILISTIC_SAMPLER = "{\n"
      + "    \"project_id\": \"grpc-testing\",\n"
      + "    \"cloud_trace\": {\n"
      + "      \"sampling_rate\": -0.75\n"
      + "    }\n"
      + "}";

  private static final String CUSTOM_TAGS = "{\n"
          + "    \"project_id\": \"grpc-testing\",\n"
          + "    \"cloud_logging\": {},\n"
          + "    \"labels\": {\n"
          + "      \"SOURCE_VERSION\" : \"J2e1Cf\",\n"
          + "      \"SERVICE_NAME\" : \"payment-service\",\n"
          + "      \"ENTRYPOINT_SCRIPT\" : \"entrypoint.sh\"\n"
          + "    }\n"
          + "}";

  private static final String BAD_CUSTOM_TAGS =
      "{\n"
          + "    \"project_id\": \"grpc-testing\",\n"
          + "    \"cloud_monitoring\": {},\n"
          + "    \"labels\": {\n"
          + "      \"SOURCE_VERSION\" : \"J2e1Cf\",\n"
          + "      \"SERVICE_NAME\" : { \"SUB_SERVICE_NAME\" : \"payment-service\"},\n"
          + "      \"ENTRYPOINT_SCRIPT\" : \"entrypoint.sh\"\n"
          + "    }\n"
          + "}";

  private static final String LOG_FILTER_GLOBAL_EXCLUDE =
      "{\n"
          + "    \"project_id\": \"grpc-testing\",\n"
          + "    \"cloud_logging\": {\n"
          + "    \"client_rpc_events\": [{\n"
          + "        \"methods\": [\"service1/Method2\", \"*\"],\n"
          + "        \"max_metadata_bytes\": 20,\n"
          + "        \"max_message_bytes\": 15,\n"
          + "        \"exclude\": true\n"
          + "    }"
          + "    ]\n"
          + "    }\n"
          + "}";

  private static final String LOG_FILTER_INVALID_METHOD =
      "{\n"
          + "    \"project_id\": \"grpc-testing\",\n"
          + "    \"cloud_logging\": {\n"
          + "    \"client_rpc_events\": [{\n"
          + "        \"methods\": [\"s*&%ervice1/Method2\", \"*\"],\n"
          + "        \"max_metadata_bytes\": 20\n"
          + "    }"
          + "    ]\n"
          + "    }\n"
          + "}";

  ObservabilityConfigImpl observabilityConfig = new ObservabilityConfigImpl();

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  @Test
  public void nullConfig() throws IOException {
    try {
      observabilityConfig.parse(null);
      fail("exception expected!");
    } catch (IllegalArgumentException iae) {
      assertThat(iae.getMessage()).isEqualTo("GRPC_GCP_OBSERVABILITY_CONFIG value is null!");
    }
  }

  @Test
  public void emptyConfig() throws IOException {
    observabilityConfig.parse(EMPTY_CONFIG);
    assertFalse(observabilityConfig.isEnableCloudLogging());
    assertFalse(observabilityConfig.isEnableCloudMonitoring());
    assertFalse(observabilityConfig.isEnableCloudTracing());
    assertThat(observabilityConfig.getClientLogFilters()).isEmpty();
    assertThat(observabilityConfig.getServerLogFilters()).isEmpty();
    assertThat(observabilityConfig.getSampler()).isNull();
    assertThat(observabilityConfig.getProjectId()).isNull();
    assertThat(observabilityConfig.getCustomTags()).isEmpty();
  }

  @Test
  public void emptyConfigFile() throws IOException {
    File configFile = tempFolder.newFile();
    try {
      observabilityConfig.parseFile(configFile.getAbsolutePath());
      fail("exception expected!");
    } catch (IllegalArgumentException iae) {
      assertThat(iae.getMessage()).isEqualTo(
          "GRPC_GCP_OBSERVABILITY_CONFIG_FILE is empty!");
    }
  }

  @Test
  public void setProjectId() throws IOException {
    observabilityConfig.parse(PROJECT_ID);
    assertTrue(observabilityConfig.isEnableCloudLogging());
    assertThat(observabilityConfig.getProjectId()).isEqualTo("grpc-testing");
  }

  @Test
  public void logFilters() throws IOException {
    observabilityConfig.parse(LOG_FILTERS);
    assertTrue(observabilityConfig.isEnableCloudLogging());
    assertThat(observabilityConfig.getProjectId()).isEqualTo("grpc-testing");

    List<LogFilter> clientLogFilters = observabilityConfig.getClientLogFilters();
    assertThat(clientLogFilters).hasSize(1);
    assertThat(clientLogFilters.get(0).headerBytes).isEqualTo(4096);
    assertThat(clientLogFilters.get(0).messageBytes).isEqualTo(0);
    assertThat(clientLogFilters.get(0).excludePattern).isFalse();
    assertThat(clientLogFilters.get(0).matchAll).isTrue();
    assertThat(clientLogFilters.get(0).services).isEmpty();
    assertThat(clientLogFilters.get(0).methods).isEmpty();

    List<LogFilter> serverLogFilters = observabilityConfig.getServerLogFilters();
    assertThat(serverLogFilters).hasSize(1);
    assertThat(serverLogFilters.get(0).headerBytes).isEqualTo(32);
    assertThat(serverLogFilters.get(0).messageBytes).isEqualTo(64);
    assertThat(serverLogFilters.get(0).excludePattern).isFalse();
    assertThat(serverLogFilters.get(0).matchAll).isTrue();
    assertThat(serverLogFilters.get(0).services).isEmpty();
    assertThat(serverLogFilters.get(0).methods).isEmpty();
  }

  @Test
  public void setClientLogFilters() throws IOException {
    observabilityConfig.parse(CLIENT_LOG_FILTERS);
    assertTrue(observabilityConfig.isEnableCloudLogging());
    assertThat(observabilityConfig.getProjectId()).isEqualTo("grpc-testing");
    List<LogFilter> logFilterList = observabilityConfig.getClientLogFilters();
    assertThat(logFilterList).hasSize(2);
    assertThat(logFilterList.get(0).headerBytes).isEqualTo(4096);
    assertThat(logFilterList.get(0).messageBytes).isEqualTo(2048);
    assertThat(logFilterList.get(0).excludePattern).isFalse();
    assertThat(logFilterList.get(0).matchAll).isTrue();
    assertThat(logFilterList.get(0).services).isEmpty();
    assertThat(logFilterList.get(0).methods).isEmpty();

    assertThat(logFilterList.get(1).headerBytes).isEqualTo(0);
    assertThat(logFilterList.get(1).messageBytes).isEqualTo(0);
    assertThat(logFilterList.get(1).excludePattern).isTrue();
    assertThat(logFilterList.get(1).matchAll).isFalse();
    assertThat(logFilterList.get(1).services).isEqualTo(Collections.singleton("Service2"));
    assertThat(logFilterList.get(1).methods)
        .isEqualTo(Collections.singleton("service1/Method2"));
  }

  @Test
  public void setServerLogFilters() throws IOException {
    Set<String> expectedMethods = Stream.of("service1/method4", "service2/method234")
        .collect(Collectors.toCollection(HashSet::new));
    observabilityConfig.parse(SERVER_LOG_FILTERS);
    assertTrue(observabilityConfig.isEnableCloudLogging());
    List<LogFilter> logFilterList = observabilityConfig.getServerLogFilters();
    assertThat(logFilterList).hasSize(2);
    assertThat(logFilterList.get(0).headerBytes).isEqualTo(32);
    assertThat(logFilterList.get(0).messageBytes).isEqualTo(64);
    assertThat(logFilterList.get(0).excludePattern).isFalse();
    assertThat(logFilterList.get(0).matchAll).isFalse();
    assertThat(logFilterList.get(0).services).isEmpty();
    assertThat(logFilterList.get(0).methods)
        .isEqualTo(expectedMethods);

    Set<String> expectedServices = Stream.of("service4", "Service2")
        .collect(Collectors.toCollection(HashSet::new));
    assertThat(logFilterList.get(1).headerBytes).isEqualTo(0);
    assertThat(logFilterList.get(1).messageBytes).isEqualTo(0);
    assertThat(logFilterList.get(1).excludePattern).isTrue();
    assertThat(logFilterList.get(1).matchAll).isFalse();
    assertThat(logFilterList.get(1).services).isEqualTo(expectedServices);
    assertThat(logFilterList.get(1).methods).isEmpty();
  }

  @Test
  public void enableCloudMonitoring() throws IOException {
    observabilityConfig.parse(ENABLE_CLOUD_MONITORING);
    assertTrue(observabilityConfig.isEnableCloudMonitoring());
  }

  @Test
  public void enableCloudTracing() throws IOException {
    observabilityConfig.parse(ENABLE_CLOUD_TRACE);
    assertTrue(observabilityConfig.isEnableCloudTracing());
  }

  @Test
  public void enableCloudMonitoringAndTracing() throws IOException {
    observabilityConfig.parse(ENABLE_CLOUD_MONITORING_AND_TRACING);
    assertFalse(observabilityConfig.isEnableCloudLogging());
    assertTrue(observabilityConfig.isEnableCloudMonitoring());
    assertTrue(observabilityConfig.isEnableCloudTracing());
  }

  @Test
  public void alwaysSampler() throws IOException {
    observabilityConfig.parse(TRACING_ALWAYS_SAMPLER);
    assertTrue(observabilityConfig.isEnableCloudTracing());
    Sampler sampler = observabilityConfig.getSampler();
    assertThat(sampler).isNotNull();
    assertThat(sampler).isEqualTo(Samplers.alwaysSample());
  }

  @Test
  public void neverSampler() throws IOException {
    observabilityConfig.parse(TRACING_NEVER_SAMPLER);
    assertTrue(observabilityConfig.isEnableCloudTracing());
    Sampler sampler = observabilityConfig.getSampler();
    assertThat(sampler).isNotNull();
    assertThat(sampler).isEqualTo(Samplers.probabilitySampler(0.0));
  }

  @Test
  public void probabilisticSampler() throws IOException {
    observabilityConfig.parse(TRACING_PROBABILISTIC_SAMPLER);
    assertTrue(observabilityConfig.isEnableCloudTracing());
    Sampler sampler = observabilityConfig.getSampler();
    assertThat(sampler).isNotNull();
    assertThat(sampler).isEqualTo(Samplers.probabilitySampler(0.75));
  }

  @Test
  public void defaultSampler() throws IOException {
    observabilityConfig.parse(TRACING_DEFAULT_SAMPLER);
    assertTrue(observabilityConfig.isEnableCloudTracing());
    Sampler sampler = observabilityConfig.getSampler();
    assertThat(sampler).isNotNull();
    assertThat(sampler).isEqualTo(Samplers.probabilitySampler(0.00));
  }

  @Test
  public void badProbabilisticSampler_error() throws IOException {
    try {
      observabilityConfig.parse(GLOBAL_TRACING_BAD_PROBABILISTIC_SAMPLER);
      fail("exception expected!");
    } catch (IllegalArgumentException iae) {
      assertThat(iae.getMessage()).isEqualTo(
          "'sampling_rate' needs to be between [0.0, 1.0]");
    }
  }

  @Test
  public void configFileLogFilters() throws Exception {
    File configFile = tempFolder.newFile();
    Files.write(
        Paths.get(configFile.getAbsolutePath()), CLIENT_LOG_FILTERS.getBytes(Charsets.US_ASCII));
    observabilityConfig.parseFile(configFile.getAbsolutePath());
    assertTrue(observabilityConfig.isEnableCloudLogging());
    assertThat(observabilityConfig.getProjectId()).isEqualTo("grpc-testing");
    List<LogFilter> logFilters = observabilityConfig.getClientLogFilters();
    assertThat(logFilters).hasSize(2);
    assertThat(logFilters.get(0).headerBytes).isEqualTo(4096);
    assertThat(logFilters.get(0).messageBytes).isEqualTo(2048);
    assertThat(logFilters.get(1).headerBytes).isEqualTo(0);
    assertThat(logFilters.get(1).messageBytes).isEqualTo(0);

    assertThat(logFilters).hasSize(2);
    assertThat(logFilters.get(0).headerBytes).isEqualTo(4096);
    assertThat(logFilters.get(0).messageBytes).isEqualTo(2048);
    assertThat(logFilters.get(0).excludePattern).isFalse();
    assertThat(logFilters.get(0).matchAll).isTrue();
    assertThat(logFilters.get(0).services).isEmpty();
    assertThat(logFilters.get(0).methods).isEmpty();

    assertThat(logFilters.get(1).headerBytes).isEqualTo(0);
    assertThat(logFilters.get(1).messageBytes).isEqualTo(0);
    assertThat(logFilters.get(1).excludePattern).isTrue();
    assertThat(logFilters.get(1).matchAll).isFalse();
    assertThat(logFilters.get(1).services).isEqualTo(Collections.singleton("Service2"));
    assertThat(logFilters.get(1).methods)
        .isEqualTo(Collections.singleton("service1/Method2"));
  }

  @Test
  public void customTags() throws IOException {
    observabilityConfig.parse(CUSTOM_TAGS);
    assertTrue(observabilityConfig.isEnableCloudLogging());
    Map<String, String> customTags = observabilityConfig.getCustomTags();
    assertThat(customTags).hasSize(3);
    assertThat(customTags).containsEntry("SOURCE_VERSION", "J2e1Cf");
    assertThat(customTags).containsEntry("SERVICE_NAME", "payment-service");
    assertThat(customTags).containsEntry("ENTRYPOINT_SCRIPT", "entrypoint.sh");
  }

  @Test
  public void badCustomTags() throws IOException {
    try {
      observabilityConfig.parse(BAD_CUSTOM_TAGS);
      fail("exception expected!");
    } catch (IllegalArgumentException iae) {
      assertThat(iae.getMessage()).isEqualTo(
              "'labels' needs to be a map of <string, string>");
    }
  }

  @Test
  public void globalLogFilterExclude() throws IOException {
    try {
      observabilityConfig.parse(LOG_FILTER_GLOBAL_EXCLUDE);
      fail("exception expected!");
    } catch (IllegalArgumentException iae) {
      assertThat(iae.getMessage()).isEqualTo(
          "cannot have 'exclude' and '*' wildcard in the same filter");
    }
  }

  @Test
  public void logFilterInvalidMethod() throws IOException {
    try {
      observabilityConfig.parse(LOG_FILTER_INVALID_METHOD);
      fail("exception expected!");
    } catch (IllegalArgumentException iae) {
      assertThat(iae.getMessage()).contains(
          "invalid service or method filter");
    }
  }

  @Test
  public void validLogFilter() throws Exception {
    observabilityConfig.parse(VALID_LOG_FILTERS);
    assertTrue(observabilityConfig.isEnableCloudLogging());
    assertThat(observabilityConfig.getProjectId()).isEqualTo("grpc-testing");
    List<LogFilter> logFilterList = observabilityConfig.getServerLogFilters();
    assertThat(logFilterList).hasSize(1);
    assertThat(logFilterList.get(0).headerBytes).isEqualTo(16);
    assertThat(logFilterList.get(0).messageBytes).isEqualTo(64);
    assertThat(logFilterList.get(0).excludePattern).isFalse();
    assertThat(logFilterList.get(0).matchAll).isFalse();
    assertThat(logFilterList.get(0).services).isEqualTo(Collections.singleton("service.Service1"));
    assertThat(logFilterList.get(0).methods)
        .isEqualTo(Collections.singleton("service2.Service4/method4"));
  }
}
