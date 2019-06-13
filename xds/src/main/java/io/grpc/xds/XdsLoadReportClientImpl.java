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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.Durations;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats;
import io.envoyproxy.envoy.service.load_stats.v2.LoadReportingServiceGrpc;
import io.envoyproxy.envoy.service.load_stats.v2.LoadStatsRequest;
import io.envoyproxy.envoy.service.load_stats.v2.LoadStatsResponse;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.LoadBalancer.Helper;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.BackoffPolicy.Provider;
import io.grpc.internal.GrpcUtil;
import io.grpc.stub.StreamObserver;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Client of XDS load reporting service. Methods in this class are expected to be called in
 * the same synchronized context that {@link XdsLoadBalancer.Helper#getSynchronizationContext}
 * returns.
 */
@NotThreadSafe
final class XdsLoadReportClientImpl implements XdsLoadReportClient {

  @VisibleForTesting
  static final String TRAFFICDIRECTOR_GRPC_HOSTNAME_FIELD
      = "com.googleapis.trafficdirector.grpc_hostname";

  // The name of load-balanced service.
  private final String serviceName;
  private final ManagedChannel channel;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timerService;
  private final Supplier<Stopwatch> stopwatchSupplier;
  private final Stopwatch retryStopwatch;
  private final ChannelLogger logger;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final StatsStore statsStore;
  private boolean started;

  @Nullable
  private BackoffPolicy lrsRpcRetryPolicy;
  @Nullable
  private ScheduledHandle lrsRpcRetryTimer;

  @Nullable
  private LrsStream lrsStream;

  XdsLoadReportClientImpl(ManagedChannel channel,
      Helper helper,
      BackoffPolicy.Provider backoffPolicyProvider,
      StatsStore statsStore) {
    this(channel, helper, GrpcUtil.STOPWATCH_SUPPLIER, backoffPolicyProvider, statsStore);
  }

  @VisibleForTesting
  XdsLoadReportClientImpl(ManagedChannel channel,
      Helper helper,
      Supplier<Stopwatch> stopwatchSupplier,
      BackoffPolicy.Provider backoffPolicyProvider,
      StatsStore statsStore) {
    this.channel = checkNotNull(channel, "channel");
    this.serviceName = checkNotNull(helper.getAuthority(), "serviceName");
    this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
    this.retryStopwatch = stopwatchSupplier.get();
    this.logger = checkNotNull(helper.getChannelLogger(), "logger");
    this.timerService = checkNotNull(helper.getScheduledExecutorService(), "timeService");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    this.statsStore = checkNotNull(statsStore, "statsStore");
    started = false;
  }

  @Override
  public void startLoadReporting() {
    if (started) {
      return;
    }
    started = true;
    startLrsRpc();
  }

  @Override
  public void stopLoadReporting() {
    if (!started) {
      return;
    }
    if (lrsRpcRetryTimer != null) {
      lrsRpcRetryTimer.cancel();
    }
    if (lrsStream != null) {
      lrsStream.close(null);
    }
    started = false;
    // Do not shutdown channel as it is not owned by LrsClient.
  }

  @VisibleForTesting
  static class LoadReportingTask implements Runnable {
    private final LrsStream stream;

    LoadReportingTask(LrsStream stream) {
      this.stream = stream;
    }

    @Override
    public void run() {
      stream.sendLoadReport();
    }
  }

  @VisibleForTesting
  class LrsRpcRetryTask implements Runnable {

    @Override
    public void run() {
      startLrsRpc();
    }
  }

  private void startLrsRpc() {
    checkState(lrsStream == null, "previous lbStream has not been cleared yet");
    LoadReportingServiceGrpc.LoadReportingServiceStub stub
        = LoadReportingServiceGrpc.newStub(channel);
    lrsStream = new LrsStream(stub, stopwatchSupplier.get());
    retryStopwatch.reset().start();
    lrsStream.start();
  }

  private class LrsStream implements StreamObserver<LoadStatsResponse> {

    final LoadReportingServiceGrpc.LoadReportingServiceStub stub;
    final Stopwatch reportStopwatch;
    StreamObserver<LoadStatsRequest> lrsRequestWriter;
    boolean initialResponseReceived;
    boolean closed;
    long loadReportIntervalNano = -1;
    ScheduledHandle loadReportTimer;

    // The name for the google service the client talks to. Received on LRS responses.
    @Nullable
    String clusterName;

    LrsStream(LoadReportingServiceGrpc.LoadReportingServiceStub stub, Stopwatch stopwatch) {
      this.stub = checkNotNull(stub, "stub");
      reportStopwatch = checkNotNull(stopwatch, "stopwatch");
    }

    void start() {
      lrsRequestWriter = stub.withWaitForReady().streamLoadStats(this);
      reportStopwatch.reset().start();
      LoadStatsRequest initRequest =
          LoadStatsRequest.newBuilder()
              .setNode(Node.newBuilder()
                  .setMetadata(Struct.newBuilder()
                      .putFields(
                          TRAFFICDIRECTOR_GRPC_HOSTNAME_FIELD,
                          Value.newBuilder().setStringValue(serviceName).build())))
              .build();
      lrsRequestWriter.onNext(initRequest);
      logger.log(ChannelLogLevel.DEBUG, "Initial LRS request sent: {0}", initRequest);
    }

    @Override
    public void onNext(final LoadStatsResponse response) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          handleResponse(response);
        }
      });
    }

    @Override
    public void onError(final Throwable t) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          handleStreamClosed(Status.fromThrowable(t)
              .augmentDescription("Stream to XDS management server had an error"));
        }
      });
    }

    @Override
    public void onCompleted() {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          handleStreamClosed(
              Status.UNAVAILABLE.withDescription("Stream to XDS management server was closed"));
        }
      });
    }

    private void sendLoadReport() {
      long interval = reportStopwatch.elapsed(TimeUnit.NANOSECONDS);
      reportStopwatch.reset().start();
      ClusterStats report =
          statsStore.generateLoadReport()
              .toBuilder()
              .setClusterName(clusterName)
              .setLoadReportInterval(Durations.fromNanos(interval))
              .build();
      lrsRequestWriter.onNext(LoadStatsRequest.newBuilder()
          .setNode(Node.newBuilder()
              .setMetadata(Struct.newBuilder()
                  .putFields(
                      TRAFFICDIRECTOR_GRPC_HOSTNAME_FIELD,
                      Value.newBuilder().setStringValue(serviceName).build())))
          .addClusterStats(report)
          .build());
      scheduleNextLoadReport();
    }

    private void scheduleNextLoadReport() {
      // Cancel pending load report and reschedule with updated load reporting interval.
      if (loadReportTimer != null && loadReportTimer.isPending()) {
        loadReportTimer.cancel();
        loadReportTimer = null;
      }
      if (loadReportIntervalNano > 0) {
        loadReportTimer = syncContext.schedule(
            new LoadReportingTask(this), loadReportIntervalNano, TimeUnit.NANOSECONDS,
            timerService);
      }
    }

    private void handleResponse(LoadStatsResponse response) {
      if (closed) {
        return;
      }

      if (!initialResponseReceived) {
        logger.log(ChannelLogLevel.DEBUG, "Received LRS initial response: {0}", response);
        initialResponseReceived = true;
      } else {
        logger.log(ChannelLogLevel.DEBUG, "Received an LRS response: {0}", response);
      }
      loadReportIntervalNano = Durations.toNanos(response.getLoadReportingInterval());
      List<String> serviceList = Collections.unmodifiableList(response.getClustersList());
      // For gRPC use case, LRS response will only contain one cluster, which is the same as in
      // the EDS response.
      if (serviceList.size() != 1) {
        logger.log(ChannelLogLevel.ERROR, "Received clusters: {0}, expect exactly one",
            serviceList);
        return;
      }
      clusterName = serviceList.get(0);
      scheduleNextLoadReport();
    }

    private void handleStreamClosed(Status status) {
      checkArgument(!status.isOk(), "unexpected OK status");
      if (closed) {
        return;
      }
      closed = true;
      cleanUp();

      long delayNanos = 0;
      if (initialResponseReceived || lrsRpcRetryPolicy == null) {
        // Reset the backoff sequence if balancer has sent the initial response, or backoff sequence
        // has never been initialized.
        lrsRpcRetryPolicy = backoffPolicyProvider.get();
      }
      // Backoff only when balancer wasn't working previously.
      if (!initialResponseReceived) {
        // The back-off policy determines the interval between consecutive RPC upstarts, thus the
        // actual delay may be smaller than the value from the back-off policy, or even negative,
        // depending how much time was spent in the previous RPC.
        delayNanos =
            lrsRpcRetryPolicy.nextBackoffNanos() - retryStopwatch.elapsed(TimeUnit.NANOSECONDS);
      }
      logger.log(ChannelLogLevel.DEBUG, "LRS stream closed, backoff in {0} second(s)",
          TimeUnit.NANOSECONDS.toSeconds(delayNanos <= 0 ? 0 : delayNanos));
      if (delayNanos <= 0) {
        startLrsRpc();
      } else {
        lrsRpcRetryTimer =
            syncContext.schedule(new LrsRpcRetryTask(), delayNanos, TimeUnit.NANOSECONDS,
                timerService);
      }
    }

    private void close(@Nullable Exception error) {
      if (closed) {
        return;
      }
      closed = true;
      cleanUp();
      if (error == null) {
        lrsRequestWriter.onCompleted();
      } else {
        lrsRequestWriter.onError(error);
      }
    }

    private void cleanUp() {
      if (loadReportTimer != null) {
        loadReportTimer.cancel();
        loadReportTimer = null;
      }
      if (lrsStream == this) {
        lrsStream = null;
      }
    }
  }

  abstract static class XdsLoadReportClientFactory {

    private static final XdsLoadReportClientFactory DEFAULT_INSTANCE =
        new XdsLoadReportClientFactory() {
          @Override
          XdsLoadReportClient createLoadReportClient(
              ManagedChannel channel,
              Helper helper,
              Provider backoffPolicyProvider,
              StatsStore statsStore) {
            return new XdsLoadReportClientImpl(channel, helper, backoffPolicyProvider, statsStore);
          }
        };

    static XdsLoadReportClientFactory getInstance() {
      return DEFAULT_INSTANCE;
    }

    abstract XdsLoadReportClient createLoadReportClient(ManagedChannel channel, Helper helper,
        BackoffPolicy.Provider backoffPolicyProvider, StatsStore statsStore);
  }
}
