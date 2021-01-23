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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.ChannelCredentials;
import io.grpc.InsecureChannelCredentials;
import io.grpc.Internal;
import io.grpc.InternalLogId;
import io.grpc.TlsChannelCredentials;
import io.grpc.alts.GoogleDefaultChannelCredentials;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.GrpcUtil.GrpcBuildVersion;
import io.grpc.internal.JsonParser;
import io.grpc.internal.JsonUtil;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A {@link Bootstrapper} implementation that reads xDS configurations from local file system.
 */
@Internal
public class BootstrapperImpl implements Bootstrapper {

  private static final String BOOTSTRAP_PATH_SYS_ENV_VAR = "GRPC_XDS_BOOTSTRAP";
  @VisibleForTesting
  static String bootstrapPathFromEnvVar = System.getenv(BOOTSTRAP_PATH_SYS_ENV_VAR);
  private static final String BOOTSTRAP_PATH_SYS_PROPERTY = "io.grpc.xds.bootstrap";
  @VisibleForTesting
  static String bootstrapPathFromSysProp = System.getProperty(BOOTSTRAP_PATH_SYS_PROPERTY);
  private static final String BOOTSTRAP_CONFIG_SYS_ENV_VAR = "GRPC_XDS_BOOTSTRAP_CONFIG";
  @VisibleForTesting
  static String bootstrapConfigFromEnvVar = System.getenv(BOOTSTRAP_CONFIG_SYS_ENV_VAR);
  private static final String BOOTSTRAP_CONFIG_SYS_PROPERTY_VAR = "io.grpc.xds.bootstrapValue";
  @VisibleForTesting
  static String bootstrapConfigFromSysProp = System.getProperty(BOOTSTRAP_CONFIG_SYS_PROPERTY_VAR);
  private static final String XDS_V3_SERVER_FEATURE = "xds_v3";
  @VisibleForTesting
  static boolean enableV3Protocol = Boolean.parseBoolean(
      System.getenv("GRPC_XDS_EXPERIMENTAL_V3_SUPPORT"));
  @VisibleForTesting
  static final String CLIENT_FEATURE_DISABLE_OVERPROVISIONING =
      "envoy.lb.does_not_support_overprovisioning";

  private final XdsLogger logger;
  private FileReader reader = LocalFileReader.INSTANCE;

  public BootstrapperImpl() {
    logger = XdsLogger.withLogId(InternalLogId.allocate("bootstrapper", null));
  }

  /**
   * Reads and parses bootstrap config. Searches the config (or file of config) with the
   * following order:
   *
   * <ol>
   *   <li>A filesystem path defined by environment variable "GRPC_XDS_BOOTSTRAP"</li>
   *   <li>A filesystem path defined by Java System Property "io.grpc.xds.bootstrap"</li>
   *   <li>Environment variable value of "GRPC_XDS_BOOTSTRAP_CONFIG"</li>
   *   <li>Java System Property value of "io.grpc.xds.bootstrap_value"</li>
   * </ol>
   */
  @Override
  public BootstrapInfo bootstrap() throws XdsInitializationException {
    String filePath =
        bootstrapPathFromEnvVar != null ? bootstrapPathFromEnvVar : bootstrapPathFromSysProp;
    String rawBootstrap;
    if (filePath != null) {
      logger.log(XdsLogLevel.INFO, "Reading bootstrap file from {0}", filePath);
      try {
        rawBootstrap = reader.readFile(filePath);
      } catch (IOException e) {
        throw new XdsInitializationException("Fail to read bootstrap file", e);
      }
    } else {
      rawBootstrap = bootstrapConfigFromEnvVar != null
          ? bootstrapConfigFromEnvVar : bootstrapConfigFromSysProp;
    }
    if (rawBootstrap != null) {
      return parseConfig(rawBootstrap);
    }
    throw new XdsInitializationException(
        "Cannot find bootstrap configuration\n"
            + "Environment variables searched:\n"
            + "- " + BOOTSTRAP_PATH_SYS_ENV_VAR + "\n"
            + "- " + BOOTSTRAP_CONFIG_SYS_ENV_VAR + "\n\n"
            + "Java System Properties searched:\n"
            + "- " + BOOTSTRAP_PATH_SYS_PROPERTY + "\n"
            + "- " + BOOTSTRAP_CONFIG_SYS_PROPERTY_VAR + "\n\n");
  }

  @VisibleForTesting
  void setFileReader(FileReader reader) {
    this.reader = reader;
  }

  /**
   * Reads the content of the file with the given path in the file system.
   */
  interface FileReader {
    String readFile(String path) throws IOException;
  }

  private enum LocalFileReader implements FileReader {
    INSTANCE;

    @Override
    public String readFile(String path) throws IOException {
      return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
  }

  /** Parses a raw string into {@link BootstrapInfo}. */
  @SuppressWarnings("unchecked")
  private BootstrapInfo parseConfig(String rawData) throws XdsInitializationException {
    logger.log(XdsLogLevel.INFO, "Reading bootstrap information");
    Map<String, ?> rawBootstrap;
    try {
      rawBootstrap = (Map<String, ?>) JsonParser.parse(rawData);
    } catch (IOException e) {
      throw new XdsInitializationException("Failed to parse JSON", e);
    }
    logger.log(XdsLogLevel.DEBUG, "Bootstrap configuration:\n{0}", rawBootstrap);

    List<ServerInfo> servers = new ArrayList<>();
    List<?> rawServerConfigs = JsonUtil.getList(rawBootstrap, "xds_servers");
    if (rawServerConfigs == null) {
      throw new XdsInitializationException("Invalid bootstrap: 'xds_servers' does not exist.");
    }
    logger.log(XdsLogLevel.INFO, "Configured with {0} xDS servers", rawServerConfigs.size());
    // TODO(chengyuanzhang): require at least one server URI.
    List<Map<String, ?>> serverConfigList = JsonUtil.checkObjectList(rawServerConfigs);
    for (Map<String, ?> serverConfig : serverConfigList) {
      String serverUri = JsonUtil.getString(serverConfig, "server_uri");
      if (serverUri == null) {
        throw new XdsInitializationException("Invalid bootstrap: missing 'server_uri'");
      }
      logger.log(XdsLogLevel.INFO, "xDS server URI: {0}", serverUri);

      List<?> rawChannelCredsList = JsonUtil.getList(serverConfig, "channel_creds");
      if (rawChannelCredsList == null || rawChannelCredsList.isEmpty()) {
        throw new XdsInitializationException(
            "Invalid bootstrap: server " + serverUri + " 'channel_creds' required");
      }
      ChannelCredentials channelCredentials =
          parseChannelCredentials(JsonUtil.checkObjectList(rawChannelCredsList), serverUri);
      if (channelCredentials == null) {
        throw new XdsInitializationException(
            "Server " + serverUri + ": no supported channel credentials found");
      }

      boolean useProtocolV3 = false;
      List<String> serverFeatures = JsonUtil.getListOfStrings(serverConfig, "server_features");
      if (serverFeatures != null) {
        logger.log(XdsLogLevel.INFO, "Server features: {0}", serverFeatures);
        useProtocolV3 = enableV3Protocol
            && serverFeatures.contains(XDS_V3_SERVER_FEATURE);
      }
      servers.add(new ServerInfo(serverUri, channelCredentials, useProtocolV3));
    }

    Node.Builder nodeBuilder = Node.newBuilder();
    Map<String, ?> rawNode = JsonUtil.getObject(rawBootstrap, "node");
    if (rawNode != null) {
      String id = JsonUtil.getString(rawNode, "id");
      if (id != null) {
        logger.log(XdsLogLevel.INFO, "Node id: {0}", id);
        nodeBuilder.setId(id);
      }
      String cluster = JsonUtil.getString(rawNode, "cluster");
      if (cluster != null) {
        logger.log(XdsLogLevel.INFO, "Node cluster: {0}", cluster);
        nodeBuilder.setCluster(cluster);
      }
      Map<String, ?> metadata = JsonUtil.getObject(rawNode, "metadata");
      if (metadata != null) {
        nodeBuilder.setMetadata(metadata);
      }
      Map<String, ?> rawLocality = JsonUtil.getObject(rawNode, "locality");
      if (rawLocality != null) {
        String region = JsonUtil.getString(rawLocality, "region");
        String zone = JsonUtil.getString(rawLocality, "zone");
        String subZone = JsonUtil.getString(rawLocality, "sub_zone");
        if (region != null) {
          logger.log(XdsLogLevel.INFO, "Locality region: {0}", region);
        }
        if (rawLocality.containsKey("zone")) {
          logger.log(XdsLogLevel.INFO, "Locality zone: {0}", zone);
        }
        if (rawLocality.containsKey("sub_zone")) {
          logger.log(XdsLogLevel.INFO, "Locality sub_zone: {0}", subZone);
        }
        Locality locality = new Locality(region, zone, subZone);
        nodeBuilder.setLocality(locality);
      }
    }
    GrpcBuildVersion buildVersion = GrpcUtil.getGrpcBuildVersion();
    logger.log(XdsLogLevel.INFO, "Build version: {0}", buildVersion);
    nodeBuilder.setBuildVersion(buildVersion.toString());
    nodeBuilder.setUserAgentName(buildVersion.getUserAgent());
    nodeBuilder.setUserAgentVersion(buildVersion.getImplementationVersion());
    nodeBuilder.addClientFeatures(CLIENT_FEATURE_DISABLE_OVERPROVISIONING);

    Map<String, ?> certProvidersBlob = JsonUtil.getObject(rawBootstrap, "certificate_providers");
    Map<String, CertificateProviderInfo> certProviders = null;
    if (certProvidersBlob != null) {
      certProviders = new HashMap<>(certProvidersBlob.size());
      for (String name : certProvidersBlob.keySet()) {
        Map<String, ?> valueMap = JsonUtil.getObject(certProvidersBlob, name);
        String pluginName =
            checkForNull(JsonUtil.getString(valueMap, "plugin_name"), "plugin_name");
        Map<String, ?> config = checkForNull(JsonUtil.getObject(valueMap, "config"), "config");
        CertificateProviderInfo certificateProviderInfo =
            new CertificateProviderInfo(pluginName, config);
        certProviders.put(name, certificateProviderInfo);
      }
    }
    String grpcServerResourceId = JsonUtil.getString(rawBootstrap, "grpc_server_resource_name_id");
    return new BootstrapInfo(servers, nodeBuilder.build(), certProviders, grpcServerResourceId);
  }

  static <T> T checkForNull(T value, String fieldName) throws XdsInitializationException {
    if (value == null) {
      throw new XdsInitializationException(
          "Invalid bootstrap: '" + fieldName + "' does not exist.");
    }
    return value;
  }

  @Nullable
  private static ChannelCredentials parseChannelCredentials(List<Map<String, ?>> jsonList,
      String serverUri) throws XdsInitializationException {
    for (Map<String, ?> channelCreds : jsonList) {
      String type = JsonUtil.getString(channelCreds, "type");
      if (type == null) {
        throw new XdsInitializationException(
            "Invalid bootstrap: server " + serverUri + " with 'channel_creds' type unspecified");
      }
      switch (type) {
        case "google_default":
          return GoogleDefaultChannelCredentials.create();
        case "insecure":
          return InsecureChannelCredentials.create();
        case "tls":
          return TlsChannelCredentials.create();
        default:
      }
    }
    return null;
  }
}
