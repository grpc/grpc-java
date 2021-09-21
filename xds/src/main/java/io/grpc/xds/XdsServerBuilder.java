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
import static com.google.common.base.Preconditions.checkState;

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
import io.grpc.netty.NettyServerBuilder;
import io.grpc.xds.internal.sds.SdsProtocolNegotiators;
import io.grpc.xds.internal.sds.ServerWrapperForXds;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * A version of {@link ServerBuilder} to create xDS managed servers.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/7514")
public final class XdsServerBuilder extends ForwardingServerBuilder<XdsServerBuilder> {

  private final NettyServerBuilder delegate;
  private final int port;
  private XdsServingStatusListener xdsServingStatusListener;
  private AtomicBoolean isServerBuilt = new AtomicBoolean(false);

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
   * Unsupported call. Users should only use {@link #forPort(int, ServerCredentials)}.
   */
  @DoNotCall("Unsupported. Use forPort(int, ServerCredentials) instead")
  public static ServerBuilder<?> forPort(int port) {
    throw new UnsupportedOperationException(
        "Unsupported call - use forPort(int, ServerCredentials)");
  }

  /** Creates a gRPC server builder for the given port. */
  public static XdsServerBuilder forPort(int port, ServerCredentials serverCredentials) {
    NettyServerBuilder nettyDelegate = NettyServerBuilder.forPort(port, serverCredentials);
    return new XdsServerBuilder(nettyDelegate, port);
  }

  @Override
  public Server build() {
    return buildServer(new XdsClientWrapperForServerSds(port));
  }

  /**
   * Creates a Server using the given xdsClient.
   */
  @VisibleForTesting
  ServerWrapperForXds buildServer(
      XdsClientWrapperForServerSds xdsClient) {
    checkState(isServerBuilt.compareAndSet(false, true), "Server already built!");
    InternalNettyServerBuilder.eagAttributes(delegate, Attributes.newBuilder()
        .set(SdsProtocolNegotiators.SERVER_XDS_CLIENT, xdsClient)
        .build());
    return new ServerWrapperForXds(delegate, xdsClient, xdsServingStatusListener);
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
    boolean notServing;

    DefaultListener(String prefix) {
      logger = Logger.getLogger(DefaultListener.class.getName());
      this.prefix = prefix;
      notServing = true;
    }

    /** Log calls to onServing() following a call to onNotServing() at WARNING level. */
    @Override
    public void onServing() {
      if (notServing) {
        notServing = false;
        logger.warning("[" + prefix + "] Entering serving state.");
      }
    }

    @Override
    public void onNotServing(Throwable throwable) {
      logger.warning("[" + prefix + "] " + throwable.getMessage());
      notServing = true;
    }
  }
}
