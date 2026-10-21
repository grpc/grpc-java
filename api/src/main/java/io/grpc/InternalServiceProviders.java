/*
 * Copyright 2017 The gRPC Authors
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

package io.grpc;

import com.google.common.annotations.VisibleForTesting;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

@Internal
public final class InternalServiceProviders {
  private InternalServiceProviders() {
  }

  /**
   * Accessor for method.
   */
  @Deprecated
  public static <T> List<T> loadAll(
      Class<T> klass,
      Iterable<Class<?>> hardCodedClasses,
      ClassLoader classLoader,
      PriorityAccessor<T> priorityAccessor) {
    return loadAll(
        klass,
        ServiceLoader.load(klass, classLoader).iterator(),
        () -> hardCodedClasses,
        priorityAccessor);
  }

  /**
   * Accessor for method.
   */
  public static <T> List<T> loadAll(
      Class<T> klass,
      Iterator<T> serviceLoader,
      Supplier<Iterable<Class<?>>> hardCodedClasses,
      PriorityAccessor<T> priorityAccessor) {
    return ServiceProviders.loadAll(klass, serviceLoader, hardCodedClasses::get, priorityAccessor);
  }

  /**
   * Accessor for method.
   */
  @VisibleForTesting
  public static <T> Iterable<T> getCandidatesViaServiceLoader(Class<T> klass, ClassLoader cl) {
    return ServiceProviders.getCandidatesViaServiceLoader(klass, cl);
  }

  /**
   * Accessor for method.
   */
  @VisibleForTesting
  public static <T> Iterable<T> getCandidatesViaHardCoded(
      Class<T> klass, Iterable<Class<?>> hardcoded) {
    return ServiceProviders.getCandidatesViaHardCoded(klass, hardcoded);
  }

  /**
   * Accessor for {@link ServiceProviders#isAndroid}.
   */
  public static boolean isAndroid(ClassLoader cl) {
    return ServiceProviders.isAndroid(cl);
  }

  public interface PriorityAccessor<T> extends ServiceProviders.PriorityAccessor<T> {}

  public interface Supplier<T> {
    T get();
  }
}
