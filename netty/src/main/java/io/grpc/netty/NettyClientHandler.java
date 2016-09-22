/*
 * Copyright 2014, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.netty;

import static io.netty.buffer.Unpooled.directBuffer;
import static io.netty.buffer.Unpooled.unreleasableBuffer;
import static io.netty.util.CharsetUtil.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.ClientTransport.PingCallback;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.Http2Ping;
import io.grpc.netty.GrpcHttp2HeadersDecoder.GrpcHttp2ClientHeadersDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.DefaultHttp2FrameWriter;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2PingFrame;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.DefaultHttp2WindowUpdateFrame;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2FrameReader;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersDecoder;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2InboundFrameLogger;
import io.netty.handler.codec.http2.Http2NoMoreStreamIdsException;
import io.netty.handler.codec.http2.Http2OutboundFrameLogger;
import io.netty.handler.codec.http2.Http2PingFrame;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2SettingsFrame;
import io.netty.handler.codec.http2.Http2Stream2;
import io.netty.handler.codec.http2.Http2Stream2Exception;
import io.netty.handler.codec.http2.Http2Stream2Visitor;
import io.netty.handler.codec.http2.StreamBufferingEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.util.internal.PlatformDependent;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client-side Netty handler for GRPC processing. All event handlers are executed entirely within
 * the context of the Netty Channel thread.
 */
class NettyClientHandler extends AbstractNettyHandler {
  private static final Logger logger = Logger.getLogger(NettyClientHandler.class.getName());

  /**
   * A message that simply passes through the channel without any real processing. It is useful to
   * check if buffers have been drained and test the health of the channel in a single operation.
   */
  static final Object NOOP_MESSAGE = new Object();

  /**
   * Status used when the transport has exhausted the number of streams.
   */
  private static final Status EXHAUSTED_STREAMS_STATUS =
          Status.UNAVAILABLE.withDescription("Stream IDs have been exhausted");
  private static final long USER_PING_PAYLOAD = 1111;

  private static final ByteBuf userPayloadBuf =
      unreleasableBuffer(directBuffer(8).writeLong(USER_PING_PAYLOAD));

  private final ChannelFutureListener onStreamClosedListener = new ChannelFutureListener() {
    @Override
    public void operationComplete(ChannelFuture future) throws Exception {
      lifecycleManager.notifyLostUser();
    }
  };

  private final ClientTransportLifecycleManager lifecycleManager;
  private final Ticker ticker;
  private WriteQueue clientWriteQueue;
  private Http2Ping ping;
  private boolean firstSettings = true;

  static NettyClientHandler newHandler(ClientTransportLifecycleManager lifecycleManager,
                                       int flowControlWindow, int maxHeaderListSize,
                                       Ticker ticker) {
    Preconditions.checkArgument(maxHeaderListSize > 0, "maxHeaderListSize must be positive");
    Http2HeadersDecoder headersDecoder = new GrpcHttp2ClientHeadersDecoder();

    try {
      headersDecoder.configuration().headerTable().maxHeaderListSize(maxHeaderListSize);
    } catch (Http2Exception e) {
      PlatformDependent.throwException(e);
    }

    Http2FrameReader frameReader = new DefaultHttp2FrameReader(headersDecoder);
    Http2FrameWriter frameWriter = new DefaultHttp2FrameWriter();
    Http2Connection connection = new DefaultHttp2Connection(false);

    return newHandler(
        connection, frameReader, frameWriter, lifecycleManager, flowControlWindow,
        maxHeaderListSize, ticker);
  }

  @VisibleForTesting
  static NettyClientHandler newHandler(Http2Connection connection,
                                       Http2FrameReader frameReader,
                                       Http2FrameWriter frameWriter,
                                       ClientTransportLifecycleManager lifecycleManager,
                                       int flowControlWindow,
                                       int maxHeaderListSize,
                                       Ticker ticker) {
    Preconditions.checkNotNull(connection, "connection");
    Preconditions.checkNotNull(frameReader, "frameReader");
    Preconditions.checkNotNull(lifecycleManager, "lifecycleManager");
    Preconditions.checkArgument(flowControlWindow > 0, "flowControlWindow must be positive");
    Preconditions.checkNotNull(ticker, "ticker");

    Http2FrameLogger frameLogger = new Http2FrameLogger(LogLevel.DEBUG, NettyClientHandler.class);
    frameReader = new Http2InboundFrameLogger(frameReader, frameLogger);
    frameWriter = new Http2OutboundFrameLogger(frameWriter, frameLogger);

    Http2Settings settings = new Http2Settings();
    settings.pushEnabled(false);
    settings.initialWindowSize(flowControlWindow);
    settings.maxConcurrentStreams(0);
    settings.maxHeaderListSize(maxHeaderListSize);

    Http2FrameCodecBuilder frameCodecBuilder = Http2FrameCodecBuilder
        .forClient()
        .frameLogger(frameLogger)
        .frameReader(frameReader)
        .frameWriter(frameWriter)
        .bufferOutgoingStreams(true)
        .initialSettings(settings);

    return new NettyClientHandler(frameCodecBuilder, flowControlWindow, lifecycleManager, ticker);
  }

  private NettyClientHandler(Http2FrameCodecBuilder frameCodecBuilder, int initalWindowSize,
                             ClientTransportLifecycleManager lifecycleManager,
                             Ticker ticker) {
    super(frameCodecBuilder, initalWindowSize);
    this.lifecycleManager = lifecycleManager;
    this.ticker = ticker;
  }

  /**
   * Handler for commands sent from the stream.
   */
  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
          throws Exception {
    if (msg instanceof CreateStreamCommand) {
      createStream((CreateStreamCommand) msg, promise);
    } else if (msg instanceof SendGrpcFrameCommand) {
      sendGrpcFrame(ctx, (SendGrpcFrameCommand) msg, promise);
    } else if (msg instanceof CancelClientStreamCommand) {
      cancelStream(ctx, (CancelClientStreamCommand) msg, promise);
    } else if (msg instanceof RequestMessagesCommand) {
      ((RequestMessagesCommand) msg).requestMessages();
    } else if (msg instanceof SendPingCommand) {
      sendPingFrame(ctx, (SendPingCommand) msg, promise);
    } else if (msg instanceof GracefulCloseCommand) {
      gracefulClose(ctx, (GracefulCloseCommand) msg, promise);
    } else if (msg instanceof ForcefulCloseCommand) {
      forcefulClose(ctx, (ForcefulCloseCommand) msg, promise);
    } else if (msg == NOOP_MESSAGE) {
      ctx.write(Unpooled.EMPTY_BUFFER, promise);
    } else {
      throw new AssertionError("Write called for unexpected type: " + msg.getClass().getName());
    }
  }

  // @VisibleForTesting
  // FlowControlPinger flowControlPinger() {
  // return flowControlPing;
  // }

  void startWriteQueue(Channel channel) {
    clientWriteQueue = new WriteQueue(channel);
  }

  WriteQueue getWriteQueue() {
    return clientWriteQueue;
  }

  /**
   * Returns the given processed bytes back to inbound flow control.
   */
  void returnProcessedBytes(Http2Stream2 http2Stream, int bytes) {
    ctx().write(new DefaultHttp2WindowUpdateFrame(bytes).stream(http2Stream));
  }

  private static NettyClientStream.TransportState requireTransportState(Http2Stream2 http2Stream) {
    if (!(http2Stream.managedState() instanceof NettyClientStream.TransportState)) {
      throw new IllegalStateException("Must never happen.");
    }
    return (NettyClientStream.TransportState) http2Stream.managedState();
  }

  /**
   * Handler for an inbound HTTP/2 HEADERS frame.
   */
  private static void onHeadersRead(Http2HeadersFrame headers) {
    NettyClientStream.TransportState transportState = requireTransportState(headers.stream());
    transportState.transportHeadersReceived(headers.headers(), headers.endStream());
  }

  /**
   * Handler for an inbound HTTP/2 DATA frame.
   */
  private void onDataRead(Http2DataFrame data) {
    flowControlPing().onDataRead(data.content().readableBytes(), data.padding());
    NettyClientStream.TransportState transportState = requireTransportState(data.stream());
    transportState.transportDataReceived(data.content(), data.endStream());
  }

  /**
   * Handler for an inbound HTTP/2 RST_STREAM frame, terminating a stream.
   */
  private static void onRstStreamRead(Http2ResetFrame reset) {
    NettyClientStream.TransportState transportState = requireTransportState(reset.stream());
    Status status = GrpcUtil.Http2Error.statusForCode((int) reset.errorCode())
        .augmentDescription("Received Rst Stream");
    transportState.transportReportStatus(status, false /*stop delivery*/, new Metadata());
  }

  @Override
  public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
    logger.fine("Network channel being closed by the application.");
    lifecycleManager.notifyShutdown(
        Status.UNAVAILABLE.withDescription("Transport closed for unknown reason"));
    super.close(ctx, promise);
  }

  /**
   * Handler for the Channel shutting down.
   */
  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    try {
      logger.fine("Network channel is closed");
      lifecycleManager.notifyShutdown(
          Status.UNAVAILABLE.withDescription("Network closed for unknown reason"));
      cancelPing(lifecycleManager.getShutdownThrowable());
      // Report status to the application layer for any open streams
      forEachActiveStream(new Http2Stream2Visitor() {
        @Override
        public boolean visit(Http2Stream2 http2Stream) {
          NettyClientStream .TransportState clientStream = requireTransportState(http2Stream);
          if (clientStream != null) {
            clientStream.transportReportStatus(
                lifecycleManager.getShutdownStatus(), false, new Metadata());
          }
          return true;
        }
      });
    } finally {
      // Close any open streams
      super.channelInactive(ctx);
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    if (cause instanceof Http2Stream2Exception) {
      Http2Stream2Exception http2Exception = (Http2Stream2Exception) cause;

      NettyClientStream.TransportState clientStream = (NettyClientStream.TransportState)
          http2Exception.stream().managedState();
      clientStream.transportReportStatus(Utils.statusFromThrowable(cause), false, new Metadata());
      ctx.write(
          new DefaultHttp2ResetFrame(http2Exception.error()).stream(http2Exception.stream()));
    } else {
      logger.log(Level.FINE, "Caught a connection error", cause);
      lifecycleManager.notifyShutdown(Utils.statusFromThrowable(cause));
      close(ctx(), ctx().newPromise());
    }
  }

  /**
   * Attempts to create a new stream from the given command. If there are too many active streams,
   * the creation request is queued.
   */
  private void createStream(CreateStreamCommand command, final ChannelPromise promise)
          throws Exception {
    if (lifecycleManager.getShutdownThrowable() != null) {
      // The connection is going away, just terminate the stream now.
      promise.setFailure(lifecycleManager.getShutdownThrowable());
      return;
    }

    final NettyClientStream.TransportState stream = command.stream();
    final Http2Stream2 http2Stream = newStream();
    stream.setHttp2Stream(http2Stream);
    final Http2Headers headers = command.headers();

    // Create an intermediate promise so that we can intercept the failure reported back to the
    // application.
    ChannelPromise tempPromise = ctx().newPromise();
    ctx().write(new DefaultHttp2HeadersFrame(headers).stream(http2Stream), tempPromise)
        .addListener(new ChannelFutureListener() {
          @Override
          public void operationComplete(ChannelFuture future) throws Exception {
            if (future.isSuccess()) {
              // Stream might have been buffered and cancelled in the meantime.
              if (!stream.isClosed()) {
                lifecycleManager.notifyNewUser();
                stream.onStreamActive();
                http2Stream.closeFuture().addListener(onStreamClosedListener);
              }

              // Just forward on the success status to the original promise.
              promise.setSuccess();
              return;
            }

            final Throwable cause = future.cause();

            if (cause instanceof Http2NoMoreStreamIdsException) {
              logger.fine("Stream IDs have been exhausted for this connection. "
                  + "Initiating graceful shutdown of the connection.");
              lifecycleManager.notifyShutdown(EXHAUSTED_STREAMS_STATUS);
              promise.setFailure(lifecycleManager.getShutdownThrowable());
              close(ctx(), ctx().newPromise());
              return;
            }

            if (cause instanceof StreamBufferingEncoder.Http2GoAwayException) {
              StreamBufferingEncoder.Http2GoAwayException e =
                  (StreamBufferingEncoder.Http2GoAwayException) cause;
              lifecycleManager.notifyShutdown(statusFromGoAway(e.errorCode(), e.debugData()));
              promise.setFailure(lifecycleManager.getShutdownThrowable());
              return;
            }

            promise.setFailure(cause);
          }
        });
  }

  /**
   * Cancels this stream.
   */
  private static void cancelStream(ChannelHandlerContext ctx, CancelClientStreamCommand cmd,
      ChannelPromise promise) {
    NettyClientStream.TransportState stream = cmd.stream();
    stream.transportReportStatus(cmd.reason(), true, new Metadata());

    ctx.write(new DefaultHttp2ResetFrame(Http2Error.CANCEL).stream(stream.http2Stream()), promise);
  }

  /**
   * Sends the given GRPC frame for the stream.
   */
  private static void sendGrpcFrame(ChannelHandlerContext ctx, SendGrpcFrameCommand cmd,
      ChannelPromise promise) {
    // Note: no need to flush since this is handled by the outbound flow controller.
    ctx.write(new DefaultHttp2DataFrame(cmd.content(), cmd.endStream())
        .stream(cmd.clientTransportState().http2Stream()), promise);
  }

  /**
   * Sends a PING frame. If a ping operation is already outstanding, the callback in the message is
   * registered to be called when the existing operation completes, and no new frame is sent.
   */
  private void sendPingFrame(ChannelHandlerContext ctx, SendPingCommand msg,
      ChannelPromise promise) {
    // Don't check lifecycleManager.getShutdownStatus() since we want to allow pings after shutdown
    // but before termination. After termination, messages will no longer arrive because the
    // pipeline clears all handlers on channel close.

    PingCallback callback = msg.callback();
    Executor executor = msg.executor();
    // we only allow one outstanding ping at a time, so just add the callback to
    // any outstanding operation
    if (ping != null) {
      promise.setSuccess();
      ping.addCallback(callback, executor);
      return;
    }

    // Use a new promise to prevent calling the callback twice on write failure: here and in
    // NettyClientTransport.ping(). It may appear strange, but it will behave the same as if
    // ping != null above.
    promise.setSuccess();
    promise = ctx().newPromise();
    // set outstanding operation
    Stopwatch stopwatch = Stopwatch.createStarted(ticker);
    ping = new Http2Ping(USER_PING_PAYLOAD, stopwatch);
    ping.addCallback(callback, executor);
    // and then write the ping
    ctx.write(new DefaultHttp2PingFrame(userPayloadBuf.slice(), false), promise);
    ctx.flush();
    final Http2Ping finalPing = ping;
    promise.addListener(new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        if (!future.isSuccess()) {
          Throwable cause = future.cause();
          if (cause instanceof ClosedChannelException) {
            cause = lifecycleManager.getShutdownThrowable();
            if (cause == null) {
              cause = Status.UNKNOWN.withDescription("Ping failed but for unknown reason.")
                  .withCause(future.cause()).asException();
            }
          }
          finalPing.failed(cause);
          if (ping == finalPing) {
            ping = null;
          }
        }
      }
    });
  }

  private void gracefulClose(ChannelHandlerContext ctx, GracefulCloseCommand msg,
      ChannelPromise promise) throws Exception {
    lifecycleManager.notifyShutdown(msg.getStatus());
    // Explicitly flush to create any buffered streams before sending GOAWAY.
    // TODO(ejona): determine if the need to flush is a bug in Netty
    flush(ctx);
    close(ctx, promise);
  }

  private void forcefulClose(final ChannelHandlerContext ctx, final ForcefulCloseCommand msg,
      ChannelPromise promise) throws Exception {
    lifecycleManager.notifyShutdown(
        Status.UNAVAILABLE.withDescription("Channel requested transport to shut down"));
    close(ctx, promise);
    forEachActiveStream(new Http2Stream2Visitor() {
      @Override
      public boolean visit(Http2Stream2 http2Stream) {
        NettyClientStream.TransportState clientStream = requireTransportState(http2Stream);
        clientStream.transportReportStatus(msg.getStatus(), true, new Metadata());
        ctx.write(new DefaultHttp2ResetFrame(Http2Error.CANCEL).stream(http2Stream));
        return true;
      }
    });
  }

  /**
   * Handler for a GOAWAY being received. Fails any streams created after the last known stream.
   */
  private void onGoAwayRead(Http2GoAwayFrame goAway) {
    Status status = statusFromGoAway(goAway.errorCode(), ByteBufUtil.getBytes(goAway.content()));
    lifecycleManager.notifyShutdown(status);
    final Status goAwayStatus = lifecycleManager.getShutdownStatus();
    try {
      final int lastStreamId = goAway.lastStreamId();
      forEachActiveStream(new Http2Stream2Visitor() {
        @Override
        public boolean visit(Http2Stream2 http2Stream) {
          if (http2Stream.id() > lastStreamId) {
            NettyClientStream.TransportState clientStream = requireTransportState(http2Stream);
            if (clientStream != null) {
              clientStream.transportReportStatus(goAwayStatus, false, new Metadata());
            }
          }
          return true;
        }
      });
    } catch (Http2Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void cancelPing(Throwable t) {
    if (ping != null) {
      ping.failed(t);
      ping = null;
    }
  }

  private static Status statusFromGoAway(long errorCode, byte[] debugData) {
    Status status = GrpcUtil.Http2Error.statusForCode((int) errorCode)
        .augmentDescription("Received Goaway");
    if (debugData != null && debugData.length > 0) {
      // If a debug message was provided, use it.
      String msg = new String(debugData, UTF_8);
      status = status.augmentDescription(msg);
    }
    return status;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof Http2HeadersFrame) {
      onHeadersRead((Http2HeadersFrame) msg);
    } else if (msg instanceof Http2DataFrame) {
      onDataRead((Http2DataFrame) msg);
    } else if (msg instanceof Http2ResetFrame) {
      onRstStreamRead((Http2ResetFrame) msg);
    } else if (msg instanceof Http2PingFrame) {
      Http2PingFrame pingFrame = (Http2PingFrame) msg;
      if (pingFrame.ack()) {
        onPingAckRead(pingFrame.content());
      }
    } else if (msg instanceof Http2SettingsFrame) {
      onSettingsRead();
    } else if (msg instanceof Http2GoAwayFrame) {
      onGoAwayRead((Http2GoAwayFrame) msg);
    }
    super.channelRead(ctx, msg);
  }


  private void onSettingsRead() {
    if (firstSettings) {
      firstSettings = false;
      lifecycleManager.notifyReady();
    }
  }

  private void onPingAckRead(ByteBuf data) {
    Http2Ping p = ping;
    if (data.getLong(data.readerIndex()) == flowControlPing().payload()) {
      flowControlPing().updateWindow();
      if (logger.isLoggable(Level.FINE)) {
        logger.log(Level.FINE, String.format("Window: %d",
            flowControlPing().initialConnectionWindow()));
      }
    } else if (p != null) {
      long ackPayload = data.readLong();
      if (p.payload() == ackPayload) {
        p.complete();
        ping = null;
      } else {
        logger.log(Level.WARNING, String.format(
            "Received unexpected ping ack. Expecting %d, got %d", p.payload(), ackPayload));
      }
    } else {
      logger.warning("Received unexpected ping ack. No ping outstanding");
    }
  }
}
