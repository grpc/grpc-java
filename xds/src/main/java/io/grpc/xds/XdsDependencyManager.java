/*
 * Copyright 2024 The gRPC Authors
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
import static io.grpc.xds.client.XdsClient.ResourceUpdate;
import static io.grpc.xds.client.XdsLogger.XdsLogLevel.DEBUG;

import io.grpc.InternalLogId;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.xds.XdsRouteConfigureResource.RdsUpdate;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsClient.ResourceWatcher;
import io.grpc.xds.client.XdsLogger;
import io.grpc.xds.client.XdsResourceType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * This class acts as a layer of indirection between the XdsClient and the NameResolver. It
 * maintains the watchers for the xds resources and when an update is received, it either requests
 * referenced resources or updates the XdsConfig and notifies the XdsConfigWatcher.
 */
final class XdsDependencyManager implements XdsClusterSubscriptionRegistry {
  private final XdsClient xdsClient;
  private final XdsConfigWatcher xdsConfigWatcher;
  private final SynchronizationContext syncContext;
  private final String dataPlaneAuthority;
  private final Map<String, Set<ClusterSubscription>> clusterSubscriptions = new HashMap<>();

  private final InternalLogId logId;
  private final XdsLogger logger;
  private XdsConfig lastXdsConfig = null;
  private Map<XdsResourceType<?>, TypeWatchers<?>> resourceWatchers = new HashMap<>();

  XdsDependencyManager(XdsClient xdsClient, XdsConfigWatcher xdsConfigWatcher,
                              SynchronizationContext syncContext, String dataPlaneAuthority,
                              String listenerName) {
    logId = InternalLogId.allocate("xds-dependency-manager", listenerName);
    logger = XdsLogger.withLogId(logId);
    this.xdsClient = xdsClient;
    this.xdsConfigWatcher = xdsConfigWatcher;
    this.syncContext = syncContext;
    this.dataPlaneAuthority = dataPlaneAuthority;

    // start the ball rolling
    addWatcher(new LdsWatcher(listenerName));
  }

  @Override
  public ClusterSubscription subscribeToCluster(String clusterName) {
    checkNotNull(clusterName, "clusterName");
    ClusterSubscription subscription = new ClusterSubscriptionImpl(clusterName);

    Set<ClusterSubscription> localSubscriptions =
        clusterSubscriptions.computeIfAbsent(clusterName, k -> new HashSet<>());
    localSubscriptions.add(subscription);
    addWatcher(new CdsWatcher(clusterName));

    return subscription;
  }

  @Override
  public void releaseSubscription(ClusterSubscription subscription) {
    checkNotNull(subscription, "subscription");
    String clusterName = subscription.getClusterName();
    Set<ClusterSubscription> subscriptions = clusterSubscriptions.get(clusterName);
    if (subscriptions == null) {
      logger.log(DEBUG, "Subscription already released for {0}", clusterName);
      return;
    }

    subscriptions.remove(subscription);
    if (subscriptions.isEmpty()) {
      clusterSubscriptions.remove(clusterName);
      // TODO release XdsClient watches for this cluster and its endpoint
    }
  }

  @Override
  public void refreshDynamicSubscriptions() {
    // TODO: implement
  }

  @SuppressWarnings("unchecked")
  private <T extends ResourceUpdate> void addWatcher(XdsWatcherBase<T> watcher) {
    XdsResourceType<T> type = watcher.resourceContext.getResourceType();
    String resourceName = watcher.resourceContext.resourceName;

    this.syncContext.executeLater(() -> {
      TypeWatchers<T> typeWatchers = (TypeWatchers<T>)resourceWatchers.get(type);
      if (typeWatchers == null) {
        typeWatchers = new TypeWatchers<>(type);
        resourceWatchers.put(type, typeWatchers);
      }

      typeWatchers.add(resourceName, watcher);
      xdsClient.watchXdsResource(type, resourceName, watcher);
    });
  }


  public void shutdown() {
    for (TypeWatchers<?> watchers : resourceWatchers.values()) {
      shutdownWatchersForType(watchers);
    }
  }

  private <T extends ResourceUpdate> void shutdownWatchersForType(TypeWatchers<T> watchers) {
    for (Map.Entry<String, ResourceWatcher<T>> watcherEntry : watchers.watchers.entrySet()) {
      xdsClient.cancelXdsResourceWatch(watchers.resourceType, watcherEntry.getKey(),
          watcherEntry.getValue());
    }
  }

  @Override
  public String toString() {
    return logId.toString();
  }

  private static class TypeWatchers<T extends ResourceUpdate> {
    Map<String, ResourceWatcher<T>> watchers;
    XdsResourceType<T> resourceType;

    TypeWatchers(XdsResourceType<T> resourceType) {
      watchers = new HashMap<>();
      this.resourceType = resourceType;
    }

    public void add(String resourceName, ResourceWatcher<T> watcher) {
      watchers.put(resourceName, watcher);
    }
  }


  public static class ResourceContext<T extends ResourceUpdate> {
    String resourceName;
    XdsResourceType<T> resourceType;

    public ResourceContext(XdsResourceType<T> resourceType, String resourceName) {
      this.resourceName = resourceName;
      this.resourceType = resourceType;
    }

    public XdsResourceType<T> getResourceType() {
      return resourceType;
    }
  }

  public interface XdsConfigWatcher {

    void onUpdate(XdsConfig config);

    void onError(ResourceContext<?> resourceContext, Status status);

    void onResourceDoesNotExist(ResourceContext<?> resourceContext);
  }

  public static final class ClusterSubscriptionImpl implements ClusterSubscription {
    String clusterName;

    public ClusterSubscriptionImpl(String clusterName) {
      this.clusterName = clusterName;
    }

    @Override
    public String getClusterName() {
      return clusterName;
    }
  }

  private abstract class XdsWatcherBase<T extends ResourceUpdate> implements ResourceWatcher<T> {
    final ResourceContext<T> resourceContext;

    private XdsWatcherBase(XdsResourceType<T> type, String resourceName) {
      this.resourceContext = new ResourceContext<>(type, resourceName);
    }

    @Override
    public void onError(Status error) {
      xdsConfigWatcher.onError(resourceContext, error);
    }

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      xdsConfigWatcher.onResourceDoesNotExist(resourceContext);
    }
  }

  private class LdsWatcher extends XdsWatcherBase<XdsListenerResource.LdsUpdate> {

    private LdsWatcher(String resourceName) {
      super(XdsListenerResource.getInstance(), resourceName);
    }

    @Override
    public void onChanged(XdsListenerResource.LdsUpdate update) {
       // TODO: process the update and add an RdsWatcher if needed
       //  else see if we should publish a new XdsConfig
    }
  }

  private class RdsWatcher extends XdsWatcherBase<RdsUpdate> {

    public RdsWatcher(String resourceName) {
      super(XdsRouteConfigureResource.getInstance(), resourceName);
    }

    @Override
    public void onChanged(RdsUpdate update) {
      // TODO: process the update and add CdsWatchers for all virtual hosts as needed
      //  If none needed see if we should publish a new XdsConfig
    }
  }


  private class CdsWatcher extends XdsWatcherBase<XdsClusterResource.CdsUpdate> {

    private CdsWatcher(String resourceName) {
      super(XdsClusterResource.getInstance(), resourceName);
    }

    @Override
    public void onChanged(XdsClusterResource.CdsUpdate update) {
      // TODO: process the update and add an EdsWatcher if needed
      //  else see if we should publish a new XdsConfig
    }
  }

  private class EdsWatcher extends XdsWatcherBase<XdsEndpointResource.EdsUpdate> {
    private EdsWatcher(String resourceName) {
      super(XdsEndpointResource.getInstance(), resourceName);
    }

    @Override
    public void onChanged(XdsEndpointResource.EdsUpdate update) {
      // TODO: process the update and see if we should publish a new XdsConfig
    }
  }
}
