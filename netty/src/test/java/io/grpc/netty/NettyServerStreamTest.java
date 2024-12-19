/*
 * Copyright 2014 The gRPC Authors
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

package io.grpc.netty;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static io.grpc.netty.NettyTestUtil.messageFrame;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Exception.connectionError;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.ServerStreamListener;
import io.grpc.internal.StatsTraceContext;
import io.grpc.internal.StreamListener;
import io.grpc.internal.TransportTracer;
import io.netty.buffer.EmptyByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.DefaultChannelPromise;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.util.AsciiString;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Unit tests for {@link NettyServerStream}. */
@RunWith(JUnit4.class)
public class NettyServerStreamTest extends NettyStreamTestBase<NettyServerStream> {
  private static final int TEST_MAX_MESSAGE_SIZE = 128;

  @Mock
  protected ServerStreamListener serverListener;

  @Mock
  private NettyServerHandler handler;

  private Metadata trailers = new Metadata();
  private final Queue<InputStream> listenerMessageQueue = new LinkedList<>();

  @Before
  @Override
  public void setUp() {
    super.setUp();

    // Verify onReady notification and then reset it.
    verify(listener()).onReady();
    reset(listener());

    doAnswer(
          new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
              StreamListener.MessageProducer producer =
                  (StreamListener.MessageProducer) invocation.getArguments()[0];
              InputStream message;
              while ((message = producer.next()) != null) {
                listenerMessageQueue.add(message);
              }
              return null;
            }
          })
        .when(serverListener)
        .messagesAvailable(ArgumentMatchers.<StreamListener.MessageProducer>any());
  }

  @Test
  public void writeMessageShouldSendResponse() throws Exception {
    ListMultimap<CharSequence, CharSequence> expectedHeaders =
        ImmutableListMultimap.copyOf(new DefaultHttp2Headers()
            .status(Utils.STATUS_OK)
            .set(Utils.CONTENT_TYPE_HEADER, Utils.CONTENT_TYPE_GRPC));

    stream.writeHeaders(new Metadata(), true);

    ArgumentCaptor<SendResponseHeadersCommand> sendHeadersCap =
        ArgumentCaptor.forClass(SendResponseHeadersCommand.class);
    verify(writeQueue).enqueue(sendHeadersCap.capture(), eq(true));
    SendResponseHeadersCommand sendHeaders = sendHeadersCap.getValue();
    assertThat(sendHeaders.stream()).isSameInstanceAs(stream.transportState());
    assertThat(ImmutableListMultimap.copyOf(sendHeaders.headers()))
        .containsExactlyEntriesIn(expectedHeaders);
    assertThat(sendHeaders.endOfStream()).isFalse();

    byte[] msg = smallMessage();
    stream.writeMessage(new ByteArrayInputStream(msg));
    stream.flush();

    verify(writeQueue).enqueue(
        eq(new SendGrpcFrameCommand(stream.transportState(), messageFrame(MESSAGE), false)),
        eq(true));
  }

  @Test
  public void writeFrameFutureFailedShouldCancelRpc() {
    Http2Exception h2Error = connectionError(PROTOCOL_ERROR, "Stream does not exist %d", STREAM_ID);
    when(writeQueue.enqueue(any(SendGrpcFrameCommand.class), anyBoolean())).thenReturn(
        new DefaultChannelPromise(channel).setFailure(h2Error));

    // Write multiple messages to ensure multiple SendGrpcFrameCommand are enqueued. We set up all
    // of them to fail, which allows us to assert that only a single cancel is sent, and the stream
    // isn't spammed with multiple RST_STREAM.
    stream.writeMessage(new ByteArrayInputStream(smallMessage()));
    stream.writeMessage(new ByteArrayInputStream(largeMessage()));
    stream.flush();

    verifyWriteFutureFailure(h2Error);
    // Verify CancelServerStreamCommand enqueued once, right after first SendGrpcFrameCommand.
    InOrder inOrder = Mockito.inOrder(writeQueue);
    inOrder.verify(writeQueue).enqueue(any(SendGrpcFrameCommand.class), eq(false));
    // Verify that failed SendGrpcFrameCommand results in immediate CancelServerStreamCommand.
    inOrder.verify(writeQueue).enqueue(any(CancelServerStreamCommand.class), eq(true));
    // Verify that any other failures do not produce another CancelServerStreamCommand in the queue.
    inOrder.verify(writeQueue, atLeast(1)).enqueue(any(SendGrpcFrameCommand.class), eq(false));
    inOrder.verify(writeQueue).enqueue(any(SendGrpcFrameCommand.class), eq(true));
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void writeHeadersFutureFailedShouldCancelRpc() {
    Http2Exception h2Error = connectionError(PROTOCOL_ERROR, "Stream does not exist %d", STREAM_ID);
    Class<SendResponseHeadersCommand> headersCommandClass = SendResponseHeadersCommand.class;
    when(writeQueue.enqueue(any(headersCommandClass), anyBoolean())).thenReturn(
        new DefaultChannelPromise(channel).setFailure(h2Error));

    // Prepare different headers to make it easier to distinguish in the error message.
    Metadata headers1 = new Metadata();
    headers1.put(Metadata.Key.of("writeHeaders", Metadata.ASCII_STRING_MARSHALLER), "1");
    Metadata headers2 = new Metadata();
    headers2.put(Metadata.Key.of("writeHeaders", Metadata.ASCII_STRING_MARSHALLER), "2");
    Metadata headers3 = new Metadata();
    headers3.put(Metadata.Key.of("writeHeaders", Metadata.ASCII_STRING_MARSHALLER), "3");

    // Note writeHeaders flush argument shouldn't matter for this test.
    stream().writeHeaders(headers1, false);
    stream().writeHeaders(headers2, false);
    stream().writeHeaders(headers3, true);
    stream.flush();

    verifyWriteFutureFailure(h2Error);
    // Verify CancelServerStreamCommand enqueued once, right after first SendResponseHeadersCommand.
    InOrder inOrder = Mockito.inOrder(writeQueue);
    inOrder.verify(writeQueue).enqueue(any(headersCommandClass), anyBoolean());
    inOrder.verify(writeQueue).enqueue(any(CancelServerStreamCommand.class), eq(true));
    inOrder.verify(writeQueue, atLeast(1)).enqueue(any(headersCommandClass), anyBoolean());
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void writeTrailersFutureFailedShouldCancelRpc() {
    Http2Exception h2Error = connectionError(PROTOCOL_ERROR, "Stream does not exist %d", STREAM_ID);
    when(writeQueue.enqueue(any(SendResponseHeadersCommand.class), eq(true))).thenReturn(
        new DefaultChannelPromise(channel).setFailure(h2Error));

    stream().close(Status.OK, trailers);

    verifyWriteFutureFailure(h2Error);
    verify(writeQueue).enqueue(any(CancelServerStreamCommand.class), eq(true));
  }

  private void verifyWriteFutureFailure(Http2Exception h2Error) {
    // Check the error that caused the future write failure propagated via Status.
    Status cancelReason = findCancelServerStreamCommand().reason();
    assertThat(cancelReason.getCode()).isEqualTo(Status.INTERNAL.getCode());
    assertThat(cancelReason.getCause()).isEqualTo(h2Error);
    // Verify the listener has closed.
    verify(serverListener).closed(same(cancelReason));
  }

  private CancelServerStreamCommand findCancelServerStreamCommand() {
    // Ensure there's no CancelServerStreamCommand enqueued with flush=false.
    verify(writeQueue, never()).enqueue(any(CancelServerStreamCommand.class), eq(false));

    List<CancelServerStreamCommand> commands = Lists.newArrayList(
        Iterables.transform(
          Iterables.filter(
            Mockito.mockingDetails(writeQueue).getInvocations(),
            // Get enqueue() innovations only
            invocation -> invocation.getMethod().getName().equals("enqueue")
              // Find the cancel commands.
              && invocation.getArgument(0) instanceof CancelServerStreamCommand),
          invocation -> invocation.getArgument(0, CancelServerStreamCommand.class)));

    assertWithMessage("Expected exactly one CancelClientStreamCommand").that(commands).hasSize(1);
    return commands.get(0);
  }

  @Test
  public void writeHeadersShouldSendHeaders() throws Exception {
    Metadata headers = new Metadata();
    ListMultimap<CharSequence, CharSequence> expectedHeaders =
        ImmutableListMultimap.copyOf(Utils.convertServerHeaders(headers));

    stream().writeHeaders(headers, true);

    ArgumentCaptor<SendResponseHeadersCommand> sendHeadersCap =
        ArgumentCaptor.forClass(SendResponseHeadersCommand.class);
    verify(writeQueue).enqueue(sendHeadersCap.capture(), eq(true));
    SendResponseHeadersCommand sendHeaders = sendHeadersCap.getValue();
    assertThat(sendHeaders.stream()).isSameInstanceAs(stream.transportState());
    assertThat(ImmutableListMultimap.copyOf(sendHeaders.headers()))
        .containsExactlyEntriesIn(expectedHeaders);
    assertThat(sendHeaders.endOfStream()).isFalse();
  }

  @Test
  public void closeBeforeClientHalfCloseShouldSucceed() throws Exception {
    ListMultimap<CharSequence, CharSequence> expectedHeaders =
        ImmutableListMultimap.copyOf(new DefaultHttp2Headers()
            .status(new AsciiString("200"))
            .set(new AsciiString("content-type"), new AsciiString("application/grpc"))
            .set(new AsciiString("grpc-status"), new AsciiString("0")));

    stream().close(Status.OK, new Metadata());

    ArgumentCaptor<SendResponseHeadersCommand> sendHeadersCap =
        ArgumentCaptor.forClass(SendResponseHeadersCommand.class);
    verify(writeQueue).enqueue(sendHeadersCap.capture(), eq(true));
    SendResponseHeadersCommand sendHeaders = sendHeadersCap.getValue();
    assertThat(sendHeaders.stream()).isSameInstanceAs(stream.transportState());
    assertThat(ImmutableListMultimap.copyOf(sendHeaders.headers()))
        .containsExactlyEntriesIn(expectedHeaders);
    assertThat(sendHeaders.endOfStream()).isTrue();
    verifyNoInteractions(serverListener);

    // Sending complete. Listener gets closed()
    stream().transportState().complete();

    verify(serverListener).closed(Status.OK);
    assertNull("no message expected", listenerMessageQueue.poll());
  }

  @Test
  public void closeWithErrorBeforeClientHalfCloseShouldSucceed() throws Exception {
    ListMultimap<CharSequence, CharSequence> expectedHeaders =
        ImmutableListMultimap.copyOf(new DefaultHttp2Headers()
            .status(new AsciiString("200"))
            .set(new AsciiString("content-type"), new AsciiString("application/grpc"))
            .set(new AsciiString("grpc-status"), new AsciiString("1")));

    // Error is sent on wire and ends the stream
    stream().close(Status.CANCELLED, trailers);

    ArgumentCaptor<SendResponseHeadersCommand> sendHeadersCap =
        ArgumentCaptor.forClass(SendResponseHeadersCommand.class);
    verify(writeQueue).enqueue(sendHeadersCap.capture(), eq(true));
    SendResponseHeadersCommand sendHeaders = sendHeadersCap.getValue();
    assertThat(sendHeaders.stream()).isSameInstanceAs(stream.transportState());
    assertThat(ImmutableListMultimap.copyOf(sendHeaders.headers()))
        .containsExactlyEntriesIn(expectedHeaders);
    assertThat(sendHeaders.endOfStream()).isTrue();
    verifyNoInteractions(serverListener);

    // Sending complete. Listener gets closed()
    stream().transportState().complete();
    verify(serverListener).closed(Status.OK);
    assertNull("no message expected", listenerMessageQueue.poll());
  }

  @Test
  public void closeAfterClientHalfCloseShouldSucceed() throws Exception {
    ListMultimap<CharSequence, CharSequence> expectedHeaders =
        ImmutableListMultimap.copyOf(new DefaultHttp2Headers()
            .status(new AsciiString("200"))
            .set(new AsciiString("content-type"), new AsciiString("application/grpc"))
            .set(new AsciiString("grpc-status"), new AsciiString("0")));

    // Client half-closes. Listener gets halfClosed()
    stream().transportState()
        .inboundDataReceived(new EmptyByteBuf(UnpooledByteBufAllocator.DEFAULT), true);

    verify(serverListener).halfClosed();

    // Server closes. Status sent
    stream().close(Status.OK, trailers);
    assertNull("no message expected", listenerMessageQueue.poll());

    ArgumentCaptor<SendResponseHeadersCommand> cmdCap =
        ArgumentCaptor.forClass(SendResponseHeadersCommand.class);
    verify(writeQueue).enqueue(cmdCap.capture(), eq(true));
    SendResponseHeadersCommand cmd = cmdCap.getValue();
    assertThat(cmd.stream()).isSameInstanceAs(stream.transportState());
    assertThat(ImmutableListMultimap.copyOf(cmd.headers()))
        .containsExactlyEntriesIn(expectedHeaders);
    assertThat(cmd.endOfStream()).isTrue();

    // Sending and receiving complete. Listener gets closed()
    stream().transportState().complete();
    verify(serverListener).closed(Status.OK);
    assertNull("no message expected", listenerMessageQueue.poll());
  }

  @Test
  public void abortStreamAndNotSendStatus() throws Exception {
    Status status = Status.INTERNAL.withCause(new Throwable());
    stream().transportState().transportReportStatus(status);
    verify(serverListener).closed(same(status));
    verify(channel, never()).writeAndFlush(any(SendResponseHeadersCommand.class));
    verify(channel, never()).writeAndFlush(any(SendGrpcFrameCommand.class));
    assertNull("no message expected", listenerMessageQueue.poll());
  }

  @Test
  public void abortStreamAfterClientHalfCloseShouldCallClose() {
    Status status = Status.INTERNAL.withCause(new Throwable());
    // Client half-closes. Listener gets halfClosed()
    stream().transportState().inboundDataReceived(
        new EmptyByteBuf(UnpooledByteBufAllocator.DEFAULT), true);
    verify(serverListener).halfClosed();
    // Abort from the transport layer
    stream().transportState().transportReportStatus(status);
    verify(serverListener).closed(same(status));
    assertNull("no message expected", listenerMessageQueue.poll());
  }

  @Test
  public void emptyFramerShouldSendNoPayload() {
    ListMultimap<CharSequence, CharSequence> expectedHeaders =
        ImmutableListMultimap.copyOf(new DefaultHttp2Headers()
            .status(new AsciiString("200"))
            .set(new AsciiString("content-type"), new AsciiString("application/grpc"))
            .set(new AsciiString("grpc-status"), new AsciiString("0")));
    ArgumentCaptor<SendResponseHeadersCommand> cmdCap =
        ArgumentCaptor.forClass(SendResponseHeadersCommand.class);

    stream().close(Status.OK, new Metadata());

    verify(writeQueue).enqueue(cmdCap.capture(), eq(true));
    SendResponseHeadersCommand cmd = cmdCap.getValue();
    assertThat(cmd.stream()).isSameInstanceAs(stream.transportState());
    assertThat(ImmutableListMultimap.copyOf(cmd.headers()))
        .containsExactlyEntriesIn(expectedHeaders);
    assertThat(cmd.endOfStream()).isTrue();
  }

  @Test
  public void cancelStreamShouldSucceed() {
    stream().cancel(Status.DEADLINE_EXCEEDED);
    verify(writeQueue).enqueue(
        CancelServerStreamCommand.withReset(stream().transportState(), Status.DEADLINE_EXCEEDED),
        true);
  }

  @Test
  public void oversizedMessagesResultInResourceExhaustedTrailers() throws Exception {
    @SuppressWarnings("InlineMeInliner") // Requires Java 11
    String oversizedMsg = Strings.repeat("a", TEST_MAX_MESSAGE_SIZE + 1);
    stream.request(1);
    stream.transportState().inboundDataReceived(messageFrame(oversizedMsg), false);
    assertNull("message should have caused a deframer error", listenerMessageQueue().poll());

    ArgumentCaptor<CancelServerStreamCommand> cancelCmdCap =
            ArgumentCaptor.forClass(CancelServerStreamCommand.class);
    verify(writeQueue).enqueue(cancelCmdCap.capture(), eq(true));

    Status status = Status.RESOURCE_EXHAUSTED
            .withDescription("gRPC message exceeds maximum size 128: 129");

    CancelServerStreamCommand actualCmd = cancelCmdCap.getValue();
    assertThat(actualCmd.reason().getCode()).isEqualTo(status.getCode());
    assertThat(actualCmd.reason().getDescription()).isEqualTo(status.getDescription());
    assertThat(actualCmd.wantsHeaders()).isTrue();
  }

  @Override
  @SuppressWarnings("DirectInvocationOnMock")
  protected NettyServerStream createStream() {
    when(handler.getWriteQueue()).thenReturn(writeQueue);
    StatsTraceContext statsTraceCtx = StatsTraceContext.NOOP;
    TransportTracer transportTracer = new TransportTracer();
    NettyServerStream.TransportState state = new NettyServerStream.TransportState(
        handler, channel.eventLoop(), http2Stream, TEST_MAX_MESSAGE_SIZE, statsTraceCtx,
        transportTracer, "method");
    NettyServerStream stream = new NettyServerStream(channel, state, Attributes.EMPTY,
        "test-authority", statsTraceCtx);
    stream.transportState().setListener(serverListener);
    state.onStreamAllocated();
    verify(serverListener, atLeastOnce()).onReady();
    verifyNoMoreInteractions(serverListener);
    return stream;
  }

  @Override
  protected void sendHeadersIfServer() {
    stream.writeHeaders(new Metadata(), true);
  }

  @Override
  protected void closeStream() {
    stream().close(Status.ABORTED, new Metadata());
  }

  @Override
  protected ServerStreamListener listener() {
    return serverListener;
  }

  @Override
  protected Queue<InputStream> listenerMessageQueue() {
    return listenerMessageQueue;
  }

  private NettyServerStream stream() {
    return stream;
  }
}
