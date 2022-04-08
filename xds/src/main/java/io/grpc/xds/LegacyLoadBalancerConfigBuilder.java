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

import com.google.common.collect.ImmutableMap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LeastRequestLbConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.RingHashLbConfig;
import io.grpc.xds.ClientXdsClient.ResourceInvalidException;
import java.util.Map;

/**
 * Builds a JSON LB configuration based on the old style of using the xDS Cluster proto message. The
 * lb_policy field is used to select the policy and configuration is extracted from various policy
 * specific fields in Cluster.
 */
abstract class LegacyLoadBalancerConfigBuilder {

  /**
   * Factory method for creating a new {link LoadBalancerConfigConverter} for a given xDS {@link
   * Cluster}.
   *
   * @throws ResourceInvalidException If the {@link Cluster} has an invalid LB configuration.
   */
  static LegacyLoadBalancerConfigBuilder forCluster(Cluster cluster, boolean enableLeastRequest)
      throws ResourceInvalidException {
    switch (cluster.getLbPolicy()) {
      case ROUND_ROBIN:
        return new RoundRobinLoadBalancerConfigBuilder();
      case RING_HASH:
        return new RingHashLoadBalancerConfigBuilder(cluster);
      case LEAST_REQUEST:
        if (enableLeastRequest) {
          return new LeastRequestLoadBalancerConfigBuilder(cluster);
        }
        break;
      default:
    }
    throw new ResourceInvalidException(
        "Cluster " + cluster.getName() + ": unsupported lb policy: " + cluster.getLbPolicy());
  }

  /**
   * Builds the configuration {@link Map} based on the configuration data from the provider {@code
   * Cluster}.
   */
  abstract ImmutableMap<String, ?> build();

  /**
   * Round robin does not support any configuration, a null value will always be built.
   */
  static class RoundRobinLoadBalancerConfigBuilder extends LegacyLoadBalancerConfigBuilder {

    static final String LB_NAME_FIELD = "round_robin";

    @Override
    ImmutableMap<String, ?> build() {
      return ImmutableMap.of(LB_NAME_FIELD, ImmutableMap.of());
    }
  }

  /**
   * Builds a load balancer config for the {@link RingHashLoadBalancerProvider}.
   */
  static class RingHashLoadBalancerConfigBuilder extends LegacyLoadBalancerConfigBuilder {

    static final long DEFAULT_RING_HASH_LB_POLICY_MIN_RING_SIZE = 1024L;
    static final long DEFAULT_RING_HASH_LB_POLICY_MAX_RING_SIZE = 8 * 1024 * 1024L;
    static final long MAX_RING_HASH_LB_POLICY_RING_SIZE = 8 * 1024 * 1024L;

    static final String LB_NAME_FIELD = "ring_hash_experimental";
    static final String MIN_RING_SIZE_FIELD_NAME = "minRingSize";
    static final String MAX_RING_SIZE_FIELD_NAME = "maxRingSize";

    private final Long minRingSize;
    private final Long maxRingSize;

    RingHashLoadBalancerConfigBuilder(Cluster cluster) throws ResourceInvalidException {
      RingHashLbConfig lbConfig = cluster.getRingHashLbConfig();
      minRingSize = lbConfig.hasMinimumRingSize() ? lbConfig.getMinimumRingSize().getValue()
          : DEFAULT_RING_HASH_LB_POLICY_MIN_RING_SIZE;
      maxRingSize = lbConfig.hasMaximumRingSize() ? lbConfig.getMaximumRingSize().getValue()
          : DEFAULT_RING_HASH_LB_POLICY_MAX_RING_SIZE;
      if (lbConfig.getHashFunction() != RingHashLbConfig.HashFunction.XX_HASH
          || minRingSize > maxRingSize || maxRingSize > MAX_RING_HASH_LB_POLICY_RING_SIZE) {
        throw new ResourceInvalidException(
            "Cluster " + cluster.getName() + ": invalid ring_hash_lb_config: " + lbConfig);
      }
    }

    @Override
    ImmutableMap<String, ?> build() {
      ImmutableMap.Builder<String, Object> configBuilder = ImmutableMap.builder();
      configBuilder.put(MIN_RING_SIZE_FIELD_NAME, minRingSize.doubleValue());
      configBuilder.put(MAX_RING_SIZE_FIELD_NAME, maxRingSize.doubleValue());
      return ImmutableMap.of(LB_NAME_FIELD, configBuilder.build());
    }
  }

  static class LeastRequestLoadBalancerConfigBuilder extends LegacyLoadBalancerConfigBuilder {

    static final long DEFAULT_LEAST_REQUEST_CHOICE_COUNT = 2L;

    static final String LB_NAME_FIELD = "least_request_experimental";
    static final String CHOICE_COUNT_FIELD_NAME = "choiceCount";

    private final Long choiceCount;

    LeastRequestLoadBalancerConfigBuilder(Cluster cluster) throws ResourceInvalidException {
      LeastRequestLbConfig lbConfig = cluster.getLeastRequestLbConfig();
      choiceCount = lbConfig.hasChoiceCount() ? lbConfig.getChoiceCount().getValue()
          : DEFAULT_LEAST_REQUEST_CHOICE_COUNT;
      if (choiceCount < DEFAULT_LEAST_REQUEST_CHOICE_COUNT) {
        throw new ResourceInvalidException(
            "Cluster " + cluster.getName() + ": invalid least_request_lb_config: " + lbConfig);
      }
    }

    @Override
    ImmutableMap<String, ?> build() {
      ImmutableMap.Builder<String, Object> configBuilder = ImmutableMap.builder();
      configBuilder.put(CHOICE_COUNT_FIELD_NAME, choiceCount.doubleValue());
      return ImmutableMap.of(LB_NAME_FIELD, configBuilder.build());
    }
  }
}
