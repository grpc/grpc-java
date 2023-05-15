/*
 * Copyright 2021, gRPC Authors All rights reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link JsonUtil}. */
@RunWith(JUnit4.class)
public class JsonUtilTest {
  @Test
  public void getNumber() {
    Map<String, Object> map = new HashMap<>();
    map.put("key_number_1", 1D);
    map.put("key_string_2.0", "2.0");
    map.put("key_string_3", "3");
    map.put("key_string_nan", "NaN");
    map.put("key_number_5.5", 5.5D);
    map.put("key_string_six", "six");
    map.put("key_number_7", 7F);
    map.put("key_string_infinity", "Infinity");
    map.put("key_string_minus_infinity", "-Infinity");
    map.put("key_string_exponent", "2.998e8");
    map.put("key_string_minus_zero", "-0");
    map.put("key_string_boolean", true);

    assertThat(JsonUtil.getNumberAsDouble(map, "key_number_1")).isEqualTo(1D);
    assertThat(JsonUtil.getNumberAsInteger(map, "key_number_1")).isEqualTo(1);
    assertThat(JsonUtil.getNumberAsLong(map, "key_number_1")).isEqualTo(1L);

    assertThat(JsonUtil.getNumberAsDouble(map, "key_string_2.0")).isEqualTo(2D);
    assertThat(JsonUtil.getNumberAsFloat(map, "key_string_2.0")).isEqualTo(2F);
    try {
      JsonUtil.getNumberAsInteger(map, "key_string_2.0");
      fail("expecting to throw but did not");
    } catch (RuntimeException e) {
      assertThat(e).hasMessageThat().isEqualTo(
          "value '2.0' for key 'key_string_2.0' is not an integer");
    }
    try {
      JsonUtil.getNumberAsLong(map, "key_string_2.0");
      fail("expecting to throw but did not");
    } catch (RuntimeException e) {
      assertThat(e).hasMessageThat().isEqualTo(
          "value '2.0' for key 'key_string_2.0' is not a long integer");
    }

    assertThat(JsonUtil.getNumberAsDouble(map, "key_string_3")).isEqualTo(3D);
    assertThat(JsonUtil.getNumberAsInteger(map, "key_string_3")).isEqualTo(3);
    assertThat(JsonUtil.getNumberAsLong(map, "key_string_3")).isEqualTo(3L);
    assertThat(JsonUtil.getNumberAsFloat(map, "key_string_3")).isEqualTo(3F);

    assertThat(JsonUtil.getNumberAsDouble(map, "key_string_nan")).isNaN();
    assertThat(JsonUtil.getNumberAsFloat(map, "key_string_nan")).isNaN();
    try {
      JsonUtil.getNumberAsInteger(map, "key_string_nan");
      fail("expecting to throw but did not");
    } catch (RuntimeException e) {
      assertThat(e).hasMessageThat().isEqualTo(
          "value 'NaN' for key 'key_string_nan' is not an integer");
    }
    try {
      JsonUtil.getNumberAsLong(map, "key_string_nan");
      fail("expecting to throw but did not");
    } catch (RuntimeException e) {
      assertThat(e).hasMessageThat().isEqualTo(
          "value 'NaN' for key 'key_string_nan' is not a long integer");
    }

    assertThat(JsonUtil.getNumberAsDouble(map, "key_number_5.5")).isEqualTo(5.5D);
    try {
      JsonUtil.getNumberAsInteger(map, "key_number_5.5");
      fail("expecting to throw but did not");
    } catch (RuntimeException e) {
      assertThat(e).hasMessageThat().isEqualTo("Number expected to be integer: 5.5");
    }
    try {
      JsonUtil.getNumberAsLong(map, "key_number_5.5");
      fail("expecting to throw but did not");
    } catch (RuntimeException e) {
      assertThat(e).hasMessageThat().isEqualTo("Number expected to be long: 5.5");
    }

    try {
      JsonUtil.getNumberAsDouble(map, "key_string_six");
      fail("expecting to throw but did not");
    } catch (RuntimeException e) {
      assertThat(e).hasMessageThat().isEqualTo(
          "value 'six' for key 'key_string_six' is not a double");
    }
    try {
      JsonUtil.getNumberAsInteger(map, "key_string_six");
      fail("expecting to throw but did not");
    } catch (RuntimeException e) {
      assertThat(e).hasMessageThat().isEqualTo(
          "value 'six' for key 'key_string_six' is not an integer");
    }
    try {
      JsonUtil.getNumberAsLong(map, "key_string_six");
      fail("expecting to throw but did not");
    } catch (RuntimeException e) {
      assertThat(e).hasMessageThat().isEqualTo(
          "value 'six' for key 'key_string_six' is not a long integer");
    }
    try {
      JsonUtil.getNumberAsFloat(map, "key_string_six");
      fail("expecting to throw but did not");
    } catch (RuntimeException e) {
      assertThat(e).hasMessageThat().isEqualTo(
          "string value 'six' for key 'key_string_six' cannot be parsed as a float");
    }

    assertThat(JsonUtil.getNumberAsFloat(map, "key_number_7")).isEqualTo(7F);

    assertThat(JsonUtil.getNumberAsDouble(map, "key_string_infinity")).isPositiveInfinity();
    assertThat(JsonUtil.getNumberAsDouble(map, "key_string_minus_infinity")).isNegativeInfinity();
    assertThat(JsonUtil.getNumberAsDouble(map, "key_string_exponent")).isEqualTo(2.998e8D);

    assertThat(JsonUtil.getNumberAsFloat(map, "key_string_infinity")).isPositiveInfinity();
    assertThat(JsonUtil.getNumberAsFloat(map, "key_string_minus_infinity")).isNegativeInfinity();
    assertThat(JsonUtil.getNumberAsFloat(map, "key_string_exponent")).isEqualTo(2.998e8F);

    assertThat(JsonUtil.getNumberAsDouble(map, "key_string_minus_zero")).isZero();
    assertThat(JsonUtil.getNumberAsInteger(map, "key_string_minus_zero")).isEqualTo(0);
    assertThat(JsonUtil.getNumberAsLong(map, "key_string_minus_zero")).isEqualTo(0L);
    assertThat(JsonUtil.getNumberAsFloat(map, "key_string_minus_zero")).isZero();

    assertThat(JsonUtil.getNumberAsDouble(map, "key_nonexistent")).isNull();
    assertThat(JsonUtil.getNumberAsInteger(map, "key_nonexistent")).isNull();
    assertThat(JsonUtil.getNumberAsLong(map, "key_nonexistent")).isNull();
    assertThat(JsonUtil.getNumberAsFloat(map, "key_nonexistent")).isNull();

    try {
      JsonUtil.getNumberAsFloat(map, "key_string_boolean");
      fail("expecting to throw but did not");
    } catch (RuntimeException e) {
      assertThat(e).hasMessageThat().isEqualTo(
          "value true for key 'key_string_boolean' is not a float");
    }
  }

  @Test
  public void getObject_mapExplicitNullValue() {
    Map<String, ?> mapWithNullValue = Collections.singletonMap("key", null);
    try {
      JsonUtil.getObject(mapWithNullValue, "key");
      fail("ClassCastException expected");
    } catch (ClassCastException e) {
      assertThat(e).hasMessageThat()
          .isEqualTo("value 'null' for key 'key' in '{key=null}' is not object");
    }
  }
}
