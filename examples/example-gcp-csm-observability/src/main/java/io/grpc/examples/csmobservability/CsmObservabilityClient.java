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

package io.grpc.examples.csmobservability;

import io.grpc.Channel;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.gcp.csm.observability.CsmObservability;
import io.opentelemetry.exporter.logging.LoggingMetricExporter;
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A simple CSM observability client that requests a greeting from the {@link HelloWorldServer} and
 * generates CSM telemetry data based on the configuration.
 */
public class CsmObservabilityClient {
  private static final Logger logger = Logger.getLogger(CsmObservabilityClient.class.getName());

  private static volatile boolean sendRpcs = true;
  private final GreeterGrpc.GreeterBlockingStub blockingStub;

  /** Construct client for accessing HelloWorld server using the existing channel. */
  public CsmObservabilityClient(Channel channel) {
    blockingStub = GreeterGrpc.newBlockingStub(channel);
  }

  /** Say hello to server. */
  public void greet(String name) {
    logger.info("Will try to greet " + name + " ...");
    HelloRequest request = HelloRequest.newBuilder().setName(name).build();
    HelloReply response;
    try {
      response = blockingStub.sayHello(request);
    } catch (StatusRuntimeException e) {
      logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
      return;
    }
    logger.info("Greeting: " + response.getMessage());
  }

  /**
   * Greet server. If provided, the first element of {@code args} is the name to use in the
   * greeting. The second argument is the target server.
   */
  public static void main(String[] args) throws Exception {
    String user = "world";
    // String target = "xds:///helloworld:50051";
    String target = "localhost:50051";
    int prometheusPort = 9465;
    if (args.length > 0) {
      if ("--help".equals(args[0])) {
        System.err.println("Usage: [name [target [prometheusPort]]]");
        System.err.println("");
        System.err.println("  name    The name you wish to be greeted by. Defaults to " + user);
        System.err.println("  target  The server to connect to. Defaults to " + target);
        System.err.println("  prometheusPort  The port to expose prometheus metrics. Defaults to " + prometheusPort);
        System.exit(1);
      }
      user = args[0];
    }
    if (args.length > 1) {
      target = args[1];
    }
    if (args.length > 2) {
      prometheusPort = Integer.parseInt(args[2]);
    }

    Thread mainThread = Thread.currentThread();

    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC client since JVM is shutting down");

        sendRpcs = false;
        try {
          mainThread.join();
        } catch (InterruptedException e) {
          e.printStackTrace(System.err);
        }
        System.err.println("*** client shut down");
      }
    });

    /**
     * Adds a PrometheusHttpServer to convert OpenTelemetry metrics to Prometheus format and
     * expose these via a HttpServer exporter to the SdkMeterProvider.
     */
    SdkMeterProvider sdkMeterProvider = SdkMeterProvider.builder()
        .registerMetricReader(
            PrometheusHttpServer.builder().setPort(prometheusPort).build())
        .build();

    /** Initialize OpenTelemetry SDK with MeterProvider configured with Prometeheus. */
    OpenTelemetrySdk openTelemetrySdk =
        OpenTelemetrySdk.builder().setMeterProvider(sdkMeterProvider).build();

    /** Initialize CSM Observability. */
    CsmObservability observability = CsmObservability.newBuilder()
        .sdk(openTelemetrySdk)
        .build();
    /** Registers CSM observabiity globally. */
    observability.registerGlobal();

    /** Create a communication channel to the server, known as a Channel. */
    ManagedChannel channel = Grpc.newChannelBuilder(target, InsecureChannelCredentials.create())
        .build();

    /** Run RPCs every second. */
    while (sendRpcs) {
      CsmObservabilityClient client = new CsmObservabilityClient(channel);
      client.greet(user);
      // Sleep for a bit before sending the next RPC.
      Thread.sleep(3000);
    }

    /** Close CSM Observability. */
    if (observability != null) {
      observability.close();
    }
    openTelemetrySdk.close();
    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
  }
}
