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

import com.google.protobuf.BoolValue;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.util.Durations;
import com.google.re2j.Pattern;
import io.envoyproxy.envoy.config.core.v3.RuntimeFractionalPercent;
import io.envoyproxy.envoy.config.route.v3.QueryParameterMatcher;
import io.envoyproxy.envoy.config.route.v3.RedirectAction;
import io.envoyproxy.envoy.config.route.v3.RouteAction.MaxStreamDuration;
import io.envoyproxy.envoy.config.route.v3.WeightedCluster;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.envoyproxy.envoy.type.v3.Int64Range;
import io.grpc.xds.ClientXdsClient.StructOrError;
import io.grpc.xds.Matchers.FractionMatcher;
import io.grpc.xds.Matchers.HeaderMatcher;
import io.grpc.xds.Matchers.PathMatcher;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.VirtualHost.Route.RouteAction;
import io.grpc.xds.VirtualHost.Route.RouteAction.ClusterWeight;
import io.grpc.xds.VirtualHost.Route.RouteMatch;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ClientXdsClientDataTest {

  @Test
  public void parseRoute() {
    io.envoyproxy.envoy.config.route.v3.Route proto1 =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-blade")
            .setMatch(
                io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
                    .setPath("/service/method"))
            .setRoute(
                io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
                    .setCluster("cluster-foo"))
            .build();
    StructOrError<Route> struct1 = ClientXdsClient.parseRoute(proto1);
    assertThat(struct1.getErrorDetail()).isNull();
    assertThat(struct1.getStruct())
        .isEqualTo(
            Route.create(
                RouteMatch.create(PathMatcher.fromPath("/service/method", false),
                    Collections.<HeaderMatcher>emptyList(), null),
                RouteAction.forCluster("cluster-foo", null), null));

    io.envoyproxy.envoy.config.route.v3.Route unsupportedProto =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-blade")
            .setMatch(io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPath(""))
            .setRedirect(RedirectAction.getDefaultInstance())
            .build();
    StructOrError<Route> unsupportedStruct = ClientXdsClient.parseRoute(unsupportedProto);
    assertThat(unsupportedStruct.getErrorDetail()).isNotNull();
    assertThat(unsupportedStruct.getStruct()).isNull();
  }

  @Test
  public void parseRoute_skipRouteWithUnsupportedMatcher() {
    io.envoyproxy.envoy.config.route.v3.Route proto =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("ignore me")
            .setMatch(
                io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
                    .setPath("/service/method")
                    .addQueryParameters(
                        io.envoyproxy.envoy.config.route.v3.QueryParameterMatcher
                            .getDefaultInstance()))
            .setRoute(
                io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
                    .setCluster("cluster-foo"))
            .build();
    assertThat(ClientXdsClient.parseRoute(proto)).isNull();
  }

  @Test
  public void parseRoute_skipRouteWithUnsupportedAction() {
    io.envoyproxy.envoy.config.route.v3.Route proto =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("ignore me")
            .setMatch(
                io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
                    .setPath("/service/method"))
            .setRoute(
                io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
                    .setClusterHeader("some cluster header"))
            .build();
    assertThat(ClientXdsClient.parseRoute(proto)).isNull();
  }

  @Test
  public void parseRouteMatch_withPathMatching() {
    // path_specifier = prefix
    io.envoyproxy.envoy.config.route.v3.RouteMatch proto1 =
        io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPrefix("/").build();
    StructOrError<RouteMatch> struct1 = ClientXdsClient.parseRouteMatch(proto1);
    assertThat(struct1.getErrorDetail()).isNull();
    assertThat(struct1.getStruct()).isEqualTo(
        RouteMatch.create(
            PathMatcher.fromPrefix("/", false), Collections.<HeaderMatcher>emptyList(), null));

    proto1 = proto1.toBuilder().setCaseSensitive(BoolValue.newBuilder().setValue(true)).build();
    struct1 = ClientXdsClient.parseRouteMatch(proto1);
    assertThat(struct1.getStruct()).isEqualTo(
        RouteMatch.create(
            PathMatcher.fromPrefix("/", true), Collections.<HeaderMatcher>emptyList(), null));

    // path_specifier = path
    io.envoyproxy.envoy.config.route.v3.RouteMatch proto2 =
        io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
            .setPath("/service/method")
            .build();
    StructOrError<RouteMatch> struct2 = ClientXdsClient.parseRouteMatch(proto2);
    assertThat(struct2.getErrorDetail()).isNull();
    assertThat(struct2.getStruct()).isEqualTo(
        RouteMatch.create(
            PathMatcher.fromPath("/service/method", false),
            Collections.<HeaderMatcher>emptyList(), null));

    proto2 = proto2.toBuilder().setCaseSensitive(BoolValue.newBuilder().setValue(true)).build();
    struct2 = ClientXdsClient.parseRouteMatch(proto2);
    assertThat(struct2.getStruct()).isEqualTo(
        RouteMatch.create(
            PathMatcher.fromPath("/service/method", true),
            Collections.<HeaderMatcher>emptyList(), null));

    // path_specifier = safe_regex
    io.envoyproxy.envoy.config.route.v3.RouteMatch proto4 =
        io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
            .setSafeRegex(RegexMatcher.newBuilder().setRegex("."))
            .build();
    StructOrError<RouteMatch> struct4 = ClientXdsClient.parseRouteMatch(proto4);
    assertThat(struct4.getErrorDetail()).isNull();
    assertThat(struct4.getStruct()).isEqualTo(
        RouteMatch.create(
            PathMatcher.fromRegEx(Pattern.compile(".")),
            Collections.<HeaderMatcher>emptyList(), null));

    // query_parameters is set
    io.envoyproxy.envoy.config.route.v3.RouteMatch proto6 =
        io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
            .addQueryParameters(QueryParameterMatcher.getDefaultInstance())
            .build();
    StructOrError<RouteMatch> struct6 = ClientXdsClient.parseRouteMatch(proto6);
    assertThat(struct6).isNull();

    // path_specifier unset
    io.envoyproxy.envoy.config.route.v3.RouteMatch unsetProto =
        io.envoyproxy.envoy.config.route.v3.RouteMatch.getDefaultInstance();
    StructOrError<RouteMatch> unsetStruct = ClientXdsClient.parseRouteMatch(unsetProto);
    assertThat(unsetStruct.getErrorDetail()).isNotNull();
    assertThat(unsetStruct.getStruct()).isNull();
  }

  @Test
  public void parseRouteMatch_withHeaderMatching() {
    io.envoyproxy.envoy.config.route.v3.RouteMatch proto =
        io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
            .setPrefix("")
            .addHeaders(
                io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
                    .setName(":scheme")
                    .setPrefixMatch("http"))
            .addHeaders(
                io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
                    .setName(":method")
                    .setExactMatch("PUT"))
            .build();
    StructOrError<RouteMatch> struct = ClientXdsClient.parseRouteMatch(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct())
        .isEqualTo(
            RouteMatch.create(
                PathMatcher.fromPrefix("", false),
                Arrays.asList(
                    HeaderMatcher.forPrefix(":scheme", "http", false),
                    HeaderMatcher.forExactValue(":method", "PUT", false)),
                null));
  }

  @Test
  public void parseRouteMatch_withRuntimeFraction() {
    io.envoyproxy.envoy.config.route.v3.RouteMatch proto =
        io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
            .setPrefix("")
            .setRuntimeFraction(
                RuntimeFractionalPercent.newBuilder()
                    .setDefaultValue(
                        FractionalPercent.newBuilder()
                            .setNumerator(30)
                            .setDenominator(FractionalPercent.DenominatorType.HUNDRED)))
            .build();
    StructOrError<RouteMatch> struct = ClientXdsClient.parseRouteMatch(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct())
        .isEqualTo(
            RouteMatch.create(
                PathMatcher.fromPrefix( "", false), Collections.<HeaderMatcher>emptyList(),
                FractionMatcher.create(30, 100)));
  }

  @Test
  public void parseHeaderMatcher() {
    // header_match_specifier = exact_match
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto1 =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName(":method")
            .setExactMatch("PUT")
            .build();
    StructOrError<HeaderMatcher> struct1 = ClientXdsClient.parseHeaderMatcher(proto1);
    assertThat(struct1.getErrorDetail()).isNull();
    assertThat(struct1.getStruct()).isEqualTo(
        HeaderMatcher.forExactValue(":method", "PUT", false));

    // header_match_specifier = safe_regex_match
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto3 =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName(":method")
            .setSafeRegexMatch(RegexMatcher.newBuilder().setRegex("P*"))
            .build();
    StructOrError<HeaderMatcher> struct3 = ClientXdsClient.parseHeaderMatcher(proto3);
    assertThat(struct3.getErrorDetail()).isNull();
    assertThat(struct3.getStruct()).isEqualTo(
        HeaderMatcher.forSafeRegEx(":method", Pattern.compile("P*"), false));

    // header_match_specifier = range_match
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto4 =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName("timeout")
            .setRangeMatch(Int64Range.newBuilder().setStart(10L).setEnd(20L))
            .build();
    StructOrError<HeaderMatcher> struct4 = ClientXdsClient.parseHeaderMatcher(proto4);
    assertThat(struct4.getErrorDetail()).isNull();
    assertThat(struct4.getStruct()).isEqualTo(
        HeaderMatcher.forRange("timeout", HeaderMatcher.Range.create(10L, 20L), false));

    // header_match_specifier = present_match
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto5 =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName("user-agent")
            .setPresentMatch(true)
            .build();
    StructOrError<HeaderMatcher> struct5 = ClientXdsClient.parseHeaderMatcher(proto5);
    assertThat(struct5.getErrorDetail()).isNull();
    assertThat(struct5.getStruct()).isEqualTo(
        HeaderMatcher.forPresent("user-agent", true, false));

    // header_match_specifier = prefix_match
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto6 =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName("authority")
            .setPrefixMatch("service-foo")
            .build();
    StructOrError<HeaderMatcher> struct6 = ClientXdsClient.parseHeaderMatcher(proto6);
    assertThat(struct6.getErrorDetail()).isNull();
    assertThat(struct6.getStruct()).isEqualTo(
        HeaderMatcher.forPrefix("authority", "service-foo", false));

    // header_match_specifier = suffix_match
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto7 =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName("authority")
            .setSuffixMatch("googleapis.com")
            .build();
    StructOrError<HeaderMatcher> struct7 = ClientXdsClient.parseHeaderMatcher(proto7);
    assertThat(struct7.getErrorDetail()).isNull();
    assertThat(struct7.getStruct()).isEqualTo(
        HeaderMatcher.forSuffix("authority", "googleapis.com", false));

    // header_match_specifier unset
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher unsetProto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.getDefaultInstance();
    StructOrError<HeaderMatcher> unsetStruct = ClientXdsClient.parseHeaderMatcher(unsetProto);
    assertThat(unsetStruct.getErrorDetail()).isNotNull();
    assertThat(unsetStruct.getStruct()).isNull();
  }

  @Test
  public void parseHeaderMatcher_malformedRegExPattern() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName(":method")
            .setSafeRegexMatch(RegexMatcher.newBuilder().setRegex("["))
            .build();
    StructOrError<HeaderMatcher> struct = ClientXdsClient.parseHeaderMatcher(proto);
    assertThat(struct.getErrorDetail()).isNotNull();
    assertThat(struct.getStruct()).isNull();
  }

  @Test
  public void parseRouteAction_withCluster() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .build();
    StructOrError<RouteAction> struct = ClientXdsClient.parseRouteAction(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct().cluster()).isEqualTo("cluster-foo");
    assertThat(struct.getStruct().weightedClusters()).isNull();
  }

  @Test
  public void parseRouteAction_withWeightedCluster() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setWeightedClusters(
                WeightedCluster.newBuilder()
                    .addClusters(
                        WeightedCluster.ClusterWeight
                            .newBuilder()
                            .setName("cluster-foo")
                            .setWeight(UInt32Value.newBuilder().setValue(30)))
                    .addClusters(WeightedCluster.ClusterWeight
                        .newBuilder()
                        .setName("cluster-bar")
                        .setWeight(UInt32Value.newBuilder().setValue(70))))
            .build();
    StructOrError<RouteAction> struct = ClientXdsClient.parseRouteAction(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct().cluster()).isNull();
    assertThat(struct.getStruct().weightedClusters()).containsExactly(
        ClusterWeight.create("cluster-foo", 30, null),
        ClusterWeight.create("cluster-bar", 70, null));
  }

  @Test
  public void parseRouteAction_withUnspecifiedCluster() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.getDefaultInstance();
    StructOrError<RouteAction> unsetStruct = ClientXdsClient.parseRouteAction(proto);
    assertThat(unsetStruct.getStruct()).isNull();
    assertThat(unsetStruct.getErrorDetail()).isNotNull();
  }

  @Test
  public void parseRouteAction_withTimeoutByGrpcTimeoutHeaderMax() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .setMaxStreamDuration(
                MaxStreamDuration.newBuilder()
                    .setGrpcTimeoutHeaderMax(Durations.fromSeconds(5L))
                    .setMaxStreamDuration(Durations.fromMillis(20L)))
            .build();
    StructOrError<RouteAction> struct = ClientXdsClient.parseRouteAction(proto);
    assertThat(struct.getStruct().timeoutNano()).isEqualTo(TimeUnit.SECONDS.toNanos(5L));
  }

  @Test
  public void parseRouteAction_withTimeoutByMaxStreamDuration() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .setMaxStreamDuration(
                MaxStreamDuration.newBuilder()
                    .setMaxStreamDuration(Durations.fromSeconds(5L)))
            .build();
    StructOrError<RouteAction> struct = ClientXdsClient.parseRouteAction(proto);
    assertThat(struct.getStruct().timeoutNano()).isEqualTo(TimeUnit.SECONDS.toNanos(5L));
  }

  @Test
  public void parseRouteAction_withTimeoutUnset() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .build();
    StructOrError<RouteAction> struct = ClientXdsClient.parseRouteAction(proto);
    assertThat(struct.getStruct().timeoutNano()).isNull();
  }

  @Test
  public void parseClusterWeight() {
    io.envoyproxy.envoy.config.route.v3.WeightedCluster.ClusterWeight proto =
        io.envoyproxy.envoy.config.route.v3.WeightedCluster.ClusterWeight.newBuilder()
            .setName("cluster-foo")
            .setWeight(UInt32Value.newBuilder().setValue(30)).build();
    ClusterWeight struct = ClientXdsClient.parseClusterWeight(proto).getStruct();
    assertThat(struct.name()).isEqualTo("cluster-foo");
    assertThat(struct.weight()).isEqualTo(30);
  }
}
