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

import static com.google.common.math.LongMath.checkedAdd;

import java.text.ParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Helper utility to work with JSON values in Java types. Includes the JSON dialect used by
 * Protocol Buffers.
 */
public class JsonUtil {
  /**
   * Gets a list from an object for the given key.  If the key is not present, this returns null.
   * If the value is not a List, throws an exception.
   */
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
   * Gets a list from an object for the given key, and verifies all entries are objects.  If the key
   * is not present, this returns null.  If the value is not a List or an entry is not an object,
   * throws an exception.
   */
  @Nullable
  public static List<Map<String, ?>> getListOfObjects(Map<String, ?> obj, String key) {
    List<?> list = getList(obj, key);
    if (list == null) {
      return null;
    }
    return checkObjectList(list);
  }

  /**
   * Gets a list from an object for the given key, and verifies all entries are strings.  If the key
   * is not present, this returns null.  If the value is not a List or an entry is not a string,
   * throws an exception.
   */
  @Nullable
  public static List<String> getListOfStrings(Map<String, ?> obj, String key) {
    List<?> list = getList(obj, key);
    if (list == null) {
      return null;
    }
    return checkStringList(list);
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
   * Gets a number from an object for the given key.  If the key is not present, this returns null.
   * If the value is not a Double, throws an exception.
   */
  @Nullable
  public static Double getNumber(Map<String, ?> obj, String key) {
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
   * Gets a number from an object for the given key, casted to an integer.  If the key is not
   * present, this returns null.  If the value is not a Double or loses precision when cast to an
   * integer, throws an exception.
   */
  public static Integer getNumberAsInteger(Map<String, ?> obj, String key) {
    Double d = getNumber(obj, key);
    if (d == null) {
      return null;
    }
    int i = d.intValue();
    if (i != d) {
      throw new ClassCastException("Number expected to be integer: " + d);
    }
    return i;
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
   * Gets a string from an object for the given key, parsed as a duration (defined by protobuf).  If
   * the key is not present, this returns null.  If the value is not a String or not properly
   * formatted, throws an exception.
   */
  public static Long getStringAsDuration(Map<String, ?> obj, String key) {
    String value = getString(obj, key);
    if (value == null) {
      return null;
    }
    try {
      return parseDuration(value);
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
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

  /**
   * Casts a list of unchecked JSON values to a list of checked objects in Java type.
   * If the given list contains a value that is not a Map, throws an exception.
   */
  @SuppressWarnings("unchecked")
  public static List<Map<String, ?>> checkObjectList(List<?> rawList) {
    for (int i = 0; i < rawList.size(); i++) {
      if (!(rawList.get(i) instanceof Map)) {
        throw new ClassCastException(
            String.format("value %s for idx %d in %s is not object", rawList.get(i), i, rawList));
      }
    }
    return (List<Map<String, ?>>) rawList;
  }

  /**
   * Casts a list of unchecked JSON values to a list of String. If the given list
   * contains a value that is not a String, throws an exception.
   */
  @SuppressWarnings("unchecked")
  public static List<String> checkStringList(List<?> rawList) {
    for (int i = 0; i < rawList.size(); i++) {
      if (!(rawList.get(i) instanceof String)) {
        throw new ClassCastException(
            String.format(
                "value '%s' for idx %d in '%s' is not string", rawList.get(i), i, rawList));
      }
    }
    return (List<String>) rawList;
  }

  private static final long DURATION_SECONDS_MIN = -315576000000L;
  private static final long DURATION_SECONDS_MAX = 315576000000L;

  /**
   * Parse from a string to produce a duration.  Copy of
   * {@link com.google.protobuf.util.Durations#parse}.
   *
   * @return A Duration parsed from the string.
   * @throws ParseException if parsing fails.
   */
  private static long parseDuration(String value) throws ParseException {
    // Must ended with "s".
    if (value.isEmpty() || value.charAt(value.length() - 1) != 's') {
      throw new ParseException("Invalid duration string: " + value, 0);
    }
    boolean negative = false;
    if (value.charAt(0) == '-') {
      negative = true;
      value = value.substring(1);
    }
    String secondValue = value.substring(0, value.length() - 1);
    String nanoValue = "";
    int pointPosition = secondValue.indexOf('.');
    if (pointPosition != -1) {
      nanoValue = secondValue.substring(pointPosition + 1);
      secondValue = secondValue.substring(0, pointPosition);
    }
    long seconds = Long.parseLong(secondValue);
    int nanos = nanoValue.isEmpty() ? 0 : parseNanos(nanoValue);
    if (seconds < 0) {
      throw new ParseException("Invalid duration string: " + value, 0);
    }
    if (negative) {
      seconds = -seconds;
      nanos = -nanos;
    }
    try {
      return normalizedDuration(seconds, nanos);
    } catch (IllegalArgumentException e) {
      throw new ParseException("Duration value is out of range.", 0);
    }
  }

  /**
   * Copy of {@link com.google.protobuf.util.Timestamps#parseNanos}.
   */
  private static int parseNanos(String value) throws ParseException {
    int result = 0;
    for (int i = 0; i < 9; ++i) {
      result = result * 10;
      if (i < value.length()) {
        if (value.charAt(i) < '0' || value.charAt(i) > '9') {
          throw new ParseException("Invalid nanoseconds.", 0);
        }
        result += value.charAt(i) - '0';
      }
    }
    return result;
  }

  private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

  /**
   * Copy of {@link com.google.protobuf.util.Durations#normalizedDuration}.
   */
  @SuppressWarnings("NarrowingCompoundAssignment")
  private static long normalizedDuration(long seconds, int nanos) {
    if (nanos <= -NANOS_PER_SECOND || nanos >= NANOS_PER_SECOND) {
      seconds = checkedAdd(seconds, nanos / NANOS_PER_SECOND);
      nanos %= NANOS_PER_SECOND;
    }
    if (seconds > 0 && nanos < 0) {
      nanos += NANOS_PER_SECOND; // no overflow since nanos is negative (and we're adding)
      seconds--; // no overflow since seconds is positive (and we're decrementing)
    }
    if (seconds < 0 && nanos > 0) {
      nanos -= NANOS_PER_SECOND; // no overflow since nanos is positive (and we're subtracting)
      seconds++; // no overflow since seconds is negative (and we're incrementing)
    }
    if (!durationIsValid(seconds, nanos)) {
      throw new IllegalArgumentException(String.format(
          "Duration is not valid. See proto definition for valid values. "
              + "Seconds (%s) must be in range [-315,576,000,000, +315,576,000,000]. "
              + "Nanos (%s) must be in range [-999,999,999, +999,999,999]. "
              + "Nanos must have the same sign as seconds", seconds, nanos));
    }
    return saturatedAdd(TimeUnit.SECONDS.toNanos(seconds), nanos);
  }

  /**
   * Returns true if the given number of seconds and nanos is a valid {@code Duration}. The {@code
   * seconds} value must be in the range [-315,576,000,000, +315,576,000,000]. The {@code nanos}
   * value must be in the range [-999,999,999, +999,999,999].
   *
   * <p><b>Note:</b> Durations less than one second are represented with a 0 {@code seconds} field
   * and a positive or negative {@code nanos} field. For durations of one second or more, a non-zero
   * value for the {@code nanos} field must be of the same sign as the {@code seconds} field.
   *
   * <p>Copy of {@link com.google.protobuf.util.Duration#isValid}.</p>
   */
  private static boolean durationIsValid(long seconds, int nanos) {
    if (seconds < DURATION_SECONDS_MIN || seconds > DURATION_SECONDS_MAX) {
      return false;
    }
    if (nanos < -999999999L || nanos >= NANOS_PER_SECOND) {
      return false;
    }
    if (seconds < 0 || nanos < 0) {
      if (seconds > 0 || nanos > 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns the sum of {@code a} and {@code b} unless it would overflow or underflow in which case
   * {@code Long.MAX_VALUE} or {@code Long.MIN_VALUE} is returned, respectively.
   *
   * <p>Copy of {@link com.google.common.math.LongMath#saturatedAdd}.</p>
   *
   */
  @SuppressWarnings("ShortCircuitBoolean")
  private static long saturatedAdd(long a, long b) {
    long naiveSum = a + b;
    if ((a ^ b) < 0 | (a ^ naiveSum) >= 0) {
      // If a and b have different signs or a has the same sign as the result then there was no
      // overflow, return.
      return naiveSum;
    }
    // we did over/under flow, if the sign is negative we should return MAX otherwise MIN
    return Long.MAX_VALUE + ((naiveSum >>> (Long.SIZE - 1)) ^ 1);
  }
}
