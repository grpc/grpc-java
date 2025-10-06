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

import io.grpc.EquivalentAddressGroup;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.tp.zah.XxHash64;
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
@Internal
public final class RandomSubsettingLoadBalancer extends LoadBalancer {
  private final GracefulSwitchLoadBalancer switchLb;
  private final XxHash64 hashFunc;

  public RandomSubsettingLoadBalancer(Helper helper) {
    switchLb = new GracefulSwitchLoadBalancer(checkNotNull(helper, "helper"));
    long seed = new Random().nextLong();
    hashFunc = new XxHash64(seed);
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
  private ResolvedAddresses filterEndpoints(ResolvedAddresses resolvedAddresses, long subsetSize) {
    // configured subset sizes in the range [Integer.MAX_VALUE, Long.MAX_VALUE] will always fall
    // into this if statement due to collection indexing limitations in JVM
    if (subsetSize >= resolvedAddresses.getAddresses().size()) {
      return resolvedAddresses;
    }

    ArrayList<EndpointWithHash> endpointWithHashList = new ArrayList<>();

    for (EquivalentAddressGroup addressGroup : resolvedAddresses.getAddresses()) {
      endpointWithHashList.add(
          new EndpointWithHash(
              addressGroup,
              hashFunc.hashAsciiString(addressGroup.getAddresses().get(0).toString())));
    }

    Collections.sort(endpointWithHashList, new HashAddressComparator());

    ArrayList<EquivalentAddressGroup> addressGroups = new ArrayList<>();

    // for loop is executed for subset sizes in range [0, Integer.MAX_VALUE), therefore indexing
    // variable is not going to overflow here
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
    public final long hash;

    public EndpointWithHash(EquivalentAddressGroup addressGroup, long hash) {
      this.addressGroup = addressGroup;
      this.hash = hash;
    }
  }

  private static final class HashAddressComparator implements Comparator<EndpointWithHash> {
    @Override
    public int compare(EndpointWithHash lhs, EndpointWithHash rhs) {
      return Long.compare(lhs.hash, rhs.hash);
    }
  }

  public static final class RandomSubsettingLoadBalancerConfig {
    public final long subsetSize;
    public final Object childConfig;

    private RandomSubsettingLoadBalancerConfig(long subsetSize, Object childConfig) {
      this.subsetSize = subsetSize;
      this.childConfig = childConfig;
    }

    public static class Builder {
      Long subsetSize;
      Object childConfig;

      public Builder setSubsetSize(Integer subsetSize) {
        checkNotNull(subsetSize, "subsetSize");
        // {@code Integer.toUnsignedLong(int)} is not part of Android API level 21, therefore doing
        // it manually
        Long subsetSizeAsLong = ((long) subsetSize) & 0xFFFFFFFFL;
        checkArgument(subsetSizeAsLong > 0L, "Subset size must be greater than 0");
        this.subsetSize = subsetSizeAsLong;
        return this;
      }

      public Builder setChildConfig(Object childConfig) {
        this.childConfig = checkNotNull(childConfig, "childConfig");
        return this;
      }

      public RandomSubsettingLoadBalancerConfig build() {
        return new RandomSubsettingLoadBalancerConfig(
            checkNotNull(subsetSize, "subsetSize"),
            checkNotNull(childConfig, "childConfig"));
      }
    }
  }
}
