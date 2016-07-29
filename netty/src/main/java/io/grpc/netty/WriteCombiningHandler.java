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

import static com.google.common.base.Preconditions.checkNotNull;
import static io.netty.util.ReferenceCountUtil.safeRelease;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.EmptyByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

import java.util.ArrayList;
import java.util.List;

public class WriteCombiningHandler extends ChannelOutboundHandlerAdapter {

  private static final int INITIAL_BUFFER_SIZE = 4096;
  private static final int MAX_COPY_BUFFER_SIZE = 256;

  private ByteBuf buffer;
  private CollectivePromise bufferPromise;

  private ChannelHandlerContext ctx;

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    buffer = ctx.alloc().directBuffer(INITIAL_BUFFER_SIZE, INITIAL_BUFFER_SIZE);
    bufferPromise = new CollectivePromise(ctx);
    this.ctx = ctx;
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    safeRelease(buffer);
    buffer = null;
    bufferPromise = null;
    this.ctx = null;
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
    if (!(msg instanceof ByteBuf)) {
      writeBuffer();
      ctx.write(msg, promise);
      return;
    }
    final ByteBuf data = (ByteBuf) msg;
    if (data.readableBytes() > MAX_COPY_BUFFER_SIZE) {
      writeBuffer();
      ctx.write(data, promise);
    } else if (data.readableBytes() > buffer.writableBytes()) {
      writeBuffer();
      copyBytes(data, promise);
    } else {
      copyBytes(data, promise);
    }
  }

  @Override
  public void flush(ChannelHandlerContext ctx) throws Exception {
    writeBuffer();
    ctx.flush();
  }

  private void writeBuffer() {
    if (!buffer.isReadable()) {
      return;
    }
    ctx.write(buffer.retainedSlice(), bufferPromise);
    newBufferAndPromise();
  }

  private void newBufferAndPromise() {
    if (buffer.writableBytes() > MAX_COPY_BUFFER_SIZE * 2) {
      buffer = buffer.slice(buffer.writerIndex(), buffer.writableBytes());
      buffer.writerIndex(0);
    } else {
      safeRelease(buffer);
      assert buffer.refCnt() == 0;
      buffer = ctx.alloc().directBuffer(INITIAL_BUFFER_SIZE, INITIAL_BUFFER_SIZE);
    }
    bufferPromise = new CollectivePromise(ctx);
  }

  private void copyBytes(ByteBuf buf, ChannelPromise promise) {
    buffer.writeBytes(buf);
    bufferPromise.add(promise);
    safeRelease(buf);
  }

  private static final class CollectivePromise extends DefaultChannelPromise
      implements FutureListener<Void> {

    private final List<ChannelPromise> promises = new ArrayList<ChannelPromise>();

    CollectivePromise(ChannelHandlerContext ctx) {
      super(ctx.channel(), ctx.executor());
      addListener(this);
    }

    void add(ChannelPromise promise) {
      checkNotNull(promise);
      promises.add(promise);
    }

    @Override
    public void operationComplete(Future<Void> future) throws Exception {
      final boolean isSuccess = future.isSuccess();
      for (int i = 0; i < promises.size(); i++) {
        ChannelPromise promise = promises.get(i);
        if (isSuccess) {
          promise.trySuccess();
        } else {
          promise.tryFailure(future.cause());
        }
      }
    }
  }
}
