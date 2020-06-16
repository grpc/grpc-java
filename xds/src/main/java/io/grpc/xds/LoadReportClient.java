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
import io.grpc.InternalLogId;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Client of xDS load reporting service based on LRS protocol, which reports load stats of
 * gRPC client's perspective to a management server.
 */
@NotThreadSafe
final class LoadReportClient {
  @VisibleForTesting
  static final String TARGET_NAME_METADATA_KEY = "PROXYLESS_CLIENT_HOSTNAME";

  private final XdsLogger logger;
  private final ManagedChannel channel;
  private final Node node;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timerService;
  private final Supplier<Stopwatch> stopwatchSupplier;
  private final Stopwatch retryStopwatch;
  private final BackoffPolicy.Provider backoffPolicyProvider;

  // Sources of load stats data for each cluster:cluster_service.
  private final Map<String, Map<String, LoadStatsStore>> loadStatsStoreMap = new HashMap<>();
  private boolean started;

  @Nullable
  private BackoffPolicy lrsRpcRetryPolicy;
  @Nullable
  private ScheduledHandle lrsRpcRetryTimer;
  @Nullable
  private LrsStream lrsStream;
  @Nullable
  private LoadReportCallback callback;

  LoadReportClient(
      InternalLogId logId,
      String targetName,
      ManagedChannel channel,
      Node node,
      SynchronizationContext syncContext,
      ScheduledExecutorService scheduledExecutorService,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier) {
    this.channel = checkNotNull(channel, "channel");
    this.syncContext = checkNotNull(syncContext, "syncContext");
    this.timerService = checkNotNull(scheduledExecutorService, "timeService");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
    this.retryStopwatch = stopwatchSupplier.get();
    checkNotNull(targetName, "targetName");
    checkNotNull(node, "node");
    Struct metadata =
        node.getMetadata()
            .toBuilder()
            .putFields(
                TARGET_NAME_METADATA_KEY,
                Value.newBuilder().setStringValue(targetName).build())
            .build();
    this.node = node.toBuilder().setMetadata(metadata).build();
    String logPrefix = checkNotNull(logId, "logId").toString().concat("-lrs-client");
    logger = XdsLogger.withPrefix(logPrefix);
  }

  /**
   * Establishes load reporting communication and negotiates with traffic director to report load
   * stats periodically. Calling this method on an already started {@link LoadReportClient} is
   * no-op.
   */
  void startLoadReporting(LoadReportCallback callback) {
    if (started) {
      return;
    }
    this.callback = callback;
    started = true;
    startLrsRpc();
  }

  /**
   * Terminates load reporting. Calling this method on an already stopped
   * {@link LoadReportClient} is no-op.
   */
  void stopLoadReporting() {
    if (!started) {
      return;
    }
    if (lrsRpcRetryTimer != null) {
      lrsRpcRetryTimer.cancel();
    }
    if (lrsStream != null) {
      lrsStream.close(Status.CANCELLED.withDescription("stop load reporting").asException());
    }
    started = false;
    // Do not shutdown channel as it is not owned by LrsClient.
  }

  /**
   * Provides this LoadReportClient source of load stats data for the given
   * cluster:cluster_service. If requested, data from the given loadStatsStore is
   * periodically queried and sent to traffic director by this LoadReportClient.
   */
  void addLoadStatsStore(
      String clusterName, @Nullable String clusterServiceName, LoadStatsStore loadStatsStore) {
    checkState(
        !loadStatsStoreMap.containsKey(clusterName)
            || !loadStatsStoreMap.get(clusterName).containsKey(clusterServiceName),
        "load stats for cluster: %s, cluster service: %s already exists",
        clusterName, clusterServiceName);
    logger.log(
        XdsLogLevel.INFO,
        "Add load stats for cluster: {0}, cluster_service: {1}", clusterName, clusterServiceName);
    if (!loadStatsStoreMap.containsKey(clusterName)) {
      loadStatsStoreMap.put(clusterName, new HashMap<String, LoadStatsStore>());
    }
    Map<String, LoadStatsStore> clusterLoadStatsStores = loadStatsStoreMap.get(clusterName);
    clusterLoadStatsStores.put(clusterServiceName, loadStatsStore);
  }

  /**
   * Stops providing load stats data for the given cluster:cluster_service.
   */
  void removeLoadStatsStore(String clusterName, @Nullable String clusterServiceName) {
    checkState(
        loadStatsStoreMap.containsKey(clusterName)
            && loadStatsStoreMap.get(clusterName).containsKey(clusterServiceName),
        "load stats for cluster: %s, cluster service: %s does not exist",
        clusterName, clusterServiceName);
    logger.log(
        XdsLogLevel.INFO,
        "Remove load stats for cluster: {0}, cluster_service: {1}",
        clusterName,
        clusterServiceName);
    Map<String, LoadStatsStore> clusterLoadStatsStores = loadStatsStoreMap.get(clusterName);
    clusterLoadStatsStores.remove(clusterServiceName);
    if (clusterLoadStatsStores.isEmpty()) {
      loadStatsStoreMap.remove(clusterName);
    }
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

    // Cluster to report loads for asked by management server.
    final Set<String> clusterNames = new HashSet<>();
    final LoadReportingServiceGrpc.LoadReportingServiceStub stub;
    final Stopwatch reportStopwatch;
    StreamObserver<LoadStatsRequest> lrsRequestWriter;
    boolean initialResponseReceived;
    boolean closed;
    long loadReportIntervalNano = -1;
    ScheduledHandle loadReportTimer;

    LrsStream(LoadReportingServiceGrpc.LoadReportingServiceStub stub, Stopwatch stopwatch) {
      this.stub = checkNotNull(stub, "stub");
      reportStopwatch = checkNotNull(stopwatch, "stopwatch");
    }

    void start() {
      lrsRequestWriter = stub.withWaitForReady().streamLoadStats(this);
      reportStopwatch.reset().start();

      // Send an initial LRS request with empty cluster stats. Management server is able to
      // infer clusters the gRPC client sending loads to.
      LoadStatsRequest initRequest =
          LoadStatsRequest.newBuilder()
              .setNode(node)
              .build();
      lrsRequestWriter.onNext(initRequest);
      logger.log(XdsLogLevel.DEBUG, "Initial LRS request sent:\n{0}", initRequest);
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
          handleStreamClosed(Status.fromThrowable(t));
        }
      });
    }

    @Override
    public void onCompleted() {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          handleStreamClosed(
              Status.UNAVAILABLE.withDescription("Closed by server"));
        }
      });
    }

    private void sendLoadReport() {
      long interval = reportStopwatch.elapsed(TimeUnit.NANOSECONDS);
      reportStopwatch.reset().start();
      LoadStatsRequest.Builder requestBuilder = LoadStatsRequest.newBuilder().setNode(node);
      for (String name : clusterNames) {
        if (loadStatsStoreMap.containsKey(name)) {
          Map<String, LoadStatsStore> clusterLoadStatsStores = loadStatsStoreMap.get(name);
          for (LoadStatsStore statsStore : clusterLoadStatsStores.values()) {
            ClusterStats report =
                statsStore.generateLoadReport()
                    .toBuilder()
                    .setLoadReportInterval(Durations.fromNanos(interval))
                    .build();
            requestBuilder.addClusterStats(report);
          }
        }
      }
      LoadStatsRequest request = requestBuilder.build();
      lrsRequestWriter.onNext(request);
      logger.log(XdsLogLevel.DEBUG, "Sent LoadStatsRequest\n{0}", request);
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
        logger.log(XdsLogLevel.DEBUG, "Received LRS initial response:\n{0}", response);
        initialResponseReceived = true;
      } else {
        logger.log(XdsLogLevel.DEBUG, "Received LRS response:\n{0}", response);
      }
      long interval =  Durations.toNanos(response.getLoadReportingInterval());
      if (interval != loadReportIntervalNano) {
        logger.log(XdsLogLevel.INFO, "Update load reporting interval to {0} ns", interval);
        loadReportIntervalNano = interval;
        callback.onReportResponse(loadReportIntervalNano);
      }
      if (clusterNames.size() != response.getClustersCount()
          || !clusterNames.containsAll(response.getClustersList())) {
        logger.log(
            XdsLogLevel.INFO,
            "Update load reporting clusters to {0}", response.getClustersList());
        clusterNames.clear();
        clusterNames.addAll(response.getClustersList());
      }
      scheduleNextLoadReport();
    }

    private void handleStreamClosed(Status status) {
      checkArgument(!status.isOk(), "unexpected OK status");
      if (closed) {
        return;
      }
      logger.log(
          XdsLogLevel.ERROR,
          "LRS stream closed with status {0}: {1}. Cause: {2}",
          status.getCode(), status.getDescription(), status.getCause());
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
      logger.log(XdsLogLevel.INFO, "Retry LRS stream in {0} ns", delayNanos);
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

  /**
   * Callbacks for passing information received from client load reporting responses to xDS load
   * balancer, such as the load reporting interval requested by the traffic director.
   *
   * <p>Implementations are not required to be thread-safe as callbacks will be invoked in xDS load
   * balancer's {@link io.grpc.SynchronizationContext}.
   */
  interface LoadReportCallback {

    /**
     * The load reporting interval has been received.
     *
     * @param reportIntervalNano load reporting interval requested by remote traffic director.
     */
    void onReportResponse(long reportIntervalNano);
  }
}
