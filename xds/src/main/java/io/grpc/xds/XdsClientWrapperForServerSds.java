/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.xds;

import com.google.common.annotations.VisibleForTesting;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.Internal;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.SharedResourceHolder;
import io.grpc.xds.EnvoyServerProtoData.CidrRange;
import io.grpc.xds.EnvoyServerProtoData.FilterChain;
import io.grpc.xds.EnvoyServerProtoData.FilterChainMatch;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Serves as a wrapper for {@link XdsClientImpl} used on the server side by {@link
 * io.grpc.xds.internal.sds.XdsServerBuilder}.
 */
@Internal
public final class XdsClientWrapperForServerSds {
  private static final Logger logger =
      Logger.getLogger(XdsClientWrapperForServerSds.class.getName());

  private static final EventLoopGroupResource eventLoopGroupResource =
      Epoll.isAvailable() ? new EventLoopGroupResource("GrpcServerXdsClient") : null;

  private EnvoyServerProtoData.Listener curListener;
  // TODO(sanjaypujare): implement shutting down XdsServer which will need xdsClient reference
  @SuppressWarnings("unused")
  @Nullable private XdsClient xdsClient;
  private final int port;

  /** Creates a new instance. */
  public static XdsClientWrapperForServerSds newInstance(
      int port, Bootstrapper bootstrapper, SynchronizationContext syncContext) throws IOException {
    Bootstrapper.BootstrapInfo bootstrapInfo = bootstrapper.readBootstrap();
    final List<Bootstrapper.ServerInfo> serverList = bootstrapInfo.getServers();
    if (serverList.isEmpty()) {
      throw new NoSuchElementException("No management server provided by bootstrap");
    }
    final Node node = bootstrapInfo.getNode();
    if (eventLoopGroupResource == null) {
      throw new IllegalStateException(Epoll.unavailabilityCause());
    }
    final EventLoopGroup timeService = SharedResourceHolder.get(eventLoopGroupResource);
    XdsClientImpl xdsClientImpl =
        new XdsClientImpl(
            "",
            serverList,
            XdsClient.XdsChannelFactory.getInstance(),
            node,
            syncContext,
            timeService,
            new ExponentialBackoffPolicy.Provider(),
            GrpcUtil.STOPWATCH_SUPPLIER);
    return new XdsClientWrapperForServerSds(port, xdsClientImpl);
  }

  @VisibleForTesting
  XdsClientWrapperForServerSds(int port, XdsClient xdsClient) {
    this.port = port;
    this.xdsClient = xdsClient;
    xdsClient.watchListenerData(
        port,
        new XdsClient.ListenerWatcher() {
          @Override
          public void onListenerChanged(XdsClient.ListenerUpdate update) {
            logger.log(
                Level.INFO,
                "Setting myListener from ConfigUpdate listener :{0}",
                update.getListener().toString());
            curListener = update.getListener();
          }

          @Override
          public void onError(Status error) {
            // In order to distinguish between IO error and resource not found, set curListener
            // to null in case of NOT_FOUND
            if (error.getCode().equals(Status.Code.NOT_FOUND)) {
              curListener = null;
            }
            // TODO(sanjaypujare): Implement logic for other cases based on final design.
            logger.log(Level.SEVERE, "ListenerWatcher in XdsClientWrapperForServerSds:{0}", error);
          }
        });
  }

  /**
   * Locates the best matching FilterChain to the channel from the current listener and returns the
   * DownstreamTlsContext from that FilterChain.
   */
  public DownstreamTlsContext getDownstreamTlsContext(Channel channel) {
    if (curListener != null && channel != null) {
      SocketAddress localAddress = channel.localAddress();
      if (!(localAddress instanceof InetSocketAddress)) {
        return null;
      }
      InetSocketAddress localInetAddr = (InetSocketAddress) localAddress;
      if (port != localInetAddr.getPort()) {
        throw new IllegalStateException(
            "Channel localAddress port does not match requested listener port");
      }
      List<FilterChain> filterChains = curListener.getFilterChains();
      int highestScore = -1;
      FilterChain bestMatch = null;
      for (FilterChain filterChain : filterChains) {
        int curScore = getFilterChainMatchScore(filterChain.getFilterChainMatch(), localInetAddr);
        if (curScore > 0 && curScore > highestScore) {
          bestMatch = filterChain;
          highestScore = curScore;
        }
      }
      if (bestMatch != null) {
        return bestMatch.getDownstreamTlsContext();
      }
    }
    return null;
  }

  /**
   * Computes a score for a match of filterChainMatch with channel.
   *
   * <p>-1 => mismatch (of port or IP address etc)
   *
   * <p>0 => unimplemented (types or logic etc)
   *
   * <p>1 => filterChainMatch is null, so nothing to match.
   *
   * <p>value > 2 indicates some kind of IP address match.
   */
  private static int getFilterChainMatchScore(
      FilterChainMatch filterChainMatch, InetSocketAddress localInetAddr) {
    if (filterChainMatch == null) {
      return 1;
    }
    if (filterChainMatch.getDestinationPort() != localInetAddr.getPort()) {
      return -1;
    }
    return getIpAddressAndRangesMatchScore(
        localInetAddr.getAddress(), filterChainMatch.getPrefixRanges());
  }

  /**
   * Computes a score for IP address match against a list of CidrRange called only after ports have
   * matched, so a minimum score of 2 is returned to indicate a match or else -1 for a mismatch.
   *
   * <p>-1 => mismatch.
   *
   * <p>2 => prefixRanges is null/empty, so no IP address to match.
   *
   * <p>value > 2 indicates some kind of IP address match.
   */
  private static int getIpAddressAndRangesMatchScore(
      InetAddress localAddress, List<CidrRange> prefixRanges) {
    if (prefixRanges == null || prefixRanges.isEmpty()) {
      return 2;
    }
    int highestScore = -1;
    for (CidrRange cidrRange : prefixRanges) {
      int curScore = getIpAddressMatchScore(localAddress, cidrRange);
      if (curScore > highestScore) {
        highestScore = curScore;
      }
    }
    return highestScore;
  }

  /**
   * Computes a score for IP address to CidrRange match.
   *
   * <p>-1 => mismatch
   *
   * <p>0 => unimplemented (prefixLen < 32 logic)
   *
   * <p>4 => match because cidrRange is IPANY_ADDRESS (0.0.0.0)
   *
   * <p>8 => exact match
   */
  private static int getIpAddressMatchScore(InetAddress localAddress, CidrRange cidrRange) {
    if (cidrRange.getPrefixLen() == 32) {
      try {
        InetAddress cidrAddr = InetAddress.getByName(cidrRange.getAddressPrefix());
        if (cidrAddr.isAnyLocalAddress()) {
          return 4;
        }
        if (cidrAddr.equals(localAddress)) {
          return 8;
        }
        return -1;
      } catch (UnknownHostException e) {
        logger.log(Level.SEVERE, "cidrRange address parsing", e);
      }
    }
    // TODO(sanjaypujare): implement CIDR logic to match prefixes if needed
    return 0;
  }

  private static final class EventLoopGroupResource
      implements SharedResourceHolder.Resource<EventLoopGroup> {

    private final String name;

    EventLoopGroupResource(String name) {
      this.name = name;
    }

    @Override
    public EventLoopGroup create() {
      // Use Netty's DefaultThreadFactory in order to get the benefit of FastThreadLocal.
      ThreadFactory threadFactory = new DefaultThreadFactory(name, /* daemon= */ true);
      return new EpollEventLoopGroup(1, threadFactory);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    @Override
    public void close(EventLoopGroup instance) {
      try {
        instance.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
      } catch (InterruptedException e) {
        logger.log(Level.SEVERE, "from EventLoopGroup.shutdownGracefully", e);
        Thread.currentThread().interrupt(); // to not "swallow" the exception...
      }
    }
  }
}
