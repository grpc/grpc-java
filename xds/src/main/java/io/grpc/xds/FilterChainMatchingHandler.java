/*
 * Copyright 2021 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.xds.XdsServerWrapper.ATTR_SERVER_ROUTING_CONFIG;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.protobuf.UInt32Value;
import io.grpc.Attributes;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.InternalProtocolNegotiationEvent;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.ProtocolNegotiationEvent;
import io.grpc.xds.EnvoyServerProtoData.CidrRange;
import io.grpc.xds.EnvoyServerProtoData.ConnectionSourceType;
import io.grpc.xds.EnvoyServerProtoData.FilterChain;
import io.grpc.xds.EnvoyServerProtoData.FilterChainMatch;
import io.grpc.xds.XdsServerWrapper.ServerRoutingConfig;
import io.grpc.xds.internal.Matchers.CidrMatcher;
import io.grpc.xds.internal.sds.SdsProtocolNegotiators.ServerSdsHandler;
import io.grpc.xds.internal.sds.SslContextProviderSupplier;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.cert.CertStoreException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;


/**
 * Handles L4 filter chain match for the connection based on the xds configuration.
 * */
public final class FilterChainMatchingHandler extends ChannelInboundHandlerAdapter {
  private final GrpcHttp2ConnectionHandler grpcHandler;
  private final FilterChainSelector selector;
  @Nullable
  private final ProtocolNegotiator fallbackProtocolNegotiator;

  /**
   * Selects the filter chain using the selector configuration.
   * */
  public FilterChainMatchingHandler(
          GrpcHttp2ConnectionHandler grpcHandler, FilterChainSelector selector,
          @Nullable ProtocolNegotiator fallbackProtocolNegotiator) {
    this.grpcHandler = checkNotNull(grpcHandler, "grpcHandler");
    this.selector = checkNotNull(selector, "selector");
    this.fallbackProtocolNegotiator = fallbackProtocolNegotiator;
  }

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    if (!(evt instanceof ProtocolNegotiationEvent)) {
      super.userEventTriggered(ctx, evt);
      return;
    }
    FilteredConfig config = selector.select(
            (InetSocketAddress) ctx.channel().localAddress(),
            (InetSocketAddress) ctx.channel().remoteAddress());
    if (config == null) {
      throw new IllegalStateException("not filter chain");
    }
    ProtocolNegotiationEvent pne = InternalProtocolNegotiationEvent.getDefault();
    Attributes attr = InternalProtocolNegotiationEvent.getAttributes(pne)
            .toBuilder().set(ATTR_SERVER_ROUTING_CONFIG, config.routingConfig).build();
    pne = InternalProtocolNegotiationEvent.withAttributes(pne, attr);
    SslContextProviderSupplier sslContextProviderSupplier = config.sslContextProviderSupplier;

    ChannelHandler handler;
    if (sslContextProviderSupplier == null) {
      if (fallbackProtocolNegotiator == null) {
        ctx.fireExceptionCaught(new CertStoreException("No certificate source found!"));
        return;
      }
      handler = fallbackProtocolNegotiator.newHandler(grpcHandler);
    } else {
      handler = new ServerSdsHandler(grpcHandler, sslContextProviderSupplier);
    }
    ctx.pipeline().replace(this, null, handler);
    ctx.fireUserEventTriggered(pne);
  }

  public static final class FilterChainSelector {
    public static final FilterChainSelector NO_FILTER_CHAIN = new FilterChainSelector(
            Collections.<FilterChain, ServerRoutingConfig>emptyMap(), null, null);

    private final Map<FilterChain, ServerRoutingConfig> routingConfigs;
    @Nullable
    private final SslContextProviderSupplier defaultSslContextProviderSupplier;
    @Nullable
    private final ServerRoutingConfig defaultRoutingConfig;

    FilterChainSelector(Map<FilterChain, ServerRoutingConfig> routingConfigs,
                        @Nullable SslContextProviderSupplier defaultSslContextProviderSupplier,
                        @Nullable ServerRoutingConfig defaultRoutingConfig) {
      this.routingConfigs = checkNotNull(routingConfigs, "routingConfigs");
      this.defaultSslContextProviderSupplier = defaultSslContextProviderSupplier;
      this.defaultRoutingConfig = defaultRoutingConfig;
    }

    @VisibleForTesting
    Map<FilterChain, ServerRoutingConfig> getRoutingConfigs() {
      return routingConfigs;
    }

    @VisibleForTesting
    ServerRoutingConfig getDefaultRoutingConfig() {
      return defaultRoutingConfig;
    }

    @VisibleForTesting
    SslContextProviderSupplier getDefaultSslContextProviderSupplier() {
      return defaultSslContextProviderSupplier;
    }

    /**
     * returns null means we should close the connection.
     */
    @Nullable
    public FilteredConfig select(InetSocketAddress localAddr, InetSocketAddress remoteAddr) {
      Collection<FilterChain> filterChains = routingConfigs.keySet();
      filterChains = filterOnDestinationPort(filterChains);
      filterChains = filterOnIpAddress(filterChains, localAddr.getAddress(), true);
      filterChains = filterOnServerNames(filterChains);
      filterChains = filterOnTransportProtocol(filterChains);
      filterChains = filterOnApplicationProtocols(filterChains);
      filterChains =
              filterOnSourceType(filterChains, remoteAddr.getAddress(), localAddr.getAddress());
      filterChains = filterOnIpAddress(filterChains, remoteAddr.getAddress(), false);
      filterChains = filterOnSourcePort(filterChains, remoteAddr.getPort());

      if (filterChains.size() > 1) {
        // TODO(chengyuanzhang): should we just return any matched one?
        return null;
      }
      if (filterChains.size() == 1) {
        FilterChain selected = Iterables.getOnlyElement(filterChains);
        return new FilteredConfig(
                routingConfigs.get(selected), selected.getSslContextProviderSupplier());
      }
      if (defaultRoutingConfig != null) {
        return new FilteredConfig(defaultRoutingConfig, defaultSslContextProviderSupplier);
      }
      return null;
    }

    // reject if filer-chain-match has non-empty application_protocols
    private static Collection<FilterChain> filterOnApplicationProtocols(
            Collection<FilterChain> filterChains) {
      ArrayList<FilterChain> filtered = new ArrayList<>(filterChains.size());
      for (FilterChain filterChain : filterChains) {
        FilterChainMatch filterChainMatch = filterChain.getFilterChainMatch();

        if (filterChainMatch.getApplicationProtocols().isEmpty()) {
          filtered.add(filterChain);
        }
      }
      return filtered;
    }

    // reject if filer-chain-match has non-empty transport protocol other than "raw_buffer"
    private static Collection<FilterChain> filterOnTransportProtocol(
                Collection<FilterChain> filterChains) {
      ArrayList<FilterChain> filtered = new ArrayList<>(filterChains.size());
      for (FilterChain filterChain : filterChains) {
        FilterChainMatch filterChainMatch = filterChain.getFilterChainMatch();

        String transportProtocol = filterChainMatch.getTransportProtocol();
        if ( Strings.isNullOrEmpty(transportProtocol) || "raw_buffer".equals(transportProtocol)) {
          filtered.add(filterChain);
        }
      }
      return filtered;
    }

    // reject if filer-chain-match has server_name(s)
    private static Collection<FilterChain> filterOnServerNames(
                Collection<FilterChain> filterChains) {
      ArrayList<FilterChain> filtered = new ArrayList<>(filterChains.size());
      for (FilterChain filterChain : filterChains) {
        FilterChainMatch filterChainMatch = filterChain.getFilterChainMatch();

        if (filterChainMatch.getServerNames().isEmpty()) {
          filtered.add(filterChain);
        }
      }
      return filtered;
    }

    // destination_port present => Always fail match
    private static Collection<FilterChain> filterOnDestinationPort(
                Collection<FilterChain> filterChains) {
      ArrayList<FilterChain> filtered = new ArrayList<>(filterChains.size());
      for (FilterChain filterChain : filterChains) {
        FilterChainMatch filterChainMatch = filterChain.getFilterChainMatch();

        if (filterChainMatch.getDestinationPort() == UInt32Value.getDefaultInstance().getValue()) {
          filtered.add(filterChain);
        }
      }
      return filtered;
    }

    private static Collection<FilterChain> filterOnSourcePort(
                Collection<FilterChain> filterChains, int sourcePort) {
      ArrayList<FilterChain> filteredOnMatch = new ArrayList<>(filterChains.size());
      ArrayList<FilterChain> filteredOnEmpty = new ArrayList<>(filterChains.size());
      for (FilterChain filterChain : filterChains) {
        FilterChainMatch filterChainMatch = filterChain.getFilterChainMatch();

        List<Integer> sourcePortsToMatch = filterChainMatch.getSourcePorts();
        if (sourcePortsToMatch.isEmpty()) {
          filteredOnEmpty.add(filterChain);
        } else if (sourcePortsToMatch.contains(sourcePort)) {
          filteredOnMatch.add(filterChain);
        }
      }
      // match against source port is more specific than match against empty list
      return filteredOnMatch.isEmpty() ? filteredOnEmpty : filteredOnMatch;
    }

    private static Collection<FilterChain> filterOnSourceType(
                Collection<FilterChain> filterChains, InetAddress sourceAddress,
                InetAddress destAddress) {
      ArrayList<FilterChain> filtered = new ArrayList<>(filterChains.size());
      for (FilterChain filterChain : filterChains) {
        FilterChainMatch filterChainMatch = filterChain.getFilterChainMatch();
        ConnectionSourceType sourceType =
                          filterChainMatch.getConnectionSourceType();

        boolean matching = false;
        if (sourceType == ConnectionSourceType.SAME_IP_OR_LOOPBACK) {
          matching =
            sourceAddress.isLoopbackAddress()
            || sourceAddress.isAnyLocalAddress()
            || sourceAddress.equals(destAddress);
        } else if (sourceType == ConnectionSourceType.EXTERNAL) {
          matching = !sourceAddress.isLoopbackAddress() && !sourceAddress.isAnyLocalAddress();
        } else { // ANY or null
          matching = true;
        }
        if (matching) {
          filtered.add(filterChain);
        }
      }
      return filtered;
    }

    private static int getMatchingPrefixLength(
            FilterChainMatch filterChainMatch, InetAddress address, boolean forDestination) {
      boolean isIPv6 = address instanceof Inet6Address;
      List<CidrRange> cidrRanges =
              forDestination
                      ? filterChainMatch.getPrefixRanges()
                      : filterChainMatch.getSourcePrefixRanges();
      int matchingPrefixLength;
      if (cidrRanges.isEmpty()) { // if there is no CidrRange assume 0-length match
        matchingPrefixLength = 0;
      } else {
        matchingPrefixLength = -1;
        for (CidrRange cidrRange : cidrRanges) {
          InetAddress cidrAddr = cidrRange.getAddressPrefix();
          boolean cidrIsIpv6 = cidrAddr instanceof Inet6Address;
          if (isIPv6 == cidrIsIpv6) {
            int prefixLen = cidrRange.getPrefixLen();
            CidrMatcher matcher = CidrMatcher.create(cidrAddr, prefixLen);
            if (matcher.matches(address) && prefixLen > matchingPrefixLength) {
              matchingPrefixLength = prefixLen;
            }
          }
        }
      }
      return matchingPrefixLength;
    }

    // use prefix_ranges (CIDR) and get the most specific matches
    private static Collection<FilterChain> filterOnIpAddress(
            Collection<FilterChain> filterChains, InetAddress address, boolean forDestination) {
      // curent list of top ones
      ArrayList<FilterChain> topOnes = new ArrayList<>(filterChains.size());
      int topMatchingPrefixLen = -1;
      for (FilterChain filterChain : filterChains) {
        int currentMatchingPrefixLen =
            getMatchingPrefixLength(filterChain.getFilterChainMatch(), address, forDestination);

        if (currentMatchingPrefixLen >= 0) {
          if (currentMatchingPrefixLen < topMatchingPrefixLen) {
            continue;
          }
          if (currentMatchingPrefixLen > topMatchingPrefixLen) {
            topMatchingPrefixLen = currentMatchingPrefixLen;
            topOnes.clear();
          }
          topOnes.add(filterChain);
        }
      }
      return topOnes;
    }
  }

  /**
   * The FilterChain level configuration.
   */
  public static final class FilteredConfig {
    private final ServerRoutingConfig routingConfig;
    @Nullable
    private final SslContextProviderSupplier sslContextProviderSupplier;

    private FilteredConfig(ServerRoutingConfig routingConfig,
                           @Nullable SslContextProviderSupplier sslContextProviderSupplier) {
      this.routingConfig = checkNotNull(routingConfig, "routingConfig");
      this.sslContextProviderSupplier = sslContextProviderSupplier;
    }
  }
}




