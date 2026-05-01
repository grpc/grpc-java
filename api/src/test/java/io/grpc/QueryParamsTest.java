/*
 * Copyright 2026 The gRPC Authors
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.grpc.QueryParams.Entry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link QueryParams}. */
@RunWith(JUnit4.class)
public class QueryParamsTest {

  @Test
  public void emptyInstance() {
    QueryParams params = new QueryParams();
    assertTrue(params.isEmpty());
    assertEquals("", params.toRawQueryString());
    assertEquals("", params.toString());
  }

  @Test
  public void parseEmptyString() {
    QueryParams params = QueryParams.parseRawQueryString("");
    assertEquals("", params.toRawQueryString());
    assertTrue(params.isEmpty());
  }

  @Test
  public void parseNormalPairs() {
    QueryParams params = QueryParams.parseRawQueryString("a=b&c=d");
    assertEquals("a=b&c=d", params.toRawQueryString());

    QueryParams.Entry a = params.getLast("a");
    assertEquals("a", a.getKey());
    assertEquals("b", a.getValue());

    QueryParams.Entry c = params.getLast("c");
    assertEquals("c", c.getKey());
    assertEquals("d", c.getValue());
  }

  @Test
  public void parseLoneKey() {
    QueryParams params = QueryParams.parseRawQueryString("a&b");
    assertEquals("a&b", params.toRawQueryString());

    QueryParams.Entry a = params.getLast("a");
    assertEquals("a", a.getKey());
    assertNull(a.getValue());

    QueryParams.Entry b = params.getLast("b");
    assertEquals("b", b.getKey());
    assertNull(b.getValue());
  }

  @Test
  public void parseEmptyKeysAndValues() {
    QueryParams params = QueryParams.parseRawQueryString("=&=");
    assertEquals("=&=", params.toRawQueryString());

    QueryParams.Entry first = params.getLast("");
    // getLast returns the LAST one
    assertEquals("", first.getKey());
    assertEquals("", first.getValue());
  }

  @Test
  public void roundTripPreservesEncoding() {
    // Spaces as +
    QueryParams params1 = QueryParams.parseRawQueryString("a+b=c+d");
    assertEquals("a+b=c+d", params1.toRawQueryString());
    assertEquals("a b", params1.getLast("a b").getKey());
    assertEquals("c d", params1.getLast("a b").getValue());

    // Spaces as %20
    QueryParams params2 = QueryParams.parseRawQueryString("a%20b=c%20d");
    assertEquals("a%20b=c%20d", params2.toRawQueryString());
    assertEquals("a b", params2.getLast("a b").getKey());
    assertEquals("c d", params2.getLast("a b").getValue());

    // Case of percent encoding
    QueryParams params3 = QueryParams.parseRawQueryString("a=%4A");
    assertEquals("a=%4A", params3.toRawQueryString());
    assertEquals("J", params3.getLast("a").getValue());

    QueryParams params4 = QueryParams.parseRawQueryString("a=%4a");
    assertEquals("a=%4a", params4.toRawQueryString());
    assertEquals("J", params4.getLast("a").getValue());
  }

  @Test
  public void addMethod() {
    QueryParams params = new QueryParams();
    params.add(QueryParams.Entry.forKeyValue("a b", "c d"));
    params.add(QueryParams.Entry.forLoneKey("e f"));

    // URLEncoder encodes spaces as +
    assertEquals("a+b=c+d&e+f", params.toRawQueryString());
  }

  @Test
  public void removeAllMethod() {
    QueryParams params = QueryParams.parseRawQueryString("a=b&c=d&a=b&a=c");

    QueryParams.Entry toRemove = QueryParams.Entry.forKeyValue("a", "b");
    int removed = params.removeAll(toRemove);

    assertEquals(2, removed);
    assertEquals("c=d&a=c", params.toRawQueryString());
  }

  @Test
  public void removeAllLoneKey() {
    QueryParams params = QueryParams.parseRawQueryString("a&b&a&a=b");

    QueryParams.Entry toRemove = QueryParams.Entry.forLoneKey("a");
    int removed = params.removeAll(toRemove);

    assertEquals(2, removed);
    assertEquals("b&a=b", params.toRawQueryString());
  }

  @Test
  public void parseInvalidEncodingThrows() {
    assertThrows(IllegalArgumentException.class, () -> QueryParams.parseRawQueryString("a=%GH"));
  }

  @Test
  public void uriIntegration() {
    QueryParams params = new QueryParams();
    params.add(Entry.forKeyValue("a", "b"));
    params.add(Entry.forKeyValue("c", "d"));

    Uri uri =
        Uri.newBuilder()
            .setScheme("http")
            .setHost("example.com")
            .setRawQuery(params.toRawQueryString())
            .build();

    assertEquals("http://example.com?a=b&c=d", uri.toString());
    assertEquals("a=b&c=d", uri.getRawQuery());
  }

  @Test
  public void keysAndValuesWithCharactersNeedingUrlEncoding() {
    QueryParams params = new QueryParams();
    params.add(Entry.forKeyValue("a=b", "c&d"));
    params.add(Entry.forKeyValue("e+f", "g h"));

    assertEquals("a%3Db=c%26d&e%2Bf=g+h", params.toRawQueryString());

    QueryParams roundTripped = QueryParams.parseRawQueryString(params.toRawQueryString());
    assertEquals("c&d", roundTripped.getLast("a=b").getValue());
    assertEquals("g h", roundTripped.getLast("e+f").getValue());
  }

  @Test
  public void keysAndValuesWithCodePointsOutsideAsciiRange() {
    QueryParams params = new QueryParams();
    params.add(Entry.forKeyValue("€", "𐐷"));

    assertEquals("%E2%82%AC=%F0%90%90%B7", params.toRawQueryString());

    QueryParams roundTripped = QueryParams.parseRawQueryString(params.toRawQueryString());
    assertEquals("𐐷", roundTripped.getLast("€").getValue());
  }
}
