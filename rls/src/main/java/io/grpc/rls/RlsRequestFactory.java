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
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.rls.RlsProtoData.GrpcKeyBuilder;
import io.grpc.rls.RlsProtoData.GrpcKeyBuilder.Name;
import io.grpc.rls.RlsProtoData.NameMatcher;
import io.grpc.rls.RlsProtoData.RouteLookupConfig;
import io.grpc.rls.RlsProtoData.RouteLookupRequest;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.CheckReturnValue;

/**
 * A RlsRequestFactory creates {@link RouteLookupRequest} using key builder map from {@link
 * RouteLookupConfig}.
 */
final class RlsRequestFactory {

  private final String target;
  /**
   * schema: Path(/serviceName/methodName or /serviceName/*), rls request headerName, header fields.
   */
  private final Table<String, String, NameMatcher> keyBuilderTable;

  RlsRequestFactory(RouteLookupConfig rlsConfig) {
    checkNotNull(rlsConfig, "rlsConfig");
    this.target = rlsConfig.getLookupService();
    this.keyBuilderTable = createKeyBuilderTable(rlsConfig);
  }

  private static Table<String, String, NameMatcher> createKeyBuilderTable(
      RouteLookupConfig config) {
    Table<String, String, NameMatcher> table = HashBasedTable.create();
    for (GrpcKeyBuilder grpcKeyBuilder : config.getGrpcKeyBuilders()) {
      for (NameMatcher nameMatcher : grpcKeyBuilder.getHeaders()) {
        for (Name name : grpcKeyBuilder.getNames()) {
          String method =
              name.getMethod() == null || name.getMethod().isEmpty()
                  ? "*" : name.getMethod();
          String path = "/" + name.getService() + "/" + method;
          table.put(path, nameMatcher.getKey(), nameMatcher);
        }
      }
    }
    return table;
  }

  /** Creates a {@link RouteLookupRequest} for given request's metadata. */
  @CheckReturnValue
  RouteLookupRequest create(String service, String method, Metadata metadata) {
    checkNotNull(service, "service");
    checkNotNull(method, "method");
    String path = "/" + service + "/" + method;
    Map<String, NameMatcher> keyBuilder = keyBuilderTable.row(path);
    // if no matching keyBuilder found, fall back to wildcard match (ServiceName/*)
    if (keyBuilder.isEmpty()) {
      keyBuilder = keyBuilderTable.row("/" + service + "/*");
    }
    Map<String, String> rlsRequestHeaders = createRequestHeaders(metadata, keyBuilder);
    return new RouteLookupRequest(target, path, "grpc", rlsRequestHeaders);
  }

  private Map<String, String> createRequestHeaders(
      Metadata metadata, Map<String, NameMatcher> keyBuilder) {
    Map<String, String> rlsRequestHeaders = new HashMap<>();
    for (Map.Entry<String, NameMatcher> entry : keyBuilder.entrySet()) {
      NameMatcher nameMatcher = entry.getValue();
      String value = null;
      for (String requestHeaderName : nameMatcher.names()) {
        value = metadata.get(Metadata.Key.of(requestHeaderName, Metadata.ASCII_STRING_MARSHALLER));
        if (value != null) {
          break;
        }
      }
      if (value != null) {
        rlsRequestHeaders.put(entry.getKey(), value);
      } else if (!nameMatcher.isOptional()) {
        throw new StatusRuntimeException(
            Status.INVALID_ARGUMENT.withDescription(
                String.format("Missing mandatory metadata(%s) not found", entry.getKey())));
      }
    }
    return rlsRequestHeaders;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("lookupService", target)
        .add("keyBuilderTable", keyBuilderTable)
        .toString();
  }
}
