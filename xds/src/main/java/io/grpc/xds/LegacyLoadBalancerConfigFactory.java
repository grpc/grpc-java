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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LeastRequestLbConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.RingHashLbConfig;
import io.grpc.xds.ClientXdsClient.ResourceInvalidException;

/**
 * Builds a JSON LB configuration based on the old style of using the xDS Cluster proto message. The
 * lb_policy field is used to select the policy and configuration is extracted from various policy
 * specific fields in Cluster.
 */
abstract class LegacyLoadBalancerConfigFactory {

  static final String ROUND_ROBIN_FIELD_NAME = "round_robin";

  static final String RING_HASH_FIELD_NAME = "ring_hash_experimental";
  static final String MIN_RING_SIZE_FIELD_NAME = "minRingSize";
  static final String MAX_RING_SIZE_FIELD_NAME = "maxRingSize";

  static final String LEAST_REQUEST_FIELD_NAME = "least_request_experimental";
  static final String CHOICE_COUNT_FIELD_NAME = "choiceCount";

  static final String WRR_LOCALITY_FIELD_NAME = "wrr_locality_experimental";
  static final String CHILD_POLICY_FIELD = "childPolicy";

  /**
   * Factory method for creating a new {link LoadBalancerConfigConverter} for a given xDS {@link
   * Cluster}.
   *
   * @throws ResourceInvalidException If the {@link Cluster} has an invalid LB configuration.
   */
  static ImmutableMap<String, ?> newConfig(Cluster cluster, boolean enableLeastRequest)
      throws ResourceInvalidException {
    switch (cluster.getLbPolicy()) {
      case RING_HASH:
        return newRingHashConfig(cluster);
      case ROUND_ROBIN:
        return newWrrLocalityConfig(newRoundRobinConfig());
      case LEAST_REQUEST:
        if (enableLeastRequest) {
          return newWrrLocalityConfig(newLeastRequestConfig(cluster));
        }
        break;
      default:
    }
    throw new ResourceInvalidException(
        "Cluster " + cluster.getName() + ": unsupported lb policy: " + cluster.getLbPolicy());
  }

  private static ImmutableMap<String, ?> newWrrLocalityConfig(
      ImmutableMap<String, ?> childConfig) {
    return ImmutableMap.<String, Object>builder().put(WRR_LOCALITY_FIELD_NAME,
        ImmutableMap.of(CHILD_POLICY_FIELD, ImmutableList.of(childConfig))).build();
  }

  // Builds an empty configuration for round robin (it is not configurable).
  private static ImmutableMap<String, ?> newRoundRobinConfig() {
    return ImmutableMap.of(ROUND_ROBIN_FIELD_NAME, ImmutableMap.of());
  }

  // Builds a ring hash config and validates the hash function selection.
  private static ImmutableMap<String, ?> newRingHashConfig(Cluster cluster)
      throws ResourceInvalidException {
    RingHashLbConfig lbConfig = cluster.getRingHashLbConfig();

    // The hash function needs to be validated here as it is not exposed in the returned
    // configuration for later validation.
    if (lbConfig.getHashFunction() != RingHashLbConfig.HashFunction.XX_HASH) {
      throw new ResourceInvalidException(
          "Cluster " + cluster.getName() + ": invalid ring hash function: " + lbConfig);
    }

    ImmutableMap.Builder<String, Object> configBuilder = ImmutableMap.builder();
    if (lbConfig.hasMinimumRingSize()) {
      configBuilder.put(MIN_RING_SIZE_FIELD_NAME,
          ((Long) lbConfig.getMinimumRingSize().getValue()).doubleValue());
    }
    if (lbConfig.hasMaximumRingSize()) {
      configBuilder.put(MAX_RING_SIZE_FIELD_NAME,
          ((Long) lbConfig.getMaximumRingSize().getValue()).doubleValue());
    }
    return ImmutableMap.of(RING_HASH_FIELD_NAME, configBuilder.buildOrThrow());
  }

  // Builds a new least request config.
  private static ImmutableMap<String, ?> newLeastRequestConfig(Cluster cluster) {
    LeastRequestLbConfig lbConfig = cluster.getLeastRequestLbConfig();

    ImmutableMap.Builder<String, Object> configBuilder = ImmutableMap.builder();
    if (lbConfig.hasChoiceCount()) {
      configBuilder.put(CHOICE_COUNT_FIELD_NAME,
          ((Integer) lbConfig.getChoiceCount().getValue()).doubleValue());
    }
    return ImmutableMap.of(LEAST_REQUEST_FIELD_NAME, configBuilder.buildOrThrow());
  }
}
