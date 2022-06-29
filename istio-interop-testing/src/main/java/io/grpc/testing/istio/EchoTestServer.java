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
import io.grpc.Context;
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
import io.istio.test.Echo.EchoRequest;
import io.istio.test.Echo.EchoResponse;
import io.istio.test.Echo.ForwardEchoRequest;
import io.istio.test.Echo.ForwardEchoResponse;
import io.istio.test.Echo.Header;
import io.istio.test.EchoTestServiceGrpc;
import io.istio.test.EchoTestServiceGrpc.EchoTestServiceBlockingStub;
import io.istio.test.EchoTestServiceGrpc.EchoTestServiceImplBase;
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
public final class EchoTestServer {

  private static final Logger logger = Logger.getLogger(EchoTestServer.class.getName());

  static final Context.Key<String> CLIENT_ADDRESS_CONTEXT_KEY =
      Context.key("io.grpc.testing.istio.ClientAddress");
  static final Context.Key<String> AUTHORITY_CONTEXT_KEY =
      Context.key("io.grpc.testing.istio.Authority");
  static final Context.Key<Map<String,String>> REQUEST_HEADERS_CONTEXT_KEY =
      Context.key("io.grpc.testing.istio.RequestHeaders");

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
        logger.log(Level.SEVERE, "stopServers", e);
        throw e;
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
              EchoResponse echoResponse = (EchoResponse) message;
              String oldMessage = echoResponse.getMessage();

              EchoMessage echoMessage = new EchoMessage();

              for (String key : requestHeaders.keys()) {
                if (!key.endsWith("-bin")) {

                  echoMessage.writeKeyValueForRequest(REQUEST_HEADER, key,
                      requestHeaders.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)));
                }
              }
              // This is not a complete list. May need to add/remove fields later,
              // such as "ServiceVersion", "ServicePort", "URL", "Method", "ResponseHeader",
              // "Cluster", "IstioVersion"
              // Only keep the fields needed for now.
              if (peerAddress instanceof InetSocketAddress) {
                InetSocketAddress inetPeerAddress = (InetSocketAddress) peerAddress;
                echoMessage.writeKeyValue(IP, inetPeerAddress.getAddress().getHostAddress());
              }
              echoMessage.writeKeyValue(STATUS_CODE, "200");
              echoMessage.writeKeyValue(HOST, call.getAuthority());
              echoMessage.writeMessage(oldMessage);
              echoResponse =
                  EchoResponse.newBuilder()
                      .setMessage(echoMessage.toString())
                      .build();
              super.sendMessage((RespT) echoResponse);
            }
          },
          requestHeaders);
      /*Context ctx = Context.current();
      if (peerAddress instanceof InetSocketAddress) {
        InetSocketAddress inetPeerAddress = (InetSocketAddress) peerAddress;
        ctx = ctx.withValue(CLIENT_ADDRESS_CONTEXT_KEY,
            inetPeerAddress.getAddress().getHostAddress());
      }
      ctx = ctx.withValue(AUTHORITY_CONTEXT_KEY, call.getAuthority());
      Map<String, String> requestHeadersCopy = new HashMap<>();
      for (String key : requestHeaders.keys()) {
        if (!key.endsWith("-bin")) {
          requestHeadersCopy.put(key,
              requestHeaders.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)));
        }
      }
      ctx = ctx.withValue(REQUEST_HEADERS_CONTEXT_KEY, requestHeadersCopy);
      return Contexts.interceptCall(
          ctx,
          call,
          requestHeaders,
          next);
       */
    }
  }

  private static class EchoTestServiceImpl extends EchoTestServiceImplBase {

    private final String hostname;

    EchoTestServiceImpl(String hostname) {
      this.hostname = hostname;
    }

    @Override
    public void echo(EchoRequest request,
        io.grpc.stub.StreamObserver<EchoResponse> responseObserver) {

      EchoMessage echoMessage = new EchoMessage();
      echoMessage.writeKeyValue(HOSTNAME, hostname);
      echoMessage.writeKeyValue("Echo", request.getMessage());
      /*String clientAddress = CLIENT_ADDRESS_CONTEXT_KEY.get();
      if (clientAddress != null) {
        echoMessage.writeKeyValue(IP, clientAddress);
      }
      Map<String, String> requestHeadersCopy = REQUEST_HEADERS_CONTEXT_KEY.get();
      for (Map.Entry<String, String> entry : requestHeadersCopy.entrySet()) {
        echoMessage.writeKeyValueForRequest(REQUEST_HEADER, entry.getKey(), entry.getValue());
      }
      echoMessage.writeKeyValue(STATUS_CODE, "200");
      echoMessage.writeKeyValue(HOST, AUTHORITY_CONTEXT_KEY.get()); */
      EchoResponse echoResponse = EchoResponse.newBuilder()
          .setMessage(echoMessage.toString())
          .build();

      responseObserver.onNext(echoResponse);
      responseObserver.onCompleted();
    }

    @Override
    public void forwardEcho(ForwardEchoRequest request,
        StreamObserver<ForwardEchoResponse> responseObserver) {
      try {
        responseObserver.onNext(buildEchoResponse(request));
        responseObserver.onCompleted();
      } catch (InterruptedException e) {
        responseObserver.onError(e);
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        responseObserver.onError(e);
      }
    }

    private ForwardEchoResponse buildEchoResponse(ForwardEchoRequest request)
        throws InterruptedException {
      ForwardEchoResponse.Builder forwardEchoResponseBuilder
          = ForwardEchoResponse.newBuilder();
      String rawUrl = request.getUrl();
      if (!rawUrl.startsWith(GRPC_SCHEME)) {
        throw new StatusRuntimeException(
            Status.UNIMPLEMENTED.withDescription("protocol grpc:// required"));
      }
      rawUrl = rawUrl.substring(GRPC_SCHEME.length());

      // May need to use xds security if urlScheme is "xds"
      ManagedChannelBuilder<?> channelBuilder = Grpc.newChannelBuilder(
          rawUrl, InsecureChannelCredentials.create());
      ManagedChannel channel = channelBuilder.build();

      List<Header> requestHeaders = request.getHeadersList();
      Metadata metadata = new Metadata();

      for (Header header : requestHeaders) {
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
      EchoRequest echoRequest = EchoRequest.newBuilder()
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
        EchoTestServiceBlockingStub stub
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
      return forwardEchoResponseBuilder.build();
    }

    private String callEcho(EchoTestServiceBlockingStub stub,
        EchoRequest echoRequest, int count) {
      try {
        EchoResponse echoResponse = stub.echo(echoRequest);
        return echoResponse.getMessage();
      } catch (Exception e) {
        logger.log(Level.INFO, "RPC failed " + count, e);
      }
      return "";
    }
  }

  private static class EchoMessage {
    private final StringBuilder sb = new StringBuilder();

    void writeKeyValue(String key, String value) {
      sb.append(key).append("=").append(value).append("\n");
    }

    void writeKeyValueForRequest(String requestHeader, String key, String value) {
      if (value != null) {
        writeKeyValue(requestHeader, key + ":" + value);
      }
    }

    void writeMessage(String message) {
      sb.append(message);
    }

    @Override
    public String toString() {
      return sb.toString();
    }
  }
}
