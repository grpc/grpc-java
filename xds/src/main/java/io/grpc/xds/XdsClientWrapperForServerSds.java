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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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

  @VisibleForTesting
  static boolean experimentalNewServerApiEnvVar = Boolean.parseBoolean(
          System.getenv("GRPC_XDS_EXPERIMENTAL_NEW_SERVER_API"));

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
      reportError(Status.fromThrowable(e));
      throw new IOException(e);
    }
    Node node = bootstrapInfo.getNode();
    Bootstrapper.ServerInfo serverInfo = bootstrapInfo.getServers().get(0);  // use first server
    ManagedChannel channel =
        Grpc.newChannelBuilder(serverInfo.getTarget(), serverInfo.getChannelCredentials())
            .keepAliveTime(5, TimeUnit.MINUTES).build();
    timeService = SharedResourceHolder.get(timeServiceResource);
    newServerApi = serverInfo.isUseProtocolV3() && experimentalNewServerApiEnvVar;
    String grpcServerResourceId = bootstrapInfo.getGrpcServerResourceId();
    if (newServerApi && grpcServerResourceId == null) {
      reportError(
          Status.INVALID_ARGUMENT.withDescription("missing grpc_server_resource_name_id value"));
      throw new IOException("missing grpc_server_resource_name_id value");
    }
    XdsClient xdsClientImpl =
        new ServerXdsClient(
            channel,
            serverInfo.isUseProtocolV3(),
            node,
            timeService,
            new ExponentialBackoffPolicy.Provider(),
            GrpcUtil.STOPWATCH_SUPPLIER,
            experimentalNewServerApiEnvVar,
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
            reportError(Status.NOT_FOUND.withDescription(resourceName));
          }

          @Override
          public void onError(Status error) {
            logger.log(
                Level.WARNING, "ListenerWatcher in XdsClientWrapperForServerSds: {0}", error);
            reportError(error);
          }
        };
    xdsClient.watchListenerData(port, listenerWatcher);
  }

  /**
   * Locates the best matching FilterChain to the channel from the current listener and if found
   * returns the DownstreamTlsContext from that FilterChain, else null.
   */
  @Nullable
  public DownstreamTlsContext getDownstreamTlsContext(Channel channel) {
    if (curListener != null && channel != null) {
      SocketAddress localAddress = channel.localAddress();
      checkState(
          localAddress instanceof InetSocketAddress,
          "Channel localAddress is expected to be InetSocketAddress");
      InetSocketAddress localInetAddr = (InetSocketAddress) localAddress;
      checkState(
          port == localInetAddr.getPort(),
          "Channel localAddress port does not match requested listener port");
      return getDownstreamTlsContext(localInetAddr);
    }
    return null;
  }

  private DownstreamTlsContext getDownstreamTlsContext(InetSocketAddress localInetAddr) {
    checkNotNull(localInetAddr, "localInetAddr");
    if (curListener != null) {
      List<FilterChain> filterChains = curListener.getFilterChains();
      FilterChainComparator comparator = new FilterChainComparator(localInetAddr);
      FilterChain bestMatch =
          filterChains.isEmpty() ? null : Collections.max(filterChains, comparator);
      if (bestMatch != null
          && (newServerApi || comparator.isMatching(bestMatch.getFilterChainMatch()))) {
        return bestMatch.getDownstreamTlsContext();
      }
    }
    return null;
  }

  /** Adds a {@link ServerWatcher} to the list. */
  public void addServerWatcher(ServerWatcher serverWatcher) {
    checkNotNull(serverWatcher, "serverWatcher");
    synchronized (serverWatchers) {
      serverWatchers.add(serverWatcher);
    }
    if (curListener != null) {
      serverWatcher.onSuccess(getDownstreamTlsContext(new InetSocketAddress(port)));
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

  private void reportError(Status status) {
    for (ServerWatcher watcher : getServerWatchers()) {
      watcher.onError(status);
    }
  }

  private void reportSuccess() {
    DownstreamTlsContext downstreamTlsContext =
        getDownstreamTlsContext(new InetSocketAddress(port));
    for (ServerWatcher watcher : getServerWatchers()) {
      watcher.onSuccess(downstreamTlsContext);
    }
  }


  @VisibleForTesting
  XdsClient.ListenerWatcher getListenerWatcher() {
    return listenerWatcher;
  }

  /** Watcher interface for the clients of this class. */
  public interface ServerWatcher {

    /** Called to report errors from the control plane including "not found". */
    void onError(Status error);

    /** Called to report successful receipt of server config. */
    void onSuccess(DownstreamTlsContext downstreamTlsContext);
  }

  private static final class FilterChainComparator implements Comparator<FilterChain> {
    private final InetSocketAddress localAddress;

    private enum Match {
      NO_MATCH,
      EMPTY_PREFIX_RANGE_MATCH,
      IPANY_MATCH,
      EXACT_ADDRESS_MATCH
    }

    private FilterChainComparator(InetSocketAddress localAddress) {
      checkNotNull(localAddress, "localAddress cannot be null");
      this.localAddress = localAddress;
    }

    @Override
    public int compare(FilterChain first, FilterChain second) {
      checkNotNull(first, "first arg cannot be null");
      checkNotNull(second, "second arg cannot be null");
      FilterChainMatch firstMatch = first.getFilterChainMatch();
      FilterChainMatch secondMatch = second.getFilterChainMatch();

      if (firstMatch == null) {
        return (secondMatch == null) ? 0 : (isMatching(secondMatch) ? -1 : 1);
      } else {
        return (secondMatch == null)
            ? (isMatching(firstMatch) ? 1 : -1)
            : compare(firstMatch, secondMatch);
      }
    }

    private int compare(FilterChainMatch first, FilterChainMatch second) {
      int channelPort = localAddress.getPort();

      if (first.getDestinationPort() == channelPort) {
        return (second.getDestinationPort() == channelPort)
            ? compare(first.getPrefixRanges(), second.getPrefixRanges())
            : (isInetAddressMatching(first.getPrefixRanges()) ? 1 : 0);
      } else {
        return (second.getDestinationPort() == channelPort)
            ? (isInetAddressMatching(second.getPrefixRanges()) ? -1 : 0)
            : 0;
      }
    }

    private int compare(List<CidrRange> first, List<CidrRange> second) {
      return getInetAddressMatch(first).ordinal() - getInetAddressMatch(second).ordinal();
    }

    private boolean isInetAddressMatching(List<CidrRange> prefixRanges) {
      return getInetAddressMatch(prefixRanges).ordinal() > Match.NO_MATCH.ordinal();
    }

    private Match getInetAddressMatch(List<CidrRange> prefixRanges) {
      if (prefixRanges == null || prefixRanges.isEmpty()) {
        return Match.EMPTY_PREFIX_RANGE_MATCH;
      }
      InetAddress localInetAddress = localAddress.getAddress();
      for (CidrRange cidrRange : prefixRanges) {
        if (cidrRange.getPrefixLen() == 32) {
          try {
            InetAddress cidrAddr = InetAddress.getByName(cidrRange.getAddressPrefix());
            if (cidrAddr.isAnyLocalAddress()) {
              return Match.IPANY_MATCH;
            }
            if (cidrAddr.equals(localInetAddress)) {
              return Match.EXACT_ADDRESS_MATCH;
            }
          } catch (UnknownHostException e) {
            logger.log(Level.WARNING, "cidrRange address parsing", e);
            // continue
          }
        }
        // TODO(sanjaypujare): implement prefix match logic as needed
      }
      return Match.NO_MATCH;
    }

    private boolean isMatching(FilterChainMatch filterChainMatch) {
      if (filterChainMatch == null) {
        return true;
      }
      int destPort = filterChainMatch.getDestinationPort();
      if (destPort != localAddress.getPort()) {
        return false;
      }
      return isInetAddressMatching(filterChainMatch.getPrefixRanges());
    }
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
