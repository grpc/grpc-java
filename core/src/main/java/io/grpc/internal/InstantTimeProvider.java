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

package io.grpc.internal;

/**
 * {@link InstantTimeProvider} resolves InstantTimeProvider which implements {@link TimeProvider}.
 */

final class InstantTimeProvider implements TimeProvider {

  private Class<?> instantClass;

  public InstantTimeProvider(Class<?> instantClass) {
    this.instantClass = instantClass;
  }

  @Override
  public long currentTimeNanos() {
    try {
      Object instant = instantClass.getMethod("now").invoke(null);
      int nanos = (int) instantClass.getMethod("getNano").invoke(instant);
      long epochSeconds = (long) instantClass.getMethod("getEpochSecond").invoke(instant);
      return epochSeconds * 1_000_000_000L + nanos;
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }
}
