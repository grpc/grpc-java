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
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableMap;
import com.google.re2j.Pattern;
import io.grpc.Metadata;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.VirtualHost.Route.RouteMatch;
import io.grpc.xds.VirtualHost.Route.RouteMatch.PathMatcher;
import io.grpc.xds.internal.Matchers.HeaderMatcher;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RoutingUtils }. */
@RunWith(JUnit4.class)
public class RoutingUtilsTest {

  @Test
  public void findVirtualHostForHostName_exactMatchFirst() {
    String hostname = "a.googleapis.com";
    List<Route> routes = Collections.emptyList();
    VirtualHost vHost1 = VirtualHost.create("virtualhost01.googleapis.com",
        Arrays.asList("a.googleapis.com", "b.googleapis.com"), routes,
        ImmutableMap.of());
    VirtualHost vHost2 = VirtualHost.create("virtualhost02.googleapis.com",
        Collections.singletonList("*.googleapis.com"), routes,
        ImmutableMap.of());
    VirtualHost vHost3 = VirtualHost.create("virtualhost03.googleapis.com",
        Collections.singletonList("*"), routes,
        ImmutableMap.of());
    List<VirtualHost> virtualHosts = Arrays.asList(vHost1, vHost2, vHost3);
    assertThat(RoutingUtils.findVirtualHostForHostName(virtualHosts, hostname))
         .isEqualTo(vHost1);
  }

  @Test
  public void findVirtualHostForHostName_preferSuffixDomainOverPrefixDomain() {
    String hostname = "a.googleapis.com";
    List<Route> routes = Collections.emptyList();
    VirtualHost vHost1 = VirtualHost.create("virtualhost01.googleapis.com",
        Arrays.asList("*.googleapis.com", "b.googleapis.com"), routes,
        ImmutableMap.of());
    VirtualHost vHost2 = VirtualHost.create("virtualhost02.googleapis.com",
        Collections.singletonList("a.googleapis.*"), routes,
        ImmutableMap.of());
    VirtualHost vHost3 = VirtualHost.create("virtualhost03.googleapis.com",
        Collections.singletonList("*"), routes,
        ImmutableMap.of());
    List<VirtualHost> virtualHosts = Arrays.asList(vHost1, vHost2, vHost3);
    assertThat(RoutingUtils.findVirtualHostForHostName(virtualHosts, hostname))
        .isEqualTo(vHost1);
  }

  @Test
  public void findVirtualHostForHostName_asteriskMatchAnyDomain() {
    String hostname = "a.googleapis.com";
    List<Route> routes = Collections.emptyList();
    VirtualHost vHost1 = VirtualHost.create("virtualhost01.googleapis.com",
        Collections.singletonList("*"), routes,
        ImmutableMap.of());
    VirtualHost vHost2 = VirtualHost.create("virtualhost02.googleapis.com",
        Collections.singletonList("b.googleapis.com"), routes,
        ImmutableMap.of());
    List<VirtualHost> virtualHosts = Arrays.asList(vHost1, vHost2);
    assertThat(RoutingUtils.findVirtualHostForHostName(virtualHosts, hostname))
        .isEqualTo(vHost1);
  }

  @Test
  public void routeMatching_pathOnly() {
    Metadata headers = new Metadata();
    ThreadSafeRandom random = mock(ThreadSafeRandom.class);

    RouteMatch routeMatch1 =
        RouteMatch.create(
            PathMatcher.fromPath("/FooService/barMethod", true),
            Collections.emptyList(), null);
    assertThat(RoutingUtils.matchRoute(routeMatch1, "/FooService/barMethod", headers, random))
        .isTrue();
    assertThat(RoutingUtils.matchRoute(routeMatch1, "/FooService/bazMethod", headers, random))
        .isFalse();

    RouteMatch routeMatch2 =
        RouteMatch.create(
            PathMatcher.fromPrefix("/FooService/", true),
            Collections.emptyList(), null);
    assertThat(RoutingUtils.matchRoute(routeMatch2, "/FooService/barMethod", headers, random))
        .isTrue();
    assertThat(RoutingUtils.matchRoute(routeMatch2, "/FooService/bazMethod", headers, random))
        .isTrue();
    assertThat(RoutingUtils.matchRoute(routeMatch2, "/BarService/bazMethod", headers, random))
        .isFalse();

    RouteMatch routeMatch3 =
        RouteMatch.create(
            PathMatcher.fromRegEx(Pattern.compile(".*Foo.*")),
            Collections.emptyList(), null);
    assertThat(RoutingUtils.matchRoute(routeMatch3, "/FooService/barMethod", headers, random))
        .isTrue();
  }

  @Test
  public void routeMatching_pathOnly_caseInsensitive() {
    Metadata headers = new Metadata();
    ThreadSafeRandom random = mock(ThreadSafeRandom.class);

    RouteMatch routeMatch1 =
        RouteMatch.create(
            PathMatcher.fromPath("/FooService/barMethod", false),
            Collections.emptyList(), null);
    assertThat(RoutingUtils.matchRoute(routeMatch1, "/fooservice/barmethod", headers, random))
        .isTrue();

    RouteMatch routeMatch2 =
        RouteMatch.create(
            PathMatcher.fromPrefix("/FooService", false),
            Collections.emptyList(), null);
    assertThat(RoutingUtils.matchRoute(routeMatch2, "/fooservice/barmethod", headers, random))
        .isTrue();
  }

  @Test
  public void routeMatching_withHeaders() {
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("authority", Metadata.ASCII_STRING_MARSHALLER),
        "foo.googleapis.com");
    headers.put(Metadata.Key.of("grpc-encoding", Metadata.ASCII_STRING_MARSHALLER), "gzip");
    headers.put(Metadata.Key.of("user-agent", Metadata.ASCII_STRING_MARSHALLER), "gRPC-Java");
    headers.put(Metadata.Key.of("content-length", Metadata.ASCII_STRING_MARSHALLER), "1000");
    headers.put(Metadata.Key.of("custom-key", Metadata.ASCII_STRING_MARSHALLER), "custom-value1");
    headers.put(Metadata.Key.of("custom-key", Metadata.ASCII_STRING_MARSHALLER), "custom-value2");
    ThreadSafeRandom random = mock(ThreadSafeRandom.class);

    PathMatcher pathMatcher = PathMatcher.fromPath("/FooService/barMethod", true);
    RouteMatch routeMatch1 = RouteMatch.create(
        pathMatcher,
        Arrays.asList(
            HeaderMatcher.forExactValue("grpc-encoding", "gzip", false),
            HeaderMatcher.forSafeRegEx("authority", Pattern.compile(".*googleapis.*"), false),
            HeaderMatcher.forRange(
                "content-length", HeaderMatcher.Range.create(100, 10000), false),
            HeaderMatcher.forPresent("user-agent", true, false),
            HeaderMatcher.forPrefix("custom-key", "custom-", false),
            HeaderMatcher.forSuffix("custom-key", "value2", false)),
            null);
    assertThat(RoutingUtils.matchRoute(routeMatch1, "/FooService/barMethod", headers, random))
        .isTrue();

    RouteMatch routeMatch2 = RouteMatch.create(
        pathMatcher,
        Collections.singletonList(
            HeaderMatcher.forSafeRegEx("authority", Pattern.compile(".*googleapis.*"), true)),
        null);
    assertThat(RoutingUtils.matchRoute(routeMatch2, "/FooService/barMethod", headers, random))
        .isFalse();

    RouteMatch routeMatch3 = RouteMatch.create(
        pathMatcher,
        Collections.singletonList(
            HeaderMatcher.forExactValue("user-agent", "gRPC-Go", false)), null);
    assertThat(RoutingUtils.matchRoute(routeMatch3, "/FooService/barMethod", headers, random))
        .isFalse();

    RouteMatch routeMatch4 = RouteMatch.create(
        pathMatcher,
        Collections.singletonList(HeaderMatcher.forPresent("user-agent", false, false)),
        null);
    assertThat(RoutingUtils.matchRoute(routeMatch4, "/FooService/barMethod", headers, random))
        .isFalse();

    RouteMatch routeMatch5 = RouteMatch.create(
        pathMatcher,
        Collections.singletonList(HeaderMatcher.forPresent("user-agent", false, true)), // inverted
        null);
    assertThat(RoutingUtils.matchRoute(routeMatch5, "/FooService/barMethod", headers, random))
        .isTrue();

    RouteMatch routeMatch6 = RouteMatch.create(
        pathMatcher,
        Collections.singletonList(HeaderMatcher.forPresent("user-agent", true, true)),
        null);
    assertThat(RoutingUtils.matchRoute(routeMatch6, "/FooService/barMethod", headers, random))
        .isFalse();

    RouteMatch routeMatch7 = RouteMatch.create(
        pathMatcher,
        Collections.singletonList(
            HeaderMatcher.forExactValue("custom-key", "custom-value1,custom-value2", false)),
        null);
    assertThat(RoutingUtils.matchRoute(routeMatch7, "/FooService/barMethod", headers, random))
        .isTrue();

    RouteMatch routeMatch8 = RouteMatch.create(
        pathMatcher,
        Collections.singletonList(
            HeaderMatcher.forExactValue("content-type", "application/grpc", false)),
        null);
    assertThat(RoutingUtils.matchRoute(
        routeMatch8, "/FooService/barMethod", new Metadata(), random)).isTrue();

    RouteMatch routeMatch9 = RouteMatch.create(
        pathMatcher,
        Collections.singletonList(
            HeaderMatcher.forExactValue("custom-key!", "custom-value1,custom-value2", false)),
        null);
    assertThat(RoutingUtils.matchRoute(routeMatch9, "/FooService/barMethod", headers, random))
        .isFalse();
  }
}
