/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds.internal;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.envoyproxy.envoy.type.v3.FractionalPercent.DenominatorType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MatcherParserTest {

  @Test
  public void parseStringMatcher_exact() {
    StringMatcher proto =
        StringMatcher.newBuilder().setExact("exact-match").setIgnoreCase(true).build();
    Matchers.StringMatcher matcher = MatcherParser.parseStringMatcher(proto);
    assertThat(matcher).isNotNull();
  }

  @Test
  public void parseStringMatcher_allTypes() {
    MatcherParser.parseStringMatcher(StringMatcher.newBuilder().setExact("test").build());
    MatcherParser.parseStringMatcher(StringMatcher.newBuilder().setPrefix("test").build());
    MatcherParser.parseStringMatcher(StringMatcher.newBuilder().setSuffix("test").build());
    MatcherParser.parseStringMatcher(StringMatcher.newBuilder().setContains("test").build());
    MatcherParser.parseStringMatcher(StringMatcher.newBuilder()
        .setSafeRegex(RegexMatcher.newBuilder().setRegex(".*").build()).build());
  }

  @Test
  public void parseStringMatcher_unknownTypeThrows() {
    StringMatcher unknownProto = StringMatcher.getDefaultInstance();
    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
        () -> MatcherParser.parseStringMatcher(unknownProto));
    assertThat(exception).hasMessageThat().contains("Unknown StringMatcher match pattern");
  }

  @Test
  public void parseFractionMatcher_denominators() {
    Matchers.FractionMatcher hundred = MatcherParser.parseFractionMatcher(FractionalPercent
        .newBuilder().setNumerator(1).setDenominator(DenominatorType.HUNDRED).build());
    assertThat(hundred.numerator()).isEqualTo(1);
    assertThat(hundred.denominator()).isEqualTo(100);

    Matchers.FractionMatcher tenThousand = MatcherParser.parseFractionMatcher(FractionalPercent
        .newBuilder().setNumerator(2).setDenominator(DenominatorType.TEN_THOUSAND).build());
    assertThat(tenThousand.numerator()).isEqualTo(2);
    assertThat(tenThousand.denominator()).isEqualTo(10_000);

    Matchers.FractionMatcher million = MatcherParser.parseFractionMatcher(FractionalPercent
        .newBuilder().setNumerator(3).setDenominator(DenominatorType.MILLION).build());
    assertThat(million.numerator()).isEqualTo(3);
    assertThat(million.denominator()).isEqualTo(1_000_000);
  }

  @Test
  public void parseFractionMatcher_unknownDenominatorThrows() {
    FractionalPercent unknownProto =
        FractionalPercent.newBuilder().setDenominatorValue(999).build();
    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
        () -> MatcherParser.parseFractionMatcher(unknownProto));
    assertThat(exception).hasMessageThat().contains("Unknown denominator type");
  }
}
