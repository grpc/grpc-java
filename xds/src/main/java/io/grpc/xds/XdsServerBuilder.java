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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.xds.InternalXdsAttributes.ATTR_DRAIN_GRACE_NANOS;
import static io.grpc.xds.InternalXdsAttributes.ATTR_FILTER_CHAIN_SELECTOR_MANAGER;

import com.google.common.annotations.VisibleForTesting;
import com.google.errorprone.annotations.DoNotCall;
import io.grpc.Attributes;
import io.grpc.ExperimentalApi;
import io.grpc.ForwardingServerBuilder;
import io.grpc.Internal;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCredentials;
import io.grpc.netty.InternalNettyServerBuilder;
import io.grpc.netty.InternalNettyServerCredentials;
import io.grpc.netty.InternalProtocolNegotiator;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.xds.FilterChainMatchingProtocolNegotiators.FilterChainMatchingNegotiatorServerFactory;
import io.grpc.xds.XdsNameResolverProvider.XdsClientPoolFactory;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * A version of {@link ServerBuilder} to create xDS managed servers.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/7514")
public final class XdsServerBuilder extends ForwardingServerBuilder<XdsServerBuilder> {
  private static final long AS_LARGE_AS_INFINITE = TimeUnit.DAYS.toNanos(1000);

  private final NettyServerBuilder delegate;
  private final int port;
  private XdsServingStatusListener xdsServingStatusListener;
  private AtomicBoolean isServerBuilt = new AtomicBoolean(false);
  private final FilterRegistry filterRegistry = FilterRegistry.getDefaultRegistry();
  private XdsClientPoolFactory xdsClientPoolFactory =
          SharedXdsClientPoolProvider.getDefaultProvider();
  private long drainGraceTime = 10;
  private TimeUnit drainGraceTimeUnit = TimeUnit.MINUTES;

  private XdsServerBuilder(NettyServerBuilder nettyDelegate, int port) {
    this.delegate = nettyDelegate;
    this.port = port;
    xdsServingStatusListener = new DefaultListener("port:" + port);
  }

  @Override
  @Internal
  protected ServerBuilder<?> delegate() {
    checkState(!isServerBuilt.get(), "Server already built!");
    return delegate;
  }

  /** Set the {@link XdsServingStatusListener} to receive "serving" and "not serving" states. */
  public XdsServerBuilder xdsServingStatusListener(
      XdsServingStatusListener xdsServingStatusListener) {
    this.xdsServingStatusListener =
        checkNotNull(xdsServingStatusListener, "xdsServingStatusListener");
    return this;
  }

  /**
   * Sets the grace time when draining connections with outdated configuration. When an xDS config
   * update changes connection configuration, pre-existing connections stop accepting new RPCs to be
   * replaced by new connections. RPCs on those pre-existing connections have the grace time to
   * complete. RPCs that do not complete in time will be cancelled, allowing the connection to
   * terminate. {@code Long.MAX_VALUE} nano seconds or an unreasonably large value are considered
   * infinite. The default is 10 minutes.
   */
  public XdsServerBuilder drainGraceTime(long drainGraceTime, TimeUnit drainGraceTimeUnit) {
    checkArgument(drainGraceTime >= 0, "drain grace time must be non-negative: %s",
        drainGraceTime);
    checkNotNull(drainGraceTimeUnit, "drainGraceTimeUnit");
    if (drainGraceTimeUnit.toNanos(drainGraceTime) >= AS_LARGE_AS_INFINITE) {
      drainGraceTimeUnit = null;
    }
    this.drainGraceTime = drainGraceTime;
    this.drainGraceTimeUnit = drainGraceTimeUnit;
    return this;
  }

  @DoNotCall("Unsupported. Use forPort(int, ServerCredentials) instead")
  public static ServerBuilder<?> forPort(int port) {
    throw new UnsupportedOperationException(
            "Unsupported call - use forPort(int, ServerCredentials)");
  }

  /** Creates a gRPC server builder for the given port. */
  public static XdsServerBuilder forPort(int port, ServerCredentials serverCredentials) {
    checkNotNull(serverCredentials, "serverCredentials");
    InternalProtocolNegotiator.ServerFactory originalNegotiatorFactory =
            InternalNettyServerCredentials.toNegotiator(serverCredentials);
    ServerCredentials wrappedCredentials = InternalNettyServerCredentials.create(
            new FilterChainMatchingNegotiatorServerFactory(originalNegotiatorFactory));
    NettyServerBuilder nettyDelegate = NettyServerBuilder.forPort(port, wrappedCredentials);
    return new XdsServerBuilder(nettyDelegate, port);
  }

  @Override
  public Server build() {
    checkState(isServerBuilt.compareAndSet(false, true), "Server already built!");
    FilterChainSelectorManager filterChainSelectorManager = new FilterChainSelectorManager();
    Attributes.Builder builder = Attributes.newBuilder()
            .set(ATTR_FILTER_CHAIN_SELECTOR_MANAGER, filterChainSelectorManager);
    if (drainGraceTimeUnit != null) {
      builder.set(ATTR_DRAIN_GRACE_NANOS, drainGraceTimeUnit.toNanos(drainGraceTime));
    }
    InternalNettyServerBuilder.eagAttributes(delegate, builder.build());
    return new XdsServerWrapper("0.0.0.0:" + port, delegate, xdsServingStatusListener,
            filterChainSelectorManager, xdsClientPoolFactory, filterRegistry);
  }

  @VisibleForTesting
  XdsServerBuilder xdsClientPoolFactory(XdsClientPoolFactory xdsClientPoolFactory) {
    this.xdsClientPoolFactory = checkNotNull(xdsClientPoolFactory, "xdsClientPoolFactory");
    return this;
  }

  /**
   * Allows providing bootstrap override, useful for testing.
   */
  public XdsServerBuilder overrideBootstrapForTest(Map<String, ?> bootstrapOverride) {
    checkNotNull(bootstrapOverride, "bootstrapOverride");
    if (this.xdsClientPoolFactory == SharedXdsClientPoolProvider.getDefaultProvider()) {
      this.xdsClientPoolFactory = new SharedXdsClientPoolProvider();
    }
    this.xdsClientPoolFactory.setBootstrapOverride(bootstrapOverride);
    return this;
  }

  /**
   * Returns the delegate {@link NettyServerBuilder} to allow experimental level
   * transport-specific configuration. Note this API will always be experimental.
   */
  public ServerBuilder<?> transportBuilder() {
    return delegate;
  }

  /**
   * Applications can register this listener to receive "serving" and "not serving" states of
   * the server using {@link #xdsServingStatusListener(XdsServingStatusListener)}.
   */
  public interface XdsServingStatusListener {

    /** Callback invoked when server begins serving. */
    void onServing();

    /** Callback invoked when server is forced to be "not serving" due to an error.
     * @param throwable cause of the error
     */
    void onNotServing(Throwable throwable);
  }

  /** Default implementation of {@link XdsServingStatusListener} that logs at WARNING level. */
  private static class DefaultListener implements XdsServingStatusListener {
    private final Logger logger;
    private final String prefix;
    boolean notServingDueToError;

    DefaultListener(String prefix) {
      logger = Logger.getLogger(DefaultListener.class.getName());
      this.prefix = prefix;
    }

    /** Log calls to onServing() following a call to onNotServing() at WARNING level. */
    @Override
    public void onServing() {
      if (notServingDueToError) {
        notServingDueToError = false;
        logger.warning("[" + prefix + "] Entering serving state.");
      }
    }

    @Override
    public void onNotServing(Throwable throwable) {
      logger.warning("[" + prefix + "] " + throwable.getMessage());
      notServingDueToError = true;
    }
  }
}
