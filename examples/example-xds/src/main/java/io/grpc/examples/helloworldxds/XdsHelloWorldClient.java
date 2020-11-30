/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.examples.helloworldxds;

import io.grpc.Channel;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.xds.XdsChannelCredentials;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A simple xDS client that requests a greeting from {@code HelloWorldServer} or {@link
 * XdsHelloWorldServer}.
 */
public class XdsHelloWorldClient {
  private static final Logger logger = Logger.getLogger(XdsHelloWorldClient.class.getName());

  private final GreeterGrpc.GreeterBlockingStub blockingStub;

  /** Construct client for accessing HelloWorld server using the existing channel. */
  public XdsHelloWorldClient(Channel channel) {
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
   * greeting. The second argument is the target server. A {@code --xds-creds} flag is also accepted.
   */
  public static void main(String[] args) throws Exception {
    String user = "xds world";
    // The example defaults to the same behavior as the hello world example. To enable xDS, pass an
    // "xds:"-prefixed string as the target.
    String target = "localhost:50051";
    ChannelCredentials credentials = InsecureChannelCredentials.create();
    if (args.length > 0) {
      if ("--help".equals(args[0])) {
        System.out.println("Usage: [--xds-creds] [NAME [TARGET]]");
        System.out.println("");
        System.err.println("  --xds-creds  Use credentials provided by xDS. Defaults to insecure");
        System.out.println("");
        System.err.println("  NAME    The name you wish to be greeted by. Defaults to " + user);
        System.err.println("  TARGET  The server to connect to. Defaults to " + target);
        System.exit(1);
      } else if ("--xds-creds".equals(args[0])) {
        // The xDS credentials use the security configured by the xDS server when available. When
        // xDS is not used or when xDS does not provide security configuration, the xDS credentials
        // fall back to other credentials (in this case, InsecureChannelCredentials).
        credentials = XdsChannelCredentials.create(InsecureChannelCredentials.create());
        args = Arrays.copyOfRange(args, 1, args.length);
      }
    }
    if (args.length > 0) {
      user = args[0];
    }
    if (args.length > 1) {
      target = args[1];
    }

    // This uses the new ChannelCredentials API. Grpc.newChannelBuilder() is the same as
    // ManagedChannelBuilder.forTarget(), except that it is passed credentials. When using this API,
    // you don't use methods like `managedChannelBuilder.usePlaintext()`, as that configuration is
    // provided by the ChannelCredentials.
    ManagedChannel channel = Grpc.newChannelBuilder(target, credentials)
        .build();
    try {
      XdsHelloWorldClient client = new XdsHelloWorldClient(channel);
      client.greet(user);
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
