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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.Status;
import io.grpc.xds.XdsPicker.Locality;
import io.grpc.xds.XdsPicker.LocalityState;
import io.grpc.xds.XdsPicker.WrrAlgorithm;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  private WrrAlgorithm wrrAlgorithm;
  @Mock
  private PickSubchannelArgs pickSubchannelArgs;

  private final PickResult pickResult0 = PickResult.withNoResult();
  private final PickResult pickResult1 = PickResult.withDrop(Status.UNAVAILABLE);
  private final PickResult pickResult2 = PickResult.withSubchannel(mock(Subchannel.class));

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


  @Test
  public void emptyList() {
    List<LocalityState> emptyList = new ArrayList<>();
    Map<Locality, SubchannelPicker> emptyPickers = new HashMap<>();

    thrown.expect(IllegalArgumentException.class);
    new XdsPicker(emptyList, emptyPickers);
  }

  @Test
  public void missingPicker() {
    List<LocalityState> localityStates = Arrays.asList(
        new LocalityState(new Locality("r1", "z1", "sz1"), 0, null),
        new LocalityState(new Locality("r2", "z2", "sz2"), 0, null));
    Map<Locality, SubchannelPicker> subChannelPickers = new HashMap<>();
    subChannelPickers.put(new Locality("r1", "z1", "sz1"), subPicker0);
    subChannelPickers.put(new Locality("r2", "z3", "sz2"), subPicker1);

    thrown.expect(IllegalArgumentException.class);
    new XdsPicker(localityStates, subChannelPickers);
  }

  @Test
  public void pickWithFakeAlgorithm() {
    List<LocalityState> wrrList = Arrays.asList(
        new LocalityState(new Locality("r0", "z0", "sz0"), 0, null),
        new LocalityState(new Locality("r1", "z1", "sz1"), 0, null),
        new LocalityState(new Locality("r2", "z2", "sz2"), 0, null));
    Map<Locality, SubchannelPicker> subChannelPickers = new LinkedHashMap<>();
    subChannelPickers.put(new Locality("r1", "z1", "sz1"), subPicker1);
    subChannelPickers.put(new Locality("r0", "z0", "sz0"), subPicker0);
    subChannelPickers.put(new Locality("r2", "z2", "sz2"), subPicker2);
    XdsPicker xdsPicker = new XdsPicker(wrrList, subChannelPickers, wrrAlgorithm);

    doReturn(wrrList.get(1).locality).when(wrrAlgorithm).pickLocality(wrrList);
    assertThat(xdsPicker.pickSubchannel(pickSubchannelArgs)).isSameAs(pickResult1);

    doReturn(wrrList.get(2).locality).when(wrrAlgorithm).pickLocality(wrrList);
    assertThat(xdsPicker.pickSubchannel(pickSubchannelArgs)).isSameAs(pickResult2);

    doReturn(wrrList.get(0).locality).when(wrrAlgorithm).pickLocality(wrrList);
    assertThat(xdsPicker.pickSubchannel(pickSubchannelArgs)).isSameAs(pickResult0);
  }
}
