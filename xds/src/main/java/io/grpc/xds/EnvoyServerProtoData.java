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

import com.google.common.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Defines gRPC data types for Envoy protobuf messages used in xDS protocol on the server side,
 * similar to how {@link EnvoyProtoData} defines it for the client side.
 */
final class EnvoyServerProtoData {

  // Prevent instantiation.
  private EnvoyServerProtoData() {
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
      this.prefixRanges = Collections.unmodifiableList(prefixRanges);
      this.applicationProtocols = Collections.unmodifiableList(applicationProtocols);
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
          prefixRanges,
          applicationProtocols);
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
    @Nullable
    private final String address;
    private final List<FilterChain> filterChains;

    private Listener(String name, String address,
        List<FilterChain> filterChains) {
      this.name = name;
      this.address = address;
      this.filterChains = Collections.unmodifiableList(filterChains);
    }

    private static String convertEnvoyAddressToString(
        io.envoyproxy.envoy.api.v2.core.Address proto) {
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
          convertEnvoyAddressToString(proto.getAddress()),
          filterChains);
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
