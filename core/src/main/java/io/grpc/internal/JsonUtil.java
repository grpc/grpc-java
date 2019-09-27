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

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Helper utility to work with JSON values in Java types.
 */
public class JsonUtil {
  /**
   * Gets a list from an object for the given key.  If the key is not present, this returns null.
   * If the value is not a List, throws an exception.
   */
  @SuppressWarnings("unchecked")
  @Nullable
  public static List<?> getList(Map<String, ?> obj, String key) {
    assert key != null;
    if (!obj.containsKey(key)) {
      return null;
    }
    Object value = obj.get(key);
    if (!(value instanceof List)) {
      throw new ClassCastException(
          String.format("value '%s' for key '%s' in '%s' is not List", value, key, obj));
    }
    return (List<?>) value;
  }

  /**
   * Gets an object from an object for the given key.  If the key is not present, this returns null.
   * If the value is not a Map, throws an exception.
   */
  @SuppressWarnings("unchecked")
  @Nullable
  public static Map<String, ?> getObject(Map<String, ?> obj, String key) {
    assert key != null;
    if (!obj.containsKey(key)) {
      return null;
    }
    Object value = obj.get(key);
    if (!(value instanceof Map)) {
      throw new ClassCastException(
          String.format("value '%s' for key '%s' in '%s' is not object", value, key, obj));
    }
    return (Map<String, ?>) value;
  }

  /**
   * Gets a double from an object for the given key.  If the key is not present, this returns null.
   * If the value is not a Double, throws an exception.
   */
  @Nullable
  public static Double getDouble(Map<String, ?> obj, String key) {
    assert key != null;
    if (!obj.containsKey(key)) {
      return null;
    }
    Object value = obj.get(key);
    if (!(value instanceof Double)) {
      throw new ClassCastException(
          String.format("value '%s' for key '%s' in '%s' is not Double", value, key, obj));
    }
    return (Double) value;
  }

  /**
   * Gets a string from an object for the given key.  If the key is not present, this returns null.
   * If the value is not a String, throws an exception.
   */
  @Nullable
  public static String getString(Map<String, ?> obj, String key) {
    assert key != null;
    if (!obj.containsKey(key)) {
      return null;
    }
    Object value = obj.get(key);
    if (!(value instanceof String)) {
      throw new ClassCastException(
          String.format("value '%s' for key '%s' in '%s' is not String", value, key, obj));
    }
    return (String) value;
  }

  /**
   * Gets a boolean from an object for the given key.  If the key is not present, this returns null.
   * If the value is not a Boolean, throws an exception.
   */
  @Nullable
  public static Boolean getBoolean(Map<String, ?> obj, String key) {
    assert key != null;
    if (!obj.containsKey(key)) {
      return null;
    }
    Object value = obj.get(key);
    if (!(value instanceof Boolean)) {
      throw new ClassCastException(
          String.format("value '%s' for key '%s' in '%s' is not Boolean", value, key, obj));
    }
    return (Boolean) value;
  }
}
