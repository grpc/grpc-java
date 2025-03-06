/*
 * Copyright 2021 The gRPC Authors
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

import com.google.common.base.MoreObjects;
import com.google.protobuf.Message;
import io.grpc.ClientInterceptor;
import io.grpc.ServerInterceptor;
import java.io.Closeable;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;

/**
 * Defines the parsing functionality of an HTTP filter.
 *
 * <p>A Filter may optionally implement either {@link Filter#buildClientInterceptor} or
 * {@link Filter#buildServerInterceptor} or both, and return true from corresponding
 * {@link Provider#isClientFilter()}, {@link Provider#isServerFilter()} to indicate that the filter
 * is capable of working on the client side or server side or both, respectively.
 */
interface Filter extends Closeable {

  /** Represents an opaque data structure holding configuration for a filter. */
  interface FilterConfig {
    String typeUrl();
  }

  /**
   * Common interface for filter providers.
   */
  interface Provider {
    /**
     * The proto message types supported by this filter. A filter will be registered by each of its
     * supported message types.
     */
    String[] typeUrls();

    /**
     * Whether the filter can be installed on the client side.
     *
     * <p>Returns true if the filter implements {@link Filter#buildClientInterceptor}.
     */
    default boolean isClientFilter() {
      return false;
    }

    /**
     * Whether the filter can be installed into xDS-enabled servers.
     *
     * <p>Returns true if the filter implements {@link Filter#buildServerInterceptor}.
     */
    default boolean isServerFilter() {
      return false;
    }

    /**
     * Creates a new instance of the filter.
     *
     * <p>Returns a filter instance registered with the same typeUrls as the provider,
     * capable of working with the same FilterConfig type returned by provider's parse functions.
     *
     * <p>For xDS gRPC clients, new filter instances are created per combination of:
     * <ol>
     *   <li><code>XdsNameResolver</code> instance,</li>
     *   <li>Filter name+typeUrl in HttpConnectionManager (HCM) http_filters.</li>
     * </ol>
     *
     * <p>For xDS-enabled gRPC servers, new filter instances are created per combination of:
     * <ol>
     *   <li>Server instance,</li>
     *   <li>FilterChain name,</li>
     *   <li>Filter name+typeUrl in FilterChain's HCM.http_filters.</li>
     * </ol>
     */
    Filter newInstance();

    /**
     * Parses the top-level filter config from raw proto message. The message may be either a {@link
     * com.google.protobuf.Any} or a {@link com.google.protobuf.Struct}.
     */
    ConfigOrError<? extends FilterConfig> parseFilterConfig(Message rawProtoMessage);

    /**
     * Parses the per-filter override filter config from raw proto message. The message may be
     * either a {@link com.google.protobuf.Any} or a {@link com.google.protobuf.Struct}.
     */
    ConfigOrError<? extends FilterConfig> parseFilterConfigOverride(Message rawProtoMessage);
  }

  /** Uses the FilterConfigs produced above to produce an HTTP filter interceptor for clients. */
  @Nullable
  default ClientInterceptor buildClientInterceptor(
      FilterConfig config, @Nullable FilterConfig overrideConfig,
      ScheduledExecutorService scheduler) {
    return null;
  }

  /** Uses the FilterConfigs produced above to produce an HTTP filter interceptor for the server. */
  @Nullable
  default ServerInterceptor buildServerInterceptor(
      FilterConfig config, @Nullable FilterConfig overrideConfig) {
    return null;
  }

  /**
   * Releases filter resources like shared resources and remote connections.
   *
   * <p>See {@link Provider#newInstance()} for details on filter instance creation.
   */
  @Override
  default void close() {}

  /** Filter config with instance name. */
  final class NamedFilterConfig {
    // filter instance name
    final String name;
    final FilterConfig filterConfig;

    NamedFilterConfig(String name, FilterConfig filterConfig) {
      this.name = name;
      this.filterConfig = filterConfig;
    }

    String filterStateKey() {
      return name + "_" + filterConfig.typeUrl();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      NamedFilterConfig that = (NamedFilterConfig) o;
      return Objects.equals(name, that.name)
          && Objects.equals(filterConfig, that.filterConfig);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, filterConfig);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("name", name)
          .add("filterConfig", filterConfig)
          .toString();
    }
  }
}
