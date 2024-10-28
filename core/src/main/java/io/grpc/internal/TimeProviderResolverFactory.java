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
  static TimeProvider resolveTimeProvider() {
    try {
      Class<?> instantClass = Class.forName("java.time.Instant");
      return new InstantTimeProvider(instantClass);
    } catch (ClassNotFoundException ex) {
      return new ConcurrentTimeProvider();
    } catch (NoSuchMethodException ex) {
      throw new AssertionError(ex);
    }
  }
}
