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
 * {@link TimeProviderResolverFactory} resolves Time providers.
 */

final class TimeProviderResolverFactory {

  private static final Class<T> INSTANT_CLASS = initInstant();

  private static Class<?> initInstant() {
    try {
      return Class.forName("java.time.Instant");
    } catch (Exception ex) {
      return null;
    }
  }

  static TimeProvider resolveTimeProvider() {
    if (INSTANT_CLASS == null) {
      return new ConcurrentTimeProvider();
    } else {
      return new InstantTimeProvider(INSTANT_CLASS);
    }
  }
}
