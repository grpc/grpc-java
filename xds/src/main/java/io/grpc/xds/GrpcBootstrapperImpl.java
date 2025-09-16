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
import com.google.common.collect.ImmutableSet;
import io.grpc.internal.JsonUtil;
import io.grpc.xds.client.BootstrapperImpl;
import io.grpc.xds.client.XdsInitializationException;
import io.grpc.xds.client.XdsLogger;
import java.io.IOException;
import java.util.List;
import java.util.Map;
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
    return super.bootstrap(rawData);
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
  protected ImmutableMap<String, ?> getImplSpecificConfig(Map<String, ?> serverConfig,
                                                          String serverUri)
      throws XdsInitializationException {
    return getChannelCredentialsConfig(serverConfig, serverUri);
  }

  private static ImmutableMap<String, ?> getChannelCredentialsConfig(Map<String, ?> serverConfig,
                                                                     String serverUri)
      throws XdsInitializationException {
    List<?> rawChannelCredsList = JsonUtil.getList(serverConfig, "channel_creds");
    if (rawChannelCredsList == null || rawChannelCredsList.isEmpty()) {
      throw new XdsInitializationException(
          "Invalid bootstrap: server " + serverUri + " 'channel_creds' required");
    }
    ImmutableMap<String, ?> channelCredentialsConfig =
        parseChannelCredentials(JsonUtil.checkObjectList(rawChannelCredsList), serverUri);
    if (channelCredentialsConfig == null) {
      throw new XdsInitializationException(
          "Server " + serverUri + ": no supported channel credentials found");
    }
    return channelCredentialsConfig;
  }

  @Nullable
  private static ImmutableMap<String, ?> parseChannelCredentials(List<Map<String, ?>> jsonList,
                                                                 String serverUri)
      throws XdsInitializationException {
    for (Map<String, ?> channelCreds : jsonList) {
      String type = JsonUtil.getString(channelCreds, "type");
      if (type == null) {
        throw new XdsInitializationException(
            "Invalid bootstrap: server " + serverUri + " with 'channel_creds' type unspecified");
      }
      ImmutableSet<String> supportedNames =  XdsCredentialsRegistry.getDefaultRegistry()
          .getSupportedCredentialNames();
      if (supportedNames.contains(type)) {
        return ImmutableMap.copyOf(channelCreds);
      }
    }
    return null;
  }
}
