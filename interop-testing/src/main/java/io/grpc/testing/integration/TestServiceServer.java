/*
 * Copyright 2014 The gRPC Authors
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.BindableService;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCredentials;
import io.grpc.ServerInterceptors;
import io.grpc.TlsServerCredentials;
import io.grpc.alts.AltsServerCredentials;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.services.MetricRecorder;
import io.grpc.testing.TlsTesting;
import io.grpc.xds.orca.OrcaMetricReportingServerInterceptor;
import io.grpc.xds.orca.OrcaServiceImpl;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Server that manages startup/shutdown of a single {@code TestService}. */
public class TestServiceServer {
  /** The main application allowing this server to be launched from the command line. */
  public static void main(String[] args) throws Exception {
    final TestServiceServer server = new TestServiceServer();
    server.parseArgs(args);
    if (server.useTls) {
      System.out.println(
          "\nUsing fake CA for TLS certificate. Test clients should expect host\n"
              + "*.test.google.fr and our test CA. For the Java test client binary, use:\n"
              + "--server_host_override=foo.test.google.fr --use_test_ca=true\n");
    }

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

  private int port = 8080;
  private boolean useTls = true;
  private boolean useAlts = false;

  private ScheduledExecutorService executor;
  private Server server;
  private int localHandshakerPort = -1;
  private Util.AddressType addressType = Util.AddressType.IPV4_IPV6;

  @VisibleForTesting
  void parseArgs(String[] args) {
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
        port = Integer.parseInt(value);
      } else if ("use_tls".equals(key)) {
        useTls = Boolean.parseBoolean(value);
      } else if ("use_alts".equals(key)) {
        useAlts = Boolean.parseBoolean(value);
      } else if ("local_handshaker_port".equals(key)) {
        localHandshakerPort = Integer.parseInt(value);
      } else if ("address_type".equals(key)) {
        addressType = Util.AddressType.valueOf(value.toUpperCase(Locale.ROOT));
      } else if ("grpc_version".equals(key)) {
        if (!"2".equals(value)) {
          System.err.println("Only grpc version 2 is supported");
          usage = true;
          break;
        }
      } else {
        System.err.println("Unknown argument: " + key);
        usage = true;
        break;
      }
    }
    if (useAlts) {
      useTls = false;
    }
    if (usage) {
      TestServiceServer s = new TestServiceServer();
      System.out.println(
          "Usage: [ARGS...]"
              + "\n"
              + "\n  --port=PORT           Port to connect to. Default " + s.port
              + "\n  --use_tls=true|false  Whether to use TLS. Default " + s.useTls
              + "\n  --use_alts=true|false Whether to use ALTS. Enable ALTS will disable TLS."
              + "\n                        Default " + s.useAlts
              + "\n  --local_handshaker_port=PORT"
              + "\n                        Use local ALTS handshaker service on the specified port "
              + "\n                        for testing. Only effective when --use_alts=true."
              + "\n  --address_type=IPV4|IPV6|IPV4_IPV6"
              + "\n                        What type of addresses to listen on. Default IPV4_IPV6"
      );
      System.exit(1);
    }
  }

  @SuppressWarnings("AddressSelection")
  @VisibleForTesting
  void start() throws Exception {
    executor = Executors.newSingleThreadScheduledExecutor();
    ServerCredentials serverCreds;
    if (useAlts) {
      if (localHandshakerPort > -1) {
        serverCreds = AltsServerCredentials.newBuilder()
            .enableUntrustedAltsForTesting()
            .setHandshakerAddressForTesting("localhost:" + localHandshakerPort).build();
      } else {
        serverCreds = AltsServerCredentials.create();
      }
    } else if (useTls) {
      serverCreds = TlsServerCredentials.create(
          TlsTesting.loadCert("server1.pem"), TlsTesting.loadCert("server1.key"));
    } else {
      serverCreds = InsecureServerCredentials.create();
    }
    MetricRecorder metricRecorder = MetricRecorder.newInstance();
    BindableService orcaOobService =
        OrcaServiceImpl.createService(executor, metricRecorder, 1, TimeUnit.SECONDS);

    // Create ServerBuilder with appropriate addresses
    // - IPV4_IPV6: bind to wildcard which covers all addresses on all interfaces of both families
    // - IPV4: bind to v4 address for local hostname + v4 localhost
    // - IPV6: bind to all v6 addresses for local hostname + v6 localhost
    ServerBuilder<?> serverBuilder;
    switch (addressType) {
      case IPV4_IPV6:
        serverBuilder = Grpc.newServerBuilderForPort(port, serverCreds);
        break;
      case IPV4:
        SocketAddress v4Address = Util.getV4Address(port);
        InetSocketAddress localV4Address = new InetSocketAddress("127.0.0.1", port);
        serverBuilder =
            NettyServerBuilder.forAddress(localV4Address, serverCreds);
        if (v4Address != null && !v4Address.equals(localV4Address)) {
          ((NettyServerBuilder) serverBuilder).addListenAddress(v4Address);
        }
        break;
      case IPV6:
        List<SocketAddress> v6Addresses = Util.getV6Addresses(port);
        InetSocketAddress localV6Address = new InetSocketAddress("::1", port);
        serverBuilder =
            NettyServerBuilder.forAddress(localV6Address, serverCreds);
        for (SocketAddress address : v6Addresses) {
          if (!address.equals(localV6Address)) {
            ((NettyServerBuilder) serverBuilder).addListenAddress(address);
          }
        }
        break;
      default:
        throw new AssertionError("Unknown address type: " + addressType);
    }
    server = serverBuilder
        .maxInboundMessageSize(AbstractInteropTest.MAX_MESSAGE_SIZE)
        .addService(
            ServerInterceptors.intercept(
                new TestServiceImpl(executor, metricRecorder), TestServiceImpl.interceptors()))
        .addService(orcaOobService)
        .intercept(OrcaMetricReportingServerInterceptor.create(metricRecorder))
        .build()
        .start();
  }

  @VisibleForTesting
  void stop() throws Exception {
    server.shutdownNow();
    if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
      System.err.println("Timed out waiting for server shutdown");
    }
    MoreExecutors.shutdownAndAwaitTermination(executor, 5, TimeUnit.SECONDS);
  }

  @VisibleForTesting
  int getPort() {
    return server.getPort();
  }

  /** Await termination on the main thread since the grpc library uses daemon threads. */
  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

}
