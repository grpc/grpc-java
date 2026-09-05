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
import static io.grpc.binder.internal.TransactionBuilder.newOutOfBandCloseTxnBuilder;
import static io.grpc.binder.internal.TransactionBuilder.newStreamTxnToClientBuilder;
import static io.grpc.binder.internal.TransactionBuilder.utf8;
import static io.grpc.binder.internal.TransactionUtils.FLAG_MESSAGE_DATA;
import static org.mockito.Mockito.mock;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.os.Looper;
import android.os.Parcel;
import androidx.test.core.app.ApplicationProvider;
import io.grpc.CallOptions;
import io.grpc.ClientStreamTracer;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StringMarshaller;
import io.grpc.binder.AndroidComponentAddress;
import io.grpc.internal.ClientStream;
import io.grpc.internal.ClientStreamListener;
import io.grpc.internal.FixedObjectPool;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.StreamListener.MessageProducer;
import java.net.SocketAddress;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Unit tests for {@link Inbound.ClientInbound}.
 *
 * <p>Both ClientInbound and ServerInbound share certain functionality from Inbound like message
 * reassembly and flow control. This file tests both {@link Inbound.ClientInbound} specifics and the
 * functionality common to both Inbounds to avoid duplicating tests in {@link ServerInboundTest}.
 *
 * <p>Threading model: All Executors lead to Robolectric's main thread, where everything runs,
 * including the test cases themselves. This makes it easy to write deterministic positive and
 * negative assertions about listener callbacks because we can drain all executors and know that if
 * the SUT was going to do something, it would have already happened. It certainly isn't realistic
 * with respect to concurrency but that aspect is integration tested elsewhere (at a higher level).
 */
@RunWith(RobolectricTestRunner.class)
public final class ClientInboundTest {

  private static final Metadata.Key<String> SOME_METADATA_KEY =
      Metadata.Key.of("some-metadata-key", Metadata.ASCII_STRING_MARSHALLER);

  private static final MethodDescriptor<String, String> methodDescriptor =
      MethodDescriptor.<String, String>newBuilder()
          .setType(MethodDescriptor.MethodType.UNKNOWN)
          .setFullMethodName("package.Service/Method")
          .setRequestMarshaller(StringMarshaller.INSTANCE)
          .setResponseMarshaller(StringMarshaller.INSTANCE)
          .build();

  private BinderClientTransport transport;
  private Inbound.ClientInbound inbound;
  private int nextTxIndex; // Only used in test cases where the index value is unimportant.
  private ClientStream clientStream;
  private FakeClientStreamListener listener;

  @Before
  public void setUp() throws Exception {
    // Inbound is presently impossible to create in isolation. We need a dummy instance of the
    // transport to own new Inbounds and provide its deps. TODO(jdcormie): Refactor Inbound so it
    // can be unit tested without hacks.
    transport = createDummyTransport();
    listener = new FakeClientStreamListener();
    clientStream =
        transport.newStream(
            methodDescriptor, new Metadata(), CallOptions.DEFAULT, new ClientStreamTracer[0]);
    clientStream.start(listener);
    clientStream.writeMessage(methodDescriptor.getRequestMarshaller().stream("request"));
    inbound =
        (Inbound.ClientInbound) transport.getOngoingCalls().get(BinderTransport.FIRST_CALL_ID);
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

  @Test
  public void singleMessageEndToEndDelivery() throws Exception {
    newStreamTxnToClientBuilder(nextTxIndex++).withPrefix(new Metadata()).dispatchTo(inbound);
    clientStream.request(1);

    newStreamTxnToClientBuilder(nextTxIndex++)
        .withMessage(utf8("single-message-content"))
        .dispatchTo(inbound);
    newStreamTxnToClientBuilder(nextTxIndex++)
        .withSuffix(Status.OK, new Metadata())
        .dispatchTo(inbound);

    drainExecutors();
    assertThat(listener.getReadMessages()).containsExactly("single-message-content");
    assertThat(listener.isClosed()).isTrue();
    assertThat(listener.getClosedStatus()).isOk();
    assertThat(listener.getClosedTrailers().keys()).isEmpty();
  }

  @Test
  public void multiMessageStreamingDelivery() throws Exception {
    newStreamTxnToClientBuilder(nextTxIndex++).withPrefix(new Metadata()).dispatchTo(inbound);
    clientStream.request(3);

    newStreamTxnToClientBuilder(nextTxIndex++)
        .withMessage(utf8("stream-message-1"))
        .dispatchTo(inbound);
    newStreamTxnToClientBuilder(nextTxIndex++)
        .withMessage(utf8("stream-message-2"))
        .dispatchTo(inbound);
    newStreamTxnToClientBuilder(nextTxIndex++)
        .withMessage(utf8("stream-message-3"))
        .dispatchTo(inbound);
    newStreamTxnToClientBuilder(nextTxIndex++)
        .withSuffix(Status.OK, new Metadata())
        .dispatchTo(inbound);

    drainExecutors();
    assertThat(listener.getReadMessages())
        .containsExactly("stream-message-1", "stream-message-2", "stream-message-3")
        .inOrder();
    assertThat(listener.isClosed()).isTrue();
    assertThat(listener.getClosedStatus()).isOk();
    assertThat(listener.getClosedTrailers().keys()).isEmpty();
  }

  @Test
  public void oversizedMessageLengthHeaderAbortsStream() throws Exception {
    newStreamTxnToClientBuilder(nextTxIndex++).withPrefix(new Metadata()).dispatchTo(inbound);
    clientStream.request(1);

    Parcel parcel = Parcel.obtain();
    try {
      parcel.writeInt(0); // placeholder for flags
      parcel.writeInt(nextTxIndex++);
      parcel.writeInt(1000); // claim message length 1000, but write nothing else
      TransactionUtils.fillInFlags(parcel, FLAG_MESSAGE_DATA);
      parcel.setDataPosition(0);
      inbound.handleTransaction(parcel);
    } finally {
      parcel.recycle();
    }

    drainExecutors();
    assertThat(listener.isClosed()).isTrue();
    assertThat(listener.getClosedStatus()).hasCode(Status.Code.INTERNAL);
    assertThat(listener.getClosedStatus().getDescription())
        .contains("Message size is larger than remaining parcel size");
    assertThat(listener.getReadMessages()).isEmpty();
  }

  @Test
  public void zeroByteMessagePayload() throws Exception {
    newStreamTxnToClientBuilder(nextTxIndex++).withPrefix(new Metadata()).dispatchTo(inbound);
    clientStream.request(1);

    newStreamTxnToClientBuilder(nextTxIndex++).withMessage(utf8("")).dispatchTo(inbound);
    newStreamTxnToClientBuilder(nextTxIndex++)
        .withSuffix(Status.OK, new Metadata())
        .dispatchTo(inbound);

    drainExecutors();
    assertThat(listener.getReadMessages()).containsExactly("");
    assertThat(listener.isClosed()).isTrue();
    assertThat(listener.getClosedStatus()).isOk();
    assertThat(listener.getClosedTrailers().keys()).isEmpty();
  }

  @Test
  public void partialConsumptionFromMessageProducer() throws Exception {
    newStreamTxnToClientBuilder(nextTxIndex++).withPrefix(new Metadata()).dispatchTo(inbound);
    clientStream.request(2);
    listener.setReadPermits(1);

    newStreamTxnToClientBuilder(nextTxIndex++)
        .withMessage(utf8("partial-consume-1"))
        .dispatchTo(inbound);
    newStreamTxnToClientBuilder(nextTxIndex++)
        .withMessage(utf8("partial-consume-2"))
        .dispatchTo(inbound);

    drainExecutors();
    // Only partial-consume-1 was read by the listener even though 2 were requested
    assertThat(listener.getReadMessages()).containsExactly("partial-consume-1");

    MessageProducer producer = listener.pollMessageProducer();
    assertThat(producer).isNotNull();

    // Explicitly consume the second message from the producer outside the callback
    String unconsumedMessage = FakeStreamListener.readString(producer.next());
    assertThat(unconsumedMessage).isEqualTo("partial-consume-2");

    // Producer is now drained
    assertThat(producer.next()).isNull();
    assertThat(listener.pollMessageProducer()).isNull();

    // Listener read messages remain strictly unchanged
    assertThat(listener.getReadMessages()).containsExactly("partial-consume-1");
  }

  @Test
  public void deferredReadFlowControlMultipleMessagesAndSuffix() throws Exception {
    newStreamTxnToClientBuilder(nextTxIndex++).withPrefix(new Metadata()).dispatchTo(inbound);

    // Messages and suffix arrive while 0 messages are requested.
    newStreamTxnToClientBuilder(nextTxIndex++)
        .withMessage(utf8("deferred-msg-1"))
        .dispatchTo(inbound);
    newStreamTxnToClientBuilder(nextTxIndex++)
        .withMessage(utf8("deferred-msg-2"))
        .dispatchTo(inbound);
    newStreamTxnToClientBuilder(nextTxIndex++)
        .withMessage(utf8("deferred-msg-3"))
        .dispatchTo(inbound);
    newStreamTxnToClientBuilder(nextTxIndex++)
        .withSuffix(Status.OK, new Metadata())
        .dispatchTo(inbound);

    // Initially 0 requested messages: nothing delivered, suffix not delivered.
    drainExecutors();
    assertThat(listener.getReadMessages()).isEmpty();
    assertThat(listener.isClosed()).isFalse();

    // Request 1 message: deferred-msg-1 delivered, suffix still not delivered.
    clientStream.request(1);
    drainExecutors();
    assertThat(listener.getReadMessages()).containsExactly("deferred-msg-1");
    assertThat(listener.isClosed()).isFalse();

    // Request 1 message: deferred-msg-2 delivered, suffix still not delivered.
    clientStream.request(1);
    drainExecutors();
    assertThat(listener.getReadMessages())
        .containsExactly("deferred-msg-1", "deferred-msg-2")
        .inOrder();
    assertThat(listener.isClosed()).isFalse();

    // Request 1 message: deferred-msg-3 delivered, all messages consumed, suffix is now delivered.
    clientStream.request(1);
    drainExecutors();
    assertThat(listener.getReadMessages())
        .containsExactly("deferred-msg-1", "deferred-msg-2", "deferred-msg-3")
        .inOrder();
    assertThat(listener.isClosed()).isTrue();
    assertThat(listener.getClosedStatus()).isOk();
    assertThat(listener.getClosedTrailers().keys()).isEmpty();
  }

  @Test
  public void multiPacketBlockFragmentationAndReassembly() throws Exception {
    newStreamTxnToClientBuilder(nextTxIndex++).withPrefix(new Metadata()).dispatchTo(inbound);
    clientStream.request(1);

    newStreamTxnToClientBuilder(nextTxIndex++)
        .withMessageFragment(utf8("first"))
        .dispatchTo(inbound);
    newStreamTxnToClientBuilder(nextTxIndex++)
        .withMessageFragment(utf8("second"))
        .dispatchTo(inbound);
    newStreamTxnToClientBuilder(nextTxIndex++)
        .withFinalMessageFragment(utf8("third"))
        .dispatchTo(inbound);
    newStreamTxnToClientBuilder(nextTxIndex++)
        .withSuffix(Status.OK, new Metadata())
        .dispatchTo(inbound);

    drainExecutors();
    assertThat(listener.getReadMessages()).containsExactly("firstsecondthird");
    assertThat(listener.isClosed()).isTrue();
    assertThat(listener.getClosedStatus()).isOk();
    assertThat(listener.getClosedTrailers().keys()).isEmpty();
  }

  @Test
  public void outOfOrderMessageDeliveredBeforePrefix() throws Exception {
    // Deliver message (Tx 1) BEFORE prefix (Tx 0)
    newStreamTxnToClientBuilder(1).withMessage(utf8("some message")).dispatchTo(inbound);

    // Deliver prefix (Tx 0)
    newStreamTxnToClientBuilder(0).withPrefix(new Metadata()).dispatchTo(inbound);

    // Request message after prefix has arrived
    clientStream.request(1);

    // Deliver suffix (Tx 2)
    newStreamTxnToClientBuilder(2).withSuffix(Status.OK, new Metadata()).dispatchTo(inbound);

    drainExecutors();
    // Verify message and suffix are delivered
    assertThat(listener.getReadMessages()).containsExactly("some message");
    assertThat(listener.isClosed()).isTrue();
    assertThat(listener.getClosedStatus()).isOk();
    assertThat(listener.getClosedTrailers().keys()).isEmpty();
  }

  @Test
  public void sequenceGapCausesBufferingUntilMissingTransactionArrives() throws Exception {
    newStreamTxnToClientBuilder(0).withPrefix(new Metadata()).dispatchTo(inbound);
    clientStream.request(2);

    // Send index 2, skipping expected index 1
    newStreamTxnToClientBuilder(2).withMessage(utf8("gap-message-2")).dispatchTo(inbound);

    drainExecutors();
    assertThat(listener.getReadMessages()).isEmpty();

    // Now send index 1
    newStreamTxnToClientBuilder(1).withMessage(utf8("gap-message-1")).dispatchTo(inbound);

    drainExecutors();
    // Both messages delivered in order
    assertThat(listener.getReadMessages())
        .containsExactly("gap-message-1", "gap-message-2")
        .inOrder();
  }

  @Test
  public void outOfBandClose() throws Exception {
    newStreamTxnToClientBuilder(nextTxIndex++).withPrefix(new Metadata()).dispatchTo(inbound);

    newOutOfBandCloseTxnBuilder(Status.CANCELLED.withDescription("remote cancelled RPC"))
        .dispatchTo(inbound);

    drainExecutors();
    assertThat(listener.isClosed()).isTrue();
    assertThat(listener.getClosedStatus()).hasCode(Status.Code.CANCELLED);
    assertThat(listener.getClosedStatus().getDescription()).contains("remote cancelled RPC");
  }

  @Test
  public void cleanupUnconsumedResourcesOnAbnormalClose() throws Exception {
    newStreamTxnToClientBuilder(nextTxIndex++).withPrefix(new Metadata()).dispatchTo(inbound);
    // Do not request messages so messages remain unconsumed in Inbound
    newStreamTxnToClientBuilder(nextTxIndex++)
        .withMessage(utf8("unconsumed-1"))
        .dispatchTo(inbound);
    newStreamTxnToClientBuilder(nextTxIndex++)
        .withMessage(utf8("unconsumed-2"))
        .dispatchTo(inbound);
    newStreamTxnToClientBuilder(nextTxIndex++)
        .withMessageFragment(utf8("unconsumed-partial-block"))
        .dispatchTo(inbound);

    // Abort abnormally via out-of-band close
    newOutOfBandCloseTxnBuilder(Status.UNAVAILABLE.withDescription("aborted")).dispatchTo(inbound);

    drainExecutors();
    assertThat(transport.getOngoingCalls()).isEmpty();
    assertThat(listener.isClosed()).isTrue();
    assertThat(listener.getClosedStatus()).hasCode(Status.Code.UNAVAILABLE);
    assertThat(listener.getClosedStatus().getDescription()).contains("aborted");
    assertThat(listener.getReadMessages()).isEmpty();
  }

  @Test
  public void transactionsIgnoredAfterClosed() throws Exception {
    newStreamTxnToClientBuilder(nextTxIndex++).withPrefix(new Metadata()).dispatchTo(inbound);
    newOutOfBandCloseTxnBuilder(Status.CANCELLED).dispatchTo(inbound);

    drainExecutors();
    assertThat(listener.isClosed()).isTrue();
    assertThat(listener.getClosedStatus()).hasCode(Status.Code.CANCELLED);

    // Any subsequent transaction should be ignored silently
    newStreamTxnToClientBuilder(nextTxIndex++).withMessage(utf8("ignored")).dispatchTo(inbound);
    drainExecutors();
    assertThat(listener.getReadMessages()).isEmpty();
  }

  @Test
  public void allInOneUnaryTransaction() throws Exception {
    newStreamTxnToClientBuilder(0)
        .withPrefix(new Metadata())
        .withMessage(utf8("all-in-one-message"))
        .withSuffix(Status.OK, new Metadata())
        .dispatchTo(inbound);
    clientStream.request(1);

    drainExecutors();
    assertThat(listener.getReadMessages()).containsExactly("all-in-one-message");
    assertThat(listener.isClosed()).isTrue();
    assertThat(listener.getClosedStatus()).isOk();
    assertThat(listener.getClosedTrailers().keys()).isEmpty();
  }

  @Test
  public void suffixWithNonOkStatusAndTrailers() throws Exception {
    newStreamTxnToClientBuilder(nextTxIndex++).withPrefix(new Metadata()).dispatchTo(inbound);
    Metadata trailers = new Metadata();
    trailers.put(SOME_METADATA_KEY, "trailer-val");

    newStreamTxnToClientBuilder(nextTxIndex++)
        .withSuffix(Status.NOT_FOUND.withDescription("item not found"), trailers)
        .dispatchTo(inbound);

    drainExecutors();
    assertThat(listener.isClosed()).isTrue();
    assertThat(listener.getClosedStatus()).hasCode(Status.Code.NOT_FOUND);
    assertThat(listener.getClosedStatus().getDescription()).isEqualTo("item not found");
    assertThat(listener.getClosedTrailers().get(SOME_METADATA_KEY)).isEqualTo("trailer-val");
  }

  @Test
  public void prefixDeliversHeadersToListener() throws Exception {
    Metadata headers = new Metadata();
    headers.put(SOME_METADATA_KEY, "header-value");

    newStreamTxnToClientBuilder(nextTxIndex++).withPrefix(headers).dispatchTo(inbound);

    drainExecutors();
    assertThat(listener.getHeaders().get(SOME_METADATA_KEY)).isEqualTo("header-value");
  }

  @Test
  public void countsForInUse() {
    assertThat(inbound.countsForInUse()).isTrue();

    ClientStream balancerStream =
        transport.newStream(
            methodDescriptor,
            new Metadata(),
            CallOptions.DEFAULT.withOption(GrpcUtil.CALL_OPTIONS_RPC_OWNED_BY_BALANCER, true),
            new ClientStreamTracer[0]);
    balancerStream.start(mock(ClientStreamListener.class));
    Inbound.ClientInbound notInUseInbound =
        (Inbound.ClientInbound) transport.getOngoingCalls().get(inbound.callId + 1);
    assertThat(notInUseInbound.countsForInUse()).isFalse();
  }

  @Test
  public void excessRequestedMessagesDeliverCleanlyOnSuffix() throws Exception {
    newStreamTxnToClientBuilder(nextTxIndex++).withPrefix(new Metadata()).dispatchTo(inbound);
    clientStream.request(5);

    newStreamTxnToClientBuilder(nextTxIndex++).withMessage(utf8("msg1")).dispatchTo(inbound);
    newStreamTxnToClientBuilder(nextTxIndex++).withMessage(utf8("msg2")).dispatchTo(inbound);
    newStreamTxnToClientBuilder(nextTxIndex++)
        .withSuffix(Status.OK, new Metadata())
        .dispatchTo(inbound);

    drainExecutors();
    assertThat(listener.getReadMessages()).containsExactly("msg1", "msg2").inOrder();
    assertThat(listener.isClosed()).isTrue();
    assertThat(listener.getClosedStatus()).isOk();
  }

  @Test
  public void clientCancelUnregisters() throws Exception {
    newStreamTxnToClientBuilder(0).withPrefix(new Metadata()).dispatchTo(inbound);
    newStreamTxnToClientBuilder(1)
        .withMessageFragment(utf8("unconsumed-partial"))
        .dispatchTo(inbound);

    clientStream.cancel(Status.CANCELLED.withDescription("client cancel"));

    drainExecutors();
    assertThat(transport.getOngoingCalls()).doesNotContainKey(inbound.callId);
  }

  @Test
  public void abnormalCloseBeforeStreamStartDoesNotThrow() throws Exception {
    transport.newStream(
        methodDescriptor, new Metadata(), CallOptions.DEFAULT, new ClientStreamTracer[0]);
    Inbound.ClientInbound unstartedInbound =
        (Inbound.ClientInbound) transport.getOngoingCalls().get(inbound.callId + 1);
    assertThat(transport.getOngoingCalls()).containsKey(unstartedInbound.callId);

    newOutOfBandCloseTxnBuilder(Status.CANCELLED.withDescription("remote abort"))
        .dispatchTo(unstartedInbound);

    drainExecutors();
    assertThat(transport.getOngoingCalls()).doesNotContainKey(unstartedInbound.callId);
  }

  private static BinderClientTransport createDummyTransport() {
    MainThreadScheduledExecutorService mainThreadExecutor =
        new MainThreadScheduledExecutorService();
    BinderClientTransportFactory factory =
        new BinderClientTransportFactory.Builder()
            .setSourceContext(ApplicationProvider.getApplicationContext())
            .setOffloadExecutorPool(new FixedObjectPool<>(mainThreadExecutor))
            .setScheduledExecutorPool(new FixedObjectPool<>(mainThreadExecutor))
            .buildClientTransportFactory();
    SocketAddress serverAddress =
        AndroidComponentAddress.forComponent(new ComponentName("fake.pkg", "fake.cls"));
    BinderClientTransport transport =
        new BinderClientTransportBuilder()
            .setFactory(factory)
            .setServerAddress(serverAddress)
            .build();
    // This hack lets us create a transport without the need for a real server for handshaking.
    synchronized (transport) {
      // Blackhole Outbound.
      transport.setOutgoingBinder(mock(OneWayBinderProxy.class));
    }
    Runnable unused = transport.start(mock(ManagedClientTransport.Listener.class));
    synchronized (transport) {
      transport.setState(BinderTransport.TransportState.READY);
    }
    return transport;
  }
}
