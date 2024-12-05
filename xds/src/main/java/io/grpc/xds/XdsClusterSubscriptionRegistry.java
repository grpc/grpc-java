package io.grpc.xds;

public interface XdsClusterSubscriptionRegistry {
  ClusterSubscription subscribeToCluster(String clusterName);
  void releaseSubscription(ClusterSubscription subscription);
  void refreshDynamicSubscriptions();

  interface ClusterSubscription {
    String getClusterName();
  }
}
