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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link GcpLogSink}.
 */
@RunWith(JUnit4.class)
public class GcpLogSinkTest {

  @Rule
  public final MockitoRule mockito = MockitoJUnit.rule();

  private static final Map<String, String> locationTags = ImmutableMap.of("project_id", "PROJECT",
      "location", "us-central1-c",
      "cluster_name", "grpc-observability-cluster",
      "namespace_name", "default" ,
      "pod_name", "app1-6c7c58f897-n92c5");
  private static final Map<String, String> customTags = ImmutableMap.of("KEY1", "Value1",
      "KEY2", "VALUE2");
  private static final long flushLimit = 10L;
  // gRPC is expected to alway use this log name when reporting to GCP cloud logging.
  private static final String expectedLogName =
      "microservices.googleapis.com%2Fobservability%2Fgrpc";
  private final long seqId = 1;
  private final String destProjectName = "PROJECT";
  private final String serviceName = "service";
  private final String methodName = "method";
  private final String authority = "authority";
  private final Duration timeout = Durations.fromMillis(1234);
  private final String rpcId = "d155e885-9587-4e77-81f7-3aa5a443d47f";
  private final GrpcLogRecord logProto = GrpcLogRecord.newBuilder()
      .setSequenceId(seqId)
      .setServiceName(serviceName)
      .setMethodName(methodName)
      .setAuthority(authority)
      .setTimeout(timeout)
      .setEventType(EventType.GRPC_CALL_REQUEST_HEADER)
      .setEventLogger(EventLogger.LOGGER_CLIENT)
      .setRpcId(rpcId)
      .build();
  private final Struct expectedStructLogProto = Struct.newBuilder()
      .putFields("sequence_id", Value.newBuilder().setStringValue(String.valueOf(seqId)).build())
      .putFields("service_name", Value.newBuilder().setStringValue(serviceName).build())
      .putFields("method_name", Value.newBuilder().setStringValue(methodName).build())
      .putFields("authority", Value.newBuilder().setStringValue(authority).build())
      .putFields("timeout", Value.newBuilder().setStringValue("1.234s").build())
      .putFields("event_type", Value.newBuilder().setStringValue(
          String.valueOf(EventType.GRPC_CALL_REQUEST_HEADER)).build())
      .putFields("event_logger", Value.newBuilder().setStringValue(
          String.valueOf(EventLogger.LOGGER_CLIENT)).build())
      .putFields("rpc_id", Value.newBuilder().setStringValue(rpcId).build())
      .build();
  private Logging mockLogging;

  @Before
  public void setUp() {
    mockLogging = mock(Logging.class);
  }

  @Test
  public void createSink() {
    Sink mockSink = new GcpLogSink(mockLogging, destProjectName, locationTags,
        customTags, flushLimit);
    assertThat(mockSink).isInstanceOf(GcpLogSink.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void verifyWrite() throws Exception {
    Sink mockSink = new GcpLogSink(mockLogging, destProjectName, locationTags,
        customTags, flushLimit);
    mockSink.write(logProto);

    ArgumentCaptor<Collection<LogEntry>> logEntrySetCaptor = ArgumentCaptor.forClass(
        (Class) Collection.class);
    verify(mockLogging, times(1)).write(logEntrySetCaptor.capture());
    for (Iterator<LogEntry> it = logEntrySetCaptor.getValue().iterator(); it.hasNext(); ) {
      LogEntry entry = it.next();
      System.out.println(entry);
      assertThat(entry.getPayload().getData()).isEqualTo(expectedStructLogProto);
      assertThat(entry.getLogName()).isEqualTo(expectedLogName);
    }
    verifyNoMoreInteractions(mockLogging);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void verifyWriteWithTags() {
    GcpLogSink mockSink = new GcpLogSink(mockLogging, destProjectName, locationTags,
        customTags, flushLimit);
    MonitoredResource expectedMonitoredResource = GcpLogSink.getResource(locationTags);
    mockSink.write(logProto);

    ArgumentCaptor<Collection<LogEntry>> logEntrySetCaptor = ArgumentCaptor.forClass(
        (Class) Collection.class);
    verify(mockLogging, times(1)).write(logEntrySetCaptor.capture());
    System.out.println(logEntrySetCaptor.getValue());
    for (Iterator<LogEntry> it = logEntrySetCaptor.getValue().iterator(); it.hasNext(); ) {
      LogEntry entry = it.next();
      assertThat(entry.getResource()).isEqualTo(expectedMonitoredResource);
      assertThat(entry.getLabels()).isEqualTo(customTags);
      assertThat(entry.getPayload().getData()).isEqualTo(expectedStructLogProto);
      assertThat(entry.getLogName()).isEqualTo(expectedLogName);
    }
    verifyNoMoreInteractions(mockLogging);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void emptyCustomTags_labelsNotSet() {
    Map<String, String> emptyCustomTags = null;
    Map<String, String> expectedEmptyLabels = new HashMap<>();
    GcpLogSink mockSink = new GcpLogSink(mockLogging, destProjectName, locationTags,
        emptyCustomTags, flushLimit);
    mockSink.write(logProto);

    ArgumentCaptor<Collection<LogEntry>> logEntrySetCaptor = ArgumentCaptor.forClass(
        (Class) Collection.class);
    verify(mockLogging, times(1)).write(logEntrySetCaptor.capture());
    for (Iterator<LogEntry> it = logEntrySetCaptor.getValue().iterator(); it.hasNext(); ) {
      LogEntry entry = it.next();
      assertThat(entry.getLabels()).isEqualTo(expectedEmptyLabels);
      assertThat(entry.getPayload().getData()).isEqualTo(expectedStructLogProto);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void emptyCustomTags_setSourceProject() {
    Map<String, String> emptyCustomTags = null;
    String destinationProjectId = "DESTINATION_PROJECT";
    Map<String, String> expectedLabels = GcpLogSink.getCustomTags(emptyCustomTags, locationTags,
        destinationProjectId);
    GcpLogSink mockSink = new GcpLogSink(mockLogging, destinationProjectId, locationTags,
        emptyCustomTags, flushLimit);
    mockSink.write(logProto);

    ArgumentCaptor<Collection<LogEntry>> logEntrySetCaptor = ArgumentCaptor.forClass(
        (Class) Collection.class);
    verify(mockLogging, times(1)).write(logEntrySetCaptor.capture());
    for (Iterator<LogEntry> it = logEntrySetCaptor.getValue().iterator(); it.hasNext(); ) {
      LogEntry entry = it.next();
      assertThat(entry.getLabels()).isEqualTo(expectedLabels);
      assertThat(entry.getPayload().getData()).isEqualTo(expectedStructLogProto);
    }
  }

  @Test
  public void verifyFlush() {
    long lowerFlushLimit = 2L;
    GcpLogSink mockSink = new GcpLogSink(mockLogging, destProjectName, locationTags,
        customTags, lowerFlushLimit);
    mockSink.write(logProto);
    verify(mockLogging, never()).flush();
    mockSink.write(logProto);
    verify(mockLogging, times(1)).flush();
    mockSink.write(logProto);
    mockSink.write(logProto);
    verify(mockLogging, times(2)).flush();
  }

  @Test
  public void verifyClose() throws Exception {
    Sink mockSink = new GcpLogSink(mockLogging, destProjectName, locationTags,
        customTags, flushLimit);
    mockSink.write(logProto);
    verify(mockLogging, times(1)).write(anyIterable());
    mockSink.close();
    verify(mockLogging).close();
    verifyNoMoreInteractions(mockLogging);
  }
}
