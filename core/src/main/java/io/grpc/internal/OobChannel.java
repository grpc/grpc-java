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
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ClientStreamTracer;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.Context;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalChannelz;
import io.grpc.InternalChannelz.ChannelStats;
import io.grpc.InternalChannelz.ChannelTrace;
import io.grpc.InternalInstrumented;
import io.grpc.InternalLogId;
import io.grpc.InternalWithLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.ClientCallImpl.ClientStreamProvider;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A ManagedChannel backed by a single {@link InternalSubchannel} and used for {@link LoadBalancer}
 * to its own RPC needs.
 */
@ThreadSafe
final class OobChannel extends ManagedChannel implements InternalInstrumented<ChannelStats> {
  private static final Logger log = Logger.getLogger(OobChannel.class.getName());

  private InternalSubchannel subchannel;
  private AbstractSubchannel subchannelImpl;
  private SubchannelPicker subchannelPicker;

  private final InternalLogId logId;
  private final String authority;
  private final DelayedClientTransport delayedTransport;
  private final InternalChannelz channelz;
  private final ObjectPool<? extends Executor> executorPool;
  private final Executor executor;
  private final ScheduledExecutorService deadlineCancellationExecutor;
  private final CountDownLatch terminatedLatch = new CountDownLatch(1);
  private volatile boolean shutdown;
  private final CallTracer channelCallsTracer;
  private final ChannelTracer channelTracer;
  private final TimeProvider timeProvider;

  private final ClientStreamProvider transportProvider = new ClientStreamProvider() {
    @Override
    public ClientStream newStream(MethodDescriptor<?, ?> method,
        CallOptions callOptions, Metadata headers, Context context) {
      ClientStreamTracer[] tracers = GrpcUtil.getClientStreamTracers(
          callOptions, headers, 0, /* isTransparentRetry= */ false,
          /* isHedging= */ false);
      Context origContext = context.attach();
      // delayed transport's newStream() always acquires a lock, but concurrent performance doesn't
      // matter here because OOB communication should be sparse, and it's not on application RPC's
      // critical path.
      try {
        return delayedTransport.newStream(method, headers, callOptions, tracers);
      } finally {
        context.detach(origContext);
      }
    }
  };

  OobChannel(
      String authority, ObjectPool<? extends Executor> executorPool,
      ScheduledExecutorService deadlineCancellationExecutor, SynchronizationContext syncContext,
      CallTracer callsTracer, ChannelTracer channelTracer, InternalChannelz channelz,
      TimeProvider timeProvider) {
    this.authority = checkNotNull(authority, "authority");
    this.logId = InternalLogId.allocate(getClass(), authority);
    this.executorPool = checkNotNull(executorPool, "executorPool");
    this.executor = checkNotNull(executorPool.getObject(), "executor");
    this.deadlineCancellationExecutor = checkNotNull(
        deadlineCancellationExecutor, "deadlineCancellationExecutor");
    this.delayedTransport = new DelayedClientTransport(executor, syncContext);
    this.channelz = Preconditions.checkNotNull(channelz);
    this.delayedTransport.start(new ManagedClientTransport.Listener() {
        @Override
        public void transportShutdown(Status s) {
          // Don't care
        }

        @Override
        public void transportTerminated() {
          subchannelImpl.shutdown();
        }

        @Override
        public void transportReady() {
          // Don't care
        }

        @Override
        public Attributes filterTransport(Attributes attributes) {
          return attributes;
        }

        @Override
        public void transportInUse(boolean inUse) {
          // Don't care
        }
      });
    this.channelCallsTracer = callsTracer;
    this.channelTracer = checkNotNull(channelTracer, "channelTracer");
    this.timeProvider = checkNotNull(timeProvider, "timeProvider");
  }

  // Must be called only once, right after the OobChannel is created.
  void setSubchannel(final InternalSubchannel subchannel) {
    log.log(Level.FINE, "[{0}] Created with [{1}]", new Object[] {this, subchannel});
    this.subchannel = subchannel;
    subchannelImpl = new AbstractSubchannel() {
        @Override
        public void shutdown() {
          subchannel.shutdown(Status.UNAVAILABLE.withDescription("OobChannel is shutdown"));
        }

        @Override
        InternalInstrumented<ChannelStats> getInstrumentedInternalSubchannel() {
          return subchannel;
        }

        @Override
        public void requestConnection() {
          subchannel.obtainActiveTransport();
        }

        @Override
        public List<EquivalentAddressGroup> getAllAddresses() {
          return subchannel.getAddressGroups();
        }

        @Override
        public Attributes getAttributes() {
          return Attributes.EMPTY;
        }

        @Override
        public Object getInternalSubchannel() {
          return subchannel;
        }
    };

    final class OobSubchannelPicker extends SubchannelPicker {
      final PickResult result = PickResult.withSubchannel(subchannelImpl);

      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return result;
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(OobSubchannelPicker.class)
            .add("result", result)
            .toString();
      }
    }

    subchannelPicker = new OobSubchannelPicker();
    delayedTransport.reprocess(subchannelPicker);
  }

  void updateAddresses(List<EquivalentAddressGroup> eag) {
    subchannel.updateAddresses(eag);
  }

  @Override
  public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
      MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions) {
    return new ClientCallImpl<>(methodDescriptor,
        callOptions.getExecutor() == null ? executor : callOptions.getExecutor(),
        callOptions, transportProvider, deadlineCancellationExecutor, channelCallsTracer, null);
  }

  @Override
  public String authority() {
    return authority;
  }

  @Override
  public boolean isTerminated() {
    return terminatedLatch.getCount() == 0;
  }

  @Override
  public boolean awaitTermination(long time, TimeUnit unit) throws InterruptedException {
    return terminatedLatch.await(time, unit);
  }

  @Override
  public ConnectivityState getState(boolean requestConnectionIgnored) {
    if (subchannel == null) {
      return ConnectivityState.IDLE;
    }
    return subchannel.getState();
  }

  @Override
  public ManagedChannel shutdown() {
    shutdown = true;
    delayedTransport.shutdown(Status.UNAVAILABLE.withDescription("OobChannel.shutdown() called"));
    return this;
  }

  @Override
  public boolean isShutdown() {
    return shutdown;
  }

  @Override
  public ManagedChannel shutdownNow() {
    shutdown = true;
    delayedTransport.shutdownNow(
        Status.UNAVAILABLE.withDescription("OobChannel.shutdownNow() called"));
    return this;
  }

  void handleSubchannelStateChange(final ConnectivityStateInfo newState) {
    channelTracer.reportEvent(
        new ChannelTrace.Event.Builder()
            .setDescription("Entering " + newState.getState() + " state")
            .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
            .setTimestampNanos(timeProvider.currentTimeNanos())
            .build());
    switch (newState.getState()) {
      case READY:
      case IDLE:
        delayedTransport.reprocess(subchannelPicker);
        break;
      case TRANSIENT_FAILURE:
        final class OobErrorPicker extends SubchannelPicker {
          final PickResult errorResult = PickResult.withError(newState.getStatus());

          @Override
          public PickResult pickSubchannel(PickSubchannelArgs args) {
            return errorResult;
          }

          @Override
          public String toString() {
            return MoreObjects.toStringHelper(OobErrorPicker.class)
                .add("errorResult", errorResult)
                .toString();
          }
        }

        delayedTransport.reprocess(new OobErrorPicker());
        break;
      default:
        // Do nothing
    }
  }

  // must be run from channel executor
  void handleSubchannelTerminated() {
    channelz.removeSubchannel(this);
    // When delayedTransport is terminated, it shuts down subchannel.  Therefore, at this point
    // both delayedTransport and subchannel have terminated.
    executorPool.returnObject(executor);
    terminatedLatch.countDown();
  }

  @VisibleForTesting
  Subchannel getSubchannel() {
    return subchannelImpl;
  }

  InternalSubchannel getInternalSubchannel() {
    return subchannel;
  }

  @Override
  public ListenableFuture<ChannelStats> getStats() {
    final SettableFuture<ChannelStats> ret = SettableFuture.create();
    final ChannelStats.Builder builder = new ChannelStats.Builder();
    channelCallsTracer.updateBuilder(builder);
    channelTracer.updateBuilder(builder);
    builder
        .setTarget(authority)
        .setState(subchannel.getState())
        .setSubchannels(Collections.<InternalWithLogId>singletonList(subchannel));
    ret.set(builder.build());
    return ret;
  }

  @Override
  public InternalLogId getLogId() {
    return logId;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("logId", logId.getId())
        .add("authority", authority)
        .toString();
  }

  @Override
  public void resetConnectBackoff() {
    subchannel.resetConnectBackoff();
  }
}
