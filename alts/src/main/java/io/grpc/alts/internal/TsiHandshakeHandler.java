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
import io.grpc.netty.InternalProtocolNegotiators.ProtocolNegotiationHandler;
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
 * Performs The TSI Handshake during ProtocolNegotiation.
 */
public final class TsiHandshakeHandler extends ProtocolNegotiationHandler {

  private final NettyTsiHandshaker handshaker;
  private final HandshakeValidator handshakeValidator;
  private ChannelHandlerContext ctx;

  /** Constructs a TsiHandshakeHandler. */
  public TsiHandshakeHandler(
      ChannelHandler next, NettyTsiHandshaker handshaker, HandshakeValidator handshakeValidator) {
    super(next);
    this.handshaker = checkNotNull(handshaker, "handshaker");
    this.handshakeValidator = checkNotNull(handshakeValidator, "handshakeValidator");
  }

  @Override
  protected void protocolNegotiationEventTriggered(ChannelHandlerContext ctx) {
    this.ctx = ctx;
    ctx.pipeline().addBefore(ctx.name(), /* newName= */ null, new InternalTsiHandshakeDecoder());
  }

  /** A ByteToMessageDecoder actually performs TsiHandshakeHandler. */
  private final class InternalTsiHandshakeDecoder extends ByteToMessageDecoder {

    private static final int HANDSHAKE_FRAME_SIZE = 1024;

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
    protected void decode(ChannelHandlerContext decoderCtx, ByteBuf in, List<Object> out)
        throws Exception {
      // Process the data. If we need to send more data, do so now.
      if (handshaker.processBytesFromPeer(in) && handshaker.isInProgress()) {
        sendHandshake(decoderCtx);
      }

      // If the handshake is complete, transition to the framing state.
      if (!handshaker.isInProgress()) {
        TsiPeer peer = handshaker.extractPeer();
        Object authContext = handshaker.extractPeerObject();
        SecurityDetails details = handshakeValidator.validatePeerObject(authContext);
        // createFrameProtector must be called last.
        TsiFrameProtector protector = handshaker.createFrameProtector(decoderCtx.alloc());
        TsiFrameHandler framer = null;
        boolean success = false;
        try {
          framer = new TsiFrameHandler(protector);
          ChannelHandlerContext parentCtx = TsiHandshakeHandler.this.ctx;
          replaceProtocolNegotiationEvent(
              getSecurityUpdatedProtocolNegotiationEvent(peer, authContext, details));

          // Replacing TsiHandshakeHandler requires pretty complex coordination.
          // current pipeline: ...-InternalTsiHandshakeHandler(Decoder)-TsiHandshakeHandler-...
          // final pipeline: ...-TsiFrameHandler-NEXT-...
          // When the decoder is removed, it may release any buffered bytes. To make sure the NEXT
          // handler receives the buffered bytes, the NEXT handler needs to be added before the
          // decoder is removed. While the NEXT handler may write bytes, so the framer need to be
          // present before NEXT is added.
          decoderCtx.pipeline().addBefore(parentCtx.name(), null, framer);
          // replace TsiHandshakeHandler to NEXT.
          TsiHandshakeHandler.this.fireProtocolNegotiationEvent(parentCtx);
          decoderCtx.pipeline().remove(decoderCtx.name());
          success = true;
          InternalProtocolNegotiators.negotiationLogger(parentCtx)
              .log(ChannelLogLevel.INFO, "TsiHandshake finished");
        } finally {
          if (!success && framer != null) {
            try {
              decoderCtx.pipeline().remove(framer);
            } catch (Exception e) {
              // ignore to preserve original exception if exists
            }
          }
        }
      }
    }

    private ProtocolNegotiationEvent getSecurityUpdatedProtocolNegotiationEvent(
        TsiPeer peer, Object authContext, SecurityDetails details) {
      ProtocolNegotiationEvent pne = TsiHandshakeHandler.this.getProtocolNegotiationEvent();
      Attributes.Builder attrs = InternalProtocolNegotiationEvent.getAttributes(pne)
          .toBuilder()
          .set(TSI_PEER_KEY, peer)
          .set(AUTH_CONTEXT_KEY, authContext)
          .set(GrpcAttributes.ATTR_SECURITY_LEVEL, details.getSecurityLevel());
      pne = InternalProtocolNegotiationEvent.withAttributes(pne, attrs.build());
      return InternalProtocolNegotiationEvent.withSecurity(pne, details.getSecurity());
    }

    /**
     * Sends as many bytes as are available from the handshaker to the remote peer.
     */
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

      @Nullable
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
}
