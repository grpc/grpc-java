/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.examples.nameresolve;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class NameResolveServer {
    static public final int serverCount = 3;
    static public final int startPort = 50051;
    private static final Logger logger = Logger.getLogger(NameResolveServer.class.getName());
    private Server[] servers;

    public static void main(String[] args) throws IOException, InterruptedException {
        final NameResolveServer server = new NameResolveServer();
        server.start();
        server.blockUntilShutdown();
    }

    private void start() throws IOException {
        servers = new Server[serverCount];
        for (int i = 0; i < serverCount; i++) {
            int port = startPort + i;
            servers[i] = ServerBuilder.forPort(port)
                    .addService(new GreeterImpl(port))
                    .build()
                    .start();
            logger.info("Server started, listening on " + port);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("*** shutting down gRPC server since JVM is shutting down");
            try {
                NameResolveServer.this.stop();
            } catch (InterruptedException e) {
                e.printStackTrace(System.err);
            }
            System.err.println("*** server shut down");
        }));
    }

    private void stop() throws InterruptedException {
        for (int i = 0; i < serverCount; i++) {
            if (servers[i] != null) {
                servers[i].shutdown().awaitTermination(30, TimeUnit.SECONDS);
            }
        }
    }

    private void blockUntilShutdown() throws InterruptedException {
        for (int i = 0; i < serverCount; i++) {
            if (servers[i] != null) {
                servers[i].awaitTermination();
            }
        }
    }

    static class GreeterImpl extends GreeterGrpc.GreeterImplBase {

        int port;

        public GreeterImpl(int port) {
            this.port = port;
        }

        @Override
        public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
            HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + req.getName() + " from server<" + this.port + ">").build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }
    }
}
