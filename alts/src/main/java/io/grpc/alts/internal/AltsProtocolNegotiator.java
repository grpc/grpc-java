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
import io.grpc.ChannelLogger;
import io.grpc.Grpc;
import io.grpc.InternalChannelz.OtherSecurity;
import io.grpc.InternalChannelz.Security;
import io.grpc.SecurityLevel;
import io.grpc.Status;
import io.grpc.alts.internal.RpcProtocolVersionsUtil.RpcVersionsCheckResult;
import io.grpc.grpclb.GrpclbConstants;
import io.grpc.internal.ObjectPool;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.InternalProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiators;
import io.netty.channel.ChannelHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A gRPC {@link ProtocolNegotiator} for ALTS. This class creates a Netty handler that provides ALTS
 * security on the wire, similar to Netty's {@code SslHandler}.
 */
// TODO(carl-mastrangelo): rename this AltsProtocolNegotiators.
public final class AltsProtocolNegotiator {
  private static final Logger logger = Logger.getLogger(AltsProtocolNegotiator.class.getName());

  static final String ALTS_MAX_CONCURRENT_HANDSHAKES_ENV_VARIABLE =
      "GRPC_ALTS_MAX_CONCURRENT_HANDSHAKES";
  @VisibleForTesting static final int DEFAULT_ALTS_MAX_CONCURRENT_HANDSHAKES = 32;
  private static final AsyncSemaphore handshakeSemaphore =
      new AsyncSemaphore(getAltsMaxConcurrentHandshakes());

  @Grpc.TransportAttr
  public static final Attributes.Key<TsiPeer> TSI_PEER_KEY =
      Attributes.Key.create("internal:TSI_PEER");
  @Grpc.TransportAttr
  public static final Attributes.Key<Object> AUTH_CONTEXT_KEY =
      Attributes.Key.create("internal:AUTH_CONTEXT_KEY");

  private static final AsciiString SCHEME = AsciiString.of("https");

  private static final String DIRECT_PATH_SERVICE_CFE_CLUSTER_PREFIX = "google_cfe_";
  private static final String CFE_CLUSTER_RESOURCE_NAME_PREFIX =
      "/envoy.config.cluster.v3.Cluster/google_cfe_";
  private static final String CFE_CLUSTER_AUTHORITY_NAME =
      "traffic-director-c2p.xds.googleapis.com";

  /**
   * ClientAltsProtocolNegotiatorFactory is a factory for doing client side negotiation of an ALTS
   * channel.
   */
  public static final class ClientAltsProtocolNegotiatorFactory
      implements InternalProtocolNegotiator.ClientFactory {

    private final ImmutableList<String> targetServiceAccounts;
    private final ObjectPool<Channel> handshakerChannelPool;

    public ClientAltsProtocolNegotiatorFactory(
        List<String> targetServiceAccounts,
        ObjectPool<Channel> handshakerChannelPool) {
      this.targetServiceAccounts = ImmutableList.copyOf(targetServiceAccounts);
      this.handshakerChannelPool = checkNotNull(handshakerChannelPool, "handshakerChannelPool");
    }

    @Override
    public ProtocolNegotiator newNegotiator() {
      return new ClientAltsProtocolNegotiator(
          targetServiceAccounts,
          handshakerChannelPool);
    }

    @Override
    public int getDefaultPort() {
      return 443;
    }
  }

  private static final class ClientAltsProtocolNegotiator implements ProtocolNegotiator {
    private final TsiHandshakerFactory handshakerFactory;
    private final LazyChannel lazyHandshakerChannel;

    ClientAltsProtocolNegotiator(
        ImmutableList<String> targetServiceAccounts, ObjectPool<Channel> handshakerChannelPool) {
      this.lazyHandshakerChannel = new LazyChannel(handshakerChannelPool);
      this.handshakerFactory =
          new ClientTsiHandshakerFactory(targetServiceAccounts, lazyHandshakerChannel);
    }

    @Override
    public AsciiString scheme() {
      return SCHEME;
    }

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      ChannelLogger negotiationLogger = grpcHandler.getNegotiationLogger();
      TsiHandshaker handshaker =
          handshakerFactory.newHandshaker(grpcHandler.getAuthority(), negotiationLogger);
      NettyTsiHandshaker nettyHandshaker = new NettyTsiHandshaker(handshaker);
      ChannelHandler gnh = InternalProtocolNegotiators.grpcNegotiationHandler(grpcHandler);
      ChannelHandler thh = new TsiHandshakeHandler(
          gnh, nettyHandshaker, new AltsHandshakeValidator(), handshakeSemaphore,
          negotiationLogger);
      ChannelHandler wuah = InternalProtocolNegotiators.waitUntilActiveHandler(thh,
          negotiationLogger);
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
      public TsiHandshaker newHandshaker(
          @Nullable String authority, ChannelLogger negotiationLogger) {
        assert authority == null;
        return AltsTsiHandshaker.newServer(
            HandshakerServiceGrpc.newStub(lazyHandshakerChannel.get()),
            new AltsHandshakerOptions(RpcProtocolVersionsUtil.getRpcProtocolVersions()),
            negotiationLogger);
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
      ChannelLogger negotiationLogger = grpcHandler.getNegotiationLogger();
      TsiHandshaker handshaker =
          handshakerFactory.newHandshaker(/* authority= */ null, negotiationLogger);
      NettyTsiHandshaker nettyHandshaker = new NettyTsiHandshaker(handshaker);
      ChannelHandler gnh = InternalProtocolNegotiators.grpcNegotiationHandler(grpcHandler);
      ChannelHandler thh = new TsiHandshakeHandler(
          gnh, nettyHandshaker, new AltsHandshakeValidator(), handshakeSemaphore,
          negotiationLogger);
      ChannelHandler wuah = InternalProtocolNegotiators.waitUntilActiveHandler(thh,
          negotiationLogger);
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
      implements InternalProtocolNegotiator.ClientFactory {
    @VisibleForTesting
    @Nullable
    static Attributes.Key<String> clusterNameAttrKey = loadClusterNameAttrKey();
    private final ImmutableList<String> targetServiceAccounts;
    private final ObjectPool<Channel> handshakerChannelPool;
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
      this.handshakerChannelPool = checkNotNull(handshakerChannelPool, "handshakerChannelPool");
      this.sslContext = checkNotNull(sslContext, "sslContext");
    }

    @Override
    public ProtocolNegotiator newNegotiator() {
      return new GoogleDefaultProtocolNegotiator(
          targetServiceAccounts,
          handshakerChannelPool,
          sslContext,
          clusterNameAttrKey);
    }

    @Override
    public int getDefaultPort() {
      return 443;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static Attributes.Key<String> loadClusterNameAttrKey() {
      Attributes.Key<String> key = null;
      try {
        Class<?> klass = Class.forName("io.grpc.xds.InternalXdsAttributes");
        key = (Attributes.Key<String>) klass.getField("ATTR_CLUSTER_NAME").get(null);
      } catch (ClassNotFoundException e) {
        logger.log(Level.FINE,
            "Unable to load xDS endpoint cluster name key, this may be expected", e);
      } catch (NoSuchFieldException e) {
        logger.log(Level.FINE,
            "Unable to load xDS endpoint cluster name key, this may be expected", e);
      } catch (IllegalAccessException e) {
        logger.log(Level.FINE,
            "Unable to load xDS endpoint cluster name key, this may be expected", e);
      }
      return key;
    }
  }

  private static final class GoogleDefaultProtocolNegotiator implements ProtocolNegotiator {
    private final TsiHandshakerFactory handshakerFactory;
    private final LazyChannel lazyHandshakerChannel;
    private final SslContext sslContext;
    @Nullable
    private final Attributes.Key<String> clusterNameAttrKey;

    GoogleDefaultProtocolNegotiator(
        ImmutableList<String> targetServiceAccounts,
        ObjectPool<Channel> handshakerChannelPool,
        SslContext sslContext,
        @Nullable Attributes.Key<String> clusterNameAttrKey) {
      this.lazyHandshakerChannel = new LazyChannel(handshakerChannelPool);
      this.handshakerFactory =
          new ClientTsiHandshakerFactory(targetServiceAccounts, lazyHandshakerChannel);
      this.sslContext = checkNotNull(sslContext, "checkNotNull");
      this.clusterNameAttrKey = clusterNameAttrKey;
    }

    @Override
    public AsciiString scheme() {
      return SCHEME;
    }

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      ChannelHandler gnh = InternalProtocolNegotiators.grpcNegotiationHandler(grpcHandler);
      ChannelLogger negotiationLogger = grpcHandler.getNegotiationLogger();
      ChannelHandler securityHandler;
      boolean isXdsDirectPath = false;
      if (clusterNameAttrKey != null) {
        isXdsDirectPath = isDirectPathCluster(
            grpcHandler.getEagAttributes().get(clusterNameAttrKey));
      }
      if (grpcHandler.getEagAttributes().get(GrpclbConstants.ATTR_LB_ADDR_AUTHORITY) != null
          || grpcHandler.getEagAttributes().get(GrpclbConstants.ATTR_LB_PROVIDED_BACKEND) != null
          || isXdsDirectPath) {
        TsiHandshaker handshaker =
            handshakerFactory.newHandshaker(grpcHandler.getAuthority(), negotiationLogger); 
        NettyTsiHandshaker nettyHandshaker = new NettyTsiHandshaker(handshaker);
        securityHandler = new TsiHandshakeHandler(
            gnh, nettyHandshaker, new AltsHandshakeValidator(), handshakeSemaphore,
            negotiationLogger);
      } else {
        securityHandler = InternalProtocolNegotiators.clientTlsHandler(
            gnh, sslContext, grpcHandler.getAuthority(), negotiationLogger);
      }
      ChannelHandler wuah = InternalProtocolNegotiators.waitUntilActiveHandler(securityHandler,
          negotiationLogger);
      return wuah;
    }

    private boolean isDirectPathCluster(String clusterName) {
      if (clusterName == null) {
        return false;
      }
      if (clusterName.startsWith(DIRECT_PATH_SERVICE_CFE_CLUSTER_PREFIX)) {
        return false;
      }
      if (!clusterName.startsWith("xdstp:")) {
        return true;
      }
      try {
        URI uri = new URI(clusterName);
        // If authority AND path match our CFE checks, use TLS; otherwise use ALTS.
        return !CFE_CLUSTER_AUTHORITY_NAME.equals(uri.getHost())
            || !uri.getPath().startsWith(CFE_CLUSTER_RESOURCE_NAME_PREFIX);
      } catch (URISyntaxException e) {
        return true; // Shouldn't happen, but assume ALTS.
      }
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
    public TsiHandshaker newHandshaker(
        @Nullable String authority, ChannelLogger negotiationLogger) {
      AltsClientOptions handshakerOptions =
          new AltsClientOptions.Builder()
              .setRpcProtocolVersions(RpcProtocolVersionsUtil.getRpcProtocolVersions())
              .setTargetServiceAccounts(targetServiceAccounts)
              .setTargetName(authority)
              .build();
      return AltsTsiHandshaker.newClient(
          HandshakerServiceGrpc.newStub(lazyHandshakerChannel.get()),
          handshakerOptions,
          negotiationLogger);
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
      AltsInternalContext altsContext = (AltsInternalContext) peerObject;
      // Checks peer Rpc Protocol Versions in the ALTS auth context. Fails the connection if
      // Rpc Protocol Versions mismatch.
      RpcVersionsCheckResult checkResult =
          RpcProtocolVersionsUtil.checkRpcProtocolVersions(
              RpcProtocolVersionsUtil.getRpcProtocolVersions(),
              altsContext.getPeerRpcVersions());
      if (!checkResult.getResult()) {
        String errorMessage =
            "Local Rpc Protocol Versions "
                + RpcProtocolVersionsUtil.getRpcProtocolVersions()
                + " are not compatible with peer Rpc Protocol Versions "
                + altsContext.getPeerRpcVersions();
        throw Status.UNAVAILABLE.withDescription(errorMessage).asRuntimeException();
      }
      return new SecurityDetails(
          SecurityLevel.PRIVACY_AND_INTEGRITY,
          new Security(new OtherSecurity("alts", Any.pack(altsContext.context))));
    }
  }

  @VisibleForTesting
  static int getAltsMaxConcurrentHandshakes(String altsMaxConcurrentHandshakes) {
    if (altsMaxConcurrentHandshakes == null) {
      return DEFAULT_ALTS_MAX_CONCURRENT_HANDSHAKES;
    }
    try {
      int effectiveMaxConcurrentHandshakes = Integer.parseInt(altsMaxConcurrentHandshakes);
      if (effectiveMaxConcurrentHandshakes < 0) {
        logger.warning(
            "GRPC_ALTS_MAX_CONCURRENT_HANDSHAKES environment variable set to invalid value.");
        return DEFAULT_ALTS_MAX_CONCURRENT_HANDSHAKES;
      }
      return effectiveMaxConcurrentHandshakes;
    } catch (NumberFormatException e) {
      logger.warning(
          "GRPC_ALTS_MAX_CONCURRENT_HANDSHAKES environment variable set to invalid value.");
      return DEFAULT_ALTS_MAX_CONCURRENT_HANDSHAKES;
    }
  }

  private static int getAltsMaxConcurrentHandshakes() {
    return getAltsMaxConcurrentHandshakes(
        System.getenv(ALTS_MAX_CONCURRENT_HANDSHAKES_ENV_VARIABLE));
  }

  private AltsProtocolNegotiator() {}
}
