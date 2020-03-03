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

import com.google.common.base.Converter;
import io.grpc.internal.JsonUtil;
import io.grpc.lookup.v1.RouteLookupRequest;
import io.grpc.lookup.v1.RouteLookupResponse;
import io.grpc.rls.internal.RlsProtoData.GrpcKeyBuilder;
import io.grpc.rls.internal.RlsProtoData.GrpcKeyBuilder.Name;
import io.grpc.rls.internal.RlsProtoData.NameMatcher;
import io.grpc.rls.internal.RlsProtoData.RequestProcessingStrategy;
import io.grpc.rls.internal.RlsProtoData.RouteLookupConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * RlsProtoConverters is a collection of {@link Converter} between RouteLookupService proto / json
 * messages to internal representation in {@link RlsProtoData}.
 */
public final class RlsProtoConverters {

  /**
   * RouteLookupRequestConverter converts between {@link RouteLookupRequest} and {@link
   * RlsProtoData.RouteLookupRequest}.
   */
  public static final class RouteLookupRequestConverter
      extends Converter<RouteLookupRequest, RlsProtoData.RouteLookupRequest> {

    @Override
    protected RlsProtoData.RouteLookupRequest doForward(RouteLookupRequest routeLookupRequest) {
      return
          new RlsProtoData.RouteLookupRequest(
              /* server= */ routeLookupRequest.getServer(),
              /* path= */ routeLookupRequest.getPath(),
              /* targetType= */ routeLookupRequest.getTargetType(),
              routeLookupRequest.getKeyMapMap());
    }

    @Override
    protected RouteLookupRequest doBackward(RlsProtoData.RouteLookupRequest routeLookupRequest) {
      return
          RouteLookupRequest.newBuilder()
              .setServer(routeLookupRequest.getServer())
              .setPath(routeLookupRequest.getPath())
              .setTargetType(routeLookupRequest.getTargetType())
              .putAllKeyMap(routeLookupRequest.getKeyMap())
              .build();
    }
  }

  /**
   * RouteLookupResponseConverter converts between {@link RouteLookupResponse} and {@link
   * RlsProtoData.RouteLookupResponse}.
   */
  public static final class RouteLookupResponseConverter
      extends Converter<RouteLookupResponse, RlsProtoData.RouteLookupResponse> {

    @Override
    protected RlsProtoData.RouteLookupResponse doForward(RouteLookupResponse routeLookupResponse) {
      return
          new RlsProtoData.RouteLookupResponse(
              routeLookupResponse.getTarget(),
              routeLookupResponse.getHeaderData());
    }

    @Override
    protected RouteLookupResponse doBackward(RlsProtoData.RouteLookupResponse routeLookupResponse) {
      return RouteLookupResponse.newBuilder()
          .setTarget(routeLookupResponse.getTarget())
          .setHeaderData(routeLookupResponse.getHeaderData())
          .build();
    }
  }

  /**
   * RouteLookupConfigConverter converts between json map to {@link RouteLookupConfig}.
   */
  public static final class RouteLookupConfigConverter
      extends Converter<Map<String, ?>, RouteLookupConfig> {

    @Override
    protected RouteLookupConfig doForward(Map<String, ?> json) {
      List<GrpcKeyBuilder> grpcKeyBuilders =
          GrpcKeyBuilderConverter
              .covertAll(JsonUtil.checkObjectList(JsonUtil.getList(json, "grpcKeyBuilders")));
      String lookupService = JsonUtil.getString(json, "lookupService");
      long timeout =
          TimeUnit.SECONDS.toMillis(JsonUtil.getNumberAsLong(json, "lookupServiceTimeout"));
      Long maxAge =
          convertTimeIfNotNull(
              TimeUnit.SECONDS, TimeUnit.MILLISECONDS, JsonUtil.getNumberAsLong(json, "maxAge"));
      Long staleAge =
          convertTimeIfNotNull(
              TimeUnit.SECONDS, TimeUnit.MILLISECONDS, JsonUtil.getNumberAsLong(json, "staleAge"));
      long cacheSize = JsonUtil.getNumberAsLong(json, "cacheSizeBytes");
      List<String> validTargets = JsonUtil.checkStringList(JsonUtil.getList(json, "validTargets"));
      String defaultTarget = JsonUtil.getString(json, "defaultTarget");
      RequestProcessingStrategy strategy =
          RequestProcessingStrategy
              .valueOf(JsonUtil.getString(json, "requestProcessingStrategy").toUpperCase());
      return new RouteLookupConfig(
          grpcKeyBuilders,
          lookupService,
          /* lookupServiceTimeoutInMillis= */ timeout,
          /* maxAgeInMillis= */ maxAge,
          /* staleAgeInMillis= */ staleAge,
          /* cacheSizeBytes= */ cacheSize,
          validTargets,
          defaultTarget,
          strategy);
    }

    private static Long convertTimeIfNotNull(TimeUnit from, TimeUnit to, Long value) {
      if (value == null) {
        return null;
      }
      return to.convert(value, from);
    }

    @Override
    protected Map<String, Object> doBackward(RouteLookupConfig routeLookupConfig) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class GrpcKeyBuilderConverter {
    public static List<GrpcKeyBuilder> covertAll(List<Map<String, ?>> keyBuilders) {
      List<GrpcKeyBuilder> keyBuilderList = new ArrayList<>();
      for (Map<String, ?> keyBuilder : keyBuilders) {
        keyBuilderList.add(convert(keyBuilder));
      }
      return keyBuilderList;
    }

    @SuppressWarnings("unchecked")
    public static GrpcKeyBuilder convert(Map<String, ?> keyBuilder) {
      List<Map<String, ?>> rawNames =
          JsonUtil.checkObjectList(JsonUtil.getList(keyBuilder, "names"));
      List<Name> names = new ArrayList<>();
      for (Map<String, ?> rawName : rawNames) {
        names.add(
            new Name(
                JsonUtil.getString(rawName, "service"), JsonUtil.getString(rawName, "method")));
      }
      List<Map<String, ?>> rawHeaders =
          JsonUtil.checkObjectList(JsonUtil.getList(keyBuilder, "headers"));
      List<NameMatcher> nameMatchers = new ArrayList<>();
      for (Map<String, ?> rawHeader : rawHeaders) {
        NameMatcher matcher =
            new NameMatcher(
                JsonUtil.getString(rawHeader, "key"),
                (List<String>) rawHeader.get("names"),
                (Boolean) rawHeader.get("optional"));
        checkArgument(
            matcher.isOptional(), "NameMatcher for GrpcKeyBuilders shouldn't be required");
        nameMatchers.add(matcher);
      }
      return new GrpcKeyBuilder(names, nameMatchers);
    }
  }
}
