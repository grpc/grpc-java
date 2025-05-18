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
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StreamTracer;
import io.opentelemetry.api.common.AttributesBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
  private final ImmutableList<OpenTelemetryPlugin> plugins;

  OpenTelemetryMetricsModule(Supplier<Stopwatch> stopwatchSupplier,
      OpenTelemetryMetricsResource resource, Collection<String> optionalLabels,
      List<OpenTelemetryPlugin> plugins) {
    this.resource = checkNotNull(resource, "resource");
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
    this.localityEnabled = optionalLabels.contains(LOCALITY_KEY.getKey());
    this.backendServiceEnabled = optionalLabels.contains(BACKEND_SERVICE_KEY.getKey());
    this.plugins = ImmutableList.copyOf(plugins);
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
    return new MetricsClientInterceptor(target, pluginBuilder.build());
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
      AttributesBuilder builder = io.opentelemetry.api.common.Attributes.builder()
          .put(METHOD_KEY, fullMethodName)
          .put(TARGET_KEY, target)
          .put(STATUS_KEY, statusCode.toString());
      if (module.localityEnabled) {
        String savedLocality = locality;
        if (savedLocality == null) {
          savedLocality = "";
        }
        builder.put(LOCALITY_KEY, savedLocality);
      }
      if (module.backendServiceEnabled) {
        String savedBackendService = backendService;
        if (savedBackendService == null) {
          savedBackendService = "";
        }
        builder.put(BACKEND_SERVICE_KEY, savedBackendService);
      }
      for (OpenTelemetryPlugin.ClientStreamPlugin plugin : streamPlugins) {
        plugin.addLabels(builder);
      }
      io.opentelemetry.api.common.Attributes attribute = builder.build();

      if (module.resource.clientAttemptDurationCounter() != null ) {
        module.resource.clientAttemptDurationCounter()
            .record(attemptNanos * SECONDS_PER_NANO, attribute);
      }
      if (module.resource.clientTotalSentCompressedMessageSizeCounter() != null) {
        module.resource.clientTotalSentCompressedMessageSizeCounter()
            .record(outboundWireSize, attribute);
      }
      if (module.resource.clientTotalReceivedCompressedMessageSizeCounter() != null) {
        module.resource.clientTotalReceivedCompressedMessageSizeCounter()
            .record(inboundWireSize, attribute);
      }
    }
  }

  @VisibleForTesting
  static final class CallAttemptsTracerFactory extends ClientStreamTracer.Factory {
    private final OpenTelemetryMetricsModule module;
    private final String target;
    private final Stopwatch attemptStopwatch;
    private final Stopwatch callStopWatch;
    @GuardedBy("lock")
    private boolean callEnded;
    private final String fullMethodName;
    private final List<OpenTelemetryPlugin.ClientCallPlugin> callPlugins;
    private Status status;
    private long callLatencyNanos;
    private final Object lock = new Object();
    private final AtomicLong attemptsPerCall = new AtomicLong();
    @GuardedBy("lock")
    private int activeStreams;
    @GuardedBy("lock")
    private boolean finishedCallToBeRecorded;

    CallAttemptsTracerFactory(
        OpenTelemetryMetricsModule module,
        String target,
        String fullMethodName,
        List<OpenTelemetryPlugin.ClientCallPlugin> callPlugins) {
      this.module = checkNotNull(module, "module");
      this.target = checkNotNull(target, "target");
      this.fullMethodName = checkNotNull(fullMethodName, "fullMethodName");
      this.callPlugins = checkNotNull(callPlugins, "callPlugins");
      this.attemptStopwatch = module.stopwatchSupplier.get();
      this.callStopWatch = module.stopwatchSupplier.get().start();

      io.opentelemetry.api.common.Attributes attribute = io.opentelemetry.api.common.Attributes.of(
          METHOD_KEY, fullMethodName,
          TARGET_KEY, target);

      // Record here in case mewClientStreamTracer() would never be called.
      if (module.resource.clientAttemptCountCounter() != null) {
        module.resource.clientAttemptCountCounter().add(1, attribute);
      }
    }

    @Override
    public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata metadata) {
      synchronized (lock) {
        if (finishedCallToBeRecorded) {
          // This can be the case when the call is cancelled but a retry attempt is created.
          return new ClientStreamTracer() {};
        }
        if (++activeStreams == 1 && attemptStopwatch.isRunning()) {
          attemptStopwatch.stop();
        }
      }
      // Skip recording for the first time, since it is already recorded in
      // CallAttemptsTracerFactory constructor. attemptsPerCall will be non-zero after the first
      // attempt, as first attempt cannot be a transparent retry.
      if (attemptsPerCall.get() > 0) {
        io.opentelemetry.api.common.Attributes attribute =
            io.opentelemetry.api.common.Attributes.of(METHOD_KEY, fullMethodName,
                TARGET_KEY, target);
        if (module.resource.clientAttemptCountCounter() != null) {
          module.resource.clientAttemptCountCounter().add(1, attribute);
        }
      }
      if (!info.isTransparentRetry()) {
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
          attemptStopwatch.start();
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
      if (shouldRecordFinishedCall) {
        recordFinishedCall();
      }
    }

    void recordFinishedCall() {
      if (attemptsPerCall.get() == 0) {
        ClientTracer tracer = newClientTracer(null);
        tracer.attemptNanos = attemptStopwatch.elapsed(TimeUnit.NANOSECONDS);
        tracer.statusCode = status.getCode();
        tracer.recordFinishedAttempt();
      }
      callLatencyNanos = callStopWatch.elapsed(TimeUnit.NANOSECONDS);
      io.opentelemetry.api.common.Attributes attribute =
          io.opentelemetry.api.common.Attributes.of(METHOD_KEY, fullMethodName,
              TARGET_KEY, target,
              STATUS_KEY, status.getCode().toString());

      if (module.resource.clientCallDurationCounter() != null) {
        module.resource.clientCallDurationCounter()
            .record(callLatencyNanos * SECONDS_PER_NANO, attribute);
      }
    }
  }

  private static final class ServerTracer extends ServerStreamTracer {
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
        module.resource.serverCallCountCounter().add(1, attribute);
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
      AttributesBuilder builder = io.opentelemetry.api.common.Attributes.builder()
          .put(METHOD_KEY, recordMethodName(fullMethodName, isGeneratedMethod))
          .put(STATUS_KEY, status.getCode().toString());
      for (OpenTelemetryPlugin.ServerStreamPlugin plugin : streamPlugins) {
        plugin.addLabels(builder);
      }
      io.opentelemetry.api.common.Attributes attributes = builder.build();

      if (module.resource.serverCallDurationCounter() != null) {
        module.resource.serverCallDurationCounter()
            .record(elapsedTimeNanos * SECONDS_PER_NANO, attributes);
      }
      if (module.resource.serverTotalSentCompressedMessageSizeCounter() != null) {
        module.resource.serverTotalSentCompressedMessageSizeCounter()
            .record(outboundWireSize, attributes);
      }
      if (module.resource.serverTotalReceivedCompressedMessageSizeCounter() != null) {
        module.resource.serverTotalReceivedCompressedMessageSizeCounter()
            .record(inboundWireSize, attributes);
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
      return new ServerTracer(OpenTelemetryMetricsModule.this, fullMethodName, streamPlugins);
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
          OpenTelemetryMetricsModule.this, target,
          recordMethodName(method.getFullMethodName(), method.isSampledToLocalTracing()),
          callPlugins);
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
