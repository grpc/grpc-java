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

import static com.google.common.base.Charsets.UTF_8;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_WINDOW_SIZE;
import static org.junit.Assert.assertEquals;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.grpc.InternalChannelz.TransportStats;
import io.grpc.internal.AbstractStream;
import io.grpc.internal.FakeClock;
import io.grpc.internal.MessageFramer;
import io.grpc.internal.StatsTraceContext;
import io.grpc.internal.TransportTracer;
import io.grpc.internal.WritableBuffer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.DefaultHttp2FrameWriter;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameReader;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersDecoder;
import io.netty.handler.codec.http2.Http2LocalFlowController;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.mockito.verification.VerificationMode;

/**
 * Base class for Netty handler unit tests.
 */
@RunWith(JUnit4.class)
public abstract class NettyHandlerTestBase<T extends Http2ConnectionHandler> {

  protected static final int STREAM_ID = 3;
  private ByteBuf content;

  private EmbeddedChannel channel;

  private ChannelHandlerContext ctx;

  private Http2FrameWriter frameWriter;

  private Http2FrameReader frameReader;

  private T handler;

  private WriteQueue writeQueue;

  /**
   * Does additional setup jobs. Call it manually when necessary.
   */
  protected void manualSetUp() throws Exception {}

  protected final TransportTracer transportTracer = new TransportTracer();
  protected int flowControlWindow = DEFAULT_WINDOW_SIZE;
  protected boolean autoFlowControl = false;

  private final FakeClock fakeClock = new FakeClock();

  FakeClock fakeClock() {
    return fakeClock;
  }

  /**
   * Must be called by subclasses to initialize the handler and channel.
   */
  protected final void initChannel(Http2HeadersDecoder headersDecoder) throws Exception {
    content = Unpooled.copiedBuffer("hello world", UTF_8);
    frameWriter = mock(Http2FrameWriter.class, delegatesTo(new DefaultHttp2FrameWriter()));
    frameReader = new DefaultHttp2FrameReader(headersDecoder);

    channel = new FakeClockSupportedChanel();
    handler = newHandler();
    channel.pipeline().addLast(handler);
    ctx = channel.pipeline().context(handler);

    writeQueue = initWriteQueue();
  }

  private final class FakeClockSupportedChanel extends EmbeddedChannel {
    EventLoop eventLoop;

    FakeClockSupportedChanel(ChannelHandler... handlers) {
      super(handlers);
    }

    @Override
    public EventLoop eventLoop() {
      if (eventLoop == null) {
        createEventLoop();
      }
      return eventLoop;
    }

    void createEventLoop() {
      EventLoop realEventLoop = super.eventLoop();
      if (realEventLoop == null) {
        return;
      }
      eventLoop = mock(EventLoop.class, delegatesTo(realEventLoop));
      doAnswer(
          new Answer<ScheduledFuture<Void>>() {
            @Override
            public ScheduledFuture<Void> answer(InvocationOnMock invocation) throws Throwable {
              Runnable command = (Runnable) invocation.getArguments()[0];
              Long delay = (Long) invocation.getArguments()[1];
              TimeUnit timeUnit = (TimeUnit) invocation.getArguments()[2];
              return new FakeClockScheduledNettyFuture(eventLoop, command, delay, timeUnit);
            }
          }).when(eventLoop).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }
  }

  private final class FakeClockScheduledNettyFuture extends DefaultPromise<Void>
      implements ScheduledFuture<Void> {
    final java.util.concurrent.ScheduledFuture<?> future;

    FakeClockScheduledNettyFuture(
        EventLoop eventLoop, final Runnable command, long delay, TimeUnit timeUnit) {
      super(eventLoop);
      Runnable wrap = new Runnable() {
        @Override
        public void run() {
          try {
            command.run();
          } catch (Throwable t) {
            setFailure(t);
            return;
          }
          if (!isDone()) {
            Promise<Void> unused = setSuccess(null);
          }
          // else: The command itself, such as a shutdown task, might have cancelled all the
          // scheduled tasks already.
        }
      };
      future = fakeClock.getScheduledExecutorService().schedule(wrap, delay, timeUnit);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      if (future.cancel(mayInterruptIfRunning)) {
        return super.cancel(mayInterruptIfRunning);
      }
      return false;
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return Math.max(future.getDelay(unit), 1L); // never return zero or negative delay.
    }

    @Override
    public int compareTo(Delayed o) {
      return future.compareTo(o);
    }
  }

  protected final WriteQueue writeQueue() {
    return writeQueue;
  }

  protected final T handler() {
    return handler;
  }

  protected final EmbeddedChannel channel() {
    return channel;
  }

  protected final ChannelHandlerContext ctx() {
    return ctx;
  }

  protected final Http2FrameWriter frameWriter() {
    return frameWriter;
  }

  protected final Http2FrameReader frameReader() {
    return frameReader;
  }

  protected final ByteBuf content() {
    return content;
  }

  protected final byte[] contentAsArray() {
    return ByteBufUtil.getBytes(content());
  }

  protected final Http2FrameWriter verifyWrite() {
    return verify(frameWriter);
  }

  protected final Http2FrameWriter verifyWrite(VerificationMode verificationMode) {
    return verify(frameWriter, verificationMode);
  }

  protected final void channelRead(Object obj) throws Exception {
    channel.writeInbound(obj);
  }

  protected ByteBuf grpcDataFrame(int streamId, boolean endStream, byte[] content) {
    final ByteBuf compressionFrame = Unpooled.buffer(content.length);
    MessageFramer framer = new MessageFramer(
        new MessageFramer.Sink() {
          @Override
          public void deliverFrame(
              WritableBuffer frame, boolean endOfStream, boolean flush, int numMessages) {
            if (frame != null) {
              ByteBuf bytebuf = ((NettyWritableBuffer) frame).bytebuf();
              compressionFrame.writeBytes(bytebuf);
            }
          }
        },
        new NettyWritableBufferAllocator(ByteBufAllocator.DEFAULT),
        StatsTraceContext.NOOP);
    framer.writePayload(new ByteArrayInputStream(content));
    framer.flush();
    ChannelHandlerContext ctx = newMockContext();
    new DefaultHttp2FrameWriter().writeData(ctx, streamId, compressionFrame, 0, endStream,
        newPromise());
    return captureWrite(ctx);
  }

  protected final ByteBuf dataFrame(int streamId, boolean endStream, ByteBuf content) {
    // Need to retain the content since the frameWriter releases it.
    content.retain();

    ChannelHandlerContext ctx = newMockContext();
    new DefaultHttp2FrameWriter().writeData(ctx, streamId, content, 0, endStream, newPromise());
    return captureWrite(ctx);
  }

  protected final ByteBuf pingFrame(boolean ack, long payload) {
    ChannelHandlerContext ctx = newMockContext();
    new DefaultHttp2FrameWriter().writePing(ctx, ack, payload, newPromise());
    return captureWrite(ctx);
  }

  protected final ByteBuf headersFrame(int streamId, Http2Headers headers) {
    ChannelHandlerContext ctx = newMockContext();
    new DefaultHttp2FrameWriter().writeHeaders(ctx, streamId, headers, 0, false, newPromise());
    return captureWrite(ctx);
  }

  protected final ByteBuf goAwayFrame(int lastStreamId) {
    return goAwayFrame(lastStreamId, 0, Unpooled.EMPTY_BUFFER);
  }

  protected final ByteBuf goAwayFrame(int lastStreamId, int errorCode, ByteBuf data) {
    ChannelHandlerContext ctx = newMockContext();
    new DefaultHttp2FrameWriter().writeGoAway(ctx, lastStreamId, errorCode, data, newPromise());
    return captureWrite(ctx);
  }

  protected final ByteBuf rstStreamFrame(int streamId, int errorCode) {
    ChannelHandlerContext ctx = newMockContext();
    new DefaultHttp2FrameWriter().writeRstStream(ctx, streamId, errorCode, newPromise());
    return captureWrite(ctx);
  }

  protected final ByteBuf serializeSettings(Http2Settings settings) {
    ChannelHandlerContext ctx = newMockContext();
    new DefaultHttp2FrameWriter().writeSettings(ctx, settings, newPromise());
    return captureWrite(ctx);
  }

  protected final ByteBuf windowUpdate(int streamId, int delta) {
    ChannelHandlerContext ctx = newMockContext();
    new DefaultHttp2FrameWriter().writeWindowUpdate(ctx, streamId, delta, newPromise());
    return captureWrite(ctx);
  }

  protected final ChannelPromise newPromise() {
    return channel.newPromise();
  }

  protected final Http2Connection connection() {
    return handler().connection();
  }

  protected abstract AbstractStream stream() throws Exception;

  @CanIgnoreReturnValue
  protected final ChannelFuture enqueue(WriteQueue.QueuedCommand command) {
    ChannelFuture future = writeQueue.enqueue(command, true);
    channel.runPendingTasks();
    return future;
  }

  protected final ChannelHandlerContext newMockContext() {
    ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
    when(ctx.alloc()).thenReturn(UnpooledByteBufAllocator.DEFAULT);
    EventLoop eventLoop = mock(EventLoop.class);
    when(ctx.executor()).thenReturn(eventLoop);
    when(ctx.channel()).thenReturn(channel);
    return ctx;
  }

  protected final ByteBuf captureWrite(ChannelHandlerContext ctx) {
    ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
    verify(ctx, atLeastOnce()).write(captor.capture(), any(ChannelPromise.class));
    CompositeByteBuf composite = Unpooled.compositeBuffer();
    for (ByteBuf buf : captor.getAllValues()) {
      composite.addComponent(buf);
      composite.writerIndex(composite.writerIndex() + buf.readableBytes());
    }
    return composite;
  }

  protected abstract T newHandler() throws Http2Exception;

  protected abstract WriteQueue initWriteQueue();

  protected abstract void makeStream() throws Exception;

  @Test
  public void dataPingSentOnHeaderRecieved() throws Exception {
    manualSetUp();
    makeStream();
    AbstractNettyHandler handler = (AbstractNettyHandler) handler();
    handler.setAutoTuneFlowControl(true);

    channelRead(dataFrame(3, false, content()));

    assertEquals(1, handler.flowControlPing().getPingCount());
  }

  @Test
  public void dataPingAckIsRecognized() throws Exception {
    manualSetUp();
    makeStream();
    AbstractNettyHandler handler = (AbstractNettyHandler) handler();
    handler.setAutoTuneFlowControl(true);

    channelRead(dataFrame(3, false, content()));
    long pingData = handler.flowControlPing().payload();
    channelRead(pingFrame(true, pingData));

    assertEquals(1, handler.flowControlPing().getPingCount());
    assertEquals(1, handler.flowControlPing().getPingReturn());
  }

  @Test
  public void dataSizeSincePingAccumulates() throws Exception {
    manualSetUp();
    makeStream();
    AbstractNettyHandler handler = (AbstractNettyHandler) handler();
    handler.setAutoTuneFlowControl(true);
    long frameData = 123456;
    ByteBuf buff = ctx().alloc().buffer(16);
    buff.writeLong(frameData);
    int length = buff.readableBytes();

    channelRead(dataFrame(3, false, buff.copy()));
    channelRead(dataFrame(3, false, buff.copy()));
    channelRead(dataFrame(3, false, buff.copy()));

    assertEquals(length * 3, handler.flowControlPing().getDataSincePing());
  }

  @Test
  public void windowUpdateMatchesTarget() throws Exception {
    manualSetUp();
    Http2Stream connectionStream = connection().connectionStream();
    Http2LocalFlowController localFlowController = connection().local().flowController();
    makeStream();
    AbstractNettyHandler handler = (AbstractNettyHandler) handler();
    handler.setAutoTuneFlowControl(true);

    byte[] data = initXkbBuffer(1);
    int wireSize = data.length + 5; // 5 is the size of the header
    ByteBuf frame = grpcDataFrame(3, false, data);
    channelRead(frame);
    int accumulator = wireSize;
    // 40 is arbitrary, any number large enough to trigger a window update would work
    for (int i = 0; i < 40; i++) {
      channelRead(grpcDataFrame(3, false, data));
      accumulator += wireSize;
    }
    long pingData = handler.flowControlPing().payload();
    channelRead(pingFrame(true, pingData));

    assertEquals(accumulator, handler.flowControlPing().getDataSincePing());
    assertEquals(2 * accumulator, localFlowController.initialWindowSize(connectionStream));
  }

  @Test
  public void windowShouldNotExceedMaxWindowSize() throws Exception {
    manualSetUp();
    makeStream();
    AbstractNettyHandler handler = (AbstractNettyHandler) handler();
    handler.setAutoTuneFlowControl(true);
    Http2Stream connectionStream = connection().connectionStream();
    Http2LocalFlowController localFlowController = connection().local().flowController();
    int maxWindow = handler.flowControlPing().maxWindow();
    fakeClock.forwardTime(10, TimeUnit.SECONDS);

    handler.flowControlPing().setDataSizeAndSincePing(maxWindow);
    fakeClock.forwardTime(1, TimeUnit.SECONDS);
    long payload = handler.flowControlPing().payload();
    channelRead(pingFrame(true, payload));

    assertEquals(maxWindow, localFlowController.initialWindowSize(connectionStream));
  }

  @Test
  public void transportTracer_windowSizeDefault() throws Exception {
    manualSetUp();
    TransportStats transportStats = transportTracer.getStats();
    assertEquals(Http2CodecUtil.DEFAULT_WINDOW_SIZE, transportStats.localFlowControlWindow);
    assertEquals(flowControlWindow, transportStats.remoteFlowControlWindow);
  }

  @Test
  public void transportTracer_windowSize() throws Exception {
    flowControlWindow = 1024 * 1024;
    manualSetUp();
    TransportStats transportStats = transportTracer.getStats();
    assertEquals(Http2CodecUtil.DEFAULT_WINDOW_SIZE, transportStats.localFlowControlWindow);
    assertEquals(flowControlWindow, transportStats.remoteFlowControlWindow);
  }

  @Test
  public void transportTracer_windowUpdate_remote() throws Exception {
    manualSetUp();
    TransportStats before = transportTracer.getStats();
    assertEquals(Http2CodecUtil.DEFAULT_WINDOW_SIZE, before.localFlowControlWindow);
    assertEquals(Http2CodecUtil.DEFAULT_WINDOW_SIZE, before.remoteFlowControlWindow);

    ByteBuf serializedSettings = windowUpdate(0, 1000);
    channelRead(serializedSettings);
    TransportStats after = transportTracer.getStats();
    assertEquals(Http2CodecUtil.DEFAULT_WINDOW_SIZE + 1000,
        after.localFlowControlWindow);
    assertEquals(flowControlWindow, after.remoteFlowControlWindow);
  }

  @Test
  public void transportTracer_windowUpdate_local() throws Exception {
    manualSetUp();
    TransportStats before = transportTracer.getStats();
    assertEquals(Http2CodecUtil.DEFAULT_WINDOW_SIZE, before.localFlowControlWindow);
    assertEquals(flowControlWindow, before.remoteFlowControlWindow);

    // If the window size is below a certain threshold, netty will wait to apply the update.
    // Use a large increment to be sure that it exceeds the threshold.
    connection().local().flowController().incrementWindowSize(
        connection().connectionStream(), 8 * Http2CodecUtil.DEFAULT_WINDOW_SIZE);

    TransportStats after = transportTracer.getStats();
    assertEquals(Http2CodecUtil.DEFAULT_WINDOW_SIZE, after.localFlowControlWindow);
    assertEquals(flowControlWindow + 8 * Http2CodecUtil.DEFAULT_WINDOW_SIZE,
        connection().local().flowController().windowSize(connection().connectionStream()));
  }

  private AbstractNettyHandler setupPingTest() throws Exception {
    this.flowControlWindow = 1024 * 64;
    manualSetUp();
    makeStream();

    AbstractNettyHandler handler = (AbstractNettyHandler) handler();
    handler.setAutoTuneFlowControl(true);
    return handler;
  }

  @Test
  public void bdpPingLimitOutstanding() throws Exception {
    AbstractNettyHandler handler = setupPingTest();
    long pingData = handler.flowControlPing().payload();

    byte[] data1KbBuf = initXkbBuffer(1);
    byte[] data40KbBuf = initXkbBuffer(40);

    readXCopies(1, data1KbBuf); // should initiate a ping

    readXCopies(1, data40KbBuf); // no ping, already active
    fakeClock().forwardTime(20, TimeUnit.MILLISECONDS);
    readPingAck(pingData);
    assertEquals(1, handler.flowControlPing().getPingCount());
    assertEquals(1, handler.flowControlPing().getPingReturn());

    readXCopies(4, data40KbBuf); // initiate ping
    assertEquals(2, handler.flowControlPing().getPingCount());
    fakeClock.forwardTime(1, TimeUnit.MILLISECONDS);
    readPingAck(pingData);

    readXCopies(1, data1KbBuf); // ping again since had 160K data since last ping started
    assertEquals(3, handler.flowControlPing().getPingCount());
    fakeClock.forwardTime(1, TimeUnit.MILLISECONDS);
    readPingAck(pingData);

    fakeClock.forwardTime(1, TimeUnit.MILLISECONDS);
    readXCopies(1, data1KbBuf); // no ping, too little data
    assertEquals(3, handler.flowControlPing().getPingCount());
  }

  @Test
  public void testPingBackoff() throws Exception {
    AbstractNettyHandler handler = setupPingTest();
    long pingData = handler.flowControlPing().payload();
    byte[] data40KbBuf = initXkbBuffer(40);

    handler.flowControlPing().setDataSizeAndSincePing(200000);

    for (int i = 0; i <= 10; i++) {
      int beforeCount = handler.flowControlPing().getPingCount();
      // should resize on 0
      readXCopies(6, data40KbBuf); // initiate ping on i= {0, 1, 3, 6, 10}
      int afterCount = handler.flowControlPing().getPingCount();
      fakeClock().forwardNanos(200);
      if (afterCount > beforeCount) {
        readPingAck(pingData); // should increase backoff multiplier
      }
    }
    assertEquals(6, handler.flowControlPing().getPingCount());
  }

  @Test
  public void bdpPingWindowResizing() throws Exception {
    this.flowControlWindow = 1024 * 8;
    manualSetUp();
    makeStream();

    AbstractNettyHandler handler = (AbstractNettyHandler) handler();
    handler.setAutoTuneFlowControl(true);
    Http2LocalFlowController localFlowController = connection().local().flowController();
    long pingData = handler.flowControlPing().payload();
    int initialWindowSize = localFlowController.initialWindowSize();
    byte[] data1Kb = initXkbBuffer(1);
    byte[] data10Kb = initXkbBuffer(10);

    readXCopies(1, data1Kb); // initiate ping
    fakeClock().forwardNanos(2);
    readPingAck(pingData); // should not resize window because of small target window
    assertEquals(initialWindowSize, localFlowController.initialWindowSize());

    readXCopies(2, data10Kb); // initiate ping on first
    fakeClock().forwardNanos(200);
    readPingAck(pingData); // should resize window
    int windowSizeA = localFlowController.initialWindowSize();
    Assert.assertNotEquals(initialWindowSize, windowSizeA);

    readXCopies(3, data10Kb); // initiate ping w/ first 10K packet
    fakeClock().forwardNanos(5000);
    readPingAck(pingData); // should not resize window as bandwidth didn't increase
    Assert.assertEquals(windowSizeA, localFlowController.initialWindowSize());

    readXCopies(6, data10Kb); // initiate ping with fist packet
    fakeClock().forwardNanos(100);
    readPingAck(pingData); // should resize window
    int windowSizeB = localFlowController.initialWindowSize();
    Assert.assertNotEquals(windowSizeA, windowSizeB);
  }

  private void readPingAck(long pingData) throws Exception {
    channelRead(pingFrame(true, pingData));
  }

  private void readXCopies(int copies, byte[] data) throws Exception {
    for (int i = 0; i < copies; i++) {
      channelRead(grpcDataFrame(STREAM_ID, false, data)); // buffer it
      stream().request(1); // consume it
    }
  }

  private byte[] initXkbBuffer(int multiple) {
    ByteBuffer data = ByteBuffer.allocate(1024 * multiple);

    for (int i = 0; i < multiple * 1024 / 4; i++) {
      data.putInt(4 * i, 1111);
    }
    return data.array();
  }

}
