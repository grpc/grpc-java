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

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.primitives.UnsignedBytes;
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
  private static final Comparator<byte[]> BYTE_ARRAY_COMPARATOR =
      UnsignedBytes.lexicographicalComparator();

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
  private ResolvedAddresses filterEndpoints(ResolvedAddresses resolvedAddresses, long subsetSize) {
    // configured subset sizes in the range [Integer.MAX_VALUE, Long.MAX_VALUE] will always fall
    // into this if statement due to collection indexing limitations in JVM
    if (subsetSize >= resolvedAddresses.getAddresses().size()) {
      return resolvedAddresses;
    }

    ArrayList<EndpointWithHash> endpointWithHashList =
        new ArrayList<>(resolvedAddresses.getAddresses().size());

    for (EquivalentAddressGroup addressGroup : resolvedAddresses.getAddresses()) {
      endpointWithHashList.add(
          new EndpointWithHash(
              addressGroup,
              hashFunc.hashString(
                  addressGroup.getAddresses().get(0).toString(),
                  StandardCharsets.UTF_8)));
    }

    Collections.sort(endpointWithHashList, new HashAddressComparator());

    // array is constructed for subset sizes in range [0, Integer.MAX_VALUE), therefore casting
    // from long to int is not going to overflow here
    ArrayList<EquivalentAddressGroup> addressGroups = new ArrayList<>((int) subsetSize);

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
    public final HashCode hashCode;

    public EndpointWithHash(EquivalentAddressGroup addressGroup, HashCode hashCode) {
      this.addressGroup = addressGroup;
      this.hashCode = hashCode;
    }
  }

  private static final class HashAddressComparator implements Comparator<EndpointWithHash> {
    @Override
    public int compare(EndpointWithHash lhs, EndpointWithHash rhs) {
      return BYTE_ARRAY_COMPARATOR.compare(lhs.hashCode.asBytes(), rhs.hashCode.asBytes());
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
