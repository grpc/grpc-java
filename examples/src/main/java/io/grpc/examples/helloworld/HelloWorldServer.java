/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc.examples.helloworld;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Server that manages startup/shutdown of a {@code Greeter} server.
 */
public class HelloWorldServer {
  private static final Logger logger = Logger.getLogger(HelloWorldServer.class.getName());

  private Server server;
  private void start() throws IOException {
    /* The port on which the server should run */
    int port = 50051;
    /*
     * By default gRPC uses a global, shared Executor.newCachedThreadPool() for gRPC callbacks into
     * your application. This is convenient, but can cause an excessive number of threads to be
     * created if there are many RPCs. It is often better to limit the number of threads your
     * application uses for processing and let RPCs queue when the CPU is saturated.
     * The appropriate number of threads varies heavily between applications.
     * Async application code generally does not need more threads than CPU cores.
     */
    ExecutorService executor = Executors.newFixedThreadPool(2);
    server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
        .executor(executor)
        .addService(new GreeterImpl())
        .build()
        .start();
    logger.info("Server started, listening on " + port);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        try {
          HelloWorldServer.this.stop();
        } catch (InterruptedException e) {
          if (server != null) {
            server.shutdownNow();
          }
          e.printStackTrace(System.err);
        } finally {
          executor.shutdown();
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
    final HelloWorldServer server = new HelloWorldServer();
    server.start();
    server.blockUntilShutdown();
  }

  static class GreeterImpl extends io.grpc.examples.helloworld.GreeterGrpc.GreeterImplBase {

    @Override
    public void sayHello(io.grpc.examples.helloworld.HelloRequest req, StreamObserver<io.grpc.examples.helloworld.HelloReply> responseObserver) {
      io.grpc.examples.helloworld.HelloReply reply = io.grpc.examples.helloworld.HelloReply.newBuilder().setMessage("Hello " + req.getName()).build();
      responseObserver.onNext(reply);
      responseObserver.onCompleted();
    }
  }
}
