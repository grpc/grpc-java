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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

public class WriteCombiningHandler extends ChannelOutboundHandlerAdapter {

  private static final int BUFFER_SIZE = 4096;

  private ByteBuf buffer;
  private ByteBufAllocator allocator;

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    allocator = ctx.channel().alloc();
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
    ByteBuf data = (ByteBuf) msg;
    if (buffer == null) {
      buffer = allocator.directBuffer(BUFFER_SIZE, BUFFER_SIZE);
    }

    if (buffer.writableBytes() >= data.readableBytes()) {
      buffer.writeBytes(data);
      promise.setSuccess();
      ReferenceCountUtil.safeRelease(data);
    } else {
      ctx.write(buffer);
      buffer = null;
      if (data.readableBytes() < BUFFER_SIZE) {
        write(ctx, msg, promise);
      } else {
        ctx.write(msg, promise);
      }
    }
  }

  @Override
  public void flush(ChannelHandlerContext ctx) throws Exception {
    if (buffer != null && buffer.readableBytes() > 0) {
      ctx.write(buffer, ctx.voidPromise());
      buffer = null;
    }
    ctx.flush();
  }
}
