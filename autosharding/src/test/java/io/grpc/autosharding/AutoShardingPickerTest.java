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

import io.grpc.CallOptions;
import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer.PickDetailsConsumer;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.autosharding.PickerEndpoint.ExitIdler;
import io.grpc.autosharding.SliceMap.SliceEntry;
import io.grpc.internal.PickSubchannelArgsImpl;
import io.grpc.testing.TestMethodDescriptors;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AutoShardingPickerTest {

  private static final MethodDescriptor<Void, Void> METHOD = TestMethodDescriptors.voidMethod();
  private static final ExitIdler NOOP_EXIT_IDLER = new ExitIdler() {
    @Override
    public void exitIdle() {}
  };
  private static final PickDetailsConsumer NOOP_CONSUMER = new PickDetailsConsumer() {};

  private PickSubchannelArgs createArgs(Metadata headers) {
    return new PickSubchannelArgsImpl(METHOD, headers, CallOptions.DEFAULT, NOOP_CONSUMER);
  }

  private static class FakePicker extends SubchannelPicker {
    private final PickResult result;

    FakePicker(PickResult result) {
      this.result = result;
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return result;
    }
  }

  @Test
  public void pick_noSliceMap_fallbackEnabled_picksFromFallbackPool() {
    PickResult readyResult = PickResult.withNoResult(); // using as token
    PickerEndpoint ep0 = new PickerEndpoint(
        ConnectivityState.READY, new FakePicker(readyResult), NOOP_EXIT_IDLER);

    SliceMap emptySliceMap = new SliceMap(
        Collections.emptyList(), Collections.singletonList(0), 1L);
    AutoShardingPicker picker = new AutoShardingPicker(
        emptySliceMap, Collections.singletonList(ep0), true, "x-slice-key");

    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of("x-slice-key", Metadata.ASCII_STRING_MARSHALLER), "user123");

    PickResult result = picker.pickSubchannel(createArgs(headers));
    assertThat(result).isSameInstanceAs(readyResult);
  }

  @Test
  public void pick_noSliceMap_fallbackDisabled_returnsUnavailableError() {
    PickerEndpoint ep0 = new PickerEndpoint(
        ConnectivityState.READY, new FakePicker(PickResult.withNoResult()), NOOP_EXIT_IDLER);

    SliceMap emptySliceMap = new SliceMap(
        Collections.emptyList(), Collections.singletonList(0), 1L);
    AutoShardingPicker picker = new AutoShardingPicker(
        emptySliceMap, Collections.singletonList(ep0), false, "x-slice-key");

    Metadata headers = new Metadata();
    PickResult result = picker.pickSubchannel(createArgs(headers));

    assertThat(result.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription())
        .contains("No sharding assignment available and fallback disabled");
  }

  @Test
  public void pick_sliceFound_readyEndpoint_returnsPickResult() {
    PickResult expectedResult = PickResult.withNoResult();
    PickerEndpoint ep0 = new PickerEndpoint(
        ConnectivityState.READY, new FakePicker(expectedResult), NOOP_EXIT_IDLER);

    SliceEntry slice = new SliceEntry(
        "".getBytes(StandardCharsets.UTF_8), Collections.singletonList(0));
    SliceMap sliceMap = new SliceMap(
        Collections.singletonList(slice), Collections.singletonList(0), 1L);

    AutoShardingPicker picker = new AutoShardingPicker(
        sliceMap, Collections.singletonList(ep0), false, "x-slice-key");

    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of("x-slice-key", Metadata.ASCII_STRING_MARSHALLER), "anyKey");

    PickResult result = picker.pickSubchannel(createArgs(headers));
    assertThat(result).isSameInstanceAs(expectedResult);
  }

  @Test
  public void pick_sliceFound_idleEndpoint_triggersConnectionAndQueues() {
    AtomicInteger connectCalls = new AtomicInteger(0);
    PickerEndpoint ep0 = new PickerEndpoint(
        ConnectivityState.IDLE,
        new FakePicker(PickResult.withNoResult()),
        connectCalls::incrementAndGet);

    SliceEntry slice = new SliceEntry(
        "".getBytes(StandardCharsets.UTF_8), Collections.singletonList(0));
    SliceMap sliceMap = new SliceMap(
        Collections.singletonList(slice), Collections.singletonList(0), 1L);

    AutoShardingPicker picker = new AutoShardingPicker(
        sliceMap, Collections.singletonList(ep0), false, "x-slice-key");

    PickResult result = picker.pickSubchannel(createArgs(new Metadata()));

    assertThat(connectCalls.get()).isEqualTo(1);
    assertThat(result.hasResult()).isFalse();
  }

  @Test
  public void pick_sliceFound_connectingEndpoint_queuesPick() {
    AtomicInteger connectCalls = new AtomicInteger(0);
    PickerEndpoint ep0 = new PickerEndpoint(
        ConnectivityState.CONNECTING,
        new FakePicker(PickResult.withNoResult()),
        connectCalls::incrementAndGet);

    SliceEntry slice = new SliceEntry(
        "".getBytes(StandardCharsets.UTF_8), Collections.singletonList(0));
    SliceMap sliceMap = new SliceMap(
        Collections.singletonList(slice), Collections.singletonList(0), 1L);

    AutoShardingPicker picker = new AutoShardingPicker(
        sliceMap, Collections.singletonList(ep0), false, "x-slice-key");

    PickResult result = picker.pickSubchannel(createArgs(new Metadata()));

    assertThat(connectCalls.get()).isEqualTo(0);
    assertThat(result.hasResult()).isFalse();
  }

  @Test
  public void pick_sliceFound_allTransientFailure_fallbackEnabled_picksFromFallbackPool() {
    PickResult fallbackReadyResult = PickResult.withNoResult();
    PickerEndpoint ep0 = new PickerEndpoint(
        ConnectivityState.TRANSIENT_FAILURE,
        new FakePicker(PickResult.withError(Status.UNAVAILABLE.withDescription("ep0 down"))),
        NOOP_EXIT_IDLER);
    PickerEndpoint ep1 = new PickerEndpoint(
        ConnectivityState.READY, new FakePicker(fallbackReadyResult), NOOP_EXIT_IDLER);

    // Slice 0 only has ep0 (which is down)
    SliceEntry slice0 = new SliceEntry(
        "".getBytes(StandardCharsets.UTF_8), Collections.singletonList(0));
    // Fallback pool has ep1 (which is ready)
    SliceMap sliceMap = new SliceMap(
        Collections.singletonList(slice0), Collections.singletonList(1), 1L);

    AutoShardingPicker picker = new AutoShardingPicker(
        sliceMap, Arrays.asList(ep0, ep1), true, "x-slice-key");

    PickResult result = picker.pickSubchannel(createArgs(new Metadata()));
    assertThat(result).isSameInstanceAs(fallbackReadyResult);
  }

  @Test
  public void pick_sliceFound_allTransientFailure_fallbackDisabled_delegatesToEndpointPicker() {
    Status epError = Status.UNAVAILABLE.withDescription("connection refused to ep0");
    PickerEndpoint ep0 = new PickerEndpoint(
        ConnectivityState.TRANSIENT_FAILURE,
        new FakePicker(PickResult.withError(epError)),
        NOOP_EXIT_IDLER);

    SliceEntry slice0 = new SliceEntry(
        "".getBytes(StandardCharsets.UTF_8), Collections.singletonList(0));
    SliceMap sliceMap = new SliceMap(
        Collections.singletonList(slice0), Collections.singletonList(0), 1L);

    AutoShardingPicker picker = new AutoShardingPicker(
        sliceMap, Collections.singletonList(ep0), false, "x-slice-key");

    PickResult result = picker.pickSubchannel(createArgs(new Metadata()));
    assertThat(result.getStatus()).isEqualTo(epError);
  }

  @Test
  public void pick_binaryHeader_extractedProperly() {
    PickResult ready0 = PickResult.withNoResult();
    PickResult ready1 = PickResult.withNoResult();

    PickerEndpoint ep0 = new PickerEndpoint(
        ConnectivityState.READY, new FakePicker(ready0), NOOP_EXIT_IDLER);
    PickerEndpoint ep1 = new PickerEndpoint(
        ConnectivityState.READY, new FakePicker(ready1), NOOP_EXIT_IDLER);

    SliceEntry s0 = new SliceEntry(new byte[] {0x00}, Collections.singletonList(0));
    SliceEntry s1 = new SliceEntry(new byte[] {0x50}, Collections.singletonList(1));
    SliceMap sliceMap = new SliceMap(Arrays.asList(s0, s1), Arrays.asList(0, 1), 1L);

    AutoShardingPicker picker = new AutoShardingPicker(
        sliceMap, Arrays.asList(ep0, ep1), false, "slice-key-bin");

    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of("slice-key-bin", Metadata.BINARY_BYTE_MARSHALLER),
        new byte[] {0x60});

    PickResult result = picker.pickSubchannel(createArgs(headers));
    assertThat(result).isSameInstanceAs(ready1);
  }

  @Test
  public void pick_emptySliceEndpoints_fallbackDisabled_returnsUnavailable() {
    PickerEndpoint ep0 = new PickerEndpoint(
        ConnectivityState.READY, new FakePicker(PickResult.withNoResult()), NOOP_EXIT_IDLER);

    SliceEntry emptySlice = new SliceEntry(
        "".getBytes(StandardCharsets.UTF_8), Collections.emptyList());
    SliceMap sliceMap = new SliceMap(
        Collections.singletonList(emptySlice), Collections.singletonList(0), 1L);

    AutoShardingPicker picker = new AutoShardingPicker(
        sliceMap, Collections.singletonList(ep0), false, "x-slice-key");

    PickResult result = picker.pickSubchannel(createArgs(new Metadata()));
    assertThat(result.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription())
        .contains("No valid endpoints in slice and fallback disabled");
  }

  @Test
  public void pick_emptySliceEndpoints_fallbackEnabled_routesToFallbackPool() {
    PickResult fallbackReadyResult = PickResult.withNoResult();
    PickerEndpoint ep0 = new PickerEndpoint(
        ConnectivityState.READY, new FakePicker(fallbackReadyResult), NOOP_EXIT_IDLER);

    // Gap slice with empty endpoints list
    SliceEntry gapSlice = new SliceEntry(
        "".getBytes(StandardCharsets.UTF_8), Collections.emptyList());
    // Fallback pool has ep0
    SliceMap sliceMap = new SliceMap(
        Collections.singletonList(gapSlice), Collections.singletonList(0), 1L);

    AutoShardingPicker picker = new AutoShardingPicker(
        sliceMap, Collections.singletonList(ep0), true, "x-key");

    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of("x-key", Metadata.ASCII_STRING_MARSHALLER), "anyKey");

    PickResult result = picker.pickSubchannel(createArgs(headers));
    assertThat(result).isSameInstanceAs(fallbackReadyResult);
  }

  @Test
  public void pickerEndpoint_gettersAndToString() {
    FakePicker fakePicker = new FakePicker(PickResult.withNoResult());
    AtomicInteger count = new AtomicInteger();
    PickerEndpoint ep = new PickerEndpoint(
        ConnectivityState.IDLE, fakePicker, count::incrementAndGet);

    assertThat(ep.getState()).isEqualTo(ConnectivityState.IDLE);
    assertThat(ep.getPicker()).isSameInstanceAs(fakePicker);
    assertThat(ep.toString()).contains("state=IDLE");

    ep.requestConnection();
    assertThat(count.get()).isEqualTo(1);
  }
}
