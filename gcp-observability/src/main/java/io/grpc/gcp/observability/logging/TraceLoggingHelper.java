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

import com.google.cloud.logging.LogEntry;
import com.google.common.annotations.VisibleForTesting;
import io.grpc.Internal;
import io.opencensus.trace.SpanContext;
import io.opencensus.trace.TraceId;

@Internal
public class TraceLoggingHelper {

  private final String tracePrefix;

  public TraceLoggingHelper(String projectId) {
    this.tracePrefix = "projects/" + projectId + "/traces/";;
  }

  @VisibleForTesting
  void enhanceLogEntry(LogEntry.Builder builder, SpanContext spanContext) {
    addTracingData(tracePrefix, spanContext, builder);
  }

  private static void addTracingData(
      String tracePrefix, SpanContext spanContext, LogEntry.Builder builder) {
    builder.setTrace(formatTraceId(tracePrefix, spanContext.getTraceId()));
    builder.setSpanId(spanContext.getSpanId().toLowerBase16());
    builder.setTraceSampled(spanContext.getTraceOptions().isSampled());
  }

  private static String formatTraceId(String tracePrefix, TraceId traceId) {
    return tracePrefix + traceId.toLowerBase16();
  }
}
