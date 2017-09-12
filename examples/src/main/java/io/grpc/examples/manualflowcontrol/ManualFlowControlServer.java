/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

package io.grpc.examples.manualflowcontrol;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.examples.helloworld.StreamingGreeterGrpc;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.logging.Logger;

public class ManualFlowControlServer {
  private static final Logger logger =
      Logger.getLogger(ManualFlowControlServer.class.getName());

  public static void main(String[] args) throws InterruptedException, IOException {
    // Service class implementation
    StreamingGreeterGrpc.StreamingGreeterImplBase svc = new StreamingGreeterGrpc.StreamingGreeterImplBase() {
      @Override
      public StreamObserver<HelloRequest> sayHelloStreaming(final StreamObserver<HelloReply> responseObserver) {
        // Set up manual flow control for the request stream. It feels backwards to configure the request
        // stream's flow control using the response stream's observer, but this is the way it is.
        final ServerCallStreamObserver<HelloReply> serverCallStreamObserver =
            (ServerCallStreamObserver<HelloReply>) responseObserver;
        serverCallStreamObserver.disableAutoInboundFlowControl();

        // Set up a back-pressure-aware consumer for the request stream. The onReadyHandler will be invoked
        // when the consuming side has enough buffer space to receive more messages.
        //
        // Note: the onReadyHandler is invoked by gRPC's internal thread pool. You can't block here or deadlocks
        // can occur.
        serverCallStreamObserver.setOnReadyHandler(new Runnable() {
          @Override
          public void run() {
            logger.info("READY");
            // Signal the request sender to send one message.
            serverCallStreamObserver.request(1);
          }
        });

        // Give gRPC a StreamObserver it can write incoming requests into.
        return new StreamObserver<HelloRequest>() {
          @Override
          public void onNext(HelloRequest request) {
            // Process the request and send a response or an error.
            try {
              // Accept and enqueue the request.
              String name = request.getName();
              logger.info("--> " + name);

              // Simulate server "work"
              Thread.sleep(100);

              // Send a response.
              String message = "Hello " + name;
              logger.info("<-- " + message);
              HelloReply reply = HelloReply.newBuilder().setMessage(message).build();
              responseObserver.onNext(reply);

              // Request more messages only if the consuming buffer has room to receive it
              if (serverCallStreamObserver.isReady()) {
                // Signal the sender to send another request.
                serverCallStreamObserver.request(1);
              }

            } catch (Throwable throwable) {
              throwable.printStackTrace();
              responseObserver.onError(Status.UNKNOWN.withCause(throwable).asException());
            }
          }

          @Override
          public void onError(Throwable t) {
            // End the response stream if the client presents an error.
            t.printStackTrace();
            responseObserver.onCompleted();
          }

          @Override
          public void onCompleted() {
            // Signal the end of work when the client ends the request stream.
            logger.info("COMPLETED");
            responseObserver.onCompleted();
          }
        };
      }
    };

    final Server server = ServerBuilder
        .forPort(50051)
        .addService(svc)
        .build()
        .start();

    logger.info("Listening on " + server.getPort());

    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        logger.info("Shutting down");
        server.shutdown();
      }
    });
    server.awaitTermination();
  }
}
