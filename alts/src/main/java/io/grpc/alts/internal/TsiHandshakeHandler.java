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
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.security.GeneralSecurityException;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Performs The TSI Handshake. When the handshake is complete, it fires a user event with a {@link
 * TsiHandshakeCompletionEvent} indicating the result of the handshake.
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

  private final NettyTsiHandshaker handshaker;
  private final HandshakeValidator handshakeValidator;
  private final ChannelHandler next;

  private ProtocolNegotiationEvent pne = InternalProtocolNegotiationEvent.getDefault();

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
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    InternalProtocolNegotiators.negotiationLogger(ctx)
        .log(ChannelLogLevel.INFO, "TsiHandshake started");
    sendHandshake(ctx);
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
        // replace the current handler with the framer (instead of adding before) since there may
        // be pending data after the handshake frame.  The data will need to be decoded before
        // being passed to the `next` handler.
        ctx.pipeline().replace(ctx.name(), null, framer);
        // Once the framer is in the pipeline, it will be cleaned up when the handler is removed.
        success = true;
      } finally {
        if (!success && protector != null) {
          protector.destroy();
        }
      }
      // Add the `next` handler as late as possible, as it will issue writes on being added.
      ctx.pipeline().addAfter(ctx.pipeline().context(framer).name(), null, next);
      fireProtocolNegotiationEvent(ctx, peer, authContext, details);
    }
  }

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    if (evt instanceof ProtocolNegotiationEvent) {
      pne = (ProtocolNegotiationEvent) evt;
    } else {
      super.userEventTriggered(ctx, evt);
    }
  }

  private void fireProtocolNegotiationEvent(
      ChannelHandlerContext ctx, TsiPeer peer, Object authContext, SecurityDetails details) {
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
      } finally {
        buf.release(written ? 1 : 2);
      }
    }
  }
}
