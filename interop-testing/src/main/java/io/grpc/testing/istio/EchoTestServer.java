/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.testing.istio;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.grpc.ForwardingServerCall;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.istio.EchoTestServiceGrpc.EchoTestServiceImplBase;
import io.grpc.testing.istio.Istio.ForwardEchoRequest;
import io.grpc.testing.istio.Istio.ForwardEchoResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class implements the Istio echo server functionality similar to
 * https://github.com/istio/istio/blob/master/pkg/test/echo/server/endpoint/grpc.go .
 * Please see Istio framework docs https://github.com/istio/istio/wiki/Istio-Test-Framework .
 */
public class EchoTestServer {

  private static final Logger logger = Logger.getLogger(EchoTestServer.class.getName());

  private static final String REQUEST_ID = "x-request-id";
  private static final String STATUS_CODE = "StatusCode";
  private static final String HOST = "Host";
  private static final String HOSTNAME = "Hostname";
  private static final String REQUEST_HEADER = "RequestHeader";
  private static final String IP = "IP";
  public static final String GRPC_SCHEME = "grpc://";

  @VisibleForTesting List<Server> servers;

  /**
   * Preprocess args, for two things:
   * 1. merge duplicate flags. So "--grpc=8080 --grpc=9090" becomes
   * "--grpc=8080,9090".
   * 2. replace '-' to '_'. So "--istio-version=123" becomes
   * "--istio_version=123" (so exclude the leading "--").
   **/
  @VisibleForTesting
  static Map<String, List<String>> preprocessArgs(String[] args) {
    HashMap<String, List<String>> argsMap = new HashMap<>();
    for (String arg : args) {
      String[] keyValue = arg.split("=", 2);

      if (keyValue.length == 2) {
        String key = keyValue[0];
        String value = keyValue[1];

        key = key.substring(0, 2) + key.substring(2).replace('-', '_');
        List<String> oldValue = argsMap.get(key);
        if (oldValue == null) {
          oldValue = new ArrayList<>();
        }
        oldValue.add(value);
        argsMap.put(key, oldValue);
      }
    }
    return ImmutableMap.<String, List<String>>builder().putAll(argsMap).build();
  }

  /** Turn gRPC ports from a string list to an int list. */
  @VisibleForTesting
  static List<Integer> getGrpcPorts(Map<String, List<String>> args) {
    List<String> grpcPorts = args.get("--grpc");
    List<Integer> grpcPortsInt = new ArrayList<>(grpcPorts.size());

    for (String port : grpcPorts) {
      grpcPortsInt.add(Integer.parseInt(port));
    }
    return grpcPortsInt;
  }

  private static String determineHostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (IOException ex) {
      logger.log(Level.INFO, "Failed to determine hostname. Will generate one", ex);
    }
    // let's make an identifier for ourselves.
    return "generated-" + new Random().nextInt();
  }

  /**
   * The main application allowing this program to be launched from the command line.
   */
  public static void main(String[] args) throws Exception {
    Map<String, List<String>> processedArgs = preprocessArgs(args);
    List<Integer> grpcPorts = getGrpcPorts(processedArgs);

    String hostname = determineHostname();
    EchoTestServer echoTestServer = new EchoTestServer();
    echoTestServer.runServers(grpcPorts, hostname);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        System.out.println("Shutting down");
        echoTestServer.stopServers();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }));
    echoTestServer.blockUntilShutdown();
  }

  void runServers(List<Integer> grpcPorts, String hostname) throws IOException {
    ServerServiceDefinition service = ServerInterceptors.intercept(
        new EchoTestServiceImpl(hostname), new EchoTestServerInterceptor());
    servers = new ArrayList<>(grpcPorts.size() + 1);
    for (int port : grpcPorts) {
      runServer(port, service);
    }
  }

  void runServer(int port, ServerServiceDefinition service) throws IOException {
    logger.log(Level.INFO, "Listening GRPC on " + port);
    servers.add(Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
        .addService(service)
        .build().start());
  }

  void stopServers() {
    for (Server server : servers) {
      server.shutdownNow();
    }
  }

  void blockUntilShutdown() throws InterruptedException {
    for (Server server : servers) {
      if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
        System.err.println("Timed out waiting for server shutdown");
      }
    }
  }

  private static class EchoTestServerInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
        final Metadata requestHeaders, ServerCallHandler<ReqT, RespT> next) {
      final String methodName = call.getMethodDescriptor().getBareMethodName();

      // we need this processing only for Echo
      if (!"Echo".equals(methodName)) {
        return next.startCall(call, requestHeaders);
      }

      final SocketAddress peerAddress = call.getAttributes()
          .get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);

      return next.startCall(
          new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {

            @SuppressWarnings("unchecked")
            @Override
            public void sendMessage(RespT message) {
              io.grpc.testing.istio.Istio.EchoResponse echoResponse =
                  (io.grpc.testing.istio.Istio.EchoResponse) message;
              String oldMessage = echoResponse.getMessage();

              StringBuilder sb = new StringBuilder();

              for (String key : requestHeaders.keys()) {
                if (!key.endsWith("-bin")) {

                  String value =
                      requestHeaders.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
                  if (value != null) {
                    sb.append(REQUEST_HEADER)
                        .append("=")
                        .append(key)
                        .append(":")
                        .append(value)
                        .append("\n");
                  }
                }
              }
              // This is not a complete list. May need to add/remove fields later,
              // such as "ServiceVersion", "ServicePort", "URL", "Method", "ResponseHeader",
              // "Cluster", "IstioVersion"
              // Only keep the fields needed for now.
              if (peerAddress instanceof InetSocketAddress) {
                InetSocketAddress inetPeerAddress = (InetSocketAddress) peerAddress;
                sb.append(IP)
                    .append("=")
                    .append(inetPeerAddress.getAddress().getHostAddress())
                    .append("\n");
              }
              sb.append(STATUS_CODE + "=200\n");
              sb.append(HOST + "=" + call.getAuthority() + "\n");
              sb.append(oldMessage);
              echoResponse =
                  io.grpc.testing.istio.Istio.EchoResponse.newBuilder()
                      .setMessage(sb.toString())
                      .build();
              super.sendMessage((RespT) echoResponse);
            }
          },
          requestHeaders);
    }
  }

  private static class EchoTestServiceImpl extends EchoTestServiceImplBase {

    private final String hostname;

    EchoTestServiceImpl(String hostname) {
      this.hostname = hostname;
    }

    @Override
    public void echo(io.grpc.testing.istio.Istio.EchoRequest request,
        io.grpc.stub.StreamObserver<io.grpc.testing.istio.Istio.EchoResponse> responseObserver) {

      StringBuilder sb = new StringBuilder();
      sb.append(HOSTNAME)
          .append("=")
          .append(hostname)
          .append("\nEcho=")
          .append(request.getMessage())
          .append("\n");
      io.grpc.testing.istio.Istio.EchoResponse echoResponse
          = io.grpc.testing.istio.Istio.EchoResponse.newBuilder()
          .setMessage(sb.toString())
          .build();

      responseObserver.onNext(echoResponse);
      responseObserver.onCompleted();
    }

    @Override
    public void forwardEcho(ForwardEchoRequest request,
        StreamObserver<ForwardEchoResponse> responseObserver) {
      Istio.ForwardEchoResponse.Builder forwardEchoResponseBuilder
          = io.grpc.testing.istio.Istio.ForwardEchoResponse.newBuilder();
      try {
        String rawUrl = request.getUrl();
        if (!rawUrl.startsWith(GRPC_SCHEME)) {
          responseObserver.onError(
              new StatusRuntimeException(
                  Status.UNIMPLEMENTED.withDescription("protocol grpc:// required")));
          return;
        }
        rawUrl = rawUrl.substring(GRPC_SCHEME.length());

        // May need to use xds security if urlScheme is "xds"
        ManagedChannelBuilder<?> channelBuilder = Grpc.newChannelBuilder(
            rawUrl, InsecureChannelCredentials.create());
        ManagedChannel channel = channelBuilder.build();

        List<Istio.Header> requestHeaders = request.getHeadersList();
        Metadata metadata = new Metadata();

        for (Istio.Header header : requestHeaders) {
          metadata.put(Metadata.Key.of(header.getKey(), Metadata.ASCII_STRING_MARSHALLER),
              header.getValue());
        }

        int count = request.getCount() == 0 ? 1 : request.getCount();
        Duration durationPerQuery = Duration.ZERO;
        if (request.getQps() > 0) {
          durationPerQuery = Duration.ofNanos(
              Duration.ofSeconds(1).toNanos() / request.getQps());
        }
        logger.info("qps=" + request.getQps());
        logger.info("durationPerQuery=" + durationPerQuery);
        io.grpc.testing.istio.Istio.EchoRequest echoRequest
            = io.grpc.testing.istio.Istio.EchoRequest.newBuilder()
            .setMessage(request.getMessage())
            .build();
        Instant start = Instant.now();
        logger.info("starting instant=" + start);
        Duration expected = Duration.ZERO;
        for (int i = 0; i < count; i++) {
          Metadata currentMetadata = new Metadata();
          currentMetadata.merge(metadata);
          currentMetadata.put(
              Metadata.Key.of(REQUEST_ID, Metadata.ASCII_STRING_MARSHALLER), "" + i);
          EchoTestServiceGrpc.EchoTestServiceBlockingStub stub
              = EchoTestServiceGrpc.newBlockingStub(channel).withInterceptors(
                  MetadataUtils.newAttachHeadersInterceptor(currentMetadata))
              .withDeadlineAfter(request.getTimeoutMicros(), TimeUnit.MICROSECONDS);
          String response = callEcho(stub, echoRequest, i);
          forwardEchoResponseBuilder.addOutput(response);
          Instant current = Instant.now();
          logger.info("after rpc instant=" + current);
          Duration elapsed = Duration.between(start, current);
          expected = expected.plus(durationPerQuery);
          Duration timeLeft = expected.minus(elapsed);
          logger.info("elapsed=" + elapsed + ", expected=" + expected + ", timeLeft=" + timeLeft);
          if (!timeLeft.isNegative()) {
            logger.info("sleeping for ms =" + timeLeft);
            Thread.sleep(timeLeft.toMillis());
          }
        }
        responseObserver.onNext(forwardEchoResponseBuilder.build());
        responseObserver.onCompleted();
      } catch (Exception e) {
        responseObserver.onError(e);
      }
    }

    private String callEcho(EchoTestServiceGrpc.EchoTestServiceBlockingStub stub,
        io.grpc.testing.istio.Istio.EchoRequest echoRequest, int count) {
      try {
        Istio.EchoResponse echoResponse = stub.echo(echoRequest);
        return echoResponse.getMessage();
      } catch (Exception e) {
        logger.log(Level.INFO, "RPC failed " + count, e);
      }
      return "";
    }
  }
}
