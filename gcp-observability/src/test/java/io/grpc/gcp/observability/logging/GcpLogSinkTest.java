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

package io.grpc.gcp.observability.logging;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.cloud.MonitoredResource;
import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Logging;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Duration;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.Durations;
import io.grpc.observabilitylog.v1.GrpcLogRecord;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventLogger;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link GcpLogSink}.
 */
@RunWith(JUnit4.class)
public class GcpLogSinkTest {

  @Rule
  public final MockitoRule mockito = MockitoJUnit.rule();

  private static final ImmutableMap<String, String> LOCATION_TAGS =
      ImmutableMap.of("project_id", "PROJECT",
      "location", "us-central1-c",
      "cluster_name", "grpc-observability-cluster",
      "namespace_name", "default" ,
      "pod_name", "app1-6c7c58f897-n92c5");
  private static final ImmutableMap<String, String> CUSTOM_TAGS =
      ImmutableMap.of("KEY1", "Value1",
      "KEY2", "VALUE2");
  private static final long FLUSH_LIMIT = 10L;
  // gRPC is expected to always use this log name when reporting to GCP cloud logging.
  private static final String EXPECTED_LOG_NAME =
      "microservices.googleapis.com%2Fobservability%2Fgrpc";
  private static final long SEQ_ID = 1;
  private static final String DEST_PROJECT_NAME = "PROJECT";
  private static final String SERVICE_NAME = "service";
  private static final String METHOD_NAME = "method";
  private static final String AUTHORITY = "authority";
  private static final Duration TIMEOUT = Durations.fromMillis(1234);
  private static final String RPC_ID = "d155e885-9587-4e77-81f7-3aa5a443d47f";
  private static final GrpcLogRecord LOG_PROTO = GrpcLogRecord.newBuilder()
      .setSequenceId(SEQ_ID)
      .setServiceName(SERVICE_NAME)
      .setMethodName(METHOD_NAME)
      .setAuthority(AUTHORITY)
      .setTimeout(TIMEOUT)
      .setEventType(EventType.GRPC_CALL_REQUEST_HEADER)
      .setEventLogger(EventLogger.LOGGER_CLIENT)
      .setRpcId(RPC_ID)
      .build();
  private static final Struct EXPECTED_STRUCT_LOG_PROTO = Struct.newBuilder()
      .putFields("sequence_id", Value.newBuilder().setStringValue(String.valueOf(SEQ_ID)).build())
      .putFields("service_name", Value.newBuilder().setStringValue(SERVICE_NAME).build())
      .putFields("method_name", Value.newBuilder().setStringValue(METHOD_NAME).build())
      .putFields("authority", Value.newBuilder().setStringValue(AUTHORITY).build())
      .putFields("timeout", Value.newBuilder().setStringValue("1.234s").build())
      .putFields("event_type", Value.newBuilder().setStringValue(
          String.valueOf(EventType.GRPC_CALL_REQUEST_HEADER)).build())
      .putFields("event_logger", Value.newBuilder().setStringValue(
          String.valueOf(EventLogger.LOGGER_CLIENT)).build())
      .putFields("rpc_id", Value.newBuilder().setStringValue(RPC_ID).build())
      .build();
  @Mock
  private Logging mockLogging;

  @Test
  @SuppressWarnings("unchecked")
  public void verifyWrite() throws Exception {
    GcpLogSink sink = new GcpLogSink(mockLogging, DEST_PROJECT_NAME, LOCATION_TAGS,
        CUSTOM_TAGS, FLUSH_LIMIT, Collections.emptySet());
    sink.write(LOG_PROTO);

    ArgumentCaptor<Collection<LogEntry>> logEntrySetCaptor = ArgumentCaptor.forClass(
        (Class) Collection.class);
    verify(mockLogging, times(1)).write(logEntrySetCaptor.capture());
    for (Iterator<LogEntry> it = logEntrySetCaptor.getValue().iterator(); it.hasNext(); ) {
      LogEntry entry = it.next();
      assertThat(entry.getPayload().getData()).isEqualTo(EXPECTED_STRUCT_LOG_PROTO);
      assertThat(entry.getLogName()).isEqualTo(EXPECTED_LOG_NAME);
    }
    verifyNoMoreInteractions(mockLogging);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void verifyWriteWithTags() {
    GcpLogSink sink = new GcpLogSink(mockLogging, DEST_PROJECT_NAME, LOCATION_TAGS,
        CUSTOM_TAGS, FLUSH_LIMIT, Collections.emptySet());
    MonitoredResource expectedMonitoredResource = GcpLogSink.getResource(LOCATION_TAGS);
    sink.write(LOG_PROTO);

    ArgumentCaptor<Collection<LogEntry>> logEntrySetCaptor = ArgumentCaptor.forClass(
        (Class) Collection.class);
    verify(mockLogging, times(1)).write(logEntrySetCaptor.capture());
    System.out.println(logEntrySetCaptor.getValue());
    for (Iterator<LogEntry> it = logEntrySetCaptor.getValue().iterator(); it.hasNext(); ) {
      LogEntry entry = it.next();
      assertThat(entry.getResource()).isEqualTo(expectedMonitoredResource);
      assertThat(entry.getLabels()).isEqualTo(CUSTOM_TAGS);
      assertThat(entry.getPayload().getData()).isEqualTo(EXPECTED_STRUCT_LOG_PROTO);
      assertThat(entry.getLogName()).isEqualTo(EXPECTED_LOG_NAME);
    }
    verifyNoMoreInteractions(mockLogging);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void emptyCustomTags_labelsNotSet() {
    Map<String, String> emptyCustomTags = null;
    Map<String, String> expectedEmptyLabels = new HashMap<>();
    GcpLogSink sink = new GcpLogSink(mockLogging, DEST_PROJECT_NAME, LOCATION_TAGS,
        emptyCustomTags, FLUSH_LIMIT, Collections.emptySet());
    sink.write(LOG_PROTO);

    ArgumentCaptor<Collection<LogEntry>> logEntrySetCaptor = ArgumentCaptor.forClass(
        (Class) Collection.class);
    verify(mockLogging, times(1)).write(logEntrySetCaptor.capture());
    for (Iterator<LogEntry> it = logEntrySetCaptor.getValue().iterator(); it.hasNext(); ) {
      LogEntry entry = it.next();
      assertThat(entry.getLabels()).isEqualTo(expectedEmptyLabels);
      assertThat(entry.getPayload().getData()).isEqualTo(EXPECTED_STRUCT_LOG_PROTO);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void emptyCustomTags_setSourceProject() {
    Map<String, String> emptyCustomTags = null;
    String destinationProjectId = "DESTINATION_PROJECT";
    Map<String, String> expectedLabels = GcpLogSink.getCustomTags(emptyCustomTags, LOCATION_TAGS,
        destinationProjectId);
    GcpLogSink sink = new GcpLogSink(mockLogging, destinationProjectId, LOCATION_TAGS,
        emptyCustomTags, FLUSH_LIMIT, Collections.emptySet());
    sink.write(LOG_PROTO);

    ArgumentCaptor<Collection<LogEntry>> logEntrySetCaptor = ArgumentCaptor.forClass(
        (Class) Collection.class);
    verify(mockLogging, times(1)).write(logEntrySetCaptor.capture());
    for (Iterator<LogEntry> it = logEntrySetCaptor.getValue().iterator(); it.hasNext(); ) {
      LogEntry entry = it.next();
      assertThat(entry.getLabels()).isEqualTo(expectedLabels);
      assertThat(entry.getPayload().getData()).isEqualTo(EXPECTED_STRUCT_LOG_PROTO);
    }
  }

  @Test
  public void verifyFlush() {
    long lowerFlushLimit = 2L;
    GcpLogSink sink = new GcpLogSink(mockLogging, DEST_PROJECT_NAME, LOCATION_TAGS,
        CUSTOM_TAGS, lowerFlushLimit, Collections.emptySet());
    sink.write(LOG_PROTO);
    verify(mockLogging, never()).flush();
    sink.write(LOG_PROTO);
    verify(mockLogging, times(1)).flush();
    sink.write(LOG_PROTO);
    sink.write(LOG_PROTO);
    verify(mockLogging, times(2)).flush();
  }

  @Test
  public void verifyClose() throws Exception {
    GcpLogSink sink = new GcpLogSink(mockLogging, DEST_PROJECT_NAME, LOCATION_TAGS,
        CUSTOM_TAGS, FLUSH_LIMIT, Collections.emptySet());
    sink.write(LOG_PROTO);
    verify(mockLogging, times(1)).write(anyIterable());
    sink.close();
    verify(mockLogging).close();
    verifyNoMoreInteractions(mockLogging);
  }

  @Test
  public void verifyExclude() throws Exception {
    Sink mockSink = new GcpLogSink(mockLogging, DEST_PROJECT_NAME, LOCATION_TAGS,
        CUSTOM_TAGS, FLUSH_LIMIT, Collections.singleton("service"));
    mockSink.write(LOG_PROTO);
    verifyNoInteractions(mockLogging);
  }
}
