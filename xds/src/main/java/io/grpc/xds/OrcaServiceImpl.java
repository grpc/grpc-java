/*
 * Copyright 2022 The gRPC Authors
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

import com.github.xds.data.orca.v3.OrcaLoadReport;
import com.github.xds.service.orca.v3.OpenRcaServiceGrpc;
import com.github.xds.service.orca.v3.OrcaLoadReportRequest;
import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.util.Durations;
import io.grpc.SynchronizationContext;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

final class OrcaServiceImpl extends OpenRcaServiceGrpc.OpenRcaServiceImplBase {
  private static final Logger logger = Logger.getLogger(OrcaServiceImpl.class.getName());

  private final long minReportIntervalNanos;
  private final ScheduledExecutorService timeService;
  private volatile ConcurrentHashMap<String, Double> metricsData = new ConcurrentHashMap<>();
  private volatile double cpuUtilization;
  private volatile double memoryUtilization;
  @VisibleForTesting
  final AtomicInteger clientCount = new AtomicInteger(0);

  public OrcaServiceImpl(long minReportIntervalNanos, ScheduledExecutorService timeService) {
    this.minReportIntervalNanos = minReportIntervalNanos;
    this.timeService = checkNotNull(timeService);
  }

  @Override
  public void streamCoreMetrics(
      OrcaLoadReportRequest request, StreamObserver<OrcaLoadReport> responseObserver) {
    OrcaClient client = new OrcaClient(request, responseObserver);
    client.run();
    clientCount.getAndIncrement();
  }

  private final class OrcaClient implements Runnable {
    final OrcaLoadReportRequest request;
    final ServerCallStreamObserver<OrcaLoadReport> responseObserver;
    SynchronizationContext.ScheduledHandle periodicReportTimer;
    final long reportIntervalNanos;
    final SynchronizationContext syncContext = new SynchronizationContext(
        new Thread.UncaughtExceptionHandler() {
          @Override
          public void uncaughtException(Thread t, Throwable e) {
            logger.log(Level.SEVERE, "Exception!" + e);
          }
        });

    OrcaClient(OrcaLoadReportRequest request, StreamObserver<OrcaLoadReport> responseObserver) {
      this.request = checkNotNull(request);
      this.reportIntervalNanos = Math.max(Durations.toNanos(request.getReportInterval()),
          minReportIntervalNanos);
      this.responseObserver = (ServerCallStreamObserver<OrcaLoadReport>) responseObserver;
      this.responseObserver.setOnCancelHandler(new Runnable() {
        @Override
        public void run() {
          syncContext.execute(new Runnable() {
            @Override
            public void run() {
              if (periodicReportTimer != null) {
                periodicReportTimer.cancel();
              }
              clientCount.getAndDecrement();
            }
          });
        }
      });
    }

    @Override
    public void run() {
      if (periodicReportTimer != null && periodicReportTimer.isPending()) {
        return;
      }
      OrcaLoadReport report = generateMetricsReport();
      responseObserver.onNext(report);
      periodicReportTimer = syncContext.schedule(OrcaClient.this, reportIntervalNanos,
          TimeUnit.NANOSECONDS, timeService);
    }
  }

  private OrcaLoadReport generateMetricsReport() {
    return OrcaLoadReport.newBuilder().setCpuUtilization(cpuUtilization)
        .setMemUtilization(memoryUtilization)
        .putAllUtilization(metricsData)
        .build();
  }

  void setUtilizationMetric(String key, double value) {
    metricsData.put(key, value);
  }

  void setAllUtilizationMetrics(Map<String, Double> metrics) {
    metricsData = new ConcurrentHashMap<>(metrics);
  }

  void deleteUtilizationMetric(String key) {
    metricsData.remove(key);
  }

  void setCpuUtilizationMetric(double value) {
    cpuUtilization = value;
  }

  void deleteCpuUtilizationMetric() {
    cpuUtilization = 0;
  }

  void setMemoryUtilizationMetric(double value) {
    memoryUtilization = value;
  }

  void deleteMemoryUtilizationMetric() {
    memoryUtilization = 0;
  }
}
