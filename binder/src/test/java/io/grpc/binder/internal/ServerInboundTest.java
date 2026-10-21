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

package io.grpc.binder.internal;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.StatusSubject.assertThat;
import static io.grpc.binder.internal.BinderTransport.FIRST_CALL_ID;
import static io.grpc.binder.internal.TransactionBuilder.newOutOfBandCloseTxnBuilder;
import static io.grpc.binder.internal.TransactionBuilder.newStreamTxnToServerBuilder;
import static io.grpc.binder.internal.TransactionBuilder.utf8;
import static java.util.Objects.requireNonNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.os.IBinder;
import android.os.Looper;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.binder.internal.FakeServerTransportListener.CreatedStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Unit tests for {@link Inbound.ServerInbound}.
 *
 * <p>Focuses on server-specific inbound behaviors like method name extraction, initial headers and
 * half-close handling. Inbound functions common to both client and server are tested in {@link
 * ClientInboundTest}.
 *
 * <p>Threading model: All Executors lead to Robolectric's main thread, where everything runs,
 * including the test cases themselves. This makes it easy to write deterministic positive and
 * negative assertions about listener callbacks because we can drain all executors and know that if
 * the SUT was going to do something, it would have already happened. It certainly isn't realistic
 * with respect to concurrency but that aspect is integration tested elsewhere (at a higher level).
 */
@RunWith(RobolectricTestRunner.class)
public final class ServerInboundTest {

  private static final Metadata.Key<String> SOME_METADATA_KEY =
      Metadata.Key.of("some-metadata-key", Metadata.ASCII_STRING_MARSHALLER);

  private BinderServerTransport transport;
  private int nextTxIndex; // Only used in test cases where the index value is unimportant.
  private FakeServerTransportListener<FakeServerStreamListener> transportListener;
  private CreatedStream<FakeServerStreamListener> createdStream;

  @Before
  public void setUp() throws Exception {
    // ServerInbound is presently impossible to create in isolation. We need a dummy instance of the
    // transport to own new Inbounds and provide its deps. TODO(jdcormie): Refactor Inbound so it
    // can be unit tested without hacks.
    IBinder mockBinder = mock(IBinder.class); // Black hole Outbound.
    when(mockBinder.transact(anyInt(), any(), any(), anyInt())).thenReturn(true);
    transport = new BinderServerTransportBuilder().setCallbackBinder(mockBinder).build();

    transportListener = new FakeServerTransportListener<>(FakeServerStreamListener::new);
    transport.start(transportListener);
  }

  /**
   * Drains any pending asynchronous tasks on transport executors before asserting state.
   *
   * <p>In this single-threaded test model, tasks posted to the main looper or transport executors
   * are executed synchronously when the looper is idled.
   */
  private void drainExecutors() {
    shadowOf(Looper.getMainLooper()).idle();
  }

  private static Inbound.ServerInbound getInboundOrDie(BinderTransport transport, int callId) {
    return (Inbound.ServerInbound) requireNonNull(transport.getOngoingCalls().get(callId));
  }

  @Test
  public void prefixInitializesServerStreamWithMethodAndHeaders() throws Exception {
    Metadata headers = new Metadata();
    headers.put(SOME_METADATA_KEY, "server-val");

    newStreamTxnToServerBuilder(nextTxIndex++)
        .withPrefix("my.custom.package.Service/StreamingCall", headers)
        .dispatchTo(transport, FIRST_CALL_ID);

    drainExecutors();
    createdStream = transportListener.getOnlyCreatedStream();
    assertThat(createdStream.getMethodName())
        .isEqualTo("my.custom.package.Service/StreamingCall");
    assertThat(createdStream.getHeaders().get(SOME_METADATA_KEY)).isEqualTo("server-val");
    assertThat(createdStream.getStream()).isInstanceOf(MultiMessageServerStream.class);
    assertThat(transport.getOngoingCalls()).containsKey(FIRST_CALL_ID);
  }

  @Test
  public void singleMessageStreamTypeDetectedFromFlag() throws Exception {
    newStreamTxnToServerBuilder(nextTxIndex++)
        .withPrefix("service/UnaryCall", new Metadata())
        .withExpectSingleMessage()
        .dispatchTo(transport, FIRST_CALL_ID);

    drainExecutors();
    createdStream = transportListener.getOnlyCreatedStream();
    assertThat(createdStream.getMethodName()).isEqualTo("service/UnaryCall");
    assertThat(createdStream.getStream()).isInstanceOf(SingleMessageServerStream.class);
  }

  @Test
  public void singleMessageEndToEndDelivery() throws Exception {
    newStreamTxnToServerBuilder(nextTxIndex++)
        .withPrefix("package.Service/Method", new Metadata())
        .dispatchTo(transport, FIRST_CALL_ID);
    Inbound.ServerInbound inbound = getInboundOrDie(transport, FIRST_CALL_ID);
    createdStream = transportListener.getOnlyCreatedStream();
    createdStream.getStream().request(1);

    newStreamTxnToServerBuilder(nextTxIndex++)
        .withMessage(utf8("server-request-content"))
        .dispatchTo(inbound);
    newStreamTxnToServerBuilder(nextTxIndex++).withSuffix().dispatchTo(inbound);

    drainExecutors();
    assertThat(createdStream.getStreamListener().getReadMessages()).containsExactly("server-request-content");
    assertThat(createdStream.getStreamListener().isHalfClosed()).isTrue();
    assertThat(transport.getOngoingCalls()).containsKey(inbound.callId);
    assertThat(createdStream.getStreamListener().isClosed()).isFalse();
  }

  @Test
  public void multiMessageStreamingDelivery() throws Exception {
    newStreamTxnToServerBuilder(nextTxIndex++)
        .withPrefix("package.Service/Method", new Metadata())
        .dispatchTo(transport, FIRST_CALL_ID);
    Inbound.ServerInbound inbound = getInboundOrDie(transport, FIRST_CALL_ID);
    createdStream = transportListener.getOnlyCreatedStream();
    createdStream.getStream().request(3);

    newStreamTxnToServerBuilder(nextTxIndex++)
        .withMessage(utf8("server-request-1"))
        .dispatchTo(inbound);
    newStreamTxnToServerBuilder(nextTxIndex++)
        .withMessage(utf8("server-request-2"))
        .dispatchTo(inbound);
    newStreamTxnToServerBuilder(nextTxIndex++)
        .withMessage(utf8("server-request-3"))
        .dispatchTo(inbound);
    newStreamTxnToServerBuilder(nextTxIndex++).withSuffix().dispatchTo(inbound);

    drainExecutors();
    assertThat(createdStream.getStreamListener().getReadMessages())
        .containsExactly("server-request-1", "server-request-2", "server-request-3")
        .inOrder();
    assertThat(createdStream.getStreamListener().isHalfClosed()).isTrue();
    assertThat(transport.getOngoingCalls()).containsKey(inbound.callId);
    assertThat(createdStream.getStreamListener().isClosed()).isFalse();
  }

  @Test
  public void deferredReadFlowControlMultipleMessagesAndSuffix() throws Exception {
    newStreamTxnToServerBuilder(nextTxIndex++)
        .withPrefix("package.Service/Method", new Metadata())
        .dispatchTo(transport, FIRST_CALL_ID);
    Inbound.ServerInbound inbound = getInboundOrDie(transport, FIRST_CALL_ID);
    createdStream = transportListener.getOnlyCreatedStream();

    // Messages and suffix arrive while 0 messages are requested.
    newStreamTxnToServerBuilder(nextTxIndex++)
        .withMessage(utf8("deferred-msg-1"))
        .dispatchTo(inbound);
    newStreamTxnToServerBuilder(nextTxIndex++)
        .withMessage(utf8("deferred-msg-2"))
        .dispatchTo(inbound);
    newStreamTxnToServerBuilder(nextTxIndex++)
        .withMessage(utf8("deferred-msg-3"))
        .dispatchTo(inbound);
    newStreamTxnToServerBuilder(nextTxIndex++).withSuffix().dispatchTo(inbound);

    // Initially 0 requested messages: nothing delivered, half-close not delivered.
    drainExecutors();
    assertThat(createdStream.getStreamListener().getReadMessages()).isEmpty();
    assertThat(createdStream.getStreamListener().isHalfClosed()).isFalse();
    assertThat(transport.getOngoingCalls()).containsKey(inbound.callId);

    // Request 1 message: deferred-msg-1 delivered, half-close still not delivered.
    createdStream.getStream().request(1);
    drainExecutors();
    assertThat(createdStream.getStreamListener().getReadMessages()).containsExactly("deferred-msg-1");
    assertThat(createdStream.getStreamListener().isHalfClosed()).isFalse();

    // Request 1 message: deferred-msg-2 delivered, half-close still not delivered.
    createdStream.getStream().request(1);
    drainExecutors();
    assertThat(createdStream.getStreamListener().getReadMessages())
        .containsExactly("deferred-msg-1", "deferred-msg-2")
        .inOrder();
    assertThat(createdStream.getStreamListener().isHalfClosed()).isFalse();

    // Request 1 message: deferred-msg-3 delivered, all messages consumed, half-close is now delivered.
    createdStream.getStream().request(1);
    drainExecutors();
    assertThat(createdStream.getStreamListener().getReadMessages())
        .containsExactly("deferred-msg-1", "deferred-msg-2", "deferred-msg-3")
        .inOrder();
    assertThat(createdStream.getStreamListener().isHalfClosed()).isTrue();
    assertThat(transport.getOngoingCalls()).containsKey(inbound.callId);
    assertThat(createdStream.getStreamListener().isClosed()).isFalse();
  }

  @Test
  public void allInOneUnaryTransactionDeliversMessageAndHalfClose() throws Exception {
    newStreamTxnToServerBuilder(0)
        .withPrefix("package.Service/Method", new Metadata())
        .withMessage(utf8("all-in-one-message"))
        .withSuffix()
        .dispatchTo(transport, FIRST_CALL_ID);
    Inbound.ServerInbound inbound = getInboundOrDie(transport, FIRST_CALL_ID);
    createdStream = transportListener.getOnlyCreatedStream();
    createdStream.getStream().request(1);

    drainExecutors();
    assertThat(createdStream.getStreamListener().getReadMessages()).containsExactly("all-in-one-message");
    assertThat(createdStream.getStreamListener().isHalfClosed()).isTrue();
    assertThat(transport.getOngoingCalls()).containsKey(inbound.callId);
    assertThat(createdStream.getStreamListener().isClosed()).isFalse();
  }

  @Test
  public void clientHalfCloseDeliveredToListener() throws Exception {
    newStreamTxnToServerBuilder(nextTxIndex++)
        .withPrefix("package.Service/Method", new Metadata())
        .dispatchTo(transport, FIRST_CALL_ID);
    Inbound.ServerInbound inbound = getInboundOrDie(transport, FIRST_CALL_ID);
    createdStream = transportListener.getOnlyCreatedStream();
    drainExecutors();
    assertThat(createdStream.getStreamListener().isHalfClosed()).isFalse();

    newStreamTxnToServerBuilder(nextTxIndex++).withSuffix().dispatchTo(inbound);

    drainExecutors();
    assertThat(createdStream.getStreamListener().isHalfClosed()).isTrue();
    assertThat(transport.getOngoingCalls()).containsKey(inbound.callId);
    assertThat(createdStream.getStreamListener().isClosed()).isFalse();
  }

  @Test
  public void onCloseSentClosesStream() throws Exception {
    newStreamTxnToServerBuilder(nextTxIndex++)
        .withPrefix("package.Service/Method", new Metadata())
        .dispatchTo(transport, FIRST_CALL_ID);
    Inbound.ServerInbound inbound = getInboundOrDie(transport, FIRST_CALL_ID);
    createdStream = transportListener.getOnlyCreatedStream();
    drainExecutors();
    assertThat(transport.getOngoingCalls()).containsKey(inbound.callId);
    assertThat(createdStream.getStreamListener().isClosed()).isFalse();

    createdStream.getStream().close(Status.OK, new Metadata());
    drainExecutors();
    assertThat(transport.getOngoingCalls()).doesNotContainKey(inbound.callId);
    assertThat(createdStream.getStreamListener().isClosed()).isTrue();
  }

  @Test
  public void outOfBandCloseAbortsServerStream() throws Exception {
    newStreamTxnToServerBuilder(nextTxIndex++)
        .withPrefix("package.Service/Method", new Metadata())
        .dispatchTo(transport, FIRST_CALL_ID);
    Inbound.ServerInbound inbound = getInboundOrDie(transport, FIRST_CALL_ID);
    createdStream = transportListener.getOnlyCreatedStream();

    newOutOfBandCloseTxnBuilder(Status.CANCELLED.withDescription("client cancel"))
        .dispatchTo(inbound);

    drainExecutors();
    assertThat(transport.getOngoingCalls()).doesNotContainKey(inbound.callId);
    assertThat(createdStream.getStreamListener().isClosed()).isTrue();
    assertThat(createdStream.getStreamListener().getClosedStatus()).hasCode(Status.Code.CANCELLED);
    assertThat(createdStream.getStreamListener().getClosedStatus().getDescription()).contains("client cancel");
  }
}
