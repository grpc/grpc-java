/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.internal;

import com.google.common.base.MoreObjects;
import io.grpc.BinaryLog;
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
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * A {@link ServerBuilder} that delegates all its builder method to another builder by default.
 *
 * <p>Temporarily duplicates io.grpc.ForwardingServerBuilder (temporarily package-private)
 * to fix ABI backward compatibility.

 * @param <T> The concrete type of this builder.
 * @see <a href="https://github.com/grpc/grpc-java/issues/7211">grpc/grpc-java#7211</a>
 */
public abstract class AbstractServerImplBuilder
    <T extends AbstractServerImplBuilder<T>> extends ServerBuilder<T> {

  /** The default constructor. */
  protected AbstractServerImplBuilder() {}

  /**
   * This method serves to force sub classes to "hide" this static factory.
   */
  public static ServerBuilder<?> forPort(int port) {
    throw new UnsupportedOperationException("Subclass failed to hide static factory");
  }

  /**
   * Returns the delegated {@code ServerBuilder}.
   */
  protected abstract ServerBuilder<?> delegate();

  @Override
  public T directExecutor() {
    delegate().directExecutor();
    return thisT();
  }

  @Override
  public T executor(@Nullable Executor executor) {
    delegate().executor(executor);
    return thisT();
  }

  @Override
  public T addService(ServerServiceDefinition service) {
    delegate().addService(service);
    return thisT();
  }

  @Override
  public T addService(BindableService bindableService) {
    delegate().addService(bindableService);
    return thisT();
  }

  @Override
  public T intercept(ServerInterceptor interceptor) {
    delegate().intercept(interceptor);
    return thisT();
  }

  @Override
  public T addTransportFilter(ServerTransportFilter filter) {
    delegate().addTransportFilter(filter);
    return thisT();
  }

  @Override
  public T addStreamTracerFactory(ServerStreamTracer.Factory factory) {
    delegate().addStreamTracerFactory(factory);
    return thisT();
  }

  @Override
  public T fallbackHandlerRegistry(@Nullable HandlerRegistry fallbackRegistry) {
    delegate().fallbackHandlerRegistry(fallbackRegistry);
    return thisT();
  }

  @Override
  public T useTransportSecurity(File certChain, File privateKey) {
    delegate().useTransportSecurity(certChain, privateKey);
    return thisT();
  }

  @Override
  public T useTransportSecurity(InputStream certChain, InputStream privateKey) {
    delegate().useTransportSecurity(certChain, privateKey);
    return thisT();
  }

  @Override
  public T decompressorRegistry(@Nullable DecompressorRegistry registry) {
    delegate().decompressorRegistry(registry);
    return thisT();
  }

  @Override
  public T compressorRegistry(@Nullable CompressorRegistry registry) {
    delegate().compressorRegistry(registry);
    return thisT();
  }

  @Override
  public T handshakeTimeout(long timeout, TimeUnit unit) {
    delegate().handshakeTimeout(timeout, unit);
    return thisT();
  }

  @Override
  public T maxInboundMessageSize(int bytes) {
    delegate().maxInboundMessageSize(bytes);
    return thisT();
  }

  @Override
  public T maxInboundMetadataSize(int bytes) {
    delegate().maxInboundMetadataSize(bytes);
    return thisT();
  }

  @Override
  public T setBinaryLog(BinaryLog binaryLog) {
    delegate().setBinaryLog(binaryLog);
    return thisT();
  }

  /**
   * Returns the {@link Server} built by the delegate by default. Overriding method can return
   * different value.
   */
  @Override
  public Server build() {
    return delegate().build();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("delegate", delegate()).toString();
  }

  /**
   * Returns the correctly typed version of the builder.
   */
  protected final T thisT() {
    @SuppressWarnings("unchecked")
    T thisT = (T) this;
    return thisT;
  }
}
