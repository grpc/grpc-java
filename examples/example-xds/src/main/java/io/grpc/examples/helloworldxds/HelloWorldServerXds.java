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

import io.grpc.Server;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.internal.sds.XdsServerBuilder;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An xDS-managed Server for the {@code Greeter} service.
 */
public class HelloWorldServerXds {
  private static final Logger logger = Logger.getLogger(HelloWorldServerXds.class.getName());
  private final int port;
  private final boolean useXdsCreds;
  private final String hostName;
  private Server server;

  public HelloWorldServerXds(int port, String hostName, boolean useXdsCreds) {
    this.port = port;
    this.hostName = hostName;
    this.useXdsCreds = useXdsCreds;
  }

  private void start() throws IOException {
    XdsServerBuilder builder = XdsServerBuilder.forPort(port).addService(new HostnameGreeter(hostName));
    if (useXdsCreds) {
      builder = builder.useXdsSecurityWithPlaintextFallback();
    }
    server = builder.build().start();
    logger.info("Server started, listening on " + port);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread() {
              @Override
              public void run() {
                // Use stderr here since the logger may have been reset by its JVM shutdown hook.
                System.err.println("*** shutting down gRPC server since JVM is shutting down");
                try {
                  HelloWorldServerXds.this.stop();
                } catch (InterruptedException e) {
                  logger.log(Level.SEVERE, "During stop", e);
                }
                System.err.println("*** server shut down");
              }
            });
  }

  private void stop() throws InterruptedException {
    if (server != null) {
      server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
    }
  }

  /** Await termination on the main thread since the grpc library uses daemon threads. */
  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  /** Main launches the server from the command line. */
  public static void main(String[] args) throws IOException, InterruptedException {
    boolean useXdsCreds = false;
    String hostName = null;
    if (args.length < 1 || args.length > 3) {
      System.out.println("USAGE: HelloWorldServerTls port [hostname [--secure]]");
      System.err.println("");
      System.err.println("  port  The port to bind to.");
      System.err.println("  hostname  The name clients will see in greet responses. ");
      System.err.println("            Defaults to the machine's hostname");
      System.out.println(
          "  '--secure'  Indicates using xDS credentials options; otherwise defaults to insecure credentials.");
      System.exit(1);
    }
    if (args.length > 1) {
      hostName = args[1];
      if (args.length == 3) {
        useXdsCreds = args[2].toLowerCase().startsWith("--s");
      }
    }
    final HelloWorldServerXds server =
        new HelloWorldServerXds(Integer.parseInt(args[0]), hostName, useXdsCreds);
    server.start();
    server.blockUntilShutdown();
  }
}
