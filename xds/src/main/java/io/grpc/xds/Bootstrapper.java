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

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.envoyproxy.envoy.api.v2.core.Locality;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.Internal;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.GrpcUtil.GrpcBuildVersion;
import io.grpc.internal.JsonParser;
import io.grpc.internal.JsonUtil;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
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
    public BootstrapInfo readBootstrap() throws IOException {
      String filePath = System.getenv(BOOTSTRAP_PATH_SYS_ENV_VAR);
      if (filePath == null) {
        throw
            new IOException("Environment variable " + BOOTSTRAP_PATH_SYS_ENV_VAR + " not defined.");
      }
      XdsLogger
          .withPrefix(LOG_PREFIX)
          .log(XdsLogLevel.INFO, BOOTSTRAP_PATH_SYS_ENV_VAR + "={0}", filePath);
      return parseConfig(
          new String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8));
    }
  };

  public static Bootstrapper getInstance() {
    return DEFAULT_INSTANCE;
  }

  /**
   * Returns configurations from bootstrap.
   */
  public abstract BootstrapInfo readBootstrap() throws IOException;

  @VisibleForTesting
  @SuppressWarnings("deprecation")
  static BootstrapInfo parseConfig(String rawData) throws IOException {
    XdsLogger logger = XdsLogger.withPrefix(LOG_PREFIX);
    logger.log(XdsLogLevel.INFO, "Reading bootstrap information");
    @SuppressWarnings("unchecked")
    Map<String, ?> rawBootstrap = (Map<String, ?>) JsonParser.parse(rawData);
    logger.log(XdsLogLevel.DEBUG, "Bootstrap configuration:\n{0}", rawBootstrap);

    List<ServerInfo> servers = new ArrayList<>();
    List<?> rawServerConfigs = JsonUtil.getList(rawBootstrap, "xds_servers");
    if (rawServerConfigs == null) {
      throw new IOException("Invalid bootstrap: 'xds_servers' does not exist.");
    }
    logger.log(XdsLogLevel.INFO, "Configured with {0} xDS servers", rawServerConfigs.size());
    List<Map<String, ?>> serverConfigList = JsonUtil.checkObjectList(rawServerConfigs);
    for (Map<String, ?> serverConfig : serverConfigList) {
      String serverUri = JsonUtil.getString(serverConfig, "server_uri");
      if (serverUri == null) {
        throw new IOException("Invalid bootstrap: 'xds_servers' contains unknown server.");
      }
      logger.log(XdsLogLevel.INFO, "xDS server URI: {0}", serverUri);
      List<ChannelCreds> channelCredsOptions = new ArrayList<>();
      List<?> rawChannelCredsList = JsonUtil.getList(serverConfig, "channel_creds");
      // List of channel creds is optional.
      if (rawChannelCredsList != null) {
        List<Map<String, ?>> channelCredsList = JsonUtil.checkObjectList(rawChannelCredsList);
        for (Map<String, ?> channelCreds : channelCredsList) {
          String type = JsonUtil.getString(channelCreds, "type");
          if (type == null) {
            throw new IOException("Invalid bootstrap: 'xds_servers' contains server with "
                + "unknown type 'channel_creds'.");
          }
          logger.log(XdsLogLevel.INFO, "Channel credentials option: {0}", type);
          ChannelCreds creds = new ChannelCreds(type, JsonUtil.getObject(channelCreds, "config"));
          channelCredsOptions.add(creds);
        }
      }
      servers.add(new ServerInfo(serverUri, channelCredsOptions));
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
        Struct.Builder structBuilder = Struct.newBuilder();
        for (Map.Entry<String, ?> entry : metadata.entrySet()) {
          logger.log(
              XdsLogLevel.INFO,
              "Node metadata field {0}: {1}", entry.getKey(), entry.getValue());
          structBuilder.putFields(entry.getKey(), convertToValue(entry.getValue()));
        }
        nodeBuilder.setMetadata(structBuilder);
      }
      Map<String, ?> rawLocality = JsonUtil.getObject(rawNode, "locality");
      if (rawLocality != null) {
        Locality.Builder localityBuilder = Locality.newBuilder();
        if (rawLocality.containsKey("region")) {
          String region = JsonUtil.getString(rawLocality, "region");
          logger.log(XdsLogLevel.INFO, "Locality region: {0}", region);
          localityBuilder.setRegion(region);
        }
        if (rawLocality.containsKey("zone")) {
          String zone = JsonUtil.getString(rawLocality, "zone");
          logger.log(XdsLogLevel.INFO, "Locality zone: {0}", zone);
          localityBuilder.setZone(zone);
        }
        if (rawLocality.containsKey("sub_zone")) {
          String subZone = JsonUtil.getString(rawLocality, "sub_zone");
          logger.log(XdsLogLevel.INFO, "Locality sub_zone: {0}", subZone);
          localityBuilder.setSubZone(subZone);
        }
        nodeBuilder.setLocality(localityBuilder);
      }
    }
    GrpcBuildVersion buildVersion = GrpcUtil.getGrpcBuildVersion();
    logger.log(XdsLogLevel.INFO, "Build version: {0}", buildVersion);
    nodeBuilder.setBuildVersion(buildVersion.toString());
    nodeBuilder.setUserAgentName(buildVersion.getUserAgent());
    nodeBuilder.setUserAgentVersion(buildVersion.getImplementationVersion());
    nodeBuilder.addClientFeatures(CLIENT_FEATURE_DISABLE_OVERPROVISIONING);

    return new BootstrapInfo(servers, nodeBuilder.build());
  }

  /**
   * Converts Java representation of the given JSON value to protobuf's {@link
   * com.google.protobuf.Value} representation.
   *
   * <p>The given {@code rawObject} must be a valid JSON value in Java representation, which is
   * either a {@code Map<String, ?>}, {@code List<?>}, {@code String}, {@code Double},
   * {@code Boolean}, or {@code null}.
   */
  private static Value convertToValue(Object rawObject) {
    Value.Builder valueBuilder = Value.newBuilder();
    if (rawObject == null) {
      valueBuilder.setNullValue(NullValue.NULL_VALUE);
    } else if (rawObject instanceof Double) {
      valueBuilder.setNumberValue((Double) rawObject);
    } else if (rawObject instanceof String) {
      valueBuilder.setStringValue((String) rawObject);
    } else if (rawObject instanceof Boolean) {
      valueBuilder.setBoolValue((Boolean) rawObject);
    } else if (rawObject instanceof Map) {
      Struct.Builder structBuilder = Struct.newBuilder();
      @SuppressWarnings("unchecked")
      Map<String, ?> map = (Map<String, ?>) rawObject;
      for (Map.Entry<String, ?> entry : map.entrySet()) {
        structBuilder.putFields(entry.getKey(), convertToValue(entry.getValue()));
      }
      valueBuilder.setStructValue(structBuilder);
    } else if (rawObject instanceof List) {
      ListValue.Builder listBuilder = ListValue.newBuilder();
      List<?> list = (List<?>) rawObject;
      for (Object obj : list) {
        listBuilder.addValues(convertToValue(obj));
      }
      valueBuilder.setListValue(listBuilder);
    }
    return valueBuilder.build();
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

    @VisibleForTesting
    ServerInfo(String serverUri, List<ChannelCreds> channelCredsList) {
      this.serverUri = serverUri;
      this.channelCredsList = channelCredsList;
    }

    String getServerUri() {
      return serverUri;
    }

    List<ChannelCreds> getChannelCredentials() {
      return Collections.unmodifiableList(channelCredsList);
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

    @VisibleForTesting
    BootstrapInfo(List<ServerInfo> servers, Node node) {
      this.servers = servers;
      this.node = node;
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

  }
}
