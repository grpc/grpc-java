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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import io.grpc.gcp.observability.ObservabilityConfig.LogFilter;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ObservabilityConfigImplTest {
  private static final String EVENT_TYPES = "{\n"
      + "    \"enable_cloud_logging\": false,\n"
      + "    \"event_types\": "
      + "[\"GRPC_CALL_REQUEST_HEADER\", \"GRPC_CALL_HALF_CLOSE\", \"GRPC_CALL_TRAILER\"]\n"
      + "}";

  private static final String LOG_FILTERS = "{\n"
      + "    \"enable_cloud_logging\": true,\n"
      + "    \"destination_project_id\": \"grpc-testing\",\n"
      + "    \"flush_message_count\": 1000,\n"
      + "    \"log_filters\": [{\n"
      + "        \"pattern\": \"*/*\",\n"
      + "        \"header_bytes\": 4096,\n"
      + "        \"message_bytes\": 2048\n"
      + "    },"
      + "   {\n"
      + "        \"pattern\": \"service1/Method2\"\n"
      + "    }"
      + "    ]\n"
      + "}";

  private static final String DEST_PROJECT_ID = "{\n"
      + "    \"enable_cloud_logging\": true,\n"
      + "    \"destination_project_id\": \"grpc-testing\"\n"
      + "}";

  private static final String FLUSH_MESSAGE_COUNT = "{\n"
      + "    \"enable_cloud_logging\": true,\n"
      + "    \"flush_message_count\": 500\n"
      + "}";

  private static final String DISABLE_CLOUD_LOGGING = "{\n"
      + "    \"enable_cloud_logging\": false\n"
      + "}";

  private static final String ENABLE_CLOUD_MONITORING_AND_TRACING = "{\n"
          + "    \"enable_cloud_monitoring\": true,\n"
          + "    \"enable_cloud_tracing\": true\n"
          + "}";

  private static final String GLOBAL_TRACING_ALWAYS_SAMPLER = "{\n"
          + "    \"enable_cloud_tracing\": true,\n"
          + "    \"global_trace_sampler\": \"always\"\n"
          + "}";

  private static final String GLOBAL_TRACING_NEVER_SAMPLER = "{\n"
          + "    \"enable_cloud_tracing\": true,\n"
          + "    \"global_trace_sampler\": \"never\"\n"
          + "}";

  private static final String GLOBAL_TRACING_PROBABILISTIC_SAMPLER = "{\n"
          + "    \"enable_cloud_tracing\": true,\n"
          + "    \"global_trace_sampling_rate\": 0.75\n"
          + "}";

  private static final String GLOBAL_TRACING_BOTH_SAMPLER_ERROR = "{\n"
          + "    \"enable_cloud_tracing\": true,\n"
          + "    \"global_trace_sampler\": \"never\",\n"
          + "    \"global_trace_sampling_rate\": 0.75\n"
          + "}";

  private static final String GLOBAL_TRACING_BADPROBABILISTIC_SAMPLER = "{\n"
          + "    \"enable_cloud_tracing\": true,\n"
          + "    \"global_trace_sampling_rate\": -0.75\n"
          + "}";


  ObservabilityConfigImpl observabilityConfig = new ObservabilityConfigImpl();

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  @Test
  public void nullConfig() throws IOException {
    try {
      observabilityConfig.parse(null);
      fail("exception expected!");
    } catch (IllegalArgumentException iae) {
      assertThat(iae.getMessage()).isEqualTo("GRPC_CONFIG_OBSERVABILITY value is null!");
    }
  }

  @Test
  public void emptyConfig() throws IOException {
    observabilityConfig.parse("{}");
    assertFalse(observabilityConfig.isEnableCloudLogging());
    assertFalse(observabilityConfig.isEnableCloudMonitoring());
    assertFalse(observabilityConfig.isEnableCloudTracing());
    assertNull(observabilityConfig.getDestinationProjectId());
    assertNull(observabilityConfig.getFlushMessageCount());
    assertNull(observabilityConfig.getLogFilters());
    assertNull(observabilityConfig.getEventTypes());
  }

  @Test
  public void disableCloudLogging() throws IOException {
    observabilityConfig.parse(DISABLE_CLOUD_LOGGING);
    assertFalse(observabilityConfig.isEnableCloudLogging());
    assertFalse(observabilityConfig.isEnableCloudMonitoring());
    assertFalse(observabilityConfig.isEnableCloudTracing());
    assertNull(observabilityConfig.getDestinationProjectId());
    assertNull(observabilityConfig.getFlushMessageCount());
    assertNull(observabilityConfig.getLogFilters());
    assertNull(observabilityConfig.getEventTypes());
  }

  @Test
  public void destProjectId() throws IOException {
    observabilityConfig.parse(DEST_PROJECT_ID);
    assertTrue(observabilityConfig.isEnableCloudLogging());
    assertThat(observabilityConfig.getDestinationProjectId()).isEqualTo("grpc-testing");
  }

  @Test
  public void flushMessageCount() throws Exception {
    observabilityConfig.parse(FLUSH_MESSAGE_COUNT);
    assertTrue(observabilityConfig.isEnableCloudLogging());
    assertThat(observabilityConfig.getFlushMessageCount()).isEqualTo(500L);
  }

  @Test
  public void logFilters() throws IOException {
    observabilityConfig.parse(LOG_FILTERS);
    assertTrue(observabilityConfig.isEnableCloudLogging());
    assertThat(observabilityConfig.getDestinationProjectId()).isEqualTo("grpc-testing");
    assertThat(observabilityConfig.getFlushMessageCount()).isEqualTo(1000L);
    List<LogFilter> logFilters = observabilityConfig.getLogFilters();
    assertThat(logFilters).hasSize(2);
    assertThat(logFilters.get(0).pattern).isEqualTo("*/*");
    assertThat(logFilters.get(0).headerBytes).isEqualTo(4096);
    assertThat(logFilters.get(0).messageBytes).isEqualTo(2048);
    assertThat(logFilters.get(1).pattern).isEqualTo("service1/Method2");
    assertThat(logFilters.get(1).headerBytes).isNull();
    assertThat(logFilters.get(1).messageBytes).isNull();
  }

  @Test
  public void eventTypes() throws IOException {
    observabilityConfig.parse(EVENT_TYPES);
    assertFalse(observabilityConfig.isEnableCloudLogging());
    List<EventType> eventTypes = observabilityConfig.getEventTypes();
    assertThat(eventTypes).isEqualTo(
        ImmutableList.of(EventType.GRPC_CALL_REQUEST_HEADER, EventType.GRPC_CALL_HALF_CLOSE,
            EventType.GRPC_CALL_TRAILER));
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
    observabilityConfig.parse(GLOBAL_TRACING_ALWAYS_SAMPLER);
    assertTrue(observabilityConfig.isEnableCloudTracing());
    ObservabilityConfig.Sampler sampler = observabilityConfig.getSampler();
    assertThat(sampler).isNotNull();
    assertThat(sampler.getType()).isEqualTo(ObservabilityConfig.SamplerType.ALWAYS);
  }

  @Test
  public void neverSampler() throws IOException {
    observabilityConfig.parse(GLOBAL_TRACING_NEVER_SAMPLER);
    assertTrue(observabilityConfig.isEnableCloudTracing());
    ObservabilityConfig.Sampler sampler = observabilityConfig.getSampler();
    assertThat(sampler).isNotNull();
    assertThat(sampler.getType()).isEqualTo(ObservabilityConfig.SamplerType.NEVER);
  }

  @Test
  public void probabilisticSampler() throws IOException {
    observabilityConfig.parse(GLOBAL_TRACING_PROBABILISTIC_SAMPLER);
    assertTrue(observabilityConfig.isEnableCloudTracing());
    ObservabilityConfig.Sampler sampler = observabilityConfig.getSampler();
    assertThat(sampler).isNotNull();
    assertThat(sampler.getType()).isEqualTo(ObservabilityConfig.SamplerType.PROBABILISTIC);
    assertThat(sampler.getProbability()).isEqualTo(0.75);
  }

  @Test
  public void bothSamplerAndSamplingRate_error() throws IOException {
    try {
      observabilityConfig.parse(GLOBAL_TRACING_BOTH_SAMPLER_ERROR);
      fail("exception expected!");
    } catch (IllegalArgumentException iae) {
      assertThat(iae.getMessage())
          .isEqualTo(
              "only one of 'global_trace_sampler' or 'global_trace_sampling_rate' can be"
                  + " specified");
    }
  }

  @Test
  public void badProbabilisticSampler_error() throws IOException {
    try {
      observabilityConfig.parse(GLOBAL_TRACING_BADPROBABILISTIC_SAMPLER);
      fail("exception expected!");
    } catch (IllegalArgumentException iae) {
      assertThat(iae.getMessage()).isEqualTo(
              "'global_trace_sampling_rate' needs to be between 0.0 and 1.0");
    }
  }

  @Test
  public void configFileLogFilters() throws Exception {
    File configFile = tempFolder.newFile();
    Files.write(Paths.get(configFile.getAbsolutePath()), LOG_FILTERS.getBytes(Charsets.US_ASCII));
    observabilityConfig.parseFile(configFile.getAbsolutePath());
    assertTrue(observabilityConfig.isEnableCloudLogging());
    assertThat(observabilityConfig.getDestinationProjectId()).isEqualTo("grpc-testing");
    assertThat(observabilityConfig.getFlushMessageCount()).isEqualTo(1000L);
    List<LogFilter> logFilters = observabilityConfig.getLogFilters();
    assertThat(logFilters).hasSize(2);
    assertThat(logFilters.get(0).pattern).isEqualTo("*/*");
    assertThat(logFilters.get(0).headerBytes).isEqualTo(4096);
    assertThat(logFilters.get(0).messageBytes).isEqualTo(2048);
    assertThat(logFilters.get(1).pattern).isEqualTo("service1/Method2");
    assertThat(logFilters.get(1).headerBytes).isNull();
    assertThat(logFilters.get(1).messageBytes).isNull();
  }
}
