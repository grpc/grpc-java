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
import static io.grpc.xds.XdsLbPolicies.CDS_POLICY_NAME;
import static io.grpc.xds.XdsLbPolicies.WEIGHTED_TARGET_POLICY_NAME;
import static io.grpc.xds.XdsLbPolicies.XDS_ROUTING_POLICY_NAME;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.re2j.Pattern;
import io.grpc.internal.JsonParser;
import io.grpc.xds.EnvoyProtoData.ClusterWeight;
import io.grpc.xds.EnvoyProtoData.Route;
import io.grpc.xds.EnvoyProtoData.RouteAction;
import io.grpc.xds.RouteMatch.FractionMatcher;
import io.grpc.xds.RouteMatch.HeaderMatcher;
import io.grpc.xds.RouteMatch.PathMatcher;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link XdsNameResolver}. */
@RunWith(JUnit4.class)
public class XdsNameResolverTest {

  @Test
  public void generateWeightedTargetRawConfig() throws IOException {
    List<ClusterWeight> clusterWeights =
        Arrays.asList(
            new ClusterWeight("cluster-foo", 30), new ClusterWeight("cluster-bar", 50));
    Map<String, ?> config = XdsNameResolver.generateWeightedTargetRawConfig(clusterWeights);
    String expectedJson = "{\n"
        + "  \"weighted_target_experimental\": {\n"
        + "    \"targets\": {\n"
        + "      \"cluster-foo\": {\n"
        + "        \"weight\": 30,\n"
        + "        \"childPolicy\": [{\n"
        + "          \"cds_experimental\": {\n"
        + "            \"cluster\": \"cluster-foo\"\n"
        + "          }\n"
        + "        }]\n"
        + "      },\n"
        + "      \"cluster-bar\": {\n"
        + "        \"weight\": 50,\n"
        + "        \"childPolicy\": [{\n"
        + "          \"cds_experimental\": {\n"
        + "            \"cluster\": \"cluster-bar\"\n"
        + "          }\n"
        + "        }]\n"
        + "      }\n"
        + "    }\n"
        + "  }\n"
        + "}";
    assertThat(config).isEqualTo(JsonParser.parse(expectedJson));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void generateXdsRoutingRawConfig() {
    Route r1 =
        new Route(
            new RouteMatch(
                new PathMatcher(null, "", null), Collections.<HeaderMatcher>emptyList(),
                new FractionMatcher(10, 20)),
            new RouteAction("cluster-foo", null));
    Route r2 =
        new Route(
            new RouteMatch(
                new PathMatcher("/service/method", null, null),
                Arrays.asList(
                    new HeaderMatcher(":scheme", "https", null, null, null, null, null, false)),
                null),
            new RouteAction(
                null,
                Arrays.asList(
                    new ClusterWeight("cluster-foo", 20),
                    new ClusterWeight("cluster-bar", 20))));

    Map<String, ?> config =
        XdsNameResolver.generateXdsRoutingRawConfig(Arrays.asList(r1, r2));
    assertThat(config.keySet()).containsExactly("xds_routing_experimental");
    Map<String, ?> content = (Map<String, ?>) config.get(XDS_ROUTING_POLICY_NAME);
    assertThat(content.keySet()).containsExactly("action", "route");
    Map<String, Map<String, ?>> actions = (Map<String, Map<String, ?>>) content.get("action");
    List<Map<String, ?>> routes = (List<Map<String, ?>>) content.get("route");
    assertThat(actions).hasSize(2);
    assertThat(routes).hasSize(2);

    Map<String, ?> route0 = routes.get(0);
    assertThat(route0.keySet()).containsExactly("prefix", "matchFraction", "action");
    assertThat((String) route0.get("prefix")).isEqualTo("");
    assertThat((Map<String, ?>) route0.get("matchFraction"))
        .containsExactly("numerator", 10, "denominator", 20);
    assertCdsPolicy(actions.get(route0.get("action")), "cluster-foo");

    Map<String, ?> route1 = routes.get(1);
    assertThat(route1.keySet()).containsExactly("path", "headers", "action");
    assertThat((String) route1.get("path")).isEqualTo("/service/method");
    Map<String, ?> header = Iterables.getOnlyElement((List<Map<String, ?>>) route1.get("headers"));
    assertThat(header)
        .containsExactly("name", ":scheme", "exactMatch", "https", "invertMatch", false);
    assertWeightedTargetPolicy(
        actions.get(route1.get("action")),
        ImmutableMap.of(
            "cluster-foo", 20,  "cluster-bar", 20));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void generateXdsRoutingRawConfig_allowDuplicateMatchers() {
    Route route =
        new Route(
            new RouteMatch(
                new PathMatcher("/service/method", null, null),
                Collections.<HeaderMatcher>emptyList(), null),
            new RouteAction("cluster-foo", null));

    Map<String, ?> config =
        XdsNameResolver.generateXdsRoutingRawConfig(Arrays.asList(route, route));
    assertThat(config.keySet()).containsExactly(XDS_ROUTING_POLICY_NAME);
    Map<String, ?> content = (Map<String, ?>) config.get(XDS_ROUTING_POLICY_NAME);
    assertThat(content.keySet()).containsExactly("action", "route");
    Map<String, ?> actions = (Map<String, ?>) content.get("action");
    List<?> routes = (List<?>) content.get("route");
    assertThat(actions).hasSize(1);
    assertThat(routes).hasSize(2);
    assertThat(routes.get(0)).isEqualTo(routes.get(1));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void convertToRawRoute() throws IOException {
    RouteMatch routeMatch1 =
        new RouteMatch(
            new PathMatcher("/service/method", null, null),
            Collections.<HeaderMatcher>emptyList(), null);
    String expectedJson1 = "{\n"
        + "  \"path\": \"/service/method\",\n"
        + "  \"action\": \"action_foo\""
        + "}";
    assertThat(XdsNameResolver.convertToRawRoute(routeMatch1, "action_foo"))
        .isEqualTo(JsonParser.parse(expectedJson1));

    RouteMatch routeMatch2 =
        new RouteMatch(
            new PathMatcher(null, "/", null), Collections.<HeaderMatcher>emptyList(),
            new FractionMatcher(10, 100));
    Map<String, ?> rawRoute2 = XdsNameResolver.convertToRawRoute(routeMatch2, "action_foo");
    Map<String, ?> rawMatchFraction = (Map<String, ?>) rawRoute2.get("matchFraction");
    assertThat(rawMatchFraction).containsExactly("numerator", 10, "denominator", 100);

    RouteMatch routeMatch3 =
        new RouteMatch(
            new PathMatcher(null, "/", null),
            Arrays.asList(
                new HeaderMatcher("timeout", null, null, new HeaderMatcher.Range(0L, 10L),
                    null, null, null, false)),
            null);
    Map<String, ?> rawRoute3 = XdsNameResolver.convertToRawRoute(routeMatch3, "action_foo");
    Map<String, ?> header =
        (Map<String, ?>) Iterables.getOnlyElement((List<?>) rawRoute3.get("headers"));
    assertThat((Map<String, ?>) header.get("rangeMatch")).containsExactly("start", 0L, "end", 10L);

    RouteMatch routeMatch4 =
        new RouteMatch(
            new PathMatcher(null, "/", null),
            Arrays.asList(
                new HeaderMatcher(":scheme", "https", null, null, null, null, null, false),
                new HeaderMatcher(
                    ":path", null, Pattern.compile("google.*"), null, null, null, null, true),
                new HeaderMatcher("timeout", null, null, null, true, null, null, false),
                new HeaderMatcher(":authority", null, null, null, null, "google", null, false),
                new HeaderMatcher(":authority", null, null, null, null, null, "grpc.io", false)),
            null);

    String expectedJson4 = "{\n"
        + "  \"prefix\": \"/\",\n"
        + "  \"headers\": [\n"
        + "    {\n"
        + "      \"name\": \":scheme\",\n"
        + "      \"exactMatch\": \"https\",\n"
        + "      \"invertMatch\": false\n"
        + "    },\n"
        + "    {\n"
        + "      \"name\": \":path\",\n"
        + "      \"regexMatch\": \"google.*\",\n"
        + "      \"invertMatch\": true\n"
        + "    },\n"
        + "    {\n"
        + "      \"name\": \"timeout\",\n"
        + "      \"presentMatch\": true,\n"
        + "      \"invertMatch\": false\n"
        + "    },\n"
        + "    {\n"
        + "      \"name\": \":authority\",\n"
        + "      \"prefixMatch\": \"google\",\n"
        + "      \"invertMatch\": false\n"
        + "    },\n"
        + "    {\n"
        + "      \"name\": \":authority\",\n"
        + "      \"suffixMatch\": \"grpc.io\",\n"
        + "      \"invertMatch\": false\n"
        + "    }\n"
        + "  ],\n"
        + "  \"action\": \"action_foo\""
        + "}";
    assertThat(XdsNameResolver.convertToRawRoute(routeMatch4, "action_foo"))
        .isEqualTo(JsonParser.parse(expectedJson4));
  }

  /** Asserts that the given action contains a single CDS policy with the given cluster name. */
  @SuppressWarnings("unchecked")
  static void assertCdsPolicy(Map<String, ?> action, String clusterName) {
    assertThat(action.keySet()).containsExactly("childPolicy");
    Map<String, ?> lbConfig =
        Iterables.getOnlyElement((List<Map<String, ?>>) action.get("childPolicy"));
    assertThat(lbConfig.keySet()).containsExactly(CDS_POLICY_NAME);
    Map<String, ?> rawConfigValues = (Map<String, ?>) lbConfig.get(CDS_POLICY_NAME);
    assertThat(rawConfigValues).containsExactly("cluster", clusterName);
  }

  /**
   * Asserts that the given action contains a single weighted-target policy with the given cluster
   * to weight mapping.
   */
  @SuppressWarnings("unchecked")
  static void assertWeightedTargetPolicy(
      Map<String, ?> action, Map<String, Integer> clusterWeights) {
    assertThat(action.keySet()).containsExactly("childPolicy");
    Map<String, ?> lbConfig =
        Iterables.getOnlyElement((List<Map<String, ?>>) action.get("childPolicy"));
    assertThat(lbConfig.keySet()).containsExactly(WEIGHTED_TARGET_POLICY_NAME);
    Map<String, ?> rawConfigValues = (Map<String, ?>) lbConfig.get(WEIGHTED_TARGET_POLICY_NAME);
    assertWeightedTargetConfigClusterWeights(rawConfigValues, clusterWeights);
  }

  /**
   * Asserts that the given raw config is a weighted-target config with the given cluster to weight
   * mapping.
   */
  @SuppressWarnings("unchecked")
  static void assertWeightedTargetConfigClusterWeights(
      Map<String, ?> rawConfigValues, Map<String, Integer> clusterWeight) {
    assertThat(rawConfigValues.keySet()).containsExactly("targets");
    Map<String, ?> targets = (Map<String, ?>) rawConfigValues.get("targets");
    assertThat(targets.keySet()).isEqualTo(clusterWeight.keySet());
    for (String targetName : targets.keySet()) {
      Map<String, ?> target = (Map<String, ?>) targets.get(targetName);
      assertThat(target.keySet()).containsExactly("childPolicy", "weight");
      Map<String, ?> lbConfig =
          Iterables.getOnlyElement((List<Map<String, ?>>) target.get("childPolicy"));
      assertThat(lbConfig.keySet()).containsExactly(CDS_POLICY_NAME);
      Map<String, ?> rawClusterConfigValues = (Map<String, ?>) lbConfig.get(CDS_POLICY_NAME);
      assertThat(rawClusterConfigValues).containsExactly("cluster", targetName);
      assertThat(target.get("weight")).isEqualTo(clusterWeight.get(targetName));
    }
  }
}
