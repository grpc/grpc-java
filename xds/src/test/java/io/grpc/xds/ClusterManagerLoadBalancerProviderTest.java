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

import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.internal.JsonParser;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.xds.ClusterManagerLoadBalancerProvider.ClusterManagerConfig;
import java.io.IOException;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ClusterManagerLoadBalancerProvider}. */
@RunWith(JUnit4.class)
public class ClusterManagerLoadBalancerProviderTest {

  @Test
  public void parseClusterManagerLoadBalancingPolicyConfig() throws IOException {
    LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();
    ClusterManagerLoadBalancerProvider provider =
        new ClusterManagerLoadBalancerProvider(lbRegistry);
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
        throw new UnsupportedOperationException("Should not be called");
      }

      @Override
      public ConfigOrError parseLoadBalancingPolicyConfig(
          Map<String, ?> rawLoadBalancingPolicyConfig) {
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
        throw new UnsupportedOperationException("Should not be called");
      }

      @Override
      public ConfigOrError parseLoadBalancingPolicyConfig(
          Map<String, ?> rawLoadBalancingPolicyConfig) {
        return ConfigOrError.fromConfig(barConfig);
      }
    };
    lbRegistry.register(lbProviderFoo);
    lbRegistry.register(lbProviderBar);

    String clusterManagerConfigJson = "{\n"
        + "  \"childPolicy\": {\n"
        + "    \"child1\": {\n"
        + "      \"lbPolicy\": [\n"
        + "        {\n"
        + "          \"foo_policy\": {"
        + "            \"config_name\": \"config_value\"\n"
        + "          }\n"
        + "        }\n"
        + "      ]\n"
        + "    },\n"
        + "    \"child2\": {\n"
        + "      \"lbPolicy\": [\n"
        + "        {\n"
        + "          \"bar_policy\": {}\n"
        + "        }, {\n"
        + "          \"unsupported\": {}\n"
        + "        }\n"
        + "      ]\n"
        + "    }\n"
        + "  }\n"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> rawLbConfigMap = (Map<String, ?>) JsonParser.parse(clusterManagerConfigJson);
    ConfigOrError configOrError = provider.parseLoadBalancingPolicyConfig(rawLbConfigMap);
    assertThat(configOrError.getConfig()).isNotNull();
    ClusterManagerConfig config = (ClusterManagerConfig) configOrError.getConfig();
    assertThat(config.childPolicies)
        .containsExactly(
            "child1",
            new PolicySelection(
                lbProviderFoo, fooConfig),
            "child2",
            new PolicySelection(lbProviderBar, barConfig));
  }

  @Test
  public void registered() {
    LoadBalancerProvider provider =
        LoadBalancerRegistry
            .getDefaultRegistry()
            .getProvider("cluster_manager_experimental");
    assertThat(provider).isInstanceOf(ClusterManagerLoadBalancerProvider.class);
  }
}
