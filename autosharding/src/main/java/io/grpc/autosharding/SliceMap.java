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

import com.google.common.primitives.UnsignedBytes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;

final class SliceMap {

  static final class SliceEntry {
    final byte[] startKey;
    final List<Integer> endpoints;

    SliceEntry(byte[] startKey, List<Integer> endpoints) {
      this.startKey = startKey;
      this.endpoints = Collections.unmodifiableList(new ArrayList<>(endpoints));
    }
  }

  private static final Comparator<byte[]> UNSIGNED_BYTES_COMPARATOR =
      UnsignedBytes.lexicographicalComparator();
  private static final byte[] EMPTY_BYTES = new byte[0];

  private final List<SliceEntry> slices;
  private final List<Integer> fallbackPool;
  private final long generation;

  SliceMap(List<SliceEntry> slices, List<Integer> fallbackPool, long generation) {
    List<SliceEntry> sortedSlices = new ArrayList<>(slices);
    sortedSlices.sort((e1, e2) -> UNSIGNED_BYTES_COMPARATOR.compare(e1.startKey, e2.startKey));
    this.slices = Collections.unmodifiableList(sortedSlices);
    this.fallbackPool = Collections.unmodifiableList(new ArrayList<>(fallbackPool));
    this.generation = generation;
  }

  /**
   * Looks up the matching slice index for the given key.
   * Returns -1 if slices is empty (e.g. startup/fallback case where there are no assignments)
   * or if the key is smaller than the first slice's startKey.
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
      int cmp = UNSIGNED_BYTES_COMPARATOR.compare(slices.get(mid).startKey, searchKey);

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

  List<SliceEntry> getSlices() {
    return slices;
  }

  List<Integer> getFallbackPool() {
    return fallbackPool;
  }

  long getGeneration() {
    return generation;
  }
}
