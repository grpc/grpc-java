/*
 * Copyright 2016, gRPC Authors All rights reserved.
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

package io.grpc.testing;

import static com.google.common.base.Preconditions.checkState;

import io.grpc.BindableService;
import io.grpc.ExperimentalApi;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.util.MutableHandlerRegistry;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.junit.rules.ExternalResource;
import org.junit.rules.TestRule;

/**
 * {@code GrpcServerRule} is a JUnit {@link TestRule} that is particularly useful for mocking out
 * external gRPC-based services and asserting that the expected requests were made.
 *
 * <p>It provides and starts a default in-process gRPC server with a {@link MutableHandlerRegistry}
 * for adding services, and provides a default in-process client channel that is connected to the
 * default server.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/2488")
public class GrpcServerRule extends ExternalResource {

  private ManagedChannel channel;
  private Server server;
  private String serverName;
  private MutableHandlerRegistry serviceRegistry;
  private boolean useDirectExecutor;
  private boolean setUp;
  private boolean tearDown;
  /**
   * True if the default server and channel are initiated.
   */
  private volatile boolean initiated;

  /**
   * Configures the rule to use a direct executor for the default server and channel. This can only
   * be called at the rule instantiation.
   *
   * @return this
   */
  public final GrpcServerRule directExecutor() {
    checkState(!setUp, "directExecutor() can only be called at the rule instantiation");
    useDirectExecutor = true;
    return this;
  }

  /**
   * Returns the default client channel.
   */
  public final ManagedChannel getChannel() {
    if (setUp && !tearDown && !initiated) {
      initDefaultServer();
    }
    return channel;
  }

  /**
   * Returns the default server.
   */
  public final Server getServer() {
    if (setUp && !tearDown && !initiated) {
      initDefaultServer();
    }
    return server;
  }

  /**
   * Returns the server name of the default server.
   */
  public final String getServerName() {
    if (setUp && !tearDown && !initiated) {
      initDefaultServer();
    }
    return serverName;
  }

  /**
   * Returns the service registry for the default server. The registry is used to add service
   * instances (e.g. {@link BindableService} or {@link ServerServiceDefinition} to the server.
   */
  public final MutableHandlerRegistry getServiceRegistry() {
    if (setUp && !tearDown && !initiated) {
      initDefaultServer();
    }
    return serviceRegistry;
  }

  /**
   * After the test has completed, clean up the channel and server.
   */
  @Override
  protected void after() {
    tearDown = true;
    if (server == null) {
      return;
    }

    serverName = null;
    serviceRegistry = null;

    channel.shutdown();
    server.shutdown();

    try {
      channel.awaitTermination(1, TimeUnit.MINUTES);
      server.awaitTermination(1, TimeUnit.MINUTES);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } finally {
      channel.shutdownNow();
      channel = null;

      server.shutdownNow();
      server = null;
    }
  }

  @Override
  protected void before() {
    setUp = true;
  }

  /**
   * Creates the default server and channel, and starts the server.
   */
  private synchronized void initDefaultServer() {
    if (server != null) {
      return;
    }

    serverName = InProcessServerBuilder.generateName();

    serviceRegistry = new MutableHandlerRegistry();

    InProcessServerBuilder serverBuilder = InProcessServerBuilder.forName(serverName)
        .fallbackHandlerRegistry(serviceRegistry);

    if (useDirectExecutor) {
      serverBuilder.directExecutor();
    }

    server = serverBuilder.build();

    try {
      server.start();
    } catch (IOException e) {
      throw new AssertionError(e);
    }

    InProcessChannelBuilder channelBuilder = InProcessChannelBuilder.forName(serverName);

    if (useDirectExecutor) {
      channelBuilder.directExecutor();
    }

    channel = channelBuilder.build();
    initiated = true;
  }
}
