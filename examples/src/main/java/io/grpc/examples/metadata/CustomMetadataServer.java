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

package io.grpc.examples.metadata;

import io.grpc.Context;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * A simple server that receives and sends metadata through interceptors.
 */
public class CustomMetadataServer {
  private static final Logger logger = Logger.getLogger(CustomMetadataServer.class.getName());

  /* The port on which the server should run */
  private static final int PORT = 50051;
  private Server server;

  private void start() throws IOException {
    MetadataServerInterceptor interceptor = new MetadataServerInterceptor();
    interceptor.outgoingHeader.set("Server->Client header value");
    interceptor.outgoingTrailer.set("Server->Client trailer value");
    server = ServerBuilder.forPort(PORT)
        .addService(
            ServerInterceptors.intercept(new GreeterImpl(), interceptor))
        .build()
        .start();
    logger.info("Server started, listening on " + PORT);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        CustomMetadataServer.this.stop();
        System.err.println("*** server shut down");
      }
    });
  }

  private void stop() {
    if (server != null) {
      server.shutdown();
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
    final CustomMetadataServer server = new CustomMetadataServer();
    server.start();
    server.blockUntilShutdown();
  }

  private static class GreeterImpl extends GreeterGrpc.GreeterImplBase {

    @Override
    public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
      // The Context was attached by the interceptor
      logger.info(
          "Context converted from header received from client: "
          + MetadataServerInterceptor.CLIENT_HEADER_CTX_KEY.get());
      HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + req.getName()).build();
      responseObserver.onNext(reply);
      responseObserver.onCompleted();
    }
  }
}
