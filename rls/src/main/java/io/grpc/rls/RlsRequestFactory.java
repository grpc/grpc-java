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

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CheckReturnValue;
import io.grpc.Metadata;
import io.grpc.rls.RlsProtoData.ExtraKeys;
import io.grpc.rls.RlsProtoData.GrpcKeyBuilder;
import io.grpc.rls.RlsProtoData.GrpcKeyBuilder.Name;
import io.grpc.rls.RlsProtoData.NameMatcher;
import io.grpc.rls.RlsProtoData.RouteLookupConfig;
import io.grpc.rls.RlsProtoData.RouteLookupRequest;
import io.grpc.rls.RlsProtoData.RouteLookupRequestKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A RlsRequestFactory creates {@link RouteLookupRequestKey} using key builder map from {@link
 * RouteLookupConfig}.
 */
final class RlsRequestFactory {

  private final String target;
  private final Map<String, GrpcKeyBuilder> keyBuilderTable;

  RlsRequestFactory(RouteLookupConfig rlsConfig, String target) {
    checkNotNull(rlsConfig, "rlsConfig");
    this.target = checkNotNull(target, "target");
    this.keyBuilderTable = createKeyBuilderTable(rlsConfig);
  }

  private static Map<String, GrpcKeyBuilder> createKeyBuilderTable(
      RouteLookupConfig config) {
    Map<String, GrpcKeyBuilder> table = new HashMap<>();
    for (GrpcKeyBuilder grpcKeyBuilder : config.grpcKeybuilders()) {
      for (Name name : grpcKeyBuilder.names()) {
        boolean noMethod = name.method() == null || name.method().isEmpty();
        String method = noMethod ? "*" : name.method();
        String path = "/" + name.service() + "/" + method;
        table.put(path, grpcKeyBuilder);
      }
    }
    return table;
  }

  /** Creates a {@link RouteLookupRequestKey} for the given request lookup metadata. */
  @CheckReturnValue
  RouteLookupRequestKey create(String service, String method, Metadata metadata) {
    checkNotNull(service, "service");
    checkNotNull(method, "method");
    String path = "/" + service + "/" + method;
    GrpcKeyBuilder grpcKeyBuilder = keyBuilderTable.get(path);
    if (grpcKeyBuilder == null) {
      // if no matching keyBuilder found, fall back to wildcard match (ServiceName/*)
      grpcKeyBuilder = keyBuilderTable.get("/" + service + "/*");
    }
    if (grpcKeyBuilder == null) {
      return RouteLookupRequestKey.create(ImmutableMap.of());
    }
    ImmutableMap.Builder<String, String> rlsRequestHeaders =
        createRequestHeaders(metadata, grpcKeyBuilder.headers());
    ExtraKeys extraKeys = grpcKeyBuilder.extraKeys();
    Map<String, String> constantKeys = grpcKeyBuilder.constantKeys();
    if (extraKeys.host() != null) {
      rlsRequestHeaders.put(extraKeys.host(), target);
    }
    if (extraKeys.service() != null) {
      rlsRequestHeaders.put(extraKeys.service(), service);
    }
    if (extraKeys.method() != null) {
      rlsRequestHeaders.put(extraKeys.method(), method);
    }
    rlsRequestHeaders.putAll(constantKeys);
    return RouteLookupRequestKey.create(rlsRequestHeaders.buildOrThrow());
  }

  /** Creates a {@link RouteLookupRequest} using the given request lookup key and reason. */
  @CheckReturnValue
  RouteLookupRequest create(RouteLookupRequestKey routeLookupRequestKey,
      RouteLookupRequest.Reason reason) {
    return RouteLookupRequest.create(routeLookupRequestKey.keyMap(), reason);
  }

  private ImmutableMap.Builder<String, String> createRequestHeaders(
      Metadata metadata, List<NameMatcher> keyBuilder) {
    ImmutableMap.Builder<String, String> rlsRequestHeaders = ImmutableMap.builder();
    for (NameMatcher nameMatcher : keyBuilder) {
      String value = null;
      for (String requestHeaderName : nameMatcher.names()) {
        value = metadata.get(Metadata.Key.of(requestHeaderName, Metadata.ASCII_STRING_MARSHALLER));
        if (value != null) {
          break;
        }
      }
      if (value != null) {
        rlsRequestHeaders.put(nameMatcher.key(), value);
      }
    }
    return rlsRequestHeaders;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("target", target)
        .add("keyBuilderTable", keyBuilderTable)
        .toString();
  }
}
