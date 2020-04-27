/*
 * Copyright 2020 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.StreamBufferingEncoder;

/** A ListeningEncoder notifies {@link Http2OutboundFrameListener} on http2 outbound frame event. */
interface ListeningEncoder {

  void setListener(Http2OutboundFrameListener listener);

  /**
   * Partial implementation of (Listening subset of event) event listener for outbound http2
   * frames.
   */
  class Http2OutboundFrameListener {

    /** Notifies on outbound WINDOW_UPDATE frame. */
    public void onWindowUpdate(int streamId, int windowSizeIncrement) {}

    /** Notifies on outbound PING frame. */
    public void onPing(boolean ack, long data) {}

    /** Notifies on outbound DATA frame. */
    public void onData(int streamId, ByteBuf data, int padding, boolean endStream) {}
  }

  /** A {@link StreamBufferingEncoder} notifies http2 outbound frame event. */
  final class ListeningStreamBufferingEncoder
      extends StreamBufferingEncoder implements ListeningEncoder {

    private Http2OutboundFrameListener listener = new Http2OutboundFrameListener();

    public ListeningStreamBufferingEncoder(Http2ConnectionEncoder encoder) {
      super(encoder);
    }

    @Override
    public void setListener(Http2OutboundFrameListener listener) {
      this.listener = checkNotNull(listener, "listener");
    }

    @Override
    public ChannelFuture writePing(
        ChannelHandlerContext ctx, boolean ack, long data, ChannelPromise promise) {
      listener.onPing(ack, data);
      return super.writePing(ctx, ack, data, promise);
    }

    @Override
    public ChannelFuture writeWindowUpdate(
        ChannelHandlerContext ctx, int streamId, int windowSizeIncrement, ChannelPromise promise) {
      listener.onWindowUpdate(streamId, windowSizeIncrement);
      return super.writeWindowUpdate(ctx, streamId, windowSizeIncrement, promise);
    }

    @Override
    public ChannelFuture writeData(
        ChannelHandlerContext ctx,
        int streamId,
        ByteBuf data,
        int padding,
        boolean eos,
        ChannelPromise promise) {
      listener.onData(streamId, data, padding, eos);
      return super.writeData(ctx, streamId, data, padding, eos, promise);
    }
  }

  /** A {@link DefaultHttp2ConnectionEncoder} notifies http2 outbound frame event. */
  final class ListeningDefaultHttp2ConnectionEncoder
      extends DefaultHttp2ConnectionEncoder implements ListeningEncoder {

    private Http2OutboundFrameListener listener = new Http2OutboundFrameListener();

    public ListeningDefaultHttp2ConnectionEncoder(
        Http2Connection connection, Http2FrameWriter frameWriter) {
      super(connection, frameWriter);
    }

    @Override
    public void setListener(Http2OutboundFrameListener listener) {
      this.listener = checkNotNull(listener, "listener");
    }

    @Override
    public ChannelFuture writePing(
        ChannelHandlerContext ctx, boolean ack, long data, ChannelPromise promise) {
      listener.onPing(ack, data);
      return super.writePing(ctx, ack, data, promise);
    }

    @Override
    public ChannelFuture writeWindowUpdate(
        ChannelHandlerContext ctx, int streamId, int windowSizeIncrement, ChannelPromise promise) {
      listener.onWindowUpdate(streamId, windowSizeIncrement);
      return super.writeWindowUpdate(ctx, streamId, windowSizeIncrement, promise);
    }

    @Override
    public ChannelFuture writeData(
        ChannelHandlerContext ctx,
        int streamId,
        ByteBuf data,
        int padding,
        boolean eos,
        ChannelPromise promise) {
      listener.onData(streamId, data, padding, eos);
      return super.writeData(ctx, streamId, data, padding, eos, promise);
    }
  }
}
