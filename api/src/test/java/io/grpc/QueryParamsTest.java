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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

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
    assertThat(params.asList()).isEmpty();
    assertThat(params.toRawQuery()).isNull();
  }

  @Test
  public void parseNull_yieldsEmptyInstance() {
    QueryParams params = QueryParams.fromRawQuery(null);
    assertThat(params.asList()).isEmpty();
    assertThat(params.toRawQuery()).isNull();
  }

  @Test
  public void parseEmptyString_yieldsSingleLoneKey() {
    QueryParams params = QueryParams.fromRawQuery("");
    assertThat(params.toRawQuery()).isEmpty();
    assertThat(params.asList()).isNotEmpty();
    Entry entry = params.asList().get(0);
    assertThat(entry).isNotNull();
    assertThat(entry.getKey()).isEmpty();
    assertThat(entry.hasValue()).isFalse();
    assertThat(entry.getValue()).isNull();
  }

  @Test
  public void parseNormalPairs() {
    QueryParams params = QueryParams.fromRawQuery("a=b&c=d");
    assertThat(params.toRawQuery()).isEqualTo("a=b&c=d");

    QueryParams.Entry a = params.asList().get(0);
    assertThat(a.getKey()).isEqualTo("a");
    assertThat(a.hasValue()).isTrue();
    assertThat(a.getValue()).isEqualTo("b");

    QueryParams.Entry c = params.asList().get(1);
    assertThat(c.getKey()).isEqualTo("c");
    assertThat(c.getValue()).isEqualTo("d");
  }

  @Test
  public void parseLoneKey() {
    QueryParams params = QueryParams.fromRawQuery("a&b");
    assertThat(params.toRawQuery()).isEqualTo("a&b");

    QueryParams.Entry a = params.asList().get(0);
    assertThat(a.getKey()).isEqualTo("a");
    assertThat(a.hasValue()).isFalse();

    QueryParams.Entry b = params.asList().get(1);
    assertThat(b.getKey()).isEqualTo("b");
    assertThat(b.hasValue()).isFalse();
  }

  @Test
  public void parseEmptyKeysAndValues() {
    QueryParams params = QueryParams.fromRawQuery("=&=");
    assertThat(params.toRawQuery()).isEqualTo("=&=");

    assertThat(params.asList()).hasSize(2);
    assertThat(params.asList().get(0).getKey()).isEmpty();
    assertThat(params.asList().get(0).hasValue()).isTrue();
    assertThat(params.asList().get(0).getValue()).isEmpty();
    assertThat(params.asList().get(1).getKey()).isEmpty();
    assertThat(params.asList().get(1).hasValue()).isTrue();
    assertThat(params.asList().get(1).getValue()).isEmpty();
  }

  @Test
  public void roundTripPreservesEncodingOfSpaces() {
    // Spaces can be encoded as + or %20.
    QueryParams params = QueryParams.fromRawQuery("a+b=c%20d");
    assertThat(params.asList().get(0).getKey()).isEqualTo("a b");
    assertThat(params.asList().get(0).getValue()).isEqualTo("c d");
    assertThat(params.toRawQuery()).isEqualTo("a+b=c%20d");
  }

  @Test
  public void roundTripPreservesCaseOfHexDigits() {
    // Percent encoding can use upper or lower case.
    QueryParams params = QueryParams.fromRawQuery("%4A%4a=%4B%4b");
    assertThat(params.asList().get(0).getKey()).isEqualTo("JJ");
    assertThat(params.asList().get(0).getValue()).isEqualTo("KK");
    assertThat(params.toRawQuery()).isEqualTo("%4A%4a=%4B%4b");
  }

  @Test
  public void asListMethod() {
    QueryParams params = new QueryParams();
    params.asList().add(QueryParams.Entry.forKeyValue("a b", "c d"));
    params.asList().add(QueryParams.Entry.forLoneKey("e f"));

    // URLEncoder encodes spaces as +
    assertThat(params.toRawQuery()).isEqualTo("a+b=c+d&e+f");
  }

  @Test
  public void parseInvalidPercentEncodingThrows() {
    assertThrows(IllegalArgumentException.class, () -> QueryParams.fromRawQuery("a=%GH"));
  }

  @Test
  public void parseInvalidKeyValueEncodingSucceeds() {
    QueryParams params = QueryParams.fromRawQuery("====");
    assertThat(params.asList())
        .containsExactly(Entry.forRawKeyValue("", "==="))
        .inOrder();
    assertThat(params.toRawQuery()).isEqualTo("====");
  }

  @Test
  public void uriIntegration_canBuild() {
    QueryParams params = new QueryParams();
    params.asList().add(Entry.forKeyValue("a", "b"));
    params.asList().add(Entry.forKeyValue("c", "d"));

    Uri uri =
        Uri.newBuilder()
            .setScheme("http")
            .setHost("example.com")
            .setRawQuery(params.toRawQuery())
            .build();

    assertThat(uri.toString()).isEqualTo("http://example.com?a=b&c=d");
    assertThat(uri.getRawQuery()).isEqualTo("a=b&c=d");
  }

  @Test
  public void uriIntegration_canBuildEmpty() {
    QueryParams params = new QueryParams();
    Uri uri =
        Uri.newBuilder()
            .setScheme("http")
            .setHost("example.com")
            .setRawQuery(params.toRawQuery())
            .build();

    assertThat(uri.toString()).isEqualTo("http://example.com");
    assertThat(uri.getRawQuery()).isNull();
  }

  @Test
  public void uriIntegration_canParse() {
    Uri uri = Uri.create("http://example.com?a=b&c=d&e");
    QueryParams params = QueryParams.fromRawQuery(uri.getRawQuery());

    assertThat(params.asList())
        .containsExactly(
            Entry.forKeyValue("a", "b"), Entry.forKeyValue("c", "d"), Entry.forLoneKey("e"))
        .inOrder();
  }

  @Test
  public void keysAndValuesWithCharactersNeedingUrlEncoding() {
    QueryParams params = new QueryParams();
    params.asList().add(Entry.forKeyValue("a=b", "c&d"));
    params.asList().add(Entry.forKeyValue("e+f", "g h"));

    assertThat(params.toRawQuery()).isEqualTo("a%3Db=c%26d&e%2Bf=g+h");

    QueryParams roundTripped = QueryParams.fromRawQuery(params.toRawQuery());
    assertThat(roundTripped).isEqualTo(params);
  }

  @Test
  public void keysAndValuesWithCodePointsOutsideAsciiRange() {
    QueryParams params = new QueryParams();
    params.asList().add(Entry.forKeyValue("€", "𐐷"));

    assertThat(params.toRawQuery()).isEqualTo("%E2%82%AC=%F0%90%90%B7");

    QueryParams roundTripped = QueryParams.fromRawQuery(params.toRawQuery());
    assertThat(roundTripped).isEqualTo(params);
  }

  @Test
  public void toStringMethod() {
    QueryParams params = new QueryParams();
    assertThat(params.toString()).isEqualTo("[]");

    params.asList().add(Entry.forKeyValue("a", "b"));
    assertThat(params.toString()).isEqualTo("[a=b]");

    params.asList().add(Entry.forLoneKey("c"));
    assertThat(params.toString()).isEqualTo("[a=b, c]");

    params.asList().add(Entry.forKeyValue("d=e", "f&g"));
    assertThat(params.toString()).isEqualTo("[a=b, c, d%3De=f%26g]");
  }

  @Test
  public void entryProperties() {
    Entry keyValue = Entry.forKeyValue("key", "val");
    assertThat(keyValue.getKey()).isEqualTo("key");
    assertThat(keyValue.getValue()).isEqualTo("val");
    assertThat(keyValue.hasValue()).isTrue();

    Entry loneKey = Entry.forLoneKey("key");
    assertThat(loneKey.getKey()).isEqualTo("key");
    assertThat(loneKey.getValue()).isNull();
    assertThat(loneKey.hasValue()).isFalse();
  }

  @Test
  public void equalsAndHashCode_container() {
    QueryParams params1 = new QueryParams();
    QueryParams params2 = new QueryParams();

    // Empty instances are equal
    assertThat(params1).isEqualTo(params2);
    assertThat(params1.hashCode()).isEqualTo(params2.hashCode());

    params1.asList().add(Entry.forKeyValue("a", "b"));
    params1.asList().add(Entry.forLoneKey("c"));

    params2.asList().add(Entry.forKeyValue("a", "b"));
    params2.asList().add(Entry.forLoneKey("c"));

    // Identical parameters in identical order are equal
    assertThat(params1).isEqualTo(params2);
    assertThat(params1.hashCode()).isEqualTo(params2.hashCode());

    // Order matters.
    QueryParams params3 = new QueryParams();
    params3.asList().add(Entry.forLoneKey("c"));
    params3.asList().add(Entry.forKeyValue("a", "b"));
    assertThat(params1).isNotEqualTo(params3);
  }

  @Test
  public void equalsAndHashCode_entry() {
    // Raw matches are equal.
    assertThat(Entry.forRawKeyValue("a+b", "c")).isEqualTo(Entry.forRawKeyValue("a+b", "c"));
    assertThat(Entry.forRawKeyValue("a+b", "c").hashCode())
        .isEqualTo(Entry.forRawKeyValue("a+b", "c").hashCode());

    // Spaces encoding matters. + and %20 are not equal.
    assertThat(Entry.forRawKeyValue("a+b", "c")).isNotEqualTo(Entry.forRawKeyValue("a%20b", "c"));

    // Case of hex digits matter: %4A vs %4a are not equal raw keys.
    assertThat(Entry.forRawKeyValue("a", "%4A")).isNotEqualTo(Entry.forRawKeyValue("a", "%4a"));
  }
}
