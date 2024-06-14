/*
 * Copyright 2016 The gRPC Authors
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

import static io.grpc.testing.integration.Util.AddressType.IPV4;
import static io.grpc.testing.integration.Util.AddressType.IPV4_IPV6;
import static io.grpc.testing.integration.Util.AddressType.IPV6;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.grpc.Channel;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.TlsChannelCredentials;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.TlsTesting;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link StressTestClient}. */
@RunWith(JUnit4.class)
public class DualStackClientTest {
  protected static final EmptyProtos.Empty EMPTY = EmptyProtos.Empty.getDefaultInstance();

  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

//  @Rule
//  public final Timeout globalTimeout = Timeout.seconds(10);


  @Test
  public void check_ipv4() throws Exception {
    checkConnectionWorks(IPV4);
  }

  @Test
  public void check_ipv6() throws Exception {
    checkConnectionWorks(IPV6);
  }

  @Test
  public void check_ipv4_ipv6() throws Exception {
    checkConnectionWorks(IPV4_IPV6);
  }

  @Test
  public void checkNoAddressType() throws Exception {
    checkConnectionWorks(null);
  }

  private void checkConnectionWorks(Util.AddressType addressType)
      throws Exception {

    TestServiceServer server = getAndStartTestServiceServer(addressType);
    String target = getTargetServer(addressType);

    int port = server.getPort();
    try {
      ManagedChannel realChannel = createChannel(port, target);
      Channel channel = cleanupRule.register(realChannel);
      TestServiceGrpc.TestServiceBlockingStub stub = TestServiceGrpc.newBlockingStub(channel);

      assertEquals(EMPTY, stub.emptyCall(EMPTY));
    } catch (Exception e) {
      throw new AssertionError(e);
    } finally {
      server.stop();
    }
  }

  private static ManagedChannel createChannel(int port, String target) {
    ChannelCredentials creds;
    try {
      creds = TlsChannelCredentials.newBuilder()
          .trustManager(TlsTesting.loadCert("ca.pem"))
          .build();
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }

    ManagedChannelBuilder<?> builder;
    if (port == 0) {
      builder = Grpc.newChannelBuilder(target, creds);
    } else {
      builder = Grpc.newChannelBuilderForAddress(target, port, creds);
    }

    builder.overrideAuthority("foo.test.google.fr");
    return builder.build();
  }

  @Test
  public void checkGaugesShouldBeExported_ipv4() throws Exception {
    checkGaugesShouldBeExported(IPV4);
  }

  private void checkGaugesShouldBeExported(Util.AddressType addressType)
      throws Exception {
    TestServiceServer server = getAndStartTestServiceServer(addressType);

    String serverHost = getTargetServer(addressType);

    StressTestClient client = null;
    ManagedChannel ch = null;
    try {
      // Connect to the metrics service
      client = new StressTestClient();
      client.parseArgs(new String[]{"--test_cases=empty_unary:1",
          "--server_addresses=" + serverHost + ":" + server.getPort(), "--metrics_port=" + 8079,
          "--server_host_override=foo.test.google.fr",
          "--use_tls=true",
          "--use_test_ca=true",
          "--num_stubs_per_channel=1"});
      client.runStressTest();
      Thread.sleep(2000);
      assertEquals(0, client.getTotalFailureCount());
      assertTrue(client.getTotalCallCount() > 0);

    } finally {
      if (client != null) {
        client.shutdown();
      }
      if (ch != null) {
        ch.shutdownNow();
      }
      server.stop();
    }
  }

  private static TestServiceServer getAndStartTestServiceServer(Util.AddressType addressType) throws Exception {
    TestServiceServer server = new TestServiceServer();
    int port = 8082;
    String[] args =
        addressType != null
        ? new String[]{"--port=8082", "--use_tls=true", "--address_type=" + addressType}
        : new String[]{"--port=8083", "--use_tls=true"};
    server.parseArgs(args);
    server.start();
    return server;
  }

  private static String getTargetServer(Util.AddressType addressType) {
    if (addressType == null) {
      return "localhost";
    }

    switch (addressType) {
      case IPV4:
        return "127.0.0.1";
      case IPV6:
        return "[::1]";
      case IPV4_IPV6:
        return "localhost";
      default:
        return "localhost";
    }
  }

}
