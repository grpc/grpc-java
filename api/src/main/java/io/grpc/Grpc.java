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

package io.grpc;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
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
  @TransportAttr
  public static final Attributes.Key<SocketAddress> TRANSPORT_ATTR_REMOTE_ADDR =
      Attributes.Key.create("remote-addr");

  /**
   * Attribute key for the local address of a transport.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1710")
  @TransportAttr
  public static final Attributes.Key<SocketAddress> TRANSPORT_ATTR_LOCAL_ADDR =
      Attributes.Key.create("local-addr");

  /**
   * Attribute key for SSL session of a transport.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1710")
  @TransportAttr
  public static final Attributes.Key<SSLSession> TRANSPORT_ATTR_SSL_SESSION =
      Attributes.Key.create("ssl-session");

  /**
   * Annotation for transport attributes. It follows the annotation semantics defined
   * by {@link Attributes}.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/4972")
  @Retention(RetentionPolicy.SOURCE)
  @Documented
  public @interface TransportAttr {}

  /**
   * Creates a channel builder with a target string and credentials. The target can be either a
   * valid {@link NameResolver}-compliant URI, or an authority string.
   *
   * <p>A {@code NameResolver}-compliant URI is an absolute hierarchical URI as defined by {@link
   * java.net.URI}. Example URIs:
   * <ul>
   *   <li>{@code "dns:///foo.googleapis.com:8080"}</li>
   *   <li>{@code "dns:///foo.googleapis.com"}</li>
   *   <li>{@code "dns:///%5B2001:db8:85a3:8d3:1319:8a2e:370:7348%5D:443"}</li>
   *   <li>{@code "dns://8.8.8.8/foo.googleapis.com:8080"}</li>
   *   <li>{@code "dns://8.8.8.8/foo.googleapis.com"}</li>
   *   <li>{@code "zookeeper://zk.example.com:9900/example_service"}</li>
   * </ul>
   *
   * <p>An authority string will be converted to a {@code NameResolver}-compliant URI, which has
   * the scheme from the name resolver with the highest priority (e.g. {@code "dns"}),
   * no authority, and the original authority string as its path after properly escaped.
   * We recommend libraries to specify the schema explicitly if it is known, since libraries cannot
   * know which NameResolver will be default during runtime.
   * Example authority strings:
   * <ul>
   *   <li>{@code "localhost"}</li>
   *   <li>{@code "127.0.0.1"}</li>
   *   <li>{@code "localhost:8080"}</li>
   *   <li>{@code "foo.googleapis.com:8080"}</li>
   *   <li>{@code "127.0.0.1:8080"}</li>
   *   <li>{@code "[2001:db8:85a3:8d3:1319:8a2e:370:7348]"}</li>
   *   <li>{@code "[2001:db8:85a3:8d3:1319:8a2e:370:7348]:443"}</li>
   * </ul>
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/7479")
  public static ManagedChannelBuilder<?> newChannelBuilder(
      String target, ChannelCredentials creds) {
    return ManagedChannelRegistry.getDefaultRegistry().newChannelBuilder(target, creds);
  }

  /**
   * Creates a channel builder from a host, port, and credentials. The host and port are combined to
   * form an authority string and then passed to {@link #newChannelBuilder(String,
   * ChannelCredentials)}. IPv6 addresses are properly surrounded by square brackets ("[]").
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/7479")
  public static ManagedChannelBuilder<?> newChannelBuilderForAddress(
      String host, int port, ChannelCredentials creds) {
    return newChannelBuilder(authorityFromHostAndPort(host, port), creds);
  }

  /**
   * Combine a host and port into an authority string.
   */
  // A copy of GrpcUtil.authorityFromHostAndPort
  private static String authorityFromHostAndPort(String host, int port) {
    try {
      return new URI(null, null, host, port, null, null, null).getAuthority();
    } catch (URISyntaxException ex) {
      throw new IllegalArgumentException("Invalid host or port: " + host + " " + port, ex);
    }
  }

  /**
   * Static factory for creating a new ServerBuilder.
   *
   * @param port the port to listen on
   * @param creds the server identity
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/7621")
  public static ServerBuilder<?> newServerBuilderForPort(int port, ServerCredentials creds) {
    return ServerRegistry.getDefaultRegistry().newServerBuilderForPort(port, creds);
  }
}
