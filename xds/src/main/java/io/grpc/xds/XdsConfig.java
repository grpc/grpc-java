package io.grpc.xds;

import io.grpc.StatusOr;
import java.util.Map;

public class XdsConfig {
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
    public final XdsClusterResource clusterResource;
    public final XdsEndpointResource endpoint;

    public XdsClusterConfig(String clusterName, XdsClusterResource clusterResource,
                            XdsEndpointResource endpoint) {
      this.clusterName = clusterName;
      this.clusterResource = clusterResource;
      this.endpoint = endpoint;
    }
  }
}
