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

package io.grpc.examples.orca;

import io.grpc.BindableService;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.services.CallMetricRecorder;
import io.grpc.services.MetricRecorder;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.orca.OrcaMetricReportingServerInterceptor;
import io.grpc.xds.orca.OrcaServiceImpl;
import io.grpc.xds.shaded.com.github.xds.data.orca.v3.OrcaLoadReport;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Server that manages startup/shutdown of a {@code Greeter} server.
 */
public class CustomBackendMetricsServer {
  private static final Logger logger = Logger.getLogger(CustomBackendMetricsServer.class.getName());

  private Server server;
  private static Random random = new Random();
  private MetricRecorder metricRecorder;

  private void start() throws IOException {
    /* The port on which the server should run */
    int port = 50051;

    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    metricRecorder = MetricRecorder.newInstance();
    // Configure OOB metrics reporting minimum report interval to be 1s. This allows client
    // configuration to be as short as 1s, suitable for test demonstration.
    BindableService orcaOobService =
        OrcaServiceImpl.createService(executor, metricRecorder, 1, TimeUnit.SECONDS);
    server = ServerBuilder.forPort(port)
        .addService(new GreeterImpl())
        // Enable OOB custom backend metrics reporting.
        .addService(orcaOobService)
        // Enable per-query custom backend metrics reporting.
        .intercept(OrcaMetricReportingServerInterceptor.getInstance())
        .build()
        .start();
    logger.info("Server started, listening on " + port);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        try {
          CustomBackendMetricsServer.this.stop();
        } catch (InterruptedException e) {
          e.printStackTrace(System.err);
        }
        System.err.println("*** server shut down");
      }
    });
  }

  private void stop() throws InterruptedException {
    if (server != null) {
      server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
    }
  }

  /**
   * Await termination on the main thread since the grpc library uses daemon threads.
   */
  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  /**
   * Main launches the server from the command line.
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    CustomBackendMetricsServer server = new CustomBackendMetricsServer();
    server.start();
    server.blockUntilShutdown();
  }

  class GreeterImpl extends GreeterGrpc.GreeterImplBase {

    @Override
    public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
      HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + req.getName()).build();
      OrcaLoadReport randomPerRpcMetrics = OrcaLoadReport.newBuilder()
          .setCpuUtilization(random.nextDouble())
          .setMemUtilization(random.nextDouble())
          .putUtilization("util", random.nextDouble())
          .putRequestCost("cost", random.nextDouble())
          .build();
      // Sets per-query backend metrics to a random test report.
      CallMetricRecorder.getCurrent()
          .recordMemoryUtilizationMetric(randomPerRpcMetrics.getMemUtilization())
          .recordCallMetric("cost", randomPerRpcMetrics.getRequestCostOrDefault("cost", 0.0))
          .recordUtilizationMetric("util", randomPerRpcMetrics.getUtilizationOrDefault("util", 0.0));
      System.out.println("Hello World Server updates RPC metrics data:\n" + randomPerRpcMetrics);

      OrcaLoadReport randomOobMetrics = OrcaLoadReport.newBuilder()
          .setCpuUtilization(random.nextDouble())
          .setMemUtilization(random.nextDouble())
          .putUtilization("util", random.nextDouble())
          .build();
      // Sets OOB backend metrics to a random test report.
      metricRecorder.setCpuUtilizationMetric(randomOobMetrics.getCpuUtilization());
      metricRecorder.setMemoryUtilizationMetric(randomOobMetrics.getMemUtilization());
      metricRecorder.setAllUtilizationMetrics(randomOobMetrics.getUtilizationMap());
      System.out.println("Hello World Server updates OOB metrics data:\n" + randomOobMetrics);
      responseObserver.onNext(reply);
      responseObserver.onCompleted();
    }
  }
}
