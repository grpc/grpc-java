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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.xds.internal.extauthz.ExtAuthzTestHelper.CapturingListener;
import io.grpc.xds.internal.headermutations.HeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderMutator;
import io.grpc.xds.internal.headermutations.HeaderValueOption;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Unit tests for {@link FailingCallWithTrailerMutations}.
 */
@RunWith(JUnit4.class)
public class FailingCallWithTrailerMutationsTest {

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  private HeaderMutator mockHeaderMutator;

  private HeaderMutations responseMutations;
  private Status failStatus;
  private FailingCallWithTrailerMutations<Void, Void> failingCall;

  @Before
  public void setUp() {
    HeaderValueOption option = HeaderValueOption.create(
        io.grpc.xds.internal.grpcservice.HeaderValue.create("test-key", "test-value"),
        HeaderValueOption.HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD);
    responseMutations = HeaderMutations.create(ImmutableList.of(option), ImmutableList.of());
    failStatus = Status.PERMISSION_DENIED.withDescription("authz denied");

    failingCall = new FailingCallWithTrailerMutations<>(
        failStatus,
        responseMutations,
        mockHeaderMutator);
  }

  @Test
  public void start_appliesTrailersMutationsAndClosesListener() {
    Metadata headers = new Metadata();
    CapturingListener<Void> listener = new CapturingListener<>();

    // Trigger start on the failing call
    failingCall.start(listener, headers);

    // 1. Verify that trailers are lazily allocated and mutations are applied to them
    verify(mockHeaderMutator).applyMutations(eq(responseMutations), any(Metadata.class));

    // 2. Verify that the listener was closed with the correct status
    assertThat(listener.getCloseStatus()).isEqualTo(failStatus);
    assertThat(listener.getCloseTrailers()).isNotNull();
  }

  @Test
  public void outboundMethods_areNoOpsAndSafe() {
    // Verify that calling any outbound transport methods does not throw any exceptions
    failingCall.sendMessage(null);
    failingCall.request(1);
    failingCall.halfClose();
    failingCall.cancel("cancel", null);
  }

  @Test
  public void start_calledTwice_firesOnCloseTwice() {
    CapturingListener<Void> firstListener = new CapturingListener<>();
    Metadata headers1 = new Metadata();
    failingCall.start(firstListener, headers1);
    assertThat(firstListener.getCloseStatus()).isEqualTo(failStatus);

    // Call start again with a different listener
    CapturingListener<Void> secondListener = new CapturingListener<>();
    Metadata headers2 = new Metadata();
    failingCall.start(secondListener, headers2);
    assertThat(secondListener.getCloseStatus()).isEqualTo(failStatus);
  }
}
