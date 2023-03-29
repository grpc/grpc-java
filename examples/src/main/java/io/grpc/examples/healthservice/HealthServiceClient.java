/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.examples.healthservice;

import io.grpc.Channel;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A client that requests a greeting from the {@link HelloWorldServer}.
 */
public class HealthServiceClient {
  private static final Logger logger = Logger.getLogger(HealthServiceClient.class.getName());

  private final GreeterGrpc.GreeterBlockingStub greeterBlockingStub;
  private final HealthGrpc.HealthStub healthStub;
  private final HealthGrpc.HealthBlockingStub healthBlockingStub;

  private final HealthCheckRequest healthRequest;

  /** Construct client for accessing HelloWorld server using the existing channel. */
  public HealthServiceClient(Channel channel) {
    greeterBlockingStub = GreeterGrpc.newBlockingStub(channel);
    healthStub = HealthGrpc.newStub(channel);
    healthBlockingStub = HealthGrpc.newBlockingStub(channel);
    healthRequest = HealthCheckRequest.getDefaultInstance();
    LoadBalancerProvider roundRobin = LoadBalancerRegistry.getDefaultRegistry()
        .getProvider("round_robin");

  }

  private ServingStatus checkHealth(String prefix) {
    HealthCheckResponse response =
        healthBlockingStub.check(healthRequest);
    logger.info(prefix + ", current health is: " + response.getStatus());
    return response.getStatus();
  }

  /** Say hello to server. */
  public void greet(String name) {
    logger.info("Will try to greet " + name + " ...");
    HelloRequest request = HelloRequest.newBuilder().setName(name).build();
    HelloReply response;
    try {
      response = greeterBlockingStub.sayHello(request);
    } catch (StatusRuntimeException e) {
      logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
      return;
    }
    logger.info("Greeting: " + response.getMessage());
  }


  private static void runTest(String[] users, ManagedChannel channel)
      throws InterruptedException {
    try {
      // Set a watch
      HealthServiceClient client = new HealthServiceClient(channel);
      client.checkHealth("Before call");
      client.greet(users[0]);
      client.checkHealth("After user " + users[0]);
      for (String user : users) {
        client.greet(user);
        Thread.sleep(100); // Since the health update is asynchronous give it time to propagate
      }
      client.checkHealth("After all users");
      Thread.sleep(10000);
      client.checkHealth("After 10 second wait");
      client.greet("Larry");
    } finally {
      // ManagedChannels use resources like threads and TCP connections. To prevent leaking these
      // resources the channel should be shut down when it will no longer be used. If it may be used
      // again leave it running.
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
  private static Map<String, Object> generateHealthConfig(String serviceName) {
    Map<String, Object> config = new HashMap<>();
    Map<String, Object> serviceMap = new HashMap<>();

    config.put("healthCheckConfig", serviceMap);
    serviceMap.put("serviceName", serviceName);
    return config;
  }

  /**
   * Greet server. If provided, the first element of {@code args} is the name to use in the
   * greeting. The second argument is the target server.  The server should also provide the health
   * service.  This has an example of using the health service directly through the unary call check
   * to get the current health and indirectly through the round robin load balancer, which uses the
   * streaming rpc (see {@link  io.grpc.protobuf.services.HealthCheckingLoadBalancerFactory}).
   */
  public static void main(String[] args) throws Exception {
    System.setProperty("java.util.logging.SimpleFormatter.format",
        "%1$tH:%1$tM:%1$tS %4$s %2$s: %5$s%6$s%n");

    String[] users = {"world", "foo", "I am Grut"};
    // Access a service running on the local machine on port 50051
    String target = "localhost:50051";
    // Allow passing in the user and target strings as command line arguments
    if (args.length > 0) {
      if ("--help".equals(args[0])) {
        System.err.println("Usage: [target [name] [name] ...]");
        System.err.println("");
        System.err.println("  target  The server to connect to. Defaults to " + target);
        System.err.println("  name    The names you wish to be greeted by. Defaults to " + users);
        System.exit(1);
      }
      target = args[0];
    }
    if (args.length > 1) {
      users = new String[args.length-1];
      for (int i=0; i < users.length; i++) {
        users[i] = args[i+1];
      }
    }

    // Will see failures because of server stopping processing
    ManagedChannel channelSimple = Grpc.newChannelBuilder(target, InsecureChannelCredentials.create())
        .build();
    runTest(users, channelSimple);

    // Will block sending requests, so will not see failures since the round robin load balancer
    // Uses the health service's watch rpc
    ManagedChannel channelRR = Grpc.newChannelBuilder(target, InsecureChannelCredentials.create())
        .defaultLoadBalancingPolicy("round_robin")
        .defaultServiceConfig(generateHealthConfig(""))
        .build();
    runTest(users, channelRR);

  }
}
