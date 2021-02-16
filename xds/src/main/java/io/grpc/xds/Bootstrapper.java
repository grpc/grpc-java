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
import io.grpc.Internal;
import io.grpc.xds.EnvoyProtoData.Node;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Loads configuration information to bootstrap gRPC's integration of xDS protocol.
 */
@Internal
public interface Bootstrapper {

  /**
   * Returns configurations from bootstrap.
   */
  BootstrapInfo bootstrap() throws XdsInitializationException;

  /**
   * Data class containing xDS server information, such as server URI and channel credentials
   * to be used for communication.
   */
  @Internal
  class ServerInfo {
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
  class CertificateProviderInfo {
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
  class BootstrapInfo {
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
