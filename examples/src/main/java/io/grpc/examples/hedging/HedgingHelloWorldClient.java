/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.examples.hedging;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A client that requests a greeting from the {@link HedgingHelloWorldServer} with a hedging policy.
 */
public class HedgingHelloWorldClient {
  static final String ENV_DISABLE_HEDGING = "DISABLE_HEDGING_IN_HEDGING_EXAMPLE";

  private static final Logger logger = Logger.getLogger(HedgingHelloWorldClient.class.getName());

  private final boolean hedging;
  private final ManagedChannel channel;
  private final GreeterGrpc.GreeterBlockingStub blockingStub;
  private final PriorityBlockingQueue<Long> latencies = new PriorityBlockingQueue<>();
  private final AtomicInteger failedRpcs = new AtomicInteger();

  /** Construct client connecting to HelloWorld server at {@code host:port}. */
  public HedgingHelloWorldClient(String host, int port, boolean hedging) {
    Map<String, ?> hedgingServiceConfig =
      new Gson()
          .fromJson(
              new JsonReader(
                  new InputStreamReader(
                      HedgingHelloWorldClient.class.getResourceAsStream(
                          "hedging_service_config.json"),
                      UTF_8)),
              Map.class);

    ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder.forAddress(host, port)
        // Channels are secure by default (via SSL/TLS). For the example we disable TLS to avoid
        // needing certificates.
        .usePlaintext();
    if (hedging) {
      channelBuilder.defaultServiceConfig(hedgingServiceConfig).enableRetry();
    }
    channel = channelBuilder.build();
    blockingStub = GreeterGrpc.newBlockingStub(channel);
    this.hedging = hedging;
  }

  public void shutdown() throws InterruptedException {
    channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
  }

  /** Say hello to server. */
  public void greet(String name) {
    HelloRequest request = HelloRequest.newBuilder().setName(name).build();
    HelloReply response = null;
    StatusRuntimeException statusRuntimeException = null;
    long startTime = System.nanoTime();
    try {
      response = blockingStub.sayHello(request);
    } catch (StatusRuntimeException e) {
      failedRpcs.incrementAndGet();
      statusRuntimeException = e;
    }
    long latencyMills = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
    latencies.offer(latencyMills);

    if (statusRuntimeException == null) {
      logger.log(
          Level.INFO,
          "Greeting: {0}. Latency: {1}ms",
          new Object[] {response.getMessage(), latencyMills});
    } else {
      logger.log(
          Level.INFO,
          "RPC failed: {0}. Latency: {1}ms",
          new Object[] {statusRuntimeException.getStatus(), latencyMills});
    }
  }

  void printSummary() {
    int rpcCount = latencies.size();
    long latency50 = 0L;
    long latency90 = 0L;
    long latency95 = 0L;
    long latency99 = 0L;
    long latency999 = 0L;
    long latencyMax = 0L;
    for (int i = 0; i < rpcCount; i++) {
      long latency = latencies.poll();
      if (i == rpcCount * 50 / 100 - 1) {
        latency50 = latency;
      }
      if (i == rpcCount * 90 / 100 - 1) {
        latency90 = latency;
      }
      if (i == rpcCount * 95 / 100 - 1) {
        latency95 = latency;
      }
      if (i == rpcCount * 99 / 100 - 1) {
        latency99 = latency;
      }
      if (i == rpcCount * 999 / 1000 - 1) {
        latency999 = latency;
      }
      if (i == rpcCount - 1) {
        latencyMax = latency;
      }
    }

    logger.log(
        Level.INFO,
        "\n\nTotal RPCs sent: {0}. Total RPCs failed: {1}\n"
            + (hedging ? "[Hedging enabled]\n" : "[Hedging disabled]\n")
            + "========================\n"
            + "50% latency: {2}ms\n"
            + "90% latency: {3}ms\n"
            + "95% latency: {4}ms\n"
            + "99% latency: {5}ms\n"
            + "99.9% latency: {6}ms\n"
            + "Max latency: {7}ms\n"
            + "========================\n",
        new Object[]{
            rpcCount, failedRpcs.get(),
            latency50, latency90, latency95, latency99, latency999, latencyMax});

    if (hedging) {
      logger.log(
          Level.INFO,
          "To disable hedging, run the client with environment variable {0}=true.",
          ENV_DISABLE_HEDGING);
    } else {
      logger.log(
          Level.INFO,
          "To enable hedging, unset environment variable {0} and then run the client.",
          ENV_DISABLE_HEDGING);
    }
  }

  public static void main(String[] args) throws Exception {
    boolean hedging = !Boolean.parseBoolean(System.getenv(ENV_DISABLE_HEDGING));
    final HedgingHelloWorldClient client = new HedgingHelloWorldClient("localhost", 50051, hedging);
    ForkJoinPool executor = new ForkJoinPool();

    for (int i = 0; i < 2000; i++) {
      final String userId = "user" + i;
      executor.execute(
          new Runnable() {
            @Override
            public void run() {
              client.greet(userId);
            }
          });
    }

    executor.awaitQuiescence(100, TimeUnit.SECONDS);
    executor.shutdown();
    client.printSummary();
    client.shutdown();
  }
}
