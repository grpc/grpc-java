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

import static com.google.common.truth.Truth.assertThat;

import com.google.cloud.autosharding.v1.AssignmentChunk;
import com.google.cloud.autosharding.v1.EndpointState;
import com.google.cloud.autosharding.v1.PerSliceEndpointState;
import com.google.cloud.autosharding.v1.SliceAssignment;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link AssignmentParser}. */
@RunWith(JUnit4.class)
public class AssignmentParserTest {

  @Test
  public void parse_singleChunkCoveringWholeKeyspace() {
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addEndpoints(endpoint("host-a"))
            .addEndpoints(endpoint("host-b"))
            .addSliceAssignments(sliceAssignment("", "m", 0))
            .addSliceAssignments(sliceAssignment("m", null, 1))
            .build();

    Assignment assignment = parseFully(ImmutableList.of(chunk));

    assertThat(assignment.getEndpointNames()).containsExactly("host-a", "host-b").inOrder();
    assertThat(assignment.getSlices()).hasSize(2);
    assertSlice(assignment.getSlices().get(0), "", "m", 0);
    assertSlice(assignment.getSlices().get(1), "m", null, 1);
  }

  @Test
  public void parse_endpointNamesCombinedInChunkOrder() {
    AssignmentChunk chunk1 =
        AssignmentChunk.newBuilder()
            .addEndpoints(endpoint("host-a"))
            .addEndpoints(endpoint("host-b"))
            .build();
    AssignmentChunk chunk2 =
        AssignmentChunk.newBuilder()
            .addEndpoints(endpoint("host-c"))
            // Index 2 only resolves once chunk1's endpoints are prepended.
            .addSliceAssignments(sliceAssignment("", null, 2))
            .build();

    Assignment assignment = parseFully(ImmutableList.of(chunk1, chunk2));

    assertThat(assignment.getEndpointNames())
        .containsExactly("host-a", "host-b", "host-c")
        .inOrder();
    assertSlice(assignment.getSlices().get(0), "", null, 2);
  }

  @Test
  public void parse_slicesAcrossChunksAreSorted() {
    AssignmentChunk chunk1 =
        AssignmentChunk.newBuilder()
            .addEndpoints(endpoint("host-a"))
            .addSliceAssignments(sliceAssignment("m", null, 0))
            .build();
    AssignmentChunk chunk2 =
        AssignmentChunk.newBuilder().addSliceAssignments(sliceAssignment("", "m", 0)).build();

    Assignment assignment = parseFully(ImmutableList.of(chunk1, chunk2));

    assertThat(assignment.getSlices()).hasSize(2);
    assertSlice(assignment.getSlices().get(0), "", "m", 0);
    assertSlice(assignment.getSlices().get(1), "m", null, 0);
  }

  @Test
  public void parse_fillsLeadingGap() {
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addEndpoints(endpoint("host-a"))
            .addSliceAssignments(sliceAssignment("d", null, 0))
            .build();

    Assignment assignment = parseFully(ImmutableList.of(chunk));

    assertThat(assignment.getSlices()).hasSize(2);
    assertSlice(assignment.getSlices().get(0), "", "d");
    assertSlice(assignment.getSlices().get(1), "d", null, 0);
  }

  @Test
  public void parse_fillsTrailingGap() {
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addEndpoints(endpoint("host-a"))
            .addSliceAssignments(sliceAssignment("", "d", 0))
            .build();

    Assignment assignment = parseFully(ImmutableList.of(chunk));

    assertThat(assignment.getSlices()).hasSize(2);
    assertSlice(assignment.getSlices().get(0), "", "d", 0);
    assertSlice(assignment.getSlices().get(1), "d", null);
  }

  @Test
  public void parse_fillsInteriorGap() {
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addEndpoints(endpoint("host-a"))
            .addEndpoints(endpoint("host-b"))
            .addSliceAssignments(sliceAssignment("", "d", 0))
            .addSliceAssignments(sliceAssignment("m", null, 1))
            .build();

    Assignment assignment = parseFully(ImmutableList.of(chunk));

    assertThat(assignment.getSlices()).hasSize(3);
    assertSlice(assignment.getSlices().get(0), "", "d", 0);
    assertSlice(assignment.getSlices().get(1), "d", "m");
    assertSlice(assignment.getSlices().get(2), "m", null, 1);
  }

  @Test
  public void parse_noSlices_isRejected() {
    AssignmentParser.Result result =
        AssignmentParser.parse(ImmutableList.of(AssignmentChunk.getDefaultInstance()));

    // Treated like an assignment whose slices were all dropped: no usable slice remains.
    assertThat(result.assignment).isNull();
    assertThat(result.errorMessage).contains("no slices");
  }

  @Test
  public void parse_noChunks_isRejected() {
    AssignmentParser.Result result = AssignmentParser.parse(ImmutableList.of());

    assertThat(result.assignment).isNull();
    assertThat(result.errorMessage).contains("no slices");
  }

  @Test
  public void parse_sliceWithNoEndpoints_isPreserved() {
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addEndpoints(endpoint("host-a"))
            .addSliceAssignments(sliceAssignment("", "d"))
            .addSliceAssignments(sliceAssignment("d", null, 0))
            .build();

    Assignment assignment = parseFully(ImmutableList.of(chunk));

    assertThat(assignment.getSlices()).hasSize(2);
    assertSlice(assignment.getSlices().get(0), "", "d");
    assertSlice(assignment.getSlices().get(1), "d", null, 0);
  }

  @Test
  public void parse_multipleEndpointsPerSlice() {
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addEndpoints(endpoint("host-a"))
            .addEndpoints(endpoint("host-b"))
            .addSliceAssignments(sliceAssignment("", null, 0, 1))
            .build();

    Assignment assignment = parseFully(ImmutableList.of(chunk));

    assertSlice(assignment.getSlices().get(0), "", null, 0, 1);
  }

  @Test
  public void parse_unsignedByteOrderingIsUsed() {
    // 0x80 is negative as a signed byte but must sort after 0x01.
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addSliceAssignments(
                SliceAssignment.newBuilder()
                    .setSlice(
                        com.google.cloud.autosharding.v1.Slice.newBuilder()
                            .setStartKey(ByteString.copyFrom(new byte[] {(byte) 0x80}))))
            .addSliceAssignments(
                SliceAssignment.newBuilder()
                    .setSlice(
                        com.google.cloud.autosharding.v1.Slice.newBuilder()
                            .setStartKey(ByteString.copyFrom(new byte[] {0x01}))
                            .setEndKey(ByteString.copyFrom(new byte[] {(byte) 0x80}))))
            .build();

    Assignment assignment = parseFully(ImmutableList.of(chunk));

    // Leading gap ["", 0x01) plus the two declared slices.
    assertThat(assignment.getSlices()).hasSize(3);
    assertThat(assignment.getSlices().get(1).getStartKey()).isEqualTo(new byte[] {0x01});
    assertThat(assignment.getSlices().get(2).getStartKey()).isEqualTo(new byte[] {(byte) 0x80});
    assertThat(assignment.getSlices().get(2).getEndKey()).isNull();
  }

  @Test
  public void parse_resultingSlicesAreContiguous() {
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addEndpoints(endpoint("host-a"))
            .addSliceAssignments(sliceAssignment("b", "d", 0))
            .addSliceAssignments(sliceAssignment("k", "m", 0))
            .build();

    Assignment assignment = parseFully(ImmutableList.of(chunk));

    List<Assignment.Slice> slices = assignment.getSlices();
    assertThat(slices.get(0).getStartKey()).isEqualTo(new byte[0]);
    for (int i = 0; i + 1 < slices.size(); i++) {
      assertThat(slices.get(i).getEndKey()).isEqualTo(slices.get(i + 1).getStartKey());
    }
    assertThat(slices.get(slices.size() - 1).getEndKey()).isNull();
  }

  @Test
  public void parse_endpointIndexOutOfRange_sliceBecomesGap() {
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addEndpoints(endpoint("host-a"))
            .addSliceAssignments(sliceAssignment("", "m", 0))
            .addSliceAssignments(sliceAssignment("m", null, 5))
            .build();

    AssignmentParser.Result result = AssignmentParser.parse(ImmutableList.of(chunk));

    assertThat(result.errorMessage).contains("out-of-range endpoint index 5");
    assertThat(result.assignment.getSlices()).hasSize(2);
    assertSlice(result.assignment.getSlices().get(0), "", "m", 0);
    assertSlice(result.assignment.getSlices().get(1), "m", null);
  }

  @Test
  public void parse_negativeEndpointIndex_sliceBecomesGap() {
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addEndpoints(endpoint("host-a"))
            .addSliceAssignments(sliceAssignment("", "m", 0))
            .addSliceAssignments(sliceAssignment("m", null, -1))
            .build();

    AssignmentParser.Result result = AssignmentParser.parse(ImmutableList.of(chunk));

    assertThat(result.errorMessage).contains("out-of-range endpoint index -1");
    assertThat(result.assignment.getSlices()).hasSize(2);
    assertSlice(result.assignment.getSlices().get(1), "m", null);
  }

  @Test
  public void parse_endpointIndexOutOfRange_dropsTheWholeSliceNotJustThatEndpoint() {
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addEndpoints(endpoint("host-a"))
            .addSliceAssignments(sliceAssignment("", "m", 0))
            // Index 0 is valid, but the slice as a whole is rejected because index 5 is not.
            .addSliceAssignments(sliceAssignment("m", null, 0, 5))
            .build();

    AssignmentParser.Result result = AssignmentParser.parse(ImmutableList.of(chunk));

    assertSlice(result.assignment.getSlices().get(1), "m", null);
  }

  @Test
  public void parse_startKeyGreaterThanEndKey_sliceBecomesGap() {
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addEndpoints(endpoint("host-a"))
            .addSliceAssignments(sliceAssignment("", "m", 0))
            .addSliceAssignments(sliceAssignment("z", "n"))
            .build();

    AssignmentParser.Result result = AssignmentParser.parse(ImmutableList.of(chunk));

    assertThat(result.errorMessage).contains("greater than end_key");
    assertThat(result.assignment.getSlices()).hasSize(2);
    assertSlice(result.assignment.getSlices().get(0), "", "m", 0);
    assertSlice(result.assignment.getSlices().get(1), "m", null);
  }

  @Test
  public void parse_zeroWidthSlice_isDropped() {
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addEndpoints(endpoint("host-a"))
            .addEndpoints(endpoint("host-b"))
            .addSliceAssignments(sliceAssignment("", "m", 0))
            // start_key == end_key satisfies the gRFC's "start_key <= end_key", but the slice
            // covers no keys and would collide with the next slice's start key.
            .addSliceAssignments(sliceAssignment("m", "m", 0))
            .addSliceAssignments(sliceAssignment("m", null, 1))
            .build();

    AssignmentParser.Result result = AssignmentParser.parse(ImmutableList.of(chunk));

    assertThat(result.errorMessage).contains("is empty");
    // No gap appears where it was: its neighbours already met at "m".
    assertThat(result.assignment.getSlices()).hasSize(2);
    assertSlice(result.assignment.getSlices().get(0), "", "m", 0);
    assertSlice(result.assignment.getSlices().get(1), "m", null, 1);
  }

  /**
   * An empty range is unroutable whatever it carries, and it need not sit next to another
   * slice, so dropping it has to fall through to ordinary gap filling.
   */
  @Test
  public void parse_zeroWidthSlice_withEndpointsAndNoNeighbour_leavesNoHole() {
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addEndpoints(endpoint("host-a"))
            .addEndpoints(endpoint("host-b"))
            .addSliceAssignments(sliceAssignment("", "a", 0))
            .addSliceAssignments(sliceAssignment("m", "m", 1))
            .addSliceAssignments(sliceAssignment("z", null, 0))
            .build();

    AssignmentParser.Result result = AssignmentParser.parse(ImmutableList.of(chunk));

    assertThat(result.errorMessage).contains("is empty");
    assertThat(result.assignment.getSlices()).hasSize(3);
    assertSlice(result.assignment.getSlices().get(0), "", "a", 0);
    // ["a", "z") is one gap, not two slices meeting at "m".
    assertSlice(result.assignment.getSlices().get(1), "a", "z");
    assertSlice(result.assignment.getSlices().get(2), "z", null, 0);
  }

  @Test
  public void parse_singleKeySlice_isKept() {
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addEndpoints(endpoint("host-a"))
            // How a server actually assigns exactly one key: end_key is the successor of
            // start_key, not start_key itself.
            .addSliceAssignments(
                SliceAssignment.newBuilder()
                    .setSlice(
                        com.google.cloud.autosharding.v1.Slice.newBuilder()
                            .setStartKey(ByteString.copyFromUtf8("m"))
                            .setEndKey(ByteString.copyFrom(new byte[] {'m', 0})))
                    .addEndpoints(PerSliceEndpointState.newBuilder().setEndpointIndex(0)))
            .build();

    AssignmentParser.Result result = AssignmentParser.parse(ImmutableList.of(chunk));

    assertThat(result.errorMessage).isNull();
    assertThat(result.assignment.getSlices()).hasSize(3);
    assertThat(result.assignment.getSlices().get(1).getEndpoints()).containsExactly(0);
  }

  /**
   * The picker looks a key up by binary search over start keys, so two slices sharing one would
   * make the result depend on where the search happened to land.
   */
  @Test
  public void parse_startKeysAreUnique() {
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addEndpoints(endpoint("host-a"))
            .addSliceAssignments(sliceAssignment("", "", 0))
            .addSliceAssignments(sliceAssignment("", "m", 0))
            .addSliceAssignments(sliceAssignment("m", "m", 0))
            .addSliceAssignments(sliceAssignment("m", null, 0))
            .build();

    AssignmentParser.Result result = AssignmentParser.parse(ImmutableList.of(chunk));

    List<Assignment.Slice> slices = result.assignment.getSlices();
    for (int i = 0; i + 1 < slices.size(); i++) {
      assertThat(slices.get(i).getStartKey()).isNotEqualTo(slices.get(i + 1).getStartKey());
    }
  }

  @Test
  public void parse_overlappingSlices_bothAreDropped() {
    // There is no way to tell which of the two the server meant, so neither is used and the keys
    // they covered become a gap.
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addSliceAssignments(sliceAssignment("a", "m"))
            .addSliceAssignments(sliceAssignment("d", null))
            .build();

    AssignmentParser.Result result = AssignmentParser.parse(ImmutableList.of(chunk));

    assertThat(result.errorMessage).contains("overlaps");
    assertThat(result.assignment).isNull();
  }

  @Test
  public void parse_overlappingSlices_aSliceClearOfThemSurvives() {
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addSliceAssignments(sliceAssignment("a", "m"))
            .addSliceAssignments(sliceAssignment("d", "p"))
            .addSliceAssignments(sliceAssignment("p", "z"))
            .build();

    AssignmentParser.Result result = AssignmentParser.parse(ImmutableList.of(chunk));

    assertThat(result.errorMessage).contains("overlaps");
    // ["p", "z") abuts the overlap without entering it, so only it is kept.
    assertThat(result.assignment.getSlices()).hasSize(3);
    assertSlice(result.assignment.getSlices().get(0), "", "p");
    assertSlice(result.assignment.getSlices().get(1), "p", "z");
    assertSlice(result.assignment.getSlices().get(2), "z", null);
    assertThat(result.assignment.getSlices().get(0).getEndpoints()).isEmpty();
  }

  @Test
  public void parse_slicesOverlappingOnlyThroughAThird_areAllDropped() {
    // ["b", "c") and ["d", "e") are disjoint, but both collide with ["a", "z"), so all three go.
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addSliceAssignments(sliceAssignment("a", "z"))
            .addSliceAssignments(sliceAssignment("b", "c"))
            .addSliceAssignments(sliceAssignment("d", "e"))
            .build();

    AssignmentParser.Result result = AssignmentParser.parse(ImmutableList.of(chunk));

    assertThat(result.errorMessage).contains("overlaps");
    assertThat(result.assignment).isNull();
  }

  @Test
  public void parse_duplicateStartKeys_bothAreDropped() {
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addSliceAssignments(sliceAssignment("a", "m"))
            .addSliceAssignments(sliceAssignment("a", "z"))
            .build();

    AssignmentParser.Result result = AssignmentParser.parse(ImmutableList.of(chunk));

    assertThat(result.errorMessage).contains("overlaps");
    assertThat(result.assignment).isNull();
  }

  @Test
  public void parse_sliceExtendingToInfinityFollowedByAnother_bothAreDropped() {
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addSliceAssignments(sliceAssignment("a", null))
            .addSliceAssignments(sliceAssignment("m", null))
            .build();

    AssignmentParser.Result result = AssignmentParser.parse(ImmutableList.of(chunk));

    assertThat(result.errorMessage).contains("overlaps");
    assertThat(result.assignment).isNull();
  }

  @Test
  public void parse_everySliceInvalid_yieldsNoAssignment() {
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addEndpoints(endpoint("host-a"))
            .addSliceAssignments(sliceAssignment("", null, 1))
            .build();

    AssignmentParser.Result result = AssignmentParser.parse(ImmutableList.of(chunk));

    assertThat(result.assignment).isNull();
    assertThat(result.errorMessage).contains("out-of-range endpoint index 1");
  }

  @Test
  public void parse_manyProblems_errorMessageStaysWithinTheAckBudget() {
    AssignmentChunk.Builder chunk = AssignmentChunk.newBuilder();
    for (int i = 0; i < 40; i++) {
      // Inverted key range, so every one of them is dropped.
      chunk.addSliceAssignments(sliceAssignment("z" + i, "a"));
    }

    AssignmentParser.Result result = AssignmentParser.parse(ImmutableList.of(chunk.build()));

    assertThat(result.assignment).isNull();
    // autosharding.proto: error_message "MUST NOT exceed 512 characters".
    assertThat(result.errorMessage.length()).isAtMost(512);
    assertThat(result.errorMessage).contains("greater than end_key");
    assertThat(result.errorMessage).containsMatch("; and \\d+ more$");
  }

  @Test
  public void parse_fewProblems_allAreReported() {
    AssignmentChunk.Builder chunk = AssignmentChunk.newBuilder();
    for (String startKey : new String[] {"v", "w", "x", "y", "z"}) {
      chunk.addSliceAssignments(sliceAssignment(startKey, "a"));
    }

    AssignmentParser.Result result = AssignmentParser.parse(ImmutableList.of(chunk.build()));

    // Five short descriptions fit comfortably, so nothing is elided.
    assertThat(result.errorMessage).doesNotContain("more");
    assertThat(result.errorMessage.split("; ")).hasLength(5);
  }

  @Test
  public void parse_longKeysAreShortenedInTheErrorMessage() {
    byte[] longKey = new byte[512];
    Arrays.fill(longKey, (byte) 0xAB);
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addSliceAssignments(
                SliceAssignment.newBuilder()
                    .setSlice(
                        com.google.cloud.autosharding.v1.Slice.newBuilder()
                            .setStartKey(ByteString.copyFrom(longKey))
                            .setEndKey(ByteString.copyFromUtf8("a"))))
            .build();

    AssignmentParser.Result result = AssignmentParser.parse(ImmutableList.of(chunk));

    // Hex-encoding 512 bytes in full would be 1024 characters on its own.
    assertThat(result.errorMessage.length()).isAtMost(512);
    assertThat(result.errorMessage).contains("...");
  }

  @Test
  public void parse_sliceAssignmentWithoutSlice_isDroppedNotTreatedAsWholeKeyspace() {
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addEndpoints(endpoint("host-a"))
            .addEndpoints(endpoint("host-b"))
            .addSliceAssignments(sliceAssignment("", "m", 0))
            // No slice set; its default would read as ["", inf) and overlap everything.
            .addSliceAssignments(
                SliceAssignment.newBuilder()
                    .addEndpoints(PerSliceEndpointState.newBuilder().setEndpointIndex(1)))
            .build();

    AssignmentParser.Result result = AssignmentParser.parse(ImmutableList.of(chunk));

    assertThat(result.errorMessage).contains("no slice");
    assertThat(result.assignment).isNotNull();
    assertSlice(result.assignment.getSlices().get(0), "", "m", 0);
    assertSlice(result.assignment.getSlices().get(1), "m", null);
  }

  @Test
  public void parse_onlySliceAssignmentWithoutSlice_isUnusable() {
    AssignmentChunk chunk =
        AssignmentChunk.newBuilder()
            .addEndpoints(endpoint("host-a"))
            .addSliceAssignments(
                SliceAssignment.newBuilder()
                    .addEndpoints(PerSliceEndpointState.newBuilder().setEndpointIndex(0)))
            .build();

    AssignmentParser.Result result = AssignmentParser.parse(ImmutableList.of(chunk));

    assertThat(result.assignment).isNull();
    assertThat(result.errorMessage).contains("no slice");
  }

  @Test
  public void slice_mutatingReturnedKeys_doesNotAffectTheSlice() {
    Assignment.Slice slice =
        new Assignment.Slice(
            "a".getBytes(StandardCharsets.UTF_8),
            "m".getBytes(StandardCharsets.UTF_8),
            ImmutableList.of());

    slice.getStartKey()[0] = 'x';
    slice.getEndKey()[0] = 'y';

    assertSlice(slice, "a", "m");
  }

  /** Parses chunks that are expected to be usable in their entirety. */
  private static Assignment parseFully(List<AssignmentChunk> chunks) {
    AssignmentParser.Result result = AssignmentParser.parse(chunks);
    assertThat(result.errorMessage).isNull();
    assertThat(result.assignment).isNotNull();
    return result.assignment;
  }

  private static EndpointState endpoint(String name) {
    return EndpointState.newBuilder().setEndpoint(name).build();
  }

  private static SliceAssignment sliceAssignment(
      String startKey, @Nullable String endKey, int... endpointIndices) {
    com.google.cloud.autosharding.v1.Slice.Builder slice =
        com.google.cloud.autosharding.v1.Slice.newBuilder()
            .setStartKey(ByteString.copyFromUtf8(startKey));
    if (endKey != null) {
      slice.setEndKey(ByteString.copyFromUtf8(endKey));
    }
    SliceAssignment.Builder builder = SliceAssignment.newBuilder().setSlice(slice);
    for (int index : endpointIndices) {
      builder.addEndpoints(PerSliceEndpointState.newBuilder().setEndpointIndex(index));
    }
    return builder.build();
  }

  private static void assertSlice(
      Assignment.Slice slice, String startKey, @Nullable String endKey, int... endpoints) {
    assertThat(slice.getStartKey()).isEqualTo(startKey.getBytes(StandardCharsets.UTF_8));
    if (endKey == null) {
      assertThat(slice.getEndKey()).isNull();
    } else {
      assertThat(slice.getEndKey()).isEqualTo(endKey.getBytes(StandardCharsets.UTF_8));
    }
    assertThat(slice.getEndpoints())
        .containsExactlyElementsIn(Arrays.stream(endpoints).boxed().toArray())
        .inOrder();
  }
}
