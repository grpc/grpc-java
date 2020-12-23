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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.TimeProvider;
import io.grpc.lookup.v1.RouteLookupServiceGrpc;
import io.grpc.lookup.v1.RouteLookupServiceGrpc.RouteLookupServiceStub;
import io.grpc.rls.ChildLoadBalancerHelper.ChildLoadBalancerHelperProvider;
import io.grpc.rls.LbPolicyConfiguration.ChildLbStatusListener;
import io.grpc.rls.LbPolicyConfiguration.ChildLoadBalancingPolicy;
import io.grpc.rls.LbPolicyConfiguration.ChildPolicyWrapper;
import io.grpc.rls.LbPolicyConfiguration.RefCountedChildPolicyWrapperFactory;
import io.grpc.rls.LruCache.EvictionListener;
import io.grpc.rls.LruCache.EvictionType;
import io.grpc.rls.RlsProtoConverters.RouteLookupResponseConverter;
import io.grpc.rls.RlsProtoData.RouteLookupConfig;
import io.grpc.rls.RlsProtoData.RouteLookupRequest;
import io.grpc.rls.RlsProtoData.RouteLookupResponse;
import io.grpc.rls.Throttler.ThrottledException;
import io.grpc.stub.StreamObserver;
import io.grpc.util.ForwardingLoadBalancerHelper;
import java.util.HashMap;
import java.util.Map;
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

  // System property to use direct path enabled OobChannel, by default direct path is enabled.
  private static final String RLS_ENABLE_OOB_CHANNEL_DIRECTPATH_PROPERTY =
      "io.grpc.rls.CachingRlsLbClient.enable_oobchannel_directpath";
  @VisibleForTesting
  static boolean enableOobChannelDirectPath =
      Boolean.parseBoolean(System.getProperty(RLS_ENABLE_OOB_CHANNEL_DIRECTPATH_PROPERTY, "false"));

  // All cache status changes (pending, backoff, success) must be under this lock
  private final Object lock = new Object();
  // LRU cache based on access order (BACKOFF and actual data will be here)
  @GuardedBy("lock")
  private final LinkedHashLruCache<RouteLookupRequest, CacheEntry> linkedHashLruCache;
  // any RPC on the fly will cached in this map
  @GuardedBy("lock")
  private final Map<RouteLookupRequest, PendingCacheEntry> pendingCallCache = new HashMap<>();

  private final SynchronizationContext synchronizationContext;
  private final ScheduledExecutorService scheduledExecutorService;
  private final TimeProvider timeProvider;
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
  private final RefCountedChildPolicyWrapperFactory refCountedChildPolicyWrapperFactory;
  private final ChannelLogger logger;

  private CachingRlsLbClient(Builder builder) {
    helper = new RlsLbHelper(checkNotNull(builder.helper, "helper"));
    scheduledExecutorService = helper.getScheduledExecutorService();
    synchronizationContext = helper.getSynchronizationContext();
    lbPolicyConfig = checkNotNull(builder.lbPolicyConfig, "lbPolicyConfig");
    RouteLookupConfig rlsConfig = lbPolicyConfig.getRouteLookupConfig();
    maxAgeNanos = TimeUnit.MILLISECONDS.toNanos(rlsConfig.getMaxAgeInMillis());
    staleAgeNanos = TimeUnit.MILLISECONDS.toNanos(rlsConfig.getStaleAgeInMillis());
    callTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(rlsConfig.getLookupServiceTimeoutInMillis());
    timeProvider = checkNotNull(builder.timeProvider, "timeProvider");
    throttler = checkNotNull(builder.throttler, "throttler");
    linkedHashLruCache =
        new RlsAsyncLruCache(
            rlsConfig.getCacheSizeBytes(),
            builder.evictionListener,
            scheduledExecutorService,
            timeProvider);
    RlsRequestFactory requestFactory = new RlsRequestFactory(lbPolicyConfig.getRouteLookupConfig());
    rlsPicker = new RlsPicker(requestFactory);
    ManagedChannelBuilder<?> rlsChannelBuilder =
        helper.createResolvingOobChannelBuilder(rlsConfig.getLookupService());
    logger = helper.getChannelLogger();
    if (enableOobChannelDirectPath) {
      logger.log(
          ChannelLogLevel.DEBUG,
          "RLS channel direct path enabled. RLS channel service config: {0}",
          getDirectpathServiceConfig());
      rlsChannelBuilder.defaultServiceConfig(getDirectpathServiceConfig());
      rlsChannelBuilder.disableServiceConfigLookUp();
    }
    rlsChannel = rlsChannelBuilder.build();
    helper.updateBalancingState(ConnectivityState.CONNECTING, rlsPicker);
    rlsStub = RouteLookupServiceGrpc.newStub(rlsChannel);
    childLbResolvedAddressFactory =
        checkNotNull(builder.resolvedAddressFactory, "resolvedAddressFactory");
    backoffProvider = builder.backoffProvider;
    ChildLoadBalancerHelperProvider childLbHelperProvider =
        new ChildLoadBalancerHelperProvider(helper, new SubchannelStateManagerImpl(), rlsPicker);
    refCountedChildPolicyWrapperFactory =
        new RefCountedChildPolicyWrapperFactory(
            childLbHelperProvider, new BackoffRefreshListener());
    logger.log(ChannelLogLevel.DEBUG, "CachingRlsLbClient created");
  }

  private static ImmutableMap<String, Object> getDirectpathServiceConfig() {
    ImmutableMap<String, Object> pickFirstStrategy =
        ImmutableMap.<String, Object>of("pick_first", ImmutableMap.of());

    ImmutableMap<String, Object> childPolicy =
        ImmutableMap.<String, Object>of("childPolicy", ImmutableList.of(pickFirstStrategy));

    ImmutableMap<String, Object> grpcLbPolicy =
        ImmutableMap.<String, Object>of("grpclb", childPolicy);

    return ImmutableMap.<String, Object>of("loadBalancingConfig", ImmutableList.of(grpcLbPolicy));
  }

  @CheckReturnValue
  private ListenableFuture<RouteLookupResponse> asyncRlsCall(RouteLookupRequest request) {
    final SettableFuture<RouteLookupResponse> response = SettableFuture.create();
    if (throttler.shouldThrottle()) {
      logger.log(ChannelLogLevel.DEBUG, "Request is throttled");
      response.setException(new ThrottledException());
      return response;
    }
    io.grpc.lookup.v1.RouteLookupRequest routeLookupRequest = REQUEST_CONVERTER.convert(request);
    logger.log(ChannelLogLevel.DEBUG, "Sending RouteLookupRequest: {0}", routeLookupRequest);
    rlsStub.withDeadlineAfter(callTimeoutNanos, TimeUnit.NANOSECONDS)
        .routeLookup(
            routeLookupRequest,
            new StreamObserver<io.grpc.lookup.v1.RouteLookupResponse>() {
              @Override
              public void onNext(io.grpc.lookup.v1.RouteLookupResponse value) {
                logger.log(ChannelLogLevel.DEBUG, "Received RouteLookupResponse: {0}", value);
                response.set(RESPONSE_CONVERTER.reverse().convert(value));
              }

              @Override
              public void onError(Throwable t) {
                logger.log(ChannelLogLevel.DEBUG, "Error looking up route:", t);
                response.setException(t);
                throttler.registerBackendResponse(false);
                helper.propagateRlsError();
              }

              @Override
              public void onCompleted() {
                throttler.registerBackendResponse(true);
              }
            });
    return response;
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
        return handleNewRequest(request);
      }

      if (cacheEntry instanceof DataCacheEntry) {
        // cache hit, initiate async-refresh if entry is staled
        logger.log(ChannelLogLevel.DEBUG, "Cache hit for the request");
        DataCacheEntry dataEntry = ((DataCacheEntry) cacheEntry);
        if (dataEntry.isStaled(timeProvider.currentTimeNanos())) {
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
      // all childPolicyWrapper will be returned via AutoCleaningEvictionListener
      linkedHashLruCache.close();
      // TODO(creamsoup) maybe cancel all pending requests
      pendingCallCache.clear();
      rlsChannel.shutdown();
      rlsPicker.close();
    }
  }

  /**
   * Populates async cache entry for new request. This is only methods directly modifies the cache,
   * any status change is happening via event (async request finished, timed out, etc) in {@link
   * PendingCacheEntry}, {@link DataCacheEntry} and {@link BackoffCacheEntry}.
   */
  private CachedRouteLookupResponse handleNewRequest(RouteLookupRequest request) {
    synchronized (lock) {
      PendingCacheEntry pendingEntry = pendingCallCache.get(request);
      if (pendingEntry != null) {
        return CachedRouteLookupResponse.pendingResponse(pendingEntry);
      }

      ListenableFuture<RouteLookupResponse> asyncCall = asyncRlsCall(request);
      if (!asyncCall.isDone()) {
        pendingEntry = new PendingCacheEntry(request, asyncCall);
        pendingCallCache.put(request, pendingEntry);
        return CachedRouteLookupResponse.pendingResponse(pendingEntry);
      } else {
        // async call returned finished future is most likely throttled
        try {
          RouteLookupResponse response = asyncCall.get();
          DataCacheEntry dataEntry = new DataCacheEntry(request, response);
          linkedHashLruCache.cache(request, dataEntry);
          return CachedRouteLookupResponse.dataEntry(dataEntry);
        } catch (Exception e) {
          BackoffCacheEntry backoffEntry =
              new BackoffCacheEntry(request, Status.fromThrowable(e), backoffProvider.get());
          linkedHashLruCache.cache(request, backoffEntry);
          return CachedRouteLookupResponse.backoffEntry(backoffEntry);
        }
      }
    }
  }

  void requestConnection() {
    rlsChannel.getState(true);
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

    void propagateRlsError() {
      getSynchronizationContext().execute(new Runnable() {
        @Override
        public void run() {
          if (picker != null) {
            // Refresh the channel state and let pending RPCs reprocess the picker.
            updateBalancingState(state, picker);
          }
        }
      });
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
  final class PendingCacheEntry {
    private final ListenableFuture<RouteLookupResponse> pendingCall;
    private final RouteLookupRequest request;
    private final BackoffPolicy backoffPolicy;

    PendingCacheEntry(
        RouteLookupRequest request, ListenableFuture<RouteLookupResponse> pendingCall) {
      this(request, pendingCall, null);
    }

    PendingCacheEntry(
        RouteLookupRequest request,
        ListenableFuture<RouteLookupResponse> pendingCall,
        @Nullable BackoffPolicy backoffPolicy) {
      this.request = checkNotNull(request, "request");
      this.pendingCall = pendingCall;
      this.backoffPolicy = backoffPolicy == null ? backoffProvider.get() : backoffPolicy;
      pendingCall.addListener(
          new Runnable() {
            @Override
            public void run() {
              handleDoneFuture();
            }
          },
          synchronizationContext);
    }

    private void handleDoneFuture() {
      synchronized (lock) {
        pendingCallCache.remove(request);
        if (pendingCall.isCancelled()) {
          return;
        }

        try {
          transitionToDataEntry(pendingCall.get());
        } catch (Exception e) {
          if (e instanceof ThrottledException) {
            transitionToBackOff(Status.RESOURCE_EXHAUSTED.withCause(e));
          } else {
            transitionToBackOff(Status.fromThrowable(e));
          }
        }
      }
    }

    private void transitionToDataEntry(RouteLookupResponse routeLookupResponse) {
      synchronized (lock) {
        logger.log(
            ChannelLogLevel.DEBUG,
            "Transition to data cache: routeLookupResponse={0}",
            routeLookupResponse);
        linkedHashLruCache.cache(request, new DataCacheEntry(request, routeLookupResponse));
      }
    }

    private void transitionToBackOff(Status status) {
      synchronized (lock) {
        logger.log(ChannelLogLevel.DEBUG, "Transition to back off: status={0}", status);
        linkedHashLruCache.cache(request, new BackoffCacheEntry(request, status, backoffPolicy));
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("request", request)
          .toString();
    }
  }

  /** Common cache entry data for {@link RlsAsyncLruCache}. */
  abstract class CacheEntry {

    protected final RouteLookupRequest request;

    CacheEntry(RouteLookupRequest request) {
      this.request = checkNotNull(request, "request");
    }

    abstract int getSizeBytes();

    final boolean isExpired() {
      return isExpired(timeProvider.currentTimeNanos());
    }

    abstract boolean isExpired(long now);

    abstract void cleanup();
  }

  /** Implementation of {@link CacheEntry} contains valid data. */
  final class DataCacheEntry extends CacheEntry {
    private final RouteLookupResponse response;
    private final long expireTime;
    private final long staleTime;
    private final ChildPolicyWrapper childPolicyWrapper;

    DataCacheEntry(RouteLookupRequest request, final RouteLookupResponse response) {
      super(request);
      this.response = checkNotNull(response, "response");
      // TODO(creamsoup) fallback to other targets if first one is not available
      childPolicyWrapper =
          refCountedChildPolicyWrapperFactory
              .createOrGet(response.getTargets().get(0));
      long now = timeProvider.currentTimeNanos();
      expireTime = now + maxAgeNanos;
      staleTime = now + staleAgeNanos;

      if (childPolicyWrapper.getPicker() != null) {
        childPolicyWrapper.refreshState();
      } else {
        createChildLbPolicy();
      }
    }

    private void createChildLbPolicy() {
      ChildLoadBalancingPolicy childPolicy = lbPolicyConfig.getLoadBalancingPolicy();
      LoadBalancerProvider lbProvider = childPolicy.getEffectiveLbProvider();
      ConfigOrError lbConfig =
          lbProvider
              .parseLoadBalancingPolicyConfig(
                  childPolicy.getEffectiveChildPolicy(childPolicyWrapper.getTarget()));

      LoadBalancer lb = lbProvider.newLoadBalancer(childPolicyWrapper.getHelper());
      logger.log(
          ChannelLogLevel.DEBUG,
          "RLS child lb created. config: {0}",
          lbConfig.getConfig());
      lb.handleResolvedAddresses(childLbResolvedAddressFactory.create(lbConfig.getConfig()));
      lb.requestConnection();
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
      synchronized (lock) {
        if (pendingCallCache.containsKey(request)) {
          // pending already requested
          return;
        }
        final ListenableFuture<RouteLookupResponse> asyncCall = asyncRlsCall(request);
        if (!asyncCall.isDone()) {
          pendingCallCache.put(request, new PendingCacheEntry(request, asyncCall));
        } else {
          // async call returned finished future is most likely throttled
          try {
            RouteLookupResponse response = asyncCall.get();
            linkedHashLruCache.cache(request, new DataCacheEntry(request, response));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (Exception e) {
            BackoffCacheEntry backoffEntry =
                new BackoffCacheEntry(request, Status.fromThrowable(e), backoffProvider.get());
            linkedHashLruCache.cache(request, backoffEntry);
          }
        }
      }
    }

    @Nullable
    ChildPolicyWrapper getChildPolicyWrapper() {
      return childPolicyWrapper;
    }

    String getHeaderData() {
      return response.getHeaderData();
    }

    @Override
    int getSizeBytes() {
      // size of strings and java object overhead, actual memory usage is more than this.
      return
          (response.getTargets().get(0).length() + response.getHeaderData().length()) * 2 + 38 * 2;
    }

    @Override
    boolean isExpired(long now) {
      return expireTime <= now;
    }

    boolean isStaled(long now) {
      return staleTime <= now;
    }

    @Override
    void cleanup() {
      refCountedChildPolicyWrapperFactory.release(childPolicyWrapper);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("request", request)
          .add("response", response)
          .add("expireTime", expireTime)
          .add("staleTime", staleTime)
          .add("childPolicyWrapper", childPolicyWrapper)
          .toString();
    }
  }

  /**
   * Implementation of {@link CacheEntry} contains error. This entry will transition to pending
   * status when the backoff time is expired.
   */
  private final class BackoffCacheEntry extends CacheEntry {

    private final Status status;
    private final ScheduledHandle scheduledHandle;
    private final BackoffPolicy backoffPolicy;
    private final long expireNanos;
    private boolean shutdown = false;

    BackoffCacheEntry(RouteLookupRequest request, Status status, BackoffPolicy backoffPolicy) {
      super(request);
      this.status = checkNotNull(status, "status");
      this.backoffPolicy = checkNotNull(backoffPolicy, "backoffPolicy");
      long delayNanos = backoffPolicy.nextBackoffNanos();
      this.expireNanos = timeProvider.currentTimeNanos() + delayNanos;
      this.scheduledHandle =
          synchronizationContext.schedule(
              new Runnable() {
                @Override
                public void run() {
                  transitionToPending();
                }
              },
              delayNanos,
              TimeUnit.NANOSECONDS,
              scheduledExecutorService);
    }

    /** Forcefully refreshes cache entry by ignoring the backoff timer. */
    void forceRefresh() {
      if (scheduledHandle.isPending()) {
        scheduledHandle.cancel();
        transitionToPending();
      }
    }

    private void transitionToPending() {
      synchronized (lock) {
        if (shutdown) {
          return;
        }
        ListenableFuture<RouteLookupResponse> call = asyncRlsCall(request);
        if (!call.isDone()) {
          PendingCacheEntry pendingEntry = new PendingCacheEntry(request, call, backoffPolicy);
          pendingCallCache.put(request, pendingEntry);
          linkedHashLruCache.invalidate(request);
        } else {
          try {
            RouteLookupResponse response = call.get();
            linkedHashLruCache.cache(request, new DataCacheEntry(request, response));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (Exception e) {
            linkedHashLruCache.cache(
                request,
                new BackoffCacheEntry(request, Status.fromThrowable(e), backoffPolicy));
          }
        }
      }
    }

    Status getStatus() {
      return status;
    }

    @Override
    int getSizeBytes() {
      return 0;
    }

    @Override
    boolean isExpired(long now) {
      return expireNanos <= now;
    }

    @Override
    void cleanup() {
      if (shutdown) {
        return;
      }
      shutdown = true;
      if (!scheduledHandle.isPending()) {
        scheduledHandle.cancel();
      }
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
    private TimeProvider timeProvider = TimeProvider.SYSTEM_TIME_PROVIDER;
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

    Builder setTimeProvider(TimeProvider timeProvider) {
      this.timeProvider = checkNotNull(timeProvider, "timeProvider");
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
      return new CachingRlsLbClient(this);
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

    RlsAsyncLruCache(long maxEstimatedSizeBytes,
        @Nullable EvictionListener<RouteLookupRequest, CacheEntry> evictionListener,
        ScheduledExecutorService ses, TimeProvider timeProvider) {
      super(
          maxEstimatedSizeBytes,
          new AutoCleaningEvictionListener(evictionListener),
          1,
          TimeUnit.MINUTES,
          ses,
          timeProvider);
    }

    @Override
    protected boolean isExpired(RouteLookupRequest key, CacheEntry value, long nowNanos) {
      return value.isExpired();
    }

    @Override
    protected int estimateSizeOf(RouteLookupRequest key, CacheEntry value) {
      return value.getSizeBytes();
    }

    @Override
    protected boolean shouldInvalidateEldestEntry(
        RouteLookupRequest eldestKey, CacheEntry eldestValue) {
      // eldest entry should be evicted if size limit exceeded
      return true;
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
        synchronized (lock) {
          for (CacheEntry value : linkedHashLruCache.values()) {
            if (value instanceof BackoffCacheEntry) {
              ((BackoffCacheEntry) value).forceRefresh();
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

    RlsPicker(RlsRequestFactory requestFactory) {
      this.requestFactory = checkNotNull(requestFactory, "requestFactory");
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      String[] methodName = args.getMethodDescriptor().getFullMethodName().split("/", 2);
      RouteLookupRequest request =
          requestFactory.create(methodName[0], methodName[1], args.getHeaders());
      final CachedRouteLookupResponse response = CachingRlsLbClient.this.get(request);
      logger.log(ChannelLogLevel.DEBUG,
          "Got route lookup cache entry for service={0}, method={1}, headers={2}:\n {3}",
          new Object[]{methodName[0], methodName[1], args.getHeaders(), response});

      if (response.getHeaderData() != null && !response.getHeaderData().isEmpty()) {
        Metadata headers = args.getHeaders();
        headers.discardAll(RLS_DATA_KEY);
        headers.put(RLS_DATA_KEY, response.getHeaderData());
      }
      String defaultTarget = lbPolicyConfig.getRouteLookupConfig().getDefaultTarget();
      boolean hasFallback = defaultTarget != null && !defaultTarget.isEmpty();
      if (response.hasData()) {
        ChildPolicyWrapper childPolicyWrapper = response.getChildPolicyWrapper();
        SubchannelPicker picker = childPolicyWrapper.getPicker();
        if (picker == null) {
          return PickResult.withNoResult();
        }
        PickResult result = picker.pickSubchannel(args);
        if (result.getStatus().isOk()) {
          return result;
        }
        if (hasFallback) {
          return useFallback(args);
        }
        return PickResult.withError(result.getStatus());
      } else if (response.hasError()) {
        if (hasFallback) {
          return useFallback(args);
        }
        return PickResult.withError(response.getStatus());
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
      return picker.pickSubchannel(args);
    }

    private void startFallbackChildPolicy() {
      String defaultTarget = lbPolicyConfig.getRouteLookupConfig().getDefaultTarget();
      logger.log(ChannelLogLevel.DEBUG, "starting fallback to {0}", defaultTarget);
      synchronized (lock) {
        if (fallbackChildPolicyWrapper != null) {
          return;
        }
        fallbackChildPolicyWrapper = refCountedChildPolicyWrapperFactory.createOrGet(defaultTarget);
      }
      LoadBalancerProvider lbProvider =
          lbPolicyConfig.getLoadBalancingPolicy().getEffectiveLbProvider();
      final LoadBalancer lb =
          lbProvider.newLoadBalancer(fallbackChildPolicyWrapper.getHelper());
      final ConfigOrError lbConfig =
          lbProvider
              .parseLoadBalancingPolicyConfig(
                  lbPolicyConfig
                      .getLoadBalancingPolicy()
                      .getEffectiveChildPolicy(defaultTarget));
      helper.getSynchronizationContext().execute(
          new Runnable() {
            @Override
            public void run() {
              lb.handleResolvedAddresses(
                  childLbResolvedAddressFactory.create(lbConfig.getConfig()));
              lb.requestConnection();
            }
          });
    }

    void close() {
      if (fallbackChildPolicyWrapper != null) {
        refCountedChildPolicyWrapperFactory.release(fallbackChildPolicyWrapper);
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("target", lbPolicyConfig.getRouteLookupConfig().getLookupService())
          .toString();
    }
  }
}
