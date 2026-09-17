/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds.internal.extauthz;

import static com.google.common.truth.Truth.assertThat;

import io.grpc.Metadata;
import io.grpc.NoopClientCall;
import io.grpc.Status;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link FailingClientCall}. */
@RunWith(JUnit4.class)
public class FailingClientCallTest {

  private final CapturingListener<Object> listener = new CapturingListener<>();

  @Test
  public void startCallsOnClose() {
    Status error = Status.UNAVAILABLE.withDescription("test error");
    FailingClientCall<Object, Object> call = new FailingClientCall<>(error);
    Metadata metadata = new Metadata();
    call.start(listener, metadata);

    assertThat(listener.getCloseStatus()).isEqualTo(error);
    assertThat(listener.getCloseTrailers()).isNotNull();
    assertThat(listener.getCloseTrailers().keys()).isEmpty();
  }

  @Test
  public void otherMethodsAreNoOps() {
    Status error = Status.UNAVAILABLE.withDescription("test error");
    FailingClientCall<Object, Object> call = new FailingClientCall<>(error);
    Metadata metadata = new Metadata();

    call.start(listener, metadata); // Must call start first

    call.request(1);
    call.cancel("message", new RuntimeException("cause"));
    call.halfClose();
    call.sendMessage(new Object());

    // Only one onClose should have been called (from start), no additional callbacks.
    assertThat(listener.getCloseStatus()).isEqualTo(error);
    assertThat(listener.getCloseTrailers()).isNotNull();
    assertThat(listener.getCloseTrailers().keys()).isEmpty();
  }

  /** A capturing listener that records the status and trailers from {@link #onClose}. */
  private static final class CapturingListener<T>
      extends NoopClientCall.NoopClientCallListener<T> {
    @Nullable private Status closeStatus;
    @Nullable private Metadata closeTrailers;

    @Override
    public void onClose(Status status, Metadata trailers) {
      this.closeStatus = status;
      this.closeTrailers = trailers;
    }

    @Nullable
    Status getCloseStatus() {
      return closeStatus;
    }

    @Nullable
    Metadata getCloseTrailers() {
      return closeTrailers;
    }
  }
}
