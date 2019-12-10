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
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats;
import io.envoyproxy.envoy.service.load_stats.v2.LoadReportingServiceGrpc;
import io.envoyproxy.envoy.service.load_stats.v2.LoadStatsRequest;
import io.envoyproxy.envoy.service.load_stats.v2.LoadStatsResponse;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.stub.StreamObserver;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Client of xDS load reporting service based on LRS protocol.
 */
@NotThreadSafe
final class LoadReportClientImpl implements LoadReportClient {

  // TODO(chengyuanzhang): use channel logger once XdsClientImpl migrates to use channel logger.
  private static final Logger logger = Logger.getLogger(XdsClientImpl.class.getName());

  private final String clusterName;
  private final ManagedChannel channel;
  private final Node node;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timerService;
  private final Supplier<Stopwatch> stopwatchSupplier;
  private final Stopwatch retryStopwatch;
  private final BackoffPolicy.Provider backoffPolicyProvider;

  // Sources of load stats data for each service in cluster.
  private final Map<String, LoadStatsStore> loadStatsStoreMap = new HashMap<>();
  private boolean started;

  @Nullable
  private BackoffPolicy lrsRpcRetryPolicy;
  @Nullable
  private ScheduledHandle lrsRpcRetryTimer;
  @Nullable
  private LrsStream lrsStream;
  @Nullable
  private LoadReportCallback callback;

  LoadReportClientImpl(ManagedChannel channel,
      String clusterName,
      Node node,
      SynchronizationContext syncContext,
      ScheduledExecutorService scheduledExecutorService,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier) {
    this.channel = checkNotNull(channel, "channel");
    this.clusterName = checkNotNull(clusterName, "clusterName");
    this.node = checkNotNull(node, "node");
    this.syncContext = checkNotNull(syncContext, "syncContext");
    this.timerService = checkNotNull(scheduledExecutorService, "timeService");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
    this.retryStopwatch = stopwatchSupplier.get();
    started = false;
  }

  @Override
  public void startLoadReporting(LoadReportCallback callback) {
    if (started) {
      return;
    }
    this.callback = callback;
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
      lrsStream.close(Status.CANCELLED.withDescription("stop load reporting").asException());
    }
    started = false;
    // Do not shutdown channel as it is not owned by LrsClient.
  }

  @Override
  public void addLoadStatsStore(String clusterServiceName, LoadStatsStore loadStatsStore) {
    loadStatsStoreMap.put(clusterServiceName, loadStatsStore);
  }

  @Override
  public void removeLoadStatsStore(String clusterServiceName) {
    loadStatsStoreMap.remove(clusterServiceName);
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

    // Name of cluster service to report loads for, instructed by LRS responses.
    // Currently we expect a gRPC client only talks to a single service per cluster. But we
    // could support switching cluster services, for which loads for a cluster may
    // spread to multiple services.
    @Nullable
    String clusterServiceName;

    LrsStream(LoadReportingServiceGrpc.LoadReportingServiceStub stub, Stopwatch stopwatch) {
      this.stub = checkNotNull(stub, "stub");
      reportStopwatch = checkNotNull(stopwatch, "stopwatch");
    }

    void start() {
      lrsRequestWriter = stub.withWaitForReady().streamLoadStats(this);
      reportStopwatch.reset().start();
      LoadStatsRequest initRequest =
          LoadStatsRequest.newBuilder()
              .setNode(node)
              .addClusterStats(ClusterStats.newBuilder().setClusterName(clusterName))
              .build();
      lrsRequestWriter.onNext(initRequest);
      logger.log(Level.FINE, "Initial LRS request sent: {0}", initRequest);
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
      LoadStatsRequest.Builder requestBuilder = LoadStatsRequest.newBuilder().setNode(node);
      if (loadStatsStoreMap.containsKey(clusterServiceName)) {
        LoadStatsStore loadStatsStore = loadStatsStoreMap.get(clusterServiceName);
        ClusterStats report =
            loadStatsStore.generateLoadReport()
                .toBuilder()
                .setClusterName(clusterServiceName)
                .setLoadReportInterval(Durations.fromNanos(interval))
                .build();
        requestBuilder.addClusterStats(report);
      }
      lrsRequestWriter.onNext(requestBuilder.build());
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
        logger.log(Level.FINE, "Received LRS initial response: {0}", response);
        initialResponseReceived = true;
      } else {
        logger.log(Level.FINE, "Received an LRS response: {0}", response);
      }
      loadReportIntervalNano = Durations.toNanos(response.getLoadReportingInterval());
      callback.onReportResponse(loadReportIntervalNano);
      List<String> serviceList = Collections.unmodifiableList(response.getClustersList());
      // For current gRPC use case, we expect traffic director only request client to report
      // loads for a single service per cluster (which is the cluster service gRPC client talks
      // to). We could support reporting loads for multiple services per cluster that gRPC
      // client sends loads to due to service switching.
      if (serviceList.size() != 1) {
        logger.log(Level.FINE, "Received clusters: {0}, expect exactly one",
            serviceList);
        return;
      }
      clusterServiceName = serviceList.get(0);
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
      logger.log(Level.FINE, "LRS stream closed, backoff in {0} second(s)",
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

  /**
   * Factory class for creating {@link LoadReportClient} instances.
   */
  // TODO(chengyuanzhang): eliminate this factory after migrating EDS load balancer to
  //  use XdsClient.
  abstract static class LoadReportClientFactory {

    private static final LoadReportClientFactory DEFAULT_INSTANCE =
        new LoadReportClientFactory() {
          @Override
          LoadReportClient createLoadReportClient(
              ManagedChannel channel,
              String clusterName,
              Node node,
              SynchronizationContext syncContext,
              ScheduledExecutorService timeService,
              BackoffPolicy.Provider backoffPolicyProvider,
              Supplier<Stopwatch> stopwatchSupplier) {
            return new LoadReportClientImpl(channel, clusterName, node, syncContext, timeService,
                backoffPolicyProvider, stopwatchSupplier);
          }
        };

    static LoadReportClientFactory getInstance() {
      return DEFAULT_INSTANCE;
    }

    abstract LoadReportClient createLoadReportClient(ManagedChannel channel, String clusterName,
        Node node, SynchronizationContext syncContext, ScheduledExecutorService timeService,
        BackoffPolicy.Provider backoffPolicyProvider, Supplier<Stopwatch> stopwatchSupplier);
  }
}
