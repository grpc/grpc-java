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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.UInt32Value;
import io.grpc.Internal;
import io.grpc.Status;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.EnvoyServerProtoData.CidrRange;
import io.grpc.xds.EnvoyServerProtoData.FilterChain;
import io.grpc.xds.EnvoyServerProtoData.FilterChainMatch;
import io.grpc.xds.internal.Matchers.CidrMatcher;
import io.grpc.xds.internal.sds.SslContextProviderSupplier;
import io.netty.channel.Channel;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Serves as a wrapper for {@link XdsClient} used on the server side by {@link
 * XdsServerBuilder}.
 */
@Internal
public final class XdsClientWrapperForServerSds {
  private static final Logger logger =
      Logger.getLogger(XdsClientWrapperForServerSds.class.getName());

  private AtomicReference<EnvoyServerProtoData.Listener> curListener = new AtomicReference<>();
  private ObjectPool<XdsClient> xdsClientPool;
  private final XdsNameResolverProvider.XdsClientPoolFactory xdsClientPoolFactory;
  @Nullable private XdsClient xdsClient;
  private final int port;
  private XdsClient.LdsResourceWatcher listenerWatcher;
  private boolean newServerApi;
  private String grpcServerResourceId;
  @VisibleForTesting final Set<ServerWatcher> serverWatchers = new HashSet<>();

  /**
   * Creates a {@link XdsClientWrapperForServerSds}.
   *
   * @param port server's port for which listener config is needed.
   */
  XdsClientWrapperForServerSds(int port) {
    this(port, SharedXdsClientPoolProvider.getDefaultProvider());
  }

  @VisibleForTesting
  XdsClientWrapperForServerSds(int port,
      XdsNameResolverProvider.XdsClientPoolFactory xdsClientPoolFactory) {
    this.port = port;
    this.xdsClientPoolFactory = checkNotNull(xdsClientPoolFactory, "xdsClientPoolFactory");
  }

  @VisibleForTesting XdsClient getXdsClient() {
    return xdsClient;
  }

  public TlsContextManager getTlsContextManager() {
    return xdsClient.getTlsContextManager();
  }

  /** Accepts an XdsClient and starts a watch. */
  @VisibleForTesting
  public void start() {
    try {
      xdsClientPool = xdsClientPoolFactory.getOrCreate();
    } catch (Exception e) {
      reportError(e, true);
      return;
    }
    xdsClient = xdsClientPool.getObject();
    this.listenerWatcher =
        new XdsClient.LdsResourceWatcher() {
          @Override
          public void onChanged(XdsClient.LdsUpdate update) {
            releaseOldSuppliers(curListener.getAndSet(update.listener()));
            reportSuccess();
          }

          @Override
          public void onResourceDoesNotExist(String resourceName) {
            logger.log(Level.WARNING, "Resource {0} is unavailable", resourceName);
            releaseOldSuppliers(curListener.getAndSet(null));
            reportError(Status.NOT_FOUND.asException(), true);
          }

          @Override
          public void onError(Status error) {
            logger.log(
                Level.WARNING, "LdsResourceWatcher in XdsClientWrapperForServerSds: {0}", error);
            if (isResourceAbsent(error)) {
              releaseOldSuppliers(curListener.getAndSet(null));
              reportError(error.asException(), true);
            } else {
              reportError(error.asException(), false);
            }
          }
        };
    newServerApi = xdsClient.getBootstrapInfo().getServers().get(0).isUseProtocolV3();
    if (!newServerApi) {
      reportError(
          new XdsInitializationException(
              "requires use of xds_v3 in xds bootstrap"),
          true);
      return;
    }
    grpcServerResourceId = xdsClient.getBootstrapInfo()
        .getServerListenerResourceNameTemplate();
    if (grpcServerResourceId == null) {
      reportError(
          new XdsInitializationException(
              "missing server_listener_resource_name_template value in xds bootstrap"),
          true);
      return;
    }
    grpcServerResourceId = grpcServerResourceId.replaceAll("%s", "0.0.0.0:" + port);
    xdsClient.watchLdsResource(grpcServerResourceId, listenerWatcher);
  }

  // go thru the old listener and release all the old SslContextProviderSupplier
  private void releaseOldSuppliers(EnvoyServerProtoData.Listener oldListener) {
    if (oldListener != null) {
      List<FilterChain> filterChains = oldListener.getFilterChains();
      for (FilterChain filterChain : filterChains) {
        releaseSupplier(filterChain);
      }
      releaseSupplier(oldListener.getDefaultFilterChain());
    }
  }

  private static void releaseSupplier(FilterChain filterChain) {
    if (filterChain != null) {
      SslContextProviderSupplier sslContextProviderSupplier =
          filterChain.getSslContextProviderSupplier();
      if (sslContextProviderSupplier != null) {
        sslContextProviderSupplier.close();
      }
    }
  }

  /** Whether the throwable indicates our listener resource is absent/deleted. */
  private static boolean isResourceAbsent(Status status) {
    Status.Code code  = status.getCode();
    switch (code) {
      case NOT_FOUND:
      case INVALID_ARGUMENT:
      case PERMISSION_DENIED:  // means resource not available for us
      case UNIMPLEMENTED:
      case UNAUTHENTICATED:  // same as above, resource not available for us
        return true;
      default:
        return false;
    }
  }

  /**
   * Locates the best matching FilterChain to the channel from the current listener and if found
   * returns the SslContextProviderSupplier from that FilterChain, else null.
   */
  @Nullable
  public SslContextProviderSupplier getSslContextProviderSupplier(Channel channel) {
    EnvoyServerProtoData.Listener copyListener = curListener.get();
    if (copyListener != null && channel != null) {
      SocketAddress localAddress = channel.localAddress();
      SocketAddress remoteAddress = channel.remoteAddress();
      if (localAddress instanceof InetSocketAddress && remoteAddress instanceof InetSocketAddress) {
        InetSocketAddress localInetAddr = (InetSocketAddress) localAddress;
        InetSocketAddress remoteInetAddr = (InetSocketAddress) remoteAddress;
        checkState(
            port == localInetAddr.getPort(),
            "Channel localAddress port does not match requested listener port");
        return getSslContextProviderSupplier(localInetAddr, remoteInetAddr, copyListener);
      }
    }
    return null;
  }

  /**
   * Using the logic specified at
   * https://www.envoyproxy.io/docs/envoy/latest/api-v2/api/v2/listener/listener_components.proto.html?highlight=filter%20chain#listener-filterchainmatch
   * locate a matching filter and return the corresponding SslContextProviderSupplier or else
   * return one from default filter chain.
   *
   * @param localInetAddr dest address of the inbound connection
   * @param remoteInetAddr source address of the inbound connection
   */
  private static SslContextProviderSupplier getSslContextProviderSupplier(
      InetSocketAddress localInetAddr, InetSocketAddress remoteInetAddr,
      EnvoyServerProtoData.Listener listener) {
    List<FilterChain> filterChains = listener.getFilterChains();

    filterChains = filterOnDestinationPort(filterChains);
    filterChains = filterOnIpAddress(filterChains, localInetAddr.getAddress(), true);
    filterChains = filterOnServerNames(filterChains);
    filterChains = filterOnTransportProtocol(filterChains);
    filterChains = filterOnApplicationProtocols(filterChains);
    filterChains =
        filterOnSourceType(filterChains, remoteInetAddr.getAddress(), localInetAddr.getAddress());
    filterChains = filterOnIpAddress(filterChains, remoteInetAddr.getAddress(), false);
    filterChains = filterOnSourcePort(filterChains, remoteInetAddr.getPort());

    if (filterChains.size() > 1) {
      // close the connection
      throw new IllegalStateException("Found 2 matching filter-chains");
    } else if (filterChains.size() == 1) {
      return filterChains.get(0).getSslContextProviderSupplier();
    }
    if (listener.getDefaultFilterChain() == null) {
      // close the connection
      throw new RuntimeException(
          "no matching filter chain. local: " + localInetAddr + " remote: " + remoteInetAddr);
    }
    return listener.getDefaultFilterChain().getSslContextProviderSupplier();
  }

  // reject if filer-chain-match has non-empty application_protocols
  private static List<FilterChain> filterOnApplicationProtocols(List<FilterChain> filterChains) {
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
  private static List<FilterChain> filterOnTransportProtocol(List<FilterChain> filterChains) {
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
  private static List<FilterChain> filterOnServerNames(List<FilterChain> filterChains) {
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
  private static List<FilterChain> filterOnDestinationPort(List<FilterChain> filterChains) {
    ArrayList<FilterChain> filtered = new ArrayList<>(filterChains.size());
    for (FilterChain filterChain : filterChains) {
      FilterChainMatch filterChainMatch = filterChain.getFilterChainMatch();

      if (filterChainMatch.getDestinationPort() == UInt32Value.getDefaultInstance().getValue()) {
        filtered.add(filterChain);
      }
    }
    return filtered;
  }

  private static List<FilterChain> filterOnSourcePort(
      List<FilterChain> filterChains, int sourcePort) {
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

  private static List<FilterChain> filterOnSourceType(
      List<FilterChain> filterChains, InetAddress sourceAddress, InetAddress destAddress) {
    ArrayList<FilterChain> filtered = new ArrayList<>(filterChains.size());
    for (FilterChain filterChain : filterChains) {
      FilterChainMatch filterChainMatch = filterChain.getFilterChainMatch();
      EnvoyServerProtoData.ConnectionSourceType sourceType =
          filterChainMatch.getConnectionSourceType();

      boolean matching = false;
      if (sourceType == EnvoyServerProtoData.ConnectionSourceType.SAME_IP_OR_LOOPBACK) {
        matching =
            sourceAddress.isLoopbackAddress()
                || sourceAddress.isAnyLocalAddress()
                || sourceAddress.equals(destAddress);
      } else if (sourceType == EnvoyServerProtoData.ConnectionSourceType.EXTERNAL) {
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
  private static List<FilterChain> filterOnIpAddress(
      List<FilterChain> filterChains, InetAddress address, boolean forDestination) {
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

  /** Adds a {@link ServerWatcher} to the list. */
  public void addServerWatcher(ServerWatcher serverWatcher) {
    checkNotNull(serverWatcher, "serverWatcher");
    synchronized (serverWatchers) {
      serverWatchers.add(serverWatcher);
    }
    EnvoyServerProtoData.Listener copyListener = curListener.get();
    if (copyListener != null) {
      serverWatcher.onListenerUpdate();
    }
  }

  /** Removes a {@link ServerWatcher} from the list. */
  public void removeServerWatcher(ServerWatcher serverWatcher) {
    checkNotNull(serverWatcher, "serverWatcher");
    synchronized (serverWatchers) {
      serverWatchers.remove(serverWatcher);
    }
  }

  private Set<ServerWatcher> getServerWatchers() {
    synchronized (serverWatchers) {
      return ImmutableSet.copyOf(serverWatchers);
    }
  }

  private void reportError(Throwable throwable, boolean isAbsent) {
    for (ServerWatcher watcher : getServerWatchers()) {
      watcher.onError(throwable, isAbsent);
    }
  }

  private void reportSuccess() {
    for (ServerWatcher watcher : getServerWatchers()) {
      watcher.onListenerUpdate();
    }
  }

  @VisibleForTesting
  public XdsClient.LdsResourceWatcher getListenerWatcher() {
    return listenerWatcher;
  }

  /** Watcher interface for the clients of this class. */
  public interface ServerWatcher {

    /** Called to report errors from the control plane including "not found". */
    void onError(Throwable throwable, boolean isAbsent);

    /** Called to report successful receipt of listener config. */
    void onListenerUpdate();
  }

  /** Shutdown this instance and release resources. */
  public void shutdown() {
    logger.log(Level.FINER, "Shutdown");
    if (xdsClient != null) {
      xdsClient.cancelLdsResourceWatch(grpcServerResourceId, listenerWatcher);
      xdsClient = xdsClientPool.returnObject(xdsClient);
    }
    releaseOldSuppliers(curListener.getAndSet(null));
  }
}
