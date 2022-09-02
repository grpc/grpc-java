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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Server;
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
import io.istio.test.EchoTestServiceGrpc.EchoTestServiceImplBase;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link EchoTestServer}.
 */
@RunWith(JUnit4.class)
public class EchoTestServerTest {

  private static final String[] EXPECTED_KEY_SET = {
      "--server_first", "--forwarding-address",
      "--bind_ip", "--istio-version", "--bind_localhost", "--grpc", "--tls",
      "--cluster", "--key", "--tcp", "--crt", "--metrics", "--port", "--version"
  };

  private static final String TEST_ARGS =
      "--metrics=15014 --cluster=\"cluster-0\" --port=\"18080\" --grpc=\"17070\" --port=\"18085\""
          + " --tcp=\"19090\" --port=\"18443\" --tls=18443 --tcp=\"16060\" --server_first=16060"
          + " --tcp=\"19091\" --tcp=\"16061\" --server_first=16061 --port=\"18081\""
          + " --grpc=\"17071\" --port=\"19443\" --tls=19443 --port=\"18082\" --bind_ip=18082"
          + " --port=\"18084\" --bind_localhost=18084 --tcp=\"19092\" --port=\"18083\""
          + " --port=\"8080\" --port=\"3333\" --version=\"v1\" --istio-version=3 --crt=/cert.crt"
          + " --key=/cert.key --forwarding-address=192.168.1.10:7072";

  private static final String TEST_ARGS_PORTS =
      "--metrics=15014 --cluster=\"cluster-0\" --port=\"18080\" --grpc=17070 --port=18085"
          + " --tcp=\"19090\" --port=\"18443\" --tls=18443 --tcp=16060 --server_first=16060"
          + " --tcp=\"19091\" --tcp=\"16061\" --server_first=16061 --port=\"18081\""
          + " --grpc=\"17071\" --port=\"19443\" --tls=\"19443\" --port=\"18082\" --bind_ip=18082"
          + " --port=\"18084\" --bind_localhost=18084 --tcp=\"19092\" --port=\"18083\""
          + " --port=\"8080\" --port=3333 --version=\"v1\" --istio-version=3 --crt=/cert.crt"
          + " --key=/cert.key --xds-grpc-server=12034 --xds-grpc-server=\"34012\"";

  @Test
  public void preprocessArgsTest() {
    String[] splitArgs = TEST_ARGS.split(" ");
    Map<String, List<String>> processedArgs = EchoTestServer.preprocessArgs(splitArgs);

    assertEquals(processedArgs.keySet(), ImmutableSet.copyOf(EXPECTED_KEY_SET));
    assertEquals(processedArgs.get("--server_first"), ImmutableList.of("16060", "16061"));
    assertEquals(processedArgs.get("--bind_ip"), ImmutableList.of("18082"));
    assertEquals(processedArgs.get("--bind_localhost"), ImmutableList.of("18084"));
    assertEquals(processedArgs.get("--grpc"), ImmutableList.of("\"17070\"", "\"17071\""));
    assertEquals(processedArgs.get("--tls"), ImmutableList.of("18443", "19443"));
    assertEquals(processedArgs.get("--cluster"), ImmutableList.of("\"cluster-0\""));
    assertEquals(processedArgs.get("--key"), ImmutableList.of("/cert.key"));
    assertEquals(processedArgs.get("--tcp"), ImmutableList.of("\"19090\"", "\"16060\"",
        "\"19091\"","\"16061\"","\"19092\""));
    assertEquals(processedArgs.get("--istio-version"), ImmutableList.of("3"));
    assertEquals(processedArgs.get("--crt"), ImmutableList.of("/cert.crt"));
    assertEquals(processedArgs.get("--metrics"), ImmutableList.of("15014"));
    assertEquals(ImmutableList.of("192.168.1.10:7072"), processedArgs.get("--forwarding-address"));
    assertEquals(
        processedArgs.get("--port"),
        ImmutableList.of(
            "\"18080\"",
            "\"18085\"",
            "\"18443\"",
            "\"18081\"",
            "\"19443\"",
            "\"18082\"",
            "\"18084\"",
            "\"18083\"",
            "\"8080\"",
            "\"3333\""));
  }

  @Test
  public void preprocessArgsPortsTest() {
    String[] splitArgs = TEST_ARGS_PORTS.split(" ");
    Map<String, List<String>> processedArgs = EchoTestServer.preprocessArgs(splitArgs);

    Set<Integer> ports = EchoTestServer.getPorts(processedArgs, "--port");
    assertThat(ports).containsExactly(18080, 8080, 18081, 18082, 19443, 18083, 18084, 18085,
        3333, 18443);
    ports = EchoTestServer.getPorts(processedArgs, "--grpc");
    assertThat(ports).containsExactly(17070, 17071);
    ports = EchoTestServer.getPorts(processedArgs, "--tls");
    assertThat(ports).containsExactly(18443, 19443);
    ports = EchoTestServer.getPorts(processedArgs, "--xds-grpc-server");
    assertThat(ports).containsExactly(34012, 12034);
  }


  @Test
  public void echoTest() throws IOException, InterruptedException {
    EchoTestServer echoTestServer = new EchoTestServer();

    echoTestServer.runServers(
        "test-host",
        ImmutableList.of(0, 0),
        ImmutableList.of(),
        ImmutableList.of(),
        "0.0.0.0:7072",
        null);
    assertEquals(2, echoTestServer.servers.size());
    int port = echoTestServer.servers.get(0).getPort();
    assertNotEquals(0, port);
    assertNotEquals(0, echoTestServer.servers.get(1).getPort());

    ManagedChannelBuilder<?> channelBuilder =
        Grpc.newChannelBuilderForAddress("localhost", port, InsecureChannelCredentials.create());
    ManagedChannel channel = channelBuilder.build();

    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("header1", Metadata.ASCII_STRING_MARSHALLER), "value1");
    metadata.put(Metadata.Key.of("header2", Metadata.ASCII_STRING_MARSHALLER), "value2");

    EchoTestServiceGrpc.EchoTestServiceBlockingStub stub =
        EchoTestServiceGrpc.newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));

    EchoRequest echoRequest = EchoRequest.newBuilder()
        .setMessage("test-message1")
        .build();
    EchoResponse echoResponse = stub.echo(echoRequest);
    String echoMessage = echoResponse.getMessage();
    Set<String> lines = ImmutableSet.copyOf(echoMessage.split("\n"));

    assertThat(lines).contains("RequestHeader=header1:value1");
    assertThat(lines).contains("RequestHeader=header2:value2");
    assertThat(lines).contains("Echo=test-message1");
    assertThat(lines).contains("Hostname=test-host");
    assertThat(lines).contains("Host=localhost:" + port);
    assertThat(lines).contains("StatusCode=200");

    echoTestServer.stopServers();
    echoTestServer.blockUntilShutdown();
  }

  static final int COUNT_OF_REQUESTS_TO_FORWARD = 60;

  @Test
  public void forwardEchoTest() throws IOException, InterruptedException {
    EchoTestServer echoTestServer = new EchoTestServer();

    echoTestServer.runServers(
        "test-host",
        ImmutableList.of(0, 0),
        ImmutableList.of(),
        ImmutableList.of(),
        "0.0.0.0:7072",
        null);
    assertEquals(2, echoTestServer.servers.size());
    int port1 = echoTestServer.servers.get(0).getPort();
    int port2 = echoTestServer.servers.get(1).getPort();

    ManagedChannelBuilder<?> channelBuilder =
        Grpc.newChannelBuilderForAddress("localhost", port1, InsecureChannelCredentials.create());
    ManagedChannel channel = channelBuilder.build();

    ForwardEchoRequest forwardEchoRequest =
        ForwardEchoRequest.newBuilder()
            .setCount(COUNT_OF_REQUESTS_TO_FORWARD)
            .setQps(100)
            .setTimeoutMicros(5000_000L) // 5000 millis
            .setUrl("grpc://localhost:" + port2)
            .addHeaders(
                Header.newBuilder().setKey("test-key1").setValue("test-value1").build())
            .addHeaders(
                Header.newBuilder().setKey("test-key2").setValue("test-value2").build())
            .setMessage("forward-echo-test-message")
            .build();

    EchoTestServiceGrpc.EchoTestServiceBlockingStub stub =
        EchoTestServiceGrpc.newBlockingStub(channel);

    Instant start = Instant.now();
    ForwardEchoResponse forwardEchoResponse = stub.forwardEcho(forwardEchoRequest);
    Instant end = Instant.now();
    List<String> outputs = forwardEchoResponse.getOutputList();
    assertEquals(COUNT_OF_REQUESTS_TO_FORWARD, outputs.size());
    for (int i = 0; i < COUNT_OF_REQUESTS_TO_FORWARD; i++) {
      validateOutput(outputs.get(i), i);
    }
    long duration = Duration.between(start, end).toMillis();
    assertThat(duration).isAtLeast(COUNT_OF_REQUESTS_TO_FORWARD * 10L);
    echoTestServer.stopServers();
    echoTestServer.blockUntilShutdown();
  }

  private static void validateOutput(String output, int i) {
    List<String> content = Splitter.on('\n').splitToList(output);
    assertThat(content.size()).isAtLeast(7); // see echo implementation
    assertThat(content.get(0))
        .isEqualTo(String.format("[%d] grpcecho.Echo(forward-echo-test-message)", i));
    String prefix = "[" + i + " body] ";
    assertThat(content).contains(prefix + "RequestHeader=x-request-id:" + i);
    assertThat(content).contains(prefix + "RequestHeader=test-key1:test-value1");
    assertThat(content).contains(prefix + "RequestHeader=test-key2:test-value2");
    assertThat(content).contains(prefix + "Hostname=test-host");
    assertThat(content).contains(prefix + "StatusCode=200");
  }

  @Test
  public void nonGrpcForwardEchoTest() throws IOException, InterruptedException {
    ForwardServiceForNonGrpcImpl forwardServiceForNonGrpc = new ForwardServiceForNonGrpcImpl();
    forwardServiceForNonGrpc.receivedRequests = new ArrayList<>();
    forwardServiceForNonGrpc.responsesToReturn = new ArrayList<>();
    Server nonGrpcEchoServer =
        EchoTestServer.runServer(
            0, forwardServiceForNonGrpc.bindService(), InsecureServerCredentials.create(),
                "", false);
    int nonGrpcEchoServerPort = nonGrpcEchoServer.getPort();

    EchoTestServer echoTestServer = new EchoTestServer();

    echoTestServer.runServers(
        "test-host",
        ImmutableList.of(0),
        ImmutableList.of(),
        ImmutableList.of(),
        "0.0.0.0:" + nonGrpcEchoServerPort,
        null);
    assertEquals(1, echoTestServer.servers.size());
    int port1 = echoTestServer.servers.get(0).getPort();

    ManagedChannelBuilder<?> channelBuilder =
        Grpc.newChannelBuilderForAddress("localhost", port1, InsecureChannelCredentials.create());
    ManagedChannel channel = channelBuilder.build();

    EchoTestServiceGrpc.EchoTestServiceBlockingStub stub =
        EchoTestServiceGrpc.newBlockingStub(channel);

    forwardServiceForNonGrpc.responsesToReturn.add(
        ForwardEchoResponse.newBuilder().addOutput("line 1").addOutput("line 2").build());

    ForwardEchoRequest forwardEchoRequest =
        ForwardEchoRequest.newBuilder()
            .setCount(COUNT_OF_REQUESTS_TO_FORWARD)
            .setQps(100)
            .setTimeoutMicros(2000_000L) // 2000 millis
            .setUrl("http://www.example.com") // non grpc protocol
            .addHeaders(
                Header.newBuilder().setKey("test-key1").setValue("test-value1").build())
            .addHeaders(
                Header.newBuilder().setKey("test-key2").setValue("test-value2").build())
            .setMessage("non-grpc-forward-echo-test-message1")
            .build();

    ForwardEchoResponse forwardEchoResponse = stub.forwardEcho(forwardEchoRequest);
    List<String> outputs = forwardEchoResponse.getOutputList();
    assertEquals(2, outputs.size());
    assertThat(outputs.get(0)).isEqualTo("line 1");
    assertThat(outputs.get(1)).isEqualTo("line 2");

    assertThat(forwardServiceForNonGrpc.receivedRequests).hasSize(1);
    ForwardEchoRequest receivedRequest = forwardServiceForNonGrpc.receivedRequests.remove(0);
    assertThat(receivedRequest.getUrl()).isEqualTo("http://www.example.com");
    assertThat(receivedRequest.getMessage()).isEqualTo("non-grpc-forward-echo-test-message1");
    assertThat(receivedRequest.getCount()).isEqualTo(COUNT_OF_REQUESTS_TO_FORWARD);
    assertThat(receivedRequest.getQps()).isEqualTo(100);

    forwardServiceForNonGrpc.responsesToReturn.add(
        Status.UNIMPLEMENTED.asRuntimeException());
    forwardEchoRequest =
        ForwardEchoRequest.newBuilder()
            .setCount(1)
            .setQps(100)
            .setTimeoutMicros(2000_000L) // 2000 millis
            .setUrl("redis://192.168.1.1") // non grpc protocol
            .addHeaders(
                Header.newBuilder().setKey("test-key1").setValue("test-value1").build())
            .setMessage("non-grpc-forward-echo-test-message2")
            .build();

    try {
      ForwardEchoResponse unused = stub.forwardEcho(forwardEchoRequest);
      fail("exception expected");
    } catch (StatusRuntimeException e) {
      assertThat(e.getStatus()).isEqualTo(Status.UNIMPLEMENTED);
    }

    assertThat(forwardServiceForNonGrpc.receivedRequests).hasSize(1);
    receivedRequest = forwardServiceForNonGrpc.receivedRequests.remove(0);
    assertThat(receivedRequest.getUrl()).isEqualTo("redis://192.168.1.1");
    assertThat(receivedRequest.getMessage()).isEqualTo("non-grpc-forward-echo-test-message2");
    assertThat(receivedRequest.getCount()).isEqualTo(1);

    forwardServiceForNonGrpc.responsesToReturn.add(
        ForwardEchoResponse.newBuilder().addOutput("line 3").build());

    forwardEchoRequest =
        ForwardEchoRequest.newBuilder()
            .setCount(1)
            .setQps(100)
            .setTimeoutMicros(2000_000L) // 2000 millis
            .setUrl("http2://192.168.1.1") // non grpc protocol
            .addHeaders(
                Header.newBuilder().setKey("test-key3").setValue("test-value3").build())
            .setMessage("non-grpc-forward-echo-test-message3")
            .build();
    forwardEchoResponse = stub.forwardEcho(forwardEchoRequest);
    outputs = forwardEchoResponse.getOutputList();
    assertEquals(1, outputs.size());
    assertThat(outputs.get(0)).isEqualTo("line 3");

    assertThat(forwardServiceForNonGrpc.receivedRequests).hasSize(1);
    receivedRequest = forwardServiceForNonGrpc.receivedRequests.remove(0);
    assertThat(receivedRequest.getUrl()).isEqualTo("http2://192.168.1.1");
    assertThat(receivedRequest.getMessage()).isEqualTo("non-grpc-forward-echo-test-message3");
    List<Header> headers = receivedRequest.getHeadersList();
    assertThat(headers).hasSize(1);
    assertThat(headers.get(0).getKey()).isEqualTo("test-key3");
    assertThat(headers.get(0).getValue()).isEqualTo("test-value3");

    echoTestServer.stopServers();
    echoTestServer.blockUntilShutdown();
    nonGrpcEchoServer.shutdown();
    nonGrpcEchoServer.awaitTermination(5, TimeUnit.SECONDS);
  }

  /**
   * Emulate the Go Echo server that receives the non-grpc protocol requests.
   */
  private static class ForwardServiceForNonGrpcImpl extends EchoTestServiceImplBase {

    List<ForwardEchoRequest> receivedRequests;
    List<Object> responsesToReturn;

    @Override
    public void forwardEcho(ForwardEchoRequest request,
        StreamObserver<ForwardEchoResponse> responseObserver) {
      receivedRequests.add(request);
      Object response = responsesToReturn.remove(0);
      if (response instanceof Throwable) {
        responseObserver.onError((Throwable) response);
      } else if (response instanceof ForwardEchoResponse) {
        responseObserver.onNext((ForwardEchoResponse) response);
        responseObserver.onCompleted();
      }
      responseObserver.onError(new IllegalArgumentException("Unknown type in responsesToReturn"));
    }
  }
}
