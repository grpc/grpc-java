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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Internal;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * Loads configuration information to bootstrap gRPC's integration of xDS protocol.
 */
@Internal
public abstract class Bootstrapper {

  private static final String LOG_PREFIX = "xds-bootstrap";
  private static final String BOOTSTRAP_PATH_SYS_ENV_VAR = "GRPC_XDS_BOOTSTRAP";
  @VisibleForTesting
  static final String CLIENT_FEATURE_DISABLE_OVERPROVISIONING =
      "envoy.lb.does_not_support_overprovisioning";

  private static final Bootstrapper DEFAULT_INSTANCE = new Bootstrapper() {
    @Override
    public BootstrapInfo readBootstrap() throws XdsInitializationException {
      String filePath = System.getenv(BOOTSTRAP_PATH_SYS_ENV_VAR);
      if (filePath == null) {
        throw new XdsInitializationException(
            "Environment variable " + BOOTSTRAP_PATH_SYS_ENV_VAR + " not defined.");
      }
      XdsLogger
          .withPrefix(LOG_PREFIX)
          .log(XdsLogLevel.INFO, BOOTSTRAP_PATH_SYS_ENV_VAR + "={0}", filePath);
      String fileContent;
      try {
        fileContent = new String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new XdsInitializationException("Fail to read bootstrap file", e);
      }
      return parseConfig(fileContent);
    }
  };

  public static Bootstrapper getInstance() {
    return DEFAULT_INSTANCE;
  }

  /**
   * Returns configurations from bootstrap.
   */
  public abstract BootstrapInfo readBootstrap() throws XdsInitializationException;

  /** Parses a raw string into {@link BootstrapInfo}. */
  @VisibleForTesting
  @SuppressWarnings("unchecked")
  static BootstrapInfo parseConfig(String rawData) throws XdsInitializationException {
    XdsLogger logger = XdsLogger.withPrefix(LOG_PREFIX);
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
    List<Map<String, ?>> serverConfigList = JsonUtil.checkObjectList(rawServerConfigs);
    for (Map<String, ?> serverConfig : serverConfigList) {
      String serverUri = JsonUtil.getString(serverConfig, "server_uri");
      if (serverUri == null) {
        throw new XdsInitializationException(
            "Invalid bootstrap: missing 'xds_servers'");
      }
      logger.log(XdsLogLevel.INFO, "xDS server URI: {0}", serverUri);
      List<ChannelCreds> channelCredsOptions = new ArrayList<>();
      List<?> rawChannelCredsList = JsonUtil.getList(serverConfig, "channel_creds");
      if (rawChannelCredsList == null || rawChannelCredsList.isEmpty()) {
        throw new XdsInitializationException(
            "Invalid bootstrap: server " + serverUri + " 'channel_creds' required");
      }
      List<Map<String, ?>> channelCredsList = JsonUtil.checkObjectList(rawChannelCredsList);
      for (Map<String, ?> channelCreds : channelCredsList) {
        String type = JsonUtil.getString(channelCreds, "type");
        if (type == null) {
          throw new XdsInitializationException(
              "Invalid bootstrap: server " + serverUri + " with 'channel_creds' type unspecified");
        }
        logger.log(XdsLogLevel.INFO, "Channel credentials option: {0}", type);
        ChannelCreds creds = new ChannelCreds(type, JsonUtil.getObject(channelCreds, "config"));
        channelCredsOptions.add(creds);
      }
      List<String> serverFeatures = JsonUtil.getListOfStrings(serverConfig, "server_features");
      if (serverFeatures != null) {
        logger.log(XdsLogLevel.INFO, "Server features: {0}", serverFeatures);
      }
      servers.add(new ServerInfo(serverUri, channelCredsOptions, serverFeatures));
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
    return new BootstrapInfo(servers, nodeBuilder.build(), certProviders);
  }

  static <T> T checkForNull(T value, String fieldName) throws XdsInitializationException {
    if (value == null) {
      throw new XdsInitializationException(
          "Invalid bootstrap: '" + fieldName + "' does not exist.");
    }
    return value;
  }

  /**
   * Data class containing channel credentials configurations for xDS protocol communication.
   */
  // TODO(chengyuanzhang): May need more complex structure for channel creds config representation.
  @Immutable
  static class ChannelCreds {
    private final String type;
    @Nullable
    private final Map<String, ?> config;

    @VisibleForTesting
    ChannelCreds(String type, @Nullable Map<String, ?> config) {
      this.type = type;
      this.config = config;
    }

    String getType() {
      return type;
    }

    @Nullable
    Map<String, ?> getConfig() {
      if (config != null) {
        return Collections.unmodifiableMap(config);
      }
      return null;
    }
  }

  /**
   * Data class containing xDS server information, such as server URI and channel credential
   * options to be used for communication.
   */
  @Immutable
  static class ServerInfo {
    private final String serverUri;
    private final List<ChannelCreds> channelCredsList;
    @Nullable
    private final List<String> serverFeatures;

    @VisibleForTesting
    ServerInfo(String serverUri, List<ChannelCreds> channelCredsList, List<String> serverFeatures) {
      this.serverUri = serverUri;
      this.channelCredsList = channelCredsList;
      this.serverFeatures = serverFeatures;
    }

    String getServerUri() {
      return serverUri;
    }

    List<ChannelCreds> getChannelCredentials() {
      return Collections.unmodifiableList(channelCredsList);
    }

    List<String> getServerFeatures() {
      return serverFeatures == null
          ? Collections.<String>emptyList()
          : Collections.unmodifiableList(serverFeatures);
    }
  }

  /**
   * Data class containing Certificate provider information: the plugin-name and an opaque
   * Map that represents the config for that plugin.
   */
  @Internal
  @Immutable
  public static class CertificateProviderInfo {
    private final String pluginName;
    private final Map<String, ?> config;

    CertificateProviderInfo(String pluginName, Map<String, ?> config) {
      this.pluginName = checkNotNull(pluginName, "pluginName");
      this.config = checkNotNull(config, "config");
    }

    public String getPluginName() {
      return pluginName;
    }

    public Map<String, ?> getConfig() {
      return config;
    }
  }

  /**
   * Data class containing the results of reading bootstrap.
   */
  @Internal
  @Immutable
  public static class BootstrapInfo {
    private List<ServerInfo> servers;
    private final Node node;
    @Nullable private final Map<String, CertificateProviderInfo> certProviders;

    @VisibleForTesting
    BootstrapInfo(
        List<ServerInfo> servers, Node node, Map<String, CertificateProviderInfo> certProviders) {
      this.servers = servers;
      this.node = node;
      this.certProviders = certProviders;
    }

    /**
     * Returns the list of xDS servers to be connected to.
     */
    List<ServerInfo> getServers() {
      return Collections.unmodifiableList(servers);
    }

    /**
     * Returns the node identifier to be included in xDS requests.
     */
    public Node getNode() {
      return node;
    }

    /** Returns the cert-providers config map. */
    public Map<String, CertificateProviderInfo> getCertProviders() {
      return Collections.unmodifiableMap(certProviders);
    }
  }
}
