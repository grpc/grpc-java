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

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.grpc.Internal;
import io.grpc.xds.internal.sds.SslContextProviderSupplier;
import java.net.InetAddress;
import java.net.UnknownHostException;
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

  @AutoValue
  abstract static class CidrRange {

    abstract InetAddress addressPrefix();

    abstract int prefixLen();

    static CidrRange create(String addressPrefix, int prefixLen) throws UnknownHostException {
      return new AutoValue_EnvoyServerProtoData_CidrRange(
          InetAddress.getByName(addressPrefix), prefixLen);
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
   * {@link io.envoyproxy.envoy.config.listener.v3.FilterChainMatch}.
   */
  @AutoValue
  abstract static class FilterChainMatch {

    abstract int destinationPort();

    abstract ImmutableList<CidrRange> prefixRanges();

    abstract ImmutableList<String> applicationProtocols();

    abstract ImmutableList<CidrRange> sourcePrefixRanges();

    abstract ConnectionSourceType connectionSourceType();

    abstract ImmutableList<Integer> sourcePorts();

    abstract ImmutableList<String> serverNames();

    abstract String transportProtocol();

    public static FilterChainMatch create(int destinationPort,
        ImmutableList<CidrRange> prefixRanges,
        ImmutableList<String> applicationProtocols, ImmutableList<CidrRange> sourcePrefixRanges,
        ConnectionSourceType connectionSourceType, ImmutableList<Integer> sourcePorts,
        ImmutableList<String> serverNames, String transportProtocol) {
      return new AutoValue_EnvoyServerProtoData_FilterChainMatch(
          destinationPort, prefixRanges, applicationProtocols, sourcePrefixRanges,
          connectionSourceType, sourcePorts, serverNames, transportProtocol);
    }
  }

  /**
   * Corresponds to Envoy proto message {@link io.envoyproxy.envoy.config.listener.v3.FilterChain}.
   */
  @AutoValue
  abstract static class FilterChain {

    // possibly empty
    abstract String name();

    // TODO(sanjaypujare): flatten structure by moving FilterChainMatch class members here.
    abstract FilterChainMatch filterChainMatch();

    abstract HttpConnectionManager httpConnectionManager();

    @Nullable
    abstract SslContextProviderSupplier sslContextProviderSupplier();

    static FilterChain create(
        String name,
        FilterChainMatch filterChainMatch,
        HttpConnectionManager httpConnectionManager,
        @Nullable DownstreamTlsContext downstreamTlsContext,
        TlsContextManager tlsContextManager) {
      SslContextProviderSupplier sslContextProviderSupplier =
          downstreamTlsContext == null
              ? null : new SslContextProviderSupplier(downstreamTlsContext, tlsContextManager);
      return new AutoValue_EnvoyServerProtoData_FilterChain(
          name, filterChainMatch, httpConnectionManager, sslContextProviderSupplier);
    }
  }

  /**
   * Corresponds to Envoy proto message {@link io.envoyproxy.envoy.config.listener.v3.Listener} and
   * related classes.
   */
  @AutoValue
  abstract static class Listener {

    abstract String name();

    @Nullable
    abstract String address();

    abstract ImmutableList<FilterChain> filterChains();

    @Nullable
    abstract FilterChain defaultFilterChain();

    static Listener create(
        String name,
        @Nullable String address,
        ImmutableList<FilterChain> filterChains,
        @Nullable FilterChain defaultFilterChain) {
      return new AutoValue_EnvoyServerProtoData_Listener(name, address, filterChains,
          defaultFilterChain);
    }
  }
}
