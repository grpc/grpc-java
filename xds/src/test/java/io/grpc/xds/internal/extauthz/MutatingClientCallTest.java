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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.xds.internal.extauthz.ExtAuthzTestHelper.CapturingClientCall;
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
 * Unit tests for {@link MutatingClientCall}.
 */
@RunWith(JUnit4.class)
public class MutatingClientCallTest {

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  private final CapturingClientCall<Void, Void> mockDelegateCall = new CapturingClientCall<>();
  @Mock
  private HeaderMutator mockHeaderMutator;
  private final CapturingListener<Void> mockResponseListener = new CapturingListener<>();

  private HeaderMutations requestMutations;
  private HeaderMutations responseMutations;
  private MutatingClientCall<Void, Void> mutatingCall;

  @Before
  public void setUp() {
    HeaderValueOption option = HeaderValueOption.create(
        io.grpc.xds.internal.grpcservice.HeaderValue.create("test-key", "test-value"),
        HeaderValueOption.HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD);
    requestMutations = HeaderMutations.create(ImmutableList.of(option), ImmutableList.of());
    responseMutations = HeaderMutations.create(ImmutableList.of(option), ImmutableList.of());

    mutatingCall = new MutatingClientCall<>(
        mockDelegateCall,
        requestMutations,
        responseMutations,
        mockHeaderMutator);
  }

  @Test
  public void start_appliesRequestMutationsSymmetrically() {
    Metadata headers = new Metadata();

    // Trigger start
    mutatingCall.start(mockResponseListener, headers);

    // 1. Verify request mutations are applied lazily to headers before forwarding start
    verify(mockHeaderMutator).applyMutations(eq(requestMutations), eq(headers));

    // 2. Verify delegate start was triggered with the mutated headers and captured listener
    assertThat(mockDelegateCall.isStarted()).isTrue();
    assertThat(mockDelegateCall.getHeaders()).isSameInstanceAs(headers);
    assertThat(mockDelegateCall.getListener()).isNotNull();
  }

  @Test
  public void onHeaders_appliesResponseMutationsSymmetrically() {
    Metadata headers = new Metadata();
    mutatingCall.start(mockResponseListener, headers);

    // Get the wrapped listener that was passed to delegate
    assertThat(mockDelegateCall.getListener()).isNotNull();

    // Trigger onHeaders on captured listener
    Metadata responseHeaders = new Metadata();
    mockDelegateCall.getListener().onHeaders(responseHeaders);

    // 1. Verify response mutations are applied lazily to response headers
    verify(mockHeaderMutator).applyMutations(eq(responseMutations), eq(responseHeaders));

    // 2. Verify response headers were forwarded to real listener
    assertThat(mockResponseListener.getHeaders()).isSameInstanceAs(responseHeaders);
  }

  @Test
  public void listenerCallbacks_areDelegatedCorrectly() {
    Metadata headers = new Metadata();
    mutatingCall.start(mockResponseListener, headers);

    assertThat(mockDelegateCall.getListener()).isNotNull();

    // Test onMessage delegation
    mockDelegateCall.getListener().onMessage(null);
    assertThat(mockResponseListener.getMessages()).containsExactly((Void) null);

    // Test onReady delegation
    mockDelegateCall.getListener().onReady();
    assertThat(mockResponseListener.isOnReadyCalled()).isTrue();

    // Test onClose delegation
    Status expectedStatus = Status.OK;
    Metadata expectedTrailers = new Metadata();
    mockDelegateCall.getListener().onClose(expectedStatus, expectedTrailers);
    assertThat(mockResponseListener.getCloseStatus()).isEqualTo(expectedStatus);
    assertThat(mockResponseListener.getCloseTrailers()).isSameInstanceAs(expectedTrailers);
  }

  @Test
  public void emptyRequestMutations_nonEmptyResponseMutations_wrapsListener() {
    HeaderMutations emptyRequest =
        HeaderMutations.create(ImmutableList.of(), ImmutableList.of());
    CapturingClientCall<Void, Void> delegate = new CapturingClientCall<>();
    MutatingClientCall<Void, Void> call = new MutatingClientCall<>(
        delegate, emptyRequest, responseMutations, mockHeaderMutator);

    Metadata headers = new Metadata();
    CapturingListener<Void> listener = new CapturingListener<>();
    call.start(listener, headers);

    // Request mutations should still be called (even if empty)
    verify(mockHeaderMutator).applyMutations(eq(emptyRequest), eq(headers));

    // Verify delegate was started with a wrapped listener
    assertThat(delegate.isStarted()).isTrue();
    assertThat(delegate.getHeaders()).isSameInstanceAs(headers);
    assertThat(delegate.getListener()).isNotNull();

    // Trigger onHeaders and verify response mutations are applied
    Metadata responseHeaders = new Metadata();
    delegate.getListener().onHeaders(responseHeaders);
    verify(mockHeaderMutator).applyMutations(
        eq(responseMutations), eq(responseHeaders));
    assertThat(listener.getHeaders()).isSameInstanceAs(responseHeaders);
  }

  @Test
  public void start_emptyResponseMutations_headersPassThroughUnmodified() {
    HeaderMutations emptyResponseMutations =
        HeaderMutations.create(ImmutableList.of(), ImmutableList.of());
    CapturingClientCall<Void, Void> delegate = new CapturingClientCall<>();
    MutatingClientCall<Void, Void> call = new MutatingClientCall<>(
        delegate, requestMutations, emptyResponseMutations, mockHeaderMutator);

    CapturingListener<Void> listener = new CapturingListener<>();
    Metadata headers = new Metadata();
    call.start(listener, headers);

    // Request mutations should be applied
    verify(mockHeaderMutator).applyMutations(eq(requestMutations), eq(headers));

    // Simulate receiving response headers from the server
    Metadata.Key<String> responseKey =
        Metadata.Key.of("x-response-header", Metadata.ASCII_STRING_MARSHALLER);
    Metadata responseHeaders = new Metadata();
    responseHeaders.put(responseKey, "original-value");
    delegate.getListener().onHeaders(responseHeaders);

    // Verify: headers pass through UNMODIFIED — no response mutation applied
    assertThat(listener.getHeaders().get(responseKey)).isEqualTo("original-value");
    // headerMutator should NOT have been called again for response mutations
    org.mockito.Mockito.verifyNoMoreInteractions(mockHeaderMutator);
  }
}
