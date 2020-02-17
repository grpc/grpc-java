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
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import io.envoyproxy.envoy.type.FractionalPercent;
import io.envoyproxy.envoy.type.FractionalPercent.DenominatorType;
import io.grpc.EquivalentAddressGroup;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
      return Objects.equal(region, locality.region)
          && Objects.equal(zone, locality.zone)
          && Objects.equal(subzone, locality.subzone);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(region, zone, subzone);
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
          && Objects.equal(endpoints, that.endpoints);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(endpoints, localityWeight, priority);
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
          && Objects.equal(eag, that.eag)
          && isHealthy == that.isHealthy;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(eag, loadBalancingWeight, isHealthy);
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
   * See corresponding Enovy proto message {@link
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
      return dropsPerMillion == that.dropsPerMillion && Objects.equal(category, that.category);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(category, dropsPerMillion);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("category", category)
          .add("dropsPerMillion", dropsPerMillion)
          .toString();
    }
  }

  static final class CidrRange {
    private final String addressPrefix;
    private final int prefixLen;

    @VisibleForTesting
    CidrRange(String addressPrefix, int prefixLen) {
      this.addressPrefix = addressPrefix;
      this.prefixLen = prefixLen;
    }

    static CidrRange fromEnvoyProtoCidrRange(
        io.envoyproxy.envoy.api.v2.core.CidrRange proto) {
      return new CidrRange(proto.getAddressPrefix(), proto.getPrefixLen().getValue());
    }

    public String getAddressPrefix() {
      return addressPrefix;
    }

    public int getPrefixLen() {
      return prefixLen;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      CidrRange cidrRange = (CidrRange) o;
      return prefixLen == cidrRange.prefixLen
          && java.util.Objects.equals(addressPrefix, cidrRange.addressPrefix);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(addressPrefix, prefixLen);
    }

    @Override
    public String toString() {
      return "CidrRange{"
          + "addressPrefix='" + addressPrefix + '\''
          + ", prefixLen=" + prefixLen
          + '}';
    }
  }

  /**
   * Corresponds to Envoy proto message
   * {@link io.envoyproxy.envoy.api.v2.listener.FilterChainMatch}.
   */
  static final class FilterChainMatch {
    private final int destinationPort;
    private final List<CidrRange> prefixRanges;
    private final List<String> applicationProtocols;

    private FilterChainMatch(int destinationPort,
        List<CidrRange> prefixRanges, List<String> applicationProtocols) {
      this.destinationPort = destinationPort;
      this.prefixRanges = prefixRanges;
      this.applicationProtocols = applicationProtocols;
    }

    static FilterChainMatch fromEnvoyProtoFilterChainMatch(
        io.envoyproxy.envoy.api.v2.listener.FilterChainMatch proto) {
      List<CidrRange> prefixRanges = new ArrayList<>();
      for (io.envoyproxy.envoy.api.v2.core.CidrRange range : proto.getPrefixRangesList()) {
        prefixRanges.add(CidrRange.fromEnvoyProtoCidrRange(range));
      }
      List<String> applicationProtocols = new ArrayList<>();
      for (String appProtocol  : proto.getApplicationProtocolsList()) {
        applicationProtocols.add(appProtocol);
      }
      return new FilterChainMatch(
          proto.getDestinationPort().getValue(),
          Collections.unmodifiableList(prefixRanges),
          Collections.unmodifiableList(applicationProtocols));
    }

    public int getDestinationPort() {
      return destinationPort;
    }

    public List<CidrRange> getPrefixRanges() {
      return prefixRanges;
    }

    public List<String> getApplicationProtocols() {
      return applicationProtocols;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      FilterChainMatch that = (FilterChainMatch) o;
      return destinationPort == that.destinationPort
          && java.util.Objects.equals(prefixRanges, that.prefixRanges)
          && java.util.Objects.equals(applicationProtocols, that.applicationProtocols);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(destinationPort, prefixRanges, applicationProtocols);
    }

    @Override
    public String toString() {
      return "FilterChainMatch{"
          + "destinationPort=" + destinationPort
          + ", prefixRanges=" + prefixRanges
          + ", applicationProtocols=" + applicationProtocols
          + '}';
    }
  }

  /**
   * Corresponds to Envoy proto message {@link io.envoyproxy.envoy.api.v2.listener.FilterChain}.
   */
  static final class FilterChain {
    private final FilterChainMatch filterChainMatch;
    // TODO(sanjaypujare): remove dependency on envoy data type along with rest of the code.
    private final io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext downstreamTlsContext;


    private FilterChain(FilterChainMatch filterChainMatch,
        io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext downstreamTlsContext) {
      this.filterChainMatch = filterChainMatch;
      this.downstreamTlsContext = downstreamTlsContext;
    }

    static FilterChain fromEnvoyProtoFilterChain(
        io.envoyproxy.envoy.api.v2.listener.FilterChain proto) {
      return new FilterChain(
          FilterChainMatch.fromEnvoyProtoFilterChainMatch(proto.getFilterChainMatch()),
          proto.getTlsContext()
      );
    }

    public FilterChainMatch getFilterChainMatch() {
      return filterChainMatch;
    }

    public io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext getDownstreamTlsContext() {
      return downstreamTlsContext;
    }


    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      FilterChain that = (FilterChain) o;
      return java.util.Objects.equals(filterChainMatch, that.filterChainMatch)
          && java.util.Objects.equals(downstreamTlsContext, that.downstreamTlsContext);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(filterChainMatch, downstreamTlsContext);
    }

    @Override
    public String toString() {
      return "FilterChain{"
          + "filterChainMatch=" + filterChainMatch
          + ", downstreamTlsContext=" + downstreamTlsContext
          + '}';
    }
  }

  /**
   * Corresponds to Envoy proto message {@link io.envoyproxy.envoy.api.v2.Listener} & related
   * classes.
   */
  static final class Listener {
    private final String name;
    private final String address;
    private final List<FilterChain> filterChains;

    private Listener(String name, String address,
        List<FilterChain> filterChains) {
      this.name = name;
      this.address = address;
      this.filterChains = filterChains;
    }

    static String fromEnvoyProtoAddress(io.envoyproxy.envoy.api.v2.core.Address proto) {
      if (proto.hasSocketAddress()) {
        io.envoyproxy.envoy.api.v2.core.SocketAddress socketAddress = proto.getSocketAddress();
        String address = socketAddress.getAddress();
        switch (socketAddress.getPortSpecifierCase()) {
          case NAMED_PORT:
            return address + ":" + socketAddress.getNamedPort();
          case PORT_VALUE:
            return address + ":" + socketAddress.getPortValue();
          default:
            return address;
        }
      }
      return null;
    }

    static Listener fromEnvoyProtoListener(io.envoyproxy.envoy.api.v2.Listener proto) {
      List<FilterChain> filterChains = new ArrayList<>(proto.getFilterChainsCount());
      for (io.envoyproxy.envoy.api.v2.listener.FilterChain filterChain :
          proto.getFilterChainsList()) {
        filterChains.add(FilterChain.fromEnvoyProtoFilterChain(filterChain));
      }
      return new Listener(
          proto.getName(),
          fromEnvoyProtoAddress(proto.getAddress()),
          Collections.unmodifiableList(filterChains));
    }

    public String getName() {
      return name;
    }

    public String getAddress() {
      return address;
    }

    public List<FilterChain> getFilterChains() {
      return filterChains;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Listener listener = (Listener) o;
      return java.util.Objects.equals(name, listener.name)
          &&  java.util.Objects.equals(address, listener.address)
          &&  java.util.Objects.equals(filterChains, listener.filterChains);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(name, address, filterChains);
    }

    @Override
    public String toString() {
      return "Listener{"
          + "name='" + name + '\''
          + ", address='" + address + '\''
          + ", filterChains=" + filterChains
          + '}';
    }
  }
}
