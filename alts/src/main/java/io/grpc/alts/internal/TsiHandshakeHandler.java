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
import static io.grpc.alts.internal.AltsProtocolNegotiator.AUTH_CONTEXT_KEY;
import static io.grpc.alts.internal.AltsProtocolNegotiator.TSI_PEER_KEY;

import io.grpc.Attributes;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.InternalChannelz.Security;
import io.grpc.SecurityLevel;
import io.grpc.alts.internal.TsiHandshakeHandler.HandshakeValidator.SecurityDetails;
import io.grpc.internal.GrpcAttributes;
import io.grpc.netty.InternalProtocolNegotiationEvent;
import io.grpc.netty.InternalProtocolNegotiators;
import io.grpc.netty.ProtocolNegotiationEvent;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.security.GeneralSecurityException;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import javax.annotation.Nullable;

/**
 * Performs The TSI Handshake.
 */
public final class TsiHandshakeHandler extends ByteToMessageDecoder {
  /**
   * Validates a Tsi Peer object.
   */
  public abstract static class HandshakeValidator {

    public static final class SecurityDetails {

      private final SecurityLevel securityLevel;
      private final Security security;

      /**
       * Constructs SecurityDetails.
       */
      public SecurityDetails(io.grpc.SecurityLevel securityLevel, @Nullable Security security) {
        this.securityLevel = checkNotNull(securityLevel, "securityLevel");
        this.security = security;
      }

      public Security getSecurity() {
        return security;
      }

      public SecurityLevel getSecurityLevel() {
        return securityLevel;
      }
    }

    /**
     * Validates a Tsi Peer object.
     */
    public abstract SecurityDetails validatePeerObject(Object peerObject)
        throws GeneralSecurityException;
  }

  private static final int HANDSHAKE_FRAME_SIZE = 1024;
  // Avoid performing too many handshakes in parallel, as it may cause queuing in the handshake
  // server and cause unbounded blocking on the event loop (b/168808426). This is a workaround until
  // there is an async TSI handshaking API to avoid the blocking.
  private static final AsyncSemaphore semaphore = new AsyncSemaphore(32);

  private final NettyTsiHandshaker handshaker;
  private final HandshakeValidator handshakeValidator;
  private final ChannelHandler next;

  private ProtocolNegotiationEvent pne;
  private boolean semaphoreAcquired;

  /**
   * Constructs a TsiHandshakeHandler.
   */
  public TsiHandshakeHandler(
      ChannelHandler next, NettyTsiHandshaker handshaker, HandshakeValidator handshakeValidator) {
    this.handshaker = checkNotNull(handshaker, "handshaker");
    this.handshakeValidator = checkNotNull(handshakeValidator, "handshakeValidator");
    this.next = checkNotNull(next, "next");
  }

  @Override
  protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
      throws Exception {
    // TODO: Not sure why override is needed. Investigate if it can be removed.
    decode(ctx, in, out);
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
    // Process the data. If we need to send more data, do so now.
    if (handshaker.processBytesFromPeer(in) && handshaker.isInProgress()) {
      sendHandshake(ctx);
    }

    // If the handshake is complete, transition to the framing state.
    if (!handshaker.isInProgress()) {
      TsiPeer peer = handshaker.extractPeer();
      Object authContext = handshaker.extractPeerObject();
      SecurityDetails details = handshakeValidator.validatePeerObject(authContext);
      // createFrameProtector must be called last.
      TsiFrameProtector protector = handshaker.createFrameProtector(ctx.alloc());
      TsiFrameHandler framer;
      boolean success = false;
      try {
        framer = new TsiFrameHandler(protector);
        // adding framer and next handler after this handler before removing Decoder (current
        // handler). This will prevents any missing read from decoder and/or unframed write from
        // next handler.
        ctx.pipeline().addAfter(ctx.name(), null, framer);
        ctx.pipeline().addAfter(ctx.pipeline().context(framer).name(), null, next);
        ctx.pipeline().remove(ctx.name());
        fireProtocolNegotiationEvent(ctx, peer, authContext, details);
        success = true;
      } finally {
        if (!success && protector != null) {
          protector.destroy();
        }
      }
    }
  }

  @Override
  public void userEventTriggered(final ChannelHandlerContext ctx, Object evt) throws Exception {
    if (evt instanceof ProtocolNegotiationEvent) {
      checkState(pne == null, "negotiation already started");
      pne = (ProtocolNegotiationEvent) evt;
      InternalProtocolNegotiators.negotiationLogger(ctx)
          .log(ChannelLogLevel.INFO, "TsiHandshake started");
      ChannelFuture acquire = semaphore.acquire(ctx);
      if (acquire.isSuccess()) {
        semaphoreAcquired = true;
        sendHandshake(ctx);
      } else {
        acquire.addListener(new ChannelFutureListener() {
          @Override public void operationComplete(ChannelFuture future) {
            if (!future.isSuccess()) {
              ctx.fireExceptionCaught(future.cause());
              return;
            }
            if (ctx.isRemoved()) {
              semaphore.release();
              return;
            }
            semaphoreAcquired = true;
            try {
              sendHandshake(ctx);
            } catch (Exception ex) {
              ctx.fireExceptionCaught(ex);
            }
            ctx.flush();
          }
        });
      }
    } else {
      super.userEventTriggered(ctx, evt);
    }
  }

  private void fireProtocolNegotiationEvent(
      ChannelHandlerContext ctx, TsiPeer peer, Object authContext, SecurityDetails details) {
    checkState(pne != null, "negotiation not yet complete");
    InternalProtocolNegotiators.negotiationLogger(ctx)
        .log(ChannelLogLevel.INFO, "TsiHandshake finished");
    ProtocolNegotiationEvent localPne = pne;
    Attributes.Builder attrs = InternalProtocolNegotiationEvent.getAttributes(localPne).toBuilder()
        .set(TSI_PEER_KEY, peer)
        .set(AUTH_CONTEXT_KEY, authContext)
        .set(GrpcAttributes.ATTR_SECURITY_LEVEL, details.getSecurityLevel());
    localPne = InternalProtocolNegotiationEvent.withAttributes(localPne, attrs.build());
    localPne = InternalProtocolNegotiationEvent.withSecurity(localPne, details.getSecurity());
    ctx.fireUserEventTriggered(localPne);
  }

  /** Sends as many bytes as are available from the handshaker to the remote peer. */
  @SuppressWarnings("FutureReturnValueIgnored") // for addListener
  private void sendHandshake(ChannelHandlerContext ctx) throws GeneralSecurityException {
    while (true) {
      boolean written = false;
      ByteBuf buf = ctx.alloc().buffer(HANDSHAKE_FRAME_SIZE).retain(); // refcnt = 2
      try {
        handshaker.getBytesToSendToPeer(buf);
        if (buf.isReadable()) {
          ctx.writeAndFlush(buf).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
          written = true;
        } else {
          break;
        }
      } catch (GeneralSecurityException e) {
        throw new GeneralSecurityException("TsiHandshakeHandler encountered exception", e);
      } finally {
        buf.release(written ? 1 : 2);
      }
    }
  }

  @Override
  protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception {
    if (semaphoreAcquired) {
      semaphore.release();
      semaphoreAcquired = false;
    }
    handshaker.close();
  }

  private static class AsyncSemaphore {
    private final Object lock = new Object();
    @SuppressWarnings("JdkObsolete") // LinkedList avoids high watermark memory issues
    private final Queue<ChannelPromise> queue = new LinkedList<>();
    private int permits;

    public AsyncSemaphore(int permits) {
      this.permits = permits;
    }

    public ChannelFuture acquire(ChannelHandlerContext ctx) {
      synchronized (lock) {
        if (permits > 0) {
          permits--;
          return ctx.newSucceededFuture();
        }
        ChannelPromise promise = ctx.newPromise();
        queue.add(promise);
        return promise;
      }
    }

    public void release() {
      ChannelPromise next;
      synchronized (lock) {
        next = queue.poll();
        if (next == null) {
          permits++;
          return;
        }
      }
      next.setSuccess();
    }
  }
}
