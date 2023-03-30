/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.examples.healthservice;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Server that manages startup/shutdown of a {@code Greeter} server.
 */
public class HealthServiceServer {
  private static final Logger logger = Logger.getLogger(HealthServiceServer.class.getName());

  private Server server;
  private HealthStatusManager health;

  private void start() throws IOException {
    /* The port on which the server should run */
    int port = 50051;
    health = new HealthStatusManager();
    server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
        .addService(new GreeterImpl())
        .addService(health.getHealthService())
        .build()
        .start();
    logger.info("Server started, listening on " + port);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        try {
          HealthServiceServer.this.stop();
        } catch (InterruptedException e) {
          e.printStackTrace(System.err);
        }
        System.err.println("*** server shut down");
      }
    });

    health.setStatus("", ServingStatus.SERVING);
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
    System.setProperty("java.util.logging.SimpleFormatter.format",
        "%1$tH:%1$tM:%1$tS %4$s %2$s: %5$s%6$s%n");

    final HealthServiceServer server = new HealthServiceServer();
    server.start();
    server.blockUntilShutdown();
  }

  private class GreeterImpl extends GreeterGrpc.GreeterImplBase {
    boolean isServing = true;

    @Override
    public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
      if (!isServing) {
        responseObserver.onError(
            Status.INTERNAL.withDescription("Not Serving right now").asRuntimeException());
        return;
      }

      if (isNameLongEnough(req)) {
        HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + req.getName()).build();
        responseObserver.onNext(reply);
        responseObserver.onCompleted();
      } else {
        logger.warning("Tiny message received, throwing a temper tantrum");
        health.setStatus("", ServingStatus.NOT_SERVING);
        isServing = false;

        // In 10 seconds set it back to serving
        new Thread(new Runnable() {
          @Override
          public void run() {
            try {
              Thread.sleep(10000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return;
            }
            isServing = true;
            health.setStatus("", ServingStatus.SERVING);
            logger.info("tantrum complete");
          }
        }).start();
        responseObserver.onError(
            Status.INVALID_ARGUMENT.withDescription("Offended by short name").asRuntimeException());
      }
    }

    private boolean isNameLongEnough(HelloRequest req) {
      return isServing && req.getName().length() >= 5;
    }
  }
}
