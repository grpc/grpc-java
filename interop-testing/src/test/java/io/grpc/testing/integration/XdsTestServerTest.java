/*
 * Copyright 2024 The gRPC Authors
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


  @Test
  public void check_ipv4() throws Exception {
    checkConnectionWorks("127.0.0.1", "--address_type=IPV4");
  }

  @Test
  public void check_ipv6() throws Exception {
    checkConnectionWorks("::1", "--address_type=IPV6");
  }

  @Test
  public void check_ipv4_ipv6() throws Exception {
    checkConnectionWorks("localhost", "--address_type=IPV4_IPV6");
  }

  @Test
  public void checkNoAddressType() throws Exception {
    // This ensures that all of the other xds tests aren't broken by the address_type argument.
    checkConnectionWorks("localhost", null);
  }

  // Simple test to ensure that communication with the server works which includes starting and
  // stopping the server, creating a channel and doing a unary rpc.
  private void checkConnectionWorks(String targetServer, String addressTypeArg)
      throws Exception {

    int port = Util.pickUnusedPort();

    XdsTestServer server = getAndStartTestServiceServer(port, addressTypeArg);

    try {
      ManagedChannel realChannel = createChannel(port, targetServer);
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

  private static XdsTestServer getAndStartTestServiceServer(int port, String addressTypeArg)
      throws Exception {
    XdsTestServer server = new XdsTestServer();
    String[] args = addressTypeArg != null
        ? new String[]{"--port=" + port, addressTypeArg}
        : new String[]{"--port=" + port};
    server.parseArgs(args);
    server.start();
    return server;
  }

}
