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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Attributes;
import io.grpc.BinaryLog;
import io.grpc.BindableService;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.ExperimentalApi;
import io.grpc.ForwardingServerBuilder;
import io.grpc.HandlerRegistry;
import io.grpc.Internal;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCredentials;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.ServerTransportFilter;
import io.grpc.netty.InternalNettyServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.xds.internal.sds.SdsProtocolNegotiators;
import io.grpc.xds.internal.sds.ServerWrapperForXds;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * A version of {@link ServerBuilder} to create xDS managed servers.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/7514")
public final class XdsServerBuilder extends ForwardingServerBuilder<XdsServerBuilder> {

  private final NettyServerBuilder delegate;
  private final int port;
  private XdsServingStatusListener xdsServingStatusListener;
  private AtomicBoolean isServerBuilt = new AtomicBoolean(false);

  private XdsServerBuilder(NettyServerBuilder nettyDelegate, int port) {
    this.delegate = nettyDelegate;
    this.port = port;
    xdsServingStatusListener = new DefaultListener("port:" + port);
  }

  @Override
  @Internal
  protected ServerBuilder<?> delegate() {
    return delegate;
  }

  /** Set the {@link XdsServingStatusListener} to receive "serving" and "not serving" states. */
  public XdsServerBuilder xdsServingStatusListener(
      XdsServingStatusListener xdsServingStatusListener) {
    this.xdsServingStatusListener =
        checkNotNull(xdsServingStatusListener, "xdsServingStatusListener");
    return this;
  }

  /**
   * Unsupported call. Users should only use {@link #forPort(int, ServerCredentials)}.
   */
  public static ServerBuilder<?> forPort(int port) {
    throw new UnsupportedOperationException(
        "Unsupported call - use forPort(int, ServerCredentials)");
  }

  /** Creates a gRPC server builder for the given port. */
  public static XdsServerBuilder forPort(int port, ServerCredentials serverCredentials) {
    NettyServerBuilder nettyDelegate = NettyServerBuilder.forPort(port, serverCredentials);
    return new XdsServerBuilder(nettyDelegate, port);
  }

  @Override
  public Server build() {
    return buildServer(new XdsClientWrapperForServerSds(port));
  }

  /**
   * Creates a Server using the given xdsClient.
   */
  @VisibleForTesting
  ServerWrapperForXds buildServer(
      XdsClientWrapperForServerSds xdsClient) {
    checkState(isServerBuilt.compareAndSet(false, true), "Server already built!");
    InternalNettyServerBuilder.eagAttributes(delegate, Attributes.newBuilder()
        .set(SdsProtocolNegotiators.SERVER_XDS_CLIENT, xdsClient)
        .build());
    return new ServerWrapperForXds(delegate, xdsClient, xdsServingStatusListener);
  }

  /**
   * Returns the delegate {@link NettyServerBuilder} to allow experimental level
   * transport-specific configuration. Note this API will always be experimental.
   */
  public ServerBuilder<?> transportBuilder() {
    return delegate;
  }

  /**
   * Applications can register this listener to receive "serving" and "not serving" states of
   * the server using {@link #xdsServingStatusListener(XdsServingStatusListener)}.
   */
  public interface XdsServingStatusListener {

    /** Callback invoked when server begins serving. */
    void onServing();

    /** Callback invoked when server is forced to be "not serving" due to an error.
     * @param throwable cause of the error
     */
    void onNotServing(Throwable throwable);
  }

  /** Default implementation of {@link XdsServingStatusListener} that logs at WARNING level. */
  private static class DefaultListener implements XdsServingStatusListener {
    XdsLogger xdsLogger;
    boolean notServing;

    DefaultListener(String prefix) {
      xdsLogger = XdsLogger.withPrefix(prefix);
      notServing = true;
    }

    /** Log calls to onServing() following a call to onNotServing() at WARNING level. */
    @Override
    public void onServing() {
      if (notServing) {
        notServing = false;
        xdsLogger.log(XdsLogger.XdsLogLevel.WARNING, "Entering serving state.");
      }
    }

    @Override
    public void onNotServing(Throwable throwable) {
      xdsLogger.log(XdsLogger.XdsLogLevel.WARNING, throwable.getMessage());
      notServing = true;
    }
  }

  @Override
  public XdsServerBuilder directExecutor() {
    checkState(!isServerBuilt.get(), "Server already built!");
    return super.directExecutor();
  }

  @Override
  public XdsServerBuilder executor(@Nullable Executor executor) {
    checkState(!isServerBuilt.get(), "Server already built!");
    return super.executor(executor);
  }

  @Override
  public XdsServerBuilder addService(ServerServiceDefinition service) {
    checkState(!isServerBuilt.get(), "Server already built!");
    return super.addService(service);
  }

  @Override
  public XdsServerBuilder addService(BindableService bindableService) {
    checkState(!isServerBuilt.get(), "Server already built!");
    return super.addService(bindableService);
  }

  @Override
  public XdsServerBuilder intercept(ServerInterceptor interceptor) {
    checkState(!isServerBuilt.get(), "Server already built!");
    return super.intercept(interceptor);
  }

  @Override
  public XdsServerBuilder addTransportFilter(ServerTransportFilter filter) {
    checkState(!isServerBuilt.get(), "Server already built!");
    return super.addTransportFilter(filter);
  }

  @Override
  public XdsServerBuilder addStreamTracerFactory(ServerStreamTracer.Factory factory) {
    checkState(!isServerBuilt.get(), "Server already built!");
    return super.addStreamTracerFactory(factory);
  }

  @Override
  public XdsServerBuilder fallbackHandlerRegistry(@Nullable HandlerRegistry fallbackRegistry) {
    checkState(!isServerBuilt.get(), "Server already built!");
    return super.fallbackHandlerRegistry(fallbackRegistry);
  }

  @Override
  public XdsServerBuilder useTransportSecurity(File certChain, File privateKey) {
    checkState(!isServerBuilt.get(), "Server already built!");
    return super.useTransportSecurity(certChain, privateKey);
  }

  @Override
  public XdsServerBuilder useTransportSecurity(InputStream certChain, InputStream privateKey) {
    checkState(!isServerBuilt.get(), "Server already built!");
    return super.useTransportSecurity(certChain, privateKey);
  }

  @Override
  public XdsServerBuilder decompressorRegistry(@Nullable DecompressorRegistry registry) {
    checkState(!isServerBuilt.get(), "Server already built!");
    return super.decompressorRegistry(registry);
  }

  @Override
  public XdsServerBuilder compressorRegistry(@Nullable CompressorRegistry registry) {
    checkState(!isServerBuilt.get(), "Server already built!");
    return super.compressorRegistry(registry);
  }

  @Override
  public XdsServerBuilder handshakeTimeout(long timeout, TimeUnit unit) {
    checkState(!isServerBuilt.get(), "Server already built!");
    return super.handshakeTimeout(timeout, unit);
  }

  @Override
  public XdsServerBuilder maxInboundMessageSize(int bytes) {
    checkState(!isServerBuilt.get(), "Server already built!");
    return super.maxInboundMessageSize(bytes);
  }

  @Override
  public XdsServerBuilder maxInboundMetadataSize(int bytes) {
    checkState(!isServerBuilt.get(), "Server already built!");
    return super.maxInboundMetadataSize(bytes);
  }

  @Override
  public XdsServerBuilder setBinaryLog(BinaryLog binaryLog) {
    checkState(!isServerBuilt.get(), "Server already built!");
    return super.setBinaryLog(binaryLog);
  }
}
