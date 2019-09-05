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

import com.google.auth.oauth2.ComputeEngineCredentials;
import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.util.JsonFormat;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.CallCredentials;
import io.grpc.auth.MoreCallCredentials;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Loads configuration information to bootstrap xDS load balancer.
 */
@NotThreadSafe
abstract class Bootstrapper {

  private static final String BOOTSTRAP_PATH_SYS_ENV_VAR = "GRPC_XDS_BOOTSTRAP";
  private static final Logger log = Logger.getLogger(Bootstrapper.class.getName());
  private static RuntimeException failToBootstrapException;
  private static Bootstrapper DEFAULT_INSTANCE;

  static Bootstrapper getInstance() {
    if (DEFAULT_INSTANCE == null && failToBootstrapException == null) {
      try {
        DEFAULT_INSTANCE = new FileBasedBootstrapper();
      } catch (RuntimeException e) {
        failToBootstrapException = e;
      }
    }
    if (DEFAULT_INSTANCE == null) {
      throw failToBootstrapException;
    }
    return DEFAULT_INSTANCE;
  }

  @Nullable
  private static ConfigReader createConfigReaderOrNull() {
    String filePath = System.getenv(BOOTSTRAP_PATH_SYS_ENV_VAR);
    ConfigReader reader = null;
    if (filePath != null) {
      try {
        reader = new ConfigReader(Paths.get(filePath));
      } catch (Exception e) {
        log.warning("Unable to read bootstrap file: " + e.getMessage());
      }
    } else {
      log.warning("Environment variable " + BOOTSTRAP_PATH_SYS_ENV_VAR + " not found.");
    }
    return reader;
  }

  /**
   * Returns the canonical name of the traffic director to be connected to.
   */
  abstract String getBalancerName();

  /**
   * Returns a {@link Node} message with project/network metadata in it to be included in
   * xDS requests.
   */
  abstract Node getNode();

  /**
   * Returns the credentials to use when communicating with the xDS server.
   */
  abstract CallCredentials getCallCredentials();

  @VisibleForTesting
  static final class FileBasedBootstrapper extends Bootstrapper {

    private final String balancerName;
    private final Node node;
    // TODO(chengyuanzhang): Add configuration for call credentials loaded from bootstrap file.
    //  hard-coded for alpha release.

    private FileBasedBootstrapper() {
      this(createConfigReaderOrNull());
    }

    @VisibleForTesting
    FileBasedBootstrapper(@Nullable ConfigReader reader) {
      if (reader == null) {
        throw new RuntimeException("Failed to bootstrap from config file.");
      }
      balancerName = reader.getServerConfig().getGrpcServices(0).getGoogleGrpc().getTargetUri();
      node = reader.getNode();
    }

    @Override
    String getBalancerName() {
      return balancerName;
    }
    
    @Override
    Node getNode() {
      return node;
    }

    @Override
    CallCredentials getCallCredentials() {
      return MoreCallCredentials.from(ComputeEngineCredentials.create());
    }
  }

  @VisibleForTesting
  static final class ConfigReader {
    private final Node node;
    private final ApiConfigSource serverConfig;

    private ConfigReader(Path path) throws IOException {
      this(new String(Files.readAllBytes(path), Charset.defaultCharset()));
    }

    ConfigReader(String data) throws IOException {
      Bootstrap.Builder bootstrapBuilder = Bootstrap.newBuilder();
      JsonFormat.parser().merge(data, bootstrapBuilder);
      node = bootstrapBuilder.getNode();
      serverConfig = bootstrapBuilder.getXdsServer();
      if (!serverConfig.getApiType().equals(ApiType.GRPC)) {
        throw new RuntimeException("Unexpected api type: " + serverConfig.getApiType().toString());
      }
      if (serverConfig.getGrpcServicesCount() != 1) {
        throw new RuntimeException(
            "Unexpected number of gRPC services: expected: 1, actual: "
                + serverConfig.getGrpcServicesCount());
      }
    }

    private Node getNode() {
      return node;
    }

    private ApiConfigSource getServerConfig() {
      return serverConfig;
    }
  }
}
