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
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.StringValue;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.util.Durations;
import com.google.re2j.Pattern;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.ExtensionConfigSource;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.core.v3.RuntimeFractionalPercent;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.core.v3.TrafficDirection;
import io.envoyproxy.envoy.config.core.v3.TransportSocket;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.listener.v3.Filter;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.FilterChainMatch;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.listener.v3.ListenerFilter;
import io.envoyproxy.envoy.config.route.v3.DirectResponseAction;
import io.envoyproxy.envoy.config.route.v3.FilterAction;
import io.envoyproxy.envoy.config.route.v3.RedirectAction;
import io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.ConnectionProperties;
import io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.FilterState;
import io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.Header;
import io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.QueryParameter;
import io.envoyproxy.envoy.config.route.v3.RouteAction.MaxStreamDuration;
import io.envoyproxy.envoy.config.route.v3.WeightedCluster;
import io.envoyproxy.envoy.extensions.filters.common.fault.v3.FaultDelay;
import io.envoyproxy.envoy.extensions.filters.http.fault.v3.HTTPFault;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatchAndSubstitute;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher.GoogleRE2;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.envoyproxy.envoy.type.v3.FractionalPercent.DenominatorType;
import io.envoyproxy.envoy.type.v3.Int64Range;
import io.grpc.Status.Code;
import io.grpc.xds.ClientXdsClient.StructOrError;
import io.grpc.xds.Endpoints.LbEndpoint;
import io.grpc.xds.Endpoints.LocalityLbEndpoints;
import io.grpc.xds.FaultConfig.FaultAbort;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.Matchers.FractionMatcher;
import io.grpc.xds.Matchers.HeaderMatcher;
import io.grpc.xds.Matchers.PathMatcher;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.VirtualHost.Route.RouteAction;
import io.grpc.xds.VirtualHost.Route.RouteAction.ClusterWeight;
import io.grpc.xds.VirtualHost.Route.RouteAction.HashPolicy;
import io.grpc.xds.VirtualHost.Route.RouteMatch;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ClientXdsClientDataTest {

  @Test
  public void parseRoute_withRouteAction() {
    io.envoyproxy.envoy.config.route.v3.Route proto =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-blade")
            .setMatch(
                io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
                    .setPath("/service/method"))
            .setRoute(
                io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
                    .setCluster("cluster-foo"))
            .build();
    StructOrError<Route> struct = ClientXdsClient.parseRoute(proto, false);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct())
        .isEqualTo(
            Route.create(
                RouteMatch.create(PathMatcher.fromPath("/service/method", false),
                    Collections.<HeaderMatcher>emptyList(), null),
                RouteAction.forCluster("cluster-foo", Collections.<HashPolicy>emptyList(), null),
                ImmutableMap.<String, FilterConfig>of()));
  }

  @Test
  public void parseRoute_withUnsupportedActionTypes() {
    StructOrError<Route> res;
    io.envoyproxy.envoy.config.route.v3.Route redirectRoute =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-blade")
            .setMatch(io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPath(""))
            .setRedirect(RedirectAction.getDefaultInstance())
            .build();
    res = ClientXdsClient.parseRoute(redirectRoute, false);
    assertThat(res.getStruct()).isNull();
    assertThat(res.getErrorDetail()).isEqualTo("Unsupported action type: redirect");

    io.envoyproxy.envoy.config.route.v3.Route directResponseRoute =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-blade")
            .setMatch(io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPath(""))
            .setDirectResponse(DirectResponseAction.getDefaultInstance())
            .build();
    res = ClientXdsClient.parseRoute(directResponseRoute, false);
    assertThat(res.getStruct()).isNull();
    assertThat(res.getErrorDetail()).isEqualTo("Unsupported action type: direct_response");

    io.envoyproxy.envoy.config.route.v3.Route filterRoute =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-blade")
            .setMatch(io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPath(""))
            .setFilterAction(FilterAction.getDefaultInstance())
            .build();
    res = ClientXdsClient.parseRoute(filterRoute, false);
    assertThat(res.getStruct()).isNull();
    assertThat(res.getErrorDetail()).isEqualTo("Unsupported action type: filter_action");
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
                            .getDefaultInstance()))  // query parameter not supported
            .setRoute(
                io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
                    .setCluster("cluster-foo"))
            .build();
    assertThat(ClientXdsClient.parseRoute(proto, false)).isNull();
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
                    .setClusterHeader("cluster header"))  // cluster_header action not supported
            .build();
    assertThat(ClientXdsClient.parseRoute(proto, false)).isNull();
  }

  @Test
  public void parseRouteMatch_withHeaderMatcher() {
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
  public void parseRouteMatch_withRuntimeFractionMatcher() {
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
  public void parsePathMatcher_withFullPath() {
    io.envoyproxy.envoy.config.route.v3.RouteMatch proto =
        io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
            .setPath("/service/method")
            .build();
    StructOrError<PathMatcher> struct = ClientXdsClient.parsePathMatcher(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct()).isEqualTo(
        PathMatcher.fromPath("/service/method", false));
  }

  @Test
  public void parsePathMatcher_withPrefix() {
    io.envoyproxy.envoy.config.route.v3.RouteMatch proto =
        io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPrefix("/").build();
    StructOrError<PathMatcher> struct = ClientXdsClient.parsePathMatcher(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct()).isEqualTo(
        PathMatcher.fromPrefix("/", false));
  }

  @Test
  public void parsePathMatcher_withSafeRegEx() {
    io.envoyproxy.envoy.config.route.v3.RouteMatch proto =
        io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
            .setSafeRegex(RegexMatcher.newBuilder().setRegex("."))
            .build();
    StructOrError<PathMatcher> struct = ClientXdsClient.parsePathMatcher(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct()).isEqualTo(PathMatcher.fromRegEx(Pattern.compile(".")));
  }

  @Test
  public void parseHeaderMatcher_withExactMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName(":method")
            .setExactMatch("PUT")
            .build();
    StructOrError<HeaderMatcher> struct1 = ClientXdsClient.parseHeaderMatcher(proto);
    assertThat(struct1.getErrorDetail()).isNull();
    assertThat(struct1.getStruct()).isEqualTo(
        HeaderMatcher.forExactValue(":method", "PUT", false));
  }

  @Test
  public void parseHeaderMatcher_withSafeRegExMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName(":method")
            .setSafeRegexMatch(RegexMatcher.newBuilder().setRegex("P*"))
            .build();
    StructOrError<HeaderMatcher> struct3 = ClientXdsClient.parseHeaderMatcher(proto);
    assertThat(struct3.getErrorDetail()).isNull();
    assertThat(struct3.getStruct()).isEqualTo(
        HeaderMatcher.forSafeRegEx(":method", Pattern.compile("P*"), false));
  }

  @Test
  public void parseHeaderMatcher_withRangeMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName("timeout")
            .setRangeMatch(Int64Range.newBuilder().setStart(10L).setEnd(20L))
            .build();
    StructOrError<HeaderMatcher> struct4 = ClientXdsClient.parseHeaderMatcher(proto);
    assertThat(struct4.getErrorDetail()).isNull();
    assertThat(struct4.getStruct()).isEqualTo(
        HeaderMatcher.forRange("timeout", HeaderMatcher.Range.create(10L, 20L), false));
  }

  @Test
  public void parseHeaderMatcher_withPresentMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName("user-agent")
            .setPresentMatch(true)
            .build();
    StructOrError<HeaderMatcher> struct5 = ClientXdsClient.parseHeaderMatcher(proto);
    assertThat(struct5.getErrorDetail()).isNull();
    assertThat(struct5.getStruct()).isEqualTo(
        HeaderMatcher.forPresent("user-agent", true, false));
  }

  @Test
  public void parseHeaderMatcher_withPrefixMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName("authority")
            .setPrefixMatch("service-foo")
            .build();
    StructOrError<HeaderMatcher> struct6 = ClientXdsClient.parseHeaderMatcher(proto);
    assertThat(struct6.getErrorDetail()).isNull();
    assertThat(struct6.getStruct()).isEqualTo(
        HeaderMatcher.forPrefix("authority", "service-foo", false));
  }

  @Test
  public void parseHeaderMatcher_withSuffixMatch() {
    io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto =
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher.newBuilder()
            .setName("authority")
            .setSuffixMatch("googleapis.com")
            .build();
    StructOrError<HeaderMatcher> struct7 = ClientXdsClient.parseHeaderMatcher(proto);
    assertThat(struct7.getErrorDetail()).isNull();
    assertThat(struct7.getStruct()).isEqualTo(
        HeaderMatcher.forSuffix("authority", "googleapis.com", false));
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
    StructOrError<RouteAction> struct = ClientXdsClient.parseRouteAction(proto, false);
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
    StructOrError<RouteAction> struct = ClientXdsClient.parseRouteAction(proto, false);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct().cluster()).isNull();
    assertThat(struct.getStruct().weightedClusters()).containsExactly(
        ClusterWeight.create("cluster-foo", 30, ImmutableMap.<String, FilterConfig>of()),
        ClusterWeight.create("cluster-bar", 70, ImmutableMap.<String, FilterConfig>of()));
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
    StructOrError<RouteAction> struct = ClientXdsClient.parseRouteAction(proto, false);
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
    StructOrError<RouteAction> struct = ClientXdsClient.parseRouteAction(proto, false);
    assertThat(struct.getStruct().timeoutNano()).isEqualTo(TimeUnit.SECONDS.toNanos(5L));
  }

  @Test
  public void parseRouteAction_withTimeoutUnset() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .build();
    StructOrError<RouteAction> struct = ClientXdsClient.parseRouteAction(proto, false);
    assertThat(struct.getStruct().timeoutNano()).isNull();
  }

  @Test
  public void parseRouteAction_withHashPolicies() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .addHashPolicy(
                io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.newBuilder()
                    .setHeader(
                        Header.newBuilder()
                            .setHeaderName("user-agent")
                            .setRegexRewrite(
                                RegexMatchAndSubstitute.newBuilder()
                                    .setPattern(
                                        RegexMatcher.newBuilder()
                                            .setGoogleRe2(GoogleRE2.getDefaultInstance())
                                            .setRegex("grpc.*"))
                                    .setSubstitution("gRPC"))))
            .addHashPolicy(
                io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.newBuilder()
                    .setConnectionProperties(ConnectionProperties.newBuilder().setSourceIp(true))
                    .setTerminal(true))  // unsupported
            .addHashPolicy(
                io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.newBuilder()
                    .setFilterState(
                        FilterState.newBuilder()
                            .setKey(ClientXdsClient.HASH_POLICY_FILTER_STATE_KEY)))
            .addHashPolicy(
                io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.newBuilder()
                    .setQueryParameter(
                        QueryParameter.newBuilder().setName("param"))) // unsupported
            .build();
    StructOrError<RouteAction> struct = ClientXdsClient.parseRouteAction(proto, false);
    List<HashPolicy> policies = struct.getStruct().hashPolicies();
    assertThat(policies).hasSize(2);
    assertThat(policies.get(0).type()).isEqualTo(HashPolicy.Type.HEADER);
    assertThat(policies.get(0).headerName()).isEqualTo("user-agent");
    assertThat(policies.get(0).isTerminal()).isFalse();
    assertThat(policies.get(0).regEx().pattern()).isEqualTo("grpc.*");
    assertThat(policies.get(0).regExSubstitution()).isEqualTo("gRPC");

    assertThat(policies.get(1).type()).isEqualTo(HashPolicy.Type.CHANNEL_ID);
    assertThat(policies.get(1).isTerminal()).isFalse();
  }

  @Test
  public void parseClusterWeight() {
    io.envoyproxy.envoy.config.route.v3.WeightedCluster.ClusterWeight proto =
        io.envoyproxy.envoy.config.route.v3.WeightedCluster.ClusterWeight.newBuilder()
            .setName("cluster-foo")
            .setWeight(UInt32Value.newBuilder().setValue(30))
            .build();
    ClusterWeight clusterWeight = ClientXdsClient.parseClusterWeight(proto, false).getStruct();
    assertThat(clusterWeight.name()).isEqualTo("cluster-foo");
    assertThat(clusterWeight.weight()).isEqualTo(30);
  }

  @Test
  public void parseFaultAbort_withHttpStatus() {
    io.envoyproxy.envoy.extensions.filters.http.fault.v3.FaultAbort proto =
        io.envoyproxy.envoy.extensions.filters.http.fault.v3.FaultAbort.newBuilder()
            .setPercentage(FractionalPercent.newBuilder()
               .setNumerator(100).setDenominator(DenominatorType.TEN_THOUSAND))
            .setHttpStatus(400).build();
    FaultAbort res = FaultFilter.parseFaultAbort(proto).config;
    assertThat(res.percent().numerator()).isEqualTo(100);
    assertThat(res.percent().denominatorType())
        .isEqualTo(FaultConfig.FractionalPercent.DenominatorType.TEN_THOUSAND);
    assertThat(res.status().getCode()).isEqualTo(Code.INTERNAL);
  }

  @Test
  public void parseFaultAbort_withGrpcStatus() {
    io.envoyproxy.envoy.extensions.filters.http.fault.v3.FaultAbort proto =
        io.envoyproxy.envoy.extensions.filters.http.fault.v3.FaultAbort.newBuilder()
            .setPercentage(FractionalPercent.newBuilder()
                .setNumerator(600).setDenominator(DenominatorType.MILLION))
            .setGrpcStatus(Code.DEADLINE_EXCEEDED.value()).build();
    FaultAbort faultAbort = FaultFilter.parseFaultAbort(proto).config;
    assertThat(faultAbort.percent().numerator()).isEqualTo(600);
    assertThat(faultAbort.percent().denominatorType())
        .isEqualTo(FaultConfig.FractionalPercent.DenominatorType.MILLION);
    assertThat(faultAbort.status().getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);
  }

  @Test
  public void parseLocalityLbEndpoints_withHealthyEndpoints() {
    io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints proto =
        io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints.newBuilder()
            .setLocality(Locality.newBuilder()
                .setRegion("region-foo").setZone("zone-foo").setSubZone("subZone-foo"))
            .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(100))  // locality weight
            .setPriority(1)
            .addLbEndpoints(io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint.newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                    .setAddress(Address.newBuilder()
                        .setSocketAddress(
                            SocketAddress.newBuilder()
                                .setAddress("172.14.14.5").setPortValue(8888))))
                .setHealthStatus(io.envoyproxy.envoy.config.core.v3.HealthStatus.HEALTHY)
                .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(20)))  // endpoint weight
            .build();
    StructOrError<LocalityLbEndpoints> struct = ClientXdsClient.parseLocalityLbEndpoints(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct()).isEqualTo(
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create("172.14.14.5", 8888, 20, true)), 100, 1));
  }

  @Test
  public void parseLocalityLbEndpoints_treatUnknownHealthAsHealthy() {
    io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints proto =
        io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints.newBuilder()
            .setLocality(Locality.newBuilder()
                .setRegion("region-foo").setZone("zone-foo").setSubZone("subZone-foo"))
            .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(100))  // locality weight
            .setPriority(1)
            .addLbEndpoints(io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint.newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                    .setAddress(Address.newBuilder()
                        .setSocketAddress(
                            SocketAddress.newBuilder()
                                .setAddress("172.14.14.5").setPortValue(8888))))
                .setHealthStatus(io.envoyproxy.envoy.config.core.v3.HealthStatus.UNKNOWN)
                .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(20)))  // endpoint weight
            .build();
    StructOrError<LocalityLbEndpoints> struct = ClientXdsClient.parseLocalityLbEndpoints(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct()).isEqualTo(
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create("172.14.14.5", 8888, 20, true)), 100, 1));
  }

  @Test
  public void parseLocalityLbEndpoints_withUnHealthyEndpoints() {
    io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints proto =
        io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints.newBuilder()
            .setLocality(Locality.newBuilder()
                .setRegion("region-foo").setZone("zone-foo").setSubZone("subZone-foo"))
            .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(100))  // locality weight
            .setPriority(1)
            .addLbEndpoints(io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint.newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                    .setAddress(Address.newBuilder()
                        .setSocketAddress(
                            SocketAddress.newBuilder()
                                .setAddress("172.14.14.5").setPortValue(8888))))
                .setHealthStatus(io.envoyproxy.envoy.config.core.v3.HealthStatus.UNHEALTHY)
                .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(20)))  // endpoint weight
            .build();
    StructOrError<LocalityLbEndpoints> struct = ClientXdsClient.parseLocalityLbEndpoints(proto);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct()).isEqualTo(
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create("172.14.14.5", 8888, 20, false)), 100, 1));
  }

  @Test
  public void parseLocalityLbEndpoints_ignorZeroWeightLocality() {
    io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints proto =
        io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints.newBuilder()
            .setLocality(Locality.newBuilder()
                .setRegion("region-foo").setZone("zone-foo").setSubZone("subZone-foo"))
            .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(0))  // locality weight
            .setPriority(1)
            .addLbEndpoints(io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint.newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                    .setAddress(Address.newBuilder()
                        .setSocketAddress(
                            SocketAddress.newBuilder()
                                .setAddress("172.14.14.5").setPortValue(8888))))
                .setHealthStatus(io.envoyproxy.envoy.config.core.v3.HealthStatus.UNKNOWN)
                .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(20)))  // endpoint weight
            .build();
    assertThat(ClientXdsClient.parseLocalityLbEndpoints(proto)).isNull();
  }

  @Test
  public void parseLocalityLbEndpoints_invalidPriority() {
    io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints proto =
        io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints.newBuilder()
            .setLocality(Locality.newBuilder()
                .setRegion("region-foo").setZone("zone-foo").setSubZone("subZone-foo"))
            .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(100))  // locality weight
            .setPriority(-1)
            .addLbEndpoints(io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint.newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                    .setAddress(Address.newBuilder()
                        .setSocketAddress(
                            SocketAddress.newBuilder()
                                .setAddress("172.14.14.5").setPortValue(8888))))
                .setHealthStatus(io.envoyproxy.envoy.config.core.v3.HealthStatus.UNKNOWN)
                .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(20)))  // endpoint weight
            .build();
    StructOrError<LocalityLbEndpoints> struct = ClientXdsClient.parseLocalityLbEndpoints(proto);
    assertThat(struct.getErrorDetail()).isEqualTo("negative priority");
  }

  @Test
  public void parseHttpFilter_unsupportedButOptional() {
    HttpFilter httpFilter = HttpFilter.newBuilder()
        .setIsOptional(true)
        .setTypedConfig(Any.pack(StringValue.of("unsupported")))
        .build();
    assertThat(ClientXdsClient.parseHttpFilter(httpFilter)).isNull();
  }

  @Test
  public void parseHttpFilter_unsupportedAndRequired() {
    HttpFilter httpFilter = HttpFilter.newBuilder()
        .setIsOptional(false)
        .setName("unsupported.filter")
        .setTypedConfig(Any.pack(StringValue.of("string value")))
        .build();
    assertThat(ClientXdsClient.parseHttpFilter(httpFilter).getErrorDetail()).isEqualTo(
        "HttpFilter [unsupported.filter] is not optional and has an unsupported config type: "
            + "type.googleapis.com/google.protobuf.StringValue");
  }

  @Test
  public void parseOverrideFilterConfigs_unsupportedButOptional() {
    HTTPFault httpFault = HTTPFault.newBuilder()
        .setDelay(FaultDelay.newBuilder().setFixedDelay(Durations.fromNanos(3000)))
        .build();
    Map<String, Any> configOverrides = ImmutableMap.of(
        "envoy.fault",
        Any.pack(httpFault),
        "unsupported.filter",
        Any.pack(io.envoyproxy.envoy.config.route.v3.FilterConfig.newBuilder()
            .setIsOptional(true).setConfig(Any.pack(StringValue.of("string value")))
            .build()));
    Map<String, FilterConfig> parsedConfigs =
        ClientXdsClient.parseOverrideFilterConfigs(configOverrides).getStruct();
    assertThat(parsedConfigs).hasSize(1);
    assertThat(parsedConfigs).containsKey("envoy.fault");
  }

  @Test
  public void parseOverrideFilterConfigs_unsupportedAndRequired() {
    HTTPFault httpFault = HTTPFault.newBuilder()
        .setDelay(FaultDelay.newBuilder().setFixedDelay(Durations.fromNanos(3000)))
        .build();
    Map<String, Any> configOverrides = ImmutableMap.of(
        "envoy.fault",
        Any.pack(httpFault),
        "unsupported.filter",
        Any.pack(io.envoyproxy.envoy.config.route.v3.FilterConfig.newBuilder()
            .setIsOptional(false).setConfig(Any.pack(StringValue.of("string value")))
            .build()));
    assertThat(ClientXdsClient.parseOverrideFilterConfigs(configOverrides).getErrorDetail())
        .isEqualTo(
            "HttpFilter [unsupported.filter] is not optional and has an unsupported config type: "
                + "type.googleapis.com/google.protobuf.StringValue");

    configOverrides = ImmutableMap.of(
        "envoy.fault",
        Any.pack(httpFault),
        "unsupported.filter",
        Any.pack(StringValue.of("string value")));
    assertThat(ClientXdsClient.parseOverrideFilterConfigs(configOverrides).getErrorDetail())
        .isEqualTo(
            "HttpFilter [unsupported.filter] is not optional and has an unsupported config type: "
                + "type.googleapis.com/google.protobuf.StringValue");
  }

  @Test
  public void parseServerSideListener_invalidTrafficDirection() {
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.OUTBOUND)
            .build();
    StructOrError<io.grpc.xds.EnvoyServerProtoData.Listener> struct =
        ClientXdsClient.parseServerSideListener(listener);
    assertThat(struct.getErrorDetail()).isEqualTo("Listener listener1 is not INBOUND");
  }

  @Test
  public void parseServerSideListener_listenerFiltersPresent() {
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addListenerFilters(ListenerFilter.newBuilder().build())
            .build();
    StructOrError<io.grpc.xds.EnvoyServerProtoData.Listener> struct =
        ClientXdsClient.parseServerSideListener(listener);
    assertThat(struct.getErrorDetail())
        .isEqualTo("Listener listener1 cannot have listener_filters");
  }

  @Test
  public void parseServerSideListener_useOriginalDst() {
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .setUseOriginalDst(BoolValue.of(true))
            .build();
    StructOrError<io.grpc.xds.EnvoyServerProtoData.Listener> struct =
        ClientXdsClient.parseServerSideListener(listener);
    assertThat(struct.getErrorDetail())
        .isEqualTo("Listener listener1 cannot have use_original_dst set to true");
  }

  @Test
  public void parseServerSideListener_noHcm() {
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addFilterChains(FilterChain.newBuilder().build())
            .build();
    StructOrError<io.grpc.xds.EnvoyServerProtoData.Listener> struct =
        ClientXdsClient.parseServerSideListener(listener);
    assertThat(struct.getErrorDetail())
        .isEqualTo("filerChain  has to have envoy.http_connection_manager");
  }

  @Test
  public void parseServerSideListener_duplicateFilterName() {
    FilterChain filterChain =
        buildFilterChain(
            Filter.newBuilder()
                .setName("envoy.http_connection_manager")
                .setTypedConfig(Any.pack(HttpConnectionManager.getDefaultInstance()))
                .build(),
            Filter.newBuilder()
                .setName("envoy.http_connection_manager")
                .setTypedConfig(Any.pack(HttpConnectionManager.getDefaultInstance()))
                .build());
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addFilterChains(filterChain)
            .build();
    StructOrError<io.grpc.xds.EnvoyServerProtoData.Listener> struct =
        ClientXdsClient.parseServerSideListener(listener);
    assertThat(struct.getErrorDetail())
        .isEqualTo("filerChain  has non-unique filter name:envoy.http_connection_manager");
  }

  @Test
  public void parseServerSideListener_nonHcmFilter() {
    FilterChain filterChain =
        buildFilterChain(
            Filter.newBuilder()
                .setName("xyz")
                .setTypedConfig(Any.pack(HttpConnectionManager.getDefaultInstance()))
                .build());
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addFilterChains(filterChain)
            .build();
    StructOrError<io.grpc.xds.EnvoyServerProtoData.Listener> struct =
        ClientXdsClient.parseServerSideListener(listener);
    assertThat(struct.getErrorDetail()).isEqualTo("filter xyz not supported.");
  }

  @Test
  public void parseServerSideListener_configDiscoveryFilter() {
    Filter filter =
        Filter.newBuilder()
            .setName("envoy.http_connection_manager")
            .setConfigDiscovery(ExtensionConfigSource.newBuilder().build())
            .build();
    FilterChain filterChain = buildFilterChain(filter);
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addFilterChains(filterChain)
            .build();
    StructOrError<io.grpc.xds.EnvoyServerProtoData.Listener> struct =
        ClientXdsClient.parseServerSideListener(listener);
    assertThat(struct.getErrorDetail())
        .isEqualTo("filter envoy.http_connection_manager with config_discovery not supported");
  }

  @Test
  public void parseServerSideListener_expectTypedConfigFilter() {
    Filter filter = Filter.newBuilder().setName("envoy.http_connection_manager").build();
    FilterChain filterChain = buildFilterChain(filter);
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addFilterChains(filterChain)
            .build();
    StructOrError<io.grpc.xds.EnvoyServerProtoData.Listener> struct =
        ClientXdsClient.parseServerSideListener(listener);
    assertThat(struct.getErrorDetail())
        .isEqualTo("filter envoy.http_connection_manager expected to have typed_config");
  }

  @Test
  public void parseServerSideListener_wrongTypeUrl() {
    Filter filter =
        Filter.newBuilder()
            .setName("envoy.http_connection_manager")
            .setTypedConfig(Any.newBuilder().setTypeUrl("badTypeUrl"))
            .build();
    FilterChain filterChain = buildFilterChain(filter);
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addFilterChains(filterChain)
            .build();
    StructOrError<io.grpc.xds.EnvoyServerProtoData.Listener> struct =
        ClientXdsClient.parseServerSideListener(listener);
    assertThat(struct.getErrorDetail())
        .isEqualTo(
            "filter envoy.http_connection_manager with unsupported typed_config type:badTypeUrl");
  }

  @Test
  public void parseServerSideListener_duplicateHttpFilter() {
    Filter filter =
        buildHttpConnectionManager(
            "envoy.http_connection_manager",
            HttpFilter.newBuilder().setName("hf").setIsOptional(true).build(),
            HttpFilter.newBuilder().setName("hf").setIsOptional(true).build());
    FilterChain filterChain = buildFilterChain(filter);
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addFilterChains(filterChain)
            .build();
    StructOrError<io.grpc.xds.EnvoyServerProtoData.Listener> struct =
        ClientXdsClient.parseServerSideListener(listener);
    assertThat(struct.getErrorDetail())
        .isEqualTo("http-connection-manager has non-unique http-filter name:hf");
  }

  @Test
  public void parseServerSideListener_unsupportedHttpFilter() {
    Filter filter =
        buildHttpConnectionManager(
            "envoy.http_connection_manager", HttpFilter.newBuilder().setName("hf").build());
    FilterChain filterChain = buildFilterChain(filter);
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addFilterChains(filterChain)
            .build();
    StructOrError<io.grpc.xds.EnvoyServerProtoData.Listener> struct =
        ClientXdsClient.parseServerSideListener(listener);
    assertThat(struct.getErrorDetail())
        .isEqualTo("http-connection-manager has unsupported http-filter:hf");
  }

  @Test
  public void parseServerSideListener_configDiscoveryHttpFilter() {
    Filter filter =
        buildHttpConnectionManager(
            "envoy.http_connection_manager",
            HttpFilter.newBuilder()
                .setName("envoy.router")
                .setConfigDiscovery(ExtensionConfigSource.newBuilder().build())
                .build());
    FilterChain filterChain = buildFilterChain(filter);
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addFilterChains(filterChain)
            .build();
    StructOrError<io.grpc.xds.EnvoyServerProtoData.Listener> struct =
        ClientXdsClient.parseServerSideListener(listener);
    assertThat(struct.getErrorDetail())
        .isEqualTo(
            "http-connection-manager http-filter envoy.router uses "
                + "config-discovery which is unsupported");
  }

  @Test
  public void parseServerSideListener_badTypeUrlHttpFilter() {
    HTTPFault fault = HTTPFault.newBuilder().build();
    Filter filter =
        buildHttpConnectionManager(
            "envoy.http_connection_manager",
            HttpFilter.newBuilder()
                .setName("envoy.router")
                .setTypedConfig(Any.pack(fault, "type.googleapis.com"))
                .build());
    FilterChain filterChain = buildFilterChain(filter);
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addFilterChains(filterChain)
            .build();
    StructOrError<io.grpc.xds.EnvoyServerProtoData.Listener> struct =
        ClientXdsClient.parseServerSideListener(listener);
    assertThat(struct.getErrorDetail())
        .isEqualTo(
            "http-connection-manager http-filter envoy.router has unsupported typed-config type:"
                + "type.googleapis.com/envoy.extensions.filters.http.fault.v3.HTTPFault");
  }

  static Filter buildHttpConnectionManager(String name, HttpFilter... httpFilters) {
    return Filter.newBuilder()
        .setName(name)
        .setTypedConfig(
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .addAllHttpFilters(Arrays.asList(httpFilters))
                    .build(),
                "type.googleapis.com"))
        .build();
  }

  static FilterChain buildFilterChain(Filter... filters) {
    return FilterChain.newBuilder()
        .setFilterChainMatch(
            FilterChainMatch.newBuilder()
                .addAllApplicationProtocols(Arrays.asList("managed-mtls"))
                .build())
        .setTransportSocket(TransportSocket.getDefaultInstance())
        .addAllFilters(Arrays.asList(filters))
        .build();
  }
}
