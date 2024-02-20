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

package io.grpc.xds.client;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.protobuf.util.Durations;
import io.envoyproxy.envoy.service.load_stats.v3.LoadReportingServiceGrpc;
import io.envoyproxy.envoy.service.load_stats.v3.LoadStatsRequest;
import io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse;
import io.grpc.Internal;
import io.grpc.InternalLogId;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.xds.client.EnvoyProtoData.Node;
import io.grpc.xds.client.Stats.ClusterStats;
import io.grpc.xds.client.Stats.DroppedRequests;
import io.grpc.xds.client.Stats.UpstreamLocalityStats;
import io.grpc.xds.client.XdsLogger.XdsLogLevel;
import io.grpc.xds.client.XdsTransportFactory.EventHandler;
import io.grpc.xds.client.XdsTransportFactory.StreamingCall;
import io.grpc.xds.client.XdsTransportFactory.XdsTransport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Client of xDS load reporting service based on LRS protocol, which reports load stats of
 * gRPC client's perspective to a management server.
 */
@Internal
public final class LoadReportClient {
  private final InternalLogId logId;
  private final XdsLogger logger;
  private final XdsTransport xdsTransport;
  private final Node node;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timerService;
  private final Stopwatch retryStopwatch;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  @VisibleForTesting
  public final LoadStatsManager2 loadStatsManager;
  private boolean started;
  @Nullable
  private BackoffPolicy lrsRpcRetryPolicy;
  @Nullable
  private ScheduledHandle lrsRpcRetryTimer;
  @Nullable
  private LrsStream lrsStream;
  private static final MethodDescriptor<LoadStatsRequest, LoadStatsResponse> method =
      LoadReportingServiceGrpc.getStreamLoadStatsMethod();

  @VisibleForTesting
  public LoadReportClient(
      LoadStatsManager2 loadStatsManager,
      XdsTransport xdsTransport,
      Node node,
      SynchronizationContext syncContext,
      ScheduledExecutorService scheduledExecutorService,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier) {
    this.loadStatsManager = checkNotNull(loadStatsManager, "loadStatsManager");
    this.xdsTransport = checkNotNull(xdsTransport, "xdsTransport");
    this.syncContext = checkNotNull(syncContext, "syncContext");
    this.timerService = checkNotNull(scheduledExecutorService, "timeService");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    this.retryStopwatch = checkNotNull(stopwatchSupplier, "stopwatchSupplier").get();
    this.node = checkNotNull(node, "node").toBuilder()
        .addClientFeatures("envoy.lrs.supports_send_all_clusters").build();
    logId = InternalLogId.allocate("lrs-client", null);
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @VisibleForTesting
  public boolean lrsStreamIsNull() {
    return lrsStream == null;
  }

  /**
   * Establishes load reporting communication and negotiates with traffic director to report load
   * stats periodically. Calling this method on an already started {@link LoadReportClient} is
   * no-op.
   */
  public void startLoadReporting() {
    syncContext.throwIfNotInThisSynchronizationContext();
    if (started) {
      return;
    }
    started = true;
    logger.log(XdsLogLevel.INFO, "Starting load reporting RPC");
    startLrsRpc();
  }

  /**
   * Terminates load reporting. Calling this method on an already stopped
   * {@link LoadReportClient} is no-op.
   */
  public void stopLoadReporting() {
    syncContext.throwIfNotInThisSynchronizationContext();
    if (!started) {
      return;
    }
    started = false;
    logger.log(XdsLogLevel.INFO, "Stopping load reporting RPC");
    if (lrsRpcRetryTimer != null && lrsRpcRetryTimer.isPending()) {
      lrsRpcRetryTimer.cancel();
    }
    if (lrsStream != null) {
      lrsStream.close(Status.CANCELLED.withDescription("stop load reporting").asException());
    }
    // Do not shut down channel as it is not owned by LrsClient.
  }

  private static class LoadReportingTask implements Runnable {
    private final LrsStream stream;

    LoadReportingTask(LrsStream stream) {
      this.stream = stream;
    }

    @Override
    public void run() {
      stream.sendLoadReport();
    }
  }

  private class LrsRpcRetryTask implements Runnable {

    @Override
    public void run() {
      startLrsRpc();
    }
  }

  private void startLrsRpc() {
    if (!started) {
      return;
    }
    checkState(lrsStream == null, "previous lbStream has not been cleared yet");
    retryStopwatch.reset().start();
    lrsStream = new LrsStream();
  }

  private final class LrsStream implements EventHandler<LoadStatsResponse> {
    boolean initialResponseReceived;
    boolean closed;
    long intervalNano = -1;
    boolean reportAllClusters;
    List<String> clusterNames;  // clusters to report loads for, if not report all.
    ScheduledHandle loadReportTimer;
    private final StreamingCall<LoadStatsRequest, LoadStatsResponse> call;

    LrsStream() {
      this.call = xdsTransport.createStreamingCall(method.getFullMethodName(),
          method.getRequestMarshaller(), method.getResponseMarshaller());
      call.start(this);
      logger.log(XdsLogLevel.DEBUG, "Sending initial LRS request");
      sendLoadStatsRequest(Collections.<ClusterStats>emptyList());
    }

    @Override
    public void onReady() {}

    @Override
    public void onRecvMessage(LoadStatsResponse response) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          logger.log(XdsLogLevel.DEBUG, "Received LRS response:\n{0}", response);
          handleRpcResponse(response.getClustersList(), response.getSendAllClusters(),
              Durations.toNanos(response.getLoadReportingInterval()));
          call.startRecvMessage();
        }
      });
    }

    @Override
    public void onStatusReceived(final Status status) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (status.isOk()) {
            handleStreamClosed(Status.UNAVAILABLE.withDescription("Closed by server"));
          } else {
            handleStreamClosed(status);
          }
        }
      });
    }

    void sendLoadStatsRequest(List<ClusterStats> clusterStatsList) {
      LoadStatsRequest.Builder requestBuilder =
          LoadStatsRequest.newBuilder().setNode(node.toEnvoyProtoNode());
      for (ClusterStats stats : clusterStatsList) {
        requestBuilder.addClusterStats(buildClusterStats(stats));
      }
      LoadStatsRequest request = requestBuilder.build();
      call.sendMessage(request);
      logger.log(XdsLogLevel.DEBUG, "Sent LoadStatsRequest\n{0}", request);
    }

    void handleRpcResponse(List<String> clusters, boolean sendAllClusters,
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

    private void sendLoadReport() {
      if (closed) {
        return;
      }
      List<ClusterStats> clusterStatsList;
      if (reportAllClusters) {
        clusterStatsList = loadStatsManager.getAllClusterStatsReports();
      } else {
        clusterStatsList = new ArrayList<>();
        for (String name : clusterNames) {
          clusterStatsList.addAll(loadStatsManager.getClusterStatsReports(name));
        }
      }
      sendLoadStatsRequest(clusterStatsList);
      scheduleNextLoadReport();
    }

    private void scheduleNextLoadReport() {
      // Cancel pending load report and reschedule with updated load reporting interval.
      if (loadReportTimer != null && loadReportTimer.isPending()) {
        loadReportTimer.cancel();
        loadReportTimer = null;
      }
      if (intervalNano > 0) {
        loadReportTimer = syncContext.schedule(
            new LoadReportingTask(this), intervalNano, TimeUnit.NANOSECONDS, timerService);
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

      if (initialResponseReceived || lrsRpcRetryPolicy == null) {
        // Reset the backoff sequence if balancer has sent the initial response, or backoff sequence
        // has never been initialized.
        lrsRpcRetryPolicy = backoffPolicyProvider.get();
      }
      // The back-off policy determines the interval between consecutive RPC upstarts, thus the
      // actual delay may be smaller than the value from the back-off policy, or even negative,
      // depending on how much time was spent in the previous RPC.
      long delayNanos =
          lrsRpcRetryPolicy.nextBackoffNanos() - retryStopwatch.elapsed(TimeUnit.NANOSECONDS);
      logger.log(XdsLogLevel.INFO, "Retry LRS stream in {0} ns", delayNanos);
      if (delayNanos <= 0) {
        startLrsRpc();
      } else {
        lrsRpcRetryTimer = syncContext.schedule(
            new LrsRpcRetryTask(), delayNanos, TimeUnit.NANOSECONDS, timerService);
      }
    }

    private void close(Exception error) {
      if (closed) {
        return;
      }
      closed = true;
      cleanUp();
      call.sendError(error);
    }

    private void cleanUp() {
      if (loadReportTimer != null && loadReportTimer.isPending()) {
        loadReportTimer.cancel();
        loadReportTimer = null;
      }
      if (lrsStream == this) {
        lrsStream = null;
      }
    }

    private io.envoyproxy.envoy.config.endpoint.v3.ClusterStats buildClusterStats(
        ClusterStats stats) {
      io.envoyproxy.envoy.config.endpoint.v3.ClusterStats.Builder builder =
          io.envoyproxy.envoy.config.endpoint.v3.ClusterStats.newBuilder()
              .setClusterName(stats.clusterName());
      if (stats.clusterServiceName() != null) {
        builder.setClusterServiceName(stats.clusterServiceName());
      }
      for (UpstreamLocalityStats upstreamLocalityStats : stats.upstreamLocalityStatsList()) {
        builder.addUpstreamLocalityStats(
            io.envoyproxy.envoy.config.endpoint.v3.UpstreamLocalityStats.newBuilder()
                .setLocality(
                    io.envoyproxy.envoy.config.core.v3.Locality.newBuilder()
                        .setRegion(upstreamLocalityStats.locality().region())
                        .setZone(upstreamLocalityStats.locality().zone())
                        .setSubZone(upstreamLocalityStats.locality().subZone()))
            .setTotalSuccessfulRequests(upstreamLocalityStats.totalSuccessfulRequests())
            .setTotalErrorRequests(upstreamLocalityStats.totalErrorRequests())
            .setTotalRequestsInProgress(upstreamLocalityStats.totalRequestsInProgress())
            .setTotalIssuedRequests(upstreamLocalityStats.totalIssuedRequests())
            .addAllLoadMetricStats(
                upstreamLocalityStats.loadMetricStatsMap().entrySet().stream().map(
                    e -> io.envoyproxy.envoy.config.endpoint.v3.EndpointLoadMetricStats.newBuilder()
                        .setMetricName(e.getKey())
                        .setNumRequestsFinishedWithMetric(
                            e.getValue().numRequestsFinishedWithMetric())
                        .setTotalMetricValue(e.getValue().totalMetricValue())
                        .build())
                .collect(Collectors.toList())));
      }
      for (DroppedRequests droppedRequests : stats.droppedRequestsList()) {
        builder.addDroppedRequests(
            io.envoyproxy.envoy.config.endpoint.v3.ClusterStats.DroppedRequests.newBuilder()
                .setCategory(droppedRequests.category())
                .setDroppedCount(droppedRequests.droppedCount()));
      }
      return builder
          .setTotalDroppedRequests(stats.totalDroppedRequests())
          .setLoadReportInterval(Durations.fromNanos(stats.loadReportIntervalNano()))
          .build();
    }
  }
}
