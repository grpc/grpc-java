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
import static org.mockito.Mockito.mock;

import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status.Code;
import io.grpc.internal.JsonParser;
import io.grpc.xds.RingHashLoadBalancer.RingHashConfig;
import java.io.IOException;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RingHashLoadBalancerProvider}. */
@RunWith(JUnit4.class)
public class RingHashLoadBalancerProviderTest {

  private final RingHashLoadBalancerProvider provider = new RingHashLoadBalancerProvider();

  @Test
  public void provided() {
    LoadBalancerRegistry registry = LoadBalancerRegistry.getDefaultRegistry();
    assertThat(registry.getProvider("ring_hash")).isInstanceOf(RingHashLoadBalancerProvider.class);
  }

  @Test
  public void providesLoadBalancer() {
    assertThat(provider.newLoadBalancer(mock(Helper.class)))
        .isInstanceOf(RingHashLoadBalancer.class);
  }

  @Test
  public void parseLoadBalancingConfig_valid() throws IOException {
    String lbConfig = "{\"minRingSize\" : 10, \"maxRingSize\" : 100}";
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getConfig()).isNotNull();
    RingHashConfig config = (RingHashConfig) configOrError.getConfig();
    assertThat(config.minRingSize).isEqualTo(10L);
    assertThat(config.maxRingSize).isEqualTo(100L);
  }

  @Test
  public void parseLoadBalancingConfig_missingRingSize() throws IOException {
    String lbConfig = "{\"minRingSize\" : 10}";
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getError()).isNotNull();
    assertThat(configOrError.getError().getCode()).isEqualTo(Code.INVALID_ARGUMENT);
    assertThat(configOrError.getError().getDescription())
        .isEqualTo("Missing 'mingRingSize'/'maxRingSize'");
  }

  @Test
  public void parseLoadBalancingConfig_zeroMinRingSize() throws IOException {
    String lbConfig = "{\"minRingSize\" : 0, \"maxRingSize\" : 100}";
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getError()).isNotNull();
    assertThat(configOrError.getError().getCode()).isEqualTo(Code.INVALID_ARGUMENT);
    assertThat(configOrError.getError().getDescription())
        .isEqualTo("Invalid 'mingRingSize'/'maxRingSize'");
  }

  @Test
  public void parseLoadBalancingConfig_minRingSizeGreaterThanMaxRingSize() throws IOException {
    String lbConfig = "{\"minRingSize\" : 100, \"maxRingSize\" : 10}";
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getError()).isNotNull();
    assertThat(configOrError.getError().getCode()).isEqualTo(Code.INVALID_ARGUMENT);
    assertThat(configOrError.getError().getDescription())
        .isEqualTo("Invalid 'mingRingSize'/'maxRingSize'");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> parseJsonObject(String json) throws IOException {
    return (Map<String, ?>) JsonParser.parse(json);
  }
}
