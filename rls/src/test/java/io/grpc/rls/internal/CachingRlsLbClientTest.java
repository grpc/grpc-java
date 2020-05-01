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

package io.grpc.rls.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.base.Converter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.ManagedChannel;
import io.grpc.NameResolver.Factory;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.lookup.v1.RouteLookupServiceGrpc;
import io.grpc.rls.internal.CachingRlsLbClient.CacheEntry;
import io.grpc.rls.internal.CachingRlsLbClient.CachedRouteLookupResponse;
import io.grpc.rls.internal.CachingRlsLbClient.RlsPicker;
import io.grpc.rls.internal.DoNotUseDirectScheduledExecutorService.FakeTimeProvider;
import io.grpc.rls.internal.LbPolicyConfiguration.ChildLoadBalancingPolicy;
import io.grpc.rls.internal.LbPolicyConfiguration.ChildPolicyWrapper;
import io.grpc.rls.internal.LruCache.EvictionListener;
import io.grpc.rls.internal.LruCache.EvictionType;
import io.grpc.rls.internal.RlsProtoConverters.RouteLookupResponseConverter;
import io.grpc.rls.internal.RlsProtoData.GrpcKeyBuilder;
import io.grpc.rls.internal.RlsProtoData.GrpcKeyBuilder.Name;
import io.grpc.rls.internal.RlsProtoData.NameMatcher;
import io.grpc.rls.internal.RlsProtoData.RequestProcessingStrategy;
import io.grpc.rls.internal.RlsProtoData.RouteLookupConfig;
import io.grpc.rls.internal.RlsProtoData.RouteLookupRequest;
import io.grpc.rls.internal.RlsProtoData.RouteLookupResponse;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
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

  @Before
  public void setUp() throws Exception {
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
            new RouteLookupResponse("target", "header")));

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
            new RouteLookupResponse("target", "header")));

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
        new RouteLookupRequest("server", "/foo/bar", "grpc", ImmutableMap.<String, String>of());
    rlsServerImpl.setLookupTable(
        ImmutableMap.of(
            routeLookupRequest,
            new RouteLookupResponse("target", "header")));

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
    assertThat(pickerCaptor.getValue()).isInstanceOf(RlsPicker.class);

    // move backoff further back to only test error behavior
    fakeBackoffProvider.nextPolicy = createBackoffPolicy(100, TimeUnit.MILLISECONDS);
    // try to get invalid
    RouteLookupRequest invalidRouteLookupRequest =
        new RouteLookupRequest(
            "unknown_server", "/doesn/exists", "grpc", ImmutableMap.<String, String>of());
    CachedRouteLookupResponse errorResp = getInSyncContext(invalidRouteLookupRequest);
    assertThat(errorResp.isPending()).isTrue();
    fakeTimeProvider.forwardTime(SERVER_LATENCY_MILLIS, TimeUnit.MILLISECONDS);

    errorResp = getInSyncContext(invalidRouteLookupRequest);
    assertThat(errorResp.hasError()).isTrue();

    inOrder.verify(helper, never())
        .updateBalancingState(any(ConnectivityState.class), any(SubchannelPicker.class));
  }

  @Test
  public void get_childPolicyWrapper_reusedForSameTarget() throws Exception {
    RouteLookupRequest routeLookupRequest =
        new RouteLookupRequest("server", "/foo/bar", "grpc", ImmutableMap.<String, String>of());
    RouteLookupRequest routeLookupRequest2 =
        new RouteLookupRequest("server", "/foo/baz", "grpc", ImmutableMap.<String, String>of());
    rlsServerImpl.setLookupTable(
        ImmutableMap.of(
            routeLookupRequest, new RouteLookupResponse("target", "header"),
            routeLookupRequest2, new RouteLookupResponse("target", "header2")));

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
        /* cacheSize= */ 1000,
        /* validTargets= */ ImmutableList.of("a valid target"),
        /* defaultTarget= */ "us_east_1.cloudbigtable.googleapis.com",
        RequestProcessingStrategy.SYNC_LOOKUP_CLIENT_SEES_ERROR);
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
    public LoadBalancer newLoadBalancer(final Helper helper) {
      return new LoadBalancer() {

        @Override
        public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
          // TODO: make the picker accessible
          helper.updateBalancingState(ConnectivityState.READY, mock(SubchannelPicker.class));
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
    public ManagedChannel createResolvingOobChannel(String target) {
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
      return InProcessChannelBuilder.forName(target).directExecutor().build();
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
    @Deprecated
    public Factory getNameResolverFactory() {
      throw new UnsupportedOperationException();
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
