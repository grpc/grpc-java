/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.observability;

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.InternalServerProvider;
import io.grpc.ServerBuilder;
import io.grpc.ServerCredentials;
import io.grpc.ServerProvider;
import io.grpc.ServerRegistry;
import io.grpc.observability.interceptors.InternalLoggingServerInterceptor;

/** A server provider that injects the logging interceptor. */
final class LoggingServerProvider extends ServerProvider {
  private final ServerProvider prevProvider;
  private final InternalLoggingServerInterceptor.Factory serverInterceptorFactory;

  private static LoggingServerProvider instance;

  private LoggingServerProvider(InternalLoggingServerInterceptor.Factory factory) {
    prevProvider = ServerProvider.provider();
    serverInterceptorFactory = factory;
  }

  static synchronized void init(InternalLoggingServerInterceptor.Factory factory) {
    if (instance != null) {
      throw new IllegalStateException("LoggingServerProvider already initialized!");
    }
    instance = new LoggingServerProvider(factory);
    ServerRegistry.getDefaultRegistry().register(instance);
  }

  static synchronized void finish() {
    if (instance == null) {
      throw new IllegalStateException("LoggingServerProvider not initialized!");
    }
    ServerRegistry.getDefaultRegistry().deregister(instance);
    instance = null;
  }

  @Override
  protected boolean isAvailable() {
    return true;
  }

  @Override
  protected int priority() {
    return 6;
  }

  private ServerBuilder<?> addInterceptor(ServerBuilder<?> builder) {
    return builder.intercept(serverInterceptorFactory.create());
  }

  @Override
  protected ServerBuilder<?> builderForPort(int port) {
    return addInterceptor(InternalServerProvider.builderForPort(prevProvider, port));
  }

  @Override
  protected NewServerBuilderResult newServerBuilderForPort(int port, ServerCredentials creds) {
    ServerProvider.NewServerBuilderResult result = InternalServerProvider.newServerBuilderForPort(
        prevProvider, port,
        creds);
    ServerBuilder<?> builder = result.getServerBuilder();
    if (builder != null) {
      return ServerProvider.NewServerBuilderResult.serverBuilder(
              addInterceptor(builder));
    }
    checkNotNull(result.getError(), "Expected error to be set!");
    return result;
  }
}
