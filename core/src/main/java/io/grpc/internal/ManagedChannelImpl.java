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
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.internal.ServiceConfigInterceptor.HEDGING_POLICY_KEY;
import static io.grpc.internal.ServiceConfigInterceptor.RETRY_POLICY_KEY;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ClientStreamTracer;
import io.grpc.CompressorRegistry;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.Context;
import io.grpc.DecompressorRegistry;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalChannelz;
import io.grpc.InternalChannelz.ChannelStats;
import io.grpc.InternalChannelz.ChannelTrace;
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
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.NameResolver.ResolutionResult;
import io.grpc.NameResolverRegistry;
import io.grpc.ProxyDetector;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.AutoConfiguredLoadBalancerFactory.AutoConfiguredLoadBalancer;
import io.grpc.internal.ClientCallImpl.ClientTransportProvider;
import io.grpc.internal.RetriableStream.ChannelBufferMeter;
import io.grpc.internal.RetriableStream.Throttle;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
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
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/** A communication channel for making outgoing RPCs. */
@ThreadSafe
final class ManagedChannelImpl extends ManagedChannel implements
    InternalInstrumented<ChannelStats> {
  static final Logger logger = Logger.getLogger(ManagedChannelImpl.class.getName());

  // Matching this pattern means the target string is a URI target or at least intended to be one.
  // A URI target must be an absolute hierarchical URI.
  // From RFC 2396: scheme = alpha *( alpha | digit | "+" | "-" | "." )
  @VisibleForTesting
  static final Pattern URI_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*:/.*");

  static final long IDLE_TIMEOUT_MILLIS_DISABLE = -1;

  @VisibleForTesting
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

  private static final ServiceConfigHolder EMPTY_SERVICE_CONFIG =
      new ServiceConfigHolder(
          Collections.<String, Object>emptyMap(),
          ManagedChannelServiceConfig.empty());

  private final InternalLogId logId;
  private final String target;
  private final NameResolverRegistry nameResolverRegistry;
  private final NameResolver.Factory nameResolverFactory;
  private final NameResolver.Args nameResolverArgs;
  private final AutoConfiguredLoadBalancerFactory loadBalancerFactory;
  private final ClientTransportFactory transportFactory;
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
  /** The timout before entering idle mode. */
  private final long idleTimeoutMillis;

  private final ConnectivityStateManager channelStateManager = new ConnectivityStateManager();

  private final ServiceConfigInterceptor serviceConfigInterceptor;

  private final BackoffPolicy.Provider backoffPolicyProvider;

  /**
   * We delegate to this channel, so that we can have interceptors as necessary. If there aren't
   * any interceptors and the {@link io.grpc.BinaryLog} is {@code null} then this will just be a
   * {@link RealChannel}.
   */
  private final Channel interceptorChannel;
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
  private volatile boolean terminating;
  // Must be mutated from syncContext
  private volatile boolean terminated;
  private final CountDownLatch terminatedLatch = new CountDownLatch(1);

  private final CallTracer.Factory callTracerFactory;
  private final CallTracer channelCallTracer;
  private final ChannelTracer channelTracer;
  private final ChannelLogger channelLogger;
  private final InternalChannelz channelz;

  // Must be mutated and read from syncContext
  // a flag for doing channel tracing when flipped
  private ResolutionState lastResolutionState = ResolutionState.NO_RESOLUTION;
  // Must be mutated and read from constructor or syncContext
  // used for channel tracing when value changed
  private ServiceConfigHolder lastServiceConfig = EMPTY_SERVICE_CONFIG;
  @Nullable
  private final ServiceConfigHolder defaultServiceConfig;
  // Must be mutated and read from constructor or syncContext
  private boolean serviceConfigUpdated = false;
  private final boolean lookUpServiceConfig;

  // One instance per channel.
  private final ChannelBufferMeter channelBufferUsed = new ChannelBufferMeter();

  private final long perRpcBufferLimit;
  private final long channelBufferLimit;

  // Temporary false flag that can skip the retry code path.
  private final boolean retryEnabled;

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
      cancelNameResolverBackoff();
      nameResolver.shutdown();
      nameResolverStarted = false;
      if (channelIsActive) {
        nameResolver = getNameResolver(target, nameResolverFactory, nameResolverArgs);
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
    if (inUseStateAggregator.isInUse()) {
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

  // Run from syncContext
  @VisibleForTesting
  class DelayedNameResolverRefresh implements Runnable {
    @Override
    public void run() {
      scheduledNameResolverRefresh = null;
      refreshNameResolution();
    }
  }

  // Must be used from syncContext
  @Nullable private ScheduledHandle scheduledNameResolverRefresh;
  // The policy to control backoff between name resolution attempts. Non-null when an attempt is
  // scheduled. Must be used from syncContext
  @Nullable private BackoffPolicy nameResolverBackoffPolicy;

  // Must be run from syncContext
  private void cancelNameResolverBackoff() {
    syncContext.throwIfNotInThisSynchronizationContext();
    if (scheduledNameResolverRefresh != null) {
      scheduledNameResolverRefresh.cancel();
      scheduledNameResolverRefresh = null;
      nameResolverBackoffPolicy = null;
    }
  }

  /**
   * Force name resolution refresh to happen immediately and reset refresh back-off. Must be run
   * from syncContext.
   */
  private void refreshAndResetNameResolution() {
    syncContext.throwIfNotInThisSynchronizationContext();
    cancelNameResolverBackoff();
    refreshNameResolution();
  }

  private void refreshNameResolution() {
    syncContext.throwIfNotInThisSynchronizationContext();
    if (nameResolverStarted) {
      nameResolver.refresh();
    }
  }

  private final class ChannelTransportProvider implements ClientTransportProvider {
    @Override
    public ClientTransport get(PickSubchannelArgs args) {
      SubchannelPicker pickerCopy = subchannelPicker;
      if (shutdown.get()) {
        // If channel is shut down, delayedTransport is also shut down which will fail the stream
        // properly.
        return delayedTransport;
      }
      if (pickerCopy == null) {
        final class ExitIdleModeForTransport implements Runnable {
          @Override
          public void run() {
            exitIdleMode();
          }
        }

        syncContext.execute(new ExitIdleModeForTransport());
        return delayedTransport;
      }
      // There is no need to reschedule the idle timer here.
      //
      // pickerCopy != null, which means idle timer has not expired when this method starts.
      // Even if idle timer expires right after we grab pickerCopy, and it shuts down LoadBalancer
      // which calls Subchannel.shutdown(), the InternalSubchannel will be actually shutdown after
      // SUBCHANNEL_SHUTDOWN_DELAY_SECONDS, which gives the caller time to start RPC on it.
      //
      // In most cases the idle timer is scheduled to fire after the transport has created the
      // stream, which would have reported in-use state to the channel that would have cancelled
      // the idle timer.
      PickResult pickResult = pickerCopy.pickSubchannel(args);
      ClientTransport transport = GrpcUtil.getTransportFromPickResult(
          pickResult, args.getCallOptions().isWaitForReady());
      if (transport != null) {
        return transport;
      }
      return delayedTransport;
    }

    @Override
    public <ReqT> ClientStream newRetriableStream(
        final MethodDescriptor<ReqT, ?> method,
        final CallOptions callOptions,
        final Metadata headers,
        final Context context) {
      checkState(retryEnabled, "retry should be enabled");
      final Throttle throttle = lastServiceConfig.managedChannelServiceConfig.getRetryThrottling();
      final class RetryStream extends RetriableStream<ReqT> {
        RetryStream() {
          super(
              method,
              headers,
              channelBufferUsed,
              perRpcBufferLimit,
              channelBufferLimit,
              getCallExecutor(callOptions),
              transportFactory.getScheduledExecutorService(),
              callOptions.getOption(RETRY_POLICY_KEY),
              callOptions.getOption(HEDGING_POLICY_KEY),
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
        ClientStream newSubstream(ClientStreamTracer.Factory tracerFactory, Metadata newHeaders) {
          CallOptions newOptions = callOptions.withStreamTracerFactory(tracerFactory);
          ClientTransport transport =
              get(new PickSubchannelArgsImpl(method, newHeaders, newOptions));
          Context origContext = context.attach();
          try {
            return transport.newStream(method, newHeaders, newOptions);
          } finally {
            context.detach(origContext);
          }
        }
      }

      return new RetryStream();
    }
  }

  private final ClientTransportProvider transportProvider = new ChannelTransportProvider();

  private final Rescheduler idleTimer;

  ManagedChannelImpl(
      AbstractManagedChannelImplBuilder<?> builder,
      ClientTransportFactory clientTransportFactory,
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
    this.transportFactory =
        new CallCredentialsApplyingTransportFactory(clientTransportFactory, this.executor);
    this.scheduledExecutor =
        new RestrictedScheduledExecutor(transportFactory.getScheduledExecutorService());
    maxTraceEvents = builder.maxTraceEvents;
    channelTracer = new ChannelTracer(
        logId, builder.maxTraceEvents, timeProvider.currentTimeNanos(),
        "Channel for '" + target + "'");
    channelLogger = new ChannelLoggerImpl(channelTracer, timeProvider);
    this.nameResolverFactory = builder.getNameResolverFactory();
    ProxyDetector proxyDetector =
        builder.proxyDetector != null ? builder.proxyDetector : GrpcUtil.DEFAULT_PROXY_DETECTOR;
    this.retryEnabled = builder.retryEnabled && !builder.temporarilyDisableRetry;
    this.loadBalancerFactory = new AutoConfiguredLoadBalancerFactory(builder.defaultLbPolicy);
    this.offloadExecutorHolder =
        new ExecutorHolder(
            checkNotNull(builder.offloadExecutorPool, "offloadExecutorPool"));
    this.nameResolverRegistry = builder.nameResolverRegistry;
    ScParser serviceConfigParser =
        new ScParser(
            retryEnabled,
            builder.maxRetryAttempts,
            builder.maxHedgedAttempts,
            loadBalancerFactory,
            channelLogger);
    this.nameResolverArgs =
        NameResolver.Args.newBuilder()
            .setDefaultPort(builder.getDefaultPort())
            .setProxyDetector(proxyDetector)
            .setSynchronizationContext(syncContext)
            .setScheduledExecutorService(scheduledExecutor)
            .setServiceConfigParser(serviceConfigParser)
            .setChannelLogger(channelLogger)
            .setOffloadExecutor(
                // Avoid creating the offloadExecutor until it is first used
                new Executor() {
                  @Override
                  public void execute(Runnable command) {
                    offloadExecutorHolder.getExecutor().execute(command);
                  }
                })
            .build();
    this.nameResolver = getNameResolver(target, nameResolverFactory, nameResolverArgs);
    this.balancerRpcExecutorPool = checkNotNull(balancerRpcExecutorPool, "balancerRpcExecutorPool");
    this.balancerRpcExecutorHolder = new ExecutorHolder(balancerRpcExecutorPool);
    this.delayedTransport = new DelayedClientTransport(this.executor, this.syncContext);
    this.delayedTransport.start(delayedTransportListener);
    this.backoffPolicyProvider = backoffPolicyProvider;

    serviceConfigInterceptor = new ServiceConfigInterceptor(retryEnabled);
    if (builder.defaultServiceConfig != null) {
      ConfigOrError parsedDefaultServiceConfig =
          serviceConfigParser.parseServiceConfig(builder.defaultServiceConfig);
      checkState(
          parsedDefaultServiceConfig.getError() == null,
          "Default config is invalid: %s",
          parsedDefaultServiceConfig.getError());
      this.defaultServiceConfig =
          new ServiceConfigHolder(
              builder.defaultServiceConfig,
              (ManagedChannelServiceConfig) parsedDefaultServiceConfig.getConfig());
      this.lastServiceConfig = this.defaultServiceConfig;
    } else {
      this.defaultServiceConfig = null;
    }
    this.lookUpServiceConfig = builder.lookUpServiceConfig;
    Channel channel = new RealChannel(nameResolver.getServiceAuthority());
    channel = ClientInterceptors.intercept(channel, serviceConfigInterceptor);
    if (builder.binlog != null) {
      channel = builder.binlog.wrapChannel(channel);
    }
    this.interceptorChannel = ClientInterceptors.intercept(channel, interceptors);
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
    if (builder.idleTimeoutMillis == IDLE_TIMEOUT_MILLIS_DISABLE) {
      this.idleTimeoutMillis = builder.idleTimeoutMillis;
    } else {
      checkArgument(
          builder.idleTimeoutMillis
              >= AbstractManagedChannelImplBuilder.IDLE_MODE_MIN_TIMEOUT_MILLIS,
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
      handleServiceConfigUpdate();
    }
  }

  // May only be called in constructor or syncContext
  private void handleServiceConfigUpdate() {
    serviceConfigUpdated = true;
    serviceConfigInterceptor.handleUpdate(lastServiceConfig.managedChannelServiceConfig);
  }

  @VisibleForTesting
  static NameResolver getNameResolver(String target, NameResolver.Factory nameResolverFactory,
      NameResolver.Args nameResolverArgs) {
    // Finding a NameResolver. Try using the target string as the URI. If that fails, try prepending
    // "dns:///".
    URI targetUri = null;
    StringBuilder uriSyntaxErrors = new StringBuilder();
    try {
      targetUri = new URI(target);
      // For "localhost:8080" this would likely cause newNameResolver to return null, because
      // "localhost" is parsed as the scheme. Will fall into the next branch and try
      // "dns:///localhost:8080".
    } catch (URISyntaxException e) {
      // Can happen with ip addresses like "[::1]:1234" or 127.0.0.1:1234.
      uriSyntaxErrors.append(e.getMessage());
    }
    if (targetUri != null) {
      NameResolver resolver = nameResolverFactory.newNameResolver(targetUri, nameResolverArgs);
      if (resolver != null) {
        return resolver;
      }
      // "foo.googleapis.com:8080" cause resolver to be null, because "foo.googleapis.com" is an
      // unmapped scheme. Just fall through and will try "dns:///foo.googleapis.com:8080"
    }

    // If we reached here, the targetUri couldn't be used.
    if (!URI_PATTERN.matcher(target).matches()) {
      // It doesn't look like a URI target. Maybe it's an authority string. Try with the default
      // scheme from the factory.
      try {
        targetUri = new URI(nameResolverFactory.getDefaultScheme(), "", "/" + target, null);
      } catch (URISyntaxException e) {
        // Should not be possible.
        throw new IllegalArgumentException(e);
      }
      NameResolver resolver = nameResolverFactory.newNameResolver(targetUri, nameResolverArgs);
      if (resolver != null) {
        return resolver;
      }
    }
    throw new IllegalArgumentException(String.format(
        "cannot find a NameResolver for %s%s",
        target, uriSyntaxErrors.length() > 0 ? " (" + uriSyntaxErrors + ")" : ""));
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

    // Put gotoState(SHUTDOWN) as early into the syncContext's queue as possible.
    // delayedTransport.shutdown() may also add some tasks into the queue. But some things inside
    // delayedTransport.shutdown() like setting delayedTransport.shutdown = true are not run in the
    // syncContext's queue and should not be blocked, so we do not drain() immediately here.
    final class Shutdown implements Runnable {
      @Override
      public void run() {
        channelLogger.log(ChannelLogLevel.INFO, "Entering SHUTDOWN state");
        channelStateManager.gotoState(SHUTDOWN);
      }
    }

    syncContext.executeLater(new Shutdown());

    uncommittedRetriableStreamsRegistry.onShutdown(SHUTDOWN_STATUS);
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
    uncommittedRetriableStreamsRegistry.onShutdownNow(SHUTDOWN_NOW_STATUS);
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
    cancelIdleTimer(/* permanent= */ true);
    shutdownNameResolverAndLoadBalancer(false);
    final class PanicSubchannelPicker extends SubchannelPicker {
      private final PickResult panicPickResult =
          PickResult.withDrop(
              Status.INTERNAL.withDescription("Panic! This is a bug!").withCause(t));

      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return panicPickResult;
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(PanicSubchannelPicker.class)
            .add("panicPickResult", panicPickResult)
            .toString();
      }
    }

    updateSubchannelPicker(new PanicSubchannelPicker());
    channelLogger.log(ChannelLogLevel.ERROR, "PANIC! Entering TRANSIENT_FAILURE");
    channelStateManager.gotoState(TRANSIENT_FAILURE);
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
    // Set when the NameResolver is initially created. When we create a new NameResolver for the
    // same target, the new instance must have the same value.
    private final String authority;

    private RealChannel(String authority) {
      this.authority =  checkNotNull(authority, "authority");
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions) {
      return new ClientCallImpl<>(
          method,
          getCallExecutor(callOptions),
          callOptions,
          transportProvider,
          terminated ? null : transportFactory.getScheduledExecutorService(),
          channelCallTracer,
          retryEnabled)
          .setFullStreamDecompression(fullStreamDecompression)
          .setDecompressorRegistry(decompressorRegistry)
          .setCompressorRegistry(compressorRegistry);
    }

    @Override
    public String authority() {
      return authority;
    }
  }

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
      refreshAndResetNameResolution();
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
        if (scheduledNameResolverRefresh != null && scheduledNameResolverRefresh.isPending()) {
          checkState(nameResolverStarted, "name resolver must be started");
          refreshAndResetNameResolution();
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

  private class LbHelperImpl extends LoadBalancer.Helper {
    AutoConfiguredLoadBalancer lb;

    @Deprecated
    @Override
    public AbstractSubchannel createSubchannel(
        List<EquivalentAddressGroup> addressGroups, Attributes attrs) {
      logWarningIfNotInSyncContext("createSubchannel()");
      // TODO(ejona): can we be even stricter? Like loadBalancer == null?
      checkNotNull(addressGroups, "addressGroups");
      checkNotNull(attrs, "attrs");
      final SubchannelImpl subchannel = createSubchannelInternal(
          CreateSubchannelArgs.newBuilder()
              .setAddresses(addressGroups)
              .setAttributes(attrs)
              .build());

      final SubchannelStateListener listener =
          new LoadBalancer.SubchannelStateListener() {
            @Override
            public void onSubchannelState(ConnectivityStateInfo newState) {
              // Call LB only if it's not shutdown.  If LB is shutdown, lbHelper won't match.
              if (LbHelperImpl.this != ManagedChannelImpl.this.lbHelper) {
                return;
              }
              lb.handleSubchannelState(subchannel, newState);
            }
          };

      subchannel.internalStart(listener);
      return subchannel;
    }

    @Override
    public AbstractSubchannel createSubchannel(CreateSubchannelArgs args) {
      syncContext.throwIfNotInThisSynchronizationContext();
      return createSubchannelInternal(args);
    }

    private SubchannelImpl createSubchannelInternal(CreateSubchannelArgs args) {
      // TODO(ejona): can we be even stricter? Like loadBalancer == null?
      checkState(!terminated, "Channel is terminated");
      return new SubchannelImpl(args, this);
    }

    @Override
    public void updateBalancingState(
        final ConnectivityState newState, final SubchannelPicker newPicker) {
      checkNotNull(newState, "newState");
      checkNotNull(newPicker, "newPicker");
      logWarningIfNotInSyncContext("updateBalancingState()");
      final class UpdateBalancingState implements Runnable {
        @Override
        public void run() {
          if (LbHelperImpl.this != lbHelper) {
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
      logWarningIfNotInSyncContext("refreshNameResolution()");
      final class LoadBalancerRefreshNameResolution implements Runnable {
        @Override
        public void run() {
          refreshAndResetNameResolution();
        }
      }

      syncContext.execute(new LoadBalancerRefreshNameResolution());
    }

    @Deprecated
    @Override
    public void updateSubchannelAddresses(
        LoadBalancer.Subchannel subchannel, List<EquivalentAddressGroup> addrs) {
      checkArgument(subchannel instanceof SubchannelImpl,
          "subchannel must have been returned from createSubchannel");
      logWarningIfNotInSyncContext("updateSubchannelAddresses()");
      ((InternalSubchannel) subchannel.getInternalSubchannel()).updateAddresses(addrs);
    }

    @Override
    public ManagedChannel createOobChannel(EquivalentAddressGroup addressGroup, String authority) {
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
          authority, balancerRpcExecutorPool, transportFactory.getScheduledExecutorService(),
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
          handleInternalSubchannelState(newState);
          oobChannel.handleSubchannelStateChange(newState);
        }
      }

      final InternalSubchannel internalSubchannel = new InternalSubchannel(
          Collections.singletonList(addressGroup),
          authority, userAgent, backoffPolicyProvider, transportFactory,
          transportFactory.getScheduledExecutorService(), stopwatchSupplier, syncContext,
          // All callback methods are run from syncContext
          new ManagedOobChannelCallback(),
          channelz,
          callTracerFactory.create(),
          subchannelTracer,
          subchannelLogId,
          subchannelLogger);
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

    @Override
    public void updateOobChannelAddresses(ManagedChannel channel, EquivalentAddressGroup eag) {
      checkArgument(channel instanceof OobChannel,
          "channel must have been returned from createOobChannel");
      ((OobChannel) channel).updateAddresses(eag);
    }

    @Override
    public String getAuthority() {
      return ManagedChannelImpl.this.authority();
    }

    @Deprecated
    @Override
    public NameResolver.Factory getNameResolverFactory() {
      return nameResolverFactory;
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
  }

  private final class NameResolverListener extends NameResolver.Listener2 {
    final LbHelperImpl helper;
    final NameResolver resolver;

    NameResolverListener(LbHelperImpl helperImpl, NameResolver resolver) {
      this.helper = checkNotNull(helperImpl, "helperImpl");
      this.resolver = checkNotNull(resolver, "resolver");
    }

    @Override
    public void onResult(final ResolutionResult resolutionResult) {
      final class NamesResolved implements Runnable {

        @SuppressWarnings({"ReferenceEquality", "deprecation"})
        @Override
        public void run() {
          List<EquivalentAddressGroup> servers = resolutionResult.getAddresses();
          Attributes attrs = resolutionResult.getAttributes();
          channelLogger.log(
              ChannelLogLevel.DEBUG, "Resolved address: {0}, config={1}", servers, attrs);
          ResolutionState lastResolutionStateCopy = lastResolutionState;

          if (lastResolutionState != ResolutionState.SUCCESS) {
            channelLogger.log(ChannelLogLevel.INFO, "Address resolved: {0}", servers);
            lastResolutionState = ResolutionState.SUCCESS;
          }

          nameResolverBackoffPolicy = null;
          ConfigOrError configOrError = resolutionResult.getServiceConfig();
          ServiceConfigHolder validServiceConfig = null;
          Status serviceConfigError = null;
          if (configOrError != null) {
            Map<String, ?> rawServiceConfig =
                resolutionResult.getAttributes().get(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG);
            validServiceConfig = configOrError.getConfig() == null
                ? null
                : new ServiceConfigHolder(
                    rawServiceConfig, (ManagedChannelServiceConfig) configOrError.getConfig());
            serviceConfigError = configOrError.getError();
          }

          ServiceConfigHolder effectiveServiceConfig;
          if (!lookUpServiceConfig) {
            if (validServiceConfig != null) {
              channelLogger.log(
                  ChannelLogLevel.INFO,
                  "Service config from name resolver discarded by channel settings");
            }
            effectiveServiceConfig =
                defaultServiceConfig == null ? EMPTY_SERVICE_CONFIG : defaultServiceConfig;
            attrs = attrs.toBuilder().discard(GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG).build();
          } else {
            // Try to use config if returned from name resolver
            // Otherwise, try to use the default config if available
            if (validServiceConfig != null) {
              effectiveServiceConfig = validServiceConfig;
            } else if (defaultServiceConfig != null) {
              effectiveServiceConfig = defaultServiceConfig;
              channelLogger.log(
                  ChannelLogLevel.INFO,
                  "Received no service config, using default service config");
            } else if (serviceConfigError != null) {
              if (!serviceConfigUpdated) {
                // First DNS lookup has invalid service config, and cannot fall back to default
                channelLogger.log(
                    ChannelLogLevel.INFO,
                    "Fallback to error due to invalid first service config without default config");
                onError(configOrError.getError());
                return;
              } else {
                effectiveServiceConfig = lastServiceConfig;
              }
            } else {
              effectiveServiceConfig = EMPTY_SERVICE_CONFIG;
            }
            if (!effectiveServiceConfig.equals(lastServiceConfig)) {
              channelLogger.log(
                  ChannelLogLevel.INFO,
                  "Service config changed{0}",
                  effectiveServiceConfig == EMPTY_SERVICE_CONFIG ? " to empty" : "");
              lastServiceConfig = effectiveServiceConfig;
            }

            try {
              // TODO(creamsoup): when `servers` is empty and lastResolutionStateCopy == SUCCESS
              //  and lbNeedAddress, it shouldn't call the handleServiceConfigUpdate. But,
              //  lbNeedAddress is not deterministic
              handleServiceConfigUpdate();
            } catch (RuntimeException re) {
              logger.log(
                  Level.WARNING,
                  "[" + getLogId() + "] Unexpected exception from parsing service config",
                  re);
            }
          }

          // Call LB only if it's not shutdown.  If LB is shutdown, lbHelper won't match.
          if (NameResolverListener.this.helper == ManagedChannelImpl.this.lbHelper) {
            Attributes effectiveAttrs = attrs;
            if (effectiveServiceConfig != validServiceConfig) {
              effectiveAttrs = attrs.toBuilder()
                  .set(
                      GrpcAttributes.NAME_RESOLVER_SERVICE_CONFIG,
                      effectiveServiceConfig.rawServiceConfig)
                  .build();
            }

            Status handleResult = helper.lb.tryHandleResolvedAddresses(
                ResolvedAddresses.newBuilder()
                    .setAddresses(servers)
                    .setAttributes(effectiveAttrs)
                    .setLoadBalancingPolicyConfig(
                        effectiveServiceConfig.managedChannelServiceConfig.getLoadBalancingConfig())
                    .build());

            if (!handleResult.isOk()) {
              if (servers.isEmpty() && lastResolutionStateCopy == ResolutionState.SUCCESS) {
                // lb doesn't expose that it needs address or not, because for some LB it is not
                // deterministic. Assuming lb needs address if LB returns error when the address is
                // empty and it is not the first resolution.
                scheduleExponentialBackOffInSyncContext();
              } else {
                handleErrorInSyncContext(handleResult.augmentDescription(resolver + " was used"));
              }
            }
          }
        }
      }

      syncContext.execute(new NamesResolved());
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
      if (lastResolutionState != ResolutionState.ERROR) {
        channelLogger.log(ChannelLogLevel.WARNING, "Failed to resolve name: {0}", error);
        lastResolutionState = ResolutionState.ERROR;
      }
      // Call LB only if it's not shutdown.  If LB is shutdown, lbHelper won't match.
      if (NameResolverListener.this.helper != ManagedChannelImpl.this.lbHelper) {
        return;
      }

      helper.lb.handleNameResolutionError(error);

      scheduleExponentialBackOffInSyncContext();
    }

    private void scheduleExponentialBackOffInSyncContext() {
      if (scheduledNameResolverRefresh != null && scheduledNameResolverRefresh.isPending()) {
        // The name resolver may invoke onError multiple times, but we only want to
        // schedule one backoff attempt
        // TODO(ericgribkoff) Update contract of NameResolver.Listener or decide if we
        // want to reset the backoff interval upon repeated onError() calls
        return;
      }
      if (nameResolverBackoffPolicy == null) {
        nameResolverBackoffPolicy = backoffPolicyProvider.get();
      }
      long delayNanos = nameResolverBackoffPolicy.nextBackoffNanos();
      channelLogger.log(
          ChannelLogLevel.DEBUG,
          "Scheduling DNS resolution backoff for {0} ns", delayNanos);
      scheduledNameResolverRefresh =
          syncContext.schedule(
              new DelayedNameResolverRefresh(), delayNanos, TimeUnit.NANOSECONDS,
              transportFactory .getScheduledExecutorService());
    }
  }

  private final class SubchannelImpl extends AbstractSubchannel {
    final CreateSubchannelArgs args;
    final LbHelperImpl helper;
    final InternalLogId subchannelLogId;
    final ChannelLoggerImpl subchannelLogger;
    final ChannelTracer subchannelTracer;
    SubchannelStateListener listener;
    InternalSubchannel subchannel;
    boolean started;
    boolean shutdown;
    ScheduledHandle delayedShutdownTask;

    SubchannelImpl(CreateSubchannelArgs args, LbHelperImpl helper) {
      this.args = checkNotNull(args, "args");
      this.helper = checkNotNull(helper, "helper");
      subchannelLogId = InternalLogId.allocate("Subchannel", /*details=*/ authority());
      subchannelTracer = new ChannelTracer(
          subchannelLogId, maxTraceEvents, timeProvider.currentTimeNanos(),
          "Subchannel for " + args.getAddresses());
      subchannelLogger = new ChannelLoggerImpl(subchannelTracer, timeProvider);
    }

    // This can be called either in or outside of syncContext
    // TODO(zhangkun83): merge it back into start() once the caller createSubchannel() is deleted.
    private void internalStart(final SubchannelStateListener listener) {
      checkState(!started, "already started");
      checkState(!shutdown, "already shutdown");
      started = true;
      this.listener = listener;
      // TODO(zhangkun): possibly remove the volatile of terminating when this whole method is
      // required to be called from syncContext
      if (terminating) {
        syncContext.execute(new Runnable() {
            @Override
            public void run() {
              listener.onSubchannelState(ConnectivityStateInfo.forNonError(SHUTDOWN));
            }
          });
        return;
      }
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
          handleInternalSubchannelState(newState);
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
          args.getAddresses(),
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
          subchannelLogger);

      channelTracer.reportEvent(new ChannelTrace.Event.Builder()
          .setDescription("Child Subchannel started")
          .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
          .setTimestampNanos(timeProvider.currentTimeNanos())
          .setSubchannelRef(internalSubchannel)
          .build());

      this.subchannel = internalSubchannel;
      // TODO(zhangkun83): no need to schedule on syncContext when this whole method is required
      // to be called from syncContext
      syncContext.execute(new Runnable() {
          @Override
          public void run() {
            channelz.addSubchannel(internalSubchannel);
            subchannels.add(internalSubchannel);
          }
        });
    }

    @Override
    public void start(SubchannelStateListener listener) {
      syncContext.throwIfNotInThisSynchronizationContext();
      internalStart(listener);
    }

    @Override
    InternalInstrumented<ChannelStats> getInstrumentedInternalSubchannel() {
      checkState(started, "not started");
      return subchannel;
    }

    @Override
    public void shutdown() {
      // TODO(zhangkun83): replace shutdown() with internalShutdown() to turn the warning into an
      // exception.
      logWarningIfNotInSyncContext("Subchannel.shutdown()");
      syncContext.execute(new Runnable() {
          @Override
          public void run() {
            internalShutdown();
          }
        });
    }

    private void internalShutdown() {
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
      logWarningIfNotInSyncContext("Subchannel.requestConnection()");
      checkState(started, "not started");
      subchannel.obtainActiveTransport();
    }

    @Override
    public List<EquivalentAddressGroup> getAllAddresses() {
      logWarningIfNotInSyncContext("Subchannel.getAllAddresses()");
      checkState(started, "not started");
      return subchannel.getAddressGroups();
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
          callTracerFactory.create());
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
      subchannel.updateAddresses(addrs);
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
    public void transportInUse(final boolean inUse) {
      inUseStateAggregator.updateObjectInUse(delayedTransport, inUse);
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
   */
  private static final class ExecutorHolder {
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

  @VisibleForTesting
  static final class ScParser extends NameResolver.ServiceConfigParser {

    private final boolean retryEnabled;
    private final int maxRetryAttemptsLimit;
    private final int maxHedgedAttemptsLimit;
    private final AutoConfiguredLoadBalancerFactory autoLoadBalancerFactory;
    private final ChannelLogger channelLogger;

    ScParser(
        boolean retryEnabled,
        int maxRetryAttemptsLimit,
        int maxHedgedAttemptsLimit,
        AutoConfiguredLoadBalancerFactory autoLoadBalancerFactory,
        ChannelLogger channelLogger) {
      this.retryEnabled = retryEnabled;
      this.maxRetryAttemptsLimit = maxRetryAttemptsLimit;
      this.maxHedgedAttemptsLimit = maxHedgedAttemptsLimit;
      this.autoLoadBalancerFactory =
          checkNotNull(autoLoadBalancerFactory, "autoLoadBalancerFactory");
      this.channelLogger = checkNotNull(channelLogger, "channelLogger");
    }

    @Override
    public ConfigOrError parseServiceConfig(Map<String, ?> rawServiceConfig) {
      try {
        Object loadBalancingPolicySelection;
        ConfigOrError choiceFromLoadBalancer =
            autoLoadBalancerFactory.parseLoadBalancerPolicy(rawServiceConfig, channelLogger);
        if (choiceFromLoadBalancer == null) {
          loadBalancingPolicySelection = null;
        } else if (choiceFromLoadBalancer.getError() != null) {
          return ConfigOrError.fromError(choiceFromLoadBalancer.getError());
        } else {
          loadBalancingPolicySelection = choiceFromLoadBalancer.getConfig();
        }
        return ConfigOrError.fromConfig(
            ManagedChannelServiceConfig.fromServiceConfig(
                rawServiceConfig,
                retryEnabled,
                maxRetryAttemptsLimit,
                maxHedgedAttemptsLimit,
                loadBalancingPolicySelection));
      } catch (RuntimeException e) {
        return ConfigOrError.fromError(
            Status.UNKNOWN.withDescription("failed to parse service config").withCause(e));
      }
    }
  }

  private void logWarningIfNotInSyncContext(String method) {
    try {
      syncContext.throwIfNotInThisSynchronizationContext();
    } catch (IllegalStateException e) {
      logger.log(Level.WARNING,
          method + " should be called from SynchronizationContext. "
          + "This warning will become an exception in a future release. "
          + "See https://github.com/grpc/grpc-java/issues/5015 for more details", e);
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

  // TODO(creamsoup) remove this class when AutoConfiguredLoadBalancerFactory doesn't require raw
  //  service config.
  private static final class ServiceConfigHolder {
    Map<String, ?> rawServiceConfig;
    ManagedChannelServiceConfig managedChannelServiceConfig;

    ServiceConfigHolder(
        Map<String, ?> rawServiceConfig, ManagedChannelServiceConfig managedChannelServiceConfig) {
      this.rawServiceConfig = checkNotNull(rawServiceConfig, "rawServiceConfig");
      this.managedChannelServiceConfig =
          checkNotNull(managedChannelServiceConfig, "managedChannelServiceConfig");
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      ServiceConfigHolder that = (ServiceConfigHolder) o;
      return Objects.equal(rawServiceConfig, that.rawServiceConfig)
          && Objects
          .equal(managedChannelServiceConfig, that.managedChannelServiceConfig);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(rawServiceConfig, managedChannelServiceConfig);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("rawServiceConfig", rawServiceConfig)
          .add("managedChannelServiceConfig", managedChannelServiceConfig)
          .toString();
    }
  }
}
