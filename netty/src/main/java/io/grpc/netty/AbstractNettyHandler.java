/*
 * Copyright 2015, Google Inc. All rights reserved.
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
import static io.netty.handler.codec.http2.Http2Stream2.CONNECTION_STREAM;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.annotations.VisibleForTesting;

import io.grpc.Attributes;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.DefaultHttp2PingFrame;
import io.netty.handler.codec.http2.DefaultHttp2SettingsFrame;
import io.netty.handler.codec.http2.DefaultHttp2WindowUpdateFrame;
import io.netty.handler.codec.http2.Http2ChannelDuplexHandler;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Settings;

/**
 * Base class for all Netty gRPC handlers. This class standardizes exception handling (always
 * shutdown the connection) as well as sending the initial connection window at startup.
 */
abstract class AbstractNettyHandler extends Http2ChannelDuplexHandler {
  private static final long GRACEFUL_SHUTDOWN_TIMEOUT_SECONDS = 5;
  private boolean autoTuneFlowControlOn;
  private ChannelHandlerContext ctx;
  private final FlowControlPinger flowControlPing;
  private final Http2FrameCodec frameCodec;

  private static final int BDP_MEASUREMENT_PING = 1234;
  private static final ByteBuf payloadBuf =
      unreleasableBuffer(directBuffer(8).writeLong(BDP_MEASUREMENT_PING));

  AbstractNettyHandler(Http2FrameCodecBuilder frameCodecBuilder, int initialConnectionWindow) {
    frameCodecBuilder.gracefulShutdownTimeout(GRACEFUL_SHUTDOWN_TIMEOUT_SECONDS, SECONDS);
    frameCodec = frameCodecBuilder.build();
    flowControlPing = new FlowControlPinger(initialConnectionWindow);
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    this.ctx = ctx;
    ctx.pipeline().addBefore(ctx.executor(), ctx.name(), null, frameCodec);
    super.handlerAdded(ctx);
  }

  /**
   * Triggered on protocol negotiation completion.
   *
   * <p>It must me called after negotiation is completed but before given handler is added to the
   * channel.
   *
   * @param attrs arbitrary attributes passed after protocol negotiation (eg. SSLSession).
   */
  public void handleProtocolNegotiationCompleted(Attributes attrs) {
  }

  Http2FrameCodec frameCodec() {
    return frameCodec;
  }

  protected final ChannelHandlerContext ctx() {
    return ctx;
  }

  @VisibleForTesting
  FlowControlPinger flowControlPing() {
    return flowControlPing;
  }

  @VisibleForTesting
  void setAutoTuneFlowControl(boolean isOn) {
    autoTuneFlowControlOn = isOn;
  }

  /**
   * Class for handling flow control pinging and flow control window updates as necessary.
   */
  final class FlowControlPinger {

    private static final int MAX_WINDOW_SIZE = 8 * 1024 * 1024;
    private int pingCount;
    private int pingReturn;
    private boolean pinging;
    private int dataSizeSincePing;
    private float lastBandwidth; // bytes per second
    private long lastPingTime;

    private int initialConnectionWindow;

    FlowControlPinger(int initialConnectionWindow) {
      this.initialConnectionWindow = initialConnectionWindow;
    }

    public int payload() {
      return BDP_MEASUREMENT_PING;
    }

    public int maxWindow() {
      return MAX_WINDOW_SIZE;
    }

    public void onDataRead(int dataLength, int paddingLength) {
      if (!autoTuneFlowControlOn) {
        return;
      }
      if (!isPinging()) {
        setPinging(true);
        sendPing();
      }
      incrementDataSincePing(dataLength + paddingLength);
    }

    public void updateWindow() {
      if (!autoTuneFlowControlOn) {
        return;
      }
      pingReturn++;
      long elapsedTime = System.nanoTime() - lastPingTime;
      if (elapsedTime == 0) {
        elapsedTime = 1;
      }
      long bandwidth = getDataSincePing() * SECONDS.toNanos(1) / elapsedTime;
      // Calculate new window size by doubling the observed BDP, but cap at max window
      int targetWindow = Math.min(getDataSincePing() * 2, MAX_WINDOW_SIZE);
      setPinging(false);
      if (targetWindow > initialConnectionWindow && bandwidth > lastBandwidth) {
        lastBandwidth = bandwidth;
        int increase = targetWindow - initialConnectionWindow;

        ctx().write(new DefaultHttp2WindowUpdateFrame(increase).stream(CONNECTION_STREAM));
        Http2Settings updateInitialWindowSize = new Http2Settings().initialWindowSize(targetWindow);
        ctx().write(new DefaultHttp2SettingsFrame(updateInitialWindowSize));

        // TODO(buchgr): Only set this on settings ack?
        initialConnectionWindow = targetWindow;
      }
    }

    private boolean isPinging() {
      return pinging;
    }

    private void setPinging(boolean pingOut) {
      pinging = pingOut;
    }

    private void sendPing() {
      setDataSizeSincePing(0);
      lastPingTime = System.nanoTime();
      ctx().write(new DefaultHttp2PingFrame(payloadBuf.slice(), false));
      pingCount++;
    }

    private void incrementDataSincePing(int increase) {
      int currentSize = getDataSincePing();
      setDataSizeSincePing(currentSize + increase);
    }

    int initialConnectionWindow() {
      return initialConnectionWindow;
    }

    @VisibleForTesting
    int getPingCount() {
      return pingCount;
    }

    @VisibleForTesting
    int getPingReturn() {
      return pingReturn;
    }

    @VisibleForTesting
    int getDataSincePing() {
      return dataSizeSincePing;
    }

    @VisibleForTesting
    void setDataSizeSincePing(int dataSize) {
      dataSizeSincePing = dataSize;
    }
  }
}
