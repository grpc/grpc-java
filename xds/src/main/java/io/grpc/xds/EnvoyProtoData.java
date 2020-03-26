/*
 * Copyright 2019 The gRPC Authors
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import io.envoyproxy.envoy.type.FractionalPercent;
import io.envoyproxy.envoy.type.FractionalPercent.DenominatorType;
import io.grpc.EquivalentAddressGroup;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Defines gRPC data types for Envoy protobuf messages used in xDS protocol. Each data type has
 * the same name as Envoy's corresponding protobuf message, but only with fields used by gRPC.
 *
 * <p>Each data type should define a {@code fromEnvoyProtoXXX} static method to convert an Envoy
 * proto message to an instance of that data type.
 *
 * <p>For data types that need to be sent as protobuf messages, a {@code toEnvoyProtoXXX} instance
 * method is defined to convert an instance to Envoy proto message.
 */
final class EnvoyProtoData {

  // Prevent instantiation.
  private EnvoyProtoData() {
  }

  /**
   * See corresponding Envoy proto message {@link io.envoyproxy.envoy.api.v2.core.Locality}.
   */
  static final class Locality {
    private final String region;
    private final String zone;
    private final String subzone;

    /** Must only be used for testing. */
    @VisibleForTesting
    Locality(String region, String zone, String subzone) {
      this.region = region;
      this.zone = zone;
      this.subzone = subzone;
    }

    static Locality fromEnvoyProtoLocality(io.envoyproxy.envoy.api.v2.core.Locality locality) {
      return new Locality(
          /* region = */ locality.getRegion(),
          /* zone = */ locality.getZone(),
          /* subzone = */ locality.getSubZone());
    }

    io.envoyproxy.envoy.api.v2.core.Locality toEnvoyProtoLocality() {
      return io.envoyproxy.envoy.api.v2.core.Locality.newBuilder()
          .setRegion(region)
          .setZone(zone)
          .setSubZone(subzone)
          .build();
    }

    String getRegion() {
      return region;
    }

    String getZone() {
      return zone;
    }

    String getSubzone() {
      return subzone;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Locality locality = (Locality) o;
      return Objects.equals(region, locality.region)
          && Objects.equals(zone, locality.zone)
          && Objects.equals(subzone, locality.subzone);
    }

    @Override
    public int hashCode() {
      return Objects.hash(region, zone, subzone);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("region", region)
          .add("zone", zone)
          .add("subzone", subzone)
          .toString();
    }
  }

  /**
   * See corresponding Envoy proto message {@link
   * io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints}.
   */
  static final class LocalityLbEndpoints {
    private final List<LbEndpoint> endpoints;
    private final int localityWeight;
    private final int priority;

    /** Must only be used for testing. */
    @VisibleForTesting
    LocalityLbEndpoints(List<LbEndpoint> endpoints, int localityWeight, int priority) {
      this.endpoints = endpoints;
      this.localityWeight = localityWeight;
      this.priority = priority;
    }

    static LocalityLbEndpoints fromEnvoyProtoLocalityLbEndpoints(
        io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints proto) {
      List<LbEndpoint> endpoints = new ArrayList<>(proto.getLbEndpointsCount());
      for (io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint endpoint : proto.getLbEndpointsList()) {
        endpoints.add(LbEndpoint.fromEnvoyProtoLbEndpoint(endpoint));
      }
      return
          new LocalityLbEndpoints(
              endpoints,
              proto.getLoadBalancingWeight().getValue(),
              proto.getPriority());
    }

    List<LbEndpoint> getEndpoints() {
      return Collections.unmodifiableList(endpoints);
    }

    int getLocalityWeight() {
      return localityWeight;
    }

    int getPriority() {
      return priority;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      LocalityLbEndpoints that = (LocalityLbEndpoints) o;
      return localityWeight == that.localityWeight
          && priority == that.priority
          && Objects.equals(endpoints, that.endpoints);
    }

    @Override
    public int hashCode() {
      return Objects.hash(endpoints, localityWeight, priority);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("endpoints", endpoints)
          .add("localityWeight", localityWeight)
          .add("priority", priority)
          .toString();
    }
  }

  /**
   * See corresponding Envoy proto message {@link io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint}.
   */
  static final class LbEndpoint {
    private final EquivalentAddressGroup eag;
    private final int loadBalancingWeight;
    private final boolean isHealthy;

    @VisibleForTesting
    LbEndpoint(String address, int port, int loadBalancingWeight, boolean isHealthy) {
      this(
          new EquivalentAddressGroup(
              new InetSocketAddress(address, port)),
          loadBalancingWeight, isHealthy);
    }

    @VisibleForTesting
    LbEndpoint(EquivalentAddressGroup eag, int loadBalancingWeight, boolean isHealthy) {
      this.eag = eag;
      this.loadBalancingWeight = loadBalancingWeight;
      this.isHealthy = isHealthy;
    }

    static LbEndpoint fromEnvoyProtoLbEndpoint(
        io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint proto) {
      io.envoyproxy.envoy.api.v2.core.SocketAddress socketAddress =
          proto.getEndpoint().getAddress().getSocketAddress();
      InetSocketAddress addr =
          new InetSocketAddress(socketAddress.getAddress(), socketAddress.getPortValue());
      return
          new LbEndpoint(
              new EquivalentAddressGroup(ImmutableList.<java.net.SocketAddress>of(addr)),
              proto.getLoadBalancingWeight().getValue(),
              proto.getHealthStatus() == io.envoyproxy.envoy.api.v2.core.HealthStatus.HEALTHY
                  || proto.getHealthStatus() == io.envoyproxy.envoy.api.v2.core.HealthStatus.UNKNOWN
              );
    }

    EquivalentAddressGroup getAddress() {
      return eag;
    }

    int getLoadBalancingWeight() {
      return loadBalancingWeight;
    }

    boolean isHealthy() {
      return isHealthy;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      LbEndpoint that = (LbEndpoint) o;
      return loadBalancingWeight == that.loadBalancingWeight
          && Objects.equals(eag, that.eag)
          && isHealthy == that.isHealthy;
    }

    @Override
    public int hashCode() {
      return Objects.hash(eag, loadBalancingWeight, isHealthy);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("eag", eag)
          .add("loadBalancingWeight", loadBalancingWeight)
          .add("isHealthy", isHealthy)
          .toString();
    }
  }

  /**
   * See corresponding Envoy proto message {@link
   * io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy.DropOverload}.
   */
  static final class DropOverload {
    private final String category;
    private final int dropsPerMillion;

    /** Must only be used for testing. */
    @VisibleForTesting
    DropOverload(String category, int dropsPerMillion) {
      this.category = category;
      this.dropsPerMillion = dropsPerMillion;
    }

    static DropOverload fromEnvoyProtoDropOverload(
        io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy.DropOverload proto) {
      FractionalPercent percent = proto.getDropPercentage();
      int numerator = percent.getNumerator();
      DenominatorType type = percent.getDenominator();
      switch (type) {
        case TEN_THOUSAND:
          numerator *= 100;
          break;
        case HUNDRED:
          numerator *= 100_00;
          break;
        case MILLION:
          break;
        default:
          throw new IllegalArgumentException("Unknown denominator type of " + percent);
      }

      if (numerator > 1_000_000) {
        numerator = 1_000_000;
      }

      return new DropOverload(proto.getCategory(), numerator);
    }

    String getCategory() {
      return category;
    }

    int getDropsPerMillion() {
      return dropsPerMillion;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      DropOverload that = (DropOverload) o;
      return dropsPerMillion == that.dropsPerMillion && Objects.equals(category, that.category);
    }

    @Override
    public int hashCode() {
      return Objects.hash(category, dropsPerMillion);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("category", category)
          .add("dropsPerMillion", dropsPerMillion)
          .toString();
    }
  }

  /** See corresponding Envoy proto message {@link io.envoyproxy.envoy.api.v2.route.Route}. */
  static final class Route {
    private final RouteMatch routeMatch;
    @Nullable
    private final RouteAction routeAction;

    @VisibleForTesting
    Route(RouteMatch routeMatch, @Nullable RouteAction routeAction) {
      this.routeMatch = routeMatch;
      this.routeAction = routeAction;
    }

    RouteMatch getRouteMatch() {
      return routeMatch;
    }

    Optional<RouteAction> getRouteAction() {
      return Optional.fromNullable(routeAction);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Route route = (Route) o;
      return Objects.equals(routeMatch, route.routeMatch)
          && Objects.equals(routeAction, route.routeAction);
    }

    @Override
    public int hashCode() {
      return Objects.hash(routeMatch, routeAction);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("routeMatch", routeMatch)
          .add("routeAction", routeAction)
          .toString();
    }

    static Route fromEnvoyProtoRoute(io.envoyproxy.envoy.api.v2.route.Route proto) {
      RouteMatch routeMatch = RouteMatch.fromEnvoyProtoRouteMatch(proto.getMatch());
      RouteAction routeAction = null;
      if (proto.hasRoute()) {
        routeAction = RouteAction.fromEnvoyProtoRouteAction(proto.getRoute());
      }
      return new Route(routeMatch, routeAction);
    }
  }

  /** See corresponding Envoy proto message {@link io.envoyproxy.envoy.api.v2.route.RouteMatch}. */
  static final class RouteMatch {
    private final String prefix;
    private final String path;
    private final boolean hasRegex;

    @VisibleForTesting
    RouteMatch(String prefix, String path, boolean hasRegex) {
      this.prefix = prefix;
      this.path = path;
      this.hasRegex = hasRegex;
    }

    String getPrefix() {
      return prefix;
    }

    String getPath() {
      return path;
    }

    boolean hasRegex() {
      return hasRegex;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      RouteMatch that = (RouteMatch) o;
      return hasRegex == that.hasRegex
          && Objects.equals(prefix, that.prefix)
          && Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
      return Objects.hash(prefix, path, hasRegex);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("prefix", prefix)
          .add("path", path)
          .add("hasRegex", hasRegex)
          .toString();
    }

    private static RouteMatch fromEnvoyProtoRouteMatch(
        io.envoyproxy.envoy.api.v2.route.RouteMatch proto) {
      return new RouteMatch(
          proto.getPrefix(), proto.getPath(), !proto.getRegex().isEmpty() || proto.hasSafeRegex());
    }
  }

  /** See corresponding Envoy proto message {@link io.envoyproxy.envoy.api.v2.route.RouteAction}. */
  static final class RouteAction {
    private final String cluster;
    private final String clusterHeader;
    private final List<ClusterWeight> weightedCluster;

    @VisibleForTesting
    RouteAction(String cluster, String clusterHeader, List<ClusterWeight> weightedCluster) {
      this.cluster = cluster;
      this.clusterHeader = clusterHeader;
      this.weightedCluster = Collections.unmodifiableList(weightedCluster);
    }

    String getCluster() {
      return cluster;
    }

    String getClusterHeader() {
      return clusterHeader;
    }

    List<ClusterWeight> getWeightedCluster() {
      return weightedCluster;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      RouteAction that = (RouteAction) o;
      return Objects.equals(cluster, that.cluster)
              && Objects.equals(clusterHeader, that.clusterHeader)
              && Objects.equals(weightedCluster, that.weightedCluster);
    }

    @Override
    public int hashCode() {
      return Objects.hash(cluster, clusterHeader, weightedCluster);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
              .add("cluster", cluster)
              .add("clusterHeader", clusterHeader)
              .add("weightedCluster", weightedCluster)
              .toString();
    }

    private static RouteAction fromEnvoyProtoRouteAction(
        io.envoyproxy.envoy.api.v2.route.RouteAction proto) {
      List<ClusterWeight> weightedCluster = new ArrayList<>();
      List<io.envoyproxy.envoy.api.v2.route.WeightedCluster.ClusterWeight> clusterWeights
          = proto.getWeightedClusters().getClustersList();
      for (io.envoyproxy.envoy.api.v2.route.WeightedCluster.ClusterWeight clusterWeight
          : clusterWeights) {
        weightedCluster.add(ClusterWeight.fromEnvoyProtoClusterWeight(clusterWeight));
      }
      return new RouteAction(proto.getCluster(), proto.getClusterHeader(), weightedCluster);
    }
  }

  /**
   * See corresponding Envoy proto message {@link
   * io.envoyproxy.envoy.api.v2.route.WeightedCluster.ClusterWeight}.
   */
  static final class ClusterWeight {
    private final String name;
    private final int weight;

    @VisibleForTesting
    ClusterWeight(String name, int weight) {
      this.name = name;
      this.weight = weight;
    }

    String getName() {
      return name;
    }

    int getWeight() {
      return weight;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      ClusterWeight that = (ClusterWeight) o;
      return weight == that.weight && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, weight);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("name", name)
          .add("weight", weight)
          .toString();
    }

    private static ClusterWeight fromEnvoyProtoClusterWeight(
        io.envoyproxy.envoy.api.v2.route.WeightedCluster.ClusterWeight proto) {
      return new ClusterWeight(proto.getName(), proto.getWeight().getValue());
    }
  }
}
