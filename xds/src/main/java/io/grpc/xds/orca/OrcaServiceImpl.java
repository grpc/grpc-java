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
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implements a {@link BindableService} that generates Out-Of-Band server metrics.
 */
public final class OrcaServiceImpl implements BindableService {
  private static final Logger logger = Logger.getLogger(OrcaServiceImpl.class.getName());

  private final long minReportIntervalNanos;
  private final ScheduledExecutorService timeService;
  @VisibleForTesting
  final AtomicInteger clientCount = new AtomicInteger(0);
  private OrcaMetrics orcaMetrics;
  private final RealOrcaServiceImpl delegate = new RealOrcaServiceImpl();

  /**
   * Constructs a service to report server metrics. Config the report interval lower bound, the
   * executor to run the timer, and a {@link OrcaMetrics} that contains metrics data.
   */
  public OrcaServiceImpl(long minReportIntervalNanos, ScheduledExecutorService timeService,
                         OrcaMetrics orcaMetrics) {
    this.minReportIntervalNanos = minReportIntervalNanos;
    this.timeService = checkNotNull(timeService, "timeService");
    this.orcaMetrics = checkNotNull(orcaMetrics, "orcaMetrics");
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
    return OrcaLoadReport.newBuilder().setCpuUtilization(orcaMetrics.getCpuUtilization())
        .setMemUtilization(orcaMetrics.getMemoryUtilization())
        .putAllUtilization(orcaMetrics.getUtilizationMetrics())
        .build();
  }
}
