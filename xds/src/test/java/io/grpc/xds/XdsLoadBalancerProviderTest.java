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

import io.grpc.NameResolver.ConfigOrError;
import io.grpc.internal.JsonParser;
import io.grpc.xds.XdsLoadBalancerProvider.XdsConfig;
import java.io.IOException;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link XdsLoadBalancerProvider}.
 */
@RunWith(JUnit4.class)
public class XdsLoadBalancerProviderTest {
  XdsLoadBalancerProvider provider = new XdsLoadBalancerProvider();

  @Test
  public void parseLoadBalancingConfig() throws Exception {
    String rawLbConfig = "{\n"
        + "  \"childPolicy\": [{\n"
        + "    \"unknown\": {\"key\": \"val\"}\n"
        + "  }, {\n"
        + "    \"pick_first\": {}\n"
        + "  }],\n"
        + "  \"fallbackPolicy\": [{\n"
        + "    \"pick_first\": {}\n"
        + "  }],\n"
        + "  \"edsServiceName\": \"dns:///eds.service.com:8080\",\n"
        + "  \"lrsLoadReportingServerName\": \"dns:///lrs.service.com:8080\"\n"
        + "}";
    Map<String, ?> rawlbConfigMap = checkObject(JsonParser.parse(rawLbConfig));
    ConfigOrError configOrError = provider.parseLoadBalancingPolicyConfig(rawlbConfigMap);

    assertThat(configOrError.getError()).isNull();
    XdsConfig config = (XdsConfig) configOrError.getConfig();
    assertThat(config.edsServiceName).isEqualTo("dns:///eds.service.com:8080");
    assertThat(config.lrsServerName).isEqualTo("dns:///lrs.service.com:8080");
    assertThat(config.childPolicy.getProvider().getPolicyName()).isEqualTo("pick_first");
    assertThat(config.fallbackPolicy.getProvider().getPolicyName()).isEqualTo("pick_first");
  }

  @Test
  public void parseLoadBalancingConfig_defaultToRoundRobin() throws IOException {
    String rawLbConfig = "{\n"
        + "  \"edsServiceName\": \"dns:///eds.service.com:8080\",\n"
        + "  \"lrsLoadReportingServerName\": \"dns:///lrs.service.com:8080\"\n"
        + "}";
    Map<String, ?> rawlbConfigMap = checkObject(JsonParser.parse(rawLbConfig));
    ConfigOrError configOrError = provider.parseLoadBalancingPolicyConfig(rawlbConfigMap);

    assertThat(configOrError.getError()).isNull();
    XdsConfig config = (XdsConfig) configOrError.getConfig();
    assertThat(config.childPolicy.getProvider().getPolicyName()).isEqualTo("round_robin");
    assertThat(config.fallbackPolicy.getProvider().getPolicyName()).isEqualTo("round_robin");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> checkObject(Object o) {
    return (Map<String, ?>) o;
  }
}
