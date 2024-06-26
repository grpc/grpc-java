/*
 * Copyright 2014 The gRPC Authors
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

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.MessageLite;
import com.google.protobuf.StringValue;
import io.grpc.Metadata;
import io.grpc.protobuf.lite.ProtoLiteUtils;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;

/**
 * Utility methods to support integration testing.
 */
public class Util {

  public static final Metadata.Key<StringValue> METADATA_KEY =
      Metadata.Key.of(
          "google.protobuf.StringValue" + Metadata.BINARY_HEADER_SUFFIX,
          ProtoLiteUtils.metadataMarshaller(StringValue.getDefaultInstance()));
  public static final Metadata.Key<String> ECHO_INITIAL_METADATA_KEY
      = Metadata.Key.of("x-grpc-test-echo-initial", Metadata.ASCII_STRING_MARSHALLER);
  public static final Metadata.Key<byte[]> ECHO_TRAILING_METADATA_KEY
      = Metadata.Key.of("x-grpc-test-echo-trailing-bin", Metadata.BINARY_BYTE_MARSHALLER);

  /** Assert that two messages are equal, producing a useful message if not. */
  @SuppressWarnings("LiteProtoToString")
  public static void assertEquals(MessageLite expected, MessageLite actual) {
    if (expected == null || actual == null) {
      Assert.assertEquals(expected, actual);
    } else {
      if (!expected.equals(actual)) {
        // This assertEquals should always complete.
        Assert.assertEquals(expected.toString(), actual.toString());
        // But if it doesn't, then this should.
        Assert.assertEquals(expected, actual);
        Assert.fail("Messages not equal, but assertEquals didn't throw");
      }
    }
  }

  /** Assert that two lists of messages are equal, producing a useful message if not. */
  public static void assertEquals(List<? extends MessageLite> expected,
      List<? extends MessageLite> actual) {
    if (expected == null || actual == null) {
      Assert.assertEquals(expected, actual);
    } else if (expected.size() != actual.size()) {
      Assert.assertEquals(expected, actual);
    } else {
      for (int i = 0; i < expected.size(); i++) {
        assertEquals(expected.get(i), actual.get(i));
      }
    }
  }

  static List<SocketAddress> getV6Addresses(int port) throws UnknownHostException {
    List<SocketAddress> v6addresses = new ArrayList<>();
    InetAddress[] addresses = InetAddress.getAllByName(InetAddress.getLocalHost().getHostName());
    for (InetAddress address : addresses) {
      if (address.getAddress().length != 4) {
        v6addresses.add(new java.net.InetSocketAddress(address, port));
      }
    }
    return v6addresses;
  }

  static SocketAddress getV4Address(int port) throws UnknownHostException {
    InetAddress[] addresses = InetAddress.getAllByName(InetAddress.getLocalHost().getHostName());
    for (InetAddress address : addresses) {
      if (address.getAddress().length == 4) {
        return new java.net.InetSocketAddress(address, port);
      }
    }
    return null; // means it is v6 only
  }


  /**
   * Picks a port that is not used right at this moment.
   * Warning: Not thread safe. May see "BindException: Address already in use: bind" if using the
   * returned port to create a new server socket when other threads/processes are concurrently
   * creating new sockets without a specific port.
   */
  public static int pickUnusedPort() {
    try {
      ServerSocket serverSocket = new ServerSocket(0);
      int port = serverSocket.getLocalPort();
      serverSocket.close();
      return port;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }


  @VisibleForTesting
  enum AddressType {
    IPV4,
    IPV6,
    IPV4_IPV6
  }
}
