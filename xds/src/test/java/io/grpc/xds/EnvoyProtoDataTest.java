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
import io.envoyproxy.envoy.api.v2.route.QueryParameterMatcher;
import io.envoyproxy.envoy.api.v2.route.RedirectAction;
import io.grpc.xds.EnvoyProtoData.ClusterWeight;
import io.grpc.xds.EnvoyProtoData.HeaderMatcher;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.Route;
import io.grpc.xds.EnvoyProtoData.RouteAction;
import io.grpc.xds.EnvoyProtoData.RouteMatch;
import io.grpc.xds.EnvoyProtoData.StructOrError;
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
                new RouteAction("cluster-foo", null, null)));

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
  public void isDefaultRoute() {
    StructOrError<Route> struct1 = Route.fromEnvoyProtoRoute(buildSimpleRouteProto("", null));
    StructOrError<Route> struct2 = Route.fromEnvoyProtoRoute(buildSimpleRouteProto("/", null));
    StructOrError<Route> struct3 = Route.fromEnvoyProtoRoute(buildSimpleRouteProto(null, ""));
    StructOrError<Route> struct4 =
        Route.fromEnvoyProtoRoute(buildSimpleRouteProto("/service/", null));
    StructOrError<Route> struct5 =
        Route.fromEnvoyProtoRoute(buildSimpleRouteProto(null, "/service/method"));

    assertThat(struct1.getStruct().isDefaultRoute()).isTrue();
    assertThat(struct2.getStruct().isDefaultRoute()).isTrue();
    assertThat(struct3.getStruct().isDefaultRoute()).isTrue();
    assertThat(struct4.getStruct().isDefaultRoute()).isFalse();
    assertThat(struct5.getStruct().isDefaultRoute()).isFalse();
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
  public void convertRouteMatch() {
    // path_specifier = prefix
    io.envoyproxy.envoy.api.v2.route.RouteMatch proto1 =
        io.envoyproxy.envoy.api.v2.route.RouteMatch.newBuilder().setPrefix("/").build();
    StructOrError<RouteMatch> struct1 = RouteMatch.fromEnvoyProtoRouteMatch(proto1);
    assertThat(struct1.getErrorDetail()).isNull();
    assertThat(struct1.getStruct()).isEqualTo(
        new RouteMatch("/", null, null, null, Collections.<HeaderMatcher>emptyList()));

    // path_specifier = path
    io.envoyproxy.envoy.api.v2.route.RouteMatch proto2 =
        io.envoyproxy.envoy.api.v2.route.RouteMatch.newBuilder().setPath("").build();
    StructOrError<RouteMatch> struct2 = RouteMatch.fromEnvoyProtoRouteMatch(proto2);
    assertThat(struct2.getErrorDetail()).isNull();
    assertThat(struct2.getStruct()).isEqualTo(
        new RouteMatch(null, "", null, null, Collections.<HeaderMatcher>emptyList()));

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
                io.envoyproxy.envoy.type.matcher.RegexMatcher.newBuilder().setRegex("*")).build();
    StructOrError<RouteMatch> struct4 = RouteMatch.fromEnvoyProtoRouteMatch(proto4);
    assertThat(struct4.getErrorDetail()).isNull();
    assertThat(struct4.getStruct()).isEqualTo(
        new RouteMatch(null, null, "*", null, Collections.<HeaderMatcher>emptyList()));

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

    // TODO (chengyuanzhang): cover other path matching types.

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

    assertThat(struct1.getStruct()).isNotNull();
    assertThat(struct2.getStruct()).isNotNull();
    assertThat(struct3.getStruct()).isNull();
    assertThat(struct4.getStruct()).isNotNull();
    assertThat(struct5.getStruct()).isNotNull();
    assertThat(struct6.getStruct()).isNotNull();
    assertThat(struct7.getStruct()).isNull();
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
  public void convertRouteAction() {
    // cluster_specifier = cluster
    io.envoyproxy.envoy.api.v2.route.RouteAction proto1 =
        io.envoyproxy.envoy.api.v2.route.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .build();
    StructOrError<RouteAction> struct1 = RouteAction.fromEnvoyProtoRouteAction(proto1);
    assertThat(struct1.getErrorDetail()).isNull();
    assertThat(struct1.getStruct().getCluster()).isEqualTo("cluster-foo");
    assertThat(struct1.getStruct().getClusterHeader()).isNull();
    assertThat(struct1.getStruct().getWeightedCluster()).isNull();

    // cluster_specifier = cluster_header
    io.envoyproxy.envoy.api.v2.route.RouteAction proto2 =
        io.envoyproxy.envoy.api.v2.route.RouteAction.newBuilder()
            .setClusterHeader("cluster-bar")
            .build();
    StructOrError<RouteAction> struct2 = RouteAction.fromEnvoyProtoRouteAction(proto2);
    assertThat(struct2.getErrorDetail()).isNull();
    assertThat(struct2.getStruct().getCluster()).isNull();
    assertThat(struct2.getStruct().getClusterHeader()).isEqualTo("cluster-bar");
    assertThat(struct2.getStruct().getWeightedCluster()).isNull();

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
    assertThat(struct3.getStruct().getClusterHeader()).isNull();
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
        new HeaderMatcher(":method", null, "P*", null, null, null, null, false));

    // TODO (chengyuanzhang): cover other header_match types.

    // header_match_specifier unset
    io.envoyproxy.envoy.api.v2.route.HeaderMatcher unsetProto =
        io.envoyproxy.envoy.api.v2.route.HeaderMatcher.getDefaultInstance();
    StructOrError<HeaderMatcher> unsetStruct =
        HeaderMatcher.fromEnvoyProtoHeaderMatcher(unsetProto);
    assertThat(unsetStruct.getErrorDetail()).isNotNull();
    assertThat(unsetStruct.getStruct()).isNull();
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
