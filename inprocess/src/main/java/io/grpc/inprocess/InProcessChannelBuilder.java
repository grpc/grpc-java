/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc.inprocess;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.inprocess.InProcessTransport.isEnabledSupportTracingMessageSizes;

import com.google.errorprone.annotations.DoNotCall;
import io.grpc.ChannelCredentials;
import io.grpc.ChannelLogger;
import io.grpc.ExperimentalApi;
import io.grpc.ForwardingChannelBuilder2;
import io.grpc.Internal;
import io.grpc.ManagedChannelBuilder;
import io.grpc.internal.ClientTransportFactory;
import io.grpc.internal.ConnectionClientTransport;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ManagedChannelImplBuilder;
import io.grpc.internal.ManagedChannelImplBuilder.ClientTransportFactoryBuilder;
import io.grpc.internal.SharedResourceHolder;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Builder for a channel that issues in-process requests. Clients identify the in-process server by
 * its name.
 *
 * <p>The channel is intended to be fully-featured, high performance, and useful in testing.
 *
 * <p>For usage examples, see {@link InProcessServerBuilder}.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/1783")
public final class InProcessChannelBuilder extends
    ForwardingChannelBuilder2<InProcessChannelBuilder> {

  /**
   * Create a channel builder that will connect to the server with the given name.
   *
   * @param name the identity of the server to connect to
   * @return a new builder
   */
  public static InProcessChannelBuilder forName(String name) {
    return forAddress(new InProcessSocketAddress(checkNotNull(name, "name")));
  }

  /**
   * Create a channel builder that will connect to the server referenced by the given target URI.
   * Only intended for use with a custom name resolver.
   *
   * @param target the identity of the server to connect to
   * @return a new builder
   */
  public static InProcessChannelBuilder forTarget(String target) {
    return new InProcessChannelBuilder(null, checkNotNull(target, "target"));
  }

  /**
   * Create a channel builder that will connect to the server referenced by the given address.
   *
   * @param address the address of the server to connect to
   * @return a new builder
   */
  public static InProcessChannelBuilder forAddress(SocketAddress address) {
    return new InProcessChannelBuilder(checkNotNull(address, "address"), null);
  }

  /**
   * Always fails.  Call {@link #forName} instead.
   */
  @DoNotCall("Unsupported. Use forName() instead")
  public static InProcessChannelBuilder forAddress(String name, int port) {
    throw new UnsupportedOperationException("call forName() instead");
  }

  private final ManagedChannelImplBuilder managedChannelImplBuilder;
  private ScheduledExecutorService scheduledExecutorService;
  private int maxInboundMetadataSize = Integer.MAX_VALUE;
  private boolean transportIncludeStatusCause = false;
  private long assumedMessageSize = -1;

  private InProcessChannelBuilder(@Nullable SocketAddress directAddress, @Nullable String target) {

    final class InProcessChannelTransportFactoryBuilder implements ClientTransportFactoryBuilder {
      @Override
      public ClientTransportFactory buildClientTransportFactory() {
        return buildTransportFactory();
      }
    }

    if (directAddress != null) {
      managedChannelImplBuilder = new ManagedChannelImplBuilder(directAddress, "localhost",
          new InProcessChannelTransportFactoryBuilder(), null);
    } else {
      managedChannelImplBuilder = new ManagedChannelImplBuilder(target,
          new InProcessChannelTransportFactoryBuilder(), null);
    }

    // In-process transport should not record its traffic to the stats module.
    // https://github.com/grpc/grpc-java/issues/2284
    managedChannelImplBuilder.setStatsRecordStartedRpcs(false);
    managedChannelImplBuilder.setStatsRecordFinishedRpcs(false);
    managedChannelImplBuilder.setStatsRecordRetryMetrics(false);
    if (!isEnabledSupportTracingMessageSizes) {
      managedChannelImplBuilder.disableRetry();
    }
  }

  @Internal
  @Override
  protected ManagedChannelBuilder<?> delegate() {
    return managedChannelImplBuilder;
  }

  @Override
  public InProcessChannelBuilder maxInboundMessageSize(int max) {
    // TODO(carl-mastrangelo): maybe throw an exception since this not enforced?
    return super.maxInboundMessageSize(max);
  }

  /**
   * Does nothing.
   */
  @Override
  public InProcessChannelBuilder useTransportSecurity() {
    return this;
  }

  /**
   * Does nothing.
   */
  @Override
  public InProcessChannelBuilder usePlaintext() {
    return this;
  }

  /** Does nothing. */
  @Override
  public InProcessChannelBuilder keepAliveTime(long keepAliveTime, TimeUnit timeUnit) {
    return this;
  }

  /** Does nothing. */
  @Override
  public InProcessChannelBuilder keepAliveTimeout(long keepAliveTimeout, TimeUnit timeUnit) {
    return this;
  }

  /** Does nothing. */
  @Override
  public InProcessChannelBuilder keepAliveWithoutCalls(boolean enable) {
    return this;
  }

  /**
   * Provides a custom scheduled executor service.
   *
   * <p>It's an optional parameter. If the user has not provided a scheduled executor service when
   * the channel is built, the builder will use a static cached thread pool.
   *
   * @return this
   *
   * @since 1.11.0
   */
  public InProcessChannelBuilder scheduledExecutorService(
      ScheduledExecutorService scheduledExecutorService) {
    this.scheduledExecutorService =
        checkNotNull(scheduledExecutorService, "scheduledExecutorService");
    return this;
  }

  /**
   * Sets the maximum size of metadata allowed to be received. {@code Integer.MAX_VALUE} disables
   * the enforcement. Defaults to no limit ({@code Integer.MAX_VALUE}).
   *
   * <p>There is potential for performance penalty when this setting is enabled, as the Metadata
   * must actually be serialized. Since the current implementation of Metadata pre-serializes, it's
   * currently negligible. But Metadata is free to change its implementation.
   *
   * @param bytes the maximum size of received metadata
   * @return this
   * @throws IllegalArgumentException if bytes is non-positive
   * @since 1.17.0
   */
  @Override
  public InProcessChannelBuilder maxInboundMetadataSize(int bytes) {
    checkArgument(bytes > 0, "maxInboundMetadataSize must be > 0");
    this.maxInboundMetadataSize = bytes;
    return this;
  }

  /**
   * Sets whether to include the cause with the status that is propagated
   * forward from the InProcessTransport. This was added to make debugging failing
   * tests easier by showing the cause of the status.
   *
   * <p>By default, this is set to false.
   * A default value of false maintains consistency with other transports which strip causal
   * information from the status to avoid leaking information to untrusted clients, and
   * to avoid sharing language-specific information with the client.
   * For the in-process implementation, this is not a concern.
   *
   * @param enable whether to include cause in status
   * @return this
   */
  public InProcessChannelBuilder propagateCauseWithStatus(boolean enable) {
    this.transportIncludeStatusCause = enable;
    return this;
  }

  /**
   * Assumes RPC messages are the specified size. This avoids serializing
   * messages for metrics and retry memory tracking. This can dramatically
   * improve performance when accurate message sizes are not needed and if
   * nothing else needs the serialized message.
   * @param assumedMessageSize length of InProcess transport's messageSize.
   * @return this
   * @throws IllegalArgumentException if assumedMessageSize is negative.
   */
  public InProcessChannelBuilder assumedMessageSize(long assumedMessageSize) {
    checkArgument(assumedMessageSize >= 0, "assumedMessageSize must be >= 0");
    this.assumedMessageSize = assumedMessageSize;
    return this;
  }

  ClientTransportFactory buildTransportFactory() {
    return new InProcessClientTransportFactory(scheduledExecutorService,
            maxInboundMetadataSize, transportIncludeStatusCause, assumedMessageSize);
  }

  void setStatsEnabled(boolean value) {
    this.managedChannelImplBuilder.setStatsEnabled(value);
  }

  /**
   * Creates InProcess transports. Exposed for internal use, as it should be private.
   */
  static final class InProcessClientTransportFactory implements ClientTransportFactory {
    private final ScheduledExecutorService timerService;
    private final boolean useSharedTimer;
    private final int maxInboundMetadataSize;
    private boolean closed;
    private final boolean includeCauseWithStatus;
    private long assumedMessageSize;

    private InProcessClientTransportFactory(
        @Nullable ScheduledExecutorService scheduledExecutorService,
        int maxInboundMetadataSize, boolean includeCauseWithStatus, long assumedMessageSize) {
      useSharedTimer = scheduledExecutorService == null;
      timerService = useSharedTimer
          ? SharedResourceHolder.get(GrpcUtil.TIMER_SERVICE) : scheduledExecutorService;
      this.maxInboundMetadataSize = maxInboundMetadataSize;
      this.includeCauseWithStatus = includeCauseWithStatus;
      this.assumedMessageSize = assumedMessageSize;
    }

    @Override
    public ConnectionClientTransport newClientTransport(
        SocketAddress addr, ClientTransportOptions options, ChannelLogger channelLogger) {
      if (closed) {
        throw new IllegalStateException("The transport factory is closed.");
      }
      // TODO(carl-mastrangelo): Pass channelLogger in.
      return new InProcessTransport(
          addr, maxInboundMetadataSize, options.getAuthority(), options.getUserAgent(),
          options.getEagAttributes(), includeCauseWithStatus, assumedMessageSize);
    }

    @Override
    public ScheduledExecutorService getScheduledExecutorService() {
      return timerService;
    }

    @Override
    public SwapChannelCredentialsResult swapChannelCredentials(ChannelCredentials channelCreds) {
      return null;
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      if (useSharedTimer) {
        SharedResourceHolder.release(GrpcUtil.TIMER_SERVICE, timerService);
      }
    }

    @Override
    public Collection<Class<? extends SocketAddress>> getSupportedSocketAddressTypes() {
      return Arrays.asList(InProcessSocketAddress.class, AnonymousInProcessSocketAddress.class);
    }
  }
}
