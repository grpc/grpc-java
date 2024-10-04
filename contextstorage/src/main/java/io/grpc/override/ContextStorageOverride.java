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

/**
 * Including this class in your dependencies will override the default gRPC context storage using
 * reflection. It is a bridge between {@link io.grpc.Context} and
 * {@link io.opentelemetry.context.Context}, i.e. propagating io.grpc.context.Context also
 * propagates io.opentelemetry.context, and propagating io.opentelemetry.context will also propagate
 * io.grpc.context.
 */
public final class ContextStorageOverride extends Context.Storage {

  private final Context.Storage delegate = new OpenTelemetryContextStorage();

  @Override
  public Context doAttach(Context toAttach) {
    return delegate.doAttach(toAttach);
  }

  @Override
  public void detach(Context toDetach, Context toRestore) {
    delegate.detach(toDetach, toRestore);
  }

  @Override
  public Context current() {
    return delegate.current();
  }
}
