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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import io.grpc.Attributes;
import io.grpc.Channel;
import io.grpc.Grpc;
import io.grpc.InternalChannelz.OtherSecurity;
import io.grpc.InternalChannelz.Security;
import io.grpc.SecurityLevel;
import io.grpc.Status;
import io.grpc.alts.internal.RpcProtocolVersionsUtil.RpcVersionsCheckResult;
import io.grpc.internal.GrpcAttributes;
import io.grpc.internal.ObjectPool;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.InternalNettyChannelBuilder;
import io.grpc.netty.InternalNettyChannelBuilder.ProtocolNegotiatorFactory;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiators;
import io.netty.channel.ChannelHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A gRPC {@link ProtocolNegotiator} for ALTS. This class creates a Netty handler that provides ALTS
 * security on the wire, similar to Netty's {@code SslHandler}.
 */
// TODO(carl-mastrangelo): rename this AltsProtocolNegotiators.
public final class AltsProtocolNegotiator {
  private static final Logger logger = Logger.getLogger(AltsProtocolNegotiator.class.getName());

  @Grpc.TransportAttr
  public static final Attributes.Key<TsiPeer> TSI_PEER_KEY = Attributes.Key.create("TSI_PEER");
  @Grpc.TransportAttr
  public static final Attributes.Key<Object> AUTH_CONTEXT_KEY =
      Attributes.Key.create("AUTH_CONTEXT_KEY");

  private static final AsciiString SCHEME = AsciiString.of("https");

  /**
   * ClientAltsProtocolNegotiatorFactory is a factory for doing client side negotiation of an ALTS
   * channel.
   */
  public static final class ClientAltsProtocolNegotiatorFactory
      implements InternalNettyChannelBuilder.ProtocolNegotiatorFactory {

    private final ImmutableList<String> targetServiceAccounts;
    private final LazyChannel lazyHandshakerChannel;

    public ClientAltsProtocolNegotiatorFactory(
        List<String> targetServiceAccounts,
        ObjectPool<Channel> handshakerChannelPool) {
      this.targetServiceAccounts = ImmutableList.copyOf(targetServiceAccounts);
      this.lazyHandshakerChannel = new LazyChannel(handshakerChannelPool);
    }

    @Override
    public ProtocolNegotiator buildProtocolNegotiator() {
      return new ClientAltsProtocolNegotiator(
          new ClientTsiHandshakerFactory(targetServiceAccounts, lazyHandshakerChannel),
          lazyHandshakerChannel);
    }
  }

  private static final class ClientAltsProtocolNegotiator implements ProtocolNegotiator {
    private final TsiHandshakerFactory handshakerFactory;
    private final LazyChannel lazyHandshakerChannel;

    ClientAltsProtocolNegotiator(
        TsiHandshakerFactory handshakerFactory, LazyChannel lazyHandshakerChannel) {
      this.handshakerFactory = checkNotNull(handshakerFactory, "handshakerFactory");
      this.lazyHandshakerChannel = checkNotNull(lazyHandshakerChannel, "lazyHandshakerChannel");
    }

    @Override
    public AsciiString scheme() {
      return SCHEME;
    }

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      TsiHandshaker handshaker = handshakerFactory.newHandshaker(grpcHandler.getAuthority());
      NettyTsiHandshaker nettyHandshaker = new NettyTsiHandshaker(handshaker);
      ChannelHandler gnh = InternalProtocolNegotiators.grpcNegotiationHandler(grpcHandler);
      ChannelHandler thh =
          new TsiHandshakeHandler(gnh, nettyHandshaker, new AltsHandshakeValidator());
      ChannelHandler wuah = InternalProtocolNegotiators.waitUntilActiveHandler(thh);
      return wuah;
    }

    @Override
    public void close() {
      lazyHandshakerChannel.close();
    }
  }

  /**
   * Creates a protocol negotiator for ALTS on the server side.
   */
  public static ProtocolNegotiator serverAltsProtocolNegotiator(
      ObjectPool<Channel> handshakerChannelPool) {
    final LazyChannel lazyHandshakerChannel = new LazyChannel(handshakerChannelPool);
    final class ServerTsiHandshakerFactory implements TsiHandshakerFactory {

      @Override
      public TsiHandshaker newHandshaker(@Nullable String authority) {
        assert authority == null;
        return AltsTsiHandshaker.newServer(
            HandshakerServiceGrpc.newStub(lazyHandshakerChannel.get()),
            new AltsHandshakerOptions(RpcProtocolVersionsUtil.getRpcProtocolVersions()));
      }
    }

    return new ServerAltsProtocolNegotiator(
        new ServerTsiHandshakerFactory(), lazyHandshakerChannel);
  }

  @VisibleForTesting
  static final class ServerAltsProtocolNegotiator implements ProtocolNegotiator {
    private final TsiHandshakerFactory handshakerFactory;
    private final LazyChannel lazyHandshakerChannel;

    @VisibleForTesting
    ServerAltsProtocolNegotiator(
        TsiHandshakerFactory handshakerFactory, LazyChannel lazyHandshakerChannel) {
      this.handshakerFactory = checkNotNull(handshakerFactory, "handshakerFactory");
      this.lazyHandshakerChannel = checkNotNull(lazyHandshakerChannel, "lazyHandshakerChannel");
    }

    @Override
    public AsciiString scheme() {
      return SCHEME;
    }

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      TsiHandshaker handshaker = handshakerFactory.newHandshaker(/* authority= */ null);
      NettyTsiHandshaker nettyHandshaker = new NettyTsiHandshaker(handshaker);
      ChannelHandler gnh = InternalProtocolNegotiators.grpcNegotiationHandler(grpcHandler);
      ChannelHandler thh =
          new TsiHandshakeHandler(gnh, nettyHandshaker, new AltsHandshakeValidator());
      ChannelHandler wuah = InternalProtocolNegotiators.waitUntilActiveHandler(thh);
      return wuah;
    }

    @Override
    public void close() {
      logger.finest("ALTS Server ProtocolNegotiator Closed");
      lazyHandshakerChannel.close();
    }
  }

  /**
   * A Protocol Negotiator factory which can switch between ALTS and TLS based on EAG Attrs.
   */
  public static final class GoogleDefaultProtocolNegotiatorFactory
      implements ProtocolNegotiatorFactory {
    private final ImmutableList<String> targetServiceAccounts;
    private final LazyChannel lazyHandshakerChannel;
    private final SslContext sslContext;

    /**
     * Creates Negotiator Factory, which will either use the targetServiceAccounts and
     * handshakerChannelPool, or the sslContext.
     */
    public GoogleDefaultProtocolNegotiatorFactory(
        List<String> targetServiceAccounts,
        ObjectPool<Channel> handshakerChannelPool,
        SslContext sslContext) {
      this.targetServiceAccounts = ImmutableList.copyOf(targetServiceAccounts);
      this.lazyHandshakerChannel = new LazyChannel(handshakerChannelPool);
      this.sslContext = checkNotNull(sslContext, "sslContext");
    }

    @Override
    public ProtocolNegotiator buildProtocolNegotiator() {
      return new GoogleDefaultProtocolNegotiator(
          new ClientTsiHandshakerFactory(targetServiceAccounts, lazyHandshakerChannel),
          lazyHandshakerChannel,
          sslContext);
    }
  }

  private static final class GoogleDefaultProtocolNegotiator implements ProtocolNegotiator {
    private final TsiHandshakerFactory handshakerFactory;
    private final LazyChannel lazyHandshakerChannel;
    private final SslContext sslContext;

    GoogleDefaultProtocolNegotiator(
        TsiHandshakerFactory handshakerFactory,
        LazyChannel lazyHandshakerChannel,
        SslContext sslContext) {
      this.handshakerFactory = checkNotNull(handshakerFactory, "handshakerFactory");
      this.lazyHandshakerChannel = checkNotNull(lazyHandshakerChannel, "lazyHandshakerChannel");
      this.sslContext = checkNotNull(sslContext, "checkNotNull");
    }

    @Override
    public AsciiString scheme() {
      return SCHEME;
    }

    @SuppressWarnings("deprecation")
    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      ChannelHandler gnh = InternalProtocolNegotiators.grpcNegotiationHandler(grpcHandler);
      ChannelHandler securityHandler;
      if (grpcHandler.getEagAttributes().get(GrpcAttributes.ATTR_LB_ADDR_AUTHORITY) != null
          || grpcHandler.getEagAttributes().get(GrpcAttributes.ATTR_LB_PROVIDED_BACKEND) != null) {
        TsiHandshaker handshaker = handshakerFactory.newHandshaker(grpcHandler.getAuthority());
        NettyTsiHandshaker nettyHandshaker = new NettyTsiHandshaker(handshaker);
        securityHandler =
            new TsiHandshakeHandler(gnh, nettyHandshaker, new AltsHandshakeValidator());
      } else {
        securityHandler = InternalProtocolNegotiators.clientTlsHandler(
            gnh, sslContext, grpcHandler.getAuthority());
      }
      ChannelHandler wuah = InternalProtocolNegotiators.waitUntilActiveHandler(securityHandler);
      return wuah;
    }

    @Override
    public void close() {
      logger.finest("ALTS Server ProtocolNegotiator Closed");
      lazyHandshakerChannel.close();
    }
  }

  private static final class ClientTsiHandshakerFactory implements TsiHandshakerFactory {

    private final ImmutableList<String> targetServiceAccounts;
    private final LazyChannel lazyHandshakerChannel;

    ClientTsiHandshakerFactory(
        ImmutableList<String> targetServiceAccounts, LazyChannel lazyHandshakerChannel) {
      this.targetServiceAccounts = checkNotNull(targetServiceAccounts, "targetServiceAccounts");
      this.lazyHandshakerChannel = checkNotNull(lazyHandshakerChannel, "lazyHandshakerChannel");
    }

    @Override
    public TsiHandshaker newHandshaker(@Nullable String authority) {
      AltsClientOptions handshakerOptions =
          new AltsClientOptions.Builder()
              .setRpcProtocolVersions(RpcProtocolVersionsUtil.getRpcProtocolVersions())
              .setTargetServiceAccounts(targetServiceAccounts)
              .setTargetName(authority)
              .build();
      return AltsTsiHandshaker.newClient(
          HandshakerServiceGrpc.newStub(lazyHandshakerChannel.get()), handshakerOptions);
    }
  }

  /** Channel created from a channel pool lazily. */
  @VisibleForTesting
  static final class LazyChannel {
    private final ObjectPool<Channel> channelPool;
    private Channel channel;

    @VisibleForTesting
    LazyChannel(ObjectPool<Channel> channelPool) {
      this.channelPool = checkNotNull(channelPool, "channelPool");
    }

    /**
     * If channel is null, gets a channel from the channel pool, otherwise, returns the cached
     * channel.
     */
    synchronized Channel get() {
      if (channel == null) {
        channel = channelPool.getObject();
      }
      return channel;
    }

    /** Returns the cached channel to the channel pool. */
    synchronized void close() {
      if (channel != null) {
        channel = channelPool.returnObject(channel);
      }
    }
  }

  private static final class AltsHandshakeValidator extends TsiHandshakeHandler.HandshakeValidator {

    @Override
    public SecurityDetails validatePeerObject(Object peerObject) throws GeneralSecurityException {
      AltsAuthContext altsAuthContext = (AltsAuthContext) peerObject;
      // Checks peer Rpc Protocol Versions in the ALTS auth context. Fails the connection if
      // Rpc Protocol Versions mismatch.
      RpcVersionsCheckResult checkResult =
          RpcProtocolVersionsUtil.checkRpcProtocolVersions(
              RpcProtocolVersionsUtil.getRpcProtocolVersions(),
              altsAuthContext.getPeerRpcVersions());
      if (!checkResult.getResult()) {
        String errorMessage =
            "Local Rpc Protocol Versions "
                + RpcProtocolVersionsUtil.getRpcProtocolVersions()
                + " are not compatible with peer Rpc Protocol Versions "
                + altsAuthContext.getPeerRpcVersions();
        throw Status.UNAVAILABLE.withDescription(errorMessage).asRuntimeException();
      }
      return new SecurityDetails(
          SecurityLevel.PRIVACY_AND_INTEGRITY,
          new Security(new OtherSecurity("alts", Any.pack(altsAuthContext.context))));
    }
  }

  private AltsProtocolNegotiator() {}
}
