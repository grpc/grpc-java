/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.opentelemetry;


import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.Metadata;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.api.trace.TraceState;
import java.util.Arrays;

/**
 *  Binary encoded {@link SpanContext} for context propagation. This is adapted from OpenCensus
 *  binary format.
 *
 * <p>BinaryFormat format:
 *
 * <ul>
 *   <li>Binary value: &lt;version_id&gt;&lt;version_format&gt;
 *   <li>version_id: 1-byte representing the version id.
 *   <li>For version_id = 0:
 *       <ul>
 *         <li>version_format: &lt;field&gt;&lt;field&gt;
 *         <li>field_format: &lt;field_id&gt;&lt;field_format&gt;
 *         <li>Fields:
 *             <ul>
 *               <li>TraceId: (field_id = 0, len = 16, default = &#34;0000000000000000&#34;) -
 *                   16-byte array representing the trace_id.
 *               <li>SpanId: (field_id = 1, len = 8, default = &#34;00000000&#34;) - 8-byte array
 *                   representing the span_id.
 *               <li>TraceFlags: (field_id = 2, len = 1, default = &#34;0&#34;) - 1-byte array
 *                   representing the trace_flags.
 *             </ul>
 *         <li>Fields MUST be encoded using the field id order (smaller to higher).
 *         <li>Valid value example:
 *             <ul>
 *               <li>{0, 0, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 1, 97,
 *                   98, 99, 100, 101, 102, 103, 104, 2, 1}
 *               <li>version_id = 0;
 *               <li>trace_id = {64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79}
 *               <li>span_id = {97, 98, 99, 100, 101, 102, 103, 104};
 *               <li>trace_flags = {1};
 *             </ul>
 *       </ul>
 * </ul>
 */
final class BinaryFormat implements Metadata.BinaryMarshaller<SpanContext> {
  private static final byte VERSION_ID = 0;
  private static final int VERSION_ID_OFFSET = 0;
  private static final byte ID_SIZE = 1;
  private static final byte TRACE_ID_FIELD_ID = 0;

  private static final int TRACE_ID_FIELD_ID_OFFSET = VERSION_ID_OFFSET + ID_SIZE;
  private static final int TRACE_ID_OFFSET = TRACE_ID_FIELD_ID_OFFSET + ID_SIZE;
  private static final int TRACE_ID_SIZE = TraceId.getLength() / 2;

  private static final byte SPAN_ID_FIELD_ID = 1;
  private static final int SPAN_ID_FIELD_ID_OFFSET = TRACE_ID_OFFSET + TRACE_ID_SIZE;
  private static final int SPAN_ID_OFFSET = SPAN_ID_FIELD_ID_OFFSET + ID_SIZE;
  private static final int SPAN_ID_SIZE = SpanId.getLength() / 2;

  private static final byte TRACE_FLAG_FIELD_ID = 2;
  private static final int TRACE_FLAG_FIELD_ID_OFFSET = SPAN_ID_OFFSET + SPAN_ID_SIZE;
  private static final int TRACE_FLAG_OFFSET = TRACE_FLAG_FIELD_ID_OFFSET + ID_SIZE;
  private static final int REQUIRED_FORMAT_LENGTH = 3 * ID_SIZE + TRACE_ID_SIZE + SPAN_ID_SIZE;
  private static final int TRACE_FLAG_SIZE = TraceFlags.getLength() / 2;
  private static final int ALL_FORMAT_LENGTH = REQUIRED_FORMAT_LENGTH + ID_SIZE + TRACE_FLAG_SIZE;

  private static final BinaryFormat INSTANCE = new BinaryFormat();

  public static BinaryFormat getInstance() {
    return INSTANCE;
  }

  @Override
  public byte[] toBytes(SpanContext spanContext) {
    checkNotNull(spanContext, "spanContext");
    byte[] bytes = new byte[ALL_FORMAT_LENGTH];
    bytes[VERSION_ID_OFFSET] = VERSION_ID;
    bytes[TRACE_ID_FIELD_ID_OFFSET] = TRACE_ID_FIELD_ID;
    System.arraycopy(spanContext.getTraceIdBytes(), 0, bytes, TRACE_ID_OFFSET, TRACE_ID_SIZE);
    bytes[SPAN_ID_FIELD_ID_OFFSET] = SPAN_ID_FIELD_ID;
    System.arraycopy(spanContext.getSpanIdBytes(), 0, bytes, SPAN_ID_OFFSET, SPAN_ID_SIZE);
    bytes[TRACE_FLAG_FIELD_ID_OFFSET] = TRACE_FLAG_FIELD_ID;
    bytes[TRACE_FLAG_OFFSET] = spanContext.getTraceFlags().asByte();
    return bytes;
  }


  @Override
  public SpanContext parseBytes(byte[] serialized) {
    checkNotNull(serialized, "bytes");
    if (serialized.length == 0 || serialized[0] != VERSION_ID) {
      throw new IllegalArgumentException("Unsupported version.");
    }
    if (serialized.length < REQUIRED_FORMAT_LENGTH) {
      throw new IllegalArgumentException("Invalid input: truncated");
    }
    String traceId;
    String spanId;
    TraceFlags traceFlags = TraceFlags.getDefault();
    int pos = 1;
    if (serialized[pos] == TRACE_ID_FIELD_ID) {
      traceId = TraceId.fromBytes(
          Arrays.copyOfRange(serialized, pos + ID_SIZE, pos + ID_SIZE + TRACE_ID_SIZE));
      pos += ID_SIZE + TRACE_ID_SIZE;
    } else {
      throw new IllegalArgumentException("Invalid input: expected trace ID at offset " + pos);
    }
    if (serialized[pos] == SPAN_ID_FIELD_ID) {
      spanId = SpanId.fromBytes(
          Arrays.copyOfRange(serialized, pos + ID_SIZE, pos + ID_SIZE + SPAN_ID_SIZE));
      pos += ID_SIZE + SPAN_ID_SIZE;
    } else {
      throw new IllegalArgumentException("Invalid input: expected span ID at offset " + pos);
    }
    if (serialized.length > pos && serialized[pos] == TRACE_FLAG_FIELD_ID) {
      if (serialized.length < ALL_FORMAT_LENGTH) {
        throw new IllegalArgumentException("Invalid input: truncated");
      }
      traceFlags = TraceFlags.fromByte(serialized[pos + ID_SIZE]);
    }
    return SpanContext.create(traceId, spanId, traceFlags, TraceState.getDefault());
  }
}
