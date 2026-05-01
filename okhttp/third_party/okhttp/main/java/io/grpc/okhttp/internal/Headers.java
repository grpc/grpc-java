/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/*
 * Forked from OkHttp 2.7.0 com.squareup.okhttp.Headers
 */
package io.grpc.okhttp.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The header fields of a single HTTP message. Values are uninterpreted strings;
 *
 * <p>This class trims whitespace from values. It never returns values with
 * leading or trailing whitespace.
 *
 * <p>Instances of this class are immutable. Use {@link Builder} to create
 * instances.
 */
public final class Headers {
  private final String[] namesAndValues;

  private Headers(Builder builder) {
    this.namesAndValues = builder.namesAndValues.toArray(new String[builder.namesAndValues.size()]);
  }

  /** Returns the last value corresponding to the specified field, or null. */
  public String get(String name) {
    return get(namesAndValues, name);
  }

  /** Returns the number of field values. */
  public int size() {
    return namesAndValues.length / 2;
  }

  /** Returns the field at {@code position} or null if that is out of range. */
  public String name(int index) {
    int nameIndex = index * 2;
    if (nameIndex < 0 || nameIndex >= namesAndValues.length) {
      return null;
    }
    return namesAndValues[nameIndex];
  }

  /** Returns the value at {@code index} or null if that is out of range. */
  public String value(int index) {
    int valueIndex = index * 2 + 1;
    if (valueIndex < 0 || valueIndex >= namesAndValues.length) {
      return null;
    }
    return namesAndValues[valueIndex];
  }

  public Builder newBuilder() {
    Builder result = new Builder();
    Collections.addAll(result.namesAndValues, namesAndValues);
    return result;
  }

  @Override public String toString() {
    StringBuilder result = new StringBuilder();
    for (int i = 0, size = size(); i < size; i++) {
      result.append(name(i)).append(": ").append(value(i)).append("\n");
    }
    return result.toString();
  }

  private static String get(String[] namesAndValues, String name) {
    for (int i = namesAndValues.length - 2; i >= 0; i -= 2) {
      if (name.equalsIgnoreCase(namesAndValues[i])) {
        return namesAndValues[i + 1];
      }
    }
    return null;
  }

  public static final class Builder {
    private final List<String> namesAndValues = new ArrayList<>(20);

    /**
     * Add a field with the specified value without any validation. Only
     * appropriate for headers from the remote peer or cache.
     */
    Builder addLenient(String name, String value) {
      namesAndValues.add(name);
      namesAndValues.add(value.trim());
      return this;
    }

    public Builder removeAll(String name) {
      for (int i = 0; i < namesAndValues.size(); i += 2) {
        if (name.equalsIgnoreCase(namesAndValues.get(i))) {
          namesAndValues.remove(i); // name
          namesAndValues.remove(i); // value
          i -= 2;
        }
      }
      return this;
    }

    /**
     * Set a field with the specified value. If the field is not found, it is
     * added. If the field is found, the existing values are replaced.
     */
    public Builder set(String name, String value) {
      checkNameAndValue(name, value);
      removeAll(name);
      addLenient(name, value);
      return this;
    }

    private void checkNameAndValue(String name, String value) {
      if (name == null) throw new IllegalArgumentException("name == null");
      if (name.isEmpty()) throw new IllegalArgumentException("name is empty");
      for (int i = 0, length = name.length(); i < length; i++) {
        char c = name.charAt(i);
        if (c <= '\u001f' || c >= '\u007f') {
          throw new IllegalArgumentException(String.format(
              Locale.US,
              "Unexpected char %#04x at %d in header name: %s", (int) c, i, name));
        }
      }
      if (value == null) throw new IllegalArgumentException("value == null");
      for (int i = 0, length = value.length(); i < length; i++) {
        char c = value.charAt(i);
        if (c <= '\u001f' || c >= '\u007f') {
          throw new IllegalArgumentException(String.format(
              Locale.US,
              "Unexpected char %#04x at %d in header value: %s", (int) c, i, value));
        }
      }
    }

    public Headers build() {
      return new Headers(this);
    }
  }
}
