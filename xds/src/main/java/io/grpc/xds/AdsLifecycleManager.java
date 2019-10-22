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

import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.XdsClientImpl.EndpointWatchers;
import io.grpc.xds.XdsResponseReader.StreamActivityWatcher;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import javax.annotation.CheckForNull;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Manages ADS RPC lifecycle events and retry.
 */
@NotThreadSafe // Must be properly synchronized in SynchronizationContext.
final class AdsLifecycleManager {

  private final AggregatedDiscoveryServiceStub stub;
  private final EndpointWatchers endpointWatchers;
  private final SynchronizationContext syncCtx;
  private final ScheduledExecutorService timerService;
  private final Supplier<Stopwatch> stopwatchSupplier;
  private final BackoffPolicy.Provider backoffPolicyProvider;

  private boolean firstResponseReceived;
  private boolean isStreamActive;
  @CheckForNull
  private ScheduledHandle adsRpcRetryTimer;
  private Stopwatch retryStopwatch;
  @CheckForNull
  private StreamObserver<DiscoveryRequest> xdsRequestWriter;
  // never null
  private BackoffPolicy adsRpcRetryPolicy;

  AdsLifecycleManager(
      AggregatedDiscoveryServiceStub stub,
      EndpointWatchers endpointWatchers,
      SynchronizationContext syncCtx,
      ScheduledExecutorService timerService,
      Supplier<Stopwatch> stopwatchSupplier,
      BackoffPolicy.Provider backoffPolicyProvider) {
    this.stub = stub;
    this.endpointWatchers = endpointWatchers;
    this.syncCtx = syncCtx;
    this.timerService = timerService;
    this.stopwatchSupplier = stopwatchSupplier;
    this.backoffPolicyProvider = backoffPolicyProvider;
    this.adsRpcRetryPolicy = backoffPolicyProvider.get();
  }

  void start() {
    isStreamActive = true;
    firstResponseReceived = false;
    retryStopwatch = stopwatchSupplier.get().start();

    StreamActivityWatcher streamActivityWatcher = new StreamActivityWatcher() {
      @Override
      public void responseReceived() {
        firstResponseReceived = true;
      }

      @Override
      public void streamClosed() {
        if (!isStreamActive) {
          return;
        }
        isStreamActive = false;
        endpointWatchers.seizeRequestEndpoints();
        scheduleRetry();
      }
    };

    xdsRequestWriter = stub.streamAggregatedResources(
        new XdsResponseReader(streamActivityWatcher, endpointWatchers, syncCtx));

    // EDS request will be sent only if there is any EndpointWatcher added.
    endpointWatchers.requestEndpoints(xdsRequestWriter);
  }

  void shutdown() {
    isStreamActive = false;
    if (adsRpcRetryTimer != null) {
      adsRpcRetryTimer.cancel();
      adsRpcRetryTimer = null;
    }
    xdsRequestWriter.onError(
        Status.CANCELLED.withDescription("XdsClient is shutdown").asRuntimeException());
  }

  private void scheduleRetry() {
    checkState(
        adsRpcRetryTimer == null, "Scheduling retry while a retry is already pending");

    class AdsRpcRetryTask implements Runnable {
      @Override
      public void run() {
        adsRpcRetryTimer = null;
        start();
      }
    }

    long delayNanos;
    if (firstResponseReceived) {
      // Reset the backoff sequence if balancer has sent the initial response
      adsRpcRetryPolicy = backoffPolicyProvider.get();
      // Retry immediately
      delayNanos = 0;
    } else {
      delayNanos = Math.max(
          adsRpcRetryPolicy.nextBackoffNanos() - retryStopwatch.elapsed(TimeUnit.NANOSECONDS), 0);
    }

    XdsClientImpl.logger.log(Level.FINE, "XdsClient stream closed, retry in {0} ns", delayNanos);
    adsRpcRetryTimer = syncCtx.schedule(
        new AdsRpcRetryTask(),
        delayNanos,
        TimeUnit.NANOSECONDS,
        timerService);
  }
}
