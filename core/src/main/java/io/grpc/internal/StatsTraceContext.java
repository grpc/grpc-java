/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientStreamTracer;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.ServerStreamTracer;
import io.grpc.ServerStreamTracer.ServerCallInfo;
import io.grpc.Status;
import io.grpc.StreamTracer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.concurrent.ThreadSafe;

/**
 * The stats and tracing information for a stream.
 */
@ThreadSafe
public final class StatsTraceContext {
  /** Allows callers to buffer stats and tracing event reporting until the call has begun. */
  public interface ServerCallStartedListener {
    void serverCallStarted();
  }

  public static final StatsTraceContext NOOP = new StatsTraceContext(new StreamTracer[0]);

  private final StreamTracer[] tracers;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private volatile StreamTracer[] interceptorTracers = new StreamTracer[0];
  private final ServerCallStartedListener serverCallStartedListener;

  /**
   * Factory method for the client-side.
   */
  public static StatsTraceContext newClientContext(
      final CallOptions callOptions, final Attributes transportAttrs, Metadata headers) {
    List<ClientStreamTracer.Factory> factories = callOptions.getStreamTracerFactories();
    if (factories.isEmpty()) {
      return NOOP;
    }
    ClientStreamTracer.StreamInfo info =
        ClientStreamTracer.StreamInfo.newBuilder()
            .setTransportAttrs(transportAttrs).setCallOptions(callOptions).build();
    // This array will be iterated multiple times per RPC. Use primitive array instead of Collection
    // so that for-each doesn't create an Iterator every time.
    StreamTracer[] tracers = new StreamTracer[factories.size()];
    for (int i = 0; i < tracers.length; i++) {
      tracers[i] = factories.get(i).newClientStreamTracer(info, headers);
    }
    return new StatsTraceContext(tracers);
  }

  /**
   * Factory method for the server-side.
   */
  public static StatsTraceContext newServerContext(
      List<? extends ServerStreamTracer.Factory> factories,
      String fullMethodName,
      Metadata headers) {
    return newServerContext(factories, fullMethodName, headers, null);
  }

  /** Factory method for the server-side that accepts a {@link ServerCallStartedListener}. */
  public static StatsTraceContext newServerContext(
      List<? extends ServerStreamTracer.Factory> factories,
      String fullMethodName,
      Metadata headers,
      ServerCallStartedListener listener) {
    StreamTracer[] tracers = new StreamTracer[factories.size()];
    for (int i = 0; i < tracers.length; i++) {
      tracers[i] = factories.get(i).newServerStreamTracer(fullMethodName, headers);
    }
    return new StatsTraceContext(tracers, listener);
  }

  /**
   * Add stream tracers from server interceptors. These are not available until after the call has
   * begun, so if a call terminates early these tracers may never be invoked. Expected usage is
   * that only {@link #serverFilterContext}, and potentially {@link #streamClosed}, will be called
   * prior to this method, but it is up to the caller to enforce this ordering.
   *
   * <p>The newly added tracers will have their {@link ServerStreamTracer#filterContext} methods
   * applied to the returned {@code Context}.
   */
  public Context.CancellableContext setServerInterceptorTracersAndFilterContext(
      List<? extends ServerStreamTracer.Factory> factories,
      String fullMethodName,
      Metadata headers,
      Context.CancellableContext context) {
    if (!factories.isEmpty()) {
      StreamTracer[] tracerArr = new StreamTracer[factories.size()];
      for (int i = 0; i < tracerArr.length; i++) {
        ServerStreamTracer tracer = factories.get(i).newServerStreamTracer(fullMethodName, headers);
        context = tracer.filterContext(context).withCancellation();
        tracerArr[i] = tracer;
      }
      interceptorTracers = tracerArr;
    }
    return context;
  }

  @VisibleForTesting
  StatsTraceContext(StreamTracer[] tracers) {
    this(tracers, null);
  }

  StatsTraceContext(StreamTracer[] tracers, ServerCallStartedListener listener) {
    this.tracers = tracers;
    this.serverCallStartedListener = listener;
  }

  /**
   * Returns a copy of the tracer list.
   */
  @VisibleForTesting
  public List<StreamTracer> getTracersForTest() {
    return new ArrayList<>(Arrays.asList(tracers));
  }

  /**
   * See {@link ClientStreamTracer#outboundHeaders}.  For client-side only.
   *
   * <p>Transport-specific, thus should be called by transport implementations.
   */
  public void clientOutboundHeaders() {
    for (StreamTracer tracer : tracers) {
      ((ClientStreamTracer) tracer).outboundHeaders();
    }
  }

  /**
   * See {@link ClientStreamTracer#inboundHeaders}.  For client-side only.
   *
   * <p>Called from abstract stream implementations.
   */
  public void clientInboundHeaders() {
    for (StreamTracer tracer : tracers) {
      ((ClientStreamTracer) tracer).inboundHeaders();
    }
  }

  /**
   * See {@link ClientStreamTracer#inboundTrailers}.  For client-side only.
   *
   * <p>Called from abstract stream implementations.
   */
  public void clientInboundTrailers(Metadata trailers) {
    for (StreamTracer tracer : tracers) {
      ((ClientStreamTracer) tracer).inboundTrailers(trailers);
    }
  }

  /**
   * See {@link ServerStreamTracer#filterContext}.  For server-side only.
   *
   * <p>Called from {@link io.grpc.internal.ServerImpl}.
   */
  public <ReqT, RespT> Context serverFilterContext(Context context) {
    Context ctx = checkNotNull(context, "context");
    for (StreamTracer tracer : tracers) {
      ctx = ((ServerStreamTracer) tracer).filterContext(ctx);
      checkNotNull(ctx, "%s returns null context", tracer);
    }
    return ctx;
  }

  /**
   * See {@link ServerStreamTracer#serverCallStarted}.  For server-side only.
   *
   * <p>Called from {@link io.grpc.internal.ServerImpl}.
   */
  public void serverCallStarted(final ServerCallInfo<?, ?> callInfo) {
    for (StreamTracer tracer : tracers) {
      ((ServerStreamTracer) tracer).serverCallStarted(callInfo);
    }
    for (StreamTracer tracer : interceptorTracers) {
      ((ServerStreamTracer) tracer).serverCallStarted(callInfo);
    }
    if (serverCallStartedListener != null) {
      serverCallStartedListener.serverCallStarted();
    }
  }

  /**
   * See {@link StreamTracer#streamClosed}. This may be called multiple times, and only the first
   * value will be taken.
   *
   * <p>Called from abstract stream implementations.
   */
  public void streamClosed(Status status) {
    if (closed.compareAndSet(false, true)) {
      for (StreamTracer tracer : tracers) {
        tracer.streamClosed(status);
      }
    }
    for (StreamTracer tracer : interceptorTracers) {
      tracer.streamClosed(status);
    }
  }

  /**
   * See {@link StreamTracer#outboundMessage(int)}.
   *
   * <p>Called from {@link io.grpc.internal.Framer}.
   */
  public void outboundMessage(int seqNo) {
    for (StreamTracer tracer : tracers) {
      tracer.outboundMessage(seqNo);
    }
    for (StreamTracer tracer : interceptorTracers) {
      tracer.outboundMessage(seqNo);
    }
  }

  /**
   * See {@link StreamTracer#inboundMessage(int)}.
   *
   * <p>Called from {@link io.grpc.internal.MessageDeframer}.
   */
  public void inboundMessage(int seqNo) {
    for (StreamTracer tracer : tracers) {
      tracer.inboundMessage(seqNo);
    }
    for (StreamTracer tracer : interceptorTracers) {
      tracer.inboundMessage(seqNo);
    }
  }

  /**
   * See {@link StreamTracer#outboundMessageSent}.
   *
   * <p>Called from {@link io.grpc.internal.Framer}.
   */
  public void outboundMessageSent(int seqNo, long optionalWireSize, long optionalUncompressedSize) {
    for (StreamTracer tracer : tracers) {
      tracer.outboundMessageSent(seqNo, optionalWireSize, optionalUncompressedSize);
    }
    for (StreamTracer tracer : interceptorTracers) {
      tracer.outboundMessageSent(seqNo, optionalWireSize, optionalUncompressedSize);
    }
  }

  /**
   * See {@link StreamTracer#inboundMessageRead}.
   *
   * <p>Called from {@link io.grpc.internal.MessageDeframer}.
   */
  public void inboundMessageRead(int seqNo, long optionalWireSize, long optionalUncompressedSize) {
    for (StreamTracer tracer : tracers) {
      tracer.inboundMessageRead(seqNo, optionalWireSize, optionalUncompressedSize);
    }
    for (StreamTracer tracer : interceptorTracers) {
      tracer.inboundMessageRead(seqNo, optionalWireSize, optionalUncompressedSize);
    }
  }

  /**
   * See {@link StreamTracer#outboundUncompressedSize}.
   *
   * <p>Called from {@link io.grpc.internal.Framer}.
   */
  public void outboundUncompressedSize(long bytes) {
    for (StreamTracer tracer : tracers) {
      tracer.outboundUncompressedSize(bytes);
    }
    for (StreamTracer tracer : interceptorTracers) {
      tracer.outboundUncompressedSize(bytes);
    }
  }

  /**
   * See {@link StreamTracer#outboundWireSize}.
   *
   * <p>Called from {@link io.grpc.internal.Framer}.
   */
  public void outboundWireSize(long bytes) {
    for (StreamTracer tracer : tracers) {
      tracer.outboundWireSize(bytes);
    }
    for (StreamTracer tracer : interceptorTracers) {
      tracer.outboundWireSize(bytes);
    }
  }

  /**
   * See {@link StreamTracer#inboundUncompressedSize}.
   *
   * <p>Called from {@link io.grpc.internal.MessageDeframer}.
   */
  public void inboundUncompressedSize(long bytes) {
    for (StreamTracer tracer : tracers) {
      tracer.inboundUncompressedSize(bytes);
    }
    for (StreamTracer tracer : interceptorTracers) {
      tracer.inboundUncompressedSize(bytes);
    }
  }

  /**
   * See {@link StreamTracer#inboundWireSize}.
   *
   * <p>Called from {@link io.grpc.internal.MessageDeframer}.
   */
  public void inboundWireSize(long bytes) {
    for (StreamTracer tracer : tracers) {
      tracer.inboundWireSize(bytes);
    }
    for (StreamTracer tracer : interceptorTracers) {
      tracer.inboundWireSize(bytes);
    }
  }
}
