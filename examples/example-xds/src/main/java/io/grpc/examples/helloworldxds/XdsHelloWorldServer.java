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

import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.services.HealthStatusManager;
import io.grpc.xds.XdsServerBuilder;
import io.grpc.xds.XdsServerCredentials;
import java.util.Arrays;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * An xDS-managed Server for the {@code Greeter} service.
 */
public class XdsHelloWorldServer {
  public static void main(String[] args) throws IOException, InterruptedException {
    int port = 50051;
    String hostname = null;
    ServerCredentials credentials = InsecureServerCredentials.create();
    if (args.length >= 1 && "--xds-creds".equals(args[0])) {
      // The xDS credentials use the security configured by the xDS server when available. When xDS
      // is not used or when xDS does not provide security configuration, the xDS credentials fall
      // back to other credentials (in this case, InsecureServerCredentials).
      credentials = XdsServerCredentials.create(InsecureServerCredentials.create());
      args = Arrays.copyOfRange(args, 1, args.length);
    }
    if (args.length >= 1) {
      try {
        port = Integer.parseInt(args[0]);
      } catch (NumberFormatException ex) {
        System.err.println("Usage: [--xds-creds] [PORT [HOSTNAME]]");
        System.err.println("");
        System.err.println("  --xds-creds  Use credentials provided by xDS. Defaults to insecure");
        System.err.println("");
        System.err.println("  PORT      The listen port. Defaults to " + port);
        System.err.println("  HOSTNAME  The name clients will see in greet responses. ");
        System.err.println("            Defaults to the machine's hostname");
        System.exit(1);
      }
    }
    if (args.length >= 2) {
      hostname = args[1];
    }
    // Since the main server may be using TLS, we start a second server just for plaintext health
    // checks
    int healthPort = port + 1;
    final HealthStatusManager health = new HealthStatusManager();
    final Server server = XdsServerBuilder.forPort(port, credentials)
        .addService(new HostnameGreeter(hostname))
        .addService(ProtoReflectionService.newInstance()) // convenient for command line tools
        .addService(health.getHealthService()) // allow management servers to monitor health
        .build()
        .start();
    final Server healthServer =
        XdsServerBuilder.forPort(healthPort, InsecureServerCredentials.create())
        .addService(health.getHealthService()) // allow management servers to monitor health
        .build()
        .start();
    System.out.println("Listening on port " + port);
    System.out.println("Plaintext health service listening on port " + healthPort);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        health.setStatus("", ServingStatus.NOT_SERVING);
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
          healthServer.shutdownNow();
          healthServer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
          server.shutdownNow();
          healthServer.shutdownNow();
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
