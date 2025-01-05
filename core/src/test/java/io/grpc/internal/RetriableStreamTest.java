/*
 * Copyright 2017 The gRPC Authors
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

package io.grpc.internal;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.internal.ClientStreamListener.RpcProgress.DROPPED;
import static io.grpc.internal.ClientStreamListener.RpcProgress.MISCARRIED;
import static io.grpc.internal.ClientStreamListener.RpcProgress.PROCESSED;
import static io.grpc.internal.ClientStreamListener.RpcProgress.REFUSED;
import static io.grpc.internal.RetriableStream.GRPC_PREVIOUS_RPC_ATTEMPTS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.ClientStreamTracer;
import io.grpc.Codec;
import io.grpc.Compressor;
import io.grpc.DecompressorRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StringMarshaller;
import io.grpc.internal.ClientStreamListener.RpcProgress;
import io.grpc.internal.RetriableStream.ChannelBufferMeter;
import io.grpc.internal.RetriableStream.Throttle;
import io.grpc.internal.StreamListener.MessageProducer;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Unit tests for {@link RetriableStream}. */
@RunWith(JUnit4.class)
public class RetriableStreamTest {
  private static final String CANCELLED_BECAUSE_COMMITTED =
      "Stream thrown away because RetriableStream committed";
  private static final String AUTHORITY = "fakeAuthority";
  private static final Compressor COMPRESSOR = Codec.Identity.NONE;
  private static final DecompressorRegistry DECOMPRESSOR_REGISTRY =
      DecompressorRegistry.getDefaultInstance();
  private static final int MAX_INBOUND_MESSAGE_SIZE = 1234;
  private static final int MAX_OUTBOUND_MESSAGE_SIZE = 5678;
  private static final long PER_RPC_BUFFER_LIMIT = 1000;
  private static final long CHANNEL_BUFFER_LIMIT = 2000;
  private static final int MAX_ATTEMPTS = 6;
  private static final long INITIAL_BACKOFF_IN_SECONDS = 100;
  private static final long HEDGING_DELAY_IN_SECONDS = 100;
  private static final long MAX_BACKOFF_IN_SECONDS = 700;
  private static final double BACKOFF_MULTIPLIER = 2D;
  private static final double FAKE_RANDOM = .5D;
  private static final ClientStreamTracer.StreamInfo STREAM_INFO =
      ClientStreamTracer.StreamInfo.newBuilder().build();

  static {
    RetriableStream.setRandom(
        // not random
        new Random() {
          @Override
          public double nextDouble() {
            return FAKE_RANDOM;
          }
        });
  }

  private static final Code RETRIABLE_STATUS_CODE_1 = Code.UNAVAILABLE;
  private static final Code RETRIABLE_STATUS_CODE_2 = Code.DATA_LOSS;
  private static final Code NON_RETRIABLE_STATUS_CODE = Code.INTERNAL;
  private static final Code NON_FATAL_STATUS_CODE_1 = Code.UNAVAILABLE;
  private static final Code NON_FATAL_STATUS_CODE_2 = Code.DATA_LOSS;
  private static final Code FATAL_STATUS_CODE = Code.INTERNAL;
  private static final RetryPolicy RETRY_POLICY =
      new RetryPolicy(
          MAX_ATTEMPTS,
          TimeUnit.SECONDS.toNanos(INITIAL_BACKOFF_IN_SECONDS),
          TimeUnit.SECONDS.toNanos(MAX_BACKOFF_IN_SECONDS),
          BACKOFF_MULTIPLIER,
          null,
          ImmutableSet.of(RETRIABLE_STATUS_CODE_1, RETRIABLE_STATUS_CODE_2));
  private static final HedgingPolicy HEDGING_POLICY =
      new HedgingPolicy(
          MAX_ATTEMPTS,
          TimeUnit.SECONDS.toNanos(HEDGING_DELAY_IN_SECONDS),
          ImmutableSet.of(NON_FATAL_STATUS_CODE_1, NON_FATAL_STATUS_CODE_2));

  private final RetriableStreamRecorder retriableStreamRecorder =
      mock(RetriableStreamRecorder.class);
  private final ClientStreamListener masterListener = mock(ClientStreamListener.class);
  private final MethodDescriptor<String, String> method =
      MethodDescriptor.<String, String>newBuilder()
          .setType(MethodType.BIDI_STREAMING)
          .setFullMethodName(MethodDescriptor.generateFullMethodName("service_foo", "method_bar"))
          .setRequestMarshaller(new StringMarshaller())
          .setResponseMarshaller(new StringMarshaller())
          .build();
  private final ChannelBufferMeter channelBufferUsed = new ChannelBufferMeter();
  private final FakeClock fakeClock = new FakeClock();

  private final class RecordedRetriableStream extends RetriableStream<String> {
    RecordedRetriableStream(MethodDescriptor<String, ?> method, Metadata headers,
        ChannelBufferMeter channelBufferUsed, long perRpcBufferLimit, long channelBufferLimit,
        Executor callExecutor,
        ScheduledExecutorService scheduledExecutorService,
        @Nullable RetryPolicy retryPolicy,
        @Nullable HedgingPolicy hedgingPolicy,
        @Nullable Throttle throttle) {
      super(
          method, headers, channelBufferUsed, perRpcBufferLimit, channelBufferLimit, callExecutor,
          scheduledExecutorService,
          retryPolicy,
          hedgingPolicy,
          throttle);
    }

    @Override
    @SuppressWarnings("DirectInvocationOnMock")
    void postCommit() {
      retriableStreamRecorder.postCommit();
    }

    @Override
    @SuppressWarnings("DirectInvocationOnMock")
    ClientStream newSubstream(
        Metadata metadata,
        ClientStreamTracer.Factory tracerFactory,
        int previousAttempts,
        boolean isTransparentRetry) {
      bufferSizeTracer =
          tracerFactory.newClientStreamTracer(STREAM_INFO, metadata);
      int actualPreviousRpcAttemptsInHeader = metadata.get(GRPC_PREVIOUS_RPC_ATTEMPTS) == null
          ? 0 : Integer.valueOf(metadata.get(GRPC_PREVIOUS_RPC_ATTEMPTS));
      return retriableStreamRecorder.newSubstream(actualPreviousRpcAttemptsInHeader);
    }

    @Override
    @SuppressWarnings("DirectInvocationOnMock")
    Status prestart() {
      return retriableStreamRecorder.prestart();
    }
  }

  private RetriableStream<String> retriableStream =
      newThrottledRetriableStream(null /* throttle */);
  private RetriableStream<String> hedgingStream =
      newThrottledHedgingStream(null /* throttle */);

  private ClientStreamTracer bufferSizeTracer;

  private RetriableStream<String> newThrottledRetriableStream(Throttle throttle) {
    return newThrottledRetriableStream(throttle, MoreExecutors.directExecutor());
  }

  private RetriableStream<String> newThrottledRetriableStream(Throttle throttle, Executor drainer) {
    return new RecordedRetriableStream(
        method, new Metadata(), channelBufferUsed, PER_RPC_BUFFER_LIMIT, CHANNEL_BUFFER_LIMIT,
        drainer, fakeClock.getScheduledExecutorService(), RETRY_POLICY, null, throttle);
  }

  private RetriableStream<String> newThrottledHedgingStream(Throttle throttle) {
    return newThrottledHedgingStream(throttle, MoreExecutors.directExecutor());
  }

  private RetriableStream<String> newThrottledHedgingStream(Throttle throttle, Executor executor) {
    return new RecordedRetriableStream(
        method, new Metadata(), channelBufferUsed, PER_RPC_BUFFER_LIMIT, CHANNEL_BUFFER_LIMIT,
        executor, fakeClock.getScheduledExecutorService(),
        null, HEDGING_POLICY, throttle);
  }

  @After
  public void tearDown() {
    assertEquals(0, fakeClock.numPendingTasks());
  }

  @Test
  public void retry_everythingDrained() {
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    ClientStream mockStream3 = mock(ClientStream.class);

    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    doAnswer(new Answer<Void>() {
        @Override
        public Void answer(InvocationOnMock in) {
          InsightBuilder insight = (InsightBuilder) in.getArguments()[0];
          insight.appendKeyValue("remote_addr", "2.2.2.2:443");
          return null;
        }
      }).when(mockStream3).appendTimeoutInsight(any(InsightBuilder.class));

    InOrder inOrder =
        inOrder(retriableStreamRecorder, masterListener, mockStream1, mockStream2, mockStream3);

    // stream settings before start
    retriableStream.setAuthority(AUTHORITY);
    retriableStream.setCompressor(COMPRESSOR);
    retriableStream.setDecompressorRegistry(DECOMPRESSOR_REGISTRY);
    retriableStream.setFullStreamDecompression(false);
    retriableStream.setFullStreamDecompression(true);
    retriableStream.setMaxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE);
    retriableStream.setMessageCompression(true);
    retriableStream.setMaxOutboundMessageSize(MAX_OUTBOUND_MESSAGE_SIZE);
    retriableStream.setMessageCompression(false);

    inOrder.verifyNoMoreInteractions();

    // start
    retriableStream.start(masterListener);

    inOrder.verify(retriableStreamRecorder).prestart();
    inOrder.verify(retriableStreamRecorder).newSubstream(0);

    inOrder.verify(mockStream1).setAuthority(AUTHORITY);
    inOrder.verify(mockStream1).setCompressor(COMPRESSOR);
    inOrder.verify(mockStream1).setDecompressorRegistry(DECOMPRESSOR_REGISTRY);
    inOrder.verify(mockStream1).setFullStreamDecompression(false);
    inOrder.verify(mockStream1).setFullStreamDecompression(true);
    inOrder.verify(mockStream1).setMaxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE);
    inOrder.verify(mockStream1).setMessageCompression(true);
    inOrder.verify(mockStream1).setMaxOutboundMessageSize(MAX_OUTBOUND_MESSAGE_SIZE);
    inOrder.verify(mockStream1).setMessageCompression(false);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream1).start(sublistenerCaptor1.capture());
    inOrder.verify(mockStream1).isReady();
    inOrder.verifyNoMoreInteractions();

    retriableStream.sendMessage("msg1");
    retriableStream.sendMessage("msg2");
    retriableStream.request(345);
    retriableStream.flush();
    retriableStream.flush();
    retriableStream.sendMessage("msg3");
    retriableStream.request(456);

    inOrder.verify(mockStream1, times(2)).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream1).request(345);
    inOrder.verify(mockStream1, times(2)).flush();
    inOrder.verify(mockStream1).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream1).request(456);
    inOrder.verifyNoMoreInteractions();

    // retry1
    doReturn(mockStream2).when(retriableStreamRecorder).newSubstream(1);
    sublistenerCaptor1.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_2), PROCESSED, new Metadata());
    inOrder.verify(retriableStreamRecorder).newSubstream(1);
    assertEquals(1, fakeClock.numPendingTasks());

    // send more messages during backoff
    retriableStream.sendMessage("msg1 during backoff1");
    retriableStream.sendMessage("msg2 during backoff1");

    fakeClock.forwardTime((long) (INITIAL_BACKOFF_IN_SECONDS * FAKE_RANDOM) - 1L, TimeUnit.SECONDS);
    inOrder.verifyNoMoreInteractions();
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime(1L, TimeUnit.SECONDS);
    assertEquals(0, fakeClock.numPendingTasks());
    inOrder.verify(mockStream2).setAuthority(AUTHORITY);
    inOrder.verify(mockStream2).setCompressor(COMPRESSOR);
    inOrder.verify(mockStream2).setDecompressorRegistry(DECOMPRESSOR_REGISTRY);
    inOrder.verify(mockStream2).setFullStreamDecompression(false);
    inOrder.verify(mockStream2).setFullStreamDecompression(true);
    inOrder.verify(mockStream2).setMaxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE);
    inOrder.verify(mockStream2).setMessageCompression(true);
    inOrder.verify(mockStream2).setMaxOutboundMessageSize(MAX_OUTBOUND_MESSAGE_SIZE);
    inOrder.verify(mockStream2).setMessageCompression(false);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(mockStream2, times(2)).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream2).request(345);
    inOrder.verify(mockStream2, times(2)).flush();
    inOrder.verify(mockStream2).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream2).request(456);
    inOrder.verify(mockStream2, times(2)).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream2).isReady();
    inOrder.verifyNoMoreInteractions();

    // send more messages
    retriableStream.sendMessage("msg1 after retry1");
    retriableStream.sendMessage("msg2 after retry1");

    // mockStream1 is closed so it is not in the drainedSubstreams
    verifyNoMoreInteractions(mockStream1);
    inOrder.verify(mockStream2, times(2)).writeMessage(any(InputStream.class));

    // retry2
    doReturn(mockStream3).when(retriableStreamRecorder).newSubstream(2);
    sublistenerCaptor2.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());
    inOrder.verify(retriableStreamRecorder).newSubstream(2);
    assertEquals(1, fakeClock.numPendingTasks());

    // send more messages during backoff
    retriableStream.sendMessage("msg1 during backoff2");
    retriableStream.sendMessage("msg2 during backoff2");
    retriableStream.sendMessage("msg3 during backoff2");

    fakeClock.forwardTime(
        (long) (INITIAL_BACKOFF_IN_SECONDS * BACKOFF_MULTIPLIER * FAKE_RANDOM) - 1L,
        TimeUnit.SECONDS);
    inOrder.verifyNoMoreInteractions();
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime(1L, TimeUnit.SECONDS);
    assertEquals(0, fakeClock.numPendingTasks());
    inOrder.verify(mockStream3).setAuthority(AUTHORITY);
    inOrder.verify(mockStream3).setCompressor(COMPRESSOR);
    inOrder.verify(mockStream3).setDecompressorRegistry(DECOMPRESSOR_REGISTRY);
    inOrder.verify(mockStream3).setFullStreamDecompression(false);
    inOrder.verify(mockStream3).setFullStreamDecompression(true);
    inOrder.verify(mockStream3).setMaxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE);
    inOrder.verify(mockStream3).setMessageCompression(true);
    inOrder.verify(mockStream3).setMaxOutboundMessageSize(MAX_OUTBOUND_MESSAGE_SIZE);
    inOrder.verify(mockStream3).setMessageCompression(false);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor3 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream3).start(sublistenerCaptor3.capture());
    inOrder.verify(mockStream3, times(2)).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream3).request(345);
    inOrder.verify(mockStream3, times(2)).flush();
    inOrder.verify(mockStream3).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream3).request(456);
    inOrder.verify(mockStream3, times(7)).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream3).isReady();
    inOrder.verifyNoMoreInteractions();

    InsightBuilder insight = new InsightBuilder();
    retriableStream.appendTimeoutInsight(insight);
    assertThat(insight.toString()).isEqualTo(
        "[closed=[DATA_LOSS, UNAVAILABLE], open=[[remote_addr=2.2.2.2:443]]]");

    // no more retry
    sublistenerCaptor3.getValue().closed(
        Status.fromCode(NON_RETRIABLE_STATUS_CODE), PROCESSED, new Metadata());

    inOrder.verify(retriableStreamRecorder).postCommit();
    inOrder.verify(masterListener).closed(
        any(Status.class), any(RpcProgress.class), any(Metadata.class));
    inOrder.verifyNoMoreInteractions();

    insight = new InsightBuilder();
    retriableStream.appendTimeoutInsight(insight);
    assertThat(insight.toString()).isEqualTo(
        "[closed=[DATA_LOSS, UNAVAILABLE, INTERNAL], committed=[remote_addr=2.2.2.2:443]]");
  }

  @Test
  public void headersRead_cancel() {
    ClientStream mockStream1 = mock(ClientStream.class);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    InOrder inOrder = inOrder(retriableStreamRecorder);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    sublistenerCaptor1.getValue().headersRead(new Metadata());

    inOrder.verify(retriableStreamRecorder).postCommit();

    retriableStream.cancel(Status.CANCELLED);

    inOrder.verify(retriableStreamRecorder, never()).postCommit();
  }

  @Test
  public void retry_headersRead_cancel() {
    ClientStream mockStream1 = mock(ClientStream.class);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    InOrder inOrder = inOrder(retriableStreamRecorder);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    // retry
    ClientStream mockStream2 = mock(ClientStream.class);
    doReturn(mockStream2).when(retriableStreamRecorder).newSubstream(1);
    sublistenerCaptor1.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime((long) (INITIAL_BACKOFF_IN_SECONDS * FAKE_RANDOM), TimeUnit.SECONDS);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(retriableStreamRecorder, never()).postCommit();

    // headersRead
    sublistenerCaptor2.getValue().headersRead(new Metadata());

    inOrder.verify(retriableStreamRecorder).postCommit();

    // cancel
    retriableStream.cancel(Status.CANCELLED);

    inOrder.verify(retriableStreamRecorder, never()).postCommit();
  }

  @Test
  public void headersRead_closed() {
    ClientStream mockStream1 = mock(ClientStream.class);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    InOrder inOrder = inOrder(retriableStreamRecorder);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    sublistenerCaptor1.getValue().headersRead(new Metadata());

    inOrder.verify(retriableStreamRecorder).postCommit();

    Status status = Status.fromCode(RETRIABLE_STATUS_CODE_1);
    Metadata metadata = new Metadata();
    sublistenerCaptor1.getValue().closed(status, PROCESSED, metadata);

    verify(masterListener).closed(status, PROCESSED, metadata);
    inOrder.verify(retriableStreamRecorder, never()).postCommit();
  }

  @Test
  public void retry_headersRead_closed() {
    ClientStream mockStream1 = mock(ClientStream.class);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    InOrder inOrder = inOrder(retriableStreamRecorder);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    // retry
    ClientStream mockStream2 = mock(ClientStream.class);
    doReturn(mockStream2).when(retriableStreamRecorder).newSubstream(1);
    sublistenerCaptor1.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());
    fakeClock.forwardTime((long) (INITIAL_BACKOFF_IN_SECONDS * FAKE_RANDOM), TimeUnit.SECONDS);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(retriableStreamRecorder, never()).postCommit();

    // headersRead
    sublistenerCaptor2.getValue().headersRead(new Metadata());

    inOrder.verify(retriableStreamRecorder).postCommit();

    // closed even with retriable status
    Status status = Status.fromCode(RETRIABLE_STATUS_CODE_1);
    Metadata metadata = new Metadata();
    sublistenerCaptor2.getValue().closed(status, PROCESSED, metadata);

    verify(masterListener).closed(status, PROCESSED, metadata);
    inOrder.verify(retriableStreamRecorder, never()).postCommit();
  }

  @Test
  public void cancel_closed() {
    ClientStream mockStream1 = mock(ClientStream.class);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    InOrder inOrder = inOrder(retriableStreamRecorder);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    // cancel
    retriableStream.cancel(Status.CANCELLED);

    inOrder.verify(retriableStreamRecorder).postCommit();
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(mockStream1).cancel(statusCaptor.capture());
    assertEquals(Status.CANCELLED.getCode(), statusCaptor.getValue().getCode());
    assertEquals(CANCELLED_BECAUSE_COMMITTED, statusCaptor.getValue().getDescription());

    // closed even with retriable status
    Status status = Status.fromCode(RETRIABLE_STATUS_CODE_1);
    Metadata metadata = new Metadata();
    sublistenerCaptor1.getValue().closed(status, PROCESSED, metadata);
    inOrder.verify(retriableStreamRecorder, never()).postCommit();
  }

  @Test
  public void retry_cancel_closed() {
    ClientStream mockStream1 = mock(ClientStream.class);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    InOrder inOrder = inOrder(retriableStreamRecorder);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    // retry
    ClientStream mockStream2 = mock(ClientStream.class);
    doReturn(mockStream2).when(retriableStreamRecorder).newSubstream(1);
    sublistenerCaptor1.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());
    fakeClock.forwardTime((long) (INITIAL_BACKOFF_IN_SECONDS * FAKE_RANDOM), TimeUnit.SECONDS);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(retriableStreamRecorder, never()).postCommit();

    // cancel
    retriableStream.cancel(Status.CANCELLED);

    inOrder.verify(retriableStreamRecorder).postCommit();
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(mockStream2).cancel(statusCaptor.capture());
    assertEquals(Status.CANCELLED.getCode(), statusCaptor.getValue().getCode());
    assertEquals(CANCELLED_BECAUSE_COMMITTED, statusCaptor.getValue().getDescription());

    // closed
    Status status = Status.fromCode(NON_RETRIABLE_STATUS_CODE);
    Metadata metadata = new Metadata();
    sublistenerCaptor2.getValue().closed(status, PROCESSED, metadata);
    inOrder.verify(retriableStreamRecorder, never()).postCommit();
  }

  @Test
  public void transparentRetry_cancel_race() {
    FakeClock drainer = new FakeClock();
    retriableStream = newThrottledRetriableStream(null, drainer.getScheduledExecutorService());
    ClientStream mockStream1 = mock(ClientStream.class);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    InOrder inOrder = inOrder(retriableStreamRecorder);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    // retry, but don't drain
    ClientStream mockStream2 = mock(ClientStream.class);
    doReturn(mockStream2).when(retriableStreamRecorder).newSubstream(0);
    sublistenerCaptor1.getValue().closed(
        Status.fromCode(NON_RETRIABLE_STATUS_CODE), MISCARRIED, new Metadata());
    assertEquals(1, drainer.numPendingTasks());

    // cancel
    retriableStream.cancel(Status.CANCELLED);
    // drain transparent retry
    drainer.runDueTasks();
    inOrder.verify(retriableStreamRecorder).postCommit();

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream2).start(sublistenerCaptor2.capture());
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(mockStream2).cancel(statusCaptor.capture());
    assertEquals(Status.CANCELLED.getCode(), statusCaptor.getValue().getCode());
    assertEquals(CANCELLED_BECAUSE_COMMITTED, statusCaptor.getValue().getDescription());
    sublistenerCaptor2.getValue().closed(statusCaptor.getValue(), PROCESSED, new Metadata());
    verify(masterListener).closed(same(Status.CANCELLED), same(PROCESSED), any(Metadata.class));
  }

  @Test
  public void unretriableClosed_cancel() {
    ClientStream mockStream1 = mock(ClientStream.class);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    InOrder inOrder = inOrder(retriableStreamRecorder);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    // closed
    Status status = Status.fromCode(NON_RETRIABLE_STATUS_CODE);
    Metadata metadata = new Metadata();
    sublistenerCaptor1.getValue().closed(status, PROCESSED, metadata);

    inOrder.verify(retriableStreamRecorder).postCommit();
    verify(masterListener).closed(status, PROCESSED, metadata);

    // cancel
    retriableStream.cancel(Status.CANCELLED);
    inOrder.verify(retriableStreamRecorder, never()).postCommit();
  }

  @Test
  public void retry_unretriableClosed_cancel() {
    ClientStream mockStream1 = mock(ClientStream.class);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    InOrder inOrder = inOrder(retriableStreamRecorder);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    // retry
    ClientStream mockStream2 = mock(ClientStream.class);
    doReturn(mockStream2).when(retriableStreamRecorder).newSubstream(1);
    sublistenerCaptor1.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());
    fakeClock.forwardTime((long) (INITIAL_BACKOFF_IN_SECONDS * FAKE_RANDOM), TimeUnit.SECONDS);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(retriableStreamRecorder, never()).postCommit();

    // closed
    Status status = Status.fromCode(NON_RETRIABLE_STATUS_CODE);
    Metadata metadata = new Metadata();
    sublistenerCaptor2.getValue().closed(status, PROCESSED, metadata);

    inOrder.verify(retriableStreamRecorder).postCommit();
    verify(masterListener).closed(status, PROCESSED, metadata);

    // cancel
    retriableStream.cancel(Status.CANCELLED);
    inOrder.verify(retriableStreamRecorder, never()).postCommit();
    verify(masterListener, times(1)).closed(any(), any(), any());
  }

  @Test
  public void retry_cancelWhileBackoff() {
    ClientStream mockStream1 = mock(ClientStream.class);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());
    verify(mockStream1).isReady();

    // retry
    ClientStream mockStream2 = mock(ClientStream.class);
    doReturn(mockStream2).when(retriableStreamRecorder).newSubstream(1);
    sublistenerCaptor1.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());

    // cancel while backoff
    assertEquals(1, fakeClock.numPendingTasks());
    verify(retriableStreamRecorder, never()).postCommit();
    retriableStream.cancel(Status.CANCELLED);
    verify(retriableStreamRecorder).postCommit();

    verifyNoMoreInteractions(mockStream1);
    verifyNoMoreInteractions(mockStream2);
    verify(masterListener, times(1)).closed(any(), any(), any());
  }

  @Test
  public void operationsWhileDraining() {
    final ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    final AtomicReference<ClientStreamListener> sublistenerCaptor2 =
        new AtomicReference<>();
    final Status cancelStatus = Status.CANCELLED.withDescription("c");
    ClientStream mockStream1 =
        mock(
            ClientStream.class,
            delegatesTo(
                new NoopClientStream() {
                  @Override
                  public void request(int numMessages) {
                    retriableStream.sendMessage("substream1 request " + numMessages);
                    sublistenerCaptor1.getValue().onReady();
                    if (numMessages > 1) {
                      retriableStream.request(--numMessages);
                    }
                  }

                  @Override
                  public boolean isReady() {
                    return true;
                  }
                }));

    final ClientStream mockStream2 =
        mock(
            ClientStream.class,
            delegatesTo(
                new NoopClientStream() {
                  @Override
                  public void start(ClientStreamListener listener) {
                    sublistenerCaptor2.set(listener);
                  }

                  @Override
                  public void request(int numMessages) {
                    retriableStream.sendMessage("substream2 request " + numMessages);
                    sublistenerCaptor2.get().onReady();
                    if (numMessages == 3) {
                      sublistenerCaptor2.get().headersRead(new Metadata());
                    }
                    if (numMessages == 2) {
                      retriableStream.request(100);
                    }
                    if (numMessages == 100) {
                      retriableStream.cancel(cancelStatus);
                    }
                  }

                  @Override
                  public boolean isReady() {
                    return true;
                  }
                }));

    InOrder inOrder = inOrder(retriableStreamRecorder, mockStream1, mockStream2, masterListener);

    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    retriableStream.start(masterListener);

    inOrder.verify(mockStream1).start(sublistenerCaptor1.capture());

    retriableStream.request(3);

    inOrder.verify(mockStream1).request(3);
    inOrder.verify(mockStream1).writeMessage(any(InputStream.class)); // msg "substream1 request 3"
    inOrder.verify(mockStream1).request(2);
    inOrder.verify(mockStream1).writeMessage(any(InputStream.class)); // msg "substream1 request 2"
    inOrder.verify(mockStream1).request(1);
    inOrder.verify(mockStream1).writeMessage(any(InputStream.class)); // msg "substream1 request 1"
    inOrder.verify(masterListener).onReady();

    // retry
    doReturn(mockStream2).when(retriableStreamRecorder).newSubstream(1);
    sublistenerCaptor1.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());
    assertEquals(1, fakeClock.numPendingTasks());

    // send more requests during backoff
    retriableStream.request(789);

    fakeClock.forwardTime((long) (INITIAL_BACKOFF_IN_SECONDS * FAKE_RANDOM), TimeUnit.SECONDS);

    inOrder.verify(mockStream2).start(sublistenerCaptor2.get());
    inOrder.verify(mockStream2).request(3);
    inOrder.verify(retriableStreamRecorder).postCommit();
    inOrder.verify(mockStream2).writeMessage(any(InputStream.class)); // msg "substream1 request 3"
    inOrder.verify(mockStream2).request(2);
    inOrder.verify(mockStream2).writeMessage(any(InputStream.class)); // msg "substream1 request 2"
    inOrder.verify(mockStream2).request(1);

    // msg "substream1 request 1"
    inOrder.verify(mockStream2).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream2).request(789);
    // msg "substream2 request 3"
    // msg "substream2 request 2"
    inOrder.verify(mockStream2, times(2)).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream2).request(100);
    inOrder.verify(mockStream2).cancel(cancelStatus);
    inOrder.verify(masterListener, never()).onReady();

    // "substream2 request 1" will never be sent
    inOrder.verify(mockStream2, never()).writeMessage(any(InputStream.class));
  }

  @Test
  public void cancelWhileDraining() {
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 =
        mock(
            ClientStream.class,
            delegatesTo(
                new NoopClientStream() {
                  @Override
                  public void request(int numMessages) {
                    retriableStream.cancel(
                        Status.CANCELLED.withDescription("cancelled while requesting"));
                  }
                }));

    InOrder inOrder = inOrder(retriableStreamRecorder, mockStream1, mockStream2);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    retriableStream.start(masterListener);
    inOrder.verify(mockStream1).start(sublistenerCaptor1.capture());
    retriableStream.request(3);
    inOrder.verify(mockStream1).request(3);

    // retry
    doReturn(mockStream2).when(retriableStreamRecorder).newSubstream(1);
    sublistenerCaptor1.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());
    fakeClock.forwardTime((long) (INITIAL_BACKOFF_IN_SECONDS * FAKE_RANDOM), TimeUnit.SECONDS);

    inOrder.verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(mockStream2).request(3);
    inOrder.verify(retriableStreamRecorder).postCommit();
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    inOrder.verify(mockStream2).cancel(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.CANCELLED);
    assertThat(statusCaptor.getValue().getDescription())
        .isEqualTo("Stream thrown away because RetriableStream committed");
    sublistenerCaptor2.getValue().closed(Status.CANCELLED, PROCESSED, new Metadata());
    verify(masterListener).closed(
        statusCaptor.capture(), any(RpcProgress.class), any(Metadata.class));
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.CANCELLED);
    assertThat(statusCaptor.getValue().getDescription()).isEqualTo("cancelled while requesting");
  }

  @Test
  public void cancelWhileRetryStart() {
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 =
        mock(
            ClientStream.class,
            delegatesTo(
                new NoopClientStream() {
                  @Override
                  public void start(ClientStreamListener listener) {
                    retriableStream.cancel(
                        Status.CANCELLED.withDescription("cancelled while retry start"));
                  }
                }));
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);

    InOrder inOrder = inOrder(retriableStreamRecorder, mockStream1, mockStream2);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    retriableStream.start(masterListener);
    inOrder.verify(mockStream1).start(sublistenerCaptor1.capture());

    // retry
    doReturn(mockStream2).when(retriableStreamRecorder).newSubstream(1);
    sublistenerCaptor1.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());
    fakeClock.forwardTime((long) (INITIAL_BACKOFF_IN_SECONDS * FAKE_RANDOM), TimeUnit.SECONDS);

    inOrder.verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(retriableStreamRecorder).postCommit();
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    inOrder.verify(mockStream2).cancel(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.CANCELLED);
    assertThat(statusCaptor.getValue().getDescription())
        .isEqualTo("Stream thrown away because RetriableStream committed");
    sublistenerCaptor2.getValue().closed(Status.CANCELLED, PROCESSED, new Metadata());
    verify(masterListener).closed(
        statusCaptor.capture(), any(RpcProgress.class), any(Metadata.class));
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.CANCELLED);
    assertThat(statusCaptor.getValue().getDescription()).isEqualTo("cancelled while retry start");
  }

  @Test
  public void operationsAfterImmediateCommit() {
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    ClientStream mockStream1 = mock(ClientStream.class);

    InOrder inOrder = inOrder(retriableStreamRecorder, mockStream1);

    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    retriableStream.start(masterListener);

    // drained
    inOrder.verify(mockStream1).start(sublistenerCaptor1.capture());

    // commit
    sublistenerCaptor1.getValue().headersRead(new Metadata());

    retriableStream.request(3);
    inOrder.verify(mockStream1).request(3);
    retriableStream.sendMessage("msg 1");
    inOrder.verify(mockStream1).writeMessage(any(InputStream.class));
  }

  @Test
  public void isReady_whenDrained() {
    ClientStream mockStream1 = mock(ClientStream.class);

    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    retriableStream.start(masterListener);

    assertFalse(retriableStream.isReady());

    doReturn(true).when(mockStream1).isReady();

    assertTrue(retriableStream.isReady());
  }

  @Test
  public void isReady_whileDraining() {
    final AtomicReference<ClientStreamListener> sublistenerCaptor1 =
        new AtomicReference<>();
    final List<Boolean> readiness = new ArrayList<>();
    ClientStream mockStream1 =
        mock(
            ClientStream.class,
            delegatesTo(
                new NoopClientStream() {
                  @Override
                  public void start(ClientStreamListener listener) {
                    sublistenerCaptor1.set(listener);
                    readiness.add(retriableStream.isReady()); // expected false b/c in draining
                  }

                  @Override
                  public boolean isReady() {
                    return true;
                  }
                }));

    final ClientStream mockStream2 =
        mock(
            ClientStream.class,
            delegatesTo(
                new NoopClientStream() {
                  @Override
                  public void start(ClientStreamListener listener) {
                    readiness.add(retriableStream.isReady()); // expected false b/c in draining
                  }

                  @Override
                  public boolean isReady() {
                    return true;
                  }
                }));

    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    retriableStream.start(masterListener);

    verify(mockStream1).start(sublistenerCaptor1.get());
    readiness.add(retriableStream.isReady()); // expected true

    // retry
    doReturn(mockStream2).when(retriableStreamRecorder).newSubstream(1);
    doReturn(false).when(mockStream1).isReady(); // mockStream1 closed, so isReady false
    sublistenerCaptor1.get().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());
    assertEquals(1, fakeClock.numPendingTasks());

    // send more requests during backoff
    retriableStream.request(789);
    readiness.add(retriableStream.isReady()); // expected false b/c in backoff

    fakeClock.forwardTime((long) (INITIAL_BACKOFF_IN_SECONDS * FAKE_RANDOM), TimeUnit.SECONDS);

    verify(mockStream2).start(any(ClientStreamListener.class));
    readiness.add(retriableStream.isReady()); // expected true

    assertThat(readiness).containsExactly(false, true, false, false, true).inOrder();
  }

  @Test
  public void messageAvailable() {
    ClientStream mockStream1 = mock(ClientStream.class);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    ClientStreamListener listener = sublistenerCaptor1.getValue();
    listener.headersRead(new Metadata());
    MessageProducer messageProducer = mock(MessageProducer.class);
    listener.messagesAvailable(messageProducer);
    verify(masterListener).messagesAvailable(messageProducer);
  }

  @Test
  public void inboundMessagesClosedOnCancel() throws Exception {
    ClientStream mockStream1 = mock(ClientStream.class);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);

    retriableStream.start(masterListener);
    retriableStream.request(1);
    retriableStream.cancel(Status.CANCELLED.withDescription("on purpose"));

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    ClientStreamListener listener = sublistenerCaptor1.getValue();
    listener.headersRead(new Metadata());
    InputStream is = mock(InputStream.class);
    listener.messagesAvailable(new FakeMessageProducer(is));
    verify(masterListener, never()).messagesAvailable(any(MessageProducer.class));
    verify(is).close();
  }

  @Test
  public void notAdd0PrevRetryAttemptsToRespHeaders() {
    ClientStream mockStream1 = mock(ClientStream.class);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor =
            ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor.capture());

    sublistenerCaptor.getValue().headersRead(new Metadata());

    ArgumentCaptor<Metadata> metadataCaptor =
            ArgumentCaptor.forClass(Metadata.class);
    verify(masterListener).headersRead(metadataCaptor.capture());
    assertEquals(null, metadataCaptor.getValue().get(GRPC_PREVIOUS_RPC_ATTEMPTS));
  }

  @Test
  public void addPrevRetryAttemptsToRespHeaders() {
    ClientStream mockStream1 = mock(ClientStream.class);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
            ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    // retry
    ClientStream mockStream2 = mock(ClientStream.class);
    doReturn(mockStream2).when(retriableStreamRecorder).newSubstream(1);
    sublistenerCaptor1.getValue().closed(
            Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());
    fakeClock.forwardTime((long) (INITIAL_BACKOFF_IN_SECONDS * FAKE_RANDOM), TimeUnit.SECONDS);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
            ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream2).start(sublistenerCaptor2.capture());
    Metadata headers = new Metadata();
    headers.put(GRPC_PREVIOUS_RPC_ATTEMPTS, "3");
    sublistenerCaptor2.getValue().headersRead(headers);

    ArgumentCaptor<Metadata> metadataCaptor = ArgumentCaptor.forClass(Metadata.class);
    verify(masterListener).headersRead(metadataCaptor.capture());
    Iterable<String> iterable = metadataCaptor.getValue().getAll(GRPC_PREVIOUS_RPC_ATTEMPTS);
    assertEquals(1, Iterables.size(iterable));
    assertEquals("1", iterable.iterator().next());
  }

  @Test
  public void closedWhileDraining() {
    ClientStream mockStream1 = mock(ClientStream.class);
    final ClientStream mockStream2 =
        mock(
            ClientStream.class,
            delegatesTo(
                new NoopClientStream() {
                  @Override
                  public void start(ClientStreamListener listener) {
                    // closed while draning
                    listener.closed(
                        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());
                  }
                }));
    final ClientStream mockStream3 = mock(ClientStream.class);

    when(retriableStreamRecorder.newSubstream(anyInt()))
        .thenReturn(mockStream1, mockStream2, mockStream3);

    retriableStream.start(masterListener);
    retriableStream.sendMessage("msg1");
    retriableStream.sendMessage("msg2");

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    ClientStreamListener listener1 = sublistenerCaptor1.getValue();

    // retry
    listener1.closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime((long) (INITIAL_BACKOFF_IN_SECONDS * FAKE_RANDOM), TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());

    // send requests during backoff
    retriableStream.request(3);
    fakeClock.forwardTime(
        (long) (INITIAL_BACKOFF_IN_SECONDS * BACKOFF_MULTIPLIER * FAKE_RANDOM), TimeUnit.SECONDS);

    retriableStream.request(1);
    verify(mockStream1, never()).request(anyInt());
    verify(mockStream2, never()).request(anyInt());
    verify(mockStream3).request(3);
    verify(mockStream3).request(1);
  }

  @Test
  public void commitAndCancelWhileDraining() {
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 =
        mock(
            ClientStream.class,
            delegatesTo(
                new NoopClientStream() {
                  @Override
                  public void start(ClientStreamListener listener) {
                    // commit while draining
                    listener.headersRead(new Metadata());
                    // cancel while draining
                    retriableStream.cancel(
                        Status.CANCELLED.withDescription("cancelled while drained"));
                  }
                }));

    when(retriableStreamRecorder.newSubstream(anyInt()))
        .thenReturn(mockStream1, mockStream2);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    ClientStreamListener listener1 = sublistenerCaptor1.getValue();

    // retry
    listener1.closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());
    fakeClock.forwardTime((long) (INITIAL_BACKOFF_IN_SECONDS * FAKE_RANDOM), TimeUnit.SECONDS);

    verify(mockStream2).start(any(ClientStreamListener.class));
    verify(retriableStreamRecorder).postCommit();
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(mockStream2).cancel(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.CANCELLED);
    assertThat(statusCaptor.getValue().getDescription()).isEqualTo("cancelled while drained");
  }

  @Test
  public void perRpcBufferLimitExceeded() {
    ClientStream mockStream1 = mock(ClientStream.class);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);

    retriableStream.start(masterListener);

    bufferSizeTracer.outboundWireSize(PER_RPC_BUFFER_LIMIT);

    assertEquals(PER_RPC_BUFFER_LIMIT, channelBufferUsed.addAndGet(0));

    verify(retriableStreamRecorder, never()).postCommit();
    bufferSizeTracer.outboundWireSize(2);
    verify(retriableStreamRecorder).postCommit();

    // verify channel buffer is adjusted
    assertEquals(0, channelBufferUsed.addAndGet(0));
  }

  @Test
  public void perRpcBufferLimitExceededDuringBackoff() {
    ClientStream mockStream1 = mock(ClientStream.class);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);

    retriableStream.start(masterListener);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());
    verify(mockStream1).isReady();

    bufferSizeTracer.outboundWireSize(PER_RPC_BUFFER_LIMIT - 1);

    // retry
    ClientStream mockStream2 = mock(ClientStream.class);
    doReturn(mockStream2).when(retriableStreamRecorder).newSubstream(1);
    sublistenerCaptor1.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());

    assertEquals(1, fakeClock.numPendingTasks());
    bufferSizeTracer.outboundWireSize(2);
    verify(retriableStreamRecorder, never()).postCommit();

    fakeClock.forwardTime((long) (INITIAL_BACKOFF_IN_SECONDS * FAKE_RANDOM), TimeUnit.SECONDS);
    verify(mockStream2).start(any(ClientStreamListener.class));
    verify(mockStream2).isReady();

    // bufferLimitExceeded
    bufferSizeTracer.outboundWireSize(PER_RPC_BUFFER_LIMIT - 1);
    verify(retriableStreamRecorder).postCommit();

    verifyNoMoreInteractions(mockStream1);
    verifyNoMoreInteractions(mockStream2);
  }

  @Test
  public void channelBufferLimitExceeded() {
    ClientStream mockStream1 = mock(ClientStream.class);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);

    retriableStream.start(masterListener);

    bufferSizeTracer.outboundWireSize(100);

    assertEquals(100, channelBufferUsed.addAndGet(0));

    channelBufferUsed.addAndGet(CHANNEL_BUFFER_LIMIT - 200);
    verify(retriableStreamRecorder, never()).postCommit();
    bufferSizeTracer.outboundWireSize(100 + 1);
    verify(retriableStreamRecorder).postCommit();

    // verify channel buffer is adjusted
    assertEquals(CHANNEL_BUFFER_LIMIT - 200, channelBufferUsed.addAndGet(0));
  }

  @Test
  public void updateHeaders() {
    Metadata originalHeaders = new Metadata();
    Metadata headers = retriableStream.updateHeaders(originalHeaders, 0);
    assertNotSame(originalHeaders, headers);
    assertNull(headers.get(GRPC_PREVIOUS_RPC_ATTEMPTS));

    headers = retriableStream.updateHeaders(originalHeaders, 345);
    assertEquals("345", headers.get(GRPC_PREVIOUS_RPC_ATTEMPTS));
    assertNull(originalHeaders.get(GRPC_PREVIOUS_RPC_ATTEMPTS));
  }

  @Test
  public void expBackoff_maxBackoff_maxRetryAttempts() {
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    ClientStream mockStream3 = mock(ClientStream.class);
    ClientStream mockStream4 = mock(ClientStream.class);
    ClientStream mockStream5 = mock(ClientStream.class);
    ClientStream mockStream6 = mock(ClientStream.class);
    ClientStream mockStream7 = mock(ClientStream.class);
    InOrder inOrder = inOrder(
        mockStream1, mockStream2, mockStream3, mockStream4, mockStream5, mockStream6, mockStream7);
    when(retriableStreamRecorder.newSubstream(anyInt())).thenReturn(
        mockStream1, mockStream2, mockStream3, mockStream4, mockStream5, mockStream6, mockStream7);

    retriableStream.start(masterListener);
    assertEquals(0, fakeClock.numPendingTasks());
    verify(retriableStreamRecorder).newSubstream(0);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream1).start(sublistenerCaptor1.capture());
    inOrder.verify(mockStream1).isReady();
    inOrder.verifyNoMoreInteractions();


    // retry1
    sublistenerCaptor1.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime((long) (INITIAL_BACKOFF_IN_SECONDS * FAKE_RANDOM) - 1L, TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime(1L, TimeUnit.SECONDS);
    assertEquals(0, fakeClock.numPendingTasks());
    verify(retriableStreamRecorder).newSubstream(1);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(mockStream2).isReady();
    inOrder.verifyNoMoreInteractions();

    // retry2
    sublistenerCaptor2.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_2), PROCESSED, new Metadata());
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime(
        (long) (INITIAL_BACKOFF_IN_SECONDS * BACKOFF_MULTIPLIER * FAKE_RANDOM) - 1L,
        TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime(1L, TimeUnit.SECONDS);
    assertEquals(0, fakeClock.numPendingTasks());
    verify(retriableStreamRecorder).newSubstream(2);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor3 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream3).start(sublistenerCaptor3.capture());
    inOrder.verify(mockStream3).isReady();
    inOrder.verifyNoMoreInteractions();

    // retry3
    sublistenerCaptor3.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime(
        (long) (INITIAL_BACKOFF_IN_SECONDS * BACKOFF_MULTIPLIER * BACKOFF_MULTIPLIER * FAKE_RANDOM)
            - 1L,
        TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime(1L, TimeUnit.SECONDS);
    assertEquals(0, fakeClock.numPendingTasks());
    verify(retriableStreamRecorder).newSubstream(3);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor4 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream4).start(sublistenerCaptor4.capture());
    inOrder.verify(mockStream4).isReady();
    inOrder.verifyNoMoreInteractions();

    // retry4
    sublistenerCaptor4.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_2), PROCESSED, new Metadata());
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime((long) (MAX_BACKOFF_IN_SECONDS * FAKE_RANDOM) - 1L, TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime(1L, TimeUnit.SECONDS);
    assertEquals(0, fakeClock.numPendingTasks());
    verify(retriableStreamRecorder).newSubstream(4);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor5 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream5).start(sublistenerCaptor5.capture());
    inOrder.verify(mockStream5).isReady();
    inOrder.verifyNoMoreInteractions();

    // retry5
    sublistenerCaptor5.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_2), PROCESSED, new Metadata());
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime((long) (MAX_BACKOFF_IN_SECONDS * FAKE_RANDOM) - 1L, TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime(1L, TimeUnit.SECONDS);
    assertEquals(0, fakeClock.numPendingTasks());
    verify(retriableStreamRecorder).newSubstream(5);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor6 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream6).start(sublistenerCaptor6.capture());
    inOrder.verify(mockStream6).isReady();
    inOrder.verifyNoMoreInteractions();

    // can not retry any more
    verify(retriableStreamRecorder, never()).postCommit();
    sublistenerCaptor6.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());
    verify(retriableStreamRecorder).postCommit();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void pushback() {
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    ClientStream mockStream3 = mock(ClientStream.class);
    ClientStream mockStream4 = mock(ClientStream.class);
    ClientStream mockStream5 = mock(ClientStream.class);
    ClientStream mockStream6 = mock(ClientStream.class);
    ClientStream mockStream7 = mock(ClientStream.class);
    InOrder inOrder = inOrder(
        mockStream1, mockStream2, mockStream3, mockStream4, mockStream5, mockStream6, mockStream7);
    when(retriableStreamRecorder.newSubstream(anyInt())).thenReturn(
        mockStream1, mockStream2, mockStream3, mockStream4, mockStream5, mockStream6, mockStream7);

    retriableStream.start(masterListener);
    assertEquals(0, fakeClock.numPendingTasks());
    verify(retriableStreamRecorder).newSubstream(0);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream1).start(sublistenerCaptor1.capture());
    inOrder.verify(mockStream1).isReady();
    inOrder.verifyNoMoreInteractions();


    // retry1
    int pushbackInMillis = 123;
    Metadata headers = new Metadata();
    headers.put(RetriableStream.GRPC_RETRY_PUSHBACK_MS, "" + pushbackInMillis);
    sublistenerCaptor1.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, headers);
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime(pushbackInMillis - 1, TimeUnit.MILLISECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime(1L, TimeUnit.MILLISECONDS);
    assertEquals(0, fakeClock.numPendingTasks());
    verify(retriableStreamRecorder).newSubstream(1);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(mockStream2).isReady();
    inOrder.verifyNoMoreInteractions();

    // retry2
    pushbackInMillis = 4567 * 1000;
    headers = new Metadata();
    headers.put(RetriableStream.GRPC_RETRY_PUSHBACK_MS, "" + pushbackInMillis);
    sublistenerCaptor2.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_2), PROCESSED, headers);
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime(pushbackInMillis - 1, TimeUnit.MILLISECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime(1L, TimeUnit.MILLISECONDS);
    assertEquals(0, fakeClock.numPendingTasks());
    verify(retriableStreamRecorder).newSubstream(2);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor3 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream3).start(sublistenerCaptor3.capture());
    inOrder.verify(mockStream3).isReady();
    inOrder.verifyNoMoreInteractions();

    // retry3
    sublistenerCaptor3.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime((long) (INITIAL_BACKOFF_IN_SECONDS * FAKE_RANDOM) - 1L, TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime(1L, TimeUnit.SECONDS);
    assertEquals(0, fakeClock.numPendingTasks());
    verify(retriableStreamRecorder).newSubstream(3);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor4 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream4).start(sublistenerCaptor4.capture());
    inOrder.verify(mockStream4).isReady();
    inOrder.verifyNoMoreInteractions();

    // retry4
    sublistenerCaptor4.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_2), PROCESSED, new Metadata());
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime(
        (long) (INITIAL_BACKOFF_IN_SECONDS * BACKOFF_MULTIPLIER * FAKE_RANDOM) - 1L,
        TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime(1L, TimeUnit.SECONDS);
    assertEquals(0, fakeClock.numPendingTasks());
    verify(retriableStreamRecorder).newSubstream(4);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor5 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream5).start(sublistenerCaptor5.capture());
    inOrder.verify(mockStream5).isReady();
    inOrder.verifyNoMoreInteractions();

    // retry5
    sublistenerCaptor5.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_2), PROCESSED, new Metadata());
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime(
        (long) (INITIAL_BACKOFF_IN_SECONDS * BACKOFF_MULTIPLIER * BACKOFF_MULTIPLIER * FAKE_RANDOM)
            - 1L,
        TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime(1L, TimeUnit.SECONDS);
    assertEquals(0, fakeClock.numPendingTasks());
    verify(retriableStreamRecorder).newSubstream(5);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor6 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream6).start(sublistenerCaptor6.capture());
    inOrder.verify(mockStream6).isReady();
    inOrder.verifyNoMoreInteractions();

    // can not retry any more even pushback is positive
    pushbackInMillis = 4567 * 1000;
    headers = new Metadata();
    headers.put(RetriableStream.GRPC_RETRY_PUSHBACK_MS, "" + pushbackInMillis);
    verify(retriableStreamRecorder, never()).postCommit();
    sublistenerCaptor6.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, headers);
    verify(retriableStreamRecorder).postCommit();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void pushback_noRetry() {
    ClientStream mockStream1 = mock(ClientStream.class);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(anyInt());

    retriableStream.start(masterListener);
    assertEquals(0, fakeClock.numPendingTasks());
    verify(retriableStreamRecorder).newSubstream(0);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());
    verify(retriableStreamRecorder, never()).postCommit();

    // pushback no retry
    Metadata headers = new Metadata();
    headers.put(RetriableStream.GRPC_RETRY_PUSHBACK_MS, "");
    sublistenerCaptor1.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, headers);

    verify(retriableStreamRecorder, never()).newSubstream(1);
    verify(retriableStreamRecorder).postCommit();
  }

  @Test
  public void throttle() {
    Throttle throttle = new Throttle(4f, 0.8f);
    assertTrue(throttle.isAboveThreshold());
    assertTrue(throttle.onQualifiedFailureThenCheckIsAboveThreshold()); // token = 3
    assertTrue(throttle.isAboveThreshold());
    assertFalse(throttle.onQualifiedFailureThenCheckIsAboveThreshold()); // token = 2
    assertFalse(throttle.isAboveThreshold());
    assertFalse(throttle.onQualifiedFailureThenCheckIsAboveThreshold()); // token = 1
    assertFalse(throttle.onQualifiedFailureThenCheckIsAboveThreshold()); // token = 0
    assertFalse(throttle.onQualifiedFailureThenCheckIsAboveThreshold()); // token = 0
    assertFalse(throttle.onQualifiedFailureThenCheckIsAboveThreshold()); // token = 0
    assertFalse(throttle.isAboveThreshold());

    throttle.onSuccess(); // token = 0.8
    assertFalse(throttle.isAboveThreshold());
    throttle.onSuccess(); // token = 1.6
    assertFalse(throttle.isAboveThreshold());
    throttle.onSuccess(); // token = 3.2
    assertTrue(throttle.isAboveThreshold());
    throttle.onSuccess(); // token = 4
    assertTrue(throttle.isAboveThreshold());
    throttle.onSuccess(); // token = 4
    assertTrue(throttle.isAboveThreshold());
    throttle.onSuccess(); // token = 4
    assertTrue(throttle.isAboveThreshold());

    assertTrue(throttle.isAboveThreshold());
    assertTrue(throttle.onQualifiedFailureThenCheckIsAboveThreshold()); // token = 3
    assertTrue(throttle.isAboveThreshold());
    assertFalse(throttle.onQualifiedFailureThenCheckIsAboveThreshold()); // token = 2
    assertFalse(throttle.isAboveThreshold());
  }

  @Test
  public void throttledStream_FailWithRetriableStatusCode_WithoutPushback() {
    Throttle throttle = new Throttle(4f, 0.8f);
    RetriableStream<String> retriableStream = newThrottledRetriableStream(throttle);

    ClientStream mockStream = mock(ClientStream.class);
    doReturn(mockStream).when(retriableStreamRecorder).newSubstream(anyInt());
    retriableStream.start(masterListener);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream).start(sublistenerCaptor.capture());

    // mimic some other call in the channel triggers a throttle countdown
    assertTrue(throttle.onQualifiedFailureThenCheckIsAboveThreshold()); // count = 3

    sublistenerCaptor.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());
    verify(retriableStreamRecorder).postCommit();
    assertFalse(throttle.isAboveThreshold()); // count = 2
  }

  @Test
  public void throttledStream_FailWithNonRetriableStatusCode_WithoutPushback() {
    Throttle throttle = new Throttle(4f, 0.8f);
    RetriableStream<String> retriableStream = newThrottledRetriableStream(throttle);

    ClientStream mockStream = mock(ClientStream.class);
    doReturn(mockStream).when(retriableStreamRecorder).newSubstream(anyInt());
    retriableStream.start(masterListener);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream).start(sublistenerCaptor.capture());

    // mimic some other call in the channel triggers a throttle countdown
    assertTrue(throttle.onQualifiedFailureThenCheckIsAboveThreshold()); // count = 3

    sublistenerCaptor.getValue().closed(
        Status.fromCode(NON_RETRIABLE_STATUS_CODE), PROCESSED, new Metadata());
    verify(retriableStreamRecorder).postCommit();
    assertTrue(throttle.isAboveThreshold()); // count = 3

    assertFalse(throttle.onQualifiedFailureThenCheckIsAboveThreshold()); // count = 2
  }

  @Test
  public void throttledStream_FailWithRetriableStatusCode_WithRetriablePushback() {
    Throttle throttle = new Throttle(4f, 0.8f);
    RetriableStream<String> retriableStream = newThrottledRetriableStream(throttle);

    ClientStream mockStream = mock(ClientStream.class);
    doReturn(mockStream).when(retriableStreamRecorder).newSubstream(anyInt());
    retriableStream.start(masterListener);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream).start(sublistenerCaptor.capture());

    // mimic some other call in the channel triggers a throttle countdown
    assertTrue(throttle.onQualifiedFailureThenCheckIsAboveThreshold()); // count = 3

    int pushbackInMillis = 123;
    Metadata headers = new Metadata();
    headers.put(RetriableStream.GRPC_RETRY_PUSHBACK_MS, "" + pushbackInMillis);
    sublistenerCaptor.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, headers);
    verify(retriableStreamRecorder).postCommit();
    assertFalse(throttle.isAboveThreshold()); // count = 2
  }

  @Test
  public void throttledStream_FailWithNonRetriableStatusCode_WithRetriablePushback() {
    Throttle throttle = new Throttle(4f, 0.8f);
    RetriableStream<String> retriableStream = newThrottledRetriableStream(throttle);

    ClientStream mockStream = mock(ClientStream.class);
    doReturn(mockStream).when(retriableStreamRecorder).newSubstream(anyInt());
    retriableStream.start(masterListener);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream).start(sublistenerCaptor.capture());

    // mimic some other call in the channel triggers a throttle countdown
    assertTrue(throttle.onQualifiedFailureThenCheckIsAboveThreshold()); // count = 3

    int pushbackInMillis = 123;
    Metadata headers = new Metadata();
    headers.put(RetriableStream.GRPC_RETRY_PUSHBACK_MS, "" + pushbackInMillis);
    sublistenerCaptor.getValue().closed(
        Status.fromCode(NON_RETRIABLE_STATUS_CODE), PROCESSED, headers);
    verify(retriableStreamRecorder, never()).postCommit();
    assertTrue(throttle.isAboveThreshold()); // count = 3

    // drain pending retry
    fakeClock.forwardTime(pushbackInMillis, TimeUnit.MILLISECONDS);

    assertTrue(throttle.isAboveThreshold()); // count = 3
    assertFalse(throttle.onQualifiedFailureThenCheckIsAboveThreshold()); // count = 2
  }

  @Test
  public void throttledStream_FailWithRetriableStatusCode_WithNonRetriablePushback() {
    Throttle throttle = new Throttle(4f, 0.8f);
    RetriableStream<String> retriableStream = newThrottledRetriableStream(throttle);

    ClientStream mockStream = mock(ClientStream.class);
    doReturn(mockStream).when(retriableStreamRecorder).newSubstream(anyInt());
    retriableStream.start(masterListener);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream).start(sublistenerCaptor.capture());

    // mimic some other call in the channel triggers a throttle countdown
    assertTrue(throttle.onQualifiedFailureThenCheckIsAboveThreshold()); // count = 3

    Metadata headers = new Metadata();
    headers.put(RetriableStream.GRPC_RETRY_PUSHBACK_MS, "");
    sublistenerCaptor.getValue().closed(
        Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, headers);
    verify(retriableStreamRecorder).postCommit();
    assertFalse(throttle.isAboveThreshold()); // count = 2
  }

  @Test
  public void throttledStream_FailWithNonRetriableStatusCode_WithNonRetriablePushback() {
    Throttle throttle = new Throttle(4f, 0.8f);
    RetriableStream<String> retriableStream = newThrottledRetriableStream(throttle);

    ClientStream mockStream = mock(ClientStream.class);
    doReturn(mockStream).when(retriableStreamRecorder).newSubstream(anyInt());
    retriableStream.start(masterListener);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream).start(sublistenerCaptor.capture());

    // mimic some other call in the channel triggers a throttle countdown
    assertTrue(throttle.onQualifiedFailureThenCheckIsAboveThreshold()); // count = 3

    Metadata headers = new Metadata();
    headers.put(RetriableStream.GRPC_RETRY_PUSHBACK_MS, "");
    sublistenerCaptor.getValue().closed(
        Status.fromCode(NON_RETRIABLE_STATUS_CODE), PROCESSED, headers);
    verify(retriableStreamRecorder).postCommit();
    assertFalse(throttle.isAboveThreshold()); // count = 2
  }

  @Test
  public void throttleStream_Succeed() {
    Throttle throttle = new Throttle(4f, 0.8f);
    RetriableStream<String> retriableStream = newThrottledRetriableStream(throttle);

    ClientStream mockStream = mock(ClientStream.class);
    doReturn(mockStream).when(retriableStreamRecorder).newSubstream(anyInt());
    retriableStream.start(masterListener);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream).start(sublistenerCaptor.capture());

    // mimic some other calls in the channel trigger throttle countdowns
    assertTrue(throttle.onQualifiedFailureThenCheckIsAboveThreshold()); // count = 3
    assertFalse(throttle.onQualifiedFailureThenCheckIsAboveThreshold()); // count = 2
    assertFalse(throttle.onQualifiedFailureThenCheckIsAboveThreshold()); // count = 1

    sublistenerCaptor.getValue().headersRead(new Metadata());
    verify(retriableStreamRecorder).postCommit();
    assertFalse(throttle.isAboveThreshold());  // count = 1.8

    // mimic some other call in the channel triggers a success
    throttle.onSuccess();
    assertTrue(throttle.isAboveThreshold()); // count = 2.6
  }

  @Test
  public void transparentRetry_onlyOnceOnRefused() {
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    ClientStream mockStream3 = mock(ClientStream.class);
    InOrder inOrder = inOrder(
        retriableStreamRecorder,
        mockStream1, mockStream2, mockStream3);

    // start
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    retriableStream.start(masterListener);

    inOrder.verify(retriableStreamRecorder).newSubstream(0);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream1).start(sublistenerCaptor1.capture());
    inOrder.verify(mockStream1).isReady();
    inOrder.verifyNoMoreInteractions();

    // transparent retry
    doReturn(mockStream2).when(retriableStreamRecorder).newSubstream(0);
    sublistenerCaptor1.getValue()
        .closed(Status.fromCode(NON_RETRIABLE_STATUS_CODE), REFUSED, new Metadata());

    inOrder.verify(retriableStreamRecorder).newSubstream(0);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(mockStream2).isReady();
    inOrder.verifyNoMoreInteractions();
    verify(retriableStreamRecorder, never()).postCommit();
    assertEquals(0, fakeClock.numPendingTasks());

    // no more transparent retry
    doReturn(mockStream3).when(retriableStreamRecorder).newSubstream(1);
    sublistenerCaptor2.getValue()
        .closed(Status.fromCode(RETRIABLE_STATUS_CODE_1), REFUSED, new Metadata());

    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime((long) (INITIAL_BACKOFF_IN_SECONDS * FAKE_RANDOM), TimeUnit.SECONDS);
    inOrder.verify(retriableStreamRecorder).newSubstream(1);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor3 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream3).start(sublistenerCaptor3.capture());
    inOrder.verify(mockStream3).isReady();
    inOrder.verifyNoMoreInteractions();
    verify(retriableStreamRecorder, never()).postCommit();
    assertEquals(0, fakeClock.numPendingTasks());
  }

  @Test
  public void transparentRetry_unlimitedTimesOnMiscarried() {
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    ClientStream mockStream3 = mock(ClientStream.class);
    InOrder inOrder = inOrder(
        retriableStreamRecorder,
        mockStream1, mockStream2, mockStream3);

    // start
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    retriableStream.start(masterListener);

    inOrder.verify(retriableStreamRecorder).newSubstream(0);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream1).start(sublistenerCaptor1.capture());
    inOrder.verify(mockStream1).isReady();
    inOrder.verifyNoMoreInteractions();

    // transparent retry
    doReturn(mockStream2).when(retriableStreamRecorder).newSubstream(0);
    sublistenerCaptor1.getValue()
        .closed(Status.fromCode(NON_RETRIABLE_STATUS_CODE), MISCARRIED, new Metadata());

    inOrder.verify(retriableStreamRecorder).newSubstream(0);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(mockStream2).isReady();
    inOrder.verifyNoMoreInteractions();
    verify(retriableStreamRecorder, never()).postCommit();
    assertEquals(0, fakeClock.numPendingTasks());

    // more transparent retry
    doReturn(mockStream3).when(retriableStreamRecorder).newSubstream(0);
    sublistenerCaptor2.getValue()
        .closed(Status.fromCode(NON_RETRIABLE_STATUS_CODE), MISCARRIED, new Metadata());

    inOrder.verify(retriableStreamRecorder).newSubstream(0);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor3 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream3).start(sublistenerCaptor3.capture());
    inOrder.verify(mockStream3).isReady();
    inOrder.verifyNoMoreInteractions();
    verify(retriableStreamRecorder, never()).postCommit();
    assertEquals(0, fakeClock.numPendingTasks());

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor = sublistenerCaptor3;
    for (int i = 0; i < 999; i++) {
      ClientStream mockStream = mock(ClientStream.class);
      doReturn(mockStream).when(retriableStreamRecorder).newSubstream(0);
      sublistenerCaptor.getValue()
          .closed(Status.fromCode(NON_RETRIABLE_STATUS_CODE), MISCARRIED, new Metadata());
      if (i == 998) {
        verify(retriableStreamRecorder).postCommit();
        verify(masterListener)
            .closed(any(Status.class), any(RpcProgress.class), any(Metadata.class));
      } else {
        verify(retriableStreamRecorder, never()).postCommit();
        sublistenerCaptor = ArgumentCaptor.forClass(ClientStreamListener.class);
        verify(mockStream).start(sublistenerCaptor.capture());
      }
    }
  }

  @Test
  public void normalRetry_thenNoTransparentRetry_butNormalRetry() {
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    ClientStream mockStream3 = mock(ClientStream.class);
    InOrder inOrder = inOrder(
        retriableStreamRecorder,
        mockStream1, mockStream2, mockStream3);

    // start
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    retriableStream.start(masterListener);

    inOrder.verify(retriableStreamRecorder).newSubstream(0);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream1).start(sublistenerCaptor1.capture());
    inOrder.verify(mockStream1).isReady();
    inOrder.verifyNoMoreInteractions();

    // normal retry
    doReturn(mockStream2).when(retriableStreamRecorder).newSubstream(1);
    sublistenerCaptor1.getValue()
        .closed(Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());

    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime((long) (INITIAL_BACKOFF_IN_SECONDS * FAKE_RANDOM), TimeUnit.SECONDS);
    inOrder.verify(retriableStreamRecorder).newSubstream(1);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(mockStream2).isReady();
    inOrder.verifyNoMoreInteractions();
    verify(retriableStreamRecorder, never()).postCommit();
    assertEquals(0, fakeClock.numPendingTasks());

    // no more transparent retry
    doReturn(mockStream3).when(retriableStreamRecorder).newSubstream(2);
    sublistenerCaptor2.getValue()
        .closed(Status.fromCode(RETRIABLE_STATUS_CODE_1), REFUSED, new Metadata());

    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime(
        (long) (INITIAL_BACKOFF_IN_SECONDS * BACKOFF_MULTIPLIER * FAKE_RANDOM), TimeUnit.SECONDS);
    inOrder.verify(retriableStreamRecorder).newSubstream(2);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor3 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream3).start(sublistenerCaptor3.capture());
    inOrder.verify(mockStream3).isReady();
    inOrder.verifyNoMoreInteractions();
    verify(retriableStreamRecorder, never()).postCommit();
  }

  @Test
  public void normalRetry_thenNoTransparentRetry_andNoMoreRetry() {
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    ClientStream mockStream3 = mock(ClientStream.class);
    InOrder inOrder = inOrder(
        retriableStreamRecorder,
        mockStream1, mockStream2, mockStream3);

    // start
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    retriableStream.start(masterListener);

    inOrder.verify(retriableStreamRecorder).newSubstream(0);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream1).start(sublistenerCaptor1.capture());
    inOrder.verify(mockStream1).isReady();
    inOrder.verifyNoMoreInteractions();

    // normal retry
    doReturn(mockStream2).when(retriableStreamRecorder).newSubstream(1);
    sublistenerCaptor1.getValue()
        .closed(Status.fromCode(RETRIABLE_STATUS_CODE_1), PROCESSED, new Metadata());

    assertEquals(1, fakeClock.numPendingTasks());
    fakeClock.forwardTime((long) (INITIAL_BACKOFF_IN_SECONDS * FAKE_RANDOM), TimeUnit.SECONDS);
    inOrder.verify(retriableStreamRecorder).newSubstream(1);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(mockStream2).isReady();
    inOrder.verifyNoMoreInteractions();
    verify(retriableStreamRecorder, never()).postCommit();
    assertEquals(0, fakeClock.numPendingTasks());

    // no more transparent retry
    doReturn(mockStream3).when(retriableStreamRecorder).newSubstream(2);
    sublistenerCaptor2.getValue()
        .closed(Status.fromCode(NON_RETRIABLE_STATUS_CODE), REFUSED, new Metadata());

    verify(retriableStreamRecorder).postCommit();
  }

  @Test
  public void noRetry_transparentRetry_noEarlyCommit() {
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    InOrder inOrder = inOrder(retriableStreamRecorder, mockStream1, mockStream2);
    RetriableStream<String> unretriableStream = new RecordedRetriableStream(
        method, new Metadata(), channelBufferUsed, PER_RPC_BUFFER_LIMIT, CHANNEL_BUFFER_LIMIT,
        MoreExecutors.directExecutor(), fakeClock.getScheduledExecutorService(),
        null, null, null);

    // start
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    unretriableStream.start(masterListener);

    inOrder.verify(retriableStreamRecorder).newSubstream(0);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream1).start(sublistenerCaptor1.capture());
    inOrder.verify(mockStream1).isReady();
    inOrder.verifyNoMoreInteractions();

    // transparent retry
    doReturn(mockStream2).when(retriableStreamRecorder).newSubstream(0);
    sublistenerCaptor1.getValue()
        .closed(Status.fromCode(NON_RETRIABLE_STATUS_CODE), REFUSED, new Metadata());

    inOrder.verify(retriableStreamRecorder).newSubstream(0);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(retriableStreamRecorder, never()).postCommit();
    inOrder.verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(mockStream2).isReady();
    inOrder.verifyNoMoreInteractions();
    assertEquals(0, fakeClock.numPendingTasks());
  }

  @Test
  public void droppedShouldNeverRetry() {
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    doReturn(mockStream2).when(retriableStreamRecorder).newSubstream(1);

    // start
    retriableStream.start(masterListener);

    verify(retriableStreamRecorder).newSubstream(0);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());
    verify(mockStream1).isReady();

    // drop and verify no retry
    Status status = Status.fromCode(RETRIABLE_STATUS_CODE_1);
    sublistenerCaptor1.getValue().closed(status, DROPPED, new Metadata());

    verifyNoMoreInteractions(mockStream1, mockStream2);
    verify(retriableStreamRecorder).postCommit();
    verify(masterListener).closed(same(status), any(RpcProgress.class), any(Metadata.class));
  }


  @Test
  public void hedging_everythingDrained_oneHedgeReceivesNonFatal_oneHedgeReceivesFatalStatus() {
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    ClientStream mockStream3 = mock(ClientStream.class);
    ClientStream mockStream4 = mock(ClientStream.class);
    ClientStream[] mockStreams =
        new ClientStream[]{mockStream1, mockStream2, mockStream3, mockStream4};

    for (int i = 0; i < mockStreams.length; i++) {
      doReturn(mockStreams[i]).when(retriableStreamRecorder).newSubstream(i);
      final int fakePort = 80 + i;
      doAnswer(new Answer<Void>() {
          @Override
          public Void answer(InvocationOnMock in) {
            InsightBuilder insight = (InsightBuilder) in.getArguments()[0];
            insight.appendKeyValue("remote_addr", "2.2.2.2:" + fakePort);
            return null;
          }
        }).when(mockStreams[i]).appendTimeoutInsight(any(InsightBuilder.class));
    }

    InOrder inOrder = inOrder(
        retriableStreamRecorder,
        masterListener,
        mockStream1, mockStream2, mockStream3, mockStream4);

    // stream settings before start
    hedgingStream.setAuthority(AUTHORITY);
    hedgingStream.setCompressor(COMPRESSOR);
    hedgingStream.setDecompressorRegistry(DECOMPRESSOR_REGISTRY);
    hedgingStream.setFullStreamDecompression(false);
    hedgingStream.setFullStreamDecompression(true);
    hedgingStream.setMaxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE);
    hedgingStream.setMessageCompression(true);
    hedgingStream.setMaxOutboundMessageSize(MAX_OUTBOUND_MESSAGE_SIZE);
    hedgingStream.setMessageCompression(false);

    inOrder.verifyNoMoreInteractions();

    // start
    hedgingStream.start(masterListener);
    assertEquals(1, fakeClock.numPendingTasks());

    inOrder.verify(retriableStreamRecorder).prestart();
    inOrder.verify(retriableStreamRecorder).newSubstream(0);

    inOrder.verify(mockStream1).setAuthority(AUTHORITY);
    inOrder.verify(mockStream1).setCompressor(COMPRESSOR);
    inOrder.verify(mockStream1).setDecompressorRegistry(DECOMPRESSOR_REGISTRY);
    inOrder.verify(mockStream1).setFullStreamDecompression(false);
    inOrder.verify(mockStream1).setFullStreamDecompression(true);
    inOrder.verify(mockStream1).setMaxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE);
    inOrder.verify(mockStream1).setMessageCompression(true);
    inOrder.verify(mockStream1).setMaxOutboundMessageSize(MAX_OUTBOUND_MESSAGE_SIZE);
    inOrder.verify(mockStream1).setMessageCompression(false);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream1).start(sublistenerCaptor1.capture());
    inOrder.verify(mockStream1).isReady();
    inOrder.verifyNoMoreInteractions();

    hedgingStream.sendMessage("msg1");
    hedgingStream.sendMessage("msg2");
    hedgingStream.request(345);
    hedgingStream.flush();
    hedgingStream.flush();
    hedgingStream.sendMessage("msg3");
    hedgingStream.request(456);

    inOrder.verify(mockStream1, times(2)).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream1).request(345);
    inOrder.verify(mockStream1, times(2)).flush();
    inOrder.verify(mockStream1).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream1).request(456);
    inOrder.verifyNoMoreInteractions();

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS - 1, TimeUnit.SECONDS);
    inOrder.verifyNoMoreInteractions();
    // hedge2 starts
    fakeClock.forwardTime(1, TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    inOrder.verify(retriableStreamRecorder).newSubstream(1);
    inOrder.verify(mockStream2).setAuthority(AUTHORITY);
    inOrder.verify(mockStream2).setCompressor(COMPRESSOR);
    inOrder.verify(mockStream2).setDecompressorRegistry(DECOMPRESSOR_REGISTRY);
    inOrder.verify(mockStream2).setFullStreamDecompression(false);
    inOrder.verify(mockStream2).setFullStreamDecompression(true);
    inOrder.verify(mockStream2).setMaxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE);
    inOrder.verify(mockStream2).setMessageCompression(true);
    inOrder.verify(mockStream2).setMaxOutboundMessageSize(MAX_OUTBOUND_MESSAGE_SIZE);
    inOrder.verify(mockStream2).setMessageCompression(false);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(mockStream2, times(2)).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream2).request(345);
    inOrder.verify(mockStream2, times(2)).flush();
    inOrder.verify(mockStream2).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream2).request(456);
    inOrder.verify(mockStream1).isReady();
    inOrder.verify(mockStream2).isReady();
    inOrder.verifyNoMoreInteractions();

    // send more messages
    hedgingStream.sendMessage("msg1 after hedge2 starts");
    hedgingStream.sendMessage("msg2 after hedge2 starts");

    inOrder.verify(mockStream1).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream2).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream1).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream2).writeMessage(any(InputStream.class));
    inOrder.verifyNoMoreInteractions();


    // hedge3 starts
    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());

    inOrder.verify(retriableStreamRecorder).newSubstream(2);
    inOrder.verify(mockStream3).setAuthority(AUTHORITY);
    inOrder.verify(mockStream3).setCompressor(COMPRESSOR);
    inOrder.verify(mockStream3).setDecompressorRegistry(DECOMPRESSOR_REGISTRY);
    inOrder.verify(mockStream3).setFullStreamDecompression(false);
    inOrder.verify(mockStream3).setFullStreamDecompression(true);
    inOrder.verify(mockStream3).setMaxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE);
    inOrder.verify(mockStream3).setMessageCompression(true);
    inOrder.verify(mockStream3).setMaxOutboundMessageSize(MAX_OUTBOUND_MESSAGE_SIZE);
    inOrder.verify(mockStream3).setMessageCompression(false);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor3 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream3).start(sublistenerCaptor3.capture());
    inOrder.verify(mockStream3, times(2)).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream3).request(345);
    inOrder.verify(mockStream3, times(2)).flush();
    inOrder.verify(mockStream3).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream3).request(456);
    inOrder.verify(mockStream3, times(2)).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream1).isReady();
    inOrder.verify(mockStream2).isReady();
    inOrder.verify(mockStream3).isReady();
    inOrder.verifyNoMoreInteractions();

    // send one more message
    hedgingStream.sendMessage("msg1 after hedge3 starts");
    inOrder.verify(mockStream1).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream2).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream3).writeMessage(any(InputStream.class));

    // hedge3 receives nonFatalStatus
    sublistenerCaptor3.getValue().closed(
        NON_FATAL_STATUS_CODE_1.toStatus(), PROCESSED, new Metadata());
    inOrder.verifyNoMoreInteractions();

    // send one more message
    hedgingStream.sendMessage("msg1 after hedge3 fails");
    inOrder.verify(mockStream1).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream2).writeMessage(any(InputStream.class));

    // the hedge mockStream4 starts
    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());

    inOrder.verify(retriableStreamRecorder).newSubstream(3);
    inOrder.verify(mockStream4).setAuthority(AUTHORITY);
    inOrder.verify(mockStream4).setCompressor(COMPRESSOR);
    inOrder.verify(mockStream4).setDecompressorRegistry(DECOMPRESSOR_REGISTRY);
    inOrder.verify(mockStream4).setFullStreamDecompression(false);
    inOrder.verify(mockStream4).setFullStreamDecompression(true);
    inOrder.verify(mockStream4).setMaxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE);
    inOrder.verify(mockStream4).setMessageCompression(true);
    inOrder.verify(mockStream4).setMaxOutboundMessageSize(MAX_OUTBOUND_MESSAGE_SIZE);
    inOrder.verify(mockStream4).setMessageCompression(false);

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor4 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream4).start(sublistenerCaptor4.capture());
    inOrder.verify(mockStream4, times(2)).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream4).request(345);
    inOrder.verify(mockStream4, times(2)).flush();
    inOrder.verify(mockStream4).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream4).request(456);
    inOrder.verify(mockStream4, times(4)).writeMessage(any(InputStream.class));
    inOrder.verify(mockStream1).isReady();
    inOrder.verify(mockStream2).isReady();
    inOrder.verify(mockStream4).isReady();
    inOrder.verifyNoMoreInteractions();

    InsightBuilder insight = new InsightBuilder();
    hedgingStream.appendTimeoutInsight(insight);
    assertThat(insight.toString()).isEqualTo(
        "[closed=[UNAVAILABLE], "
        + "open=[[remote_addr=2.2.2.2:80], [remote_addr=2.2.2.2:81], [remote_addr=2.2.2.2:83]]]");

    // commit
    sublistenerCaptor2.getValue().closed(
        Status.fromCode(FATAL_STATUS_CODE), PROCESSED, new Metadata());

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    inOrder.verify(mockStream1).cancel(statusCaptor.capture());
    assertEquals(Status.CANCELLED.getCode(), statusCaptor.getValue().getCode());
    assertEquals(CANCELLED_BECAUSE_COMMITTED, statusCaptor.getValue().getDescription());
    inOrder.verify(mockStream4).cancel(statusCaptor.capture());
    assertEquals(Status.CANCELLED.getCode(), statusCaptor.getValue().getCode());
    assertEquals(CANCELLED_BECAUSE_COMMITTED, statusCaptor.getValue().getDescription());
    inOrder.verify(retriableStreamRecorder).postCommit();
    sublistenerCaptor1.getValue().closed(
        Status.CANCELLED, PROCESSED, new Metadata());
    sublistenerCaptor4.getValue().closed(
        Status.CANCELLED, PROCESSED, new Metadata());
    inOrder.verify(masterListener).closed(
        any(Status.class), any(RpcProgress.class), any(Metadata.class));
    inOrder.verifyNoMoreInteractions();

    insight = new InsightBuilder();
    hedgingStream.appendTimeoutInsight(insight);
    assertThat(insight.toString()).isEqualTo(
        "[closed=[UNAVAILABLE, INTERNAL, CANCELLED, CANCELLED], "
            + "committed=[remote_addr=2.2.2.2:81]]");
  }

  @Test
  public void hedging_maxAttempts() {
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    ClientStream mockStream3 = mock(ClientStream.class);
    ClientStream mockStream4 = mock(ClientStream.class);
    ClientStream mockStream5 = mock(ClientStream.class);
    ClientStream mockStream6 = mock(ClientStream.class);
    ClientStream mockStream7 = mock(ClientStream.class);
    InOrder inOrder = inOrder(
        mockStream1, mockStream2, mockStream3, mockStream4, mockStream5, mockStream6, mockStream7,
        retriableStreamRecorder, masterListener);
    when(retriableStreamRecorder.newSubstream(anyInt())).thenReturn(
        mockStream1, mockStream2, mockStream3, mockStream4, mockStream5, mockStream6, mockStream7);

    hedgingStream.start(masterListener);
    assertEquals(1, fakeClock.numPendingTasks());
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream1).start(sublistenerCaptor1.capture());
    inOrder.verify(mockStream1).isReady();
    inOrder.verifyNoMoreInteractions();

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(mockStream2).isReady();
    inOrder.verifyNoMoreInteractions();

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor3 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream3).start(sublistenerCaptor3.capture());
    inOrder.verify(mockStream3).isReady();
    inOrder.verifyNoMoreInteractions();

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor4 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream4).start(sublistenerCaptor4.capture());
    inOrder.verify(mockStream4).isReady();
    inOrder.verifyNoMoreInteractions();

    // a random one of the hedges fails
    sublistenerCaptor2.getValue().closed(
        NON_FATAL_STATUS_CODE_1.toStatus(), PROCESSED, new Metadata());

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor5 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream5).start(sublistenerCaptor5.capture());
    inOrder.verify(mockStream5).isReady();
    inOrder.verifyNoMoreInteractions();

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    assertEquals(0, fakeClock.numPendingTasks());
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor6 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream6).start(sublistenerCaptor6.capture());
    inOrder.verify(mockStream6).isReady();
    inOrder.verifyNoMoreInteractions();

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    inOrder.verifyNoMoreInteractions();

    // all but one of the hedges fail
    sublistenerCaptor1.getValue().closed(
        NON_FATAL_STATUS_CODE_2.toStatus(), PROCESSED, new Metadata());
    sublistenerCaptor4.getValue().closed(
        NON_FATAL_STATUS_CODE_2.toStatus(), PROCESSED, new Metadata());
    sublistenerCaptor5.getValue().closed(
        NON_FATAL_STATUS_CODE_2.toStatus(), PROCESSED, new Metadata());
    inOrder.verifyNoMoreInteractions();
    sublistenerCaptor6.getValue().closed(
        NON_FATAL_STATUS_CODE_1.toStatus(), PROCESSED, new Metadata());
    inOrder.verifyNoMoreInteractions();

    hedgingStream.sendMessage("msg1 after commit");
    inOrder.verify(mockStream3).writeMessage(any(InputStream.class));
    inOrder.verifyNoMoreInteractions();

    Metadata heders = new Metadata();
    sublistenerCaptor3.getValue().headersRead(heders);
    inOrder.verify(retriableStreamRecorder).postCommit();
    inOrder.verify(masterListener).headersRead(heders);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void hedging_receiveHeaders() {
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    ClientStream mockStream3 = mock(ClientStream.class);
    InOrder inOrder = inOrder(
        mockStream1, mockStream2, mockStream3,
        retriableStreamRecorder, masterListener);
    when(retriableStreamRecorder.newSubstream(anyInt())).thenReturn(
        mockStream1, mockStream2, mockStream3);

    hedgingStream.start(masterListener);
    assertEquals(1, fakeClock.numPendingTasks());
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream1).start(sublistenerCaptor1.capture());
    inOrder.verify(mockStream1).isReady();
    inOrder.verifyNoMoreInteractions();

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(mockStream2).isReady();
    inOrder.verifyNoMoreInteractions();

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor3 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream3).start(sublistenerCaptor3.capture());
    inOrder.verify(mockStream3).isReady();
    inOrder.verifyNoMoreInteractions();

    // a random one of the hedges receives headers
    Metadata headers = new Metadata();
    sublistenerCaptor2.getValue().headersRead(headers);

    // all but one of the hedges get cancelled
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    inOrder.verify(mockStream1).cancel(statusCaptor.capture());
    assertEquals(Status.CANCELLED.getCode(), statusCaptor.getValue().getCode());
    assertEquals(CANCELLED_BECAUSE_COMMITTED, statusCaptor.getValue().getDescription());
    inOrder.verify(mockStream3).cancel(statusCaptor.capture());
    assertEquals(Status.CANCELLED.getCode(), statusCaptor.getValue().getCode());
    assertEquals(CANCELLED_BECAUSE_COMMITTED, statusCaptor.getValue().getDescription());
    inOrder.verify(retriableStreamRecorder).postCommit();
    inOrder.verify(masterListener).headersRead(headers);
    inOrder.verifyNoMoreInteractions();

  }

  @Test
  public void hedging_pushback_negative() {
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    ClientStream mockStream3 = mock(ClientStream.class);
    ClientStream mockStream4 = mock(ClientStream.class);
    InOrder inOrder = inOrder(
        mockStream1, mockStream2, mockStream3, mockStream4,
        retriableStreamRecorder, masterListener);
    when(retriableStreamRecorder.newSubstream(anyInt())).thenReturn(
        mockStream1, mockStream2, mockStream3, mockStream4);

    hedgingStream.start(masterListener);
    assertEquals(1, fakeClock.numPendingTasks());
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream1).start(sublistenerCaptor1.capture());
    inOrder.verify(mockStream1).isReady();
    inOrder.verifyNoMoreInteractions();

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(mockStream2).isReady();
    inOrder.verifyNoMoreInteractions();

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor3 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream3).start(sublistenerCaptor3.capture());
    inOrder.verify(mockStream3).isReady();
    inOrder.verifyNoMoreInteractions();

    // a random one of the hedges receives a negative pushback
    Metadata headers = new Metadata();
    headers.put(RetriableStream.GRPC_RETRY_PUSHBACK_MS, "-1");
    sublistenerCaptor2.getValue().closed(
        Status.fromCode(NON_FATAL_STATUS_CODE_1), PROCESSED, headers);
    assertEquals(0, fakeClock.numPendingTasks());

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    assertEquals(0, fakeClock.numPendingTasks());
    inOrder.verifyNoMoreInteractions();
  }

  // This is for hedging deadlock when multiple in-flight streams races when transports call back,
  // particularly for OkHttp:
  // e.g. stream1 subListener gets closed() and in turn creates another stream. This ends up with
  // transport1 thread lock is held while waiting for transport2 lock for creating a new stream.
  // Stream2 subListener gets headersRead() and then try to commit and cancel all other drained
  // streams, including the ones that is on transport1. This causes transport2 thread lock held
  // while waiting for transport1 (cancel stream requires lock). Thus deadlock.
  // Deadlock could also happen when two streams both gets headerRead() at the same time.
  // It is believed that retry does not have the issue because streams are created sequentially.
  @Test(timeout = 15000)
  public void hedging_deadlock() throws Exception {
    ClientStream mockStream1 = mock(ClientStream.class); //on transport1
    ClientStream mockStream2 = mock(ClientStream.class); //on transport2
    ClientStream mockStream3 = mock(ClientStream.class); //on transport2
    ClientStream mockStream4 = mock(ClientStream.class); //on transport1

    ReentrantLock transport1Lock = new ReentrantLock();
    ReentrantLock transport2Lock = new ReentrantLock();
    InOrder inOrder = inOrder(
        mockStream1, mockStream2, mockStream3,
        retriableStreamRecorder, masterListener);
    when(retriableStreamRecorder.newSubstream(anyInt()))
        .thenReturn(mockStream1)
        .thenReturn(mockStream2)
        .thenReturn(mockStream3)
        .thenAnswer(new Answer<ClientStream>() {

          @Override
          public ClientStream answer(InvocationOnMock invocation) throws Throwable {
            transport1Lock.lock();
            return mockStream4;
          }
        });

    hedgingStream = newThrottledHedgingStream(null, fakeClock.getScheduledExecutorService());
    hedgingStream.start(masterListener);
    assertEquals(1, fakeClock.numPendingTasks());
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream1).start(sublistenerCaptor1.capture());
    inOrder.verify(mockStream1).isReady();
    inOrder.verifyNoMoreInteractions();

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(mockStream2).isReady();
    inOrder.verifyNoMoreInteractions();

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor3 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream3).start(sublistenerCaptor3.capture());
    inOrder.verify(mockStream3).isReady();
    inOrder.verifyNoMoreInteractions();

    doAnswer(new Answer<Void>() {
      @Override
      @SuppressWarnings("LockNotBeforeTry")
      public Void answer(InvocationOnMock invocation) throws Throwable {
        transport2Lock.lock();
        transport2Lock.unlock();
        return null;
      }
    }).when(mockStream3).cancel(any(Status.class));

    doAnswer(new Answer<Void>() {
      @Override
      @SuppressWarnings("LockNotBeforeTry")
      public Void answer(InvocationOnMock invocation) throws Throwable {
        transport2Lock.lock();
        transport2Lock.unlock();
        return null;
      }
    }).when(mockStream2).cancel(any(Status.class));

    CountDownLatch latch = new CountDownLatch(1);
    Thread transport1Activity = new Thread(new Runnable() {
      @Override
      public void run() {
        transport1Lock.lock();
        try {
          sublistenerCaptor1.getValue().headersRead(new Metadata());
          latch.countDown();
        } finally {
          transport1Lock.unlock();
        }
      }
    }, "Thread-transport1");
    transport1Activity.start();
    Thread transport2Activity = new Thread(new Runnable() {
      @Override
      public void run() {
        transport2Lock.lock();
        try {
          sublistenerCaptor2.getValue()
              .closed(Status.fromCode(NON_FATAL_STATUS_CODE_1), REFUSED, new Metadata());
        } finally {
          transport2Lock.unlock();
          transport1Lock.unlock();
        }
      }
    }, "Thread-transport2");
    transport2Activity.start();
    latch.await();
    fakeClock.runDueTasks();
    transport2Activity.join();
    transport1Activity.join();
  }

  @Test
  public void hedging_pushback_positive() {
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    ClientStream mockStream3 = mock(ClientStream.class);
    ClientStream mockStream4 = mock(ClientStream.class);
    InOrder inOrder = inOrder(
        mockStream1, mockStream2, mockStream3, mockStream4,
        retriableStreamRecorder, masterListener);
    when(retriableStreamRecorder.newSubstream(anyInt())).thenReturn(
        mockStream1, mockStream2, mockStream3, mockStream4);

    hedgingStream.start(masterListener);
    assertEquals(1, fakeClock.numPendingTasks());
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream1).start(sublistenerCaptor1.capture());
    inOrder.verify(mockStream1).isReady();
    inOrder.verifyNoMoreInteractions();

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(mockStream2).isReady();
    inOrder.verifyNoMoreInteractions();


    // hedge1 receives a pushback for HEDGING_DELAY_IN_SECONDS + 1 second
    Metadata headers = new Metadata();
    headers.put(RetriableStream.GRPC_RETRY_PUSHBACK_MS, "101000");
    sublistenerCaptor1.getValue().closed(
        Status.fromCode(NON_FATAL_STATUS_CODE_1), PROCESSED, headers);

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    inOrder.verify(retriableStreamRecorder).newSubstream(anyInt());

    fakeClock.forwardTime(1, TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor3 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream3).start(sublistenerCaptor3.capture());
    inOrder.verify(mockStream3).isReady();
    inOrder.verifyNoMoreInteractions();

    // hedge2 receives a pushback for HEDGING_DELAY_IN_SECONDS - 1 second
    headers = new Metadata();
    headers.put(RetriableStream.GRPC_RETRY_PUSHBACK_MS, "99000");
    sublistenerCaptor2.getValue().closed(
        Status.fromCode(NON_FATAL_STATUS_CODE_1), PROCESSED, headers);

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS - 1, TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor4 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream4).start(sublistenerCaptor4.capture());
    inOrder.verify(mockStream4).isReady();
    inOrder.verifyNoMoreInteractions();

    // commit
    Status fatal = FATAL_STATUS_CODE.toStatus();
    Metadata metadata = new Metadata();
    sublistenerCaptor4.getValue().closed(fatal, PROCESSED, metadata);
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    inOrder.verify(mockStream3).cancel(statusCaptor.capture());
    assertEquals(Status.CANCELLED.getCode(), statusCaptor.getValue().getCode());
    assertEquals(CANCELLED_BECAUSE_COMMITTED, statusCaptor.getValue().getDescription());
    inOrder.verify(retriableStreamRecorder).postCommit();
    sublistenerCaptor3.getValue().closed(Status.CANCELLED, PROCESSED, metadata);
    inOrder.verify(masterListener).closed(fatal, PROCESSED, metadata);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void hedging_cancelled() {
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    InOrder inOrder = inOrder(
        mockStream1, mockStream2,
        retriableStreamRecorder, masterListener);
    when(retriableStreamRecorder.newSubstream(anyInt())).thenReturn(mockStream1, mockStream2);

    hedgingStream.start(masterListener);
    assertEquals(1, fakeClock.numPendingTasks());
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream1).start(sublistenerCaptor1.capture());
    inOrder.verify(mockStream1).isReady();
    inOrder.verifyNoMoreInteractions();

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    assertEquals(1, fakeClock.numPendingTasks());
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    inOrder.verify(mockStream2).start(sublistenerCaptor2.capture());
    inOrder.verify(mockStream1).isReady();
    inOrder.verify(mockStream2).isReady();
    inOrder.verifyNoMoreInteractions();

    Status status = Status.CANCELLED.withDescription("cancelled");
    hedgingStream.cancel(status);

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    inOrder.verify(mockStream1).cancel(statusCaptor.capture());
    assertEquals(Status.CANCELLED.getCode(), statusCaptor.getValue().getCode());
    assertEquals(CANCELLED_BECAUSE_COMMITTED, statusCaptor.getValue().getDescription());
    inOrder.verify(mockStream2).cancel(statusCaptor.capture());
    assertEquals(Status.CANCELLED.getCode(), statusCaptor.getValue().getCode());
    assertEquals(CANCELLED_BECAUSE_COMMITTED, statusCaptor.getValue().getDescription());

    inOrder.verify(retriableStreamRecorder).postCommit();
    sublistenerCaptor1.getValue().closed(Status.CANCELLED, PROCESSED, new Metadata());
    sublistenerCaptor2.getValue().closed(Status.CANCELLED, PROCESSED, new Metadata());
    inOrder.verify(masterListener).closed(
        any(Status.class), any(RpcProgress.class), any(Metadata.class));
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void hedging_perRpcBufferLimitExceeded() {
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    doReturn(mockStream2).when(retriableStreamRecorder).newSubstream(1);

    hedgingStream.start(masterListener);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());
    verify(mockStream1).isReady();

    ClientStreamTracer bufferSizeTracer1 = bufferSizeTracer;
    bufferSizeTracer1.outboundWireSize(PER_RPC_BUFFER_LIMIT - 1);

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream2).start(sublistenerCaptor2.capture());
    verify(mockStream1, times(2)).isReady();
    verify(mockStream2).isReady();

    ClientStreamTracer bufferSizeTracer2 = bufferSizeTracer;
    bufferSizeTracer2.outboundWireSize(PER_RPC_BUFFER_LIMIT - 1);

    verify(retriableStreamRecorder, never()).postCommit();

    // bufferLimitExceeded
    bufferSizeTracer2.outboundWireSize(2);

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(mockStream1).cancel(statusCaptor.capture());
    assertEquals(Status.CANCELLED.getCode(), statusCaptor.getValue().getCode());
    assertEquals(CANCELLED_BECAUSE_COMMITTED, statusCaptor.getValue().getDescription());
    verify(retriableStreamRecorder).postCommit();

    verifyNoMoreInteractions(mockStream1);
    verify(mockStream2).isReady();
    verifyNoMoreInteractions(mockStream2);
  }

  @Test
  public void hedging_channelBufferLimitExceeded() {
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    doReturn(mockStream1).when(retriableStreamRecorder).newSubstream(0);
    doReturn(mockStream2).when(retriableStreamRecorder).newSubstream(1);

    hedgingStream.start(masterListener);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());
    verify(mockStream1).isReady();

    ClientStreamTracer bufferSizeTracer1 = bufferSizeTracer;
    bufferSizeTracer1.outboundWireSize(100);

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream2).start(sublistenerCaptor2.capture());
    verify(mockStream1, times(2)).isReady();
    verify(mockStream2).isReady();

    ClientStreamTracer bufferSizeTracer2 = bufferSizeTracer;
    bufferSizeTracer2.outboundWireSize(100);

    verify(retriableStreamRecorder, never()).postCommit();

    //  channel bufferLimitExceeded
    channelBufferUsed.addAndGet(CHANNEL_BUFFER_LIMIT - 200);
    bufferSizeTracer2.outboundWireSize(101);

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(mockStream1).cancel(statusCaptor.capture());
    assertEquals(Status.CANCELLED.getCode(), statusCaptor.getValue().getCode());
    assertEquals(CANCELLED_BECAUSE_COMMITTED, statusCaptor.getValue().getDescription());
    verify(retriableStreamRecorder).postCommit();
    verifyNoMoreInteractions(mockStream1);
    verifyNoMoreInteractions(mockStream2);
    // verify channel buffer is adjusted
    assertEquals(CHANNEL_BUFFER_LIMIT - 200, channelBufferUsed.addAndGet(0));
  }

  @Test
  public void hedging_transparentRetry() {
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    ClientStream mockStream3 = mock(ClientStream.class);
    ClientStream mockStream4 = mock(ClientStream.class);
    when(retriableStreamRecorder.newSubstream(anyInt()))
        .thenReturn(mockStream1, mockStream2, mockStream3, mockStream4);

    hedgingStream.start(masterListener);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream2).start(sublistenerCaptor2.capture());

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor3 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream3).start(sublistenerCaptor3.capture());

    // transparent retry for hedge2
    sublistenerCaptor2.getValue()
        .closed(Status.fromCode(FATAL_STATUS_CODE), REFUSED, new Metadata());

    ArgumentCaptor<ClientStreamListener> sublistenerCaptor4 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream4).start(sublistenerCaptor4.capture());
    assertEquals(1, fakeClock.numPendingTasks());

    // no more transparent retry
    Status status = Status.fromCode(FATAL_STATUS_CODE);
    Metadata metadata = new Metadata();
    sublistenerCaptor3.getValue().closed(status, REFUSED, metadata);

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(mockStream1).cancel(statusCaptor.capture());
    assertEquals(Status.CANCELLED.getCode(), statusCaptor.getValue().getCode());
    assertEquals(CANCELLED_BECAUSE_COMMITTED, statusCaptor.getValue().getDescription());
    verify(mockStream4).cancel(statusCaptor.capture());
    assertEquals(Status.CANCELLED.getCode(), statusCaptor.getValue().getCode());
    assertEquals(CANCELLED_BECAUSE_COMMITTED, statusCaptor.getValue().getDescription());
    verify(retriableStreamRecorder).postCommit();
    sublistenerCaptor1.getValue().closed(Status.CANCELLED, PROCESSED, metadata);
    sublistenerCaptor4.getValue().closed(Status.CANCELLED, PROCESSED, metadata);
    verify(masterListener).closed(status, REFUSED, metadata);
  }

  @Test
  public void hedging_transparentRetryNotAllowed() {
    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    ClientStream mockStream3 = mock(ClientStream.class);
    when(retriableStreamRecorder.newSubstream(anyInt()))
        .thenReturn(mockStream1, mockStream2, mockStream3);

    hedgingStream.start(masterListener);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream2).start(sublistenerCaptor2.capture());

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor3 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream3).start(sublistenerCaptor3.capture());

    sublistenerCaptor2.getValue()
        .closed(Status.fromCode(NON_FATAL_STATUS_CODE_1), PROCESSED, new Metadata());

    // no more transparent retry
    Status status = Status.fromCode(FATAL_STATUS_CODE);
    Metadata metadata = new Metadata();
    sublistenerCaptor3.getValue()
        .closed(status, REFUSED, metadata);

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(mockStream1).cancel(statusCaptor.capture());
    assertEquals(Status.CANCELLED.getCode(), statusCaptor.getValue().getCode());
    assertEquals(CANCELLED_BECAUSE_COMMITTED, statusCaptor.getValue().getDescription());
    verify(retriableStreamRecorder).postCommit();
    sublistenerCaptor1.getValue()
        .closed(Status.CANCELLED, REFUSED, new Metadata());
    //master listener close should wait until all substreams are closed
    verify(masterListener).closed(status, REFUSED, metadata);
  }

  @Test
  public void hedging_throttledByOtherCall() {
    Throttle throttle = new Throttle(4f, 0.8f);
    RetriableStream<String> hedgingStream = newThrottledHedgingStream(throttle);

    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    ClientStream mockStream3 = mock(ClientStream.class);
    when(retriableStreamRecorder.newSubstream(anyInt()))
        .thenReturn(mockStream1, mockStream2, mockStream3);

    hedgingStream.start(masterListener);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    verify(mockStream2).start(any(ClientStreamListener.class));

    sublistenerCaptor1.getValue().closed(
        Status.fromCode(NON_FATAL_STATUS_CODE_1), PROCESSED, new Metadata());
    assertTrue(throttle.isAboveThreshold()); // count = 3

    // mimic some other call in the channel triggers a throttle countdown
    assertFalse(throttle.onQualifiedFailureThenCheckIsAboveThreshold()); // count = 2

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    verify(mockStream3).start(any(ClientStreamListener.class));
    assertEquals(0, fakeClock.numPendingTasks());
  }

  @Test
  public void hedging_throttledByHedgingStreams() {
    Throttle throttle = new Throttle(4f, 0.8f);
    RetriableStream<String> hedgingStream = newThrottledHedgingStream(throttle);

    ClientStream mockStream1 = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    ClientStream mockStream3 = mock(ClientStream.class);
    when(retriableStreamRecorder.newSubstream(anyInt()))
        .thenReturn(mockStream1, mockStream2, mockStream3);

    hedgingStream.start(masterListener);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor1 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream1).start(sublistenerCaptor1.capture());

    fakeClock.forwardTime(HEDGING_DELAY_IN_SECONDS, TimeUnit.SECONDS);
    ArgumentCaptor<ClientStreamListener> sublistenerCaptor2 =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream2).start(sublistenerCaptor2.capture());

    sublistenerCaptor1.getValue().closed(
        Status.fromCode(NON_FATAL_STATUS_CODE_1), PROCESSED, new Metadata());
    assertTrue(throttle.isAboveThreshold()); // count = 3
    sublistenerCaptor2.getValue().closed(
        Status.fromCode(NON_FATAL_STATUS_CODE_1), PROCESSED, new Metadata());
    assertFalse(throttle.isAboveThreshold()); // count = 2

    verify(masterListener).closed(any(Status.class), any(RpcProgress.class), any(Metadata.class));
    verifyNoInteractions(mockStream3);
    assertEquals(0, fakeClock.numPendingTasks());
  }

  /**
   * Used to stub a retriable stream as well as to record methods of the retriable stream being
   * called.
   */
  private interface RetriableStreamRecorder {
    void postCommit();

    ClientStream newSubstream(int previousAttempts);

    Status prestart();
  }

  private static final class FakeMessageProducer implements MessageProducer {
    private final Iterator<InputStream> iterator;

    public FakeMessageProducer(InputStream... iss) {
      this.iterator = Arrays.asList(iss).iterator();
    }

    @Override
    @Nullable
    public InputStream next() {
      if (iterator.hasNext()) {
        return iterator.next();
      } else {
        return null;
      }
    }
  }
}
