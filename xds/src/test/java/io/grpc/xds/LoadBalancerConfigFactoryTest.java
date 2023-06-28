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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.Duration;
import com.google.protobuf.FloatValue;
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
import io.envoyproxy.envoy.extensions.load_balancing_policies.client_side_weighted_round_robin.v3.ClientSideWeightedRoundRobin;
import io.envoyproxy.envoy.extensions.load_balancing_policies.least_request.v3.LeastRequest;
import io.envoyproxy.envoy.extensions.load_balancing_policies.pick_first.v3.PickFirst;
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
import io.grpc.xds.XdsClientImpl.ResourceInvalidException;
import java.util.List;
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
          RingHash.newBuilder().setMinimumRingSize(UInt64Value.of(RING_HASH_MIN_RING_SIZE))
              .setMaximumRingSize(UInt64Value.of(RING_HASH_MAX_RING_SIZE))
              .setHashFunction(RingHash.HashFunction.XX_HASH).build()))).build();

  private static final int LEAST_REQUEST_CHOICE_COUNT = 10;
  private static final Policy LEAST_REQUEST_POLICY = Policy.newBuilder().setTypedExtensionConfig(
      TypedExtensionConfig.newBuilder().setTypedConfig(Any.pack(
          LeastRequest.newBuilder().setChoiceCount(UInt32Value.of(LEAST_REQUEST_CHOICE_COUNT))
              .build()))).build();

  private static final Policy PICK_FIRST_POLICY = Policy.newBuilder().setTypedExtensionConfig(
      TypedExtensionConfig.newBuilder().setTypedConfig(Any.pack(
          PickFirst.newBuilder().setShuffleAddressList(true)
              .build()))).build();

  private static final Policy WRR_POLICY = Policy.newBuilder()
              .setTypedExtensionConfig(TypedExtensionConfig.newBuilder()
                  .setName("backend")
                  .setTypedConfig(
                      Any.pack(ClientSideWeightedRoundRobin.newBuilder()
                          .setBlackoutPeriod(Duration.newBuilder().setSeconds(287).build())
                          .setEnableOobLoadReport(
                              BoolValue.newBuilder().setValue(true).build())
                          .setErrorUtilizationPenalty(
                              FloatValue.newBuilder().setValue(1.75F).build())
                          .build()))
                  .build())
              .build();
  private static final String CUSTOM_POLICY_NAME = "myorg.MyCustomLeastRequestPolicy";
  private static final String CUSTOM_POLICY_FIELD_KEY = "choiceCount";
  private static final double CUSTOM_POLICY_FIELD_VALUE = 2;
  private static final Policy CUSTOM_POLICY = Policy.newBuilder().setTypedExtensionConfig(
          TypedExtensionConfig.newBuilder().setTypedConfig(Any.pack(
              TypedStruct.newBuilder().setTypeUrl(
                      "type.googleapis.com/" + CUSTOM_POLICY_NAME).setValue(
                      Struct.newBuilder().putFields(CUSTOM_POLICY_FIELD_KEY,
                          Value.newBuilder().setNumberValue(CUSTOM_POLICY_FIELD_VALUE).build()))
                  .build()))).build();
  private static final Policy CUSTOM_POLICY_UDPA = Policy.newBuilder().setTypedExtensionConfig(
      TypedExtensionConfig.newBuilder().setTypedConfig(Any.pack(
          com.github.udpa.udpa.type.v1.TypedStruct.newBuilder().setTypeUrl(
                  "type.googleapis.com/" + CUSTOM_POLICY_NAME).setValue(
                  Struct.newBuilder().putFields(CUSTOM_POLICY_FIELD_KEY,
                      Value.newBuilder().setNumberValue(CUSTOM_POLICY_FIELD_VALUE).build()))
              .build()))).build();
  private static final FakeCustomLoadBalancerProvider CUSTOM_POLICY_PROVIDER
      = new FakeCustomLoadBalancerProvider();

  private static final LbConfig VALID_ROUND_ROBIN_CONFIG = new LbConfig("wrr_locality_experimental",
      ImmutableMap.of("childPolicy",
          ImmutableList.of(ImmutableMap.of("round_robin", ImmutableMap.of()))));

  private static final LbConfig VALID_WRR_CONFIG = new LbConfig("wrr_locality_experimental",
      ImmutableMap.of("childPolicy", ImmutableList.of(
      ImmutableMap.of("weighted_round_robin",
      ImmutableMap.of("blackoutPeriod","287s", "enableOobLoadReport", true,
          "errorUtilizationPenalty", 1.75F )))));
  private static final LbConfig VALID_RING_HASH_CONFIG = new LbConfig("ring_hash_experimental",
      ImmutableMap.of("minRingSize", (double) RING_HASH_MIN_RING_SIZE, "maxRingSize",
          (double) RING_HASH_MAX_RING_SIZE));
  private static final LbConfig VALID_CUSTOM_CONFIG = new LbConfig(CUSTOM_POLICY_NAME,
      ImmutableMap.of(CUSTOM_POLICY_FIELD_KEY, CUSTOM_POLICY_FIELD_VALUE));
  private static final LbConfig VALID_CUSTOM_CONFIG_IN_WRR = new LbConfig(
      "wrr_locality_experimental", ImmutableMap.of("childPolicy", ImmutableList.of(
      ImmutableMap.of(VALID_CUSTOM_CONFIG.getPolicyName(),
          VALID_CUSTOM_CONFIG.getRawConfigValue()))));
  private static final LbConfig VALID_LEAST_REQUEST_CONFIG = new LbConfig(
      "least_request_experimental",
      ImmutableMap.of("choiceCount", (double) LEAST_REQUEST_CHOICE_COUNT));
  private static final LbConfig VALID_PICK_FIRST_CONFIG = new LbConfig(
      "pick_first",
      ImmutableMap.of("shuffleAddressList", true));

  @After
  public void deregisterCustomProvider() {
    LoadBalancerRegistry.getDefaultRegistry().deregister(CUSTOM_POLICY_PROVIDER);
  }

  @Test
  public void roundRobin() throws ResourceInvalidException {
    Cluster cluster = newCluster(buildWrrPolicy(ROUND_ROBIN_POLICY));

    assertThat(newLbConfig(cluster, true, true, true)).isEqualTo(VALID_ROUND_ROBIN_CONFIG);
  }

  @Test
  public void weightedRoundRobin() throws ResourceInvalidException {
    Cluster cluster = newCluster(buildWrrPolicy(WRR_POLICY));

    assertThat(newLbConfig(cluster, true, true, true)).isEqualTo(VALID_WRR_CONFIG);
  }

  @Test
  public void weightedRoundRobin_invalid() throws ResourceInvalidException {
    Cluster cluster = newCluster(buildWrrPolicy(Policy.newBuilder()
        .setTypedExtensionConfig(TypedExtensionConfig.newBuilder()
            .setName("backend")
            .setTypedConfig(
                Any.pack(ClientSideWeightedRoundRobin.newBuilder()
                    .setBlackoutPeriod(Duration.newBuilder().setNanos(1000000000).build())
                    .setEnableOobLoadReport(
                        BoolValue.newBuilder().setValue(true).build())
                    .build()))
            .build())
        .build()));

    assertResourceInvalidExceptionThrown(cluster, true, true, true,
        "Invalid duration in weighted round robin config");
  }

  @Test
  public void weightedRoundRobin_fallback_roundrobin() throws ResourceInvalidException {
    Cluster cluster = newCluster(buildWrrPolicy(WRR_POLICY, ROUND_ROBIN_POLICY));

    assertThat(newLbConfig(cluster, true, false, true)).isEqualTo(VALID_ROUND_ROBIN_CONFIG);
  }

  @Test
  public void roundRobin_legacy() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder().setLbPolicy(LbPolicy.ROUND_ROBIN).build();

    assertThat(newLbConfig(cluster, true, true, true)).isEqualTo(VALID_ROUND_ROBIN_CONFIG);
  }

  @Test
  public void ringHash() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder()
        .setLoadBalancingPolicy(LoadBalancingPolicy.newBuilder().addPolicies(RING_HASH_POLICY))
        .build();

    assertThat(newLbConfig(cluster, true, true, true)).isEqualTo(VALID_RING_HASH_CONFIG);
  }

  @Test
  public void ringHash_legacy() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder().setLbPolicy(LbPolicy.RING_HASH).setRingHashLbConfig(
        RingHashLbConfig.newBuilder().setMinimumRingSize(UInt64Value.of(RING_HASH_MIN_RING_SIZE))
            .setMaximumRingSize(UInt64Value.of(RING_HASH_MAX_RING_SIZE))
            .setHashFunction(HashFunction.XX_HASH)).build();

    assertThat(newLbConfig(cluster, true, true, true)).isEqualTo(VALID_RING_HASH_CONFIG);
  }

  @Test
  public void ringHash_invalidHash() {
    Cluster cluster = newCluster(
        Policy.newBuilder().setTypedExtensionConfig(TypedExtensionConfig.newBuilder()
            .setTypedConfig(Any.pack(
                RingHash.newBuilder().setMinimumRingSize(UInt64Value.of(RING_HASH_MIN_RING_SIZE))
                    .setMaximumRingSize(UInt64Value.of(RING_HASH_MAX_RING_SIZE))
                    .setHashFunction(RingHash.HashFunction.MURMUR_HASH_2).build()))).build());

    assertResourceInvalidExceptionThrown(cluster, true, true, true, "Invalid ring hash function");
  }

  @Test
  public void ringHash_invalidHash_legacy() {
    Cluster cluster = Cluster.newBuilder().setLbPolicy(LbPolicy.RING_HASH).setRingHashLbConfig(
        RingHashLbConfig.newBuilder().setHashFunction(HashFunction.MURMUR_HASH_2)).build();

    assertResourceInvalidExceptionThrown(cluster, true, true, true, "invalid ring hash function");
  }

  @Test
  public void leastRequest() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder()
        .setLoadBalancingPolicy(LoadBalancingPolicy.newBuilder().addPolicies(LEAST_REQUEST_POLICY))
        .build();

    assertThat(newLbConfig(cluster, true, true, true)).isEqualTo(VALID_LEAST_REQUEST_CONFIG);
  }

  @Test
  public void leastRequest_legacy() throws ResourceInvalidException {
    System.setProperty("io.grpc.xds.experimentalEnableLeastRequest", "true");

    Cluster cluster = Cluster.newBuilder().setLbPolicy(LbPolicy.LEAST_REQUEST)
        .setLeastRequestLbConfig(
            LeastRequestLbConfig.newBuilder()
                .setChoiceCount(UInt32Value.of(LEAST_REQUEST_CHOICE_COUNT))).build();

    LbConfig lbConfig = newLbConfig(cluster, true, true, true);
    assertThat(lbConfig.getPolicyName()).isEqualTo("wrr_locality_experimental");

    List<LbConfig> childConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(
        JsonUtil.getListOfObjects(lbConfig.getRawConfigValue(), "childPolicy"));
    assertThat(childConfigs.get(0).getPolicyName()).isEqualTo("least_request_experimental");
    assertThat(
        JsonUtil.getNumberAsLong(childConfigs.get(0).getRawConfigValue(), "choiceCount")).isEqualTo(
        LEAST_REQUEST_CHOICE_COUNT);
  }

  @Test
  public void leastRequest_notEnabled() {
    Cluster cluster = Cluster.newBuilder().setLbPolicy(LbPolicy.LEAST_REQUEST).build();

    assertResourceInvalidExceptionThrown(cluster, false, true, true, "unsupported lb policy");
  }

  @Test
  public void pickFirst() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder()
        .setLoadBalancingPolicy(LoadBalancingPolicy.newBuilder().addPolicies(PICK_FIRST_POLICY))
        .build();

    assertThat(newLbConfig(cluster, true, true, true)).isEqualTo(VALID_PICK_FIRST_CONFIG);
  }

  @Test
  public void pickFirst_notEnabled() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder()
        .setLoadBalancingPolicy(LoadBalancingPolicy.newBuilder().addPolicies(PICK_FIRST_POLICY))
        .build();

    assertResourceInvalidExceptionThrown(cluster, true, true, false, "Invalid LoadBalancingPolicy");
  }

  @Test
  public void customRootLb_providerRegistered() throws ResourceInvalidException {
    LoadBalancerRegistry.getDefaultRegistry().register(CUSTOM_POLICY_PROVIDER);

    assertThat(newLbConfig(newCluster(CUSTOM_POLICY), false,
        true, true)).isEqualTo(VALID_CUSTOM_CONFIG);
  }

  @Test
  public void customRootLb_providerNotRegistered() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder()
        .setLoadBalancingPolicy(LoadBalancingPolicy.newBuilder().addPolicies(CUSTOM_POLICY))
        .build();

    assertResourceInvalidExceptionThrown(cluster, false, true, true, "Invalid LoadBalancingPolicy");
  }

  // When a provider for the endpoint picking custom policy is available, the configuration should
  // use it.
  @Test
  public void customLbInWrr_providerRegistered() throws ResourceInvalidException {
    LoadBalancerRegistry.getDefaultRegistry().register(CUSTOM_POLICY_PROVIDER);

    Cluster cluster = Cluster.newBuilder().setLoadBalancingPolicy(LoadBalancingPolicy.newBuilder()
        .addPolicies(buildWrrPolicy(CUSTOM_POLICY, ROUND_ROBIN_POLICY))).build();

    assertThat(newLbConfig(cluster, false, true, true)).isEqualTo(VALID_CUSTOM_CONFIG_IN_WRR);
  }

  // When a provider for the endpoint picking custom policy is available, the configuration should
  // use it. This one uses the legacy UDPA TypedStruct that is also supported.
  @Test
  public void customLbInWrr_providerRegistered_udpa() throws ResourceInvalidException {
    LoadBalancerRegistry.getDefaultRegistry().register(CUSTOM_POLICY_PROVIDER);

    Cluster cluster = Cluster.newBuilder().setLoadBalancingPolicy(LoadBalancingPolicy.newBuilder()
        .addPolicies(buildWrrPolicy(CUSTOM_POLICY_UDPA, ROUND_ROBIN_POLICY))).build();

    assertThat(newLbConfig(cluster, false, true, true)).isEqualTo(VALID_CUSTOM_CONFIG_IN_WRR);
  }

  // When a provider for the custom wrr_locality child policy is NOT available, we should fall back
  // to the round_robin that is also provided.
  @Test
  public void customLbInWrr_providerNotRegistered() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder().setLoadBalancingPolicy(LoadBalancingPolicy.newBuilder()
        .addPolicies(buildWrrPolicy(CUSTOM_POLICY, ROUND_ROBIN_POLICY))).build();

    assertThat(newLbConfig(cluster, false, true, true)).isEqualTo(VALID_ROUND_ROBIN_CONFIG);
  }

  // When a provider for the custom wrr_locality child policy is NOT available and no alternative
  // child policy is provided, the configuration is invalid.
  @Test
  public void customLbInWrr_providerNotRegistered_noFallback() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder().setLoadBalancingPolicy(
        LoadBalancingPolicy.newBuilder().addPolicies(buildWrrPolicy(CUSTOM_POLICY))).build();

    assertResourceInvalidExceptionThrown(cluster, false, true, true, "Invalid LoadBalancingPolicy");
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

    assertResourceInvalidExceptionThrown(cluster, false, true, true,
        "Maximum LB config recursion depth reached");
  }

  private Cluster newCluster(Policy... policies) {
    return Cluster.newBuilder().setLoadBalancingPolicy(
        LoadBalancingPolicy.newBuilder().addAllPolicies(Lists.newArrayList(policies))).build();
  }

  private static Policy buildWrrPolicy(Policy... childPolicy) {
    return Policy.newBuilder().setTypedExtensionConfig(TypedExtensionConfig.newBuilder()
        .setTypedConfig(Any.pack(WrrLocality.newBuilder().setEndpointPickingPolicy(
                LoadBalancingPolicy.newBuilder().addAllPolicies(Lists.newArrayList(childPolicy)))
            .build()))).build();
  }

  private LbConfig newLbConfig(Cluster cluster, boolean enableLeastRequest, boolean enableWrr,
      boolean enablePickFirst)
      throws ResourceInvalidException {
    return ServiceConfigUtil.unwrapLoadBalancingConfig(
        LoadBalancerConfigFactory.newConfig(cluster, enableLeastRequest,
            enableWrr, enablePickFirst));
  }

  private void assertResourceInvalidExceptionThrown(Cluster cluster, boolean enableLeastRequest,
      boolean enableWrr, boolean enablePickFirst, String expectedMessage) {
    try {
      newLbConfig(cluster, enableLeastRequest, enableWrr, enablePickFirst);
    } catch (ResourceInvalidException e) {
      assertThat(e).hasMessageThat().contains(expectedMessage);
      return;
    }
    fail("ResourceInvalidException not thrown");
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
