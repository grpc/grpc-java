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

import com.google.cloud.autosharding.v1.AssignmentChunk;
import com.google.cloud.autosharding.v1.EndpointState;
import com.google.cloud.autosharding.v1.PerSliceEndpointState;
import com.google.cloud.autosharding.v1.SliceAssignment;
import com.google.common.collect.ImmutableList;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.UnsignedBytes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Combines the {@link AssignmentChunk} messages of a single logical assignment into a sorted,
 * contiguous and gap-free {@link Assignment}.
 *
 * <p>Validation follows gRFC A119, "Handling assignments from the Autosharding server". A slice
 * is usable only if all of the following hold:
 *
 * <ul>
 *   <li>its {@code SliceAssignment} actually carries a {@code slice};
 *   <li>its {@code startKey} is strictly less than its {@code endKey}, or it has no
 *       {@code endKey} and so runs to the end of the keyspace;
 *   <li>every endpoint index it references is valid once the endpoint names from all chunks are
 *       combined in chunk order;
 *   <li>its key range overlaps no other slice; when slices do overlap, all of them are dropped,
 *       since there is no basis for preferring one over another.
 * </ul>
 *
 * <p>A slice that fails any of these is <em>dropped and treated as a gap</em> rather than
 * invalidating the whole assignment. Gaps, whether they came from the server or from a dropped
 * slice, are filled with slices containing no endpoints, so that RPCs matching them either fall
 * back (when fallback is enabled) or fail.
 */
final class AssignmentParser {

  /**
   * The outcome of parsing one logical assignment.
   *
   * <p>Maps onto the three non-stale rows of the outcome table in gRFC A119, "Handling
   * assignments from the Autosharding server":
   *
   * <ul>
   *   <li>every slice usable: {@link #assignment} set, {@link #errorMessage} null;
   *   <li>some slices dropped but at least one kept: both set;
   *   <li>no usable slice, including none received at all: {@link #assignment} null, {@link
   *       #errorMessage} set.
   * </ul>
   */
  static final class Result {
    /** The assignment to hand to the LB policy, or null if no usable slice remained. */
    @Nullable final Assignment assignment;

    /**
     * Describes the slices that were dropped, suitable for the {@code error_message} of an
     * {@code AssignmentAck}. Null when every slice was usable.
     */
    @Nullable final String errorMessage;

    private Result(@Nullable Assignment assignment, @Nullable String errorMessage) {
      this.assignment = assignment;
      this.errorMessage = errorMessage;
    }
  }

  /**
   * The limit on {@code AssignmentAck.error_message}, from {@code autosharding.proto}: "The
   * length of this field MUST NOT exceed 512 characters".
   */
  private static final int MAX_ERROR_MESSAGE_CHARS = 512;

  /**
   * How much of a key to hex-encode into a description. Keys may be up to 512 bytes, and only
   * the leading bytes are needed to tell one slice from another in a log.
   */
  private static final int MAX_ENCODED_KEY_BYTES = 8;

  private static final String SEPARATOR = "; ";

  private static final Comparator<byte[]> UNSIGNED_BYTES_COMPARATOR =
      UnsignedBytes.lexicographicalComparator();
  private static final byte[] EMPTY_BYTES = new byte[0];

  private AssignmentParser() {}

  /**
   * Parses and validates the buffered chunks of a single logical assignment.
   *
   * <p>An assignment is unusable when no usable slice remains, whether because every slice was
   * dropped or because it carried none.
   *
   * @param chunks the chunks received since the last {@code AssignmentMetadata}, in the order
   *     they were received
   */
  static Result parse(List<AssignmentChunk> chunks) {
    checkNotNull(chunks, "chunks");

    ImmutableList<String> endpointNames = combineEndpointNames(chunks);
    List<String> dropped = new ArrayList<>();
    List<Assignment.Slice> slices = combineSlices(chunks, endpointNames.size(), dropped);

    slices.sort(
        (s1, s2) -> UNSIGNED_BYTES_COMPARATOR.compare(s1.getStartKey(), s2.getStartKey()));
    slices = dropOverlaps(slices, dropped);

    if (slices.isEmpty()) {
      return new Result(
          null, dropped.isEmpty() ? "assignment contains no slices" : describe(dropped));
    }
    String errorMessage = dropped.isEmpty() ? null : describe(dropped);
    return new Result(new Assignment(fillGaps(slices), endpointNames), errorMessage);
  }

  /**
   * Concatenates the endpoint names across all chunks, in chunk order. Slice endpoint indices
   * are defined against this combined list.
   */
  private static ImmutableList<String> combineEndpointNames(List<AssignmentChunk> chunks) {
    ImmutableList.Builder<String> names = ImmutableList.builder();
    for (AssignmentChunk chunk : chunks) {
      for (EndpointState endpointState : chunk.getEndpointsList()) {
        names.add(endpointState.getEndpoint());
      }
    }
    return names.build();
  }

  /**
   * Concatenates the slice assignments across all chunks, dropping any whose key range is
   * inverted or whose endpoint indices are out of range. Slice assignments may appear in any
   * order across chunks.
   *
   * @param dropped collects a description of each slice that was dropped
   */
  private static List<Assignment.Slice> combineSlices(
      List<AssignmentChunk> chunks, int endpointCount, List<String> dropped) {
    List<Assignment.Slice> slices = new ArrayList<>();
    for (AssignmentChunk chunk : chunks) {
      for (SliceAssignment sliceAssignment : chunk.getSliceAssignmentsList()) {
        if (!sliceAssignment.hasSlice()) {
          // The default Slice reads as ["", inf), which would claim or overlap the whole keyspace.
          dropped.add("slice assignment has no slice");
          continue;
        }
        com.google.cloud.autosharding.v1.Slice slice = sliceAssignment.getSlice();
        byte[] startKey = slice.getStartKey().toByteArray();
        byte[] endKey = slice.hasEndKey() ? slice.getEndKey().toByteArray() : null;

        if (endKey != null) {
          int keyOrder = UNSIGNED_BYTES_COMPARATOR.compare(startKey, endKey);
          if (keyOrder > 0) {
            dropped.add(
                String.format(
                    "slice has start_key %s greater than end_key %s",
                    encode(startKey), encode(endKey)));
            continue;
          }
          if (keyOrder == 0) {
            // end_key is exclusive, so [k, k) is the empty range rather than the single key k.
            // A server wanting to assign one key sends [k, k+1), i.e. an end_key of k with a
            // trailing 0x00. Dropping this cannot open a gap, because it covered nothing.
            dropped.add(
                String.format("slice [%s, %s) is empty", encode(startKey), encode(endKey)));
            continue;
          }
        }

        List<Integer> endpoints = new ArrayList<>(sliceAssignment.getEndpointsCount());
        String indexProblem = null;
        for (PerSliceEndpointState perSlice : sliceAssignment.getEndpointsList()) {
          int index = perSlice.getEndpointIndex();
          if (index < 0 || index >= endpointCount) {
            indexProblem =
                String.format(
                    "slice starting at %s references out-of-range endpoint index %s"
                        + " (assignment has %s endpoints)",
                    encode(startKey), index, endpointCount);
            break;
          }
          endpoints.add(index);
        }
        if (indexProblem != null) {
          dropped.add(indexProblem);
          continue;
        }
        slices.add(new Assignment.Slice(startKey, endKey, endpoints));
      }
    }
    return slices;
  }

  /**
   * Returns the slices of {@code sorted} that overlap no other slice.
   *
   * <p>When slices overlap, every one of them is dropped. The server has told us two different
   * things about the same key and there is no basis for preferring either, so the keys they cover
   * become a gap.
   *
   * @param sorted slices in ascending {@code startKey} order
   * @param dropped collects a description of each slice that was dropped
   */
  private static List<Assignment.Slice> dropOverlaps(
      List<Assignment.Slice> sorted, List<String> dropped) {
    List<Assignment.Slice> kept = new ArrayList<>(sorted.size());
    int index = 0;
    while (index < sorted.size()) {
      // Extend a run of slices for as long as the keys covered so far reach into the next one.
      // Every slice that joins overlaps some earlier member, and the first two overlap directly,
      // so a run longer than one slice consists entirely of slices that overlap something.
      byte[] runEndKey = sorted.get(index).getEndKey();
      int end = index + 1;
      while (end < sorted.size() && reaches(runEndKey, sorted.get(end).getStartKey())) {
        byte[] endKey = sorted.get(end).getEndKey();
        if (endKey == null || UNSIGNED_BYTES_COMPARATOR.compare(endKey, runEndKey) > 0) {
          runEndKey = endKey;
        }
        end++;
      }

      if (end - index == 1) {
        kept.add(sorted.get(index));
      } else {
        for (Assignment.Slice slice : sorted.subList(index, end)) {
          dropped.add(
              String.format(
                  "slice [%s, %s) overlaps another slice",
                  encode(slice.getStartKey()), encode(slice.getEndKey())));
        }
      }
      index = end;
    }
    return kept;
  }

  /**
   * Returns whether a range ending at {@code endKey} covers {@code startKey}, which is known not
   * to precede it. A null {@code endKey} runs to the end of the keyspace and so covers everything.
   */
  private static boolean reaches(@Nullable byte[] endKey, byte[] startKey) {
    return endKey == null || UNSIGNED_BYTES_COMPARATOR.compare(endKey, startKey) > 0;
  }

  /**
   * Returns a contiguous list of slices covering {@code ["", inf)}, inserting endpoint-less
   * slices wherever the sorted input leaves a gap.
   */
  private static List<Assignment.Slice> fillGaps(List<Assignment.Slice> sorted) {
    List<Assignment.Slice> filled = new ArrayList<>(sorted.size() + 1);
    // Exclusive upper bound of the key range covered so far; null once infinity is reached.
    byte[] cursor = EMPTY_BYTES;
    for (Assignment.Slice slice : sorted) {
      if (cursor == null) {
        // Unreachable: an infinity-ended slice overlaps anything after it, so dropOverlaps()
        // drops the whole run; a kept one is always last.
        break;
      }
      if (UNSIGNED_BYTES_COMPARATOR.compare(cursor, slice.getStartKey()) < 0) {
        filled.add(new Assignment.Slice(cursor, slice.getStartKey(), ImmutableList.of()));
      }
      filled.add(slice);
      cursor = slice.getEndKey();
    }
    if (cursor != null) {
      filled.add(new Assignment.Slice(cursor, null, ImmutableList.of()));
    }
    return filled;
  }

  /**
   * Summarizes the dropped slices, reporting as many as the {@code error_message} budget of an
   * {@code AssignmentAck} allows and naming the count of those left out.
   *
   * <p>The result is sized to fit within {@link #MAX_ERROR_MESSAGE_CHARS} so that
   * {@code AutoshardingClient}'s final truncation never has to cut a description in half.
   */
  private static String describe(List<String> dropped) {
    StringBuilder message = new StringBuilder();
    int reported = 0;
    for (String problem : dropped) {
      int separator = reported == 0 ? 0 : SEPARATOR.length();
      // Leave room for the suffix that will be needed if this is where we stop.
      int reserved = andMore(dropped.size() - reported - 1).length();
      if (message.length() + separator + problem.length() + reserved
          > MAX_ERROR_MESSAGE_CHARS) {
        break;
      }
      if (reported > 0) {
        message.append(SEPARATOR);
      }
      message.append(problem);
      reported++;
    }
    if (reported == 0) {
      // Not reachable while every description is bounded, but a lone oversized one is better
      // reported in part than not at all; AutoshardingClient trims it to the limit.
      return dropped.get(0);
    }
    return message + andMore(dropped.size() - reported);
  }

  private static String andMore(int omitted) {
    return omitted == 0 ? "" : String.format("; and %s more", omitted);
  }

  /**
   * Hex-encodes a key for a human-readable description, shortening it if it is long. The
   * protocol allows keys of up to 512 bytes, which would fill the entire error message budget
   * twice over.
   */
  private static String encode(@Nullable byte[] key) {
    if (key == null) {
      return "inf";
    }
    if (key.length <= MAX_ENCODED_KEY_BYTES) {
      return BaseEncoding.base16().encode(key);
    }
    return BaseEncoding.base16().encode(key, 0, MAX_ENCODED_KEY_BYTES) + "...";
  }
}
