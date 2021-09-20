/*
 * Copyright 2020 The gRPC Authors
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
import io.grpc.xds.RouteMatch.FractionMatcher;
import io.grpc.xds.RouteMatch.HeaderMatcher;
import io.grpc.xds.RouteMatch.HeaderMatcher.Range;
import io.grpc.xds.RouteMatch.PathMatcher;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RouteMatch}. */
@RunWith(JUnit4.class)
public class RouteMatchTest {

  private final Map<String, Iterable<String>> headers = new HashMap<>();

  @Before
  public void setUp() {
    headers.put("authority", Collections.singletonList("foo.googleapis.com"));
    headers.put("grpc-encoding", Collections.singletonList("gzip"));
    headers.put("user-agent", Collections.singletonList("gRPC-Java"));
    headers.put("content-length", Collections.singletonList("1000"));
    headers.put("custom-key", Arrays.asList("custom-value1", "custom-value2"));
  }

  @Test
  public void routeMatching_pathOnly() {
    RouteMatch routeMatch1 =
        new RouteMatch(
            PathMatcher.fromPath("/FooService/barMethod", true),
            Collections.<HeaderMatcher>emptyList(), null);
    assertThat(routeMatch1.matches("/FooService/barMethod", headers)).isTrue();
    assertThat(routeMatch1.matches("/FooService/bazMethod", headers)).isFalse();

    RouteMatch routeMatch2 =
        new RouteMatch(
            PathMatcher.fromPrefix("/FooService/", true),
            Collections.<HeaderMatcher>emptyList(), null);
    assertThat(routeMatch2.matches("/FooService/barMethod", headers)).isTrue();
    assertThat(routeMatch2.matches("/FooService/bazMethod", headers)).isTrue();
    assertThat(routeMatch2.matches("/BarService/bazMethod", headers)).isFalse();

    RouteMatch routeMatch3 =
        new RouteMatch(
            PathMatcher.fromRegEx(Pattern.compile(".*Foo.*")),
            Collections.<HeaderMatcher>emptyList(), null);
    assertThat(routeMatch3.matches("/FooService/barMethod", headers)).isTrue();
  }

  @Test
  public void pathMatching_caseInsensitive() {
    PathMatcher pathMatcher1 = PathMatcher.fromPath("/FooService/barMethod", false);
    assertThat(pathMatcher1.matches("/fooservice/barmethod")).isTrue();

    PathMatcher pathMatcher2 = PathMatcher.fromPrefix("/FooService", false);
    assertThat(pathMatcher2.matches("/fooservice/barmethod")).isTrue();
  }

  @Test
  public void routeMatching_withHeaders() {
    PathMatcher pathMatcher = PathMatcher.fromPath("/FooService/barMethod", true);
    RouteMatch routeMatch1 = new RouteMatch(
        pathMatcher,
        Arrays.asList(
            new HeaderMatcher(
                "grpc-encoding", "gzip", null, null, null, null, null, false),
            new HeaderMatcher(
                "authority", null, Pattern.compile(".*googleapis.*"), null, null, null,
                null, false),
            new HeaderMatcher(
                "content-length", null, null, new Range(100, 10000), null, null, null, false),
            new HeaderMatcher("user-agent", null, null, null, true, null, null, false),
            new HeaderMatcher("custom-key", null, null, null, null, "custom-", null, false),
            new HeaderMatcher("custom-key", null, null, null, null, null, "value2", false)),
        null);
    assertThat(routeMatch1.matches("/FooService/barMethod", headers)).isTrue();

    RouteMatch routeMatch2 = new RouteMatch(
        pathMatcher,
        Collections.singletonList(
            new HeaderMatcher(
                "authority", null, Pattern.compile(".*googleapis.*"), null, null, null,
                null, true)),
        null);
    assertThat(routeMatch2.matches("/FooService/barMethod", headers)).isFalse();

    RouteMatch routeMatch3 = new RouteMatch(
        pathMatcher,
        Collections.singletonList(
            new HeaderMatcher(
                "user-agent", "gRPC-Go", null, null, null, null,
                null, false)),
        null);
    assertThat(routeMatch3.matches("/FooService/barMethod", headers)).isFalse();

    RouteMatch routeMatch4 = new RouteMatch(
        pathMatcher,
        Collections.singletonList(
            new HeaderMatcher(
                "user-agent", null, null, null, false, null,
                null, false)),
        null);
    assertThat(routeMatch4.matches("/FooService/barMethod", headers)).isFalse();

    RouteMatch routeMatch5 = new RouteMatch(
        pathMatcher,
        Collections.singletonList(
            new HeaderMatcher(
                "user-agent", null, null, null, false, null,
                null, true)),
        null);
    assertThat(routeMatch5.matches("/FooService/barMethod", headers)).isTrue();

    RouteMatch routeMatch6 = new RouteMatch(
        pathMatcher,
        Collections.singletonList(
            new HeaderMatcher(
                "user-agent", null, null, null, true, null,
                null, true)),
        null);
    assertThat(routeMatch6.matches("/FooService/barMethod", headers)).isFalse();

    RouteMatch routeMatch7 = new RouteMatch(
        pathMatcher,
        Collections.singletonList(
            new HeaderMatcher(
                "custom-key", "custom-value1,custom-value2", null, null, null, null,
                null, false)),
        null);
    assertThat(routeMatch7.matches("/FooService/barMethod", headers)).isTrue();
  }

  @Test
  public void  routeMatching_withRuntimeFraction() {
    PathMatcher pathMatcher = PathMatcher.fromPath("/FooService/barMethod", true);
    RouteMatch routeMatch1 =
        new RouteMatch(
            pathMatcher,
            Collections.<HeaderMatcher>emptyList(),
            new FractionMatcher(100, 1000, new FakeRandom(50)));
    assertThat(routeMatch1.matches("/FooService/barMethod", headers)).isTrue();

    RouteMatch routeMatch2 =
        new RouteMatch(
            pathMatcher,
            Collections.<HeaderMatcher>emptyList(),
            new FractionMatcher(100, 1000, new FakeRandom(100)));
    assertThat(routeMatch2.matches("/FooService/barMethod", headers)).isFalse();
  }

  @Test
  public void headerMatching_specialCaseGrpcHeaders() {
    PathMatcher pathMatcher = PathMatcher.fromPath("/FooService/barMethod", true);
    Map<String, Iterable<String>> headers = new HashMap<>();
    headers.put("grpc-previous-rpc-attempts", Collections.singletonList("0"));

    RouteMatch routeMatch1 =
        new RouteMatch(pathMatcher,
            Arrays.asList(
                new HeaderMatcher(
                    "grpc-previous-rpc-attempts", "0", null, null, null, null,
                    null, false)),
            null);
    assertThat(routeMatch1.matches("/FooService/barMethod", headers)).isFalse();

    RouteMatch routeMatch2 =
        new RouteMatch(pathMatcher,
            Arrays.asList(
                new HeaderMatcher(
                    "content-type", "application/grpc", null, null, null, null,
                    null, false)),
            null);
    assertThat(routeMatch2.matches("/FooService/barMethod", headers)).isTrue();
  }

  private static final class FakeRandom implements ThreadSafeRandom {
    private final int value;

    FakeRandom(int value) {
      this.value = value;
    }

    @Override
    public int nextInt(int bound) {
      return value;
    }
  }
}
