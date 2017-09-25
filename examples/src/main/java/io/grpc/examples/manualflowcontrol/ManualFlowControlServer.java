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
import java.util.concurrent.atomic.AtomicBoolean;
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

        // Guard against spurious onReady() calls caused by a race between onNext() and onReady(). If the transport
        // toggles isReady() from false to true while onNext() is executing, but before onNext() checks isReady(),
        // request(1) would be called twice - once by onNext() and once by the onReady() scheduled during onNext()'s
        // execution.
        final AtomicBoolean wasReady = new AtomicBoolean(false);

        // Set up a back-pressure-aware producer for the request stream. The onReadyHandler will be invoked
        // when the consuming side has enough buffer space to receive more messages.
        //
        // Note: the onReadyHandler is invoked by gRPC's message thread pool. You can't block here or deadlocks
        // can occur.
        serverCallStreamObserver.setOnReadyHandler(new Runnable() {
          @Override
          public void run() {
            // Ensure the transport is ready and that a request(1) is still needed.
            if (serverCallStreamObserver.isReady() && wasReady.compareAndSet(false, true)) {
              // Signal the request sender to send one message.
              serverCallStreamObserver.request(1);
            }
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

              // Check the provided ServerCallStreamObserver to see if it is still ready to accept more messages.
              if (serverCallStreamObserver.isReady()) {
                // If so, signal the sender to send another request.
                serverCallStreamObserver.request(1);
              } else {
                // If not, note that back-pressure has begun.
                wasReady.set(false);
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
