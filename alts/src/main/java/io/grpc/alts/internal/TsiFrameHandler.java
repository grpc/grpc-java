/*
 * Copyright 2018 The gRPC Authors
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

package io.grpc.alts.internal;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import io.grpc.alts.internal.TsiFrameProtector.Consumer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPromise;
import io.netty.channel.PendingWriteQueue;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.net.SocketAddress;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Encrypts and decrypts TSI Frames. Writes are buffered here until {@link #flush} is called. Writes
 * must not be made before the TSI handshake is complete.
 */
public final class TsiFrameHandler extends ByteToMessageDecoder implements ChannelOutboundHandler {

  private static final Logger logger = Logger.getLogger(TsiFrameHandler.class.getName());

  private TsiFrameProtector protector;
  private PendingWriteQueue pendingUnprotectedWrites;
  private boolean closeInitiated;

  public TsiFrameHandler(TsiFrameProtector protector) {
    this.protector = checkNotNull(protector, "protector");
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    super.handlerAdded(ctx);
    assert pendingUnprotectedWrites == null;
    pendingUnprotectedWrites = new PendingWriteQueue(checkNotNull(ctx));
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
    checkState(protector != null, "decode() called after close()");
    protector.unprotect(in, out, ctx.alloc());
  }

  @Override
  @SuppressWarnings("FutureReturnValueIgnored") // for setSuccess
  public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
    if (protector == null) {
      promise.setFailure(new IllegalStateException("write() called after close()"));
      return;
    }
    ByteBuf msg = (ByteBuf) message;
    if (!msg.isReadable()) {
      // Nothing to encode.
      promise.setSuccess();
      return;
    }

    // Just add the message to the pending queue. We'll write it on the next flush.
    pendingUnprotectedWrites.add(msg, promise);
  }

  @Override
  public void handlerRemoved0(ChannelHandlerContext ctx) throws Exception {
    destroyProtectorAndWrites();
  }

  @Override
  public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) {
    doClose(ctx);
    ctx.disconnect(promise);
  }

  @Override
  public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
    doClose(ctx);
    ctx.close(promise);
  }

  private void doClose(ChannelHandlerContext ctx) {
    if (closeInitiated) {
      return;
    }
    closeInitiated = true;
    try {
      // flush any remaining writes before close
      if (!pendingUnprotectedWrites.isEmpty()) {
        flush(ctx);
      }
    } catch (GeneralSecurityException e) {
      logger.log(Level.FINE, "Ignored error on flush before close", e);
    } finally {
      destroyProtectorAndWrites();
    }
  }

  @Override
  @SuppressWarnings("FutureReturnValueIgnored") // for aggregatePromise.doneAllocatingPromises
  public void flush(final ChannelHandlerContext ctx) throws GeneralSecurityException {
    if (pendingUnprotectedWrites == null || pendingUnprotectedWrites.isEmpty()) {
      // Return early if there's nothing to write. Otherwise protector.protectFlush() below may
      // not check for "no-data" and go on writing the 0-byte "data" to the socket with the
      // protection framing.
      return;
    }
    // Flushes can happen after close, but only when there are no pending writes.
    checkState(protector != null, "flush() called after close()");
    final ProtectedPromise aggregatePromise =
        new ProtectedPromise(ctx.channel(), ctx.executor(), pendingUnprotectedWrites.size());
    List<ByteBuf> bufs = new ArrayList<>(pendingUnprotectedWrites.size());

    // Drain the unprotected writes.
    while (!pendingUnprotectedWrites.isEmpty()) {
      ByteBuf in = (ByteBuf) pendingUnprotectedWrites.current();
      bufs.add(in.retain());
      // Remove and release the buffer and add its promise to the aggregate.
      aggregatePromise.addUnprotectedPromise(pendingUnprotectedWrites.remove());
    }

    final class ProtectedFrameWriteFlusher implements Consumer<ByteBuf> {

      @Override
      public void accept(ByteBuf byteBuf) {
        ctx.writeAndFlush(byteBuf, aggregatePromise.newPromise());
      }
    }

    protector.protectFlush(bufs, new ProtectedFrameWriteFlusher(), ctx.alloc());
    // We're done writing, start the flow of promise events.
    aggregatePromise.doneAllocatingPromises();
  }

  // Only here to fulfill ChannelOutboundHandler
  @Override
  public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) {
    ctx.bind(localAddress, promise);
  }

  // Only here to fulfill ChannelOutboundHandler
  @Override
  public void connect(
      ChannelHandlerContext ctx,
      SocketAddress remoteAddress,
      SocketAddress localAddress,
      ChannelPromise promise) {
    ctx.connect(remoteAddress, localAddress, promise);
  }

  // Only here to fulfill ChannelOutboundHandler
  @Override
  public void deregister(ChannelHandlerContext ctx, ChannelPromise promise) {
    ctx.deregister(promise);
  }

  // Only here to fulfill ChannelOutboundHandler
  @Override
  public void read(ChannelHandlerContext ctx) {
    ctx.read();
  }

  private void destroyProtectorAndWrites() {
    try {
      if (pendingUnprotectedWrites != null && !pendingUnprotectedWrites.isEmpty()) {
        pendingUnprotectedWrites.removeAndFailAll(
            new ChannelException("Pending write on teardown of TSI handler"));
      }
    } finally {
      pendingUnprotectedWrites = null;
    }
    if (protector != null) {
      try {
        protector.destroy();
      } finally {
        protector = null;
      }
    }
  }
}
