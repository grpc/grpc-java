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
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import java.util.Arrays;
import java.util.Collections;
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
  private final HealthGrpc.HealthBlockingStub healthBlockingStub;

  /** Construct client for accessing HelloWorld server using the existing channel. */
  public HealthServiceClient(Channel channel) {
    greeterBlockingStub = GreeterGrpc.newBlockingStub(channel);
    healthBlockingStub = HealthGrpc.newBlockingStub(channel);
  }

  private ServingStatus checkHealth(String prefix) {
    HealthCheckResponse response =
        healthBlockingStub.check(HealthCheckRequest.getDefaultInstance());
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
    } catch (Exception e) {
      e.printStackTrace();
      return;
    }
    logger.info("Greeting: " + response.getMessage());
  }


  private static void runTest(String target, String[] users, boolean enableHealthChecking)
      throws InterruptedException {
    String healthServiceName;
    if (enableHealthChecking) {
        healthServiceName = ""; // requests the backend's "overall health status"
    } else {
        healthServiceName = null; // disables health checking in generateServiceConfig()
    }
    ManagedChannel channel =
        Grpc.newChannelBuilder(target, InsecureChannelCredentials.create())
        // Enable the round_robin load balancer, with or without health checking
        .defaultServiceConfig(generateServiceConfig(healthServiceName))
        .build();

    // Round Robin, when a healthCheckConfig is present in the service configuration, runs a watch
    // on the health service and when picking an endpoint will consider a transport to a server
    // whose service is not in SERVING state to be unavailable. Since we only have a single server
    // we are connecting to, then the load balancer will return an error without sending the RPC.

    System.out.println("\nDoing test with" + (enableHealthChecking ? "" : "out")
      + " health checking\n");

    try {
      HealthServiceClient client = new HealthServiceClient(channel);
      if (!enableHealthChecking) {
        client.checkHealth("Before call");
      }
      client.greet(users[0]);
      if (!enableHealthChecking) {
        client.checkHealth("After user " + users[0]);
      }

      for (String user : users) {
        client.greet(user);
        Thread.sleep(100); // Since the health update is asynchronous give it time to propagate
      }

      if (!enableHealthChecking) {
        client.checkHealth("After all users");
        Thread.sleep(10000);
        client.checkHealth("After 10 second wait");
      } else {
        Thread.sleep(10000);
      }
      client.greet("Larry");
    } finally {
      // ManagedChannels use resources like threads and TCP connections. To prevent leaking these
      // resources the channel should be shut down when it will no longer be used. If it may be used
      // again leave it running.
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
  private static Map<String, Object> generateServiceConfig(String healthServiceName) {
    Map<String, Object> config = new HashMap<>();
    if (healthServiceName != null) {
      config.put("healthCheckConfig", Collections.singletonMap("serviceName", healthServiceName));
    }
    // There is more than one round_robin implementation. If the client doesn't depend on
    // io.grpc:grpc-services, then the round_robin implementation does not support health watching
    // (to avoid a Protobuf dependency). When the client depends on grpc-services the
    // health-supporting round_robin implementation is used instead.
    config.put("loadBalancingConfig", Arrays.asList(
        Collections.singletonMap("round_robin", Collections.emptyMap())));
    return config;
  }

  /**
   * Uses a server with both a greet service and the health service.
   * If provided, the first element of {@code args} is the name to use in the
   * greeting. The second argument is the target server.
   * This has an example of using the health service directly through the unary call
   * <a href="https://github.com/grpc/grpc-java/blob/master/services/src/main/proto/grpc/health/v1/health.proto">check</a>
   * to get the current health.  It also utilizes the health of the server's greet service
   * indirectly through the round robin load balancer, which uses the streaming rpc
   * <strong>watch</strong> (you can see how it is done in
   * {@link  io.grpc.protobuf.services.HealthCheckingLoadBalancerFactory}).
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
        System.err.println("  name    The names you wish to be greeted by. Defaults to " + Arrays.toString(users));
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

    // Will see failures of rpc's sent while server service is not serving, where the failures come
    // from the server
    runTest(target, users, false);

    // The client will throw an error when sending the rpc to a non-serving service because the
    // round robin load balancer uses the health service's watch rpc.
    runTest(target, users, true);

  }
}
