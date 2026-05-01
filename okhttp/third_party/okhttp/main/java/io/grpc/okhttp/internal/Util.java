/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Forked from OkHttp 2.5.0
 */

package io.grpc.okhttp.internal;

import java.lang.reflect.Array;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Junk drawer of utility methods. */
public final class Util {
  public static final String[] EMPTY_STRING_ARRAY = new String[0];

  /** A cheap and type-safe constant for the UTF-8 Charset. */
  public static final Charset UTF_8 = Charset.forName("UTF-8");

  private Util() {
  }

  /** Returns true if two possibly-null objects are equal. */
  public static boolean equal(Object a, Object b) {
    return a == b || (a != null && a.equals(b));
  }

  /** Returns an immutable list containing {@code elements}. */
  public static <T> List<T> immutableList(T[] elements) {
    return Collections.unmodifiableList(Arrays.asList(elements.clone()));
  }

  /**
   * Returns an array containing containing only elements found in {@code first}  and also in
   * {@code second}. The returned elements are in the same order as in {@code first}.
   */
  @SuppressWarnings("unchecked")
  public static <T> T[] intersect(Class<T> arrayType, T[] first, T[] second) {
    List<T> result = intersect(first, second);
    return result.toArray((T[]) Array.newInstance(arrayType, result.size()));
  }

  /**
   * Returns a list containing containing only elements found in {@code first}  and also in
   * {@code second}. The returned elements are in the same order as in {@code first}.
   */
  private static <T> List<T> intersect(T[] first, T[] second) {
    List<T> result = new ArrayList<>();
    for (T a : first) {
      for (T b : second) {
        if (a.equals(b)) {
          result.add(b);
          break;
        }
      }
    }
    return result;
  }
}
