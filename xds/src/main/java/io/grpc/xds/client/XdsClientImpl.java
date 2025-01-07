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
import static io.grpc.xds.client.XdsResourceType.ParsedResource;
import static io.grpc.xds.client.XdsResourceType.ValidatedResourceUpdate;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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
  /** Map of authority to its activated control plane client (affected by xds fallback).
   * The last entry in the list for each value is the "active" CPC for the matching key */
  private final Map<String, List<ControlPlaneClient>> activatedCpClients = new HashMap<>();
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
  private final XdsClientMetricReporter metricReporter;

  public XdsClientImpl(
      XdsTransportFactory xdsTransportFactory,
      Bootstrapper.BootstrapInfo bootstrapInfo,
      ScheduledExecutorService timeService,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier,
      TimeProvider timeProvider,
      MessagePrettyPrinter messagePrinter,
      Object securityConfig,
      XdsClientMetricReporter metricReporter) {
    this.xdsTransportFactory = xdsTransportFactory;
    this.bootstrapInfo = bootstrapInfo;
    this.timeService = timeService;
    this.backoffPolicyProvider = backoffPolicyProvider;
    this.stopwatchSupplier = stopwatchSupplier;
    this.timeProvider = timeProvider;
    this.messagePrinter = messagePrinter;
    this.securityConfig = securityConfig;
    this.metricReporter = metricReporter;
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
            activatedCpClients.clear();
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

  private ControlPlaneClient getActiveCpc(String authority) {
    List<ControlPlaneClient> controlPlaneClients = activatedCpClients.get(authority);
    if (controlPlaneClients == null || controlPlaneClients.isEmpty()) {
      return null;
    }

    return controlPlaneClients.get(controlPlaneClients.size() - 1);
  }

  @Nullable
  @Override
  public Collection<String> getSubscribedResources(
      ServerInfo serverInfo, XdsResourceType<? extends ResourceUpdate> type) {
    ControlPlaneClient targetCpc = serverCpClientMap.get(serverInfo);
    if (targetCpc == null) {
      return null;
    }

    // This should include all of the authorities that targetCpc or a fallback from it is serving
    List<String> authorities = activatedCpClients.entrySet().stream()
        .filter(entry -> entry.getValue().contains(targetCpc))
        .map(Map.Entry::getKey)
        .collect(Collectors.toList());

    Map<String, ResourceSubscriber<? extends ResourceUpdate>> resources =
        resourceSubscribers.getOrDefault(type, Collections.emptyMap());

    Collection<String> retVal = resources.entrySet().stream()
        .filter(entry -> authorities.contains(entry.getValue().authority))
        .map(Map.Entry::getKey)
        .collect(Collectors.toList());

    return retVal.isEmpty() ? null : retVal;
  }

  @Override
  public void startMissingResourceTimers(Collection<String> resourceNames,
                                         XdsResourceType<?> resourceType) {
    Map<String, ResourceSubscriber<? extends ResourceUpdate>> subscriberMap =
        resourceSubscribers.get(resourceType);

    for (String resourceName : resourceNames) {
      ResourceSubscriber<?> subscriber = subscriberMap.get(resourceName);
      if (subscriber.respTimer == null && !subscriber.hasResult()) {
        subscriber.restartTimer();
      }
    }
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

          if (subscriber.errorDescription == null) {
            CpcWithFallbackState cpcToUse = manageControlPlaneClient(subscriber);
            if (cpcToUse.cpc != null) {
              cpcToUse.cpc.adjustResourceSubscription(type);
            }
          }
        }

        subscriber.addWatcher(watcher, watcherExecutor);
      }
    });
  }

  /**
   * Gets a ControlPlaneClient for the subscriber's authority, creating one if necessary.
   * If there already was an active CPC for this authority, and it is different from the one
   * identified, then do fallback to the identified one (cpcToUse).
   *
   * @return identified CPC or {@code null} (if there are no valid ServerInfos associated with the
   *     subscriber's authority or CPC's for all are in backoff), and whether did a fallback.
   */
  @VisibleForTesting
  private <T extends ResourceUpdate> CpcWithFallbackState manageControlPlaneClient(
      ResourceSubscriber<T> subscriber) {

    ControlPlaneClient cpcToUse;
    boolean didFallback = false;
    try {
      cpcToUse = getOrCreateControlPlaneClient(subscriber.authority);
    } catch (IllegalArgumentException e) {
      if (subscriber.errorDescription == null) {
        subscriber.errorDescription = "Bad configuration:  " + e.getMessage();
      }

      subscriber.onError(
          Status.INVALID_ARGUMENT.withDescription(subscriber.errorDescription), null);
      return new CpcWithFallbackState(null, false);
    } catch (IOException e) {
      logger.log(XdsLogLevel.DEBUG,
          "Could not create a control plane client for authority {0}: {1}",
          subscriber.authority, e.getMessage());
      return new CpcWithFallbackState(null, false);
    }

    ControlPlaneClient activeCpClient = getActiveCpc(subscriber.authority);
    if (cpcToUse != activeCpClient) {
      addCpcToAuthority(subscriber.authority, cpcToUse); // makes it active
      if (activeCpClient != null) {
        didFallback = cpcToUse != null && !cpcToUse.isInError();
        if (didFallback) {
          logger.log(XdsLogLevel.INFO, "Falling back to XDS server {0}",
              cpcToUse.getServerInfo().target());
        } else {
          logger.log(XdsLogLevel.WARNING, "No working fallback XDS Servers found from {0}",
              activeCpClient.getServerInfo().target());
        }
      }
    }

    return new CpcWithFallbackState(cpcToUse, didFallback);
  }

  private void addCpcToAuthority(String authority, ControlPlaneClient cpcToUse) {
    List<ControlPlaneClient> controlPlaneClients =
        activatedCpClients.computeIfAbsent(authority, k -> new ArrayList<>());

    if (controlPlaneClients.contains(cpcToUse)) {
      return;
    }

    // if there are any missing CPCs between the last one and cpcToUse, add them + add cpcToUse
    ImmutableList<ServerInfo> serverInfos = getServerInfos(authority);
    for (int i = controlPlaneClients.size(); i < serverInfos.size(); i++) {
      ServerInfo serverInfo = serverInfos.get(i);
      ControlPlaneClient cpc = serverCpClientMap.get(serverInfo);
      controlPlaneClients.add(cpc);
      logger.log(XdsLogLevel.DEBUG, "Adding control plane client {0} to authority {1}",
          cpc, authority);
      cpcToUse.sendDiscoveryRequests();
      if (cpc == cpcToUse) {
        break;
      }
    }
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

          List<ControlPlaneClient> controlPlaneClients =
              activatedCpClients.get(subscriber.authority);
          if (controlPlaneClients != null) {
            controlPlaneClients.forEach((cpc) -> {
              cpc.adjustResourceSubscription(type);
            });
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

  private Set<String> getResourceKeys(XdsResourceType<?> xdsResourceType) {
    if (!resourceSubscribers.containsKey(xdsResourceType)) {
      return null;
    }

    return resourceSubscribers.get(xdsResourceType).keySet();
  }

  // cpcForThisStream is null when doing shutdown
  private void cleanUpResourceTimers(ControlPlaneClient cpcForThisStream) {
    Collection<String> authoritiesForCpc = getActiveAuthorities(cpcForThisStream);
    String target = cpcForThisStream == null ? "null" : cpcForThisStream.getServerInfo().target();
    logger.log(XdsLogLevel.DEBUG, "Cleaning up resource timers for CPC {0}, authorities {1}",
        target, authoritiesForCpc);

    for (Map<String, ResourceSubscriber<?>> subscriberMap : resourceSubscribers.values()) {
      for (ResourceSubscriber<?> subscriber : subscriberMap.values()) {
        if (cpcForThisStream == null || authoritiesForCpc.contains(subscriber.authority)) {
          subscriber.stopTimer();
        }
      }
    }
  }

  private ControlPlaneClient getOrCreateControlPlaneClient(String authority) throws IOException {
    // Optimize for the common case of a working ads stream already exists for the authority
    ControlPlaneClient activeCpc = getActiveCpc(authority);
    if (activeCpc != null && !activeCpc.isInError()) {
      return activeCpc;
    }

    ImmutableList<ServerInfo> serverInfos = getServerInfos(authority);
    if (serverInfos == null) {
      throw new IllegalArgumentException("No xds servers found for authority " + authority);
    }

    for (ServerInfo serverInfo : serverInfos) {
      ControlPlaneClient cpc = getOrCreateControlPlaneClient(serverInfo);
      if (cpc.isInError()) {
        continue;
      }
      return cpc;
    }

    // Everything existed and is in backoff so throw
    throw new IOException("All xds transports for authority " + authority + " are in backoff");
  }

  private ControlPlaneClient getOrCreateControlPlaneClient(ServerInfo serverInfo) {
    syncContext.throwIfNotInThisSynchronizationContext();
    if (serverCpClientMap.containsKey(serverInfo)) {
      return serverCpClientMap.get(serverInfo);
    }

    logger.log(XdsLogLevel.DEBUG, "Creating control plane client for {0}", serverInfo.target());
    XdsTransportFactory.XdsTransport xdsTransport;
    try {
      xdsTransport = xdsTransportFactory.create(serverInfo);
    } catch (Exception e) {
      String msg = String.format("Failed to create xds transport for %s: %s",
          serverInfo.target(), e.getMessage());
      logger.log(XdsLogLevel.WARNING, msg);
      xdsTransport =
          new ControlPlaneClient.FailingXdsTransport(Status.UNAVAILABLE.withDescription(msg));
    }

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

  @SuppressWarnings("unchecked")
  private <T extends ResourceUpdate> void handleResourceUpdate(
      XdsResourceType.Args args, List<Any> resources, XdsResourceType<T> xdsResourceType,
      boolean isFirstResponse, ProcessingTracker processingTracker) {
    ControlPlaneClient controlPlaneClient = serverCpClientMap.get(args.serverInfo);

    if (isFirstResponse) {
      shutdownLowerPriorityCpcs(controlPlaneClient);
    }

    ValidatedResourceUpdate<T> result = xdsResourceType.parse(args, resources);
    logger.log(XdsLogger.XdsLogLevel.INFO,
        "Received {0} Response version {1} nonce {2}. Parsed resources: {3}",
        xdsResourceType.typeName(), args.versionInfo, args.nonce, result.unpackedResources);
    Map<String, ParsedResource<T>> parsedResources = result.parsedResources;
    Set<String> invalidResources = result.invalidResources;
    metricReporter.reportResourceUpdates(Long.valueOf(parsedResources.size()),
        Long.valueOf(invalidResources.size()),
        args.getServerInfo().target(), xdsResourceType.typeUrl());

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
      if (getActiveCpc(subscriber.authority) == controlPlaneClient) {
        subscriber.onAbsent(processingTracker, args.serverInfo);
      }
    }
  }

  @Override
  public Future<Void> reportServerConnections(ServerConnectionCallback callback) {
    SettableFuture<Void> future = SettableFuture.create();
    syncContext.execute(() -> {
      serverCpClientMap.forEach((serverInfo, controlPlaneClient) ->
          callback.reportServerConnectionGauge(
              !controlPlaneClient.isInError(), serverInfo.target()));
      future.set(null);
    });
    return future;
  }

  private void shutdownLowerPriorityCpcs(ControlPlaneClient activatedCpc) {
    // For each authority, remove any control plane clients, with lower priority than the activated
    // one, from activatedCpClients storing them all in cpcsToShutdown.
    Set<ControlPlaneClient> cpcsToShutdown = new HashSet<>();
    for ( List<ControlPlaneClient> cpcsForAuth : activatedCpClients.values()) {
      if (cpcsForAuth == null) {
        continue;
      }
      int index = cpcsForAuth.indexOf(activatedCpc);
      if (index > -1) {
        cpcsToShutdown.addAll(cpcsForAuth.subList(index + 1, cpcsForAuth.size()));
        cpcsForAuth.subList(index + 1, cpcsForAuth.size()).clear(); // remove lower priority cpcs
      }
    }

    // Shutdown any lower priority control plane clients identified above that aren't still being
    // used by another authority.  If they are still being used let the XDS server know that we
    // no longer are interested in subscriptions for authorities we are no longer responsible for.
    for (ControlPlaneClient cpc : cpcsToShutdown) {
      if (activatedCpClients.values().stream().noneMatch(list -> list.contains(cpc))) {
        cpc.shutdown();
        serverCpClientMap.remove(cpc.getServerInfo());
      } else {
        cpc.sendDiscoveryRequests();
      }
    }
  }


  /** Tracks a single subscribed resource. */
  private final class ResourceSubscriber<T extends ResourceUpdate> {
    @Nullable
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

    ResourceSubscriber(XdsResourceType<T> type, String resource) {
      syncContext.throwIfNotInThisSynchronizationContext();
      this.type = type;
      this.resource = resource;
      this.authority = getAuthority(resource);
      if (getServerInfos(authority) == null) {
        this.errorDescription = "Wrong configuration: xds server does not exist for resource "
            + resource;
        return;
      }

      // Initialize metadata in UNKNOWN state to cover the case when resource subscriber,
      // is created but not yet requested because the client is in backoff.
      this.metadata = ResourceMetadata.newResourceMetadataUnknown();
    }

    @Override
    public String toString() {
      return "ResourceSubscriber{"
          + "resource='" + resource + '\''
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
      ControlPlaneClient activeCpc = getActiveCpc(authority);
      if (activeCpc == null || !activeCpc.isReady()) {
        // When client becomes ready, it triggers a restartTimer for all relevant subscribers.
        return;
      }

      class ResourceNotFound implements Runnable {
        @Override
        public void run() {
          logger.log(XdsLogLevel.INFO, "{0} resource {1} initial fetch timeout",
              type, resource);
          respTimer = null;
          onAbsent(null, activeCpc.getServerInfo());
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
      ResourceUpdate oldData = this.data;
      this.data = parsedResource.getResourceUpdate();
      this.metadata = ResourceMetadata
          .newResourceMetadataAcked(parsedResource.getRawResource(), version, updateTime);
      absent = false;
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
      ControlPlaneClient activeCpc = getActiveCpc(authority);
      return (activeCpc != null)
             ? activeCpc.getServerInfo().target()
             : "unknown";
    }

    void onAbsent(@Nullable ProcessingTracker processingTracker, ServerInfo serverInfo) {
      if (respTimer != null && respTimer.isPending()) {  // too early to conclude absence
        return;
      }

      // Ignore deletion of State of the World resources when this feature is on,
      // and the resource is reusable.
      boolean ignoreResourceDeletionEnabled = serverInfo.ignoreResourceDeletion();
      if (ignoreResourceDeletionEnabled && type.isFullStateOfTheWorld() && data != null) {
        if (!resourceDeletionIgnored) {
          logger.log(XdsLogLevel.FORCE_WARNING,
              "xds server {0}: ignoring deletion for resource type {1} name {2}}",
              serverInfo.target(), type, resource);
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
          .newResourceMetadataNacked(metadata, rejectedVersion, rejectedTime, rejectedDetails,
              data != null);
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
        List<Any> resources, String nonce, boolean isFirstResponse,
        ProcessingTracker processingTracker) {
      checkNotNull(xdsResourceType, "xdsResourceType");
      syncContext.throwIfNotInThisSynchronizationContext();
      Set<String> toParseResourceNames =
          xdsResourceType.shouldRetrieveResourceKeysForArgs()
          ? getResourceKeys(xdsResourceType)
          : null;
      XdsResourceType.Args args = new XdsResourceType.Args(serverInfo, versionInfo, nonce,
          bootstrapInfo, securityConfig, toParseResourceNames);
      handleResourceUpdate(args, resources, xdsResourceType, isFirstResponse, processingTracker);
    }

    @Override
    public void handleStreamClosed(Status status, boolean shouldTryFallback) {
      syncContext.throwIfNotInThisSynchronizationContext();

      ControlPlaneClient cpcClosed = serverCpClientMap.get(serverInfo);
      if (cpcClosed == null) {
        logger.log(XdsLogLevel.DEBUG,
            "Couldn't find closing CPC for {0}, so skipping cleanup and reporting", serverInfo);
        return;
      }

      cleanUpResourceTimers(cpcClosed);

      if (status.isOk()) {
        return; // Not considered an error
      }

      metricReporter.reportServerFailure(1L, serverInfo.target());

      Collection<String> authoritiesForClosedCpc = getActiveAuthorities(cpcClosed);
      for (Map<String, ResourceSubscriber<? extends ResourceUpdate>> subscriberMap :
          resourceSubscribers.values()) {
        for (ResourceSubscriber<? extends ResourceUpdate> subscriber : subscriberMap.values()) {
          if (subscriber.hasResult() || !authoritiesForClosedCpc.contains(subscriber.authority)) {
            continue;
          }

          // try to fallback to lower priority control plane client
          if (shouldTryFallback && manageControlPlaneClient(subscriber).didFallback) {
            authoritiesForClosedCpc.remove(subscriber.authority);
            if (authoritiesForClosedCpc.isEmpty()) {
              return; // optimization: no need to continue once all authorities have done fallback
            }
            continue; // since we did fallback, don't consider it an error
          }

          subscriber.onError(status, null);
        }
      }
    }

  }

  private static class CpcWithFallbackState {
    ControlPlaneClient cpc;
    boolean didFallback;

    private CpcWithFallbackState(ControlPlaneClient cpc, boolean didFallback) {
      this.cpc = cpc;
      this.didFallback = didFallback;
    }
  }

  private Collection<String> getActiveAuthorities(ControlPlaneClient cpc) {
    List<String> asList = activatedCpClients.entrySet().stream()
        .filter(entry -> !entry.getValue().isEmpty()
            && cpc == entry.getValue().get(entry.getValue().size() - 1))
        .map(Map.Entry::getKey)
        .collect(Collectors.toList());

    // Since this is usually used for contains, use a set when the list is large
    return (asList.size() < 100) ? asList : new HashSet<>(asList);
  }

}
