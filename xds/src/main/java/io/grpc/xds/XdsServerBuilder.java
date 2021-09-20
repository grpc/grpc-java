/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.grpc.Attributes;
import io.grpc.ExperimentalApi;
import io.grpc.ForwardingServerBuilder;
import io.grpc.Internal;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCredentials;
import io.grpc.Status;
import io.grpc.netty.InternalNettyServerBuilder;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiators;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.xds.internal.sds.SdsProtocolNegotiators;
import io.grpc.xds.internal.sds.SdsProtocolNegotiators.ServerSdsProtocolNegotiator;
import io.grpc.xds.internal.sds.ServerWrapperForXds;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import javax.net.ssl.SSLException;

/**
 * A version of {@link ServerBuilder} to create xDS managed servers that will use SDS to set up SSL
 * with peers. Note, this is not ready to use yet.
 */
public final class XdsServerBuilder extends ForwardingServerBuilder<XdsServerBuilder> {

  private final NettyServerBuilder delegate;
  private final int port;
  private final boolean freezeNegotiator;
  private ProtocolNegotiator fallbackProtocolNegotiator;
  private ErrorNotifier errorNotifier;

  private XdsServerBuilder(NettyServerBuilder nettyDelegate, int port, boolean freezeNegotiator) {
    this.delegate = nettyDelegate;
    this.port = port;
    this.freezeNegotiator = freezeNegotiator;
  }

  @Override
  @Internal
  protected ServerBuilder<?> delegate() {
    return delegate;
  }

  /**
   * Use xDS provided security with plaintext as fallback. Note, this experimental functionality
   * is not ready for wide usage.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/7514")
  public XdsServerBuilder useXdsSecurityWithPlaintextFallback() {
    Preconditions.checkState(!freezeNegotiator, "Method unavailable when using ServerCredentials");
    this.fallbackProtocolNegotiator = InternalProtocolNegotiators.serverPlaintext();
    return this;
  }

  /**
   * Use xDS provided security with TLS as fallback. Note, this experimental functionality
   * is not ready for wide usage.
   *
   * @param certChain file containing the full certificate chain
   * @param privateKey file containing the private key
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/7514")
  public XdsServerBuilder useXdsSecurityWithTransportSecurityFallback(
      File certChain, File privateKey) throws SSLException {
    Preconditions.checkState(!freezeNegotiator, "Method unavailable when using ServerCredentials");
    SslContext sslContext = SslContextBuilder.forServer(certChain, privateKey).build();
    this.fallbackProtocolNegotiator = InternalProtocolNegotiators.serverTls(sslContext);
    return this;
  }

  /**
   * Use xDS provided security with TLS as fallback. Note, this experimental functionality
   * is not ready for wide usage.
   *
   * @param certChain InputStream containing the full certificate chain
   * @param privateKey InputStream containing the private key
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/7514")
  public XdsServerBuilder useXdsSecurityWithTransportSecurityFallback(
      InputStream certChain, InputStream privateKey) throws SSLException {
    Preconditions.checkState(!freezeNegotiator, "Method unavailable when using ServerCredentials");
    SslContext sslContext = SslContextBuilder.forServer(certChain, privateKey).build();
    this.fallbackProtocolNegotiator = InternalProtocolNegotiators.serverTls(sslContext);
    return this;
  }

  /** Set the fallback protocolNegotiator. Pass null to unset a previously set value. */
  public XdsServerBuilder fallbackProtocolNegotiator(
      ProtocolNegotiator fallbackProtocolNegotiator) {
    Preconditions.checkState(!freezeNegotiator, "Method unavailable when using ServerCredentials");
    this.fallbackProtocolNegotiator = fallbackProtocolNegotiator;
    return this;
  }

  /** Set the {@link ErrorNotifier}. Pass null to unset a previously set value. */
  public XdsServerBuilder errorNotifier(ErrorNotifier errorNotifier) {
    this.errorNotifier = errorNotifier;
    return this;
  }

  /** Creates a gRPC server builder for the given port. */
  public static XdsServerBuilder forPort(int port) {
    NettyServerBuilder nettyDelegate = NettyServerBuilder.forAddress(new InetSocketAddress(port));
    return new XdsServerBuilder(nettyDelegate, port, /* freezeNegotiator= */ false);
  }

  /** Creates a gRPC server builder for the given port. */
  public static XdsServerBuilder forPort(int port, ServerCredentials serverCredentials) {
    NettyServerBuilder nettyDelegate = NettyServerBuilder.forPort(port, serverCredentials);
    return new XdsServerBuilder(nettyDelegate, port, /* freezeNegotiator= */ true);
  }

  @Override
  public Server build() {
    XdsClientWrapperForServerSds xdsClient = new XdsClientWrapperForServerSds(port);
    ServerSdsProtocolNegotiator serverProtocolNegotiator = null;
    if (fallbackProtocolNegotiator != null) {
      serverProtocolNegotiator =
          SdsProtocolNegotiators.serverProtocolNegotiator(fallbackProtocolNegotiator);
    }
    return buildServer(xdsClient, serverProtocolNegotiator);
  }

  /**
   * Creates a Server using the given xdsClient and serverSdsProtocolNegotiator.
   */
  @VisibleForTesting
  ServerWrapperForXds buildServer(
      XdsClientWrapperForServerSds xdsClient,
      ServerSdsProtocolNegotiator serverProtocolNegotiator) {
    InternalNettyServerBuilder.eagAttributes(delegate, Attributes.newBuilder()
        .set(SdsProtocolNegotiators.SERVER_XDS_CLIENT, xdsClient)
        .build());
    if (serverProtocolNegotiator != null) {
      delegate.protocolNegotiator(serverProtocolNegotiator);
    }
    return new ServerWrapperForXds(delegate.build(), xdsClient, errorNotifier);
  }

  /** Watcher to receive error notifications from xDS control plane during {@code start()}. */
  public interface ErrorNotifier {

    void onError(Status error);
  }
}
