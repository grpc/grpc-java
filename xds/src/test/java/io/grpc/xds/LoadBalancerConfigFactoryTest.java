/*
 * Copyright 2022 The gRPC Authors
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
import static org.junit.Assert.fail;

import com.github.xds.type.v3.TypedStruct;
import com.google.protobuf.Any;
import com.google.protobuf.Struct;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.Value;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbPolicy;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LeastRequestLbConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.RingHashLbConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.RingHashLbConfig.HashFunction;
import io.envoyproxy.envoy.config.cluster.v3.LoadBalancingPolicy;
import io.envoyproxy.envoy.config.cluster.v3.LoadBalancingPolicy.Policy;
import io.envoyproxy.envoy.config.core.v3.TypedExtensionConfig;
import io.envoyproxy.envoy.extensions.load_balancing_policies.ring_hash.v3.RingHash;
import io.envoyproxy.envoy.extensions.load_balancing_policies.round_robin.v3.RoundRobin;
import io.envoyproxy.envoy.extensions.load_balancing_policies.wrr_locality.v3.WrrLocality;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.internal.JsonUtil;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.xds.ClientXdsClient.ResourceInvalidException;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit test for {@link LoadBalancerConfigFactory}.
 */
@RunWith(JUnit4.class)
public class LoadBalancerConfigFactoryTest {

  private static final Policy ROUND_ROBIN_POLICY = Policy.newBuilder().setTypedExtensionConfig(
      TypedExtensionConfig.newBuilder().setTypedConfig(
          Any.pack(RoundRobin.getDefaultInstance()))).build();

  private static final long RING_HASH_MIN_RING_SIZE = 1;
  private static final long RING_HASH_MAX_RING_SIZE = 2;
  private static final Policy RING_HASH_POLICY = Policy.newBuilder().setTypedExtensionConfig(
      TypedExtensionConfig.newBuilder().setTypedConfig(Any.pack(
          RingHash.newBuilder()
              .setMinimumRingSize(UInt64Value.of(RING_HASH_MIN_RING_SIZE))
              .setMaximumRingSize(UInt64Value.of(RING_HASH_MAX_RING_SIZE))
              .setHashFunction(RingHash.HashFunction.XX_HASH).build()))).build();

  private static final String CUSTOM_POLICY_NAME = "myorg.MyCustomLeastRequestPolicy";
  private static final String CUSTOM_POLICY_FIELD_KEY = "choiceCount";
  private static final double CUSTOM_POLICY_FIELD_VALUE = 2;
  private static final Policy CUSTOM_POLICY = Policy.newBuilder().setTypedExtensionConfig(
      TypedExtensionConfig.newBuilder().setTypedConfig(Any.pack(TypedStruct.newBuilder()
          .setTypeUrl("type.googleapis.com/" + CUSTOM_POLICY_NAME).setValue(
              Struct.newBuilder()
                  .putFields(CUSTOM_POLICY_FIELD_KEY,
                      Value.newBuilder().setNumberValue(CUSTOM_POLICY_FIELD_VALUE).build()))
          .build()))).build();
  private static final FakeCustomLoadBalancerProvider CUSTOM_POLICY_PROVIDER
      = new FakeCustomLoadBalancerProvider();

  private static Policy buildWrrPolicy(Policy childPolicy) {
    return Policy.newBuilder().setTypedExtensionConfig(TypedExtensionConfig.newBuilder()
        .setTypedConfig(Any.pack(WrrLocality.newBuilder()
            .setEndpointPickingPolicy(LoadBalancingPolicy.newBuilder().addPolicies(childPolicy))
            .build()))).build();
  }

  @After
  public void deregisterCustomProvider() {
    LoadBalancerRegistry.getDefaultRegistry().deregister(CUSTOM_POLICY_PROVIDER);
  }

  @Test
  public void roundRobin() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder()
        .setLoadBalancingPolicy(
            LoadBalancingPolicy.newBuilder().addPolicies(buildWrrPolicy(ROUND_ROBIN_POLICY)))
        .build();

    assertValidRoundRobin(ServiceConfigUtil.unwrapLoadBalancingConfig(
        LoadBalancerConfigFactory.newConfig(cluster, true)));
  }

  @Test
  public void roundRobin_legacy() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder().setLbPolicy(LbPolicy.ROUND_ROBIN).build();

    assertValidRoundRobin(ServiceConfigUtil.unwrapLoadBalancingConfig(
        LoadBalancerConfigFactory.newConfig(cluster, true)));
  }

  private void assertValidRoundRobin(LbConfig lbConfig) {
    assertThat(lbConfig.getPolicyName()).isEqualTo("wrr_locality_experimental");

    @SuppressWarnings("unchecked")
    List<LbConfig> childConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(
        (List<Map<String, ?>>) lbConfig.getRawConfigValue().get("childPolicy"));
    assertThat(childConfigs).hasSize(1);
    assertThat(childConfigs.get(0).getPolicyName()).isEqualTo("round_robin");
    assertThat(childConfigs.get(0).getRawConfigValue()).isEmpty();
  }

  @Test
  public void ringHash() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder()
        .setLoadBalancingPolicy(LoadBalancingPolicy.newBuilder().addPolicies(RING_HASH_POLICY))
        .build();

    assertValidRingHash(ServiceConfigUtil.unwrapLoadBalancingConfig(
        LoadBalancerConfigFactory.newConfig(cluster, true)));
  }

  @Test
  public void ringHash_legacy() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder()
        .setLbPolicy(LbPolicy.RING_HASH)
        .setRingHashLbConfig(
            RingHashLbConfig.newBuilder()
                .setMinimumRingSize(UInt64Value.of(RING_HASH_MIN_RING_SIZE))
                .setMaximumRingSize(UInt64Value.of(RING_HASH_MAX_RING_SIZE))
                .setHashFunction(HashFunction.XX_HASH))
        .build();

    assertValidRingHash(ServiceConfigUtil.unwrapLoadBalancingConfig(
        LoadBalancerConfigFactory.newConfig(cluster, true)));
  }

  private void assertValidRingHash(LbConfig lbConfig) {
    assertThat(lbConfig.getPolicyName()).isEqualTo("ring_hash_experimental");
    assertThat(JsonUtil.getNumberAsLong(lbConfig.getRawConfigValue(), "minRingSize")).isEqualTo(
        RING_HASH_MIN_RING_SIZE);
    assertThat(JsonUtil.getNumberAsLong(lbConfig.getRawConfigValue(), "maxRingSize")).isEqualTo(
        RING_HASH_MAX_RING_SIZE);
  }

  @Test
  public void ringHash_invalidHash() {
    Cluster cluster = Cluster.newBuilder()
        .setLoadBalancingPolicy(LoadBalancingPolicy.newBuilder()
            .addPolicies(Policy.newBuilder().setTypedExtensionConfig(
                TypedExtensionConfig.newBuilder().setTypedConfig(Any.pack(
                    RingHash.newBuilder()
                        .setMinimumRingSize(UInt64Value.of(RING_HASH_MIN_RING_SIZE))
                        .setMaximumRingSize(UInt64Value.of(RING_HASH_MAX_RING_SIZE))
                        .setHashFunction(RingHash.HashFunction.MURMUR_HASH_2).build()))).build()))
        .build();

    try {
      ServiceConfigUtil.unwrapLoadBalancingConfig(
          LoadBalancerConfigFactory.newConfig(cluster, true));
    } catch (ResourceInvalidException e) {
      // With the new config mechanism we get a more generic error than with the old one because the
      // logic loops over potentially multiple configurations and only throws an exception at the
      // end if there was no valid policies found.
      assertThat(e).hasMessageThat().contains("Invalid ring hash function");
      return;
    }
    fail("ResourceInvalidException not thrown");
  }

  @Test
  public void ringHash_invalidHash_legacy() {
    Cluster cluster = Cluster.newBuilder().setLbPolicy(LbPolicy.RING_HASH).setRingHashLbConfig(
        RingHashLbConfig.newBuilder().setHashFunction(HashFunction.MURMUR_HASH_2)).build();

    try {
      ServiceConfigUtil.unwrapLoadBalancingConfig(
          LoadBalancerConfigFactory.newConfig(cluster, true));
    } catch (ResourceInvalidException e) {
      assertThat(e).hasMessageThat().contains("invalid ring hash function");
      return;
    }
    fail("ResourceInvalidException not thrown");
  }

  @Test
  public void leastRequest() throws ResourceInvalidException {
    System.setProperty("io.grpc.xds.experimentalEnableLeastRequest", "true");

    Cluster cluster = Cluster.newBuilder()
        .setLbPolicy(LbPolicy.LEAST_REQUEST)
        .setLeastRequestLbConfig(
            LeastRequestLbConfig.newBuilder().setChoiceCount(UInt32Value.of(10)))
        .build();

    LbConfig lbConfig = ServiceConfigUtil.unwrapLoadBalancingConfig(
        LoadBalancerConfigFactory.newConfig(cluster, true));
    assertThat(lbConfig.getPolicyName()).isEqualTo("wrr_locality_experimental");

    @SuppressWarnings("unchecked")
    List<LbConfig> childConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(
        (List<Map<String, ?>>) lbConfig.getRawConfigValue().get("childPolicy"));
    assertThat(childConfigs.get(0).getPolicyName()).isEqualTo("least_request_experimental");
    assertThat(
        JsonUtil.getNumberAsLong(childConfigs.get(0).getRawConfigValue(), "choiceCount")).isEqualTo(
        10);
  }


  @Test
  public void leastRequest_notEnabled() {
    System.setProperty("io.grpc.xds.experimentalEnableLeastRequest", "false");

    Cluster cluster = Cluster.newBuilder().setLbPolicy(LbPolicy.LEAST_REQUEST).build();

    try {
      ServiceConfigUtil.unwrapLoadBalancingConfig(
          LoadBalancerConfigFactory.newConfig(cluster, false));
    } catch (ResourceInvalidException e) {
      assertThat(e).hasMessageThat().contains("unsupported lb policy");
      return;
    }
    fail("ResourceInvalidException not thrown");
  }

  @Test
  public void customConfiguration() throws ResourceInvalidException {
    LoadBalancerRegistry.getDefaultRegistry().register(CUSTOM_POLICY_PROVIDER);

    Cluster cluster = Cluster.newBuilder()
        .setLoadBalancingPolicy(
            LoadBalancingPolicy.newBuilder().addPolicies(buildWrrPolicy(CUSTOM_POLICY)))
        .build();

    assertValidCustomConfig(ServiceConfigUtil.unwrapLoadBalancingConfig(
        LoadBalancerConfigFactory.newConfig(cluster, false)));
  }

  // When a provider for the custom policy is available, the configuration should use it.
  @Test
  public void complexCustomConfig_customProviderRegistered() throws ResourceInvalidException {
    LoadBalancerRegistry.getDefaultRegistry().register(CUSTOM_POLICY_PROVIDER);

    Cluster cluster = Cluster.newBuilder()
        .setLoadBalancingPolicy(
            LoadBalancingPolicy.newBuilder().addPolicies(buildWrrPolicy(CUSTOM_POLICY))
                .addPolicies(buildWrrPolicy(ROUND_ROBIN_POLICY)))
        .build();

    assertValidCustomConfig(ServiceConfigUtil.unwrapLoadBalancingConfig(
        LoadBalancerConfigFactory.newConfig(cluster, false)));
  }

  // When a provider for the custom policy is NOT available, we still fail even if there is another
  // round_robin configuration in the list as the wrr_locality the custom config is wrapped in is
  // a recognized type and expected to have a valid config.
  @Test
  public void complexCustomConfig_customProviderNotRegistered() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder()
        .setLoadBalancingPolicy(
            LoadBalancingPolicy.newBuilder().addPolicies(buildWrrPolicy(CUSTOM_POLICY))
                .addPolicies(buildWrrPolicy(ROUND_ROBIN_POLICY)))
        .build();

    try {
      ServiceConfigUtil.unwrapLoadBalancingConfig(
          LoadBalancerConfigFactory.newConfig(cluster, false));
    } catch (ResourceInvalidException e) {
      assertThat(e).hasMessageThat().contains("Invalid LoadBalancingPolicy");
      return;
    }
    fail("ResourceInvalidException not thrown");
  }

  private void assertValidCustomConfig(LbConfig lbConfig) {
    assertThat(lbConfig.getPolicyName()).isEqualTo("wrr_locality_experimental");
    @SuppressWarnings("unchecked")
    List<LbConfig> childConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(
        (List<Map<String, ?>>) lbConfig.getRawConfigValue().get("childPolicy"));
    assertThat(childConfigs).hasSize(1);
    assertThat(childConfigs.get(0).getPolicyName()).isEqualTo(CUSTOM_POLICY_NAME);
    assertThat(childConfigs.get(0).getRawConfigValue().get(CUSTOM_POLICY_FIELD_KEY)).isEqualTo(
        CUSTOM_POLICY_FIELD_VALUE);
  }

  @Test
  public void maxRecursion() {
    Cluster cluster = Cluster.newBuilder()
        .setLoadBalancingPolicy(
            LoadBalancingPolicy.newBuilder().addPolicies(
              buildWrrPolicy( // Wheee...
                buildWrrPolicy( // ...eee...
                  buildWrrPolicy( // ...eee!
                    buildWrrPolicy(
                      buildWrrPolicy(
                        buildWrrPolicy(
                          buildWrrPolicy(
                            buildWrrPolicy(
                              buildWrrPolicy(
                                buildWrrPolicy(
                                  buildWrrPolicy(
                                    buildWrrPolicy(
                                      buildWrrPolicy(
                                        buildWrrPolicy(
                                          buildWrrPolicy(
                                            buildWrrPolicy(
                                              buildWrrPolicy(
                                                ROUND_ROBIN_POLICY))))))))))))))))))).build();

    try {
      LoadBalancerConfigFactory.newConfig(cluster, false);
    } catch (ResourceInvalidException e) {
      assertThat(e).hasMessageThat().contains("Maximum LB config recursion depth reached");
      return;
    }
    fail("Expected a ResourceInvalidException because of max recursion exceeded");
  }

  private static class FakeCustomLoadBalancerProvider extends LoadBalancerProvider {

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      return null;
    }

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
      return CUSTOM_POLICY_NAME;
    }
  }
}
