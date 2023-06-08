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

package io.grpc.xds.orca;

import static com.google.common.base.Preconditions.checkNotNull;

import com.github.xds.data.orca.v3.OrcaLoadReport;
import com.github.xds.service.orca.v3.OpenRcaServiceGrpc;
import com.github.xds.service.orca.v3.OrcaLoadReportRequest;
import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.util.Durations;
import io.grpc.BindableService;
import io.grpc.ServerServiceDefinition;
import io.grpc.SynchronizationContext;
import io.grpc.services.InternalMetricRecorder;
import io.grpc.services.MetricRecorder;
import io.grpc.services.MetricReport;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implements a {@link BindableService} that generates Out-Of-Band server metrics.
 * Register the returned service to the server, then a client can request for periodic load reports.
 */
public final class OrcaServiceImpl implements BindableService {
  private static final Logger logger = Logger.getLogger(OrcaServiceImpl.class.getName());

  /**
   * Empty or invalid (non-positive) minInterval config in will be treated to this default value.
   */
  public static final long DEFAULT_MIN_REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

  private final long minReportIntervalNanos;
  private final ScheduledExecutorService timeService;
  @VisibleForTesting
  final AtomicInteger clientCount = new AtomicInteger(0);
  private MetricRecorder metricRecorder;
  private final RealOrcaServiceImpl delegate = new RealOrcaServiceImpl();

  /**
   * Constructs a service to report server metrics. Config the report interval lower bound, the
   * executor to run the timer, and a {@link MetricRecorder} that contains metrics data.
   *
   * @param minInterval configures the minimum metrics reporting interval for the
   *        service. Bad configuration (non-positive) will be overridden to service default (30s).
   *        Minimum metrics reporting interval means, if the setting in the client's
   *        request is invalid (non-positive) or below this value, they will be treated
   *        as this value.
   */
  public static BindableService createService(ScheduledExecutorService timeService,
                                              MetricRecorder metricsRecorder,
                                              long minInterval, TimeUnit timeUnit) {
    return new OrcaServiceImpl(minInterval, timeUnit, timeService,  metricsRecorder);
  }

  public static BindableService createService(ScheduledExecutorService timeService,
                                       MetricRecorder metricRecorder) {
    return new OrcaServiceImpl(DEFAULT_MIN_REPORT_INTERVAL_NANOS, TimeUnit.NANOSECONDS,
        timeService, metricRecorder);
  }

  private OrcaServiceImpl(long minInterval, TimeUnit timeUnit, ScheduledExecutorService timeService,
                         MetricRecorder orcaMetrics) {
    this.minReportIntervalNanos = minInterval > 0 ? timeUnit.toNanos(minInterval)
        : DEFAULT_MIN_REPORT_INTERVAL_NANOS;
    this.timeService = checkNotNull(timeService, "timeService");
    this.metricRecorder = checkNotNull(orcaMetrics, "orcaMetrics");
  }

  @Override
  public ServerServiceDefinition bindService() {
    return delegate.bindService();
  }

  private final class RealOrcaServiceImpl extends OpenRcaServiceGrpc.OpenRcaServiceImplBase {
    @Override
    public void streamCoreMetrics(
        OrcaLoadReportRequest request, StreamObserver<OrcaLoadReport> responseObserver) {
      OrcaClient client = new OrcaClient(request, responseObserver);
      client.run();
      clientCount.getAndIncrement();
    }
  }

  private final class OrcaClient implements Runnable {
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
      this.reportIntervalNanos = Math.max(Durations.toNanos(
          checkNotNull(request).getReportInterval()), minReportIntervalNanos);
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
    MetricReport internalReport =
        InternalMetricRecorder.getMetricReport(metricRecorder);
    return OrcaLoadReport.newBuilder().setCpuUtilization(internalReport.getCpuUtilization())
        .setApplicationUtilization(internalReport.getApplicationUtilization())
        .setMemUtilization(internalReport.getMemoryUtilization())
        .setRpsFractional(internalReport.getQps())
        .setEps(internalReport.getEps())
        .putAllUtilization(internalReport.getUtilizationMetrics())
        .build();
  }
}
