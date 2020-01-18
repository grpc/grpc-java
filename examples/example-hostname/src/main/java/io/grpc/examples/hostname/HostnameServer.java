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

package io.grpc.examples.hostname;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.services.HealthStatusManager;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * A server that hosts HostnameGreeter, plus infrastructure services like health and reflection.
 *
 * <p>This server is intended to be a general purpose "dummy" server.
 */
public final class HostnameServer {
  public static void main(String[] args) throws IOException, InterruptedException {
    int port = 50051;
    String hostname = null;
    if (args.length >= 1) {
      try {
        port = Integer.parseInt(args[0]);
      } catch (NumberFormatException ex) {
        System.err.println("Usage: [port [hostname]]");
        System.err.println("");
        System.err.println("  port      The listen port. Defaults to " + port);
        System.err.println("  hostname  The name clients will see in greet responses. ");
        System.err.println("            Defaults to the machine's hostname");
        System.exit(1);
      }
    }
    if (args.length >= 2) {
      hostname = args[1];
    }
    HealthStatusManager health = new HealthStatusManager();
    final Server server = ServerBuilder.forPort(port)
        .addService(new HostnameGreeter(hostname))
        .addService(ProtoReflectionService.newInstance())
        .addService(health.getHealthService())
        .build()
        .start();
    System.out.println("Listening on port " + port);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Start graceful shutdown
        server.shutdown();
        try {
          // Wait for RPCs to complete processing
          if (!server.awaitTermination(30, TimeUnit.SECONDS)) {
            // That was plenty of time. Let's cancel the remaining RPCs
            server.shutdownNow();
            // shutdownNow isn't instantaneous, so give a bit of time to clean resources up
            // gracefully. Normally this will be well under a second.
            server.awaitTermination(5, TimeUnit.SECONDS);
          }
        } catch (InterruptedException ex) {
          server.shutdownNow();
        }
      }
    });
    // This would normally be tied to the service's dependencies. For example, if HostnameGreeter
    // used a Channel to contact a required service, then when 'channel.getState() ==
    // TRANSIENT_FAILURE' we'd want to set NOT_SERVING. But HostnameGreeter has no dependencies, so
    // hard-coding SERVING is appropriate.
    health.setStatus("", ServingStatus.SERVING);
    server.awaitTermination();
  }
}
