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

import com.google.re2j.Pattern;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.internal.JsonParser;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.xds.RouteMatch.FractionMatcher;
import io.grpc.xds.RouteMatch.HeaderMatcher;
import io.grpc.xds.RouteMatch.PathMatcher;
import io.grpc.xds.XdsRoutingLoadBalancerProvider.Route;
import io.grpc.xds.XdsRoutingLoadBalancerProvider.XdsRoutingConfig;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link XdsRoutingLoadBalancerProvider}. */
@RunWith(JUnit4.class)
public class XdsRoutingLoadBalancerProviderTest {

  @Test
  public void parseXdsRoutingLoadBalancingPolicyConfig() throws Exception {
    LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();
    XdsRoutingLoadBalancerProvider xdsRoutingLoadBalancerProvider =
        new XdsRoutingLoadBalancerProvider(lbRegistry);
    final Object fooConfig = new Object();
    LoadBalancerProvider lbProviderFoo = new LoadBalancerProvider() {
      @Override
      public boolean isAvailable() {
        return true;
      }

      @Override
      public int getPriority() {
        return 5;
      }

      @Override
      public String getPolicyName() {
        return "foo_policy";
      }

      @Override
      public LoadBalancer newLoadBalancer(Helper helper) {
        return mock(LoadBalancer.class);
      }

      @Override
      public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawConfig) {
        return ConfigOrError.fromConfig(fooConfig);
      }
    };
    final Object barConfig = new Object();
    LoadBalancerProvider lbProviderBar = new LoadBalancerProvider() {
      @Override
      public boolean isAvailable() {
        return true;
      }

      @Override
      public int getPriority() {
        return 5;
      }

      @Override
      public String getPolicyName() {
        return "bar_policy";
      }

      @Override
      public LoadBalancer newLoadBalancer(Helper helper) {
        return mock(LoadBalancer.class);
      }

      @Override
      public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawConfig) {
        return ConfigOrError.fromConfig(barConfig);
      }
    };
    lbRegistry.register(lbProviderFoo);
    lbRegistry.register(lbProviderBar);

    String xdsRoutingConfigJson = ("{\n"
        + "  'route' : [\n"
        + "    {\n"
        + "      'path' : '/service_1/method_1',\n"
        + "      'action' : 'action_foo'\n"
        + "    },\n"
        + "    {\n"
        + "      'path' : '/service_1/method_2',\n"
        + "      'headers' : [\n"
        + "          {\n"
        + "              'name' : ':scheme',\n"
        + "              'exactMatch' : 'https'\n"
        + "          }\n"
        + "      ],\n"
        + "      'action' : 'action_bar'\n"
        + "    },\n"
        + "    {\n"
        + "      'prefix' : '/service_2/',\n"
        + "      'headers' : [\n"
        + "          {\n"
        + "              'name' : ':path',\n"
        + "              'regexMatch' : 'google.*'\n"
        + "          }\n"
        + "      ],\n"
        + "      'matchFraction' : {\n"
        + "          'numerator' : 10,\n"
        + "          'denominator' : 100\n"
        + "      },\n"
        + "      'action' : 'action_bar'\n"
        + "    },\n"
        + "    {\n"
        + "      'regex' : '^/service_2/method_3$',\n"
        + "      'headers' : [\n"
        + "          {\n"
        + "              'name' : ':method',\n"
        + "              'presentMatch' : true,\n"
        + "              'invertMatch' : true\n"
        + "          },\n"
        + "          {\n"
        + "              'name' : 'timeout',\n"
        + "              'rangeMatch' : {\n"
        + "                  'start' : 0,\n"
        + "                  'end' : 10\n"
        + "              }\n"
        + "          }\n"
        + "      ],\n"
        + "      'matchFraction' : {\n"
        + "          'numerator' : 55,\n"
        + "          'denominator' : 1000\n"
        + "      },\n"
        + "      'action' : 'action_foo'\n"
        + "    }\n"
        + "  ],\n"
        + "  'action' : {\n"
        + "    'action_foo' : {\n"
        + "      'childPolicy' : [\n"
        + "        {'unsupported_policy' : {}},\n"
        + "        {'foo_policy' : {}}\n"
        + "      ]\n"
        + "    },\n"
        + "    'action_bar' : {\n"
        + "      'childPolicy' : [\n"
        + "        {'unsupported_policy' : {}},\n"
        + "        {'bar_policy' : {}}\n"
        + "      ]\n"
        + "    }\n"
        + "  }\n"
        + "}\n").replace("'", "\"");

    @SuppressWarnings("unchecked")
    Map<String, ?> rawLbConfigMap = (Map<String, ?>) JsonParser.parse(xdsRoutingConfigJson);
    ConfigOrError configOrError =
        xdsRoutingLoadBalancerProvider.parseLoadBalancingPolicyConfig(rawLbConfigMap);
    assertThat(configOrError.getConfig()).isNotNull();
    XdsRoutingConfig config = (XdsRoutingConfig) configOrError.getConfig();
    List<Route> configRoutes = config.routes;
    assertThat(configRoutes).hasSize(4);
    assertThat(configRoutes.get(0)).isEqualTo(
        new Route(
            new RouteMatch(
                new PathMatcher("/service_1/method_1", null, null),
                Collections.<HeaderMatcher>emptyList(), null),
            "action_foo"));
    assertThat(configRoutes.get(1)).isEqualTo(
        new Route(
            new RouteMatch(
                new PathMatcher("/service_1/method_2", null, null),
                Arrays.asList(
                    new HeaderMatcher(":scheme", "https", null, null, null, null,
                        null, false)),
                null),
            "action_bar"));
    assertThat(configRoutes.get(2)).isEqualTo(
        new Route(
            new RouteMatch(
                new PathMatcher(null, "/service_2/", null),
                Arrays.asList(
                    new HeaderMatcher(":path", null, Pattern.compile("google.*"), null,
                        null, null, null, false)),
                new FractionMatcher(10, 100)),
            "action_bar"));
    assertThat(configRoutes.get(3)).isEqualTo(
        new Route(
            new RouteMatch(
                new PathMatcher(null, null, Pattern.compile("^/service_2/method_3$")),
                Arrays.asList(
                    new HeaderMatcher(":method", null, null, null,
                        true, null, null, true),
                    new HeaderMatcher("timeout", null, null,
                        new HeaderMatcher.Range(0, 10), null, null, null, false)),
                new FractionMatcher(55, 1000)),
            "action_foo"));

    Map<String, PolicySelection> configActions = config.actions;
    assertThat(configActions).hasSize(2);
    assertThat(configActions).containsExactly(
        "action_foo",
        new PolicySelection(lbProviderFoo, fooConfig),
        "action_bar",
        new PolicySelection(
            lbProviderBar, barConfig));
  }
}
