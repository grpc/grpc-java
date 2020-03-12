/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds.internal.sds;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.Files;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
// TODO(sanjaypujare): remove dependency on envoy data types.
import io.envoyproxy.envoy.api.v2.core.DataSource;
import io.envoyproxy.envoy.api.v2.core.GrpcService.GoogleGrpc.CallCredentials.MetadataCredentialsFromPlugin;
import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.Status;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;

/** Implements file based Google GRPC call cred needed by SDS server. */
final class FileBasedPluginCredential extends CallCredentials {

  public static final String PLUGIN_NAME = "envoy.grpc_credentials.file_based_metadata";
  public static final String HEADER_KEY = "header_key";
  public static final String HEADER_PREFIX = "header_prefix";
  public static final String SECRET_DATA = "secret_data";
  public static final String FILENAME = "filename";
  public static final String DEFAULT_HEADER_KEY = "authorization";

  @VisibleForTesting final String headerKey;
  @VisibleForTesting final String headerPrefix;
  @VisibleForTesting final DataSource secretData;

  FileBasedPluginCredential(MetadataCredentialsFromPlugin metadataCredentialsFromPlugin) {
    checkNotNull(metadataCredentialsFromPlugin, "metadataCredentialsFromPlugin");
    checkArgument(
            PLUGIN_NAME.equals(metadataCredentialsFromPlugin.getName()),
            "plugin name should be %s", PLUGIN_NAME);
    checkArgument(metadataCredentialsFromPlugin.hasConfig(),
            "typed_config not supported");

    Struct configStruct = metadataCredentialsFromPlugin.getConfig();

    if (configStruct.containsFields(HEADER_KEY)) {
      Value value = configStruct.getFieldsOrThrow(HEADER_KEY);
      headerKey = value.getStringValue();
    } else {
      headerKey = DEFAULT_HEADER_KEY;
    }
    if (configStruct.containsFields(HEADER_PREFIX)) {
      Value value = configStruct.getFieldsOrThrow(HEADER_PREFIX);
      headerPrefix = value.getStringValue();
    } else {
      headerPrefix = "";
    }
    Value value = configStruct.getFieldsOrThrow(SECRET_DATA);
    checkState(value.hasStructValue(), "expected struct value for %s", SECRET_DATA);
    secretData = buildDataSourceFromConfigStruct(value.getStructValue());
  }

  private static DataSource buildDataSourceFromConfigStruct(Struct secretValueStruct) {
    checkNotNull(secretValueStruct, "secretValueStruct");
    if (secretValueStruct.containsFields(FILENAME)) {
      Value value = secretValueStruct.getFieldsOrThrow(FILENAME);
      return DataSource.newBuilder().setFilename(value.getStringValue()).build();
    } else {
      throw new UnsupportedOperationException("only secret_data of filename type supported");
    }
  }

  @Override
  public void applyRequestMetadata(
      @Nullable RequestInfo requestInfo, Executor appExecutor, final MetadataApplier applier) {
    appExecutor.execute(
        new Runnable() {
          @Override
          public void run() {
            try {
              Metadata headers = new Metadata();
              String headerValue =
                  headerPrefix
                      + Files.asCharSource(
                              new File(secretData.getFilename()), StandardCharsets.UTF_8)
                          .read();

              if (headerKey.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                Metadata.Key<byte[]> metadataHeaderKey =
                    Metadata.Key.of(headerKey, Metadata.BINARY_BYTE_MARSHALLER);
                headers.put(metadataHeaderKey, headerValue.getBytes(StandardCharsets.UTF_8));
              } else {
                Metadata.Key<String> metadataHeaderKey =
                    Metadata.Key.of(headerKey, Metadata.ASCII_STRING_MARSHALLER);
                headers.put(metadataHeaderKey, headerValue);
              }
              applier.apply(headers);
            } catch (IOException e) {
              applier.fail(Status.fromThrowable(e));
            }
          }
        });
  }

  @Override
  public void thisUsesUnstableApi() {}
}
