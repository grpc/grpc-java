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
    map.put("k1", 1D);
    map.put("k2", "2.0");
    map.put("k3", "3");
    map.put("k4", "NaN");
    map.put("k5", 5.5D);
    map.put("k6", "six");
    map.put("key_string_infinity", "Infinity");
    map.put("key_string_minus_infinity", "-Infinity");
    map.put("key_string_minus_zero", "-0");

    assertThat(JsonUtil.getNumberAsDouble(map, "k1")).isEqualTo(1D);
    assertThat(JsonUtil.getNumberAsInteger(map, "k1")).isEqualTo(1);
    assertThat(JsonUtil.getNumberAsLong(map, "k1")).isEqualTo(1L);

    assertThat(JsonUtil.getNumberAsDouble(map, "k2")).isEqualTo(2D);
    try {
      JsonUtil.getNumberAsInteger(map, "k2");
      fail("expecting to throw but did not");
    } catch (RuntimeException e) {
      assertThat(e).hasMessageThat().isEqualTo("value '2.0' for key 'k2' is not an integer");
    }
    try {
      JsonUtil.getNumberAsLong(map, "k2");
      fail("expecting to throw but did not");
    } catch (RuntimeException e) {
      assertThat(e).hasMessageThat().isEqualTo("value '2.0' for key 'k2' is not a long integer");
    }

    assertThat(JsonUtil.getNumberAsDouble(map, "k3")).isEqualTo(3D);
    assertThat(JsonUtil.getNumberAsInteger(map, "k3")).isEqualTo(3);
    assertThat(JsonUtil.getNumberAsLong(map, "k3")).isEqualTo(3L);

    assertThat(JsonUtil.getNumberAsDouble(map, "k4")).isNaN();
    try {
      JsonUtil.getNumberAsInteger(map, "k4");
      fail("expecting to throw but did not");
    } catch (RuntimeException e) {
      assertThat(e).hasMessageThat().isEqualTo("value 'NaN' for key 'k4' is not an integer");
    }
    try {
      JsonUtil.getNumberAsLong(map, "k4");
      fail("expecting to throw but did not");
    } catch (RuntimeException e) {
      assertThat(e).hasMessageThat().isEqualTo("value 'NaN' for key 'k4' is not a long integer");
    }

    assertThat(JsonUtil.getNumberAsDouble(map, "k5")).isEqualTo(5.5D);
    try {
      JsonUtil.getNumberAsInteger(map, "k5");
      fail("expecting to throw but did not");
    } catch (RuntimeException e) {
      assertThat(e).hasMessageThat().isEqualTo("Number expected to be integer: 5.5");
    }
    try {
      JsonUtil.getNumberAsLong(map, "k5");
      fail("expecting to throw but did not");
    } catch (RuntimeException e) {
      assertThat(e).hasMessageThat().isEqualTo("Number expected to be long: 5.5");
    }

    try {
      JsonUtil.getNumberAsDouble(map, "k6");
      fail("expecting to throw but did not");
    } catch (RuntimeException e) {
      assertThat(e).hasMessageThat().isEqualTo("value 'six' for key 'k6' is not a double");
    }
    try {
      JsonUtil.getNumberAsInteger(map, "k6");
      fail("expecting to throw but did not");
    } catch (RuntimeException e) {
      assertThat(e).hasMessageThat().isEqualTo("value 'six' for key 'k6' is not an integer");
    }
    try {
      JsonUtil.getNumberAsLong(map, "k6");
      fail("expecting to throw but did not");
    } catch (RuntimeException e) {
      assertThat(e).hasMessageThat().isEqualTo("value 'six' for key 'k6' is not a long integer");
    }

    assertThat(JsonUtil.getNumberAsDouble(map, "key_string_infinity")).isPositiveInfinity();
    assertThat(JsonUtil.getNumberAsDouble(map, "key_string_minus_infinity")).isNegativeInfinity();

    assertThat(JsonUtil.getNumberAsDouble(map, "key_string_minus_zero")).isZero();
    assertThat(JsonUtil.getNumberAsInteger(map, "key_string_minus_zero")).isEqualTo(0);
    assertThat(JsonUtil.getNumberAsLong(map, "key_string_minus_zero")).isEqualTo(0L);

    assertThat(JsonUtil.getNumberAsDouble(map, "key_nonexistent")).isNull();
    assertThat(JsonUtil.getNumberAsInteger(map, "key_nonexistent")).isNull();
    assertThat(JsonUtil.getNumberAsLong(map, "key_nonexistent")).isNull();
  }
}
