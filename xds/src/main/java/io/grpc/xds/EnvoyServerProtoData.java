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

import static io.grpc.xds.EnvoyProtoData.TRANSPORT_SOCKET_NAME_TLS;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.grpc.Internal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Defines gRPC data types for Envoy protobuf messages used in xDS protocol on the server side,
 * similar to how {@link EnvoyProtoData} defines it for the client side.
 */
@Internal
public final class EnvoyServerProtoData {

  // Prevent instantiation.
  private EnvoyServerProtoData() {
  }

  public abstract static class BaseTlsContext {
    @Nullable protected final CommonTlsContext commonTlsContext;

    protected BaseTlsContext(@Nullable CommonTlsContext commonTlsContext) {
      this.commonTlsContext = commonTlsContext;
    }

    @Nullable public CommonTlsContext getCommonTlsContext() {
      return commonTlsContext;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof BaseTlsContext)) {
        return false;
      }
      BaseTlsContext that = (BaseTlsContext) o;
      return Objects.equals(commonTlsContext, that.commonTlsContext);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(commonTlsContext);
    }
  }

  public static final class UpstreamTlsContext extends BaseTlsContext {

    @VisibleForTesting
    public UpstreamTlsContext(CommonTlsContext commonTlsContext) {
      super(commonTlsContext);
    }

    public static UpstreamTlsContext fromEnvoyProtoUpstreamTlsContext(
        io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext
            upstreamTlsContext) {
      return new UpstreamTlsContext(upstreamTlsContext.getCommonTlsContext());
    }

    @Override
    public String toString() {
      return "UpstreamTlsContext{" + "commonTlsContext=" + commonTlsContext + '}';
    }
  }

  public static final class DownstreamTlsContext extends BaseTlsContext {

    private final boolean requireClientCertificate;

    @VisibleForTesting
    public DownstreamTlsContext(
        CommonTlsContext commonTlsContext, boolean requireClientCertificate) {
      super(commonTlsContext);
      this.requireClientCertificate = requireClientCertificate;
    }

    public static DownstreamTlsContext fromEnvoyProtoDownstreamTlsContext(
        io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext
            downstreamTlsContext) {
      return new DownstreamTlsContext(downstreamTlsContext.getCommonTlsContext(),
        downstreamTlsContext.hasRequireClientCertificate());
    }

    public boolean isRequireClientCertificate() {
      return requireClientCertificate;
    }

    @Override
    public String toString() {
      return "DownstreamTlsContext{"
          + "commonTlsContext="
          + commonTlsContext
          + ", requireClientCertificate="
          + requireClientCertificate
          + '}';
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      if (!super.equals(o)) {
        return false;
      }
      DownstreamTlsContext that = (DownstreamTlsContext) o;
      return requireClientCertificate == that.requireClientCertificate;
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), requireClientCertificate);
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
        io.envoyproxy.envoy.config.core.v3.CidrRange proto) {
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

    @VisibleForTesting
    FilterChainMatch(int destinationPort,
        List<CidrRange> prefixRanges, List<String> applicationProtocols) {
      this.destinationPort = destinationPort;
      this.prefixRanges = Collections.unmodifiableList(prefixRanges);
      this.applicationProtocols = Collections.unmodifiableList(applicationProtocols);
    }

    static FilterChainMatch fromEnvoyProtoFilterChainMatch(
        io.envoyproxy.envoy.config.listener.v3.FilterChainMatch proto) {
      List<CidrRange> prefixRanges = new ArrayList<>();
      for (io.envoyproxy.envoy.config.core.v3.CidrRange range : proto.getPrefixRangesList()) {
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
    // TODO(sanjaypujare): flatten structure by moving FilterChainMatch class members here.
    private final FilterChainMatch filterChainMatch;
    @Nullable
    private final DownstreamTlsContext downstreamTlsContext;

    @VisibleForTesting
    FilterChain(
        FilterChainMatch filterChainMatch, @Nullable DownstreamTlsContext downstreamTlsContext) {
      this.filterChainMatch = filterChainMatch;
      this.downstreamTlsContext = downstreamTlsContext;
    }

    static FilterChain fromEnvoyProtoFilterChain(
        io.envoyproxy.envoy.config.listener.v3.FilterChain proto)
        throws InvalidProtocolBufferException {
      return new FilterChain(
          FilterChainMatch.fromEnvoyProtoFilterChainMatch(proto.getFilterChainMatch()),
          getTlsContextFromFilterChain(proto)
      );
    }

    @Nullable
    private static DownstreamTlsContext getTlsContextFromFilterChain(
        io.envoyproxy.envoy.config.listener.v3.FilterChain filterChain)
        throws InvalidProtocolBufferException {
      if (filterChain.hasTransportSocket()
          && TRANSPORT_SOCKET_NAME_TLS.equals(filterChain.getTransportSocket().getName())) {
        Any any = filterChain.getTransportSocket().getTypedConfig();
        return DownstreamTlsContext.fromEnvoyProtoDownstreamTlsContext(
            io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext.parseFrom(
                any.getValue()));
      }
      return null;
    }

    public FilterChainMatch getFilterChainMatch() {
      return filterChainMatch;
    }

    @Nullable
    public DownstreamTlsContext getDownstreamTlsContext() {
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

    @VisibleForTesting
    Listener(String name, String address,
        List<FilterChain> filterChains) {
      this.name = name;
      this.address = address;
      this.filterChains = Collections.unmodifiableList(filterChains);
    }

    private static String convertEnvoyAddressToString(Address proto) {
      if (proto.hasSocketAddress()) {
        SocketAddress socketAddress = proto.getSocketAddress();
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

    static Listener fromEnvoyProtoListener(io.envoyproxy.envoy.config.listener.v3.Listener proto)
        throws InvalidProtocolBufferException {
      List<FilterChain> filterChains = new ArrayList<>(proto.getFilterChainsCount());
      for (io.envoyproxy.envoy.config.listener.v3.FilterChain filterChain :
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
