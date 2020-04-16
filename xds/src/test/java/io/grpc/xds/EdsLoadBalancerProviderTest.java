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

import io.grpc.NameResolver.ConfigOrError;
import io.grpc.internal.JsonParser;
import io.grpc.xds.EdsLoadBalancerProvider.EdsConfig;
import java.io.IOException;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link EdsLoadBalancerProvider}. */
@RunWith(JUnit4.class)
public class EdsLoadBalancerProviderTest {
  private final EdsLoadBalancerProvider provider = new EdsLoadBalancerProvider();

  @Test
  public void parseEdsLoadBalancingPolicyConfig() throws IOException {
    String rawEdsLbConfig = "{\n"
        + "  \"cluster\": \"cluster-foo.googleapis.com\",\n"
        + "  \"edsServiceName\": \"cluster-foo:service-blade\",\n"
        + "  \"lrsLoadReportingServerName\": \"trafficdirector.googleapis.com\",\n"
        + "  \"endpointPickingPolicy\": [\n"
        + "     { \"policy_foo\": {} },\n"
        + "     { \"pick_first\": {} }\n"
        + "  ]\n"
        + "}";

    @SuppressWarnings("unchecked")
    Map<String, ?> rawLbConfigMap = (Map<String, ?>) JsonParser.parse(rawEdsLbConfig);
    ConfigOrError result = provider.parseLoadBalancingPolicyConfig(rawLbConfigMap);
    assertThat(result.getConfig()).isNotNull();
    EdsConfig config = (EdsConfig) result.getConfig();
    assertThat(config.clusterName).isEqualTo("cluster-foo.googleapis.com");
    assertThat(config.edsServiceName).isEqualTo("cluster-foo:service-blade");
    assertThat(config.lrsServerName).isEqualTo("trafficdirector.googleapis.com");
    assertThat(config.endpointPickingPolicy.getProvider().getPolicyName())
        .isEqualTo("pick_first");
  }

  @Test
  public void parseEdsLoadBalancingPolicyConfig_defaultEndpointPickingPolicy_roundRobin()
      throws IOException {
    String rawEdsLbConfig = "{\n"
        + "  \"cluster\": \"cluster-foo.googleapis.com\",\n"
        + "  \"edsServiceName\": \"cluster-foo:service-blade\",\n"
        + "  \"lrsLoadReportingServerName\": \"trafficdirector.googleapis.com\"\n"
        + "}";

    @SuppressWarnings("unchecked")
    Map<String, ?> rawLbConfigMap = (Map<String, ?>) JsonParser.parse(rawEdsLbConfig);
    ConfigOrError result = provider.parseLoadBalancingPolicyConfig(rawLbConfigMap);
    assertThat(result.getConfig()).isNotNull();
    EdsConfig config = (EdsConfig) result.getConfig();
    assertThat(config.endpointPickingPolicy.getProvider().getPolicyName())
        .isEqualTo("round_robin");
  }
}
