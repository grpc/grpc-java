/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.ClientStreamTracer.NAME_RESOLUTION_DELAYED;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.EquivalentAddressGroup.ATTR_AUTHORITY_OVERRIDE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ChannelCredentials;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientTransportFilter;
import io.grpc.CompressorRegistry;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.DecompressorRegistry;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ForwardingChannelBuilder2;
import io.grpc.ForwardingClientCall;
import io.grpc.Grpc;
import io.grpc.InternalChannelz;
import io.grpc.InternalChannelz.ChannelStats;
import io.grpc.InternalChannelz.ChannelTrace;
import io.grpc.InternalConfigSelector;
import io.grpc.InternalInstrumented;
import io.grpc.InternalLogId;
import io.grpc.InternalWithLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MetricInstrumentRegistry;
import io.grpc.MetricRecorder;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.NameResolver.ResolutionResult;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.ProxyDetector;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.AutoConfiguredLoadBalancerFactory.AutoConfiguredLoadBalancer;
import io.grpc.internal.ClientCallImpl.ClientStreamProvider;
import io.grpc.internal.ClientTransportFactory.SwapChannelCredentialsResult;
import io.grpc.internal.ManagedChannelImplBuilder.ClientTransportFactoryBuilder;
import io.grpc.internal.ManagedChannelImplBuilder.FixedPortProvider;
import io.grpc.internal.ManagedChannelServiceConfig.MethodInfo;
import io.grpc.internal.ManagedChannelServiceConfig.ServiceConfigConvertedSelector;
import io.grpc.internal.RetriableStream.ChannelBufferMeter;
import io.grpc.internal.RetriableStream.Throttle;
import io.grpc.internal.RetryingNameResolver.ResolutionResultListener;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/** A communication channel for making outgoing RPCs. */
@ThreadSafe
final class ManagedChannelImpl extends ManagedChannel implements
    InternalInstrumented<ChannelStats> {
  @VisibleForTesting
  static final Logger logger = Logger.getLogger(ManagedChannelImpl.class.getName());

  static final long IDLE_TIMEOUT_MILLIS_DISABLE = -1;

  static final long SUBCHANNEL_SHUTDOWN_DELAY_SECONDS = 5;

  @VisibleForTesting
  static final Status SHUTDOWN_NOW_STATUS =
      Status.UNAVAILABLE.withDescription("Channel shutdownNow invoked");

  @VisibleForTesting
  static final Status SHUTDOWN_STATUS =
      Status.UNAVAILABLE.withDescription("Channel shutdown invoked");

  @VisibleForTesting
  static final Status SUBCHANNEL_SHUTDOWN_STATUS =
      Status.UNAVAILABLE.withDescription("Subchannel shutdown invoked");

  private static final ManagedChannelServiceConfig EMPTY_SERVICE_CONFIG =
      ManagedChannelServiceConfig.empty();
  private static final InternalConfigSelector INITIAL_PENDING_SELECTOR =
      new InternalConfigSelector() {
        @Override
        public Result selectConfig(PickSubchannelArgs args) {
          throw new IllegalStateException("Resolution is pending");
        }
      };
  private static final LoadBalancer.PickDetailsConsumer NOOP_PICK_DETAILS_CONSUMER =
      new LoadBalancer.PickDetailsConsumer() {};

  private final InternalLogId logId;
  private final String target;
  @Nullable
  private final String authorityOverride;
  private final NameResolverRegistry nameResolverRegistry;
  private final URI targetUri;
  private final NameResolverProvider nameResolverProvider;
  private final NameResolver.Args nameResolverArgs;
  private final AutoConfiguredLoadBalancerFactory loadBalancerFactory;
  private final ClientTransportFactory originalTransportFactory;
  @Nullable
  private final ChannelCredentials originalChannelCreds;
  private final ClientTransportFactory transportFactory;
  private final ClientTransportFactory oobTransportFactory;
  private final RestrictedScheduledExecutor scheduledExecutor;
  private final Executor executor;
  private final ObjectPool<? extends Executor> executorPool;
  private final ObjectPool<? extends Executor> balancerRpcExecutorPool;
  private final ExecutorHolder balancerRpcExecutorHolder;
  private final ExecutorHolder offloadExecutorHolder;
  private final TimeProvider timeProvider;
  private final int maxTraceEvents;

  @VisibleForTesting
  final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          logger.log(
              Level.SEVERE,
              "[" + getLogId() + "] Uncaught exception in the SynchronizationContext. Panic!",
              e);
          panic(e);
        }
      });

  private boolean fullStreamDecompression;

  private final DecompressorRegistry decompressorRegistry;
  private final CompressorRegistry compressorRegistry;

  private final Supplier<Stopwatch> stopwatchSupplier;
  /** The timeout before entering idle mode. */
  private final long idleTimeoutMillis;

  private final ConnectivityStateManager channelStateManager = new ConnectivityStateManager();
  private final BackoffPolicy.Provider backoffPolicyProvider;

  /**
   * We delegate to this channel, so that we can have interceptors as necessary. If there aren't
   * any interceptors and the {@link io.grpc.BinaryLog} is {@code null} then this will just be a
   * {@link RealChannel}.
   */
  private final Channel interceptorChannel;

  private final List<ClientTransportFilter> transportFilters;
  @Nullable private final String userAgent;

  // Only null after channel is terminated. Must be assigned from the syncContext.
  private NameResolver nameResolver;

  // Must be accessed from the syncContext.
  private boolean nameResolverStarted;

  // null when channel is in idle mode.  Must be assigned from syncContext.
  @Nullable
  private LbHelperImpl lbHelper;

  // Must ONLY be assigned from updateSubchannelPicker(), which is called from syncContext.
  // null if channel is in idle mode.
  @Nullable
  private volatile SubchannelPicker subchannelPicker;

  // Must be accessed from the syncContext
  private boolean panicMode;

  // Must be mutated from syncContext
  // If any monitoring hook to be added later needs to get a snapshot of this Set, we could
  // switch to a ConcurrentHashMap.
  private final Set<InternalSubchannel> subchannels = new HashSet<>(16, .75f);

  // Must be accessed from syncContext
  @Nullable
  private Collection<RealChannel.PendingCall<?, ?>> pendingCalls;
  private final Object pendingCallsInUseObject = new Object();

  // Must be mutated from syncContext
  private final Set<OobChannel> oobChannels = new HashSet<>(1, .75f);

  // reprocess() must be run from syncContext
  private final DelayedClientTransport delayedTransport;
  private final UncommittedRetriableStreamsRegistry uncommittedRetriableStreamsRegistry
      = new UncommittedRetriableStreamsRegistry();

  // Shutdown states.
  //
  // Channel's shutdown process:
  // 1. shutdown(): stop accepting new calls from applications
  //   1a shutdown <- true
  //   1b subchannelPicker <- null
  //   1c delayedTransport.shutdown()
  // 2. delayedTransport terminated: stop stream-creation functionality
  //   2a terminating <- true
  //   2b loadBalancer.shutdown()
  //     * LoadBalancer will shutdown subchannels and OOB channels
  //   2c loadBalancer <- null
  //   2d nameResolver.shutdown()
  //   2e nameResolver <- null
  // 3. All subchannels and OOB channels terminated: Channel considered terminated

  private final AtomicBoolean shutdown = new AtomicBoolean(false);
  // Must only be mutated and read from syncContext
  private boolean shutdownNowed;
  // Must only be mutated from syncContext
  private boolean terminating;
  // Must be mutated from syncContext
  private volatile boolean terminated;
  private final CountDownLatch terminatedLatch = new CountDownLatch(1);

  private final CallTracer.Factory callTracerFactory;
  private final CallTracer channelCallTracer;
  private final ChannelTracer channelTracer;
  private final ChannelLogger channelLogger;
  private final InternalChannelz channelz;
  private final RealChannel realChannel;
  // Must be mutated and read from syncContext
  // a flag for doing channel tracing when flipped
  private ResolutionState lastResolutionState = ResolutionState.NO_RESOLUTION;
  // Must be mutated and read from constructor or syncContext
  // used for channel tracing when value changed
  private ManagedChannelServiceConfig lastServiceConfig = EMPTY_SERVICE_CONFIG;

  @Nullable
  private final ManagedChannelServiceConfig defaultServiceConfig;
  // Must be mutated and read from constructor or syncContext
  private boolean serviceConfigUpdated = false;
  private final boolean lookUpServiceConfig;

  // One instance per channel.
  private final ChannelBufferMeter channelBufferUsed = new ChannelBufferMeter();

  private final long perRpcBufferLimit;
  private final long channelBufferLimit;

  // Temporary false flag that can skip the retry code path.
  private final boolean retryEnabled;

  private final Deadline.Ticker ticker = Deadline.getSystemTicker();

  // Called from syncContext
  private final ManagedClientTransport.Listener delayedTransportListener =
      new DelayedTransportListener();

  // Must be called from syncContext
  private void maybeShutdownNowSubchannels() {
    if (shutdownNowed) {
      for (InternalSubchannel subchannel : subchannels) {
        subchannel.shutdownNow(SHUTDOWN_NOW_STATUS);
      }
      for (OobChannel oobChannel : oobChannels) {
        oobChannel.getInternalSubchannel().shutdownNow(SHUTDOWN_NOW_STATUS);
      }
    }
  }

  // Must be accessed from syncContext
  @VisibleForTesting
  final InUseStateAggregator<Object> inUseStateAggregator = new IdleModeStateAggregator();

  @Override
  public ListenableFuture<ChannelStats> getStats() {
    final SettableFuture<ChannelStats> ret = SettableFuture.create();
    final class StatsFetcher implements Runnable {
      @Override
      public void run() {
        ChannelStats.Builder builder = new InternalChannelz.ChannelStats.Builder();
        channelCallTracer.updateBuilder(builder);
        channelTracer.updateBuilder(builder);
        builder.setTarget(target).setState(channelStateManager.getState());
        List<InternalWithLogId> children = new ArrayList<>();
        children.addAll(subchannels);
        children.addAll(oobChannels);
        builder.setSubchannels(children);
        ret.set(builder.build());
      }
    }

    // subchannels and oobchannels can only be accessed from syncContext
    syncContext.execute(new StatsFetcher());
    return ret;
  }

  @Override
  public InternalLogId getLogId() {
    return logId;
  }

  // Run from syncContext
  private class IdleModeTimer implements Runnable {

    @Override
    public void run() {
      // Workaround timer scheduled while in idle mode. This can happen from handleNotInUse() after
      // an explicit enterIdleMode() by the user. Protecting here as other locations are a bit too
      // subtle to change rapidly to resolve the channel panic. See #8714
      if (lbHelper == null) {
        return;
      }
      enterIdleMode();
    }
  }

  // Must be called from syncContext
  private void shutdownNameResolverAndLoadBalancer(boolean channelIsActive) {
    syncContext.throwIfNotInThisSynchronizationContext();
    if (channelIsActive) {
      checkState(nameResolverStarted, "nameResolver is not started");
      checkState(lbHelper != null, "lbHelper is null");
    }
    if (nameResolver != null) {
      nameResolver.shutdown();
      nameResolverStarted = false;
      if (channelIsActive) {
        nameResolver = getNameResolver(
            targetUri, authorityOverride, nameResolverProvider, nameResolverArgs);
      } else {
        nameResolver = null;
      }
    }
    if (lbHelper != null) {
      lbHelper.lb.shutdown();
      lbHelper = null;
    }
    subchannelPicker = null;
  }

  /**
   * Make the channel exit idle mode, if it's in it.
   *
   * <p>Must be called from syncContext
   */
  @VisibleForTesting
  void exitIdleMode() {
    syncContext.throwIfNotInThisSynchronizationContext();
    if (shutdown.get() || panicMode) {
      return;
    }
    if (inUseStateAggregator.isInUse()) {
      // Cancel the timer now, so that a racing due timer will not put Channel on idleness
      // when the caller of exitIdleMode() is about to use the returned loadBalancer.
      cancelIdleTimer(false);
    } else {
      // exitIdleMode() may be called outside of inUseStateAggregator.handleNotInUse() while
      // isInUse() == false, in which case we still need to schedule the timer.
      rescheduleIdleTimer();
    }
    if (lbHelper != null) {
      return;
    }
    channelLogger.log(ChannelLogLevel.INFO, "Exiting idle mode");
    LbHelperImpl lbHelper = new LbHelperImpl();
    lbHelper.lb = loadBalancerFactory.newLoadBalancer(lbHelper);
    // Delay setting lbHelper until fully initialized, since loadBalancerFactory is user code and
    // may throw. We don't want to confuse our state, even if we will enter panic mode.
    this.lbHelper = lbHelper;

    channelStateManager.gotoState(CONNECTING);
    NameResolverListener listener = new NameResolverListener(lbHelper, nameResolver);
    nameResolver.start(listener);
    nameResolverStarted = true;
  }

  // Must be run from syncContext
  private void enterIdleMode() {
    // nameResolver and loadBalancer are guaranteed to be non-null.  If any of them were null,
    // either the idleModeTimer ran twice without exiting the idle mode, or the task in shutdown()
    // did not cancel idleModeTimer, or enterIdle() ran while shutdown or in idle, all of
    // which are bugs.
    shutdownNameResolverAndLoadBalancer(true);
    delayedTransport.reprocess(null);
    channelLogger.log(ChannelLogLevel.INFO, "Entering IDLE state");
    channelStateManager.gotoState(IDLE);
    // If the inUseStateAggregator still considers pending calls to be queued up or the delayed
    // transport to be holding some we need to exit idle mode to give these calls a chance to
    // be processed.
    if (inUseStateAggregator.anyObjectInUse(pendingCallsInUseObject, delayedTransport)) {
      exitIdleMode();
    }
  }

  // Must be run from syncContext
  private void cancelIdleTimer(boolean permanent) {
    idleTimer.cancel(permanent);
  }

  // Always run from syncContext
  private void rescheduleIdleTimer() {
    if (idleTimeoutMillis == IDLE_TIMEOUT_MILLIS_DISABLE) {
      return;
    }
    idleTimer.reschedule(idleTimeoutMillis, TimeUnit.MILLISECONDS);
  }

  /**
   * Force name resolution refresh to happen immediately. Must be run
   * from syncContext.
   */
  private void refreshNameResolution() {
    syncContext.throwIfNotInThisSynchronizationContext();
    if (nameResolverStarted) {
      nameResolver.refresh();
    }
  }

  private final class ChannelStreamProvider implements ClientStreamProvider {
    volatile Throttle throttle;

    @Override
    public ClientStream newStream(
        final MethodDescriptor<?, ?> method,
        final CallOptions callOptions,
        final Metadata headers,
        final Context context) {
      // There is no need to reschedule the idle timer here. If the channel isn't shut down, either
      // the delayed transport or a real transport will go in-use and cancel the idle timer.
      if (!retryEnabled) {
        ClientStreamTracer[] tracers = GrpcUtil.getClientStreamTracers(
            callOptions, headers, 0, /* isTransparentRetry= */ false);
        Context origContext = context.attach();
        try {
          return delayedTransport.newStream(method, headers, callOptions, tracers);
        } finally {
          context.detach(origContext);
        }
      } else {
        MethodInfo methodInfo = callOptions.getOption(MethodInfo.KEY);
        final RetryPolicy retryPolicy = methodInfo == null ? null : methodInfo.retryPolicy;
        final HedgingPolicy hedgingPolicy = methodInfo == null ? null : methodInfo.hedgingPolicy;
        final class RetryStream<ReqT> extends RetriableStream<ReqT> {
          @SuppressWarnings("unchecked")
          RetryStream() {
            super(
                (MethodDescriptor<ReqT, ?>) method,
                headers,
                channelBufferUsed,
                perRpcBufferLimit,
                channelBufferLimit,
                getCallExecutor(callOptions),
                transportFactory.getScheduledExecutorService(),
                retryPolicy,
                hedgingPolicy,
                throttle);
          }

          @Override
          Status prestart() {
            return uncommittedRetriableStreamsRegistry.add(this);
          }

          @Override
          void postCommit() {
            uncommittedRetriableStreamsRegistry.remove(this);
          }

          @Override
          ClientStream newSubstream(
              Metadata newHeaders, ClientStreamTracer.Factory factory, int previousAttempts,
              boolean isTransparentRetry) {
            CallOptions newOptions = callOptions.withStreamTracerFactory(factory);
            ClientStreamTracer[] tracers = GrpcUtil.getClientStreamTracers(
                newOptions, newHeaders, previousAttempts, isTransparentRetry);
            Context origContext = context.attach();
            try {
              return delayedTransport.newStream(method, newHeaders, newOptions, tracers);
            } finally {
              context.detach(origContext);
            }
          }
        }

        return new RetryStream<>();
      }
    }
  }

  private final ChannelStreamProvider transportProvider = new ChannelStreamProvider();

  private final Rescheduler idleTimer;
  private final MetricRecorder metricRecorder;

  ManagedChannelImpl(
      ManagedChannelImplBuilder builder,
      ClientTransportFactory clientTransportFactory,
      URI targetUri,
      NameResolverProvider nameResolverProvider,
      BackoffPolicy.Provider backoffPolicyProvider,
      ObjectPool<? extends Executor> balancerRpcExecutorPool,
      Supplier<Stopwatch> stopwatchSupplier,
      List<ClientInterceptor> interceptors,
      final TimeProvider timeProvider) {
    this.target = checkNotNull(builder.target, "target");
    this.logId = InternalLogId.allocate("Channel", target);
    this.timeProvider = checkNotNull(timeProvider, "timeProvider");
    this.executorPool = checkNotNull(builder.executorPool, "executorPool");
    this.executor = checkNotNull(executorPool.getObject(), "executor");
    this.originalChannelCreds = builder.channelCredentials;
    this.originalTransportFactory = clientTransportFactory;
    this.offloadExecutorHolder =
        new ExecutorHolder(checkNotNull(builder.offloadExecutorPool, "offloadExecutorPool"));
    this.transportFactory = new CallCredentialsApplyingTransportFactory(
        clientTransportFactory, builder.callCredentials, this.offloadExecutorHolder);
    this.oobTransportFactory = new CallCredentialsApplyingTransportFactory(
        clientTransportFactory, null, this.offloadExecutorHolder);
    this.scheduledExecutor =
        new RestrictedScheduledExecutor(transportFactory.getScheduledExecutorService());
    maxTraceEvents = builder.maxTraceEvents;
    channelTracer = new ChannelTracer(
        logId, builder.maxTraceEvents, timeProvider.currentTimeNanos(),
        "Channel for '" + target + "'");
    channelLogger = new ChannelLoggerImpl(channelTracer, timeProvider);
    ProxyDetector proxyDetector =
        builder.proxyDetector != null ? builder.proxyDetector : GrpcUtil.DEFAULT_PROXY_DETECTOR;
    this.retryEnabled = builder.retryEnabled;
    this.loadBalancerFactory = new AutoConfiguredLoadBalancerFactory(builder.defaultLbPolicy);
    this.nameResolverRegistry = builder.nameResolverRegistry;
    this.targetUri = checkNotNull(targetUri, "targetUri");
    this.nameResolverProvider = checkNotNull(nameResolverProvider, "nameResolverProvider");
    ScParser serviceConfigParser =
        new ScParser(
            retryEnabled,
            builder.maxRetryAttempts,
            builder.maxHedgedAttempts,
            loadBalancerFactory);
    this.authorityOverride = builder.authorityOverride;
    this.nameResolverArgs =
        NameResolver.Args.newBuilder()
            .setDefaultPort(builder.getDefaultPort())
            .setProxyDetector(proxyDetector)
            .setSynchronizationContext(syncContext)
            .setScheduledExecutorService(scheduledExecutor)
            .setServiceConfigParser(serviceConfigParser)
            .setChannelLogger(channelLogger)
            .setOffloadExecutor(this.offloadExecutorHolder)
            .setOverrideAuthority(this.authorityOverride)
            .build();
    this.nameResolver = getNameResolver(
        targetUri, authorityOverride, nameResolverProvider, nameResolverArgs);
    this.balancerRpcExecutorPool = checkNotNull(balancerRpcExecutorPool, "balancerRpcExecutorPool");
    this.balancerRpcExecutorHolder = new ExecutorHolder(balancerRpcExecutorPool);
    this.delayedTransport = new DelayedClientTransport(this.executor, this.syncContext);
    this.delayedTransport.start(delayedTransportListener);
    this.backoffPolicyProvider = backoffPolicyProvider;

    if (builder.defaultServiceConfig != null) {
      ConfigOrError parsedDefaultServiceConfig =
          serviceConfigParser.parseServiceConfig(builder.defaultServiceConfig);
      checkState(
          parsedDefaultServiceConfig.getError() == null,
          "Default config is invalid: %s",
          parsedDefaultServiceConfig.getError());
      this.defaultServiceConfig =
          (ManagedChannelServiceConfig) parsedDefaultServiceConfig.getConfig();
      this.transportProvider.throttle = this.defaultServiceConfig.getRetryThrottling();
    } else {
      this.defaultServiceConfig = null;
    }
    this.lookUpServiceConfig = builder.lookUpServiceConfig;
    realChannel = new RealChannel(nameResolver.getServiceAuthority());
    Channel channel = realChannel;
    if (builder.binlog != null) {
      channel = builder.binlog.wrapChannel(channel);
    }
    this.interceptorChannel = ClientInterceptors.intercept(channel, interceptors);
    this.transportFilters = new ArrayList<>(builder.transportFilters);
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
    if (builder.idleTimeoutMillis == IDLE_TIMEOUT_MILLIS_DISABLE) {
      this.idleTimeoutMillis = builder.idleTimeoutMillis;
    } else {
      checkArgument(
          builder.idleTimeoutMillis
              >= ManagedChannelImplBuilder.IDLE_MODE_MIN_TIMEOUT_MILLIS,
          "invalid idleTimeoutMillis %s", builder.idleTimeoutMillis);
      this.idleTimeoutMillis = builder.idleTimeoutMillis;
    }

    idleTimer = new Rescheduler(
        new IdleModeTimer(),
        syncContext,
        transportFactory.getScheduledExecutorService(),
        stopwatchSupplier.get());
    this.fullStreamDecompression = builder.fullStreamDecompression;
    this.decompressorRegistry = checkNotNull(builder.decompressorRegistry, "decompressorRegistry");
    this.compressorRegistry = checkNotNull(builder.compressorRegistry, "compressorRegistry");
    this.userAgent = builder.userAgent;

    this.channelBufferLimit = builder.retryBufferSize;
    this.perRpcBufferLimit = builder.perRpcBufferLimit;
    final class ChannelCallTracerFactory implements CallTracer.Factory {
      @Override
      public CallTracer create() {
        return new CallTracer(timeProvider);
      }
    }

    this.callTracerFactory = new ChannelCallTracerFactory();
    channelCallTracer = callTracerFactory.create();
    this.channelz = checkNotNull(builder.channelz);
    channelz.addRootChannel(this);

    if (!lookUpServiceConfig) {
      if (defaultServiceConfig != null) {
        channelLogger.log(
            ChannelLogLevel.INFO, "Service config look-up disabled, using default service config");
      }
      serviceConfigUpdated = true;
    }
    this.metricRecorder = new MetricRecorderImpl(builder.metricSinks,
        MetricInstrumentRegistry.getDefaultRegistry());
  }

  @VisibleForTesting
  static NameResolver getNameResolver(
      URI targetUri, @Nullable final String overrideAuthority,
      NameResolverProvider provider, NameResolver.Args nameResolverArgs) {
    NameResolver resolver = provider.newNameResolver(targetUri, nameResolverArgs);
    if (resolver == null) {
      throw new IllegalArgumentException("cannot create a NameResolver for " + targetUri);
    }

    // We wrap the name resolver in a RetryingNameResolver to give it the ability to retry failures.
    // TODO: After a transition period, all NameResolver implementations that need retry should use
    //       RetryingNameResolver directly and this step can be removed.
    NameResolver usedNameResolver = new RetryingNameResolver(resolver,
          new BackoffPolicyRetryScheduler(new ExponentialBackoffPolicy.Provider(),
              nameResolverArgs.getScheduledExecutorService(),
              nameResolverArgs.getSynchronizationContext()),
          nameResolverArgs.getSynchronizationContext());

    if (overrideAuthority == null) {
      return usedNameResolver;
    }

    return new ForwardingNameResolver(usedNameResolver) {
      @Override
      public String getServiceAuthority() {
        return overrideAuthority;
      }
    };
  }

  @VisibleForTesting
  InternalConfigSelector getConfigSelector() {
    return realChannel.configSelector.get();
  }
  
  @VisibleForTesting
  boolean hasThrottle() {
    return this.transportProvider.throttle != null;
  }

  /**
   * Initiates an orderly shutdown in which preexisting calls continue but new calls are immediately
   * cancelled.
   */
  @Override
  public ManagedChannelImpl shutdown() {
    channelLogger.log(ChannelLogLevel.DEBUG, "shutdown() called");
    if (!shutdown.compareAndSet(false, true)) {
      return this;
    }
    final class Shutdown implements Runnable {
      @Override
      public void run() {
        channelLogger.log(ChannelLogLevel.INFO, "Entering SHUTDOWN state");
        channelStateManager.gotoState(SHUTDOWN);
      }
    }

    syncContext.execute(new Shutdown());
    realChannel.shutdown();
    final class CancelIdleTimer implements Runnable {
      @Override
      public void run() {
        cancelIdleTimer(/* permanent= */ true);
      }
    }

    syncContext.execute(new CancelIdleTimer());
    return this;
  }

  /**
   * Initiates a forceful shutdown in which preexisting and new calls are cancelled. Although
   * forceful, the shutdown process is still not instantaneous; {@link #isTerminated()} will likely
   * return {@code false} immediately after this method returns.
   */
  @Override
  public ManagedChannelImpl shutdownNow() {
    channelLogger.log(ChannelLogLevel.DEBUG, "shutdownNow() called");
    shutdown();
    realChannel.shutdownNow();
    final class ShutdownNow implements Runnable {
      @Override
      public void run() {
        if (shutdownNowed) {
          return;
        }
        shutdownNowed = true;
        maybeShutdownNowSubchannels();
      }
    }

    syncContext.execute(new ShutdownNow());
    return this;
  }

  // Called from syncContext
  @VisibleForTesting
  void panic(final Throwable t) {
    if (panicMode) {
      // Preserve the first panic information
      return;
    }
    panicMode = true;
    try {
      cancelIdleTimer(/* permanent= */ true);
      shutdownNameResolverAndLoadBalancer(false);
    } finally {
      updateSubchannelPicker(new LoadBalancer.FixedResultPicker(PickResult.withDrop(
          Status.INTERNAL.withDescription("Panic! This is a bug!").withCause(t))));
      realChannel.updateConfigSelector(null);
      channelLogger.log(ChannelLogLevel.ERROR, "PANIC! Entering TRANSIENT_FAILURE");
      channelStateManager.gotoState(TRANSIENT_FAILURE);
    }
  }

  @VisibleForTesting
  boolean isInPanicMode() {
    return panicMode;
  }

  // Called from syncContext
  private void updateSubchannelPicker(SubchannelPicker newPicker) {
    subchannelPicker = newPicker;
    delayedTransport.reprocess(newPicker);
  }

  @Override
  public boolean isShutdown() {
    return shutdown.get();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return terminatedLatch.await(timeout, unit);
  }

  @Override
  public boolean isTerminated() {
    return terminated;
  }

  /*
   * Creates a new outgoing call on the channel.
   */
  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(MethodDescriptor<ReqT, RespT> method,
      CallOptions callOptions) {
    return interceptorChannel.newCall(method, callOptions);
  }

  @Override
  public String authority() {
    return interceptorChannel.authority();
  }

  private Executor getCallExecutor(CallOptions callOptions) {
    Executor executor = callOptions.getExecutor();
    if (executor == null) {
      executor = this.executor;
    }
    return executor;
  }

  private class RealChannel extends Channel {
    // Reference to null if no config selector is available from resolution result
    // Reference must be set() from syncContext
    private final AtomicReference<InternalConfigSelector> configSelector =
        new AtomicReference<>(INITIAL_PENDING_SELECTOR);
    // Set when the NameResolver is initially created. When we create a new NameResolver for the
    // same target, the new instance must have the same value.
    private final String authority;

    private final Channel clientCallImplChannel = new Channel() {
      @Override
      public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
          MethodDescriptor<RequestT, ResponseT> method, CallOptions callOptions) {
        return new ClientCallImpl<>(
            method,
            getCallExecutor(callOptions),
            callOptions,
            transportProvider,
            terminated ? null : transportFactory.getScheduledExecutorService(),
            channelCallTracer,
            null)
            .setFullStreamDecompression(fullStreamDecompression)
            .setDecompressorRegistry(decompressorRegistry)
            .setCompressorRegistry(compressorRegistry);
      }

      @Override
      public String authority() {
        return authority;
      }
    };

    private RealChannel(String authority) {
      this.authority =  checkNotNull(authority, "authority");
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions) {
      if (configSelector.get() != INITIAL_PENDING_SELECTOR) {
        return newClientCall(method, callOptions);
      }
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          exitIdleMode();
        }
      });
      if (configSelector.get() != INITIAL_PENDING_SELECTOR) {
        // This is an optimization for the case (typically with InProcessTransport) when name
        // resolution result is immediately available at this point. Otherwise, some users'
        // tests might observe slight behavior difference from earlier grpc versions.
        return newClientCall(method, callOptions);
      }
      if (shutdown.get()) {
        // Return a failing ClientCall.
        return new ClientCall<ReqT, RespT>() {
          @Override
          public void start(Listener<RespT> responseListener, Metadata headers) {
            responseListener.onClose(SHUTDOWN_STATUS, new Metadata());
          }

          @Override public void request(int numMessages) {}

          @Override public void cancel(@Nullable String message, @Nullable Throwable cause) {}

          @Override public void halfClose() {}

          @Override public void sendMessage(ReqT message) {}
        };
      }
      Context context = Context.current();
      final PendingCall<ReqT, RespT> pendingCall = new PendingCall<>(context, method, callOptions);
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (configSelector.get() == INITIAL_PENDING_SELECTOR) {
            if (pendingCalls == null) {
              pendingCalls = new LinkedHashSet<>();
              inUseStateAggregator.updateObjectInUse(pendingCallsInUseObject, true);
            }
            pendingCalls.add(pendingCall);
          } else {
            pendingCall.reprocess();
          }
        }
      });
      return pendingCall;
    }

    // Must run in SynchronizationContext.
    void updateConfigSelector(@Nullable InternalConfigSelector config) {
      InternalConfigSelector prevConfig = configSelector.get();
      configSelector.set(config);
      if (prevConfig == INITIAL_PENDING_SELECTOR && pendingCalls != null) {
        for (RealChannel.PendingCall<?, ?> pendingCall : pendingCalls) {
          pendingCall.reprocess();
        }
      }
    }

    // Must run in SynchronizationContext.
    void onConfigError() {
      if (configSelector.get() == INITIAL_PENDING_SELECTOR) {
        // Apply Default Service Config if initial name resolution fails.
        if (defaultServiceConfig != null) {
          updateConfigSelector(defaultServiceConfig.getDefaultConfigSelector());
          lastServiceConfig = defaultServiceConfig;
          channelLogger.log(ChannelLogLevel.ERROR,
              "Initial Name Resolution error, using default service config");
        } else {
          updateConfigSelector(null);
        }
      }
    }

    void shutdown() {
      final class RealChannelShutdown implements Runnable {
        @Override
        public void run() {
          if (pendingCalls == null) {
            if (configSelector.get() == INITIAL_PENDING_SELECTOR) {
              configSelector.set(null);
            }
            uncommittedRetriableStreamsRegistry.onShutdown(SHUTDOWN_STATUS);
          }
        }
      }

      syncContext.execute(new RealChannelShutdown());
    }

    void shutdownNow() {
      final class RealChannelShutdownNow implements Runnable {
        @Override
        public void run() {
          if (configSelector.get() == INITIAL_PENDING_SELECTOR) {
            configSelector.set(null);
          }
          if (pendingCalls != null) {
            for (RealChannel.PendingCall<?, ?> pendingCall : pendingCalls) {
              pendingCall.cancel("Channel is forcefully shutdown", null);
            }
          }
          uncommittedRetriableStreamsRegistry.onShutdownNow(SHUTDOWN_NOW_STATUS);
        }
      }

      syncContext.execute(new RealChannelShutdownNow());
    }

    @Override
    public String authority() {
      return authority;
    }

    private final class PendingCall<ReqT, RespT> extends DelayedClientCall<ReqT, RespT> {
      final Context context;
      final MethodDescriptor<ReqT, RespT> method;
      final CallOptions callOptions;
      private final long callCreationTime;

      PendingCall(
          Context context, MethodDescriptor<ReqT, RespT> method, CallOptions callOptions) {
        super(getCallExecutor(callOptions), scheduledExecutor, callOptions.getDeadline());
        this.context = context;
        this.method = method;
        this.callOptions = callOptions;
        this.callCreationTime = ticker.nanoTime();
      }

      /** Called when it's ready to create a real call and reprocess the pending call. */
      void reprocess() {
        ClientCall<ReqT, RespT> realCall;
        Context previous = context.attach();
        try {
          CallOptions delayResolutionOption = callOptions.withOption(NAME_RESOLUTION_DELAYED,
              ticker.nanoTime() - callCreationTime);
          realCall = newClientCall(method, delayResolutionOption);
        } finally {
          context.detach(previous);
        }
        Runnable toRun = setCall(realCall);
        if (toRun == null) {
          syncContext.execute(new PendingCallRemoval());
        } else {
          getCallExecutor(callOptions).execute(new Runnable() {
            @Override
            public void run() {
              toRun.run();
              syncContext.execute(new PendingCallRemoval());
            }
          });
        }
      }

      @Override
      protected void callCancelled() {
        super.callCancelled();
        syncContext.execute(new PendingCallRemoval());
      }

      final class PendingCallRemoval implements Runnable {
        @Override
        public void run() {
          if (pendingCalls != null) {
            pendingCalls.remove(PendingCall.this);
            if (pendingCalls.isEmpty()) {
              inUseStateAggregator.updateObjectInUse(pendingCallsInUseObject, false);
              pendingCalls = null;
              if (shutdown.get()) {
                uncommittedRetriableStreamsRegistry.onShutdown(SHUTDOWN_STATUS);
              }
            }
          }
        }
      }
    }

    private <ReqT, RespT> ClientCall<ReqT, RespT> newClientCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions) {
      InternalConfigSelector selector = configSelector.get();
      if (selector == null) {
        return clientCallImplChannel.newCall(method, callOptions);
      }
      if (selector instanceof ServiceConfigConvertedSelector) {
        MethodInfo methodInfo =
            ((ServiceConfigConvertedSelector) selector).config.getMethodConfig(method);
        if (methodInfo != null) {
          callOptions = callOptions.withOption(MethodInfo.KEY, methodInfo);
        }
        return clientCallImplChannel.newCall(method, callOptions);
      }
      return new ConfigSelectingClientCall<>(
          selector, clientCallImplChannel, executor, method, callOptions);
    }
  }

  /**
   * A client call for a given channel that applies a given config selector when it starts.
   */
  static final class ConfigSelectingClientCall<ReqT, RespT>
      extends ForwardingClientCall<ReqT, RespT> {

    private final InternalConfigSelector configSelector;
    private final Channel channel;
    private final Executor callExecutor;
    private final MethodDescriptor<ReqT, RespT> method;
    private final Context context;
    private CallOptions callOptions;

    private ClientCall<ReqT, RespT> delegate;

    ConfigSelectingClientCall(
        InternalConfigSelector configSelector, Channel channel, Executor channelExecutor,
        MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions) {
      this.configSelector = configSelector;
      this.channel = channel;
      this.method = method;
      this.callExecutor =
          callOptions.getExecutor() == null ? channelExecutor : callOptions.getExecutor();
      this.callOptions = callOptions.withExecutor(callExecutor);
      this.context = Context.current();
    }

    @Override
    protected ClientCall<ReqT, RespT> delegate() {
      return delegate;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void start(Listener<RespT> observer, Metadata headers) {
      PickSubchannelArgs args =
          new PickSubchannelArgsImpl(method, headers, callOptions, NOOP_PICK_DETAILS_CONSUMER);
      InternalConfigSelector.Result result = configSelector.selectConfig(args);
      Status status = result.getStatus();
      if (!status.isOk()) {
        executeCloseObserverInContext(observer,
            GrpcUtil.replaceInappropriateControlPlaneStatus(status));
        delegate = (ClientCall<ReqT, RespT>) NOOP_CALL;
        return;
      }
      ClientInterceptor interceptor = result.getInterceptor();
      ManagedChannelServiceConfig config = (ManagedChannelServiceConfig) result.getConfig();
      MethodInfo methodInfo = config.getMethodConfig(method);
      if (methodInfo != null) {
        callOptions = callOptions.withOption(MethodInfo.KEY, methodInfo);
      }
      if (interceptor != null) {
        delegate = interceptor.interceptCall(method, callOptions, channel);
      } else {
        delegate = channel.newCall(method, callOptions);
      }
      delegate.start(observer, headers);
    }

    private void executeCloseObserverInContext(
        final Listener<RespT> observer, final Status status) {
      class CloseInContext extends ContextRunnable {
        CloseInContext() {
          super(context);
        }

        @Override
        public void runInContext() {
          observer.onClose(status, new Metadata());
        }
      }

      callExecutor.execute(new CloseInContext());
    }

    @Override
    public void cancel(@Nullable String message, @Nullable Throwable cause) {
      if (delegate != null) {
        delegate.cancel(message, cause);
      }
    }
  }

  private static final ClientCall<Object, Object> NOOP_CALL = new ClientCall<Object, Object>() {
    @Override
    public void start(Listener<Object> responseListener, Metadata headers) {}

    @Override
    public void request(int numMessages) {}

    @Override
    public void cancel(String message, Throwable cause) {}

    @Override
    public void halfClose() {}

    @Override
    public void sendMessage(Object message) {}

    // Always returns {@code false}, since this is only used when the startup of the call fails.
    @Override
    public boolean isReady() {
      return false;
    }
  };

  /**
   * Terminate the channel if termination conditions are met.
   */
  // Must be run from syncContext
  private void maybeTerminateChannel() {
    if (terminated) {
      return;
    }
    if (shutdown.get() && subchannels.isEmpty() && oobChannels.isEmpty()) {
      channelLogger.log(ChannelLogLevel.INFO, "Terminated");
      channelz.removeRootChannel(this);
      executorPool.returnObject(executor);
      balancerRpcExecutorHolder.release();
      offloadExecutorHolder.release();
      // Release the transport factory so that it can deallocate any resources.
      transportFactory.close();

      terminated = true;
      terminatedLatch.countDown();
    }
  }

  // Must be called from syncContext
  private void handleInternalSubchannelState(ConnectivityStateInfo newState) {
    if (newState.getState() == TRANSIENT_FAILURE || newState.getState() == IDLE) {
      refreshNameResolution();
    }
  }

  @Override
  @SuppressWarnings("deprecation")
  public ConnectivityState getState(boolean requestConnection) {
    ConnectivityState savedChannelState = channelStateManager.getState();
    if (requestConnection && savedChannelState == IDLE) {
      final class RequestConnection implements Runnable {
        @Override
        public void run() {
          exitIdleMode();
          if (subchannelPicker != null) {
            subchannelPicker.requestConnection();
          }
          if (lbHelper != null) {
            lbHelper.lb.requestConnection();
          }
        }
      }

      syncContext.execute(new RequestConnection());
    }
    return savedChannelState;
  }

  @Override
  public void notifyWhenStateChanged(final ConnectivityState source, final Runnable callback) {
    final class NotifyStateChanged implements Runnable {
      @Override
      public void run() {
        channelStateManager.notifyWhenStateChanged(callback, executor, source);
      }
    }

    syncContext.execute(new NotifyStateChanged());
  }

  @Override
  public void resetConnectBackoff() {
    final class ResetConnectBackoff implements Runnable {
      @Override
      public void run() {
        if (shutdown.get()) {
          return;
        }
        if (nameResolverStarted) {
          refreshNameResolution();
        }
        for (InternalSubchannel subchannel : subchannels) {
          subchannel.resetConnectBackoff();
        }
        for (OobChannel oobChannel : oobChannels) {
          oobChannel.resetConnectBackoff();
        }
      }
    }

    syncContext.execute(new ResetConnectBackoff());
  }

  @Override
  public void enterIdle() {
    final class PrepareToLoseNetworkRunnable implements Runnable {
      @Override
      public void run() {
        if (shutdown.get() || lbHelper == null) {
          return;
        }
        cancelIdleTimer(/* permanent= */ false);
        enterIdleMode();
      }
    }

    syncContext.execute(new PrepareToLoseNetworkRunnable());
  }

  /**
   * A registry that prevents channel shutdown from killing existing retry attempts that are in
   * backoff.
   */
  private final class UncommittedRetriableStreamsRegistry {
    // TODO(zdapeng): This means we would acquire a lock for each new retry-able stream,
    // it's worthwhile to look for a lock-free approach.
    final Object lock = new Object();

    @GuardedBy("lock")
    Collection<ClientStream> uncommittedRetriableStreams = new HashSet<>();

    @GuardedBy("lock")
    Status shutdownStatus;

    void onShutdown(Status reason) {
      boolean shouldShutdownDelayedTransport = false;
      synchronized (lock) {
        if (shutdownStatus != null) {
          return;
        }
        shutdownStatus = reason;
        // Keep the delayedTransport open until there is no more uncommitted streams, b/c those
        // retriable streams, which may be in backoff and not using any transport, are already
        // started RPCs.
        if (uncommittedRetriableStreams.isEmpty()) {
          shouldShutdownDelayedTransport = true;
        }
      }

      if (shouldShutdownDelayedTransport) {
        delayedTransport.shutdown(reason);
      }
    }

    void onShutdownNow(Status reason) {
      onShutdown(reason);
      Collection<ClientStream> streams;

      synchronized (lock) {
        streams = new ArrayList<>(uncommittedRetriableStreams);
      }

      for (ClientStream stream : streams) {
        stream.cancel(reason);
      }
      delayedTransport.shutdownNow(reason);
    }

    /**
     * Registers a RetriableStream and return null if not shutdown, otherwise just returns the
     * shutdown Status.
     */
    @Nullable
    Status add(RetriableStream<?> retriableStream) {
      synchronized (lock) {
        if (shutdownStatus != null) {
          return shutdownStatus;
        }
        uncommittedRetriableStreams.add(retriableStream);
        return null;
      }
    }

    void remove(RetriableStream<?> retriableStream) {
      Status shutdownStatusCopy = null;

      synchronized (lock) {
        uncommittedRetriableStreams.remove(retriableStream);
        if (uncommittedRetriableStreams.isEmpty()) {
          shutdownStatusCopy = shutdownStatus;
          // Because retriable transport is long-lived, we take this opportunity to down-size the
          // hashmap.
          uncommittedRetriableStreams = new HashSet<>();
        }
      }

      if (shutdownStatusCopy != null) {
        delayedTransport.shutdown(shutdownStatusCopy);
      }
    }
  }

  private final class LbHelperImpl extends LoadBalancer.Helper {
    AutoConfiguredLoadBalancer lb;

    @Override
    public AbstractSubchannel createSubchannel(CreateSubchannelArgs args) {
      syncContext.throwIfNotInThisSynchronizationContext();
      // No new subchannel should be created after load balancer has been shutdown.
      checkState(!terminating, "Channel is being terminated");
      return new SubchannelImpl(args);
    }

    @Override
    public void updateBalancingState(
        final ConnectivityState newState, final SubchannelPicker newPicker) {
      syncContext.throwIfNotInThisSynchronizationContext();
      checkNotNull(newState, "newState");
      checkNotNull(newPicker, "newPicker");
      final class UpdateBalancingState implements Runnable {
        @Override
        public void run() {
          if (LbHelperImpl.this != lbHelper || panicMode) {
            return;
          }
          updateSubchannelPicker(newPicker);
          // It's not appropriate to report SHUTDOWN state from lb.
          // Ignore the case of newState == SHUTDOWN for now.
          if (newState != SHUTDOWN) {
            channelLogger.log(
                ChannelLogLevel.INFO, "Entering {0} state with picker: {1}", newState, newPicker);
            channelStateManager.gotoState(newState);
          }
        }
      }

      syncContext.execute(new UpdateBalancingState());
    }

    @Override
    public void refreshNameResolution() {
      syncContext.throwIfNotInThisSynchronizationContext();
      final class LoadBalancerRefreshNameResolution implements Runnable {
        @Override
        public void run() {
          ManagedChannelImpl.this.refreshNameResolution();
        }
      }

      syncContext.execute(new LoadBalancerRefreshNameResolution());
    }

    @Override
    public ManagedChannel createOobChannel(EquivalentAddressGroup addressGroup, String authority) {
      return createOobChannel(Collections.singletonList(addressGroup), authority);
    }

    @Override
    public ManagedChannel createOobChannel(List<EquivalentAddressGroup> addressGroup,
        String authority) {
      // TODO(ejona): can we be even stricter? Like terminating?
      checkState(!terminated, "Channel is terminated");
      long oobChannelCreationTime = timeProvider.currentTimeNanos();
      InternalLogId oobLogId = InternalLogId.allocate("OobChannel", /*details=*/ null);
      InternalLogId subchannelLogId =
          InternalLogId.allocate("Subchannel-OOB", /*details=*/ authority);
      ChannelTracer oobChannelTracer =
          new ChannelTracer(
              oobLogId, maxTraceEvents, oobChannelCreationTime,
              "OobChannel for " + addressGroup);
      final OobChannel oobChannel = new OobChannel(
          authority, balancerRpcExecutorPool, oobTransportFactory.getScheduledExecutorService(),
          syncContext, callTracerFactory.create(), oobChannelTracer, channelz, timeProvider);
      channelTracer.reportEvent(new ChannelTrace.Event.Builder()
          .setDescription("Child OobChannel created")
          .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
          .setTimestampNanos(oobChannelCreationTime)
          .setChannelRef(oobChannel)
          .build());
      ChannelTracer subchannelTracer =
          new ChannelTracer(subchannelLogId, maxTraceEvents, oobChannelCreationTime,
              "Subchannel for " + addressGroup);
      ChannelLogger subchannelLogger = new ChannelLoggerImpl(subchannelTracer, timeProvider);
      final class ManagedOobChannelCallback extends InternalSubchannel.Callback {
        @Override
        void onTerminated(InternalSubchannel is) {
          oobChannels.remove(oobChannel);
          channelz.removeSubchannel(is);
          oobChannel.handleSubchannelTerminated();
          maybeTerminateChannel();
        }

        @Override
        void onStateChange(InternalSubchannel is, ConnectivityStateInfo newState) {
          // TODO(chengyuanzhang): change to let LB policies explicitly manage OOB channel's
          //  state and refresh name resolution if necessary.
          handleInternalSubchannelState(newState);
          oobChannel.handleSubchannelStateChange(newState);
        }
      }

      final InternalSubchannel internalSubchannel = new InternalSubchannel(
          CreateSubchannelArgs.newBuilder().setAddresses(addressGroup).build(),
          authority, userAgent, backoffPolicyProvider, oobTransportFactory,
          oobTransportFactory.getScheduledExecutorService(), stopwatchSupplier, syncContext,
          // All callback methods are run from syncContext
          new ManagedOobChannelCallback(),
          channelz,
          callTracerFactory.create(),
          subchannelTracer,
          subchannelLogId,
          subchannelLogger,
          transportFilters);
      oobChannelTracer.reportEvent(new ChannelTrace.Event.Builder()
          .setDescription("Child Subchannel created")
          .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
          .setTimestampNanos(oobChannelCreationTime)
          .setSubchannelRef(internalSubchannel)
          .build());
      channelz.addSubchannel(oobChannel);
      channelz.addSubchannel(internalSubchannel);
      oobChannel.setSubchannel(internalSubchannel);
      final class AddOobChannel implements Runnable {
        @Override
        public void run() {
          if (terminating) {
            oobChannel.shutdown();
          }
          if (!terminated) {
            // If channel has not terminated, it will track the subchannel and block termination
            // for it.
            oobChannels.add(oobChannel);
          }
        }
      }

      syncContext.execute(new AddOobChannel());
      return oobChannel;
    }

    @Deprecated
    @Override
    public ManagedChannelBuilder<?> createResolvingOobChannelBuilder(String target) {
      return createResolvingOobChannelBuilder(target, new DefaultChannelCreds())
          // Override authority to keep the old behavior.
          // createResolvingOobChannelBuilder(String target) will be deleted soon.
          .overrideAuthority(getAuthority());
    }

    // TODO(creamsoup) prevent main channel to shutdown if oob channel is not terminated
    // TODO(zdapeng) register the channel as a subchannel of the parent channel in channelz.
    @Override
    public ManagedChannelBuilder<?> createResolvingOobChannelBuilder(
        final String target, final ChannelCredentials channelCreds) {
      checkNotNull(channelCreds, "channelCreds");

      final class ResolvingOobChannelBuilder
          extends ForwardingChannelBuilder2<ResolvingOobChannelBuilder> {
        final ManagedChannelBuilder<?> delegate;

        ResolvingOobChannelBuilder() {
          final ClientTransportFactory transportFactory;
          CallCredentials callCredentials;
          if (channelCreds instanceof DefaultChannelCreds) {
            transportFactory = originalTransportFactory;
            callCredentials = null;
          } else {
            SwapChannelCredentialsResult swapResult =
                originalTransportFactory.swapChannelCredentials(channelCreds);
            if (swapResult == null) {
              delegate = Grpc.newChannelBuilder(target, channelCreds);
              return;
            } else {
              transportFactory = swapResult.transportFactory;
              callCredentials = swapResult.callCredentials;
            }
          }
          ClientTransportFactoryBuilder transportFactoryBuilder =
              new ClientTransportFactoryBuilder() {
                @Override
                public ClientTransportFactory buildClientTransportFactory() {
                  return transportFactory;
                }
              };
          delegate = new ManagedChannelImplBuilder(
              target,
              channelCreds,
              callCredentials,
              transportFactoryBuilder,
              new FixedPortProvider(nameResolverArgs.getDefaultPort()))
              .nameResolverRegistry(nameResolverRegistry);
        }

        @Override
        protected ManagedChannelBuilder<?> delegate() {
          return delegate;
        }
      }

      checkState(!terminated, "Channel is terminated");

      @SuppressWarnings("deprecation")
      ResolvingOobChannelBuilder builder = new ResolvingOobChannelBuilder();

      return builder
          // TODO(zdapeng): executors should not outlive the parent channel.
          .executor(executor)
          .offloadExecutor(offloadExecutorHolder.getExecutor())
          .maxTraceEvents(maxTraceEvents)
          .proxyDetector(nameResolverArgs.getProxyDetector())
          .userAgent(userAgent);
    }

    @Override
    public ChannelCredentials getUnsafeChannelCredentials() {
      if (originalChannelCreds == null) {
        return new DefaultChannelCreds();
      }
      return originalChannelCreds;
    }

    @Override
    public void updateOobChannelAddresses(ManagedChannel channel, EquivalentAddressGroup eag) {
      updateOobChannelAddresses(channel, Collections.singletonList(eag));
    }

    @Override
    public void updateOobChannelAddresses(ManagedChannel channel,
        List<EquivalentAddressGroup> eag) {
      checkArgument(channel instanceof OobChannel,
          "channel must have been returned from createOobChannel");
      ((OobChannel) channel).updateAddresses(eag);
    }

    @Override
    public String getAuthority() {
      return ManagedChannelImpl.this.authority();
    }

    @Override
    public String getChannelTarget() {
      return targetUri.toString();
    }

    @Override
    public SynchronizationContext getSynchronizationContext() {
      return syncContext;
    }

    @Override
    public ScheduledExecutorService getScheduledExecutorService() {
      return scheduledExecutor;
    }

    @Override
    public ChannelLogger getChannelLogger() {
      return channelLogger;
    }

    @Override
    public NameResolver.Args getNameResolverArgs() {
      return nameResolverArgs;
    }

    @Override
    public NameResolverRegistry getNameResolverRegistry() {
      return nameResolverRegistry;
    }

    @Override
    public MetricRecorder getMetricRecorder() {
      return metricRecorder;
    }

    /**
     * A placeholder for channel creds if user did not specify channel creds for the channel.
     */
    // TODO(zdapeng): get rid of this class and let all ChannelBuilders always provide a non-null
    //     channel creds.
    final class DefaultChannelCreds extends ChannelCredentials {
      @Override
      public ChannelCredentials withoutBearerTokens() {
        return this;
      }
    }
  }

  final class NameResolverListener extends NameResolver.Listener2 {
    final LbHelperImpl helper;
    final NameResolver resolver;

    NameResolverListener(LbHelperImpl helperImpl, NameResolver resolver) {
      this.helper = checkNotNull(helperImpl, "helperImpl");
      this.resolver = checkNotNull(resolver, "resolver");
    }

    @Override
    public void onResult(final ResolutionResult resolutionResult) {
      final class NamesResolved implements Runnable {

        @Override
        public void run() {
          Status status = onResult2(resolutionResult);
          ResolutionResultListener resolutionResultListener = resolutionResult.getAttributes()
              .get(RetryingNameResolver.RESOLUTION_RESULT_LISTENER_KEY);
          resolutionResultListener.resolutionAttempted(status);
        }
      }

      syncContext.execute(new NamesResolved());
    }

    @SuppressWarnings("ReferenceEquality")
    @Override
    public Status onResult2(final ResolutionResult resolutionResult) {
      syncContext.throwIfNotInThisSynchronizationContext();
      if (ManagedChannelImpl.this.nameResolver != resolver) {
        return Status.OK;
      }

      StatusOr<List<EquivalentAddressGroup>> serversOrError =
          resolutionResult.getAddressesOrError();
      if (!serversOrError.hasValue()) {
        handleErrorInSyncContext(serversOrError.getStatus());
        return serversOrError.getStatus();
      }
      List<EquivalentAddressGroup> servers = serversOrError.getValue();
      channelLogger.log(
          ChannelLogLevel.DEBUG,
          "Resolved address: {0}, config={1}",
          servers,
          resolutionResult.getAttributes());

      if (lastResolutionState != ResolutionState.SUCCESS) {
        channelLogger.log(ChannelLogLevel.INFO, "Address resolved: {0}",
            servers);
        lastResolutionState = ResolutionState.SUCCESS;
      }
      ConfigOrError configOrError = resolutionResult.getServiceConfig();
      InternalConfigSelector resolvedConfigSelector =
          resolutionResult.getAttributes().get(InternalConfigSelector.KEY);
      ManagedChannelServiceConfig validServiceConfig =
          configOrError != null && configOrError.getConfig() != null
              ? (ManagedChannelServiceConfig) configOrError.getConfig()
              : null;
      Status serviceConfigError = configOrError != null ? configOrError.getError() : null;

      ManagedChannelServiceConfig effectiveServiceConfig;
      if (!lookUpServiceConfig) {
        if (validServiceConfig != null) {
          channelLogger.log(
              ChannelLogLevel.INFO,
              "Service config from name resolver discarded by channel settings");
        }
        effectiveServiceConfig =
            defaultServiceConfig == null ? EMPTY_SERVICE_CONFIG : defaultServiceConfig;
        if (resolvedConfigSelector != null) {
          channelLogger.log(
              ChannelLogLevel.INFO,
              "Config selector from name resolver discarded by channel settings");
        }
        realChannel.updateConfigSelector(effectiveServiceConfig.getDefaultConfigSelector());
      } else {
        // Try to use config if returned from name resolver
        // Otherwise, try to use the default config if available
        if (validServiceConfig != null) {
          effectiveServiceConfig = validServiceConfig;
          if (resolvedConfigSelector != null) {
            realChannel.updateConfigSelector(resolvedConfigSelector);
            if (effectiveServiceConfig.getDefaultConfigSelector() != null) {
              channelLogger.log(
                  ChannelLogLevel.DEBUG,
                  "Method configs in service config will be discarded due to presence of"
                      + "config-selector");
            }
          } else {
            realChannel.updateConfigSelector(effectiveServiceConfig.getDefaultConfigSelector());
          }
        } else if (defaultServiceConfig != null) {
          effectiveServiceConfig = defaultServiceConfig;
          realChannel.updateConfigSelector(effectiveServiceConfig.getDefaultConfigSelector());
          channelLogger.log(
              ChannelLogLevel.INFO,
              "Received no service config, using default service config");
        } else if (serviceConfigError != null) {
          if (!serviceConfigUpdated) {
            // First DNS lookup has invalid service config, and cannot fall back to default
            channelLogger.log(
                ChannelLogLevel.INFO,
                "Fallback to error due to invalid first service config without default config");
            // This error could be an "inappropriate" control plane error that should not bleed
            // through to client code using gRPC. We let them flow through here to the LB as
            // we later check for these error codes when investigating pick results in
            // GrpcUtil.getTransportFromPickResult().
            onError(configOrError.getError());
            return configOrError.getError();
          } else {
            effectiveServiceConfig = lastServiceConfig;
          }
        } else {
          effectiveServiceConfig = EMPTY_SERVICE_CONFIG;
          realChannel.updateConfigSelector(null);
        }
        if (!effectiveServiceConfig.equals(lastServiceConfig)) {
          channelLogger.log(
              ChannelLogLevel.INFO,
              "Service config changed{0}",
              effectiveServiceConfig == EMPTY_SERVICE_CONFIG ? " to empty" : "");
          lastServiceConfig = effectiveServiceConfig;
          transportProvider.throttle = effectiveServiceConfig.getRetryThrottling();
        }

        try {
          // TODO(creamsoup): when `serversOrError` is empty and lastResolutionStateCopy == SUCCESS
          //  and lbNeedAddress, it shouldn't call the handleServiceConfigUpdate. But,
          //  lbNeedAddress is not deterministic
          serviceConfigUpdated = true;
        } catch (RuntimeException re) {
          logger.log(
              Level.WARNING,
              "[" + getLogId() + "] Unexpected exception from parsing service config",
              re);
        }
      }

      Attributes effectiveAttrs = resolutionResult.getAttributes();
      // Call LB only if it's not shutdown.  If LB is shutdown, lbHelper won't match.
      if (NameResolverListener.this.helper == ManagedChannelImpl.this.lbHelper) {
        Attributes.Builder attrBuilder =
            effectiveAttrs.toBuilder().discard(InternalConfigSelector.KEY);
        Map<String, ?> healthCheckingConfig =
            effectiveServiceConfig.getHealthCheckingConfig();
        if (healthCheckingConfig != null) {
          attrBuilder
              .set(LoadBalancer.ATTR_HEALTH_CHECKING_CONFIG, healthCheckingConfig)
              .build();
        }
        Attributes attributes = attrBuilder.build();

        ResolvedAddresses.Builder resolvedAddresses = ResolvedAddresses.newBuilder()
            .setAddresses(serversOrError.getValue())
            .setAttributes(attributes)
            .setLoadBalancingPolicyConfig(effectiveServiceConfig.getLoadBalancingConfig());
        Status addressAcceptanceStatus = helper.lb.tryAcceptResolvedAddresses(
            resolvedAddresses.build());
        return addressAcceptanceStatus;
      }
      return Status.OK;
    }

    @Override
    public void onError(final Status error) {
      checkArgument(!error.isOk(), "the error status must not be OK");
      final class NameResolverErrorHandler implements Runnable {
        @Override
        public void run() {
          handleErrorInSyncContext(error);
        }
      }

      syncContext.execute(new NameResolverErrorHandler());
    }

    private void handleErrorInSyncContext(Status error) {
      logger.log(Level.WARNING, "[{0}] Failed to resolve name. status={1}",
          new Object[] {getLogId(), error});
      realChannel.onConfigError();
      if (lastResolutionState != ResolutionState.ERROR) {
        channelLogger.log(ChannelLogLevel.WARNING, "Failed to resolve name: {0}", error);
        lastResolutionState = ResolutionState.ERROR;
      }
      // Call LB only if it's not shutdown.  If LB is shutdown, lbHelper won't match.
      if (NameResolverListener.this.helper != ManagedChannelImpl.this.lbHelper) {
        return;
      }

      helper.lb.handleNameResolutionError(error);
    }
  }

  private final class SubchannelImpl extends AbstractSubchannel {
    final CreateSubchannelArgs args;
    final InternalLogId subchannelLogId;
    final ChannelLoggerImpl subchannelLogger;
    final ChannelTracer subchannelTracer;
    List<EquivalentAddressGroup> addressGroups;
    InternalSubchannel subchannel;
    boolean started;
    boolean shutdown;
    ScheduledHandle delayedShutdownTask;

    SubchannelImpl(CreateSubchannelArgs args) {
      checkNotNull(args, "args");
      addressGroups = args.getAddresses();
      if (authorityOverride != null) {
        List<EquivalentAddressGroup> eagsWithoutOverrideAttr =
            stripOverrideAuthorityAttributes(args.getAddresses());
        args = args.toBuilder().setAddresses(eagsWithoutOverrideAttr).build();
      }
      this.args = args;
      subchannelLogId = InternalLogId.allocate("Subchannel", /*details=*/ authority());
      subchannelTracer = new ChannelTracer(
          subchannelLogId, maxTraceEvents, timeProvider.currentTimeNanos(),
          "Subchannel for " + args.getAddresses());
      subchannelLogger = new ChannelLoggerImpl(subchannelTracer, timeProvider);
    }

    @Override
    public void start(final SubchannelStateListener listener) {
      syncContext.throwIfNotInThisSynchronizationContext();
      checkState(!started, "already started");
      checkState(!shutdown, "already shutdown");
      checkState(!terminating, "Channel is being terminated");
      started = true;
      final class ManagedInternalSubchannelCallback extends InternalSubchannel.Callback {
        // All callbacks are run in syncContext
        @Override
        void onTerminated(InternalSubchannel is) {
          subchannels.remove(is);
          channelz.removeSubchannel(is);
          maybeTerminateChannel();
        }

        @Override
        void onStateChange(InternalSubchannel is, ConnectivityStateInfo newState) {
          checkState(listener != null, "listener is null");
          listener.onSubchannelState(newState);
        }

        @Override
        void onInUse(InternalSubchannel is) {
          inUseStateAggregator.updateObjectInUse(is, true);
        }

        @Override
        void onNotInUse(InternalSubchannel is) {
          inUseStateAggregator.updateObjectInUse(is, false);
        }
      }

      final InternalSubchannel internalSubchannel = new InternalSubchannel(
          args,
          authority(),
          userAgent,
          backoffPolicyProvider,
          transportFactory,
          transportFactory.getScheduledExecutorService(),
          stopwatchSupplier,
          syncContext,
          new ManagedInternalSubchannelCallback(),
          channelz,
          callTracerFactory.create(),
          subchannelTracer,
          subchannelLogId,
          subchannelLogger,
          transportFilters);

      channelTracer.reportEvent(new ChannelTrace.Event.Builder()
          .setDescription("Child Subchannel started")
          .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
          .setTimestampNanos(timeProvider.currentTimeNanos())
          .setSubchannelRef(internalSubchannel)
          .build());

      this.subchannel = internalSubchannel;
      channelz.addSubchannel(internalSubchannel);
      subchannels.add(internalSubchannel);
    }

    @Override
    InternalInstrumented<ChannelStats> getInstrumentedInternalSubchannel() {
      checkState(started, "not started");
      return subchannel;
    }

    @Override
    public void shutdown() {
      syncContext.throwIfNotInThisSynchronizationContext();
      if (subchannel == null) {
        // start() was not successful
        shutdown = true;
        return;
      }
      if (shutdown) {
        if (terminating && delayedShutdownTask != null) {
          // shutdown() was previously called when terminating == false, thus a delayed shutdown()
          // was scheduled.  Now since terminating == true, We should expedite the shutdown.
          delayedShutdownTask.cancel();
          delayedShutdownTask = null;
          // Will fall through to the subchannel.shutdown() at the end.
        } else {
          return;
        }
      } else {
        shutdown = true;
      }
      // Add a delay to shutdown to deal with the race between 1) a transport being picked and
      // newStream() being called on it, and 2) its Subchannel is shut down by LoadBalancer (e.g.,
      // because of address change, or because LoadBalancer is shutdown by Channel entering idle
      // mode). If (2) wins, the app will see a spurious error. We work around this by delaying
      // shutdown of Subchannel for a few seconds here.
      //
      // TODO(zhangkun83): consider a better approach
      // (https://github.com/grpc/grpc-java/issues/2562).
      if (!terminating) {
        final class ShutdownSubchannel implements Runnable {
          @Override
          public void run() {
            subchannel.shutdown(SUBCHANNEL_SHUTDOWN_STATUS);
          }
        }

        delayedShutdownTask = syncContext.schedule(
            new LogExceptionRunnable(new ShutdownSubchannel()),
            SUBCHANNEL_SHUTDOWN_DELAY_SECONDS, TimeUnit.SECONDS,
            transportFactory.getScheduledExecutorService());
        return;
      }
      // When terminating == true, no more real streams will be created. It's safe and also
      // desirable to shutdown timely.
      subchannel.shutdown(SHUTDOWN_STATUS);
    }

    @Override
    public void requestConnection() {
      syncContext.throwIfNotInThisSynchronizationContext();
      checkState(started, "not started");
      subchannel.obtainActiveTransport();
    }

    @Override
    public List<EquivalentAddressGroup> getAllAddresses() {
      syncContext.throwIfNotInThisSynchronizationContext();
      checkState(started, "not started");
      return addressGroups;
    }

    @Override
    public Attributes getAttributes() {
      return args.getAttributes();
    }

    @Override
    public String toString() {
      return subchannelLogId.toString();
    }

    @Override
    public Channel asChannel() {
      checkState(started, "not started");
      return new SubchannelChannel(
          subchannel, balancerRpcExecutorHolder.getExecutor(),
          transportFactory.getScheduledExecutorService(),
          callTracerFactory.create(),
          new AtomicReference<InternalConfigSelector>(null));
    }

    @Override
    public Object getInternalSubchannel() {
      checkState(started, "Subchannel is not started");
      return subchannel;
    }

    @Override
    public ChannelLogger getChannelLogger() {
      return subchannelLogger;
    }

    @Override
    public void updateAddresses(List<EquivalentAddressGroup> addrs) {
      syncContext.throwIfNotInThisSynchronizationContext();
      addressGroups = addrs;
      if (authorityOverride != null) {
        addrs = stripOverrideAuthorityAttributes(addrs);
      }
      subchannel.updateAddresses(addrs);
    }

    @Override
    public Attributes getConnectedAddressAttributes() {
      return subchannel.getConnectedAddressAttributes();
    }

    private List<EquivalentAddressGroup> stripOverrideAuthorityAttributes(
        List<EquivalentAddressGroup> eags) {
      List<EquivalentAddressGroup> eagsWithoutOverrideAttr = new ArrayList<>();
      for (EquivalentAddressGroup eag : eags) {
        EquivalentAddressGroup eagWithoutOverrideAttr = new EquivalentAddressGroup(
            eag.getAddresses(),
            eag.getAttributes().toBuilder().discard(ATTR_AUTHORITY_OVERRIDE).build());
        eagsWithoutOverrideAttr.add(eagWithoutOverrideAttr);
      }
      return Collections.unmodifiableList(eagsWithoutOverrideAttr);
    }
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("logId", logId.getId())
        .add("target", target)
        .toString();
  }

  /**
   * Called from syncContext.
   */
  private final class DelayedTransportListener implements ManagedClientTransport.Listener {
    @Override
    public void transportShutdown(Status s) {
      checkState(shutdown.get(), "Channel must have been shut down");
    }

    @Override
    public void transportReady() {
      // Don't care
    }

    @Override
    public Attributes filterTransport(Attributes attributes) {
      return attributes;
    }

    @Override
    public void transportInUse(final boolean inUse) {
      inUseStateAggregator.updateObjectInUse(delayedTransport, inUse);
      if (inUse) {
        // It's possible to be in idle mode while inUseStateAggregator is in-use, if one of the
        // subchannels is in use. But we should never be in idle mode when delayed transport is in
        // use.
        exitIdleMode();
      }
    }

    @Override
    public void transportTerminated() {
      checkState(shutdown.get(), "Channel must have been shut down");
      terminating = true;
      shutdownNameResolverAndLoadBalancer(false);
      // No need to call channelStateManager since we are already in SHUTDOWN state.
      // Until LoadBalancer is shutdown, it may still create new subchannels.  We catch them
      // here.
      maybeShutdownNowSubchannels();
      maybeTerminateChannel();
    }
  }

  /**
   * Must be accessed from syncContext.
   */
  private final class IdleModeStateAggregator extends InUseStateAggregator<Object> {
    @Override
    protected void handleInUse() {
      exitIdleMode();
    }

    @Override
    protected void handleNotInUse() {
      if (shutdown.get()) {
        return;
      }
      rescheduleIdleTimer();
    }
  }

  /**
   * Lazily request for Executor from an executor pool.
   * Also act as an Executor directly to simply run a cmd
   */
  @VisibleForTesting
  static final class ExecutorHolder implements Executor {
    private final ObjectPool<? extends Executor> pool;
    private Executor executor;

    ExecutorHolder(ObjectPool<? extends Executor> executorPool) {
      this.pool = checkNotNull(executorPool, "executorPool");
    }

    synchronized Executor getExecutor() {
      if (executor == null) {
        executor = checkNotNull(pool.getObject(), "%s.getObject()", executor);
      }
      return executor;
    }

    synchronized void release() {
      if (executor != null) {
        executor = pool.returnObject(executor);
      }
    }

    @Override
    public void execute(Runnable command) {
      getExecutor().execute(command);
    }
  }

  private static final class RestrictedScheduledExecutor implements ScheduledExecutorService {
    final ScheduledExecutorService delegate;

    private RestrictedScheduledExecutor(ScheduledExecutorService delegate) {
      this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
      return delegate.schedule(callable, delay, unit);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable cmd, long delay, TimeUnit unit) {
      return delegate.schedule(cmd, delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      return delegate.scheduleAtFixedRate(command, initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      return delegate.scheduleWithFixedDelay(command, initialDelay, delay, unit);
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
        throws InterruptedException {
      return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
        throws InterruptedException {
      return delegate.invokeAll(tasks);
    }

    @Override
    public <T> List<Future<T>> invokeAll(
        Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException {
      return delegate.invokeAll(tasks, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
        throws InterruptedException, ExecutionException {
      return delegate.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
      return delegate.invokeAny(tasks, timeout, unit);
    }

    @Override
    public boolean isShutdown() {
      return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return delegate.isTerminated();
    }

    @Override
    public void shutdown() {
      throw new UnsupportedOperationException("Restricted: shutdown() is not allowed");
    }

    @Override
    public List<Runnable> shutdownNow() {
      throw new UnsupportedOperationException("Restricted: shutdownNow() is not allowed");
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
      return delegate.submit(task);
    }

    @Override
    public Future<?> submit(Runnable task) {
      return delegate.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
      return delegate.submit(task, result);
    }

    @Override
    public void execute(Runnable command) {
      delegate.execute(command);
    }
  }

  /**
   * A ResolutionState indicates the status of last name resolution.
   */
  enum ResolutionState {
    NO_RESOLUTION,
    SUCCESS,
    ERROR
  }
}
