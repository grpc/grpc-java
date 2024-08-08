/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.examples.dualstack;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Starts 3 different greeter services each on its own port, but all for localhost.
 * The first service listens on both IPv4 and IPv6,
 * the second on just IPv4, and the third on just IPv6.
 */
public class DualStackServer {
    private static final Logger logger = Logger.getLogger(DualStackServer.class.getName());
    private List<Server> servers;

    public static void main(String[] args) throws IOException, InterruptedException {
        final DualStackServer server = new DualStackServer();
        server.start();
        server.blockUntilShutdown();
    }

    private void start() throws IOException {
        InetSocketAddress inetSocketAddress;

        servers = new ArrayList<>();
        int[] serverPorts = ExampleDualStackNameResolver.SERVER_PORTS;
        for (int i = 0; i < serverPorts.length; i++ ) {
            String addressType;
            int port = serverPorts[i];
            ServerBuilder<?> serverBuilder;
            switch (i) {
                case 0:
                    serverBuilder = ServerBuilder.forPort(port); // bind to both IPv4 and IPv6
                    addressType = "both IPv4 and IPv6";
                    break;
                case 1:
                    // bind to IPv4 only
                    inetSocketAddress = new InetSocketAddress("127.0.0.1", port);
                    serverBuilder = NettyServerBuilder.forAddress(inetSocketAddress);
                    addressType = "IPv4 only";
                    break;
                case 2:
                    // bind to IPv6 only
                    inetSocketAddress = new InetSocketAddress("::1", port);
                    serverBuilder = NettyServerBuilder.forAddress(inetSocketAddress);
                    addressType = "IPv6 only";
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + i);
            }

            servers.add(serverBuilder
                .addService(new GreeterImpl(port, addressType))
                .build()
                .start());
            logger.info("Server started, listening on " + port);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("*** shutting down gRPC server since JVM is shutting down");
            try {
                DualStackServer.this.stop();
            } catch (InterruptedException e) {
                e.printStackTrace(System.err);
            }
            System.err.println("*** server shut down");
        }));
    }

    private void stop() throws InterruptedException {
        for (Server server : servers) {
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private void blockUntilShutdown() throws InterruptedException {
        for (Server server : servers) {
            server.awaitTermination();
        }
    }

    static class GreeterImpl extends GreeterGrpc.GreeterImplBase {

        int port;
        String addressType;

        public GreeterImpl(int port, String addressType) {
            this.port = port;
            this.addressType = addressType;
        }

        @Override
        public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
            String msg = String.format("Hello %s from server<%d> type: %s",
                req.getName(), this.port, addressType);
            HelloReply reply = HelloReply.newBuilder().setMessage(msg).build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }
    }
}
