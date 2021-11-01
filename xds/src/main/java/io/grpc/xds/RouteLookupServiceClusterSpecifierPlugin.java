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

import com.google.auto.value.AutoValue;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.Durations;
import io.grpc.lookup.v1.GrpcKeyBuilder;
import io.grpc.lookup.v1.GrpcKeyBuilder.ExtraKeys;
import io.grpc.lookup.v1.GrpcKeyBuilder.Name;
import io.grpc.lookup.v1.NameMatcher;
import io.grpc.lookup.v1.RouteLookupConfig;
import io.grpc.rls.RlsProtoData;
import java.util.ArrayList;
import java.util.List;

/** The ClusterSpecifierPlugin for RouteLookup policy. */
final class RouteLookupServiceClusterSpecifierPlugin implements ClusterSpecifierPlugin {

  static final RouteLookupServiceClusterSpecifierPlugin INSTANCE =
      new RouteLookupServiceClusterSpecifierPlugin();

  private static final String TYPE_URL =
      "type.googleapis.com/grpc.lookup.v1.RouteLookupConfig";

  private RouteLookupServiceClusterSpecifierPlugin() {}

  @Override
  public String[] typeUrls() {
    return new String[] {
        TYPE_URL,
    };
  }

  @Override
  public ConfigOrError<RlsPluginConfig> parsePlugin(Message rawProtoMessage) {
    if (!(rawProtoMessage instanceof Any)) {
      return ConfigOrError.fromError("Invalid config type: " + rawProtoMessage.getClass());
    }

    Any anyMessage = (Any) rawProtoMessage;
    RouteLookupConfig configProto;
    try {
      configProto = anyMessage.unpack(RouteLookupConfig.class);
    } catch (InvalidProtocolBufferException e) {
      return ConfigOrError.fromError("Invalid proto: " + e);
    }
    try {
      List<GrpcKeyBuilder> keyBuildersProto = configProto.getGrpcKeybuildersList();
      List<RlsProtoData.GrpcKeyBuilder> keyBuilders =
          new ArrayList<>(keyBuildersProto.size());
      for (GrpcKeyBuilder keyBuilderProto : keyBuildersProto) {
        List<Name> namesProto = keyBuilderProto.getNamesList();
        List<RlsProtoData.GrpcKeyBuilder.Name> names = new ArrayList<>(namesProto.size());
        for (Name nameProto : namesProto) {
          if (nameProto.getMethod().isEmpty()) {
            names.add(new RlsProtoData.GrpcKeyBuilder.Name(nameProto.getService()));
          } else {
            names.add(
                new RlsProtoData.GrpcKeyBuilder.Name(
                    nameProto.getService(), nameProto.getMethod()));
          }
        }

        List<NameMatcher> headersProto = keyBuilderProto.getHeadersList();
        List<RlsProtoData.NameMatcher> headers = new ArrayList<>(headersProto.size());
        for (NameMatcher headerProto : headersProto) {
          headers.add(
              new RlsProtoData.NameMatcher(
                  headerProto.getKey(), headerProto.getNamesList(),
                  headerProto.getRequiredMatch()));
        }

        String host = null;
        String service = null;
        String method = null;
        if (keyBuilderProto.hasExtraKeys()) {
          ExtraKeys extraKeysProto = keyBuilderProto.getExtraKeys();
          host = extraKeysProto.getHost();
          service = extraKeysProto.getService();
          method = extraKeysProto.getMethod();
        }
        RlsProtoData.ExtraKeys extraKeys =
            RlsProtoData.ExtraKeys.create(host, service, method);

        RlsProtoData.GrpcKeyBuilder keyBuilder =
            new RlsProtoData.GrpcKeyBuilder(
                names, headers, extraKeys, keyBuilderProto.getConstantKeysMap());
        keyBuilders.add(keyBuilder);
      }
      RlsProtoData.RouteLookupConfig config = new RlsProtoData.RouteLookupConfig(
          keyBuilders,
          configProto.getLookupService(),
          Durations.toMillis(configProto.getLookupServiceTimeout()),
          configProto.hasMaxAge() ? Durations.toMillis(configProto.getMaxAge()) : null,
          configProto.hasStaleAge() ? Durations.toMillis(configProto.getStaleAge()) : null,
          configProto.getCacheSizeBytes(),
          configProto.getValidTargetsList(),
          configProto.getDefaultTarget());
      return ConfigOrError.fromConfig(RlsPluginConfig.create(config));
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(
          "Error parsing RouteLookupConfig: \n" + configProto + "\n reason: " + e);
    }
  }

  @AutoValue
  abstract static class RlsPluginConfig implements PluginConfig {

    abstract RlsProtoData.RouteLookupConfig config();

    static RlsPluginConfig create(RlsProtoData.RouteLookupConfig config) {
      return new AutoValue_RouteLookupServiceClusterSpecifierPlugin_RlsPluginConfig(config);
    }

    @Override
    public String typeUrl() {
      return TYPE_URL;
    }
  }
}
