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

package io.grpc.internal;

import static org.junit.Assert.assertEquals;

import com.google.gson.stream.MalformedJsonException;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/**
 * Tests for {@link JsonParser}.
 */
public class JsonParserTest {

  @Rule
  public final ExpectedException thrown = ExpectedException.none();

  @Test
  public void emptyObject() throws IOException {
    assertEquals(new LinkedHashMap<String, Object>(), JsonParser.parse("{}"));
  }

  @Test
  public void emptyArray() throws IOException {
    assertEquals(new ArrayList<Object>(), JsonParser.parse("[]"));
  }

  @Test
  public void intNumber() throws IOException {
    assertEquals(1.0, JsonParser.parse("1"));
  }

  @Test
  public void doubleNumber() throws IOException {
    assertEquals(1.2, JsonParser.parse("1.2"));
  }

  @Test
  public void longNumber() throws IOException {
    assertEquals(9999999999.0, JsonParser.parse("9999999999"));
  }

  @Test
  public void objectEarlyEnd() throws IOException {
    thrown.expect(MalformedJsonException.class);

    JsonParser.parse("{foo:}");
  }

  @Test
  public void earlyEndArray() throws IOException {
    thrown.expect(EOFException.class);

    JsonParser.parse("[1, 2, ");
  }

  @Test
  public void arrayMissingElement() throws IOException {
    thrown.expect(MalformedJsonException.class);

    JsonParser.parse("[1, 2, ]");
  }

  @Test
  public void objectMissingElement() throws IOException {
    thrown.expect(MalformedJsonException.class);

    JsonParser.parse("{1: ");
  }

  @Test
  public void objectNoName() throws IOException {
    thrown.expect(MalformedJsonException.class);

    JsonParser.parse("{: 1");
  }

  @Test
  public void objectStringName() throws IOException {
    LinkedHashMap<String, Object> expected = new LinkedHashMap<String, Object>();
    expected.put("hi", 2.0);

    assertEquals(expected, JsonParser.parse("{\"hi\": 2}"));
  }
}
