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
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.ChannelCredentials;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerCredentials;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsServerCredentials;
import io.grpc.services.AdminInterface;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.XdsChannelCredentials;
import io.grpc.xds.XdsServerCredentials;
import io.istio.test.Echo.EchoRequest;
import io.istio.test.Echo.EchoResponse;
import io.istio.test.Echo.ForwardEchoRequest;
import io.istio.test.Echo.ForwardEchoResponse;
import io.istio.test.Echo.Header;
import io.istio.test.EchoTestServiceGrpc;
import io.istio.test.EchoTestServiceGrpc.EchoTestServiceFutureStub;
import io.istio.test.EchoTestServiceGrpc.EchoTestServiceImplBase;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

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

  @VisibleForTesting List<Server> servers;

  /**
   * Preprocess args, for:
   * - merging duplicate flags. So "--grpc=8080 --grpc=9090" becomes
   * "--grpc=8080,9090".
   **/
  @VisibleForTesting
  static Map<String, List<String>> preprocessArgs(String[] args) {
    HashMap<String, List<String>> argsMap = new HashMap<>();
    for (String arg : args) {
      List<String> keyValue = Splitter.on('=').limit(2).splitToList(arg);

      if (keyValue.size() == 2) {
        String key = keyValue.get(0);
        String value = keyValue.get(1);
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

  /** Turn ports from a string list to an int list. */
  @VisibleForTesting
  static Set<Integer> getPorts(Map<String, List<String>> args, String flagName) {
    List<String> grpcPorts = args.get(flagName);
    Set<Integer> grpcPortsInt = new HashSet<>(grpcPorts.size());

    for (String port : grpcPorts) {
      port = CharMatcher.is('\"').trimFrom(port);
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
    Set<Integer> grpcPorts = getPorts(processedArgs, "--grpc");
    Set<Integer> xdsPorts = getPorts(processedArgs, "--xds-grpc-server");
    // If an xds port does not exist in gRPC ports set, add it.
    grpcPorts.addAll(xdsPorts);
    // which ports are supposed to use tls
    Set<Integer> tlsPorts = getPorts(processedArgs, "--tls");
    List<String> forwardingAddress = processedArgs.get("--forwarding-address");
    if (forwardingAddress.size() > 1) {
      logger.severe("More than one value for --forwarding-address not allowed");
      System.exit(1);
    }
    if (forwardingAddress.size() == 0) {
      forwardingAddress.add("0.0.0.0:7072");
    }
    List<String> key = processedArgs.get("key");
    List<String> crt = processedArgs.get("crt");

    if (key.size() > 1 || crt.size() > 1) {
      logger.severe("More than one value for --key or --crt not allowed");
      System.exit(1);
    }
    if (key.size() != crt.size()) {
      logger.severe("Both --key or --crt should be present or absent");
      System.exit(1);
    }
    ServerCredentials tlsServerCredentials = null;
    if (key.size() == 1) {
      tlsServerCredentials = TlsServerCredentials.create(new File(crt.get(0)),
          new File(key.get(0)));
    } else if (!tlsPorts.isEmpty()) {
      logger.severe("Both --key or --crt should be present if tls ports used");
      System.exit(1);
    }

    String hostname = determineHostname();
    EchoTestServer echoTestServer = new EchoTestServer();
    echoTestServer.runServers(hostname, grpcPorts, xdsPorts, tlsPorts, forwardingAddress.get(0),
        tlsServerCredentials);
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

  void runServers(String hostname, Collection<Integer> grpcPorts, Collection<Integer> xdsPorts,
      Collection<Integer> tlsPorts, String forwardingAddress,
      ServerCredentials tlsServerCredentials)
      throws IOException {
    ServerServiceDefinition service = ServerInterceptors.intercept(
        new EchoTestServiceImpl(hostname, forwardingAddress), new EchoTestServerInterceptor());
    servers = new ArrayList<>(grpcPorts.size() + 1);
    boolean runAdminServices = Boolean.getBoolean("EXPOSE_GRPC_ADMIN");
    for (int port : grpcPorts) {
      ServerCredentials serverCredentials = InsecureServerCredentials.create();
      String credTypeString = "over plaintext";
      if (xdsPorts.contains(port)) {
        serverCredentials = XdsServerCredentials.create(InsecureServerCredentials.create());
        credTypeString = "over xDS-configured mTLS";
      } else if (tlsPorts.contains(port)) {
        serverCredentials = tlsServerCredentials;
        credTypeString = "over TLS";
      }
      servers.add(runServer(port, service, serverCredentials, credTypeString, runAdminServices));
    }
  }

  static Server runServer(
      int port, ServerServiceDefinition service, ServerCredentials serverCredentials,
      String credTypeString, boolean runAdminServices)
      throws IOException {
    logger.log(Level.INFO, "Listening GRPC ({0}) on {1}", new Object[]{credTypeString, port});
    ServerBuilder<?> builder = Grpc.newServerBuilderForPort(port, serverCredentials)
        .addService(service);
    if (runAdminServices) {
      builder = builder.addServices(AdminInterface.getStandardServices());
    }
    return builder.build().start();
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

      Context ctx = Context.current();
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
    }
  }

  private static class EchoTestServiceImpl extends EchoTestServiceImplBase {

    private final String hostname;
    private final String forwardingAddress;
    private final EchoTestServiceGrpc.EchoTestServiceBlockingStub forwardingStub;

    EchoTestServiceImpl(String hostname, String forwardingAddress) {
      this.hostname = hostname;
      this.forwardingAddress = forwardingAddress;
      this.forwardingStub = EchoTestServiceGrpc.newBlockingStub(
          Grpc.newChannelBuilder(forwardingAddress, InsecureChannelCredentials.create()).build());
    }

    @Override
    public void echo(EchoRequest request,
        io.grpc.stub.StreamObserver<EchoResponse> responseObserver) {

      EchoMessage echoMessage = new EchoMessage();
      echoMessage.writeKeyValue(HOSTNAME, hostname);
      echoMessage.writeKeyValue("Echo", request.getMessage());
      String clientAddress = CLIENT_ADDRESS_CONTEXT_KEY.get();
      if (clientAddress != null) {
        echoMessage.writeKeyValue(IP, clientAddress);
      }
      Map<String, String> requestHeadersCopy = REQUEST_HEADERS_CONTEXT_KEY.get();
      for (Map.Entry<String, String> entry : requestHeadersCopy.entrySet()) {
        echoMessage.writeKeyValueForRequest(REQUEST_HEADER, entry.getKey(), entry.getValue());
      }
      echoMessage.writeKeyValue(STATUS_CODE, "200");
      echoMessage.writeKeyValue(HOST, AUTHORITY_CONTEXT_KEY.get());
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

    private static final class EchoCall {
      EchoResponse response;
      Status status;
    }

    private ForwardEchoResponse buildEchoResponse(ForwardEchoRequest request)
        throws InterruptedException {
      ForwardEchoResponse.Builder forwardEchoResponseBuilder
          = ForwardEchoResponse.newBuilder();
      String rawUrl = request.getUrl();
      List<String> urlParts = Splitter.on(':').limit(2).splitToList(rawUrl);
      if (urlParts.size() < 2) {
        throw new StatusRuntimeException(
            Status.INVALID_ARGUMENT.withDescription("No protocol configured for url " + rawUrl));
      }
      ChannelCredentials creds;
      String target = null;
      if ("grpc".equals(urlParts.get(0))) {
        // We don't really want to test this but the istio test infrastructure needs
        // this to be supported. If we ever decide to add support for this properly,
        // we would need to add support for TLS creds here.
        creds = InsecureChannelCredentials.create();
        target = urlParts.get(1).substring(2);
      } else if ("xds".equals(urlParts.get(0))) {
        creds = XdsChannelCredentials.create(InsecureChannelCredentials.create());
        target = rawUrl;
      } else {
        logger.log(Level.INFO, "Protocol {0} not supported. Forwarding to {1}",
            new String[]{urlParts.get(0), forwardingAddress});
        return forwardingStub.withDeadline(Context.current().getDeadline()).forwardEcho(request);
      }

      ManagedChannelBuilder<?> channelBuilder = Grpc.newChannelBuilder(target, creds);
      ManagedChannel channel = channelBuilder.build();

      List<Header> requestHeaders = request.getHeadersList();
      Metadata metadata = new Metadata();

      for (Header header : requestHeaders) {
        metadata.put(Metadata.Key.of(header.getKey(), Metadata.ASCII_STRING_MARSHALLER),
            header.getValue());
      }

      int count = request.getCount() == 0 ? 1 : request.getCount();
      // Calculate the amount of time to sleep after each call.
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
      final CountDownLatch latch = new CountDownLatch(count);
      EchoCall[] echoCalls = new EchoCall[count];
      for (int i = 0; i < count; i++) {
        Metadata currentMetadata = new Metadata();
        currentMetadata.merge(metadata);
        currentMetadata.put(
            Metadata.Key.of(REQUEST_ID, Metadata.ASCII_STRING_MARSHALLER), "" + i);
        EchoTestServiceGrpc.EchoTestServiceFutureStub stub
            = EchoTestServiceGrpc.newFutureStub(channel).withInterceptors(
                MetadataUtils.newAttachHeadersInterceptor(currentMetadata))
            .withDeadlineAfter(request.getTimeoutMicros(), TimeUnit.MICROSECONDS);

        echoCalls[i] = new EchoCall();
        callEcho(stub, echoRequest, echoCalls[i], latch);
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
      latch.await();
      for (int i = 0; i < count; i++) {
        if (Status.OK.equals(echoCalls[i].status)) {
          forwardEchoResponseBuilder.addOutput(
              buildForwardEchoStruct(i, echoCalls, request.getMessage()));
        } else {
          logger.log(Level.SEVERE, "RPC {0} failed {1}: {2}",
              new Object[]{i, echoCalls[i].status.getCode(), echoCalls[i].status.getDescription()});
          forwardEchoResponseBuilder.clear();
          throw echoCalls[i].status.asRuntimeException();
        }
      }
      return forwardEchoResponseBuilder.build();
    }

    private static String buildForwardEchoStruct(int i, EchoCall[] echoCalls,
        String requestMessage) {
      // The test infrastructure might expect the entire struct instead of
      // just the message.
      StringBuilder sb = new StringBuilder();
      sb.append(String.format("[%d] grpcecho.Echo(%s)\n", i, requestMessage));
      Iterable<String> iterable = Splitter.on('\n').split(echoCalls[i].response.getMessage());
      for (String line : iterable) {
        if (!line.isEmpty()) {
          sb.append(String.format("[%d body] %s\n", i, line));
        }
      }
      return sb.toString();
    }

    private void callEcho(EchoTestServiceFutureStub stub,
        EchoRequest echoRequest, final EchoCall echoCall, CountDownLatch latch) {

      ListenableFuture<EchoResponse> response = stub.echo(echoRequest);
      Futures.addCallback(
          response,
          new FutureCallback<EchoResponse>() {
            @Override
            public void onSuccess(@Nullable EchoResponse result) {
              echoCall.response = result;
              echoCall.status = Status.OK;
              latch.countDown();
            }

            @Override
            public void onFailure(Throwable t) {
              echoCall.status = Status.fromThrowable(t);
              latch.countDown();
            }
          },
          MoreExecutors.directExecutor());
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

    @Override
    public String toString() {
      return sb.toString();
    }
  }
}
