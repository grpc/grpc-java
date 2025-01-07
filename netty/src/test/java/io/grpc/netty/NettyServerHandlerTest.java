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

import static io.grpc.internal.GrpcUtil.DEFAULT_MAX_MESSAGE_SIZE;
import static io.grpc.internal.GrpcUtil.DEFAULT_SERVER_KEEPALIVE_TIMEOUT_NANOS;
import static io.grpc.internal.GrpcUtil.DEFAULT_SERVER_KEEPALIVE_TIME_NANOS;
import static io.grpc.netty.NettyServerBuilder.MAX_CONNECTION_AGE_GRACE_NANOS_INFINITE;
import static io.grpc.netty.NettyServerBuilder.MAX_CONNECTION_AGE_NANOS_DISABLED;
import static io.grpc.netty.NettyServerBuilder.MAX_CONNECTION_IDLE_NANOS_DISABLED;
import static io.grpc.netty.NettyServerBuilder.MAX_RST_COUNT_DISABLED;
import static io.grpc.netty.Utils.CONTENT_TYPE_GRPC;
import static io.grpc.netty.Utils.CONTENT_TYPE_HEADER;
import static io.grpc.netty.Utils.HTTP_METHOD;
import static io.grpc.netty.Utils.TE_HEADER;
import static io.grpc.netty.Utils.TE_TRAILERS;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.io.ByteStreams;
import com.google.common.truth.Truth;
import io.grpc.Attributes;
import io.grpc.InternalStatus;
import io.grpc.Metadata;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StreamTracer;
import io.grpc.internal.AbstractStream;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.KeepAliveEnforcer;
import io.grpc.internal.KeepAliveManager;
import io.grpc.internal.ServerStream;
import io.grpc.internal.ServerStreamListener;
import io.grpc.internal.ServerTransportListener;
import io.grpc.internal.StatsTraceContext;
import io.grpc.internal.StreamListener;
import io.grpc.internal.testing.TestServerStreamTracer;
import io.grpc.netty.GrpcHttp2HeadersUtils.GrpcHttp2ServerHeadersDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2LocalFlowController;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.util.AsciiString;
import java.io.InputStream;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link NettyServerHandler}.
 */
@RunWith(JUnit4.class)
public class NettyServerHandlerTest extends NettyHandlerTestBase<NettyServerHandler> {

  @Rule
  public final TestRule globalTimeout = new DisableOnDebug(Timeout.seconds(10));
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  private static final AsciiString HTTP_FAKE_METHOD = AsciiString.of("FAKE");
  @Mock
  private ServerStreamListener streamListener;
  @Mock
  private ServerStreamTracer.Factory streamTracerFactory;
  private final ServerTransportListener transportListener =
      mock(ServerTransportListener.class, delegatesTo(new ServerTransportListenerImpl()));
  private final TestServerStreamTracer streamTracer = new TestServerStreamTracer();
  private NettyServerStream stream;
  private KeepAliveManager spyKeepAliveManager;
  final Queue<InputStream> streamListenerMessageQueue = new LinkedList<>();

  private int maxConcurrentStreams = Integer.MAX_VALUE;
  private int maxHeaderListSize = Integer.MAX_VALUE;
  private int softLimitHeaderListSize = Integer.MAX_VALUE;
  private boolean permitKeepAliveWithoutCalls = true;
  private long permitKeepAliveTimeInNanos = 0;
  private long maxConnectionIdleInNanos = MAX_CONNECTION_IDLE_NANOS_DISABLED;
  private long maxConnectionAgeInNanos = MAX_CONNECTION_AGE_NANOS_DISABLED;
  private long maxConnectionAgeGraceInNanos = MAX_CONNECTION_AGE_GRACE_NANOS_INFINITE;
  private long keepAliveTimeInNanos = DEFAULT_SERVER_KEEPALIVE_TIME_NANOS;
  private long keepAliveTimeoutInNanos = DEFAULT_SERVER_KEEPALIVE_TIMEOUT_NANOS;
  private int maxRstCount = MAX_RST_COUNT_DISABLED;
  private long maxRstPeriodNanos;

  private class ServerTransportListenerImpl implements ServerTransportListener {

    @Override
    public void streamCreated(ServerStream stream, String method, Metadata headers) {
      stream.setListener(streamListener);
    }

    @Override
    public Attributes transportReady(Attributes attributes) {
      return Attributes.EMPTY;
    }

    @Override
    public void transportTerminated() {
    }
  }

  @Before
  public void setUp() {
    when(streamTracerFactory.newServerStreamTracer(anyString(), any(Metadata.class)))
        .thenReturn(streamTracer);

    doAnswer(
          new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
              StreamListener.MessageProducer producer =
                  (StreamListener.MessageProducer) invocation.getArguments()[0];
              InputStream message;
              while ((message = producer.next()) != null) {
                streamListenerMessageQueue.add(message);
              }
              return null;
            }
          })
        .when(streamListener)
        .messagesAvailable(any(StreamListener.MessageProducer.class));
  }

  @Override
  protected void manualSetUp() throws Exception {
    assertNull("manualSetUp should not run more than once", handler());

    initChannel(new GrpcHttp2ServerHeadersDecoder(GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE));

    // replace the keepAliveManager with spyKeepAliveManager
    spyKeepAliveManager =
        mock(KeepAliveManager.class, delegatesTo(handler().getKeepAliveManagerForTest()));
    handler().setKeepAliveManagerForTest(spyKeepAliveManager);

    // Simulate receipt of the connection preface
    handler().handleProtocolNegotiationCompleted(Attributes.EMPTY, /*securityInfo=*/ null);
    channelRead(Http2CodecUtil.connectionPrefaceBuf());
    // Simulate receipt of initial remote settings.
    ByteBuf serializedSettings = serializeSettings(new Http2Settings());
    channelRead(serializedSettings);
    channel().releaseOutbound();
  }

  @Test
  public void transportReadyDelayedUntilConnectionPreface() throws Exception {
    initChannel(new GrpcHttp2ServerHeadersDecoder(GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE));

    handler().handleProtocolNegotiationCompleted(Attributes.EMPTY, /*securityInfo=*/ null);
    verify(transportListener, never()).transportReady(any(Attributes.class));

    // Simulate receipt of the connection preface
    channelRead(Http2CodecUtil.connectionPrefaceBuf());
    channelRead(serializeSettings(new Http2Settings()));
    verify(transportListener).transportReady(any(Attributes.class));
  }

  @Test
  public void sendFrameShouldSucceed() throws Exception {
    manualSetUp();
    createStream();

    // Send a frame and verify that it was written.
    ByteBuf content = content();
    ChannelFuture future = enqueue(
        new SendGrpcFrameCommand(stream.transportState(), content, false));
    assertTrue(future.isSuccess());
    verifyWrite().writeData(eq(ctx()), eq(STREAM_ID), same(content), eq(0), eq(false),
        any(ChannelPromise.class));
  }

  @Test
  public void streamTracerCreated() throws Exception {
    manualSetUp();
    createStream();

    verify(streamTracerFactory).newServerStreamTracer(eq("foo/bar"), any(Metadata.class));
    StatsTraceContext statsTraceCtx = stream.statsTraceContext();
    List<StreamTracer> tracers = statsTraceCtx.getTracersForTest();
    assertEquals(1, tracers.size());
    assertSame(streamTracer, tracers.get(0));
  }

  @Test
  public void inboundDataWithEndStreamShouldForwardToStreamListener() throws Exception {
    manualSetUp();
    inboundDataShouldForwardToStreamListener(true);
  }

  @Test
  public void inboundDataShouldForwardToStreamListener() throws Exception {
    manualSetUp();
    inboundDataShouldForwardToStreamListener(false);
  }

  private void inboundDataShouldForwardToStreamListener(boolean endStream) throws Exception {
    createStream();
    stream.request(1);

    // Create a data frame and then trigger the handler to read it.
    ByteBuf frame = grpcDataFrame(STREAM_ID, endStream, contentAsArray());
    channelRead(frame);
    channel().releaseOutbound();
    verify(streamListener, atLeastOnce())
        .messagesAvailable(any(StreamListener.MessageProducer.class));
    InputStream message = streamListenerMessageQueue.poll();
    assertArrayEquals(contentAsArray(), ByteStreams.toByteArray(message));
    message.close();
    assertNull("no additional message expected", streamListenerMessageQueue.poll());

    if (endStream) {
      verify(streamListener).halfClosed();
    }
    verify(streamListener, atLeastOnce()).onReady();
    verifyNoMoreInteractions(streamListener);
  }

  @Test
  public void clientHalfCloseShouldForwardToStreamListener() throws Exception {
    manualSetUp();
    createStream();
    stream.request(1);

    channelRead(emptyGrpcFrame(STREAM_ID, true));

    verify(streamListener, atLeastOnce())
        .messagesAvailable(any(StreamListener.MessageProducer.class));
    InputStream message = streamListenerMessageQueue.poll();
    assertArrayEquals(new byte[0], ByteStreams.toByteArray(message));
    assertNull("no additional message expected", streamListenerMessageQueue.poll());
    verify(streamListener).halfClosed();
    verify(streamListener, atLeastOnce()).onReady();
    verifyNoMoreInteractions(streamListener);
  }

  @Test
  public void clientCancelShouldForwardToStreamListener() throws Exception {
    manualSetUp();
    createStream();

    channelRead(rstStreamFrame(STREAM_ID, (int) Http2Error.CANCEL.code()));

    ArgumentCaptor<Status> statusCap = ArgumentCaptor.forClass(Status.class);
    verify(streamListener).closed(statusCap.capture());
    assertEquals(Code.CANCELLED, statusCap.getValue().getCode());
    Truth.assertThat(statusCap.getValue().getDescription()).contains("RST_STREAM");
    verify(streamListener, atLeastOnce()).onReady();
    assertNull("no messages expected", streamListenerMessageQueue.poll());
  }

  @Test
  public void streamErrorShouldNotCloseChannel() throws Exception {
    manualSetUp();
    createStream();
    stream.request(1);

    // When a DATA frame is read, throw an exception. It will be converted into an
    // Http2StreamException.
    RuntimeException e = new RuntimeException("Fake Exception");
    doThrow(e).when(streamListener).messagesAvailable(any(StreamListener.MessageProducer.class));

    // Read a DATA frame to trigger the exception.
    channelRead(emptyGrpcFrame(STREAM_ID, true));

    // Verify that the channel was NOT closed.
    assertTrue(channel().isOpen());

    // Verify the stream was closed.
    ArgumentCaptor<Status> captor = ArgumentCaptor.forClass(Status.class);
    verify(streamListener).closed(captor.capture());
    assertEquals(e, captor.getValue().asException().getCause());
    assertEquals(Code.UNKNOWN, captor.getValue().getCode());
  }

  @Test
  public void closeShouldGracefullyCloseChannel() throws Exception {
    manualSetUp();
    handler().close(ctx(), newPromise());

    verifyWrite().writeGoAway(eq(ctx()), eq(Integer.MAX_VALUE), eq(Http2Error.NO_ERROR.code()),
        isA(ByteBuf.class), any(ChannelPromise.class));
    verifyWrite().writePing(
        eq(ctx()),
        eq(false),
        eq(NettyServerHandler.GRACEFUL_SHUTDOWN_PING),
        isA(ChannelPromise.class));
    channelRead(pingFrame(/*ack=*/ true , NettyServerHandler.GRACEFUL_SHUTDOWN_PING));

    verifyWrite().writeGoAway(eq(ctx()), eq(0), eq(Http2Error.NO_ERROR.code()),
        isA(ByteBuf.class), any(ChannelPromise.class));

    // Verify that the channel was closed.
    assertFalse(channel().isOpen());
  }

  @Test
  public void gracefulCloseShouldGracefullyCloseChannel() throws Exception {
    manualSetUp();
    handler()
        .write(ctx(), new GracefulServerCloseCommand("test", 1, TimeUnit.MINUTES), newPromise());

    verifyWrite().writeGoAway(eq(ctx()), eq(Integer.MAX_VALUE), eq(Http2Error.NO_ERROR.code()),
        isA(ByteBuf.class), any(ChannelPromise.class));
    verifyWrite().writePing(
        eq(ctx()),
        eq(false),
        eq(NettyServerHandler.GRACEFUL_SHUTDOWN_PING),
        isA(ChannelPromise.class));
    channelRead(pingFrame(/*ack=*/ true , NettyServerHandler.GRACEFUL_SHUTDOWN_PING));

    verifyWrite().writeGoAway(eq(ctx()), eq(0), eq(Http2Error.NO_ERROR.code()),
        isA(ByteBuf.class), any(ChannelPromise.class));

    // Verify that the channel was closed.
    assertFalse(channel().isOpen());
  }

  @Test
  public void secondGracefulCloseIsSafe() throws Exception {
    manualSetUp();
    handler().write(ctx(), new GracefulServerCloseCommand("test"), newPromise());

    verifyWrite().writeGoAway(eq(ctx()), eq(Integer.MAX_VALUE), eq(Http2Error.NO_ERROR.code()),
        isA(ByteBuf.class), any(ChannelPromise.class));
    verifyWrite().writePing(
        eq(ctx()),
        eq(false),
        eq(NettyServerHandler.GRACEFUL_SHUTDOWN_PING),
        isA(ChannelPromise.class));

    handler().write(ctx(), new GracefulServerCloseCommand("test2"), newPromise());

    channel().runPendingTasks();
    // No additional GOAWAYs.
    verifyWrite().writeGoAway(any(ChannelHandlerContext.class), any(Integer.class), any(Long.class),
        any(ByteBuf.class), any(ChannelPromise.class));
    channel().checkException();
    assertTrue(channel().isOpen());

    channelRead(pingFrame(/*ack=*/ true , NettyServerHandler.GRACEFUL_SHUTDOWN_PING));
    verifyWrite().writeGoAway(eq(ctx()), eq(0), eq(Http2Error.NO_ERROR.code()),
        isA(ByteBuf.class), any(ChannelPromise.class));
    assertFalse(channel().isOpen());
  }

  @Test
  public void exceptionCaughtShouldCloseConnection() throws Exception {
    manualSetUp();
    handler().exceptionCaught(ctx(), new RuntimeException("fake exception"));

    // TODO(nmittler): EmbeddedChannel does not currently invoke the channelInactive processing,
    // so exceptionCaught() will not close streams properly in this test.
    // Once https://github.com/netty/netty/issues/4316 is resolved, we should also verify that
    // any open streams are closed properly.
    assertFalse(channel().isOpen());
  }

  @Test
  public void channelInactiveShouldCloseStreams() throws Exception {
    manualSetUp();
    createStream();
    handler().channelInactive(ctx());
    ArgumentCaptor<Status> captor = ArgumentCaptor.forClass(Status.class);
    verify(streamListener).closed(captor.capture());
    assertFalse(captor.getValue().isOk());
  }

  @Test
  public void shouldAdvertiseMaxConcurrentStreams() throws Exception {
    maxConcurrentStreams = 314;
    manualSetUp();

    ArgumentCaptor<Http2Settings> captor = ArgumentCaptor.forClass(Http2Settings.class);
    verifyWrite().writeSettings(
        any(ChannelHandlerContext.class), captor.capture(), any(ChannelPromise.class));

    assertEquals(maxConcurrentStreams, captor.getValue().maxConcurrentStreams().longValue());
  }

  @Test
  public void shouldAdvertiseMaxHeaderListSize() throws Exception {
    maxHeaderListSize = 123;
    manualSetUp();

    ArgumentCaptor<Http2Settings> captor = ArgumentCaptor.forClass(Http2Settings.class);
    verifyWrite().writeSettings(
        any(ChannelHandlerContext.class), captor.capture(), any(ChannelPromise.class));

    assertEquals(maxHeaderListSize, captor.getValue().maxHeaderListSize().longValue());
  }

  @Test
  public void connectionWindowShouldBeOverridden() throws Exception {
    flowControlWindow = 1048576; // 1MiB
    manualSetUp();

    Http2Stream connectionStream = connection().connectionStream();
    Http2LocalFlowController localFlowController = connection().local().flowController();
    int actualInitialWindowSize = localFlowController.initialWindowSize(connectionStream);
    int actualWindowSize = localFlowController.windowSize(connectionStream);
    assertEquals(flowControlWindow, actualWindowSize);
    assertEquals(flowControlWindow, actualInitialWindowSize);
  }

  @Test
  public void cancelShouldSendRstStream() throws Exception {
    manualSetUp();
    createStream();
    enqueue(CancelServerStreamCommand.withReset(stream.transportState(), Status.DEADLINE_EXCEEDED));
    verifyWrite().writeRstStream(eq(ctx()), eq(stream.transportState().id()),
        eq(Http2Error.CANCEL.code()), any(ChannelPromise.class));
  }

  @Test
  public void cancelWithNotify_shouldSendHeaders() throws Exception {
    manualSetUp();
    createStream();

    enqueue(CancelServerStreamCommand.withReason(
            stream.transportState(),
            Status.RESOURCE_EXHAUSTED.withDescription("my custom description")
    ));

    ArgumentCaptor<Http2Headers> captor = ArgumentCaptor.forClass(Http2Headers.class);
    verifyWrite()
            .writeHeaders(
                    eq(ctx()),
                    eq(STREAM_ID),
                    captor.capture(),
                    eq(0),
                    eq(true),
                    any(ChannelPromise.class));

    // For arcane reasons, the specific implementation of Http2Headers here doesn't actually support
    // methods like `get(...)`, so we have to manually convert it into a map.
    Map<String, String> actualHeaders = new HashMap<>();
    for (Map.Entry<CharSequence, CharSequence> entry : captor.getValue()) {
      actualHeaders.put(entry.getKey().toString(), entry.getValue().toString());
    }
    assertEquals("8", actualHeaders.get(InternalStatus.CODE_KEY.name()));
    assertEquals("my custom description", actualHeaders.get(InternalStatus.MESSAGE_KEY.name()));
  }

  @Test
  public void headersWithInvalidContentTypeShouldFail() throws Exception {
    manualSetUp();
    Http2Headers headers = new DefaultHttp2Headers()
        .method(HTTP_METHOD)
        .set(CONTENT_TYPE_HEADER, new AsciiString("application/bad", UTF_8))
        .set(TE_HEADER, TE_TRAILERS)
        .path(new AsciiString("/foo/bar"));
    ByteBuf headersFrame = headersFrame(STREAM_ID, headers);
    channelRead(headersFrame);
    Http2Headers responseHeaders = new DefaultHttp2Headers()
        .set(InternalStatus.CODE_KEY.name(), String.valueOf(Code.INTERNAL.value()))
        .set(InternalStatus.MESSAGE_KEY.name(), "Content-Type 'application/bad' is not supported")
        .status("" + 415)
        .set(CONTENT_TYPE_HEADER, "text/plain; charset=utf-8");

    verifyWrite()
        .writeHeaders(
            eq(ctx()),
            eq(STREAM_ID),
            eq(responseHeaders),
            eq(0),
            eq(false),
            any(ChannelPromise.class));
  }

  @Test
  public void headersWithInvalidMethodShouldFail() throws Exception {
    manualSetUp();
    Http2Headers headers = new DefaultHttp2Headers()
        .method(HTTP_FAKE_METHOD)
        .set(CONTENT_TYPE_HEADER, CONTENT_TYPE_GRPC)
        .path(new AsciiString("/foo/bar"));
    ByteBuf headersFrame = headersFrame(STREAM_ID, headers);
    channelRead(headersFrame);
    Http2Headers responseHeaders = new DefaultHttp2Headers()
        .set(InternalStatus.CODE_KEY.name(), String.valueOf(Code.INTERNAL.value()))
        .set(InternalStatus.MESSAGE_KEY.name(), "Method 'FAKE' is not supported")
        .status("" + 405)
        .set(CONTENT_TYPE_HEADER, "text/plain; charset=utf-8");

    verifyWrite()
        .writeHeaders(
            eq(ctx()),
            eq(STREAM_ID),
            eq(responseHeaders),
            eq(0),
            eq(false),
            any(ChannelPromise.class));
  }

  @Test
  public void headersWithMissingPathShouldFail() throws Exception {
    manualSetUp();
    Http2Headers headers = new DefaultHttp2Headers()
        .method(HTTP_METHOD)
        .set(CONTENT_TYPE_HEADER, CONTENT_TYPE_GRPC);
    ByteBuf headersFrame = headersFrame(STREAM_ID, headers);
    channelRead(headersFrame);
    Http2Headers responseHeaders = new DefaultHttp2Headers()
        .set(InternalStatus.CODE_KEY.name(), String.valueOf(Code.UNIMPLEMENTED.value()))
        .set(InternalStatus.MESSAGE_KEY.name(), "Expected path but is missing")
        .status("" + 404)
        .set(CONTENT_TYPE_HEADER, "text/plain; charset=utf-8");

    verifyWrite()
        .writeHeaders(
            eq(ctx()),
            eq(STREAM_ID),
            eq(responseHeaders),
            eq(0),
            eq(false),
            any(ChannelPromise.class));
  }

  @Test
  public void headersWithInvalidPathShouldFail() throws Exception {
    manualSetUp();
    Http2Headers headers = new DefaultHttp2Headers()
        .method(HTTP_METHOD)
        .set(CONTENT_TYPE_HEADER, CONTENT_TYPE_GRPC)
        .path(new AsciiString("foo/bar"));
    ByteBuf headersFrame = headersFrame(STREAM_ID, headers);
    channelRead(headersFrame);
    Http2Headers responseHeaders = new DefaultHttp2Headers()
        .set(InternalStatus.CODE_KEY.name(), String.valueOf(Code.UNIMPLEMENTED.value()))
        .set(InternalStatus.MESSAGE_KEY.name(), "Expected path to start with /: foo/bar")
        .status("" + 404)
        .set(CONTENT_TYPE_HEADER, "text/plain; charset=utf-8");

    verifyWrite()
        .writeHeaders(
            eq(ctx()),
            eq(STREAM_ID),
            eq(responseHeaders),
            eq(0),
            eq(false),
            any(ChannelPromise.class));
  }

  @Test
  public void headersSupportExtensionContentType() throws Exception {
    manualSetUp();
    Http2Headers headers = new DefaultHttp2Headers()
        .method(HTTP_METHOD)
        .set(CONTENT_TYPE_HEADER, new AsciiString("application/grpc+json", UTF_8))
        .set(TE_HEADER, TE_TRAILERS)
        .path(new AsciiString("/foo/bar"));
    ByteBuf headersFrame = headersFrame(STREAM_ID, headers);
    channelRead(headersFrame);

    ArgumentCaptor<NettyServerStream> streamCaptor =
        ArgumentCaptor.forClass(NettyServerStream.class);
    ArgumentCaptor<String> methodCaptor = ArgumentCaptor.forClass(String.class);
    verify(transportListener).streamCreated(streamCaptor.capture(), methodCaptor.capture(),
        any(Metadata.class));
    stream = streamCaptor.getValue();
  }

  @Test
  public void headersWithConnectionHeaderShouldFail() throws Exception {
    manualSetUp();
    Http2Headers headers = new DefaultHttp2Headers()
        .method(HTTP_METHOD)
        .set(CONTENT_TYPE_HEADER, CONTENT_TYPE_GRPC)
        .set(AsciiString.of("connection"), CONTENT_TYPE_GRPC)
        .path(new AsciiString("/foo/bar"));
    ByteBuf headersFrame = headersFrame(STREAM_ID, headers);
    channelRead(headersFrame);

    verifyWrite()
        .writeRstStream(
            eq(ctx()),
            eq(STREAM_ID),
            eq(Http2Error.PROTOCOL_ERROR.code()),
            any(ChannelPromise.class));
  }

  @Test
  public void headersWithMultipleHostsShouldFail() throws Exception {
    manualSetUp();
    Http2Headers headers = new DefaultHttp2Headers()
        .method(HTTP_METHOD)
        .set(CONTENT_TYPE_HEADER, CONTENT_TYPE_GRPC)
        .add(AsciiString.of("host"), AsciiString.of("example.com"))
        .add(AsciiString.of("host"), AsciiString.of("bad.com"))
        .path(new AsciiString("/foo/bar"));
    ByteBuf headersFrame = headersFrame(STREAM_ID, headers);
    channelRead(headersFrame);
    Http2Headers responseHeaders = new DefaultHttp2Headers()
        .set(InternalStatus.CODE_KEY.name(), String.valueOf(Code.INTERNAL.value()))
        .set(InternalStatus.MESSAGE_KEY.name(), "Multiple host headers")
        .status("" + 400)
        .set(CONTENT_TYPE_HEADER, "text/plain; charset=utf-8");

    verifyWrite()
        .writeHeaders(
            eq(ctx()),
            eq(STREAM_ID),
            eq(responseHeaders),
            eq(0),
            eq(false),
            any(ChannelPromise.class));
  }

  @Test
  public void headersWithErrAndEndStreamReturnErrorButNotThrowNpe() throws Exception {
    manualSetUp();
    Http2Headers headers = new DefaultHttp2Headers()
        .method(HTTP_METHOD)
        .add(AsciiString.of("host"), AsciiString.of("example.com"))
        .path(new AsciiString("/foo/bar"));
    ByteBuf headersFrame = headersFrame(STREAM_ID, headers);
    channelRead(headersFrame);
    channelRead(emptyGrpcFrame(STREAM_ID, true));

    Http2Headers responseHeaders = new DefaultHttp2Headers()
        .set(InternalStatus.CODE_KEY.name(), String.valueOf(Code.INTERNAL.value()))
        .set(InternalStatus.MESSAGE_KEY.name(), "Content-Type is missing from the request")
        .status("" + 415)
        .set(CONTENT_TYPE_HEADER, "text/plain; charset=utf-8");

    verifyWrite()
        .writeHeaders(
            eq(ctx()),
            eq(STREAM_ID),
            eq(responseHeaders),
            eq(0),
            eq(false),
            any(ChannelPromise.class));

  }

  @Test
  public void headersWithAuthorityAndHostUsesAuthority() throws Exception {
    manualSetUp();
    Http2Headers headers = new DefaultHttp2Headers()
        .method(HTTP_METHOD)
        .authority("example.com")
        .set(CONTENT_TYPE_HEADER, CONTENT_TYPE_GRPC)
        .add(AsciiString.of("host"), AsciiString.of("bad.com"))
        .path(new AsciiString("/foo/bar"));
    ByteBuf headersFrame = headersFrame(STREAM_ID, headers);
    channelRead(headersFrame);
    Metadata.Key<String> hostKey = Metadata.Key.of("host", Metadata.ASCII_STRING_MARSHALLER);

    ArgumentCaptor<NettyServerStream> streamCaptor =
        ArgumentCaptor.forClass(NettyServerStream.class);
    ArgumentCaptor<Metadata> metadataCaptor = ArgumentCaptor.forClass(Metadata.class);
    verify(transportListener).streamCreated(streamCaptor.capture(), eq("foo/bar"),
        metadataCaptor.capture());
    Truth.assertThat(streamCaptor.getValue().getAuthority()).isEqualTo("example.com");
    Truth.assertThat(metadataCaptor.getValue().get(hostKey)).isNull();
  }

  @Test
  public void headersWithOnlyHostBecomesAuthority() throws Exception {
    manualSetUp();
    // No authority header
    Http2Headers headers = new DefaultHttp2Headers()
        .method(HTTP_METHOD)
        .set(CONTENT_TYPE_HEADER, CONTENT_TYPE_GRPC)
        .add(AsciiString.of("host"), AsciiString.of("example.com"))
        .path(new AsciiString("/foo/bar"));
    ByteBuf headersFrame = headersFrame(STREAM_ID, headers);
    channelRead(headersFrame);
    Metadata.Key<String> hostKey = Metadata.Key.of("host", Metadata.ASCII_STRING_MARSHALLER);

    ArgumentCaptor<NettyServerStream> streamCaptor =
        ArgumentCaptor.forClass(NettyServerStream.class);
    ArgumentCaptor<Metadata> metadataCaptor = ArgumentCaptor.forClass(Metadata.class);
    verify(transportListener).streamCreated(streamCaptor.capture(), eq("foo/bar"),
        metadataCaptor.capture());
    Truth.assertThat(streamCaptor.getValue().getAuthority()).isEqualTo("example.com");
    Truth.assertThat(metadataCaptor.getValue().get(hostKey)).isNull();
  }

  @Test
  public void keepAliveManagerOnDataReceived_headersRead() throws Exception {
    manualSetUp();
    ByteBuf headersFrame = headersFrame(STREAM_ID, new DefaultHttp2Headers());
    channelRead(headersFrame);

    verify(spyKeepAliveManager).onDataReceived();
    verify(spyKeepAliveManager, never()).onTransportTermination();
  }

  @Test
  public void keepAliveManagerOnDataReceived_dataRead() throws Exception {
    manualSetUp();
    createStream();
    verify(spyKeepAliveManager).onDataReceived(); // received headers

    channelRead(grpcDataFrame(STREAM_ID, false, contentAsArray()));

    verify(spyKeepAliveManager, times(2)).onDataReceived();

    channelRead(grpcDataFrame(STREAM_ID, false, contentAsArray()));

    verify(spyKeepAliveManager, times(3)).onDataReceived();
    verify(spyKeepAliveManager, never()).onTransportTermination();
  }

  @Test
  public void keepAliveManagerOnDataReceived_rstStreamRead() throws Exception {
    manualSetUp();
    createStream();
    verify(spyKeepAliveManager).onDataReceived(); // received headers

    channelRead(rstStreamFrame(STREAM_ID, (int) Http2Error.CANCEL.code()));

    verify(spyKeepAliveManager, times(2)).onDataReceived();
    verify(spyKeepAliveManager, never()).onTransportTermination();
  }

  @Test
  public void keepAliveManagerOnDataReceived_pingRead() throws Exception {
    manualSetUp();
    channelRead(pingFrame(false /* isAck */, 1234L));

    verify(spyKeepAliveManager).onDataReceived();
    verify(spyKeepAliveManager, never()).onTransportTermination();
  }

  @Test
  public void keepAliveManagerOnDataReceived_pingActRead() throws Exception {
    manualSetUp();
    channelRead(pingFrame(true /* isAck */, 1234L));

    verify(spyKeepAliveManager).onDataReceived();
    verify(spyKeepAliveManager, never()).onTransportTermination();
  }

  @Test
  public void keepAliveManagerOnTransportTermination() throws Exception {
    manualSetUp();
    handler().channelInactive(handler().ctx());

    verify(spyKeepAliveManager).onTransportTermination();
  }

  @Test
  public void keepAliveManager_pingSent() throws Exception {
    keepAliveTimeInNanos = TimeUnit.MILLISECONDS.toNanos(10L);
    keepAliveTimeoutInNanos = TimeUnit.MINUTES.toNanos(30L);
    manualSetUp();

    assertEquals(0, transportTracer.getStats().keepAlivesSent);
    fakeClock().forwardNanos(keepAliveTimeInNanos);
    assertEquals(1, transportTracer.getStats().keepAlivesSent);

    verifyWrite().writePing(eq(ctx()), eq(false), eq(0xDEADL), any(ChannelPromise.class));

    spyKeepAliveManager.onDataReceived();
    fakeClock().forwardTime(10L, TimeUnit.MILLISECONDS);

    verifyWrite(times(2))
        .writePing(eq(ctx()), eq(false), eq(0xDEADL), any(ChannelPromise.class));
    assertTrue(channel().isOpen());
  }

  @Test
  public void keepAliveManager_pingTimeout() throws Exception {
    keepAliveTimeInNanos = 123L /* nanoseconds */;
    keepAliveTimeoutInNanos = 456L /* nanoseconds */;
    manualSetUp();

    fakeClock().forwardNanos(keepAliveTimeInNanos);

    assertTrue(channel().isOpen());

    fakeClock().forwardNanos(keepAliveTimeoutInNanos);

    assertFalse(channel().isOpen());
  }

  @Test
  public void keepAliveEnforcer_enforcesPings() throws Exception {
    permitKeepAliveWithoutCalls = false;
    permitKeepAliveTimeInNanos = TimeUnit.HOURS.toNanos(1);
    manualSetUp();

    for (int i = 0; i < KeepAliveEnforcer.MAX_PING_STRIKES + 1; i++) {
      channelRead(pingFrame(false /* isAck */, 1L));
    }
    verifyWrite().writeGoAway(eq(ctx()), eq(0), eq(Http2Error.ENHANCE_YOUR_CALM.code()),
        any(ByteBuf.class), any(ChannelPromise.class));
    assertFalse(channel().isActive());
  }

  @Test
  public void keepAliveEnforcer_sendingDataResetsCounters() throws Exception {
    permitKeepAliveWithoutCalls = false;
    permitKeepAliveTimeInNanos = TimeUnit.HOURS.toNanos(1);
    manualSetUp();

    createStream();
    Http2Headers headers = Utils.convertServerHeaders(new Metadata());
    ChannelFuture future = enqueue(
        SendResponseHeadersCommand.createHeaders(stream.transportState(), headers));
    future.get();
    for (int i = 0; i < 10; i++) {
      future = enqueue(
          new SendGrpcFrameCommand(stream.transportState(), content(), false));
      future.get();
      channel().releaseOutbound();
      channelRead(pingFrame(false /* isAck */, 1L));
    }
    verifyWrite(never()).writeGoAway(eq(ctx()), eq(STREAM_ID),
        eq(Http2Error.ENHANCE_YOUR_CALM.code()), any(ByteBuf.class), any(ChannelPromise.class));
  }

  @Test
  public void keepAliveEnforcer_initialIdle() throws Exception {
    permitKeepAliveWithoutCalls = false;
    permitKeepAliveTimeInNanos = 0;
    manualSetUp();

    for (int i = 0; i < KeepAliveEnforcer.MAX_PING_STRIKES + 1; i++) {
      channelRead(pingFrame(false /* isAck */, 1L));
    }
    verifyWrite().writeGoAway(eq(ctx()), eq(0),
        eq(Http2Error.ENHANCE_YOUR_CALM.code()), any(ByteBuf.class), any(ChannelPromise.class));
    assertFalse(channel().isActive());
  }

  @Test
  public void keepAliveEnforcer_noticesActive() throws Exception {
    permitKeepAliveWithoutCalls = false;
    permitKeepAliveTimeInNanos = 0;
    manualSetUp();

    createStream();
    for (int i = 0; i < 10; i++) {
      channelRead(pingFrame(false /* isAck */, 1L));
    }
    verifyWrite(never()).writeGoAway(eq(ctx()), eq(STREAM_ID),
        eq(Http2Error.ENHANCE_YOUR_CALM.code()), any(ByteBuf.class), any(ChannelPromise.class));
  }

  @Test
  public void keepAliveEnforcer_noticesInactive() throws Exception {
    permitKeepAliveWithoutCalls = false;
    permitKeepAliveTimeInNanos = 0;
    manualSetUp();

    createStream();
    channelRead(rstStreamFrame(STREAM_ID, (int) Http2Error.CANCEL.code()));
    for (int i = 0; i < KeepAliveEnforcer.MAX_PING_STRIKES + 1; i++) {
      channelRead(pingFrame(false /* isAck */, 1L));
    }
    verifyWrite().writeGoAway(eq(ctx()), eq(STREAM_ID),
        eq(Http2Error.ENHANCE_YOUR_CALM.code()), any(ByteBuf.class), any(ChannelPromise.class));
    assertFalse(channel().isActive());
  }

  @Test
  public void noGoAwaySentBeforeMaxConnectionIdleReached() throws Exception {
    maxConnectionIdleInNanos = TimeUnit.MINUTES.toNanos(30L);
    manualSetUp();

    fakeClock().forwardTime(20, TimeUnit.MINUTES);

    // GO_AWAY not sent yet
    verifyWrite(never())
        .writeGoAway(
            any(ChannelHandlerContext.class),
            anyInt(),
            anyLong(),
            any(ByteBuf.class),
            any(ChannelPromise.class));
    assertTrue(channel().isOpen());
  }

  @Test
  public void maxConnectionIdle_goAwaySent_pingAck() throws Exception {
    maxConnectionIdleInNanos = TimeUnit.MILLISECONDS.toNanos(10L);
    manualSetUp();
    assertTrue(channel().isOpen());

    fakeClock().forwardNanos(maxConnectionIdleInNanos);

    // first GO_AWAY sent
    verifyWrite().writeGoAway(
        eq(ctx()), eq(Integer.MAX_VALUE), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    // ping sent
    verifyWrite().writePing(
        eq(ctx()), eq(false), eq(0x97ACEF001L), any(ChannelPromise.class));
    verifyWrite(never()).writeGoAway(
        eq(ctx()), eq(0), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));

    channelRead(pingFrame(true /* isAck */, 0xDEADL)); // irrelevant ping Ack
    verifyWrite(never()).writeGoAway(
        eq(ctx()), eq(0), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    assertTrue(channel().isOpen());

    channelRead(pingFrame(true /* isAck */, 0x97ACEF001L));

    // second GO_AWAY sent
    verifyWrite().writeGoAway(
        eq(ctx()), eq(0), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    // channel closed
    assertFalse(channel().isOpen());
  }

  @Test
  public void maxConnectionIdle_goAwaySent_pingTimeout() throws Exception {
    maxConnectionIdleInNanos = TimeUnit.MILLISECONDS.toNanos(10L);
    manualSetUp();
    assertTrue(channel().isOpen());

    fakeClock().forwardNanos(maxConnectionIdleInNanos);

    // first GO_AWAY sent
    verifyWrite().writeGoAway(
        eq(ctx()), eq(Integer.MAX_VALUE), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    // ping sent
    verifyWrite().writePing(
        eq(ctx()), eq(false), eq(0x97ACEF001L), any(ChannelPromise.class));
    verifyWrite(never()).writeGoAway(
        eq(ctx()), eq(0), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    assertTrue(channel().isOpen());

    fakeClock().forwardTime(10, TimeUnit.SECONDS);

    // second GO_AWAY sent
    verifyWrite().writeGoAway(
        eq(ctx()), eq(0), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    // channel closed
    assertFalse(channel().isOpen());
  }

  @Test
  public void maxConnectionIdle_activeThenRst_pingAck() throws Exception {
    maxConnectionIdleInNanos = TimeUnit.MILLISECONDS.toNanos(10L);
    manualSetUp();
    createStream();

    fakeClock().forwardNanos(maxConnectionIdleInNanos);

    // GO_AWAY not sent when active
    verifyWrite(never())
        .writeGoAway(
            any(ChannelHandlerContext.class),
            anyInt(),
            anyLong(),
            any(ByteBuf.class),
            any(ChannelPromise.class));
    assertTrue(channel().isOpen());

    channelRead(rstStreamFrame(STREAM_ID, (int) Http2Error.CANCEL.code()));

    fakeClock().forwardNanos(maxConnectionIdleInNanos);

    // first GO_AWAY sent
    verifyWrite().writeGoAway(
        eq(ctx()), eq(Integer.MAX_VALUE), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    // ping sent
    verifyWrite().writePing(
        eq(ctx()), eq(false), eq(0x97ACEF001L), any(ChannelPromise.class));
    verifyWrite(never()).writeGoAway(
        eq(ctx()), eq(STREAM_ID), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    assertTrue(channel().isOpen());

    fakeClock().forwardTime(10, TimeUnit.SECONDS);

    // second GO_AWAY sent
    verifyWrite().writeGoAway(
        eq(ctx()), eq(STREAM_ID), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    // channel closed
    assertFalse(channel().isOpen());
  }

  @Test
  public void maxConnectionIdle_activeThenRst_pingTimeoutk() throws Exception {
    maxConnectionIdleInNanos = TimeUnit.MILLISECONDS.toNanos(10L);
    manualSetUp();
    createStream();

    fakeClock().forwardNanos(maxConnectionIdleInNanos);

    // GO_AWAY not sent when active
    verifyWrite(never())
        .writeGoAway(
            any(ChannelHandlerContext.class),
            anyInt(),
            anyLong(),
            any(ByteBuf.class),
            any(ChannelPromise.class));
    assertTrue(channel().isOpen());

    channelRead(rstStreamFrame(STREAM_ID, (int) Http2Error.CANCEL.code()));

    fakeClock().forwardNanos(maxConnectionIdleInNanos);

    // first GO_AWAY sent
    verifyWrite().writeGoAway(
        eq(ctx()), eq(Integer.MAX_VALUE), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    // ping sent
    verifyWrite().writePing(
        eq(ctx()), eq(false), eq(0x97ACEF001L), any(ChannelPromise.class));
    verifyWrite(never()).writeGoAway(
        eq(ctx()), eq(STREAM_ID), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    assertTrue(channel().isOpen());

    channelRead(pingFrame(true /* isAck */, 0x97ACEF001L));

    // second GO_AWAY sent
    verifyWrite().writeGoAway(
        eq(ctx()), eq(STREAM_ID), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    // channel closed
    assertFalse(channel().isOpen());
  }

  @Test
  public void noGoAwaySentBeforeMaxConnectionAgeReached() throws Exception {
    maxConnectionAgeInNanos = TimeUnit.MINUTES.toNanos(30L);
    manualSetUp();

    fakeClock().forwardTime(20, TimeUnit.MINUTES);

    // GO_AWAY not sent yet
    verifyWrite(never())
        .writeGoAway(
            any(ChannelHandlerContext.class),
            anyInt(),
            anyLong(),
            any(ByteBuf.class),
            any(ChannelPromise.class));
    assertTrue(channel().isOpen());
  }

  @Test
  public void maxConnectionAge_goAwaySent_pingAck() throws Exception {

    maxConnectionAgeInNanos = TimeUnit.MILLISECONDS.toNanos(10L);
    manualSetUp();
    assertTrue(channel().isOpen());

    fakeClock().forwardNanos(maxConnectionAgeInNanos);

    // first GO_AWAY sent
    verifyWrite().writeGoAway(
        eq(ctx()), eq(Integer.MAX_VALUE), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    // ping sent
    verifyWrite().writePing(
        eq(ctx()), eq(false), eq(0x97ACEF001L), any(ChannelPromise.class));
    verifyWrite(never()).writeGoAway(
        eq(ctx()), eq(0), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));

    channelRead(pingFrame(true /* isAck */, 0xDEADL)); // irrelevant ping Ack
    verifyWrite(never()).writeGoAway(
        eq(ctx()), eq(0), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    assertTrue(channel().isOpen());

    channelRead(pingFrame(true /* isAck */, 0x97ACEF001L));

    // second GO_AWAY sent
    verifyWrite().writeGoAway(
        eq(ctx()), eq(0), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    // channel closed
    assertFalse(channel().isOpen());
  }

  @Test
  public void maxConnectionAge_goAwaySent_pingTimeout() throws Exception {

    maxConnectionAgeInNanos = TimeUnit.MILLISECONDS.toNanos(10L);
    manualSetUp();
    assertTrue(channel().isOpen());

    fakeClock().forwardNanos(maxConnectionAgeInNanos);

    // first GO_AWAY sent
    verifyWrite().writeGoAway(
        eq(ctx()), eq(Integer.MAX_VALUE), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    // ping sent
    verifyWrite().writePing(
        eq(ctx()), eq(false), eq(0x97ACEF001L), any(ChannelPromise.class));
    verifyWrite(never()).writeGoAway(
        eq(ctx()), eq(0), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    assertTrue(channel().isOpen());

    fakeClock().forwardTime(10, TimeUnit.SECONDS);

    // second GO_AWAY sent
    verifyWrite().writeGoAway(
        eq(ctx()), eq(0), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    // channel closed
    assertFalse(channel().isOpen());
  }

  @Test
  public void maxConnectionAgeGrace_channelStillOpenDuringGracePeriod() throws Exception {
    maxConnectionAgeInNanos = TimeUnit.MILLISECONDS.toNanos(10L);
    maxConnectionAgeGraceInNanos = TimeUnit.MINUTES.toNanos(30L);
    manualSetUp();
    createStream();

    fakeClock().forwardNanos(maxConnectionAgeInNanos);

    // first GO_AWAY sent
    verifyWrite().writeGoAway(
        eq(ctx()), eq(Integer.MAX_VALUE), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    // ping sent
    verifyWrite().writePing(
        eq(ctx()), eq(false), eq(0x97ACEF001L), any(ChannelPromise.class));
    verifyWrite(never()).writeGoAway(
        eq(ctx()), eq(STREAM_ID), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));

    fakeClock().forwardTime(20, TimeUnit.MINUTES);

    // second GO_AWAY sent
    verifyWrite().writeGoAway(
        eq(ctx()), eq(STREAM_ID), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    // channel not closed yet
    assertTrue(channel().isOpen());
  }

  @Test
  public void maxConnectionAgeGrace_channelClosedAfterGracePeriod_withPingTimeout()
      throws Exception {
    maxConnectionAgeInNanos = TimeUnit.MILLISECONDS.toNanos(10L);
    maxConnectionAgeGraceInNanos = TimeUnit.MINUTES.toNanos(30L); // greater than ping timeout
    manualSetUp();
    createStream();

    fakeClock().forwardNanos(maxConnectionAgeInNanos);

    // first GO_AWAY sent
    verifyWrite().writeGoAway(
        eq(ctx()), eq(Integer.MAX_VALUE), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    // ping sent
    verifyWrite().writePing(
        eq(ctx()), eq(false), eq(0x97ACEF001L), any(ChannelPromise.class));
    verifyWrite(never()).writeGoAway(
        eq(ctx()), eq(STREAM_ID), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));

    fakeClock().forwardNanos(TimeUnit.SECONDS.toNanos(10));

    // second GO_AWAY sent
    verifyWrite().writeGoAway(
        eq(ctx()), eq(STREAM_ID), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));

    fakeClock().forwardNanos(maxConnectionAgeGraceInNanos - 2);

    assertTrue(channel().isOpen());

    fakeClock().forwardTime(2, TimeUnit.MILLISECONDS);

    // channel closed
    assertFalse(channel().isOpen());
  }

  @Test
  public void maxConnectionAgeGrace_channelClosedAfterGracePeriod_withPingAck()
      throws Exception {
    maxConnectionAgeInNanos = TimeUnit.MILLISECONDS.toNanos(10L);
    maxConnectionAgeGraceInNanos = TimeUnit.MINUTES.toNanos(30L); // greater than ping timeout
    manualSetUp();
    createStream();

    fakeClock().forwardNanos(maxConnectionAgeInNanos);

    // first GO_AWAY sent
    verifyWrite().writeGoAway(
        eq(ctx()), eq(Integer.MAX_VALUE), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));
    // ping sent
    verifyWrite().writePing(
        eq(ctx()), eq(false), eq(0x97ACEF001L), any(ChannelPromise.class));
    verifyWrite(never()).writeGoAway(
        eq(ctx()), eq(STREAM_ID), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));

    long pingRoundTripMillis = 100;  // less than ping timeout
    fakeClock().forwardTime(pingRoundTripMillis, TimeUnit.MILLISECONDS);
    channelRead(pingFrame(true /* isAck */, 0x97ACEF001L));

    // second GO_AWAY sent
    verifyWrite().writeGoAway(
        eq(ctx()), eq(STREAM_ID), eq(Http2Error.NO_ERROR.code()), any(ByteBuf.class),
        any(ChannelPromise.class));

    fakeClock().forwardNanos(maxConnectionAgeGraceInNanos - TimeUnit.MILLISECONDS.toNanos(2));

    assertTrue(channel().isOpen());

    fakeClock().forwardTime(2, TimeUnit.MILLISECONDS);

    // channel closed
    assertFalse(channel().isOpen());
  }

  @Test
  public void maxRstCount_withinLimit_succeeds() throws Exception {
    maxRstCount = 10;
    maxRstPeriodNanos = TimeUnit.MILLISECONDS.toNanos(100);
    manualSetUp();
    rapidReset(maxRstCount);

    assertTrue(channel().isOpen());
  }

  @Test
  public void maxRstCount_exceedsLimit_fails() throws Exception {
    maxRstCount = 10;
    maxRstPeriodNanos = TimeUnit.MILLISECONDS.toNanos(100);
    manualSetUp();
    assertThrows(ClosedChannelException.class, () -> rapidReset(maxRstCount + 1));

    assertFalse(channel().isOpen());
  }

  private void rapidReset(int burstSize) throws Exception {
    Http2Headers headers = new DefaultHttp2Headers()
        .method(HTTP_METHOD)
        .set(CONTENT_TYPE_HEADER, new AsciiString("application/grpc", UTF_8))
        .set(TE_HEADER, TE_TRAILERS)
        .path(new AsciiString("/foo/bar"));
    int streamId = 1;
    long rpcTimeNanos = maxRstPeriodNanos / 2 / burstSize;
    for (int period = 0; period < 3; period++) {
      for (int i = 0; i < burstSize; i++) {
        channelRead(headersFrame(streamId, headers));
        channelRead(rstStreamFrame(streamId, (int) Http2Error.CANCEL.code()));
        streamId += 2;
        fakeClock().forwardNanos(rpcTimeNanos);
      }
      while (channel().readOutbound() != null) {}
      fakeClock().forwardNanos(maxRstPeriodNanos - rpcTimeNanos * burstSize + 1);
    }
  }

  private void createStream() throws Exception {
    Http2Headers headers = new DefaultHttp2Headers()
        .method(HTTP_METHOD)
        .set(CONTENT_TYPE_HEADER, CONTENT_TYPE_GRPC)
        .set(TE_HEADER, TE_TRAILERS)
        .path(new AsciiString("/foo/bar"));
    ByteBuf headersFrame = headersFrame(STREAM_ID, headers);
    channelRead(headersFrame);

    ArgumentCaptor<NettyServerStream> streamCaptor =
        ArgumentCaptor.forClass(NettyServerStream.class);
    ArgumentCaptor<String> methodCaptor = ArgumentCaptor.forClass(String.class);
    verify(transportListener).streamCreated(streamCaptor.capture(), methodCaptor.capture(),
        any(Metadata.class));
    stream = streamCaptor.getValue();
  }

  private ByteBuf emptyGrpcFrame(int streamId, boolean endStream) throws Exception {
    ByteBuf buf = NettyTestUtil.messageFrame("");
    return dataFrame(streamId, endStream, buf);
  }

  @Override
  protected NettyServerHandler newHandler() {
    return NettyServerHandler.newHandler(
        /* channelUnused= */ channel().newPromise(),
        frameReader(),
        frameWriter(),
        transportListener,
        Arrays.asList(streamTracerFactory),
        transportTracer,
        maxConcurrentStreams,
        autoFlowControl,
        flowControlWindow,
        maxHeaderListSize,
        softLimitHeaderListSize,
        DEFAULT_MAX_MESSAGE_SIZE,
        keepAliveTimeInNanos,
        keepAliveTimeoutInNanos,
        maxConnectionIdleInNanos,
        maxConnectionAgeInNanos,
        maxConnectionAgeGraceInNanos,
        permitKeepAliveWithoutCalls,
        permitKeepAliveTimeInNanos,
        maxRstCount,
        maxRstPeriodNanos,
        Attributes.EMPTY,
        fakeClock().getTicker());
  }

  @Override
  protected WriteQueue initWriteQueue() {
    return handler().getWriteQueue();
  }

  @Override
  protected void makeStream() throws Exception {
    createStream();
  }

  @Override
  protected AbstractStream stream() throws Exception {
    if (stream == null) {
      makeStream();
    }

    return stream;
  }

}
