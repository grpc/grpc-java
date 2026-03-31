/*
 * Copyright 2024 The gRPC Authors
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import io.grpc.CallCredentials;
import io.grpc.ChannelCredentials;
import io.grpc.internal.JsonUtil;
import io.grpc.xds.client.BootstrapperImpl;
import io.grpc.xds.client.XdsInitializationException;
import io.grpc.xds.client.XdsLogger;
import io.grpc.xds.internal.grpcservice.ChannelCredsConfig;
import io.grpc.xds.internal.grpcservice.ConfiguredChannelCredentials;
import io.grpc.xds.internal.grpcservice.GrpcServiceXdsContext;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

class GrpcBootstrapperImpl extends BootstrapperImpl {
  private static final String BOOTSTRAP_PATH_SYS_ENV_VAR = "GRPC_XDS_BOOTSTRAP";
  private static final String BOOTSTRAP_PATH_SYS_PROPERTY = "io.grpc.xds.bootstrap";
  private static final String BOOTSTRAP_CONFIG_SYS_ENV_VAR = "GRPC_XDS_BOOTSTRAP_CONFIG";
  private static final String BOOTSTRAP_CONFIG_SYS_PROPERTY = "io.grpc.xds.bootstrapConfig";
  @VisibleForTesting
  String bootstrapPathFromEnvVar = System.getenv(BOOTSTRAP_PATH_SYS_ENV_VAR);
  @VisibleForTesting
  String bootstrapPathFromSysProp = System.getProperty(BOOTSTRAP_PATH_SYS_PROPERTY);
  @VisibleForTesting
  String bootstrapConfigFromEnvVar = System.getenv(BOOTSTRAP_CONFIG_SYS_ENV_VAR);
  @VisibleForTesting
  String bootstrapConfigFromSysProp = System.getProperty(BOOTSTRAP_CONFIG_SYS_PROPERTY);

  GrpcBootstrapperImpl() {
    super();
  }

  @Override
  public BootstrapInfo bootstrap(Map<String, ?> rawData) throws XdsInitializationException {
    BootstrapInfo info = super.bootstrap(rawData);
    if (info.servers().isEmpty()) {
      throw new XdsInitializationException("Invalid bootstrap: 'xds_servers' is empty");
    }
    return info;
  }

  /**
   * Gets the bootstrap config as JSON. Searches the config (or file of config) with the
   * following order:
   *
   * <ol>
   *   <li>A filesystem path defined by environment variable "GRPC_XDS_BOOTSTRAP"</li>
   *   <li>A filesystem path defined by Java System Property "io.grpc.xds.bootstrap"</li>
   *   <li>Environment variable value of "GRPC_XDS_BOOTSTRAP_CONFIG"</li>
   *   <li>Java System Property value of "io.grpc.xds.bootstrapConfig"</li>
   * </ol>
   */
  @Override
  protected String getJsonContent() throws XdsInitializationException, IOException {
    String jsonContent;
    String filePath =
        bootstrapPathFromEnvVar != null ? bootstrapPathFromEnvVar : bootstrapPathFromSysProp;
    if (filePath != null) {
      logger.log(XdsLogger.XdsLogLevel.INFO, "Reading bootstrap file from {0}", filePath);
      jsonContent = reader.readFile(filePath);
      logger.log(XdsLogger.XdsLogLevel.INFO, "Reading bootstrap from " + filePath);
    } else {
      jsonContent = bootstrapConfigFromEnvVar != null
          ? bootstrapConfigFromEnvVar : bootstrapConfigFromSysProp;
    }
    if (jsonContent == null) {
      throw new XdsInitializationException(
          "Cannot find bootstrap configuration\n"
              + "Environment variables searched:\n"
              + "- " + BOOTSTRAP_PATH_SYS_ENV_VAR + "\n"
              + "- " + BOOTSTRAP_CONFIG_SYS_ENV_VAR + "\n\n"
              + "Java System Properties searched:\n"
              + "- " + BOOTSTRAP_PATH_SYS_PROPERTY + "\n"
              + "- " + BOOTSTRAP_CONFIG_SYS_PROPERTY + "\n\n");
    }

    return jsonContent;
  }

  @Override
  protected Object getImplSpecificConfig(Map<String, ?> serverConfig, String serverUri)
      throws XdsInitializationException {
    ConfiguredChannelCredentials configuredChannel = getChannelCredentials(serverConfig, serverUri);
    return configuredChannel != null ? configuredChannel.channelCredentials() : null;
  }

  @GuardedBy("GrpcBootstrapperImpl.class")
  private static Map<String, ?> defaultBootstrapOverride;
  @GuardedBy("GrpcBootstrapperImpl.class")
  private static BootstrapInfo defaultBootstrap;

  static synchronized void setDefaultBootstrapOverride(Map<String, ?> rawBootstrap) {
    defaultBootstrapOverride = rawBootstrap;
  }

  static synchronized BootstrapInfo defaultBootstrap() throws XdsInitializationException {
    if (defaultBootstrap == null) {
      if (defaultBootstrapOverride == null) {
        defaultBootstrap = new GrpcBootstrapperImpl().bootstrap();
      } else {
        defaultBootstrap = new GrpcBootstrapperImpl().bootstrap(defaultBootstrapOverride);
      }
    }
    return defaultBootstrap;
  }

  private static ConfiguredChannelCredentials getChannelCredentials(Map<String, ?> serverConfig,
                                                                  String serverUri)
      throws XdsInitializationException {
    List<?> rawChannelCredsList = JsonUtil.getList(serverConfig, "channel_creds");
    if (rawChannelCredsList == null || rawChannelCredsList.isEmpty()) {
      throw new XdsInitializationException(
          "Invalid bootstrap: server " + serverUri + " 'channel_creds' required");
    }
    ConfiguredChannelCredentials credentials =
        parseChannelCredentials(JsonUtil.checkObjectList(rawChannelCredsList), serverUri);
    if (credentials == null) {
      throw new XdsInitializationException(
          "Server " + serverUri + ": no supported channel credentials found");
    }
    return credentials;
  }

  @Nullable
  private static ConfiguredChannelCredentials parseChannelCredentials(List<Map<String, ?>> jsonList,
          String serverUri)
      throws XdsInitializationException {
    for (Map<String, ?> channelCreds : jsonList) {
      String type = JsonUtil.getString(channelCreds, "type");
      if (type == null) {
        throw new XdsInitializationException(
            "Invalid bootstrap: server " + serverUri + " with 'channel_creds' type unspecified");
      }
      XdsCredentialsProvider provider =  XdsCredentialsRegistry.getDefaultRegistry()
          .getProvider(type);
      if (provider != null) {
        Map<String, ?> config = JsonUtil.getObject(channelCreds, "config");
        if (config == null) {
          config = ImmutableMap.of();
        }

        ChannelCredentials creds = provider.newChannelCredentials(config);
        if (creds == null) {
          continue;
        }
        return ConfiguredChannelCredentials.create(creds, new JsonChannelCredsConfig(type, config));
      }
    }
    return null;
  }

  @Override
  protected Optional<Object> parseAllowedGrpcServices(
      Map<String, ?> rawAllowedGrpcServices)
      throws XdsInitializationException {
    ImmutableMap.Builder<String, GrpcServiceXdsContext.AllowedGrpcService> builder =
        ImmutableMap.builder();
    for (String targetUri : rawAllowedGrpcServices.keySet()) {
      Map<String, ?> serviceConfig = JsonUtil.getObject(rawAllowedGrpcServices, targetUri);
      if (serviceConfig == null) {
        throw new XdsInitializationException(
            "Invalid allowed_grpc_services config for " + targetUri);
      }
      ConfiguredChannelCredentials configuredChannel =
          getChannelCredentials(serviceConfig, targetUri);

      Optional<CallCredentials> callCredentials = Optional.empty();
      List<?> rawCallCredsList = JsonUtil.getList(serviceConfig, "call_creds");
      if (rawCallCredsList != null && !rawCallCredsList.isEmpty()) {
        callCredentials =
            parseCallCredentials(JsonUtil.checkObjectList(rawCallCredsList), targetUri);
      }

      GrpcServiceXdsContext.AllowedGrpcService.Builder b = GrpcServiceXdsContext.AllowedGrpcService
          .builder().configuredChannelCredentials(configuredChannel);
      callCredentials.ifPresent(b::callCredentials);
      builder.put(targetUri, b.build());
    }
    ImmutableMap<String, GrpcServiceXdsContext.AllowedGrpcService> parsed = builder.buildOrThrow();
    return parsed.isEmpty() ? Optional.empty() : Optional.of(parsed);
  }

  @SuppressWarnings("unused")
  private static Optional<CallCredentials> parseCallCredentials(List<Map<String, ?>> jsonList,
                                                          String targetUri)
      throws XdsInitializationException {
    // TODO(sauravzg): Currently no xDS call credentials providers are implemented (no
    // XdsCallCredentialsRegistry).
    // As per A102/A97, we should just ignore unsupported call credentials types
    // without throwing an exception.
    return Optional.empty();
  }

  private static final class JsonChannelCredsConfig implements ChannelCredsConfig {
    private final String type;
    private final Map<String, ?> config;

    JsonChannelCredsConfig(String type, Map<String, ?> config) {
      this.type = type;
      this.config = config;
    }

    @Override
    public String type() {
      return type;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      JsonChannelCredsConfig that = (JsonChannelCredsConfig) o;
      return java.util.Objects.equals(type, that.type)
          && java.util.Objects.equals(config, that.config);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(type, config);
    }
  }

}

