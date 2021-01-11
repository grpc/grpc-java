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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static io.grpc.rls.CachingRlsLbClient.RLS_DATA_KEY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.base.Converter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ChannelLogger;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ForwardingChannelBuilder;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.PickSubchannelArgsImpl;
import io.grpc.lookup.v1.RouteLookupServiceGrpc;
import io.grpc.rls.CachingRlsLbClient.CacheEntry;
import io.grpc.rls.CachingRlsLbClient.CachedRouteLookupResponse;
import io.grpc.rls.CachingRlsLbClient.RlsPicker;
import io.grpc.rls.DoNotUseDirectScheduledExecutorService.FakeTimeProvider;
import io.grpc.rls.LbPolicyConfiguration.ChildLoadBalancingPolicy;
import io.grpc.rls.LbPolicyConfiguration.ChildPolicyWrapper;
import io.grpc.rls.LruCache.EvictionListener;
import io.grpc.rls.LruCache.EvictionType;
import io.grpc.rls.RlsProtoConverters.RouteLookupResponseConverter;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nonnull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class CachingRlsLbClientTest {

  private static final RouteLookupConfig ROUTE_LOOKUP_CONFIG = getRouteLookupConfig();
  private static final int SERVER_LATENCY_MILLIS = 10;
  private static final String DEFAULT_TARGET = "fallback.cloudbigtable.googleapis.com";

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  @Rule
  public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();

  @Mock
  private EvictionListener<RouteLookupRequest, CacheEntry> evictionListener;
  @Mock
  private SocketAddress socketAddress;

  private final SynchronizationContext syncContext =
      new SynchronizationContext(new UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new RuntimeException(e);
        }
      });
  private final FakeBackoffProvider fakeBackoffProvider = new FakeBackoffProvider();
  private final ResolvedAddressFactory resolvedAddressFactory =
      new ChildLbResolvedAddressFactory(
          ImmutableList.of(new EquivalentAddressGroup(socketAddress)), Attributes.EMPTY);
  private final TestLoadBalancerProvider lbProvider = new TestLoadBalancerProvider();
  private final DoNotUseDirectScheduledExecutorService fakeScheduledExecutorService =
      mock(DoNotUseDirectScheduledExecutorService.class, CALLS_REAL_METHODS);
  private final FakeTimeProvider fakeTimeProvider =
      fakeScheduledExecutorService.getFakeTimeProvider();
  private final StaticFixedDelayRlsServerImpl rlsServerImpl =
      new StaticFixedDelayRlsServerImpl(
          TimeUnit.MILLISECONDS.toNanos(SERVER_LATENCY_MILLIS), fakeScheduledExecutorService);
  private final ChildLoadBalancingPolicy childLbPolicy =
      new ChildLoadBalancingPolicy("target", Collections.<String, Object>emptyMap(), lbProvider);
  private final Helper helper =
      mock(Helper.class, AdditionalAnswers.delegatesTo(new FakeHelper()));
  private final FakeThrottler fakeThrottler = new FakeThrottler();
  private final LbPolicyConfiguration lbPolicyConfiguration =
      new LbPolicyConfiguration(ROUTE_LOOKUP_CONFIG, childLbPolicy);

  private CachingRlsLbClient rlsLbClient;
  private boolean existingEnableOobChannelDirectPath;

  @Before
  public void setUp() throws Exception {
    existingEnableOobChannelDirectPath = CachingRlsLbClient.enableOobChannelDirectPath;
    CachingRlsLbClient.enableOobChannelDirectPath = false;

    rlsLbClient =
        CachingRlsLbClient.newBuilder()
            .setBackoffProvider(fakeBackoffProvider)
            .setResolvedAddressesFactory(resolvedAddressFactory)
            .setEvictionListener(evictionListener)
            .setHelper(helper)
            .setLbPolicyConfig(lbPolicyConfiguration)
            .setThrottler(fakeThrottler)
            .setTimeProvider(fakeTimeProvider)
            .build();
  }

  @After
  public void tearDown() throws Exception {
    rlsLbClient.close();
    CachingRlsLbClient.enableOobChannelDirectPath = existingEnableOobChannelDirectPath;
  }

  private CachedRouteLookupResponse getInSyncContext(
      final RouteLookupRequest request)
      throws ExecutionException, InterruptedException, TimeoutException {
    final SettableFuture<CachedRouteLookupResponse> responseSettableFuture =
        SettableFuture.create();
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        responseSettableFuture.set(rlsLbClient.get(request));
      }
    });
    return responseSettableFuture.get(5, TimeUnit.SECONDS);
  }

  @Test
  public void get_noError_lifeCycle() throws Exception {
    InOrder inOrder = inOrder(evictionListener);
    RouteLookupRequest routeLookupRequest =
        new RouteLookupRequest("server", "/foo/bar", "grpc", ImmutableMap.<String, String>of());
    rlsServerImpl.setLookupTable(
        ImmutableMap.of(
            routeLookupRequest,
            new RouteLookupResponse(ImmutableList.of("target"), "header")));

    // initial request
    CachedRouteLookupResponse resp = getInSyncContext(routeLookupRequest);
    assertThat(resp.isPending()).isTrue();

    // server response
    fakeTimeProvider.forwardTime(SERVER_LATENCY_MILLIS, TimeUnit.MILLISECONDS);

    resp = getInSyncContext(routeLookupRequest);
    assertThat(resp.hasData()).isTrue();

    // cache hit for staled entry
    fakeTimeProvider.forwardTime(ROUTE_LOOKUP_CONFIG.getStaleAgeInMillis(), TimeUnit.MILLISECONDS);

    resp = getInSyncContext(routeLookupRequest);
    assertThat(resp.hasData()).isTrue();

    // async refresh finishes
    fakeTimeProvider.forwardTime(SERVER_LATENCY_MILLIS, TimeUnit.MILLISECONDS);
    inOrder
        .verify(evictionListener)
        .onEviction(eq(routeLookupRequest), any(CacheEntry.class), eq(EvictionType.REPLACED));

    resp = getInSyncContext(routeLookupRequest);

    assertThat(resp.hasData()).isTrue();

    // existing cache expired
    fakeTimeProvider.forwardTime(ROUTE_LOOKUP_CONFIG.getMaxAgeInMillis(), TimeUnit.MILLISECONDS);

    resp = getInSyncContext(routeLookupRequest);

    assertThat(resp.isPending()).isTrue();
    inOrder
        .verify(evictionListener)
        .onEviction(eq(routeLookupRequest), any(CacheEntry.class), eq(EvictionType.EXPIRED));

    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void get_throttledAndRecover() throws Exception {
    RouteLookupRequest routeLookupRequest =
        new RouteLookupRequest("server", "/foo/bar", "grpc", ImmutableMap.<String, String>of());
    rlsServerImpl.setLookupTable(
        ImmutableMap.of(
            routeLookupRequest,
            new RouteLookupResponse(ImmutableList.of("target"), "header")));

    fakeThrottler.nextResult = true;
    fakeBackoffProvider.nextPolicy = createBackoffPolicy(10, TimeUnit.MILLISECONDS);

    CachedRouteLookupResponse resp = getInSyncContext(routeLookupRequest);

    assertThat(resp.hasError()).isTrue();

    fakeTimeProvider.forwardTime(10, TimeUnit.MILLISECONDS);
    // initially backed off entry is backed off again
    verify(evictionListener)
        .onEviction(eq(routeLookupRequest), any(CacheEntry.class), eq(EvictionType.REPLACED));

    resp = getInSyncContext(routeLookupRequest);

    assertThat(resp.hasError()).isTrue();

    // let it pass throttler
    fakeThrottler.nextResult = false;
    fakeTimeProvider.forwardTime(10, TimeUnit.MILLISECONDS);

    resp = getInSyncContext(routeLookupRequest);

    assertThat(resp.isPending()).isTrue();

    // server responses
    fakeTimeProvider.forwardTime(SERVER_LATENCY_MILLIS, TimeUnit.MILLISECONDS);

    resp = getInSyncContext(routeLookupRequest);

    assertThat(resp.hasData()).isTrue();
  }

  @Test
  public void get_updatesLbState() throws Exception {
    InOrder inOrder = inOrder(helper);
    RouteLookupRequest routeLookupRequest =
        new RouteLookupRequest("service1", "/foo/bar", "grpc", ImmutableMap.<String, String>of());
    rlsServerImpl.setLookupTable(
        ImmutableMap.of(
            routeLookupRequest,
            new RouteLookupResponse(
                ImmutableList.of("primary.cloudbigtable.googleapis.com"),
                "header-rls-data-value")));

    // valid channel
    CachedRouteLookupResponse resp = getInSyncContext(routeLookupRequest);
    assertThat(resp.isPending()).isTrue();
    fakeTimeProvider.forwardTime(SERVER_LATENCY_MILLIS, TimeUnit.MILLISECONDS);

    resp = getInSyncContext(routeLookupRequest);
    assertThat(resp.hasData()).isTrue();

    ArgumentCaptor<SubchannelPicker> pickerCaptor = ArgumentCaptor.forClass(SubchannelPicker.class);
    ArgumentCaptor<ConnectivityState> stateCaptor =
        ArgumentCaptor.forClass(ConnectivityState.class);
    inOrder.verify(helper, times(2))
        .updateBalancingState(stateCaptor.capture(), pickerCaptor.capture());

    assertThat(new HashSet<>(pickerCaptor.getAllValues())).hasSize(1);
    assertThat(stateCaptor.getAllValues())
        .containsExactly(ConnectivityState.CONNECTING, ConnectivityState.READY);
    Metadata headers = new Metadata();
    PickResult pickResult = pickerCaptor.getValue().pickSubchannel(
        new PickSubchannelArgsImpl(
            TestMethodDescriptors.voidMethod().toBuilder().setFullMethodName("foo/bar").build(),
            headers,
            CallOptions.DEFAULT));
    assertThat(pickResult.getStatus().isOk()).isTrue();
    assertThat(pickResult.getSubchannel()).isNotNull();
    assertThat(headers.get(RLS_DATA_KEY)).isEqualTo("header-rls-data-value");

    // move backoff further back to only test error behavior
    fakeBackoffProvider.nextPolicy = createBackoffPolicy(100, TimeUnit.MILLISECONDS);
    // try to get invalid
    RouteLookupRequest invalidRouteLookupRequest =
        new RouteLookupRequest(
            "service1", "/doesn/exists", "grpc", ImmutableMap.<String, String>of());
    CachedRouteLookupResponse errorResp = getInSyncContext(invalidRouteLookupRequest);
    assertThat(errorResp.isPending()).isTrue();
    fakeTimeProvider.forwardTime(SERVER_LATENCY_MILLIS, TimeUnit.MILLISECONDS);

    errorResp = getInSyncContext(invalidRouteLookupRequest);
    assertThat(errorResp.hasError()).isTrue();

    // Channel is still READY because the subchannel for method /foo/bar is still READY.
    // Method /doesn/exists will use fallback child balancer and fail immediately.
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());
    pickResult = pickerCaptor.getValue().pickSubchannel(
        new PickSubchannelArgsImpl(
            TestMethodDescriptors.voidMethod().toBuilder()
                .setFullMethodName("doesn/exists")
                .build(),
            headers,
            CallOptions.DEFAULT));
    assertThat(pickResult.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(pickResult.getStatus().getDescription()).isEqualTo("fallback not available");
  }

  @Test
  public void get_childPolicyWrapper_reusedForSameTarget() throws Exception {
    RouteLookupRequest routeLookupRequest =
        new RouteLookupRequest("server", "/foo/bar", "grpc", ImmutableMap.<String, String>of());
    RouteLookupRequest routeLookupRequest2 =
        new RouteLookupRequest("server", "/foo/baz", "grpc", ImmutableMap.<String, String>of());
    rlsServerImpl.setLookupTable(
        ImmutableMap.of(
            routeLookupRequest, new RouteLookupResponse(ImmutableList.of("target"), "header"),
            routeLookupRequest2, new RouteLookupResponse(ImmutableList.of("target"), "header2")));

    CachedRouteLookupResponse resp = getInSyncContext(routeLookupRequest);
    assertThat(resp.isPending()).isTrue();
    fakeTimeProvider.forwardTime(SERVER_LATENCY_MILLIS, TimeUnit.MILLISECONDS);

    resp = getInSyncContext(routeLookupRequest);
    assertThat(resp.hasData()).isTrue();
    assertThat(resp.getHeaderData()).isEqualTo("header");

    ChildPolicyWrapper childPolicyWrapper = resp.getChildPolicyWrapper();
    assertThat(childPolicyWrapper.getTarget()).isEqualTo("target");
    assertThat(childPolicyWrapper.getPicker()).isNotInstanceOf(RlsPicker.class);

    // request2 has same target, it should reuse childPolicyWrapper
    CachedRouteLookupResponse resp2 = getInSyncContext(routeLookupRequest2);
    assertThat(resp2.isPending()).isTrue();
    fakeTimeProvider.forwardTime(SERVER_LATENCY_MILLIS, TimeUnit.MILLISECONDS);

    resp2 = getInSyncContext(routeLookupRequest2);
    assertThat(resp2.hasData()).isTrue();
    assertThat(resp2.getHeaderData()).isEqualTo("header2");
    assertThat(resp2.getChildPolicyWrapper()).isEqualTo(resp.getChildPolicyWrapper());
  }

  private static RouteLookupConfig getRouteLookupConfig() {
    return new RouteLookupConfig(
        ImmutableList.of(
            new GrpcKeyBuilder(
                ImmutableList.of(new Name("service1", "create")),
                ImmutableList.of(
                    new NameMatcher("user", ImmutableList.of("User", "Parent"), true),
                    new NameMatcher("id", ImmutableList.of("X-Google-Id"), true)))),
        /* lookupService= */ "service1",
        /* lookupServiceTimeoutInMillis= */ TimeUnit.SECONDS.toMillis(2),
        /* maxAgeInMillis= */ TimeUnit.SECONDS.toMillis(300),
        /* staleAgeInMillis= */ TimeUnit.SECONDS.toMillis(240),
        /* cacheSizeBytes= */ 1000,
        /* validTargets= */ ImmutableList.of("a valid target"),
        DEFAULT_TARGET);
  }

  private static BackoffPolicy createBackoffPolicy(final long delay, final TimeUnit unit) {
    checkArgument(delay > 0, "delay should be positive");
    checkNotNull(unit, "unit");
    return
        new BackoffPolicy() {
          @Override
          public long nextBackoffNanos() {
            return TimeUnit.NANOSECONDS.convert(delay, unit);
          }
        };
  }

  private static final class FakeBackoffProvider implements BackoffPolicy.Provider {

    private BackoffPolicy nextPolicy = createBackoffPolicy(100, TimeUnit.MILLISECONDS);

    @Override
    public BackoffPolicy get() {
      return nextPolicy;
    }
  }

  /**
   * A load balancer that immediately goes to READY when using the rls response target and
   * immediately fails when using the fallback target.
   */
  private static final class TestLoadBalancerProvider extends LoadBalancerProvider {

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
      return null;
    }

    @Override
    public ConfigOrError parseLoadBalancingPolicyConfig(
        Map<String, ?> rawLoadBalancingPolicyConfig) {
      return ConfigOrError.fromConfig(rawLoadBalancingPolicyConfig);
    }

    @Override
    public LoadBalancer newLoadBalancer(final Helper helper) {
      return new LoadBalancer() {

        @Override
        public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
          Map<?, ?> config = (Map<?, ?>) resolvedAddresses.getLoadBalancingPolicyConfig();
          if (DEFAULT_TARGET.equals(config.get("target"))) {
            helper.updateBalancingState(
                ConnectivityState.TRANSIENT_FAILURE,
                new SubchannelPicker() {
                  @Override
                  public PickResult pickSubchannel(PickSubchannelArgs args) {
                    return PickResult.withError(
                        Status.UNAVAILABLE.withDescription("fallback not available"));
                  }
                });
          } else {
            helper.updateBalancingState(
                ConnectivityState.READY,
                new SubchannelPicker() {
                  @Override
                  public PickResult pickSubchannel(PickSubchannelArgs args) {
                    return PickResult.withSubchannel(mock(Subchannel.class));
                  }
                });
          }
        }

        @Override
        public void handleNameResolutionError(final Status error) {
          class ErrorPicker extends SubchannelPicker {
            @Override
            public PickResult pickSubchannel(PickSubchannelArgs args) {
              return PickResult.withError(error);
            }
          }

          helper.updateBalancingState(ConnectivityState.TRANSIENT_FAILURE, new ErrorPicker());
        }

        @Override
        public void shutdown() {
        }
      };
    }
  }

  private static final class StaticFixedDelayRlsServerImpl
      extends RouteLookupServiceGrpc.RouteLookupServiceImplBase {

    private static final Converter<io.grpc.lookup.v1.RouteLookupRequest, RouteLookupRequest>
        REQUEST_CONVERTER = new RlsProtoConverters.RouteLookupRequestConverter();
    private static final Converter<RouteLookupResponse, io.grpc.lookup.v1.RouteLookupResponse>
        RESPONSE_CONVERTER = new RouteLookupResponseConverter().reverse();

    private final long responseDelayNano;
    private final ScheduledExecutorService scheduledExecutorService;

    private Map<RouteLookupRequest, RouteLookupResponse> lookupTable = ImmutableMap.of();

    public StaticFixedDelayRlsServerImpl(
        long responseDelayNano, ScheduledExecutorService scheduledExecutorService) {
      checkArgument(responseDelayNano > 0, "delay must be positive");
      this.responseDelayNano = responseDelayNano;
      this.scheduledExecutorService =
          checkNotNull(scheduledExecutorService, "scheduledExecutorService");
    }

    private void setLookupTable(Map<RouteLookupRequest, RouteLookupResponse> lookupTable) {
      this.lookupTable = checkNotNull(lookupTable, "lookupTable");
    }

    @Override
    public void routeLookup(final io.grpc.lookup.v1.RouteLookupRequest request,
        final StreamObserver<io.grpc.lookup.v1.RouteLookupResponse> responseObserver) {
      ScheduledFuture<?> unused =
          scheduledExecutorService.schedule(
              new Runnable() {
                @Override
                public void run() {
                  RouteLookupResponse response =
                      lookupTable.get(REQUEST_CONVERTER.convert(request));
                  if (response == null) {
                    responseObserver.onError(new RuntimeException("not found"));
                  } else {
                    responseObserver.onNext(RESPONSE_CONVERTER.convert(response));
                    responseObserver.onCompleted();
                  }
                }
              }, responseDelayNano, TimeUnit.NANOSECONDS);
    }
  }

  private final class FakeHelper extends Helper {

    @Override
    public ManagedChannelBuilder<?> createResolvingOobChannelBuilder(String target) {
      try {
        grpcCleanupRule.register(
            InProcessServerBuilder.forName(target)
                .addService(rlsServerImpl)
                .directExecutor()
                .build()
                .start());
      } catch (IOException e) {
        throw new RuntimeException("cannot create server: " + target, e);
      }
      final InProcessChannelBuilder builder =
          InProcessChannelBuilder.forName(target).directExecutor();

      class CleaningChannelBuilder extends ForwardingChannelBuilder<CleaningChannelBuilder> {

        @Override
        protected ManagedChannelBuilder<?> delegate() {
          return builder;
        }

        @Override
        public ManagedChannel build() {
          return grpcCleanupRule.register(super.build());
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
      // no-op
    }

    @Override
    public String getAuthority() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledExecutorService getScheduledExecutorService() {
      return fakeScheduledExecutorService;
    }

    @Override
    public SynchronizationContext getSynchronizationContext() {
      return syncContext;
    }

    @Override
    public ChannelLogger getChannelLogger() {
      return mock(ChannelLogger.class);
    }
  }

  private static final class FakeThrottler implements Throttler {

    private boolean nextResult = false;

    @Override
    public boolean shouldThrottle() {
      return nextResult;
    }

    @Override
    public void registerBackendResponse(boolean throttled) {
      // no-op
    }
  }
}
