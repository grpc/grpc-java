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
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.CallCredentials;
import io.grpc.auth.MoreCallCredentials;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import javax.annotation.concurrent.Immutable;

/**
 * Loads configuration information to bootstrap xDS load balancer.
 */
@Immutable
abstract class Bootstrapper {

  private static final String BOOTSTRAP_PATH_SYS_ENV_VAR = "GRPC_XDS_BOOTSTRAP";

  static Bootstrapper getInstance() throws Exception {
    if (FileBasedBootstrapper.defaultInstance == null) {
      throw FileBasedBootstrapper.failToBootstrapException;
    }
    return FileBasedBootstrapper.defaultInstance;
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

    private static final Exception failToBootstrapException;
    private static final Bootstrapper defaultInstance;

    private final String balancerName;
    private final Node node;
    // TODO(chengyuanzhang): Add configuration for call credentials loaded from bootstrap file.
    //  hard-coded for alpha release.

    static {
      Bootstrapper instance = null;
      Exception exception = null;
      try {
        instance = new FileBasedBootstrapper(Bootstrapper.readConfig());
      } catch (Exception e) {
        exception = e;
      }
      defaultInstance = instance;
      failToBootstrapException = exception;
    }

    @VisibleForTesting
    FileBasedBootstrapper(Bootstrap bootstrapConfig) throws IOException {
      ApiConfigSource serverConfig = bootstrapConfig.getXdsServer();
      if (!serverConfig.getApiType().equals(ApiType.GRPC)) {
        throw new IOException("Unexpected api type: " + serverConfig.getApiType().toString());
      }
      if (serverConfig.getGrpcServicesCount() != 1) {
        throw new IOException(
            "Unexpected number of gRPC services: expected: 1, actual: "
                + serverConfig.getGrpcServicesCount());
      }
      balancerName = serverConfig.getGrpcServices(0).getGoogleGrpc().getTargetUri();
      node = bootstrapConfig.getNode();
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

  private static Bootstrap readConfig() throws IOException {
    String filePath = System.getenv(BOOTSTRAP_PATH_SYS_ENV_VAR);
    if (filePath == null) {
      throw new IOException("Environment variable " + BOOTSTRAP_PATH_SYS_ENV_VAR + " not found.");
    }
    return parseConfig(new String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8));
  }

  @VisibleForTesting
  static Bootstrap parseConfig(String rawData) throws InvalidProtocolBufferException {
    Bootstrap.Builder bootstrapBuilder = Bootstrap.newBuilder();
    JsonFormat.parser().merge(rawData, bootstrapBuilder);
    return bootstrapBuilder.build();
  }
}
