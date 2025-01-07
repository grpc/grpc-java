/*
 * Copyright 2017 The gRPC Authors
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
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
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
        serverCallStreamObserver.disableAutoRequest();

        // Set up a back-pressure-aware consumer for the request stream. The onReadyHandler will be invoked
        // when the consuming side has enough buffer space to receive more messages.
        //
        // Note: the onReadyHandler's invocation is serialized on the same thread pool as the incoming StreamObserver's
        // onNext(), onError(), and onComplete() handlers. Blocking the onReadyHandler will prevent additional messages
        // from being processed by the incoming StreamObserver. The onReadyHandler must return in a timely manner or
        // else message processing throughput will suffer.
        class OnReadyHandler implements Runnable {
          // Guard against spurious onReady() calls caused by a race between onNext() and onReady(). If the transport
          // toggles isReady() from false to true while onNext() is executing, but before onNext() checks isReady(),
          // request(1) would be called twice - once by onNext() and once by the onReady() scheduled during onNext()'s
          // execution.
          private boolean wasReady = false;

          @Override
          public void run() {
            if (serverCallStreamObserver.isReady() && !wasReady) {
              wasReady = true;
              logger.info("READY");
              // Signal the request sender to send one message. This happens when isReady() turns true, signaling that
              // the receive buffer has enough free space to receive more messages. Calling request() serves to prime
              // the message pump.
              serverCallStreamObserver.request(1);
            }
          }
        }
        final OnReadyHandler onReadyHandler = new OnReadyHandler();
        serverCallStreamObserver.setOnReadyHandler(onReadyHandler);

        // Give gRPC a StreamObserver that can observe and process incoming requests.
        return new StreamObserver<HelloRequest>() {
          int cnt = 0;
          @Override
          public void onNext(HelloRequest request) {
            // Process the request and send a response or an error.
            try {
              // Accept and enqueue the request.
              String name = request.getName();
              logger.info("--> " + name);

              // Simulate server "work"
              int sleepMillis = ++cnt % 20 == 0 ? 2000 : 100;
              Thread.sleep(sleepMillis);

              // Send a response.
              String message = "Hello " + name;
              logger.info("<-- " + message);
              HelloReply reply = HelloReply.newBuilder().setMessage(message).build();
              responseObserver.onNext(reply);

              // Check the provided ServerCallStreamObserver to see if it is still ready to accept more messages.
              if (serverCallStreamObserver.isReady()) {
                // Signal the sender to send another request. As long as isReady() stays true, the server will keep
                // cycling through the loop of onNext() -> request(1)...onNext() -> request(1)... until the client runs
                // out of messages and ends the loop (via onCompleted()).
                //
                // If request() was called here with the argument of more than 1, the server might runs out of receive
                // buffer space, and isReady() will turn false. When the receive buffer has sufficiently drained,
                // isReady() will turn true, and the serverCallStreamObserver's onReadyHandler will be called to restart
                // the message pump.
                serverCallStreamObserver.request(1);
              } else {
                // If not, note that back-pressure has begun.
                onReadyHandler.wasReady = false;
              }
            } catch (Throwable throwable) {
              throwable.printStackTrace();
              responseObserver.onError(
                  Status.UNKNOWN.withDescription("Error handling request").withCause(throwable).asException());
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
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("Shutting down");
        try {
          server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          e.printStackTrace(System.err);
        }
      }
    });
    server.awaitTermination();
  }
}
