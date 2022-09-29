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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.xds.AbstractXdsClient.ResourceType.CDS;
import static io.grpc.xds.AbstractXdsClient.ResourceType.EDS;
import static io.grpc.xds.AbstractXdsClient.ResourceType.LDS;
import static io.grpc.xds.AbstractXdsClient.ResourceType.RDS;
import static io.grpc.xds.Bootstrapper.XDSTP_SCHEME;
import static io.grpc.xds.XdsResourceType.ParsedResource;
import static io.grpc.xds.XdsResourceType.ValidatedResourceUpdate;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.Any;
import io.grpc.ChannelCredentials;
import io.grpc.Context;
import io.grpc.Grpc;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.TimeProvider;
import io.grpc.xds.AbstractXdsClient.ResourceType;
import io.grpc.xds.Bootstrapper.AuthorityInfo;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.LoadStatsManager2.ClusterDropStats;
import io.grpc.xds.LoadStatsManager2.ClusterLocalityStats;
import io.grpc.xds.XdsClient.ResourceStore;
import io.grpc.xds.XdsClient.XdsResponseHandler;
import io.grpc.xds.XdsClusterResource.CdsUpdate;
import io.grpc.xds.XdsListenerResource.LdsUpdate;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * XdsClient implementation for client side usages.
 */
final class ClientXdsClient extends XdsClient implements XdsResponseHandler, ResourceStore {

  // Longest time to wait, since the subscription to some resource, for concluding its absence.
  @VisibleForTesting
  static final int INITIAL_RESOURCE_FETCH_TIMEOUT_SEC = 15;
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          logger.log(
              XdsLogLevel.ERROR,
              "Uncaught exception in XdsClient SynchronizationContext. Panic!",
              e);
          // TODO(chengyuanzhang): better error handling.
          throw new AssertionError(e);
        }
      });
  private final FilterRegistry filterRegistry = FilterRegistry.getDefaultRegistry();
  private final LoadBalancerRegistry loadBalancerRegistry
      = LoadBalancerRegistry.getDefaultRegistry();
  private final Map<ServerInfo, AbstractXdsClient> serverChannelMap = new HashMap<>();
  private final Map<XdsResourceType<? extends ResourceUpdate>,
      Map<String, ResourceSubscriber<? extends ResourceUpdate>>>
      resourceSubscribers = new HashMap<>();
  private final Map<ResourceType, XdsResourceType<? extends ResourceUpdate>> xdsResourceTypeMap =
      ImmutableMap.of(
      LDS, XdsListenerResource.getInstance(),
      RDS, XdsRouteConfigureResource.getInstance(),
      CDS, XdsClusterResource.getInstance(),
      EDS, XdsEndpointResource.getInstance());
  private final LoadStatsManager2 loadStatsManager;
  private final Map<ServerInfo, LoadReportClient> serverLrsClientMap = new HashMap<>();
  private final XdsChannelFactory xdsChannelFactory;
  private final Bootstrapper.BootstrapInfo bootstrapInfo;
  private final Context context;
  private final ScheduledExecutorService timeService;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final Supplier<Stopwatch> stopwatchSupplier;
  private final TimeProvider timeProvider;
  private boolean reportingLoad;
  private final TlsContextManager tlsContextManager;
  private final InternalLogId logId;
  private final XdsLogger logger;
  private volatile boolean isShutdown;

  // TODO(zdapeng): rename to XdsClientImpl
  ClientXdsClient(
      XdsChannelFactory xdsChannelFactory,
      Bootstrapper.BootstrapInfo bootstrapInfo,
      Context context,
      ScheduledExecutorService timeService,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier,
      TimeProvider timeProvider,
      TlsContextManager tlsContextManager) {
    this.xdsChannelFactory = xdsChannelFactory;
    this.bootstrapInfo = bootstrapInfo;
    this.context = context;
    this.timeService = timeService;
    loadStatsManager = new LoadStatsManager2(stopwatchSupplier);
    this.backoffPolicyProvider = backoffPolicyProvider;
    this.stopwatchSupplier = stopwatchSupplier;
    this.timeProvider = timeProvider;
    this.tlsContextManager = checkNotNull(tlsContextManager, "tlsContextManager");
    logId = InternalLogId.allocate("xds-client", null);
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogLevel.INFO, "Created");
  }

  private void maybeCreateXdsChannelWithLrs(ServerInfo serverInfo) {
    syncContext.throwIfNotInThisSynchronizationContext();
    if (serverChannelMap.containsKey(serverInfo)) {
      return;
    }
    AbstractXdsClient xdsChannel = new AbstractXdsClient(
        xdsChannelFactory,
        serverInfo,
        bootstrapInfo.node(),
        this,
        this,
        context,
        timeService,
        syncContext,
        backoffPolicyProvider,
        stopwatchSupplier);
    LoadReportClient lrsClient = new LoadReportClient(
        loadStatsManager, xdsChannel.channel(), context, serverInfo.useProtocolV3(),
        bootstrapInfo.node(), syncContext, timeService, backoffPolicyProvider, stopwatchSupplier);
    serverChannelMap.put(serverInfo, xdsChannel);
    serverLrsClientMap.put(serverInfo, lrsClient);
  }

  @Override
  public void handleResourceResponse(
      ResourceType resourceType, ServerInfo serverInfo, String versionInfo, List<Any> resources,
      String nonce) {
    syncContext.throwIfNotInThisSynchronizationContext();
    XdsResourceType<? extends ResourceUpdate> xdsResourceType =
        xdsResourceTypeMap.get(resourceType);
    if (xdsResourceType == null) {
      logger.log(XdsLogLevel.WARNING, "Ignore an unknown type of DiscoveryResponse");
      return;
    }
    Set<String> toParseResourceNames = null;
    if (!(resourceType == LDS || resourceType == RDS)
        && resourceSubscribers.containsKey(xdsResourceType)) {
      toParseResourceNames = resourceSubscribers.get(xdsResourceType).keySet();
    }
    XdsResourceType.Args args = new XdsResourceType.Args(serverInfo, versionInfo, nonce,
        bootstrapInfo, filterRegistry, loadBalancerRegistry, tlsContextManager,
        toParseResourceNames);
    handleResourceUpdate(args, resources, xdsResourceType);
  }

  @Override
  public void handleStreamClosed(Status error) {
    syncContext.throwIfNotInThisSynchronizationContext();
    cleanUpResourceTimers();
    for (Map<String, ResourceSubscriber<? extends ResourceUpdate>> subscriberMap :
        resourceSubscribers.values()) {
      for (ResourceSubscriber<? extends ResourceUpdate> subscriber : subscriberMap.values()) {
        subscriber.onError(error);
      }
    }
  }

  @Override
  public void handleStreamRestarted(ServerInfo serverInfo) {
    syncContext.throwIfNotInThisSynchronizationContext();
    for (Map<String, ResourceSubscriber<? extends ResourceUpdate>> subscriberMap :
        resourceSubscribers.values()) {
      for (ResourceSubscriber<? extends ResourceUpdate> subscriber : subscriberMap.values()) {
        if (subscriber.serverInfo.equals(serverInfo)) {
          subscriber.restartTimer();
        }
      }
    }
  }

  @Override
  void shutdown() {
    syncContext.execute(
        new Runnable() {
          @Override
          public void run() {
            if (isShutdown) {
              return;
            }
            isShutdown = true;
            for (AbstractXdsClient xdsChannel : serverChannelMap.values()) {
              xdsChannel.shutdown();
            }
            if (reportingLoad) {
              for (final LoadReportClient lrsClient : serverLrsClientMap.values()) {
                lrsClient.stopLoadReporting();
              }
            }
            cleanUpResourceTimers();
          }
        });
  }

  @Override
  boolean isShutDown() {
    return isShutdown;
  }

  private Map<String, ResourceSubscriber<? extends ResourceUpdate>> getSubscribedResourcesMap(
      ResourceType type) {
    return resourceSubscribers.getOrDefault(xdsResourceTypeMap.get(type), Collections.emptyMap());
  }

  @Nullable
  @Override
  public XdsResourceType<? extends ResourceUpdate> getXdsResourceType(ResourceType type) {
    return xdsResourceTypeMap.get(type);
  }

  @Nullable
  @Override
  public Collection<String> getSubscribedResources(ServerInfo serverInfo,
                                                   ResourceType type) {
    Map<String, ResourceSubscriber<? extends ResourceUpdate>> resources =
        getSubscribedResourcesMap(type);
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();
    for (String key : resources.keySet()) {
      if (resources.get(key).serverInfo.equals(serverInfo)) {
        builder.add(key);
      }
    }
    Collection<String> retVal = builder.build();
    return retVal.isEmpty() ? null : retVal;
  }

  @Override
  ListenableFuture<Map<ResourceType, Map<String, ResourceMetadata>>>
      getSubscribedResourcesMetadataSnapshot() {
    final SettableFuture<Map<ResourceType, Map<String, ResourceMetadata>>> future =
        SettableFuture.create();
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        // A map from a "resource type" to a map ("resource name": "resource metadata")
        ImmutableMap.Builder<ResourceType, Map<String, ResourceMetadata>> metadataSnapshot =
            ImmutableMap.builder();
        for (XdsResourceType<? extends ResourceUpdate> resourceType: xdsResourceTypeMap.values()) {
          ImmutableMap.Builder<String, ResourceMetadata> metadataMap = ImmutableMap.builder();
          Map<String, ResourceSubscriber<? extends ResourceUpdate>> resourceSubscriberMap =
              resourceSubscribers.getOrDefault(resourceType, Collections.emptyMap());
          for (Map.Entry<String, ResourceSubscriber<? extends ResourceUpdate>> resourceEntry
              : resourceSubscriberMap.entrySet()) {
            metadataMap.put(resourceEntry.getKey(), resourceEntry.getValue().metadata);
          }
          metadataSnapshot.put(resourceType.typeName(), metadataMap.buildOrThrow());
        }
        future.set(metadataSnapshot.buildOrThrow());
      }
    });
    return future;
  }

  @Override
  TlsContextManager getTlsContextManager() {
    return tlsContextManager;
  }

  @Override
  <T extends ResourceUpdate> void watchXdsResource(XdsResourceType<T> type, String resourceName,
                                                   ResourceWatcher<T> watcher) {
    syncContext.execute(new Runnable() {
      @Override
      @SuppressWarnings("unchecked")
      public void run() {
        if (!resourceSubscribers.containsKey(type)) {
          resourceSubscribers.put(type, new HashMap<>());
        }
        ResourceSubscriber<T> subscriber =
            (ResourceSubscriber<T>) resourceSubscribers.get(type).get(resourceName);;
        if (subscriber == null) {
          logger.log(XdsLogLevel.INFO, "Subscribe {0} resource {1}", type, resourceName);
          subscriber = new ResourceSubscriber<>(type.typeName(), resourceName);
          resourceSubscribers.get(type).put(resourceName, subscriber);
          if (subscriber.xdsChannel != null) {
            subscriber.xdsChannel.adjustResourceSubscription(type);
          }
        }
        subscriber.addWatcher(watcher);
      }
    });
  }

  @Override
  <T extends ResourceUpdate> void cancelXdsResourceWatch(XdsResourceType<T> type,
                                                         String resourceName,
                                                         ResourceWatcher<T> watcher) {
    syncContext.execute(new Runnable() {
      @Override
      @SuppressWarnings("unchecked")
      public void run() {
        ResourceSubscriber<T> subscriber =
            (ResourceSubscriber<T>) resourceSubscribers.get(type).get(resourceName);;
        subscriber.removeWatcher(watcher);
        if (!subscriber.isWatched()) {
          subscriber.cancelResourceWatch();
          resourceSubscribers.get(type).remove(resourceName);
          if (subscriber.xdsChannel != null) {
            subscriber.xdsChannel.adjustResourceSubscription(type);
          }
          if (resourceSubscribers.get(type).isEmpty()) {
            resourceSubscribers.remove(type);
          }
        }
      }
    });
  }

  @Override
  ClusterDropStats addClusterDropStats(
      final ServerInfo serverInfo, String clusterName, @Nullable String edsServiceName) {
    ClusterDropStats dropCounter =
        loadStatsManager.getClusterDropStats(clusterName, edsServiceName);
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        if (!reportingLoad) {
          serverLrsClientMap.get(serverInfo).startLoadReporting();
          reportingLoad = true;
        }
      }
    });
    return dropCounter;
  }

  @Override
  ClusterLocalityStats addClusterLocalityStats(
      final ServerInfo serverInfo, String clusterName, @Nullable String edsServiceName,
      Locality locality) {
    ClusterLocalityStats loadCounter =
        loadStatsManager.getClusterLocalityStats(clusterName, edsServiceName, locality);
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        if (!reportingLoad) {
          serverLrsClientMap.get(serverInfo).startLoadReporting();
          reportingLoad = true;
        }
      }
    });
    return loadCounter;
  }

  @Override
  Bootstrapper.BootstrapInfo getBootstrapInfo() {
    return bootstrapInfo;
  }

  @Override
  public String toString() {
    return logId.toString();
  }

  private void cleanUpResourceTimers() {
    for (Map<String, ResourceSubscriber<?>> subscriberMap : resourceSubscribers.values()) {
      for (ResourceSubscriber<?> subscriber : subscriberMap.values()) {
        subscriber.stopTimer();
      }
    }
  }

  @SuppressWarnings("unchecked")
  private <T extends ResourceUpdate> void handleResourceUpdate(XdsResourceType.Args args,
                                                               List<Any> resources,
                                                               XdsResourceType<T> xdsResourceType) {
    ValidatedResourceUpdate<T> result = xdsResourceType.parse(args, resources);
    logger.log(XdsLogger.XdsLogLevel.INFO,
        "Received {0} Response version {1} nonce {2}. Parsed resources: {3}",
         xdsResourceType.typeName(), args.versionInfo, args.nonce, result.unpackedResources);
    Map<String, ParsedResource<T>> parsedResources = result.parsedResources;
    Set<String> invalidResources = result.invalidResources;
    Set<String> retainedResources = result.retainedResources;
    List<String> errors = result.errors;
    String errorDetail = null;
    if (errors.isEmpty()) {
      checkArgument(invalidResources.isEmpty(), "found invalid resources but missing errors");
      serverChannelMap.get(args.serverInfo).ackResponse(xdsResourceType, args.versionInfo,
          args.nonce);
    } else {
      errorDetail = Joiner.on('\n').join(errors);
      logger.log(XdsLogLevel.WARNING,
          "Failed processing {0} Response version {1} nonce {2}. Errors:\n{3}",
          xdsResourceType.typeName(), args.versionInfo, args.nonce, errorDetail);
      serverChannelMap.get(args.serverInfo).nackResponse(xdsResourceType, args.nonce, errorDetail);
    }

    long updateTime = timeProvider.currentTimeNanos();
    for (Map.Entry<String, ResourceSubscriber<?>> entry :
        getSubscribedResourcesMap(xdsResourceType.typeName()).entrySet()) {
      String resourceName = entry.getKey();
      ResourceSubscriber<T> subscriber = (ResourceSubscriber<T>) entry.getValue();

      if (parsedResources.containsKey(resourceName)) {
        // Happy path: the resource updated successfully. Notify the watchers of the update.
        subscriber.onData(parsedResources.get(resourceName), args.versionInfo, updateTime);
        continue;
      }

      if (invalidResources.contains(resourceName)) {
        // The resource update is invalid. Capture the error without notifying the watchers.
        subscriber.onRejected(args.versionInfo, updateTime, errorDetail);
      }

      // Nothing else to do for incremental ADS resources.
      if (xdsResourceType.dependentResource() == null) {
        continue;
      }

      // Handle State of the World ADS: invalid resources.
      if (invalidResources.contains(resourceName)) {
        // The resource is missing. Reuse the cached resource if possible.
        if (subscriber.data != null) {
          retainDependentResource(subscriber, retainedResources);
        } else {
          // No cached data. Notify the watchers of an invalid update.
          subscriber.onError(Status.UNAVAILABLE.withDescription(errorDetail));
        }
        continue;
      }

      // For State of the World services, notify watchers when their watched resource is missing
      // from the ADS update.
      subscriber.onAbsent();
      // Retain any dependent resources if the resource deletion is ignored
      // per bootstrap ignore_resource_deletion server feature.
      if (!subscriber.absent) {
        retainDependentResource(subscriber, retainedResources);
      }
    }

    // LDS/CDS responses represents the state of the world, RDS/EDS resources not referenced in
    // LDS/CDS resources should be deleted.
    if (xdsResourceType.dependentResource() != null) {
      XdsResourceType<?> dependency = xdsResourceTypeMap.get(xdsResourceType.dependentResource());
      Map<String, ResourceSubscriber<? extends ResourceUpdate>> dependentSubscribers =
          resourceSubscribers.get(dependency);
      if (dependentSubscribers == null) {
        return;
      }
      for (String resource : dependentSubscribers.keySet()) {
        if (!retainedResources.contains(resource)) {
          dependentSubscribers.get(resource).onAbsent();
        }
      }
    }
  }

  private void retainDependentResource(
      ResourceSubscriber<? extends ResourceUpdate> subscriber, Set<String> retainedResources) {
    if (subscriber.data == null) {
      return;
    }
    String resourceName = null;
    if (subscriber.type == LDS) {
      LdsUpdate ldsUpdate = (LdsUpdate) subscriber.data;
      io.grpc.xds.HttpConnectionManager hcm = ldsUpdate.httpConnectionManager();
      if (hcm != null) {
        resourceName = hcm.rdsName();
      }
    } else if (subscriber.type == CDS) {
      CdsUpdate cdsUpdate = (CdsUpdate) subscriber.data;
      resourceName = cdsUpdate.edsServiceName();
    }

    if (resourceName != null) {
      retainedResources.add(resourceName);
    }
  }

  /**
   * Tracks a single subscribed resource.
   */
  private final class ResourceSubscriber<T extends ResourceUpdate> {
    @Nullable private final ServerInfo serverInfo;
    @Nullable private final AbstractXdsClient xdsChannel;
    private final ResourceType type;
    private final String resource;
    private final Set<ResourceWatcher<T>> watchers = new HashSet<>();
    @Nullable private T data;
    private boolean absent;
    // Tracks whether the deletion has been ignored per bootstrap server feature.
    // See https://github.com/grpc/proposal/blob/master/A53-xds-ignore-resource-deletion.md
    private boolean resourceDeletionIgnored;
    @Nullable private ScheduledHandle respTimer;
    @Nullable private ResourceMetadata metadata;
    @Nullable private String errorDescription;

    ResourceSubscriber(ResourceType type, String resource) {
      syncContext.throwIfNotInThisSynchronizationContext();
      this.type = type;
      this.resource = resource;
      this.serverInfo = getServerInfo(resource);
      if (serverInfo == null) {
        this.errorDescription = "Wrong configuration: xds server does not exist for resource "
            + resource;
        this.xdsChannel = null;
        return;
      }
      // Initialize metadata in UNKNOWN state to cover the case when resource subscriber,
      // is created but not yet requested because the client is in backoff.
      this.metadata = ResourceMetadata.newResourceMetadataUnknown();
      maybeCreateXdsChannelWithLrs(serverInfo);
      this.xdsChannel = serverChannelMap.get(serverInfo);
      if (xdsChannel.isInBackoff()) {
        return;
      }
      restartTimer();
    }

    @Nullable
    private ServerInfo getServerInfo(String resource) {
      if (BootstrapperImpl.enableFederation && resource.startsWith(XDSTP_SCHEME)) {
        URI uri = URI.create(resource);
        String authority = uri.getAuthority();
        if (authority == null) {
          authority = "";
        }
        AuthorityInfo authorityInfo = bootstrapInfo.authorities().get(authority);
        if (authorityInfo == null || authorityInfo.xdsServers().isEmpty()) {
          return null;
        }
        return authorityInfo.xdsServers().get(0);
      }
      return bootstrapInfo.servers().get(0); // use first server
    }

    void addWatcher(ResourceWatcher<T> watcher) {
      checkArgument(!watchers.contains(watcher), "watcher %s already registered", watcher);
      watchers.add(watcher);
      if (errorDescription != null) {
        watcher.onError(Status.INVALID_ARGUMENT.withDescription(errorDescription));
        return;
      }
      if (data != null) {
        notifyWatcher(watcher, data);
      } else if (absent) {
        watcher.onResourceDoesNotExist(resource);
      }
    }

    void removeWatcher(ResourceWatcher<T>  watcher) {
      checkArgument(watchers.contains(watcher), "watcher %s not registered", watcher);
      watchers.remove(watcher);
    }

    void restartTimer() {
      if (data != null || absent) {  // resource already resolved
        return;
      }
      class ResourceNotFound implements Runnable {
        @Override
        public void run() {
          logger.log(XdsLogLevel.INFO, "{0} resource {1} initial fetch timeout",
              type, resource);
          respTimer = null;
          onAbsent();
        }

        @Override
        public String toString() {
          return type + this.getClass().getSimpleName();
        }
      }

      // Initial fetch scheduled or rescheduled, transition metadata state to REQUESTED.
      metadata = ResourceMetadata.newResourceMetadataRequested();
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
      logger.log(logLevel, message, type, resource,
          serverInfo != null ? serverInfo.target() : "unknown");
    }

    boolean isWatched() {
      return !watchers.isEmpty();
    }

    void onData(ParsedResource<T> parsedResource, String version, long updateTime) {
      if (respTimer != null && respTimer.isPending()) {
        respTimer.cancel();
        respTimer = null;
      }
      this.metadata = ResourceMetadata
          .newResourceMetadataAcked(parsedResource.getRawResource(), version, updateTime);
      ResourceUpdate oldData = this.data;
      this.data = parsedResource.getResourceUpdate();
      absent = false;
      if (resourceDeletionIgnored) {
        logger.log(XdsLogLevel.FORCE_INFO, "xds server {0}: server returned new version "
                + "of resource for which we previously ignored a deletion: type {1} name {2}",
            serverInfo != null ? serverInfo.target() : "unknown", type, resource);
        resourceDeletionIgnored = false;
      }
      if (!Objects.equals(oldData, data)) {
        for (ResourceWatcher<T> watcher : watchers) {
          notifyWatcher(watcher, data);
        }
      }
    }

    void onAbsent() {
      if (respTimer != null && respTimer.isPending()) {  // too early to conclude absence
        return;
      }

      // Ignore deletion of State of the World resources when this feature is on,
      // and the resource is reusable.
      boolean ignoreResourceDeletionEnabled =
          serverInfo != null && serverInfo.ignoreResourceDeletion();
      boolean isStateOfTheWorld = (type == LDS || type == CDS);
      if (ignoreResourceDeletionEnabled && isStateOfTheWorld && data != null) {
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
        for (ResourceWatcher<T> watcher : watchers) {
          watcher.onResourceDoesNotExist(resource);
        }
      }
    }

    void onError(Status error) {
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

      for (ResourceWatcher<T> watcher : watchers) {
        watcher.onError(errorAugmented);
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

  @VisibleForTesting
  static final class ResourceInvalidException extends Exception {
    private static final long serialVersionUID = 0L;

    ResourceInvalidException(String message) {
      super(message, null, false, false);
    }

    ResourceInvalidException(String message, Throwable cause) {
      super(cause != null ? message + ": " + cause.getMessage() : message, cause, false, false);
    }
  }

  abstract static class XdsChannelFactory {
    static final XdsChannelFactory DEFAULT_XDS_CHANNEL_FACTORY = new XdsChannelFactory() {
      @Override
      ManagedChannel create(ServerInfo serverInfo) {
        String target = serverInfo.target();
        ChannelCredentials channelCredentials = serverInfo.channelCredentials();
        return Grpc.newChannelBuilder(target, channelCredentials)
            .keepAliveTime(5, TimeUnit.MINUTES)
            .build();
      }
    };

    abstract ManagedChannel create(ServerInfo serverInfo);
  }
}
