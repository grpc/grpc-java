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

package io.grpc.testing.integration;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.NameResolverRegistry;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.xds.XdsNameResolverProvider;
import io.grpc.xds.XdsServerBuilder;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Abstract base class for end-to-end xds tests.
 * A local control plane is implemented in {@link XdsTestControlPlaneService}.
 * Test cases can inject xds configs to the control plane for testing.
 */
public abstract class AbstractXdsInteropTest {
  private static final Logger logger = Logger.getLogger(AbstractXdsInteropTest.class.getName());

  protected static final int testServerPort = 8080;
  private static final int controlPlaneServicePort = 443;
  private Server server;
  private Server controlPlane;
  protected TestServiceGrpc.TestServiceBlockingStub blockingStub;
  private ScheduledExecutorService executor;
  private XdsNameResolverProvider nameResolverProvider;
  private static final String scheme = "test-xds";
  protected static final String serverHostName = "0.0.0.0:" + testServerPort;
  protected static final String SERVER_LISTENER_TEMPLATE =
      "grpc/server?udpa.resource.listening_address=%s";

  private static final Map<String, ?> defaultClientBootstrapOverride = ImmutableMap.of(
      "node", ImmutableMap.of(
          "id", UUID.randomUUID().toString(),
          "cluster", "cluster0"),
      "xds_servers", Collections.singletonList(
          ImmutableMap.of(
              "server_uri", "localhost:" + controlPlaneServicePort,
              "channel_creds", Collections.singletonList(
                  ImmutableMap.of("type", "insecure")
              ),
              "server_features", Collections.singletonList("xds_v3")
          )
      )
  );

  /**
   * Provides default client bootstrap.
   * A subclass test case should override this method if it tests client bootstrap.
   */
  protected Map<String, ?> getClientBootstrapOverride() {
    return defaultClientBootstrapOverride;
  }

  private static final Map<String, ?> defaultServerBootstrapOverride = ImmutableMap.of(
      "node", ImmutableMap.of(
          "id", UUID.randomUUID().toString()),
      "xds_servers", Collections.singletonList(
          ImmutableMap.of(
              "server_uri", "localhost:" + controlPlaneServicePort,
              "channel_creds", Collections.singletonList(
                  ImmutableMap.of("type", "insecure")
              ),
              "server_features", Collections.singletonList("xds_v3")
          )
      ),
      "server_listener_resource_name_template", SERVER_LISTENER_TEMPLATE
  );

  /**
   * Provides default server bootstrap.
   * A subclass test case should override this method if it tests server bootstrap.
   */
  protected Map<String, ?> getServerBootstrapOverride() {
    return defaultServerBootstrapOverride;
  }

  protected void setUp() throws Exception {
    startControlPlane();
    startServer();
    nameResolverProvider = XdsNameResolverProvider.createForTest(scheme,
        getClientBootstrapOverride());
    NameResolverRegistry.getDefaultRegistry().register(nameResolverProvider);
    ManagedChannel channel = Grpc.newChannelBuilder(scheme + ":///" + serverHostName,
        InsecureChannelCredentials.create()).build();
    blockingStub = TestServiceGrpc.newBlockingStub(channel);
  }

  protected void tearDown() throws Exception {
    if (server != null) {
      server.shutdownNow();
      if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
        logger.log(Level.SEVERE, "Timed out waiting for server shutdown");
      }
    }
    if (controlPlane != null) {
      controlPlane.shutdownNow();
      if (!controlPlane.awaitTermination(5, TimeUnit.SECONDS)) {
        logger.log(Level.SEVERE, "Timed out waiting for server shutdown");
      }
    }
    if (executor != null) {
      MoreExecutors.shutdownAndAwaitTermination(executor, 5, TimeUnit.SECONDS);
    }
    NameResolverRegistry.getDefaultRegistry().deregister(nameResolverProvider);
  }

  protected void startServer() throws Exception {
    executor = Executors.newSingleThreadScheduledExecutor();
    XdsServerBuilder serverBuilder = XdsServerBuilder.forPort(
        testServerPort, InsecureServerCredentials.create())
        .addService(new TestServiceImpl(executor))
        .overrideBootstrapForTest(getServerBootstrapOverride());
    server = serverBuilder.build().start();
  }

  /**
   * Provides default control plane xds configs.
   * A subclass test case should override this method to inject control plane xds configs to verify
   * end-to-end behavior.
   */
  protected XdsTestControlPlaneService.XdsTestControlPlaneConfig getControlPlaneConfig() {
    String tcpListenerName = SERVER_LISTENER_TEMPLATE.replaceAll("%s", serverHostName);
    return new XdsTestControlPlaneService.XdsTestControlPlaneConfig(
        Collections.singletonList(
            XdsTestControlPlaneService.serverListener(tcpListenerName, serverHostName)),
        Collections.singletonList(XdsTestControlPlaneService.clientListener(serverHostName)),
        Collections.singletonList(XdsTestControlPlaneService.rds(serverHostName)),
        Collections.singletonList(XdsTestControlPlaneService.cds()),
        Collections.singletonList(XdsTestControlPlaneService.eds(testServerPort))
    );
  }

  private void startControlPlane() throws Exception {
    XdsTestControlPlaneService.XdsTestControlPlaneConfig controlPlaneConfig =
        getControlPlaneConfig();
    logger.log(Level.FINER, "Starting control plane with config: {0}", controlPlaneConfig);
    XdsTestControlPlaneService controlPlaneService = new XdsTestControlPlaneService(
        controlPlaneConfig);
    NettyServerBuilder controlPlaneServerBuilder =
        NettyServerBuilder.forPort(controlPlaneServicePort)
        .addService(controlPlaneService);
    controlPlane = controlPlaneServerBuilder.build().start();
  }

  /**
   * A subclass test case should override this method to verify end-to-end behaviour.
   */
  abstract void run();
}
