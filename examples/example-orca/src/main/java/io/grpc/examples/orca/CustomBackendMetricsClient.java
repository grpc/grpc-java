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

package io.grpc.examples.orca;

import static io.grpc.examples.orca.CustomBackendMetricsLoadBalancerProvider.EXAMPLE_LOAD_BALANCER;

import io.grpc.Channel;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A simple xDS client that requests a greeting from {@link CustomBackendMetricsServer}.
 * The client channel is configured to use an example load balancer policy
 * {@link CustomBackendMetricsLoadBalancerProvider} which integrates with ORCA metrics reporting.
 */
public class CustomBackendMetricsClient {
  private static final Logger logger = Logger.getLogger(CustomBackendMetricsClient.class.getName());

  private final GreeterGrpc.GreeterBlockingStub blockingStub;

  /** Construct client for accessing HelloWorld server using the existing channel. */
  public CustomBackendMetricsClient(Channel channel) {
    blockingStub = GreeterGrpc.newBlockingStub(channel);
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
   * greeting. The second argument is the target server.
   */
  public static void main(String[] args) throws Exception {
    String user = "orca tester";
    // The example defaults to the same behavior as the hello world example.
    // To receive more periodic OOB metrics reports, use duration argument to a longer value.
    String target = "localhost:50051";
    long timeBeforeShutdown = 1500;
    if (args.length > 0) {
      if ("--help".equals(args[0])) {
        System.err.println("Usage: [name [duration [target]]]");
        System.err.println("");
        System.err.println("  name    The name you wish to be greeted by. Defaults to " + user);
        System.err.println("  duration  The time period in milliseconds that the client application " +
            "wait until shutdown. Defaults to " + timeBeforeShutdown);
        System.err.println("  target  The server to connect to. Defaults to " + target);
        System.exit(1);
      }
      user = args[0];
    }
    if (args.length > 1) {
      timeBeforeShutdown = Long.parseLong(args[1]);
    }

    if (args.length > 2) {
      target = args[2];
    }

    LoadBalancerRegistry.getDefaultRegistry().register(
        new CustomBackendMetricsLoadBalancerProvider());
    ManagedChannel channel = Grpc.newChannelBuilder(target, InsecureChannelCredentials.create())
        .defaultLoadBalancingPolicy(EXAMPLE_LOAD_BALANCER)
        .build();
    try {
      CustomBackendMetricsClient client = new CustomBackendMetricsClient(channel);
      client.greet(user);
      Thread.sleep(timeBeforeShutdown);
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
