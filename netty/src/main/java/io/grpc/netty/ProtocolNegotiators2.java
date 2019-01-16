/*
 * Copyright 2019 The gRPC Authors
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
import static com.google.common.base.Preconditions.checkState;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.util.AsciiString;
import javax.annotation.CheckReturnValue;

/**
 * Common {@link ProtocolNegotiator}s used by gRPC.  Protocol negotiation follows a pattern to
 * simplify the pipeline.   The pipeline should look like:
 *
 * 1.  {@link ProtocolNegotiator#newHandler() PN.H}, created.
 * 2.  [Tail], {@link WriteBufferingAndExceptionHandler WBAEH}, [Head]
 * 3.  [Tail], WBAEH, PN.H, [Head]
 *
 * <p>Typically, PN.H with be an instance of {@link InitHandler IH}, which is a trivial handler that
 * can return the {@code scheme()} of the negotiation.  IH, and each handler after, replaces itself
 * with a "next" handler once its part of negotiation is complete.  This keeps the pipeline small,
 * and limits the interaction between handlers.
 *
 * <p>Additionally, each handler may fire a {@link ProtocolNegotiationEvent PNE} just after
 * replacing itself.  Handlers should capture user events of type PNE, and re-trigger the events
 * once that handler's part of negotiation is complete.  This can be seen in the
 * {@link WaitUntilActiveHandler WUAH}, which waits until the channel is active.  Once active, it
 * replaces itself with the next handler, and fires a PNE containing the addresses.  Continuing
 * with IH and WUAH:
 *
 * 3.  [Tail], WBAEH, IH, [Head]
 * 4.  [Tail], WBAEH, WUAH, [Head]
 * 5.  [Tail], WBAEH, {@link GrpcNegotiationHandler}, [Head]
 * 6a. [Tail], WBAEH, {@link GrpcHttp2ConnectionHandler GHCH}, [Head]
 * 6b. [Tail], GHCH, [Head]
 */
@CheckReturnValue
final class ProtocolNegotiators2 {

  private ProtocolNegotiators2() {}

  /**
   * Adapts a {@link ProtocolNegotiationEvent} to the {@link GrpcHttp2ConnectionHandler}.
   */
  public static final class GrpcNegotiationHandler extends ChannelInboundHandlerAdapter {
    private final GrpcHttp2ConnectionHandler next;

    public GrpcNegotiationHandler(GrpcHttp2ConnectionHandler next) {
      this.next = checkNotNull(next, "next");
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof ProtocolNegotiationEvent) {
        ProtocolNegotiationEvent protocolNegotiationEvent = (ProtocolNegotiationEvent) evt;
        ctx.pipeline().replace(ctx.name(), null, next);
        next.handleProtocolNegotiationCompleted(
            protocolNegotiationEvent.getAttributes(), protocolNegotiationEvent.getSecurity());
      } else {
        super.userEventTriggered(ctx, evt);
      }
    }
  }

  public static final class PlaintextProtocolNegotiator implements ProtocolNegotiator {

    @Override
    public Handler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      ChannelHandler grpcNegotiationHandler = new GrpcNegotiationHandler(grpcHandler);
      ChannelHandler activeHandler = new WaitUntilActiveHandler(grpcNegotiationHandler);
      PlaintextProtocolNegotiator.Handler initHandler = new InitHandler(Utils.HTTP, activeHandler);
      return initHandler;
    }

    @Override
    public void close() {}
  }

  /**
   * A {@link ProtocolNegotiator.Handler} that installs the next handler in the pipeline.  This
   * is likely the first handler returned by a {@link ProtocolNegotiator}.
   */
  public static final class InitHandler extends ChannelInitializer<Channel>
      implements ProtocolNegotiator.Handler {

    private final AsciiString scheme;
    private final ChannelHandler next;

    public InitHandler(AsciiString scheme, ChannelHandler next) {
      this.scheme = checkNotNull(scheme, "scheme");
      this.next = checkNotNull(next, "next");
    }

    @Override
    protected void initChannel(Channel ch) {
      ch.pipeline().addFirst(/*name=*/ null, next);
    }

    @Override
    public AsciiString scheme() {
      return scheme;
    }
  }

  /**
   * Waits for the channel to be active, and then installs the next Handler.  Using this allows
   * subsequent handlers to assume the channel is active and ready to send.  Additionally, this a
   * {@link ProtocolNegotiationEvent}, with the connection addresses.
   */
  public static final class WaitUntilActiveHandler extends ChannelInboundHandlerAdapter {
    private final ChannelHandler next;
    private ProtocolNegotiationEvent protocolNegotiationEvent;

    public WaitUntilActiveHandler(ChannelHandler next) {
      this.next = checkNotNull(next, "next");
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
      // This should be a noop, but just in case...
      super.handlerAdded(ctx);
      if (ctx.channel().isActive()) {
        ctx.pipeline().replace(ctx.name(), null, next);
        fireProtocolNegotiationEvent(ctx);
      }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      ctx.pipeline().replace(ctx.name(), null, next);
      // Still propagate channelActive to the new handler.
      super.channelActive(ctx);
      fireProtocolNegotiationEvent(ctx);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof ProtocolNegotiationEvent) {
        assert !ctx.channel().isActive();
        checkState(protocolNegotiationEvent == null, "protocolNegotiationEvent already sent");
        protocolNegotiationEvent = (ProtocolNegotiationEvent) evt;
      } else {
        super.userEventTriggered(ctx, evt);
      }
    }

    private void fireProtocolNegotiationEvent(ChannelHandlerContext ctx) {
      if (protocolNegotiationEvent == null) {
        protocolNegotiationEvent = ProtocolNegotiationEvent.DEFAULT;
      }
      Attributes attrs = protocolNegotiationEvent.getAttributes().toBuilder()
          .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, ctx.channel().localAddress())
          .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, ctx.channel().remoteAddress())
          .build();
      ctx.fireUserEventTriggered(protocolNegotiationEvent.withAttributes(attrs));
    }
  }
}
