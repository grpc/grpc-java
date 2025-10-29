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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

final class ServiceProviders {
  private ServiceProviders() {
    // do not instantiate
  }

  /**
   * If this is not Android, returns all available implementations discovered via
   * {@link ServiceLoader}.
   * If this is Android, returns all available implementations in {@code hardcoded}.
   * The list is sorted in descending priority order.
   */
  //@Deprecated
  public static <T> List<T> loadAll(
      Class<T> klass,
      Iterable<Class<?>> hardcoded,
      ClassLoader cl,
      final PriorityAccessor<T> priorityAccessor) {
    return loadAll(klass, ServiceLoader.load(klass, cl).iterator(), hardcoded, priorityAccessor);
  }

  /**
   * If this is not Android, returns all available implementations discovered via
   * {@link ServiceLoader}.
   * If this is Android, returns all available implementations in {@code hardcoded}.
   * The list is sorted in descending priority order.
   *
   * <p>{@code serviceLoader} should be created with {@code ServiceLoader.load(MyClass.class,
   * MyClass.class.getClassLoader()).iterator()} in order to be detected by R8 so that R8 full mode
   * will keep the constructors for the provider classes.
   */
  public static <T> List<T> loadAll(
      Class<T> klass,
      Iterator<T> serviceLoader,
      Iterable<Class<?>> hardcoded,
      final PriorityAccessor<T> priorityAccessor) {
    Iterator<T> candidates;
    if (serviceLoader instanceof ListIterator) {
      // A rewriting tool has replaced the ServiceLoader with a List of some sort (R8 uses
      // ArrayList, AppReduce uses singletonList). We prefer to use such iterators on Android as
      // they won't need reflection like the hard-coded list does. In addition, the provider
      // instances will have already been created, so it seems we should use them.
      //
      // R8: https://r8.googlesource.com/r8/+/490bc53d9310d4cc2a5084c05df4aadaec8c885d/src/main/java/com/android/tools/r8/ir/optimize/ServiceLoaderRewriter.java
      // AppReduce: service_loader_pass.cc
      candidates = serviceLoader;
    } else if (isAndroid(klass.getClassLoader())) {
      // Avoid getResource() on Android, which must read from a zip which uses a lot of memory
      candidates = getCandidatesViaHardCoded(klass, hardcoded).iterator();
    } else if (!serviceLoader.hasNext()) {
      // Attempt to load using the context class loader and ServiceLoader.
      // This allows frameworks like http://aries.apache.org/modules/spi-fly.html to plug in.
      candidates = ServiceLoader.load(klass).iterator();
    } else {
      candidates = serviceLoader;
    }
    List<T> list = new ArrayList<>();
    while (candidates.hasNext()) {
      T current = candidates.next();
      if (!priorityAccessor.isAvailable(current)) {
        continue;
      }
      list.add(current);
    }

    // Sort descending based on priority.  If priorities are equal, compare the class names to
    // get a reliable result.
    Collections.sort(list, Collections.reverseOrder(new Comparator<T>() {
      @Override
      public int compare(T f1, T f2) {
        int pd = priorityAccessor.getPriority(f1) - priorityAccessor.getPriority(f2);
        if (pd != 0) {
          return pd;
        }
        return f1.getClass().getName().compareTo(f2.getClass().getName());
      }
    }));
    return Collections.unmodifiableList(list);
  }

  /**
   * Returns true if the {@link ClassLoader} is for android.
   */
  static boolean isAndroid(ClassLoader cl) {
    try {
      // Specify a class loader instead of null because we may be running under Robolectric
      Class.forName("android.app.Application", /*initialize=*/ false, cl);
      return true;
    } catch (Exception e) {
      // If Application isn't loaded, it might as well not be Android.
      return false;
    }
  }

  /**
   * For testing only: Loads service providers for the {@code klass} service using {@link
   * ServiceLoader}. Does not support spi-fly and related tricks.
   */
  @VisibleForTesting
  public static <T> Iterable<T> getCandidatesViaServiceLoader(Class<T> klass, ClassLoader cl) {
    Iterable<T> i = ServiceLoader.load(klass, cl);
    if (!i.iterator().hasNext()) {
      return null;
    }
    return i;
  }

  /**
   * Load providers from a hard-coded list. This avoids using getResource(), which has performance
   * problems on Android (see https://github.com/grpc/grpc-java/issues/2037).
   */
  @VisibleForTesting
  static <T> Iterable<T> getCandidatesViaHardCoded(Class<T> klass, Iterable<Class<?>> hardcoded) {
    List<T> list = new ArrayList<>();
    for (Class<?> candidate : hardcoded) {
      T t = createForHardCoded(klass, candidate);
      if (t == null) {
        continue;
      }
      list.add(t);
    }
    return list;
  }

  private static <T> T createForHardCoded(Class<T> klass, Class<?> rawClass) {
    try {
      return rawClass.asSubclass(klass).getConstructor().newInstance();
    } catch (ClassCastException ex) {
      // Tools like Proguard that perform obfuscation rewrite strings only when the class they
      // reference is known, as otherwise they wouldn't know its new name. This means some
      // hard-coded Class.forNames() won't be rewritten. This can cause ClassCastException at
      // runtime if the class ends up appearing on the classpath but that class is part of a
      // separate copy of grpc. With tools like Maven Shade Plugin the class wouldn't be found at
      // all and so would be skipped. We want to skip in this case as well.
      return null;
    } catch (Throwable t) {
      throw new ServiceConfigurationError(
          String.format("Provider %s could not be instantiated %s", rawClass.getName(), t), t);
    }
  }

  /**
   * An interface that allows us to get priority information about a provider.
   */
  public interface PriorityAccessor<T> {
    /**
     * Checks this provider is available for use, taking the current environment into consideration.
     * If {@code false}, no other methods are safe to be called.
     */
    boolean isAvailable(T provider);

    /**
     * A priority, from 0 to 10 that this provider should be used, taking the current environment
     * into consideration. 5 should be considered the default, and then tweaked based on environment
     * detection. A priority of 0 does not imply that the provider wouldn't work; just that it
     * should be last in line.
     */
    int getPriority(T provider);
  }
}
