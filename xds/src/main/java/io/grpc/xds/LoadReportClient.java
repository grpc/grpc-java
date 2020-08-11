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
import java.util.List;
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

  private final InternalLogId logId;
  private final XdsLogger logger;
  private final ManagedChannel channel;
  private final Node node;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timerService;
  private final Supplier<Stopwatch> stopwatchSupplier;
  private final Stopwatch retryStopwatch;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final LoadStatsManager loadStatsManager;

  private boolean started;

  @Nullable
  private BackoffPolicy lrsRpcRetryPolicy;
  @Nullable
  private ScheduledHandle lrsRpcRetryTimer;
  @Nullable
  private LrsStream lrsStream;

  LoadReportClient(
      String targetName,
      LoadStatsManager loadStatsManager,
      ManagedChannel channel,
      Node node,
      SynchronizationContext syncContext,
      ScheduledExecutorService scheduledExecutorService,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier) {
    this.loadStatsManager = checkNotNull(loadStatsManager, "loadStatsManager");
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
    logId = InternalLogId.allocate("lrs-client", targetName);
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogLevel.INFO, "Created");
  }

  /**
   * Establishes load reporting communication and negotiates with traffic director to report load
   * stats periodically. Calling this method on an already started {@link LoadReportClient} is
   * no-op.
   */
  void startLoadReporting() {
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
  void stopLoadReporting() {
    if (!started) {
      return;
    }
    logger.log(XdsLogLevel.INFO, "Stopping load reporting RPC");
    if (lrsRpcRetryTimer != null) {
      lrsRpcRetryTimer.cancel();
    }
    if (lrsStream != null) {
      lrsStream.close(Status.CANCELLED.withDescription("stop load reporting").asException());
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
    StreamObserver<LoadStatsRequest> lrsRequestWriter;
    boolean initialResponseReceived;
    boolean closed;
    long loadReportIntervalNano = -1;
    boolean reportAllClusters;
    List<String> clusterNames;  // clusters to report loads for, if not report all.
    ScheduledHandle loadReportTimer;

    LrsStream(LoadReportingServiceGrpc.LoadReportingServiceStub stub, Stopwatch stopwatch) {
      this.stub = checkNotNull(stub, "stub");
    }

    void start() {
      lrsRequestWriter = stub.withWaitForReady().streamLoadStats(this);
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
      LoadStatsRequest.Builder requestBuilder = LoadStatsRequest.newBuilder().setNode(node);
      if (reportAllClusters) {
        requestBuilder.addAllClusterStats(loadStatsManager.getAllLoadReports());
      } else {
        for (String name : clusterNames) {
          requestBuilder.addAllClusterStats(loadStatsManager.getClusterLoadReports(name));
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
      reportAllClusters = response.getSendAllClusters();
      if (reportAllClusters) {
        logger.log(XdsLogLevel.INFO, "Report loads for all clusters");
      } else {
        logger.log(XdsLogLevel.INFO, "Report loads for clusters: ", response.getClustersList());
        clusterNames = response.getClustersList();
      }
      long interval = Durations.toNanos(response.getLoadReportingInterval());
      logger.log(XdsLogLevel.INFO, "Update load reporting interval to {0} ns", interval);
      loadReportIntervalNano = interval;
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
}
