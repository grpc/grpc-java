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

import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.xds.XdsChannelCredentials;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLException;

/**
 * A simple xDS client that requests a greeting from the {@link HelloWorldServerXds}.
 */
public class HelloWorldClientXds {
  private static final Logger logger = Logger.getLogger(HelloWorldClientXds.class.getName());
  private final ManagedChannel channel;
  private final GreeterGrpc.GreeterBlockingStub blockingStub;

  /** Construct client connecting to HelloWorld server at {@code host:port}. */
  public HelloWorldClientXds(String target, boolean useXdsCreds) throws SSLException {
    this.channel =
        Grpc.newChannelBuilder(
                target,
                useXdsCreds
                    ? XdsChannelCredentials.create(InsecureChannelCredentials.create())
                    : InsecureChannelCredentials.create())
            .build();
    blockingStub = GreeterGrpc.newBlockingStub(this.channel);
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
   * Greet server. If provided, the second element of {@code args} is the name to use in the
   * greeting.
   */
  public static void main(String[] args) throws Exception {
    String user = "xds-client";
    boolean useXdsCreds = false;
    if (args.length < 1 || args.length > 3) {
      System.out.println("USAGE: HelloWorldClientXds target [name [--secure]]\n");
      System.err.println("  target  The xds target to connect to using the 'xds:' target scheme.");
      System.err.println("  name    The name you wish to include in the greeting request. Defaults to " + user);
      System.err.println(
          "  '--secure'     Indicates using xDS credentials otherwise defaults to insecure.");
      System.exit(1);
    }
    if (args.length > 1) {
      user = args[1];
      if (args.length == 3) {
        useXdsCreds = args[2].toLowerCase().startsWith("--s");
      }
    }
    HelloWorldClientXds client = new HelloWorldClientXds(args[0], useXdsCreds);
    try {
      client.greet(user);
    } finally {
      client.shutdown();
    }
  }
}
