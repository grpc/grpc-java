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

package io.grpc.examples.metadata;

import io.grpc.Channel;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A simple client that like {@link io.grpc.examples.helloworld.HelloWorldClient}.
 * This client shows how to use interceptors to modify and/or access headers and trailing metadata.
 */
public class CustomMetadataClient {
  private static final Logger logger = Logger.getLogger(CustomMetadataClient.class.getName());

  private final ManagedChannel originChannel;
  private final GreeterGrpc.GreeterBlockingStub blockingStub;
  private final MetadataClientInterceptor interceptor = new MetadataClientInterceptor();

  /**
   * A custom client.
   */
  private CustomMetadataClient(String host, int port) {
    originChannel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext()
        .build();
    Channel channel = ClientInterceptors.intercept(originChannel, interceptor);
    blockingStub = GreeterGrpc.newBlockingStub(channel);
  }

  private void shutdown() throws InterruptedException {
    originChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
  }

  /**
   * A simple client method that like {@link io.grpc.examples.helloworld.HelloWorldClient}.
   */
  private void greet(String name) {
    interceptor.outgoingHeader.set("Value of client->server header");
    logger.info("Will try to greet " + name + " ...");
    HelloRequest request = HelloRequest.newBuilder().setName(name).build();
    HelloReply response;
    try {
      response = blockingStub.sayHello(request);
    } catch (StatusRuntimeException e) {
      logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
      return;
    }
    logger.info("Greeting: " + response.getMessage());
    logger.info("header received from server: " + interceptor.receivedHeaders);
    logger.info("trailer reeived from server: " + interceptor.receivedTrailers);
  }

  /**
   * Main start the client from the command line.
   */
  public static void main(String[] args) throws Exception {
    CustomMetadataClient client = new CustomMetadataClient("localhost", 50051);
    try {
      /* Access a service running on the local machine on port 50051 */
      String user = "world";
      if (args.length > 0) {
        user = args[0]; /* Use the arg as the name to greet if provided */
      }
      client.greet(user);
    } finally {
      client.shutdown();
    }
  }
}
