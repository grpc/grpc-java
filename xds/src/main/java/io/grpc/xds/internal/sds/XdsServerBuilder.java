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

package io.grpc.xds.internal.sds;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.BindableService;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.HandlerRegistry;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.ServerTransportFilter;
import io.grpc.Status;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.xds.internal.sds.SdsProtocolNegotiators.ServerSdsProtocolNegotiator;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * A version of {@link ServerBuilder} to create xDS managed servers that will use SDS to set up SSL
 * with peers. Note, this is not ready to use yet.
 */
public final class XdsServerBuilder extends ServerBuilder<XdsServerBuilder> {

  private final NettyServerBuilder delegate;
  private final int port;
  private ProtocolNegotiator fallbackProtocolNegotiator;
  private ErrorNotifier errorNotifier;

  private XdsServerBuilder(NettyServerBuilder nettyDelegate, int port) {
    this.delegate = nettyDelegate;
    this.port = port;
  }

  @Override
  public XdsServerBuilder handshakeTimeout(long timeout, TimeUnit unit) {
    delegate.handshakeTimeout(timeout, unit);
    return this;
  }

  @Override
  public XdsServerBuilder directExecutor() {
    delegate.directExecutor();
    return this;
  }

  @Override
  public XdsServerBuilder addStreamTracerFactory(ServerStreamTracer.Factory factory) {
    delegate.addStreamTracerFactory(factory);
    return this;
  }

  @Override
  public XdsServerBuilder addTransportFilter(ServerTransportFilter filter) {
    delegate.addTransportFilter(filter);
    return this;
  }

  @Override
  public XdsServerBuilder executor(Executor executor) {
    delegate.executor(executor);
    return this;
  }

  @Override
  public XdsServerBuilder addService(ServerServiceDefinition service) {
    delegate.addService(service);
    return this;
  }

  @Override
  public XdsServerBuilder addService(BindableService bindableService) {
    delegate.addService(bindableService);
    return this;
  }

  @Override
  public XdsServerBuilder fallbackHandlerRegistry(@Nullable HandlerRegistry fallbackRegistry) {
    delegate.fallbackHandlerRegistry(fallbackRegistry);
    return this;
  }

  @Override
  public XdsServerBuilder useTransportSecurity(File certChain, File privateKey) {
    throw new UnsupportedOperationException("Cannot set security parameters on XdsServerBuilder");
  }

  @Override
  public XdsServerBuilder decompressorRegistry(@Nullable DecompressorRegistry registry) {
    delegate.decompressorRegistry(registry);
    return this;
  }

  @Override
  public XdsServerBuilder compressorRegistry(@Nullable CompressorRegistry registry) {
    delegate.compressorRegistry(registry);
    return this;
  }

  @Override
  public XdsServerBuilder intercept(ServerInterceptor interceptor) {
    delegate.intercept(interceptor);
    return this;
  }

  /** Set the fallback protocolNegotiator. Pass null to unset a previously set value. */
  public XdsServerBuilder fallbackProtocolNegotiator(
      ProtocolNegotiator fallbackProtocolNegotiator) {
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
    return new XdsServerBuilder(nettyDelegate, port);
  }

  @Override
  public Server build() {
    ServerSdsProtocolNegotiator serverProtocolNegotiator =
        SdsProtocolNegotiators.serverProtocolNegotiator(port, fallbackProtocolNegotiator);
    return buildServer(serverProtocolNegotiator);
  }

  /**
   * Creates a Server using the given serverSdsProtocolNegotiator: gets the
   * getXdsClientWrapperForServerSds from the serverSdsProtocolNegotiator.
   */
  @VisibleForTesting
  public ServerWrapperForXds buildServer(ServerSdsProtocolNegotiator serverProtocolNegotiator) {
    delegate.protocolNegotiator(serverProtocolNegotiator);
    return new ServerWrapperForXds(
        delegate.build(), serverProtocolNegotiator.getXdsClientWrapperForServerSds(),
        errorNotifier);
  }

  /** Watcher to receive error notifications from xDS control plane during {@code start()}. */
  public interface ErrorNotifier {

    void onError(Status error);
  }
}
