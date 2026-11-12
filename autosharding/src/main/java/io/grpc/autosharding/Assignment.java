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
import com.google.common.primitives.UnsignedBytes;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * An immutable, validated assignment from the sharding service: slices sorted by start key that
 * together cover the whole keyspace, from the empty key to infinity, without gaps or overlaps.
 * Key ranges without a usable slice from the server are slices with no endpoints. Produced only
 * by {@link AssignmentParser}; see gRFC A119, "Contract of the AutoshardingClient".
 */
@Immutable
final class Assignment {

  /**
   * A single contiguous key range and the endpoints assigned to it. Its key arrays are shared
   * rather than copied, and must not be modified.
   */
  @Immutable
  @SuppressWarnings("Immutable") // The key arrays are never modified.
  static final class Slice {
    /** Orders slices by start key, compared as unsigned bytes. */
    static final Comparator<Slice> START_KEY_ORDER =
        (a, b) -> UnsignedBytes.lexicographicalComparator().compare(a.startKey, b.startKey);

    private final byte[] startKey;
    @Nullable private final byte[] endKey;
    private final ImmutableList<Integer> endpoints;
    private final boolean fallbackDisabled;

    /**
     * Constructs a {@link Slice} that may fall back.
     *
     * @param startKey the inclusive start key of the range
     * @param endKey the exclusive end key of the range, or {@code null} for the infinity
     *     sentinel covering the largest allowed key
     * @param endpoints indices into {@link Assignment#getEndpointNames()} assigned to this range
     */
    Slice(byte[] startKey, @Nullable byte[] endKey, List<Integer> endpoints) {
      this(startKey, endKey, endpoints, false);
    }

    /**
     * Constructs a {@link Slice}.
     *
     * @param fallbackDisabled whether picks for this range must fail rather than fall back; set
     *     when the server assigned the range no endpoints, never for a filled gap
     */
    Slice(
        byte[] startKey,
        @Nullable byte[] endKey,
        List<Integer> endpoints,
        boolean fallbackDisabled) {
      this.startKey = checkNotNull(startKey, "startKey");
      this.endKey = endKey;
      this.endpoints = ImmutableList.copyOf(checkNotNull(endpoints, "endpoints"));
      this.fallbackDisabled = fallbackDisabled;
    }

    byte[] getStartKey() {
      return startKey;
    }

    @Nullable
    byte[] getEndKey() {
      return endKey;
    }

    ImmutableList<Integer> getEndpoints() {
      return endpoints;
    }

    boolean isFallbackDisabled() {
      return fallbackDisabled;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("startKey", BaseEncoding.base16().encode(startKey))
          .add("endKey", endKey == null ? "inf" : BaseEncoding.base16().encode(endKey))
          .add("endpoints", endpoints)
          .add("fallbackDisabled", fallbackDisabled)
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
