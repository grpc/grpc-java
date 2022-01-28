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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.ChannelCredentials;
import io.grpc.ExperimentalApi;
import io.grpc.ManagedChannelBuilder;
import io.grpc.ManagedChannelProvider;
import io.grpc.ManagedChannelRegistry;
import io.grpc.observability.interceptors.LoggingChannelInterceptor;

/** A channel provider that injects logging interceptor. */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8869")
final class LoggingChannelProvider extends ManagedChannelProvider {
  @VisibleForTesting final ManagedChannelProvider prevProvider;
  @VisibleForTesting final LoggingChannelInterceptor.Factory clientInterceptorFactory;

  @VisibleForTesting static LoggingChannelProvider instance;

  private LoggingChannelProvider(LoggingChannelInterceptor.Factory factory) {
    prevProvider = ManagedChannelProvider.provider();
    clientInterceptorFactory = factory;
  }

  static synchronized void init(LoggingChannelInterceptor.Factory factory) {
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
  public ManagedChannelBuilder<?> builderForAddress(String name, int port) {
    return addInterceptor(prevProvider.builderForAddress(name, port));
  }

  @Override
  public ManagedChannelBuilder<?> builderForTarget(String target) {
    return addInterceptor(prevProvider.builderForTarget(target));
  }

  @Override
  public NewChannelBuilderResult newChannelBuilder(String target, ChannelCredentials creds) {
    NewChannelBuilderResult result = prevProvider.newChannelBuilder(target, creds);
    ManagedChannelBuilder<?> builder = result.getChannelBuilder();
    if (builder != null) {
      return NewChannelBuilderResult.channelBuilder(
              addInterceptor(builder));
    }
    checkNotNull(result.getError(), "Expected error to be set!");
    return result;
  }
}
