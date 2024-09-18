/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.override;

import io.grpc.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A Context.Storage implementation that attaches io.grpc.context to OpenTelemetry's context and
 * io.opentelemetry.context is also saved in the io.grpc.context.
 * Bridge between {@link io.grpc.Context} and {@link io.opentelemetry.context.Context}.
 */
final class OpenTelemetryContextStorage extends Context.Storage {
  private static final Logger logger = Logger.getLogger(
      OpenTelemetryContextStorage.class.getName());

  private static final io.grpc.Context.Key<io.opentelemetry.context.Context> OTEL_CONTEXT_OVER_GRPC
      = io.grpc.Context.key("otel-context-over-grpc");
  private static final Context.Key<Scope> OTEL_SCOPE = Context.key("otel-scope");
  private static final ContextKey<io.grpc.Context> GRPC_CONTEXT_OVER_OTEL =
      ContextKey.named("grpc-context-over-otel");

  @Override
  @SuppressWarnings("MustBeClosedChecker")
  public Context doAttach(Context toAttach) {
    io.grpc.Context previous = current();
    io.opentelemetry.context.Context otelContext = OTEL_CONTEXT_OVER_GRPC.get(toAttach);
    if (otelContext == null) {
      otelContext = io.opentelemetry.context.Context.current();
    }
    Scope scope = otelContext.with(GRPC_CONTEXT_OVER_OTEL, toAttach).makeCurrent();
    return previous.withValue(OTEL_SCOPE, scope);
  }

  @Override
  public void detach(Context toDetach, Context toRestore) {
    Scope scope = OTEL_SCOPE.get(toRestore);
    if (scope == null) {
      logger.log(
          Level.SEVERE, "Detaching context which was not attached.");
    } else {
      scope.close();
    }
  }

  @Override
  public Context current() {
    io.opentelemetry.context.Context otelCurrent = io.opentelemetry.context.Context.current();
    io.grpc.Context grpcCurrent = otelCurrent.get(GRPC_CONTEXT_OVER_OTEL);
    if (grpcCurrent == null) {
      grpcCurrent = Context.ROOT;
    }
    return grpcCurrent.withValue(OTEL_CONTEXT_OVER_GRPC, otelCurrent);
  }
}
