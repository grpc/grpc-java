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
import com.google.protobuf.util.Durations;
import io.envoyproxy.envoy.service.load_stats.v3.LoadReportingServiceGrpc;
import io.envoyproxy.envoy.service.load_stats.v3.LoadReportingServiceGrpc.LoadReportingServiceStub;
import io.envoyproxy.envoy.service.load_stats.v3.LoadStatsRequest;
import io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse;
import io.grpc.InternalLogId;
import io.grpc.Status;
import io.grpc.internal.BackoffPolicy;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.EnvoyProtoData.ClusterStats;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.XdsClient.XdsChannel;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Client of xDS load reporting service based on LRS protocol, which reports load stats of
 * gRPC client's perspective to a management server.
 */
final class LoadReportClient {
  private final InternalLogId logId;
  private final XdsLogger logger;
  private final XdsChannel xdsChannel;
  private final Node node;
  private final ScheduledExecutorService timerService;
  private final Stopwatch retryStopwatch;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final LoadStatsManager loadStatsManager;
  private final Object lock = new Object();

  private boolean started;
  @Nullable
  private BackoffPolicy lrsRpcRetryPolicy;
  @Nullable
  private ScheduledFuture<?> lrsRpcRetryTimer;
  @Nullable
  private LrsStream lrsStream;

  LoadReportClient(
      LoadStatsManager loadStatsManager,
      XdsChannel xdsChannel,
      Node node,
      ScheduledExecutorService scheduledExecutorService,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier) {
    this.loadStatsManager = checkNotNull(loadStatsManager, "loadStatsManager");
    this.xdsChannel = checkNotNull(xdsChannel, "xdsChannel");
    this.timerService = checkNotNull(scheduledExecutorService, "timeService");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    this.retryStopwatch = checkNotNull(stopwatchSupplier, "stopwatchSupplier").get();
    this.node = checkNotNull(node, "node").toBuilder()
        .addClientFeatures("envoy.lrs.supports_send_all_clusters").build();
    logId = InternalLogId.allocate("lrs-client", null);
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogLevel.INFO, "Created");
  }

  /**
   * Establishes load reporting communication and negotiates with traffic director to report load
   * stats periodically. Calling this method on an already started {@link LoadReportClient} is
   * no-op.
   */
  void startLoadReporting() {
    synchronized (lock) {
      if (started) {
        return;
      }
      started = true;
      logger.log(XdsLogLevel.INFO, "Starting load reporting RPC");
      startLrsRpc();
    }
  }

  /**
   * Terminates load reporting. Calling this method on an already stopped
   * {@link LoadReportClient} is no-op.
   */
  void stopLoadReporting() {
    synchronized (lock) {
      if (!started) {
        return;
      }
      started = false;
      logger.log(XdsLogLevel.INFO, "Stopping load reporting RPC");
      if (lrsRpcRetryTimer != null) {
        lrsRpcRetryTimer.cancel(false);
      }
      if (lrsStream != null) {
        lrsStream.close(Status.CANCELLED.withDescription("stop load reporting").asException());
      }
      // Do not shutdown channel as it is not owned by LrsClient.
    }
  }

  @VisibleForTesting
  class LoadReportingTask implements Runnable {
    private final LrsStream stream;

    LoadReportingTask(LrsStream stream) {
      this.stream = stream;
    }

    @Override
    public void run() {
      synchronized (lock) {
        stream.sendLoadReport();
      }
    }
  }

  @VisibleForTesting
  class LrsRpcRetryTask implements Runnable {

    @Override
    public void run() {
      synchronized (lock) {
        startLrsRpc();
      }
    }
  }

  private void startLrsRpc() {
    if (!started) {
      return;
    }
    checkState(lrsStream == null, "previous lbStream has not been cleared yet");
    if (xdsChannel.isUseProtocolV3()) {
      lrsStream = new LrsStreamV3();
    } else {
      lrsStream = new LrsStreamV2();
    }
    retryStopwatch.reset().start();
    lrsStream.start();
  }

  private abstract class LrsStream {
    boolean initialResponseReceived;
    boolean closed;
    long intervalNano = -1;
    boolean reportAllClusters;
    List<String> clusterNames;  // clusters to report loads for, if not report all.
    ScheduledFuture<?> loadReportTimer;

    abstract void start();

    abstract void sendLoadStatsRequest(List<ClusterStats> clusterStatsList);

    abstract void sendError(Exception error);

    final void handleRpcResponse(List<String> clusters, boolean sendAllClusters,
        long loadReportIntervalNano) {
      if (closed) {
        return;
      }
      if (!initialResponseReceived) {
        logger.log(XdsLogLevel.DEBUG, "Initial LRS response received");
        initialResponseReceived = true;
      }
      reportAllClusters = sendAllClusters;
      if (reportAllClusters) {
        logger.log(XdsLogLevel.INFO, "Report loads for all clusters");
      } else {
        logger.log(XdsLogLevel.INFO, "Report loads for clusters: ", clusters);
        clusterNames = clusters;
      }
      intervalNano = loadReportIntervalNano;
      logger.log(XdsLogLevel.INFO, "Update load reporting interval to {0} ns", intervalNano);
      scheduleNextLoadReport();
    }

    final void handleRpcError(Throwable t) {
      handleStreamClosed(Status.fromThrowable(t));
    }

    final void handleRpcCompleted() {
      handleStreamClosed(Status.UNAVAILABLE.withDescription("Closed by server"));
    }

    private void sendLoadReport() {
      if (closed) {
        return;
      }
      List<ClusterStats> clusterStatsList;
      if (reportAllClusters) {
        clusterStatsList = loadStatsManager.getAllLoadReports();
      } else {
        clusterStatsList = new ArrayList<>();
        for (String name : clusterNames) {
          clusterStatsList.addAll(loadStatsManager.getClusterLoadReports(name));
        }
      }
      sendLoadStatsRequest(clusterStatsList);
      scheduleNextLoadReport();
    }

    private void scheduleNextLoadReport() {
      // Cancel pending load report and reschedule with updated load reporting interval.
      if (loadReportTimer != null && !loadReportTimer.isDone()) {
        loadReportTimer.cancel(false);
        loadReportTimer = null;
      }
      if (intervalNano > 0) {
        loadReportTimer = timerService.schedule(
            new LoadReportingTask(this), intervalNano, TimeUnit.NANOSECONDS);
      }
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
            timerService.schedule(new LrsRpcRetryTask(), delayNanos, TimeUnit.NANOSECONDS);
      }
    }

    private void close(Exception error) {
      if (closed) {
        return;
      }
      closed = true;
      cleanUp();
      sendError(error);
    }

    private void cleanUp() {
      if (loadReportTimer != null) {
        loadReportTimer.cancel(false);
        loadReportTimer = null;
      }
      if (lrsStream == this) {
        lrsStream = null;
      }
    }
  }

  private final class LrsStreamV2 extends LrsStream {
    StreamObserver<io.envoyproxy.envoy.service.load_stats.v2.LoadStatsRequest> lrsRequestWriterV2;

    @Override
    void start() {
      StreamObserver<io.envoyproxy.envoy.service.load_stats.v2.LoadStatsResponse>
              lrsResponseReaderV2 =
          new StreamObserver<io.envoyproxy.envoy.service.load_stats.v2.LoadStatsResponse>() {
            @Override
            public void onNext(
                io.envoyproxy.envoy.service.load_stats.v2.LoadStatsResponse response) {
              synchronized (lock) {
                logger.log(XdsLogLevel.DEBUG, "Received LoadStatsResponse:\n{0}", response);
                handleRpcResponse(response.getClustersList(), response.getSendAllClusters(),
                    Durations.toNanos(response.getLoadReportingInterval()));
              }
            }

            @Override
            public void onError(Throwable t) {
              synchronized (lock) {
                handleRpcError(t);
              }
            }

            @Override
            public void onCompleted() {
              synchronized (lock) {
                handleRpcCompleted();
              }
            }
          };
      io.envoyproxy.envoy.service.load_stats.v2.LoadReportingServiceGrpc.LoadReportingServiceStub
          stubV2 = io.envoyproxy.envoy.service.load_stats.v2.LoadReportingServiceGrpc.newStub(
              xdsChannel.getManagedChannel());
      lrsRequestWriterV2 = stubV2.withWaitForReady().streamLoadStats(lrsResponseReaderV2);
      logger.log(XdsLogLevel.DEBUG, "Sending initial LRS request");
      sendLoadStatsRequest(Collections.<ClusterStats>emptyList());
    }

    @Override
    void sendLoadStatsRequest(List<ClusterStats> clusterStatsList) {
      io.envoyproxy.envoy.service.load_stats.v2.LoadStatsRequest.Builder requestBuilder =
          io.envoyproxy.envoy.service.load_stats.v2.LoadStatsRequest.newBuilder()
              .setNode(node.toEnvoyProtoNodeV2());
      for (ClusterStats stats : clusterStatsList) {
        requestBuilder.addClusterStats(stats.toEnvoyProtoClusterStatsV2());
      }
      io.envoyproxy.envoy.service.load_stats.v2.LoadStatsRequest request = requestBuilder.build();
      lrsRequestWriterV2.onNext(requestBuilder.build());
      logger.log(XdsLogLevel.DEBUG, "Sent LoadStatsRequest\n{0}", request);
    }

    @Override
    void sendError(Exception error) {
      lrsRequestWriterV2.onError(error);
    }
  }

  private final class LrsStreamV3 extends LrsStream {
    StreamObserver<LoadStatsRequest> lrsRequestWriterV3;

    @Override
    void start() {
      StreamObserver<LoadStatsResponse> lrsResponseReaderV3 =
          new StreamObserver<LoadStatsResponse>() {
            @Override
            public void onNext(LoadStatsResponse response) {
              synchronized (lock) {
                logger.log(XdsLogLevel.DEBUG, "Received LRS response:\n{0}", response);
                handleRpcResponse(response.getClustersList(), response.getSendAllClusters(),
                    Durations.toNanos(response.getLoadReportingInterval()));
              }
            }

            @Override
            public void onError(Throwable t) {
              synchronized (lock) {
                handleRpcError(t);
              }
            }

            @Override
            public void onCompleted() {
              synchronized (lock) {
                handleRpcCompleted();
              }
            }
          };
      LoadReportingServiceStub stubV3 =
          LoadReportingServiceGrpc.newStub(xdsChannel.getManagedChannel());
      lrsRequestWriterV3 = stubV3.withWaitForReady().streamLoadStats(lrsResponseReaderV3);
      logger.log(XdsLogLevel.DEBUG, "Sending initial LRS request");
      sendLoadStatsRequest(Collections.<ClusterStats>emptyList());
    }

    @Override
    void sendLoadStatsRequest(List<ClusterStats> clusterStatsList) {
      LoadStatsRequest.Builder requestBuilder =
          LoadStatsRequest.newBuilder().setNode(node.toEnvoyProtoNode());
      for (ClusterStats stats : clusterStatsList) {
        requestBuilder.addClusterStats(stats.toEnvoyProtoClusterStats());
      }
      LoadStatsRequest request = requestBuilder.build();
      lrsRequestWriterV3.onNext(request);
      logger.log(XdsLogLevel.DEBUG, "Sent LoadStatsRequest\n{0}", request);
    }

    @Override
    void sendError(Exception error) {
      lrsRequestWriterV3.onError(error);
    }
  }
}
