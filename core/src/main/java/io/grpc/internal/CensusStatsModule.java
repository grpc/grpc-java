/*
 * Copyright 2016, gRPC Authors All rights reserved.
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
import static com.google.common.base.Preconditions.checkState;
import static io.opencensus.tags.unsafe.ContextUtils.TAG_CONTEXT_KEY;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientStreamTracer;
import io.grpc.Context;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.StreamTracer;
import io.opencensus.contrib.grpc.metrics.RpcMeasureConstants;
import io.opencensus.stats.MeasureMap;
import io.opencensus.stats.Stats;
import io.opencensus.stats.StatsRecorder;
import io.opencensus.tags.TagContext;
import io.opencensus.tags.TagValue;
import io.opencensus.tags.Tagger;
import io.opencensus.tags.Tags;
import io.opencensus.tags.propagation.TagContextBinarySerializer;
import io.opencensus.tags.propagation.TagContextSerializationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides factories for {@link StreamTracer} that records stats to Census.
 *
 * <p>On the client-side, a factory is created for each call, because ClientCall starts earlier than
 * the ClientStream, and in some cases may even not create a ClientStream at all.  Therefore, it's
 * the factory that reports the summary to Census.
 *
 * <p>On the server-side, there is only one ServerStream per each ServerCall, and ServerStream
 * starts earlier than the ServerCall.  Therefore, only one tracer is created per stream/call and
 * it's the tracer that reports the summary to Census.
 */
public final class CensusStatsModule {
  private static final Logger logger = Logger.getLogger(CensusStatsModule.class.getName());
  private static final double NANOS_PER_MILLI = TimeUnit.MILLISECONDS.toNanos(1);
  private static final ClientTracer BLANK_CLIENT_TRACER = new ClientTracer();

  private final Tagger tagger;
  private final StatsRecorder statsRecorder;
  private final Supplier<Stopwatch> stopwatchSupplier;
  @VisibleForTesting
  final Metadata.Key<TagContext> statsHeader;
  private final boolean propagateTags;

  /**
   * Creates a {@link CensusStatsModule} with the default OpenCensus implementation.
   */
  CensusStatsModule(Supplier<Stopwatch> stopwatchSupplier, boolean propagateTags) {
    this(
        Tags.getTagger(),
        Tags.getTagPropagationComponent().getBinarySerializer(),
        Stats.getStatsRecorder(),
        stopwatchSupplier,
        propagateTags);
  }

  /**
   * Creates a {@link CensusStatsModule} with the given OpenCensus implementation.
   */
  public CensusStatsModule(
      final Tagger tagger,
      final TagContextBinarySerializer tagCtxSerializer,
      StatsRecorder statsRecorder, Supplier<Stopwatch> stopwatchSupplier,
      boolean propagateTags) {
    this.tagger = checkNotNull(tagger, "tagger");
    this.statsRecorder = checkNotNull(statsRecorder, "statsRecorder");
    checkNotNull(tagCtxSerializer, "tagCtxSerializer");
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
    this.propagateTags = propagateTags;
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
   * Creates a {@link ClientCallTracer} for a new call.
   */
  @VisibleForTesting
  ClientCallTracer newClientCallTracer(
      TagContext parentCtx, String fullMethodName,
      boolean recordStartedRpcs, boolean recordFinishedRpcs) {
    return new ClientCallTracer(
        this, parentCtx, fullMethodName, recordStartedRpcs, recordFinishedRpcs);
  }

  /**
   * Returns the server tracer factory.
   */
  ServerStreamTracer.Factory getServerTracerFactory(
      boolean recordStartedRpcs, boolean recordFinishedRpcs) {
    return new ServerTracerFactory(recordStartedRpcs, recordFinishedRpcs);
  }

  /**
   * Returns the client interceptor that facilitates Census-based stats reporting.
   */
  ClientInterceptor getClientInterceptor(boolean recordStartedRpcs, boolean recordFinishedRpcs) {
    return new StatsClientInterceptor(recordStartedRpcs, recordFinishedRpcs);
  }

  private static final class ClientTracer extends ClientStreamTracer {
    // When using Atomic*FieldUpdater, some Samsung Android 5.0.x devices encounter a bug in the JDK
    // reflection API that triggers a NoSuchFieldException. When this occurs, fallback to a
    // synchronized implementation.
    private static final ClientTracerAtomicHelper atomicHelper = getAtomicHelper();

    private static ClientTracerAtomicHelper getAtomicHelper() {
      ClientTracerAtomicHelper helper;
      try {
        helper =
            new FieldUpdaterClientTracerAtomicHelper(
                AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "outboundMessageCount"),
                AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "inboundMessageCount"),
                AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "outboundWireSize"),
                AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "inboundWireSize"),
                AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "outboundUncompressedSize"),
                AtomicLongFieldUpdater.newUpdater(ClientTracer.class, "inboundUncompressedSize"));
      } catch (Throwable t) {
        logger.log(Level.WARNING, "FieldUpdaterClientTracerAtomicHelper failed", t);
        helper = new SynchronizedClientTracerAtomicHelper();
      }
      return helper;
    }

    volatile long outboundMessageCount;
    volatile long inboundMessageCount;
    volatile long outboundWireSize;
    volatile long inboundWireSize;
    volatile long outboundUncompressedSize;
    volatile long inboundUncompressedSize;

    @Override
    public void outboundWireSize(long bytes) {
      atomicHelper.outboundWireSizeGetAndAdd(this, bytes);
    }

    @Override
    public void inboundWireSize(long bytes) {
      atomicHelper.inboundWireSizeGetAndAdd(this, bytes);
    }

    @Override
    public void outboundUncompressedSize(long bytes) {
      atomicHelper.outboundUncompressedSizeGetAndAdd(this, bytes);
    }

    @Override
    public void inboundUncompressedSize(long bytes) {
      atomicHelper.inboundUncompressedSizeGetAndAdd(this, bytes);
    }

    @Override
    public void inboundMessage(int seqNo) {
      atomicHelper.inboundMessageCountGetAndIncrement(this);
    }

    @Override
    public void outboundMessage(int seqNo) {
      atomicHelper.outboundMessageCountGetAndIncrement(this);
    }

    private abstract static class ClientTracerAtomicHelper {
      public abstract long outboundMessageCountGetAndIncrement(ClientTracer obj);

      public abstract long inboundMessageCountGetAndIncrement(ClientTracer obj);

      public abstract long outboundWireSizeGetAndAdd(ClientTracer obj, long delta);

      public abstract long inboundWireSizeGetAndAdd(ClientTracer obj, long delta);

      public abstract long outboundUncompressedSizeGetAndAdd(ClientTracer obj, long delta);

      public abstract long inboundUncompressedSizeGetAndAdd(ClientTracer obj, long delta);
    }

    private static final class FieldUpdaterClientTracerAtomicHelper
        extends ClientTracerAtomicHelper {
      private final AtomicLongFieldUpdater<ClientTracer> outboundMessageCountUpdater;
      private final AtomicLongFieldUpdater<ClientTracer> inboundMessageCountUpdater;
      private final AtomicLongFieldUpdater<ClientTracer> outboundWireSizeUpdater;
      private final AtomicLongFieldUpdater<ClientTracer> inboundWireSizeUpdater;
      private final AtomicLongFieldUpdater<ClientTracer> outboundUncompressedSizeUpdater;
      private final AtomicLongFieldUpdater<ClientTracer> inboundUncompressedSizeUpdater;

      private FieldUpdaterClientTracerAtomicHelper(
          AtomicLongFieldUpdater<ClientTracer> outboundMessageCountUpdater,
          AtomicLongFieldUpdater<ClientTracer> inboundMessageCountUpdater,
          AtomicLongFieldUpdater<ClientTracer> outboundWireSizeUpdater,
          AtomicLongFieldUpdater<ClientTracer> inboundWireSizeUpdater,
          AtomicLongFieldUpdater<ClientTracer> outboundUncompressedSizeUpdater,
          AtomicLongFieldUpdater<ClientTracer> inboundUncompressedSizeUpdater) {
        this.outboundMessageCountUpdater = outboundMessageCountUpdater;
        this.inboundMessageCountUpdater = inboundMessageCountUpdater;
        this.outboundWireSizeUpdater = outboundWireSizeUpdater;
        this.inboundWireSizeUpdater = inboundWireSizeUpdater;
        this.outboundUncompressedSizeUpdater = outboundUncompressedSizeUpdater;
        this.inboundUncompressedSizeUpdater = inboundUncompressedSizeUpdater;
      }

      @Override
      public long outboundMessageCountGetAndIncrement(ClientTracer obj) {
        return outboundMessageCountUpdater.getAndIncrement(obj);
      }

      @Override
      public long inboundMessageCountGetAndIncrement(ClientTracer obj) {
        return inboundMessageCountUpdater.getAndIncrement(obj);
      }

      @Override
      public long outboundWireSizeGetAndAdd(ClientTracer obj, long delta) {
        return outboundWireSizeUpdater.getAndAdd(obj, delta);
      }

      @Override
      public long inboundWireSizeGetAndAdd(ClientTracer obj, long delta) {
        return inboundWireSizeUpdater.getAndAdd(obj, delta);
      }

      @Override
      public long outboundUncompressedSizeGetAndAdd(ClientTracer obj, long delta) {
        return outboundUncompressedSizeUpdater.getAndAdd(obj, delta);
      }

      @Override
      public long inboundUncompressedSizeGetAndAdd(ClientTracer obj, long delta) {
        return inboundUncompressedSizeUpdater.getAndAdd(obj, delta);
      }
    }

    private static final class SynchronizedClientTracerAtomicHelper
        extends ClientTracerAtomicHelper {

      @Override
      public long outboundMessageCountGetAndIncrement(ClientTracer obj) {
        synchronized (obj) {
          return obj.outboundMessageCount++;
        }
      }

      @Override
      public long inboundMessageCountGetAndIncrement(ClientTracer obj) {
        synchronized (obj) {
          return obj.inboundMessageCount++;
        }
      }

      @Override
      public long outboundWireSizeGetAndAdd(ClientTracer obj, long delta) {
        synchronized (obj) {
          long prev = obj.outboundWireSize;
          obj.outboundWireSize += delta;
          return prev;
        }
      }

      @Override
      public long inboundWireSizeGetAndAdd(ClientTracer obj, long delta) {
        synchronized (obj) {
          long prev = obj.inboundWireSize;
          obj.inboundWireSize += delta;
          return prev;
        }
      }

      @Override
      public long outboundUncompressedSizeGetAndAdd(ClientTracer obj, long delta) {
        synchronized (obj) {
          long prev = obj.outboundUncompressedSize;
          obj.outboundUncompressedSize += delta;
          return prev;
        }
      }

      @Override
      public long inboundUncompressedSizeGetAndAdd(ClientTracer obj, long delta) {
        synchronized (obj) {
          long prev = obj.inboundUncompressedSize;
          obj.inboundUncompressedSize += delta;
          return prev;
        }
      }
    }
  }

  @VisibleForTesting
  static final class ClientCallTracer extends ClientStreamTracer.Factory {
    // When using Atomic*FieldUpdater, some Samsung Android 5.0.x devices encounter a bug in the JDK
    // reflection API that triggers a NoSuchFieldException. When this occurs, fallback to a
    // synchronized implementation.
    private static final ClientCallTracerAtomicHelper atomicHelper = getAtomicHelper();

    private static ClientCallTracerAtomicHelper getAtomicHelper() {
      ClientCallTracerAtomicHelper helper;
      try {
        helper =
            new FieldUpdaterClientCallTracerAtomicHelper(
                AtomicReferenceFieldUpdater.newUpdater(
                    ClientCallTracer.class, ClientTracer.class, "streamTracer"),
                AtomicIntegerFieldUpdater.newUpdater(ClientCallTracer.class, "callEnded"));
      } catch (Throwable t) {
        logger.log(Level.WARNING, "FieldUpdaterClientCallTracerAtomicHelper failed", t);
        helper = new SynchronizedClientCallTracerAtomicHelper();
      }
      return helper;
    }

    private final CensusStatsModule module;
    private final String fullMethodName;
    private final Stopwatch stopwatch;
    private volatile ClientTracer streamTracer;
    private volatile int callEnded;
    private final TagContext parentCtx;
    private final TagContext startCtx;
    private final boolean recordFinishedRpcs;

    ClientCallTracer(
        CensusStatsModule module,
        TagContext parentCtx,
        String fullMethodName,
        boolean recordStartedRpcs,
        boolean recordFinishedRpcs) {
      this.module = module;
      this.fullMethodName = checkNotNull(fullMethodName, "fullMethodName");
      this.parentCtx = checkNotNull(parentCtx);
      this.startCtx =
          module.tagger.toBuilder(parentCtx)
          .put(RpcMeasureConstants.RPC_METHOD, TagValue.create(fullMethodName)).build();
      this.stopwatch = module.stopwatchSupplier.get().start();
      this.recordFinishedRpcs = recordFinishedRpcs;
      if (recordStartedRpcs) {
        module.statsRecorder.newMeasureMap().put(RpcMeasureConstants.RPC_CLIENT_STARTED_COUNT, 1)
            .record(startCtx);
      }
    }

    @Override
    public ClientStreamTracer newClientStreamTracer(CallOptions callOptions, Metadata headers) {
      ClientTracer tracer = new ClientTracer();
      // TODO(zhangkun83): Once retry or hedging is implemented, a ClientCall may start more than
      // one streams.  We will need to update this file to support them.
      checkState(
          atomicHelper.streamTracerCompareAndSet(this, null, tracer),
          "Are you creating multiple streams per call? This class doesn't yet support this case.");
      if (module.propagateTags) {
        headers.discardAll(module.statsHeader);
        if (!module.tagger.empty().equals(parentCtx)) {
          headers.put(module.statsHeader, parentCtx);
        }
      }
      return tracer;
    }

    /**
     * Record a finished call and mark the current time as the end time.
     *
     * <p>Can be called from any thread without synchronization.  Calling it the second time or more
     * is a no-op.
     */
    void callEnded(Status status) {
      if (atomicHelper.callEndedGetAndSet(this, 1) != 0) {
        return;
      }
      if (!recordFinishedRpcs) {
        return;
      }
      stopwatch.stop();
      long roundtripNanos = stopwatch.elapsed(TimeUnit.NANOSECONDS);
      ClientTracer tracer = streamTracer;
      if (tracer == null) {
        tracer = BLANK_CLIENT_TRACER;
      }
      MeasureMap measureMap = module.statsRecorder.newMeasureMap()
          .put(RpcMeasureConstants.RPC_CLIENT_FINISHED_COUNT, 1)
          // The latency is double value
          .put(RpcMeasureConstants.RPC_CLIENT_ROUNDTRIP_LATENCY, roundtripNanos / NANOS_PER_MILLI)
          .put(RpcMeasureConstants.RPC_CLIENT_REQUEST_COUNT, tracer.outboundMessageCount)
          .put(RpcMeasureConstants.RPC_CLIENT_RESPONSE_COUNT, tracer.inboundMessageCount)
          .put(RpcMeasureConstants.RPC_CLIENT_REQUEST_BYTES, tracer.outboundWireSize)
          .put(RpcMeasureConstants.RPC_CLIENT_RESPONSE_BYTES, tracer.inboundWireSize)
          .put(
              RpcMeasureConstants.RPC_CLIENT_UNCOMPRESSED_REQUEST_BYTES,
              tracer.outboundUncompressedSize)
          .put(
              RpcMeasureConstants.RPC_CLIENT_UNCOMPRESSED_RESPONSE_BYTES,
              tracer.inboundUncompressedSize);
      if (!status.isOk()) {
        measureMap.put(RpcMeasureConstants.RPC_CLIENT_ERROR_COUNT, 1);
      }
      measureMap.record(
          module
              .tagger
              .toBuilder(startCtx)
              .put(RpcMeasureConstants.RPC_STATUS, TagValue.create(status.getCode().toString()))
              .build());
    }

    private abstract static class ClientCallTracerAtomicHelper {
      public abstract boolean streamTracerCompareAndSet(
          ClientCallTracer obj, ClientTracer expect, ClientTracer update);

      public abstract int callEndedGetAndSet(ClientCallTracer obj, int newValue);
    }

    private static final class FieldUpdaterClientCallTracerAtomicHelper
        extends ClientCallTracerAtomicHelper {
      private final AtomicReferenceFieldUpdater<ClientCallTracer, ClientTracer> streamTracerUpdater;
      private final AtomicIntegerFieldUpdater<ClientCallTracer> callEndedUpdater;

      private FieldUpdaterClientCallTracerAtomicHelper(
          AtomicReferenceFieldUpdater<ClientCallTracer, ClientTracer> streamTracerUpdater,
          AtomicIntegerFieldUpdater<ClientCallTracer> callEndedUpdater) {
        this.streamTracerUpdater = streamTracerUpdater;
        this.callEndedUpdater = callEndedUpdater;
      }

      @Override
      public boolean streamTracerCompareAndSet(
          ClientCallTracer obj, ClientTracer expect, ClientTracer update) {
        return streamTracerUpdater.compareAndSet(obj, expect, update);
      }

      @Override
      public int callEndedGetAndSet(ClientCallTracer obj, int newValue) {
        return callEndedUpdater.getAndSet(obj, newValue);
      }
    }

    private static final class SynchronizedClientCallTracerAtomicHelper
        extends ClientCallTracerAtomicHelper {

      @Override
      public boolean streamTracerCompareAndSet(
          ClientCallTracer obj, ClientTracer expect, ClientTracer update) {
        synchronized (obj) {
          if (obj.streamTracer == expect) {
            obj.streamTracer = update;
            return true;
          }
          return false;
        }
      }

      @Override
      public int callEndedGetAndSet(ClientCallTracer obj, int newValue) {
        synchronized (obj) {
          int prev = obj.callEnded;
          obj.callEnded = newValue;
          return prev;
        }
      }
    }
  }

  private static final class ServerTracer extends ServerStreamTracer {
    // When using Atomic*FieldUpdater, some Samsung Android 5.0.x devices encounter a bug in the JDK
    // reflection API that triggers a NoSuchFieldException. When this occurs, fallback to a
    // synchronized implementation.
    private static final ServerTracerAtomicHelper atomicHelper = getAtomicHelper();

    private static ServerTracerAtomicHelper getAtomicHelper() {
      ServerTracerAtomicHelper helper;
      try {
        helper =
            new FieldUpdaterServerTracerAtomicHelper(
                AtomicIntegerFieldUpdater.newUpdater(ServerTracer.class, "streamClosed"),
                AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "outboundMessageCount"),
                AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "inboundMessageCount"),
                AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "outboundWireSize"),
                AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "inboundWireSize"),
                AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "outboundUncompressedSize"),
                AtomicLongFieldUpdater.newUpdater(ServerTracer.class, "inboundUncompressedSize"));
      } catch (Throwable t) {
        logger.log(Level.WARNING, "FieldUpdaterServerTracerAtomicHelper failed", t);
        helper = new SynchronizedServerTracerAtomicHelper();
      }
      return helper;
    }

    private final CensusStatsModule module;
    private final String fullMethodName;
    private final TagContext parentCtx;
    private volatile int streamClosed;
    private final Stopwatch stopwatch;
    private final Tagger tagger;
    private final boolean recordFinishedRpcs;
    private volatile long outboundMessageCount;
    private volatile long inboundMessageCount;
    private volatile long outboundWireSize;
    private volatile long inboundWireSize;
    private volatile long outboundUncompressedSize;
    private volatile long inboundUncompressedSize;

    ServerTracer(
        CensusStatsModule module,
        String fullMethodName,
        TagContext parentCtx,
        Supplier<Stopwatch> stopwatchSupplier,
        Tagger tagger,
        boolean recordStartedRpcs,
        boolean recordFinishedRpcs) {
      this.module = module;
      this.fullMethodName = checkNotNull(fullMethodName, "fullMethodName");
      this.parentCtx = checkNotNull(parentCtx, "parentCtx");
      this.stopwatch = stopwatchSupplier.get().start();
      this.tagger = tagger;
      this.recordFinishedRpcs = recordFinishedRpcs;
      if (recordStartedRpcs) {
        module.statsRecorder.newMeasureMap().put(RpcMeasureConstants.RPC_SERVER_STARTED_COUNT, 1)
            .record(parentCtx);
      }
    }

    @Override
    public void outboundWireSize(long bytes) {
      atomicHelper.outboundWireSizeGetAndAdd(this, bytes);
    }

    @Override
    public void inboundWireSize(long bytes) {
      atomicHelper.inboundWireSizeGetAndAdd(this, bytes);
    }

    @Override
    public void outboundUncompressedSize(long bytes) {
      atomicHelper.outboundUncompressedSizeGetAndAdd(this, bytes);
    }

    @Override
    public void inboundUncompressedSize(long bytes) {
      atomicHelper.inboundUncompressedSizeGetAndAdd(this, bytes);
    }

    @Override
    public void inboundMessage(int seqNo) {
      atomicHelper.inboundMessageCountGetAndIncrement(this);
    }

    @Override
    public void outboundMessage(int seqNo) {
      atomicHelper.outboundMessageCountGetAndIncrement(this);
    }

    /**
     * Record a finished stream and mark the current time as the end time.
     *
     * <p>Can be called from any thread without synchronization.  Calling it the second time or more
     * is a no-op.
     */
    @Override
    public void streamClosed(Status status) {
      if (atomicHelper.streamClosedGetAndSet(this, 1) != 0) {
        return;
      }
      if (!recordFinishedRpcs) {
        return;
      }
      stopwatch.stop();
      long elapsedTimeNanos = stopwatch.elapsed(TimeUnit.NANOSECONDS);
      MeasureMap measureMap = module.statsRecorder.newMeasureMap()
          .put(RpcMeasureConstants.RPC_SERVER_FINISHED_COUNT, 1)
          // The latency is double value
          .put(RpcMeasureConstants.RPC_SERVER_SERVER_LATENCY, elapsedTimeNanos / NANOS_PER_MILLI)
          .put(RpcMeasureConstants.RPC_SERVER_RESPONSE_COUNT, outboundMessageCount)
          .put(RpcMeasureConstants.RPC_SERVER_REQUEST_COUNT, inboundMessageCount)
          .put(RpcMeasureConstants.RPC_SERVER_RESPONSE_BYTES, outboundWireSize)
          .put(RpcMeasureConstants.RPC_SERVER_REQUEST_BYTES, inboundWireSize)
          .put(RpcMeasureConstants.RPC_SERVER_UNCOMPRESSED_RESPONSE_BYTES, outboundUncompressedSize)
          .put(RpcMeasureConstants.RPC_SERVER_UNCOMPRESSED_REQUEST_BYTES, inboundUncompressedSize);
      if (!status.isOk()) {
        measureMap.put(RpcMeasureConstants.RPC_SERVER_ERROR_COUNT, 1);
      }
      measureMap.record(
          module
              .tagger
              .toBuilder(parentCtx)
              .put(RpcMeasureConstants.RPC_STATUS, TagValue.create(status.getCode().toString()))
              .build());
    }

    @Override
    public Context filterContext(Context context) {
      if (!tagger.empty().equals(parentCtx)) {
        return context.withValue(TAG_CONTEXT_KEY, parentCtx);
      }
      return context;
    }

    private abstract static class ServerTracerAtomicHelper {
      public abstract int streamClosedGetAndSet(ServerTracer obj, int newValue);

      public abstract long outboundMessageCountGetAndIncrement(ServerTracer obj);

      public abstract long inboundMessageCountGetAndIncrement(ServerTracer obj);

      public abstract long outboundWireSizeGetAndAdd(ServerTracer obj, long delta);

      public abstract long inboundWireSizeGetAndAdd(ServerTracer obj, long delta);

      public abstract long outboundUncompressedSizeGetAndAdd(ServerTracer obj, long delta);

      public abstract long inboundUncompressedSizeGetAndAdd(ServerTracer obj, long delta);
    }

    private static final class FieldUpdaterServerTracerAtomicHelper
        extends ServerTracerAtomicHelper {
      private final AtomicIntegerFieldUpdater<ServerTracer> streamClosedUpdater;
      private final AtomicLongFieldUpdater<ServerTracer> outboundMessageCountUpdater;
      private final AtomicLongFieldUpdater<ServerTracer> inboundMessageCountUpdater;
      private final AtomicLongFieldUpdater<ServerTracer> outboundWireSizeUpdater;
      private final AtomicLongFieldUpdater<ServerTracer> inboundWireSizeUpdater;
      private final AtomicLongFieldUpdater<ServerTracer> outboundUncompressedSizeUpdater;
      private final AtomicLongFieldUpdater<ServerTracer> inboundUncompressedSizeUpdater;

      private FieldUpdaterServerTracerAtomicHelper(
          AtomicIntegerFieldUpdater<ServerTracer> streamClosedUpdater,
          AtomicLongFieldUpdater<ServerTracer> outboundMessageCountUpdater,
          AtomicLongFieldUpdater<ServerTracer> inboundMessageCountUpdater,
          AtomicLongFieldUpdater<ServerTracer> outboundWireSizeUpdater,
          AtomicLongFieldUpdater<ServerTracer> inboundWireSizeUpdater,
          AtomicLongFieldUpdater<ServerTracer> outboundUncompressedSizeUpdater,
          AtomicLongFieldUpdater<ServerTracer> inboundUncompressedSizeUpdater) {
        this.streamClosedUpdater = streamClosedUpdater;
        this.outboundMessageCountUpdater = outboundMessageCountUpdater;
        this.inboundMessageCountUpdater = inboundMessageCountUpdater;
        this.outboundWireSizeUpdater = outboundWireSizeUpdater;
        this.inboundWireSizeUpdater = inboundWireSizeUpdater;
        this.outboundUncompressedSizeUpdater = outboundUncompressedSizeUpdater;
        this.inboundUncompressedSizeUpdater = inboundUncompressedSizeUpdater;
      }

      @Override
      public int streamClosedGetAndSet(ServerTracer obj, int newValue) {
        return streamClosedUpdater.getAndSet(obj, newValue);
      }

      @Override
      public long outboundMessageCountGetAndIncrement(ServerTracer obj) {
        return outboundMessageCountUpdater.getAndIncrement(obj);
      }

      @Override
      public long inboundMessageCountGetAndIncrement(ServerTracer obj) {
        return inboundMessageCountUpdater.getAndIncrement(obj);
      }

      @Override
      public long outboundWireSizeGetAndAdd(ServerTracer obj, long delta) {
        return outboundWireSizeUpdater.getAndAdd(obj, delta);
      }

      @Override
      public long inboundWireSizeGetAndAdd(ServerTracer obj, long delta) {
        return inboundWireSizeUpdater.getAndAdd(obj, delta);
      }

      @Override
      public long outboundUncompressedSizeGetAndAdd(ServerTracer obj, long delta) {
        return outboundUncompressedSizeUpdater.getAndAdd(obj, delta);
      }

      @Override
      public long inboundUncompressedSizeGetAndAdd(ServerTracer obj, long delta) {
        return inboundUncompressedSizeUpdater.getAndAdd(obj, delta);
      }
    }

    private static final class SynchronizedServerTracerAtomicHelper
        extends ServerTracerAtomicHelper {

      @Override
      public int streamClosedGetAndSet(ServerTracer obj, int newValue) {
        synchronized (obj) {
          int prev = obj.streamClosed;
          obj.streamClosed = newValue;
          return prev;
        }
      }

      @Override
      public long outboundMessageCountGetAndIncrement(ServerTracer obj) {
        synchronized (obj) {
          return obj.outboundMessageCount++;
        }
      }

      @Override
      public long inboundMessageCountGetAndIncrement(ServerTracer obj) {
        synchronized (obj) {
          return obj.inboundMessageCount++;
        }
      }

      @Override
      public long outboundWireSizeGetAndAdd(ServerTracer obj, long delta) {
        synchronized (obj) {
          long prev = obj.outboundWireSize;
          obj.outboundWireSize += delta;
          return prev;
        }
      }

      @Override
      public long inboundWireSizeGetAndAdd(ServerTracer obj, long delta) {
        synchronized (obj) {
          long prev = obj.inboundWireSize;
          obj.inboundWireSize += delta;
          return prev;
        }
      }

      @Override
      public long outboundUncompressedSizeGetAndAdd(ServerTracer obj, long delta) {
        synchronized (obj) {
          long prev = obj.outboundUncompressedSize;
          obj.outboundUncompressedSize += delta;
          return prev;
        }
      }

      @Override
      public long inboundUncompressedSizeGetAndAdd(ServerTracer obj, long delta) {
        synchronized (obj) {
          long prev = obj.inboundUncompressedSize;
          obj.inboundUncompressedSize += delta;
          return prev;
        }
      }
    }
  }

  @VisibleForTesting
  final class ServerTracerFactory extends ServerStreamTracer.Factory {
    private final boolean recordStartedRpcs;
    private final boolean recordFinishedRpcs;

    ServerTracerFactory(boolean recordStartedRpcs, boolean recordFinishedRpcs) {
      this.recordStartedRpcs = recordStartedRpcs;
      this.recordFinishedRpcs = recordFinishedRpcs;
    }

    @Override
    public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
      TagContext parentCtx = headers.get(statsHeader);
      if (parentCtx == null) {
        parentCtx = tagger.empty();
      }
      parentCtx =
          tagger
              .toBuilder(parentCtx)
              .put(RpcMeasureConstants.RPC_METHOD, TagValue.create(fullMethodName))
              .build();
      return new ServerTracer(
          CensusStatsModule.this,
          fullMethodName,
          parentCtx,
          stopwatchSupplier,
          tagger,
          recordStartedRpcs,
          recordFinishedRpcs);
    }
  }

  @VisibleForTesting
  final class StatsClientInterceptor implements ClientInterceptor {
    private final boolean recordStartedRpcs;
    private final boolean recordFinishedRpcs;

    StatsClientInterceptor(boolean recordStartedRpcs, boolean recordFinishedRpcs) {
      this.recordStartedRpcs = recordStartedRpcs;
      this.recordFinishedRpcs = recordFinishedRpcs;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      // New RPCs on client-side inherit the tag context from the current Context.
      TagContext parentCtx = tagger.getCurrentTagContext();
      final ClientCallTracer tracerFactory =
          newClientCallTracer(parentCtx, method.getFullMethodName(),
              recordStartedRpcs, recordFinishedRpcs);
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
