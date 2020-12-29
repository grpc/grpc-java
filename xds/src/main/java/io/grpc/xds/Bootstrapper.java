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
import io.grpc.ChannelCredentials;
import io.grpc.InsecureChannelCredentials;
import io.grpc.Internal;
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
  private static final String BOOTSTRAP_PATH_SYS_PROPERTY_VAR = "io.grpc.xds.bootstrap";
  private static final String XDS_V3_SERVER_FEATURE = "xds_v3";
  @VisibleForTesting
  static boolean enableV3Protocol = Boolean.parseBoolean(
      System.getenv("GRPC_XDS_EXPERIMENTAL_V3_SUPPORT"));
  @VisibleForTesting
  static final String CLIENT_FEATURE_DISABLE_OVERPROVISIONING =
      "envoy.lb.does_not_support_overprovisioning";

  private static final Bootstrapper DEFAULT_INSTANCE = new Bootstrapper() {
    @Override
    public BootstrapInfo bootstrap() throws XdsInitializationException {
      String filePathSource = BOOTSTRAP_PATH_SYS_ENV_VAR;
      String filePath = System.getenv(filePathSource);
      if (filePath == null) {
        filePathSource = BOOTSTRAP_PATH_SYS_PROPERTY_VAR;
        filePath = System.getProperty(filePathSource);
      }
      if (filePath == null) {
        throw new XdsInitializationException(
            "Environment variable " + BOOTSTRAP_PATH_SYS_ENV_VAR
            + " or Java System Property " + BOOTSTRAP_PATH_SYS_PROPERTY_VAR + " not defined.");
      }
      XdsLogger
          .withPrefix(LOG_PREFIX)
          .log(XdsLogLevel.INFO, filePathSource + "={0}", filePath);
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
  public abstract BootstrapInfo bootstrap() throws XdsInitializationException;

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

  /**
   * Data class containing xDS server information, such as server URI and channel credentials
   * to be used for communication.
   */
  @Immutable
  static class ServerInfo {
    private final String target;
    private final ChannelCredentials channelCredentials;
    private final boolean useProtocolV3;

    @VisibleForTesting
    ServerInfo(String target, ChannelCredentials channelCredentials, boolean useProtocolV3) {
      this.target = checkNotNull(target, "target");
      this.channelCredentials = checkNotNull(channelCredentials, "channelCredentials");
      this.useProtocolV3 = useProtocolV3;
    }

    String getTarget() {
      return target;
    }

    ChannelCredentials getChannelCredentials() {
      return channelCredentials;
    }

    boolean isUseProtocolV3() {
      return useProtocolV3;
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
    @Nullable private final String grpcServerResourceId;

    @VisibleForTesting
    BootstrapInfo(
        List<ServerInfo> servers,
        Node node,
        Map<String, CertificateProviderInfo> certProviders,
        String grpcServerResourceId) {
      this.servers = servers;
      this.node = node;
      this.certProviders = certProviders;
      this.grpcServerResourceId = grpcServerResourceId;
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

    @Nullable
    public String getGrpcServerResourceId() {
      return grpcServerResourceId;
    }
  }
}
