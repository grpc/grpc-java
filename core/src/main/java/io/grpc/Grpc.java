/*
 * Copyright 2016, gRPC Authors All rights reserved.
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

package io.grpc;

import java.net.SocketAddress;
import javax.net.ssl.SSLSession;

/**
 * Stuff that are part of the public API but are not bound to particular classes, e.g., static
 * methods, constants, attribute and context keys.
 */
public final class Grpc {
  private Grpc() {
  }

  /**
   * Attribute key for the remote address of a transport.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1710")
  public static final Attributes.Key<SocketAddress> TRANSPORT_ATTR_REMOTE_ADDR =
          Attributes.Key.of("remote-addr");

  /**
   * Attribute key for SSL session of a transport.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1710")
  public static final Attributes.Key<SSLSession> TRANSPORT_ATTR_SSL_SESSION =
          Attributes.Key.of("ssl-session");

  /**
   * The side.
   */
  public enum Side {
    CLIENT,
    SERVER
  }

  /**
   * Convert a full method name to a tracing span name.
   *
   * @param side if the span is on the client-side or server-side
   * @param fullMethodName the method name as returned by {@link
   *                       MethodDescriptor#getFullMethodName}.
   */
  public static String toSpanName(Side side, String fullMethodName) {
    String prefix;
    switch (side) {
      case CLIENT:
        prefix = "Sent";
        break;
      case SERVER:
        prefix = "Recv";
        break;
      default:
        throw new AssertionError("Unsupported side: " + side);
    }
    return prefix + "." + fullMethodName.replace('/', '.');
  }

  /**
   * Register methods so that they can be recorded by the tracing component.
   */
  public static void registerMethodsForTracing(String[] fullMethodNames) {
    SampledSpanStore sampledStore = Tracing.getExportComponent().getSampledStore();
    if (sampledStore == null) {
      return;
    }
    ArrayList<String> spanNames = new ArrayList<String>(fullMethodNames.length * 2);
    for (String method : fullMethodNames) {
      spanNames.add(toSpanName(Side.CLIENT, method));
      spanNames.add(toSpanName(Side.SERVER, method));
    }
    sampledStore.registerSpanNamesForCollection(spanNames);
  }
}
