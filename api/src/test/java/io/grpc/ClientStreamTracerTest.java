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
  private final CallOptions callOptions = CallOptions.DEFAULT.withDeadlineAfter(1, MINUTES);

  @Test
  public void streamInfo_empty() {
    StreamInfo info = StreamInfo.newBuilder().build();
    assertThat(info.getCallOptions()).isSameInstanceAs(CallOptions.DEFAULT);
  }

  @Test
  public void streamInfo_withInfo() {
    StreamInfo info = StreamInfo.newBuilder().setCallOptions(callOptions).build();
    assertThat(info.getCallOptions()).isSameInstanceAs(callOptions);
  }

  @Test
  public void streamInfo_noEquality() {
    StreamInfo info1 = StreamInfo.newBuilder().setCallOptions(callOptions).build();
    StreamInfo info2 = StreamInfo.newBuilder().setCallOptions(callOptions).build();

    assertThat(info1).isNotSameInstanceAs(info2);
    assertThat(info1).isNotEqualTo(info2);
  }

  @Test
  public void streamInfo_toBuilder() {
    StreamInfo info1 = StreamInfo.newBuilder()
        .setCallOptions(callOptions).build();
    StreamInfo info2 = info1.toBuilder().build();
    assertThat(info2.getCallOptions()).isSameInstanceAs(callOptions);
  }

  /**
   * Guards a backward-compatibility contract: third-party subclasses of {@link ClientStreamTracer}
   * or {@link ClientStreamTracer.Factory} that predate gRFC A121, and therefore do not override the
   * new delay methods, must keep working when the channel invokes them. The inherited defaults must
   * be safe no-ops: they must not throw, and they must not fan out into any other tracer callback
   * (which would corrupt the stats such a subclass already collects).
   *
   * <p>Deliberately uses real subclasses rather than partial mocks so that the actual inherited
   * bytecode a third party would get is what runs here.
   */
  @Test
  public void defaultDelayMethodsMaintainBackwardCompatibility() {
    // Overrides every callback a stale subclass could plausibly already implement, so that any
    // accidental delegation out of the new default methods is caught.
    ClientStreamTracer tracer = new ClientStreamTracer() {
      @Override
      public void streamCreated(Attributes transportAttrs, Metadata headers) {
        throw new AssertionError("default delay method must not call streamCreated()");
      }

      @Override
      public void createPendingStream() {
        throw new AssertionError("default delay method must not call createPendingStream()");
      }

      @Override
      public void inboundHeaders() {
        throw new AssertionError("default delay method must not call inboundHeaders()");
      }

      @Override
      public void inboundTrailers(Metadata trailers) {
        throw new AssertionError("default delay method must not call inboundTrailers()");
      }

      @Override
      public void streamClosed(Status status) {
        throw new AssertionError("default delay method must not call streamClosed()");
      }

      @Override
      public void addOptionalLabel(String key, String value) {
        throw new AssertionError("default delay method must not call addOptionalLabel()");
      }

      @Override
      public void outboundMessage(int seqNo) {
        throw new AssertionError("default delay method must not call outboundMessage()");
      }

      @Override
      public void inboundMessage(int seqNo) {
        throw new AssertionError("default delay method must not call inboundMessage()");
      }
    };

    // A full delay lifecycle, including a type that the subclass has never heard of.
    tracer.recordDelayStart("connecting", "test");
    tracer.recordDelayReasonChanged("connecting", "test2");
    tracer.recordDelayEnd("connecting");
    tracer.recordDelayStart("some_future_delay_type", "a reason from a newer gRPC");
    tracer.recordDelayEnd("some_future_delay_type");
    // Unbalanced and null-reason calls must be tolerated too; the base class keeps no state.
    tracer.recordDelayReasonChanged("connecting", null);
    tracer.recordDelayEnd("never_started");

    ClientStreamTracer.Factory factory = new ClientStreamTracer.Factory() {
      @Override
      public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
        throw new AssertionError("default delay method must not call newClientStreamTracer()");
      }
    };

    factory.recordDelayStart("resolving", "test");
    factory.recordDelayReasonChanged("resolving", "test2");
    factory.recordDelayEnd("resolving");
    factory.recordDelayStart("some_future_delay_type", "a reason from a newer gRPC");
    factory.recordDelayEnd("some_future_delay_type");
    factory.recordDelayReasonChanged("resolving", null);
    factory.recordDelayEnd("never_started");
  }
}
