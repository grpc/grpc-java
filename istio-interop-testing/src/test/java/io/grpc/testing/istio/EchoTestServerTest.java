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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.istio.test.Echo.EchoRequest;
import io.istio.test.Echo.EchoResponse;
import io.istio.test.Echo.ForwardEchoRequest;
import io.istio.test.Echo.ForwardEchoResponse;
import io.istio.test.Echo.Header;
import io.istio.test.EchoTestServiceGrpc;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link EchoTestServer}.
 */
@RunWith(JUnit4.class)
public class EchoTestServerTest {

  @Test
  public void preprocessArgsTest() {
    String[] splitArgs = TEST_ARGS.split(" ");
    Map<String, List<String>> processedArgs = EchoTestServer.preprocessArgs(splitArgs);

    assertEquals(processedArgs.keySet(), ImmutableSet.copyOf(EXPECTED_KEY_SET));
    assertEquals(processedArgs.get("--server_first"), ImmutableList.of("16060", "16061"));
    assertEquals(processedArgs.get("--bind_ip"), ImmutableList.of("18082"));
    assertEquals(processedArgs.get("--bind_localhost"), ImmutableList.of("18084"));
    assertEquals(processedArgs.get("--version"), ImmutableList.of("\"v1\""));
    assertEquals(processedArgs.get("--grpc"), ImmutableList.of("\"17070\"", "\"17071\""));
    assertEquals(processedArgs.get("--tls"), ImmutableList.of("18443", "19443"));
    assertEquals(processedArgs.get("--cluster"), ImmutableList.of("\"cluster-0\""));
    assertEquals(processedArgs.get("--key"), ImmutableList.of("/cert.key"));
    assertEquals(processedArgs.get("--tcp"), ImmutableList.of("\"19090\"", "\"16060\"",
        "\"19091\"","\"16061\"","\"19092\""));
    assertEquals(processedArgs.get("--istio_version"), ImmutableList.of("3"));
    assertEquals(processedArgs.get("--crt"), ImmutableList.of("/cert.crt"));
    assertEquals(processedArgs.get("--metrics"), ImmutableList.of("15014"));
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
  public void echoTest() throws IOException, InterruptedException {
    EchoTestServer echoTestServer = new EchoTestServer();

    echoTestServer.runServers(ImmutableList.of(0, 0), "test-host");
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
    Set<String> lines = ImmutableSet.copyOf(echoMessage.split(System.lineSeparator()));

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
  public void forwardEchoTest() throws IOException {
    EchoTestServer echoTestServer = new EchoTestServer();

    echoTestServer.runServers(ImmutableList.of(0, 0), "test-host");
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
            .setTimeoutMicros(100_000L) // 100 millis
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
    assertThat(duration).isIn(Range.closed(
        COUNT_OF_REQUESTS_TO_FORWARD * 10L, 2 * COUNT_OF_REQUESTS_TO_FORWARD * 10L));
  }

  private static void validateOutput(String output, int i) {
    Set<String> lines = ImmutableSet.copyOf(output.split(System.lineSeparator()));
    assertThat(lines).contains("RequestHeader=x-request-id:" + i);
    assertThat(lines).contains("RequestHeader=test-key1:test-value1");
    assertThat(lines).contains("RequestHeader=test-key2:test-value2");
    assertThat(lines).contains("Hostname=test-host");
    assertThat(lines).contains("StatusCode=200");
    assertThat(lines).contains("Echo=forward-echo-test-message");
  }

  private static final String[] EXPECTED_KEY_SET = {
      "--server_first",
      "--bind_ip", "--istio_version", "--bind_localhost", "--version", "--grpc", "--tls",
      "--cluster", "--key", "--tcp", "--crt", "--metrics", "--port"
  };

  private static final String TEST_ARGS =
      "--metrics=15014 --cluster=\"cluster-0\" --port=\"18080\" --grpc=\"17070\" --port=\"18085\""
          + " --tcp=\"19090\" --port=\"18443\" --tls=18443 --tcp=\"16060\" --server_first=16060"
          + " --tcp=\"19091\" --tcp=\"16061\" --server_first=16061 --port=\"18081\""
          + " --grpc=\"17071\" --port=\"19443\" --tls=19443 --port=\"18082\" --bind_ip=18082"
          + " --port=\"18084\" --bind_localhost=18084 --tcp=\"19092\" --port=\"18083\""
          + " --port=\"8080\" --port=\"3333\" --version=\"v1\" --istio-version=3 --crt=/cert.crt"
          + " --key=/cert.key";
}
