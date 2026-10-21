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

package io.grpc.examples.preserialized;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.stub.ClientCalls;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A client that requests a greeting from a hello-world server, but using a pre-serialized request.
 * This is a performance optimization that can be useful if you read the request from on-disk or a
 * database where it is already serialized, or if you need to send the same complicated message to
 * many servers. The same approach can avoid deserializing responses, to be stored in a database.
 * This adjustment is client-side only; the server is unable to detect the difference, so this
 * client is fully-compatible with the normal {@link HelloWorldServer}.
 */
public class PreSerializedClient {
  private static final Logger logger = Logger.getLogger(PreSerializedClient.class.getName());

  /**
   * Modified sayHello() descriptor with bytes as the request, instead of HelloRequest. By adjusting
   * toBuilder() you can choose which of the request and response are bytes.
   */
  private static final MethodDescriptor<byte[], HelloReply> SAY_HELLO
      = GreeterGrpc.getSayHelloMethod()
      .toBuilder(new ByteArrayMarshaller(), GreeterGrpc.getSayHelloMethod().getResponseMarshaller())
      .build();

  private final Channel channel;

  /** Construct client for accessing hello-world server using the existing channel. */
  public PreSerializedClient(Channel channel) {
    this.channel = channel;
  }

  /** Say hello to server. */
  public void greet(String name) {
    logger.info("Will try to greet " + name + " ...");
    byte[] request = HelloRequest.newBuilder().setName(name).build().toByteArray();
    HelloReply response;
    try {
      // Stubs use ClientCalls to send RPCs. Since the generated stub won't have byte[] in its
      // method signature, this uses ClientCalls directly. It isn't as convenient, but it behaves
      // the same as a normal stub.
      response = ClientCalls.blockingUnaryCall(channel, SAY_HELLO, CallOptions.DEFAULT, request);
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
    String target = "localhost:50051";
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
    }

    ManagedChannel channel = Grpc.newChannelBuilder(target, InsecureChannelCredentials.create())
        .build();
    try {
      PreSerializedClient client = new PreSerializedClient(channel);
      client.greet(user);
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
