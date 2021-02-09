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
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.grpc.Internal;
import java.net.InetAddress;
import java.net.UnknownHostException;
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

  static final String TRANSPORT_SOCKET_NAME_TLS = "envoy.transport_sockets.tls";

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
    private final InetAddress addressPrefix;
    private final int prefixLen;

    @VisibleForTesting
    CidrRange(String addressPrefix, int prefixLen) throws InvalidProtocolBufferException {
      try {
        this.addressPrefix = InetAddress.getByName(addressPrefix);
      } catch (UnknownHostException e) {
        throw new InvalidProtocolBufferException(e.getMessage());
      }
      this.prefixLen = prefixLen;
    }

    static CidrRange fromEnvoyProtoCidrRange(
        io.envoyproxy.envoy.config.core.v3.CidrRange proto) throws InvalidProtocolBufferException {
      return new CidrRange(proto.getAddressPrefix(), proto.getPrefixLen().getValue());
    }

    public InetAddress getAddressPrefix() {
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

  enum ConnectionSourceType {
    // Any connection source matches.
    ANY,

    // Match a connection originating from the same host.
    SAME_IP_OR_LOOPBACK,

    // Match a connection originating from a different host.
    EXTERNAL
  }

  /**
   * Corresponds to Envoy proto message
   * {@link io.envoyproxy.envoy.api.v2.listener.FilterChainMatch}.
   */
  static final class FilterChainMatch {
    private final int destinationPort;
    private final List<CidrRange> prefixRanges;
    private final List<String> applicationProtocols;
    private final List<CidrRange> sourcePrefixRanges;
    private final ConnectionSourceType sourceType;
    private final List<Integer> sourcePorts;

    @VisibleForTesting
    FilterChainMatch(
        int destinationPort,
        List<CidrRange> prefixRanges,
        List<String> applicationProtocols,
        List<CidrRange> sourcePrefixRanges,
        ConnectionSourceType sourceType,
        List<Integer> sourcePorts) {
      this.destinationPort = destinationPort;
      this.prefixRanges = Collections.unmodifiableList(prefixRanges);
      this.applicationProtocols = Collections.unmodifiableList(applicationProtocols);
      this.sourcePrefixRanges = sourcePrefixRanges;
      this.sourceType = sourceType;
      this.sourcePorts = sourcePorts;
    }

    static FilterChainMatch fromEnvoyProtoFilterChainMatch(
        io.envoyproxy.envoy.config.listener.v3.FilterChainMatch proto)
        throws InvalidProtocolBufferException {
      List<CidrRange> prefixRanges = new ArrayList<>();
      for (io.envoyproxy.envoy.config.core.v3.CidrRange range : proto.getPrefixRangesList()) {
        prefixRanges.add(CidrRange.fromEnvoyProtoCidrRange(range));
      }
      List<CidrRange> sourcePrefixRanges = new ArrayList<>();
      for (io.envoyproxy.envoy.config.core.v3.CidrRange range : proto.getSourcePrefixRangesList()) {
        sourcePrefixRanges.add(CidrRange.fromEnvoyProtoCidrRange(range));
      }
      List<String> applicationProtocols = new ArrayList<>();
      for (String appProtocol : proto.getApplicationProtocolsList()) {
        applicationProtocols.add(appProtocol);
      }
      ConnectionSourceType sourceType = null;
      switch (proto.getSourceType()) {
        case ANY:
          sourceType = ConnectionSourceType.ANY;
          break;
        case EXTERNAL:
          sourceType = ConnectionSourceType.EXTERNAL;
          break;
        case SAME_IP_OR_LOOPBACK:
          sourceType = ConnectionSourceType.SAME_IP_OR_LOOPBACK;
          break;
        default:
          throw new InvalidProtocolBufferException("Unknown source-type:" + proto.getSourceType());
      }
      return new FilterChainMatch(
          proto.getDestinationPort().getValue(),
          prefixRanges,
          applicationProtocols,
          sourcePrefixRanges,
          sourceType,
          proto.getSourcePortsList());
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

    public List<CidrRange> getSourcePrefixRanges() {
      return sourcePrefixRanges;
    }

    public ConnectionSourceType getConnectionSourceType() {
      return sourceType;
    }

    public List<Integer> getSourcePorts() {
      return sourcePorts;
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
          && Objects.equals(prefixRanges, that.prefixRanges)
          && Objects.equals(applicationProtocols, that.applicationProtocols)
          && Objects.equals(sourcePrefixRanges, that.sourcePrefixRanges)
          && sourceType == that.sourceType
          && Objects.equals(sourcePorts, that.sourcePorts);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          destinationPort,
          prefixRanges,
          applicationProtocols,
          sourcePrefixRanges,
          sourceType,
          sourcePorts);
    }

    @Override
    public String toString() {
      return "FilterChainMatch{"
          + "destinationPort="
          + destinationPort
          + ", prefixRanges="
          + prefixRanges
          + ", applicationProtocols="
          + applicationProtocols
          + ", sourcePrefixRanges="
          + sourcePrefixRanges
          + ", sourceType="
          + sourceType
          + ", sourcePorts="
          + sourcePorts
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
    private final FilterChain defaultFilterChain;

    @VisibleForTesting
    Listener(String name, String address,
        List<FilterChain> filterChains, FilterChain defaultFilterChain) {
      this.name = name;
      this.address = address;
      this.filterChains = Collections.unmodifiableList(filterChains);
      this.defaultFilterChain = defaultFilterChain;
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
        if (isAcceptable(filterChain.getFilterChainMatch())) {
          filterChains.add(FilterChain.fromEnvoyProtoFilterChain(filterChain));
        }
      }
      return new Listener(
          proto.getName(),
          convertEnvoyAddressToString(proto.getAddress()),
          filterChains, FilterChain.fromEnvoyProtoFilterChain(proto.getDefaultFilterChain()));
    }

    // check if a filter is acceptable for gRPC server side processing
    private static boolean isAcceptable(
        io.envoyproxy.envoy.config.listener.v3.FilterChainMatch filterChainMatch) {
      // reject if filer-chain-match
      // - has server_name
      // - transport protocol is other than "raw_buffer"
      // - application_protocols is non-empty (for now accept "managed-mtls")
      if (!filterChainMatch.getServerNamesList().isEmpty()) {
        return false;
      }
      String transportProtocol = filterChainMatch.getTransportProtocol();
      if (!transportProtocol.isEmpty() && !"raw_buffer".equals(transportProtocol)) {
        return false;
      }
      List<String> appProtocols = filterChainMatch.getApplicationProtocolsList();
      return appProtocols.isEmpty()
          || appProtocols.contains("managed-mtls"); // TODO(sanjaypujare): remove once TD fixed
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

    public FilterChain getDefaultFilterChain() {
      return defaultFilterChain;
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
      return Objects.equals(name, listener.name)
          && Objects.equals(address, listener.address)
          && Objects.equals(filterChains, listener.filterChains)
          && Objects.equals(defaultFilterChain, listener.defaultFilterChain);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, address, filterChains, defaultFilterChain);
    }

    @Override
    public String toString() {
      return "Listener{"
          + "name='"
          + name
          + '\''
          + ", address='"
          + address
          + '\''
          + ", filterChains="
          + filterChains
          + ", defaultFilterChain="
          + defaultFilterChain
          + '}';
    }
  }
}
