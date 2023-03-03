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

package io.grpc.census;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.census.internal.ObservabilityCensusConstants.API_LATENCY_PER_CALL;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StreamTracer;
import io.grpc.census.internal.DeprecatedCensusConstants;
import io.opencensus.contrib.grpc.metrics.RpcMeasureConstants;
import io.opencensus.stats.Measure;
import io.opencensus.stats.Measure.MeasureDouble;
import io.opencensus.stats.Measure.MeasureLong;
import io.opencensus.stats.MeasureMap;
import io.opencensus.stats.Stats;
import io.opencensus.stats.StatsRecorder;
import io.opencensus.tags.TagContext;
import io.opencensus.tags.TagValue;
import io.opencensus.tags.Tagger;
import io.opencensus.tags.Tags;
import io.opencensus.tags.propagation.TagContextBinarySerializer;
import io.opencensus.tags.propagation.TagContextSerializationException;
import io.opencensus.tags.unsafe.ContextUtils;
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
 * Provides factories for {@link StreamTracer} that records stats to Census.
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
final class CensusStatsModule {
  private static final Logger logger = Logger.getLogger(CensusStatsModule.class.getName());
  private static final double NANOS_PER_MILLI = TimeUnit.MILLISECONDS.toNanos(1);

  private final Tagger tagger;
  private final StatsRecorder statsRecorder;
  private final Supplier<Stopwatch> stopwatchSupplier;
  @VisibleForTesting
  final Metadata.Key<TagContext> statsHeader;
  private final boolean propagateTags;
  private final boolean recordStartedRpcs;
  private final boolean recordFinishedRpcs;
  private final boolean recordRealTimeMetrics;
  private final boolean recordRetryMetrics;

  /**
   * Creates a {@link CensusStatsModule} with the default OpenCensus implementation.
   */
  CensusStatsModule(Supplier<Stopwatch> stopwatchSupplier,
      boolean propagateTags, boolean recordStartedRpcs, boolean recordFinishedRpcs,
      boolean recordRealTimeMetrics, boolean recordRetryMetrics) {
    this(
        Tags.getTagger(),
        Tags.getTagPropagationComponent().getBinarySerializer(),
        Stats.getStatsRecorder(),
        stopwatchSupplier,
        propagateTags, recordStartedRpcs, recordFinishedRpcs, recordRealTimeMetrics,
        recordRetryMetrics);
  }

  /**
   * Creates a {@link CensusStatsModule} with the given OpenCensus implementation.
   */
  CensusStatsModule(
      final Tagger tagger,
      final TagContextBinarySerializer tagCtxSerializer,
      StatsRecorder statsRecorder, Supplier<Stopwatch> stopwatchSupplier,
      boolean propagateTags, boolean recordStartedRpcs, boolean recordFinishedRpcs,
      boolean recordRealTimeMetrics, boolean recordRetryMetrics) {
    this.tagger = checkNotNull(tagger, "tagger");
    this.statsRecorder = checkNotNull(statsRecorder, "statsRecorder");
    checkNotNull(tagCtxSerializer, "tagCtxSerializer");
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
    this.propagateTags = propagateTags;
    this.recordStartedRpcs = recordStartedRpcs;
    this.recordFinishedRpcs = recordFinishedRpcs;
    this.recordRealTimeMetrics = recordRealTimeMetrics;
    this.recordRetryMetrics = recordRetryMetrics;
    this.statsHeader =
        Metadata.Key.of("grpc-tags-bin", new Metadata.BinaryMarshaller<TagContext>() {
            @Override
            public byte[] toBytes(TagContext context) {
              // TODO(carl-mastrangelo): currently we only make sure the correctness. We may need to
              // optimize out the allocation and copy in the future.
              try {
                return tagCtxSerializer.toByteArray(context);
              } catch (TagContextSerializationException e) {
                throw new RuntimeException(e);
              }
            }

            @Override
            public TagContext parseBytes(byte[] serialized) {
              try {
                return tagCtxSerializer.fromByteArray(serialized);
              } catch (Exception e) {
                logger.log(Level.FINE, "Failed to parse stats header", e);
                return tagger.empty();
              }
            }
          });
  }

  /**
   * Returns the server tracer factory.
   */
  ServerStreamTracer.Factory getServerTracerFactory() {
    return new ServerTracerFactory();
  }

  /**
   * Returns the client interceptor that facilitates Census-based stats reporting.
   */
  ClientInterceptor getClientInterceptor() {
    return new StatsClientInterceptor();
  }

  private void recordRealTimeMetric(TagContext ctx, MeasureDouble measure, double value) {
    if (recordRealTimeMetrics) {
      MeasureMap measureMap = statsRecorder.newMeasureMap().put(measure, value);
      measureMap.record(ctx);
    }
  }

  private void recordRealTimeMetric(TagContext ctx, MeasureLong measure, long value) {
    if (recordRealTimeMetrics) {
      MeasureMap measureMap = statsRecorder.newMeasureMap().put(measure, value);
      measureMap.record(ctx);
    }
  }

  private static final class ClientTracer extends ClientStreamTracer {
    @Nullable private static final AtomicLongFieldUpdater<ClientTracer> outboundMessageCountUpdater;
    @Nullable private static final AtomicLongFieldUpdater<ClientTracer> inboundMessageCountUpdater;
    @Nullable private static final AtomicLongFieldUpdater<ClientTracer> outboundWireSizeUpdater;
    @Nullable private static final AtomicLongFieldUpdater<ClientTracer> inboundWireSizeUpdater;

    @Nullable
    private static final AtomicLongFieldUpdater<ClientTracer> outboundUncompressedSizeUpdater;

    @Nullable
    private static final AtomicLongFieldUpdater<ClientTracer> inboundUncompressedSizeUpdater;

    /*
     * When using Atomic*FieldUpdater, some Samsung Android 5.0.x devices encounter a bug in their
     * JDK reflection API that triggers a NoSuchFieldException. When this occurs, we fallback to
     * (potentially racy) direct updates of the volatile variables.
     */
    static {
      AtomicLongFieldUpdater<ClientTracer> tmpOutboundMessageCountUpdater;
      AtomicLongFieldUpdater<ClientTracer> tmpInboundMessageCountUpdater;
      AtomicLongFieldUpdater<ClientTracer> tmpOutboundWireSizeUpdater;
      AtomicLongFieldUpdater<ClientTracer> tmpInboundWireSizeUpdater;
      AtomicLongFieldUpdater<ClientTracer> tmpOutboundUncompressedSizeUpdater;
      AtomicLongFieldUpdater<ClientTracer> tmpInboundUncompressedSizeUpdater;
      try {
        tmpOutboundMessageCountUpdater =
            AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "outboundMessageCount");
        tmpInboundMessageCountUpdater =
            AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "inboundMessageCount");
        tmpOutboundWireSizeUpdater =
            AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "outboundWireSize");
        tmpInboundWireSizeUpdater =
            AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "inboundWireSize");
        tmpOutboundUncompressedSizeUpdater =
            AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "outboundUncompressedSize");
        tmpInboundUncompressedSizeUpdater =
            AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "inboundUncompressedSize");
      } catch (Throwable t) {
        logger.log(Level.SEVERE, "Creating atomic field updaters failed", t);
        tmpOutboundMessageCountUpdater = null;
        tmpInboundMessageCountUpdater = null;
        tmpOutboundWireSizeUpdater = null;
        tmpInboundWireSizeUpdater = null;
        tmpOutboundUncompressedSizeUpdater = null;
        tmpInboundUncompressedSizeUpdater = null;
      }
      outboundMessageCountUpdater = tmpOutboundMessageCountUpdater;
      inboundMessageCountUpdater = tmpInboundMessageCountUpdater;
      outboundWireSizeUpdater = tmpOutboundWireSizeUpdater;
      inboundWireSizeUpdater = tmpInboundWireSizeUpdater;
      outboundUncompressedSizeUpdater = tmpOutboundUncompressedSizeUpdater;
      inboundUncompressedSizeUpdater = tmpInboundUncompressedSizeUpdater;
    }

    final Stopwatch stopwatch;
    final CallAttemptsTracerFactory attemptsState;
    final AtomicBoolean inboundReceivedOrClosed = new AtomicBoolean();
    final CensusStatsModule module;
    final TagContext parentCtx;
    final TagContext startCtx;
    final StreamInfo info;
    volatile long outboundMessageCount;
    volatile long inboundMessageCount;
    volatile long outboundWireSize;
    volatile long inboundWireSize;
    volatile long outboundUncompressedSize;
    volatile long inboundUncompressedSize;
    long roundtripNanos;
    Code statusCode;

    ClientTracer(
        CallAttemptsTracerFactory attemptsState, CensusStatsModule module, TagContext parentCtx,
        TagContext startCtx, StreamInfo info) {
      this.attemptsState = attemptsState;
      this.module = module;
      this.parentCtx = parentCtx;
      this.startCtx = startCtx;
      this.info = info;
      this.stopwatch = module.stopwatchSupplier.get().start();
    }

    @Override
    public void streamCreated(Attributes transportAttrs, Metadata headers) {
      if (module.propagateTags) {
        headers.discardAll(module.statsHeader);
        if (!module.tagger.empty().equals(parentCtx)) {
          headers.put(module.statsHeader, parentCtx);
        }
      }
    }

    @Override
    @SuppressWarnings({"NonAtomicVolatileUpdate", "NonAtomicOperationOnVolatileField"})
    public void outboundWireSize(long bytes) {
      if (outboundWireSizeUpdater != null) {
        outboundWireSizeUpdater.getAndAdd(this, bytes);
      } else {
        outboundWireSize += bytes;
      }
      module.recordRealTimeMetric(
          startCtx, RpcMeasureConstants.GRPC_CLIENT_SENT_BYTES_PER_METHOD, (double) bytes);
    }

    @Override
    @SuppressWarnings("NonAtomicVolatileUpdate")
    public void inboundWireSize(long bytes) {
      if (inboundWireSizeUpdater != null) {
        inboundWireSizeUpdater.getAndAdd(this, bytes);
      } else {
        inboundWireSize += bytes;
      }
      module.recordRealTimeMetric(
          startCtx, RpcMeasureConstants.GRPC_CLIENT_RECEIVED_BYTES_PER_METHOD, (double) bytes);
    }

    @Override
    @SuppressWarnings("NonAtomicVolatileUpdate")
    public void outboundUncompressedSize(long bytes) {
      if (outboundUncompressedSizeUpdater != null) {
        outboundUncompressedSizeUpdater.getAndAdd(this, bytes);
      } else {
        outboundUncompressedSize += bytes;
      }
    }

    @Override
    @SuppressWarnings("NonAtomicVolatileUpdate")
    public void inboundUncompressedSize(long bytes) {
      if (inboundUncompressedSizeUpdater != null) {
        inboundUncompressedSizeUpdater.getAndAdd(this, bytes);
      } else {
        inboundUncompressedSize += bytes;
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
      if (inboundMessageCountUpdater != null) {
        inboundMessageCountUpdater.getAndIncrement(this);
      } else {
        inboundMessageCount++;
      }
      module.recordRealTimeMetric(
          startCtx, RpcMeasureConstants.GRPC_CLIENT_RECEIVED_MESSAGES_PER_METHOD, 1);
    }

    @Override
    @SuppressWarnings("NonAtomicVolatileUpdate")
    public void outboundMessage(int seqNo) {
      if (outboundMessageCountUpdater != null) {
        outboundMessageCountUpdater.getAndIncrement(this);
      } else {
        outboundMessageCount++;
      }
      module.recordRealTimeMetric(
          startCtx, RpcMeasureConstants.GRPC_CLIENT_SENT_MESSAGES_PER_METHOD, 1);
    }

    @Override
    public void streamClosed(Status status) {
      stopwatch.stop();
      roundtripNanos = stopwatch.elapsed(TimeUnit.NANOSECONDS);
      Deadline deadline = info.getCallOptions().getDeadline();
      statusCode = status.getCode();
      if (statusCode == Status.Code.CANCELLED && deadline != null) {
        // When the server's deadline expires, it can only reset the stream with CANCEL and no
        // description. Since our timer may be delayed in firing, we double-check the deadline and
        // turn the failure into the likely more helpful DEADLINE_EXCEEDED status.
        if (deadline.isExpired()) {
          statusCode = Code.DEADLINE_EXCEEDED;
        }
      }
      attemptsState.attemptEnded();
      if (inboundReceivedOrClosed.compareAndSet(false, true)) {
        if (module.recordFinishedRpcs) {
          // Stream is closed early. So no need to record metrics for any inbound events after this
          // point.
          recordFinishedAttempt();
        }
      } // Otherwise will report stats in callEnded() to guarantee all inbound metrics are recorded.
    }

    void recordFinishedAttempt() {
      MeasureMap measureMap =
          module
              .statsRecorder
              .newMeasureMap()
              // TODO(songya): remove the deprecated measure constants once they are completed
              // removed.
              .put(DeprecatedCensusConstants.RPC_CLIENT_FINISHED_COUNT, 1)
              // The latency is double value
              .put(
                  RpcMeasureConstants.GRPC_CLIENT_ROUNDTRIP_LATENCY,
                  roundtripNanos / NANOS_PER_MILLI)
              .put(RpcMeasureConstants.GRPC_CLIENT_SENT_MESSAGES_PER_RPC, outboundMessageCount)
              .put(RpcMeasureConstants.GRPC_CLIENT_RECEIVED_MESSAGES_PER_RPC, inboundMessageCount)
              .put(RpcMeasureConstants.GRPC_CLIENT_SENT_BYTES_PER_RPC, (double) outboundWireSize)
              .put(RpcMeasureConstants.GRPC_CLIENT_RECEIVED_BYTES_PER_RPC, (double) inboundWireSize)
              .put(
                  DeprecatedCensusConstants.RPC_CLIENT_UNCOMPRESSED_REQUEST_BYTES,
                  (double) outboundUncompressedSize)
              .put(
                  DeprecatedCensusConstants.RPC_CLIENT_UNCOMPRESSED_RESPONSE_BYTES,
                  (double) inboundUncompressedSize);
      if (statusCode != Code.OK) {
        measureMap.put(DeprecatedCensusConstants.RPC_CLIENT_ERROR_COUNT, 1);
      }
      TagValue statusTag = TagValue.create(statusCode.toString());
      measureMap.record(
          module
              .tagger
              .toBuilder(startCtx)
              .putLocal(RpcMeasureConstants.GRPC_CLIENT_STATUS, statusTag)
              .build());
    }
  }

  @VisibleForTesting
  static final class CallAttemptsTracerFactory extends
      ClientStreamTracer.Factory {
    static final MeasureLong RETRIES_PER_CALL =
        Measure.MeasureLong.create(
            "grpc.io/client/retries_per_call", "Number of retries per call", "1");
    static final MeasureLong TRANSPARENT_RETRIES_PER_CALL =
        Measure.MeasureLong.create(
            "grpc.io/client/transparent_retries_per_call", "Transparent retries per call", "1");
    static final MeasureDouble RETRY_DELAY_PER_CALL =
        Measure.MeasureDouble.create(
            "grpc.io/client/retry_delay_per_call", "Retry delay per call", "ms");

    ClientTracer inboundMetricTracer;
    private final CensusStatsModule module;
    private final Stopwatch stopwatch;
    private final Stopwatch callStopwatch;
    @GuardedBy("lock")
    private boolean callEnded;
    private final TagContext parentCtx;
    private final TagContext startCtx;
    private final String fullMethodName;

    // TODO(zdapeng): optimize memory allocation using AtomicFieldUpdater.
    private final AtomicLong attemptsPerCall = new AtomicLong();
    private final AtomicLong transparentRetriesPerCall = new AtomicLong();
    // write happens before read
    private Status status;
    private final Object lock = new Object();
    // write @GuardedBy("lock") and happens before read
    private long retryDelayNanos;
    private long callLatencyNanos;
    @GuardedBy("lock")
    private int activeStreams;
    @GuardedBy("lock")
    private boolean finishedCallToBeRecorded;

    CallAttemptsTracerFactory(
        CensusStatsModule module, TagContext parentCtx, String fullMethodName) {
      this.module = checkNotNull(module, "module");
      this.parentCtx = checkNotNull(parentCtx, "parentCtx");
      this.fullMethodName = checkNotNull(fullMethodName, "fullMethodName");
      this.stopwatch = module.stopwatchSupplier.get();
      this.callStopwatch = module.stopwatchSupplier.get().start();
      TagValue methodTag = TagValue.create(fullMethodName);
      startCtx = module.tagger.toBuilder(parentCtx)
          .putLocal(RpcMeasureConstants.GRPC_CLIENT_METHOD, methodTag)
          .build();
      if (module.recordStartedRpcs) {
        // Record here in case newClientStreamTracer() would never be called.
        module.statsRecorder.newMeasureMap()
            .put(RpcMeasureConstants.GRPC_CLIENT_STARTED_RPCS, 1)
            .record(startCtx);
      }
    }

    @Override
    public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata metadata) {
      synchronized (lock) {
        if (finishedCallToBeRecorded) {
          // This can be the case when the called is cancelled but a retry attempt is created.
          return new ClientStreamTracer() {};
        }
        if (++activeStreams == 1 && stopwatch.isRunning()) {
          stopwatch.stop();
          retryDelayNanos = stopwatch.elapsed(TimeUnit.NANOSECONDS);
        }
      }
      if (module.recordStartedRpcs && attemptsPerCall.get() > 0) {
        module.statsRecorder.newMeasureMap()
            .put(RpcMeasureConstants.GRPC_CLIENT_STARTED_RPCS, 1)
            .record(startCtx);
      }
      if (info.isTransparentRetry()) {
        transparentRetriesPerCall.incrementAndGet();
      } else {
        attemptsPerCall.incrementAndGet();
      }
      return new ClientTracer(this, module, parentCtx, startCtx, info);
    }

    // Called whenever each attempt is ended.
    void attemptEnded() {
      if (!module.recordFinishedRpcs) {
        return;
      }
      boolean shouldRecordFinishedCall = false;
      synchronized (lock) {
        if (--activeStreams == 0) {
          stopwatch.start();
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
      if (!module.recordFinishedRpcs) {
        return;
      }
      callStopwatch.stop();
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
        ClientTracer tracer = new ClientTracer(this, module, parentCtx, startCtx, null);
        tracer.roundtripNanos = stopwatch.elapsed(TimeUnit.NANOSECONDS);
        tracer.statusCode = status.getCode();
        tracer.recordFinishedAttempt();
      } else if (inboundMetricTracer != null) {
        // activeStreams has been decremented to 0 by attemptEnded(),
        // so inboundMetricTracer.statusCode is guaranteed to be assigned already.
        inboundMetricTracer.recordFinishedAttempt();
      }
      if (!module.recordRetryMetrics) {
        return;
      }
      long retriesPerCall = 0;
      long attempts = attemptsPerCall.get();
      if (attempts > 0) {
        retriesPerCall = attempts - 1;
      }
      callLatencyNanos = callStopwatch.elapsed(TimeUnit.NANOSECONDS);
      MeasureMap measureMap = module.statsRecorder.newMeasureMap()
          .put(RETRIES_PER_CALL, retriesPerCall)
          .put(TRANSPARENT_RETRIES_PER_CALL, transparentRetriesPerCall.get())
          .put(RETRY_DELAY_PER_CALL, retryDelayNanos / NANOS_PER_MILLI)
          .put(API_LATENCY_PER_CALL, callLatencyNanos / NANOS_PER_MILLI);
      TagValue methodTag = TagValue.create(fullMethodName);
      TagValue statusTag = TagValue.create(status.getCode().toString());
      measureMap.record(
          module.tagger
              .toBuilder(parentCtx)
              .putLocal(RpcMeasureConstants.GRPC_CLIENT_METHOD, methodTag)
              .putLocal(RpcMeasureConstants.GRPC_CLIENT_STATUS, statusTag)
              .build());
    }
  }

  private static final class ServerTracer extends ServerStreamTracer {
    @Nullable private static final AtomicIntegerFieldUpdater<ServerTracer> streamClosedUpdater;
    @Nullable private static final AtomicLongFieldUpdater<ServerTracer> outboundMessageCountUpdater;
    @Nullable private static final AtomicLongFieldUpdater<ServerTracer> inboundMessageCountUpdater;
    @Nullable private static final AtomicLongFieldUpdater<ServerTracer> outboundWireSizeUpdater;
    @Nullable private static final AtomicLongFieldUpdater<ServerTracer> inboundWireSizeUpdater;

    @Nullable
    private static final AtomicLongFieldUpdater<ServerTracer> outboundUncompressedSizeUpdater;

    @Nullable
    private static final AtomicLongFieldUpdater<ServerTracer> inboundUncompressedSizeUpdater;

    /*
     * When using Atomic*FieldUpdater, some Samsung Android 5.0.x devices encounter a bug in their
     * JDK reflection API that triggers a NoSuchFieldException. When this occurs, we fallback to
     * (potentially racy) direct updates of the volatile variables.
     */
    static {
      AtomicIntegerFieldUpdater<ServerTracer> tmpStreamClosedUpdater;
      AtomicLongFieldUpdater<ServerTracer> tmpOutboundMessageCountUpdater;
      AtomicLongFieldUpdater<ServerTracer> tmpInboundMessageCountUpdater;
      AtomicLongFieldUpdater<ServerTracer> tmpOutboundWireSizeUpdater;
      AtomicLongFieldUpdater<ServerTracer> tmpInboundWireSizeUpdater;
      AtomicLongFieldUpdater<ServerTracer> tmpOutboundUncompressedSizeUpdater;
      AtomicLongFieldUpdater<ServerTracer> tmpInboundUncompressedSizeUpdater;
      try {
        tmpStreamClosedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(ServerTracer.class, "streamClosed");
        tmpOutboundMessageCountUpdater =
            AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "outboundMessageCount");
        tmpInboundMessageCountUpdater =
            AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "inboundMessageCount");
        tmpOutboundWireSizeUpdater =
            AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "outboundWireSize");
        tmpInboundWireSizeUpdater =
            AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "inboundWireSize");
        tmpOutboundUncompressedSizeUpdater =
            AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "outboundUncompressedSize");
        tmpInboundUncompressedSizeUpdater =
            AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "inboundUncompressedSize");
      } catch (Throwable t) {
        logger.log(Level.SEVERE, "Creating atomic field updaters failed", t);
        tmpStreamClosedUpdater = null;
        tmpOutboundMessageCountUpdater = null;
        tmpInboundMessageCountUpdater = null;
        tmpOutboundWireSizeUpdater = null;
        tmpInboundWireSizeUpdater = null;
        tmpOutboundUncompressedSizeUpdater = null;
        tmpInboundUncompressedSizeUpdater = null;
      }
      streamClosedUpdater = tmpStreamClosedUpdater;
      outboundMessageCountUpdater = tmpOutboundMessageCountUpdater;
      inboundMessageCountUpdater = tmpInboundMessageCountUpdater;
      outboundWireSizeUpdater = tmpOutboundWireSizeUpdater;
      inboundWireSizeUpdater = tmpInboundWireSizeUpdater;
      outboundUncompressedSizeUpdater = tmpOutboundUncompressedSizeUpdater;
      inboundUncompressedSizeUpdater = tmpInboundUncompressedSizeUpdater;
    }

    private final CensusStatsModule module;
    private final TagContext parentCtx;
    private volatile int streamClosed;
    private final Stopwatch stopwatch;
    private volatile long outboundMessageCount;
    private volatile long inboundMessageCount;
    private volatile long outboundWireSize;
    private volatile long inboundWireSize;
    private volatile long outboundUncompressedSize;
    private volatile long inboundUncompressedSize;

    ServerTracer(
        CensusStatsModule module,
        TagContext parentCtx) {
      this.module = checkNotNull(module, "module");
      this.parentCtx = checkNotNull(parentCtx, "parentCtx");
      this.stopwatch = module.stopwatchSupplier.get().start();
      if (module.recordStartedRpcs) {
        module.statsRecorder.newMeasureMap()
            .put(RpcMeasureConstants.GRPC_SERVER_STARTED_RPCS, 1)
            .record(parentCtx);
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
      module.recordRealTimeMetric(
          parentCtx, RpcMeasureConstants.GRPC_SERVER_SENT_BYTES_PER_METHOD, (double) bytes);
    }

    @Override
    @SuppressWarnings("NonAtomicVolatileUpdate")
    public void inboundWireSize(long bytes) {
      if (inboundWireSizeUpdater != null) {
        inboundWireSizeUpdater.getAndAdd(this, bytes);
      } else {
        inboundWireSize += bytes;
      }
      module.recordRealTimeMetric(
          parentCtx, RpcMeasureConstants.GRPC_SERVER_RECEIVED_BYTES_PER_METHOD, (double) bytes);
    }

    @Override
    @SuppressWarnings("NonAtomicVolatileUpdate")
    public void outboundUncompressedSize(long bytes) {
      if (outboundUncompressedSizeUpdater != null) {
        outboundUncompressedSizeUpdater.getAndAdd(this, bytes);
      } else {
        outboundUncompressedSize += bytes;
      }
    }

    @Override
    @SuppressWarnings("NonAtomicVolatileUpdate")
    public void inboundUncompressedSize(long bytes) {
      if (inboundUncompressedSizeUpdater != null) {
        inboundUncompressedSizeUpdater.getAndAdd(this, bytes);
      } else {
        inboundUncompressedSize += bytes;
      }
    }

    @Override
    @SuppressWarnings("NonAtomicVolatileUpdate")
    public void inboundMessage(int seqNo) {
      if (inboundMessageCountUpdater != null) {
        inboundMessageCountUpdater.getAndIncrement(this);
      } else {
        inboundMessageCount++;
      }
      module.recordRealTimeMetric(
          parentCtx, RpcMeasureConstants.GRPC_SERVER_RECEIVED_MESSAGES_PER_METHOD, 1);
    }

    @Override
    @SuppressWarnings("NonAtomicVolatileUpdate")
    public void outboundMessage(int seqNo) {
      if (outboundMessageCountUpdater != null) {
        outboundMessageCountUpdater.getAndIncrement(this);
      } else {
        outboundMessageCount++;
      }
      module.recordRealTimeMetric(
          parentCtx, RpcMeasureConstants.GRPC_SERVER_SENT_MESSAGES_PER_METHOD, 1);
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
      if (!module.recordFinishedRpcs) {
        return;
      }
      stopwatch.stop();
      long elapsedTimeNanos = stopwatch.elapsed(TimeUnit.NANOSECONDS);
      MeasureMap measureMap =
          module
              .statsRecorder
              .newMeasureMap()
              // TODO(songya): remove the deprecated measure constants once they are completed
              // removed.
              .put(DeprecatedCensusConstants.RPC_SERVER_FINISHED_COUNT, 1)
              // The latency is double value
              .put(
                  RpcMeasureConstants.GRPC_SERVER_SERVER_LATENCY,
                  elapsedTimeNanos / NANOS_PER_MILLI)
              .put(RpcMeasureConstants.GRPC_SERVER_SENT_MESSAGES_PER_RPC, outboundMessageCount)
              .put(RpcMeasureConstants.GRPC_SERVER_RECEIVED_MESSAGES_PER_RPC, inboundMessageCount)
              .put(RpcMeasureConstants.GRPC_SERVER_SENT_BYTES_PER_RPC, (double) outboundWireSize)
              .put(RpcMeasureConstants.GRPC_SERVER_RECEIVED_BYTES_PER_RPC, (double) inboundWireSize)
              .put(
                  DeprecatedCensusConstants.RPC_SERVER_UNCOMPRESSED_RESPONSE_BYTES,
                  (double) outboundUncompressedSize)
              .put(
                  DeprecatedCensusConstants.RPC_SERVER_UNCOMPRESSED_REQUEST_BYTES,
                  (double) inboundUncompressedSize);
      if (!status.isOk()) {
        measureMap.put(DeprecatedCensusConstants.RPC_SERVER_ERROR_COUNT, 1);
      }
      TagValue statusTag = TagValue.create(status.getCode().toString());
      measureMap.record(
          module
              .tagger
              .toBuilder(parentCtx)
              .putLocal(RpcMeasureConstants.GRPC_SERVER_STATUS, statusTag)
              .build());
    }

    @Override
    public Context filterContext(Context context) {
      return ContextUtils.withValue(context, parentCtx);
    }
  }

  @VisibleForTesting
  final class ServerTracerFactory extends ServerStreamTracer.Factory {
    @Override
    public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
      TagContext parentCtx = headers.get(statsHeader);
      if (parentCtx == null) {
        parentCtx = tagger.empty();
      }
      TagValue methodTag = TagValue.create(fullMethodName);
      parentCtx =
          tagger
              .toBuilder(parentCtx)
              .putLocal(RpcMeasureConstants.GRPC_SERVER_METHOD, methodTag)
              .build();
      return new ServerTracer(CensusStatsModule.this, parentCtx);
    }
  }

  @VisibleForTesting
  final class StatsClientInterceptor implements ClientInterceptor {
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      // New RPCs on client-side inherit the tag context from the current Context.
      TagContext parentCtx = tagger.getCurrentTagContext();
      final CallAttemptsTracerFactory tracerFactory = new CallAttemptsTracerFactory(
          CensusStatsModule.this, parentCtx, method.getFullMethodName());
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
