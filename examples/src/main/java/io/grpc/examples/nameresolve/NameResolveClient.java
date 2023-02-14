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

import io.grpc.*;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NameResolveClient {
    public static final String exampleScheme = "example";
    public static final String exampleServiceName = "lb.example.grpc.io";
    private static final Logger logger = Logger.getLogger(NameResolveClient.class.getName());
    private final GreeterGrpc.GreeterBlockingStub blockingStub;

    public NameResolveClient(Channel channel) {
        blockingStub = GreeterGrpc.newBlockingStub(channel);
    }

    public static void main(String[] args) throws Exception {
        NameResolverRegistry.getDefaultRegistry().register(new ExampleNameResolverProvider());

        logger.info("Use default DNS resolver");
        ManagedChannel channel = ManagedChannelBuilder.forTarget("localhost:50051")
                .usePlaintext()
                .build();
        try {
            NameResolveClient client = new NameResolveClient(channel);
            for (int i = 0; i < 5; i++) {
                client.greet("request" + i);
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }

        logger.info("Change to use example name resolver");
        /*
          Dial to "example:///resolver.example.grpc.io", use {@link ExampleNameResolver} to create connection
          "resolver.example.grpc.io" is converted to {@link java.net.URI.path}
         */
        channel = ManagedChannelBuilder.forTarget(
                        String.format("%s:///%s", exampleScheme, exampleServiceName))
                .defaultLoadBalancingPolicy("round_robin")
                .usePlaintext()
                .build();
        try {
            NameResolveClient client = new NameResolveClient(channel);
            for (int i = 0; i < 5; i++) {
                client.greet("request" + i);
            }
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    public void greet(String name) {
        HelloRequest request = HelloRequest.newBuilder().setName(name).build();
        HelloReply response;
        try {
            response = blockingStub.sayHello(request);
        } catch (StatusRuntimeException e) {
            logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
            return;
        }
        logger.info("Greeting: " + response.getMessage());
    }
}
