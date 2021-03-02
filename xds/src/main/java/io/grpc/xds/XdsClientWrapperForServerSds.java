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
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.UInt32Value;
import io.grpc.Grpc;
import io.grpc.Internal;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.SharedResourceHolder;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.EnvoyServerProtoData.CidrRange;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.EnvoyServerProtoData.FilterChain;
import io.grpc.xds.EnvoyServerProtoData.FilterChainMatch;
import io.netty.channel.Channel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
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

  private static final TimeServiceResource timeServiceResource =
      new TimeServiceResource("GrpcServerXdsClient");

  private EnvoyServerProtoData.Listener curListener;
  @SuppressWarnings("unused")
  @Nullable private XdsClient xdsClient;
  private final int port;
  private ScheduledExecutorService timeService;
  private XdsClient.ListenerWatcher listenerWatcher;
  private boolean newServerApi;
  @VisibleForTesting final Set<ServerWatcher> serverWatchers = new HashSet<>();

  /**
   * Creates a {@link XdsClientWrapperForServerSds}.
   *
   * @param port server's port for which listener config is needed.
   */
  public XdsClientWrapperForServerSds(int port) {
    this.port = port;
  }

  public boolean hasXdsClient() {
    return xdsClient != null;
  }

  /** Creates an XdsClient and starts a watch. */
  public void createXdsClientAndStart() throws IOException {
    checkState(xdsClient == null, "start() called more than once");
    Bootstrapper.BootstrapInfo bootstrapInfo;
    try {
      bootstrapInfo = new BootstrapperImpl().bootstrap();
      List<Bootstrapper.ServerInfo> serverList = bootstrapInfo.getServers();
      if (serverList.isEmpty()) {
        throw new XdsInitializationException("No management server provided by bootstrap");
      }
    } catch (XdsInitializationException e) {
      throw new IOException(e);
    }
    Node node = bootstrapInfo.getNode();
    Bootstrapper.ServerInfo serverInfo = bootstrapInfo.getServers().get(0);  // use first server
    ManagedChannel channel =
        Grpc.newChannelBuilder(serverInfo.getTarget(), serverInfo.getChannelCredentials())
            .keepAliveTime(5, TimeUnit.MINUTES).build();
    timeService = SharedResourceHolder.get(timeServiceResource);
    newServerApi = serverInfo.isUseProtocolV3();
    String grpcServerResourceId = bootstrapInfo.getGrpcServerResourceId();
    if (newServerApi && grpcServerResourceId == null) {
      throw new IOException("missing grpc_server_resource_name_id value in xds bootstrap");
    }
    XdsClient xdsClientImpl =
        new ServerXdsClient(
            channel,
            serverInfo.isUseProtocolV3(),
            node,
            timeService,
            new ExponentialBackoffPolicy.Provider(),
            GrpcUtil.STOPWATCH_SUPPLIER,
            "0.0.0.0",
            grpcServerResourceId);
    start(xdsClientImpl);
  }

  /** Accepts an XdsClient and starts a watch. */
  @VisibleForTesting
  public void start(XdsClient xdsClient) {
    checkState(this.xdsClient == null, "start() called more than once");
    checkNotNull(xdsClient, "xdsClient");
    this.xdsClient = xdsClient;
    this.listenerWatcher =
        new XdsClient.ListenerWatcher() {
          @Override
          public void onListenerChanged(XdsClient.ListenerUpdate update) {
            curListener = update.getListener();
            reportSuccess();
          }

          @Override
          public void onResourceDoesNotExist(String resourceName) {
            logger.log(Level.WARNING, "Resource {0} is unavailable", resourceName);
            curListener = null;
            reportError(Status.NOT_FOUND.asException(), true);
          }

          @Override
          public void onError(Status error) {
            logger.log(
                Level.WARNING, "ListenerWatcher in XdsClientWrapperForServerSds: {0}", error);
            reportError(error.asException(), isResourceAbsent(error));
          }
        };
    xdsClient.watchListenerData(port, listenerWatcher);
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
   * returns the DownstreamTlsContext from that FilterChain, else null.
   */
  @Nullable
  public DownstreamTlsContext getDownstreamTlsContext(Channel channel) {
    if (curListener != null && channel != null) {
      SocketAddress localAddress = channel.localAddress();
      SocketAddress remoteAddress = channel.remoteAddress();
      if (localAddress instanceof InetSocketAddress && remoteAddress instanceof InetSocketAddress) {
        InetSocketAddress localInetAddr = (InetSocketAddress) localAddress;
        InetSocketAddress remoteInetAddr = (InetSocketAddress) remoteAddress;
        checkState(
            port == localInetAddr.getPort(),
            "Channel localAddress port does not match requested listener port");
        return getDownstreamTlsContext(localInetAddr, remoteInetAddr);
      }
    }
    return null;
  }

  /**
   * Using the logic specified at
   * https://www.envoyproxy.io/docs/envoy/latest/api-v2/api/v2/listener/listener_components.proto.html?highlight=filter%20chain#listener-filterchainmatch
   * locate a matching filter and return the corresponding DownstreamTlsContext or else return one
   * from default filter chain.
   *
   * @param localInetAddr dest address of the inbound connection
   * @param remoteInetAddr source address of the inbound connection
   */
  private DownstreamTlsContext getDownstreamTlsContext(
      InetSocketAddress localInetAddr, InetSocketAddress remoteInetAddr) {
    List<FilterChain> filterChains = curListener.getFilterChains();

    filterChains = filterOnDestinationPort(filterChains);
    filterChains = filterOnIpAddress(filterChains, localInetAddr.getAddress(), true);
    filterChains =
        filterOnSourceType(filterChains, remoteInetAddr.getAddress(), localInetAddr.getAddress());
    filterChains = filterOnIpAddress(filterChains, remoteInetAddr.getAddress(), false);
    filterChains = filterOnSourcePort(filterChains, remoteInetAddr.getPort());

    // if we get more than 1, we ignore filterChains and use the defaultFilerChain
    // although spec not clear for that case
    if (filterChains.size() == 1) {
      return filterChains.get(0).getDownstreamTlsContext();
    }
    return curListener.getDefaultFilterChain().getDownstreamTlsContext();
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

  private List<FilterChain> filterOnSourceType(
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

  private static boolean isCidrMatching(byte[] cidrBytes, byte[] addressBytes, int prefixLen) {
    BigInteger cidrInt = new BigInteger(cidrBytes);
    BigInteger addrInt = new BigInteger(addressBytes);

    int shiftAmount = 8 * cidrBytes.length - prefixLen;

    cidrInt = cidrInt.shiftRight(shiftAmount);
    addrInt = addrInt.shiftRight(shiftAmount);
    return cidrInt.equals(addrInt);
  }

  private static class QueueElement {
    FilterChain filterChain;
    int indexOfMatchingPrefixRange;
    int matchingPrefixLength;

    public QueueElement(FilterChain filterChain, InetAddress address, boolean forDestination) {
      this.filterChain = filterChain;
      FilterChainMatch filterChainMatch = filterChain.getFilterChainMatch();
      byte[] addressBytes = address.getAddress();
      boolean isIPv6 = address instanceof Inet6Address;
      List<CidrRange> cidrRanges =
          forDestination
              ? filterChainMatch.getPrefixRanges()
              : filterChainMatch.getSourcePrefixRanges();
      indexOfMatchingPrefixRange = -1;
      if (cidrRanges.isEmpty()) { // if there is no CidrRange assume there is perfect match
        matchingPrefixLength = isIPv6 ? 128 : 32;
      } else {
        matchingPrefixLength = 0;
        int index = 0;
        for (CidrRange cidrRange : cidrRanges) {
          InetAddress cidrAddr = cidrRange.getAddressPrefix();
          boolean cidrIsIpv6 = cidrAddr instanceof Inet6Address;
          if (isIPv6 == cidrIsIpv6) {
            byte[] cidrBytes = cidrAddr.getAddress();
            int prefixLen = cidrRange.getPrefixLen();
            if (isCidrMatching(cidrBytes, addressBytes, prefixLen)
                && prefixLen > matchingPrefixLength) {
              matchingPrefixLength = prefixLen;
              indexOfMatchingPrefixRange = index;
            }
          }
          index++;
        }
      }
    }
  }

  private static final class QueueElementComparator implements Comparator<QueueElement> {

    @Override
    public int compare(QueueElement o1, QueueElement o2) {
      // descending order for max heap
      return o2.matchingPrefixLength - o1.matchingPrefixLength;
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof QueueElementComparator;
    }

    @Override
    public int hashCode() {
      return super.hashCode();
    }
  }

  // use prefix_ranges (CIDR) and get the most specific matches
  private List<FilterChain> filterOnIpAddress(
      List<FilterChain> filterChains, InetAddress address, boolean forDestination) {
    PriorityQueue<QueueElement> heap = new PriorityQueue<>(10, new QueueElementComparator());

    for (FilterChain filterChain : filterChains) {
      QueueElement element = new QueueElement(filterChain, address, forDestination);

      if (element.matchingPrefixLength > 0) {
        heap.add(element);
      }
    }
    // get the top ones
    ArrayList<FilterChain> topOnes = new ArrayList<>(heap.size());
    int topMatchingPrefixLen = -1;
    while (!heap.isEmpty()) {
      QueueElement element = heap.remove();
      if (topMatchingPrefixLen == -1) {
        topMatchingPrefixLen = element.matchingPrefixLength;
      } else {
        if (element.matchingPrefixLength < topMatchingPrefixLen) {
          break;
        }
      }
      topOnes.add(element.filterChain);
    }
    return topOnes;
  }

  /** Adds a {@link ServerWatcher} to the list. */
  public void addServerWatcher(ServerWatcher serverWatcher) {
    checkNotNull(serverWatcher, "serverWatcher");
    synchronized (serverWatchers) {
      serverWatchers.add(serverWatcher);
    }
    if (curListener != null) {
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
  XdsClient.ListenerWatcher getListenerWatcher() {
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
      xdsClient.shutdown();
      xdsClient = null;
    }
    if (timeService != null) {
      timeService = SharedResourceHolder.release(timeServiceResource, timeService);
    }
  }

  private static final class TimeServiceResource
          implements SharedResourceHolder.Resource<ScheduledExecutorService> {

    private final String name;

    TimeServiceResource(String name) {
      this.name = name;
    }

    @Override
    public ScheduledExecutorService create() {
      // Use Netty's DefaultThreadFactory in order to get the benefit of FastThreadLocal.
      ThreadFactory threadFactory = new DefaultThreadFactory(name, /* daemon= */ true);
      if (Epoll.isAvailable()) {
        return new EpollEventLoopGroup(1, threadFactory);
      } else {
        return Executors.newSingleThreadScheduledExecutor(threadFactory);
      }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    @Override
    public void close(ScheduledExecutorService instance) {
      try {
        if (instance instanceof EpollEventLoopGroup) {
          ((EpollEventLoopGroup)instance).shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
        } else {
          instance.shutdown();
        }
      } catch (InterruptedException e) {
        logger.log(Level.SEVERE, "Interrupted during shutdown", e);
        Thread.currentThread().interrupt();
      }
    }
  }
}
