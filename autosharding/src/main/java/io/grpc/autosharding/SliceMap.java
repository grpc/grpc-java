/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.autosharding;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.UnsignedBytes;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Immutable map from sharding keys to slices, looked up by binary search over the slices' start
 * keys. The slices, if any, are sorted by start key and cover the whole keyspace; their endpoint
 * indices refer to the picker's endpoint list.
 */
final class SliceMap {

  /**
   * Represents a single key-range slice mapping to endpoint indices in the picker. Its start key
   * array is shared rather than copied, and must not be modified.
   */
  static final class SliceEntry {
    private final byte[] startKey;
    private final ImmutableList<Integer> endpoints;
    private final boolean fallbackDisabled;

    /**
     * Constructs a {@link SliceEntry} that may fall back.
     *
     * @param startKey the inclusive start key of the slice
     * @param endpoints the list of endpoint indices assigned to this slice
     */
    SliceEntry(byte[] startKey, List<Integer> endpoints) {
      this(startKey, endpoints, false);
    }

    /**
     * Constructs a {@link SliceEntry}.
     *
     * @param fallbackDisabled whether picks for this slice must fail rather than fall back, as
     *     when the server assigned the slice no endpoints
     */
    SliceEntry(byte[] startKey, List<Integer> endpoints, boolean fallbackDisabled) {
      this.startKey = checkNotNull(startKey, "startKey");
      this.endpoints = ImmutableList.copyOf(checkNotNull(endpoints, "endpoints"));
      this.fallbackDisabled = fallbackDisabled;
    }

    byte[] getStartKey() {
      return startKey;
    }

    ImmutableList<Integer> getEndpoints() {
      return endpoints;
    }

    boolean isFallbackDisabled() {
      return fallbackDisabled;
    }
  }

  private static final Comparator<byte[]> UNSIGNED_BYTES_COMPARATOR =
      UnsignedBytes.lexicographicalComparator();
  private static final byte[] EMPTY_BYTES = new byte[0];

  private final ImmutableList<SliceEntry> slices;
  private final ImmutableList<Integer> fallbackPool;

  /**
   * Constructs an immutable {@link SliceMap}.
   *
   * @param slices the pre-validated list of key-range slice entries, in ascending unsigned
   *     {@code startKey} order as produced by {@link AssignmentParser}
   * @param fallbackPool the list of all available endpoint indices for fallback routing
   */
  SliceMap(List<SliceEntry> slices, List<Integer> fallbackPool) {
    this.slices = ImmutableList.copyOf(checkNotNull(slices, "slices"));
    this.fallbackPool = ImmutableList.copyOf(checkNotNull(fallbackPool, "fallbackPool"));
  }

  /**
   * Looks up the matching slice index for the given key using binary search.
   *
   * @param key the routing key to look up, or {@code null} to search with an empty byte array
   * @return the 0-based slice index in {@link #getSlices()}, or {@code -1} if {@code slices}
   *     is empty or if the key is smaller than the first slice's {@code startKey}
   */
  int lookup(@Nullable byte[] key) {
    if (slices.isEmpty()) {
      return -1;
    }
    byte[] searchKey = key != null ? key : EMPTY_BYTES;
    int low = 0;
    int high = slices.size() - 1;

    while (low <= high) {
      int mid = (low + high) >>> 1;
      int cmp = UNSIGNED_BYTES_COMPARATOR.compare(slices.get(mid).getStartKey(), searchKey);

      if (cmp < 0) {
        low = mid + 1;
      } else if (cmp > 0) {
        high = mid - 1;
      } else {
        return mid; // Exact match on startKey
      }
    }

    if (low == 0) {
      // Key is smaller than first slice's startKey
      return -1;
    }
    return low - 1;
  }

  ImmutableList<SliceEntry> getSlices() {
    return slices;
  }

  ImmutableList<Integer> getFallbackPool() {
    return fallbackPool;
  }
}
