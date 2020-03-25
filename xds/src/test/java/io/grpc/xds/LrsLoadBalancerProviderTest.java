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
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.LrsLoadBalancerProvider.LrsConfig;
import java.io.IOException;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link LrsLoadBalancerProvider}. */
@RunWith(JUnit4.class)
public class LrsLoadBalancerProviderTest {

  @Test
  public void parseLrsConfig() throws IOException {
    LrsLoadBalancerProvider provider = new LrsLoadBalancerProvider();

    String rawLrsLbConfig = "{\n"
        + "  \"clusterName\": \"cluster-foo.googleapis.com\",\n"
        + "  \"edsServiceName\": \"cluster-foo:service-blade\",\n"
        + "  \"lrsLoadReportingServerName\": \"trafficdirector.googleapis.com\",\n"
        + "  \"locality\": {\n"
        + "    \"region\": \"test-region\",\n"
        + "    \"zone\": \"test-zone\",\n"
        + "    \"subZone\": \"test-subzone\"\n"
        + "  }\n,"
        + "  \"childPolicy\": [\n"
        + "     { \"policy_foo\": {} },\n"
        + "     { \"round_robin\": {} }\n"
        + "  ]\n"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> rawLbConfigMap = (Map<String, ?>) JsonParser.parse(rawLrsLbConfig);
    ConfigOrError result = provider.parseLoadBalancingPolicyConfig(rawLbConfigMap);
    assertThat(result.getConfig()).isNotNull();
    LrsConfig config = (LrsConfig) result.getConfig();
    assertThat(config.clusterName).isEqualTo("cluster-foo.googleapis.com");
    assertThat(config.edsServiceName).isEqualTo("cluster-foo:service-blade");
    assertThat(config.lrsServerName).isEqualTo("trafficdirector.googleapis.com");
    Locality locality = config.locality;
    assertThat(locality.getRegion()).isEqualTo("test-region");
    assertThat(locality.getZone()).isEqualTo("test-zone");
    assertThat(locality.getSubZone()).isEqualTo("test-subzone");
    assertThat(config.childPolicy.getProvider().getPolicyName()).isEqualTo("round_robin");
  }
}
