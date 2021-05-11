/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;

import com.google.re2j.Pattern;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher;
import io.envoyproxy.envoy.type.v3.Int64Range;
import io.grpc.xds.Matcher.HeaderMatcher;
import io.grpc.xds.Matcher.StringMatcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class MatcherParserTest {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Test
  public void parseHeaderMatcher_withExactMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName(":method")
            .setExactMatch("PUT")
            .build();
    Matcher result = MatcherParser.parseHeaderMatcher(proto);
    assertThat(result).isEqualTo(
        HeaderMatcher.forExactValue(":method", "PUT", false));
  }

  @Test
  public void parseHeaderMatcher_withSafeRegExMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName(":method")
            .setSafeRegexMatch(RegexMatcher.newBuilder().setRegex("P*"))
            .build();
    Matcher result = MatcherParser.parseHeaderMatcher(proto);
    assertThat(result).isEqualTo(
        HeaderMatcher.forSafeRegEx(":method", Pattern.compile("P*"), false));
  }

  @Test
  public void parseHeaderMatcher_withRangeMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName("timeout")
            .setRangeMatch(Int64Range.newBuilder().setStart(10L).setEnd(20L))
            .build();
    Matcher result = MatcherParser.parseHeaderMatcher(proto);
    assertThat(result).isEqualTo(
        HeaderMatcher.forRange("timeout", HeaderMatcher.Range.create(10L, 20L), false));
  }

  @Test
  public void parseHeaderMatcher_withPresentMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName("user-agent")
            .setPresentMatch(true)
            .build();
    Matcher result = MatcherParser.parseHeaderMatcher(proto);
    assertThat(result).isEqualTo(
        HeaderMatcher.forPresent("user-agent", true, false));
  }

  @Test
  public void parseHeaderMatcher_withSuffixMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName("authority")
            .setSuffixMatch("googleapis.com")
            .build();
    Matcher result = MatcherParser.parseHeaderMatcher(proto);
    assertThat(result).isEqualTo(
        HeaderMatcher.forSuffix("authority", "googleapis.com", false));
  }

  @Test(expected = IllegalArgumentException.class)
  public void parseHeaderMatcher_malformedRegExPattern() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName(":method")
            .setSafeRegexMatch(RegexMatcher.newBuilder().setRegex("["))
            .build();
    MatcherParser.parseHeaderMatcher(proto);
  }

  @Test
  public void parseHeaderMatcher_withPrefixMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName("authority")
            .setPrefixMatch("service-foo")
            .build();
    Matcher result = MatcherParser.parseHeaderMatcher(proto);
    assertThat(result).isEqualTo(
        HeaderMatcher.forPrefix("authority", "service-foo", false));
  }

  @Test(expected = IllegalArgumentException.class)
  public void parsePathMatcher_ruleNotSet() {
    io.envoyproxy.envoy.type.matcher.v3.PathMatcher proto =
        io.envoyproxy.envoy.type.matcher.v3.PathMatcher.newBuilder()
            .build();
    MatcherParser.parsePathMatcher(proto);
  }

  @Test(expected = IllegalArgumentException.class)
  public void parseStringMatcher_matchNotSet() {
    io.envoyproxy.envoy.type.matcher.v3.StringMatcher proto =
        io.envoyproxy.envoy.type.matcher.v3.StringMatcher.newBuilder()
            .build();
    MatcherParser.parseStringMatcher(proto);
  }

  @Test
  public void parseStringMatcher_exactMatch() {
    io.envoyproxy.envoy.type.matcher.v3.StringMatcher proto =
        io.envoyproxy.envoy.type.matcher.v3.StringMatcher.newBuilder()
            .setExact("auth")
            .setIgnoreCase(true)
            .build();
    Matcher result = MatcherParser.parseStringMatcher(proto);
    assertThat(result).isEqualTo(
        StringMatcher.forExact("auth",  true));
  }

  @Test
  public void parseStringMatcher_prefixMatch() {
    io.envoyproxy.envoy.type.matcher.v3.StringMatcher proto =
        io.envoyproxy.envoy.type.matcher.v3.StringMatcher.newBuilder()
            .setPrefix("auth")
            .setIgnoreCase(false)
            .build();
    Matcher result = MatcherParser.parseStringMatcher(proto);
    assertThat(result).isEqualTo(
        StringMatcher.forPrefix("auth",  false));
  }

  @Test
  public void parseStringMatcher_suffixMatch() {
    io.envoyproxy.envoy.type.matcher.v3.StringMatcher proto =
        io.envoyproxy.envoy.type.matcher.v3.StringMatcher.newBuilder()
            .setSuffix("auth")
            .setIgnoreCase(false)
            .build();
    Matcher result = MatcherParser.parseStringMatcher(proto);
    assertThat(result).isEqualTo(
        StringMatcher.forSuffix("auth",  false));
  }

  @Test
  public void parseStringMatcher_regExmatch() {
    io.envoyproxy.envoy.type.matcher.v3.StringMatcher proto =
        io.envoyproxy.envoy.type.matcher.v3.StringMatcher.newBuilder()
            .setSafeRegex(
                RegexMatcher.newBuilder().setRegex("auth*").build()
            )
            .setIgnoreCase(false)
            .build();
    Matcher result = MatcherParser.parseStringMatcher(proto);
    assertThat(result).isEqualTo(
        StringMatcher.forSafeRegEx(Pattern.compile("auth*"),  false));
  }
}
