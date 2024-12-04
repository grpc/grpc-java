package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.xds.client.XdsLogger.XdsLogLevel.DEBUG;

import io.grpc.InternalLogId;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.SynchronizationContext;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsLogger;
import io.grpc.xds.client.XdsResourceType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class XdsDependencyManager {
  private final XdsClient xdsClient;
  private final XdsConfigWatcher xdsConfigWatcher;
  private final SynchronizationContext syncContext;
  private final String dataPlaneAuthority;
  private final Map<String, List<ClusterSubscription>> clusterSubscriptions = new HashMap<>();
  private final InternalLogId logId;
  private final XdsLogger logger;
  private XdsConfig lastXdsConfig = null;
  private Map<XdsResourceType, Map<String, XdsClient.ResourceWatcher>> resourceWatchers
      = new HashMap<>();

  public XdsDependencyManager(XdsClient xdsClient, XdsConfigWatcher xdsConfigWatcher,
                              SynchronizationContext syncContext, String dataPlaneAuthority,
                              String listenerName)
  {
    logId = InternalLogId.allocate("xds-dependency-manager", listenerName);
    logger = XdsLogger.withLogId(logId);
    this.xdsClient = xdsClient;
    this.xdsConfigWatcher = xdsConfigWatcher;
    this.syncContext = syncContext;
    this.dataPlaneAuthority = dataPlaneAuthority;

    // start the ball rolling
    this.syncContext.executeLater(() -> {
      ResourceContext listenerContext =
          new ResourceContext(XdsListenerResource.getInstance(), listenerName);
      LdsWatcher ldsWatcher = new LdsWatcher(listenerContext);
      resourceWatchers.computeIfAbsent(listenerContext.resourceType, k -> new HashMap<>())
          .put(listenerContext.resourceName, ldsWatcher);
      xdsClient.watchXdsResource(listenerContext.resourceType, listenerName, ldsWatcher);
    });
  }

  public ClusterSubscription subscribeToCluster(String clusterName) {
    checkNotNull(clusterName, "clusterName");
    ClusterSubscription subscription = new ClusterSubscription(clusterName);
    clusterSubscriptions.computeIfAbsent(clusterName, k -> new ArrayList<>()).add(subscription);

    // TODO add XdsClient watches for this cluster, children and their endpoints
    return subscription;
  }

  public void releaseSubscription(ClusterSubscription subscription) {
    checkNotNull(subscription, "subscription");
    List<ClusterSubscription> subscriptionList =
        clusterSubscriptions.get(subscription.clusterName);
    if (subscriptionList == null) {
      logger.log(DEBUG, "Subscription already released {0}", subscription.clusterName);
      return;
    }

    subscriptionList.remove(subscription);
    if (subscriptionList.isEmpty()) {
      clusterSubscriptions.remove(subscription.clusterName);
      // TODO release XdsClient watches for this cluster, children and their endpoints
    }
  }

  public void shutdown() {
    for (Map.Entry<XdsResourceType, Map<String, XdsClient.ResourceWatcher>> typeMapEntry
        : resourceWatchers.entrySet()) {
      for (Map.Entry<String, XdsClient.ResourceWatcher> watcherEntry :
          typeMapEntry.getValue().entrySet()) {
        xdsClient.cancelXdsResourceWatch(typeMapEntry.getKey(), watcherEntry.getKey(),
            watcherEntry.getValue());
      }
    }
  }

  public static class ResourceContext {
    String resourceName;
    XdsResourceType resourceType;

    public ResourceContext(XdsResourceType resourceType, String resourceName) {
      this.resourceName = resourceName;
      this.resourceType = resourceType;
    };
  }

  public interface XdsConfigWatcher {
    void OnUpdate(XdsConfig config);
    void OnError(ResourceContext resourceContext, Status status);
    void OnResourceDoesNotExist(ResourceContext resourceContext);
  }

  public static final class ClusterSubscription {
    String clusterName;

    public ClusterSubscription(String clusterName) {
      this.clusterName = clusterName;
    }
  }

  /**
   * A full XDS configuration from listener through endpoints
   */
  public static class XdsConfig {
    public final XdsListenerResource listener;
    public final XdsRouteConfigureResource route;
    public final Map<String, StatusOr<XdsClusterConfig>> clusters;

    public XdsConfig(XdsListenerResource listener, XdsRouteConfigureResource route,
                     Map<String, StatusOr<XdsClusterConfig>> clusters) {
      this.listener = listener;
      this.route = route;
      this.clusters = clusters;
    }

    public static class XdsClusterConfig {
      public final String clusterName;
      public final XdsClusterResource cluster;
      public final XdsEndpointResource endpoint;

      public XdsClusterConfig(String clusterName, XdsClusterResource cluster,
                              XdsEndpointResource endpoint) {
        this.clusterName = clusterName;
        this.cluster = cluster;
        this.endpoint = endpoint;
      }
    }
  }

  private class LdsWatcher implements XdsClient.ResourceWatcher<XdsListenerResource.LdsUpdate> {
    ResourceContext resourceContext;

    public LdsWatcher(ResourceContext resourceContext) {
      this.resourceContext = resourceContext;
    }

    @Override
    public void onError(Status error) {
      xdsConfigWatcher.OnError(listenerContext, error);
    }

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      xdsConfigWatcher.OnResourceDoesNotExist(listenerContext);
    }

    @Override
    public void onChanged(XdsListenerResource.LdsUpdate update) {
       // TODO: process the update
    }
  }

  private class RdsWatcher
      implements XdsClient.ResourceWatcher<XdsRouteConfigureResource.RdsUpdate> {
    ResourceContext resourceContext;

    public RdsWatcher(ResourceContext resourceContext) {
      this.resourceContext = resourceContext;
    }

    @Override
    public void onError(Status error) {
      xdsConfigWatcher.OnError(resourceContext, error);
    }

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      xdsConfigWatcher.OnResourceDoesNotExist(resourceContext);
    }

    @Override
    public void onChanged(XdsRouteConfigureResource.RdsUpdate update) {
      // TODO: process the update
    }
  }

}
