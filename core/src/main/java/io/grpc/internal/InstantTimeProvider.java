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

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * {@link InstantTimeProvider} resolves InstantTimeProvider which implements {@link TimeProvider}.
 */
final class InstantTimeProvider implements TimeProvider {
  private Method now;
  private Method getNano;
  private Method getEpochSecond;

  public InstantTimeProvider(Class<?> instantClass) {
    try {
      this.now = instantClass.getMethod("now");
      this.getNano = instantClass.getMethod("getNano");
      this.getEpochSecond = instantClass.getMethod("getEpochSecond");
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public long currentTimeNanos() {
    try {
      Object instant = now.invoke(null);
      int nanos = (int) getNano.invoke(instant);
      long epochSeconds = (long) getEpochSecond.invoke(instant);
      return TimeUnit.SECONDS.toNanos(epochSeconds) + nanos;
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }
}
