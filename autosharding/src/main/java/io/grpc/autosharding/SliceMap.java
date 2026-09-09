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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;

/**
 * An immutable lookup structure mapping application routing keys to slice indices.
 *
 * <p>The assignment provider guarantees that the assignment is pre-validated, gap-free, 
 * non-overlapping, and covers the entire keyspace {@code ["" .. inf)}.
 * <ul>
 *   <li>The first slice's {@code startKey} is expected to be {@code new byte[0]} ({@code ""}).</li>
 *   <li>Unassigned key ranges (gaps) returned by the autosharding server are filled as slice
 *       entries with an empty {@code endpoints} list.</li>
 *   <li>Endpoint indices in {@link SliceEntry#getEndpoints()} and {@link #getFallbackPool()}
 *       are non-negative indices corresponding 1:1 to the endpoint snapshot list in
 *       {@link AutoShardingPicker}.</li>
 * </ul>
 *
 * <p>Behavior on Invalid or Edge-case Inputs:
 * <ul>
 *   <li>Empty slices list: {@link #lookup(byte[])} returns {@code -1}, allowing
 *       {@link AutoShardingPicker} to fall back to the fallback pool or fail with UNAVAILABLE.</li>
 *   <li>Key smaller than first slice start key: {@link #lookup(byte[])} returns {@code -1}
 *       if the first slice's {@code startKey} is not {@code ""} and the key precedes it.</li>
 *   <li>Null key: Treated as an empty byte array ({@code new byte[0]}).</li>
 *   <li>Unsorted slices: The constructor automatically sorts slices lexicographically
 *       using unsigned byte comparison.</li>
 *   <li>Null constructor arguments: Throws {@link NullPointerException} if {@code slices},
 *       {@code fallbackPool}, {@code startKey}, or {@code endpoints} is {@code null}.</li>
 * </ul>
 */
final class SliceMap {

  /**
   * Represents a single key-range slice mapping to endpoint indices in the picker.
   */
  static final class SliceEntry {
    private final byte[] startKey;
    private final ImmutableList<Integer> endpoints;

    /**
     * Constructs a {@link SliceEntry}.
     *
     * @param startKey the inclusive start key of the slice
     * @param endpoints the list of endpoint indices assigned to this slice
     */
    SliceEntry(byte[] startKey, List<Integer> endpoints) {
      this.startKey = checkNotNull(startKey, "startKey").clone();
      this.endpoints = ImmutableList.copyOf(checkNotNull(endpoints, "endpoints"));
    }

    byte[] getStartKey() {
      return startKey;
    }

    ImmutableList<Integer> getEndpoints() {
      return endpoints;
    }
  }

  private static final Comparator<byte[]> UNSIGNED_BYTES_COMPARATOR =
      UnsignedBytes.lexicographicalComparator();
  private static final byte[] EMPTY_BYTES = new byte[0];

  private final ImmutableList<SliceEntry> slices;
  private final ImmutableList<Integer> fallbackPool;
  private final long generation;

  /**
   * Constructs an immutable {@link SliceMap}.
   *
   * @param slices the pre-validated list of key-range slice entries
   * @param fallbackPool the list of all available endpoint indices for fallback routing
   * @param generation the snapshot generation number from the assignment
   */
  SliceMap(List<SliceEntry> slices, List<Integer> fallbackPool, long generation) {
    List<SliceEntry> sortedSlices = new ArrayList<>(checkNotNull(slices, "slices"));
    sortedSlices.sort(
        (e1, e2) -> UNSIGNED_BYTES_COMPARATOR.compare(e1.getStartKey(), e2.getStartKey()));
    this.slices = ImmutableList.copyOf(sortedSlices);
    this.fallbackPool = ImmutableList.copyOf(checkNotNull(fallbackPool, "fallbackPool"));
    this.generation = generation;
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

  long getGeneration() {
    return generation;
  }
}
