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

import io.grpc.ChannelCredentials;
import io.grpc.InternalManagedChannelProvider;
import io.grpc.ManagedChannelBuilder;
import io.grpc.ManagedChannelProvider;
import io.grpc.ManagedChannelRegistry;
import io.grpc.observability.interceptors.InternalLoggingChannelInterceptor;

/** A channel provider that injects logging interceptor. */
final class LoggingChannelProvider extends ManagedChannelProvider {
  private final ManagedChannelProvider prevProvider;
  private final InternalLoggingChannelInterceptor.Factory clientInterceptorFactory;

  private static LoggingChannelProvider instance;

  private LoggingChannelProvider(InternalLoggingChannelInterceptor.Factory factory) {
    prevProvider = ManagedChannelProvider.provider();
    clientInterceptorFactory = factory;
  }

  static synchronized void init(InternalLoggingChannelInterceptor.Factory factory) {
    if (instance != null) {
      throw new IllegalStateException("LoggingChannelProvider already initialized!");
    }
    instance = new LoggingChannelProvider(factory);
    ManagedChannelRegistry.getDefaultRegistry().register(instance);
  }

  static synchronized void finish() {
    if (instance == null) {
      throw new IllegalStateException("LoggingChannelProvider not initialized!");
    }
    ManagedChannelRegistry.getDefaultRegistry().deregister(instance);
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

  private ManagedChannelBuilder<?> addInterceptor(ManagedChannelBuilder<?> builder) {
    return builder.intercept(clientInterceptorFactory.create());
  }

  @Override
  protected ManagedChannelBuilder<?> builderForAddress(String name, int port) {
    return addInterceptor(
        InternalManagedChannelProvider.builderForAddress(prevProvider, name, port));
  }

  @Override
  protected ManagedChannelBuilder<?> builderForTarget(String target) {
    return addInterceptor(InternalManagedChannelProvider.builderForTarget(prevProvider, target));
  }

  @Override
  protected NewChannelBuilderResult newChannelBuilder(String target, ChannelCredentials creds) {
    NewChannelBuilderResult result = InternalManagedChannelProvider.newChannelBuilder(prevProvider,
        target, creds);
    ManagedChannelBuilder<?> builder = result.getChannelBuilder();
    if (builder != null) {
      return NewChannelBuilderResult.channelBuilder(
              addInterceptor(builder));
    }
    checkNotNull(result.getError(), "Expected error to be set!");
    return result;
  }
}
