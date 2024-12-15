/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.rls;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Converter;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.base.Ticker;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LongCounterMetricInstrument;
import io.grpc.LongGaugeMetricInstrument;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MetricInstrumentRegistry;
import io.grpc.MetricRecorder.BatchCallback;
import io.grpc.MetricRecorder.BatchRecorder;
import io.grpc.MetricRecorder.Registration;
import io.grpc.Status;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.lookup.v1.RouteLookupServiceGrpc;
import io.grpc.lookup.v1.RouteLookupServiceGrpc.RouteLookupServiceStub;
import io.grpc.rls.ChildLoadBalancerHelper.ChildLoadBalancerHelperProvider;
import io.grpc.rls.LbPolicyConfiguration.ChildLbStatusListener;
import io.grpc.rls.LbPolicyConfiguration.ChildPolicyWrapper;
import io.grpc.rls.LbPolicyConfiguration.RefCountedChildPolicyWrapperFactory;
import io.grpc.rls.LruCache.EvictionListener;
import io.grpc.rls.LruCache.EvictionType;
import io.grpc.rls.RlsProtoConverters.RouteLookupResponseConverter;
import io.grpc.rls.RlsProtoData.RouteLookupConfig;
import io.grpc.rls.RlsProtoData.RouteLookupRequest;
import io.grpc.rls.RlsProtoData.RouteLookupResponse;
import io.grpc.stub.StreamObserver;
import io.grpc.util.ForwardingLoadBalancerHelper;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A CachingRlsLbClient is a core implementation of RLS loadbalancer supports dynamic request
 * routing by fetching the decision from route lookup server. Every single request is routed by
 * the server's decision. To reduce the performance penalty, {@link LruCache} is used.
 */
@ThreadSafe
final class CachingRlsLbClient {

  private static final Converter<RouteLookupRequest, io.grpc.lookup.v1.RouteLookupRequest>
      REQUEST_CONVERTER = new RlsProtoConverters.RouteLookupRequestConverter().reverse();
  private static final Converter<RouteLookupResponse, io.grpc.lookup.v1.RouteLookupResponse>
      RESPONSE_CONVERTER = new RouteLookupResponseConverter().reverse();
  public static final long MIN_EVICTION_TIME_DELTA_NANOS = TimeUnit.SECONDS.toNanos(5);
  public static final int BYTES_PER_CHAR = 2;
  public static final int STRING_OVERHEAD_BYTES = 38;
  /** Minimum bytes for a Java Object. */
  public static final int OBJ_OVERHEAD_B = 16;

  private static final LongCounterMetricInstrument DEFAULT_TARGET_PICKS_COUNTER;
  private static final LongCounterMetricInstrument TARGET_PICKS_COUNTER;
  private static final LongCounterMetricInstrument FAILED_PICKS_COUNTER;
  private static final LongGaugeMetricInstrument CACHE_ENTRIES_GAUGE;
  private static final LongGaugeMetricInstrument CACHE_SIZE_GAUGE;
  private final Registration gaugeRegistration;
  private final String metricsInstanceUuid = UUID.randomUUID().toString();

  // All cache status changes (pending, backoff, success) must be under this lock
  private final Object lock = new Object();
  // LRU cache based on access order (BACKOFF and actual data will be here)
  @GuardedBy("lock")
  private final RlsAsyncLruCache linkedHashLruCache;
  private final Future<?> periodicCleaner;
  // any RPC on the fly will cached in this map
  @GuardedBy("lock")
  private final Map<RouteLookupRequest, PendingCacheEntry> pendingCallCache = new HashMap<>();

  private final ScheduledExecutorService scheduledExecutorService;
  private final Ticker ticker;
  private final Throttler throttler;

  private final LbPolicyConfiguration lbPolicyConfig;
  private final BackoffPolicy.Provider backoffProvider;
  private final long maxAgeNanos;
  private final long staleAgeNanos;
  private final long callTimeoutNanos;

  private final RlsLbHelper helper;
  private final ManagedChannel rlsChannel;
  private final RouteLookupServiceStub rlsStub;
  private final RlsPicker rlsPicker;
  private final ResolvedAddressFactory childLbResolvedAddressFactory;
  @GuardedBy("lock")
  private final RefCountedChildPolicyWrapperFactory refCountedChildPolicyWrapperFactory;
  private final ChannelLogger logger;

  static {
    MetricInstrumentRegistry metricInstrumentRegistry
        = MetricInstrumentRegistry.getDefaultRegistry();
    DEFAULT_TARGET_PICKS_COUNTER = metricInstrumentRegistry.registerLongCounter(
        "grpc.lb.rls.default_target_picks",
        "EXPERIMENTAL. Number of LB picks sent to the default target", "{pick}",
        Arrays.asList("grpc.target", "grpc.lb.rls.server_target",
            "grpc.lb.rls.data_plane_target", "grpc.lb.pick_result"), Collections.emptyList(),
        false);
    TARGET_PICKS_COUNTER = metricInstrumentRegistry.registerLongCounter("grpc.lb.rls.target_picks",
        "EXPERIMENTAL. Number of LB picks sent to each RLS target. Note that if the default "
            + "target is also returned by the RLS server, RPCs sent to that target from the cache "
            + "will be counted in this metric, not in grpc.rls.default_target_picks.", "{pick}",
        Arrays.asList("grpc.target", "grpc.lb.rls.server_target", "grpc.lb.rls.data_plane_target",
            "grpc.lb.pick_result"), Collections.emptyList(),
        false);
    FAILED_PICKS_COUNTER = metricInstrumentRegistry.registerLongCounter("grpc.lb.rls.failed_picks",
        "EXPERIMENTAL. Number of LB picks failed due to either a failed RLS request or the "
            + "RLS channel being throttled", "{pick}",
        Arrays.asList("grpc.target", "grpc.lb.rls.server_target"),
        Collections.emptyList(), false);
    CACHE_ENTRIES_GAUGE = metricInstrumentRegistry.registerLongGauge("grpc.lb.rls.cache_entries",
        "EXPERIMENTAL. Number of entries in the RLS cache", "{entry}",
        Arrays.asList("grpc.target", "grpc.lb.rls.server_target", "grpc.lb.rls.instance_uuid"),
        Collections.emptyList(), false);
    CACHE_SIZE_GAUGE = metricInstrumentRegistry.registerLongGauge("grpc.lb.rls.cache_size",
        "EXPERIMENTAL. The current size of the RLS cache", "By",
        Arrays.asList("grpc.target", "grpc.lb.rls.server_target", "grpc.lb.rls.instance_uuid"),
        Collections.emptyList(), false);
  }

  private CachingRlsLbClient(Builder builder) {
    helper = new RlsLbHelper(checkNotNull(builder.helper, "helper"));
    scheduledExecutorService = helper.getScheduledExecutorService();
    lbPolicyConfig = checkNotNull(builder.lbPolicyConfig, "lbPolicyConfig");
    RouteLookupConfig rlsConfig = lbPolicyConfig.getRouteLookupConfig();
    maxAgeNanos = rlsConfig.maxAgeInNanos();
    staleAgeNanos = rlsConfig.staleAgeInNanos();
    callTimeoutNanos = rlsConfig.lookupServiceTimeoutInNanos();
    ticker = checkNotNull(builder.ticker, "ticker");
    throttler = checkNotNull(builder.throttler, "throttler");
    linkedHashLruCache =
        new RlsAsyncLruCache(
            rlsConfig.cacheSizeBytes(),
            new AutoCleaningEvictionListener(builder.evictionListener),
            ticker,
            helper);
    periodicCleaner =
        scheduledExecutorService.scheduleAtFixedRate(this::periodicClean, 1, 1, TimeUnit.MINUTES);
    logger = helper.getChannelLogger();
    String serverHost = null;
    try {
      serverHost = new URI(null, helper.getAuthority(), null, null, null).getHost();
    } catch (URISyntaxException ignore) {
      // handled by the following null check
    }
    if (serverHost == null) {
      logger.log(
          ChannelLogLevel.DEBUG, "Can not get hostname from authority: {0}", helper.getAuthority());
      serverHost = helper.getAuthority();
    }
    RlsRequestFactory requestFactory = new RlsRequestFactory(
        lbPolicyConfig.getRouteLookupConfig(), serverHost);
    rlsPicker = new RlsPicker(requestFactory, rlsConfig.lookupService());
    // It is safe to use helper.getUnsafeChannelCredentials() because the client authenticates the
    // RLS server using the same authority as the backends, even though the RLS server’s addresses
    // will be looked up differently than the backends; overrideAuthority(helper.getAuthority()) is
    // called to impose the authority security restrictions.
    ManagedChannelBuilder<?> rlsChannelBuilder = helper.createResolvingOobChannelBuilder(
        rlsConfig.lookupService(), helper.getUnsafeChannelCredentials());
    rlsChannelBuilder.overrideAuthority(helper.getAuthority());
    Map<String, ?> routeLookupChannelServiceConfig =
        lbPolicyConfig.getRouteLookupChannelServiceConfig();
    if (routeLookupChannelServiceConfig != null) {
      logger.log(
          ChannelLogLevel.DEBUG,
          "RLS channel service config: {0}",
          routeLookupChannelServiceConfig);
      rlsChannelBuilder.defaultServiceConfig(routeLookupChannelServiceConfig);
      rlsChannelBuilder.disableServiceConfigLookUp();
    }
    rlsChannel = rlsChannelBuilder.build();
    rlsStub = RouteLookupServiceGrpc.newStub(rlsChannel);
    childLbResolvedAddressFactory =
        checkNotNull(builder.resolvedAddressFactory, "resolvedAddressFactory");
    backoffProvider = builder.backoffProvider;
    ChildLoadBalancerHelperProvider childLbHelperProvider =
        new ChildLoadBalancerHelperProvider(helper, new SubchannelStateManagerImpl(), rlsPicker);
    refCountedChildPolicyWrapperFactory =
        new RefCountedChildPolicyWrapperFactory(
            lbPolicyConfig.getLoadBalancingPolicy(), childLbResolvedAddressFactory,
            childLbHelperProvider,
            new BackoffRefreshListener());

    gaugeRegistration = helper.getMetricRecorder()
        .registerBatchCallback(new BatchCallback() {
          @Override
          public void accept(BatchRecorder recorder) {
            int estimatedSize;
            long estimatedSizeBytes;
            synchronized (lock) {
              estimatedSize = linkedHashLruCache.estimatedSize();
              estimatedSizeBytes = linkedHashLruCache.estimatedSizeBytes();
            }
            recorder.recordLongGauge(CACHE_ENTRIES_GAUGE, estimatedSize,
                Arrays.asList(helper.getChannelTarget(), rlsConfig.lookupService(),
                    metricsInstanceUuid), Collections.emptyList());
            recorder.recordLongGauge(CACHE_SIZE_GAUGE, estimatedSizeBytes,
                Arrays.asList(helper.getChannelTarget(), rlsConfig.lookupService(),
                    metricsInstanceUuid), Collections.emptyList());
          }
        }, CACHE_ENTRIES_GAUGE, CACHE_SIZE_GAUGE);

    logger.log(ChannelLogLevel.DEBUG, "CachingRlsLbClient created");
  }

  void init() {
    synchronized (lock) {
      refCountedChildPolicyWrapperFactory.init();
    }
  }

  /**
   * Convert the status to UNAVAILABLE and enhance the error message.
   * @param status status as provided by server
   * @param serverName Used for error description
   * @return Transformed status
   */
  static Status convertRlsServerStatus(Status status, String serverName) {
    return Status.UNAVAILABLE.withCause(status.getCause()).withDescription(
        String.format("Unable to retrieve RLS targets from RLS server %s.  "
                + "RLS server returned: %s: %s",
            serverName, status.getCode(), status.getDescription()));
  }

  private void periodicClean() {
    synchronized (lock) {
      linkedHashLruCache.cleanupExpiredEntries();
    }
  }

  /** Populates async cache entry for new request. */
  @GuardedBy("lock")
  private CachedRouteLookupResponse asyncRlsCall(
      RouteLookupRequest request, @Nullable BackoffPolicy backoffPolicy) {
    if (throttler.shouldThrottle()) {
      logger.log(ChannelLogLevel.DEBUG, "[RLS Entry {0}] Throttled RouteLookup", request);
      // Cache updated, but no need to call updateBalancingState because no RPCs were queued waiting
      // on this result
      return CachedRouteLookupResponse.backoffEntry(createBackOffEntry(
          request, Status.RESOURCE_EXHAUSTED.withDescription("RLS throttled"), backoffPolicy));
    }
    final SettableFuture<RouteLookupResponse> response = SettableFuture.create();
    io.grpc.lookup.v1.RouteLookupRequest routeLookupRequest = REQUEST_CONVERTER.convert(request);
    logger.log(ChannelLogLevel.DEBUG,
        "[RLS Entry {0}] Starting RouteLookup: {1}", request, routeLookupRequest);
    rlsStub.withDeadlineAfter(callTimeoutNanos, TimeUnit.NANOSECONDS)
        .routeLookup(
            routeLookupRequest,
            new StreamObserver<io.grpc.lookup.v1.RouteLookupResponse>() {
              @Override
              public void onNext(io.grpc.lookup.v1.RouteLookupResponse value) {
                logger.log(ChannelLogLevel.DEBUG,
                    "[RLS Entry {0}] RouteLookup succeeded: {1}", request, value);
                response.set(RESPONSE_CONVERTER.reverse().convert(value));
              }

              @Override
              public void onError(Throwable t) {
                logger.log(ChannelLogLevel.DEBUG,
                    "[RLS Entry {0}] RouteLookup failed: {1}", request, t);
                response.setException(t);
                throttler.registerBackendResponse(true);
              }

              @Override
              public void onCompleted() {
                throttler.registerBackendResponse(false);
              }
            });
    return CachedRouteLookupResponse.pendingResponse(
        createPendingEntry(request, response, backoffPolicy));
  }

  /**
   * Returns async response of the {@code request}. The returned value can be in 3 different states;
   * cached, pending and backed-off due to error. The result remains same even if the status is
   * changed after the return.
   */
  @CheckReturnValue
  final CachedRouteLookupResponse get(final RouteLookupRequest request) {
    synchronized (lock) {
      final CacheEntry cacheEntry;
      cacheEntry = linkedHashLruCache.read(request);
      if (cacheEntry == null) {
        PendingCacheEntry pendingEntry = pendingCallCache.get(request);
        if (pendingEntry != null) {
          return CachedRouteLookupResponse.pendingResponse(pendingEntry);
        }
        return asyncRlsCall(request, /* backoffPolicy= */ null);
      }

      if (cacheEntry instanceof DataCacheEntry) {
        // cache hit, initiate async-refresh if entry is staled
        DataCacheEntry dataEntry = ((DataCacheEntry) cacheEntry);
        if (dataEntry.isStaled(ticker.read())) {
          dataEntry.maybeRefresh();
        }
        return CachedRouteLookupResponse.dataEntry((DataCacheEntry) cacheEntry);
      }
      return CachedRouteLookupResponse.backoffEntry((BackoffCacheEntry) cacheEntry);
    }
  }

  /** Performs any pending maintenance operations needed by the cache. */
  void close() {
    logger.log(ChannelLogLevel.DEBUG, "CachingRlsLbClient closed");
    synchronized (lock) {
      periodicCleaner.cancel(false);
      // all childPolicyWrapper will be returned via AutoCleaningEvictionListener
      linkedHashLruCache.close();
      // TODO(creamsoup) maybe cancel all pending requests
      pendingCallCache.clear();
      rlsChannel.shutdownNow();
      rlsPicker.close();
      gaugeRegistration.close();
    }
  }

  void requestConnection() {
    rlsChannel.getState(true);
  }

  @GuardedBy("lock")
  private PendingCacheEntry createPendingEntry(
      RouteLookupRequest request,
      ListenableFuture<RouteLookupResponse> pendingCall,
      @Nullable BackoffPolicy backoffPolicy) {
    PendingCacheEntry entry = new PendingCacheEntry(request, pendingCall, backoffPolicy);
    // Add the entry to the map before adding the Listener, because the listener removes the
    // entry from the map
    pendingCallCache.put(request, entry);
    // Beware that the listener can run immediately on the current thread
    pendingCall.addListener(() -> pendingRpcComplete(entry), MoreExecutors.directExecutor());
    return entry;
  }

  private void pendingRpcComplete(PendingCacheEntry entry) {
    synchronized (lock) {
      boolean clientClosed = pendingCallCache.remove(entry.request) == null;
      if (clientClosed) {
        return;
      }

      try {
        createDataEntry(entry.request, Futures.getDone(entry.pendingCall));
        // Cache updated. DataCacheEntry constructor indirectly calls updateBalancingState() to
        // reattempt picks when the child LB is done connecting
      } catch (Exception e) {
        createBackOffEntry(entry.request, Status.fromThrowable(e), entry.backoffPolicy);
        // Cache updated. updateBalancingState() to reattempt picks
        helper.triggerPendingRpcProcessing();
      }
    }
  }

  @GuardedBy("lock")
  private DataCacheEntry createDataEntry(
      RouteLookupRequest request, RouteLookupResponse routeLookupResponse) {
    logger.log(
        ChannelLogLevel.DEBUG,
        "[RLS Entry {0}] Transition to data cache: routeLookupResponse={1}",
        request, routeLookupResponse);
    DataCacheEntry entry = new DataCacheEntry(request, routeLookupResponse);
    // Constructor for DataCacheEntry causes updateBalancingState, but the picks can't happen until
    // this cache update because the lock is held
    linkedHashLruCache.cacheAndClean(request, entry);
    return entry;
  }

  @GuardedBy("lock")
  private BackoffCacheEntry createBackOffEntry(
      RouteLookupRequest request, Status status, @Nullable BackoffPolicy backoffPolicy) {
    if (backoffPolicy == null) {
      backoffPolicy = backoffProvider.get();
    }
    long delayNanos = backoffPolicy.nextBackoffNanos();
    logger.log(
        ChannelLogLevel.DEBUG,
        "[RLS Entry {0}] Transition to back off: status={1}, delayNanos={2}",
        request, status, delayNanos);
    BackoffCacheEntry entry = new BackoffCacheEntry(request, status, backoffPolicy);
    // Lock is held, so the task can't execute before the assignment
    entry.scheduledFuture = scheduledExecutorService.schedule(
        () -> refreshBackoffEntry(entry), delayNanos, TimeUnit.NANOSECONDS);
    linkedHashLruCache.cacheAndClean(request, entry);
    return entry;
  }

  private void refreshBackoffEntry(BackoffCacheEntry entry) {
    synchronized (lock) {
      // This checks whether the task has been cancelled and prevents a second execution.
      if (!entry.scheduledFuture.cancel(false)) {
        // Future was previously cancelled
        return;
      }
      logger.log(ChannelLogLevel.DEBUG,
          "[RLS Entry {0}] Calling RLS for transition to pending", entry.request);
      linkedHashLruCache.invalidate(entry.request);
      asyncRlsCall(entry.request, entry.backoffPolicy);
    }
  }

  private static final class RlsLbHelper extends ForwardingLoadBalancerHelper {

    final Helper helper;
    private ConnectivityState state;
    private SubchannelPicker picker;

    RlsLbHelper(Helper helper) {
      this.helper = helper;
    }

    @Override
    protected Helper delegate() {
      return helper;
    }

    @Override
    public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
      state = newState;
      picker = newPicker;
      super.updateBalancingState(newState, newPicker);
    }

    void triggerPendingRpcProcessing() {
      checkState(state != null, "updateBalancingState hasn't yet been called");
      helper.getSynchronizationContext().execute(
          () -> super.updateBalancingState(state, picker));
    }
  }

  /**
   * Viewer class for cached {@link RouteLookupResponse} and associated {@link ChildPolicyWrapper}.
   */
  static final class CachedRouteLookupResponse {
    // Should only have 1 of following 3 cache entries
    @Nullable
    private final DataCacheEntry dataCacheEntry;
    @Nullable
    private final PendingCacheEntry pendingCacheEntry;
    @Nullable
    private final BackoffCacheEntry backoffCacheEntry;

    CachedRouteLookupResponse(
        DataCacheEntry dataCacheEntry,
        PendingCacheEntry pendingCacheEntry,
        BackoffCacheEntry backoffCacheEntry) {
      this.dataCacheEntry = dataCacheEntry;
      this.pendingCacheEntry = pendingCacheEntry;
      this.backoffCacheEntry = backoffCacheEntry;
      checkState((dataCacheEntry != null ^ pendingCacheEntry != null ^ backoffCacheEntry != null)
          && !(dataCacheEntry != null && pendingCacheEntry != null && backoffCacheEntry != null),
          "Expected only 1 cache entry value provided");
    }

    static CachedRouteLookupResponse pendingResponse(PendingCacheEntry pendingEntry) {
      return new CachedRouteLookupResponse(null, pendingEntry, null);
    }

    static CachedRouteLookupResponse backoffEntry(BackoffCacheEntry backoffEntry) {
      return new CachedRouteLookupResponse(null, null, backoffEntry);
    }

    static CachedRouteLookupResponse dataEntry(DataCacheEntry dataEntry) {
      return new CachedRouteLookupResponse(dataEntry, null, null);
    }

    boolean hasData() {
      return dataCacheEntry != null;
    }

    @Nullable
    ChildPolicyWrapper getChildPolicyWrapper() {
      if (!hasData()) {
        return null;
      }
      return dataCacheEntry.getChildPolicyWrapper();
    }

    @VisibleForTesting
    @Nullable
    ChildPolicyWrapper getChildPolicyWrapper(String target) {
      if (!hasData()) {
        return null;
      }
      return dataCacheEntry.getChildPolicyWrapper(target);
    }

    @Nullable
    String getHeaderData() {
      if (!hasData()) {
        return null;
      }
      return dataCacheEntry.getHeaderData();
    }

    boolean hasError() {
      return backoffCacheEntry != null;
    }

    boolean isPending() {
      return pendingCacheEntry != null;
    }

    @Nullable
    Status getStatus() {
      if (!hasError()) {
        return null;
      }
      return backoffCacheEntry.getStatus();
    }

    @Override
    public String toString() {
      ToStringHelper toStringHelper = MoreObjects.toStringHelper(this);
      if (dataCacheEntry != null) {
        toStringHelper.add("dataCacheEntry", dataCacheEntry);
      }
      if (pendingCacheEntry != null) {
        toStringHelper.add("pendingCacheEntry", pendingCacheEntry);
      }
      if (backoffCacheEntry != null) {
        toStringHelper.add("backoffCacheEntry", backoffCacheEntry);
      }
      return toStringHelper.toString();
    }
  }

  /** A pending cache entry when the async RouteLookup RPC is still on the fly. */
  static final class PendingCacheEntry {
    private final ListenableFuture<RouteLookupResponse> pendingCall;
    private final RouteLookupRequest request;
    @Nullable
    private final BackoffPolicy backoffPolicy;

    PendingCacheEntry(
        RouteLookupRequest request,
        ListenableFuture<RouteLookupResponse> pendingCall,
        @Nullable BackoffPolicy backoffPolicy) {
      this.request = checkNotNull(request, "request");
      this.pendingCall = checkNotNull(pendingCall, "pendingCall");
      this.backoffPolicy = backoffPolicy;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("request", request)
          .toString();
    }
  }

  /** Common cache entry data for {@link RlsAsyncLruCache}. */
  abstract static class CacheEntry {

    protected final RouteLookupRequest request;

    CacheEntry(RouteLookupRequest request) {
      this.request = checkNotNull(request, "request");
    }

    abstract int getSizeBytes();

    abstract boolean isExpired(long now);

    abstract void cleanup();

    protected boolean isOldEnoughToBeEvicted(long now) {
      return true;
    }
  }

  /** Implementation of {@link CacheEntry} contains valid data. */
  final class DataCacheEntry extends CacheEntry {
    private final RouteLookupResponse response;
    private final long minEvictionTime;
    private final long expireTime;
    private final long staleTime;
    private final List<ChildPolicyWrapper> childPolicyWrappers;

    // GuardedBy CachingRlsLbClient.lock
    DataCacheEntry(RouteLookupRequest request, final RouteLookupResponse response) {
      super(request);
      this.response = checkNotNull(response, "response");
      checkState(!response.targets().isEmpty(), "No targets returned by RLS");
      childPolicyWrappers =
          refCountedChildPolicyWrapperFactory
              .createOrGet(response.targets());
      long now = ticker.read();
      minEvictionTime = now + MIN_EVICTION_TIME_DELTA_NANOS;
      expireTime = now + maxAgeNanos;
      staleTime = now + staleAgeNanos;
    }

    /**
     * Refreshes cache entry by creating {@link PendingCacheEntry}. When the {@code
     * PendingCacheEntry} received data from RLS server, it will replace the data entry if valid
     * data still exists. Flow looks like following.
     *
     * <pre>
     * Timeline                       | async refresh
     *                                V put new cache (entry2)
     * entry1: Pending | hasValue | staled  |
     * entry2:                        | OV* | pending | hasValue | staled |
     *
     * OV: old value
     * </pre>
     */
    void maybeRefresh() {
      synchronized (lock) { // Lock is already held, but ErrorProne can't tell
        if (pendingCallCache.containsKey(request)) {
          // pending already requested
          return;
        }
        logger.log(ChannelLogLevel.DEBUG,
            "[RLS Entry {0}] Cache entry is stale, refreshing", request);
        asyncRlsCall(request, /* backoffPolicy= */ null);
      }
    }

    @VisibleForTesting
    ChildPolicyWrapper getChildPolicyWrapper(String target) {
      for (ChildPolicyWrapper childPolicyWrapper : childPolicyWrappers) {
        if (childPolicyWrapper.getTarget().equals(target)) {
          return childPolicyWrapper;
        }
      }

      throw new RuntimeException("Target not found:" + target);
    }

    @Nullable
    ChildPolicyWrapper getChildPolicyWrapper() {
      for (ChildPolicyWrapper childPolicyWrapper : childPolicyWrappers) {
        if (childPolicyWrapper.getState() != ConnectivityState.TRANSIENT_FAILURE) {
          return childPolicyWrapper;
        }
      }
      return childPolicyWrappers.get(0);
    }

    String getHeaderData() {
      return response.getHeaderData();
    }

    // Assume UTF-16 (2 bytes) and overhead of a String object is 38 bytes
    int calcStringSize(String target) {
      return target.length() * BYTES_PER_CHAR + STRING_OVERHEAD_BYTES;
    }

    @Override
    int getSizeBytes() {
      int targetSize = 0;
      for (String target : response.targets()) {
        targetSize += calcStringSize(target);
      }
      return targetSize + calcStringSize(response.getHeaderData()) + OBJ_OVERHEAD_B // response size
          + Long.SIZE * 2 + OBJ_OVERHEAD_B; // Other fields
    }

    @Override
    boolean isExpired(long now) {
      return expireTime - now <= 0;
    }

    boolean isStaled(long now) {
      return staleTime - now <= 0;
    }

    @Override
    protected boolean isOldEnoughToBeEvicted(long now) {
      return minEvictionTime - now <= 0;
    }

    @Override
    void cleanup() {
      synchronized (lock) {
        for (ChildPolicyWrapper policyWrapper : childPolicyWrappers) {
          refCountedChildPolicyWrapperFactory.release(policyWrapper);
        }
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("request", request)
          .add("response", response)
          .add("expireTime", expireTime)
          .add("staleTime", staleTime)
          .add("childPolicyWrappers", childPolicyWrappers)
          .toString();
    }
  }

  /**
   * Implementation of {@link CacheEntry} contains error. This entry will transition to pending
   * status when the backoff time is expired.
   */
  private static final class BackoffCacheEntry extends CacheEntry {

    private final Status status;
    private final BackoffPolicy backoffPolicy;
    private Future<?> scheduledFuture;

    BackoffCacheEntry(RouteLookupRequest request, Status status, BackoffPolicy backoffPolicy) {
      super(request);
      this.status = checkNotNull(status, "status");
      this.backoffPolicy = checkNotNull(backoffPolicy, "backoffPolicy");
    }

    Status getStatus() {
      return status;
    }

    @Override
    int getSizeBytes() {
      return OBJ_OVERHEAD_B * 3 + Long.SIZE + 8; // 3 java objects, 1 long and a boolean
    }

    @Override
    boolean isExpired(long now) {
      return scheduledFuture.isDone();
    }

    @Override
    void cleanup() {
      scheduledFuture.cancel(false);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("request", request)
          .add("status", status)
          .toString();
    }
  }

  /** Returns a Builder for {@link CachingRlsLbClient}. */
  static Builder newBuilder() {
    return new Builder();
  }

  /** A Builder for {@link CachingRlsLbClient}. */
  static final class Builder {

    private Helper helper;
    private LbPolicyConfiguration lbPolicyConfig;
    private Throttler throttler = new HappyThrottler();
    private ResolvedAddressFactory resolvedAddressFactory;
    private Ticker ticker = Ticker.systemTicker();
    private EvictionListener<RouteLookupRequest, CacheEntry> evictionListener;
    private BackoffPolicy.Provider backoffProvider = new ExponentialBackoffPolicy.Provider();

    Builder setHelper(Helper helper) {
      this.helper = checkNotNull(helper, "helper");
      return this;
    }

    Builder setLbPolicyConfig(LbPolicyConfiguration lbPolicyConfig) {
      this.lbPolicyConfig = checkNotNull(lbPolicyConfig, "lbPolicyConfig");
      return this;
    }

    Builder setThrottler(Throttler throttler) {
      this.throttler = checkNotNull(throttler, "throttler");
      return this;
    }

    /**
     * Sets a factory to create {@link ResolvedAddresses} for child load balancer.
     */
    Builder setResolvedAddressesFactory(
        ResolvedAddressFactory resolvedAddressFactory) {
      this.resolvedAddressFactory =
          checkNotNull(resolvedAddressFactory, "resolvedAddressFactory");
      return this;
    }

    Builder setTicker(Ticker ticker) {
      this.ticker = checkNotNull(ticker, "ticker");
      return this;
    }

    Builder setEvictionListener(
        @Nullable EvictionListener<RouteLookupRequest, CacheEntry> evictionListener) {
      this.evictionListener = evictionListener;
      return this;
    }

    Builder setBackoffProvider(BackoffPolicy.Provider provider) {
      this.backoffProvider = checkNotNull(provider, "provider");
      return this;
    }

    CachingRlsLbClient build() {
      CachingRlsLbClient client = new CachingRlsLbClient(this);
      client.init();
      return client;
    }
  }

  /**
   * When any {@link CacheEntry} is evicted from {@link LruCache}, it performs {@link
   * CacheEntry#cleanup()} after original {@link EvictionListener} is finished.
   */
  private static final class AutoCleaningEvictionListener
      implements EvictionListener<RouteLookupRequest, CacheEntry> {

    private final EvictionListener<RouteLookupRequest, CacheEntry> delegate;

    AutoCleaningEvictionListener(
        @Nullable EvictionListener<RouteLookupRequest, CacheEntry> delegate) {
      this.delegate = delegate;
    }

    @Override
    public void onEviction(RouteLookupRequest key, CacheEntry value, EvictionType cause) {
      if (delegate != null) {
        delegate.onEviction(key, value, cause);
      }
      // performs cleanup after delegation
      value.cleanup();
    }
  }

  /** A Throttler never throttles. */
  private static final class HappyThrottler implements Throttler {

    @Override
    public boolean shouldThrottle() {
      return false;
    }

    @Override
    public void registerBackendResponse(boolean throttled) {
      // no-op
    }
  }

  /** Implementation of {@link LinkedHashLruCache} for RLS. */
  private static final class RlsAsyncLruCache
      extends LinkedHashLruCache<RouteLookupRequest, CacheEntry> {
    private final RlsLbHelper helper;

    RlsAsyncLruCache(long maxEstimatedSizeBytes,
        @Nullable EvictionListener<RouteLookupRequest, CacheEntry> evictionListener,
        Ticker ticker, RlsLbHelper helper) {
      super(maxEstimatedSizeBytes, evictionListener, ticker);
      this.helper = checkNotNull(helper, "helper");
    }

    @Override
    protected boolean isExpired(RouteLookupRequest key, CacheEntry value, long nowNanos) {
      return value.isExpired(nowNanos);
    }

    @Override
    protected int estimateSizeOf(RouteLookupRequest key, CacheEntry value) {
      return value.getSizeBytes();
    }

    @Override
    protected boolean shouldInvalidateEldestEntry(
        RouteLookupRequest eldestKey, CacheEntry eldestValue, long now) {
      if (!eldestValue.isOldEnoughToBeEvicted(now)) {
        return false;
      }

      // eldest entry should be evicted if size limit exceeded
      return this.estimatedSizeBytes() > this.estimatedMaxSizeBytes();
    }

    public CacheEntry cacheAndClean(RouteLookupRequest key, CacheEntry value) {
      CacheEntry newEntry = cache(key, value);

      // force cleanup if new entry pushed cache over max size (in bytes)
      if (fitToLimit()) {
        helper.triggerPendingRpcProcessing();
      }
      return newEntry;
    }
  }

  /**
   * LbStatusListener refreshes {@link BackoffCacheEntry} when lb state is changed to {@link
   * ConnectivityState#READY} from {@link ConnectivityState#TRANSIENT_FAILURE}.
   */
  private final class BackoffRefreshListener implements ChildLbStatusListener {

    @Nullable
    private ConnectivityState prevState = null;

    @Override
    public void onStatusChanged(ConnectivityState newState) {
      if (prevState == ConnectivityState.TRANSIENT_FAILURE
          && newState == ConnectivityState.READY) {
        logger.log(ChannelLogLevel.DEBUG, "Transitioning from TRANSIENT_FAILURE to READY");
        synchronized (lock) {
          for (CacheEntry value : linkedHashLruCache.values()) {
            if (value instanceof BackoffCacheEntry) {
              refreshBackoffEntry((BackoffCacheEntry) value);
            }
          }
        }
      }
      prevState = newState;
    }
  }

  /** A header will be added when RLS server respond with additional header data. */
  @VisibleForTesting
  static final Metadata.Key<String> RLS_DATA_KEY =
      Metadata.Key.of("X-Google-RLS-Data", Metadata.ASCII_STRING_MARSHALLER);

  final class RlsPicker extends SubchannelPicker {

    private final RlsRequestFactory requestFactory;
    private final String lookupService;

    RlsPicker(RlsRequestFactory requestFactory, String lookupService) {
      this.requestFactory = checkNotNull(requestFactory, "requestFactory");
      this.lookupService = checkNotNull(lookupService, "rlsConfig");
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      String serviceName = args.getMethodDescriptor().getServiceName();
      String methodName = args.getMethodDescriptor().getBareMethodName();
      RouteLookupRequest request =
          requestFactory.create(serviceName, methodName, args.getHeaders());
      final CachedRouteLookupResponse response = CachingRlsLbClient.this.get(request);

      if (response.getHeaderData() != null && !response.getHeaderData().isEmpty()) {
        Metadata headers = args.getHeaders();
        headers.discardAll(RLS_DATA_KEY);
        headers.put(RLS_DATA_KEY, response.getHeaderData());
      }
      String defaultTarget = lbPolicyConfig.getRouteLookupConfig().defaultTarget();
      boolean hasFallback = defaultTarget != null && !defaultTarget.isEmpty();
      if (response.hasData()) {
        ChildPolicyWrapper childPolicyWrapper = response.getChildPolicyWrapper();
        SubchannelPicker picker =
            (childPolicyWrapper != null) ? childPolicyWrapper.getPicker() : null;
        if (picker == null) {
          return PickResult.withNoResult();
        }
        // Happy path
        PickResult pickResult = picker.pickSubchannel(args);
        if (pickResult.hasResult()) {
          helper.getMetricRecorder().addLongCounter(TARGET_PICKS_COUNTER, 1,
              Arrays.asList(helper.getChannelTarget(), lookupService,
                  childPolicyWrapper.getTarget(), determineMetricsPickResult(pickResult)),
              Collections.emptyList());
        }
        return pickResult;
      } else if (response.hasError()) {
        if (hasFallback) {
          return useFallback(args);
        }
        helper.getMetricRecorder().addLongCounter(FAILED_PICKS_COUNTER, 1,
            Arrays.asList(helper.getChannelTarget(), lookupService), Collections.emptyList());
        return PickResult.withError(
            convertRlsServerStatus(response.getStatus(),
                lbPolicyConfig.getRouteLookupConfig().lookupService()));
      } else {
        return PickResult.withNoResult();
      }
    }

    private ChildPolicyWrapper fallbackChildPolicyWrapper;

    /** Uses Subchannel connected to default target. */
    private PickResult useFallback(PickSubchannelArgs args) {
      // TODO(creamsoup) wait until lb is ready
      startFallbackChildPolicy();
      SubchannelPicker picker = fallbackChildPolicyWrapper.getPicker();
      if (picker == null) {
        return PickResult.withNoResult();
      }
      PickResult pickResult = picker.pickSubchannel(args);
      if (pickResult.hasResult()) {
        helper.getMetricRecorder().addLongCounter(DEFAULT_TARGET_PICKS_COUNTER, 1,
            Arrays.asList(helper.getChannelTarget(), lookupService,
                fallbackChildPolicyWrapper.getTarget(), determineMetricsPickResult(pickResult)),
            Collections.emptyList());
      }
      return pickResult;
    }

    private String determineMetricsPickResult(PickResult pickResult) {
      if (pickResult.getStatus().isOk()) {
        return "complete";
      } else if (pickResult.isDrop()) {
        return "drop";
      } else {
        return "fail";
      }
    }

    private void startFallbackChildPolicy() {
      String defaultTarget = lbPolicyConfig.getRouteLookupConfig().defaultTarget();
      synchronized (lock) {
        if (fallbackChildPolicyWrapper != null) {
          return;
        }
        logger.log(ChannelLogLevel.DEBUG, "starting fallback to {0}", defaultTarget);
        fallbackChildPolicyWrapper = refCountedChildPolicyWrapperFactory.createOrGet(defaultTarget);
      }
    }

    // GuardedBy CachingRlsLbClient.lock
    void close() {
      synchronized (lock) { // Lock is already held, but ErrorProne can't tell
        if (fallbackChildPolicyWrapper != null) {
          refCountedChildPolicyWrapperFactory.release(fallbackChildPolicyWrapper);
        }
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("target", lbPolicyConfig.getRouteLookupConfig().lookupService())
          .toString();
    }
  }

}
