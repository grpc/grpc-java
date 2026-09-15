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
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.BAGGAGE_KEY;
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.DELAY_REASON_KEY;
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.DELAY_TYPE_KEY;

import com.google.common.annotations.VisibleForTesting;
import com.google.errorprone.annotations.concurrent.GuardedBy;
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
import io.grpc.Status;
import io.grpc.internal.GrpcUtil;
import io.grpc.opentelemetry.internal.OpenTelemetryConstants;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.Attributes;
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

  /** Name of the child span that bounds a single delay, as defined by gRFC A121. */
  private static final String DELAY_SPAN_NAME = "Delay";
  /** Name of the event that carries each value of {@code grpc.delay_reason}, per gRFC A121. */
  private static final String DELAY_TRIGGERED_EVENT_NAME = "Delay triggered";

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
    @GuardedBy("this")
    @Nullable private Span activeCallDelaySpan;
    /**
     * The delay type {@link #activeCallDelaySpan} was opened with, or {@code null} if no delay is
     * open.
     *
     * <p>gRFC A121 makes the channel the owner of the delay type and has it supply the type on
     * every call, so this field is deliberately never used to decide a transition nor to label a
     * normal {@link #recordDelayEnd}. It is kept solely as the fallback label for the two paths
     * that carry no delay type: the spec-mandated automatic termination of a still-open delay from
     * {@link #callEnded} (cancellation or deadline), and the defensive rollover in
     * {@link #recordDelayStart} when a new delay type arrives without an intervening end.
     */
    @GuardedBy("this")
    @Nullable private String activeCallDelayType;
    /**
     * Incremented every time the active delay span is replaced or cleared. A
     * {@link #recordDelayStart} that started a span while not holding the monitor publishes it only
     * if the epoch it observed is still current; otherwise it ends the span itself. This is what
     * lets the {@link Span} operations happen outside the monitor without leaking or double-ending
     * a span.
     */
    @GuardedBy("this")
    private long delayEpoch;

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
     * Returns whether {@link #callEnded} has already run.
     *
     * <p>The delay methods below check this twice: once as a lock-free fast path and once again
     * inside {@code synchronized (this)}. Both checks must stay. The pair is race-free only
     * because of this ordering invariant: {@link #callEnded} publishes the {@code callEnded} flag
     * <em>before</em> acquiring the monitor, while a delay span is only ever created, published or
     * cleared while holding that same monitor. A delay span published under the monitor is
     * therefore either seen by {@code callEnded}'s own end (so it is closed), or it is created
     * after the flag is visible and the inner check rejects it. Moving the delay termination in
     * {@code callEnded} above the flag publication, or dropping the inner check, silently
     * reintroduces a window in which a delay span is started after the call ended and is never
     * ended: a span leak that no existing test would catch.
     */
    private boolean isCallEnded() {
      if (callEndedUpdater != null) {
        return callEndedUpdater.get(this) != 0;
      }
      return callEnded != 0;
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
      // Must stay below the flag publication above; see isCallEnded().
      String openDelayType = endActiveDelaySpan();
      if (openDelayType != null) {
        // A121: the tracer terminates an open delay by itself when the RPC is cancelled or reaches
        // its deadline, so the channel does not have to end it on those paths.
        logger.log(
            Level.FINE, "Call ended while a {0} delay was open; the delay span was terminated",
            openDelayType);
      }
      endSpanWithStatus(clientSpan, status);
    }

    @Override
    public void recordDelayStart(String delayType, String delayReason) {
      checkNotNull(delayType, "delayType");
      checkNotNull(delayReason, "delayReason");
      if (isCallEnded()) {
        return;
      }
      Span existingSameTypeSpan = null;
      Span previousDelaySpan = null;
      String previousDelayType = null;
      long epoch;
      synchronized (this) {
        if (isCallEnded()) {
          return;
        }
        if (activeCallDelaySpan != null && delayType.equals(activeCallDelayType)) {
          existingSameTypeSpan = activeCallDelaySpan;
          epoch = delayEpoch;
        } else {
          previousDelaySpan = activeCallDelaySpan;
          previousDelayType = activeCallDelayType;
          activeCallDelaySpan = null;
          activeCallDelayType = null;
          epoch = ++delayEpoch;
        }
      }
      if (existingSameTypeSpan != null) {
        existingSameTypeSpan.addEvent(
            DELAY_TRIGGERED_EVENT_NAME, Attributes.of(DELAY_REASON_KEY, delayReason));
        return;
      }
      if (previousDelaySpan != null) {
        // Defensive: A121 has the channel end a delay before starting the next one, so an open
        // span here means the delay type rolled over without an intervening end. Close the
        // previous segment before opening the new one so the two spans do not overlap.
        logger.log(
            Level.FINE, "Delay type changed from {0} to {1} without an intervening end",
            new Object[] {previousDelayType, delayType});
        previousDelaySpan.end();
      }
      Span delaySpan = otelTracer.spanBuilder(DELAY_SPAN_NAME)
          .setParent(Context.current().with(clientSpan))
          .setAttribute(DELAY_TYPE_KEY, delayType)
          .startSpan();
      // Recorded before the span is published so that the initial reason can never be lost to a
      // concurrent end.
      delaySpan.addEvent(
          DELAY_TRIGGERED_EVENT_NAME, Attributes.of(DELAY_REASON_KEY, delayReason));
      boolean stale;
      synchronized (this) {
        stale = isCallEnded() || delayEpoch != epoch;
        if (!stale) {
          activeCallDelaySpan = delaySpan;
          activeCallDelayType = delayType;
        }
      }
      if (stale) {
        // The call ended, or another delay transition happened, while this span was being created.
        // Nobody else can see it, so this thread is the one that must end it.
        delaySpan.end();
      }
    }

    @Override
    public void recordDelayReasonChanged(String delayType, String delayReason) {
      checkNotNull(delayType, "delayType");
      checkNotNull(delayReason, "delayReason");
      if (isCallEnded()) {
        return;
      }
      Span delaySpan;
      synchronized (this) {
        if (isCallEnded()) {
          return;
        }
        delaySpan = activeCallDelaySpan;
      }
      if (delaySpan != null) {
        // A121 records only the reason on the event; the type is an attribute of the span itself.
        // If the delay ends concurrently the SDK drops this event, which is the correct outcome:
        // the reason arrived after the delay was over.
        delaySpan.addEvent(
            DELAY_TRIGGERED_EVENT_NAME, Attributes.of(DELAY_REASON_KEY, delayReason));
      }
    }

    @Override
    public void recordDelayEnd(String delayType) {
      checkNotNull(delayType, "delayType");
      endActiveDelaySpan();
    }

    /**
     * Ends the open delay span, if any, without holding the monitor across {@link Span#end}.
     * Returns the delay type the span was opened with, or {@code null} if no delay was open.
     */
    @Nullable
    private String endActiveDelaySpan() {
      Span delaySpan;
      String delayType;
      synchronized (this) {
        delaySpan = activeCallDelaySpan;
        delayType = activeCallDelayType;
        activeCallDelaySpan = null;
        activeCallDelayType = null;
        // Invalidates a span that a concurrent recordDelayStart() is creating right now, so that
        // it ends its own span instead of publishing it after this end.
        delayEpoch++;
      }
      if (delaySpan != null) {
        delaySpan.end();
      }
      return delayType;
    }
  }

  private final class ClientTracer extends ClientStreamTracer {
    private final Span span;
    private final Span parentSpan;
    volatile int seqNo;
    boolean isPendingStream;
    @GuardedBy("this")
    @Nullable private Span activeDelaySpan;
    /**
     * Fallback label for the two paths that carry no delay type: automatic termination of a
     * still-open delay from {@link #streamClosed}, and the defensive rollover in
     * {@link #recordDelayStart}. See {@code CallAttemptsTracerFactory.activeCallDelayType}.
     */
    @GuardedBy("this")
    @Nullable private String activeDelayType;
    /** See {@code CallAttemptsTracerFactory.delayEpoch}. */
    @GuardedBy("this")
    private long delayEpoch;
    /**
     * Unlike {@code CallAttemptsTracerFactory.callEnded}, this flag is written and read only under
     * this monitor, so there is no lock-free fast path and hence no double-check to preserve here:
     * the single check inside the monitor is authoritative. The call-scoped tracer needs the
     * volatile flag because it is also read by {@code callEnded}'s atomic updater; see
     * {@code CallAttemptsTracerFactory.isCallEnded()} for the ordering invariant that makes the
     * double-check there race-free.
     */
    @GuardedBy("this")
    private boolean streamCreated;
    @GuardedBy("this")
    private boolean streamClosed;

    ClientTracer(Span span, Span parentSpan) {
      this.span = checkNotNull(span, "span");
      this.parentSpan = checkNotNull(parentSpan, "parent span");
    }

    @Override
    public void streamCreated(io.grpc.Attributes transportAtts, Metadata headers) {
      synchronized (this) {
        streamCreated = true;
      }
      endActiveDelaySpan();
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
    public void recordDelayStart(String delayType, String delayReason) {
      checkNotNull(delayType, "delayType");
      checkNotNull(delayReason, "delayReason");
      Span existingSameTypeSpan = null;
      Span previousDelaySpan = null;
      String previousDelayType = null;
      long epoch;
      synchronized (this) {
        if (streamClosed || streamCreated) {
          return;
        }
        if (activeDelaySpan != null && delayType.equals(activeDelayType)) {
          existingSameTypeSpan = activeDelaySpan;
          epoch = delayEpoch;
        } else {
          previousDelaySpan = activeDelaySpan;
          previousDelayType = activeDelayType;
          activeDelaySpan = null;
          activeDelayType = null;
          epoch = ++delayEpoch;
        }
      }
      if (existingSameTypeSpan != null) {
        existingSameTypeSpan.addEvent(
            DELAY_TRIGGERED_EVENT_NAME, Attributes.of(DELAY_REASON_KEY, delayReason));
        return;
      }
      if (previousDelaySpan != null) {
        // Defensive: A121 has the channel end a delay before starting the next one, so an open
        // span here means the delay type rolled over (e.g. rls_lookup_pending -> connecting)
        // without an intervening end. Close the previous segment before opening the new one.
        logger.log(
            Level.FINE, "Delay type changed from {0} to {1} without an intervening end",
            new Object[] {previousDelayType, delayType});
        previousDelaySpan.end();
      }
      // All attempt queuing segments use the strict child span name "Delay".
      Span delaySpan = otelTracer.spanBuilder(DELAY_SPAN_NAME)
          .setParent(Context.current().with(span))
          .setAttribute(DELAY_TYPE_KEY, delayType)
          .startSpan();
      // Recorded before the span is published so that the initial reason can never be lost to a
      // concurrent end.
      delaySpan.addEvent(
          DELAY_TRIGGERED_EVENT_NAME, Attributes.of(DELAY_REASON_KEY, delayReason));
      boolean stale;
      synchronized (this) {
        stale = streamClosed || streamCreated || delayEpoch != epoch;
        if (!stale) {
          activeDelaySpan = delaySpan;
          activeDelayType = delayType;
        }
      }
      if (stale) {
        // The stream closed, or another delay transition happened, while this span was being
        // created. Nobody else can see it, so this thread is the one that must end it.
        delaySpan.end();
      }
    }

    @Override
    public void recordDelayReasonChanged(String delayType, String delayReason) {
      checkNotNull(delayType, "delayType");
      checkNotNull(delayReason, "delayReason");
      Span delaySpan;
      synchronized (this) {
        if (streamClosed || streamCreated) {
          return;
        }
        delaySpan = activeDelaySpan;
      }
      if (delaySpan != null) {
        // A121 records only the reason on the event; the type is an attribute of the span itself.
        delaySpan.addEvent(
            DELAY_TRIGGERED_EVENT_NAME, Attributes.of(DELAY_REASON_KEY, delayReason));
      }
    }

    @Override
    public void recordDelayEnd(String delayType) {
      checkNotNull(delayType, "delayType");
      endActiveDelaySpan();
    }

    /**
     * Ends the active child span upon pick completion, stream creation or stream closure, without
     * holding the monitor across {@link Span#end}. Returns the delay type the span was opened
     * with, or {@code null} if no delay was open.
     */
    @Nullable
    private String endActiveDelaySpan() {
      Span delaySpan;
      String delayType;
      synchronized (this) {
        delaySpan = activeDelaySpan;
        delayType = activeDelayType;
        activeDelaySpan = null;
        activeDelayType = null;
        // Invalidates a span that a concurrent recordDelayStart() is creating right now, so that
        // it ends its own span instead of publishing it after this end.
        delayEpoch++;
      }
      if (delaySpan != null) {
        delaySpan.end();
      }
      return delayType;
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
    public void streamClosed(Status status) {
      synchronized (this) {
        if (streamClosed) {
          return;
        }
        streamClosed = true;
      }
      // Outside the monitor: both of these call into user-supplied OpenTelemetry code.
      String openDelayType = endActiveDelaySpan();
      if (openDelayType != null) {
        // A121: the tracer terminates an open delay by itself when the attempt is cancelled or
        // reaches its deadline, so the channel does not have to end it on those paths.
        logger.log(
            Level.FINE, "Stream closed while a {0} delay was open; the delay span was terminated",
            openDelayType);
      }
      endSpanWithStatus(span, status);
    }
  }

  private final class ServerTracer extends ServerStreamTracer {
    private final Span span;
    volatile int streamClosed;
    private int seqNo;
    private Baggage baggage;

    ServerTracer(String fullMethodName, @Nullable Span remoteSpan, Baggage baggage) {
      checkNotNull(fullMethodName, "fullMethodName");
      this.span =
          otelTracer.spanBuilder(generateTraceSpanName(true, fullMethodName))
              .setParent(remoteSpan == null ? null : Context.current().with(remoteSpan))
              .startSpan();
      this.baggage = baggage;
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
      return context
          .withValue(otelSpan, span)
          .withValue(BAGGAGE_KEY, baggage);
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
      Baggage baggage = Baggage.fromContext(context);
      return new ServerTracer(fullMethodName, remoteSpan, baggage);
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
      Context serverCallContext = Context.current();
      serverCallContext = serverCallContext.with(span);
      Baggage baggage = BAGGAGE_KEY.get();
      if (baggage != null) {
        serverCallContext = serverCallContext.with(baggage);
      } else {
        logger.log(Level.WARNING, "Server baggage not found which is unexpected, "
            + "as it is being added unconditionally in filterContext().");
      }
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
    AttributesBuilder attributesBuilder = Attributes.builder();
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
    AttributesBuilder attributesBuilder = Attributes.builder();
    attributesBuilder.put("sequence-number", seqNo);
    attributesBuilder.put("message-size-compressed", optionalWireSize);
    span.addEvent("Inbound compressed message", attributesBuilder.build());
  }

  private void recordInboundMessageSize(Span span, int seqNo, long bytes) {
    AttributesBuilder attributesBuilder = Attributes.builder();
    attributesBuilder.put("sequence-number", seqNo);
    attributesBuilder.put("message-size", bytes);
    span.addEvent("Inbound message", attributesBuilder.build());
  }

  private void endSpanWithStatus(Span span, io.grpc.Status status) {
    if (status.isOk()) {
      span.setStatus(StatusCode.OK);
    } else {
      span.setStatus(StatusCode.ERROR, GrpcUtil.statusToPrettyString(status));
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
