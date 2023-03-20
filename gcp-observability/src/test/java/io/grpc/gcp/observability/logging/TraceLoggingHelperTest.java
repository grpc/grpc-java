/*
 * Copyright 2023 The gRPC Authors
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

import com.google.cloud.logging.LogEntry;
import io.opencensus.trace.SpanContext;
import io.opencensus.trace.SpanId;
import io.opencensus.trace.TraceId;
import io.opencensus.trace.TraceOptions;
import io.opencensus.trace.Tracestate;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link TraceLoggingHelper}.
 */
@RunWith(JUnit4.class)
public class TraceLoggingHelperTest {

  private static final String PROJECT = "PROJECT";
  private static final Tracestate EMPTY_TRACESTATE = Tracestate.builder().build();
  private static TraceLoggingHelper traceLoggingHelper;

  @Before
  public void setUp() {
    traceLoggingHelper = new TraceLoggingHelper(PROJECT);
  }

  @Test
  public void enhanceLogEntry_AddSampledSpanContextToLogEntry() {
    SpanContext spanContext = SpanContext.create(
        TraceId.fromLowerBase16("5ce724c382c136b2a67bb447e6a6bd27"),
        SpanId.fromLowerBase16("de52e84d13dd232d"),
        TraceOptions.builder().setIsSampled(true).build(),
        EMPTY_TRACESTATE);
    LogEntry logEntry = getEnhancedLogEntry(traceLoggingHelper, spanContext);
    assertThat(logEntry.getTraceSampled()).isTrue();
    assertThat(logEntry.getTrace())
        .isEqualTo("projects/PROJECT/traces/5ce724c382c136b2a67bb447e6a6bd27");
    assertThat(logEntry.getSpanId()).isEqualTo("de52e84d13dd232d");
  }

  @Test
  public void enhanceLogEntry_AddNonSampledSpanContextToLogEntry() {
    SpanContext spanContext = SpanContext.create(
        TraceId.fromLowerBase16("649a8a64db2d0c757fd06bb1bfe84e2c"),
        SpanId.fromLowerBase16("731e102335b7a5a0"),
        TraceOptions.builder().setIsSampled(false).build(),
        EMPTY_TRACESTATE);
    LogEntry logEntry = getEnhancedLogEntry(traceLoggingHelper, spanContext);
    assertThat(logEntry.getTraceSampled()).isFalse();
    assertThat(logEntry.getTrace())
        .isEqualTo("projects/PROJECT/traces/649a8a64db2d0c757fd06bb1bfe84e2c");
    assertThat(logEntry.getSpanId()).isEqualTo("731e102335b7a5a0");
  }

  @Test
  public void enhanceLogEntry_AddBlankSpanContextToLogEntry() {
    SpanContext spanContext = SpanContext.INVALID;
    LogEntry logEntry = getEnhancedLogEntry(traceLoggingHelper, spanContext);
    assertThat(logEntry.getTraceSampled()).isFalse();
    assertThat(logEntry.getTrace())
        .isEqualTo("projects/PROJECT/traces/00000000000000000000000000000000");
    assertThat(logEntry.getSpanId()).isEqualTo("0000000000000000");
  }

  private static LogEntry getEnhancedLogEntry(TraceLoggingHelper traceLoggingHelper,
      SpanContext spanContext) {
    LogEntry.Builder logEntryBuilder = LogEntry.newBuilder(null);
    traceLoggingHelper.enhanceLogEntry(logEntryBuilder, spanContext);
    return logEntryBuilder.build();
  }
}
