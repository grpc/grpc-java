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

package io.grpc.examples.helloworld;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A simple client that requests a greeting from the {@link HelloWorldServer}.
 */
public class HelloWorldClient {
  private static final Logger logger = Logger.getLogger(HelloWorldClient.class.getName());

  private final ManagedChannel channel;
  private final GreeterGrpc.GreeterBlockingStub blockingStub;

  /** Construct client connecting to HelloWorld server at {@code host:port}. */
  public HelloWorldClient(String host, int port) {
    this(ManagedChannelBuilder.forAddress(host, port)
        // Channels are secure by default (via SSL/TLS). For the example we disable TLS to avoid
        // needing certificates.
        .usePlaintext()
        .build());
  }

  /** Construct client connecting to HelloWorld server at the given {@code target}. */
  public HelloWorldClient(String target) {
    this(ManagedChannelBuilder.forTarget(target)
        // Channels are secure by default (via SSL/TLS). For the example we disable TLS to avoid
        // needing certificates.
        .usePlaintext()
        .enableRetry()
        .build());
  }

  /** Construct client for accessing HelloWorld server using the existing channel. */
  HelloWorldClient(ManagedChannel channel) {
    this.channel = channel;
    blockingStub = GreeterGrpc.newBlockingStub(channel).withWaitForReady();
  }

  public void shutdown() throws InterruptedException {
    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
  }

  /** Say hello to server. */
  public void greet(String name) {
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
  }

  /**
   * Greet server. If provided, the first element of {@code args} is the name to use in the
   * greeting.
   */
  public static void main(String[] args) throws Exception {
    String target = "localhost:50051";
    if (args.length > 0) {
      target = args[0];
    }
    final HelloWorldClient client = new HelloWorldClient(target);

    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC client since JVM is shutting down");
        try {
          client.shutdown();
          System.err.println("*** client shut down");
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      }
    });

    try {
      // jvm warm up
      for (int i = 0; i < 1000; i++) {
        client.greet("world");
      }

      long totalLatency = 0;

      // compute average latency
      for (long i = 1; i <= 1000; i++) {
        long currentNano = System.nanoTime();
        client.greet("world");
        long latency = System.nanoTime() - currentNano;
        totalLatency += latency;
        logger.log(
            Level.INFO,
            "Last latency: {0}ns. Average latency: {1}ns", new Object[]{latency, totalLatency/i});
      }

      logger.log(
          Level.INFO,
          "\n\n\n"
              + "============================================================\n"
              + "Average latency for the first 1000 RPCs: {0}ns.\n"
              + "============================================================\n\n\n",
          totalLatency/1000);

      // infinite loop, send 1 rpc every second
      int i = 0;
      while (true) {
        Thread.sleep(1000);
        client.greet("request_" + i);
        i++;
      }
    } finally {
      client.shutdown();
    }
  }
}
