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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link XdsPicker}.
 */
@RunWith(JUnit4.class)
public class XdsPickerTest {
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

  private final SubchannelPicker subPicker0 = new SubchannelPicker() {
    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return pickResult0;
    }
  };

  private final SubchannelPicker subPicker1 = new SubchannelPicker() {
    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return pickResult1;
    }
  };

  private final SubchannelPicker subPicker2 = new SubchannelPicker() {
    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return pickResult2;
    }
  };

  private final SubchannelPicker subPicker3 = new SubchannelPicker() {
    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return pickResult3;
    }
  };

  private static final class FakeRandom extends Random {
    int nextInt;

    @Override
    public int nextInt(int bound) {
      assertThat(nextInt).isAtLeast(0);
      assertThat(nextInt).isLessThan(bound);
      return nextInt;
    }
  }

  private final FakeRandom fakeRandom = new FakeRandom();

  @Test
  public void emptyList() {
    List<String> emptyList = new ArrayList<>();
    List<Integer> weights = new ArrayList<>();
    Map<String, SubchannelPicker> emptyPickers = new HashMap<>();

    thrown.expect(IllegalArgumentException.class);
    new XdsPicker<String>(emptyList, weights, emptyPickers);
  }

  @Test
  public void localitiesAndWeightsSizeMismatch() {
    List<String> localities = Arrays.asList("l1", "l2", "l3");
    List<Integer> weights = Arrays.asList(1, 2);
    Map<String, SubchannelPicker> subChannelPickers = new HashMap<>();
    subChannelPickers.put("l1", subPicker1);
    subChannelPickers.put("l2", subPicker2);
    subChannelPickers.put("l3", subPicker3);

    thrown.expect(IllegalArgumentException.class);
    new XdsPicker<String>(localities, weights, subChannelPickers);
  }

  @Test
  public void missingPicker() {
    List<String> localities = Arrays.asList("l1", "l2");
    List<Integer> weights = Arrays.asList(1, 2);
    Map<String, SubchannelPicker> subChannelPickers = new HashMap<>();
    subChannelPickers.put("l1", subPicker0);
    subChannelPickers.put("l3", subPicker1);

    thrown.expect(IllegalArgumentException.class);
    new XdsPicker<String>(localities, weights, subChannelPickers);
  }

  @Test
  public void pickWithFakeWrrAlgorithm() {
    List<String> localities = Arrays.asList("l0", "l1", "l2", "l3");
    List<Integer> weights = Arrays.asList(0, 15, 0, 10);
    Map<String, SubchannelPicker> subChannelPickers = new HashMap<>();
    subChannelPickers.put("l0", subPicker0);
    subChannelPickers.put("l1", subPicker1);
    subChannelPickers.put("l2", subPicker2);
    subChannelPickers.put("l3", subPicker3);

    XdsPicker<String> xdsPicker =
        new XdsPicker<String>(localities, weights, subChannelPickers, fakeRandom);

    fakeRandom.nextInt = 0;
    assertThat(xdsPicker.pickSubchannel(pickSubchannelArgs)).isSameAs(pickResult1);

    fakeRandom.nextInt = 1;
    assertThat(xdsPicker.pickSubchannel(pickSubchannelArgs)).isSameAs(pickResult1);

    fakeRandom.nextInt = 14;
    assertThat(xdsPicker.pickSubchannel(pickSubchannelArgs)).isSameAs(pickResult1);

    fakeRandom.nextInt = 15;
    assertThat(xdsPicker.pickSubchannel(pickSubchannelArgs)).isSameAs(pickResult3);

    fakeRandom.nextInt = 24;
    assertThat(xdsPicker.pickSubchannel(pickSubchannelArgs)).isSameAs(pickResult3);
  }
}
