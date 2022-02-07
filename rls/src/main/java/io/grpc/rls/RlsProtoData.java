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

package io.grpc.rls;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.auto.value.AutoValue;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/** RlsProtoData is a collection of internal representation of RouteLookupService proto messages. */
final class RlsProtoData {

  private RlsProtoData() {}

  /** A request object sent to route lookup service. */
  @Immutable
  static final class RouteLookupRequest {

    private final ImmutableMap<String, String> keyMap;

    RouteLookupRequest(Map<String, String> keyMap) {
      this.keyMap = ImmutableMap.copyOf(checkNotNull(keyMap, "keyMap"));
    }

    /** Returns a map of key values extracted via key builders for the gRPC or HTTP request. */
    ImmutableMap<String, String> getKeyMap() {
      return keyMap;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      RouteLookupRequest that = (RouteLookupRequest) o;
      return Objects.equal(keyMap, that.keyMap);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(keyMap);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("keyMap", keyMap)
          .toString();
    }
  }

  /** A response from route lookup service. */
  @Immutable
  static final class RouteLookupResponse {

    private final ImmutableList<String> targets;

    private final String headerData;

    RouteLookupResponse(List<String> targets, String headerData) {
      checkState(targets != null && !targets.isEmpty(), "targets cannot be empty or null");
      this.targets = ImmutableList.copyOf(targets);
      this.headerData = checkNotNull(headerData, "headerData");
    }

    /**
     * Returns list of targets. Prioritized list (best one first) of addressable entities to use for
     * routing, using syntax requested by the request target_type. The targets will be tried in
     * order until a healthy one is found.
     */
    ImmutableList<String> getTargets() {
      return targets;
    }

    /**
     * Returns optional header data to pass along to AFE in the X-Google-RLS-Data header. Cached
     * with "target" and sent with all requests that match the request key. Allows the RLS to pass
     * its work product to the eventual target.
     */
    String getHeaderData() {
      return headerData;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      RouteLookupResponse that = (RouteLookupResponse) o;
      return java.util.Objects.equals(targets, that.targets)
          && java.util.Objects.equals(headerData, that.headerData);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(targets, headerData);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("targets", targets)
          .add("headerData", headerData)
          .toString();
    }
  }

  /** A config object for gRPC RouteLookupService. */
  @AutoValue
  @Immutable
  abstract static class RouteLookupConfig {

    /**
     * Returns unordered specifications for constructing keys for gRPC requests. All GrpcKeyBuilders
     * on this list must have unique "name" fields so that the client is free to prebuild a hash map
     * keyed by name. If no GrpcKeyBuilder matches, an empty key_map will be sent to the lookup
     * service; it should likely reply with a global default route and raise an alert.
     */
    abstract ImmutableList<GrpcKeyBuilder> grpcKeyBuilders();

    /**
     * Returns the name of the lookup service as a gRPC URI. Typically, this will be a subdomain of
     * the target, such as "lookup.datastore.googleapis.com".
     */
    abstract String lookupService();

    /** Returns the timeout value for lookup service requests. */
    abstract long lookupServiceTimeoutInNanos();

    /** Returns the maximum age the result will be cached. */
    abstract long maxAgeInNanos();

    /**
     * Returns the time when an entry will be in a staled status. When cache is accessed whgen the
     * entry is in staled status, it will
     */
    abstract long staleAgeInNanos();

    /**
     * Returns a rough indicator of amount of memory to use for the client cache. Some of the data
     * structure overhead is not accounted for, so actual memory consumed will be somewhat greater
     * than this value.  If this field is omitted or set to zero, a client default will be used.
     * The value may be capped to a lower amount based on client configuration.
     */
    abstract long cacheSizeBytes();

    /**
     * Returns the default target to use if needed.  If nonempty (implies request processing
     * strategy SYNC_LOOKUP_DEFAULT_TARGET_ON_ERROR is set), it will be used if RLS returns an
     * error.  Note that requests can be routed only to a subdomain of the original target,
     * {@literal e.g.} "us_east_1.cloudbigtable.googleapis.com".
     */
    @Nullable
    abstract String defaultTarget();

    static Builder builder() {
      return new AutoValue_RlsProtoData_RouteLookupConfig.Builder();
    }

    @AutoValue.Builder
    abstract static class Builder {

      abstract Builder grpcKeyBuilders(ImmutableList<GrpcKeyBuilder> grpcKeyBuilders);

      abstract Builder lookupService(String lookupService);

      abstract Builder lookupServiceTimeoutInNanos(long lookupServiceTimeoutInNanos);

      abstract Builder maxAgeInNanos(long maxAgeInNanos);

      abstract Builder staleAgeInNanos(long staleAgeInNanos);

      abstract Builder cacheSizeBytes(long cacheSizeBytes);

      abstract Builder defaultTarget(@Nullable String defaultTarget);

      abstract RouteLookupConfig build();
    }
  }

  /**
   * NameMatcher extract a key based on a given name (e.g. header name or query parameter name).
   * The name must match one of the names listed in the "name" field. If the "required_match" field
   * is true, one of the specified names must be present for the keybuilder to match.
   */
  @AutoValue
  @Immutable
  abstract static class NameMatcher {

    /** The name that will be used in the RLS key_map to refer to this value. */
    abstract String key();

    /** Returns ordered list of names; the first non-empty value will be used. */
    abstract ImmutableList<String> names();

    static NameMatcher create(String key, ImmutableList<String> names) {
      return new AutoValue_RlsProtoData_NameMatcher(key, names);
    }
  }

  /** GrpcKeyBuilder is a configuration to construct headers consumed by route lookup service. */
  @AutoValue
  @Immutable
  abstract static class GrpcKeyBuilder {

    /**
     * Returns names. To match, one of the given Name fields must match; the service and method
     * fields are specified as fixed strings. The service name is required and includes the proto
     * package name. The method name may be omitted, in which case any method on the given service
     * is matched.
     */
    abstract ImmutableList<Name> names();

    /**
     * Returns a list of NameMatchers for header. Extract keys from all listed headers. For gRPC, it
     * is an error to specify "required_match" on the NameMatcher protos, and we ignore it if set.
     */
    abstract ImmutableList<NameMatcher> headers();

    abstract ExtraKeys extraKeys();

    abstract ImmutableMap<String, String> constantKeys();

    static GrpcKeyBuilder create(
        ImmutableList<Name> names,
        ImmutableList<NameMatcher> headers,
        ExtraKeys extraKeys, ImmutableMap<String, String> constantKeys) {
      return new AutoValue_RlsProtoData_GrpcKeyBuilder(names, headers, extraKeys, constantKeys);
    }

    /**
     * Name represents a method for a given service. To match, one of the given Name fields must
     * match; the service and method fields are specified as fixed strings. The service name is
     * required and includes the proto package name. The method name may be omitted, in which case
     * any method on the given service is matched.
     */
    static final class Name {

      private final String service;

      @Nullable
      private final String method;

      /** The primary constructor. */
      Name(String service, @Nullable String method) {
        this.service = service;
        this.method = method;
      }

      String getService() {
        return service;
      }

      @Nullable
      String getMethod() {
        return method;
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (o == null || getClass() != o.getClass()) {
          return false;
        }
        Name name = (Name) o;
        return Objects.equal(service, name.service)
            && Objects.equal(method, name.method);
      }

      @Override
      public int hashCode() {
        return Objects.hashCode(service, method);
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("service", service)
            .add("method", method)
            .toString();
      }
    }
  }

  @AutoValue
  abstract static class ExtraKeys {
    static final ExtraKeys DEFAULT = create(null, null, null);

    @Nullable abstract String host();

    @Nullable abstract String service();

    @Nullable abstract String method();

    static ExtraKeys create(
        @Nullable String host, @Nullable String service, @Nullable String method) {
      return new AutoValue_RlsProtoData_ExtraKeys(host, service, method);
    }
  }
}
