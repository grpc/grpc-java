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

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.InternalMetadata.BASE64_ENCODING_OMIT_PADDING;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableMap;
import io.grpc.Metadata;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GrpcTraceBinContextPropagatorTest {
  private static final String TRACE_ID_BASE16 = "e384981d65129fa3e384981d65129fa3";
  private static final String SPAN_ID_BASE16 = "e384981d65129fa3";
  private static final String TRACE_HEADER_SAMPLED =
      "0000" + TRACE_ID_BASE16 + "01" + SPAN_ID_BASE16 + "0201";
  private static final String TRACE_HEADER_NOT_SAMPLED =
      "0000" + TRACE_ID_BASE16 + "01" + SPAN_ID_BASE16 + "0200";
  private final String goldenHeaderEncodedSampled = encode(TRACE_HEADER_SAMPLED);
  private final String goldenHeaderEncodedNotSampled = encode(TRACE_HEADER_NOT_SAMPLED);
  private static final TextMapSetter<Map<String, String>> setter = Map::put;
  private static final TextMapGetter<Map<String, String>> getter =
      new TextMapGetter<Map<String, String>>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
          return carrier.keySet();
        }

        @Nullable
        @Override
        public String get(Map<String, String> carrier, String key) {
          return carrier.get(key);
        }
      };
  private final GrpcTraceBinContextPropagator grpcTraceBinContextPropagator =
      GrpcTraceBinContextPropagator.defaultInstance();

  private static Context withSpanContext(SpanContext spanContext, Context context) {
    return context.with(Span.wrap(spanContext));
  }

  private static SpanContext getSpanContext(Context context) {
    return Span.fromContext(context).getSpanContext();
  }

  @Test
  public void inject_map_Nothing() {
    Map<String, String> carrier = new HashMap<>();
    grpcTraceBinContextPropagator.inject(Context.current(), carrier, setter);
    assertThat(carrier).hasSize(0);
  }

  @Test
  public void inject_map_invalidSpan() {
    Map<String, String> carrier = new HashMap<>();
    Context context = withSpanContext(SpanContext.getInvalid(), Context.current());
    grpcTraceBinContextPropagator.inject(context, carrier, setter);
    assertThat(carrier).isEmpty();
  }

  @Test
  public void inject_map_nullCarrier() {
    Map<String, String> carrier = new HashMap<>();
    Context context =
        withSpanContext(
            SpanContext.create(
                TRACE_ID_BASE16, SPAN_ID_BASE16, TraceFlags.getSampled(), TraceState.getDefault()),
            Context.current());
    grpcTraceBinContextPropagator.inject(context, null,
        (TextMapSetter<Map<String, String>>) (ignored, key, value) -> carrier.put(key, value));
    assertThat(carrier)
        .containsExactly(
            GrpcTraceBinContextPropagator.GRPC_TRACE_BIN_HEADER, goldenHeaderEncodedSampled);
  }

  @Test
  public void inject_map_nullContext() {
    Map<String, String> carrier = new HashMap<>();
    grpcTraceBinContextPropagator.inject(null, carrier, setter);
    assertThat(carrier).isEmpty();
  }

  @Test
  public void inject_map_invalidBinaryFormat() {
    GrpcTraceBinContextPropagator propagator = new GrpcTraceBinContextPropagator(
        new Metadata.BinaryMarshaller<SpanContext>() {
          @Override
          public byte[] toBytes(SpanContext value) {
            throw new IllegalArgumentException("failed to byte");
          }

          @Override
          public SpanContext parseBytes(byte[] serialized) {
            return null;
          }
        });
    Map<String, String> carrier = new HashMap<>();
    Context context =
        withSpanContext(
            SpanContext.create(
                TRACE_ID_BASE16, SPAN_ID_BASE16, TraceFlags.getSampled(), TraceState.getDefault()),
            Context.current());
    propagator.inject(context, carrier, setter);
    assertThat(carrier).hasSize(0);
  }

  @Test
  public void inject_map_SampledContext() {
    verify_inject_map(TraceFlags.getSampled(), goldenHeaderEncodedSampled);
  }

  @Test
  public void inject_map_NotSampledContext() {
    verify_inject_map(TraceFlags.getDefault(), goldenHeaderEncodedNotSampled);
  }

  private void verify_inject_map(TraceFlags traceFlags, String goldenHeader) {
    Map<String, String> carrier = new HashMap<>();
    Context context =
        withSpanContext(
            SpanContext.create(
                TRACE_ID_BASE16, SPAN_ID_BASE16, traceFlags, TraceState.getDefault()),
            Context.current());
    grpcTraceBinContextPropagator.inject(context, carrier, setter);
    assertThat(carrier)
        .containsExactly(
            GrpcTraceBinContextPropagator.GRPC_TRACE_BIN_HEADER, goldenHeader);
  }

  @Test
  public void extract_map_nothing() {
    Map<String, String> carrier = new HashMap<>();
    assertThat(grpcTraceBinContextPropagator.extract(Context.current(), carrier, getter))
        .isSameInstanceAs(Context.current());
  }

  @Test
  public void extract_map_SampledContext() {
    verify_extract_map(TraceFlags.getSampled(), goldenHeaderEncodedSampled);
  }

  @Test
  public void extract_map_NotSampledContext() {
    verify_extract_map(TraceFlags.getDefault(), goldenHeaderEncodedNotSampled);
  }

  private void verify_extract_map(TraceFlags traceFlags, String goldenHeader) {
    Map<String, String> carrier = ImmutableMap.of(
        GrpcTraceBinContextPropagator.GRPC_TRACE_BIN_HEADER, goldenHeader);
    Context result = grpcTraceBinContextPropagator.extract(Context.current(), carrier, getter);
    assertThat(getSpanContext(result)).isEqualTo(SpanContext.create(
        TRACE_ID_BASE16, SPAN_ID_BASE16, traceFlags, TraceState.getDefault()));
  }

  @Test
  public void inject_metadata_Nothing() {
    Metadata carrier = new Metadata();
    grpcTraceBinContextPropagator.inject(Context.current(), carrier, MetadataSetter.getInstance());
    assertThat(carrier.keys()).isEmpty();
  }

  @Test
  public void inject_metadata_nullCarrier() {
    Context context =
        withSpanContext(
            SpanContext.create(
                TRACE_ID_BASE16, SPAN_ID_BASE16, TraceFlags.getSampled(), TraceState.getDefault()),
            Context.current());
    grpcTraceBinContextPropagator.inject(context, null, MetadataSetter.getInstance());
  }

  @Test
  public void inject_metadata_invalidSpan() {
    Metadata carrier = new Metadata();
    Context context = withSpanContext(SpanContext.getInvalid(), Context.current());
    grpcTraceBinContextPropagator.inject(context, carrier, MetadataSetter.getInstance());
    assertThat(carrier.keys()).isEmpty();
  }

  @Test
  public void inject_metadata_SampledContext() {
    verify_inject_metadata(TraceFlags.getSampled(), hexStringToByteArray(TRACE_HEADER_SAMPLED));
  }

  @Test
  public void inject_metadataSetter_NotSampledContext() {
    verify_inject_metadata(TraceFlags.getDefault(), hexStringToByteArray(TRACE_HEADER_NOT_SAMPLED));
  }

  private void verify_inject_metadata(TraceFlags traceFlags, byte[] bytes) {
    Metadata metadata = new Metadata();
    Context context =
        withSpanContext(
            SpanContext.create(
                TRACE_ID_BASE16, SPAN_ID_BASE16, traceFlags, TraceState.getDefault()),
            Context.current());
    grpcTraceBinContextPropagator.inject(context, metadata, MetadataSetter.getInstance());
    byte[] injected = metadata.get(Metadata.Key.of(
        GrpcTraceBinContextPropagator.GRPC_TRACE_BIN_HEADER, Metadata.BINARY_BYTE_MARSHALLER));
    assertTrue(Arrays.equals(injected, bytes));
  }

  @Test
  public void extract_metadata_nothing() {
    assertThat(grpcTraceBinContextPropagator.extract(
        Context.current(), new Metadata(), MetadataGetter.getInstance()))
        .isSameInstanceAs(Context.current());
  }

  @Test
  public void extract_metadata_nullCarrier() {
    assertThat(grpcTraceBinContextPropagator.extract(
        Context.current(), null, MetadataGetter.getInstance()))
        .isSameInstanceAs(Context.current());
  }

  @Test
  public void extract_metadata_SampledContext() {
    verify_extract_metadata(TraceFlags.getSampled(), TRACE_HEADER_SAMPLED);
  }

  @Test
  public void extract_metadataGetter_NotSampledContext() {
    verify_extract_metadata(TraceFlags.getDefault(), TRACE_HEADER_NOT_SAMPLED);
  }

  private void verify_extract_metadata(TraceFlags traceFlags, String hex) {
    Metadata carrier = new Metadata();
    carrier.put(Metadata.Key.of(
            GrpcTraceBinContextPropagator.GRPC_TRACE_BIN_HEADER, Metadata.BINARY_BYTE_MARSHALLER),
        hexStringToByteArray(hex));
    Context result = grpcTraceBinContextPropagator.extract(Context.current(), carrier,
        MetadataGetter.getInstance());
    assertThat(getSpanContext(result)).isEqualTo(SpanContext.create(
        TRACE_ID_BASE16, SPAN_ID_BASE16, traceFlags, TraceState.getDefault()));
  }

  @Test
  public void extract_metadata_invalidBinaryFormat() {
    GrpcTraceBinContextPropagator propagator = new GrpcTraceBinContextPropagator(
        new Metadata.BinaryMarshaller<SpanContext>() {
          @Override
          public byte[] toBytes(SpanContext value) {
            return new byte[0];
          }

          @Override
          public SpanContext parseBytes(byte[] serialized) {
            throw new IllegalArgumentException("failed to byte");
          }
        });
    Metadata carrier = new Metadata();
    carrier.put(Metadata.Key.of(
            GrpcTraceBinContextPropagator.GRPC_TRACE_BIN_HEADER, Metadata.BINARY_BYTE_MARSHALLER),
        hexStringToByteArray(TRACE_HEADER_SAMPLED));
    assertThat(propagator.extract(Context.current(), carrier, MetadataGetter.getInstance()))
        .isSameInstanceAs(Context.current());
  }

  @Test
  public void extract_metadata_invalidBinaryFormatVersion() {
    Metadata carrier = new Metadata();
    carrier.put(Metadata.Key.of(
            GrpcTraceBinContextPropagator.GRPC_TRACE_BIN_HEADER, Metadata.BINARY_BYTE_MARSHALLER),
        hexStringToByteArray("0100" + TRACE_ID_BASE16 + "01" + SPAN_ID_BASE16 + "0201"));
    assertThat(grpcTraceBinContextPropagator.extract(
        Context.current(), carrier, MetadataGetter.getInstance()))
        .isSameInstanceAs(Context.current());
  }

  private static String encode(String hex) {
    return BASE64_ENCODING_OMIT_PADDING.encode(hexStringToByteArray(hex));
  }

  private static byte[] hexStringToByteArray(String s) {
    int len = s.length();
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
          + Character.digit(s.charAt(i + 1), 16));
    }
    return data;
  }
}
