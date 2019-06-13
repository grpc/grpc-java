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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.internal.FakeClock;
import io.grpc.internal.GrpcAttributes;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.xds.XdsLoadBalancer.FallbackManager;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit test for {@link FallbackManager}.
 */
@RunWith(JUnit4.class)
public class FallbackManagerTest {

  private static final long FALLBACK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);

  private final FakeClock fakeClock = new FakeClock();
  private final LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();

  private final LoadBalancerProvider fakeFallbackLbProvider = new LoadBalancerProvider() {
    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int getPriority() {
      return 5;
    }

    @Override
    public String getPolicyName() {
      return fallbackPolicy.getPolicyName();
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      return fakeFallbackLb;
    }
  };

  private final LoadBalancerProvider fakeRoundRonbinLbProvider = new LoadBalancerProvider() {
    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int getPriority() {
      return 5;
    }

    @Override
    public String getPolicyName() {
      return "round_robin";
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      return fakeRoundRobinLb;
    }
  };

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });

  @Mock
  private Helper helper;
  @Mock
  private LoadBalancer fakeRoundRobinLb;
  @Mock
  private LoadBalancer fakeFallbackLb;
  @Mock
  private ChannelLogger channelLogger;

  private FallbackManager fallbackManager;
  private LbConfig fallbackPolicy;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    doReturn(syncContext).when(helper).getSynchronizationContext();
    doReturn(fakeClock.getScheduledExecutorService()).when(helper).getScheduledExecutorService();
    doReturn(channelLogger).when(helper).getChannelLogger();
    fallbackPolicy = new LbConfig("test_policy", new HashMap<String, Void>());
    lbRegistry.register(fakeRoundRonbinLbProvider);
    lbRegistry.register(fakeFallbackLbProvider);
    fallbackManager = new FallbackManager(helper, lbRegistry);
  }

  @After
  public void tearDown() {
    assertThat(fakeClock.getPendingTasks()).isEmpty();
  }

  @Test
  public void useFallbackWhenTimeout() {
    fallbackManager.startFallbackTimer();
    List<EquivalentAddressGroup> eags = ImmutableList.of(
        new EquivalentAddressGroup(ImmutableList.<SocketAddress>of(new InetSocketAddress(8080))));
    fallbackManager.updateFallbackServers(
        eags, Attributes.EMPTY, fallbackPolicy);

    assertThat(fallbackManager.isInFallbackMode()).isFalse();
    verify(fakeFallbackLb, never())
        .handleResolvedAddresses(ArgumentMatchers.any(ResolvedAddresses.class));

    fakeClock.forwardTime(FALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);

    assertThat(fallbackManager.isInFallbackMode()).isTrue();
    verify(fakeFallbackLb).handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(eags)
            .setAttributes(
                Attributes.newBuilder()
                    .set(
                        LoadBalancer.ATTR_LOAD_BALANCING_CONFIG,
                        fallbackPolicy.getRawConfigValue())
                    .build())
            .build());
  }

  @Test
  public void fallback_handleBackendsEagsOnly() {
    fallbackManager.startFallbackTimer();
    EquivalentAddressGroup eag0 = new EquivalentAddressGroup(
        ImmutableList.<SocketAddress>of(new InetSocketAddress(8080)));
    Attributes attributes = Attributes
        .newBuilder()
        .set(GrpcAttributes.ATTR_LB_ADDR_AUTHORITY, "this is a balancer address")
        .build();
    EquivalentAddressGroup eag1 = new EquivalentAddressGroup(
        ImmutableList.<SocketAddress>of(new InetSocketAddress(8081)), attributes);
    EquivalentAddressGroup eag2 = new EquivalentAddressGroup(
        ImmutableList.<SocketAddress>of(new InetSocketAddress(8082)));
    List<EquivalentAddressGroup> eags = ImmutableList.of(eag0, eag1, eag2);
    fallbackManager.updateFallbackServers(
        eags, Attributes.EMPTY, fallbackPolicy);

    fakeClock.forwardTime(FALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);

    assertThat(fallbackManager.isInFallbackMode()).isTrue();
    verify(fakeFallbackLb).handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.of(eag0, eag2))
            .setAttributes(
                Attributes.newBuilder()
                    .set(
                        LoadBalancer.ATTR_LOAD_BALANCING_CONFIG,
                        fallbackPolicy.getRawConfigValue())
                    .build())
            .build());
  }

  @Test
  public void fallback_handleGrpclbAddresses() {
    lbRegistry.deregister(fakeFallbackLbProvider);
    fallbackPolicy = new LbConfig("grpclb", new HashMap<String, Void>());
    lbRegistry.register(fakeFallbackLbProvider);

    fallbackManager.startFallbackTimer();
    EquivalentAddressGroup eag0 = new EquivalentAddressGroup(
        ImmutableList.<SocketAddress>of(new InetSocketAddress(8080)));
    Attributes attributes = Attributes
        .newBuilder()
        .set(GrpcAttributes.ATTR_LB_ADDR_AUTHORITY, "this is a balancer address")
        .build();
    EquivalentAddressGroup eag1 = new EquivalentAddressGroup(
        ImmutableList.<SocketAddress>of(new InetSocketAddress(8081)), attributes);
    EquivalentAddressGroup eag2 = new EquivalentAddressGroup(
        ImmutableList.<SocketAddress>of(new InetSocketAddress(8082)));
    List<EquivalentAddressGroup> eags = ImmutableList.of(eag0, eag1, eag2);
    fallbackManager.updateFallbackServers(
        eags, Attributes.EMPTY, fallbackPolicy);

    fakeClock.forwardTime(FALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);

    assertThat(fallbackManager.isInFallbackMode()).isTrue();
    verify(fakeFallbackLb).handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(eags)
            .setAttributes(
                Attributes.newBuilder()
                    .set(
                        LoadBalancer.ATTR_LOAD_BALANCING_CONFIG,
                        fallbackPolicy.getRawConfigValue())
                    .build())
            .build());
  }

  @Test
  public void fallback_onlyGrpclbAddresses_NoBackendAddress() {
    lbRegistry.deregister(fakeFallbackLbProvider);
    fallbackPolicy = new LbConfig("not_grpclb", new HashMap<String, Void>());
    lbRegistry.register(fakeFallbackLbProvider);

    fallbackManager.startFallbackTimer();
    Attributes attributes = Attributes
        .newBuilder()
        .set(GrpcAttributes.ATTR_LB_ADDR_AUTHORITY, "this is a balancer address")
        .build();
    EquivalentAddressGroup eag1 = new EquivalentAddressGroup(
        ImmutableList.<SocketAddress>of(new InetSocketAddress(8081)), attributes);
    EquivalentAddressGroup eag2 = new EquivalentAddressGroup(
        ImmutableList.<SocketAddress>of(new InetSocketAddress(8082)), attributes);
    List<EquivalentAddressGroup> eags = ImmutableList.of(eag1, eag2);
    fallbackManager.updateFallbackServers(
        eags, Attributes.EMPTY, fallbackPolicy);

    fakeClock.forwardTime(FALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);

    assertThat(fallbackManager.isInFallbackMode()).isTrue();
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(fakeFallbackLb).handleNameResolutionError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
  }

  @Test
  public void cancelFallback() {
    fallbackManager.startFallbackTimer();
    List<EquivalentAddressGroup> eags = ImmutableList.of(
        new EquivalentAddressGroup(ImmutableList.<SocketAddress>of(new InetSocketAddress(8080))));
    fallbackManager.updateFallbackServers(
        eags, Attributes.EMPTY, fallbackPolicy);

    fallbackManager.cancelFallback();

    fakeClock.forwardTime(FALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);

    assertThat(fallbackManager.isInFallbackMode()).isFalse();
    verify(fakeFallbackLb, never())
        .handleResolvedAddresses(ArgumentMatchers.any(ResolvedAddresses.class));
  }
}
