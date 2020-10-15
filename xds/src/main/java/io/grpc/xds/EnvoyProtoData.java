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
import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.Durations;
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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
// TODO(chengyuanzhang): put data types into smaller categories.
final class EnvoyProtoData {
  static final String TRANSPORT_SOCKET_NAME_TLS = "envoy.transport_sockets.tls";

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
   * See corresponding Envoy proto message {@link io.envoyproxy.envoy.config.core.v3.Node}.
   */
  public static final class Node {

    private final String id;
    private final String cluster;
    @Nullable
    private final Map<String, ?> metadata;
    @Nullable
    private final Locality locality;
    private final List<Address> listeningAddresses;
    private final String buildVersion;
    private final String userAgentName;
    @Nullable
    private final String userAgentVersion;
    private final List<String> clientFeatures;

    private Node(
        String id, String cluster, @Nullable Map<String, ?> metadata, @Nullable Locality locality,
        List<Address> listeningAddresses, String buildVersion, String userAgentName,
        @Nullable String userAgentVersion, List<String> clientFeatures) {
      this.id = checkNotNull(id, "id");
      this.cluster = checkNotNull(cluster, "cluster");
      this.metadata = metadata;
      this.locality = locality;
      this.listeningAddresses = Collections.unmodifiableList(
          checkNotNull(listeningAddresses, "listeningAddresses"));
      this.buildVersion = checkNotNull(buildVersion, "buildVersion");
      this.userAgentName = checkNotNull(userAgentName, "userAgentName");
      this.userAgentVersion = userAgentVersion;
      this.clientFeatures = Collections.unmodifiableList(
          checkNotNull(clientFeatures, "clientFeatures"));
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("id", id)
          .add("cluster", cluster)
          .add("metadata", metadata)
          .add("locality", locality)
          .add("listeningAddresses", listeningAddresses)
          .add("buildVersion", buildVersion)
          .add("userAgentName", userAgentName)
          .add("userAgentVersion", userAgentVersion)
          .add("clientFeatures", clientFeatures)
          .toString();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Node node = (Node) o;
      return Objects.equals(id, node.id)
          && Objects.equals(cluster, node.cluster)
          && Objects.equals(metadata, node.metadata)
          && Objects.equals(locality, node.locality)
          && Objects.equals(listeningAddresses, node.listeningAddresses)
          && Objects.equals(buildVersion, node.buildVersion)
          && Objects.equals(userAgentName, node.userAgentName)
          && Objects.equals(userAgentVersion, node.userAgentVersion)
          && Objects.equals(clientFeatures, node.clientFeatures);
    }

    @Override
    public int hashCode() {
      return Objects
          .hash(id, cluster, metadata, locality, listeningAddresses, buildVersion, userAgentName,
              userAgentVersion, clientFeatures);
    }

    static final class Builder {
      private String id = "";
      private String cluster = "";
      @Nullable
      private Map<String, ?> metadata;
      @Nullable
      private Locality locality;
      // TODO(sanjaypujare): eliminate usage of listening_addresses field.
      private final List<Address> listeningAddresses = new ArrayList<>();
      private String buildVersion = "";
      private String userAgentName = "";
      @Nullable
      private String userAgentVersion;
      private final List<String> clientFeatures = new ArrayList<>();

      private Builder() {
      }

      Builder setId(String id) {
        this.id = checkNotNull(id, "id");
        return this;
      }

      Builder setCluster(String cluster) {
        this.cluster = checkNotNull(cluster, "cluster");
        return this;
      }

      Builder setMetadata(Map<String, ?> metadata) {
        this.metadata = checkNotNull(metadata, "metadata");
        return this;
      }

      Builder setLocality(Locality locality) {
        this.locality = checkNotNull(locality, "locality");
        return this;
      }

      Builder addListeningAddresses(Address address) {
        listeningAddresses.add(checkNotNull(address, "address"));
        return this;
      }

      Builder setBuildVersion(String buildVersion) {
        this.buildVersion = checkNotNull(buildVersion, "buildVersion");
        return this;
      }

      Builder setUserAgentName(String userAgentName) {
        this.userAgentName = checkNotNull(userAgentName, "userAgentName");
        return this;
      }

      Builder setUserAgentVersion(String userAgentVersion) {
        this.userAgentVersion = checkNotNull(userAgentVersion, "userAgentVersion");
        return this;
      }

      Builder addClientFeatures(String clientFeature) {
        this.clientFeatures.add(checkNotNull(clientFeature, "clientFeature"));
        return this;
      }

      Node build() {
        return new Node(
            id, cluster, metadata, locality, listeningAddresses, buildVersion, userAgentName,
            userAgentVersion, clientFeatures);
      }
    }

    static Builder newBuilder() {
      return new Builder();
    }

    Builder toBuilder() {
      Builder builder = new Builder().setId(id).setCluster(cluster);
      if (metadata != null) {
        builder.setMetadata(metadata);
      }
      if (locality != null) {
        builder.setLocality(locality);
      }
      builder.listeningAddresses.addAll(listeningAddresses);
      return builder;
    }

    String getId() {
      return id;
    }

    String getCluster() {
      return cluster;
    }

    @Nullable
    Map<String, ?> getMetadata() {
      return metadata;
    }

    @Nullable
    Locality getLocality() {
      return locality;
    }

    List<Address> getListeningAddresses() {
      return listeningAddresses;
    }

    @SuppressWarnings("deprecation")
    @VisibleForTesting
    public io.envoyproxy.envoy.config.core.v3.Node toEnvoyProtoNode() {
      io.envoyproxy.envoy.config.core.v3.Node.Builder builder =
          io.envoyproxy.envoy.config.core.v3.Node.newBuilder();
      builder.setId(id);
      builder.setCluster(cluster);
      if (metadata != null) {
        Struct.Builder structBuilder = Struct.newBuilder();
        for (Map.Entry<String, ?> entry : metadata.entrySet()) {
          structBuilder.putFields(entry.getKey(), convertToValue(entry.getValue()));
        }
        builder.setMetadata(structBuilder);
      }
      if (locality != null) {
        builder.setLocality(locality.toEnvoyProtoLocality());
      }
      for (Address address : listeningAddresses) {
        builder.addListeningAddresses(address.toEnvoyProtoAddress());
      }
      builder.setUserAgentName(userAgentName);
      if (userAgentVersion != null) {
        builder.setUserAgentVersion(userAgentVersion);
      }
      builder.addAllClientFeatures(clientFeatures);
      return builder.build();
    }

    @SuppressWarnings("deprecation") // Deprecated v2 API setBuildVersion().
    public io.envoyproxy.envoy.api.v2.core.Node toEnvoyProtoNodeV2() {
      io.envoyproxy.envoy.api.v2.core.Node.Builder builder =
          io.envoyproxy.envoy.api.v2.core.Node.newBuilder();
      builder.setId(id);
      builder.setCluster(cluster);
      if (metadata != null) {
        Struct.Builder structBuilder = Struct.newBuilder();
        for (Map.Entry<String, ?> entry : metadata.entrySet()) {
          structBuilder.putFields(entry.getKey(), convertToValue(entry.getValue()));
        }
        builder.setMetadata(structBuilder);
      }
      if (locality != null) {
        builder.setLocality(locality.toEnvoyProtoLocalityV2());
      }
      for (Address address : listeningAddresses) {
        builder.addListeningAddresses(address.toEnvoyProtoAddressV2());
      }
      builder.setBuildVersion(buildVersion);
      builder.setUserAgentName(userAgentName);
      if (userAgentVersion != null) {
        builder.setUserAgentVersion(userAgentVersion);
      }
      builder.addAllClientFeatures(clientFeatures);
      return builder.build();
    }
  }

  /**
   * Converts Java representation of the given JSON value to protobuf's {@link
   * com.google.protobuf.Value} representation.
   *
   * <p>The given {@code rawObject} must be a valid JSON value in Java representation, which is
   * either a {@code Map<String, ?>}, {@code List<?>}, {@code String}, {@code Double}, {@code
   * Boolean}, or {@code null}.
   */
  private static Value convertToValue(Object rawObject) {
    Value.Builder valueBuilder = Value.newBuilder();
    if (rawObject == null) {
      valueBuilder.setNullValue(NullValue.NULL_VALUE);
    } else if (rawObject instanceof Double) {
      valueBuilder.setNumberValue((Double) rawObject);
    } else if (rawObject instanceof String) {
      valueBuilder.setStringValue((String) rawObject);
    } else if (rawObject instanceof Boolean) {
      valueBuilder.setBoolValue((Boolean) rawObject);
    } else if (rawObject instanceof Map) {
      Struct.Builder structBuilder = Struct.newBuilder();
      @SuppressWarnings("unchecked")
      Map<String, ?> map = (Map<String, ?>) rawObject;
      for (Map.Entry<String, ?> entry : map.entrySet()) {
        structBuilder.putFields(entry.getKey(), convertToValue(entry.getValue()));
      }
      valueBuilder.setStructValue(structBuilder);
    } else if (rawObject instanceof List) {
      ListValue.Builder listBuilder = ListValue.newBuilder();
      List<?> list = (List<?>) rawObject;
      for (Object obj : list) {
        listBuilder.addValues(convertToValue(obj));
      }
      valueBuilder.setListValue(listBuilder);
    }
    return valueBuilder.build();
  }

  /**
   * See corresponding Envoy proto message {@link io.envoyproxy.envoy.config.core.v3.Address}.
   */
  static final class Address {
    private final String address;
    private final int port;

    Address(String address, int port) {
      this.address = checkNotNull(address, "address");
      this.port = port;
    }

    io.envoyproxy.envoy.config.core.v3.Address toEnvoyProtoAddress() {
      return
          io.envoyproxy.envoy.config.core.v3.Address.newBuilder().setSocketAddress(
              io.envoyproxy.envoy.config.core.v3.SocketAddress.newBuilder().setAddress(address)
                  .setPortValue(port)).build();
    }

    io.envoyproxy.envoy.api.v2.core.Address toEnvoyProtoAddressV2() {
      return
          io.envoyproxy.envoy.api.v2.core.Address.newBuilder().setSocketAddress(
              io.envoyproxy.envoy.api.v2.core.SocketAddress.newBuilder().setAddress(address)
                  .setPortValue(port)).build();
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("address", address)
          .add("port", port)
          .toString();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Address address1 = (Address) o;
      return port == address1.port && Objects.equals(address, address1.address);
    }

    @Override
    public int hashCode() {
      return Objects.hash(address, port);
    }
  }

  /**
   * See corresponding Envoy proto message {@link io.envoyproxy.envoy.config.core.v3.Locality}.
   */
  static final class Locality {
    private final String region;
    private final String zone;
    private final String subZone;

    Locality(@Nullable String region, @Nullable String zone, @Nullable String subZone) {
      this.region = region == null ? "" : region;
      this.zone = zone == null ? "" : zone;
      this.subZone = subZone == null ? "" : subZone;
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

    io.envoyproxy.envoy.config.core.v3.Locality toEnvoyProtoLocality() {
      return io.envoyproxy.envoy.config.core.v3.Locality.newBuilder()
          .setRegion(region)
          .setZone(zone)
          .setSubZone(subZone)
          .build();
    }

    io.envoyproxy.envoy.api.v2.core.Locality toEnvoyProtoLocalityV2() {
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
   * io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints}.
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
   * See corresponding Envoy proto message
   * {@link io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint}.
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
   * io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.Policy.DropOverload}.
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

  /** See corresponding Envoy proto message {@link
   * io.envoyproxy.envoy.config.route.v3.VirtualHost}. */
  static final class VirtualHost {
    // Canonical name of this virtual host.
    private final String name;
    // A list of domains (host/authority header) that will be matched to this virtual host.
    private final List<String> domains;
    // The list of routes that will be matched, in order, for incoming requests.
    private final List<Route> routes;

    @VisibleForTesting
    VirtualHost(String name, List<String> domains, List<Route> routes) {
      this.name = name;
      this.domains = domains;
      this.routes = routes;
    }

    String getName() {
      return name;
    }

    List<String> getDomains() {
      return domains;
    }

    List<Route> getRoutes() {
      return routes;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("name", name)
          .add("domains", domains)
          .add("routes", routes)
          .toString();
    }

    static StructOrError<VirtualHost> fromEnvoyProtoVirtualHost(
        io.envoyproxy.envoy.config.route.v3.VirtualHost proto) {
      String name = proto.getName();
      List<Route> routes = new ArrayList<>(proto.getRoutesCount());
      for (io.envoyproxy.envoy.config.route.v3.Route routeProto : proto.getRoutesList()) {
        StructOrError<Route> route = Route.fromEnvoyProtoRoute(routeProto);
        if (route == null) {
          continue;
        }
        if (route.getErrorDetail() != null) {
          return StructOrError.fromError(
              "Virtual host [" + name + "] contains invalid route : " + route.getErrorDetail());
        }
        routes.add(route.getStruct());
      }
      return StructOrError.fromStruct(
          new VirtualHost(name, Collections.unmodifiableList(proto.getDomainsList()),
              Collections.unmodifiableList(routes)));
    }
  }

  /** See corresponding Envoy proto message {@link io.envoyproxy.envoy.config.route.v3.Route}. */
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
    @Nullable
    static StructOrError<RouteMatch> convertEnvoyProtoRouteMatch(
        io.envoyproxy.envoy.config.route.v3.RouteMatch proto) {
      if (proto.getQueryParametersCount() != 0) {
        return null;
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

    private static StructOrError<PathMatcher> convertEnvoyProtoPathMatcher(
        io.envoyproxy.envoy.config.route.v3.RouteMatch proto) {
      boolean caseSensitive = proto.getCaseSensitive().getValue();
      switch (proto.getPathSpecifierCase()) {
        case PREFIX:
          return StructOrError.fromStruct(
              PathMatcher.fromPrefix(proto.getPrefix(), caseSensitive));
        case PATH:
          return StructOrError.fromStruct(PathMatcher.fromPath(proto.getPath(), caseSensitive));
        case SAFE_REGEX:
          String rawPattern = proto.getSafeRegex().getRegex();
          Pattern safeRegEx;
          try {
            safeRegEx = Pattern.compile(rawPattern);
          } catch (PatternSyntaxException e) {
            return StructOrError.fromError("Malformed safe regex pattern: " + e.getMessage());
          }
          return StructOrError.fromStruct(PathMatcher.fromRegEx(safeRegEx));
        case PATHSPECIFIER_NOT_SET:
        default:
          return StructOrError.fromError("Unknown path match type");
      }
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

  /**
   * See corresponding Envoy proto message {@link io.envoyproxy.envoy.config.route.v3.RouteAction}.
   */
  static final class RouteAction {
    @Nullable
    private final Long timeoutNano;
    // Exactly one of the following fields is non-null.
    @Nullable
    private final String cluster;
    @Nullable
    private final List<ClusterWeight> weightedClusters;

    @VisibleForTesting
    RouteAction(@Nullable Long timeoutNano, @Nullable String cluster,
        @Nullable List<ClusterWeight> weightedClusters) {
      this.timeoutNano = timeoutNano;
      this.cluster = cluster;
      this.weightedClusters = weightedClusters;
    }

    @Nullable
    Long getTimeoutNano() {
      return timeoutNano;
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
      return Objects.equals(timeoutNano, that.timeoutNano)
          && Objects.equals(cluster, that.cluster)
          && Objects.equals(weightedClusters, that.weightedClusters);
    }

    @Override
    public int hashCode() {
      return Objects.hash(timeoutNano, cluster, weightedClusters);
    }

    @Override
    public String toString() {
      ToStringHelper toStringHelper = MoreObjects.toStringHelper(this);
      if (timeoutNano != null) {
        toStringHelper.add("timeout", timeoutNano + "ns");
      }
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
          if (clusterWeights.isEmpty()) {
            return StructOrError.fromError("No cluster found in weighted cluster list");
          }
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
      Long timeoutNano = null;
      if (proto.hasMaxStreamDuration()) {
        io.envoyproxy.envoy.config.route.v3.RouteAction.MaxStreamDuration maxStreamDuration
            = proto.getMaxStreamDuration();
        if (maxStreamDuration.hasGrpcTimeoutHeaderMax()) {
          timeoutNano = Durations.toNanos(maxStreamDuration.getGrpcTimeoutHeaderMax());
        } else if (maxStreamDuration.hasMaxStreamDuration()) {
          timeoutNano = Durations.toNanos(maxStreamDuration.getMaxStreamDuration());
        }
      }
      return StructOrError.fromStruct(new RouteAction(timeoutNano, cluster, weightedClusters));
    }
  }

  /**
   * See corresponding Envoy proto message {@link
   * io.envoyproxy.envoy.config.route.v3.WeightedCluster.ClusterWeight}.
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

  /**
   * See corresponding Envoy proto message {@link
   * io.envoyproxy.envoy.config.endpoint.v3.ClusterStats}.
   */
  static final class ClusterStats {
    private final String clusterName;
    @Nullable
    private final String clusterServiceName;
    private final List<UpstreamLocalityStats> upstreamLocalityStatsList;
    private final List<DroppedRequests> droppedRequestsList;
    private final long totalDroppedRequests;
    private final long loadReportIntervalNanos;

    private ClusterStats(
        String clusterName,
        @Nullable String clusterServiceName,
        List<UpstreamLocalityStats> upstreamLocalityStatsList,
        List<DroppedRequests> droppedRequestsList,
        long totalDroppedRequests,
        long loadReportIntervalNanos) {
      this.clusterName = checkNotNull(clusterName, "clusterName");
      this.clusterServiceName = clusterServiceName;
      this.upstreamLocalityStatsList = Collections.unmodifiableList(
              checkNotNull(upstreamLocalityStatsList, "upstreamLocalityStatsList"));
      this.droppedRequestsList = Collections.unmodifiableList(
          checkNotNull(droppedRequestsList, "dropRequestsList"));
      this.totalDroppedRequests = totalDroppedRequests;
      this.loadReportIntervalNanos = loadReportIntervalNanos;
    }

    String getClusterName() {
      return clusterName;
    }

    @Nullable
    String getClusterServiceName() {
      return clusterServiceName;
    }

    List<UpstreamLocalityStats> getUpstreamLocalityStatsList() {
      return upstreamLocalityStatsList;
    }

    List<DroppedRequests> getDroppedRequestsList() {
      return droppedRequestsList;
    }

    long getTotalDroppedRequests() {
      return totalDroppedRequests;
    }

    long getLoadReportIntervalNanos() {
      return loadReportIntervalNanos;
    }

    io.envoyproxy.envoy.config.endpoint.v3.ClusterStats toEnvoyProtoClusterStats() {
      io.envoyproxy.envoy.config.endpoint.v3.ClusterStats.Builder builder =
          io.envoyproxy.envoy.config.endpoint.v3.ClusterStats.newBuilder()
              .setClusterName(clusterName);
      if (clusterServiceName != null) {
        builder.setClusterServiceName(clusterServiceName);
      }
      for (UpstreamLocalityStats upstreamLocalityStats : upstreamLocalityStatsList) {
        builder.addUpstreamLocalityStats(upstreamLocalityStats.toEnvoyProtoUpstreamLocalityStats());
      }
      for (DroppedRequests droppedRequests : droppedRequestsList) {
        builder.addDroppedRequests(droppedRequests.toEnvoyProtoDroppedRequests());
      }
      return builder
          .setTotalDroppedRequests(totalDroppedRequests)
          .setLoadReportInterval(Durations.fromNanos(loadReportIntervalNanos))
          .build();
    }

    io.envoyproxy.envoy.api.v2.endpoint.ClusterStats toEnvoyProtoClusterStatsV2() {
      io.envoyproxy.envoy.api.v2.endpoint.ClusterStats.Builder builder =
          io.envoyproxy.envoy.api.v2.endpoint.ClusterStats.newBuilder()
              .setClusterName(clusterName);
      for (UpstreamLocalityStats upstreamLocalityStats : upstreamLocalityStatsList) {
        builder.addUpstreamLocalityStats(
            upstreamLocalityStats.toEnvoyProtoUpstreamLocalityStatsV2());
      }
      for (DroppedRequests droppedRequests : droppedRequestsList) {
        builder.addDroppedRequests(droppedRequests.toEnvoyProtoDroppedRequestsV2());
      }
      return builder
          .setTotalDroppedRequests(totalDroppedRequests)
          .setLoadReportInterval(Durations.fromNanos(loadReportIntervalNanos))
          .build();
    }

    @VisibleForTesting
    Builder toBuilder() {
      Builder builder = new Builder()
          .setClusterName(clusterName)
          .setTotalDroppedRequests(totalDroppedRequests)
          .setLoadReportIntervalNanos(loadReportIntervalNanos);
      if (clusterServiceName != null) {
        builder.setClusterServiceName(clusterServiceName);
      }
      for (UpstreamLocalityStats upstreamLocalityStats : upstreamLocalityStatsList) {
        builder.addUpstreamLocalityStats(upstreamLocalityStats);
      }
      for (DroppedRequests droppedRequests : droppedRequestsList) {
        builder.addDroppedRequests(droppedRequests);
      }
      return builder;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      ClusterStats that = (ClusterStats) o;
      return totalDroppedRequests == that.totalDroppedRequests
          && loadReportIntervalNanos == that.loadReportIntervalNanos
          && Objects.equals(clusterName, that.clusterName)
          && Objects.equals(clusterServiceName, that.clusterServiceName)
          && Objects.equals(upstreamLocalityStatsList, that.upstreamLocalityStatsList)
          && Objects.equals(droppedRequestsList, that.droppedRequestsList);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          clusterName, clusterServiceName, upstreamLocalityStatsList, droppedRequestsList,
          totalDroppedRequests, loadReportIntervalNanos);
    }

    static Builder newBuilder() {
      return new Builder();
    }

    static final class Builder {
      private String clusterName;
      private String clusterServiceName;
      private final List<UpstreamLocalityStats> upstreamLocalityStatsList = new ArrayList<>();
      private final List<DroppedRequests> droppedRequestsList = new ArrayList<>();
      private long totalDroppedRequests;
      private long loadReportIntervalNanos;

      private Builder() {
      }

      Builder setClusterName(String clusterName) {
        this.clusterName = checkNotNull(clusterName, "clusterName");
        return this;
      }

      Builder setClusterServiceName(String clusterServiceName) {
        this.clusterServiceName = checkNotNull(clusterServiceName, "clusterServiceName");
        return this;
      }

      Builder setTotalDroppedRequests(long totalDroppedRequests) {
        this.totalDroppedRequests = totalDroppedRequests;
        return this;
      }

      Builder setLoadReportIntervalNanos(long loadReportIntervalNanos) {
        this.loadReportIntervalNanos = loadReportIntervalNanos;
        return this;
      }

      Builder addUpstreamLocalityStats(UpstreamLocalityStats upstreamLocalityStats) {
        upstreamLocalityStatsList.add(checkNotNull(upstreamLocalityStats, "upstreamLocalityStats"));
        return this;
      }

      Builder addAllUpstreamLocalityStats(Collection<UpstreamLocalityStats> upstreamLocalityStats) {
        upstreamLocalityStatsList.addAll(upstreamLocalityStats);
        return this;
      }

      Builder addDroppedRequests(DroppedRequests droppedRequests) {
        droppedRequestsList.add(checkNotNull(droppedRequests, "dropRequests"));
        return this;
      }

      ClusterStats build() {
        return new ClusterStats(
            clusterName, clusterServiceName,upstreamLocalityStatsList, droppedRequestsList,
            totalDroppedRequests, loadReportIntervalNanos);
      }
    }

    /**
     * See corresponding Envoy proto message {@link
     * io.envoyproxy.envoy.config.endpoint.v3.ClusterStats.DroppedRequests}.
     */
    static final class DroppedRequests {
      private final String category;
      private final long droppedCount;

      DroppedRequests(String category, long droppedCount) {
        this.category = checkNotNull(category, "category");
        this.droppedCount = droppedCount;
      }

      String getCategory() {
        return category;
      }

      long getDroppedCount() {
        return droppedCount;
      }

      private io.envoyproxy.envoy.config.endpoint.v3.ClusterStats.DroppedRequests
          toEnvoyProtoDroppedRequests() {
        return io.envoyproxy.envoy.config.endpoint.v3.ClusterStats.DroppedRequests.newBuilder()
            .setCategory(category)
            .setDroppedCount(droppedCount)
            .build();
      }

      private io.envoyproxy.envoy.api.v2.endpoint.ClusterStats.DroppedRequests
          toEnvoyProtoDroppedRequestsV2() {
        return io.envoyproxy.envoy.api.v2.endpoint.ClusterStats.DroppedRequests.newBuilder()
            .setCategory(category)
            .setDroppedCount(droppedCount)
            .build();
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (o == null || getClass() != o.getClass()) {
          return false;
        }
        DroppedRequests that = (DroppedRequests) o;
        return droppedCount == that.droppedCount && Objects.equals(category, that.category);
      }

      @Override
      public int hashCode() {
        return Objects.hash(category, droppedCount);
      }
    }
  }

  /**
   * See corresponding Envoy proto message {@link
   * io.envoyproxy.envoy.config.endpoint.v3.UpstreamLocalityStats}.
   */
  static final class UpstreamLocalityStats {
    private final Locality locality;
    private final long totalSuccessfulRequests;
    private final long totalErrorRequests;
    private final long totalRequestsInProgress;
    private final long totalIssuedRequests;
    private final List<EndpointLoadMetricStats> loadMetricStatsList;

    private UpstreamLocalityStats(
        Locality locality,
        long totalSuccessfulRequests,
        long totalErrorRequests,
        long totalRequestsInProgress,
        long totalIssuedRequests,
        List<EndpointLoadMetricStats> loadMetricStatsList) {
      this.locality = checkNotNull(locality, "locality");
      this.totalSuccessfulRequests = totalSuccessfulRequests;
      this.totalErrorRequests = totalErrorRequests;
      this.totalRequestsInProgress = totalRequestsInProgress;
      this.totalIssuedRequests = totalIssuedRequests;
      this.loadMetricStatsList = Collections.unmodifiableList(
          checkNotNull(loadMetricStatsList, "loadMetricStatsList"));
    }

    Locality getLocality() {
      return locality;
    }

    long getTotalSuccessfulRequests() {
      return totalSuccessfulRequests;
    }

    long getTotalErrorRequests() {
      return totalErrorRequests;
    }

    long getTotalRequestsInProgress() {
      return totalRequestsInProgress;
    }

    long getTotalIssuedRequests() {
      return totalIssuedRequests;
    }

    List<EndpointLoadMetricStats> getLoadMetricStatsList() {
      return loadMetricStatsList;
    }

    private io.envoyproxy.envoy.config.endpoint.v3.UpstreamLocalityStats
        toEnvoyProtoUpstreamLocalityStats() {
      io.envoyproxy.envoy.config.endpoint.v3.UpstreamLocalityStats.Builder builder
          = io.envoyproxy.envoy.config.endpoint.v3.UpstreamLocalityStats.newBuilder()
              .setLocality(locality.toEnvoyProtoLocality())
              .setTotalSuccessfulRequests(totalSuccessfulRequests)
              .setTotalErrorRequests(totalErrorRequests)
              .setTotalRequestsInProgress(totalRequestsInProgress)
              .setTotalIssuedRequests(totalIssuedRequests);
      for (EndpointLoadMetricStats endpointLoadMetricStats : loadMetricStatsList) {
        builder.addLoadMetricStats(endpointLoadMetricStats.toEnvoyProtoEndpointLoadMetricStats());
      }
      return builder.build();
    }

    private io.envoyproxy.envoy.api.v2.endpoint.UpstreamLocalityStats
        toEnvoyProtoUpstreamLocalityStatsV2() {
      io.envoyproxy.envoy.api.v2.endpoint.UpstreamLocalityStats.Builder builder
          = io.envoyproxy.envoy.api.v2.endpoint.UpstreamLocalityStats.newBuilder()
              .setLocality(locality.toEnvoyProtoLocalityV2())
              .setTotalSuccessfulRequests(totalSuccessfulRequests)
              .setTotalErrorRequests(totalErrorRequests)
              .setTotalRequestsInProgress(totalRequestsInProgress)
              .setTotalIssuedRequests(totalIssuedRequests);
      for (EndpointLoadMetricStats endpointLoadMetricStats : loadMetricStatsList) {
        builder.addLoadMetricStats(endpointLoadMetricStats.toEnvoyProtoEndpointLoadMetricStatsV2());
      }
      return builder.build();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      UpstreamLocalityStats that = (UpstreamLocalityStats) o;
      return totalSuccessfulRequests == that.totalSuccessfulRequests
          && totalErrorRequests == that.totalErrorRequests
          && totalRequestsInProgress == that.totalRequestsInProgress
          && totalIssuedRequests == that.totalIssuedRequests
          && Objects.equals(locality, that.locality)
          && Objects.equals(loadMetricStatsList, that.loadMetricStatsList);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          locality, totalSuccessfulRequests, totalErrorRequests, totalRequestsInProgress,
          totalIssuedRequests, loadMetricStatsList);
    }

    static Builder newBuilder() {
      return new Builder();
    }

    static final class Builder {
      private Locality locality;
      private long totalSuccessfulRequests;
      private long totalErrorRequests;
      private long totalRequestsInProgress;
      private long totalIssuedRequests;
      private final List<EndpointLoadMetricStats> loadMetricStatsList = new ArrayList<>();

      private Builder() {
      }

      Builder setLocality(Locality locality) {
        this.locality = checkNotNull(locality, "locality");
        return this;
      }

      Builder setTotalSuccessfulRequests(long totalSuccessfulRequests) {
        this.totalSuccessfulRequests = totalSuccessfulRequests;
        return this;
      }

      Builder setTotalErrorRequests(long totalErrorRequests) {
        this.totalErrorRequests = totalErrorRequests;
        return this;
      }

      Builder setTotalRequestsInProgress(long totalRequestsInProgress) {
        this.totalRequestsInProgress = totalRequestsInProgress;
        return this;
      }

      Builder setTotalIssuedRequests(long totalIssuedRequests) {
        this.totalIssuedRequests = totalIssuedRequests;
        return this;
      }

      Builder addLoadMetricStats(EndpointLoadMetricStats endpointLoadMetricStats) {
        loadMetricStatsList.add(checkNotNull(endpointLoadMetricStats, "endpointLoadMetricStats"));
        return this;
      }

      Builder addAllLoadMetricStats(Collection<EndpointLoadMetricStats> endpointLoadMetricStats) {
        loadMetricStatsList.addAll(
            checkNotNull(endpointLoadMetricStats, "endpointLoadMetricStats"));
        return this;
      }

      UpstreamLocalityStats build() {
        return new UpstreamLocalityStats(
            locality, totalSuccessfulRequests, totalErrorRequests, totalRequestsInProgress,
            totalIssuedRequests, loadMetricStatsList);
      }
    }
  }

  /**
   * See corresponding Envoy proto message {@link
   * io.envoyproxy.envoy.config.endpoint.v3.EndpointLoadMetricStats}.
   */
  static final class EndpointLoadMetricStats {
    private final String metricName;
    private final long numRequestsFinishedWithMetric;
    private final double totalMetricValue;

    private EndpointLoadMetricStats(String metricName, long numRequestsFinishedWithMetric,
        double totalMetricValue) {
      this.metricName = checkNotNull(metricName, "metricName");
      this.numRequestsFinishedWithMetric = numRequestsFinishedWithMetric;
      this.totalMetricValue = totalMetricValue;
    }

    String getMetricName() {
      return metricName;
    }

    long getNumRequestsFinishedWithMetric() {
      return numRequestsFinishedWithMetric;
    }

    double getTotalMetricValue() {
      return totalMetricValue;
    }

    private io.envoyproxy.envoy.config.endpoint.v3.EndpointLoadMetricStats
        toEnvoyProtoEndpointLoadMetricStats() {
      return io.envoyproxy.envoy.config.endpoint.v3.EndpointLoadMetricStats.newBuilder()
          .setMetricName(metricName)
          .setNumRequestsFinishedWithMetric(numRequestsFinishedWithMetric)
          .setTotalMetricValue(totalMetricValue)
          .build();
    }

    private io.envoyproxy.envoy.api.v2.endpoint.EndpointLoadMetricStats
        toEnvoyProtoEndpointLoadMetricStatsV2() {
      return io.envoyproxy.envoy.api.v2.endpoint.EndpointLoadMetricStats.newBuilder()
          .setMetricName(metricName)
          .setNumRequestsFinishedWithMetric(numRequestsFinishedWithMetric)
          .setTotalMetricValue(totalMetricValue)
          .build();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      EndpointLoadMetricStats that = (EndpointLoadMetricStats) o;
      return numRequestsFinishedWithMetric == that.numRequestsFinishedWithMetric
          && Double.compare(that.totalMetricValue, totalMetricValue) == 0
          && Objects.equals(metricName, that.metricName);
    }

    @Override
    public int hashCode() {
      return Objects.hash(metricName, numRequestsFinishedWithMetric, totalMetricValue);
    }

    static Builder newBuilder() {
      return new Builder();
    }

    static final class Builder {
      private String metricName;
      private long numRequestsFinishedWithMetric;
      private double totalMetricValue;

      private Builder() {
      }

      Builder setMetricName(String metricName) {
        this.metricName = checkNotNull(metricName, "metricName");
        return this;
      }

      Builder setNumRequestsFinishedWithMetric(long numRequestsFinishedWithMetric) {
        this.numRequestsFinishedWithMetric = numRequestsFinishedWithMetric;
        return this;
      }

      Builder setTotalMetricValue(double totalMetricValue) {
        this.totalMetricValue = totalMetricValue;
        return this;
      }

      EndpointLoadMetricStats build() {
        return new EndpointLoadMetricStats(
            metricName, numRequestsFinishedWithMetric, totalMetricValue);
      }
    }
  }
}
