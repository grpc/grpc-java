/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.util;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import io.grpc.InternalServiceProviders;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.JsonParser;
import io.grpc.util.RandomSubsettingLoadBalancer.RandomSubsettingLoadBalancerConfig;
import java.io.IOException;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RandomSubsettingLoadBalancerProviderTest {
  private final RandomSubsettingLoadBalancerProvider provider =
      new RandomSubsettingLoadBalancerProvider();

  @Test
  public void registered() {
    for (LoadBalancerProvider current :
        InternalServiceProviders.getCandidatesViaServiceLoader(
            LoadBalancerProvider.class, getClass().getClassLoader())) {
      if (current instanceof RandomSubsettingLoadBalancerProvider) {
        return;
      }
    }
    fail("RandomSubsettingLoadBalancerProvider not registered");
  }

  @Test
  public void providesLoadBalancer() {
    Helper helper = mock(Helper.class);
    assertThat(provider.newLoadBalancer(helper))
        .isInstanceOf(RandomSubsettingLoadBalancer.class);
  }

  @Test
  public void parseConfigRequiresSubsetSize() throws IOException {
    String emptyConfig = "{}";

    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(emptyConfig));
    assertThat(configOrError.getError()).isNotNull();
    assertThat(configOrError.getError().toString())
        .isEqualTo(
            Status.UNAVAILABLE
                .withDescription(
                    "Subset size missing in random_subsetting_experimental, LB policy config={}")
                .toString());
  }

  @Test
  public void parseConfigReturnsErrorWhenChildPolicyMissing() throws IOException {
    String missingChildPolicyConfig = "{\"subsetSize\": 3}";

    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(missingChildPolicyConfig));
    assertThat(configOrError.getError()).isNotNull();

    Status error = configOrError.getError();
    assertThat(error.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(error.getDescription()).isEqualTo(
        "Failed to parse child in random_subsetting_experimental"
        + ", LB policy config={subsetSize=3.0}");
    assertThat(error.getCause().getMessage()).isEqualTo(
        "UNAVAILABLE: No child LB config specified");
  }

  @Test
  public void parseConfigReturnsErrorWhenChildPolicyInvalid() throws IOException {
    String invalidChildPolicyConfig =
        "{"
            + "\"subsetSize\": 3, "
            + "\"childPolicy\" : [{\"random_policy\" : {}}]"
            + "}";

    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(invalidChildPolicyConfig));
    assertThat(configOrError.getError()).isNotNull();

    Status error = configOrError.getError();
    assertThat(error.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(error.getDescription()).isEqualTo(
        "Failed to parse child in random_subsetting_experimental, LB policy config="
        + "{subsetSize=3.0, childPolicy=[{random_policy={}}]}");
    assertThat(error.getCause().getMessage()).contains(
        "UNAVAILABLE: None of [random_policy] specified by Service Config are available.");
  }

  @Test
  public void parseValidConfig() throws IOException {
    String validConfig =
        "{"
            + "\"subsetSize\": 3, "
            + "\"childPolicy\" : [{\"round_robin\" : {}}]"
            + "}";
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(validConfig));
    assertThat(configOrError.getConfig()).isNotNull();

    RandomSubsettingLoadBalancerConfig actualConfig =
        (RandomSubsettingLoadBalancerConfig) configOrError.getConfig();
    assertThat(GracefulSwitchLoadBalancerAccessor.getChildProvider(
        actualConfig.childConfig).getPolicyName()).isEqualTo("round_robin");
    assertThat(actualConfig.subsetSize).isEqualTo(3);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> parseJsonObject(String json) throws IOException {
    return (Map<String, ?>) JsonParser.parse(json);
  }
}
