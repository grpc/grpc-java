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

package io.grpc.examples.debug;

import io.grpc.Channel;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.services.AdminInterface;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A client that creates a channelz service and then requests a greeting 50 times.
 */
public class HelloWorldDebuggableClient {

  private static final Logger logger = Logger.getLogger(HelloWorldDebuggableClient.class.getName());
  public static final int NUM_ITERATIONS = 50;

  private final GreeterGrpc.GreeterBlockingStub blockingStub;

  /** Construct client for accessing HelloWorld server using the existing channel. */
  public HelloWorldDebuggableClient(Channel channel) {
    // 'channel' here is a Channel, not a ManagedChannel, so it is not this code's responsibility to
    // shut it down.

    // Passing Channels to code makes code easier to test and makes it easier to reuse Channels.
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
    String user = "world";
    // Access a service running on the local machine on port 50051
    String target = "localhost:50051";
    int debugPort = 51051;
    // Allow passing in the user and target strings as command line arguments
    if (args.length > 0) {
      if ("--help".equals(args[0])) {
        System.err.println("Usage: [name [target]]");
        System.err.println("");
        System.err.println("  name    The name you wish to be greeted by. Defaults to " + user);
        System.err.println("  target  The server to connect to. Defaults to " + target);
        System.exit(1);
      }
      user = args[0];
    }
    if (args.length > 1) {
      target = args[1];
      //
      // Parse port number from target and add 1000 to it for debug port
      String[] split = target.split(":");
      if (split.length == 2) {
        try {
          debugPort = Integer.parseInt(split[1]) + 1000;
        } catch (NumberFormatException e) {
            System.err.println("Invalid port number " + split[1]);
        }
      }
    }

    // Create a communication channel to the server, known as a Channel. Channels are thread-safe
    // and reusable. It is common to create channels at the beginning of your application and reuse
    // them until the application shuts down.
    //
    // For the example we use plaintext insecure credentials to avoid needing TLS certificates. To
    // use TLS, use TlsChannelCredentials instead.
    ManagedChannel channel1 = Grpc.newChannelBuilder(target, InsecureChannelCredentials.create()).build();
    ManagedChannel channel2 = Grpc.newChannelBuilder(target, InsecureChannelCredentials.create()).build();
    Server server = null;
    try {
      // Create a service from which grpcdebug can request debug info
      server = Grpc.newServerBuilderForPort(debugPort, InsecureServerCredentials.create())
          .addService(ProtoReflectionService.newInstance())
          .addServices(AdminInterface.getStandardServices())
          .build()
          .start();

      // Do the client requests
      HelloWorldDebuggableClient client1 = new HelloWorldDebuggableClient(channel1);
      HelloWorldDebuggableClient client2 = new HelloWorldDebuggableClient(channel1);
      HelloWorldDebuggableClient client3 = new HelloWorldDebuggableClient(channel2);
      for (int i=0; i < NUM_ITERATIONS; i++) {
        switch (i % 3) {
          case 0:
            client1.greet(user);
            break;
          case 1:
            client2.greet(user);
            break;
          case 2:
            client3.greet(user);
            break;
        }
      }
      System.out.println("Completed " + NUM_ITERATIONS + "requests, sleeping for 30 seconds to give some time for command line");
      Thread.sleep(30000); // Give some time for running grpcdebug
    } finally {
      // ManagedChannels use resources like threads and TCP connections. To prevent leaking these
      // resources the channel should be shut down when it will no longer be used. If it may be used
      // again leave it running.
      channel1.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      channel2.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);

      if (server != null) {
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }
}
