/*
 * Copyright 2019 The gRPC Authors
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.Provider;

/**
 * Utility to load dynamically Conscrypt when it is available.
 */
public final class ConscryptLoader {
  private static final Method NEW_PROVIDER_METHOD;
  private static final Method IS_CONSCRYPT_METHOD;

  static {
    Method newProvider;
    Method isConscrypt;
    try {
      Class<?> conscryptClass = Class.forName("org.conscrypt.Conscrypt");
      newProvider = conscryptClass.getMethod("newProvider");
      isConscrypt = conscryptClass.getMethod("isConscrypt", Provider.class);
    } catch (ClassNotFoundException ex) {
      newProvider = null;
      isConscrypt = null;
    } catch (NoSuchMethodException ex) {
      throw new AssertionError(ex);
    }
    NEW_PROVIDER_METHOD = newProvider;
    IS_CONSCRYPT_METHOD = isConscrypt;
  }

  /**
   * Returns {@code true} when the Conscrypt Java classes are available. Does not imply it actually
   * works on this platform.
   */
  public static boolean isPresent() {
    return NEW_PROVIDER_METHOD != null;
  }

  /** Same as {@code Conscrypt.isConscrypt(Provider)}. */
  public static boolean isConscrypt(Provider provider) {
    if (!isPresent()) {
      return false;
    }
    try {
      return (Boolean) IS_CONSCRYPT_METHOD.invoke(null, provider);
    } catch (IllegalAccessException ex) {
      throw new AssertionError(ex);
    } catch (InvocationTargetException ex) {
      throw new AssertionError(ex);
    }
  }

  /** Same as {@code Conscrypt.newProvider()}. */
  public static Provider newProvider() throws Throwable {
    if (!isPresent()) {
      Class.forName("org.conscrypt.Conscrypt");
      throw new AssertionError("Unexpected failure referencing Conscrypt class");
    }
    // Exceptions here probably mean something's wrong with the JNI loading. Maybe the platform is
    // not supported. It's an error, but it may occur in some environments as part of normal
    // operation. It's too hard to distinguish "normal" from "abnormal" failures here.
    return (Provider) NEW_PROVIDER_METHOD.invoke(null);
  }
}
