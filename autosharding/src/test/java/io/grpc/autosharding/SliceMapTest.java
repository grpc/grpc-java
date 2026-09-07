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

import io.grpc.autosharding.SliceMap.SliceEntry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SliceMapTest {

  @Test
  public void lookup_emptySlices_returnsInvalidIndex() {
    SliceMap sliceMap = new SliceMap(Collections.emptyList(), Arrays.asList(0, 1), 1L);
    assertThat(sliceMap.lookup(new byte[] {1, 2, 3})).isEqualTo(-1);
    assertThat(sliceMap.lookup(null)).isEqualTo(-1);
    assertThat(sliceMap.lookup(new byte[0])).isEqualTo(-1);
  }

  @Test
  public void lookup_singleSlice() {
    byte[] startKey = new byte[0]; // Covers ["" .. inf)
    SliceEntry slice = new SliceEntry(startKey, Arrays.asList(0, 1));
    SliceMap sliceMap = new SliceMap(
        Collections.singletonList(slice), Arrays.asList(0, 1), 10L);

    assertThat(sliceMap.lookup(new byte[0])).isEqualTo(0);
    assertThat(sliceMap.lookup("foo".getBytes(StandardCharsets.UTF_8))).isEqualTo(0);
    assertThat(sliceMap.lookup(null)).isEqualTo(0);
  }

  @Test
  public void lookup_multipleSlices() {
    // Slices: ["" .. "m"), ["m" .. "t"), ["t" .. inf)
    SliceEntry s1 = new SliceEntry(
        "".getBytes(StandardCharsets.UTF_8), Collections.singletonList(0));
    SliceEntry s2 = new SliceEntry(
        "m".getBytes(StandardCharsets.UTF_8), Collections.singletonList(1));
    SliceEntry s3 = new SliceEntry(
        "t".getBytes(StandardCharsets.UTF_8), Collections.singletonList(2));

    SliceMap sliceMap = new SliceMap(Arrays.asList(s3, s1, s2), Arrays.asList(0, 1, 2), 5L);

    // Exact matches
    assertThat(sliceMap.lookup("".getBytes(StandardCharsets.UTF_8))).isEqualTo(0);
    assertThat(sliceMap.lookup("m".getBytes(StandardCharsets.UTF_8))).isEqualTo(1);
    assertThat(sliceMap.lookup("t".getBytes(StandardCharsets.UTF_8))).isEqualTo(2);

    // In-between matches
    assertThat(sliceMap.lookup("a".getBytes(StandardCharsets.UTF_8))).isEqualTo(0);
    assertThat(sliceMap.lookup("l".getBytes(StandardCharsets.UTF_8))).isEqualTo(0);
    assertThat(sliceMap.lookup("n".getBytes(StandardCharsets.UTF_8))).isEqualTo(1);
    assertThat(sliceMap.lookup("s".getBytes(StandardCharsets.UTF_8))).isEqualTo(1);
    assertThat(sliceMap.lookup("u".getBytes(StandardCharsets.UTF_8))).isEqualTo(2);
    assertThat(sliceMap.lookup("zzz".getBytes(StandardCharsets.UTF_8))).isEqualTo(2);
  }

  @Test
  public void lookup_keySmallerThanFirstSlice_returnsInvalidIndex() {
    // Slice starts at "m"
    SliceEntry s1 = new SliceEntry(
        "m".getBytes(StandardCharsets.UTF_8), Collections.singletonList(0));
    SliceMap sliceMap = new SliceMap(
        Collections.singletonList(s1), Collections.singletonList(0), 1L);

    assertThat(sliceMap.lookup("a".getBytes(StandardCharsets.UTF_8))).isEqualTo(-1);
    assertThat(sliceMap.lookup("".getBytes(StandardCharsets.UTF_8))).isEqualTo(-1);
    assertThat(sliceMap.lookup(null)).isEqualTo(-1);
    assertThat(sliceMap.lookup("m".getBytes(StandardCharsets.UTF_8))).isEqualTo(0);
    assertThat(sliceMap.lookup("z".getBytes(StandardCharsets.UTF_8))).isEqualTo(0);
  }

  @Test
  public void lookup_unsignedByteComparison() {
    // Test that 0x80 is treated as greater than 0x7F (unsigned)
    byte[] key1 = new byte[] {0x7F};
    byte[] key2 = new byte[] {(byte) 0x80};
    byte[] key3 = new byte[] {(byte) 0xFF};

    SliceEntry s1 = new SliceEntry(new byte[0], Collections.singletonList(0));
    SliceEntry s2 = new SliceEntry(key1, Collections.singletonList(1));
    SliceEntry s3 = new SliceEntry(key2, Collections.singletonList(2));
    SliceEntry s4 = new SliceEntry(key3, Collections.singletonList(3));

    SliceMap sliceMap = new SliceMap(
        Arrays.asList(s4, s2, s1, s3), Arrays.asList(0, 1, 2, 3), 1L);

    assertThat(sliceMap.lookup(new byte[] {0x10})).isEqualTo(0);
    assertThat(sliceMap.lookup(new byte[] {0x7F})).isEqualTo(1);
    assertThat(sliceMap.lookup(new byte[] {(byte) 0x80})).isEqualTo(2);
    assertThat(sliceMap.lookup(new byte[] {(byte) 0x90})).isEqualTo(2);
    assertThat(sliceMap.lookup(new byte[] {(byte) 0xFF})).isEqualTo(3);
    assertThat(sliceMap.lookup(new byte[] {(byte) 0xFF, 0x01})).isEqualTo(3);
  }

  @Test
  public void gettersAndImmutability() {
    byte[] inputKey = new byte[] {1};
    List<SliceEntry> slices = new ArrayList<>();
    slices.add(new SliceEntry(inputKey, Arrays.asList(0, 1)));
    List<Integer> fallback = new ArrayList<>(Arrays.asList(0, 1));

    SliceMap sliceMap = new SliceMap(slices, fallback, 42L);

    assertThat(sliceMap.getGeneration()).isEqualTo(42L);
    assertThat(sliceMap.getFallbackPool()).containsExactly(0, 1).inOrder();
    assertThat(sliceMap.getSlices()).hasSize(1);
    assertThat(sliceMap.getSlices().get(0).getStartKey()).isEqualTo(new byte[] {1});
    assertThat(sliceMap.getSlices().get(0).getEndpoints()).containsExactly(0, 1).inOrder();

    // Verify defensive copying: mutating input collections and key array does not affect sliceMap
    inputKey[0] = 99;
    slices.clear();
    fallback.clear();
    assertThat(sliceMap.getSlices()).hasSize(1);
    assertThat(sliceMap.getSlices().get(0).getStartKey()).isEqualTo(new byte[] {1});
    assertThat(sliceMap.getFallbackPool()).containsExactly(0, 1).inOrder();
  }

  @Test
  public void lookup_nullKey_treatedAsEmptyBytes() {
    SliceEntry s1 = new SliceEntry(
        "".getBytes(StandardCharsets.UTF_8), Collections.singletonList(0));
    SliceEntry s2 = new SliceEntry(
        "m".getBytes(StandardCharsets.UTF_8), Collections.singletonList(1));
    SliceMap sliceMap = new SliceMap(Arrays.asList(s1, s2), Arrays.asList(0, 1), 1L);

    assertThat(sliceMap.lookup(null)).isEqualTo(0);
  }

  @Test
  public void constructor_unsortedSlices_sortedLexicographically() {
    SliceEntry s1 = new SliceEntry(
        "".getBytes(StandardCharsets.UTF_8), Collections.singletonList(0));
    SliceEntry s2 = new SliceEntry(
        "m".getBytes(StandardCharsets.UTF_8), Collections.singletonList(1));
    SliceEntry s3 = new SliceEntry(
        "z".getBytes(StandardCharsets.UTF_8), Collections.singletonList(2));

    // Pass in reverse order
    SliceMap sliceMap = new SliceMap(Arrays.asList(s3, s1, s2), Arrays.asList(0, 1, 2), 1L);

    assertThat(sliceMap.getSlices().get(0).getStartKey())
        .isEqualTo("".getBytes(StandardCharsets.UTF_8));
    assertThat(sliceMap.getSlices().get(1).getStartKey())
        .isEqualTo("m".getBytes(StandardCharsets.UTF_8));
    assertThat(sliceMap.getSlices().get(2).getStartKey())
        .isEqualTo("z".getBytes(StandardCharsets.UTF_8));

    assertThat(sliceMap.lookup("abc".getBytes(StandardCharsets.UTF_8))).isEqualTo(0);
    assertThat(sliceMap.lookup("mmm".getBytes(StandardCharsets.UTF_8))).isEqualTo(1);
    assertThat(sliceMap.lookup("zzz".getBytes(StandardCharsets.UTF_8))).isEqualTo(2);
  }

  @Test
  public void lookup_duplicateStartKeys_matchesOne() {
    SliceEntry s1 = new SliceEntry(
        "a".getBytes(StandardCharsets.UTF_8), Collections.singletonList(0));
    SliceEntry s2 = new SliceEntry(
        "a".getBytes(StandardCharsets.UTF_8), Collections.singletonList(1));
    SliceMap sliceMap = new SliceMap(Arrays.asList(s1, s2), Arrays.asList(0, 1), 1L);

    int idx = sliceMap.lookup("a".getBytes(StandardCharsets.UTF_8));
    assertThat(idx).isAnyOf(0, 1);
  }

  @Test
  public void constructor_nullInputs_throwsNullPointerException() {
    org.junit.Assert.assertThrows(
        NullPointerException.class,
        () -> new SliceMap(null, Collections.singletonList(0), 1L));

    org.junit.Assert.assertThrows(
        NullPointerException.class,
        () -> new SliceMap(Collections.emptyList(), null, 1L));

    org.junit.Assert.assertThrows(
        NullPointerException.class,
        () -> new SliceEntry(null, Collections.singletonList(0)));

    org.junit.Assert.assertThrows(
        NullPointerException.class,
        () -> new SliceEntry(new byte[0], null));
  }
}
