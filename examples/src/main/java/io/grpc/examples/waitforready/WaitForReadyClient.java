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

package io.grpc.examples.waitforready;

import io.grpc.Channel;
import io.grpc.Deadline;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.examples.helloworld.HelloReply;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This is an example of using waitForReady.  This is a feature which can be used on a stub
 * which will cause the rpcs to wait (until optional deadline is exceeded) for the
 * server to become available before sending the request.  This is useful for batch workflows
 * where there is no need to fail fast.
 *
 * Below is a simple client that requests a greeting from the
 * {@link io.grpc.examples.helloworld.HelloWorldServer} and defines waitForReady on the stub.
 * To test,
 *   1. run this client without a server running - client rpc should hang
 *   2. start the server - client rpc should complete
 *   3. run this client again - client rpc should complete nearly immediately
 */
public class WaitForReadyClient {
  private static final Logger logger = Logger.getLogger(WaitForReadyClient.class.getName());

  private final GreeterGrpc.GreeterBlockingStub blockingStub;

  /**
   *  Construct client for accessing HelloWorld server using the existing channel which will
   *  wait for the server to become ready, however long that may take, before sending the request.
   */
  public WaitForReadyClient(Channel channel) {
    // This is the only difference from the simple HelloWorld example
    blockingStub = GreeterGrpc.newBlockingStub(channel).withWaitForReady();
  }

  /**
   *  Construct a client for accessing HelloWorld server using the existing channel which will
   *  wait for the server to become ready, up to the specified deadline, before sending the request.
   *  if the deadline is exceeded before the server becomes ready, then the rpc call will fail with
   *  a Status of DEADLINE_EXCEEDED without the request being sent.
   */
  public WaitForReadyClient(Channel channel, Deadline deadline) {
    blockingStub = GreeterGrpc.newBlockingStub(channel).withWaitForReady().withDeadline(deadline);
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
    // Allow passing in the user and target strings as command line arguments
    if (args.length > 0) {
      if ("--help".equals(args[0])) {
        System.err.println("Usage: [name [target]]");
        System.err.println();
        System.err.println("  name    The name you wish to be greeted by. Defaults to " + user);
        System.err.println("  target  The server to connect to. Defaults to " + target);
        System.exit(1);
      }
      user = args[0];
    }
    if (args.length > 1) {
      target = args[1];
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
      // If server isn't running, this will fail after 5 seconds.  Will also fail if the server is
      // running particularly slowly and takes more than 5 minutes to respond.
      WaitForReadyClient clientWithTimeout =
          new WaitForReadyClient(channel, Deadline.after(5, TimeUnit.SECONDS));
      clientWithTimeout.greet(user);

      // This will wait forever until the server becomes ready
      WaitForReadyClient client = new WaitForReadyClient(channel);
      client.greet(user);
    } finally {
      // ManagedChannels use resources like threads and TCP connections. To prevent leaking these
      // resources the channel should be shut down when it will no longer be used. If it may be used
      // again leave it running.
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
