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

package io.grpc.rls.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.rls.internal.RlsProtoData.GrpcKeyBuilder.Name;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/** RlsProtoData is a collection of internal representation of RouteLookupService proto messages. */
public final class RlsProtoData {

  /** A request object sent to route lookup service. */
  @Immutable
  public static final class RouteLookupRequest {

    private final String server;

    private final String path;

    private final String targetType;

    private final ImmutableMap<String, String> keyMap;

    /** Constructor for RouteLookupRequest. */
    public RouteLookupRequest(
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
    public String getServer() {
      return server;
    }

    /**
     * Returns a full path of the request, {@literal i.e.} "/service/method". Only set for gRPC
     * requests; HTTP requests must use key_map explicitly.
     */
    public String getPath() {
      return path;
    }

    /**
     * Returns the target type allows the client to specify what kind of target format it would like
     * from RLS to allow it to find the regional server, {@literal e.g.} "grpc".
     */
    public String getTargetType() {
      return targetType;
    }

    /** Returns a map of key values extracted via key builders for the gRPC or HTTP request. */
    public ImmutableMap<String, String> getKeyMap() {
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
  public static final class RouteLookupResponse {

    private final String target;

    private final String headerData;

    public RouteLookupResponse(String target, String headerData) {
      this.target = checkNotNull(target, "target");
      this.headerData = checkNotNull(headerData, "headerData");
    }

    /**
     * Returns target. A target is an actual addressable entity to use for routing decision, using
     * syntax requested by the request target_type.
     */
    public String getTarget() {
      return target;
    }

    /**
     * Returns optional header data to pass along to AFE in the X-Google-RLS-Data header. Cached
     * with "target" and sent with all requests that match the request key. Allows the RLS to pass
     * its work product to the eventual target.
     */
    public String getHeaderData() {
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
      return java.util.Objects.equals(target, that.target)
          && java.util.Objects.equals(headerData, that.headerData);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(target, headerData);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("target", target)
          .add("headerData", headerData)
          .toString();
    }
  }

  /** A config object for gRPC RouteLookupService. */
  @Immutable
  public static final class RouteLookupConfig {

    private static final long MAX_AGE_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private final ImmutableList<GrpcKeyBuilder> grpcKeyBuilders;

    private final String lookupService;

    private final long lookupServiceTimeoutInMillis;

    private final long maxAgeInMillis;

    private final long staleAgeInMillis;

    private final long cacheSizeBytes;

    private final ImmutableList<String> validTargets;

    private final String defaultTarget;

    private final RequestProcessingStrategy requestProcessingStrategy;

    /** Constructs RouteLookupConfig. */
    public RouteLookupConfig(
        List<GrpcKeyBuilder> grpcKeyBuilders,
        String lookupService,
        long lookupServiceTimeoutInMillis,
        @Nullable Long maxAgeInMillis,
        @Nullable Long staleAgeInMillis,
        long cacheSizeBytes,
        List<String> validTargets,
        String defaultTarget,
        RequestProcessingStrategy requestProcessingStrategy) {
      checkState(
          !checkNotNull(grpcKeyBuilders, "grpcKeyBuilders").isEmpty(),
          "must have at least one GrpcKeyBuilder");
      checkUniqueName(grpcKeyBuilders);
      this.grpcKeyBuilders = ImmutableList.copyOf(grpcKeyBuilders);
      // TODO(creamsoup) also check if it is URI
      checkState(
          lookupService != null && !lookupService.isEmpty(), "lookupService must not be empty");
      this.lookupService = lookupService;
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
      this.defaultTarget = checkNotNull(defaultTarget, "defaultTarget");
      this.requestProcessingStrategy = requestProcessingStrategy;
      checkNotNull(requestProcessingStrategy, "requestProcessingStrategy");
      checkState(
          !((requestProcessingStrategy == RequestProcessingStrategy.SYNC_LOOKUP_CLIENT_SEES_ERROR
              || requestProcessingStrategy
              == RequestProcessingStrategy.ASYNC_LOOKUP_DEFAULT_TARGET_ON_MISS)
              && defaultTarget.isEmpty()),
          "defaultTarget cannot be empty if strategy is %s",
          requestProcessingStrategy);
    }

    /**
     * Returns unordered specifications for constructing keys for gRPC requests. All GrpcKeyBuilders
     * on this list must have unique "name" fields so that the client is free to prebuild a hash map
     * keyed by name. If no GrpcKeyBuilder matches, an empty key_map will be sent to the lookup
     * service; it should likely reply with a global default route and raise an alert.
     */
    public ImmutableList<GrpcKeyBuilder> getGrpcKeyBuilders() {
      return grpcKeyBuilders;
    }

    /**
     * Returns the name of the lookup service as a gRPC URI. Typically, this will be a subdomain of
     * the target, such as "lookup.datastore.googleapis.com".
     */
    public String getLookupService() {
      return lookupService;
    }

    /** Returns the timeout value for lookup service requests. */
    public long getLookupServiceTimeoutInMillis() {
      return lookupServiceTimeoutInMillis;
    }


    /** Returns the maximum age the result will be cached. */
    public long getMaxAgeInMillis() {
      return maxAgeInMillis;
    }

    /**
     * Returns the time when an entry will be in a staled status. When cache is accessed whgen the
     * entry is in staled status, it will
     */
    public long getStaleAgeInMillis() {
      return staleAgeInMillis;
    }

    /**
     * Returns a rough indicator of amount of memory to use for the client cache. Some of the data
     * structure overhead is not accounted for, so actual memory consumed will be somewhat greater
     * than this value.  If this field is omitted or set to zero, a client default will be used.
     * The value may be capped to a lower amount based on client configuration.
     */
    public long getCacheSizeBytes() {
      return cacheSizeBytes;
    }

    /**
     * Returns the list of all the possible targets that can be returned by the lookup service.  If
     * a target not on this list is returned, it will be treated the same as an RPC error from the
     * RLS.
     */
    public ImmutableList<String> getValidTargets() {
      return validTargets;
    }

    /**
     * Returns the default target to use. It will be used for request processing strategy
     * {@link RequestProcessingStrategy#SYNC_LOOKUP_DEFAULT_TARGET_ON_ERROR} if RLS
     * returns an error, or strategy {@link
     * RequestProcessingStrategy#ASYNC_LOOKUP_DEFAULT_TARGET_ON_MISS} if RLS returns an error or
     * there is a cache miss in the client.  It will also be used if there are no healthy backends
     * for an RLS target. Note that requests can be routed only to a subdomain of the original
     * target, {@literal e.g.} "us_east_1.cloudbigtable.googleapis.com".
     */
    public String getDefaultTarget() {
      return defaultTarget;
    }

    /** Returns {@link RequestProcessingStrategy} to process RLS response. */
    public RequestProcessingStrategy getRequestProcessingStrategy() {
      return requestProcessingStrategy;
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
          && Objects.equal(defaultTarget, that.defaultTarget)
          && requestProcessingStrategy == that.requestProcessingStrategy;
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
          defaultTarget,
          requestProcessingStrategy);
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
          .add("requestProcessingStrategy", requestProcessingStrategy)
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

  /** RequestProcessingStrategy specifies how to process a request when not already in the cache. */
  enum RequestProcessingStrategy {
    /**
     * Query the RLS and process the request using target returned by the lookup. The target will
     * then be cached and used for processing subsequent requests for the same key. Any errors
     * during lookup service processing will fall back to default target for request processing.
     */
    SYNC_LOOKUP_DEFAULT_TARGET_ON_ERROR,

    /**
     * Query the RLS and process the request using target returned by the lookup. The target will
     * then be cached and used for processing subsequent requests for the same key. Any errors
     * during lookup service processing will return an error back to the client.  Services with
     * strict regional routing requirements should use this strategy.
     */
    SYNC_LOOKUP_CLIENT_SEES_ERROR,

    /**
     * Query the RLS asynchronously but respond with the default target.  The target in the lookup
     * response will then be cached and used for subsequent requests.  Services with strict latency
     * requirements (but not strict regional routing requirements) should use this strategy.
     */
    ASYNC_LOOKUP_DEFAULT_TARGET_ON_MISS;
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
    public String getKey() {
      return key;
    }

    /** Returns ordered list of names; the first non-empty value will be used. */
    public ImmutableList<String> names() {
      return names;
    }

    /**
     * Indicates if this extraction optional. A key builder will still match if no value is found.
     */
    public boolean isOptional() {
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
    public ImmutableList<Name> getNames() {
      return names;
    }

    /**
     * Returns a list of NameMatchers for header. Extract keys from all listed headers. For gRPC, it
     * is an error to specify "required_match" on the NameMatcher protos, and we ignore it if set.
     */
    public ImmutableList<NameMatcher> getHeaders() {
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

      public Name(String service) {
        this(service, "*");
      }

      public Name(String service, String method) {
        checkState(
            !checkNotNull(service, "service").isEmpty(),
            "service must not be empty or null");
        this.service = service;
        this.method = method;
      }

      public String getService() {
        return service;
      }

      public String getMethod() {
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
