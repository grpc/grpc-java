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

package io.grpc.xds.client;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.xds.client.Bootstrapper.XDSTP_SCHEME;
import static io.grpc.xds.client.ControlPlaneClient.CLOSED_BY_SERVER;
import static io.grpc.xds.client.XdsResourceType.ParsedResource;
import static io.grpc.xds.client.XdsResourceType.ValidatedResourceUpdate;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.Any;
import io.grpc.Internal;
import io.grpc.InternalLogId;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.TimeProvider;
import io.grpc.xds.client.Bootstrapper.AuthorityInfo;
import io.grpc.xds.client.Bootstrapper.ServerInfo;
import io.grpc.xds.client.XdsClient.ResourceStore;
import io.grpc.xds.client.XdsLogger.XdsLogLevel;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * XdsClient implementation.
 */
@Internal
public final class XdsClientImpl extends XdsClient implements ResourceStore {

  // Longest time to wait, since the subscription to some resource, for concluding its absence.
  @VisibleForTesting
  public static final int INITIAL_RESOURCE_FETCH_TIMEOUT_SEC = 15;

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          logger.log(
              XdsLogLevel.ERROR,
              "Uncaught exception in XdsClient SynchronizationContext. Panic!",
              e);
          // TODO: better error handling.
          throw new AssertionError(e);
        }
      });

  private final Map<ServerInfo, LoadStatsManager2> loadStatsManagerMap = new HashMap<>();
  final Map<ServerInfo, LoadReportClient> serverLrsClientMap = new HashMap<>();
  /** Map of authority to its active control plane client (affected by xds fallback). */
  private final Map<String, ControlPlaneClient> activeCpClients = new HashMap<>();

  private final Map<ServerInfo, ControlPlaneClient> serverCpClientMap = new HashMap<>();

  /** Maps resource type to the corresponding map of subscribers (keyed by resource name). */
  private final Map<XdsResourceType<? extends ResourceUpdate>,
      Map<String, ResourceSubscriber<? extends ResourceUpdate>>>
      resourceSubscribers = new HashMap<>();
  /** Maps typeUrl to the corresponding XdsResourceType. */
  private final Map<String, XdsResourceType<?>> subscribedResourceTypeUrls = new HashMap<>();

  private final XdsTransportFactory xdsTransportFactory;
  private final Bootstrapper.BootstrapInfo bootstrapInfo;
  private final ScheduledExecutorService timeService;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final Supplier<Stopwatch> stopwatchSupplier;
  private final TimeProvider timeProvider;
  private final Object securityConfig;
  private final InternalLogId logId;
  private final XdsLogger logger;
  private volatile boolean isShutdown;
  private final MessagePrettyPrinter messagePrinter;

  public XdsClientImpl(
      XdsTransportFactory xdsTransportFactory,
      Bootstrapper.BootstrapInfo bootstrapInfo,
      ScheduledExecutorService timeService,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier,
      TimeProvider timeProvider,
      MessagePrettyPrinter messagePrinter,
      Object securityConfig) {
    this.xdsTransportFactory = xdsTransportFactory;
    this.bootstrapInfo = bootstrapInfo;
    this.timeService = timeService;
    this.backoffPolicyProvider = backoffPolicyProvider;
    this.stopwatchSupplier = stopwatchSupplier;
    this.timeProvider = timeProvider;
    this.messagePrinter = messagePrinter;
    this.securityConfig = securityConfig;
    logId = InternalLogId.allocate("xds-client", null);
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public void shutdown() {
    syncContext.execute(
        new Runnable() {
          @Override
          public void run() {
            if (isShutdown) {
              return;
            }
            isShutdown = true;
            for (ControlPlaneClient xdsChannel : serverCpClientMap.values()) {
              xdsChannel.shutdown();
            }
            for (final LoadReportClient lrsClient : serverLrsClientMap.values()) {
              lrsClient.stopLoadReporting();
            }
            cleanUpResourceTimers(null);
          }
        });
  }

  @Override
  public boolean isShutDown() {
    return isShutdown;
  }

  @Override
  public Map<String, XdsResourceType<?>> getSubscribedResourceTypesWithTypeUrl() {
    return Collections.unmodifiableMap(subscribedResourceTypeUrls);
  }

  @Override
  public void assignResourcesToOwner(XdsResourceType<?> type, Collection<String> resources,
                                     Object owner) {
    if (isShutDown()) {
      return;
    }

    Map<String, ResourceSubscriber<? extends ResourceUpdate>> resourceSubscriberMap =
        resourceSubscribers.get(type);

    ControlPlaneClient controlPlaneClient = (ControlPlaneClient) owner;
    for (String resource : resources) {
      ResourceSubscriber<? extends ResourceUpdate> subscriber = resourceSubscriberMap.get(resource);
      if (subscriber != null && subscriber.controlPlaneClient == null) {
        subscriber.controlPlaneClient = controlPlaneClient;
      }
    }
  }

  public void assignUnassignedResources(String authority) {
    if (isShutDown()) {
      return;
    }

    ControlPlaneClient controlPlaneClient = activeCpClients.get(authority);
    if (controlPlaneClient == null) {
      return;
    }

    for (XdsResourceType<?> resourceType : resourceSubscribers.keySet()) {
      for (ResourceSubscriber<? extends ResourceUpdate> subscriber
          : resourceSubscribers.get(resourceType).values()) {
        if (subscriber.controlPlaneClient == null
            && Objects.equals(subscriber.authority, authority)) {
          subscriber.controlPlaneClient = controlPlaneClient;
        }
      }
    }
  }

  @Nullable
  @Override
  public Collection<String> getSubscribedResources(
      ServerInfo serverInfo, XdsResourceType<? extends ResourceUpdate> type) {
    return getSubscribedResources(serverInfo, type, null, false);
  }

  @Nullable
  @Override
  public Collection<String> getSubscribedResources(
      ServerInfo serverInfo, XdsResourceType<? extends ResourceUpdate> type, String authority) {
    return getSubscribedResources(serverInfo, type, authority, true);
  }

  private Collection<String> getSubscribedResources(ServerInfo serverInfo, XdsResourceType<?
      extends ResourceUpdate> type, String authority, boolean useAuthority) {
    Map<String, ResourceSubscriber<? extends ResourceUpdate>> resources =
        resourceSubscribers.getOrDefault(type, Collections.emptyMap());
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();
    String target = serverInfo.target();

    for (String resourceName : resources.keySet()) {
      ResourceSubscriber<? extends ResourceUpdate> resource = resources.get(resourceName);
      if ( resource.isCpcMutable() || (target.equals(resource.getTarget())
          && (!useAuthority || Objects.equals(authority, resource.authority)))) {
        builder.add(resourceName);
      }
    }
    Collection<String> retVal = builder.build();
    return retVal.isEmpty() ? null : retVal;
  }


  // As XdsClient APIs becomes resource agnostic, subscribed resource types are dynamic.
  // ResourceTypes that do not have subscribers does not show up in the snapshot keys.
  @Override
  public ListenableFuture<Map<XdsResourceType<?>, Map<String, ResourceMetadata>>>
      getSubscribedResourcesMetadataSnapshot() {
    final SettableFuture<Map<XdsResourceType<?>, Map<String, ResourceMetadata>>> future =
        SettableFuture.create();
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        // A map from a "resource type" to a map ("resource name": "resource metadata")
        ImmutableMap.Builder<XdsResourceType<?>, Map<String, ResourceMetadata>> metadataSnapshot =
            ImmutableMap.builder();
        for (XdsResourceType<?> resourceType : resourceSubscribers.keySet()) {
          ImmutableMap.Builder<String, ResourceMetadata> metadataMap = ImmutableMap.builder();
          for (Map.Entry<String, ResourceSubscriber<? extends ResourceUpdate>> resourceEntry
              : resourceSubscribers.get(resourceType).entrySet()) {
            metadataMap.put(resourceEntry.getKey(), resourceEntry.getValue().metadata);
          }
          metadataSnapshot.put(resourceType, metadataMap.buildOrThrow());
        }
        future.set(metadataSnapshot.buildOrThrow());
      }
    });
    return future;
  }

  @Override
  public Object getSecurityConfig() {
    return securityConfig;
  }

  @Override
  public <T extends ResourceUpdate> void watchXdsResource(XdsResourceType<T> type,
                                                          String resourceName,
                                                          ResourceWatcher<T> watcher,
                                                          Executor watcherExecutor) {
    syncContext.execute(new Runnable() {
      @Override
      @SuppressWarnings("unchecked")
      public void run() {
        if (!resourceSubscribers.containsKey(type)) {
          resourceSubscribers.put(type, new HashMap<>());
          subscribedResourceTypeUrls.put(type.typeUrl(), type);
        }
        ResourceSubscriber<T> subscriber =
            (ResourceSubscriber<T>) resourceSubscribers.get(type).get(resourceName);
        if (subscriber == null) {
          logger.log(XdsLogLevel.INFO, "Subscribe {0} resource {1}", type, resourceName);
          subscriber = new ResourceSubscriber<>(type, resourceName);
          resourceSubscribers.get(type).put(resourceName, subscriber);
          subscriber.addWatcher(watcher, watcherExecutor);
          if (subscriber.errorDescription != null) {
            return;
          }

          ControlPlaneClient cpcToUse;
          if (subscriber.controlPlaneClient == null) {
            cpcToUse = activeCpClients.get(subscriber.authority);
          } else {
            cpcToUse = subscriber.controlPlaneClient;
          }

          if (subscriber.controlPlaneClient != null) {
            doFallbackIfNecessary(subscriber.controlPlaneClient, subscriber.authority);
          }
          if (cpcToUse != null) {
            cpcToUse.adjustResourceSubscription(type, subscriber.authority);
          } else {
            // No active control plane client for the authority. Start a new one.
            ImmutableList<ServerInfo> serverInfos = getServerInfos(subscriber.authority);
            if (serverInfos == null) {
              logger.log(XdsLogLevel.INFO,
                  "Request for unknown authority {}", subscriber.authority);
              return;
            }
            cpcToUse = getOrCreateControlPlaneClient(serverInfos);
            if (cpcToUse != null) {
              logger.log(XdsLogLevel.DEBUG, "Start new control plane client {0}",
                  cpcToUse.getServerInfo());
            } else {
              logger.log(XdsLogLevel.WARNING, "No active control plane client for authority {0}",
                  subscriber.authority);
            }
          }
        } else {
          subscriber.addWatcher(watcher, watcherExecutor);
        }
      }
    });
  }

  @Override
  public <T extends ResourceUpdate> void cancelXdsResourceWatch(XdsResourceType<T> type,
                                                                String resourceName,
                                                                ResourceWatcher<T> watcher) {
    syncContext.execute(new Runnable() {
      @Override
      @SuppressWarnings("unchecked")
      public void run() {
        ResourceSubscriber<T> subscriber =
            (ResourceSubscriber<T>) resourceSubscribers.get(type).get(resourceName);
        if (subscriber == null) {
          logger.log(XdsLogLevel.WARNING, "double cancel of resource watch for {0}:{1}",
              type.typeName(), resourceName);
          return;
        }
        subscriber.removeWatcher(watcher);
        if (!subscriber.isWatched()) {
          subscriber.cancelResourceWatch();
          resourceSubscribers.get(type).remove(resourceName);
          ControlPlaneClient controlPlaneClient = subscriber.controlPlaneClient;
          if (controlPlaneClient != null) {
            controlPlaneClient.adjustResourceSubscription(type, subscriber.authority);
          }
          if (resourceSubscribers.get(type).isEmpty()) {
            resourceSubscribers.remove(type);
            subscribedResourceTypeUrls.remove(type.typeUrl());
          }
        }
      }
    });
  }

  @Override
  public LoadStatsManager2.ClusterDropStats addClusterDropStats(
      final ServerInfo serverInfo, String clusterName,
      @Nullable String edsServiceName) {
    LoadStatsManager2 loadStatsManager = loadStatsManagerMap.get(serverInfo);
    LoadStatsManager2.ClusterDropStats dropCounter =
        loadStatsManager.getClusterDropStats(clusterName, edsServiceName);
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        serverLrsClientMap.get(serverInfo).startLoadReporting();
      }
    });
    return dropCounter;
  }

  @Override
  public LoadStatsManager2.ClusterLocalityStats addClusterLocalityStats(
      final ServerInfo serverInfo, String clusterName, @Nullable String edsServiceName,
      Locality locality) {
    LoadStatsManager2 loadStatsManager = loadStatsManagerMap.get(serverInfo);
    LoadStatsManager2.ClusterLocalityStats loadCounter =
        loadStatsManager.getClusterLocalityStats(clusterName, edsServiceName, locality);
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        serverLrsClientMap.get(serverInfo).startLoadReporting();
      }
    });
    return loadCounter;
  }


  @Override
  public Bootstrapper.BootstrapInfo getBootstrapInfo() {
    return bootstrapInfo;
  }

  @Override
  public String toString() {
    return logId.toString();
  }

  @Override
  protected void startSubscriberTimersIfNeeded(ServerInfo serverInfo) {
    if (isShutDown()) {
      return;
    }

    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        if (isShutDown()) {
          return;
        }

        // TODO add Jitter so that server isn't swamped when it comes up if this is a restart
        ControlPlaneClient cpc = serverCpClientMap.get(serverInfo);
        if (cpc == null)  {
          return;
        }

        for (Map<String, ResourceSubscriber<?>> subscriberMap : resourceSubscribers.values()) {
          for (ResourceSubscriber<?> subscriber : subscriberMap.values()) {
            if (cpc == subscriber.controlPlaneClient && subscriber.respTimer == null) {
              subscriber.restartTimer();
            }
          }
        }

      }
    });
  }

  @VisibleForTesting
  public boolean isSubscriberCpcConnected(XdsResourceType<?> type, String resourceName) {
    ControlPlaneClient cpc = resourceSubscribers.get(type).get(resourceName).controlPlaneClient;
    return cpc != null && cpc.isConnected();
  }

  private Set<String> getResourceKeys(XdsResourceType<?> xdsResourceType) {
    if (!resourceSubscribers.containsKey(xdsResourceType)) {
      return null;
    }

    return resourceSubscribers.get(xdsResourceType).keySet();
  }

  private void cleanUpResourceTimers(ControlPlaneClient cpcForThisStream) {
    for (Map<String, ResourceSubscriber<?>> subscriberMap : resourceSubscribers.values()) {
      for (ResourceSubscriber<?> subscriber : subscriberMap.values()) {
        if (cpcForThisStream == null || cpcForThisStream.equals(subscriber.controlPlaneClient)) {
          subscriber.stopTimer();
        }
      }
    }
  }

  public ControlPlaneClient getOrCreateControlPlaneClient(ImmutableList<ServerInfo> serverInfos) {
    if (serverInfos.isEmpty()) {
      return null;
    }

    for (ServerInfo serverInfo : serverInfos) {
      if (serverCpClientMap.containsKey(serverInfo)) {
        ControlPlaneClient controlPlaneClient = serverCpClientMap.get(serverInfo);
        if (controlPlaneClient.isInBackoff()) {
          continue;
        }
        return controlPlaneClient;
      } else {
        ControlPlaneClient cpc = getOrCreateControlPlaneClient(serverInfo);
        logger.log(XdsLogLevel.DEBUG, "Created control plane client {0}", cpc);
        return cpc;
      }
    }

    // Everything existed and is in backoff so return null
    return null;
  }

  private ControlPlaneClient getOrCreateControlPlaneClient(ServerInfo serverInfo) {
    syncContext.throwIfNotInThisSynchronizationContext();
    if (serverCpClientMap.containsKey(serverInfo)) {
      return serverCpClientMap.get(serverInfo);
    }

    XdsTransportFactory.XdsTransport xdsTransport = xdsTransportFactory.create(serverInfo);

    ControlPlaneClient controlPlaneClient = new ControlPlaneClient(
        xdsTransport,
        serverInfo,
        bootstrapInfo.node(),
        new ResponseHandler(serverInfo),
        this,
        timeService,
        syncContext,
        backoffPolicyProvider,
        stopwatchSupplier,
        messagePrinter
    );

    serverCpClientMap.put(serverInfo, controlPlaneClient);

    LoadStatsManager2 loadStatsManager = new LoadStatsManager2(stopwatchSupplier);
    loadStatsManagerMap.put(serverInfo, loadStatsManager);
    LoadReportClient lrsClient = new LoadReportClient(
        loadStatsManager, xdsTransport, bootstrapInfo.node(),
        syncContext, timeService, backoffPolicyProvider, stopwatchSupplier);
    serverLrsClientMap.put(serverInfo, lrsClient);

    return controlPlaneClient;
  }

  @VisibleForTesting
  @Override
  public Map<ServerInfo, LoadReportClient> getServerLrsClientMap() {
    return ImmutableMap.copyOf(serverLrsClientMap);
  }

  private String getAuthority(String resource) {
    String authority;
    if (resource.startsWith(XDSTP_SCHEME)) {
      URI uri = URI.create(resource);
      authority = uri.getAuthority();
      if (authority == null) {
        authority = "";
      }
    } else {
      authority = null;
    }

    return authority;
  }

  @Nullable
  private ImmutableList<ServerInfo> getServerInfos(String authority) {
    if (authority != null) {
      AuthorityInfo authorityInfo = bootstrapInfo.authorities().get(authority);
      if (authorityInfo == null || authorityInfo.xdsServers().isEmpty()) {
        return null;
      }
      return authorityInfo.xdsServers();
    } else {
      return bootstrapInfo.servers();
    }
  }

  private List<String> getAuthoritiesForServerInfo(ServerInfo serverInfo) {
    List<String> authorities = new ArrayList<>();
    for (Map.Entry<String, AuthorityInfo> entry : bootstrapInfo.authorities().entrySet()) {
      if (entry.getValue().xdsServers().contains(serverInfo)) {
        authorities.add(entry.getKey());
      }
    }

    if (bootstrapInfo.servers().contains(serverInfo)) {
      authorities.add(null);
    }

    return authorities;
  }

  @SuppressWarnings("unchecked")
  private <T extends ResourceUpdate> void handleResourceUpdate(
      XdsResourceType.Args args, List<Any> resources, XdsResourceType<T> xdsResourceType,
      ProcessingTracker processingTracker) {
    ControlPlaneClient controlPlaneClient = serverCpClientMap.get(args.serverInfo);

    ValidatedResourceUpdate<T> result = xdsResourceType.parse(args, resources);
    logger.log(XdsLogger.XdsLogLevel.INFO,
        "Received {0} Response version {1} nonce {2}. Parsed resources: {3}",
        xdsResourceType.typeName(), args.versionInfo, args.nonce, result.unpackedResources);
    Map<String, ParsedResource<T>> parsedResources = result.parsedResources;
    Set<String> invalidResources = result.invalidResources;
    List<String> errors = result.errors;
    String errorDetail = null;
    if (errors.isEmpty()) {
      checkArgument(invalidResources.isEmpty(), "found invalid resources but missing errors");
      controlPlaneClient.ackResponse(xdsResourceType, args.versionInfo,
          args.nonce);
    } else {
      errorDetail = Joiner.on('\n').join(errors);
      logger.log(XdsLogLevel.WARNING,
          "Failed processing {0} Response version {1} nonce {2}. Errors:\n{3}",
          xdsResourceType.typeName(), args.versionInfo, args.nonce, errorDetail);
      controlPlaneClient.nackResponse(xdsResourceType, args.nonce, errorDetail);
    }

    long updateTime = timeProvider.currentTimeNanos();
    Map<String, ResourceSubscriber<? extends ResourceUpdate>> subscribedResources =
        resourceSubscribers.getOrDefault(xdsResourceType, Collections.emptyMap());
    for (Map.Entry<String, ResourceSubscriber<?>> entry : subscribedResources.entrySet()) {
      String resourceName = entry.getKey();
      ResourceSubscriber<T> subscriber = (ResourceSubscriber<T>) entry.getValue();
      if (parsedResources.containsKey(resourceName)) {
        if (subscriber.isCpcMutable()) {
          subscriber.controlPlaneClient = controlPlaneClient;
        }
        // Happy path: the resource updated successfully. Notify the watchers of the update.
        subscriber.onData(parsedResources.get(resourceName), args.versionInfo, updateTime,
            processingTracker);
        continue;
      }

      if (invalidResources.contains(resourceName)) {
        // The resource update is invalid. Capture the error without notifying the watchers.
        subscriber.onRejected(args.versionInfo, updateTime, errorDetail);
      }

      // Nothing else to do for incremental ADS resources.
      if (!xdsResourceType.isFullStateOfTheWorld()) {
        continue;
      }

      // Handle State of the World ADS: invalid resources.
      if (invalidResources.contains(resourceName)) {
        // The resource is missing. Reuse the cached resource if possible.
        if (subscriber.data == null) {
          // No cached data. Notify the watchers of an invalid update.
          subscriber.onError(Status.UNAVAILABLE.withDescription(errorDetail), processingTracker);
        }
        continue;
      }

      // For State of the World services, notify watchers when their watched resource is missing
      // from the ADS update. Note that we can only do this if the resource update is coming from
      // the same xDS server that the ResourceSubscriber is subscribed to.
      if (controlPlaneClient.equals(subscriber.controlPlaneClient)) {
        subscriber.onAbsent(processingTracker);
      }
    }
  }

  /**
   * Try to fallback to a lower priority control plane client.
   *
   * @param oldCpc           The control plane that we are falling back from
   * @param activeServerInfo Matches the currently active control plane client
   * @param serverInfos      The full ordered list of xds servers
   * @param authority        The authority within which we are falling back
   * @return true if a fallback was successful, false otherwise.
   */
  private boolean doFallbackForAuthority(ControlPlaneClient oldCpc, ServerInfo activeServerInfo,
                                         List<ServerInfo> serverInfos, String authority) {
    if (serverInfos == null || serverInfos.size() < 2) {
      return false;
    }

    // Build a list of servers with lower priority than the current server.
    int i = serverInfos.indexOf(activeServerInfo);
    List<ServerInfo> fallbackServers = (i > -1)
        ? serverInfos.subList(i, serverInfos.size())
        : null;

    ControlPlaneClient fallbackCpc =
        fallbackServers != null
        ? getOrCreateControlPlaneClient(ImmutableList.copyOf(fallbackServers))
        : null;

    return fallBackToCpc(fallbackCpc, authority, oldCpc);
  }

  private boolean fallBackToCpc(ControlPlaneClient fallbackCpc, String authority,
                                ControlPlaneClient oldCpc) {
    boolean didFallback = false;
    if (fallbackCpc != null && ! fallbackCpc.isInBackoff()) {
      logger.log(XdsLogLevel.INFO, "Falling back to XDS server {0}",
          fallbackCpc.getServerInfo().target());

      setCpcForAuthority(authority, fallbackCpc);
      fallbackCpc.sendDiscoveryRequests(authority);
      didFallback = true;
    } else {
      logger.log(XdsLogLevel.WARNING, "No working fallback XDS Servers found from {0}",
          oldCpc.getServerInfo().target());
    }
    return didFallback;
  }

  private void doFallbackIfNecessary(ControlPlaneClient cpc, String authority) {
    if (cpc == null || !BootstrapperImpl.isEnabledXdsFallback()) {
      return;
    }

    ControlPlaneClient activeCpClient = activeCpClients.get(authority);
    if (cpc == activeCpClient) {
      return;
    }
    if (activeCpClient == null) {
      setCpcForAuthority(authority, cpc);
      return;
    }

    fallBackToCpc(cpc, authority, activeCpClient);
  }

  private void setCpcForAuthority(String authority, ControlPlaneClient cpc) {
    activeCpClients.put(authority, cpc);

    // Take ownership of resource's whose names are shared across xds servers (ex. LDS)
    for (Map.Entry<XdsResourceType<?>, Map<String, ResourceSubscriber<?>>> subscriberTypeEntry
        : resourceSubscribers.entrySet()) {
      if (!subscriberTypeEntry.getKey().isSharedName()) {
        continue;
      }
      for (ResourceSubscriber<?> subscriber : subscriberTypeEntry.getValue().values()) {
        if (subscriber.controlPlaneClient == cpc
            || !Objects.equals(subscriber.authority, authority)) {
          continue;
        }
        subscriber.data = null;
        subscriber.absent = false;
        subscriber.controlPlaneClient = cpc;
        subscriber.restartTimer();
      }
    }

    // Rely on the watchers to clear out the old cache values and rely on the
    // backup xds servers having unique resource names
  }

  /**
   * Tracks a single subscribed resource.
   */
  private final class ResourceSubscriber<T extends ResourceUpdate> {
    @Nullable
    private final ImmutableList<ServerInfo> serverInfos;
    @Nullable
    private ControlPlaneClient controlPlaneClient;
    private final String authority;
    private final XdsResourceType<T> type;
    private final String resource;
    private final Map<ResourceWatcher<T>, Executor> watchers = new HashMap<>();
    @Nullable
    private T data;
    private boolean absent;
    // Tracks whether the deletion has been ignored per bootstrap server feature.
    // See https://github.com/grpc/proposal/blob/master/A53-xds-ignore-resource-deletion.md
    private boolean resourceDeletionIgnored;
    @Nullable
    private ScheduledHandle respTimer;
    @Nullable
    private ResourceMetadata metadata;
    @Nullable
    private String errorDescription;
    private boolean cpcFixed = false;

    ResourceSubscriber(XdsResourceType<T> type, String resource) {
      syncContext.throwIfNotInThisSynchronizationContext();
      this.type = type;
      this.resource = resource;
      this.authority = getAuthority(resource);
      this.serverInfos = getServerInfos(authority);
      if (serverInfos == null) {
        this.errorDescription = "Wrong configuration: xds server does not exist for resource "
            + resource;
        this.controlPlaneClient = null;
        return;
      }

      // Initialize metadata in UNKNOWN state to cover the case when resource subscriber,
      // is created but not yet requested because the client is in backoff.
      this.metadata = ResourceMetadata.newResourceMetadataUnknown();

      ControlPlaneClient controlPlaneClient = null;
      try {
        controlPlaneClient = getOrCreateControlPlaneClient(serverInfos);
        if (controlPlaneClient == null) {
          return;
        }

        if (!controlPlaneClient.isConnected()) {
          controlPlaneClient.connect(); // Make sure we are at least trying to connect
        }
      } catch (IllegalArgumentException e) {
        controlPlaneClient = null;
        this.errorDescription = "Bad configuration:  " + e.getMessage();
        return;
      } finally {
        this.controlPlaneClient = controlPlaneClient;
      }

      restartTimer();
    }

    @Override
    public String toString() {
      return "ResourceSubscriber{"
          + "resource='" + resource + '\''
          + ", controlPlaneClient=" + controlPlaneClient
          + ", authority='" + authority + '\''
          + ", type=" + type
          + ", watchers=" + watchers.size()
          + ", data=" + data
          + ", absent=" + absent
          + ", resourceDeletionIgnored=" + resourceDeletionIgnored
          + ", errorDescription='" + errorDescription + '\''
          + '}';
    }

    void addWatcher(ResourceWatcher<T> watcher, Executor watcherExecutor) {
      checkArgument(!watchers.containsKey(watcher), "watcher %s already registered", watcher);
      watchers.put(watcher, watcherExecutor);
      T savedData = data;
      boolean savedAbsent = absent;
      watcherExecutor.execute(() -> {
        if (errorDescription != null) {
          watcher.onError(Status.INVALID_ARGUMENT.withDescription(errorDescription));
          return;
        }
        if (savedData != null) {
          notifyWatcher(watcher, savedData);
        } else if (savedAbsent) {
          watcher.onResourceDoesNotExist(resource);
        }
      });
    }

    void removeWatcher(ResourceWatcher<T> watcher) {
      checkArgument(watchers.containsKey(watcher), "watcher %s not registered", watcher);
      watchers.remove(watcher);
    }

    void restartTimer() {
      if (data != null || absent) {  // resource already resolved
        return;
      }
      if (controlPlaneClient == null || !controlPlaneClient.isReady()) {
        // When client becomes ready, it triggers a restartTimer for all relevant subscribers.
        return;
      }

      class ResourceNotFound implements Runnable {
        @Override
        public void run() {
          logger.log(XdsLogLevel.INFO, "{0} resource {1} initial fetch timeout",
              type, resource);
          respTimer = null;
          onAbsent(null);
        }

        @Override
        public String toString() {
          return type + this.getClass().getSimpleName();
        }
      }

      // Initial fetch scheduled or rescheduled, transition metadata state to REQUESTED.
      metadata = ResourceMetadata.newResourceMetadataRequested();

      if (respTimer != null) {
        respTimer.cancel();
      }
      respTimer = syncContext.schedule(
          new ResourceNotFound(), INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS,
          timeService);
    }

    void stopTimer() {
      if (respTimer != null && respTimer.isPending()) {
        respTimer.cancel();
        respTimer = null;
      }
    }

    void cancelResourceWatch() {
      if (isWatched()) {
        throw new IllegalStateException("Can't cancel resource watch with active watchers present");
      }
      stopTimer();
      String message = "Unsubscribing {0} resource {1} from server {2}";
      XdsLogLevel logLevel = XdsLogLevel.INFO;
      if (resourceDeletionIgnored) {
        message += " for which we previously ignored a deletion";
        logLevel = XdsLogLevel.FORCE_INFO;
      }
      logger.log(logLevel, message, type, resource, getTarget());
    }

    boolean isWatched() {
      return !watchers.isEmpty();
    }

    boolean hasResult() {
      return data != null || absent;
    }

    void onData(ParsedResource<T> parsedResource, String version, long updateTime,
                ProcessingTracker processingTracker) {
      if (respTimer != null && respTimer.isPending()) {
        respTimer.cancel();
        respTimer = null;
      }
      this.metadata = ResourceMetadata
          .newResourceMetadataAcked(parsedResource.getRawResource(), version, updateTime);
      ResourceUpdate oldData = this.data;
      this.data = parsedResource.getResourceUpdate();
      absent = false;
      cpcFixed = true;
      if (resourceDeletionIgnored) {
        logger.log(XdsLogLevel.FORCE_INFO, "xds server {0}: server returned new version "
                + "of resource for which we previously ignored a deletion: type {1} name {2}",
            getTarget(), type, resource);
        resourceDeletionIgnored = false;
      }
      if (!Objects.equals(oldData, data)) {
        for (ResourceWatcher<T> watcher : watchers.keySet()) {
          processingTracker.startTask();
          watchers.get(watcher).execute(() -> {
            try {
              notifyWatcher(watcher, data);
            } finally {
              processingTracker.onComplete();
            }
          });
        }
      }
    }

    private String getTarget() {
      return (serverInfos != null && controlPlaneClient != null)
             ? controlPlaneClient.getServerInfo().target()
             : "unknown";
    }

    private boolean isCpcMutable() {
      return !cpcFixed || type.isSharedName();
    }

    void onAbsent(@Nullable ProcessingTracker processingTracker) {
      if (respTimer != null && respTimer.isPending()) {  // too early to conclude absence
        return;
      }

      // Ignore deletion of State of the World resources when this feature is on,
      // and the resource is reusable.
      boolean ignoreResourceDeletionEnabled =
          serverInfos != null && controlPlaneClient != null
              && controlPlaneClient.getServerInfo().ignoreResourceDeletion();
      if (ignoreResourceDeletionEnabled && type.isFullStateOfTheWorld() && data != null) {
        if (!resourceDeletionIgnored) {
          logger.log(XdsLogLevel.FORCE_WARNING,
              "xds server {0}: ignoring deletion for resource type {1} name {2}}",
              getTarget(), type, resource);
          resourceDeletionIgnored = true;
        }
        return;
      }

      logger.log(XdsLogLevel.INFO, "Conclude {0} resource {1} not exist", type, resource);
      if (!absent) {
        data = null;
        absent = true;
        metadata = ResourceMetadata.newResourceMetadataDoesNotExist();
        for (ResourceWatcher<T> watcher : watchers.keySet()) {
          if (processingTracker != null) {
            processingTracker.startTask();
          }
          watchers.get(watcher).execute(() -> {
            try {
              watcher.onResourceDoesNotExist(resource);
            } finally {
              if (processingTracker != null) {
                processingTracker.onComplete();
              }
            }
          });
        }
      }
    }

    void onError(Status error, @Nullable ProcessingTracker tracker) {
      if (respTimer != null && respTimer.isPending()) {
        respTimer.cancel();
        respTimer = null;
      }

      // Include node ID in xds failures to allow cross-referencing with control plane logs
      // when debugging.
      String description = error.getDescription() == null ? "" : error.getDescription() + " ";
      Status errorAugmented = Status.fromCode(error.getCode())
          .withDescription(description + "nodeID: " + bootstrapInfo.node().getId())
          .withCause(error.getCause());

      for (ResourceWatcher<T> watcher : watchers.keySet()) {
        if (tracker != null) {
          tracker.startTask();
        }
        watchers.get(watcher).execute(() -> {
          try {
            watcher.onError(errorAugmented);
          } finally {
            if (tracker != null) {
              tracker.onComplete();
            }
          }
        });
      }
    }

    void onRejected(String rejectedVersion, long rejectedTime, String rejectedDetails) {
      metadata = ResourceMetadata
          .newResourceMetadataNacked(metadata, rejectedVersion, rejectedTime, rejectedDetails);
    }

    private void notifyWatcher(ResourceWatcher<T> watcher, T update) {
      watcher.onChanged(update);
    }
  }

  private class ResponseHandler implements XdsResponseHandler {
    final ServerInfo serverInfo;

    ResponseHandler(ServerInfo serverInfo) {
      this.serverInfo = serverInfo;
    }

    @Override
    public void handleResourceResponse(
        XdsResourceType<?> xdsResourceType, ServerInfo serverInfo, String versionInfo,
        List<Any> resources, String nonce, ProcessingTracker processingTracker) {
      checkNotNull(xdsResourceType, "xdsResourceType");
      syncContext.throwIfNotInThisSynchronizationContext();
      Set<String> toParseResourceNames =
          xdsResourceType.shouldRetrieveResourceKeysForArgs()
          ? getResourceKeys(xdsResourceType)
          : null;
      XdsResourceType.Args args = new XdsResourceType.Args(serverInfo, versionInfo, nonce,
          bootstrapInfo, securityConfig, toParseResourceNames);
      handleResourceUpdate(args, resources, xdsResourceType, processingTracker);
    }

    @Override
    public void handleStreamClosed(Status status) {
      syncContext.throwIfNotInThisSynchronizationContext();
      ControlPlaneClient cpcForThisStream = serverCpClientMap.get(serverInfo);
      cleanUpResourceTimers(cpcForThisStream);

      Status error = status.isOk() ? Status.UNAVAILABLE.withDescription(CLOSED_BY_SERVER) : status;
      boolean checkForFallback = !status.isOk() && BootstrapperImpl.isEnabledXdsFallback();

      for (Map<String, ResourceSubscriber<? extends ResourceUpdate>> subscriberMap :
          resourceSubscribers.values()) {
        for (ResourceSubscriber<? extends ResourceUpdate> subscriber : subscriberMap.values()) {
          if (subscriber.hasResult()
              || (subscriber.cpcFixed && !cpcForThisStream.equals(subscriber.controlPlaneClient))) {
            continue;
          }
          if (checkForFallback) {
            // try to fallback to lower priority control plane client
            if (doFallbackForAuthority(cpcForThisStream,
                serverInfo, subscriber.serverInfos, subscriber.authority)) {
              return;
            }
            checkForFallback = false;
          }

          subscriber.onError(error, null);
        }
      }
    }

    @Override
    public void handleStreamRestarted(ServerInfo serverInfo) {
      if (isShutDown()) {
        return;
      }

      syncContext.throwIfNotInThisSynchronizationContext();
      ControlPlaneClient controlPlaneClient = serverCpClientMap.get(serverInfo);
      if (controlPlaneClient == null) {
        return;
      }

      for (String authority : getAuthoritiesForServerInfo(serverInfo)) {
        if (controlPlaneClient.hasSubscribedResources(authority)) {
          internalHandleStreamReady(serverInfo, controlPlaneClient, authority);
        }
      }
    }

    private void internalHandleStreamReady(
        ServerInfo serverInfo, ControlPlaneClient controlPlaneClient, String authority) {
      ControlPlaneClient activeCpClient = activeCpClients.get(authority);
      if (activeCpClient == null) {
        setCpcForAuthority(authority, controlPlaneClient);
        restartMatchingSubscriberTimers(controlPlaneClient, authority);
        controlPlaneClient.sendDiscoveryRequests(authority);
        return; // Since nothing was active can ignore fallback
      }

      if (activeCpClient.isReady()
          && compareCpClients(activeCpClient, controlPlaneClient, authority) < 0) {
        logger.log(XdsLogLevel.INFO, "Ignoring stream restart for lower priority server {0}",
            serverInfo.target());
        return;
      }

      if (activeCpClient != controlPlaneClient) {
        setCpcForAuthority(authority, controlPlaneClient);
      } else {
        assignUnassignedResources(authority);
      }

      restartMatchingSubscriberTimers(controlPlaneClient, authority);
      controlPlaneClient.sendDiscoveryRequests(authority);

      // If fallback isn't enabled, we're done.
      if (!BootstrapperImpl.isEnabledXdsFallback()) {
        return;
      }

      // Shutdown any lower priority control plane clients.
      Iterator<ControlPlaneClient> iterator = serverCpClientMap.values().iterator();
      while (iterator.hasNext()) {
        ControlPlaneClient client = iterator.next();
        if (compareCpClients(client, controlPlaneClient, authority) > 0
                && !activeCpClients.containsValue(client)) {
          iterator.remove();
          client.shutdown(); // TODO someday add an exponential backoff to mitigate flapping
        }
      }
    }

    private void restartMatchingSubscriberTimers(
        ControlPlaneClient controlPlaneClient, String authority) {
      // Restart the timers for all the watched resources that are associated with this stream
      for (Map<String, ResourceSubscriber<? extends ResourceUpdate>> subscriberMap :
          resourceSubscribers.values()) {
        for (ResourceSubscriber<? extends ResourceUpdate> subscriber : subscriberMap.values()) {
          if ((subscriber.controlPlaneClient == null)
              || (subscriber.controlPlaneClient.equals(controlPlaneClient)
                  && Objects.equals(subscriber.authority, authority))) {
            subscriber.restartTimer();
          }
        }
      }
    }
  }

  private int compareCpClients(ControlPlaneClient base, ControlPlaneClient other,
                               String authority) {
    if (base == other || other == null) {
      return 0;
    }

    ImmutableList<ServerInfo> serverInfos = getServerInfos(authority);
    ServerInfo baseServerInfo = base.getServerInfo();
    ServerInfo otherServerInfo = other.getServerInfo();

    if (serverInfos == null || !serverInfos.contains(baseServerInfo)
        || !serverInfos.contains(otherServerInfo)) {
      return -100; // At least one of them isn't serving this authority
    }

    return serverInfos.indexOf(baseServerInfo) - serverInfos.indexOf(otherServerInfo);
  }
}
