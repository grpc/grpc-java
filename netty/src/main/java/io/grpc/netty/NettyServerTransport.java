/*
 * Copyright 2014 The gRPC Authors
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

package io.grpc.netty;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalLogId;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.internal.ServerTransport;
import io.grpc.internal.ServerTransportListener;
import io.grpc.internal.TransportTracer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Netty-based server transport.
 */
class NettyServerTransport implements ServerTransport {
  // connectionLog is for connection related messages only
  private static final Logger connectionLog = Logger.getLogger(
      String.format("%s.connections", NettyServerTransport.class.getName()));

  // Some exceptions are not very useful and add too much noise to the log
  private static final ImmutableList<String> QUIET_EXCEPTIONS = ImmutableList.of(
      "NativeIoException" /* Netty exceptions */);

  private final InternalLogId logId;
  private final Channel channel;
  private final ChannelPromise channelUnused;
  private final ProtocolNegotiator protocolNegotiator;
  private final int maxStreams;
  // only accessed from channel event loop
  private NettyServerHandler grpcHandler;
  private ServerTransportListener listener;
  private boolean terminated;
  private final boolean autoFlowControl;
  private final int flowControlWindow;
  private final int maxMessageSize;
  private final int maxHeaderListSize;
  private final int softLimitHeaderListSize;
  private final long keepAliveTimeInNanos;
  private final long keepAliveTimeoutInNanos;
  private final long maxConnectionIdleInNanos;
  private final long maxConnectionAgeInNanos;
  private final long maxConnectionAgeGraceInNanos;
  private final boolean permitKeepAliveWithoutCalls;
  private final long permitKeepAliveTimeInNanos;
  private final int maxRstCount;
  private final long maxRstPeriodNanos;
  private final Attributes eagAttributes;
  private final List<? extends ServerStreamTracer.Factory> streamTracerFactories;
  private final TransportTracer transportTracer;

  NettyServerTransport(
      Channel channel,
      ChannelPromise channelUnused,
      ProtocolNegotiator protocolNegotiator,
      List<? extends ServerStreamTracer.Factory> streamTracerFactories,
      TransportTracer transportTracer,
      int maxStreams,
      boolean autoFlowControl,
      int flowControlWindow,
      int maxMessageSize,
      int maxHeaderListSize,
      int softLimitHeaderListSize,
      long keepAliveTimeInNanos,
      long keepAliveTimeoutInNanos,
      long maxConnectionIdleInNanos,
      long maxConnectionAgeInNanos,
      long maxConnectionAgeGraceInNanos,
      boolean permitKeepAliveWithoutCalls,
      long permitKeepAliveTimeInNanos,
      int maxRstCount,
      long maxRstPeriodNanos,
      Attributes eagAttributes) {
    this.channel = Preconditions.checkNotNull(channel, "channel");
    this.channelUnused = channelUnused;
    this.protocolNegotiator = Preconditions.checkNotNull(protocolNegotiator, "protocolNegotiator");
    this.streamTracerFactories =
        Preconditions.checkNotNull(streamTracerFactories, "streamTracerFactories");
    this.transportTracer = Preconditions.checkNotNull(transportTracer, "transportTracer");
    this.maxStreams = maxStreams;
    this.autoFlowControl = autoFlowControl;
    this.flowControlWindow = flowControlWindow;
    this.maxMessageSize = maxMessageSize;
    this.maxHeaderListSize = maxHeaderListSize;
    this.softLimitHeaderListSize = softLimitHeaderListSize;
    this.keepAliveTimeInNanos = keepAliveTimeInNanos;
    this.keepAliveTimeoutInNanos = keepAliveTimeoutInNanos;
    this.maxConnectionIdleInNanos = maxConnectionIdleInNanos;
    this.maxConnectionAgeInNanos = maxConnectionAgeInNanos;
    this.maxConnectionAgeGraceInNanos = maxConnectionAgeGraceInNanos;
    this.permitKeepAliveWithoutCalls = permitKeepAliveWithoutCalls;
    this.permitKeepAliveTimeInNanos = permitKeepAliveTimeInNanos;
    this.maxRstCount = maxRstCount;
    this.maxRstPeriodNanos = maxRstPeriodNanos;
    this.eagAttributes = Preconditions.checkNotNull(eagAttributes, "eagAttributes");
    SocketAddress remote = channel.remoteAddress();
    this.logId = InternalLogId.allocate(getClass(), remote != null ? remote.toString() : null);
  }

  public void start(ServerTransportListener listener) {
    Preconditions.checkState(this.listener == null, "Handler already registered");
    this.listener = listener;

    // Create the Netty handler for the pipeline.
    grpcHandler = createHandler(listener, channelUnused);

    // Notify when the channel closes.
    final class TerminationNotifier implements ChannelFutureListener {
      boolean done;

      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        if (!done) {
          done = true;
          notifyTerminated(grpcHandler.connectionError());
        }
      }
    }

    ChannelHandler negotiationHandler = protocolNegotiator.newHandler(grpcHandler);
    ChannelHandler bufferingHandler = new WriteBufferingAndExceptionHandler(negotiationHandler);

    ChannelFutureListener terminationNotifier = new TerminationNotifier();
    channelUnused.addListener(terminationNotifier);
    channel.closeFuture().addListener(terminationNotifier);

    channel.pipeline().addLast(bufferingHandler);
  }

  @Override
  public ScheduledExecutorService getScheduledExecutorService() {
    return channel.eventLoop();
  }

  @Override
  public void shutdown() {
    if (channel.isOpen()) {
      channel.close();
    }
  }

  @Override
  public void shutdownNow(Status reason) {
    if (channel.isOpen()) {
      channel.writeAndFlush(new ForcefulCloseCommand(reason));
    }
  }

  @Override
  public InternalLogId getLogId() {
    return logId;
  }

  /**
   * For testing purposes only.
   */
  Channel channel() {
    return channel;
  }

  /**
   * Accepts a throwable and returns the appropriate logging level. Uninteresting exceptions
   * should not clutter the log.
   */
  @VisibleForTesting
  static Level getLogLevel(Throwable t) {
    if (t.getClass().equals(IOException.class)
        || t.getClass().equals(SocketException.class)
        || QUIET_EXCEPTIONS.contains(t.getClass().getSimpleName())) {
      return Level.FINE;
    }
    return Level.INFO;
  }

  private void notifyTerminated(Throwable t) {
    if (t != null) {
      connectionLog.log(getLogLevel(t), "Transport failed", t);
    }
    if (!terminated) {
      terminated = true;
      listener.transportTerminated();
    }
  }

  @Override
  public ListenableFuture<SocketStats> getStats() {
    final SettableFuture<SocketStats> result = SettableFuture.create();
    if (channel.eventLoop().inEventLoop()) {
      // This is necessary, otherwise we will block forever if we get the future from inside
      // the event loop.
      result.set(getStatsHelper(channel));
      return result;
    }
    channel.eventLoop().submit(
        new Runnable() {
          @Override
          public void run() {
            result.set(getStatsHelper(channel));
          }
        })
        .addListener(
            new GenericFutureListener<Future<Object>>() {
              @Override
              public void operationComplete(Future<Object> future) throws Exception {
                if (!future.isSuccess()) {
                  result.setException(future.cause());
                }
              }
            });
    return result;
  }

  private SocketStats getStatsHelper(Channel ch) {
    Preconditions.checkState(ch.eventLoop().inEventLoop());
    return new SocketStats(
        transportTracer.getStats(),
        channel.localAddress(),
        channel.remoteAddress(),
        Utils.getSocketOptions(ch),
        grpcHandler == null ? null : grpcHandler.getSecurityInfo());

  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("logId", logId.getId())
        .add("channel", channel)
        .toString();
  }

  /**
   * Creates the Netty handler to be used in the channel pipeline.
   */
  private NettyServerHandler createHandler(
      ServerTransportListener transportListener, ChannelPromise channelUnused) {
    return NettyServerHandler.newHandler(
        transportListener,
        channelUnused,
        streamTracerFactories,
        transportTracer,
        maxStreams,
        autoFlowControl,
        flowControlWindow,
        maxHeaderListSize,
        softLimitHeaderListSize,
        maxMessageSize,
        keepAliveTimeInNanos,
        keepAliveTimeoutInNanos,
        maxConnectionIdleInNanos,
        maxConnectionAgeInNanos,
        maxConnectionAgeGraceInNanos,
        permitKeepAliveWithoutCalls,
        permitKeepAliveTimeInNanos,
        maxRstCount,
        maxRstPeriodNanos,
        eagAttributes);
  }
}
