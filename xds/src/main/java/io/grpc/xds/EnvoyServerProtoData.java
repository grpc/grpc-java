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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.grpc.Internal;
import io.grpc.xds.internal.sds.SslContextProviderSupplier;
import java.net.InetAddress;
import java.net.UnknownHostException;
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
    private final InetAddress addressPrefix;
    private final int prefixLen;

    CidrRange(String addressPrefix, int prefixLen) throws UnknownHostException {
      this.addressPrefix = InetAddress.getByName(addressPrefix);
      this.prefixLen = prefixLen;
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
    private final List<String> serverNames;
    private final String transportProtocol;

    @VisibleForTesting
    FilterChainMatch(
        int destinationPort,
        List<CidrRange> prefixRanges,
        List<String> applicationProtocols,
        List<CidrRange> sourcePrefixRanges,
        ConnectionSourceType sourceType,
        List<Integer> sourcePorts,
        List<String> serverNames,
        String transportProtocol) {
      this.destinationPort = destinationPort;
      this.prefixRanges = Collections.unmodifiableList(prefixRanges);
      this.applicationProtocols = Collections.unmodifiableList(applicationProtocols);
      this.sourcePrefixRanges = sourcePrefixRanges;
      this.sourceType = sourceType;
      this.sourcePorts = sourcePorts;
      this.serverNames = Collections.unmodifiableList(serverNames);
      this.transportProtocol = transportProtocol;
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

    public List<String> getServerNames() {
      return serverNames;
    }

    public String getTransportProtocol() {
      return transportProtocol;
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
          && Objects.equals(sourcePorts, that.sourcePorts)
          && Objects.equals(serverNames, that.serverNames)
          && Objects.equals(transportProtocol, that.transportProtocol);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          destinationPort,
          prefixRanges,
          applicationProtocols,
          sourcePrefixRanges,
          sourceType,
          sourcePorts,
          serverNames,
          transportProtocol);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
              .add("destinationPort", destinationPort)
              .add("prefixRanges", prefixRanges)
              .add("applicationProtocols", applicationProtocols)
              .add("sourcePrefixRanges", sourcePrefixRanges)
              .add("sourceType", sourceType)
              .add("sourcePorts", sourcePorts)
              .add("serverNames", serverNames)
              .add("transportProtocol", transportProtocol)
              .toString();
    }
  }

  /**
   * Corresponds to Envoy proto message {@link io.envoyproxy.envoy.api.v2.listener.FilterChain}.
   */
  static final class FilterChain {
    // possibly empty
    private final String name;
    // TODO(sanjaypujare): flatten structure by moving FilterChainMatch class members here.
    private final FilterChainMatch filterChainMatch;
    private final HttpConnectionManager httpConnectionManager;
    @Nullable
    private final SslContextProviderSupplier sslContextProviderSupplier;

    FilterChain(
        String name,
        FilterChainMatch filterChainMatch,
        HttpConnectionManager httpConnectionManager,
        @Nullable DownstreamTlsContext downstreamTlsContext,
        TlsContextManager tlsContextManager) {
      SslContextProviderSupplier sslContextProviderSupplier1 = downstreamTlsContext == null ? null
          : new SslContextProviderSupplier(downstreamTlsContext, tlsContextManager);
      this.name = checkNotNull(name, "name");
      // TODO(chengyuanzhang): enforce non-null, change tests to use a default/empty
      //  FilterChainMatch instead of null, as that's how the proto is converted.
      this.filterChainMatch = filterChainMatch;
      this.sslContextProviderSupplier = sslContextProviderSupplier1;
      this.httpConnectionManager = checkNotNull(httpConnectionManager, "httpConnectionManager");
    }

    String getName() {
      return name;
    }

    public FilterChainMatch getFilterChainMatch() {
      return filterChainMatch;
    }

    HttpConnectionManager getHttpConnectionManager() {
      return httpConnectionManager;
    }

    @Nullable
    public SslContextProviderSupplier getSslContextProviderSupplier() {
      return sslContextProviderSupplier;
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
      return Objects.equals(name, that.name)
          && Objects.equals(filterChainMatch, that.filterChainMatch)
          && Objects.equals(httpConnectionManager, that.httpConnectionManager)
          && Objects.equals(sslContextProviderSupplier, that.sslContextProviderSupplier);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          name, filterChainMatch, httpConnectionManager, sslContextProviderSupplier);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("name", name)
          .add("filterChainMatch", filterChainMatch)
          .add("httpConnectionManager", httpConnectionManager)
          .add("sslContextProviderSupplier", sslContextProviderSupplier)
          .toString();
    }
  }

  /**
   * Corresponds to Envoy proto message {@link io.envoyproxy.envoy.api.v2.Listener} & related
   * classes.
   */
  public static final class Listener {
    private final String name;
    @Nullable
    private final String address;
    private final List<FilterChain> filterChains;
    @Nullable
    private final FilterChain defaultFilterChain;

    /** Construct a Listener. */
    public Listener(String name, @Nullable String address,
        List<FilterChain> filterChains, @Nullable FilterChain defaultFilterChain) {
      this.name = checkNotNull(name, "name");
      this.address = address;
      this.filterChains = Collections.unmodifiableList(checkNotNull(filterChains, "filterChains"));
      this.defaultFilterChain = defaultFilterChain;
    }

    public String getName() {
      return name;
    }

    @Nullable
    public String getAddress() {
      return address;
    }

    public List<FilterChain> getFilterChains() {
      return filterChains;
    }

    @Nullable
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
      return MoreObjects.toStringHelper(this)
          .add("name", name)
          .add("address", address)
          .add("filterChains", filterChains)
          .add("defaultFilterChain", defaultFilterChain)
          .toString();
    }
  }
}
