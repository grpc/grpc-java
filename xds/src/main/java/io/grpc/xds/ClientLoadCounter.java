/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.util.ForwardingClientStreamTracer;
import io.grpc.xds.XdsLoadStatsStore.StatsCounter;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Client side aggregator for load stats.
 *
 * <p>All methods except {@link #snapshot()} in this class are thread-safe.
 */
@NotThreadSafe
final class ClientLoadCounter extends XdsLoadStatsStore.StatsCounter {
  private final AtomicLong callsInProgress = new AtomicLong();
  private final AtomicLong callsFinished = new AtomicLong();
  private final AtomicLong callsFailed = new AtomicLong();

  ClientLoadCounter() {
  }

  /**
   * Must only be used for testing.
   */
  @VisibleForTesting
  ClientLoadCounter(long callsFinished, long callsInProgress, long callsFailed) {
    this.callsFinished.set(callsFinished);
    this.callsInProgress.set(callsInProgress);
    this.callsFailed.set(callsFailed);
  }

  @Override
  void incrementCallsInProgress() {
    callsInProgress.getAndIncrement();
  }

  @Override
  void decrementCallsInProgress() {
    callsInProgress.getAndDecrement();
  }

  @Override
  void incrementCallsFinished() {
    callsFinished.getAndIncrement();
  }

  @Override
  void incrementCallsFailed() {
    callsFailed.getAndIncrement();
  }

  /**
   * Generate snapshot for recorded query counts and metrics since previous snapshot.
   *
   * <p>This method is not thread-safe and must be called from {@link
   * io.grpc.LoadBalancer.Helper#getSynchronizationContext()}.
   */
  @Override
  public ClientLoadSnapshot snapshot() {
    return new ClientLoadSnapshot(callsFinished.getAndSet(0),
        callsInProgress.get(),
        callsFailed.getAndSet(0));
  }

  /**
   * A {@link ClientLoadSnapshot} represents a snapshot of {@link ClientLoadCounter} to be sent as
   * part of {@link io.envoyproxy.envoy.api.v2.endpoint.ClusterStats} to the balancer.
   */
  static final class ClientLoadSnapshot {

    @VisibleForTesting
    static final ClientLoadSnapshot EMPTY_SNAPSHOT = new ClientLoadSnapshot(0, 0, 0);
    private final long callsFinished;
    private final long callsInProgress;
    private final long callsFailed;

    /**
     * External usage must only be for testing.
     */
    @VisibleForTesting
    ClientLoadSnapshot(long callsFinished, long callsInProgress, long callsFailed) {
      this.callsFinished = callsFinished;
      this.callsInProgress = callsInProgress;
      this.callsFailed = callsFailed;
    }

    long getCallsFinished() {
      return callsFinished;
    }

    long getCallsInProgress() {
      return callsInProgress;
    }

    long getCallsFailed() {
      return callsFailed;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("callsFinished", callsFinished)
          .add("callsInProgress", callsInProgress)
          .add("callsFailed", callsFailed)
          .toString();
    }
  }

  /**
   * An {@link XdsClientLoadRecorder} instance records and aggregates client-side load data into an
   * {@link ClientLoadCounter} object.
   */
  @ThreadSafe
  static final class XdsClientLoadRecorder extends ClientStreamTracer.Factory {

    private final ClientStreamTracer.Factory delegate;
    private final StatsCounter counter;

    XdsClientLoadRecorder(StatsCounter counter, ClientStreamTracer.Factory delegate) {
      this.counter = checkNotNull(counter, "counter");
      this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
      counter.incrementCallsInProgress();
      final ClientStreamTracer delegateTracer = delegate.newClientStreamTracer(info, headers);
      return new ForwardingClientStreamTracer() {
        @Override
        protected ClientStreamTracer delegate() {
          return delegateTracer;
        }

        @Override
        public void streamClosed(Status status) {
          counter.incrementCallsFinished();
          counter.decrementCallsInProgress();
          if (!status.isOk()) {
            counter.incrementCallsFailed();
          }
          delegate().streamClosed(status);
        }
      };
    }
  }
}
