/*
 * Copyright 2017, gRPC Authors All rights reserved.
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
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

final class ServiceProviders {
  private static final Logger logger = Logger.getLogger(ServiceProviders.class.getName());

  private ServiceProviders() {
    // do not instantiate
  }

  /**
   * If this is not Android, returns the highest priority implementation of the class via
   * {@link ServiceLoader}.
   * If this is Android, returns an instance of the highest priority class in {@code hardcoded}.
   */
  public static <T extends ServiceProvider> T load(
      Class<T> klass, List<Class<?>> hardcoded, ClassLoader cl) {
    List<T> candidates = loadAll(klass, hardcoded, cl);
    if (candidates.isEmpty()) {
      return null;
    }
    return candidates.get(0);
  }

  /**
   * If this is not Android, returns all available implementations discovered via
   * {@link ServiceLoader}.
   * If this is Android, returns all available implementations in {@code hardcoded}.
   * The list is sorted in descending priority order.
   *
   * <p>If a failure was encountered while initializing any class, then the result is empty.
   */
  public static <T extends ServiceProvider> List<T> loadAll(
      Class<T> klass, List<Class<?>> hardcoded, ClassLoader cl) {
    Iterable<T> candidates;
    if (isAndroid(cl)) {
      candidates = getCandidatesViaHardCoded(klass, hardcoded);
    } else {
      candidates = getCandidatesViaServiceLoader(klass, cl);
    }
    List<T> list = new ArrayList<T>();
    Iterator<T> iter = candidates.iterator();
    try {
      while (iter.hasNext()) {
        T current = iter.next();
        if (!current.isAvailable()) {
          continue;
        }
        list.add(current);
      }

      // Sort descending based on priority.
      Collections.sort(list, Collections.reverseOrder(new Comparator<ServiceProvider>() {
        @Override
        public int compare(ServiceProvider f1, ServiceProvider f2) {
          return f1.priority() - f2.priority();
        }
      }));
      return Collections.unmodifiableList(list);
    } catch (Throwable t) {
      // The iterator from ServiceLoader may throw ServiceConfigurationError, or
      // the ServiceProvider may thrown some runtime exception when its methods are called
      logger.log(
          Level.SEVERE,
          String.format("caught exception trying to load: %s. Will now abort.", klass),
          t);
      return Collections.emptyList();
    }
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
   * Loads service providers for the {@link ServiceProvider} service using {@link ServiceLoader}.
   */
  @VisibleForTesting
  public static <T> Iterable<T> getCandidatesViaServiceLoader(Class<T> klass, ClassLoader cl) {
    Iterable<T> i = ServiceLoader.load(klass, cl);
    // Attempt to load using the context class loader and ServiceLoader.
    // This allows frameworks like http://aries.apache.org/modules/spi-fly.html to plug in.
    if (!i.iterator().hasNext()) {
      i = ServiceLoader.load(klass);
    }
    return i;
  }

  /**
   * Load providers from a hard-coded list. This avoids using getResource(), which has performance
   * problems on Android (see https://github.com/grpc/grpc-java/issues/2037).
   */
  @VisibleForTesting
  static <T> Iterable<T> getCandidatesViaHardCoded(Class<T> klass, List<Class<?>> hardcoded) {
    // Class.forName(String) is used to remove the need for ProGuard configuration. Note that
    // ProGuard does not detect usages of Class.forName(String, boolean, ClassLoader):
    // https://sourceforge.net/p/proguard/bugs/418/
    List<T> list = new ArrayList<T>();
    for (Class<?> candidate : hardcoded) {
      try {
        list.add(create(klass, candidate));
      } catch (Throwable t) {
        logger.log(Level.SEVERE, "caught exception trying to create via hardcoded: " + klass, t);
        return Collections.emptyList();
      }
    }
    return list;
  }

  @VisibleForTesting
  static <T> T create(Class<T> klass, Class<?> rawClass) {
    try {
      return rawClass.asSubclass(klass).getConstructor().newInstance();
    } catch (Throwable t) {
      throw new ServiceConfigurationError(
          String.format("Provider %s could not be instantiated %s", rawClass.getName(), t), t);
    }
  }
}
