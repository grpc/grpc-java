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
import static io.grpc.ClientStreamTracer.NAME_RESOLUTION_DELAYED;
import static io.grpc.internal.GrpcUtil.IMPLEMENTATION_VERSION;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientStreamTracer;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerStreamTracer;
import io.grpc.opentelemetry.internal.OpenTelemetryConstants;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Provides factories for {@link io.grpc.StreamTracer} that records tracing to OpenTelemetry.
 */
final class OpenTelemetryTracingModule {
  private static final Logger logger = Logger.getLogger(OpenTelemetryTracingModule.class.getName());

  @VisibleForTesting
  final io.grpc.Context.Key<Span> otelSpan = io.grpc.Context.key("opentelemetry-span-key");
  @Nullable
  private static final AtomicIntegerFieldUpdater<CallAttemptsTracerFactory> callEndedUpdater;
  @Nullable
  private static final AtomicIntegerFieldUpdater<ServerTracer> streamClosedUpdater;

  /*
   * When using Atomic*FieldUpdater, some Samsung Android 5.0.x devices encounter a bug in their JDK
   * reflection API that triggers a NoSuchFieldException. When this occurs, we fallback to
   * (potentially racy) direct updates of the volatile variables.
   */
  static {
    AtomicIntegerFieldUpdater<CallAttemptsTracerFactory> tmpCallEndedUpdater;
    AtomicIntegerFieldUpdater<ServerTracer> tmpStreamClosedUpdater;
    try {
      tmpCallEndedUpdater =
          AtomicIntegerFieldUpdater.newUpdater(CallAttemptsTracerFactory.class, "callEnded");
      tmpStreamClosedUpdater =
          AtomicIntegerFieldUpdater.newUpdater(ServerTracer.class, "streamClosed");
    } catch (Throwable t) {
      logger.log(Level.SEVERE, "Creating atomic field updaters failed", t);
      tmpCallEndedUpdater = null;
      tmpStreamClosedUpdater = null;
    }
    callEndedUpdater = tmpCallEndedUpdater;
    streamClosedUpdater = tmpStreamClosedUpdater;
  }

  private final Tracer otelTracer;
  private final ContextPropagators contextPropagators;
  private final MetadataGetter metadataGetter = MetadataGetter.getInstance();
  private final MetadataSetter metadataSetter = MetadataSetter.getInstance();
  private final TracingClientInterceptor clientInterceptor = new TracingClientInterceptor();
  private final ServerInterceptor serverSpanPropagationInterceptor =
      new TracingServerSpanPropagationInterceptor();
  private final ServerTracerFactory serverTracerFactory = new ServerTracerFactory();

  OpenTelemetryTracingModule(OpenTelemetry openTelemetry) {
    this.otelTracer = checkNotNull(openTelemetry.getTracerProvider(), "tracerProvider")
        .tracerBuilder(OpenTelemetryConstants.INSTRUMENTATION_SCOPE)
        .setInstrumentationVersion(IMPLEMENTATION_VERSION)
        .build();
    this.contextPropagators = checkNotNull(openTelemetry.getPropagators(), "contextPropagators");
  }

  @VisibleForTesting
  Tracer getTracer() {
    return otelTracer;
  }

  /**
   * Creates a {@link CallAttemptsTracerFactory} for a new call.
   */
  @VisibleForTesting
  CallAttemptsTracerFactory newClientCallTracer(Span clientSpan, MethodDescriptor<?, ?> method) {
    return new CallAttemptsTracerFactory(clientSpan, method);
  }

  /**
   * Returns the server tracer factory.
   */
  ServerStreamTracer.Factory getServerTracerFactory() {
    return serverTracerFactory;
  }

  /**
   * Returns the client interceptor that facilitates otel tracing reporting.
   */
  ClientInterceptor getClientInterceptor() {
    return clientInterceptor;
  }

  ServerInterceptor getServerSpanPropagationInterceptor() {
    return serverSpanPropagationInterceptor;
  }

  @VisibleForTesting
  final class CallAttemptsTracerFactory extends ClientStreamTracer.Factory {
    volatile int callEnded;
    private final Span clientSpan;
    private final String fullMethodName;

    CallAttemptsTracerFactory(Span clientSpan, MethodDescriptor<?, ?> method) {
      checkNotNull(method, "method");
      this.fullMethodName = checkNotNull(method.getFullMethodName(), "fullMethodName");
      this.clientSpan = checkNotNull(clientSpan, "clientSpan");
    }

    @Override
    public ClientStreamTracer newClientStreamTracer(
        ClientStreamTracer.StreamInfo info, Metadata headers) {
      Span attemptSpan = otelTracer.spanBuilder(
              "Attempt." + fullMethodName.replace('/', '.'))
          .setParent(Context.current().with(clientSpan))
          .startSpan();
      attemptSpan.setAttribute(
          "previous-rpc-attempts", info.getPreviousAttempts());
      attemptSpan.setAttribute(
          "transparent-retry",info.isTransparentRetry());
      if (info.getCallOptions().getOption(NAME_RESOLUTION_DELAYED) != null) {
        clientSpan.addEvent("Delayed name resolution complete");
      }
      return new ClientTracer(attemptSpan, clientSpan);
    }

    /**
     * Record a finished call and mark the current time as the end time.
     *
     * <p>Can be called from any thread without synchronization.  Calling it the second time or more
     * is a no-op.
     */
    void callEnded(io.grpc.Status status) {
      if (callEndedUpdater != null) {
        if (callEndedUpdater.getAndSet(this, 1) != 0) {
          return;
        }
      } else {
        if (callEnded != 0) {
          return;
        }
        callEnded = 1;
      }
      endSpanWithStatus(clientSpan, status);
    }
  }

  private final class ClientTracer extends ClientStreamTracer {
    private final Span span;
    private final Span parentSpan;
    volatile int seqNo;
    boolean isPendingStream;

    ClientTracer(Span span, Span parentSpan) {
      this.span = checkNotNull(span, "span");
      this.parentSpan = checkNotNull(parentSpan, "parent span");
    }

    @Override
    public void streamCreated(Attributes transportAtts, Metadata headers) {
      contextPropagators.getTextMapPropagator().inject(Context.current().with(span), headers,
          metadataSetter);
      if (isPendingStream) {
        span.addEvent("Delayed LB pick complete");
      }
    }

    @Override
    public void createPendingStream() {
      isPendingStream = true;
    }

    @Override
    public void outboundMessageSent(
        int seqNo, long optionalWireSize, long optionalUncompressedSize) {
      recordOutboundMessageSentEvent(span, seqNo, optionalWireSize, optionalUncompressedSize);
    }

    @Override
    public void inboundMessageRead(
        int seqNo, long optionalWireSize, long optionalUncompressedSize) {
      if (optionalWireSize != optionalUncompressedSize) {
        recordInboundCompressedMessage(span, seqNo, optionalWireSize);
      }
    }

    @Override
    public void inboundMessage(int seqNo) {
      this.seqNo = seqNo;
    }

    @Override
    public void inboundUncompressedSize(long bytes) {
      recordInboundMessageSize(parentSpan, seqNo, bytes);
    }

    @Override
    public void streamClosed(io.grpc.Status status) {
      endSpanWithStatus(span, status);
    }
  }

  private final class ServerTracer extends ServerStreamTracer {
    private final Span span;
    volatile int streamClosed;
    private int seqNo;

    ServerTracer(String fullMethodName, @Nullable Span remoteSpan) {
      checkNotNull(fullMethodName, "fullMethodName");
      this.span =
          otelTracer.spanBuilder(generateTraceSpanName(true, fullMethodName))
              .setParent(remoteSpan == null ? null : Context.current().with(remoteSpan))
              .startSpan();
    }

    /**
     * Record a finished stream and mark the current time as the end time.
     *
     * <p>Can be called from any thread without synchronization.  Calling it the second time or more
     * is a no-op.
     */
    @Override
    public void streamClosed(io.grpc.Status status) {
      if (streamClosedUpdater != null) {
        if (streamClosedUpdater.getAndSet(this, 1) != 0) {
          return;
        }
      } else {
        if (streamClosed != 0) {
          return;
        }
        streamClosed = 1;
      }
      endSpanWithStatus(span, status);
    }

    @Override
    public io.grpc.Context filterContext(io.grpc.Context context) {
      return context.withValue(otelSpan, span);
    }

    @Override
    public void outboundMessageSent(
        int seqNo, long optionalWireSize, long optionalUncompressedSize) {
      recordOutboundMessageSentEvent(span, seqNo, optionalWireSize, optionalUncompressedSize);
    }

    @Override
    public void inboundMessageRead(
        int seqNo, long optionalWireSize, long optionalUncompressedSize) {
      if (optionalWireSize != optionalUncompressedSize) {
        recordInboundCompressedMessage(span, seqNo, optionalWireSize);
      }
    }

    @Override
    public void inboundMessage(int seqNo) {
      this.seqNo = seqNo;
    }

    @Override
    public void inboundUncompressedSize(long bytes) {
      recordInboundMessageSize(span, seqNo, bytes);
    }
  }

  @VisibleForTesting
  final class ServerTracerFactory extends ServerStreamTracer.Factory {
    @SuppressWarnings("ReferenceEquality")
    @Override
    public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
      Context context = contextPropagators.getTextMapPropagator().extract(
          Context.current(), headers, metadataGetter
      );
      Span remoteSpan = Span.fromContext(context);
      if (remoteSpan == Span.getInvalid()) {
        remoteSpan = null;
      }
      return new ServerTracer(fullMethodName, remoteSpan);
    }
  }

  @VisibleForTesting
  final class TracingServerSpanPropagationInterceptor implements ServerInterceptor {
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
        Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      Span span = otelSpan.get(io.grpc.Context.current());
      if (span == null) {
        logger.log(Level.FINE, "Server span not found. ServerTracerFactory for server "
            + "tracing must be set.");
        return next.startCall(call, headers);
      }
      Context serverCallContext = Context.current().with(span);
      try (Scope scope = serverCallContext.makeCurrent()) {
        return new ContextServerCallListener<>(next.startCall(call, headers), serverCallContext);
      }
    }
  }

  private static class ContextServerCallListener<ReqT> extends
      ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {
    private final Context context;

    protected ContextServerCallListener(ServerCall.Listener<ReqT> delegate, Context context) {
      super(delegate);
      this.context = checkNotNull(context, "context");
    }

    @Override
    public void onMessage(ReqT message) {
      try (Scope scope = context.makeCurrent()) {
        delegate().onMessage(message);
      }
    }

    @Override
    public void onHalfClose() {
      try (Scope scope = context.makeCurrent()) {
        delegate().onHalfClose();
      }
    }

    @Override
    public void onCancel() {
      try (Scope scope = context.makeCurrent()) {
        delegate().onCancel();
      }
    }

    @Override
    public void onComplete() {
      try (Scope scope = context.makeCurrent()) {
        delegate().onComplete();
      }
    }

    @Override
    public void onReady() {
      try (Scope scope = context.makeCurrent()) {
        delegate().onReady();
      }
    }
  }

  @VisibleForTesting
  final class TracingClientInterceptor implements ClientInterceptor {

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      Span clientSpan = otelTracer.spanBuilder(
          generateTraceSpanName(false, method.getFullMethodName()))
          .startSpan();

      final CallAttemptsTracerFactory tracerFactory = newClientCallTracer(clientSpan, method);
      ClientCall<ReqT, RespT> call =
          next.newCall(
              method,
              callOptions.withStreamTracerFactory(tracerFactory));
      return new SimpleForwardingClientCall<ReqT, RespT>(call) {
        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          delegate().start(
              new SimpleForwardingClientCallListener<RespT>(responseListener) {
                @Override
                public void onClose(io.grpc.Status status, Metadata trailers) {
                  tracerFactory.callEnded(status);
                  super.onClose(status, trailers);
                }
              },
              headers);
        }
      };
    }
  }

  // Attribute named "message-size" always means the message size the application sees.
  // If there was compression, additional event reports "message-size-compressed".
  //
  // An example trace with message compression:
  //
  // Sending:
  // |-- Event 'Outbound message sent', attributes('sequence-numer' = 0, 'message-size' = 7854,
  //                                               'message-size-compressed' = 5493) ----|
  //
  // Receiving:
  // |-- Event 'Inbound compressed message', attributes('sequence-numer' = 0,
  //                                                    'message-size-compressed' = 5493 ) ----|
  // |-- Event 'Inbound message received', attributes('sequence-numer' = 0,
  //                                                  'message-size' = 7854) ----|
  //
  // An example trace with no message compression:
  //
  // Sending:
  // |-- Event 'Outbound message sent', attributes('sequence-numer' = 0, 'message-size' = 7854) ---|
  //
  // Receiving:
  // |-- Event 'Inbound message received', attributes('sequence-numer' = 0,
  //                                                  'message-size' = 7854) ----|
  private void recordOutboundMessageSentEvent(Span span,
      int seqNo, long optionalWireSize, long optionalUncompressedSize) {
    AttributesBuilder attributesBuilder = io.opentelemetry.api.common.Attributes.builder();
    attributesBuilder.put("sequence-number", seqNo);
    if (optionalUncompressedSize != -1) {
      attributesBuilder.put("message-size", optionalUncompressedSize);
    }
    if (optionalWireSize != -1 && optionalWireSize != optionalUncompressedSize) {
      attributesBuilder.put("message-size-compressed", optionalWireSize);
    }
    span.addEvent("Outbound message", attributesBuilder.build());
  }

  private void recordInboundCompressedMessage(Span span, int seqNo, long optionalWireSize) {
    AttributesBuilder attributesBuilder = io.opentelemetry.api.common.Attributes.builder();
    attributesBuilder.put("sequence-number", seqNo);
    attributesBuilder.put("message-size-compressed", optionalWireSize);
    span.addEvent("Inbound compressed message", attributesBuilder.build());
  }

  private void recordInboundMessageSize(Span span, int seqNo, long bytes) {
    AttributesBuilder attributesBuilder = io.opentelemetry.api.common.Attributes.builder();
    attributesBuilder.put("sequence-number", seqNo);
    attributesBuilder.put("message-size", bytes);
    span.addEvent("Inbound message", attributesBuilder.build());
  }

  private String generateErrorStatusDescription(io.grpc.Status status) {
    if (status.getDescription() != null) {
      return status.getCode() + ": " + status.getDescription();
    } else {
      return status.getCode().toString();
    }
  }

  private void endSpanWithStatus(Span span, io.grpc.Status status) {
    if (status.isOk()) {
      span.setStatus(StatusCode.OK);
    } else {
      span.setStatus(StatusCode.ERROR, generateErrorStatusDescription(status));
    }
    span.end();
  }

  /**
   * Convert a full method name to a tracing span name.
   *
   * @param isServer {@code false} if the span is on the client-side, {@code true} if on the
   *                 server-side
   * @param fullMethodName the method name as returned by
   *        {@link MethodDescriptor#getFullMethodName}.
   */
  @VisibleForTesting
  static String generateTraceSpanName(boolean isServer, String fullMethodName) {
    String prefix = isServer ? "Recv" : "Sent";
    return prefix + "." + fullMethodName.replace('/', '.');
  }
}
