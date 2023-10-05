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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
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
import io.grpc.opentelemetry.internal.OpenTelemetryConstants;
import io.opentelemetry.api.common.AttributeKey;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * Provides factories for {@link StreamTracer} that records metrics to OpenTelemetry.
 *
 * <p>On the client-side, a factory is created for each call, and the factory creates a stream
 * tracer for each attempt. If there is no stream created when the call is ended, we still create a
 * tracer. It's the tracer that reports per-attempt stats, and the factory that reports the stats
 * of the overall RPC, such as RETRIES_PER_CALL, to Census.
 *
 * <p>On the server-side, there is only one ServerStream per each ServerCall, and ServerStream
 * starts earlier than the ServerCall.  Therefore, only one tracer is created per stream/call and
 * it's the tracer that reports the summary to Census.
 */
final class OpenTelemetryMetricsModule {
  private static final Logger logger = Logger.getLogger(OpenTelemetryMetricsModule.class.getName());
  private static final double NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

  private final OpenTelemetryState state;
  private final Supplier<Stopwatch> stopwatchSupplier;

  OpenTelemetryMetricsModule(Supplier<Stopwatch> stopwatchSupplier, OpenTelemetryState state) {
    this.state = checkNotNull(state, "state");
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
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
  ClientInterceptor getClientInterceptor() {
    return new MetricsClientInterceptor();
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
    final AtomicBoolean inboundReceivedOrClosed = new AtomicBoolean();
    final OpenTelemetryMetricsModule module;
    final StreamInfo info;
    final String fullMethodName;
    final boolean isGeneratedMethod;
    volatile long outboundWireSize;
    volatile long inboundWireSize;
    long attemptNanos;
    Code statusCode;

    ClientTracer(CallAttemptsTracerFactory attemptsState, OpenTelemetryMetricsModule module,
        StreamInfo info, String fullMethodName, boolean isGeneratedMethod) {
      this.attemptsState = attemptsState;
      this.module = module;
      this.info = info;
      this.fullMethodName = fullMethodName;
      this.isGeneratedMethod = isGeneratedMethod;
      this.stopwatch = module.stopwatchSupplier.get().start();
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
    @SuppressWarnings("NonAtomicVolatileUpdate")
    public void inboundMessage(int seqNo) {
      if (inboundReceivedOrClosed.compareAndSet(false, true)) {
        // Because inboundUncompressedSize() might be called after streamClosed(),
        // we will report stats in callEnded(). Note that this attempt is already committed.
        attemptsState.inboundMetricTracer = this;
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
      if (inboundReceivedOrClosed.compareAndSet(false, true)) {
        // Stream is closed early. So no need to record metrics for any inbound events after this
        // point.
        recordFinishedAttempt();
      } // Otherwise will report metrics in callEnded() to guarantee all inbound metrics are
      // recorded.
    }

    void recordFinishedAttempt() {
      // TODO(dnvindhya) : add target as an attribute
      io.opentelemetry.api.common.Attributes attribute = io.opentelemetry.api.common.Attributes.of(
          AttributeKey.stringKey(OpenTelemetryConstants.METHOD_KEY),
          recordMethodName(fullMethodName, isGeneratedMethod),
          AttributeKey.stringKey(OpenTelemetryConstants.STATUS_KEY), statusCode.toString());

      module.state.clientAttemptDuration.record(attemptNanos / NANOS_PER_SECOND, attribute);
      module.state.clientTotalSentCompressedMessageSize.record(outboundWireSize, attribute);
      module.state.clientTotalReceivedCompressedMessageSize.record(inboundWireSize, attribute);
    }
  }

  @VisibleForTesting
  static final class CallAttemptsTracerFactory extends ClientStreamTracer.Factory {
    ClientTracer inboundMetricTracer;
    private final OpenTelemetryMetricsModule module;
    private final Stopwatch attemptStopwatch;
    private final Stopwatch callStopWatch;
    @GuardedBy("lock")
    private boolean callEnded;
    private final String fullMethodName;
    private final boolean isGeneratedMethod;
    private Status status;
    private long callLatencyNanos;
    private final Object lock = new Object();
    private final AtomicLong attemptsPerCall = new AtomicLong();
    @GuardedBy("lock")
    private int activeStreams;
    @GuardedBy("lock")
    private boolean finishedCallToBeRecorded;

    CallAttemptsTracerFactory(OpenTelemetryMetricsModule module, String fullMethodName,
        Boolean isGeneratedMethod ) {
      this.module = checkNotNull(module, "module");
      this.fullMethodName = checkNotNull(fullMethodName, "fullMethodName");
      this.isGeneratedMethod = isGeneratedMethod;
      this.attemptStopwatch = module.stopwatchSupplier.get();
      this.callStopWatch = module.stopwatchSupplier.get().start();

      // TODO(dnvindhya) : add target as an attribute
      io.opentelemetry.api.common.Attributes attribute = io.opentelemetry.api.common.Attributes.of(
          AttributeKey.stringKey(OpenTelemetryConstants.METHOD_KEY),
          recordMethodName(fullMethodName, isGeneratedMethod));

      // Record here in case mewClientStreamTracer() would never be called.
      module.state.clientAttemptCount.add(1, attribute);
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
      if (attemptsPerCall.get() > 0) {
        // TODO(dnvindhya): Add target as an attribute
        io.opentelemetry.api.common.Attributes attribute =
            io.opentelemetry.api.common.Attributes.of(
                AttributeKey.stringKey(OpenTelemetryConstants.METHOD_KEY),
                recordMethodName(fullMethodName, isGeneratedMethod));
        module.state.clientAttemptCount.add(1, attribute);
      }
      attemptsPerCall.incrementAndGet();
      return new ClientTracer(this, module, info, fullMethodName, isGeneratedMethod);
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
        ClientTracer tracer = new ClientTracer(this, module, null, fullMethodName,
            isGeneratedMethod);
        tracer.attemptNanos = attemptStopwatch.elapsed(TimeUnit.NANOSECONDS);
        tracer.statusCode = status.getCode();
        tracer.recordFinishedAttempt();
      } else if (inboundMetricTracer != null) {
        // activeStreams has been decremented to 0 by attemptEnded(),
        // so inboundMetricTracer.statusCode is guaranteed to be assigned already.
        inboundMetricTracer.recordFinishedAttempt();
      }
      callLatencyNanos = callStopWatch.elapsed(TimeUnit.NANOSECONDS);
      // TODO(dnvindhya): record target as an attribute
      io.opentelemetry.api.common.Attributes attribute
          = io.opentelemetry.api.common.Attributes.of(
          AttributeKey.stringKey(OpenTelemetryConstants.METHOD_KEY),
          recordMethodName(fullMethodName, isGeneratedMethod),
          AttributeKey.stringKey(OpenTelemetryConstants.STATUS_KEY), status.getCode().toString());

      module.state.clientCallDuration.record(callLatencyNanos / NANOS_PER_SECOND, attribute);
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
    private volatile boolean isGeneratedMethod;
    private volatile int streamClosed;
    private final Stopwatch stopwatch;
    private volatile long outboundWireSize;
    private volatile long inboundWireSize;

    ServerTracer(OpenTelemetryMetricsModule module, String fullMethodName) {
      this.module = checkNotNull(module, "module");
      this.fullMethodName = fullMethodName;
      this.stopwatch = module.stopwatchSupplier.get().start();
    }

    @Override
    public void serverCallStarted(ServerCallInfo<?, ?> callInfo) {
      // Only record method name as an attribute if isSampledToLocalTracing is set to true,
      // which is true for all generated methods. Otherwise, programatically
      // created methods result in high cardinality metrics.
      isGeneratedMethod = callInfo.getMethodDescriptor().isSampledToLocalTracing();
      io.opentelemetry.api.common.Attributes attribute =
          io.opentelemetry.api.common.Attributes.of(
              AttributeKey.stringKey(OpenTelemetryConstants.METHOD_KEY),
              recordMethodName(fullMethodName, isGeneratedMethod));

      module.state.serverCallCount.add(1, attribute);
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
      io.opentelemetry.api.common.Attributes attributes
          = io.opentelemetry.api.common.Attributes.of(
          AttributeKey.stringKey(OpenTelemetryConstants.METHOD_KEY),
          recordMethodName(fullMethodName, isGeneratedMethod),
          AttributeKey.stringKey(OpenTelemetryConstants.STATUS_KEY), status.getCode().toString());

      module.state.serverCallDuration.record(elapsedTimeNanos / NANOS_PER_SECOND, attributes);
      module.state.serverTotalSentCompressedMessageSize.record(outboundWireSize, attributes);
      module.state.serverTotalReceivedCompressedMessageSize.record(inboundWireSize, attributes);
    }
  }

  @VisibleForTesting
  final class ServerTracerFactory extends ServerStreamTracer.Factory {
    @Override
    public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
      return new ServerTracer(OpenTelemetryMetricsModule.this, fullMethodName);
    }
  }

  @VisibleForTesting
  final class MetricsClientInterceptor implements ClientInterceptor {
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      // Only record method name as an attribute if isSampledToLocalTracing is set to true,
      // which is true for all generated methods. Otherwise, programatically
      // created methods result in high cardinality metrics.
      final CallAttemptsTracerFactory tracerFactory = new CallAttemptsTracerFactory(
          OpenTelemetryMetricsModule.this, method.getFullMethodName(),
          method.isSampledToLocalTracing());
      ClientCall<ReqT, RespT> call =
          next.newCall(method, callOptions.withStreamTracerFactory(tracerFactory));
      return new SimpleForwardingClientCall<ReqT, RespT>(call) {
        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
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
