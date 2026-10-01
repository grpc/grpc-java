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

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;
import java.util.List;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * An immutable, validated, gap-free snapshot of a logical assignment received from the
 * autosharding service.
 *
 * <p>Instances are produced exclusively by {@link AssignmentParser}, which guarantees the
 * following invariants (see gRFC A119, "Contract of the AutoshardingClient"):
 * <ol>
 *   <li>The {@link #getSlices()} list covers the entire keyspace, starting at the minimum
 *       possible key (the empty byte string) and ending at the maximum possible key
 *       (infinity, represented by a {@code null} {@link Slice#getEndKey()}).</li>
 *   <li>The slices are sorted in ascending lexicographical (unsigned) order by
 *       {@link Slice#getStartKey()}.</li>
 *   <li>The partitioning is contiguous and non-overlapping: for every index {@code i} in
 *       {@code [0, N-2]}, {@code slices[i].endKey} is exactly {@code slices[i + 1].startKey}.</li>
 *   <li>Key ranges not assigned by the autosharding server are present as slices with an
 *       empty {@link Slice#getEndpoints()} list.</li>
 * </ol>
 */
@Immutable
final class Assignment {

  /** A single contiguous key range and the endpoints assigned to it. */
  @Immutable
  @SuppressWarnings("Immutable") // Defensive copies are made; arrays are never mutated.
  static final class Slice {
    private final byte[] startKey;
    @Nullable private final byte[] endKey;
    private final ImmutableList<Integer> endpoints;

    /**
     * Constructs a {@link Slice}.
     *
     * @param startKey the inclusive start key of the range
     * @param endKey the exclusive end key of the range, or {@code null} for the infinity
     *     sentinel covering the largest allowed key
     * @param endpoints indices into {@link Assignment#getEndpointNames()} assigned to this range
     */
    Slice(byte[] startKey, @Nullable byte[] endKey, List<Integer> endpoints) {
      this.startKey = checkNotNull(startKey, "startKey").clone();
      this.endKey = endKey == null ? null : endKey.clone();
      this.endpoints = ImmutableList.copyOf(checkNotNull(endpoints, "endpoints"));
    }

    byte[] getStartKey() {
      return startKey.clone();
    }

    @Nullable
    byte[] getEndKey() {
      return endKey == null ? null : endKey.clone();
    }

    ImmutableList<Integer> getEndpoints() {
      return endpoints;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("startKey", BaseEncoding.base16().encode(startKey))
          .add("endKey", endKey == null ? "inf" : BaseEncoding.base16().encode(endKey))
          .add("endpoints", endpoints)
          .toString();
    }
  }

  private final ImmutableList<Slice> slices;
  private final ImmutableList<String> endpointNames;

  /**
   * Constructs an {@link Assignment}.
   *
   * @param slices the validated, sorted, contiguous and gap-free list of key-range slices
   * @param endpointNames the complete list of endpoint names, combined across all chunks in
   *     chunk order
   */
  Assignment(List<Slice> slices, List<String> endpointNames) {
    this.slices = ImmutableList.copyOf(checkNotNull(slices, "slices"));
    this.endpointNames = ImmutableList.copyOf(checkNotNull(endpointNames, "endpointNames"));
  }

  ImmutableList<Slice> getSlices() {
    return slices;
  }

  ImmutableList<String> getEndpointNames() {
    return endpointNames;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("endpointNames", endpointNames)
        .add("slices", slices)
        .toString();
  }
}
