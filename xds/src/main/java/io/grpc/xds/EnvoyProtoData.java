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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.collect.ImmutableList;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.envoyproxy.envoy.type.v3.FractionalPercent.DenominatorType;
import io.grpc.EquivalentAddressGroup;
import io.grpc.xds.RouteMatch.FractionMatcher;
import io.grpc.xds.RouteMatch.HeaderMatcher;
import io.grpc.xds.RouteMatch.PathMatcher;
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
 *
 * <p>Data conversion should follow the invariant: converted data is guaranteed to be valid for
 * gRPC. If the protobuf message contains invalid data, the conversion should fail and no object
 * should be instantiated.
 */
final class EnvoyProtoData {

  // Prevent instantiation.
  private EnvoyProtoData() {
  }

  static final class StructOrError<T> {

    /**
     * Returns a {@link StructOrError} for the successfully converted data object.
     */
    static <T> StructOrError<T> fromStruct(T struct) {
      return new StructOrError<>(struct);
    }

    /**
     * Returns a {@link StructOrError} for the failure to convert the data object.
     */
    static <T> StructOrError<T> fromError(String errorDetail) {
      return new StructOrError<>(errorDetail);
    }

    private final String errorDetail;
    private final T struct;

    private StructOrError(T struct) {
      this.struct = checkNotNull(struct, "struct");
      this.errorDetail = null;
    }

    private StructOrError(String errorDetail) {
      this.struct = null;
      this.errorDetail = checkNotNull(errorDetail, "errorDetail");
    }

    /**
     * Returns struct if exists, otherwise null.
     */
    @Nullable
    public T getStruct() {
      return struct;
    }

    /**
     * Returns error detail if exists, otherwise null.
     */
    @Nullable
    String getErrorDetail() {
      return errorDetail;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      StructOrError<?> that = (StructOrError<?>) o;
      return Objects.equals(errorDetail, that.errorDetail) && Objects.equals(struct, that.struct);
    }

    @Override
    public int hashCode() {
      return Objects.hash(errorDetail, struct);
    }

    @Override
    public String toString() {
      if (struct != null) {
        return MoreObjects.toStringHelper(this)
            .add("struct", struct)
            .toString();
      } else {
        assert errorDetail != null;
        return MoreObjects.toStringHelper(this)
            .add("error", errorDetail)
            .toString();
      }
    }
  }

  /**
   * See corresponding Envoy proto message {@link io.envoyproxy.envoy.api.v2.core.Locality}.
   */
  static final class Locality {
    private final String region;
    private final String zone;
    private final String subZone;

    Locality(String region, String zone, String subZone) {
      this.region = region;
      this.zone = zone;
      this.subZone = subZone;
    }

    static Locality fromEnvoyProtoLocality(io.envoyproxy.envoy.config.core.v3.Locality locality) {
      return new Locality(
          /* region = */ locality.getRegion(),
          /* zone = */ locality.getZone(),
          /* subZone = */ locality.getSubZone());
    }

    @VisibleForTesting
    static Locality fromEnvoyProtoLocalityV2(io.envoyproxy.envoy.api.v2.core.Locality locality) {
      return new Locality(
          /* region = */ locality.getRegion(),
          /* zone = */ locality.getZone(),
          /* subZone = */ locality.getSubZone());
    }

    io.envoyproxy.envoy.api.v2.core.Locality toEnvoyProtoLocality() {
      return io.envoyproxy.envoy.api.v2.core.Locality.newBuilder()
          .setRegion(region)
          .setZone(zone)
          .setSubZone(subZone)
          .build();
    }

    String getRegion() {
      return region;
    }

    String getZone() {
      return zone;
    }

    String getSubZone() {
      return subZone;
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
          && Objects.equals(subZone, locality.subZone);
    }

    @Override
    public int hashCode() {
      return Objects.hash(region, zone, subZone);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("region", region)
          .add("zone", zone)
          .add("subZone", subZone)
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
        io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints proto) {
      List<LbEndpoint> endpoints = new ArrayList<>(proto.getLbEndpointsCount());
      for (io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint endpoint :
          proto.getLbEndpointsList()) {
        endpoints.add(LbEndpoint.fromEnvoyProtoLbEndpoint(endpoint));
      }
      return
          new LocalityLbEndpoints(
              endpoints,
              proto.getLoadBalancingWeight().getValue(),
              proto.getPriority());
    }

    @VisibleForTesting
    static LocalityLbEndpoints fromEnvoyProtoLocalityLbEndpointsV2(
        io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints proto) {
      List<LbEndpoint> endpoints = new ArrayList<>(proto.getLbEndpointsCount());
      for (io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint endpoint : proto.getLbEndpointsList()) {
        endpoints.add(LbEndpoint.fromEnvoyProtoLbEndpointV2(endpoint));
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
        io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint proto) {
      io.envoyproxy.envoy.config.core.v3.SocketAddress socketAddress =
          proto.getEndpoint().getAddress().getSocketAddress();
      InetSocketAddress addr =
          new InetSocketAddress(socketAddress.getAddress(), socketAddress.getPortValue());
      return new LbEndpoint(
          new EquivalentAddressGroup(ImmutableList.<java.net.SocketAddress>of(addr)),
          proto.getLoadBalancingWeight().getValue(),
          proto.getHealthStatus() == io.envoyproxy.envoy.config.core.v3.HealthStatus.HEALTHY
              || proto.getHealthStatus()
                  == io.envoyproxy.envoy.config.core.v3.HealthStatus.UNKNOWN);
    }

    private static LbEndpoint fromEnvoyProtoLbEndpointV2(
        io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint proto) {
      io.envoyproxy.envoy.api.v2.core.SocketAddress socketAddress =
          proto.getEndpoint().getAddress().getSocketAddress();
      InetSocketAddress addr =
          new InetSocketAddress(socketAddress.getAddress(), socketAddress.getPortValue());
      return new LbEndpoint(
          new EquivalentAddressGroup(ImmutableList.<java.net.SocketAddress>of(addr)),
          proto.getLoadBalancingWeight().getValue(),
          proto.getHealthStatus() == io.envoyproxy.envoy.api.v2.core.HealthStatus.HEALTHY
              || proto.getHealthStatus() == io.envoyproxy.envoy.api.v2.core.HealthStatus.UNKNOWN);
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
        io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.Policy.DropOverload proto) {
      FractionalPercent percent = proto.getDropPercentage();
      int numerator = percent.getNumerator();
      DenominatorType type = percent.getDenominator();
      switch (type) {
        case TEN_THOUSAND:
          numerator *= 100;
          break;
        case HUNDRED:
          numerator *= 10_000;
          break;
        case MILLION:
          break;
        case UNRECOGNIZED:
        default:
          throw new IllegalArgumentException("Unknown denominator type of " + percent);
      }

      if (numerator > 1_000_000) {
        numerator = 1_000_000;
      }

      return new DropOverload(proto.getCategory(), numerator);
    }

    @VisibleForTesting
    static DropOverload fromEnvoyProtoDropOverloadV2(
        io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy.DropOverload proto) {
      io.envoyproxy.envoy.type.FractionalPercent percent = proto.getDropPercentage();
      int numerator = percent.getNumerator();
      io.envoyproxy.envoy.type.FractionalPercent.DenominatorType type = percent.getDenominator();
      switch (type) {
        case TEN_THOUSAND:
          numerator *= 100;
          break;
        case HUNDRED:
          numerator *= 10_000;
          break;
        case MILLION:
          break;
        case UNRECOGNIZED:
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
    private final RouteAction routeAction;

    @VisibleForTesting
    Route(RouteMatch routeMatch, @Nullable RouteAction routeAction) {
      this.routeMatch = routeMatch;
      this.routeAction = routeAction;
    }

    RouteMatch getRouteMatch() {
      return routeMatch;
    }

    RouteAction getRouteAction() {
      return routeAction;
    }

    // TODO(chengyuanzhang): delete and do not use after routing feature is always ON.
    boolean isDefaultRoute() {
      // For backward compatibility, all the other matchers are ignored.
      String prefix = routeMatch.getPathMatch().getPrefix();
      if (prefix != null) {
        return prefix.isEmpty() || prefix.equals("/");
      }
      return false;
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

    @Nullable
    static StructOrError<Route> fromEnvoyProtoRoute(
        io.envoyproxy.envoy.config.route.v3.Route proto) {
      StructOrError<RouteMatch> routeMatch = convertEnvoyProtoRouteMatch(proto.getMatch());
      if (routeMatch == null) {
        return null;
      }
      if (routeMatch.getErrorDetail() != null) {
        return StructOrError.fromError(
            "Invalid route [" + proto.getName() + "]: " + routeMatch.getErrorDetail());
      }

      StructOrError<RouteAction> routeAction;
      switch (proto.getActionCase()) {
        case ROUTE:
          routeAction = RouteAction.fromEnvoyProtoRouteAction(proto.getRoute());
          break;
        case REDIRECT:
          return StructOrError.fromError("Unsupported action type: redirect");
        case DIRECT_RESPONSE:
          return StructOrError.fromError("Unsupported action type: direct_response");
        case FILTER_ACTION:
          return StructOrError.fromError("Unsupported action type: filter_action");
        case ACTION_NOT_SET:
        default:
          return StructOrError.fromError("Unknown action type: " + proto.getActionCase());
      }
      if (routeAction == null) {
        return null;
      }
      if (routeAction.getErrorDetail() != null) {
        return StructOrError.fromError(
            "Invalid route [" + proto.getName() + "]: " + routeAction.getErrorDetail());
      }
      return StructOrError.fromStruct(new Route(routeMatch.getStruct(), routeAction.getStruct()));
    }

    @VisibleForTesting
    @SuppressWarnings("deprecation")
    @Nullable
    static StructOrError<RouteMatch> convertEnvoyProtoRouteMatch(
        io.envoyproxy.envoy.config.route.v3.RouteMatch proto) {
      if (proto.getQueryParametersCount() != 0) {
        return null;
      }
      if (proto.hasCaseSensitive() && !proto.getCaseSensitive().getValue()) {
        return StructOrError.fromError("Unsupported match option: case insensitive");
      }

      StructOrError<PathMatcher> pathMatch = convertEnvoyProtoPathMatcher(proto);
      if (pathMatch.getErrorDetail() != null) {
        return StructOrError.fromError(pathMatch.getErrorDetail());
      }

      FractionMatcher fractionMatch = null;
      if (proto.hasRuntimeFraction()) {
        StructOrError<FractionMatcher> parsedFraction =
            convertEnvoyProtoFraction(proto.getRuntimeFraction().getDefaultValue());
        if (parsedFraction.getErrorDetail() != null) {
          return StructOrError.fromError(parsedFraction.getErrorDetail());
        }
        fractionMatch = parsedFraction.getStruct();
      }

      List<HeaderMatcher> headerMatchers = new ArrayList<>();
      for (io.envoyproxy.envoy.config.route.v3.HeaderMatcher hmProto : proto.getHeadersList()) {
        StructOrError<HeaderMatcher> headerMatcher = convertEnvoyProtoHeaderMatcher(hmProto);
        if (headerMatcher.getErrorDetail() != null) {
          return StructOrError.fromError(headerMatcher.getErrorDetail());
        }
        headerMatchers.add(headerMatcher.getStruct());
      }

      return StructOrError.fromStruct(
          new RouteMatch(
              pathMatch.getStruct(), Collections.unmodifiableList(headerMatchers), fractionMatch));
    }

    @SuppressWarnings("deprecation")
    private static StructOrError<PathMatcher> convertEnvoyProtoPathMatcher(
        io.envoyproxy.envoy.config.route.v3.RouteMatch proto) {
      String path = null;
      String prefix = null;
      Pattern safeRegEx = null;
      switch (proto.getPathSpecifierCase()) {
        case PREFIX:
          prefix = proto.getPrefix();
          break;
        case PATH:
          path = proto.getPath();
          break;
        case SAFE_REGEX:
          String rawPattern = proto.getSafeRegex().getRegex();
          try {
            safeRegEx = Pattern.compile(rawPattern);
          } catch (PatternSyntaxException e) {
            return StructOrError.fromError("Malformed safe regex pattern: " + e.getMessage());
          }
          break;
        case PATHSPECIFIER_NOT_SET:
        default:
          return StructOrError.fromError("Unknown path match type");
      }
      return StructOrError.fromStruct(new PathMatcher(path, prefix, safeRegEx));
    }

    private static StructOrError<FractionMatcher> convertEnvoyProtoFraction(
        io.envoyproxy.envoy.type.v3.FractionalPercent proto) {
      int numerator = proto.getNumerator();
      int denominator = 0;
      switch (proto.getDenominator()) {
        case HUNDRED:
          denominator = 100;
          break;
        case TEN_THOUSAND:
          denominator = 10_000;
          break;
        case MILLION:
          denominator = 1_000_000;
          break;
        case UNRECOGNIZED:
        default:
          return StructOrError.fromError(
              "Unrecognized fractional percent denominator: " + proto.getDenominator());
      }
      return StructOrError.fromStruct(new FractionMatcher(numerator, denominator));
    }

    @VisibleForTesting
    @SuppressWarnings("deprecation")
    static StructOrError<HeaderMatcher> convertEnvoyProtoHeaderMatcher(
        io.envoyproxy.envoy.config.route.v3.HeaderMatcher proto) {
      String exactMatch = null;
      Pattern safeRegExMatch = null;
      HeaderMatcher.Range rangeMatch = null;
      Boolean presentMatch = null;
      String prefixMatch = null;
      String suffixMatch = null;

      switch (proto.getHeaderMatchSpecifierCase()) {
        case EXACT_MATCH:
          exactMatch = proto.getExactMatch();
          break;
        case SAFE_REGEX_MATCH:
          String rawPattern = proto.getSafeRegexMatch().getRegex();
          try {
            safeRegExMatch = Pattern.compile(rawPattern);
          } catch (PatternSyntaxException e) {
            return StructOrError.fromError(
                "HeaderMatcher [" + proto.getName() + "] contains malformed safe regex pattern: "
                    + e.getMessage());
          }
          break;
        case RANGE_MATCH:
          rangeMatch =
              new HeaderMatcher.Range(
                  proto.getRangeMatch().getStart(), proto.getRangeMatch().getEnd());
          break;
        case PRESENT_MATCH:
          presentMatch = proto.getPresentMatch();
          break;
        case PREFIX_MATCH:
          prefixMatch = proto.getPrefixMatch();
          break;
        case SUFFIX_MATCH:
          suffixMatch = proto.getSuffixMatch();
          break;
        case HEADERMATCHSPECIFIER_NOT_SET:
        default:
          return StructOrError.fromError("Unknown header matcher type");
      }
      return StructOrError.fromStruct(
          new HeaderMatcher(
              proto.getName(), exactMatch, safeRegExMatch, rangeMatch, presentMatch,
              prefixMatch, suffixMatch, proto.getInvertMatch()));
    }
  }

  /** See corresponding Envoy proto message {@link io.envoyproxy.envoy.api.v2.route.RouteAction}. */
  static final class RouteAction {
    // Exactly one of the following fields is non-null.
    @Nullable
    private final String cluster;
    @Nullable
    private final List<ClusterWeight> weightedClusters;

    @VisibleForTesting
    RouteAction(@Nullable String cluster, @Nullable List<ClusterWeight> weightedClusters) {
      this.cluster = cluster;
      this.weightedClusters = weightedClusters;
    }

    @Nullable
    String getCluster() {
      return cluster;
    }

    @Nullable
    List<ClusterWeight> getWeightedCluster() {
      return weightedClusters;
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
          && Objects.equals(weightedClusters, that.weightedClusters);
    }

    @Override
    public int hashCode() {
      return Objects.hash(cluster, weightedClusters);
    }

    @Override
    public String toString() {
      ToStringHelper toStringHelper = MoreObjects.toStringHelper(this);
      if (cluster != null) {
        toStringHelper.add("cluster", cluster);
      }
      if (weightedClusters != null) {
        toStringHelper.add("weightedClusters", weightedClusters);
      }
      return toStringHelper.toString();
    }

    @Nullable
    @VisibleForTesting
    static StructOrError<RouteAction> fromEnvoyProtoRouteAction(
        io.envoyproxy.envoy.config.route.v3.RouteAction proto) {
      String cluster = null;
      List<ClusterWeight> weightedClusters = null;
      switch (proto.getClusterSpecifierCase()) {
        case CLUSTER:
          cluster = proto.getCluster();
          break;
        case CLUSTER_HEADER:
          return null;
        case WEIGHTED_CLUSTERS:
          List<io.envoyproxy.envoy.config.route.v3.WeightedCluster.ClusterWeight> clusterWeights
              = proto.getWeightedClusters().getClustersList();
          weightedClusters = new ArrayList<>();
          for (io.envoyproxy.envoy.config.route.v3.WeightedCluster.ClusterWeight clusterWeight
              : clusterWeights) {
            weightedClusters.add(ClusterWeight.fromEnvoyProtoClusterWeight(clusterWeight));
          }
          // TODO(chengyuanzhang): validate if the sum of weights equals to total weight.
          break;
        case CLUSTERSPECIFIER_NOT_SET:
        default:
          return StructOrError.fromError(
              "Unknown cluster specifier: " + proto.getClusterSpecifierCase());
      }
      return StructOrError.fromStruct(new RouteAction(cluster, weightedClusters));
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

    @VisibleForTesting
    static ClusterWeight fromEnvoyProtoClusterWeight(
        io.envoyproxy.envoy.config.route.v3.WeightedCluster.ClusterWeight proto) {
      return new ClusterWeight(proto.getName(), proto.getWeight().getValue());
    }
  }
}
