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
import com.google.protobuf.UInt64Value;
import com.google.protobuf.util.Durations;
import com.google.re2j.Pattern;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbPolicy;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.RingHashLbConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.RingHashLbConfig.HashFunction;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.HttpProtocolOptions;
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
import io.envoyproxy.envoy.config.rbac.v3.Permission;
import io.envoyproxy.envoy.config.rbac.v3.Policy;
import io.envoyproxy.envoy.config.rbac.v3.Principal;
import io.envoyproxy.envoy.config.rbac.v3.RBAC;
import io.envoyproxy.envoy.config.rbac.v3.RBAC.Action;
import io.envoyproxy.envoy.config.route.v3.DirectResponseAction;
import io.envoyproxy.envoy.config.route.v3.FilterAction;
import io.envoyproxy.envoy.config.route.v3.NonForwardingAction;
import io.envoyproxy.envoy.config.route.v3.RedirectAction;
import io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.ConnectionProperties;
import io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.FilterState;
import io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.Header;
import io.envoyproxy.envoy.config.route.v3.RouteAction.HashPolicy.QueryParameter;
import io.envoyproxy.envoy.config.route.v3.RouteAction.MaxStreamDuration;
import io.envoyproxy.envoy.config.route.v3.WeightedCluster;
import io.envoyproxy.envoy.extensions.filters.common.fault.v3.FaultDelay;
import io.envoyproxy.envoy.extensions.filters.http.fault.v3.FaultAbort;
import io.envoyproxy.envoy.extensions.filters.http.fault.v3.HTTPFault;
import io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBACPerRoute;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatchAndSubstitute;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher.GoogleRE2;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.envoyproxy.envoy.type.v3.FractionalPercent.DenominatorType;
import io.envoyproxy.envoy.type.v3.Int64Range;
import io.grpc.xds.ClientXdsClient.ResourceInvalidException;
import io.grpc.xds.ClientXdsClient.StructOrError;
import io.grpc.xds.Endpoints.LbEndpoint;
import io.grpc.xds.Endpoints.LocalityLbEndpoints;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.VirtualHost.Route.RouteAction;
import io.grpc.xds.VirtualHost.Route.RouteAction.ClusterWeight;
import io.grpc.xds.VirtualHost.Route.RouteAction.HashPolicy;
import io.grpc.xds.VirtualHost.Route.RouteMatch;
import io.grpc.xds.VirtualHost.Route.RouteMatch.PathMatcher;
import io.grpc.xds.XdsClient.CdsUpdate;
import io.grpc.xds.internal.Matchers.FractionMatcher;
import io.grpc.xds.internal.Matchers.HeaderMatcher;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ClientXdsClientDataTest {

  @SuppressWarnings("deprecation") // https://github.com/grpc/grpc-java/issues/7467
  @Rule
  public final ExpectedException thrown = ExpectedException.none();
  private final FilterRegistry filterRegistry = FilterRegistry.newRegistry();

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
    StructOrError<Route> struct = ClientXdsClient.parseRoute(proto, filterRegistry, false);
    assertThat(struct.getErrorDetail()).isNull();
    assertThat(struct.getStruct())
        .isEqualTo(
            Route.forAction(
                RouteMatch.create(PathMatcher.fromPath("/service/method", false),
                    Collections.<HeaderMatcher>emptyList(), null),
                RouteAction.forCluster("cluster-foo", Collections.<HashPolicy>emptyList(), null),
                ImmutableMap.<String, FilterConfig>of()));
  }

  @Test
  public void parseRoute_withNonForwardingAction() {
    io.envoyproxy.envoy.config.route.v3.Route proto =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-blade")
            .setMatch(
                io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder()
                    .setPath("/service/method"))
            .setNonForwardingAction(NonForwardingAction.getDefaultInstance())
            .build();
    StructOrError<Route> struct = ClientXdsClient.parseRoute(proto, filterRegistry, false);
    assertThat(struct.getStruct())
        .isEqualTo(
            Route.forNonForwardingAction(
                RouteMatch.create(PathMatcher.fromPath("/service/method", false),
                    Collections.<HeaderMatcher>emptyList(), null),
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
    res = ClientXdsClient.parseRoute(redirectRoute, filterRegistry, false);
    assertThat(res.getStruct()).isNull();
    assertThat(res.getErrorDetail())
        .isEqualTo("Route [route-blade] with unknown action type: REDIRECT");

    io.envoyproxy.envoy.config.route.v3.Route directResponseRoute =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-blade")
            .setMatch(io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPath(""))
            .setDirectResponse(DirectResponseAction.getDefaultInstance())
            .build();
    res = ClientXdsClient.parseRoute(directResponseRoute, filterRegistry, false);
    assertThat(res.getStruct()).isNull();
    assertThat(res.getErrorDetail())
        .isEqualTo("Route [route-blade] with unknown action type: DIRECT_RESPONSE");

    io.envoyproxy.envoy.config.route.v3.Route filterRoute =
        io.envoyproxy.envoy.config.route.v3.Route.newBuilder()
            .setName("route-blade")
            .setMatch(io.envoyproxy.envoy.config.route.v3.RouteMatch.newBuilder().setPath(""))
            .setFilterAction(FilterAction.getDefaultInstance())
            .build();
    res = ClientXdsClient.parseRoute(filterRoute, filterRegistry, false);
    assertThat(res.getStruct()).isNull();
    assertThat(res.getErrorDetail())
        .isEqualTo("Route [route-blade] with unknown action type: FILTER_ACTION");
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
    assertThat(ClientXdsClient.parseRoute(proto, filterRegistry, false)).isNull();
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
    assertThat(ClientXdsClient.parseRoute(proto, filterRegistry, false)).isNull();
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
    StructOrError<RouteAction> struct =
        ClientXdsClient.parseRouteAction(proto, filterRegistry, false);
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
    StructOrError<RouteAction> struct =
        ClientXdsClient.parseRouteAction(proto, filterRegistry, false);
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
    StructOrError<RouteAction> struct =
        ClientXdsClient.parseRouteAction(proto, filterRegistry, false);
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
    StructOrError<RouteAction> struct =
        ClientXdsClient.parseRouteAction(proto, filterRegistry, false);
    assertThat(struct.getStruct().timeoutNano()).isEqualTo(TimeUnit.SECONDS.toNanos(5L));
  }

  @Test
  public void parseRouteAction_withTimeoutUnset() {
    io.envoyproxy.envoy.config.route.v3.RouteAction proto =
        io.envoyproxy.envoy.config.route.v3.RouteAction.newBuilder()
            .setCluster("cluster-foo")
            .build();
    StructOrError<RouteAction> struct =
        ClientXdsClient.parseRouteAction(proto, filterRegistry, false);
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
    StructOrError<RouteAction> struct =
        ClientXdsClient.parseRouteAction(proto, filterRegistry, false);
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
    ClusterWeight clusterWeight =
        ClientXdsClient.parseClusterWeight(proto, filterRegistry, false).getStruct();
    assertThat(clusterWeight.name()).isEqualTo("cluster-foo");
    assertThat(clusterWeight.weight()).isEqualTo(30);
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
    assertThat(ClientXdsClient.parseHttpFilter(httpFilter, filterRegistry, true)).isNull();
  }

  @Test
  public void parseHttpFilter_unsupportedAndRequired() {
    HttpFilter httpFilter = HttpFilter.newBuilder()
        .setIsOptional(false)
        .setName("unsupported.filter")
        .setTypedConfig(Any.pack(StringValue.of("string value")))
        .build();
    assertThat(ClientXdsClient.parseHttpFilter(httpFilter, filterRegistry, true)
        .getErrorDetail()).isEqualTo(
            "HttpFilter [unsupported.filter]"
                + "(type.googleapis.com/google.protobuf.StringValue) is required but unsupported "
                + "for client");
  }

  @Test
  public void parseHttpFilter_routerFilterForClient() {
    filterRegistry.register(RouterFilter.INSTANCE);
    HttpFilter httpFilter =
        HttpFilter.newBuilder()
            .setIsOptional(false)
            .setName("envoy.router")
            .setTypedConfig(Any.pack(Router.getDefaultInstance()))
            .build();
    FilterConfig config = ClientXdsClient.parseHttpFilter(
        httpFilter, filterRegistry, true /* isForClient */).getStruct();
    assertThat(config.typeUrl()).isEqualTo(RouterFilter.TYPE_URL);
  }

  @Test
  public void parseHttpFilter_routerFilterForServer() {
    filterRegistry.register(RouterFilter.INSTANCE);
    HttpFilter httpFilter =
        HttpFilter.newBuilder()
            .setIsOptional(false)
            .setName("envoy.router")
            .setTypedConfig(Any.pack(Router.getDefaultInstance()))
            .build();
    FilterConfig config = ClientXdsClient.parseHttpFilter(
        httpFilter, filterRegistry, false /* isForClient */).getStruct();
    assertThat(config.typeUrl()).isEqualTo(RouterFilter.TYPE_URL);
  }

  @Test
  public void parseHttpFilter_faultConfigForClient() {
    filterRegistry.register(FaultFilter.INSTANCE);
    HttpFilter httpFilter =
        HttpFilter.newBuilder()
            .setIsOptional(false)
            .setName("envoy.fault")
            .setTypedConfig(
                Any.pack(
                    HTTPFault.newBuilder()
                        .setDelay(
                            FaultDelay.newBuilder()
                                .setFixedDelay(Durations.fromNanos(1234L)))
                        .setAbort(
                            FaultAbort.newBuilder()
                                .setHttpStatus(300)
                                .setPercentage(
                                    FractionalPercent.newBuilder()
                                        .setNumerator(10)
                                        .setDenominator(DenominatorType.HUNDRED)))
                        .build()))
            .build();
    FilterConfig config = ClientXdsClient.parseHttpFilter(
        httpFilter, filterRegistry, true /* isForClient */).getStruct();
    assertThat(config).isInstanceOf(FaultConfig.class);
  }

  @Test
  public void parseHttpFilter_faultConfigUnsupportedForServer() {
    filterRegistry.register(FaultFilter.INSTANCE);
    HttpFilter httpFilter =
        HttpFilter.newBuilder()
            .setIsOptional(false)
            .setName("envoy.fault")
            .setTypedConfig(
                Any.pack(
                    HTTPFault.newBuilder()
                        .setDelay(
                            FaultDelay.newBuilder()
                                .setFixedDelay(Durations.fromNanos(1234L)))
                        .setAbort(
                            FaultAbort.newBuilder()
                                .setHttpStatus(300)
                                .setPercentage(
                                    FractionalPercent.newBuilder()
                                        .setNumerator(10)
                                        .setDenominator(DenominatorType.HUNDRED)))
                        .build()))
            .build();
    StructOrError<FilterConfig> config =
        ClientXdsClient.parseHttpFilter(httpFilter, filterRegistry, false /* isForClient */);
    assertThat(config.getErrorDetail()).isEqualTo(
        "HttpFilter [envoy.fault](" + FaultFilter.TYPE_URL + ") is required but "
            + "unsupported for server");
  }

  @Test
  public void parseHttpFilter_rbacConfigForServer() {
    filterRegistry.register(RbacFilter.INSTANCE);
    HttpFilter httpFilter =
        HttpFilter.newBuilder()
            .setIsOptional(false)
            .setName("envoy.auth")
            .setTypedConfig(
                Any.pack(
                    io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC.newBuilder()
                        .setRules(
                            RBAC.newBuilder()
                                .setAction(Action.ALLOW)
                                .putPolicies(
                                    "allow-all",
                                    Policy.newBuilder()
                                        .addPrincipals(Principal.newBuilder().setAny(true))
                                        .addPermissions(Permission.newBuilder().setAny(true))
                                        .build())
                                .build())
                        .build()))
            .build();
    FilterConfig config = ClientXdsClient.parseHttpFilter(
        httpFilter, filterRegistry, false /* isForClient */).getStruct();
    assertThat(config).isInstanceOf(RbacConfig.class);
  }

  @Test
  public void parseHttpFilter_rbacConfigUnsupportedForClient() {
    filterRegistry.register(RbacFilter.INSTANCE);
    HttpFilter httpFilter =
        HttpFilter.newBuilder()
            .setIsOptional(false)
            .setName("envoy.auth")
            .setTypedConfig(
                Any.pack(
                    io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC.newBuilder()
                        .setRules(
                            RBAC.newBuilder()
                                .setAction(Action.ALLOW)
                                .putPolicies(
                                    "allow-all",
                                    Policy.newBuilder()
                                        .addPrincipals(Principal.newBuilder().setAny(true))
                                        .addPermissions(Permission.newBuilder().setAny(true))
                                        .build())
                                .build())
                        .build()))
            .build();
    StructOrError<FilterConfig> config =
        ClientXdsClient.parseHttpFilter(httpFilter, filterRegistry, true /* isForClient */);
    assertThat(config.getErrorDetail()).isEqualTo(
        "HttpFilter [envoy.auth](" + RbacFilter.TYPE_URL + ") is required but "
            + "unsupported for client");
  }

  @Test
  public void parseOverrideRbacFilterConfig() {
    filterRegistry.register(RbacFilter.INSTANCE);
    RBACPerRoute rbacPerRoute =
        RBACPerRoute.newBuilder()
            .setRbac(
                io.envoyproxy.envoy.extensions.filters.http.rbac.v3.RBAC.newBuilder()
                    .setRules(
                        RBAC.newBuilder()
                            .setAction(Action.ALLOW)
                            .putPolicies(
                                "allow-all",
                                Policy.newBuilder()
                                    .addPrincipals(Principal.newBuilder().setAny(true))
                                    .addPermissions(Permission.newBuilder().setAny(true))
                            .build())))
            .build();
    Map<String, Any> configOverrides = ImmutableMap.of("envoy.auth", Any.pack(rbacPerRoute));
    Map<String, FilterConfig> parsedConfigs =
        ClientXdsClient.parseOverrideFilterConfigs(configOverrides, filterRegistry).getStruct();
    assertThat(parsedConfigs).hasSize(1);
    assertThat(parsedConfigs).containsKey("envoy.auth");
    assertThat(parsedConfigs.get("envoy.auth")).isInstanceOf(RbacConfig.class);
  }

  @Test
  public void parseOverrideFilterConfigs_unsupportedButOptional() {
    filterRegistry.register(FaultFilter.INSTANCE);
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
        ClientXdsClient.parseOverrideFilterConfigs(configOverrides, filterRegistry).getStruct();
    assertThat(parsedConfigs).hasSize(1);
    assertThat(parsedConfigs).containsKey("envoy.fault");
  }

  @Test
  public void parseOverrideFilterConfigs_unsupportedAndRequired() {
    filterRegistry.register(FaultFilter.INSTANCE);
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
    assertThat(ClientXdsClient.parseOverrideFilterConfigs(configOverrides, filterRegistry)
        .getErrorDetail()).isEqualTo(
            "HttpFilter [unsupported.filter]"
                + "(type.googleapis.com/google.protobuf.StringValue) is required but unsupported");

    configOverrides = ImmutableMap.of(
        "envoy.fault",
        Any.pack(httpFault),
        "unsupported.filter",
        Any.pack(StringValue.of("string value")));
    assertThat(ClientXdsClient.parseOverrideFilterConfigs(configOverrides, filterRegistry)
        .getErrorDetail()).isEqualTo(
            "HttpFilter [unsupported.filter]"
                + "(type.googleapis.com/google.protobuf.StringValue) is required but unsupported");
  }

  @Test
  public void parseHttpConnectionManager_xffNumTrustedHopsUnsupported()
      throws ResourceInvalidException {
    HttpConnectionManager hcm =
        HttpConnectionManager.newBuilder()
            .setXffNumTrustedHops(2)
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("HttpConnectionManager with xff_num_trusted_hops unsupported");
    ClientXdsClient.parseHttpConnectionManager(
        hcm, new HashSet<String>(), filterRegistry, false /* does not matter */,
        true /* does not matter */);
  }
  
  @Test
  public void parseHttpConnectionManager_missingRdsAndInlinedRouteConfiguration()
      throws ResourceInvalidException {
    HttpConnectionManager hcm =
        HttpConnectionManager.newBuilder()
            .setCommonHttpProtocolOptions(
                HttpProtocolOptions.newBuilder()
                    .setMaxStreamDuration(Durations.fromNanos(1000L)))
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("HttpConnectionManager neither has inlined route_config nor RDS");
    ClientXdsClient.parseHttpConnectionManager(
        hcm, new HashSet<String>(), filterRegistry, false /* does not matter */,
        true /* does not matter */);
  }

  @Test
  public void parseHttpConnectionManager_duplicateHttpFilters() throws ResourceInvalidException {
    HttpConnectionManager hcm =
        HttpConnectionManager.newBuilder()
            .addHttpFilters(
                HttpFilter.newBuilder().setName("envoy.filter.foo").setIsOptional(true))
            .addHttpFilters(
                HttpFilter.newBuilder().setName("envoy.filter.foo").setIsOptional(true))
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("HttpConnectionManager contains duplicate HttpFilter: envoy.filter.foo");
    ClientXdsClient.parseHttpConnectionManager(
        hcm, new HashSet<String>(), filterRegistry, true /* parseHttpFilter */,
        true /* does not matter */);
  }

  @Test
  public void parseCluster_ringHashLbPolicy_defaultLbConfig() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.getDefaultInstance()))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.RING_HASH)
        .build();

    CdsUpdate update = ClientXdsClient.parseCluster(cluster, new HashSet<String>());
    assertThat(update.lbPolicy()).isEqualTo(CdsUpdate.LbPolicy.RING_HASH);
    assertThat(update.minRingSize())
        .isEqualTo(ClientXdsClient.DEFAULT_RING_HASH_LB_POLICY_MIN_RING_SIZE);
    assertThat(update.maxRingSize())
        .isEqualTo(ClientXdsClient.DEFAULT_RING_HASH_LB_POLICY_MAX_RING_SIZE);
  }

  @Test
  public void parseCluster_ringHashLbPolicy_invalidRingSizeConfig_minGreaterThanMax()
      throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.getDefaultInstance()))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.RING_HASH)
        .setRingHashLbConfig(
            RingHashLbConfig.newBuilder()
                .setHashFunction(HashFunction.XX_HASH)
                .setMinimumRingSize(UInt64Value.newBuilder().setValue(1000L))
                .setMaximumRingSize(UInt64Value.newBuilder().setValue(100L)))
        .build();

    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("Cluster cluster-foo.googleapis.com: invalid ring_hash_lb_config");
    ClientXdsClient.parseCluster(cluster, new HashSet<String>());
  }

  @Test
  public void parseCluster_ringHashLbPolicy_invalidRingSizeConfig_tooLargeRingSize()
      throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder()
        .setName("cluster-foo.googleapis.com")
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(
            EdsClusterConfig.newBuilder()
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.getDefaultInstance()))
                .setServiceName("service-foo.googleapis.com"))
        .setLbPolicy(LbPolicy.RING_HASH)
        .setRingHashLbConfig(
            RingHashLbConfig.newBuilder()
                .setHashFunction(HashFunction.XX_HASH)
                .setMinimumRingSize(UInt64Value.newBuilder().setValue(1000L))
                .setMaximumRingSize(
                    UInt64Value.newBuilder()
                        .setValue(ClientXdsClient.MAX_RING_HASH_LB_POLICY_RING_SIZE + 1)))
        .build();

    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("Cluster cluster-foo.googleapis.com: invalid ring_hash_lb_config");
    ClientXdsClient.parseCluster(cluster, new HashSet<String>());
  }

  @Test
  public void parseServerSideListener_invalidTrafficDirection() throws ResourceInvalidException {
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.OUTBOUND)
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("Listener listener1 with invalid traffic direction: OUTBOUND");
    ClientXdsClient.parseServerSideListener(
        listener, new HashSet<String>(), null, filterRegistry, true /* does not matter */);
  }

  @Test
  public void parseServerSideListener_listenerFiltersPresent() throws ResourceInvalidException {
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .addListenerFilters(ListenerFilter.newBuilder().build())
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("Listener listener1 cannot have listener_filters");
    ClientXdsClient.parseServerSideListener(
        listener, new HashSet<String>(), null, filterRegistry, true /* does not matter */);
  }

  @Test
  public void parseServerSideListener_useOriginalDst() throws ResourceInvalidException {
    Listener listener =
        Listener.newBuilder()
            .setName("listener1")
            .setTrafficDirection(TrafficDirection.INBOUND)
            .setUseOriginalDst(BoolValue.of(true))
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage("Listener listener1 cannot have use_original_dst set to true");
    ClientXdsClient.parseServerSideListener(
        listener, new HashSet<String>(), null, filterRegistry, true /* does not matter */);
  }

  @Test
  public void parseFilterChain_noHcm() throws ResourceInvalidException {
    FilterChain filterChain =
        FilterChain.newBuilder()
            .setName("filter-chain-foo")
            .setFilterChainMatch(FilterChainMatch.getDefaultInstance())
            .setTransportSocket(TransportSocket.getDefaultInstance())
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "FilterChain filter-chain-foo missing required HttpConnectionManager filter");
    ClientXdsClient.parseFilterChain(
        filterChain, new HashSet<String>(), null, filterRegistry, true /* does not matter */);
  }

  @Test
  public void parseFilterChain_duplicateFilter() throws ResourceInvalidException {
    Filter filter = buildHttpConnectionManagerFilter(
        HttpFilter.newBuilder().setName("http-filter-foo").setIsOptional(true).build());
    FilterChain filterChain =
        FilterChain.newBuilder()
            .setName("filter-chain-foo")
            .setFilterChainMatch(FilterChainMatch.getDefaultInstance())
            .setTransportSocket(TransportSocket.getDefaultInstance())
            .addAllFilters(Arrays.asList(filter, filter))
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "FilterChain filter-chain-foo with duplicated filter: envoy.http_connection_manager");
    ClientXdsClient.parseFilterChain(
        filterChain, new HashSet<String>(), null, filterRegistry, true /* does not matter */);
  }

  @Test
  public void parseFilterChain_filterMissingTypedConfig() throws ResourceInvalidException {
    Filter filter = Filter.newBuilder().setName("envoy.http_connection_manager").build();
    FilterChain filterChain =
        FilterChain.newBuilder()
            .setName("filter-chain-foo")
            .setFilterChainMatch(FilterChainMatch.getDefaultInstance())
            .setTransportSocket(TransportSocket.getDefaultInstance())
            .addFilters(filter)
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "FilterChain filter-chain-foo contains filter envoy.http_connection_manager "
            + "without typed_config");
    ClientXdsClient.parseFilterChain(
        filterChain, new HashSet<String>(), null, filterRegistry, true /* does not matter */);
  }

  @Test
  public void parseFilterChain_unsupportedFilter() throws ResourceInvalidException {
    Filter filter =
        Filter.newBuilder()
            .setName("unsupported")
            .setTypedConfig(Any.newBuilder().setTypeUrl("unsupported-type-url"))
            .build();
    FilterChain filterChain =
        FilterChain.newBuilder()
            .setName("filter-chain-foo")
            .setFilterChainMatch(FilterChainMatch.getDefaultInstance())
            .setTransportSocket(TransportSocket.getDefaultInstance())
            .addFilters(filter)
            .build();
    thrown.expect(ResourceInvalidException.class);
    thrown.expectMessage(
        "FilterChain filter-chain-foo contains filter unsupported with unsupported "
            + "typed_config type unsupported-type-url");
    ClientXdsClient.parseFilterChain(
        filterChain, new HashSet<String>(), null, filterRegistry, true /* does not matter */);
  }

  @Test
  public void parseFilterChain_noName_generatedUuid() throws ResourceInvalidException {
    FilterChain filterChain1 =
        FilterChain.newBuilder()
            .setFilterChainMatch(FilterChainMatch.getDefaultInstance())
            .addFilters(buildHttpConnectionManagerFilter(
                HttpFilter.newBuilder()
                    .setName("http-filter-foo")
                    .setIsOptional(true)
                    .build()))
            .build();
    FilterChain filterChain2 =
        FilterChain.newBuilder()
            .setFilterChainMatch(FilterChainMatch.getDefaultInstance())
            .addFilters(buildHttpConnectionManagerFilter(
                HttpFilter.newBuilder()
                    .setName("http-filter-bar")
                    .setIsOptional(true)
                    .build()))
            .build();

    EnvoyServerProtoData.FilterChain parsedFilterChain1 = ClientXdsClient.parseFilterChain(
        filterChain1, new HashSet<String>(), null, filterRegistry, true /* does not matter */);
    EnvoyServerProtoData.FilterChain parsedFilterChain2 = ClientXdsClient.parseFilterChain(
        filterChain2, new HashSet<String>(), null, filterRegistry, true /* does not matter */);
    assertThat(parsedFilterChain1.getName()).isNotEqualTo(parsedFilterChain2.getName());
  }

  private static Filter buildHttpConnectionManagerFilter(HttpFilter... httpFilters) {
    return Filter.newBuilder()
        .setName("envoy.http_connection_manager")
        .setTypedConfig(
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRds(
                        Rds.newBuilder()
                            .setRouteConfigName("route-config.googleapis.com")
                            .setConfigSource(
                                ConfigSource.newBuilder()
                                    .setAds(AggregatedConfigSource.getDefaultInstance())))
                    .addAllHttpFilters(Arrays.asList(httpFilters))
                    .build(),
                "type.googleapis.com"))
        .build();
  }
}
