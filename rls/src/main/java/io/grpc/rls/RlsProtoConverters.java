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
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.base.Converter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.internal.JsonUtil;
import io.grpc.lookup.v1.RouteLookupRequest;
import io.grpc.lookup.v1.RouteLookupResponse;
import io.grpc.rls.RlsProtoData.ExtraKeys;
import io.grpc.rls.RlsProtoData.GrpcKeyBuilder;
import io.grpc.rls.RlsProtoData.GrpcKeyBuilder.Name;
import io.grpc.rls.RlsProtoData.NameMatcher;
import io.grpc.rls.RlsProtoData.RouteLookupConfig;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * RlsProtoConverters is a collection of {@link Converter} between RouteLookupService proto / json
 * messages to internal representation in {@link RlsProtoData}.
 */
final class RlsProtoConverters {

  private static final long MAX_AGE_NANOS = MINUTES.toNanos(5);
  private static final long MAX_CACHE_SIZE = 5 * 1024 * 1024; // 5MiB
  private static final long DEFAULT_LOOKUP_SERVICE_TIMEOUT = SECONDS.toNanos(10);
  private static final ImmutableList<String> EXTRA_KEY_NAMES =
      ImmutableList.of("host", "service", "method");

  /**
   * RouteLookupRequestConverter converts between {@link RouteLookupRequest} and {@link
   * RlsProtoData.RouteLookupRequest}.
   */
  static final class RouteLookupRequestConverter
      extends Converter<RouteLookupRequest, RlsProtoData.RouteLookupRequest> {

    @Override
    protected RlsProtoData.RouteLookupRequest doForward(RouteLookupRequest routeLookupRequest) {
      return RlsProtoData.RouteLookupRequest.create(
          ImmutableMap.copyOf(routeLookupRequest.getKeyMapMap()));
    }

    @Override
    protected RouteLookupRequest doBackward(RlsProtoData.RouteLookupRequest routeLookupRequest) {
      return
          RouteLookupRequest.newBuilder()
              .setTargetType("grpc")
              .putAllKeyMap(routeLookupRequest.keyMap())
              .build();
    }
  }

  /**
   * RouteLookupResponseConverter converts between {@link RouteLookupResponse} and {@link
   * RlsProtoData.RouteLookupResponse}.
   */
  static final class RouteLookupResponseConverter
      extends Converter<RouteLookupResponse, RlsProtoData.RouteLookupResponse> {

    @Override
    protected RlsProtoData.RouteLookupResponse doForward(RouteLookupResponse routeLookupResponse) {
      return
          RlsProtoData.RouteLookupResponse.create(
              ImmutableList.copyOf(routeLookupResponse.getTargetsList()),
              routeLookupResponse.getHeaderData());
    }

    @Override
    protected RouteLookupResponse doBackward(RlsProtoData.RouteLookupResponse routeLookupResponse) {
      return RouteLookupResponse.newBuilder()
          .addAllTargets(routeLookupResponse.targets())
          .setHeaderData(routeLookupResponse.getHeaderData())
          .build();
    }
  }

  /**
   * RouteLookupConfigConverter converts between json map to {@link RouteLookupConfig}.
   */
  static final class RouteLookupConfigConverter
      extends Converter<Map<String, ?>, RouteLookupConfig> {

    @Override
    protected RouteLookupConfig doForward(Map<String, ?> json) {
      ImmutableList<GrpcKeyBuilder> grpcKeybuilders =
          GrpcKeyBuilderConverter.covertAll(
              checkNotNull(JsonUtil.getListOfObjects(json, "grpcKeybuilders"), "grpcKeybuilders"));

      // Validate grpc_keybuilders
      checkArgument(!grpcKeybuilders.isEmpty(), "must have at least one GrpcKeyBuilder");
      Set<Name> names = new HashSet<>();
      for (GrpcKeyBuilder keyBuilder : grpcKeybuilders) {
        for (Name name : keyBuilder.names()) {
          checkArgument(names.add(name), "duplicate names in grpc_keybuilders: " + name);
        }

        Set<String> keys = new HashSet<>();
        for (NameMatcher header : keyBuilder.headers()) {
          checkKeys(keys, header.key(), "header");
        }
        for (String key : keyBuilder.constantKeys().keySet()) {
          checkKeys(keys, key, "constant");
        }
        String extraKeyStr = keyToString(keyBuilder.extraKeys());
        checkArgument(keys.add(extraKeyStr),
            "duplicate extra key in grpc_keybuilders: " + extraKeyStr);
      }

      // Validate lookup_service
      String lookupService = JsonUtil.getString(json, "lookupService");
      checkArgument(!Strings.isNullOrEmpty(lookupService), "lookupService must not be empty");
      try {
        URI unused = new URI(lookupService);
      } catch (URISyntaxException e) {
        throw new IllegalArgumentException(
            "The lookupService field is not valid URI: " + lookupService, e);
      }
      long timeout = orDefault(
          JsonUtil.getStringAsDuration(json, "lookupServiceTimeout"),
          DEFAULT_LOOKUP_SERVICE_TIMEOUT);
      checkArgument(timeout > 0, "lookupServiceTimeout should be positive");
      Long maxAge = JsonUtil.getStringAsDuration(json, "maxAge");
      Long staleAge = JsonUtil.getStringAsDuration(json, "staleAge");
      if (maxAge == null) {
        checkArgument(staleAge == null, "to specify staleAge, must have maxAge");
        maxAge = MAX_AGE_NANOS;
      }
      if (staleAge == null) {
        staleAge = MAX_AGE_NANOS;
      }
      maxAge = Math.min(maxAge, MAX_AGE_NANOS);
      staleAge = Math.min(staleAge, maxAge);
      long cacheSize = orDefault(JsonUtil.getNumberAsLong(json, "cacheSizeBytes"), MAX_CACHE_SIZE);
      checkArgument(cacheSize > 0, "cacheSize must be positive");
      cacheSize = Math.min(cacheSize, MAX_CACHE_SIZE);
      String defaultTarget = Strings.emptyToNull(JsonUtil.getString(json, "defaultTarget"));
      return RouteLookupConfig.builder()
          .grpcKeybuilders(grpcKeybuilders)
          .lookupService(lookupService)
          .lookupServiceTimeoutInNanos(timeout)
          .maxAgeInNanos(maxAge)
          .staleAgeInNanos(staleAge)
          .cacheSizeBytes(cacheSize)
          .defaultTarget(defaultTarget)
          .build();
    }

    private static String keyToString(ExtraKeys extraKeys) {
      return String.format("host: %s, service: %s, method: %s",
          extraKeys.host(), extraKeys.service(), extraKeys.method());
    }

    private static <T> T orDefault(@Nullable T value, T defaultValue) {
      if (value == null) {
        return checkNotNull(defaultValue, "defaultValue");
      }
      return value;
    }

    @Override
    protected Map<String, Object> doBackward(RouteLookupConfig routeLookupConfig) {
      throw new UnsupportedOperationException();
    }
  }

  private static void checkKeys(Set<String> keys, String key, String keyType) {
    checkArgument(key != null, "unset " + keyType + "  key");
    checkArgument(!key.isEmpty(), "Empty string for " + keyType + " key");
    checkArgument(keys.add(key), "duplicate " + keyType + " key in grpc_keybuilders: " + key);
  }

  private static final class GrpcKeyBuilderConverter {
    public static ImmutableList<GrpcKeyBuilder> covertAll(List<Map<String, ?>> keyBuilders) {
      ImmutableList.Builder<GrpcKeyBuilder> keyBuilderList = ImmutableList.builder();
      for (Map<String, ?> keyBuilder : keyBuilders) {
        keyBuilderList.add(convert(keyBuilder));
      }
      return keyBuilderList.build();
    }

    @SuppressWarnings("unchecked")
    static GrpcKeyBuilder convert(Map<String, ?> keyBuilder) {
      List<?> rawRawNames = JsonUtil.getList(keyBuilder, "names");
      checkArgument(
          rawRawNames != null && !rawRawNames.isEmpty(),
          "each keyBuilder must have at least one name");
      List<Map<String, ?>> rawNames = JsonUtil.checkObjectList(rawRawNames);
      ImmutableList.Builder<Name> namesBuilder = ImmutableList.builder();
      for (Map<String, ?> rawName : rawNames) {
        String serviceName = JsonUtil.getString(rawName, "service");
        checkArgument(!Strings.isNullOrEmpty(serviceName), "service must not be empty or null");
        namesBuilder.add(Name.create(serviceName, JsonUtil.getString(rawName, "method")));
      }
      List<?> rawRawHeaders = JsonUtil.getList(keyBuilder, "headers");
      List<Map<String, ?>> rawHeaders =
          rawRawHeaders == null
              ? new ArrayList<Map<String, ?>>() : JsonUtil.checkObjectList(rawRawHeaders);
      ImmutableList.Builder<NameMatcher> nameMatchersBuilder = ImmutableList.builder();
      for (Map<String, ?> rawHeader : rawHeaders) {
        Boolean requiredMatch = JsonUtil.getBoolean(rawHeader, "requiredMatch");
        checkArgument(
            requiredMatch == null || !requiredMatch,
            "requiredMatch shouldn't be specified for gRPC");
        NameMatcher matcher = NameMatcher.create(
            JsonUtil.getString(rawHeader, "key"),
            ImmutableList.copyOf((List<String>) rawHeader.get("names")));
        nameMatchersBuilder.add(matcher);
      }
      ExtraKeys extraKeys = ExtraKeys.DEFAULT;
      Map<String, String> rawExtraKeys =
          (Map<String, String>) JsonUtil.getObject(keyBuilder,  "extraKeys");
      if (rawExtraKeys != null) {
        extraKeys = ExtraKeys.create(
            rawExtraKeys.get("host"), rawExtraKeys.get("service"), rawExtraKeys.get("method"));
      }
      Map<String, String> constantKeys =
          (Map<String, String>) JsonUtil.getObject(keyBuilder,  "constantKeys");
      if (constantKeys == null) {
        constantKeys = ImmutableMap.of();
      }
      ImmutableList<NameMatcher> nameMatchers = nameMatchersBuilder.build();
      checkUniqueKey(nameMatchers, constantKeys.keySet());
      return GrpcKeyBuilder.create(
          namesBuilder.build(), nameMatchers, extraKeys, ImmutableMap.copyOf(constantKeys));
    }
  }

  private static void checkUniqueKey(List<NameMatcher> nameMatchers, Set<String> constantKeys) {
    Set<String> keys = new HashSet<>(constantKeys);
    keys.addAll(EXTRA_KEY_NAMES);
    for (NameMatcher nameMatcher :  nameMatchers) {
      keys.add(nameMatcher.key());
    }
    if (keys.size() != nameMatchers.size() + constantKeys.size() + EXTRA_KEY_NAMES.size()) {
      throw new IllegalArgumentException("keys in KeyBuilder must be unique");
    }
  }

  private RlsProtoConverters() {}
}
