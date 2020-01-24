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

package io.grpc.testing.integration;

import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.integration.Messages.SimpleRequest;
import io.grpc.testing.integration.Messages.SimpleResponse;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Interop test server that implements the xDS testing service. */
public final class XdsTestServer {
  private static Logger logger = Logger.getLogger(XdsTestServer.class.getName());

  private int port = 8080;
  private String serverId = "java_server";
  private Server server;

  /**
   * The main application allowing this client to be launched from the command line.
   */
  public static void main(String[] args) throws Exception {
    final XdsTestServer server = new XdsTestServer();
    server.parseArgs(args);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread() {
              @Override
              @SuppressWarnings("CatchAndPrintStackTrace")
              public void run() {
                try {
                  System.out.println("Shutting down");
                  server.stop();
                } catch (Exception e) {
                  e.printStackTrace();
                }
              }
            });
    server.start();
    System.out.println("Server started on port " + server.port);
    server.blockUntilShutdown();
  }

  private void parseArgs(String[] args) {
    boolean usage = false;
    for (String arg : args) {
      if (!arg.startsWith("--")) {
        System.err.println("All arguments must start with '--': " + arg);
        usage = true;
        break;
      }
      String[] parts = arg.substring(2).split("=", 2);
      String key = parts[0];
      if ("help".equals(key)) {
        usage = true;
        break;
      }
      if (parts.length != 2) {
        System.err.println("All arguments must be of the form --arg=value");
        usage = true;
        break;
      }
      String value = parts[1];
      if ("port".equals(key)) {
        port = Integer.valueOf(value);
      } else if ("server_id".equals(key)) {
        serverId = value;
      } else {
        System.err.println("Unknown argument: " + key);
        usage = true;
        break;
      }
    }

    if (usage) {
      XdsTestServer s = new XdsTestServer();
      System.err.println(
          "Usage: [ARGS...]"
              + "\n"
              + "\n  --port=INT          listening port for server."
              + "\n                      Default: "
              + s.port
              + "\n  --server_id=STRING  server ID for response."
              + "\n                      Default: "
              + s.serverId);
      System.exit(1);
    }
  }

  private void start() throws Exception {
    server = NettyServerBuilder.forPort(port).addService(new TestServiceImpl()).build().start();
  }

  private void stop() throws Exception {
    server.shutdownNow();
    if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
      System.err.println("Timed out waiting for server shutdown");
    }
  }

  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  private class TestServiceImpl extends TestServiceGrpc.TestServiceImplBase {
    private final String host;

    private TestServiceImpl() {
      try {
        host = InetAddress.getLocalHost().getHostName();
      } catch (UnknownHostException e) {
        logger.log(Level.SEVERE, "Failed to get host", e);
        throw new RuntimeException(e);
      }
    }

    @Override
    public void unaryCall(SimpleRequest req, StreamObserver<SimpleResponse> responseObserver) {
      responseObserver.onNext(
          SimpleResponse.newBuilder().setServerId(serverId).setHostname(host).build());
      responseObserver.onCompleted();
    }
  }
}
