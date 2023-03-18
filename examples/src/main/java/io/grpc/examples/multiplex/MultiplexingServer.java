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

package io.grpc.examples.multiplex;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.examples.echo.EchoGrpc;
import io.grpc.examples.echo.EchoRequest;
import io.grpc.examples.echo.EchoResponse;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * A sample gRPC server that serves both the Greeting and Echo services.
 */
public class MultiplexingServer {

  private static final Logger logger = Logger.getLogger(MultiplexingServer.class.getName());

  private final int port;
  private Server server = null;

  public MultiplexingServer(int port) throws IOException {
    this.port = port;
  }
      private void start() throws IOException {
        /* The port on which the server should run */
        server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
            .addService(new io.grpc.examples.multiplex.MultiplexingServer.GreeterImpl())
            .addService(new io.grpc.examples.multiplex.EchoServer())
            .build()
            .start();
        logger.info("Server started, listening on " + port);
        Runtime.getRuntime().addShutdownHook(new Thread() {
          @Override
          public void run() {
            // Use stderr here since the logger may have been reset by its JVM shutdown hook.
            System.err.println("*** shutting down gRPC server since JVM is shutting down");
            try {
              io.grpc.examples.multiplex.MultiplexingServer.this.stop();
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
        final MultiplexingServer server = new MultiplexingServer(500051);
        server.start();
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
