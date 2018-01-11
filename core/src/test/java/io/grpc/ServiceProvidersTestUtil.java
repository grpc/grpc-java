/*
 * Copyright 2018, gRPC Authors All rights reserved.
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

public final class ServiceProvidersTestUtil {

  /**
   * Creates an iterator of the iterable class {@code callableClassName} via reflection.
   */
  public static Iterator<?> invokeCallable(
      String callableClassName,
      ClassLoader cl,
      final Set<String> classLoaderHistory) throws Exception {
    cl = new ClassLoader(cl) {
      @Override
      public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        classLoaderHistory.add(name);
        return super.loadClass(name, resolve);
      }
    };

    cl = new StaticTestingClassLoader(cl, Pattern.compile("io\\.grpc\\.[^.]*"));

    return invokeIteratorCallable(callableClassName, cl);
  }

  private static Iterator<?> invokeIteratorCallable(
      String callableClassName, ClassLoader cl) throws Exception {
    Class<?> klass = Class.forName(callableClassName, true, cl);
    Constructor<?> ctor = klass.getDeclaredConstructor();
    Object instance = ctor.newInstance();
    Method callMethod = klass.getMethod("call");
    return (Iterator<?>) callMethod.invoke(instance);
  }
}
