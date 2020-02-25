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
import io.grpc.internal.JsonParser;
import io.grpc.internal.JsonUtil;
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

  private static final String BOOTSTRAP_PATH_SYS_ENV_VAR = "GRPC_XDS_BOOTSTRAP";

  private static final Bootstrapper DEFAULT_INSTANCE = new Bootstrapper() {
    @Override
    public BootstrapInfo readBootstrap() throws IOException {
      String filePath = System.getenv(BOOTSTRAP_PATH_SYS_ENV_VAR);
      if (filePath == null) {
        throw
            new IOException("Environment variable " + BOOTSTRAP_PATH_SYS_ENV_VAR + " not defined.");
      }
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
  static BootstrapInfo parseConfig(String rawData) throws IOException {
    @SuppressWarnings("unchecked")
    Map<String, ?> rawBootstrap = (Map<String, ?>) JsonParser.parse(rawData);

    List<ServerInfo> servers = new ArrayList<>();
    List<?> rawServerConfigs = JsonUtil.getList(rawBootstrap, "xds_servers");
    if (rawServerConfigs == null) {
      throw new IOException("Invalid bootstrap: 'xds_servers' does not exist.");
    }
    List<Map<String, ?>> serverConfigList = JsonUtil.checkObjectList(rawServerConfigs);
    for (Map<String, ?> serverConfig : serverConfigList) {
      String serverUri = JsonUtil.getString(serverConfig, "server_uri");
      if (serverUri == null) {
        throw new IOException("Invalid bootstrap: 'xds_servers' contains unknown server.");
      }
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
        nodeBuilder.setId(id);
      }
      String cluster = JsonUtil.getString(rawNode, "cluster");
      if (cluster != null) {
        nodeBuilder.setCluster(cluster);
      }
      Map<String, ?> metadata = JsonUtil.getObject(rawNode, "metadata");
      if (metadata != null) {
        Struct.Builder structBuilder = Struct.newBuilder();
        for (Map.Entry<String, ?> entry : metadata.entrySet()) {
          structBuilder.putFields(entry.getKey(), convertToValue(entry.getValue()));
        }
        nodeBuilder.setMetadata(structBuilder);
      }
      Map<String, ?> rawLocality = JsonUtil.getObject(rawNode, "locality");
      if (rawLocality != null) {
        Locality.Builder localityBuilder = Locality.newBuilder();
        if (rawLocality.containsKey("region")) {
          localityBuilder.setRegion(JsonUtil.getString(rawLocality, "region"));
        }
        if (rawLocality.containsKey("zone")) {
          localityBuilder.setZone(JsonUtil.getString(rawLocality, "zone"));
        }
        if (rawLocality.containsKey("sub_zone")) {
          localityBuilder.setSubZone(JsonUtil.getString(rawLocality, "sub_zone"));
        }
        nodeBuilder.setLocality(localityBuilder);
      }
    }
    nodeBuilder.setBuildVersion(GrpcUtil.getGrpcBuildVersion());

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
