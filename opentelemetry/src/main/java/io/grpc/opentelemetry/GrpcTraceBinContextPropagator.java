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
import static io.grpc.InternalMetadata.BASE64_ENCODING_OMIT_PADDING;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.BaseEncoding;
import io.grpc.ExperimentalApi;
import io.grpc.Metadata;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.Collection;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 *  A {@link TextMapPropagator} for transmitting "grpc-trace-bin" span context.
 *
 *  <p>This propagator can transmit the "grpc-trace-bin" context in either binary or Base64-encoded
 * text format, depending on the capabilities of the provided {@link TextMapGetter} and
 * {@link TextMapSetter}.
 *
 *  <p>If the {@code TextMapGetter} and {@code TextMapSetter} only support text format, Base64
 *  encoding and decoding will be used when communicating with the carrier API. But gRPC uses
 *  it with gRPC's metadata-based getter/setter, and the propagator can directly transmit the binary
 *  header, avoiding the need for Base64 encoding.
 */

@ExperimentalApi("https://github.com/grpc/grpc-java/issues/11400")
public final class GrpcTraceBinContextPropagator implements TextMapPropagator {
  private static final Logger log = Logger.getLogger(GrpcTraceBinContextPropagator.class.getName());
  public static final String GRPC_TRACE_BIN_HEADER = "grpc-trace-bin";
  private final Metadata.BinaryMarshaller<SpanContext> binaryFormat;
  private static final GrpcTraceBinContextPropagator INSTANCE =
      new GrpcTraceBinContextPropagator(BinaryFormat.getInstance());

  public static GrpcTraceBinContextPropagator defaultInstance() {
    return INSTANCE;
  }

  @VisibleForTesting
  GrpcTraceBinContextPropagator(Metadata.BinaryMarshaller<SpanContext> binaryFormat) {
    this.binaryFormat = checkNotNull(binaryFormat, "binaryFormat");
  }

  @Override
  public Collection<String> fields() {
    return Collections.singleton(GRPC_TRACE_BIN_HEADER);
  }

  @Override
  public <C> void inject(Context context, @Nullable C carrier, TextMapSetter<C> setter) {
    if (context == null || setter == null) {
      return;
    }
    SpanContext spanContext = Span.fromContext(context).getSpanContext();
    if (!spanContext.isValid()) {
      return;
    }
    try {
      byte[] b = binaryFormat.toBytes(spanContext);
      if (setter instanceof MetadataSetter) {
        ((MetadataSetter) setter).set((Metadata) carrier, GRPC_TRACE_BIN_HEADER, b);
      } else {
        setter.set(carrier, GRPC_TRACE_BIN_HEADER, BASE64_ENCODING_OMIT_PADDING.encode(b));
      }
    } catch (Exception e) {
      log.log(Level.FINE, "Set grpc-trace-bin spanContext failed", e);
    }
  }

  @Override
  public <C> Context extract(Context context, @Nullable C carrier, TextMapGetter<C> getter) {
    if (context == null) {
      return Context.root();
    }
    if (getter == null) {
      return context;
    }
    byte[] b;
    if (getter instanceof MetadataGetter) {
      try {
        b = ((MetadataGetter) getter).getBinary((Metadata) carrier, GRPC_TRACE_BIN_HEADER);
        if (b == null) {
          log.log(Level.FINE, "No grpc-trace-bin present in carrier");
          return context;
        }
      } catch (Exception e) {
        log.log(Level.FINE, "Get 'grpc-trace-bin' from MetadataGetter failed", e);
        return context;
      }
    } else {
      String value;
      try {
        value = getter.get(carrier, GRPC_TRACE_BIN_HEADER);
        if (value == null) {
          log.log(Level.FINE, "No grpc-trace-bin present in carrier");
          return context;
        }
      } catch (Exception e) {
        log.log(Level.FINE, "Get 'grpc-trace-bin' from getter failed", e);
        return context;
      }
      try {
        b = BaseEncoding.base64().decode(value);
      } catch (Exception e) {
        log.log(Level.FINE, "Base64-decode spanContext bytes failed", e);
        return context;
      }
    }

    SpanContext spanContext;
    try {
      spanContext = binaryFormat.parseBytes(b);
    } catch (Exception e) {
      log.log(Level.FINE, "Failed to parse tracing header", e);
      return context;
    }
    if (!spanContext.isValid()) {
      return context;
    }
    return context.with(Span.wrap(spanContext));
  }
}
