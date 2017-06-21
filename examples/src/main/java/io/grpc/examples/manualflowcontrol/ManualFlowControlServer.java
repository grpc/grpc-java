/*
 * Copyright 2016, gRPC Authors All rights reserved.
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
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.stub.CallStreamObserver;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ManualFlowControlServer {
    public static void main(String[] args) throws InterruptedException, IOException {
        final ExecutorService pool = Executors.newCachedThreadPool();
        final String COMPLETED = "COMPLETED";

        // Service class implementation
        GreeterGrpc.GreeterImplBase svc = new GreeterGrpc.GreeterImplBase() {
            @Override
            public StreamObserver<HelloRequest> sayHelloStreaming(final StreamObserver<HelloReply> responseObserver) {
                // Use a queue to buffer between received messages and sent messages.
                // The queue is a fixed capacity so that back-pressure will be applied to the request stream when the
                // work queue is full.
                final Queue<String> work = new LinkedList<String>();

                // Set up manual flow control for the request stream. It feels backwards to configure the request
                // stream's flow control using the response stream's observer, but this is the way it is.
                final CallStreamObserver<HelloReply> responseCallStreamObserver = (CallStreamObserver<HelloReply>) responseObserver;
                responseCallStreamObserver.disableAutoInboundFlowControl();
                // Signal the request sender to send one message.
                responseCallStreamObserver.request(1);

                // Set up a back-pressure-aware producer for the response stream. The onReadyHandler will be invoked
                // when the consuming side has enough buffer space to receive more messages.
                //
                // Note: the onReadyHandler is invoked by gRPC's internal thread pool. You can't block in this in
                // method or deadlocks can occur.
                responseCallStreamObserver.setOnReadyHandler(new Runnable() {
                   @Override
                    public void run() {
                        // Start generating values from where we left off on a non-gRPC thread.
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                // requestStream.isReady() will go false when the consuming side runs out of buffer space and
                                // signals to slow down with back-pressure.
                                while (responseCallStreamObserver.isReady()) {
                                    if (!work.isEmpty()) {
                                        // Send more messages if there are more messages to send.
                                        String name = work.remove();
                                        if (name != COMPLETED) {
                                            // Simulate doing some work to generate the next value
                                            try { Thread.sleep(200); } catch (InterruptedException e) { e.printStackTrace(); }

                                            String message = "Hello " + name;
                                            System.out.println("<-- " + message);
                                            HelloReply reply = HelloReply.newBuilder().setMessage(message).build();

                                            // Send a response.
                                            responseObserver.onNext(reply);
                                        } else {
                                            System.out.println("Done.");
                                            responseObserver.onCompleted();
                                        }
                                    }
                                }
                            }
                        });
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
                            System.out.println("--> " + name);
                            work.add(name);
                            // Signal the sender to send another request.
                            responseCallStreamObserver.request(1);
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
                        work.add(COMPLETED);
                    }
                };
            }
        };

        final Server server = ServerBuilder
                .forPort(50051)
                .addService(svc)
                .build()
                .start();

        System.out.println("Listening on " + server.getPort());

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                System.out.println("Shutting down");
                server.shutdown();
                pool.shutdown();
            }
        });
        server.awaitTermination();
    }
}
