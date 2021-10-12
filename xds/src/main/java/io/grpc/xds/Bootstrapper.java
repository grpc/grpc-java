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

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.ChannelCredentials;
import io.grpc.Internal;
import io.grpc.xds.EnvoyProtoData.Node;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Loads configuration information to bootstrap gRPC's integration of xDS protocol.
 */
@Internal
public abstract class Bootstrapper {

  /**
   * Returns system-loaded bootstrap configuration.
   */
  public abstract BootstrapInfo bootstrap() throws XdsInitializationException;

  /**
   * Returns bootstrap configuration given by the raw data in JSON format.
   */
  BootstrapInfo bootstrap(Map<String, ?> rawData) throws XdsInitializationException {
    throw new UnsupportedOperationException();
  }

  /**
   * Data class containing xDS server information, such as server URI and channel credentials
   * to be used for communication.
   */
  @AutoValue
  @Internal
  abstract static class ServerInfo {
    abstract String target();

    abstract ChannelCredentials channelCredentials();

    abstract boolean useProtocolV3();

    @VisibleForTesting
    static ServerInfo create(
        String target, ChannelCredentials channelCredentials, boolean useProtocolV3) {
      return new AutoValue_Bootstrapper_ServerInfo(target, channelCredentials, useProtocolV3);
    }
  }

  /**
   * Data class containing Certificate provider information: the plugin-name and an opaque
   * Map that represents the config for that plugin.
   */
  @AutoValue
  @Internal
  public abstract static class CertificateProviderInfo {
    public abstract String pluginName();

    public abstract ImmutableMap<String, ?> config();

    @VisibleForTesting
    public static CertificateProviderInfo create(String pluginName, Map<String, ?> config) {
      return new AutoValue_Bootstrapper_CertificateProviderInfo(
          pluginName, ImmutableMap.copyOf(config));
    }
  }

  /**
   * Data class containing the results of reading bootstrap.
   */
  @AutoValue
  @Internal
  public abstract static class BootstrapInfo {
    /** Returns the list of xDS servers to be connected to. */
    abstract ImmutableList<ServerInfo> servers();

    /** Returns the node identifier to be included in xDS requests. */
    public abstract Node node();

    /** Returns the cert-providers config map. */
    @Nullable
    public abstract ImmutableMap<String, CertificateProviderInfo> certProviders();

    @Nullable
    public abstract String serverListenerResourceNameTemplate();

    @VisibleForTesting
    static Builder builder() {
      return new AutoValue_Bootstrapper_BootstrapInfo.Builder();
    }

    @AutoValue.Builder
    @VisibleForTesting
    abstract static class Builder {
      abstract Builder servers(List<ServerInfo> servers);

      abstract Builder node(Node node);

      abstract Builder certProviders(@Nullable Map<String, CertificateProviderInfo> certProviders);

      abstract Builder serverListenerResourceNameTemplate(
          @Nullable String serverListenerResourceNameTemplate);

      abstract BootstrapInfo build();
    }
  }
}
