/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.Status;
import io.grpc.xds.WeightedRandomPicker.WeightedChildPicker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link WeightedRandomPicker}.
 */
@RunWith(JUnit4.class)
public class WeightedRandomPickerTest {

  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock
  private PickSubchannelArgs pickSubchannelArgs;

  private final PickResult pickResult0 = PickResult.withNoResult();
  private final PickResult pickResult1 = PickResult.withDrop(Status.UNAVAILABLE);
  private final PickResult pickResult2 = PickResult.withSubchannel(mock(Subchannel.class));
  private final PickResult pickResult3 = PickResult.withSubchannel(mock(Subchannel.class));

  private final SubchannelPicker childPicker0 = new SubchannelPicker() {
    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return pickResult0;
    }
  };

  private final SubchannelPicker childPicker1 = new SubchannelPicker() {
    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return pickResult1;
    }
  };

  private final SubchannelPicker childPicker2 = new SubchannelPicker() {
    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return pickResult2;
    }
  };

  private final SubchannelPicker childPicker3 = new SubchannelPicker() {
    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return pickResult3;
    }
  };

  private static final class FakeRandom implements ThreadSafeRandom {
    int nextInt;
    long bound;
    Long nextLong;

    @Override
    public int nextInt(int bound) {
      this.bound = bound;

      assertThat(nextInt).isAtLeast(0);
      assertThat(nextInt).isLessThan(bound);
      return nextInt;
    }

    @Override
    public long nextLong() {
      throw new UnsupportedOperationException("Should not be called");
    }

    @Override
    public long nextLong(long bound) {
      this.bound = bound;

      if (nextLong == null) {
        assertThat(nextInt).isAtLeast(0);
        if (bound <= Integer.MAX_VALUE) {
          assertThat(nextInt).isLessThan((int)bound);
        }
        return nextInt;
      }

      assertThat(nextLong).isAtLeast(0);
      assertThat(nextLong).isLessThan(bound);
      return nextLong;
    }
  }

  private final FakeRandom fakeRandom = new FakeRandom();

  @Test
  public void emptyList() {
    List<WeightedChildPicker> emptyList = new ArrayList<>();

    assertThrows(IllegalArgumentException.class, () -> new WeightedRandomPicker(emptyList));
  }

  @Test
  public void negativeWeight() {
    assertThrows(IllegalArgumentException.class, () -> new WeightedChildPicker(-1, childPicker0));
  }

  @Test
  public void overWeightSingle() {
    assertThrows(IllegalArgumentException.class,
        () -> new WeightedChildPicker(Integer.MAX_VALUE * 3L, childPicker0));
  }

  @Test
  public void overWeightAggregate() {

    List<WeightedChildPicker> weightedChildPickers = Arrays.asList(
        new WeightedChildPicker(Integer.MAX_VALUE, childPicker0),
        new WeightedChildPicker(Integer.MAX_VALUE, childPicker1),
        new WeightedChildPicker(10, childPicker2));

    assertThrows(IllegalArgumentException.class,
        () -> new WeightedRandomPicker(weightedChildPickers, fakeRandom));
  }

  @Test
  public void pickWithFakeRandom() {
    WeightedChildPicker weightedChildPicker0 = new WeightedChildPicker(0, childPicker0);
    WeightedChildPicker weightedChildPicker1 = new WeightedChildPicker(15, childPicker1);
    WeightedChildPicker weightedChildPicker2 = new WeightedChildPicker(0, childPicker2);
    WeightedChildPicker weightedChildPicker3 = new WeightedChildPicker(10, childPicker3);

    WeightedRandomPicker xdsPicker = new WeightedRandomPicker(
        Arrays.asList(
            weightedChildPicker0,
            weightedChildPicker1,
            weightedChildPicker2,
            weightedChildPicker3),
        fakeRandom);

    fakeRandom.nextInt = 0;
    assertThat(xdsPicker.pickSubchannel(pickSubchannelArgs)).isSameInstanceAs(pickResult1);
    assertThat(fakeRandom.bound).isEqualTo(25);

    fakeRandom.nextInt = 1;
    assertThat(xdsPicker.pickSubchannel(pickSubchannelArgs)).isSameInstanceAs(pickResult1);
    assertThat(fakeRandom.bound).isEqualTo(25);

    fakeRandom.nextInt = 14;
    assertThat(xdsPicker.pickSubchannel(pickSubchannelArgs)).isSameInstanceAs(pickResult1);
    assertThat(fakeRandom.bound).isEqualTo(25);

    fakeRandom.nextInt = 15;
    assertThat(xdsPicker.pickSubchannel(pickSubchannelArgs)).isSameInstanceAs(pickResult3);
    assertThat(fakeRandom.bound).isEqualTo(25);

    fakeRandom.nextInt = 24;
    assertThat(xdsPicker.pickSubchannel(pickSubchannelArgs)).isSameInstanceAs(pickResult3);
    assertThat(fakeRandom.bound).isEqualTo(25);
  }

  @Test
  public void pickFromLargeTotal() {

    List<WeightedChildPicker> weightedChildPickers = Arrays.asList(
        new WeightedChildPicker(10, childPicker0),
        new WeightedChildPicker(Integer.MAX_VALUE, childPicker1),
        new WeightedChildPicker(10, childPicker2));
    WeightedRandomPicker xdsPicker = new WeightedRandomPicker(weightedChildPickers,fakeRandom);

    long totalWeight = weightedChildPickers.stream()
        .mapToLong(WeightedChildPicker::getWeight)
        .reduce(0, Long::sum);

    fakeRandom.nextLong = 5L;
    assertThat(xdsPicker.pickSubchannel(pickSubchannelArgs)).isSameInstanceAs(pickResult0);
    assertThat(fakeRandom.bound).isEqualTo(totalWeight);

    fakeRandom.nextLong = 16L;
    assertThat(xdsPicker.pickSubchannel(pickSubchannelArgs)).isSameInstanceAs(pickResult1);
    assertThat(fakeRandom.bound).isEqualTo(totalWeight);

    fakeRandom.nextLong = Integer.MAX_VALUE + 10L;
    assertThat(xdsPicker.pickSubchannel(pickSubchannelArgs)).isSameInstanceAs(pickResult2);
    assertThat(fakeRandom.bound).isEqualTo(totalWeight);

    fakeRandom.nextLong = Integer.MAX_VALUE + 15L;
    assertThat(xdsPicker.pickSubchannel(pickSubchannelArgs)).isSameInstanceAs(pickResult2);
    assertThat(fakeRandom.bound).isEqualTo(totalWeight);
  }

  @Test
  public void allZeroWeights() {
    WeightedChildPicker weightedChildPicker0 = new WeightedChildPicker(0, childPicker0);
    WeightedChildPicker weightedChildPicker1 = new WeightedChildPicker(0, childPicker1);
    WeightedChildPicker weightedChildPicker2 = new WeightedChildPicker(0, childPicker2);
    WeightedChildPicker weightedChildPicker3 = new WeightedChildPicker(0, childPicker3);

    WeightedRandomPicker xdsPicker = new WeightedRandomPicker(
        Arrays.asList(
            weightedChildPicker0,
            weightedChildPicker1,
            weightedChildPicker2,
            weightedChildPicker3),
        fakeRandom);

    fakeRandom.nextInt = 0;
    assertThat(xdsPicker.pickSubchannel(pickSubchannelArgs)).isSameInstanceAs(pickResult0);
    assertThat(fakeRandom.bound).isEqualTo(4);

    fakeRandom.nextInt = 1;
    assertThat(xdsPicker.pickSubchannel(pickSubchannelArgs)).isSameInstanceAs(pickResult1);
    assertThat(fakeRandom.bound).isEqualTo(4);

    fakeRandom.nextInt = 2;
    assertThat(xdsPicker.pickSubchannel(pickSubchannelArgs)).isSameInstanceAs(pickResult2);
    assertThat(fakeRandom.bound).isEqualTo(4);

    fakeRandom.nextInt = 3;
    assertThat(xdsPicker.pickSubchannel(pickSubchannelArgs)).isSameInstanceAs(pickResult3);
    assertThat(fakeRandom.bound).isEqualTo(4);
  }
}
