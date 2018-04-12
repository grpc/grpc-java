/*
 * Copyright 2018, gRPC Authors All rights reserved.
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

import io.grpc.ExperimentalApi;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import org.junit.rules.ExternalResource;
import org.junit.rules.TestRule;

/**
 * A JUnit {@link TestRule} that can register gRPC resources and manages its automatic release at
 * the end of the test.
 *
 * @since 1.12.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/2488")
@NotThreadSafe
public final class GrpcCleanupRule extends ExternalResource {

  private final List<Resource> resources = new ArrayList<Resource>();

  private RuntimeException exception;

  /**
   * Registers the given channel to the rule. Once registered, the channel will be automatically
   * shutdown at the end of the test.
   *
   * <p>This method need be properly synchronized when used in multiple threads. This method must
   * not be used during test tear down.
   *
   * @return the input channel
   */
  public <T extends ManagedChannel> T register(@Nonnull T channel) {
    resources.add(new ManagedChannelResource(channel));
    return channel;
  }

  /**
   * Registers the given server to the rule. Once registered, the server will be automatically
   * shutdown at the end of the test.
   *
   * <p>This method need be properly synchronized when used in multiple threads. This method must
   * not be used during test tear down.
   *
   * @return the input server
   */
  public <T extends Server> T register(@Nonnull T server) {
    resources.add(new ServerResource(server));
    return server;
  }

  /**
   * Releases all the registered resources.
   */
  @Override
  protected void after() {
    for (int i = resources.size() - 1; i >= 0; i--) {
      resources.get(i).cleanUp();
    }

    for (int i = resources.size() - 1; i >= 0; i--) {
      resources.get(i).awaitCleanupDone();
    }

    resources.clear();

    if (exception != null) {
      throw exception;
    }
  }

  private interface Resource {
    void cleanUp();

    void awaitCleanupDone();
  }

  private final class ManagedChannelResource implements Resource {
    final ManagedChannel channel;

    ManagedChannelResource(ManagedChannel channel) {
      this.channel = channel;
    }

    @Override
    public void cleanUp() {
      channel.shutdown();
    }

    @Override
    public void awaitCleanupDone() {
      if (exception != null) {
        channel.shutdownNow();
        return;
      }

      try {
        channel.awaitTermination(1, TimeUnit.MINUTES);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        exception = new RuntimeException("Channel " + channel + " shutdown was interrupted", e);
      } finally {
        channel.shutdownNow();
      }
    }
  }

  private final class ServerResource implements Resource {
    final Server server;

    ServerResource(Server server) {
      this.server = server;
    }

    @Override
    public void cleanUp() {
      server.shutdown();
    }

    @Override
    public void awaitCleanupDone() {
      if (exception != null) {
        server.shutdownNow();
        return;
      }

      try {
        server.awaitTermination(1, TimeUnit.MINUTES);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        exception = new RuntimeException("Server " + server + " shutdown was interrupted", e);
      } finally {
        server.shutdownNow();
      }
    }
  }
}
