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

import io.grpc.Channel;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.testing.GrpcCleanupRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests to make sure that the {@link XdsTestServer} is working as expected.
 * Specifically, that for dualstack communication is handled correctly across address families
 * and that the test server is correctly handling the address_type flag.
 */
@RunWith(JUnit4.class)
public class XdsTestServerTest {
  protected static final EmptyProtos.Empty EMPTY = EmptyProtos.Empty.getDefaultInstance();

  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  @Rule
  public final Timeout globalTimeout = Timeout.seconds(10);


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
    // This ensures that all of the other xds tests aren't broken by the address_type argument.
    checkConnectionWorks(null);
  }

  // Simple test to ensure that communication with the server works which includes starting and
  // stopping the server, creating a channel and doing a unary rpc.
  private void checkConnectionWorks(Util.AddressType addressType)
      throws Exception {

    int port = Util.pickUnusedPort();

    XdsTestServer server = getAndStartTestServiceServer(addressType, port);
    String target = getTargetServer(addressType);

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
    ChannelCredentials creds = InsecureChannelCredentials.create();

    ManagedChannelBuilder<?> builder;
    if (port == 0) {
      builder = Grpc.newChannelBuilder(target, creds);
    } else {
      builder = Grpc.newChannelBuilderForAddress(target, port, creds);
    }

    builder.overrideAuthority("foo.test.google.fr");
    return builder.build();
  }

  private static XdsTestServer getAndStartTestServiceServer(Util.AddressType addressType, int port)
      throws Exception {
    XdsTestServer server = new XdsTestServer();
    String[] args =
        addressType != null
        ? new String[]{"--port=" + port, "--address_type=" + addressType}
        : new String[]{"--port=" + port};
    server.parseArgs(args);
    server.start();
    return server;
  }

  // Done this way to make sure that the target server is correct for the address type we want to
  // test.
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
