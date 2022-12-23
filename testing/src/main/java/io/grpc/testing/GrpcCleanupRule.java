/*
 * Copyright 2018 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import com.google.common.collect.Lists;
import io.grpc.ExperimentalApi;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import org.junit.rules.ExternalResource;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * A JUnit {@link ExternalResource} that can register gRPC resources and manages its automatic
 * release at the end of the test. If any of the resources registered to the rule can not be
 * successfully released, the test will fail.
 *
 * <p>Example usage:
 * <pre>{@code @Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();
 * ...
 * // The Channel and Server can be created in any order
 * grpcCleanup.register(
 *     InProcessServerBuilder.forName("my-test-case")
 *         .directExecutor()
 *         .addService(serviceImpl)
 *         .build()
 *         .start());
 * ManagedChannel channel = grpcCleanup.register(
 *     InProcessChannelBuilder.forName("my-test-case")
 *         .directExecutor()
 *         .build());
 * }</pre>
 *
 * <p>To use as a replacement for {@link GrpcServerRule}:
 * <pre>{@code String serverName = InProcessServerBuilder.generateName();
 * MutableHandlerRegistry serviceRegistry = new MutableHandlerRegistry();
 * Server server = grpcCleanup.register(
 *     InProcessServerBuilder.forName(serverName)
 *         .fallbackHandlerRegistry(serviceRegistry)
 *         .build()
 *         .start());
 * ManagedChannel channel = grpcCleanup.register(
 *     InProcessChannelBuilder.forName(serverName).build());
 * }</pre>
 *
 * @since 1.13.0
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/2488")
@NotThreadSafe
public final class GrpcCleanupRule extends ExternalResource {

  private final List<Resource> resources = new ArrayList<>();
  private long timeoutNanos = TimeUnit.SECONDS.toNanos(10L);
  private Stopwatch stopwatch = Stopwatch.createUnstarted();

  private boolean abruptShutdown;

  /**
   * Sets a positive total time limit for the automatic resource cleanup. If any of the resources
   * registered to the rule fails to be released in time, the test will fail.
   *
   * <p>Note that the resource cleanup duration may or may not be counted as part of the JUnit
   * {@link org.junit.rules.Timeout Timeout} rule's test duration, depending on which rule is
   * applied first.
   *
   * @return this
   */
  public GrpcCleanupRule setTimeout(long timeout, TimeUnit timeUnit) {
    checkArgument(timeout > 0, "timeout should be positive");
    timeoutNanos = timeUnit.toNanos(timeout);
    return this;
  }

  /**
   * Sets a specified time source for monitoring cleanup timeout.
   *
   * @return this
   */
  @SuppressWarnings("BetaApi") // Test only.
  @VisibleForTesting
  GrpcCleanupRule setTicker(Ticker ticker) {
    this.stopwatch = Stopwatch.createUnstarted(ticker);
    return this;
  }

  /**
   * Registers the given channel to the rule. Once registered, the channel will be automatically
   * shutdown at the end of the test.
   *
   * <p>This method need be properly synchronized if used in multiple threads. This method must
   * not be used during the test teardown.
   *
   * @return the input channel
   */
  public <T extends ManagedChannel> T register(@Nonnull T channel) {
    checkNotNull(channel, "channel");
    register(new ManagedChannelResource(channel));
    return channel;
  }

  /**
   * Registers the given server to the rule. Once registered, the server will be automatically
   * shutdown at the end of the test.
   *
   * <p>This method need be properly synchronized if used in multiple threads. This method must
   * not be used during the test teardown.
   *
   * @return the input server
   */
  public <T extends Server> T register(@Nonnull T server) {
    checkNotNull(server, "server");
    register(new ServerResource(server));
    return server;
  }

  @VisibleForTesting
  void register(Resource resource) {
    resources.add(resource);
  }

  // The class extends ExternalResource so it can be used in JUnit 5. But JUnit 5 will only call
  // before() and after(), thus code cannot assume this method will be called.
  @Override
  public Statement apply(final Statement base, Description description) {
    return super.apply(new Statement() {
      @Override
      public void evaluate() throws Throwable {
        abruptShutdown = false;
        try {
          base.evaluate();
        } catch (Throwable t) {
          abruptShutdown = true;
          throw t;
        }
      }
    }, description);
  }

  /**
   * Releases all the registered resources.
   */
  @Override
  protected void after() {
    stopwatch.reset();
    stopwatch.start();

    InterruptedException interrupted = null;
    if (!abruptShutdown) {
      for (Resource resource : Lists.reverse(resources)) {
        resource.cleanUp();
      }

      for (int i = resources.size() - 1; i >= 0; i--) {
        try {
          boolean released = resources.get(i).awaitReleased(
              timeoutNanos - stopwatch.elapsed(TimeUnit.NANOSECONDS), TimeUnit.NANOSECONDS);
          if (released) {
            resources.remove(i);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          interrupted = e;
          break;
        }
      }
    }

    if (!resources.isEmpty()) {
      for (Resource resource : Lists.reverse(resources)) {
        resource.forceCleanUp();
      }

      try {
        if (interrupted != null) {
          throw new AssertionError(
              "Thread interrupted before resources gracefully released", interrupted);
        } else if (!abruptShutdown) {
          throw new AssertionError(
            "Resources could not be released in time at the end of test: " + resources);
        }
      } finally {
        resources.clear();
      }
    }
  }

  @VisibleForTesting
  interface Resource {
    void cleanUp();

    /**
     * Error already happened, try the best to clean up. Never throws.
     */
    void forceCleanUp();

    /**
     * Returns true if the resource is released in time.
     */
    boolean awaitReleased(long duration, TimeUnit timeUnit) throws InterruptedException;
  }

  private static final class ManagedChannelResource implements Resource {
    final ManagedChannel channel;

    ManagedChannelResource(ManagedChannel channel) {
      this.channel = channel;
    }

    @Override
    public void cleanUp() {
      channel.shutdown();
    }

    @Override
    public void forceCleanUp() {
      channel.shutdownNow();
    }

    @Override
    public boolean awaitReleased(long duration, TimeUnit timeUnit) throws InterruptedException {
      return channel.awaitTermination(duration, timeUnit);
    }

    @Override
    public String toString() {
      return channel.toString();
    }
  }

  private static final class ServerResource implements Resource {
    final Server server;

    ServerResource(Server server) {
      this.server = server;
    }

    @Override
    public void cleanUp() {
      server.shutdown();
    }

    @Override
    public void forceCleanUp() {
      server.shutdownNow();
    }

    @Override
    public boolean awaitReleased(long duration, TimeUnit timeUnit) throws InterruptedException {
      return server.awaitTermination(duration, timeUnit);
    }

    @Override
    public String toString() {
      return server.toString();
    }
  }
}
