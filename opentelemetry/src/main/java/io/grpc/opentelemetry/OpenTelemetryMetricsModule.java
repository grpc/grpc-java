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

package io.grpc.opentelemetry;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.BACKEND_SERVICE_KEY;
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.BAGGAGE_KEY;
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.CUSTOM_LABEL_KEY;
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.DELAY_TYPE_KEY;
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.LOCALITY_KEY;
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.METHOD_KEY;
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.STATUS_KEY;
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.TARGET_KEY;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.Deadline;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StreamTracer;
import io.grpc.internal.StatsTraceContext.ServerCallMethodListener;
import io.grpc.opentelemetry.GrpcOpenTelemetry.TargetFilter;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.context.Context;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Provides factories for {@link StreamTracer} that records metrics to OpenTelemetry.
 *
 * <p>On the client-side, a factory is created for each call, and the factory creates a stream
 * tracer for each attempt. If there is no stream created when the call is ended, we still create a
 * tracer. It's the tracer that reports per-attempt stats, and the factory that reports the stats
 * of the overall RPC, such as RETRIES_PER_CALL, to OpenTelemetry.
 *
 * <p>This module optionally applies a target attribute filter to limit the cardinality of
 * the {@code grpc.target} attribute in client-side metrics by mapping disallowed targets
 * to a stable placeholder value.
 *
 * <p>On the server-side, there is only one ServerStream per each ServerCall, and ServerStream
 * starts earlier than the ServerCall. Therefore, only one tracer is created per stream/call, and
 * it's the tracer that reports the summary to OpenTelemetry.
 */
final class OpenTelemetryMetricsModule {
  private static final Logger logger = Logger.getLogger(OpenTelemetryMetricsModule.class.getName());
  public static final ImmutableSet<String> DEFAULT_PER_CALL_METRICS_SET =
      ImmutableSet.of(
          "grpc.client.attempt.started",
          "grpc.client.attempt.duration",
          "grpc.client.attempt.sent_total_compressed_message_size",
          "grpc.client.attempt.rcvd_total_compressed_message_size",
          "grpc.client.call.duration",
          "grpc.server.call.started",
          "grpc.server.call.duration",
          "grpc.server.call.sent_total_compressed_message_size",
          "grpc.server.call.rcvd_total_compressed_message_size");

  // Using floating point because TimeUnit.NANOSECONDS.toSeconds would discard
  // fractional seconds.
  private static final double SECONDS_PER_NANO = 1e-9;

  private final OpenTelemetryMetricsResource resource;
  private final Supplier<Stopwatch> stopwatchSupplier;
  private final boolean localityEnabled;
  private final boolean backendServiceEnabled;
  private final boolean customLabelEnabled;
  private final ImmutableList<OpenTelemetryPlugin> plugins;
  @Nullable
  private final TargetFilter targetAttributeFilter;

  OpenTelemetryMetricsModule(Supplier<Stopwatch> stopwatchSupplier,
                             OpenTelemetryMetricsResource resource,
                             Collection<String> optionalLabels, List<OpenTelemetryPlugin> plugins) {
    this(stopwatchSupplier, resource, optionalLabels, plugins, null);
  }

  OpenTelemetryMetricsModule(Supplier<Stopwatch> stopwatchSupplier,
      OpenTelemetryMetricsResource resource,
      Collection<String> optionalLabels, List<OpenTelemetryPlugin> plugins,
      @Nullable TargetFilter targetAttributeFilter) {
    this.resource = checkNotNull(resource, "resource");
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
    this.localityEnabled = optionalLabels.contains(LOCALITY_KEY.getKey());
    this.backendServiceEnabled = optionalLabels.contains(BACKEND_SERVICE_KEY.getKey());
    this.customLabelEnabled = optionalLabels.contains(CUSTOM_LABEL_KEY.getKey());
    this.plugins = ImmutableList.copyOf(plugins);
    this.targetAttributeFilter = targetAttributeFilter;
  }

  @VisibleForTesting
  TargetFilter getTargetAttributeFilter() {
    return targetAttributeFilter;
  }

  /**
   * Returns the server tracer factory.
   */
  ServerStreamTracer.Factory getServerTracerFactory() {
    return new ServerTracerFactory();
  }

  /**
   * Returns the client interceptor that facilitates OpenTelemetry metrics reporting.
   */
  ClientInterceptor getClientInterceptor(String target) {
    ImmutableList.Builder<OpenTelemetryPlugin> pluginBuilder =
        ImmutableList.builderWithExpectedSize(plugins.size());
    for (OpenTelemetryPlugin plugin : plugins) {
      if (plugin.enablePluginForChannel(target)) {
        pluginBuilder.add(plugin);
      }
    }
    String filteredTarget = recordTarget(target);
    return new MetricsClientInterceptor(filteredTarget, pluginBuilder.build());
  }

  String recordTarget(String target) {
    if (targetAttributeFilter == null || target == null) {
      return target;
    }
    return targetAttributeFilter.test(target) ? target : "other";
  }

  static String recordMethodName(String fullMethodName, boolean isGeneratedMethod) {
    return isGeneratedMethod ? fullMethodName : "other";
  }

  private static final class ClientTracer extends ClientStreamTracer {
    @Nullable private static final AtomicLongFieldUpdater<ClientTracer> outboundWireSizeUpdater;
    @Nullable private static final AtomicLongFieldUpdater<ClientTracer> inboundWireSizeUpdater;

    /*
     * When using Atomic*FieldUpdater, some Samsung Android 5.0.x devices encounter a bug in their
     * JDK reflection API that triggers a NoSuchFieldException. When this occurs, we fall back to
     * (potentially racy) direct updates of the volatile variables.
     */
    static {
      AtomicLongFieldUpdater<ClientTracer> tmpOutboundWireSizeUpdater;
      AtomicLongFieldUpdater<ClientTracer> tmpInboundWireSizeUpdater;
      try {
        tmpOutboundWireSizeUpdater =
            AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "outboundWireSize");
        tmpInboundWireSizeUpdater =
            AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "inboundWireSize");
      } catch (Throwable t) {
        logger.log(Level.SEVERE, "Creating atomic field updaters failed", t);
        tmpOutboundWireSizeUpdater = null;
        tmpInboundWireSizeUpdater = null;
      }
      outboundWireSizeUpdater = tmpOutboundWireSizeUpdater;
      inboundWireSizeUpdater = tmpInboundWireSizeUpdater;
    }

    final Stopwatch stopwatch;
    final CallAttemptsTracerFactory attemptsState;
    final OpenTelemetryMetricsModule module;
    final StreamInfo info;
    final String target;
    final String fullMethodName;
    final List<OpenTelemetryPlugin.ClientStreamPlugin> streamPlugins;
    volatile long outboundWireSize;
    volatile long inboundWireSize;
    volatile String locality;
    volatile String backendService;
    long attemptNanos;
    Code statusCode;
    @GuardedBy("this")
    @Nullable private Stopwatch activeDelayStopwatch;
    /**
     * Type of the delay currently being timed, or {@code null} if no delay is open.
     *
     * <p>The channel owns the delay type and supplies it on every call, so this is never used to
     * label a normal {@link #recordDelayEnd}. It exists solely as the fallback label for the two
     * cases where the delay has to be terminated without the channel naming it: automatic
     * termination when the attempt finishes while a delay is still open (the cancellation and
     * deadline paths of gRFC A121), and rollover when a delay of a different type is started
     * before the current one was ended.
     */
    @GuardedBy("this")
    @Nullable private String activeDelayType;
    @GuardedBy("this")
    private boolean streamCreated;
    @GuardedBy("this")
    private boolean streamClosed;

    ClientTracer(CallAttemptsTracerFactory attemptsState, OpenTelemetryMetricsModule module,
        StreamInfo info, String target, String fullMethodName,
        List<OpenTelemetryPlugin.ClientStreamPlugin> streamPlugins) {
      this.attemptsState = attemptsState;
      this.module = module;
      this.info = info;
      this.target = target;
      this.fullMethodName = fullMethodName;
      this.streamPlugins = streamPlugins;
      this.stopwatch = module.stopwatchSupplier.get().start();
    }

    @Override
    public void streamCreated(io.grpc.Attributes transportAtts, Metadata headers) {
      synchronized (this) {
        streamCreated = true;
      }
      // A delay can only be outstanding here if the channel did not end it itself; the wait is
      // over either way, so terminate it.
      terminateOpenDelay();
    }

    @Override
    public void recordDelayStart(String delayType, String delayReason) {
      checkNotNull(delayType, "delayType");
      if (module.resource.clientAttemptDelayCounter() == null) {
        // Nothing to record, so do not pay for timing the delay.
        return;
      }
      long rolledOverNanos = 0;
      String rolledOverType = null;
      synchronized (this) {
        if (streamClosed || streamCreated) {
          return;
        }
        if (activeDelayStopwatch != null) {
          if (delayType.equals(activeDelayType)) {
            // Redundant start: keep timing the delay from when it actually started.
            return;
          }
          // The channel normally ends a delay before starting the next one. If it did not, close
          // out the previous segment under its own type so the new one is timed separately.
          rolledOverNanos = activeDelayStopwatch.stop().elapsed(TimeUnit.NANOSECONDS);
          rolledOverType = activeDelayType;
        }
        activeDelayStopwatch = module.stopwatchSupplier.get().start();
        activeDelayType = delayType;
      }
      if (rolledOverType != null) {
        recordDelay(rolledOverNanos, rolledOverType);
      }
    }

    @Override
    public void recordDelayEnd(String delayType) {
      checkNotNull(delayType, "delayType");
      long delayNanos;
      synchronized (this) {
        if (activeDelayStopwatch == null) {
          return;
        }
        delayNanos = activeDelayStopwatch.stop().elapsed(TimeUnit.NANOSECONDS);
        activeDelayStopwatch = null;
        activeDelayType = null;
      }
      recordDelay(delayNanos, delayType);
    }

    /**
     * Ends a delay that is still open, labeled with the type the channel gave when it started.
     * No-op if no delay is open.
     */
    private void terminateOpenDelay() {
      long delayNanos;
      String delayType;
      synchronized (this) {
        if (activeDelayStopwatch == null) {
          return;
        }
        delayNanos = activeDelayStopwatch.stop().elapsed(TimeUnit.NANOSECONDS);
        delayType = activeDelayType;
        activeDelayStopwatch = null;
        activeDelayType = null;
      }
      recordDelay(delayNanos, delayType);
    }

    /**
     * Records a delay to {@code grpc.client.attempt.delay.duration}. Must be called without
     * holding any lock, since it calls into user-supplied OpenTelemetry code.
     */
    private void recordDelay(long delayNanos, String delayType) {
      DoubleHistogram delayHistogram = module.resource.clientAttemptDelayCounter();
      if (delayHistogram == null) {
        return;
      }
      // gRFC A121 defines grpc.method, grpc.target, and grpc.delay_type for the delay histograms.
      // Per-subchannel optional labels (grpc.lb.locality, grpc.lb.backend_service) are excluded
      // because subchannel selection has not completed while an attempt delay is active.
      delayHistogram.record(
          delayNanos * SECONDS_PER_NANO,
          attemptsState.callLevelBaseAttributes.toBuilder().put(DELAY_TYPE_KEY, delayType).build(),
          attemptsState.otelContext);
    }

    @Override
    public void inboundHeaders(Metadata headers) {
      for (OpenTelemetryPlugin.ClientStreamPlugin plugin : streamPlugins) {
        plugin.inboundHeaders(headers);
      }
    }

    @Override
    @SuppressWarnings("NonAtomicVolatileUpdate")
    public void outboundWireSize(long bytes) {
      if (outboundWireSizeUpdater != null) {
        outboundWireSizeUpdater.getAndAdd(this, bytes);
      } else {
        outboundWireSize += bytes;
      }
    }

    @Override
    @SuppressWarnings("NonAtomicVolatileUpdate")
    public void inboundWireSize(long bytes) {
      if (inboundWireSizeUpdater != null) {
        inboundWireSizeUpdater.getAndAdd(this, bytes);
      } else {
        inboundWireSize += bytes;
      }
    }

    @Override
    public void addOptionalLabel(String key, String value) {
      if ("grpc.lb.locality".equals(key)) {
        locality = value;
      }
      if ("grpc.lb.backend_service".equals(key)) {
        backendService = value;
      }
    }

    @Override
    public void inboundTrailers(Metadata trailers) {
      for (OpenTelemetryPlugin.ClientStreamPlugin plugin : streamPlugins) {
        plugin.inboundTrailers(trailers);
      }
    }

    @Override
    public void streamClosed(Status status) {
      synchronized (this) {
        streamClosed = true;
      }
      // If the attempt finishes while a delay is still open (e.g. the RPC was cancelled or its
      // deadline expired while queued), gRFC A121 expects the partial duration to be recorded.
      // Records outside the lock above, which also serialises the delay callbacks.
      terminateOpenDelay();
      stopwatch.stop();
      attemptNanos = stopwatch.elapsed(TimeUnit.NANOSECONDS);
      Deadline deadline = info.getCallOptions().getDeadline();
      statusCode = status.getCode();
      if (statusCode == Code.CANCELLED && deadline != null) {
        // When the server's deadline expires, it can only reset the stream with CANCEL and no
        // description. Since our timer may be delayed in firing, we double-check the deadline and
        // turn the failure into the likely more helpful DEADLINE_EXCEEDED status.
        if (deadline.isExpired()) {
          statusCode = Code.DEADLINE_EXCEEDED;
        }
      }
      attemptsState.attemptEnded();
      recordFinishedAttempt();
    }

    void recordFinishedAttempt() {
      AttributesBuilder builder = Attributes.builder()
          .put(METHOD_KEY, fullMethodName)
          .put(TARGET_KEY, target)
          .put(STATUS_KEY, statusCode.toString());
      addOptionalLabels(builder);
      Attributes attribute = builder.build();

      if (module.resource.clientAttemptDurationCounter() != null ) {
        module.resource.clientAttemptDurationCounter()
            .record(attemptNanos * SECONDS_PER_NANO, attribute, attemptsState.otelContext);
      }
      if (module.resource.clientTotalSentCompressedMessageSizeCounter() != null) {
        module.resource.clientTotalSentCompressedMessageSizeCounter()
            .record(outboundWireSize, attribute, attemptsState.otelContext);
      }
      if (module.resource.clientTotalReceivedCompressedMessageSizeCounter() != null) {
        module.resource.clientTotalReceivedCompressedMessageSizeCounter()
            .record(inboundWireSize, attribute, attemptsState.otelContext);
      }
    }

    private void addOptionalLabels(AttributesBuilder builder) {
      if (module.localityEnabled) {
        builder.put(LOCALITY_KEY, Objects.toString(locality, ""));
      }
      if (module.backendServiceEnabled) {
        builder.put(BACKEND_SERVICE_KEY, Objects.toString(backendService, ""));
      }
      if (module.customLabelEnabled) {
        builder.put(
            CUSTOM_LABEL_KEY, info.getCallOptions().getOption(Grpc.CALL_OPTION_CUSTOM_LABEL));
      }
      for (OpenTelemetryPlugin.ClientStreamPlugin plugin : streamPlugins) {
        plugin.addLabels(builder);
      }
    }
  }

  @VisibleForTesting
  static final class CallAttemptsTracerFactory extends ClientStreamTracer.Factory {
    private final OpenTelemetryMetricsModule module;
    private final String target;
    private final Stopwatch attemptDelayStopwatch;
    private final Stopwatch callStopWatch;
    @GuardedBy("lock")
    private boolean callEnded;
    private final String fullMethodName;
    private final List<OpenTelemetryPlugin.ClientCallPlugin> callPlugins;
    private final Context otelContext;
    private Status status;
    @GuardedBy("lock")
    @Nullable private Stopwatch activeCallDelayStopwatch;
    /**
     * Type of the call-level delay currently being timed, or {@code null} if no delay is open.
     *
     * <p>The channel owns the delay type and supplies it on every call, so this is never used to
     * label a normal {@link #recordDelayEnd}. It exists solely as the fallback label for the two
     * cases where the delay has to be terminated without the channel naming it: automatic
     * termination when the call ends while a delay is still open (the cancellation and deadline
     * paths of gRFC A121), and rollover when a delay of a different type is started before the
     * current one was ended.
     */
    @GuardedBy("lock")
    @Nullable private String activeCallDelayType;
    private final Attributes callLevelBaseAttributes;
    private long retryDelayNanos;
    private long callLatencyNanos;
    private final Object lock = new Object();
    private final AtomicLong attemptsPerCall = new AtomicLong();
    private final AtomicLong hedgedAttemptsPerCall = new AtomicLong();
    private final AtomicLong transparentRetriesPerCall = new AtomicLong();
    @GuardedBy("lock")
    private int activeStreams;
    @GuardedBy("lock")
    private boolean finishedCallToBeRecorded;

    CallAttemptsTracerFactory(
        OpenTelemetryMetricsModule module,
        String target,
        CallOptions callOptions,
        String fullMethodName,
        List<OpenTelemetryPlugin.ClientCallPlugin> callPlugins, Context otelContext) {
      this.module = checkNotNull(module, "module");
      this.target = checkNotNull(target, "target");
      this.fullMethodName = checkNotNull(fullMethodName, "fullMethodName");
      this.callPlugins = checkNotNull(callPlugins, "callPlugins");
      this.otelContext = checkNotNull(otelContext, "otelContext");
      this.attemptDelayStopwatch = module.stopwatchSupplier.get();
      this.callStopWatch = module.stopwatchSupplier.get().start();

      AttributesBuilder builder = Attributes.builder()
          .put(METHOD_KEY, fullMethodName)
          .put(TARGET_KEY, target);
      if (module.customLabelEnabled) {
        builder.put(
            CUSTOM_LABEL_KEY, callOptions.getOption(Grpc.CALL_OPTION_CUSTOM_LABEL));
      }
      this.callLevelBaseAttributes = builder.build();

      // Record here in case newClientStreamTracer() would never be called.
      if (module.resource.clientAttemptCountCounter() != null) {
        module.resource.clientAttemptCountCounter().add(1, callLevelBaseAttributes, otelContext);
      }
    }

    @Override
    public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata metadata) {
      synchronized (lock) {
        if (finishedCallToBeRecorded) {
          // This can be the case when the call is cancelled but a retry attempt is created.
          return new ClientStreamTracer() {};
        }
        if (++activeStreams == 1 && attemptDelayStopwatch.isRunning()) {
          attemptDelayStopwatch.stop();
          retryDelayNanos = attemptDelayStopwatch.elapsed(TimeUnit.NANOSECONDS);
        }
      }
      // Skip recording for the first time, since it is already recorded in
      // CallAttemptsTracerFactory constructor. attemptsPerCall will be non-zero after the first
      // attempt, as first attempt cannot be a transparent retry.
      if (attemptsPerCall.get() > 0) {
        AttributesBuilder builder = Attributes.builder()
            .put(METHOD_KEY, fullMethodName)
            .put(TARGET_KEY, target);
        if (module.customLabelEnabled) {
          builder.put(
              CUSTOM_LABEL_KEY, info.getCallOptions().getOption(Grpc.CALL_OPTION_CUSTOM_LABEL));
        }
        Attributes attribute = builder.build();
        if (module.resource.clientAttemptCountCounter() != null) {
          module.resource.clientAttemptCountCounter().add(1, attribute, otelContext);
        }
      }
      if (info.isTransparentRetry()) {
        transparentRetriesPerCall.incrementAndGet();
      } else if (info.isHedging()) {
        hedgedAttemptsPerCall.incrementAndGet();
      } else {
        attemptsPerCall.incrementAndGet();
      }
      return newClientTracer(info);
    }

    private ClientTracer newClientTracer(StreamInfo info) {
      List<OpenTelemetryPlugin.ClientStreamPlugin> streamPlugins = Collections.emptyList();
      if (!callPlugins.isEmpty()) {
        streamPlugins = new ArrayList<>(callPlugins.size());
        for (OpenTelemetryPlugin.ClientCallPlugin plugin : callPlugins) {
          streamPlugins.add(plugin.newClientStreamPlugin());
        }
        streamPlugins = Collections.unmodifiableList(streamPlugins);
      }
      return new ClientTracer(this, module, info, target, fullMethodName, streamPlugins);
    }

    // Called whenever each attempt is ended.
    void attemptEnded() {
      boolean shouldRecordFinishedCall = false;
      synchronized (lock) {
        if (--activeStreams == 0) {
          attemptDelayStopwatch.start();
          if (callEnded && !finishedCallToBeRecorded) {
            shouldRecordFinishedCall = true;
            finishedCallToBeRecorded = true;
          }
        }
      }
      if (shouldRecordFinishedCall) {
        recordFinishedCall();
      }
    }

    void callEnded(Status status) {
      callStopWatch.stop();
      this.status = status;
      boolean shouldRecordFinishedCall = false;
      synchronized (lock) {
        if (callEnded) {
          // TODO(https://github.com/grpc/grpc-java/issues/7921): this shouldn't happen
          return;
        }
        callEnded = true;
        if (activeStreams == 0 && !finishedCallToBeRecorded) {
          shouldRecordFinishedCall = true;
          finishedCallToBeRecorded = true;
        }
      }
      // If the call ends while a delay is still open (e.g. the RPC was cancelled or its deadline
      // expired while waiting for name resolution), gRFC A121 expects the partial duration to be
      // recorded. Records outside the lock above, which also serialises the delay callbacks.
      terminateOpenDelay();
      if (shouldRecordFinishedCall) {
        recordFinishedCall();
      }
    }

    void recordFinishedCall() {
      if (attemptsPerCall.get() == 0) {
        ClientTracer tracer = newClientTracer(null);
        tracer.attemptNanos = attemptDelayStopwatch.elapsed(TimeUnit.NANOSECONDS);
        tracer.statusCode = status.getCode();
        tracer.recordFinishedAttempt();
      }
      callLatencyNanos = callStopWatch.elapsed(TimeUnit.NANOSECONDS);

      // Duration
      if (module.resource.clientCallDurationCounter() != null) {
        module.resource.clientCallDurationCounter().record(
            callLatencyNanos * SECONDS_PER_NANO,
            callLevelBaseAttributes.toBuilder()
                .put(STATUS_KEY, status.getCode().toString())
                .build(),
            otelContext
        );
      }

      // Retry counts
      if (module.resource.clientCallRetriesCounter() != null) {
        long retriesPerCall = Math.max(attemptsPerCall.get() - 1, 0);
        if (retriesPerCall > 0) {
          module.resource.clientCallRetriesCounter()
              .record(retriesPerCall, callLevelBaseAttributes, otelContext);
        }
      }

      // Hedge counts
      if (module.resource.clientCallHedgesCounter() != null) {
        long hedges = hedgedAttemptsPerCall.get();
        if (hedges > 0) {
          module.resource.clientCallHedgesCounter()
              .record(hedges, callLevelBaseAttributes, otelContext);
        }
      }

      // Transparent Retry counts
      if (module.resource.clientCallTransparentRetriesCounter() != null) {
        long transparentRetries = transparentRetriesPerCall.get();
        if (transparentRetries > 0) {
          module.resource.clientCallTransparentRetriesCounter()
              .record(transparentRetries, callLevelBaseAttributes, otelContext);
        }
      }

      // Retry delay
      if (module.resource.clientCallRetryDelayCounter() != null) {
        module.resource.clientCallRetryDelayCounter().record(
            retryDelayNanos * SECONDS_PER_NANO,
            callLevelBaseAttributes,
            otelContext
        );
      }
    }

    @Override
    public void recordDelayStart(String delayType, String delayReason) {
      checkNotNull(delayType, "delayType");
      if (module.resource.clientCallDelayCounter() == null) {
        // Nothing to record, so do not pay for timing the delay.
        return;
      }
      long rolledOverNanos = 0;
      String rolledOverType = null;
      synchronized (lock) {
        if (callEnded) {
          return;
        }
        if (activeCallDelayStopwatch != null) {
          if (delayType.equals(activeCallDelayType)) {
            // Redundant start: keep timing the delay from when it actually started.
            return;
          }
          // The channel normally ends a delay before starting the next one. If it did not, close
          // out the previous segment under its own type so the new one is timed separately.
          rolledOverNanos = activeCallDelayStopwatch.stop().elapsed(TimeUnit.NANOSECONDS);
          rolledOverType = activeCallDelayType;
        }
        activeCallDelayStopwatch = module.stopwatchSupplier.get().start();
        activeCallDelayType = delayType;
      }
      if (rolledOverType != null) {
        recordDelay(rolledOverNanos, rolledOverType);
      }
    }

    @Override
    public void recordDelayEnd(String delayType) {
      checkNotNull(delayType, "delayType");
      long delayNanos;
      synchronized (lock) {
        if (activeCallDelayStopwatch == null) {
          return;
        }
        delayNanos = activeCallDelayStopwatch.stop().elapsed(TimeUnit.NANOSECONDS);
        activeCallDelayStopwatch = null;
        activeCallDelayType = null;
      }
      recordDelay(delayNanos, delayType);
    }

    /**
     * Ends a call-level delay that is still open, labeled with the type the channel gave when it
     * started. No-op if no delay is open.
     */
    private void terminateOpenDelay() {
      long delayNanos;
      String delayType;
      synchronized (lock) {
        if (activeCallDelayStopwatch == null) {
          return;
        }
        delayNanos = activeCallDelayStopwatch.stop().elapsed(TimeUnit.NANOSECONDS);
        delayType = activeCallDelayType;
        activeCallDelayStopwatch = null;
        activeCallDelayType = null;
      }
      recordDelay(delayNanos, delayType);
    }

    /**
     * Records a delay to {@code grpc.client.call.delay.duration}. Must be called without holding
     * {@code lock}, since it calls into user-supplied OpenTelemetry code.
     */
    private void recordDelay(long delayNanos, String delayType) {
      DoubleHistogram delayHistogram = module.resource.clientCallDelayCounter();
      if (delayHistogram == null) {
        return;
      }
      delayHistogram.record(
          delayNanos * SECONDS_PER_NANO,
          callLevelBaseAttributes.toBuilder().put(DELAY_TYPE_KEY, delayType).build(),
          otelContext);
    }
  }

  private static final class ServerTracer extends ServerStreamTracer
      implements ServerCallMethodListener {
    @Nullable private static final AtomicIntegerFieldUpdater<ServerTracer> streamClosedUpdater;
    @Nullable private static final AtomicLongFieldUpdater<ServerTracer> outboundWireSizeUpdater;
    @Nullable private static final AtomicLongFieldUpdater<ServerTracer> inboundWireSizeUpdater;

    /*
     * When using Atomic*FieldUpdater, some Samsung Android 5.0.x devices encounter a bug in their
     * JDK reflection API that triggers a NoSuchFieldException. When this occurs, we fall back to
     * (potentially racy) direct updates of the volatile variables.
     */
    static {
      AtomicIntegerFieldUpdater<ServerTracer> tmpStreamClosedUpdater;
      AtomicLongFieldUpdater<ServerTracer> tmpOutboundWireSizeUpdater;
      AtomicLongFieldUpdater<ServerTracer> tmpInboundWireSizeUpdater;
      try {
        tmpStreamClosedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(ServerTracer.class, "streamClosed");
        tmpOutboundWireSizeUpdater =
            AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "outboundWireSize");
        tmpInboundWireSizeUpdater =
            AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "inboundWireSize");
      } catch (Throwable t) {
        logger.log(Level.SEVERE, "Creating atomic field updaters failed", t);
        tmpStreamClosedUpdater = null;
        tmpOutboundWireSizeUpdater = null;
        tmpInboundWireSizeUpdater = null;
      }
      streamClosedUpdater = tmpStreamClosedUpdater;
      outboundWireSizeUpdater = tmpOutboundWireSizeUpdater;
      inboundWireSizeUpdater = tmpInboundWireSizeUpdater;
    }

    private final OpenTelemetryMetricsModule module;
    private final String fullMethodName;
    private final List<OpenTelemetryPlugin.ServerStreamPlugin> streamPlugins;
    private Context otelContext = Context.root();
    private volatile boolean isGeneratedMethod;
    private volatile int streamClosed;
    private final Stopwatch stopwatch;
    private volatile long outboundWireSize;
    private volatile long inboundWireSize;

    ServerTracer(OpenTelemetryMetricsModule module, String fullMethodName,
        List<OpenTelemetryPlugin.ServerStreamPlugin> streamPlugins) {
      this.module = checkNotNull(module, "module");
      this.fullMethodName = fullMethodName;
      this.streamPlugins = checkNotNull(streamPlugins, "streamPlugins");
      this.stopwatch = module.stopwatchSupplier.get().start();
    }

    @Override
    public io.grpc.Context filterContext(io.grpc.Context context) {
      Baggage baggage = BAGGAGE_KEY.get(context);
      if (baggage != null) {
        otelContext = Context.current().with(baggage);
      } else {
        otelContext = Context.current();
      }
      return context;
    }

    @Override
    public void serverCallMethodResolved(MethodDescriptor<?, ?> method) {
      isGeneratedMethod = method.isSampledToLocalTracing();
    }

    @Override
    public void serverCallStarted(ServerCallInfo<?, ?> callInfo) {
      // Only record method name as an attribute if isSampledToLocalTracing is set to true,
      // which is true for all generated methods. Otherwise, programmatically
      // created methods result in high cardinality metrics.
      boolean isSampledToLocalTracing = callInfo.getMethodDescriptor().isSampledToLocalTracing();
      isGeneratedMethod = isSampledToLocalTracing;

      io.opentelemetry.api.common.Attributes attribute =
          io.opentelemetry.api.common.Attributes.of(
              METHOD_KEY, recordMethodName(fullMethodName, isSampledToLocalTracing));

      if (module.resource.serverCallCountCounter() != null) {
        module.resource.serverCallCountCounter().add(1, attribute, otelContext);
      }
    }

    @Override
    @SuppressWarnings("NonAtomicVolatileUpdate")
    public void outboundWireSize(long bytes) {
      if (outboundWireSizeUpdater != null) {
        outboundWireSizeUpdater.getAndAdd(this, bytes);
      } else {
        outboundWireSize += bytes;
      }
    }

    @Override
    @SuppressWarnings("NonAtomicVolatileUpdate")
    public void inboundWireSize(long bytes) {
      if (inboundWireSizeUpdater != null) {
        inboundWireSizeUpdater.getAndAdd(this, bytes);
      } else {
        inboundWireSize += bytes;
      }
    }

    /**
     * Record a finished stream and mark the current time as the end time.
     *
     * <p>Can be called from any thread without synchronization.  Calling it the second time or more
     * is a no-op.
     */
    @Override
    public void streamClosed(Status status) {
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
      stopwatch.stop();
      long elapsedTimeNanos = stopwatch.elapsed(TimeUnit.NANOSECONDS);
      recordClosedStream(
          status,
          elapsedTimeNanos,
          outboundWireSize,
          inboundWireSize,
          isGeneratedMethod);
    }

    private void recordClosedStream(
        Status status,
        long elapsedTimeNanos,
        long closedOutboundWireSize,
        long closedInboundWireSize,
        boolean generatedMethod) {
      AttributesBuilder builder =
          io.opentelemetry.api.common.Attributes.builder()
              .put(METHOD_KEY, recordMethodName(fullMethodName, generatedMethod))
              .put(STATUS_KEY, status.getCode().toString());
      for (OpenTelemetryPlugin.ServerStreamPlugin plugin : streamPlugins) {
        plugin.addLabels(builder);
      }
      io.opentelemetry.api.common.Attributes attributes = builder.build();

      if (module.resource.serverCallDurationCounter() != null) {
        module.resource.serverCallDurationCounter()
            .record(elapsedTimeNanos * SECONDS_PER_NANO, attributes, otelContext);
      }
      if (module.resource.serverTotalSentCompressedMessageSizeCounter() != null) {
        module.resource.serverTotalSentCompressedMessageSizeCounter()
            .record(closedOutboundWireSize, attributes, otelContext);
      }
      if (module.resource.serverTotalReceivedCompressedMessageSizeCounter() != null) {
        module.resource.serverTotalReceivedCompressedMessageSizeCounter()
            .record(closedInboundWireSize, attributes, otelContext);
      }
    }
  }

  @VisibleForTesting
  final class ServerTracerFactory extends ServerStreamTracer.Factory {
    @Override
    public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
      final List<OpenTelemetryPlugin.ServerStreamPlugin> streamPlugins;
      if (plugins.isEmpty()) {
        streamPlugins = Collections.emptyList();
      } else {
        List<OpenTelemetryPlugin.ServerStreamPlugin> streamPluginsMutable =
            new ArrayList<>(plugins.size());
        for (OpenTelemetryPlugin plugin : plugins) {
          streamPluginsMutable.add(plugin.newServerStreamPlugin(headers));
        }
        streamPlugins = Collections.unmodifiableList(streamPluginsMutable);
      }
      return new ServerTracer(OpenTelemetryMetricsModule.this, fullMethodName,
          streamPlugins);
    }
  }

  @VisibleForTesting
  final class MetricsClientInterceptor implements ClientInterceptor {
    private final String target;
    private final ImmutableList<OpenTelemetryPlugin> plugins;

    MetricsClientInterceptor(String target, ImmutableList<OpenTelemetryPlugin> plugins) {
      this.target = checkNotNull(target, "target");
      this.plugins = checkNotNull(plugins, "plugins");
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      final List<OpenTelemetryPlugin.ClientCallPlugin> callPlugins;
      if (plugins.isEmpty()) {
        callPlugins = Collections.emptyList();
      } else {
        List<OpenTelemetryPlugin.ClientCallPlugin> callPluginsMutable =
            new ArrayList<>(plugins.size());
        for (OpenTelemetryPlugin plugin : plugins) {
          callPluginsMutable.add(plugin.newClientCallPlugin());
        }
        callPlugins = Collections.unmodifiableList(callPluginsMutable);
        for (OpenTelemetryPlugin.ClientCallPlugin plugin : callPlugins) {
          callOptions = plugin.filterCallOptions(callOptions);
        }
      }
      // Only record method name as an attribute if isSampledToLocalTracing is set to true,
      // which is true for all generated methods. Otherwise, programatically
      // created methods result in high cardinality metrics.
      final CallAttemptsTracerFactory tracerFactory = new CallAttemptsTracerFactory(
          OpenTelemetryMetricsModule.this, target, callOptions,
          recordMethodName(method.getFullMethodName(), method.isSampledToLocalTracing()),
          callPlugins, Context.current());
      ClientCall<ReqT, RespT> call =
          next.newCall(method, callOptions.withStreamTracerFactory(tracerFactory));
      return new SimpleForwardingClientCall<ReqT, RespT>(call) {
        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          for (OpenTelemetryPlugin.ClientCallPlugin plugin : callPlugins) {
            plugin.addMetadata(headers);
          }
          delegate().start(
              new SimpleForwardingClientCallListener<RespT>(responseListener) {
                @Override
                public void onClose(Status status, Metadata trailers) {
                  tracerFactory.callEnded(status);
                  super.onClose(status, trailers);
                }
              },
              headers);
        }
      };
    }
  }
}
