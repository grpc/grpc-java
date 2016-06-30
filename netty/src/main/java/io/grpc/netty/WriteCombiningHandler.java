/*
 * Copyright 2016, Google Inc. All rights reserved.
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

import static io.netty.util.ReferenceCountUtil.safeRelease;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;

/**
 * A handler that buffers small writes ({@link ByteBuf}s) and combines (copies) them into
 * a larger {@link ByteBuf} before passing them down the pipeline. This handler is supposed to
 * improve performance, as writing many small buffers can be quite slow.
 */
public final class WriteCombiningHandler extends ChannelOutboundHandlerAdapter {

  /*
   * An RPC typically translates to a HTTP/2 HEADERS frame, followed by a sequence of DATA frames
   * and maybe concluded by a HEADERS frame. The HTTP/2 codec typically encodes each frame
   * as two buffers. A 9 byte frame header followed by the payload. So for this handler, an RPC
   * could look like the following sequence of buffers:
   *
   * FrameHeader, HeadersPayload, FrameHeader, DataPayload, FrameHeader, HeadersPayload
   *
   * FrameHeader is always 9 bytes. From looking at gRPC headers, we found that the HeadersPayload
   * is typically in the 100 - 200 byte range. The DataPayload can be of arbitrary size and might
   * be too big to be combined. However, if the above assumptions hold we should typically be able
   * to combine the 6 buffers into 3:
   *
   * FrameHeader+HeadersPayload+FrameHeader, DataPayload, FrameHeader+HeadersPayload
   *
   * The {@code MAX_COPY_BUFFER_SIZE} and {@code MAX_BUFFERED_WRITES} constants were chosen so
   * that in a best case scenario, we can fit up to {@code MAX_BUFFERED_WRITES} into one pooled
   * buffer without allocation. That is, every second buffer would be a 9 byte FrameHeader,
   * followed by a payload lte {@code MAX_COPY_BUFFER_SIZE}.
   *
   * INITIAL_BUFFER_SIZE = (9 + MAX_COPY_BUFFER_SIZE) * MAX_BUFFERED_WRITES/2
   */

  /**
   * Initial size of the {@code ByteBuf} that we use to combine writes. The size is nothing
   * scientific. Not too big to bring too much overhead per channel, and not too small to amortize
   * allocation cost over many writes.
   */
  private static final int INITIAL_BUFFER_SIZE  = 4096;

  /**
   * Maximum size of a buffer to be copied.
   */
  private static final int MAX_COPY_BUFFER_SIZE = 247;

  /**
   * Maximum number of buffers to be combined before {@link #write} is called.
   */
  private static final int MAX_BUFFERED_WRITES  = 32;

  private ByteBuf pooledBuffer;
  private ByteBuf buffer;
  private ChannelPromise bufferPromise;
  private ChannelHandlerContext ctx;

  private int numBufferedWrites;

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) {
    reset(ctx);
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) {
    writeBuffer();
    reset(null);
  }

  @Override
  public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) {
    writeBuffer();
    ctx.disconnect(promise);
  }

  @Override
  public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
    writeBuffer();
    ctx.close(promise);
  }

  @Override
  public void flush(ChannelHandlerContext ctx) {
    writeBuffer();
    ctx.flush();
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
    if (!(msg instanceof ByteBuf) || ((ByteBuf) msg).readableBytes() > MAX_COPY_BUFFER_SIZE) {
      writeBuffer();
      ctx.write(msg, promise);
      return;
    }

    ByteBuf buf = (ByteBuf) msg;
    if (numBufferedWrites == MAX_BUFFERED_WRITES
        || numBufferedWrites > 1 && buf.readableBytes() > buffer.writableBytes()) {
      writeBuffer();
    }
    copyBytes(buf, promise);
  }

  private void newBufferAndPromise(int minCapacity) {
    if (pooledBuffer == null || pooledBuffer.writableBytes() < minCapacity) {
      safeRelease(pooledBuffer);
      pooledBuffer = ctx.alloc().directBuffer(INITIAL_BUFFER_SIZE, INITIAL_BUFFER_SIZE);
    }
    pooledBuffer = pooledBuffer.slice(pooledBuffer.writerIndex(), pooledBuffer.writableBytes());
    pooledBuffer.writerIndex(0);

    buffer = pooledBuffer;
    bufferPromise = new CollectivePromise(ctx);
  }

  private void writeBuffer() {
    if (numBufferedWrites == 0) {
      return;
    }

    if (numBufferedWrites == 1) {
      ctx.write(buffer, bufferPromise);
    } else {
      ctx.write(buffer.retainedSlice(), bufferPromise);
    }

    numBufferedWrites = 0;
    buffer = null;
    bufferPromise = null;
  }

  private void copyBytes(ByteBuf buf, ChannelPromise promise) {
    if (numBufferedWrites == 0) {
      // Only copy buffers if there are at least two.
      buffer = buf;
      bufferPromise = promise;
    } else if (numBufferedWrites == 1) {
      ByteBuf buf0 = buffer;
      ChannelPromise promise0 = bufferPromise;
      newBufferAndPromise(buf0.readableBytes() + buf.readableBytes());
      doCopyBytes(buf0, promise0);
      doCopyBytes(buf, promise);
    } else {
      doCopyBytes(buf, promise);
    }
    numBufferedWrites++;
  }

  private void doCopyBytes(ByteBuf buf, ChannelPromise promise) {
    buffer.writeBytes(buf);
    safeRelease(buf);
    ((CollectivePromise) bufferPromise).add(promise);
  }

  private void reset(ChannelHandlerContext ctx) {
    safeRelease(pooledBuffer);
    if (buffer != pooledBuffer) {
      safeRelease(buffer);
    }
    this.ctx = ctx;
    pooledBuffer = null;
    buffer = null;
    bufferPromise = null;
    numBufferedWrites = 0;
  }

  private static final class CollectivePromise extends DefaultChannelPromise
      implements ChannelFutureListener {

    private final ChannelPromise[] promises = new ChannelPromise[MAX_BUFFERED_WRITES];
    private int numPromises;

    CollectivePromise(ChannelHandlerContext ctx) {
      super(ctx.channel(), ctx.executor());
      addListener(this);
    }

    void add(ChannelPromise promise) {
      promises[numPromises++] = promise;
    }

    @Override
    public void operationComplete(ChannelFuture future) {
      final boolean isSuccess = future.isSuccess();
      for (int i = 0; i < numPromises; i++) {
        ChannelPromise promise = promises[i];
        if (isSuccess) {
          promise.trySuccess();
        } else {
          promise.tryFailure(future.cause());
        }
      }
    }
  }
}
