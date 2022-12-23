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
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.internal.JsonParser;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedPolicySelection;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedTargetConfig;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link WeightedTargetLoadBalancerProvider}. */
@RunWith(JUnit4.class)
public class WeightedTargetLoadBalancerProviderTest {

  @Test
  public void parseWeightedTargetConfig() throws Exception {
    LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();
    WeightedTargetLoadBalancerProvider weightedTargetLoadBalancerProvider =
        new WeightedTargetLoadBalancerProvider(lbRegistry);
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

    String weightedTargetConfigJson = ("{"
        + "  'targets' : {"
        + "    'target_1' : {"
        + "      'weight' : 10,"
        + "      'childPolicy' : ["
        + "        {'unsupported_policy' : {}},"
        + "        {'foo_policy' : {}}"
        + "      ]"
        + "    },"
        + "    'target_2' : {"
        + "      'weight' : 20,"
        + "      'childPolicy' : ["
        + "        {'unsupported_policy' : {}},"
        + "        {'bar_policy' : {}}"
        + "      ]"
        + "    }"
        + "  }"
        + "}").replace('\'', '"');

    @SuppressWarnings("unchecked")
    Map<String, ?> rawLbConfigMap = (Map<String, ?>) JsonParser.parse(weightedTargetConfigJson);
    ConfigOrError parsedConfig =
        weightedTargetLoadBalancerProvider.parseLoadBalancingPolicyConfig(rawLbConfigMap);
    ConfigOrError expectedConfig = ConfigOrError.fromConfig(
        new WeightedTargetConfig(ImmutableMap.of(
            "target_1",
            new WeightedPolicySelection(
                10,
                new PolicySelection(lbProviderFoo, fooConfig)),
            "target_2",
            new WeightedPolicySelection(
                20,
                new PolicySelection(lbProviderBar, barConfig)))));
    assertThat(parsedConfig).isEqualTo(expectedConfig);
  }
}
