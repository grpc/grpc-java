/*
 * Copyright 2019 The gRPC Authors
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

import com.google.common.testing.EqualsTester;
import com.google.protobuf.BoolValue;
import com.google.protobuf.UInt32Value;
import com.google.re2j.Pattern;
import io.envoyproxy.envoy.api.v2.route.QueryParameterMatcher;
import io.envoyproxy.envoy.api.v2.route.RedirectAction;
import io.grpc.xds.EnvoyProtoData.ClusterWeight;
import io.grpc.xds.EnvoyProtoData.HeaderMatcher;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.Route;
import io.grpc.xds.EnvoyProtoData.RouteAction;
import io.grpc.xds.EnvoyProtoData.RouteMatch;
import io.grpc.xds.EnvoyProtoData.StructOrError;
import java.util.Arrays;
import java.util.Collections;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link EnvoyProtoData}.
 */
@RunWith(JUnit4.class)
public class EnvoyProtoDataTest {

  @Test
  public void locality_convertToAndFromLocalityProto() {
    io.envoyproxy.envoy.api.v2.core.Locality locality =
        io.envoyproxy.envoy.api.v2.core.Locality.newBuilder()
            .setRegion("test_region")
            .setZone("test_zone")
            .setSubZone("test_subzone")
            .build();
    Locality xdsLocality = Locality.fromEnvoyProtoLocality(locality);
    assertThat(xdsLocality.getRegion()).isEqualTo("test_region");
    assertThat(xdsLocality.getZone()).isEqualTo("test_zone");
    assertThat(xdsLocality.getSubZone()).isEqualTo("test_subzone");

    io.envoyproxy.envoy.api.v2.core.Locality convertedLocality = xdsLocality.toEnvoyProtoLocality();
    assertThat(convertedLocality.getRegion()).isEqualTo("test_region");
    assertThat(convertedLocality.getZone()).isEqualTo("test_zone");
    assertThat(convertedLocality.getSubZone()).isEqualTo("test_subzone");
  }

  @Test
  public void locality_equal() {
    new EqualsTester()
        .addEqualityGroup(
            new Locality("region-a", "zone-a", "subzone-a"),
            new Locality("region-a", "zone-a", "subzone-a"))
        .addEqualityGroup(
            new Locality("region", "zone", "subzone")
        )
        .addEqualityGroup(
            new Locality("", "", ""),
            new Locality("", "", ""))
        .testEquals();
  }

  @Test
  public void locality_hash() {
    assertThat(new Locality("region", "zone", "subzone").hashCode())
        .isEqualTo(new Locality("region", "zone","subzone").hashCode());
  }

  // TODO(chengyuanzhang): add test for other data types.

  @Test
  public void convertRoute() {
    io.envoyproxy.envoy.api.v2.route.Route proto1 =
        io.envoyproxy.envoy.api.v2.route.Route.newBuilder()
            .setName("route-blade")
            .setMatch(
                io.envoyproxy.envoy.api.v2.route.RouteMatch.newBuilder()
                    .setPath("/service/method"))
            .setRoute(
                io.envoyproxy.envoy.api.v2.route.RouteAction.newBuilder()
                    .setCluster("cluster-foo"))
            .build();
    StructOrError<Route> struct1 = Route.fromEnvoyProtoRoute(proto1);
    assertThat(struct1.getErrorDetail()).isNull();
    assertThat(struct1.getStruct())
        .isEqualTo(
            new Route(
                new RouteMatch(
                    null, "/service/method", null, null, Collections.<HeaderMatcher>emptyList()),
                new RouteAction("cluster-foo", null)));

    io.envoyproxy.envoy.api.v2.route.Route unsupportedProto =
        io.envoyproxy.envoy.api.v2.route.Route.newBuilder()
            .setName("route-blade")
            .setMatch(io.envoyproxy.envoy.api.v2.route.RouteMatch.newBuilder().setPath(""))
            .setRedirect(RedirectAction.getDefaultInstance())
            .build();
    StructOrError<Route> unsupportedStruct = Route.fromEnvoyProtoRoute(unsupportedProto);
    assertThat(unsupportedStruct.getErrorDetail()).isNotNull();
    assertThat(unsupportedStruct.getStruct()).isNull();
  }

  @Test
  public void convertRoute_skipWithUnsupportedMatcher() {
    io.envoyproxy.envoy.api.v2.route.Route proto =
        io.envoyproxy.envoy.api.v2.route.Route.newBuilder()
            .setName("ignore me")
            .setMatch(
                io.envoyproxy.envoy.api.v2.route.RouteMatch.newBuilder()
                    .setPath("/service/method")
                    .addQueryParameters(
                        io.envoyproxy.envoy.api.v2.route.QueryParameterMatcher
                            .getDefaultInstance()))
            .setRoute(
                io.envoyproxy.envoy.api.v2.route.RouteAction.newBuilder()
                    .setCluster("cluster-foo"))
            .build();
    assertThat(Route.fromEnvoyProtoRoute(proto)).isNull();
  }

  @Test
  public void convertRoute_skipWithUnsupportedAction() {
    io.envoyproxy.envoy.api.v2.route.Route proto =
        io.envoyproxy.envoy.api.v2.route.Route.newBuilder()
            .setName("ignore me")
            .setMatch(
                io.envoyproxy.envoy.api.v2.route.RouteMatch.newBuilder()
                    .setPath("/service/method"))
            .setRoute(
                io.envoyproxy.envoy.api.v2.route.RouteAction.newBuilder()
                    .setClusterHeader("some cluster header"))
            .build();
    assertThat(Route.fromEnvoyProtoRoute(proto)).isNull();
  }

  @Test
  public void isDefaultRoute() {
    StructOrError<Route> struct1 = Route.fromEnvoyProtoRoute(buildSimpleRouteProto("", null));
    StructOrError<Route> struct2 = Route.fromEnvoyProtoRoute(buildSimpleRouteProto("/", null));
    StructOrError<Route> struct3 =
        Route.fromEnvoyProtoRoute(buildSimpleRouteProto("/service/", null));
    StructOrError<Route> struct4 =
        Route.fromEnvoyProtoRoute(buildSimpleRouteProto(null, "/service/method"));

    assertThat(struct1.getStruct().isDefaultRoute()).isTrue();
    assertThat(struct2.getStruct().isDefaultRoute()).isTrue();
    assertThat(struct3.getStruct().isDefaultRoute()).isFalse();
    assertThat(struct4.getStruct().isDefaultRoute()).isFalse();
  }

  private static io.envoyproxy.envoy.api.v2.route.Route buildSimpleRouteProto(
      @Nullable String pathPrefix, @Nullable String path) {
    io.envoyproxy.envoy.api.v2.route.Route.Builder routeBuilder =
        io.envoyproxy.envoy.api.v2.route.Route.newBuilder()
            .setName("simple-route")
            .setRoute(io.envoyproxy.envoy.api.v2.route.RouteAction.newBuilder()
            .setCluster("simple-cluster"));
    if (pathPrefix != null) {
      routeBuilder.setMatch(io.envoyproxy.envoy.api.v2.route.RouteMatch.newBuilder()
          .setPrefix(pathPrefix));
    } else if (path != null) {
      routeBuilder.setMatch(io.envoyproxy.envoy.api.v2.route.RouteMatch.newBuilder()
          .setPath(path));
    }
    return routeBuilder.build();
  }

  @Test
  public void convertRouteMatch_pathMatching() {
    // path_specifier = prefix
    io.envoyproxy.envoy.api.v2.route.RouteMatch proto1 =
        io.envoyproxy.envoy.api.v2.route.RouteMatch.newBuilder().setPrefix("/").build();
    StructOrError<RouteMatch> struct1 = RouteMatch.fromEnvoyProtoRouteMatch(proto1);
    assertThat(struct1.getErrorDetail()).isNull();
    assertThat(struct1.getStruct()).isEqualTo(
        new RouteMatch("/", null, null, null, Collections.<HeaderMatcher>emptyList()));

    // path_specifier = path
    io.envoyproxy.envoy.api.v2.route.RouteMatch proto2 =
        io.envoyproxy.envoy.api.v2.route.RouteMatch.newBuilder().setPath("/service/method").build();
    StructOrError<RouteMatch> struct2 = RouteMatch.fromEnvoyProtoRouteMatch(proto2);
    assertThat(struct2.getErrorDetail()).isNull();
    assertThat(struct2.getStruct()).isEqualTo(
        new RouteMatch(
            null, "/service/method", null, null, Collections.<HeaderMatcher>emptyList()));

    // path_specifier = regex
    @SuppressWarnings("deprecation")
    io.envoyproxy.envoy.api.v2.route.RouteMatch proto3 =
        io.envoyproxy.envoy.api.v2.route.RouteMatch.newBuilder().setRegex("*").build();
    StructOrError<RouteMatch> struct3 = RouteMatch.fromEnvoyProtoRouteMatch(proto3);
    assertThat(struct3.getErrorDetail()).isNotNull();
    assertThat(struct3.getStruct()).isNull();

    // path_specifier = safe_regex
    io.envoyproxy.envoy.api.v2.route.RouteMatch proto4 =
        io.envoyproxy.envoy.api.v2.route.RouteMatch.newBuilder()
            .setSafeRegex(
                io.envoyproxy.envoy.type.matcher.RegexMatcher.newBuilder().setRegex(".")).build();
    StructOrError<RouteMatch> struct4 = RouteMatch.fromEnvoyProtoRouteMatch(proto4);
    assertThat(struct4.getErrorDetail()).isNull();
    assertThat(struct4.getStruct()).isEqualTo(
        new RouteMatch(
            null, null, Pattern.compile("."), null, Collections.<HeaderMatcher>emptyList()));

    // case_sensitive = false
    io.envoyproxy.envoy.api.v2.route.RouteMatch proto5 =
        io.envoyproxy.envoy.api.v2.route.RouteMatch.newBuilder()
            .setCaseSensitive(BoolValue.newBuilder().setValue(false))
            .build();
    StructOrError<RouteMatch> struct5 = RouteMatch.fromEnvoyProtoRouteMatch(proto5);
    assertThat(struct5.getErrorDetail()).isNotNull();
    assertThat(struct5.getStruct()).isNull();

    // query_parameters is set
    io.envoyproxy.envoy.api.v2.route.RouteMatch proto6 =
        io.envoyproxy.envoy.api.v2.route.RouteMatch.newBuilder()
            .addQueryParameters(QueryParameterMatcher.getDefaultInstance())
            .build();
    StructOrError<RouteMatch> struct6 = RouteMatch.fromEnvoyProtoRouteMatch(proto6);
    assertThat(struct6).isNull();

    // path_specifier unset
    io.envoyproxy.envoy.api.v2.route.RouteMatch unsetProto =
        io.envoyproxy.envoy.api.v2.route.RouteMatch.getDefaultInstance();
    StructOrError<RouteMatch> unsetStruct = RouteMatch.fromEnvoyProtoRouteMatch(unsetProto);
    assertThat(unsetStruct.getErrorDetail()).isNotNull();
    assertThat(unsetStruct.getStruct()).isNull();
  }

  @Test
  public void convertRouteMatch_pathMatchFormat() {
    StructOrError<RouteMatch> struct1 =
        RouteMatch.fromEnvoyProtoRouteMatch(buildSimpleRouteMatchProto("", null));
    StructOrError<RouteMatch> struct2 =
        RouteMatch.fromEnvoyProtoRouteMatch(buildSimpleRouteMatchProto("/", null));
    StructOrError<RouteMatch> struct3 =
        RouteMatch.fromEnvoyProtoRouteMatch(buildSimpleRouteMatchProto("/service", null));
    StructOrError<RouteMatch> struct4 =
        RouteMatch.fromEnvoyProtoRouteMatch(buildSimpleRouteMatchProto("/service/", null));
    StructOrError<RouteMatch> struct5 =
        RouteMatch.fromEnvoyProtoRouteMatch(buildSimpleRouteMatchProto(null, ""));
    StructOrError<RouteMatch> struct6 =
        RouteMatch.fromEnvoyProtoRouteMatch(buildSimpleRouteMatchProto(null, "/service/method"));
    StructOrError<RouteMatch> struct7 =
        RouteMatch.fromEnvoyProtoRouteMatch(buildSimpleRouteMatchProto(null, "/service/method/"));
    StructOrError<RouteMatch> struct8 =
        RouteMatch.fromEnvoyProtoRouteMatch(
            io.envoyproxy.envoy.api.v2.route.RouteMatch.newBuilder()
                .setSafeRegex(
                    io.envoyproxy.envoy.type.matcher.RegexMatcher.newBuilder().setRegex("["))
                .build());

    assertThat(struct1.getStruct()).isNotNull();
    assertThat(struct2.getStruct()).isNotNull();
    assertThat(struct3.getStruct()).isNull();
    assertThat(struct4.getStruct()).isNotNull();
    assertThat(struct5.getStruct()).isNull();
    assertThat(struct6.getStruct()).isNotNull();
    assertThat(struct7.getStruct()).isNull();
    assertThat(struct8.getStruct()).isNull();
  }

  private static io.envoyproxy.envoy.api.v2.route.RouteMatch buildSimpleRouteMatchProto(
      @Nullable String pathPrefix, @Nullable String path) {
    io.envoyproxy.envoy.api.v2.route.RouteMatch.Builder builder =
        io.envoyproxy.envoy.api.v2.route.RouteMatch.newBuilder();
    if (pathPrefix != null) {
      builder.setPrefix(pathPrefix);
    } else if (path != null) {
      builder.setPath(path);
    }
    return builder.build();
  }

  @Test
  public void convertRouteMatch_withHeaderMatching() {
    io.envoyproxy.envoy.api.v2.route.RouteMatch proto =
        io.envoyproxy.envoy.api.v2.route.RouteMatch.newBuilder()
            .setPrefix("")
            .addHeaders(
                io.envoyproxy.envoy.api.v2.route.HeaderMatcher.newBuilder()
                    .setName(":scheme")
                    .setPrefixMatch("http"))
            .addHeaders(
                io.envoyproxy.envoy.api.v2.route.HeaderMatcher.newBuilder()
                    .setName(":method")
                    .setExactMatch("PUT"))
            .build();
    StructOrError<RouteMatch> struct = RouteMatch.fromEnvoyProtoRouteMatch(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct())
        .isEqualTo(
            new RouteMatch("", null, null, null,
                Arrays.asList(
                    new HeaderMatcher(":scheme", null, null, null, null, "http", null, false),
                    new HeaderMatcher(":method", "PUT", null, null, null, null, null, false))));
  }

  @Test
  public void convertRouteMatch_withRuntimeFraction() {
    io.envoyproxy.envoy.api.v2.route.RouteMatch proto =
        io.envoyproxy.envoy.api.v2.route.RouteMatch.newBuilder()
            .setPrefix("")
            .setRuntimeFraction(
                io.envoyproxy.envoy.api.v2.core.RuntimeFractionalPercent.newBuilder()
                    .setDefaultValue(
                        io.envoyproxy.envoy.type.FractionalPercent.newBuilder()
                            .setNumerator(30)
                            .setDenominator(
                                io.envoyproxy.envoy.type.FractionalPercent.DenominatorType
                                    .HUNDRED)))
            .build();
    StructOrError<RouteMatch> struct = RouteMatch.fromEnvoyProtoRouteMatch(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct())
        .isEqualTo(
            new RouteMatch(
                "", null, null, new RouteMatch.Fraction(30, 100),
                Collections.<HeaderMatcher>emptyList()));
  }

  @Test
  public void convertRouteAction() {
    // cluster_specifier = cluster
    io.envoyproxy.envoy.api.v2.route.RouteAction proto1 =
        io.envoyproxy.envoy.api.v2.route.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .build();
    StructOrError<RouteAction> struct1 = RouteAction.fromEnvoyProtoRouteAction(proto1);
    assertThat(struct1.getErrorDetail()).isNull();
    assertThat(struct1.getStruct().getCluster()).isEqualTo("cluster-foo");
    assertThat(struct1.getStruct().getWeightedCluster()).isNull();

    // cluster_specifier = cluster_header
    io.envoyproxy.envoy.api.v2.route.RouteAction proto2 =
        io.envoyproxy.envoy.api.v2.route.RouteAction.newBuilder()
            .setClusterHeader("cluster-bar")
            .build();
    StructOrError<RouteAction> struct2 = RouteAction.fromEnvoyProtoRouteAction(proto2);
    assertThat(struct2).isNull();

    // cluster_specifier = weighted_cluster
    io.envoyproxy.envoy.api.v2.route.RouteAction proto3 =
        io.envoyproxy.envoy.api.v2.route.RouteAction.newBuilder()
            .setWeightedClusters(
                io.envoyproxy.envoy.api.v2.route.WeightedCluster.newBuilder()
                    .addClusters(
                        io.envoyproxy.envoy.api.v2.route.WeightedCluster.ClusterWeight.newBuilder()
                            .setName("cluster-baz")
                            .setWeight(UInt32Value.newBuilder().setValue(100))))
            .build();
    StructOrError<RouteAction> struct3 = RouteAction.fromEnvoyProtoRouteAction(proto3);
    assertThat(struct3.getErrorDetail()).isNull();
    assertThat(struct3.getStruct().getCluster()).isNull();
    assertThat(struct3.getStruct().getWeightedCluster())
        .containsExactly(new ClusterWeight("cluster-baz", 100));

    // cluster_specifier unset
    io.envoyproxy.envoy.api.v2.route.RouteAction unsetProto =
        io.envoyproxy.envoy.api.v2.route.RouteAction.getDefaultInstance();
    StructOrError<RouteAction> unsetStruct = RouteAction.fromEnvoyProtoRouteAction(unsetProto);
    assertThat(unsetStruct.getErrorDetail()).isNotNull();
    assertThat(unsetStruct.getStruct()).isNull();
  }

  @Test
  public void convertHeaderMatcher() {
    // header_match_specifier = exact_match
    io.envoyproxy.envoy.api.v2.route.HeaderMatcher proto1 =
        io.envoyproxy.envoy.api.v2.route.HeaderMatcher.newBuilder()
            .setName(":method")
            .setExactMatch("PUT")
            .build();
    StructOrError<HeaderMatcher> struct1 = HeaderMatcher.fromEnvoyProtoHeaderMatcher(proto1);
    assertThat(struct1.getErrorDetail()).isNull();
    assertThat(struct1.getStruct()).isEqualTo(
        new HeaderMatcher(":method", "PUT", null, null, null, null, null, false));

    // header_match_specifier = regex_match
    @SuppressWarnings("deprecation")
    io.envoyproxy.envoy.api.v2.route.HeaderMatcher proto2 =
        io.envoyproxy.envoy.api.v2.route.HeaderMatcher.newBuilder()
            .setName(":method")
            .setRegexMatch("*")
            .build();
    StructOrError<HeaderMatcher> struct2 = HeaderMatcher.fromEnvoyProtoHeaderMatcher(proto2);
    assertThat(struct2.getErrorDetail()).isNotNull();
    assertThat(struct2.getStruct()).isNull();

    // header_match_specifier = safe_regex_match
    io.envoyproxy.envoy.api.v2.route.HeaderMatcher proto3 =
        io.envoyproxy.envoy.api.v2.route.HeaderMatcher.newBuilder()
            .setName(":method")
            .setSafeRegexMatch(
                io.envoyproxy.envoy.type.matcher.RegexMatcher.newBuilder().setRegex("P*"))
            .build();
    StructOrError<HeaderMatcher> struct3 = HeaderMatcher.fromEnvoyProtoHeaderMatcher(proto3);
    assertThat(struct3.getErrorDetail()).isNull();
    assertThat(struct3.getStruct()).isEqualTo(
        new HeaderMatcher(":method", null, Pattern.compile("P*"), null, null, null, null, false));

    // header_match_specifier = range_match
    io.envoyproxy.envoy.api.v2.route.HeaderMatcher proto4 =
        io.envoyproxy.envoy.api.v2.route.HeaderMatcher.newBuilder()
            .setName("timeout")
            .setRangeMatch(
                io.envoyproxy.envoy.type.Int64Range.newBuilder().setStart(10L).setEnd(20L))
            .build();
    StructOrError<HeaderMatcher> struct4 = HeaderMatcher.fromEnvoyProtoHeaderMatcher(proto4);
    assertThat(struct4.getErrorDetail()).isNull();
    assertThat(struct4.getStruct()).isEqualTo(
        new HeaderMatcher(
            "timeout", null, null, new HeaderMatcher.Range(10L, 20L), null, null, null, false));

    // header_match_specifier = present_match
    io.envoyproxy.envoy.api.v2.route.HeaderMatcher proto5 =
        io.envoyproxy.envoy.api.v2.route.HeaderMatcher.newBuilder()
            .setName("user-agent")
            .setPresentMatch(true)
            .build();
    StructOrError<HeaderMatcher> struct5 = HeaderMatcher.fromEnvoyProtoHeaderMatcher(proto5);
    assertThat(struct5.getErrorDetail()).isNull();
    assertThat(struct5.getStruct()).isEqualTo(
        new HeaderMatcher("user-agent", null, null, null, true, null, null, false));

    // header_match_specifier = prefix_match
    io.envoyproxy.envoy.api.v2.route.HeaderMatcher proto6 =
        io.envoyproxy.envoy.api.v2.route.HeaderMatcher.newBuilder()
            .setName("authority")
            .setPrefixMatch("service-foo")
            .build();
    StructOrError<HeaderMatcher> struct6 = HeaderMatcher.fromEnvoyProtoHeaderMatcher(proto6);
    assertThat(struct6.getErrorDetail()).isNull();
    assertThat(struct6.getStruct()).isEqualTo(
        new HeaderMatcher("authority", null, null, null, null, "service-foo", null, false));

    // header_match_specifier = suffix_match
    io.envoyproxy.envoy.api.v2.route.HeaderMatcher proto7 =
        io.envoyproxy.envoy.api.v2.route.HeaderMatcher.newBuilder()
            .setName("authority")
            .setSuffixMatch("googleapis.com")
            .build();
    StructOrError<HeaderMatcher> struct7 = HeaderMatcher.fromEnvoyProtoHeaderMatcher(proto7);
    assertThat(struct7.getErrorDetail()).isNull();
    assertThat(struct7.getStruct()).isEqualTo(
        new HeaderMatcher(
            "authority", null, null, null, null, null, "googleapis.com", false));

    // header_match_specifier unset
    io.envoyproxy.envoy.api.v2.route.HeaderMatcher unsetProto =
        io.envoyproxy.envoy.api.v2.route.HeaderMatcher.getDefaultInstance();
    StructOrError<HeaderMatcher> unsetStruct =
        HeaderMatcher.fromEnvoyProtoHeaderMatcher(unsetProto);
    assertThat(unsetStruct.getErrorDetail()).isNotNull();
    assertThat(unsetStruct.getStruct()).isNull();
  }

  @Test
  public void convertHeaderMatcher_malformedRegExPattern() {
    io.envoyproxy.envoy.api.v2.route.HeaderMatcher proto =
        io.envoyproxy.envoy.api.v2.route.HeaderMatcher.newBuilder()
            .setName(":method")
            .setSafeRegexMatch(
                io.envoyproxy.envoy.type.matcher.RegexMatcher.newBuilder().setRegex("["))
            .build();
    StructOrError<HeaderMatcher> struct = HeaderMatcher.fromEnvoyProtoHeaderMatcher(proto);
    assertThat(struct.getErrorDetail()).isNotNull();
    assertThat(struct.getStruct()).isNull();
  }

  @Test
  public void convertClusterWeight() {
    io.envoyproxy.envoy.api.v2.route.WeightedCluster.ClusterWeight proto =
        io.envoyproxy.envoy.api.v2.route.WeightedCluster.ClusterWeight.newBuilder()
            .setName("cluster-foo")
            .setWeight(UInt32Value.newBuilder().setValue(30)).build();
    ClusterWeight struct = ClusterWeight.fromEnvoyProtoClusterWeight(proto);
    assertThat(struct.getName()).isEqualTo("cluster-foo");
    assertThat(struct.getWeight()).isEqualTo(30);
  }
}
