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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Logging;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Duration;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.Durations;
import io.grpc.gcp.observability.ObservabilityConfig;
import io.grpc.observabilitylog.v1.GrpcLogRecord;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventLogger;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import io.opencensus.trace.SpanContext;
import io.opencensus.trace.SpanId;
import io.opencensus.trace.TraceId;
import io.opencensus.trace.TraceOptions;
import io.opencensus.trace.Tracestate;
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

  private static final ImmutableMap<String, String> CUSTOM_TAGS =
      ImmutableMap.of("KEY1", "Value1",
      "KEY2", "VALUE2");
  // gRPC is expected to always use this log name when reporting to GCP cloud logging.
  private static final String EXPECTED_LOG_NAME =
      "microservices.googleapis.com%2Fobservability%2Fgrpc";
  private static final long SEQ_ID = 1;
  private static final String DEST_PROJECT_NAME = "PROJECT";
  private static final String SERVICE_NAME = "service";
  private static final String METHOD_NAME = "method";
  private static final String AUTHORITY = "authority";
  private static final Duration TIMEOUT = Durations.fromMillis(1234);
  private static final String CALL_ID = "d155e885-9587-4e77-81f7-3aa5a443d47f";
  private static final GrpcLogRecord LOG_PROTO = GrpcLogRecord.newBuilder()
      .setSequenceId(SEQ_ID)
      .setServiceName(SERVICE_NAME)
      .setMethodName(METHOD_NAME)
      .setAuthority(AUTHORITY)
      .setPayload(io.grpc.observabilitylog.v1.Payload.newBuilder().setTimeout(TIMEOUT))
      .setType(EventType.CLIENT_HEADER)
      .setLogger(EventLogger.CLIENT)
      .setCallId(CALL_ID)
      .build();
  //       .putFields("timeout", Value.newBuilder().setStringValue("1.234s").build())
  private static final Struct struct =
      Struct.newBuilder()
          .putFields("timeout", Value.newBuilder().setStringValue("1.234s").build())
          .build();
  private static final Struct EXPECTED_STRUCT_LOG_PROTO = Struct.newBuilder()
      .putFields("sequenceId", Value.newBuilder().setStringValue(String.valueOf(SEQ_ID)).build())
      .putFields("serviceName", Value.newBuilder().setStringValue(SERVICE_NAME).build())
      .putFields("methodName", Value.newBuilder().setStringValue(METHOD_NAME).build())
      .putFields("authority", Value.newBuilder().setStringValue(AUTHORITY).build())
      .putFields("payload", Value.newBuilder().setStructValue(struct).build())
      .putFields("type", Value.newBuilder().setStringValue(
          String.valueOf(EventType.CLIENT_HEADER)).build())
      .putFields("logger", Value.newBuilder().setStringValue(
          String.valueOf(EventLogger.CLIENT)).build())
      .putFields("callId", Value.newBuilder().setStringValue(CALL_ID).build())
      .build();
  @Mock
  private Logging mockLogging;
  @Mock
  private ObservabilityConfig mockConfig;

  @Test
  @SuppressWarnings("unchecked")
  public void verifyWrite() throws Exception {
    when(mockConfig.getCustomTags()).thenReturn(CUSTOM_TAGS);
    GcpLogSink sink = new GcpLogSink(mockLogging, DEST_PROJECT_NAME,
        mockConfig, Collections.emptySet(), new TraceLoggingHelper(DEST_PROJECT_NAME));
    sink.write(LOG_PROTO, null);

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
    when(mockConfig.getCustomTags()).thenReturn(CUSTOM_TAGS);
    GcpLogSink sink = new GcpLogSink(mockLogging, DEST_PROJECT_NAME,
        mockConfig, Collections.emptySet(), new TraceLoggingHelper(DEST_PROJECT_NAME));
    sink.write(LOG_PROTO, null);

    ArgumentCaptor<Collection<LogEntry>> logEntrySetCaptor = ArgumentCaptor.forClass(
        (Class) Collection.class);
    verify(mockLogging, times(1)).write(logEntrySetCaptor.capture());
    System.out.println(logEntrySetCaptor.getValue());
    for (Iterator<LogEntry> it = logEntrySetCaptor.getValue().iterator(); it.hasNext(); ) {
      LogEntry entry = it.next();
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
    when(mockConfig.getCustomTags()).thenReturn(emptyCustomTags);
    Map<String, String> expectedEmptyLabels = new HashMap<>();
    GcpLogSink sink = new GcpLogSink(mockLogging, DEST_PROJECT_NAME,
        mockConfig, Collections.emptySet(), new TraceLoggingHelper(DEST_PROJECT_NAME));
    sink.write(LOG_PROTO, null);

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
    when(mockConfig.getCustomTags()).thenReturn(emptyCustomTags);
    String projectId = "PROJECT";
    Map<String, String> expectedLabels = GcpLogSink.getCustomTags(emptyCustomTags
    );
    GcpLogSink sink = new GcpLogSink(mockLogging, projectId,
        mockConfig, Collections.emptySet(), new TraceLoggingHelper(DEST_PROJECT_NAME));
    sink.write(LOG_PROTO, null);

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
  public void verifyClose() throws Exception {
    GcpLogSink sink = new GcpLogSink(mockLogging, DEST_PROJECT_NAME,
        mockConfig, Collections.emptySet(), new TraceLoggingHelper(DEST_PROJECT_NAME));
    sink.write(LOG_PROTO, null);
    verify(mockLogging, times(1)).write(anyIterable());
    sink.close();
    verify(mockLogging).close();
    verifyNoMoreInteractions(mockLogging);
  }

  @Test
  public void verifyExclude() throws Exception {
    Sink mockSink = new GcpLogSink(mockLogging, DEST_PROJECT_NAME,
        mockConfig, Collections.singleton("service"), new TraceLoggingHelper(DEST_PROJECT_NAME));
    mockSink.write(LOG_PROTO, null);
    verifyNoInteractions(mockLogging);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void verifyNoTraceDataInLogs_withTraceDisabled() throws Exception {
    SpanContext validSpanContext = SpanContext.create(
        TraceId.fromLowerBase16("4c6af40c499951eb7de2777ba1e4fefa"),
        SpanId.fromLowerBase16("de52e84d13dd232d"),
        TraceOptions.builder().setIsSampled(true).build(),
        Tracestate.builder().build());

    TraceLoggingHelper traceLoggingHelper = new TraceLoggingHelper(DEST_PROJECT_NAME);
    when(mockConfig.isEnableCloudTracing()).thenReturn(false);
    Sink mockSink = new GcpLogSink(mockLogging, DEST_PROJECT_NAME,
        mockConfig, Collections.emptySet(), traceLoggingHelper);
    mockSink.write(LOG_PROTO, validSpanContext);

    ArgumentCaptor<Collection<LogEntry>> logEntrySetCaptor = ArgumentCaptor.forClass(
        (Class) Collection.class);
    verify(mockLogging, times(1)).write(logEntrySetCaptor.capture());
    for (Iterator<LogEntry> it = logEntrySetCaptor.getValue().iterator(); it.hasNext(); ) {
      LogEntry entry = it.next();
      assertThat(entry.getTrace()).isNull(); // Field not present
      assertThat(entry.getSpanId()).isNull(); // Field not present
      assertThat(entry.getTraceSampled()).isFalse(); // Default value
      assertThat(entry.getPayload().getData()).isEqualTo(EXPECTED_STRUCT_LOG_PROTO);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void verifyTraceDataInLogs_withValidSpanContext() throws Exception {
    CharSequence traceIdSeq = "4c6af40c499951eb7de2777ba1e4fefa";
    CharSequence spanIdSeq = "de52e84d13dd232d";
    TraceId traceId = TraceId.fromLowerBase16(traceIdSeq);
    SpanId spanId = SpanId.fromLowerBase16(spanIdSeq);
    boolean traceSampled = true;

    SpanContext validSpanContext = SpanContext.create(traceId, spanId,
        TraceOptions.builder().setIsSampled(traceSampled).build(),
        Tracestate.builder().build());

    TraceLoggingHelper traceLoggingHelper = new TraceLoggingHelper(DEST_PROJECT_NAME);
    when(mockConfig.isEnableCloudTracing()).thenReturn(true);
    Sink mockSink = new GcpLogSink(mockLogging, DEST_PROJECT_NAME,
        mockConfig, Collections.emptySet(), traceLoggingHelper);
    mockSink.write(LOG_PROTO, validSpanContext);

    String expectedTrace = "projects/" + DEST_PROJECT_NAME + "/traces/" + traceIdSeq;

    ArgumentCaptor<Collection<LogEntry>> logEntrySetCaptor = ArgumentCaptor.forClass(
        (Class) Collection.class);
    verify(mockLogging, times(1)).write(logEntrySetCaptor.capture());
    for (Iterator<LogEntry> it = logEntrySetCaptor.getValue().iterator(); it.hasNext(); ) {
      LogEntry entry = it.next();
      assertThat(entry.getTrace()).isEqualTo(expectedTrace);
      assertThat(entry.getSpanId()).isEqualTo("" + spanIdSeq);
      assertThat(entry.getTraceSampled()).isEqualTo(traceSampled);
      assertThat(entry.getPayload().getData()).isEqualTo(EXPECTED_STRUCT_LOG_PROTO);
    }

  }

  @Test
  @SuppressWarnings("unchecked")
  public void verifyTraceDataLogs_withNullSpanContext() throws Exception {
    TraceLoggingHelper traceLoggingHelper = new TraceLoggingHelper(DEST_PROJECT_NAME);
    when(mockConfig.isEnableCloudTracing()).thenReturn(true);
    Sink mockSink = new GcpLogSink(mockLogging, DEST_PROJECT_NAME,
        mockConfig, Collections.emptySet(), traceLoggingHelper);

    String expectedTrace =
        "projects/" + DEST_PROJECT_NAME + "/traces/00000000000000000000000000000000";
    String expectedSpanId = "0000000000000000";

    ArgumentCaptor<Collection<LogEntry>> logEntrySetCaptor = ArgumentCaptor.forClass(
        (Class) Collection.class);

    // Client log with default span context
    mockSink.write(LOG_PROTO , SpanContext.INVALID);
    verify(mockLogging, times(1)).write(logEntrySetCaptor.capture());
    for (Iterator<LogEntry> it = logEntrySetCaptor.getValue().iterator(); it.hasNext(); ) {
      LogEntry entry = it.next();
      assertThat(entry.getTrace()).isEqualTo(expectedTrace);
      assertThat(entry.getSpanId()).isEqualTo(expectedSpanId);
      assertThat(entry.getTraceSampled()).isFalse();
    }

    // Server log
    GrpcLogRecord serverLogProto = LOG_PROTO.toBuilder().setLogger(EventLogger.SERVER).build();
    mockSink.write(serverLogProto , SpanContext.INVALID);
    verify(mockLogging, times(2)).write(logEntrySetCaptor.capture());
    for (Iterator<LogEntry> it = logEntrySetCaptor.getValue().iterator(); it.hasNext(); ) {
      LogEntry entry = it.next();
      assertThat(entry.getTrace()).isEqualTo(expectedTrace);
      assertThat(entry.getSpanId()).isEqualTo(expectedSpanId);
      assertThat(entry.getTraceSampled()).isFalse();
    }
  }
}
