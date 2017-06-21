/*
 * Copyright, 1999-2017, SALESFORCE.com
 * All Rights Reserved
 * Company Confidential
 */

package io.grpc.examples.manualflowcontrol;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.stub.StreamObserver;

import java.io.IOException;

public class ManualFlowControlServer {
    public static void main(String[] args) throws InterruptedException, IOException {
        GreeterGrpc.GreeterImplBase svc = new GreeterGrpc.GreeterImplBase() {
            @Override
            public StreamObserver<HelloRequest> sayHelloStreaming(final StreamObserver<HelloReply> responseObserver) {

                // Give gRPC a StreamObserver it can write incoming requests into
                return new StreamObserver<HelloRequest>() {
                    @Override
                    public void onNext(HelloRequest request) {
                        // Process the request and send a response or an error.
                        try {
                            String message = "Hello " + request.getName();
                            System.out.println("Replying: " + message);
                            HelloReply reply = HelloReply.newBuilder().setMessage(message).build();
                            responseObserver.onNext(reply);
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
                        // End the response stream when the client ends the request stream.
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

        System.out.println("Listening on " + server.getPort());

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                System.out.println("Shutting down");
                server.shutdown();
            }
        });
        server.awaitTermination();
    }
}
