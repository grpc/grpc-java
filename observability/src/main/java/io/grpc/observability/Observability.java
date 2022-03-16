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

import io.grpc.ExperimentalApi;
import io.grpc.ManagedChannelProvider.ProviderNotFoundException;
import io.grpc.observability.interceptors.InternalLoggingChannelInterceptor;
import io.grpc.observability.interceptors.InternalLoggingServerInterceptor;
import io.grpc.observability.logging.GcpLogSink;
import io.grpc.observability.logging.Sink;

/** The main class for gRPC Observability features. */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8869")
public final class Observability {
  private static boolean initialized = false;
  private static final String PROJECT_ID = "PROJECT";

  /**
   * Initialize grpc-observability.
   *
   * @throws ProviderNotFoundException if no underlying channel/server provider is available.
   */
  public static synchronized void grpcInit() {
    if (initialized) {
      throw new IllegalStateException("Observability already initialized!");
    }
    // TODO(dnvindhya): PROJECT_ID to be replaced with configured destinationProjectId
    Sink sink = new GcpLogSink(PROJECT_ID);
    LoggingChannelProvider.init(new InternalLoggingChannelInterceptor.FactoryImpl(sink));
    LoggingServerProvider.init(new InternalLoggingServerInterceptor.FactoryImpl());
    // TODO(sanjaypujare): initialize customTags map
    initialized = true;
  }

  /** Un-initialize or finish grpc-observability. */
  public static synchronized void grpcFinish() {
    if (!initialized) {
      throw new IllegalStateException("Observability not initialized!");
    }
    LoggingChannelProvider.finish();
    LoggingServerProvider.finish();
    // TODO(sanjaypujare): finish customTags map
    initialized = false;
  }

  private Observability() {
  }
}
