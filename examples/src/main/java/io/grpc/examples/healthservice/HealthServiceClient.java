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

import io.grpc.Channel;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A simple client that requests a greeting from the {@link HelloWorldServer}.
 */
public class HelloWorldClient {
  private static final Logger logger = Logger.getLogger(HelloWorldClient.class.getName());

  private final GreeterGrpc.GreeterBlockingStub blockingStub;

  private final HealthGrpc.HealthStub stub;
  private final HealthGrpc.HealthBlockingStub blockingStub;

  private final HealthCheckRequest healthRequest;

  /** Construct client for accessing HelloWorld server using the existing channel. */
  public HelloWorldClient(Channel channel) {
    stub = HealthGrpc.newStub(channel);
    blockingStub = HealthGrpc.newBlockingStub(channel);

    healthRequest =
        HealthCheckRequest.newBuilder().setService(HealthStatusManager.SERVICE_NAME_ALL_SERVICES)
            .build();

    checkHealth();
  }

  private ServingStatus checkHealth(String prefix) {
    HealthCheckResponse response =
        blockingStub.withDeadlineAfter(5, TimeUnit.SECONDS).check(request);
    logger.info(prefix + ", current health is: " + response.getStatus());
    return response.getStatus();
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
      for (i=0; i < users.length; i++) {
        users[i] = args[i+1];
      }
    }

    // Create a communication channel to the server, known as a Channel. Channels are thread-safe
    // and reusable. It is common to create channels at the beginning of your application and reuse
    // them until the application shuts down.
    //
    // For the example we use plaintext insecure credentials to avoid needing TLS certificates. To
    // use TLS, use TlsChannelCredentials instead.
    ManagedChannel channel = Grpc.newChannelBuilder(target, InsecureChannelCredentials.create())
        .build();
    try {
      // Set a watch
      HelloWorldClient client = new HelloWorldClient(channel);
      client.checkHealth("Before call");
      client.greet(user[0]);
      client.checkHealth("After user " + user[0]);
      for (String user : users) {
        client.greet(user);
      }
      client.checkHealth("After all users");
      Thread.sleep(5000);
      client.checkHealth("After 5 second wait");
    } finally {
      // ManagedChannels use resources like threads and TCP connections. To prevent leaking these
      // resources the channel should be shut down when it will no longer be used. If it may be used
      // again leave it running.
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
