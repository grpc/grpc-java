/*
 * Copyright 2019, gRPC Authors All rights reserved.
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

package io.grpc;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MINUTES;

import io.grpc.ClientStreamTracer.StreamInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for the embedded classes in {@link ClientStreamTracer}. */
@RunWith(JUnit4.class)
public class ClientStreamTracerTest {
  private static final Attributes.Key<String> TRANSPORT_ATTR_KEY =
      Attributes.Key.create("transport-attr-key");
  private final CallOptions callOptions = CallOptions.DEFAULT.withDeadlineAfter(1, MINUTES);
  private final Attributes transportAttrs =
      Attributes.newBuilder().set(TRANSPORT_ATTR_KEY, "value").build();

  @Test
  public void streamInfo_empty() {
    StreamInfo info = StreamInfo.newBuilder().build();
    assertThat(info.getCallOptions()).isSameInstanceAs(CallOptions.DEFAULT);
    assertThat(info.getTransportAttrs()).isSameInstanceAs(Attributes.EMPTY);
  }

  @Test
  public void streamInfo_withInfo() {
    StreamInfo info = StreamInfo.newBuilder()
        .setCallOptions(callOptions).setTransportAttrs(transportAttrs).build();
    assertThat(info.getCallOptions()).isSameInstanceAs(callOptions);
    assertThat(info.getTransportAttrs()).isSameInstanceAs(transportAttrs);
  }

  @Test
  public void streamInfo_noEquality() {
    StreamInfo info1 = StreamInfo.newBuilder()
        .setCallOptions(callOptions).setTransportAttrs(transportAttrs).build();
    StreamInfo info2 = StreamInfo.newBuilder()
        .setCallOptions(callOptions).setTransportAttrs(transportAttrs).build();

    assertThat(info1).isNotSameInstanceAs(info2);
    assertThat(info1).isNotEqualTo(info2);
  }

  @Test
  public void streamInfo_toBuilder() {
    StreamInfo info1 = StreamInfo.newBuilder()
        .setCallOptions(callOptions).setTransportAttrs(transportAttrs).build();
    StreamInfo info2 = info1.toBuilder().build();
    assertThat(info2.getCallOptions()).isSameInstanceAs(callOptions);
    assertThat(info2.getTransportAttrs()).isSameInstanceAs(transportAttrs);
  }
}
