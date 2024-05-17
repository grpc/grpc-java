/*
 * Copyright 2017 The gRPC Authors
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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link TestServiceClient}. */
@RunWith(JUnit4.class)
public class TestServiceClientTest {

  @Test
  public void emptyArgumentListShouldNotThrowException() throws Exception {
    TestServiceClient client = new TestServiceClient();
    client.parseArgs(new String[0]);
    client.setUp();
  }

  @Test
  public void addressType_try_null() throws Exception {
//    addressType_test(null);
    TestServiceServer server = new TestServiceServer();
    server.parseArgs(new String[] {"--port=8082", "--use_tls=true"});
    try {
      server.start();

      String[] clientArgs = new String[] {"--server_host=localhost", "--server_port=8082",
          "--use_tls=true"};
      TestServiceClient.main(clientArgs);
    } finally {
      server.stop();
    }
  }

  @Test
  public void addressType_parse_v6() throws Exception {
    addressType_test(Util.AddressType.IPV6);
  }

  private void addressType_test(Util.AddressType addressType) throws Exception {
    int port = 8082;
    TestServiceServer server = new TestServiceServer();

    String[] serverArgs =
        addressType != null
        ? new String[]{"--port=" + port, "--use_tls=true", "--address_type=" + addressType}
        : new String[] {"--port=" + port, "--use_tls=true"};
    server.parseArgs(serverArgs);
    server.start();

    String serverHost = getServerHost(addressType);
    String[] clientArgs = new String[] {"--server_host=" + serverHost, "--server_port=" + port,
        "--use_tls=true"};

    TestServiceClient client = new TestServiceClient();
    try {
      client.parseArgs(clientArgs);
      client.setUp();
      client.run();
    } finally {
      try {
        client.tearDown();
      } catch (Exception e) {
        // ignore
      }
      server.stop();
    }

  }

  private String getServerHost(Util.AddressType addressType) {
    if (addressType == null) {
      return "localhost";
    }

    switch (addressType) {
      case IPV4:
        return "127.0.0.1";
      case IPV6:
        return "[::1]";
      default:
        return "localhost";
    }
  }

}
