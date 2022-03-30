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

package io.grpc.observability;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import io.grpc.observability.ObservabilityConfig.LogFilter;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import java.io.IOException;
import java.util.List;
import org.junit.Test;
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

  private static final String DISABLE_CLOUD_LOGGING = "{\n"
      + "    \"enable_cloud_logging\": false\n"
      + "}";

  ObservabilityConfigImpl observabilityConfig = new ObservabilityConfigImpl();

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
    assertTrue(observabilityConfig.isEnableCloudLogging());
    assertNull(observabilityConfig.getDestinationProjectId());
    assertNull(observabilityConfig.getLogFilters());
  }

  @Test
  public void disableCloudLogging() throws IOException {
    observabilityConfig.parse(DISABLE_CLOUD_LOGGING);
    assertFalse(observabilityConfig.isEnableCloudLogging());
    assertNull(observabilityConfig.getDestinationProjectId());
    assertNull(observabilityConfig.getLogFilters());
  }

  @Test
  public void destProjectId() throws IOException {
    observabilityConfig.parse(DEST_PROJECT_ID);
    assertTrue(observabilityConfig.isEnableCloudLogging());
    assertThat(observabilityConfig.getDestinationProjectId()).isEqualTo("grpc-testing");
  }

  @Test
  public void logFilters() throws IOException {
    observabilityConfig.parse(LOG_FILTERS);
    assertTrue(observabilityConfig.isEnableCloudLogging());
    assertThat(observabilityConfig.getDestinationProjectId()).isEqualTo("grpc-testing");
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
}
