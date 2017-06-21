/*
 * Copyright, 1999-2017, SALESFORCE.com
 * All Rights Reserved
 * Company Confidential
 */

package io.grpc.examples.manualflowcontrol;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.stub.CallStreamObserver;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ManualFlowControlClient {
    public static void main(String[] args) throws InterruptedException {
        final Object done = new Object();

        // Create a channel and a stub
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", 50051)
                .usePlaintext(true)
                .build();
        GreeterGrpc.GreeterStub stub = GreeterGrpc.newStub(channel);

        ClientResponseObserver<HelloRequest, HelloReply> clientResponseObserver =
                new ClientResponseObserver<HelloRequest, HelloReply>() {

            ClientCallStreamObserver<HelloRequest> requestStream;

            @Override
            public void beforeStart(final ClientCallStreamObserver<HelloRequest> requestStream) {
                this.requestStream = requestStream;
                // Set up manual flow control for the response stream.
                requestStream.disableAutoInboundFlowControl();

                // Set up a back-pressure-aware producer for the request stream. The onReadyHandler will be invoked
                // when the consuming side has enough buffer space to receive more messages.
                requestStream.setOnReadyHandler(new Runnable() {
                    // An iterator is used so we can pause and resume iteration of the request data.
                    Iterator<String> iterator = names().iterator();

                    @Override
                    public void run() {
                        // requestStream.isReady() will go false when the consuming side runs out of buffer space and
                        // signals to slow down with back-pressure.
                        while (requestStream.isReady()) {
                            if (iterator.hasNext()) {
                                // Send more messages if there are more messages to send.
                                String name = iterator.next();
                                System.out.println("Put: " + name);
                                HelloRequest request = HelloRequest.newBuilder().setName(name).build();
                                requestStream.onNext(request);
                            } else {
                                // Signal completion if there is nothing left to send.
                                requestStream.onCompleted();
                            }
                        }
                    }
                });
            }

            @Override
            public void onNext(HelloReply value) {
                System.out.println("Got: " + value.getMessage());
                // Signal the sender to send one message.
                requestStream.request(1);
            }

            @Override
            public void onError(Throwable t) {
                t.printStackTrace();
                synchronized (done) {
                    done.notify();
                }
            }

            @Override
            public void onCompleted() {
                System.out.println("All Done");
                synchronized (done) {
                    done.notify();
                }
            }
        };

        // Note: clientResponseObserver is handling both request and response stream processing.
        stub.sayHelloStreaming(clientResponseObserver);

        synchronized (done) {
            done.wait();
        }

        channel.shutdown();
        channel.awaitTermination(1, TimeUnit.SECONDS);
    }

    private static List<String> names() {
        List<String> names = new ArrayList<String>();

        names.add("Sophia");
        names.add("Jackson");
        names.add("Emma");
        names.add("Aiden");
        names.add("Olivia");
        names.add("Lucas");
        names.add("Ava");
        names.add("Liam");
        names.add("Mia");
        names.add("Noah");
        names.add("Isabella");
        names.add("Ethan");
        names.add("Riley");
        names.add("Mason");
        names.add("Aria");
        names.add("Caden");
        names.add("Zoe");
        names.add("Oliver");
        names.add("Charlotte");
        names.add("Elijah");
        names.add("Lily");
        names.add("Grayson");
        names.add("Layla	");
        names.add("Jacob");
        names.add("Amelia");
        names.add("Michael");
        names.add("Emily");
        names.add("Benjamin");
        names.add("Madelyn");
        names.add("Carter");
        names.add("Aubrey");
        names.add("James");
        names.add("Adalyn");
        names.add("Jayden");
        names.add("Madison");
        names.add("Logan");
        names.add("Chloe");
        names.add("Alexander");
        names.add("Harper");
        names.add("Caleb");
        names.add("Abigail");
        names.add("Ryan");
        names.add("Aaliyah");
        names.add("Luke");
        names.add("Avery");
        names.add("Daniel");
        names.add("Evelyn");
        names.add("Jack");
        names.add("Kaylee");
        names.add("William");
        names.add("Ella");
        names.add("Owen");
        names.add("Ellie");
        names.add("Gabriel");
        names.add("Scarlett");
        names.add("Matthew");
        names.add("Arianna");
        names.add("Connor");
        names.add("Hailey");
        names.add("Jayce");
        names.add("Nora");
        names.add("Isaac");
        names.add("Addison");
        names.add("Sebastian");
        names.add("Brooklyn");
        names.add("Henry");
        names.add("Hannah");
        names.add("Muhammad");
        names.add("Mila");
        names.add("Cameron");
        names.add("Leah");
        names.add("Wyatt");
        names.add("Elizabeth");
        names.add("Dylan");
        names.add("Sarah");
        names.add("Nathan");
        names.add("Eliana");
        names.add("Nicholas");
        names.add("Mackenzie");
        names.add("Julian");
        names.add("Peyton");
        names.add("Eli");
        names.add("Maria	");
        names.add("Levi");
        names.add("Grace");
        names.add("Isaiah");
        names.add("Adeline");
        names.add("Landon");
        names.add("Elena");
        names.add("David");
        names.add("Anna");
        names.add("Christian");
        names.add("Victoria");
        names.add("Andrew");
        names.add("Camilla");
        names.add("Brayden");
        names.add("Lillian");
        names.add("John");
        names.add("Natalie");
        names.add("Lincoln");

        return names;
    }
}
