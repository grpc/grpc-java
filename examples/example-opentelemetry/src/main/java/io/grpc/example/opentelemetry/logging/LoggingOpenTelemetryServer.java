/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.examples.opentelemetry.logging;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.opentelemetry.GrpcOpenTelemetry;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.exporter.logging.LoggingMetricExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import java.time.Duration;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * gRPC server that manages startup/shutdown of a {@code Greeter} server and exports
 * gRPC OpenTelemetry metrics data using {@code java.util.logging}.
 */
public class LoggingOpenTelemetryServer {
  private static final Logger logger = Logger.getLogger(LoggingOpenTelemetryServer.class.getName());

  private Server gRPCServer;
  private void start(int port) throws IOException {
    gRPCServer = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
        .addService(new GreeterImpl())
        .build()
        .start();
    logger.info("Server started, listening on " + port);
  }

  private void stop() throws InterruptedException {
    if (gRPCServer != null) {
      gRPCServer.shutdown().awaitTermination(30, TimeUnit.SECONDS);
    }
  }

  /**
   * Await termination on the main thread since the grpc library uses daemon threads.
   */
  private void blockUntilShutdown() throws InterruptedException {
    if (gRPCServer != null) {
      gRPCServer.awaitTermination();
    }
  }

  /**
   * Main launches the server from the command line.
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    // The port on which the server should run.
    int port = 50051;
    // The port on which prometheus metrics are exposed.
    int prometheusPort = 9464;
    // The number of milliseconds between metric exports.
    long metricExportInterval = 800L;

    if (args.length > 0) {
      if ("--help".equals(args[0])) {
        System.err.println("Usage: [port]");
        System.err.println("");
        System.err.println("  port  The port on which server will run. Defaults to " + port);
        System.exit(1);
      }
      port = Integer.parseInt(args[0]);
    }

    // Create an instance of PeriodicMetricReader and configure it to export
    // via a logging exporter to the SdkMeterProvider.
    SdkMeterProvider sdkMeterProvider = SdkMeterProvider.builder()
        .registerMetricReader(
            PeriodicMetricReader.builder(LoggingMetricExporter.create())
                .setInterval(Duration.ofMillis(metricExportInterval))
                .build())
        .build();

    // Initialize OpenTelemetry SDK with MeterProvider configured with Logging metrics exporter
    OpenTelemetrySdk openTelemetrySdk =
        OpenTelemetrySdk.builder().setMeterProvider(sdkMeterProvider).build();

    // Initialize gRPC OpenTelemetry.
    // Following client metrics are enabled by default :
    //     1. grpc.server.call.started
    //     2. grpc.server.call.sent_total_compressed_message_size
    //     3. grpc.server.call.rcvd_total_compressed_message_size
    //     4. grpc.server.call.duration
    GrpcOpenTelemetry grpcOpenTelmetry = GrpcOpenTelemetry.newBuilder()
        .sdk(openTelemetrySdk)
        .build();
    // Registers gRPC OpenTelemetry globally.
    grpcOpenTelmetry.registerGlobal();

    final LoggingOpenTelemetryServer server = new LoggingOpenTelemetryServer();
    server.start(port);

    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        try {
          server.stop();
        } catch (InterruptedException e) {
          e.printStackTrace(System.err);
        }
        // Shut down OpenTelemetry SDK.
        openTelemetrySdk.close();

        System.err.println("*** server shut down");
      }
    });

    server.blockUntilShutdown();
  }

  static class GreeterImpl extends GreeterGrpc.GreeterImplBase {

    @Override
    public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
      HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + req.getName()).build();
      responseObserver.onNext(reply);
      responseObserver.onCompleted();
    }
  }
}
