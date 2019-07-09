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
import static org.mockito.Mockito.mock;

import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.Status;
import io.grpc.xds.InterLocalityPicker.IntraLocalityPicker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link InterLocalityPicker}.
 */
@RunWith(JUnit4.class)
public class InterLocalityPickerTest {
  private static final XdsLocality LOCALITY0
      = new XdsLocality("test_region0", "test_zone0", "test_subzone0");
  private static final XdsLocality LOCALITY1
      = new XdsLocality("test_region1", "test_zone1", "test_subzone1");
  private static final XdsLocality LOCALITY2
      = new XdsLocality("test_region2", "test_zone2", "test_subzone2");
  private static final XdsLocality LOCALITY3
      = new XdsLocality("test_region3", "test_zone3", "test_subzone3");

  @Rule
  public final ExpectedException thrown = ExpectedException.none();

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
    int bound;

    @Override
    public int nextInt(int bound) {
      this.bound = bound;

      assertThat(nextInt).isAtLeast(0);
      assertThat(nextInt).isLessThan(bound);
      return nextInt;
    }
  }

  private final FakeRandom fakeRandom = new FakeRandom();

  @Test
  public void emptyList() {
    List<IntraLocalityPicker> emptyList = new ArrayList<>();

    thrown.expect(IllegalArgumentException.class);
    new InterLocalityPicker(emptyList);
  }

  @Test
  public void negativeWeight() {
    thrown.expect(IllegalArgumentException.class);
    new IntraLocalityPicker(LOCALITY0, -1, childPicker0);
  }

  @Test
  public void pickWithFakeRandom() {
    IntraLocalityPicker localityPicker0 = new IntraLocalityPicker(LOCALITY0, 0, childPicker0);
    IntraLocalityPicker localityPicker1 = new IntraLocalityPicker(LOCALITY1, 15, childPicker1);
    IntraLocalityPicker localityPicker2 = new IntraLocalityPicker(LOCALITY2, 0, childPicker2);
    IntraLocalityPicker localityPicker3 = new IntraLocalityPicker(LOCALITY3, 10, childPicker3);

    InterLocalityPicker xdsPicker
        = new InterLocalityPicker(
            Arrays.asList(localityPicker0, localityPicker1, localityPicker2,
                localityPicker3), fakeRandom);

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
  public void allZeroWeights() {
    IntraLocalityPicker localityPicker0 = new IntraLocalityPicker(LOCALITY0, 0, childPicker0);
    IntraLocalityPicker localityPicker1 = new IntraLocalityPicker(LOCALITY1, 0, childPicker1);
    IntraLocalityPicker localityPicker2 = new IntraLocalityPicker(LOCALITY2, 0, childPicker2);
    IntraLocalityPicker localityPicker3 = new IntraLocalityPicker(LOCALITY3, 0, childPicker3);

    InterLocalityPicker xdsPicker
        = new InterLocalityPicker(
        Arrays.asList(localityPicker0, localityPicker1, localityPicker2,
            localityPicker3), fakeRandom);

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
