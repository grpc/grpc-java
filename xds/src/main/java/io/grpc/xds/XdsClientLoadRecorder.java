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

import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.util.ForwardingClientStreamTracer;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
public class XdsClientLoadRecorder extends ClientStreamTracer.Factory {

  private final ClientStreamTracer.Factory delegate;
  private final ClientLoadCounter counter;

  XdsClientLoadRecorder(ClientLoadCounter counter, ClientStreamTracer.Factory delegate) {
    this.counter = checkNotNull(counter, "counter");
    this.delegate = checkNotNull(delegate, "delegate");
  }

  @Override
  public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
    counter.callsStarted.getAndIncrement();
    final ClientStreamTracer delegateTracer = delegate.newClientStreamTracer(info, headers);
    return new StreamTracer(delegateTracer);
  }

  /**
   * A {@link ClientLoadSnapshot} represents a snapshot of {@link ClientLoadCounter} to be sent as
   * part of {@link io.envoyproxy.envoy.api.v2.endpoint.ClusterStats} to the balancer.
   */
  static class ClientLoadSnapshot {

    long callsSucceed;
    long callsInProgress;
    long callsFailed;
  }

  static class ClientLoadCounter {
    private AtomicLong callsStarted = new AtomicLong();
    private AtomicLong callsFinished = new AtomicLong();
    private AtomicLong callsFailed = new AtomicLong();

    /**
     * Generate a query count snapshot and reset counts for next snapshot.
     */
    ClientLoadSnapshot snapshot() {
      ClientLoadSnapshot snapshot = new ClientLoadSnapshot();
      long numStarted = callsStarted.get();
      long numFinished = callsFinished.getAndSet(0);
      // Number of calls started rolls over to next snapshot to account for calls in progress.
      callsStarted.set(numStarted - numFinished);
      snapshot.callsFailed = callsFailed.getAndSet(0);
      snapshot.callsSucceed = numFinished - snapshot.callsFailed;
      snapshot.callsInProgress = numStarted - numFinished;
      return snapshot;
    }
  }

  private class StreamTracer extends ForwardingClientStreamTracer {

    private final ClientStreamTracer delegate;

    private StreamTracer(ClientStreamTracer delegate) {
      this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    protected ClientStreamTracer delegate() {
      return delegate;
    }

    @Override
    public void streamClosed(Status status) {
      counter.callsFinished.getAndIncrement();
      if (!status.isOk()) {
        counter.callsFailed.getAndIncrement();
      }
    }
  }
}
