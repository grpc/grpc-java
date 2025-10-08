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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.primitives.Ints;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Random;


/**
 * Wraps a child {@code LoadBalancer}, separating the total set of backends into smaller subsets for
 * the child balancer to balance across.
 *
 * <p>This implements random subsetting gRFC:
 * https://https://github.com/grpc/proposal/blob/master/A68-random-subsetting.md
 */
final class RandomSubsettingLoadBalancer extends LoadBalancer {
  private final GracefulSwitchLoadBalancer switchLb;
  private final HashFunction hashFunc;

  public RandomSubsettingLoadBalancer(Helper helper) {
    switchLb = new GracefulSwitchLoadBalancer(checkNotNull(helper, "helper"));
    int seed = new Random().nextInt();
    hashFunc = Hashing.murmur3_128(seed);
  }

  @Override
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    RandomSubsettingLoadBalancerConfig config =
        (RandomSubsettingLoadBalancerConfig)
            resolvedAddresses.getLoadBalancingPolicyConfig();

    ResolvedAddresses subsetAddresses = filterEndpoints(resolvedAddresses, config.subsetSize);

    return switchLb.acceptResolvedAddresses(
        subsetAddresses.toBuilder()
            .setLoadBalancingPolicyConfig(config.childConfig)
            .build());
  }

  // implements the subsetting algorithm, as described in A68:
  // https://github.com/grpc/proposal/pull/423
  private ResolvedAddresses filterEndpoints(ResolvedAddresses resolvedAddresses, int subsetSize) {
    if (subsetSize >= resolvedAddresses.getAddresses().size()) {
      return resolvedAddresses;
    }

    ArrayList<EndpointWithHash> endpointWithHashList =
        new ArrayList<>(resolvedAddresses.getAddresses().size());

    for (EquivalentAddressGroup addressGroup : resolvedAddresses.getAddresses()) {
      HashCode hashCode = hashFunc.hashString(
          addressGroup.getAddresses().get(0).toString(),
          StandardCharsets.UTF_8);
      endpointWithHashList.add(new EndpointWithHash(addressGroup, hashCode.asLong()));
    }

    Collections.sort(endpointWithHashList, new HashAddressComparator());

    ArrayList<EquivalentAddressGroup> addressGroups = new ArrayList<>(subsetSize);

    for (int idx = 0; idx < subsetSize; ++idx) {
      addressGroups.add(endpointWithHashList.get(idx).addressGroup);
    }

    return resolvedAddresses.toBuilder().setAddresses(addressGroups).build();
  }

  @Override
  public void handleNameResolutionError(Status error) {
    switchLb.handleNameResolutionError(error);
  }

  @Override
  public void shutdown() {
    switchLb.shutdown();
  }

  private static final class EndpointWithHash {
    public final EquivalentAddressGroup addressGroup;
    public final long hashCode;

    public EndpointWithHash(EquivalentAddressGroup addressGroup, long hashCode) {
      this.addressGroup = addressGroup;
      this.hashCode = hashCode;
    }
  }

  private static final class HashAddressComparator implements Comparator<EndpointWithHash> {
    @Override
    public int compare(EndpointWithHash lhs, EndpointWithHash rhs) {
      return Long.compare(lhs.hashCode, rhs.hashCode);
    }
  }

  public static final class RandomSubsettingLoadBalancerConfig {
    public final int subsetSize;
    public final Object childConfig;

    private RandomSubsettingLoadBalancerConfig(int subsetSize, Object childConfig) {
      this.subsetSize = subsetSize;
      this.childConfig = childConfig;
    }

    public static class Builder {
      int subsetSize;
      Object childConfig;

      public Builder setSubsetSize(long subsetSize) {
        checkArgument(subsetSize > 0L, "Subset size must be greater than 0");
        // clamping subset size to Integer.MAX_VALUE due to collection indexing limitations in JVM
        this.subsetSize = Ints.saturatedCast(subsetSize);
        return this;
      }

      public Builder setChildConfig(Object childConfig) {
        this.childConfig = checkNotNull(childConfig, "childConfig");
        return this;
      }

      public RandomSubsettingLoadBalancerConfig build() {
        checkState(subsetSize != 0L, "Subset size must be set before building the config");
        return new RandomSubsettingLoadBalancerConfig(
            subsetSize,
            checkNotNull(childConfig, "childConfig"));
      }
    }
  }
}
