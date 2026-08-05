/*
 * Copyright 2024 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static io.grpc.rls.CachingRlsLbClient.RLS_DATA_KEY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Converter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ChannelCredentials;
import io.grpc.ChannelLogger;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ForwardingChannelBuilder2;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickDetailsConsumer;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MetricRecorder;
import io.grpc.MetricRecorder.Registration;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.internal.PickSubchannelArgsImpl;
import io.grpc.lookup.v1.RouteLookupServiceGrpc;
import io.grpc.rls.CachingRlsLbClient.CachedRouteLookupResponse;
import io.grpc.rls.LbPolicyConfiguration.ChildLoadBalancingPolicy;
import io.grpc.rls.RlsProtoConverters.RouteLookupResponseConverter;
import io.grpc.rls.RlsProtoData.ExtraKeys;
import io.grpc.rls.RlsProtoData.GrpcKeyBuilder;
import io.grpc.rls.RlsProtoData.GrpcKeyBuilder.Name;
import io.grpc.rls.RlsProtoData.NameMatcher;
import io.grpc.rls.RlsProtoData.RouteLookupConfig;
import io.grpc.rls.RlsProtoData.RouteLookupRequest;
import io.grpc.rls.RlsProtoData.RouteLookupResponse;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.TestMethodDescriptors;
import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public class StaleHeaderDataStressTest {

  private static final RouteLookupConfig ROUTE_LOOKUP_CONFIG = getRouteLookupConfig();
  private static final int SERVER_LATENCY_MILLIS = 10;
  private static final String DEFAULT_TARGET = "fallback.cloudbigtable.googleapis.com";

  @Rule
  public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();

  private final SocketAddress socketAddress = mock(SocketAddress.class);
  private final MetricRecorder mockMetricRecorder = mock(MetricRecorder.class);
  private final Registration mockGaugeRegistration = mock(Registration.class);

  private final SynchronizationContext syncContext =
      new SynchronizationContext(new UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new RuntimeException(e);
        }
      });
  private final CountingBackoffProvider backoffProvider = new CountingBackoffProvider();
  private final ResolvedAddressFactory resolvedAddressFactory =
      new ChildLbResolvedAddressFactory(
          ImmutableList.of(new EquivalentAddressGroup(socketAddress)), Attributes.EMPTY);
  private final TestLoadBalancerProvider lbProvider = new TestLoadBalancerProvider();
  private final FakeClock fakeClock = new FakeClock();
  private final DynamicRlsServerImpl rlsServerImpl =
      new DynamicRlsServerImpl(
          TimeUnit.MILLISECONDS.toNanos(SERVER_LATENCY_MILLIS),
          fakeClock.getScheduledExecutorService());
  private final ChildLoadBalancingPolicy childLbPolicy =
      new ChildLoadBalancingPolicy("target", Collections.<String, Object>emptyMap(), lbProvider);
  private final FakeHelper fakeHelper = new FakeHelper();
  private final Helper helper = fakeHelper;
  private final Throttler nonThrottlingThrottler = new Throttler() {
    @Override
    public boolean shouldThrottle() {
      return false;
    }

    @Override
    public void registerBackendResponse(boolean throttled) {
    }
  };

  private LbPolicyConfiguration lbPolicyConfiguration =
      new LbPolicyConfiguration(ROUTE_LOOKUP_CONFIG, null, childLbPolicy);

  private CachingRlsLbClient rlsLbClient;

  private void setUpRlsLbClient() {
    rlsLbClient =
        CachingRlsLbClient.newBuilder()
            .setBackoffProvider(backoffProvider)
            .setResolvedAddressesFactory(resolvedAddressFactory)
            .setHelper(helper)
            .setLbPolicyConfig(lbPolicyConfiguration)
            .setThrottler(nonThrottlingThrottler)
            .setTicker(fakeClock.getTicker())
            .build();
  }

  @Before
  public void setUpMockMetricRecorder() {
    when(mockMetricRecorder.registerBatchCallback(any(), any())).thenReturn(mockGaugeRegistration);
  }

  @After
  public void tearDown() {
    if (rlsLbClient != null) {
      rlsLbClient.close();
    }
  }

  private CachedRouteLookupResponse getInSyncContext(
      final RlsProtoData.RouteLookupRequestKey routeLookupRequestKey)
      throws ExecutionException, InterruptedException, TimeoutException {
    final SettableFuture<CachedRouteLookupResponse> responseSettableFuture =
        SettableFuture.create();
    syncContext.execute(() -> responseSettableFuture.set(rlsLbClient.get(routeLookupRequestKey)));
    return responseSettableFuture.get(5, TimeUnit.SECONDS);
  }

  // --------------------------------------------------------------------------
  // Challenge 1: Concurrent calls to maybeRefresh() on stale entries
  // --------------------------------------------------------------------------
  @Test
  public void concurrentCallsToMaybeRefresh_triggersOnlyOneBackgroundRlsRpc() throws Exception {
    setUpRlsLbClient();
    RlsProtoData.RouteLookupRequestKey key =
        RlsProtoData.RouteLookupRequestKey.create(
            ImmutableMap.of("server", "bigtable.googleapis.com", "service-key", "s1", "method-key", "m1"));

    rlsServerImpl.setResponseForKey(key, RouteLookupResponse.create(ImmutableList.of("target1"), "hd-v1"));

    // Populate initial cache entry
    getInSyncContext(key);
    fakeClock.forwardTime(SERVER_LATENCY_MILLIS, TimeUnit.MILLISECONDS);
    CachedRouteLookupResponse resp = getInSyncContext(key);
    assertThat(resp.hasData()).isTrue();
    assertThat(resp.getHeaderData()).isEqualTo("hd-v1");

    // Advance clock past staleAge (240s) so entry becomes STALE
    fakeClock.forwardTime(ROUTE_LOOKUP_CONFIG.staleAgeInNanos(), TimeUnit.NANOSECONDS);

    rlsServerImpl.resetCallCount();

    // Concurrently invoke get() from 20 threads on the stale entry
    int threadCount = 20;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);

    List<Future<CachedRouteLookupResponse>> futures = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      futures.add(executor.submit(() -> {
        startLatch.await();
        try {
          return getInSyncContext(key);
        } finally {
          doneLatch.countDown();
        }
      }));
    }

    startLatch.countDown();
    assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    executor.shutdown();

    // Verify all concurrent get() calls returned valid stale data
    for (Future<CachedRouteLookupResponse> future : futures) {
      CachedRouteLookupResponse r = future.get();
      assertThat(r.hasData()).isTrue();
      assertThat(r.getHeaderData()).isEqualTo("hd-v1");
    }

    // Verify exactly 1 background RLS call was triggered
    assertThat(rlsServerImpl.getCallCount()).isEqualTo(1);
    assertThat(rlsServerImpl.lastRequestReason).isEqualTo(io.grpc.lookup.v1.RouteLookupRequest.Reason.REASON_STALE);
    assertThat(rlsServerImpl.lastStaleHeaderData).isEqualTo("hd-v1");
  }

  // --------------------------------------------------------------------------
  // Challenge 2: Backoff retry behavior following a failed background refresh call
  // --------------------------------------------------------------------------
  @Test
  public void failedBackgroundRefresh_transitionsToBackoff_andRetriesWithReasonMiss() throws Exception {
    setUpRlsLbClient();
    RlsProtoData.RouteLookupRequestKey key =
        RlsProtoData.RouteLookupRequestKey.create(
            ImmutableMap.of("server", "bigtable.googleapis.com", "service-key", "s2", "method-key", "m2"));

    rlsServerImpl.setResponseForKey(key, RouteLookupResponse.create(ImmutableList.of("target1"), "hd-v1"));

    // Step 1: Initial cache lookup
    getInSyncContext(key);
    fakeClock.forwardTime(SERVER_LATENCY_MILLIS, TimeUnit.MILLISECONDS);

    // Step 2: Make entry STALE
    fakeClock.forwardTime(ROUTE_LOOKUP_CONFIG.staleAgeInNanos(), TimeUnit.NANOSECONDS);

    // Set server to fail subsequent RLS RPCs
    rlsServerImpl.setErrorForKey(key, Status.UNAVAILABLE.withDescription("RLS server temporarily down"));

    // Step 3: Trigger background refresh on stale entry
    CachedRouteLookupResponse respBeforeRefreshDone = getInSyncContext(key);
    // Should still return stale data immediately before refresh completes
    assertThat(respBeforeRefreshDone.hasData()).isTrue();
    assertThat(respBeforeRefreshDone.getHeaderData()).isEqualTo("hd-v1");

    // Complete background refresh (which fails with UNAVAILABLE)
    fakeClock.forwardTime(SERVER_LATENCY_MILLIS, TimeUnit.MILLISECONDS);

    // Step 4: After background refresh fails, entry is replaced with BackoffCacheEntry!
    CachedRouteLookupResponse respAfterFailure = getInSyncContext(key);
    assertThat(respAfterFailure.hasData()).isFalse();
    assertThat(respAfterFailure.hasError()).isTrue();
    assertThat(respAfterFailure.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);

    // Step 5: Advance time by backoff period (100ms)
    fakeClock.forwardTime(100, TimeUnit.MILLISECONDS);

    // Fix RLS server so next lookup succeeds
    rlsServerImpl.setResponseForKey(key, RouteLookupResponse.create(ImmutableList.of("target1"), "hd-v2"));
    rlsServerImpl.resetCallCount();

    // Step 6: Next get() after backoff expires should send REASON_MISS with staleHeaderData = null
    CachedRouteLookupResponse respAfterBackoff = getInSyncContext(key);
    assertThat(respAfterBackoff.isPending()).isTrue();

    // Complete the pending lookup
    fakeClock.forwardTime(SERVER_LATENCY_MILLIS, TimeUnit.MILLISECONDS);

    assertThat(rlsServerImpl.lastRequestReason).isEqualTo(io.grpc.lookup.v1.RouteLookupRequest.Reason.REASON_MISS);
    assertThat(rlsServerImpl.lastStaleHeaderData).isEmpty();

    // Step 7: Verify updated data is now cached
    CachedRouteLookupResponse respFinal = getInSyncContext(key);
    assertThat(respFinal.hasData()).isTrue();
    assertThat(respFinal.getHeaderData()).isEqualTo("hd-v2");
  }

  // --------------------------------------------------------------------------
  // Challenge 3: Rapid updates to header_data across successive RLS responses
  // --------------------------------------------------------------------------
  @Test
  public void rapidHeaderDataUpdates_propagatedCorrectlyAcrossRefreshes() throws Exception {
    setUpRlsLbClient();
    RlsProtoData.RouteLookupRequestKey key =
        RlsProtoData.RouteLookupRequestKey.create(
            ImmutableMap.of("server", "bigtable.googleapis.com", "service-key", "s3", "method-key", "m3"));

    String[] headersSequence = new String[] { "hd-100", "hd-200", "hd-300", "", "" };

    // Initial lookup
    rlsServerImpl.setResponseForKey(key, RouteLookupResponse.create(ImmutableList.of("t1"), headersSequence[0]));
    getInSyncContext(key);
    fakeClock.forwardTime(SERVER_LATENCY_MILLIS, TimeUnit.MILLISECONDS);

    for (int i = 1; i < headersSequence.length; i++) {
      String expectedStaleHeader = headersSequence[i - 1];
      String newHeader = headersSequence[i];

      // Make entry stale
      fakeClock.forwardTime(ROUTE_LOOKUP_CONFIG.staleAgeInNanos(), TimeUnit.NANOSECONDS);

      rlsServerImpl.setResponseForKey(key, RouteLookupResponse.create(ImmutableList.of("t1"), newHeader));
      rlsServerImpl.resetCallCount();

      // Stale lookup triggers background refresh
      getInSyncContext(key);
      fakeClock.forwardTime(SERVER_LATENCY_MILLIS, TimeUnit.MILLISECONDS);

      // Verify server received correct stale_header_data
      assertThat(rlsServerImpl.lastRequestReason).isEqualTo(io.grpc.lookup.v1.RouteLookupRequest.Reason.REASON_STALE);
      assertThat(rlsServerImpl.lastStaleHeaderData).isEqualTo(expectedStaleHeader);

      // Verify cached entry now reflects the new header_data
      CachedRouteLookupResponse resp = getInSyncContext(key);
      assertThat(resp.getHeaderData()).isEqualTo(newHeader);
    }
  }

  // --------------------------------------------------------------------------
  // Challenge 4: Verification that RPC picker does not corrupt or leak X-Google-RLS-Data headers
  // --------------------------------------------------------------------------
  @Test
  public void rlsPicker_headerHandling_noLeakOrCorruption() throws Exception {
    setUpRlsLbClient();
    RlsProtoData.RouteLookupRequestKey key1 =
        RlsProtoData.RouteLookupRequestKey.create(
            ImmutableMap.of("server", "bigtable.googleapis.com", "service-key", "service1", "method-key", "create"));

    // Case 1: RLS server returns header_data = "rls-header-alpha"
    rlsServerImpl.setResponseForKey(key1, RouteLookupResponse.create(ImmutableList.of("t1"), "rls-header-alpha"));

    getInSyncContext(key1);
    fakeClock.forwardTime(SERVER_LATENCY_MILLIS, TimeUnit.MILLISECONDS);

    SubchannelPicker picker = fakeHelper.lastPicker;
    assertThat(picker).isNotNull();

    // Call 1: Pre-existing header in caller Metadata should be discarded and replaced by RLS header
    Metadata headers1 = new Metadata();
    headers1.put(RLS_DATA_KEY, "old-pre-existing-header");

    PickResult pickResult1 = picker.pickSubchannel(new PickSubchannelArgsImpl(
        TestMethodDescriptors.voidMethod().toBuilder().setFullMethodName("service1/create").build(),
        headers1,
        CallOptions.DEFAULT,
        new PickDetailsConsumer() {}));

    assertThat(pickResult1.getStatus().isOk()).isTrue();
    Iterable<String> values1 = headers1.getAll(RLS_DATA_KEY);
    assertThat(values1).containsExactly("rls-header-alpha");

    // Case 2: Multi-threaded concurrent pick calls mutating Metadata or picking simultaneously
    int numThreads = 10;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(numThreads);
    List<Metadata> threadHeaders = new ArrayList<>();
    for (int i = 0; i < numThreads; i++) {
      threadHeaders.add(new Metadata());
    }

    for (int i = 0; i < numThreads; i++) {
      final int idx = i;
      Future<?> unused = executor.submit(() -> {
        try {
          startLatch.await();
          Metadata h = threadHeaders.get(idx);
          h.put(RLS_DATA_KEY, "junk-" + idx);
          picker.pickSubchannel(new PickSubchannelArgsImpl(
              TestMethodDescriptors.voidMethod().toBuilder().setFullMethodName("service1/create").build(),
              h,
              CallOptions.DEFAULT,
              new PickDetailsConsumer() {}));
        } catch (Exception e) {
          throw new RuntimeException(e);
        } finally {
          doneLatch.countDown();
        }
      });
    }

    startLatch.countDown();
    assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
    executor.shutdown();

    for (int i = 0; i < numThreads; i++) {
      Iterable<String> vals = threadHeaders.get(i).getAll(RLS_DATA_KEY);
      assertThat(vals).containsExactly("rls-header-alpha");
    }

    // Case 3: Pick when header_data is empty string in RLS response
    RlsProtoData.RouteLookupRequestKey keyNullHeader =
        RlsProtoData.RouteLookupRequestKey.create(
            ImmutableMap.of("server", "bigtable.googleapis.com", "service-key", "service1", "method-key", "createNull"));
    rlsServerImpl.setResponseForKey(keyNullHeader, RouteLookupResponse.create(ImmutableList.of("t1"), ""));

    getInSyncContext(keyNullHeader);
    fakeClock.forwardTime(SERVER_LATENCY_MILLIS, TimeUnit.MILLISECONDS);

    SubchannelPicker picker2 = fakeHelper.lastPicker;
    Metadata headersNull = new Metadata();
    // Pre-set a header to check if empty/null response corrupts or leaves header intact
    headersNull.put(RLS_DATA_KEY, "caller-provided-header");

    PickResult pickResultNull = picker2.pickSubchannel(new PickSubchannelArgsImpl(
        TestMethodDescriptors.voidMethod().toBuilder().setFullMethodName("service1/createNull").build(),
        headersNull,
        CallOptions.DEFAULT,
        new PickDetailsConsumer() {}));

    assertThat(pickResultNull.getStatus().isOk()).isTrue();
    // Verify caller-provided header was NOT corrupted
    assertThat(headersNull.get(RLS_DATA_KEY)).isEqualTo("caller-provided-header");
  }

  // Helper Methods and Classes

  private static RouteLookupConfig getRouteLookupConfig() {
    return RouteLookupConfig.builder()
        .grpcKeybuilders(ImmutableList.of(
            GrpcKeyBuilder.create(
                ImmutableList.of(
                    Name.create("service1", "create"),
                    Name.create("service1", "createNull"),
                    Name.create("s1", "m1"),
                    Name.create("s2", "m2"),
                    Name.create("s3", "m3")),
                ImmutableList.of(
                    NameMatcher.create("user", ImmutableList.of("User", "Parent")),
                    NameMatcher.create("id", ImmutableList.of("X-Google-Id"))),
                ExtraKeys.create("server", "service-key", "method-key"),
                ImmutableMap.of())))
        .lookupService("service1")
        .lookupServiceTimeoutInNanos(TimeUnit.SECONDS.toNanos(10))
        .maxAgeInNanos(TimeUnit.SECONDS.toNanos(300))
        .staleAgeInNanos(TimeUnit.SECONDS.toNanos(240))
        .cacheSizeBytes(1000)
        .defaultTarget(DEFAULT_TARGET)
        .build();
  }

  private static final class CountingBackoffProvider implements BackoffPolicy.Provider {
    private final AtomicInteger count = new AtomicInteger(0);

    @Override
    public BackoffPolicy get() {
      count.incrementAndGet();
      return new BackoffPolicy() {
        @Override
        public long nextBackoffNanos() {
          return TimeUnit.MILLISECONDS.toNanos(100);
        }
      };
    }
  }

  private static final class DynamicRlsServerImpl
      extends RouteLookupServiceGrpc.RouteLookupServiceImplBase {

    private static final Converter<io.grpc.lookup.v1.RouteLookupRequest, RouteLookupRequest>
        REQUEST_CONVERTER = new RlsProtoConverters.RouteLookupRequestConverter();
    private static final Converter<RouteLookupResponse, io.grpc.lookup.v1.RouteLookupResponse>
        RESPONSE_CONVERTER = new RouteLookupResponseConverter().reverse();

    private final long responseDelayNano;
    private final ScheduledExecutorService scheduledExecutorService;

    private Map<RlsProtoData.RouteLookupRequestKey, Object> responseTable =
        Collections.synchronizedMap(new java.util.HashMap<>());

    volatile io.grpc.lookup.v1.RouteLookupRequest.Reason lastRequestReason;
    volatile String lastStaleHeaderData;
    private final AtomicInteger callCount = new AtomicInteger(0);

    public DynamicRlsServerImpl(
        long responseDelayNano, ScheduledExecutorService scheduledExecutorService) {
      checkArgument(responseDelayNano > 0, "delay must be positive");
      this.responseDelayNano = responseDelayNano;
      this.scheduledExecutorService = checkNotNull(scheduledExecutorService, "scheduledExecutorService");
    }

    public void setResponseForKey(RlsProtoData.RouteLookupRequestKey key, RouteLookupResponse response) {
      responseTable.put(key, response);
    }

    public void setErrorForKey(RlsProtoData.RouteLookupRequestKey key, Status status) {
      responseTable.put(key, status);
    }

    public void resetCallCount() {
      callCount.set(0);
    }

    public int getCallCount() {
      return callCount.get();
    }

    @Override
    public void routeLookup(final io.grpc.lookup.v1.RouteLookupRequest request,
        final StreamObserver<io.grpc.lookup.v1.RouteLookupResponse> responseObserver) {
      callCount.incrementAndGet();
      lastRequestReason = request.getReason();
      lastStaleHeaderData = request.getStaleHeaderData();

      ScheduledFuture<?> unused = scheduledExecutorService.schedule(
          () -> {
            RlsProtoData.RouteLookupRequestKey key =
                RlsProtoData.RouteLookupRequestKey.create(
                    REQUEST_CONVERTER.convert(request).keyMap());
            Object entry = responseTable.get(key);
            if (entry == null) {
              responseObserver.onError(Status.NOT_FOUND.withDescription("key not found").asRuntimeException());
            } else if (entry instanceof Status) {
              responseObserver.onError(((Status) entry).asRuntimeException());
            } else if (entry instanceof RouteLookupResponse) {
              responseObserver.onNext(RESPONSE_CONVERTER.convert((RouteLookupResponse) entry));
              responseObserver.onCompleted();
            }
          }, responseDelayNano, TimeUnit.NANOSECONDS);
    }
  }

  private static final class TestLoadBalancerProvider extends LoadBalancerProvider {
    final Set<LoadBalancer> loadBalancers = new HashSet<>();

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int getPriority() {
      return 0;
    }

    @Override
    public String getPolicyName() {
      return "target";
    }

    @Override
    public ConfigOrError parseLoadBalancingPolicyConfig(
        Map<String, ?> rawLoadBalancingPolicyConfig) {
      return ConfigOrError.fromConfig(rawLoadBalancingPolicyConfig);
    }

    @Override
    public LoadBalancer newLoadBalancer(final Helper helper) {
      LoadBalancer loadBalancer = new LoadBalancer() {
        @Override
        public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
          Map<?, ?> config = (Map<?, ?>) resolvedAddresses.getLoadBalancingPolicyConfig();
          if (DEFAULT_TARGET.equals(config.get("target"))) {
            helper.updateBalancingState(
                ConnectivityState.TRANSIENT_FAILURE,
                new FixedResultPicker(
                    PickResult.withError(Status.UNAVAILABLE.withDescription("fallback not available"))));
          } else {
            helper.updateBalancingState(
                ConnectivityState.READY,
                new FixedResultPicker(
                    PickResult.withSubchannel(mock(Subchannel.class, config.get("target").toString()))));
          }
          return Status.OK;
        }

        @Override
        public void handleNameResolutionError(final Status error) {
          helper.updateBalancingState(
              ConnectivityState.TRANSIENT_FAILURE,
              new FixedResultPicker(PickResult.withError(error)));
        }

        @Override
        public void shutdown() {
          loadBalancers.remove(this);
        }
      };

      loadBalancers.add(loadBalancer);
      return loadBalancer;
    }
  }

  private final class FakeHelper extends Helper {
    Server server;
    ManagedChannel oobChannel;
    volatile SubchannelPicker lastPicker;

    void createServerAndRegister(String target) throws IOException {
      server = InProcessServerBuilder.forName(target)
          .addService(rlsServerImpl)
          .directExecutor()
          .build()
          .start();
      grpcCleanupRule.register(server);
    }

    @Override
    public ManagedChannelBuilder<?> createResolvingOobChannelBuilder(
        String target, ChannelCredentials creds) {
      try {
        createServerAndRegister(target);
      } catch (IOException e) {
        throw new RuntimeException("cannot create server: " + target, e);
      }
      final InProcessChannelBuilder builder =
          InProcessChannelBuilder.forName(target).directExecutor();

      class CleaningChannelBuilder extends ForwardingChannelBuilder2<CleaningChannelBuilder> {
        @Override
        protected ManagedChannelBuilder<?> delegate() {
          return builder;
        }

        @Override
        public ManagedChannel build() {
          oobChannel = super.build();
          return grpcCleanupRule.register(oobChannel);
        }
      }

      return new CleaningChannelBuilder();
    }

    @Override
    public ManagedChannel createOobChannel(EquivalentAddressGroup eag, String authority) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void updateBalancingState(
        @Nonnull ConnectivityState newState, @Nonnull SubchannelPicker newPicker) {
      this.lastPicker = newPicker;
    }

    @Override
    public String getAuthority() {
      return "bigtable.googleapis.com:443";
    }

    @Override
    public ChannelCredentials getUnsafeChannelCredentials() {
      return new ChannelCredentials() {
        @Override
        public ChannelCredentials withoutBearerTokens() {
          return this;
        }
      };
    }

    @Override
    public ScheduledExecutorService getScheduledExecutorService() {
      return fakeClock.getScheduledExecutorService();
    }

    @Override
    public SynchronizationContext getSynchronizationContext() {
      return syncContext;
    }

    @Override
    public ChannelLogger getChannelLogger() {
      return mock(ChannelLogger.class);
    }

    @Override
    public MetricRecorder getMetricRecorder() {
      return mockMetricRecorder;
    }

    @Override
    public String getChannelTarget() {
      return "channelTarget";
    }
  }
}
