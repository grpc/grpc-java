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

import com.google.common.collect.ImmutableMap;
import com.google.re2j.Pattern;
import io.grpc.xds.Matcher.HeaderMatcher;
import io.grpc.xds.Matcher.HeaderMatcher.Range;
import io.grpc.xds.Matcher.IpMatcher;
import io.grpc.xds.Matcher.RouteMatcher;
import io.grpc.xds.Matcher.StringMatcher;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class MatcherTest {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Test
  public void testIpMatcher_Ipv4() {
    //00001010.00001010.00011000.00001010
    IpMatcher matcher = IpMatcher.create("10.10.24.10", 20);
    assertThat(matcher.matches("10.10.20.0")).isTrue();
    assertThat(matcher.matches("10.10.16.0")).isTrue();
    assertThat(matcher.matches("10.10.24.10")).isTrue();
    assertThat(matcher.matches("10.10.31.0")).isTrue();
    assertThat(matcher.matches("10.10.17.0")).isTrue();
    assertThat(matcher.matches("10.32.20.0")).isFalse();
    assertThat(matcher.matches("10.10.40.0")).isFalse();
    matcher = IpMatcher.create("0.0.0.0", 20);
    assertThat(matcher.matches("10.32.20.0")).isFalse();
    assertThat(matcher.matches("0.0.31.0")).isFalse();
    assertThat(matcher.matches("0.0.15.0")).isTrue();
  }

  @Test
  public void testIpMatcher_Ipv6() {
    IpMatcher matcher = IpMatcher.create("2012:00fe:d808::", 36);
    assertThat(matcher.matches("2012:00fe:d000::0")).isTrue();
    assertThat(matcher.matches("2012:00fe:d808::")).isTrue();
    assertThat(matcher.matches("2012:00fe:da81:0909:0008:4018:e930:b019")).isTrue();
    assertThat(matcher.matches("2013:00fe:d000::0")).isFalse();
  }

  @Test
  public void testStringMatcher() {
    StringMatcher matcher = StringMatcher.forExact("essence", false);
    assertThat(matcher.matches("elite")).isFalse();
    assertThat(matcher.matches("ess")).isFalse();
    assertThat(matcher.matches("")).isFalse();
    assertThat(matcher.matches("essential")).isFalse();
    assertThat(matcher.matches("Essence")).isFalse();
    assertThat(matcher.matches("essence")).isTrue();
    assertThat(matcher.matches((String)null)).isFalse();
    matcher = StringMatcher.forExact("essence", true);
    assertThat(matcher.matches("Essence")).isTrue();
    assertThat(matcher.matches("essence")).isTrue();

    matcher = StringMatcher.forPrefix("Ess", false);
    assertThat(matcher.matches("elite")).isFalse();
    assertThat(matcher.matches("ess")).isFalse();
    assertThat(matcher.matches("")).isFalse();
    assertThat(matcher.matches("e")).isFalse();
    assertThat(matcher.matches("essential")).isFalse();
    assertThat(matcher.matches("Essence")).isTrue();
    assertThat(matcher.matches("essence")).isFalse();
    assertThat(matcher.matches((String)null)).isFalse();
    matcher = StringMatcher.forPrefix("Ess", true);
    assertThat(matcher.matches("esSEncE")).isTrue();
    assertThat(matcher.matches("ess")).isTrue();
    assertThat(matcher.matches("ES")).isFalse();

    matcher = StringMatcher.forSuffix("ess", false);
    assertThat(matcher.matches("elite")).isFalse();
    assertThat(matcher.matches("es")).isFalse();
    assertThat(matcher.matches("")).isFalse();
    assertThat(matcher.matches("ess")).isTrue();
    assertThat(matcher.matches("Excess")).isTrue();
    assertThat(matcher.matches("ExcesS")).isFalse();
    assertThat(matcher.matches((String)null)).isFalse();
    matcher = StringMatcher.forSuffix("ess", true);
    assertThat(matcher.matches("esSEncESs")).isTrue();
    assertThat(matcher.matches("ess")).isTrue();

    matcher = StringMatcher.forContains("ess", true);
    assertThat(matcher.matches("elite")).isFalse();
    assertThat(matcher.matches("es")).isFalse();
    assertThat(matcher.matches("")).isFalse();
    assertThat(matcher.matches("essence")).isTrue();
    assertThat(matcher.matches("eSs")).isFalse();
    assertThat(matcher.matches("ExcesS")).isFalse();
    assertThat(matcher.matches((String)null)).isFalse();

    matcher = StringMatcher.forSafeRegEx(Pattern.compile("^es*.*"), true);
    assertThat(matcher.matches("essence")).isTrue();
  }

  @Test
  public void testRouteMatch() {
    RouteMatcher matcher = RouteMatcher.fromPath("/Namespace/get", true);
    assertThat(matcher.matches(args("/Namespace/get", null))).isTrue();
    assertThat(matcher.matches(args("/namespace/get", null))).isFalse();
    matcher = RouteMatcher.fromPath("/Namespace/get", false);
    assertThat(matcher.matches(args("/Namespace/get", null))).isTrue();
    assertThat(matcher.matches(args("/namespace/get", null))).isTrue();

    matcher = RouteMatcher.fromPrefix("/Namespace/get", true);
    assertThat(matcher.matches(args("/namespace/get/market", null))).isFalse();
    assertThat(matcher.matches(args("/namespace/put", null))).isFalse();
    assertThat(matcher.matches(args("/Namespace/get/market", null))).isTrue();
    matcher = RouteMatcher.fromPrefix("/Namespace/get", false);
    assertThat(matcher.matches(args("/namespace/get/market", null))).isTrue();
    assertThat(matcher.matches(args("/namespace/put", null))).isFalse();
    assertThat(matcher.matches(args("/Namespace/Get/market", null))).isTrue();

    matcher = RouteMatcher.fromRegEx(Pattern.compile("/name.*"));
    assertThat(matcher.matches(args("/namespace/get/market", null))).isTrue();
    assertThat(matcher.matches(args("/namespace/put", null))).isTrue();
    assertThat(matcher.matches(args("namespace/put", null))).isFalse();
  }

  private EvaluateArgs args(final String method, final Map<String, String> headers) {
    return new EvaluateArgs() {
      @Override
      public Map<String, String> getHeaders() {
        return headers;
      }

      @Override
      public String getFullMethodName() {
        return method;
      }
    };
  }

  @Test
  public void testHeaderMatcher() {
    HeaderMatcher matcher = HeaderMatcher.forExactValue("version", "v1", false);
    assertThat(matcher.matches(args(null, ImmutableMap.of("version", "v1")))).isTrue();
    assertThat(matcher.matches(args(null, ImmutableMap.of("version", "v2")))).isFalse();
    matcher = HeaderMatcher.forExactValue("version", "v1", true);
    assertThat(matcher.matches(args(null, ImmutableMap.of("version", "v1")))).isFalse();
    assertThat(matcher.matches(args(null, ImmutableMap.of("version", "v2")))).isTrue();

    matcher = HeaderMatcher.forPresent("version", true, false);
    assertThat(matcher.matches(args(null,
        ImmutableMap.of("version", "v1", "tag", "todo")))).isTrue();
    assertThat(matcher.matches(args(null,
        ImmutableMap.of("versions", "v1", "tag", "todo")))).isFalse();
    matcher = HeaderMatcher.forPresent("version", false, true);
    assertThat(matcher.matches(args(null,
        ImmutableMap.of("version", "v1", "tag", "todo")))).isTrue();
    assertThat(matcher.matches(args(null,
        ImmutableMap.of("versions", "v1", "tag", "todo")))).isFalse();

    matcher = HeaderMatcher.forPrefix("version", "v1", false);
    assertThat(matcher.matches(args(null, ImmutableMap.of("version", "v1.1")))).isTrue();
    assertThat(matcher.matches(args(null, ImmutableMap.of("version", "v2.1")))).isFalse();
    matcher = HeaderMatcher.forPrefix("version", "v1", true);
    assertThat(matcher.matches(args(null, ImmutableMap.of("version", "v1.1")))).isFalse();
    assertThat(matcher.matches(args(null, ImmutableMap.of("version", "v2.1")))).isTrue();

    matcher = HeaderMatcher.forSuffix("version", "v1", false);
    assertThat(matcher.matches(args(null, ImmutableMap.of("version", "g-v1")))).isTrue();
    assertThat(matcher.matches(args(null, ImmutableMap.of("version", "g-v2")))).isFalse();
    matcher = HeaderMatcher.forSuffix("version", "v1", true);
    assertThat(matcher.matches(args(null, ImmutableMap.of("version", "v1")))).isFalse();
    assertThat(matcher.matches(args(null, ImmutableMap.of("version", "v2")))).isTrue();

    matcher = HeaderMatcher.forSafeRegEx("version", Pattern.compile("v1\\..*"), false);
    assertThat(matcher.matches(args(null, ImmutableMap.of("version", "v1.1")))).isTrue();
    assertThat(matcher.matches(args(null, ImmutableMap.of("version", "v2")))).isFalse();
    matcher = HeaderMatcher.forSafeRegEx("version", Pattern.compile("v1\\..*"), true);
    assertThat(matcher.matches(args(null, ImmutableMap.of("version", "v1.1")))).isFalse();
    assertThat(matcher.matches(args(null, ImmutableMap.of("version", "v2.1")))).isTrue();

    matcher = HeaderMatcher.forRange("version", Range.create(8080L, 8090L), false);
    assertThat(matcher.matches(args(null, ImmutableMap.of("version", "8080")))).isTrue();
    assertThat(matcher.matches(args(null, ImmutableMap.of("version", "8000")))).isFalse();
    matcher = HeaderMatcher.forRange("version", Range.create(8080L, 8090L), true);
    assertThat(matcher.matches(args(null, ImmutableMap.of("version", "8088")))).isFalse();
    assertThat(matcher.matches(args(null, ImmutableMap.of("version", "-8088")))).isTrue();
  }
}
