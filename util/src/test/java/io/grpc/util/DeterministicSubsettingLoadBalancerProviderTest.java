/*
 * Copyright 2023 The gRPC Authors
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
import io.grpc.util.DeterministicSubsettingLoadBalancer.DeterministicSubsettingLoadBalancerConfig;
import java.io.IOException;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DeterministicSubsettingLoadBalancerProviderTest {

  private final DeterministicSubsettingLoadBalancerProvider provider =
      new DeterministicSubsettingLoadBalancerProvider();

  @Test
  public void registered() {
    for (LoadBalancerProvider current :
        InternalServiceProviders.getCandidatesViaServiceLoader(
            LoadBalancerProvider.class, getClass().getClassLoader())) {
      if (current instanceof DeterministicSubsettingLoadBalancerProvider) {
        return;
      }
    }
    fail("DeterministicSubsettingLoadBalancerProvider not registered");
  }

  @Test
  public void providesLoadBalancer() {
    Helper helper = mock(Helper.class);
    assertThat(provider.newLoadBalancer(helper))
        .isInstanceOf(DeterministicSubsettingLoadBalancer.class);
  }

  @Test
  public void parseConfigRequiresClientIdx() throws IOException {
    String config = "{ \"childPolicy\" : [{\"round_robin\" : {}}] } ";

    ConfigOrError configOrError = provider.parseLoadBalancingPolicyConfig(parseJsonObject(config));
    assertThat(configOrError.getError()).isNotNull();
    assertThat(configOrError.getError().toString())
        .isEqualTo(
            Status.INTERNAL
                .withDescription(
                    "No client index set, cannot determine subsets "
                      + "{childPolicy=[{round_robin={}}]}")
                .toString());
  }

  @Test
  public void parseConfigWithDefaults() throws IOException {
    String lbConfig =
        "{ \"clientIndex\" : 0, "
            + "\"childPolicy\" : [{\"round_robin\" : {}}], "
            + "\"sortAddresses\" : false }";
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    System.out.println(configOrError);
    assertThat(configOrError.getConfig()).isNotNull();
    DeterministicSubsettingLoadBalancerConfig config =
        (DeterministicSubsettingLoadBalancerConfig) configOrError.getConfig();

    assertThat(config.clientIndex).isEqualTo(0);
    assertThat(config.sortAddresses).isEqualTo(false);
    assertThat(config.childPolicy.getProvider().getPolicyName()).isEqualTo("round_robin");

    assertThat(config.subsetSize).isEqualTo(10);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> parseJsonObject(String json) throws IOException {
    return (Map<String, ?>) JsonParser.parse(json);
  }

  @Test
  public void parseConfigWithCustomSubsetSize() throws IOException {
    String lbConfig =
        "{ \"clientIndex\" : 0, "
            + "\"subsetSize\" : 3, "
            + "\"childPolicy\" : [{\"round_robin\" : {}}], "
            + "\"sortAddresses\" : false }";

    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getConfig()).isNotNull();
    DeterministicSubsettingLoadBalancerConfig config =
        (DeterministicSubsettingLoadBalancerConfig) configOrError.getConfig();
    assertThat(config.subsetSize).isEqualTo(3);
  }
}
