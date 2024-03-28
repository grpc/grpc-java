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

import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.services.AdminInterface;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.integration.Messages.SimpleRequest;
import io.grpc.testing.integration.Messages.SimpleResponse;
import io.grpc.xds.XdsServerBuilder;
import io.grpc.xds.XdsServerCredentials;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Interop test server that implements the xDS testing service. */
public final class XdsTestServer {
  static final Metadata.Key<String> HOSTNAME_KEY =
      Metadata.Key.of("hostname", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> CALL_BEHAVIOR_MD_KEY =
      Metadata.Key.of("rpc-behavior", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> ATTEMPT_NUM =
      Metadata.Key.of("grpc-previous-rpc-attempts", Metadata.ASCII_STRING_MARSHALLER);
  private static final String CALL_BEHAVIOR_KEEP_OPEN_VALUE = "keep-open";
  private static final String CALL_BEHAVIOR_SLEEP_VALUE = "sleep-";
  private static final String CALL_BEHAVIOR_SUCCEED_ON_RETRY_ATTEMPT_VALUE =
      "succeed-on-retry-attempt-";
  private static final String CALL_BEHAVIOR_ERROR_CODE =
      "error-code-";
  private static final String CALL_BEHAVIOR_HOSTNAME = "hostname=";
  private static final Splitter HEADER_VALUE_SPLITTER = Splitter.on(',')
      .trimResults()
      .omitEmptyStrings();
  private static final Splitter HEADER_HOSTNAME_SPLITTER = Splitter.on(' ');

  private static Logger logger = Logger.getLogger(XdsTestServer.class.getName());

  private int port = 8080;
  private int maintenancePort = 8080;
  private boolean secureMode = false;
  private String serverId = "java_server";
  private HealthStatusManager health;
  private Server server;
  private Server maintenanceServer;
  private String host;

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
      } else if ("maintenance_port".equals(key)) {
        maintenancePort = Integer.valueOf(value);
      } else if ("secure_mode".equals(key)) {
        secureMode = Boolean.parseBoolean(value);
      } else if ("server_id".equals(key)) {
        serverId = value;
      } else {
        System.err.println("Unknown argument: " + key);
        usage = true;
        break;
      }
    }

    if (secureMode && (port == maintenancePort)) {
      System.err.println(
          "port and maintenance_port should be different for secure mode: port="
              + port
              + ", maintenance_port="
              + maintenancePort);
      usage = true;
    }

    if (usage) {
      XdsTestServer s = new XdsTestServer();
      System.err.println(
          "Usage: [ARGS...]"
              + "\n"
              + "\n  --port=INT          listening port for test server."
              + "\n                      Default: "
              + s.port
              + "\n  --maintenance_port=INT      listening port for other servers."
              + "\n                      Default: "
              + s.maintenancePort
              + "\n  --secure_mode=BOOLEAN Use true to enable XdsCredentials."
              + " port and maintenance_port should be different for secure mode."
              + "\n                      Default: "
              + s.secureMode
              + "\n  --server_id=STRING  server ID for response."
              + "\n                      Default: "
              + s.serverId);
      System.exit(1);
    }
  }

  private void start() throws Exception {
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      logger.log(Level.SEVERE, "Failed to get host", e);
      throw new RuntimeException(e);
    }
    health = new HealthStatusManager();
    if (secureMode) {
      maintenanceServer =
          Grpc.newServerBuilderForPort(maintenancePort, InsecureServerCredentials.create())
              .addService(new XdsUpdateHealthServiceImpl(health))
              .addService(health.getHealthService())
              .addService(ProtoReflectionService.newInstance())
              .addServices(AdminInterface.getStandardServices())
              .build();
      maintenanceServer.start();
      server =
          XdsServerBuilder.forPort(
                  port, XdsServerCredentials.create(InsecureServerCredentials.create()))
              .addService(
                  ServerInterceptors.intercept(
                      new TestServiceImpl(serverId, host), new TestInfoInterceptor(host)))
              .build();
      server.start();
    } else {
      server =
          Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
              .addService(
                  ServerInterceptors.intercept(
                      new TestServiceImpl(serverId, host), new TestInfoInterceptor(host)))
              .addService(new XdsUpdateHealthServiceImpl(health))
              .addService(health.getHealthService())
              .addService(ProtoReflectionService.newInstance())
              .addServices(AdminInterface.getStandardServices())
              .build();
      server.start();
      maintenanceServer = null;
    }
    health.setStatus("", ServingStatus.SERVING);
  }

  private void stop() throws Exception {
    server.shutdownNow();
    if (maintenanceServer != null) {
      maintenanceServer.shutdownNow();
    }
    if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
      System.err.println("Timed out waiting for server shutdown");
    }
    if (maintenanceServer != null && !maintenanceServer.awaitTermination(5, TimeUnit.SECONDS)) {
      System.err.println("Timed out waiting for maintenanceServer shutdown");
    }
  }

  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
    if (maintenanceServer != null) {
      maintenanceServer.awaitTermination();
    }
  }

  private static class TestServiceImpl extends TestServiceGrpc.TestServiceImplBase {
    private final String serverId;
    private final String host;

    private TestServiceImpl(String serverId, String host) {
      this.serverId = serverId;
      this.host = host;
    }

    @Override
    public void emptyCall(
        EmptyProtos.Empty req, StreamObserver<EmptyProtos.Empty> responseObserver) {
      responseObserver.onNext(EmptyProtos.Empty.getDefaultInstance());
      responseObserver.onCompleted();
    }

    @Override
    public void unaryCall(SimpleRequest req, StreamObserver<SimpleResponse> responseObserver) {
      responseObserver.onNext(
          SimpleResponse.newBuilder().setServerId(serverId).setHostname(host).build());
      responseObserver.onCompleted();
    }
  }

  private static class XdsUpdateHealthServiceImpl
      extends XdsUpdateHealthServiceGrpc.XdsUpdateHealthServiceImplBase {
    private HealthStatusManager health;

    private XdsUpdateHealthServiceImpl(HealthStatusManager health) {
      this.health = health;
    }

    @Override
    public void setServing(
        EmptyProtos.Empty req, StreamObserver<EmptyProtos.Empty> responseObserver) {
      health.setStatus("", ServingStatus.SERVING);
      responseObserver.onNext(EmptyProtos.Empty.getDefaultInstance());
      responseObserver.onCompleted();
    }

    @Override
    public void setNotServing(
        EmptyProtos.Empty req, StreamObserver<EmptyProtos.Empty> responseObserver) {
      health.setStatus("", ServingStatus.NOT_SERVING);
      responseObserver.onNext(EmptyProtos.Empty.getDefaultInstance());
      responseObserver.onCompleted();
    }
  }

  private static class TestInfoInterceptor implements ServerInterceptor {
    private final String host;

    private TestInfoInterceptor(String host) {
      this.host = host;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call,
        final Metadata requestHeaders,
        ServerCallHandler<ReqT, RespT> next) {
      List<String> callBehaviors = getCallBehaviors(requestHeaders);
      ServerCall<ReqT, RespT> newCall = new SimpleForwardingServerCall<ReqT, RespT>(call) {
        @Override
        public void sendHeaders(Metadata responseHeaders) {
          responseHeaders.put(HOSTNAME_KEY, host);
          super.sendHeaders(responseHeaders);
        }
      };
      ServerCall.Listener<ReqT> noopListener = new ServerCall.Listener<ReqT>() {};

      int attemptNum = 0;
      String attemptNumHeader = requestHeaders.get(ATTEMPT_NUM);
      if (attemptNumHeader != null) {
        try {
          attemptNum = Integer.valueOf(attemptNumHeader);
        } catch (NumberFormatException e) {
          newCall.close(
              Status.INVALID_ARGUMENT.withDescription(
                  "Invalid format for grpc-previous-rpc-attempts header: " + attemptNumHeader),
              new Metadata());
          return noopListener;
        }
      }

      for (String callBehavior : callBehaviors) {
        if (callBehavior.startsWith(CALL_BEHAVIOR_HOSTNAME)) {
          List<String> splitHeader = HEADER_HOSTNAME_SPLITTER.splitToList(callBehavior);
          if (splitHeader.size() > 1) {
            if (!splitHeader.get(0).substring(CALL_BEHAVIOR_HOSTNAME.length()).equals(host)) {
              continue;
            }
            callBehavior = splitHeader.get(1);
          } else {
            newCall.close(
                Status.INVALID_ARGUMENT.withDescription(
                    "Invalid format for rpc-behavior header: " + callBehavior),
                new Metadata()
            );
            return noopListener;
          }
        }

        if (callBehavior.startsWith(CALL_BEHAVIOR_SLEEP_VALUE)) {
          try {
            int timeout = Integer.parseInt(
                callBehavior.substring(CALL_BEHAVIOR_SLEEP_VALUE.length()));
            Thread.sleep(timeout * 1000L);
          } catch (NumberFormatException e) {
            newCall.close(
                Status.INVALID_ARGUMENT.withDescription(
                    "Invalid format for rpc-behavior header: " + callBehavior),
                new Metadata());
            return noopListener;
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            newCall.close(
                Status.ABORTED.withDescription("execution of server interrupted"),
                new Metadata());
            return noopListener;
          }
        }

        if (callBehavior.startsWith(CALL_BEHAVIOR_SUCCEED_ON_RETRY_ATTEMPT_VALUE)) {
          int succeedOnAttemptNum = Integer.MAX_VALUE;
          try {
            succeedOnAttemptNum = Integer.parseInt(
                callBehavior.substring(CALL_BEHAVIOR_SUCCEED_ON_RETRY_ATTEMPT_VALUE.length()));
          } catch (NumberFormatException e) {
            newCall.close(
                Status.INVALID_ARGUMENT.withDescription(
                    "Invalid format for rpc-behavior header: " + callBehavior),
                new Metadata());
            return noopListener;
          }
          if (attemptNum == succeedOnAttemptNum) {
            return next.startCall(newCall, requestHeaders);
          }
        }

        // hang if instructed by rpc-behavior
        if (callBehavior.equals(CALL_BEHAVIOR_KEEP_OPEN_VALUE)) {
          return noopListener;
        }

        if (callBehavior.startsWith(CALL_BEHAVIOR_ERROR_CODE)) {
          try {
            int codeValue = Integer.valueOf(
                callBehavior.substring(CALL_BEHAVIOR_ERROR_CODE.length()));
            newCall.close(
                Status.fromCodeValue(codeValue).withDescription(
                    "Rpc failed as per the rpc-behavior header value:" + callBehaviors),
                new Metadata());
            return noopListener;
          } catch (NumberFormatException e) {
            newCall.close(
                Status.INVALID_ARGUMENT.withDescription(
                    "Invalid format for rpc-behavior header: " + callBehavior),
                new Metadata());
            return noopListener;
          }
        }
      }

      return next.startCall(newCall, requestHeaders);
    }
  }

  private static List<String> getCallBehaviors(Metadata requestHeaders) {
    List<String> callBehaviors = new ArrayList<>();
    Iterable<String> values = requestHeaders.getAll(CALL_BEHAVIOR_MD_KEY);
    if (values == null) {
      return callBehaviors;
    }
    for (String value : values) {
      Iterables.addAll(callBehaviors, HEADER_VALUE_SPLITTER.split(value));
    }
    return callBehaviors;
  }
}
