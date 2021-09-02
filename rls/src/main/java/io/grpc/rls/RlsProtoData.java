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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.rls.RlsProtoData.GrpcKeyBuilder.Name;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/** RlsProtoData is a collection of internal representation of RouteLookupService proto messages. */
final class RlsProtoData {

  /** A request object sent to route lookup service. */
  @Immutable
  static final class RouteLookupRequest {

    private final String server;

    private final String path;

    private final String targetType;

    private final ImmutableMap<String, String> keyMap;

    RouteLookupRequest(
        String server, String path, String targetType, Map<String, String> keyMap) {
      this.server = checkNotNull(server, "server");
      this.path = checkNotNull(path, "path");
      this.targetType = checkNotNull(targetType, "targetName");
      this.keyMap = ImmutableMap.copyOf(checkNotNull(keyMap, "keyMap"));
    }

    /**
     * Returns a full host name of the target server, {@literal e.g.} firestore.googleapis.com. Only
     * set for gRPC requests; HTTP requests must use key_map explicitly.
     */
    String getServer() {
      return server;
    }

    /**
     * Returns a full path of the request, {@literal i.e.} "/service/method". Only set for gRPC
     * requests; HTTP requests must use key_map explicitly.
     */
    String getPath() {
      return path;
    }

    /**
     * Returns the target type allows the client to specify what kind of target format it would like
     * from RLS to allow it to find the regional server, {@literal e.g.} "grpc".
     */
    String getTargetType() {
      return targetType;
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
      return Objects.equal(server, that.server)
          && Objects.equal(path, that.path)
          && Objects.equal(targetType, that.targetType)
          && Objects.equal(keyMap, that.keyMap);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(server, path, targetType, keyMap);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("server", server)
          .add("path", path)
          .add("targetName", targetType)
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
  @Immutable
  static final class RouteLookupConfig {

    private static final long MAX_AGE_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private final ImmutableList<GrpcKeyBuilder> grpcKeyBuilders;

    private final String lookupService;

    private final long lookupServiceTimeoutInMillis;

    private final long maxAgeInMillis;

    private final long staleAgeInMillis;

    private final long cacheSizeBytes;

    private final ImmutableList<String> validTargets;

    @Nullable
    private final String defaultTarget;

    RouteLookupConfig(
        List<GrpcKeyBuilder> grpcKeyBuilders,
        String lookupService,
        long lookupServiceTimeoutInMillis,
        @Nullable Long maxAgeInMillis,
        @Nullable Long staleAgeInMillis,
        long cacheSizeBytes,
        List<String> validTargets,
        @Nullable
        String defaultTarget) {
      checkState(
          !checkNotNull(grpcKeyBuilders, "grpcKeyBuilders").isEmpty(),
          "must have at least one GrpcKeyBuilder");
      checkUniqueName(grpcKeyBuilders);
      this.grpcKeyBuilders = ImmutableList.copyOf(grpcKeyBuilders);
      // TODO(creamsoup) also check if it is URI
      checkState(
          lookupService != null && !lookupService.isEmpty(), "lookupService must not be empty");
      this.lookupService = lookupService;
      checkState(
          lookupServiceTimeoutInMillis > 0, "lookupServiceTimeoutInMillis should be positive");
      this.lookupServiceTimeoutInMillis = lookupServiceTimeoutInMillis;
      if (maxAgeInMillis == null) {
        checkState(
            staleAgeInMillis == null, "To specify staleAgeInMillis, must have maxAgeInMillis");
      }
      if (maxAgeInMillis == null || maxAgeInMillis == 0) {
        maxAgeInMillis = MAX_AGE_MILLIS;
      }
      if (staleAgeInMillis == null || staleAgeInMillis == 0) {
        staleAgeInMillis = MAX_AGE_MILLIS;
      }
      this.maxAgeInMillis = Math.min(maxAgeInMillis, MAX_AGE_MILLIS);
      this.staleAgeInMillis = Math.min(staleAgeInMillis, this.maxAgeInMillis);
      checkArgument(cacheSizeBytes > 0, "cacheSize must be positive");
      this.cacheSizeBytes = cacheSizeBytes;
      this.validTargets = ImmutableList.copyOf(checkNotNull(validTargets, "validTargets"));
      this.defaultTarget = defaultTarget;
    }

    /**
     * Returns unordered specifications for constructing keys for gRPC requests. All GrpcKeyBuilders
     * on this list must have unique "name" fields so that the client is free to prebuild a hash map
     * keyed by name. If no GrpcKeyBuilder matches, an empty key_map will be sent to the lookup
     * service; it should likely reply with a global default route and raise an alert.
     */
    ImmutableList<GrpcKeyBuilder> getGrpcKeyBuilders() {
      return grpcKeyBuilders;
    }

    /**
     * Returns the name of the lookup service as a gRPC URI. Typically, this will be a subdomain of
     * the target, such as "lookup.datastore.googleapis.com".
     */
    String getLookupService() {
      return lookupService;
    }

    /** Returns the timeout value for lookup service requests. */
    long getLookupServiceTimeoutInMillis() {
      return lookupServiceTimeoutInMillis;
    }


    /** Returns the maximum age the result will be cached. */
    long getMaxAgeInMillis() {
      return maxAgeInMillis;
    }

    /**
     * Returns the time when an entry will be in a staled status. When cache is accessed whgen the
     * entry is in staled status, it will
     */
    long getStaleAgeInMillis() {
      return staleAgeInMillis;
    }

    /**
     * Returns a rough indicator of amount of memory to use for the client cache. Some of the data
     * structure overhead is not accounted for, so actual memory consumed will be somewhat greater
     * than this value.  If this field is omitted or set to zero, a client default will be used.
     * The value may be capped to a lower amount based on client configuration.
     */
    long getCacheSizeBytes() {
      return cacheSizeBytes;
    }

    /**
     * Returns the list of all the possible targets that can be returned by the lookup service.  If
     * a target not on this list is returned, it will be treated the same as an RPC error from the
     * RLS.
     */
    ImmutableList<String> getValidTargets() {
      return validTargets;
    }

    /**
     * Returns the default target to use if needed.  If nonempty (implies request processing
     * strategy SYNC_LOOKUP_DEFAULT_TARGET_ON_ERROR is set), it will be used if RLS returns an
     * error.  Note that requests can be routed only to a subdomain of the original target,
     * {@literal e.g.} "us_east_1.cloudbigtable.googleapis.com".
     */
    String getDefaultTarget() {
      return defaultTarget;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      RouteLookupConfig that = (RouteLookupConfig) o;
      return lookupServiceTimeoutInMillis == that.lookupServiceTimeoutInMillis
          && maxAgeInMillis == that.maxAgeInMillis
          && staleAgeInMillis == that.staleAgeInMillis
          && cacheSizeBytes == that.cacheSizeBytes
          && Objects.equal(grpcKeyBuilders, that.grpcKeyBuilders)
          && Objects.equal(lookupService, that.lookupService)
          && Objects.equal(defaultTarget, that.defaultTarget);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(
          grpcKeyBuilders,
          lookupService,
          lookupServiceTimeoutInMillis,
          maxAgeInMillis,
          staleAgeInMillis,
          cacheSizeBytes,
          defaultTarget);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("grpcKeyBuilders", grpcKeyBuilders)
          .add("lookupService", lookupService)
          .add("lookupServiceTimeoutInMillis", lookupServiceTimeoutInMillis)
          .add("maxAgeInMillis", maxAgeInMillis)
          .add("staleAgeInMillis", staleAgeInMillis)
          .add("cacheSize", cacheSizeBytes)
          .add("defaultTarget", defaultTarget)
          .toString();
    }
  }

  private static void checkUniqueName(List<GrpcKeyBuilder> grpcKeyBuilders) {
    Set<Name> names = new HashSet<>();
    for (GrpcKeyBuilder grpcKeyBuilder : grpcKeyBuilders) {
      int prevSize = names.size();
      names.addAll(grpcKeyBuilder.getNames());
      if (names.size() != prevSize + grpcKeyBuilder.getNames().size()) {
        throw new IllegalStateException("Names in the GrpcKeyBuilders should be unique");
      }
    }
  }

  /**
   * NameMatcher extract a key based on a given name (e.g. header name or query parameter name).
   * The name must match one of the names listed in the "name" field. If the "required_match" field
   * is true, one of the specified names must be present for the keybuilder to match.
   */
  @Immutable
  static final class NameMatcher {

    private final String key;

    private final ImmutableList<String> names;

    private final boolean optional;

    NameMatcher(String key, List<String> names, @Nullable Boolean optional) {
      this.key = checkNotNull(key, "key");
      this.names = ImmutableList.copyOf(checkNotNull(names, "names"));
      this.optional = optional != null ? optional : true;
    }

    /** The name that will be used in the RLS key_map to refer to this value. */
    String getKey() {
      return key;
    }

    /** Returns ordered list of names; the first non-empty value will be used. */
    ImmutableList<String> names() {
      return names;
    }

    /**
     * Indicates if this extraction optional. A key builder will still match if no value is found.
     */
    boolean isOptional() {
      return optional;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      NameMatcher matcher = (NameMatcher) o;
      return optional == matcher.optional
          && java.util.Objects.equals(key, matcher.key)
          && java.util.Objects.equals(names, matcher.names);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(key, names, optional);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("names", names)
          .add("optional", optional)
          .toString();
    }
  }

  /** GrpcKeyBuilder is a configuration to construct headers consumed by route lookup service. */
  static final class GrpcKeyBuilder {

    private final ImmutableList<Name> names;

    private final ImmutableList<NameMatcher> headers;

    public GrpcKeyBuilder(List<Name> names, List<NameMatcher> headers) {
      checkState(names != null && !names.isEmpty(), "names cannot be empty");
      this.names = ImmutableList.copyOf(names);
      checkUniqueKey(checkNotNull(headers, "headers"));
      this.headers = ImmutableList.copyOf(headers);
    }

    private static void checkUniqueKey(List<NameMatcher> headers) {
      Set<String> names = new HashSet<>();
      for (NameMatcher header :  headers) {
        checkState(names.add(header.key), "key in headers must be unique");
      }
    }

    /**
     * Returns names. To match, one of the given Name fields must match; the service and method
     * fields are specified as fixed strings. The service name is required and includes the proto
     * package name. The method name may be omitted, in which case any method on the given service
     * is matched.
     */
    ImmutableList<Name> getNames() {
      return names;
    }

    /**
     * Returns a list of NameMatchers for header. Extract keys from all listed headers. For gRPC, it
     * is an error to specify "required_match" on the NameMatcher protos, and we ignore it if set.
     */
    ImmutableList<NameMatcher> getHeaders() {
      return headers;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      GrpcKeyBuilder that = (GrpcKeyBuilder) o;
      return Objects.equal(names, that.names) && Objects.equal(headers, that.headers);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(names, headers);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("names", names)
          .add("headers", headers)
          .toString();
    }

    /**
     * Name represents a method for a given service. To match, one of the given Name fields must
     * match; the service and method fields are specified as fixed strings. The service name is
     * required and includes the proto package name. The method name may be omitted, in which case
     * any method on the given service is matched.
     */
    static final class Name {

      private final String service;

      private final String method;

      Name(String service) {
        this(service, "*");
      }

      Name(String service, String method) {
        checkState(
            !checkNotNull(service, "service").isEmpty(),
            "service must not be empty or null");
        this.service = service;
        this.method = method;
      }

      String getService() {
        return service;
      }

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
}
