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

import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbPolicy;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LeastRequestLbConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.RingHashLbConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.RingHashLbConfig.HashFunction;
import io.grpc.internal.JsonUtil;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.xds.ClientXdsClient.ResourceInvalidException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit test for {@link LegacyLoadBalancerConfigFactory}.
 */
@RunWith(JUnit4.class)
public class LegacyLoadBalancerConfigFactoryTest {

  @Test
  public void roundRobin() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder().setLbPolicy(LbPolicy.ROUND_ROBIN).build();

    LbConfig lbConfig = ServiceConfigUtil.unwrapLoadBalancingConfig(
        LegacyLoadBalancerConfigFactory.newConfig(cluster, true));

    assertThat(lbConfig.getPolicyName()).isEqualTo("round_robin");
    assertThat(lbConfig.getRawConfigValue()).isEmpty();
  }

  @Test
  public void ringHash() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder().setLbPolicy(LbPolicy.RING_HASH).setRingHashLbConfig(
        RingHashLbConfig.newBuilder()
            .setMinimumRingSize(UInt64Value.newBuilder().setValue(1).build())
            .setMaximumRingSize(UInt64Value.newBuilder().setValue(2).build()).build()).build();

    LbConfig lbConfig = ServiceConfigUtil.unwrapLoadBalancingConfig(
        LegacyLoadBalancerConfigFactory.newConfig(cluster, true));

    assertThat(lbConfig.getPolicyName()).isEqualTo("ring_hash_experimental");
    assertThat(JsonUtil.getNumberAsLong(lbConfig.getRawConfigValue(), "minRingSize")).isEqualTo(1);
    assertThat(JsonUtil.getNumberAsLong(lbConfig.getRawConfigValue(), "maxRingSize")).isEqualTo(2);
  }

  @Test
  public void ringHash_invalidHash() throws ResourceInvalidException {
    Cluster cluster = Cluster.newBuilder().setLbPolicy(LbPolicy.RING_HASH).setRingHashLbConfig(
        RingHashLbConfig.newBuilder().setHashFunction(HashFunction.MURMUR_HASH_2)).build();

    try {
      ServiceConfigUtil.unwrapLoadBalancingConfig(
          LegacyLoadBalancerConfigFactory.newConfig(cluster, true));
    } catch (ResourceInvalidException e) {
      assertThat(e).hasMessageThat().contains("invalid ring hash function");
      return;
    }
    fail("ResourceInvalidException not thrown");
  }

  @Test
  public void leastRequest() throws ResourceInvalidException {
    System.setProperty("io.grpc.xds.experimentalEnableLeastRequest", "true");

    Cluster cluster = Cluster.newBuilder().setLbPolicy(LbPolicy.LEAST_REQUEST)
        .setLeastRequestLbConfig(LeastRequestLbConfig.newBuilder()
            .setChoiceCount(UInt32Value.newBuilder().setValue(10).build())).build();

    LbConfig lbConfig = ServiceConfigUtil.unwrapLoadBalancingConfig(
        LegacyLoadBalancerConfigFactory.newConfig(cluster, true));

    assertThat(lbConfig.getPolicyName()).isEqualTo("least_request_experimental");
    assertThat(JsonUtil.getNumberAsLong(lbConfig.getRawConfigValue(), "choiceCount")).isEqualTo(10);
  }


  @Test
  public void leastRequest_notEnabled() throws ResourceInvalidException {
    System.setProperty("io.grpc.xds.experimentalEnableLeastRequest", "false");

    Cluster cluster = Cluster.newBuilder().setLbPolicy(LbPolicy.LEAST_REQUEST).build();

    try {
      ServiceConfigUtil.unwrapLoadBalancingConfig(
          LegacyLoadBalancerConfigFactory.newConfig(cluster, false));
    } catch (ResourceInvalidException e) {
      assertThat(e).hasMessageThat().contains("unsupported lb policy");
      return;
    }
    fail("ResourceInvalidException not thrown");
  }
}
