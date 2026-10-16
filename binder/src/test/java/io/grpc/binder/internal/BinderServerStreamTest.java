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

import static android.os.IBinder.FLAG_ONEWAY;
import static com.google.common.truth.Truth.assertThat;
import static io.grpc.binder.internal.BinderTransport.FIRST_CALL_ID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.FixedObjectPool;
import io.grpc.internal.ServerStream;
import io.grpc.internal.ServerStreamListener;
import io.grpc.internal.ServerTransportListener;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;

/** Tests server closure while another call has filled the transport's transmit window. */
@RunWith(ParameterizedRobolectricTestRunner.class)
public final class BinderServerStreamTest {
  @Rule public MockitoRule mocks = MockitoJUnit.rule();

  @Mock IBinder clientBinder;
  @Mock ServerTransportListener transportListener;
  @Mock ServerStreamListener listener;

  @Parameter public boolean singleMessage;

  @Parameters(name = "singleMessage={0}")
  public static ImmutableList<Object[]> data() {
    return ImmutableList.of(new Object[] {true}, new Object[] {false});
  }

  private BinderServerTransport transport;
  private ServerStream stream;
  private Inbound<?, ?> inbound;
  private final List<Integer> sentFlags = new ArrayList<>();
  private long sentBytes;

  @Before
  public void setUp() throws Exception {
    ScheduledExecutorService executor = new MainThreadScheduledExecutorService();
    when(transportListener.transportReady(any(Attributes.class))).thenReturn(Attributes.EMPTY);
    doAnswer(
            invocation -> recordTransaction(invocation.getArgument(0), invocation.getArgument(1)))
        .when(clientBinder)
        .transact(anyInt(), any(Parcel.class), isNull(), eq(FLAG_ONEWAY));
    doAnswer(
            invocation -> {
              stream = invocation.getArgument(0);
              stream.setListener(listener);
              return null;
            })
        .when(transportListener)
        .streamCreated(any(ServerStream.class), any(String.class), any(Metadata.class));
    transport =
        BinderServerTransport.create(
            new FixedObjectPool<>(executor),
            Attributes.EMPTY,
            ImmutableList.of(),
            OneWayBinderProxy.IDENTITY_DECORATOR,
            clientBinder);
    transport.start(transportListener);
    try (ParcelHolder parcel = ParcelHolder.obtain()) {
      int flags = TransactionUtils.FLAG_PREFIX | TransactionUtils.FLAG_SUFFIX;
      if (singleMessage) {
        flags |= TransactionUtils.FLAG_EXPECT_SINGLE_MESSAGE;
      }
      parcel.get().writeInt(flags);
      parcel.get().writeInt(0);
      parcel.get().writeString("service/method");
      MetadataHelper.writeMetadata(parcel.get(), new Metadata());
      parcel.get().setDataPosition(0);
      assertThat(transport.handleTransaction(FIRST_CALL_ID, parcel.get())).isTrue();
    }
    inbound = transport.getOngoingCalls().get(FIRST_CALL_ID);
    assertThat(inbound).isNotNull();
  }

  @Test
  public void deferredCloseWaitsUntilSuffixIsSent() throws Exception {
    fillTransmitWindow();
    stream.close(Status.OK, new Metadata());

    verify(listener, never()).closed(any(Status.class));
    assertThat(transport.getOngoingCalls()).containsKey(FIRST_CALL_ID);

    acknowledgeBytes();

    verify(listener).closed(Status.OK);
    assertThat(transport.getOngoingCalls()).doesNotContainKey(FIRST_CALL_ID);
    assertThat(sentFlags).hasSize(1);
    assertThat(TransactionUtils.hasFlag(sentFlags.get(0), TransactionUtils.FLAG_SUFFIX)).isTrue();
  }

  @Test
  public void deferredTrailerFailureClosesAndUnregistersCall() throws Exception {
    fillTransmitWindow();
    stream.close(Status.OK, oversizedTrailers());

    acknowledgeBytes();

    assertFailure(Status.Code.RESOURCE_EXHAUSTED);
  }

  @Test
  public void immediateTrailerFailureClosesAndUnregistersCall() {
    stream.close(Status.OK, oversizedTrailers());

    assertFailure(Status.Code.RESOURCE_EXHAUSTED);
  }

  @Test
  public void deferredResponseReadFailureClosesAndUnregistersCall() throws Exception {
    fillTransmitWindow();
    IOException failure = new IOException("response read failed");
    stream.writeMessage(
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw failure;
          }
        });
    stream.close(Status.OK, new Metadata());

    acknowledgeBytes();

    Status status = assertFailure(Status.Code.INTERNAL);
    assertThat(status.getCause()).isSameInstanceAs(failure);
  }

  @Test
  public void deferredSuffixTransactionFailureClosesAndUnregistersCall() throws Exception {
    fillTransmitWindow();
    stream.close(Status.OK, new Metadata());
    doThrow(new RemoteException("send failed"))
        .when(clientBinder)
        .transact(eq(FIRST_CALL_ID), any(Parcel.class), isNull(), eq(FLAG_ONEWAY));

    acknowledgeBytes();

    ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
    verify(listener).closed(status.capture());
    assertThat(status.getValue().isOk()).isFalse();
    assertThat(transport.getOngoingCalls()).doesNotContainKey(FIRST_CALL_ID);
  }

  @Test
  public void deferredResponseTransactionFailureClosesAndUnregistersCall() throws Exception {
    fillTransmitWindow();
    stream.writeMessage(new ByteArrayInputStream(new byte[BlockPool.BLOCK_SIZE + 1]));
    stream.close(Status.OK, new Metadata());
    RemoteException failure = new RemoteException("response transaction failed");
    doAnswer(
            invocation -> {
              Parcel parcel = invocation.getArgument(1);
              parcel.setDataPosition(0);
              int flags = parcel.readInt();
              if (TransactionUtils.hasFlag(flags, TransactionUtils.FLAG_MESSAGE_DATA_IS_PARTIAL)) {
                throw failure;
              }
              return recordTransaction(invocation.getArgument(0), parcel);
            })
        .when(clientBinder)
        .transact(eq(FIRST_CALL_ID), any(Parcel.class), isNull(), eq(FLAG_ONEWAY));

    acknowledgeBytes();

    Status status = assertFailure(Status.Code.INTERNAL);
    assertThat(status.getCause()).isSameInstanceAs(failure);
  }

  @Test
  public void deferredResponseWaitsForMultipleAcknowledgements() throws Exception {
    fillTransmitWindow();
    stream.writeMessage(new ByteArrayInputStream(new byte[9 * BlockPool.BLOCK_SIZE + 1]));
    stream.close(Status.OK, new Metadata());

    acknowledgeBytes();

    verify(listener, never()).closed(any(Status.class));
    assertThat(transport.getOngoingCalls()).containsKey(FIRST_CALL_ID);
    assertThat(stream.isReady()).isFalse();

    acknowledgeBytes();

    verify(listener).closed(Status.OK);
    assertThat(transport.getOngoingCalls()).doesNotContainKey(FIRST_CALL_ID);
    assertThat(
            TransactionUtils.hasFlag(
                sentFlags.get(sentFlags.size() - 1), TransactionUtils.FLAG_SUFFIX))
        .isTrue();
  }

  @Test
  public void noReadyCallbackAfterClose() {
    stream.close(Status.OK, new Metadata());

    inbound.onTransportReady();

    InOrder callbacks = inOrder(listener);
    callbacks.verify(listener).onReady();
    callbacks.verify(listener).closed(Status.OK);
    callbacks.verifyNoMoreInteractions();
  }

  @Test
  public void cancellationWhileCloseIsDeferredUnregistersCall() throws Exception {
    fillTransmitWindow();
    stream.close(Status.OK, new Metadata());

    stream.cancel(Status.CANCELLED);
    acknowledgeBytes();

    assertFailure(Status.Code.CANCELLED);
    assertThat(sentFlags).hasSize(1);
  }

  @Test
  public void readinessCallbackCanCancelDeferredClose() throws Exception {
    fillTransmitWindow();
    stream.close(Status.OK, new Metadata());
    doAnswer(
            invocation -> {
              stream.cancel(Status.CANCELLED);
              return null;
            })
        .when(listener)
        .onReady();

    acknowledgeBytes();

    assertFailure(Status.Code.CANCELLED);
  }

  private boolean recordTransaction(int callId, Parcel parcel) {
    if (callId >= FIRST_CALL_ID) {
      sentBytes += parcel.dataSize();
      if (callId == FIRST_CALL_ID) {
        parcel.setDataPosition(0);
        sentFlags.add(parcel.readInt());
      }
    }
    return true;
  }

  private Status assertFailure(Status.Code code) {
    ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
    verify(listener).closed(status.capture());
    assertThat(status.getValue().getCode()).isEqualTo(code);
    assertThat(transport.getOngoingCalls()).doesNotContainKey(FIRST_CALL_ID);
    assertThat(sentFlags).hasSize(1);
    assertThat(
            TransactionUtils.hasFlag(sentFlags.get(0), TransactionUtils.FLAG_OUT_OF_BAND_CLOSE))
        .isTrue();
    return status.getValue();
  }

  private void fillTransmitWindow() throws Exception {
    try (ParcelHolder parcel = ParcelHolder.obtain()) {
      parcel.get().writeByteArray(new byte[128 * 1024]);
      transport.sendTransaction(FIRST_CALL_ID + 1, parcel);
    }
    assertThat(stream.isReady()).isFalse();
  }

  private void acknowledgeBytes() {
    synchronized (transport) {
      transport.handleAcknowledgedBytes(sentBytes);
    }
  }

  private static Metadata oversizedTrailers() {
    Metadata trailers = new Metadata();
    trailers.put(
        Metadata.Key.of(
            "oversized-bin",
            new Metadata.BinaryStreamMarshaller<byte[]>() {
              @Override
              public InputStream toStream(byte[] value) {
                return new ByteArrayInputStream(value);
              }

              @Override
              public byte[] parseStream(InputStream stream) {
                throw new UnsupportedOperationException();
              }
            }),
        new byte[BlockPool.BLOCK_SIZE]);
    return trailers;
  }
}
