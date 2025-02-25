/*
 * Copyright 2015 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.net.InetAddresses;
import com.google.common.testing.FakeTicker;
import io.grpc.ChannelLogger;
import io.grpc.EquivalentAddressGroup;
import io.grpc.HttpConnectProxiedSocketAddress;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.NameResolver.ResolutionResult;
import io.grpc.NameResolver.ServiceConfigParser;
import io.grpc.ProxyDetector;
import io.grpc.StaticTestingClassLoader;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.internal.DnsNameResolver.AddressResolver;
import io.grpc.internal.DnsNameResolver.ResourceResolver;
import io.grpc.internal.DnsNameResolver.ResourceResolverFactory;
import io.grpc.internal.JndiResourceResolverFactory.JndiResourceResolver;
import io.grpc.internal.JndiResourceResolverFactory.RecordFetcher;
import io.grpc.internal.SharedResourceHolder.Resource;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.ExpectedException;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link DnsNameResolver}. */
@RunWith(JUnit4.class)
public class DnsNameResolverTest {

  @Rule public final TestRule globalTimeout = new DisableOnDebug(Timeout.seconds(10));
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();
  @SuppressWarnings("deprecation") // https://github.com/grpc/grpc-java/issues/7467
  @Rule public final ExpectedException thrown = ExpectedException.none();

  private final Map<String, ?> serviceConfig = new LinkedHashMap<>();

  private static final int DEFAULT_PORT = 887;
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });

  private final DnsNameResolverProvider provider = new DnsNameResolverProvider();
  private final FakeClock fakeClock = new FakeClock();
  private final FakeClock fakeExecutor = new FakeClock();
  private static final FakeClock.TaskFilter NAME_RESOLVER_REFRESH_TASK_FILTER =
      new FakeClock.TaskFilter() {
        @Override
        public boolean shouldAccept(Runnable command) {
          return command.toString().contains(
              RetryingNameResolver.DelayedNameResolverRefresh.class.getName());
        }
      };

  private final FakeExecutorResource fakeExecutorResource = new FakeExecutorResource();

  private final class FakeExecutorResource implements Resource<Executor> {
    private final AtomicInteger createCount = new AtomicInteger();

    @Override
    public Executor create() {
      createCount.incrementAndGet();
      return fakeExecutor.getScheduledExecutorService();
    }

    @Override
    public void close(Executor instance) {}
  }

  private final NameResolver.Args args = NameResolver.Args.newBuilder()
      .setDefaultPort(DEFAULT_PORT)
      .setProxyDetector(GrpcUtil.DEFAULT_PROXY_DETECTOR)
      .setSynchronizationContext(syncContext)
      .setServiceConfigParser(mock(ServiceConfigParser.class))
      .setChannelLogger(mock(ChannelLogger.class))
      .setScheduledExecutorService(fakeExecutor.getScheduledExecutorService())
      .build();

  @Mock
  private NameResolver.Listener2 mockListener;
  @Captor
  private ArgumentCaptor<ResolutionResult> resultCaptor;
  @Nullable
  private String networkaddressCacheTtlPropertyValue;
  @Mock
  private RecordFetcher recordFetcher;
  @Mock private ProxyDetector mockProxyDetector;

  private RetryingNameResolver newResolver(String name, int defaultPort) {
    return newResolver(
        name, defaultPort, GrpcUtil.NOOP_PROXY_DETECTOR, Stopwatch.createUnstarted());
  }

  private RetryingNameResolver newResolver(String name, int defaultPort, boolean isAndroid) {
    return newResolver(
        name, defaultPort, GrpcUtil.NOOP_PROXY_DETECTOR, Stopwatch.createUnstarted(),
        isAndroid);
  }

  private RetryingNameResolver newResolver(
      String name,
      int defaultPort,
      ProxyDetector proxyDetector,
      Stopwatch stopwatch) {
    return newResolver(name, defaultPort, proxyDetector, stopwatch, false);
  }

  private RetryingNameResolver newResolver(
      String name,
      final int defaultPort,
      final ProxyDetector proxyDetector,
      Stopwatch stopwatch,
      boolean isAndroid) {
    NameResolver.Args args =
        NameResolver.Args.newBuilder()
            .setDefaultPort(defaultPort)
            .setProxyDetector(proxyDetector)
            .setSynchronizationContext(syncContext)
            .setServiceConfigParser(mock(ServiceConfigParser.class))
            .setChannelLogger(mock(ChannelLogger.class))
            .setScheduledExecutorService(fakeExecutor.getScheduledExecutorService())
            .build();
    return newResolver(name, stopwatch, isAndroid, args);
  }

  private RetryingNameResolver newResolver(
      String name,
      Stopwatch stopwatch,
      boolean isAndroid,
      NameResolver.Args args) {
    DnsNameResolver dnsResolver = new DnsNameResolver(null, name, args, fakeExecutorResource,
        stopwatch, isAndroid);
    // By default, using the mocked ResourceResolver to avoid I/O
    dnsResolver.setResourceResolver(new JndiResourceResolver(recordFetcher));

    // In practice the DNS name resolver provider always wraps the resolver in a
    // RetryingNameResolver which adds retry capabilities to it. We use the same setup here.
    return new RetryingNameResolver(
        dnsResolver,
        new BackoffPolicyRetryScheduler(
            new ExponentialBackoffPolicy.Provider(),
            fakeExecutor.getScheduledExecutorService(),
            syncContext
        ),
        syncContext);
  }

  @Before
  public void setUp() {
    DnsNameResolver.enableJndi = true;
    networkaddressCacheTtlPropertyValue =
        System.getProperty(DnsNameResolver.NETWORKADDRESS_CACHE_TTL_PROPERTY);

    // By default the mock listener processes the result successfully.
    when(mockListener.onResult2(isA(ResolutionResult.class))).thenReturn(Status.OK);
  }

  @After
  public void restoreSystemProperty() {
    if (networkaddressCacheTtlPropertyValue == null) {
      System.clearProperty(DnsNameResolver.NETWORKADDRESS_CACHE_TTL_PROPERTY);
    } else {
      System.setProperty(
          DnsNameResolver.NETWORKADDRESS_CACHE_TTL_PROPERTY,
          networkaddressCacheTtlPropertyValue);
    }
  }

  @Test
  public void invalidDnsName() throws Exception {
    testInvalidUri(new URI("dns", null, "/[invalid]", null));
  }

  @Test
  public void validIpv6() throws Exception {
    testValidUri(new URI("dns", null, "/[::1]", null), "[::1]", DEFAULT_PORT);
  }

  @Test
  public void validDnsNameWithoutPort() throws Exception {
    testValidUri(new URI("dns", null, "/foo.googleapis.com", null),
        "foo.googleapis.com", DEFAULT_PORT);
  }

  @Test
  public void validDnsNameWithPort() throws Exception {
    testValidUri(new URI("dns", null, "/foo.googleapis.com:456", null),
        "foo.googleapis.com:456", 456);
  }

  @Test
  public void nullDnsName() {
    try {
      newResolver(null, DEFAULT_PORT);
      fail("Expected NullPointerException");
    } catch (NullPointerException e) {
      // expected
    }
  }

  @Test
  public void invalidDnsName_containsUnderscore() {
    try {
      newResolver("host_1", DEFAULT_PORT);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      // expected
    }
  }

  @Test
  public void resolve_androidIgnoresPropertyValue() throws Exception {
    System.setProperty(DnsNameResolver.NETWORKADDRESS_CACHE_TTL_PROPERTY, Long.toString(2));
    resolveNeverCache(true);
  }

  @Test
  public void resolve_androidIgnoresPropertyValueCacheForever() throws Exception {
    System.setProperty(DnsNameResolver.NETWORKADDRESS_CACHE_TTL_PROPERTY, Long.toString(-1));
    resolveNeverCache(true);
  }

  @Test
  public void resolve_neverCache() throws Exception {
    System.setProperty(DnsNameResolver.NETWORKADDRESS_CACHE_TTL_PROPERTY, "0");
    resolveNeverCache(false);
  }

  private void resolveNeverCache(boolean isAndroid) throws Exception {
    final List<InetAddress> answer1 = createAddressList(2);
    final List<InetAddress> answer2 = createAddressList(1);
    String name = "foo.googleapis.com";

    RetryingNameResolver resolver = newResolver(name, 81, isAndroid);
    DnsNameResolver dnsResolver = (DnsNameResolver) resolver.getRetriedNameResolver();
    AddressResolver mockResolver = mock(AddressResolver.class);
    when(mockResolver.resolveAddress(anyString())).thenReturn(answer1).thenReturn(answer2);
    dnsResolver.setAddressResolver(mockResolver);

    resolver.start(mockListener);
    assertEquals(1, fakeExecutor.runDueTasks());
    verify(mockListener).onResult2(resultCaptor.capture());
    assertAnswerMatches(answer1, 81, resultCaptor.getValue());
    assertEquals(0, fakeClock.numPendingTasks());

    resolver.refresh();
    assertEquals(1, fakeExecutor.runDueTasks());
    verify(mockListener, times(2)).onResult2(resultCaptor.capture());
    assertAnswerMatches(answer2, 81, resultCaptor.getValue());
    assertEquals(0, fakeClock.numPendingTasks());
    assertEquals(0, fakeExecutor.numPendingTasks());

    syncContext.execute(() -> resolver.shutdown());

    verify(mockResolver, times(2)).resolveAddress(anyString());
  }

  @Test
  public void testExecutor_default() throws Exception {
    final List<InetAddress> answer = createAddressList(2);

    RetryingNameResolver resolver = newResolver("foo.googleapis.com", 81);
    DnsNameResolver dnsResolver = (DnsNameResolver) resolver.getRetriedNameResolver();
    AddressResolver mockResolver = mock(AddressResolver.class);
    when(mockResolver.resolveAddress(anyString())).thenReturn(answer);
    dnsResolver.setAddressResolver(mockResolver);

    resolver.start(mockListener);
    assertEquals(1, fakeExecutor.runDueTasks());
    verify(mockListener).onResult2(resultCaptor.capture());
    assertAnswerMatches(answer, 81, resultCaptor.getValue());
    assertEquals(0, fakeClock.numPendingTasks());
    assertEquals(0, fakeExecutor.numPendingTasks());

    syncContext.execute(() -> resolver.shutdown());

    assertThat(fakeExecutorResource.createCount.get()).isEqualTo(1);
  }

  @Test
  public void testExecutor_custom() throws Exception {
    final List<InetAddress> answer = createAddressList(2);
    final AtomicInteger executions = new AtomicInteger();

    NameResolver.Args args =
        NameResolver.Args.newBuilder()
            .setDefaultPort(81)
            .setProxyDetector(GrpcUtil.NOOP_PROXY_DETECTOR)
            .setSynchronizationContext(syncContext)
            .setServiceConfigParser(mock(ServiceConfigParser.class))
            .setChannelLogger(mock(ChannelLogger.class))
            .setScheduledExecutorService(fakeExecutor.getScheduledExecutorService())
            .setOffloadExecutor(
                new Executor() {
                  @Override
                  public void execute(Runnable command) {
                    executions.incrementAndGet();
                    command.run();
                  }
                })
            .build();

    RetryingNameResolver resolver = newResolver(
        "foo.googleapis.com", Stopwatch.createUnstarted(), false, args);
    DnsNameResolver dnsResolver = (DnsNameResolver) resolver.getRetriedNameResolver();
    AddressResolver mockResolver = mock(AddressResolver.class);
    when(mockResolver.resolveAddress(anyString())).thenReturn(answer);
    dnsResolver.setAddressResolver(mockResolver);

    resolver.start(mockListener);
    assertEquals(0, fakeExecutor.runDueTasks());
    verify(mockListener).onResult2(resultCaptor.capture());
    assertAnswerMatches(answer, 81, resultCaptor.getValue());
    assertEquals(0, fakeClock.numPendingTasks());
    assertEquals(0, fakeExecutor.numPendingTasks());

    syncContext.execute(() -> resolver.shutdown());

    assertThat(fakeExecutorResource.createCount.get()).isEqualTo(0);
    assertThat(executions.get()).isEqualTo(1);
  }

  @Test
  public void resolve_cacheForever() throws Exception {
    System.setProperty(DnsNameResolver.NETWORKADDRESS_CACHE_TTL_PROPERTY, "-1");
    final List<InetAddress> answer1 = createAddressList(2);
    String name = "foo.googleapis.com";
    FakeTicker fakeTicker = new FakeTicker();

    RetryingNameResolver resolver = newResolver(
        name, 81, GrpcUtil.NOOP_PROXY_DETECTOR, Stopwatch.createUnstarted(fakeTicker));
    DnsNameResolver dnsResolver = (DnsNameResolver) resolver.getRetriedNameResolver();
    AddressResolver mockResolver = mock(AddressResolver.class);
    when(mockResolver.resolveAddress(anyString()))
        .thenReturn(answer1)
        .thenThrow(new AssertionError("should not called twice"));
    dnsResolver.setAddressResolver(mockResolver);

    resolver.start(mockListener);
    assertEquals(1, fakeExecutor.runDueTasks());
    verify(mockListener).onResult2(resultCaptor.capture());
    assertAnswerMatches(answer1, 81, resultCaptor.getValue());
    assertEquals(0, fakeClock.numPendingTasks());

    fakeTicker.advance(1, TimeUnit.DAYS);
    resolver.refresh();
    assertEquals(0, fakeExecutor.runDueTasks());
    assertEquals(0, fakeClock.numPendingTasks());
    verifyNoMoreInteractions(mockListener);

    syncContext.execute(() -> resolver.shutdown());

    verify(mockResolver).resolveAddress(anyString());
  }

  @Test
  public void resolve_usingCache() throws Exception {
    long ttl = 60;
    System.setProperty(DnsNameResolver.NETWORKADDRESS_CACHE_TTL_PROPERTY, Long.toString(ttl));
    final List<InetAddress> answer = createAddressList(2);
    String name = "foo.googleapis.com";
    FakeTicker fakeTicker = new FakeTicker();

    RetryingNameResolver resolver = newResolver(
        name, 81, GrpcUtil.NOOP_PROXY_DETECTOR, Stopwatch.createUnstarted(fakeTicker));
    DnsNameResolver dnsResolver = (DnsNameResolver) resolver.getRetriedNameResolver();
    AddressResolver mockResolver = mock(AddressResolver.class);
    when(mockResolver.resolveAddress(anyString()))
        .thenReturn(answer)
        .thenThrow(new AssertionError("should not reach here."));
    dnsResolver.setAddressResolver(mockResolver);

    resolver.start(mockListener);
    assertEquals(1, fakeExecutor.runDueTasks());
    verify(mockListener).onResult2(resultCaptor.capture());
    assertAnswerMatches(answer, 81, resultCaptor.getValue());
    assertEquals(0, fakeClock.numPendingTasks());

    // this refresh should return cached result
    fakeTicker.advance(ttl - 1, TimeUnit.SECONDS);
    resolver.refresh();
    assertEquals(0, fakeExecutor.runDueTasks());
    assertEquals(0, fakeClock.numPendingTasks());
    verifyNoMoreInteractions(mockListener);

    syncContext.execute(() -> resolver.shutdown());

    verify(mockResolver).resolveAddress(anyString());
  }

  @Test
  public void resolve_cacheExpired() throws Exception {
    long ttl = 60;
    System.setProperty(DnsNameResolver.NETWORKADDRESS_CACHE_TTL_PROPERTY, Long.toString(ttl));
    final List<InetAddress> answer1 = createAddressList(2);
    final List<InetAddress> answer2 = createAddressList(1);
    String name = "foo.googleapis.com";
    FakeTicker fakeTicker = new FakeTicker();

    RetryingNameResolver resolver = newResolver(
        name, 81, GrpcUtil.NOOP_PROXY_DETECTOR, Stopwatch.createUnstarted(fakeTicker));
    DnsNameResolver dnsResolver = (DnsNameResolver) resolver.getRetriedNameResolver();
    AddressResolver mockResolver = mock(AddressResolver.class);
    when(mockResolver.resolveAddress(anyString())).thenReturn(answer1)
        .thenReturn(answer2);
    dnsResolver.setAddressResolver(mockResolver);

    resolver.start(mockListener);
    assertEquals(1, fakeExecutor.runDueTasks());
    verify(mockListener).onResult2(resultCaptor.capture());
    assertAnswerMatches(answer1, 81, resultCaptor.getValue());
    assertEquals(0, fakeClock.numPendingTasks());

    fakeTicker.advance(ttl + 1, TimeUnit.SECONDS);
    resolver.refresh();
    assertEquals(1, fakeExecutor.runDueTasks());
    verify(mockListener, times(2)).onResult2(resultCaptor.capture());
    assertAnswerMatches(answer2, 81, resultCaptor.getValue());
    assertEquals(0, fakeClock.numPendingTasks());
    assertEquals(0, fakeExecutor.numPendingTasks());

    syncContext.execute(() -> resolver.shutdown());

    verify(mockResolver, times(2)).resolveAddress(anyString());
  }

  @Test
  public void resolve_invalidTtlPropertyValue() throws Exception {
    System.setProperty(DnsNameResolver.NETWORKADDRESS_CACHE_TTL_PROPERTY, "not_a_number");
    resolveDefaultValue();
  }

  @Test
  public void resolve_noPropertyValue() throws Exception {
    System.clearProperty(DnsNameResolver.NETWORKADDRESS_CACHE_TTL_PROPERTY);
    resolveDefaultValue();
  }

  private void resolveDefaultValue() throws Exception {
    final List<InetAddress> answer1 = createAddressList(2);
    final List<InetAddress> answer2 = createAddressList(1);
    String name = "foo.googleapis.com";
    FakeTicker fakeTicker = new FakeTicker();

    RetryingNameResolver resolver = newResolver(
        name, 81, GrpcUtil.NOOP_PROXY_DETECTOR, Stopwatch.createUnstarted(fakeTicker));
    DnsNameResolver dnsResolver = (DnsNameResolver) resolver.getRetriedNameResolver();
    AddressResolver mockResolver = mock(AddressResolver.class);
    when(mockResolver.resolveAddress(anyString())).thenReturn(answer1).thenReturn(answer2);
    dnsResolver.setAddressResolver(mockResolver);

    resolver.start(mockListener);
    assertEquals(1, fakeExecutor.runDueTasks());
    verify(mockListener).onResult2(resultCaptor.capture());
    assertAnswerMatches(answer1, 81, resultCaptor.getValue());
    assertEquals(0, fakeClock.numPendingTasks());

    fakeTicker.advance(DnsNameResolver.DEFAULT_NETWORK_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
    resolver.refresh();
    assertEquals(0, fakeExecutor.runDueTasks());
    assertEquals(0, fakeClock.numPendingTasks());
    verifyNoMoreInteractions(mockListener);

    fakeTicker.advance(1, TimeUnit.SECONDS);
    resolver.refresh();
    assertEquals(1, fakeExecutor.runDueTasks());
    verify(mockListener, times(2)).onResult2(resultCaptor.capture());
    assertAnswerMatches(answer2, 81, resultCaptor.getValue());
    assertEquals(0, fakeClock.numPendingTasks());
    assertEquals(0, fakeExecutor.numPendingTasks());

    syncContext.execute(() -> resolver.shutdown());

    verify(mockResolver, times(2)).resolveAddress(anyString());
  }

  @Test
  public void resolve_emptyResult() throws Exception {
    DnsNameResolver.enableTxt = true;
    RetryingNameResolver resolver = newResolver("dns:///addr.fake:1234", 443);
    DnsNameResolver dnsResolver = (DnsNameResolver) resolver.getRetriedNameResolver();
    dnsResolver.setAddressResolver(new AddressResolver() {
      @Override
      public List<InetAddress> resolveAddress(String host) throws Exception {
        return Collections.emptyList();
      }
    });
    ResourceResolver mockResourceResolver = mock(ResourceResolver.class);
    when(mockResourceResolver.resolveTxt(anyString()))
        .thenReturn(Collections.<String>emptyList());

    dnsResolver.setResourceResolver(mockResourceResolver);

    resolver.start(mockListener);
    assertThat(fakeExecutor.runDueTasks()).isEqualTo(1);

    ArgumentCaptor<ResolutionResult> ac = ArgumentCaptor.forClass(ResolutionResult.class);
    verify(mockListener).onResult2(ac.capture());
    verifyNoMoreInteractions(mockListener);
    assertThat(ac.getValue().getAddressesOrError().getValue()).isEmpty();
    assertThat(ac.getValue().getServiceConfig()).isNull();
    verify(mockResourceResolver, never()).resolveSrv(anyString());

    assertEquals(0, fakeClock.numPendingTasks());
    assertEquals(0, fakeExecutor.numPendingTasks());
  }

  @Test
  public void resolve_addressResolutionError() throws Exception {
    DnsNameResolver.enableTxt = true;
    when(mockProxyDetector.proxyFor(any(SocketAddress.class))).thenThrow(new IOException());
    RetryingNameResolver resolver = newResolver(
        "addr.fake:1234", 443, mockProxyDetector, Stopwatch.createUnstarted());
    DnsNameResolver dnsResolver = (DnsNameResolver) resolver.getRetriedNameResolver();
    dnsResolver.setAddressResolver(new AddressResolver() {
      @Override
      public List<InetAddress> resolveAddress(String host) throws Exception {
        return Collections.emptyList();
      }
    });
    ResourceResolver mockResourceResolver = mock(ResourceResolver.class);
    when(mockResourceResolver.resolveTxt(anyString()))
        .thenReturn(Collections.<String>emptyList());

    dnsResolver.setResourceResolver(mockResourceResolver);

    resolver.start(mockListener);
    assertThat(fakeExecutor.runDueTasks()).isEqualTo(1);

    ArgumentCaptor<ResolutionResult> ac = ArgumentCaptor.forClass(ResolutionResult.class);
    verify(mockListener).onResult2(ac.capture());
    verifyNoMoreInteractions(mockListener);
    assertThat(ac.getValue().getAddressesOrError().getStatus().getCode()).isEqualTo(
        Status.UNAVAILABLE.getCode());
    assertThat(ac.getValue().getAddressesOrError().getStatus().getDescription()).isEqualTo(
        "Unable to resolve host addr.fake");
    assertThat(ac.getValue().getAddressesOrError().getStatus().getCause())
        .isInstanceOf(IOException.class);
  }

  // Load balancer rejects the empty addresses.
  @Test
  public void resolve_emptyResult_notAccepted() throws Exception {
    when(mockListener.onResult2(isA(ResolutionResult.class))).thenReturn(Status.UNAVAILABLE);

    DnsNameResolver.enableTxt = true;
    RetryingNameResolver resolver = newResolver("dns:///addr.fake:1234", 443);
    DnsNameResolver dnsResolver = (DnsNameResolver) resolver.getRetriedNameResolver();
    dnsResolver.setAddressResolver(new AddressResolver() {
      @Override
      public List<InetAddress> resolveAddress(String host) throws Exception {
        return Collections.emptyList();
      }
    });
    ResourceResolver mockResourceResolver = mock(ResourceResolver.class);
    when(mockResourceResolver.resolveTxt(anyString()))
        .thenReturn(Collections.<String>emptyList());

    dnsResolver.setResourceResolver(mockResourceResolver);

    resolver.start(mockListener);
    syncContext.execute(() -> assertThat(fakeExecutor.runDueTasks()).isEqualTo(1));

    ArgumentCaptor<ResolutionResult> ac = ArgumentCaptor.forClass(ResolutionResult.class);
    verify(mockListener).onResult2(ac.capture());
    verifyNoMoreInteractions(mockListener);
    assertThat(ac.getValue().getAddressesOrError().getValue()).isEmpty();
    assertThat(ac.getValue().getServiceConfig()).isNull();
    verify(mockResourceResolver, never()).resolveSrv(anyString());

    assertEquals(0, fakeClock.numPendingTasks());
    // A retry should be scheduled
    assertThat(fakeExecutor.numPendingTasks(NAME_RESOLVER_REFRESH_TASK_FILTER)).isEqualTo(1);
  }

  @Test
  public void resolve_nullResourceResolver() throws Exception {
    DnsNameResolver.enableTxt = true;
    InetAddress backendAddr = InetAddresses.fromInteger(0x7f000001);
    AddressResolver mockAddressResolver = mock(AddressResolver.class);
    when(mockAddressResolver.resolveAddress(anyString()))
        .thenReturn(Collections.singletonList(backendAddr));
    String name = "foo.googleapis.com";

    RetryingNameResolver resolver = newResolver(name, 81);
    DnsNameResolver dnsResolver = (DnsNameResolver) resolver.getRetriedNameResolver();
    dnsResolver.setAddressResolver(mockAddressResolver);
    dnsResolver.setResourceResolver(null);
    resolver.start(mockListener);
    assertEquals(1, fakeExecutor.runDueTasks());
    verify(mockListener).onResult2(resultCaptor.capture());
    ResolutionResult result = resultCaptor.getValue();
    InetSocketAddress resolvedBackendAddr =
        (InetSocketAddress) Iterables.getOnlyElement(
            Iterables.getOnlyElement(result.getAddressesOrError().getValue()).getAddresses());
    assertThat(resolvedBackendAddr.getAddress()).isEqualTo(backendAddr);
    verify(mockAddressResolver).resolveAddress(name);
    assertThat(result.getServiceConfig()).isNull();

    assertEquals(0, fakeClock.numPendingTasks());
    assertEquals(0, fakeExecutor.numPendingTasks());
  }

  @Test
  public void resolve_nullResourceResolver_addressFailure() throws Exception {
    DnsNameResolver.enableTxt = true;
    AddressResolver mockAddressResolver = mock(AddressResolver.class);
    when(mockAddressResolver.resolveAddress(anyString()))
        .thenThrow(new IOException("no addr"));
    when(mockListener.onResult2(isA(ResolutionResult.class))).thenReturn(Status.UNAVAILABLE);
    String name = "foo.googleapis.com";

    RetryingNameResolver resolver = newResolver(name, 81);
    DnsNameResolver dnsResolver = (DnsNameResolver) resolver.getRetriedNameResolver();
    dnsResolver.setAddressResolver(mockAddressResolver);
    dnsResolver.setResourceResolver(null);
    resolver.start(mockListener);
    assertEquals(1, fakeExecutor.runDueTasks());
    verify(mockListener).onResult2(resultCaptor.capture());
    Status errorStatus = resultCaptor.getValue().getAddressesOrError().getStatus();
    assertThat(errorStatus.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(errorStatus.getCause()).hasMessageThat().contains("no addr");

    assertEquals(0, fakeClock.numPendingTasks());
    // A retry should be scheduled
    assertThat(fakeExecutor.numPendingTasks(NAME_RESOLVER_REFRESH_TASK_FILTER)).isEqualTo(1);
  }

  @Test
  public void resolve_presentResourceResolver() throws Exception {
    DnsNameResolver.enableTxt = true;
    InetAddress backendAddr = InetAddresses.fromInteger(0x7f000001);
    AddressResolver mockAddressResolver = mock(AddressResolver.class);
    when(mockAddressResolver.resolveAddress(anyString()))
        .thenReturn(Collections.singletonList(backendAddr));
    ResourceResolver mockResourceResolver = mock(ResourceResolver.class);
    when(mockResourceResolver.resolveTxt(anyString()))
        .thenReturn(
            Collections.singletonList(
                "grpc_config=[{\"clientLanguage\": [\"java\"], \"serviceConfig\": {}}]"));
    ServiceConfigParser serviceConfigParser = new ServiceConfigParser() {
      @Override
      public ConfigOrError parseServiceConfig(Map<String, ?> rawServiceConfig) {
        return ConfigOrError.fromConfig(rawServiceConfig);
      }
    };
    NameResolver.Args args =
        NameResolver.Args.newBuilder()
            .setDefaultPort(DEFAULT_PORT)
            .setProxyDetector(GrpcUtil.NOOP_PROXY_DETECTOR)
            .setSynchronizationContext(syncContext)
            .setServiceConfigParser(serviceConfigParser)
            .setScheduledExecutorService(fakeClock.getScheduledExecutorService())
            .build();

    String name = "foo.googleapis.com";
    RetryingNameResolver resolver = newResolver(name, Stopwatch.createUnstarted(), false, args);
    DnsNameResolver dnsResolver = (DnsNameResolver) resolver.getRetriedNameResolver();
    dnsResolver.setAddressResolver(mockAddressResolver);
    dnsResolver.setResourceResolver(mockResourceResolver);

    resolver.start(mockListener);
    assertEquals(1, fakeExecutor.runDueTasks());
    verify(mockListener).onResult2(resultCaptor.capture());
    ResolutionResult result = resultCaptor.getValue();
    InetSocketAddress resolvedBackendAddr =
        (InetSocketAddress) Iterables.getOnlyElement(
            Iterables.getOnlyElement(result.getAddressesOrError().getValue()).getAddresses());
    assertThat(resolvedBackendAddr.getAddress()).isEqualTo(backendAddr);
    assertThat(result.getServiceConfig().getConfig()).isNotNull();
    verify(mockAddressResolver).resolveAddress(name);
    verify(mockResourceResolver).resolveTxt("_grpc_config." + name);

    assertEquals(0, fakeClock.numPendingTasks());
    assertEquals(0, fakeExecutor.numPendingTasks());
  }

  @Test
  public void resolve_addressFailure_neverLookUpServiceConfig() throws Exception {
    DnsNameResolver.enableTxt = true;
    AddressResolver mockAddressResolver = mock(AddressResolver.class);
    when(mockAddressResolver.resolveAddress(anyString()))
        .thenThrow(new IOException("no addr"));
    when(mockListener.onResult2(isA(ResolutionResult.class))).thenReturn(Status.UNAVAILABLE);
    String name = "foo.googleapis.com";

    ResourceResolver mockResourceResolver = mock(ResourceResolver.class);
    RetryingNameResolver resolver = newResolver(name, 81);
    DnsNameResolver dnsResolver = (DnsNameResolver)resolver.getRetriedNameResolver();
    dnsResolver.setAddressResolver(mockAddressResolver);
    dnsResolver.setResourceResolver(mockResourceResolver);
    resolver.start(mockListener);
    assertEquals(1, fakeExecutor.runDueTasks());
    verify(mockListener).onResult2(resultCaptor.capture());
    Status errorStatus = resultCaptor.getValue().getAddressesOrError().getStatus();
    assertThat(errorStatus.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(errorStatus.getCause()).hasMessageThat().contains("no addr");
    verify(mockResourceResolver, never()).resolveTxt(anyString());

    assertEquals(0, fakeClock.numPendingTasks());
    // A retry should be scheduled
    assertThat(fakeExecutor.numPendingTasks(NAME_RESOLVER_REFRESH_TASK_FILTER)).isEqualTo(1);
  }

  @Test
  public void resolve_serviceConfigLookupFails_nullServiceConfig() throws Exception {
    DnsNameResolver.enableTxt = true;
    InetAddress backendAddr = InetAddresses.fromInteger(0x7f000001);
    AddressResolver mockAddressResolver = mock(AddressResolver.class);
    when(mockAddressResolver.resolveAddress(anyString()))
        .thenReturn(Collections.singletonList(backendAddr));
    String name = "foo.googleapis.com";
    ResourceResolver mockResourceResolver = mock(ResourceResolver.class);
    when(mockResourceResolver.resolveTxt(anyString()))
        .thenThrow(new Exception("something like javax.naming.NamingException"));

    RetryingNameResolver resolver = newResolver(name, 81);
    DnsNameResolver dnsResolver = (DnsNameResolver) resolver.getRetriedNameResolver();
    dnsResolver.setAddressResolver(mockAddressResolver);
    dnsResolver.setResourceResolver(mockResourceResolver);
    resolver.start(mockListener);
    assertEquals(1, fakeExecutor.runDueTasks());
    verify(mockListener).onResult2(resultCaptor.capture());
    ResolutionResult result = resultCaptor.getValue();
    InetSocketAddress resolvedBackendAddr =
        (InetSocketAddress) Iterables.getOnlyElement(
            Iterables.getOnlyElement(result.getAddressesOrError().getValue()).getAddresses());
    assertThat(resolvedBackendAddr.getAddress()).isEqualTo(backendAddr);
    verify(mockAddressResolver).resolveAddress(name);
    assertThat(result.getServiceConfig()).isNull();
    verify(mockResourceResolver).resolveTxt(anyString());

    assertEquals(0, fakeClock.numPendingTasks());
    assertEquals(0, fakeExecutor.numPendingTasks());
  }

  @Test
  public void resolve_serviceConfigMalformed_serviceConfigError() throws Exception {
    DnsNameResolver.enableTxt = true;
    InetAddress backendAddr = InetAddresses.fromInteger(0x7f000001);
    AddressResolver mockAddressResolver = mock(AddressResolver.class);
    when(mockAddressResolver.resolveAddress(anyString()))
        .thenReturn(Collections.singletonList(backendAddr));
    String name = "foo.googleapis.com";
    ResourceResolver mockResourceResolver = mock(ResourceResolver.class);
    when(mockResourceResolver.resolveTxt(anyString()))
        .thenReturn(Collections.singletonList("grpc_config=something invalid"));

    RetryingNameResolver resolver = newResolver(name, 81);
    DnsNameResolver dnsResolver = (DnsNameResolver) resolver.getRetriedNameResolver();
    dnsResolver.setAddressResolver(mockAddressResolver);
    dnsResolver.setResourceResolver(mockResourceResolver);
    resolver.start(mockListener);
    assertEquals(1, fakeExecutor.runDueTasks());
    verify(mockListener).onResult2(resultCaptor.capture());
    ResolutionResult result = resultCaptor.getValue();
    InetSocketAddress resolvedBackendAddr =
        (InetSocketAddress) Iterables.getOnlyElement(
            Iterables.getOnlyElement(result.getAddressesOrError().getValue()).getAddresses());
    assertThat(resolvedBackendAddr.getAddress()).isEqualTo(backendAddr);
    verify(mockAddressResolver).resolveAddress(name);
    assertThat(result.getServiceConfig()).isNotNull();
    assertThat(result.getServiceConfig().getError()).isNotNull();
    verify(mockResourceResolver).resolveTxt(anyString());

    assertEquals(0, fakeClock.numPendingTasks());
    assertEquals(0, fakeExecutor.numPendingTasks());
  }

  @Test
  public void skipMissingJndiResolverResolver() throws Exception {
    ClassLoader cl = new ClassLoader() {
      @Override
      protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if ("io.grpc.internal.JndiResourceResolverFactory".equals(name)) {
          throw new ClassNotFoundException();
        }
        return super.loadClass(name, resolve);
      }
    };

    ResourceResolverFactory factory = DnsNameResolver.getResourceResolverFactory(cl);

    assertThat(factory).isNull();
  }

  @Test
  public void skipWrongJndiResolverResolver() throws Exception {
    ClassLoader cl = new StaticTestingClassLoader(
        DnsNameResolverTest.class.getClassLoader(),
        Pattern.compile("io\\.grpc\\..+"));

    ResourceResolverFactory factory = DnsNameResolver.getResourceResolverFactory(cl);

    assertThat(factory).isNull();
  }

  @Test
  public void doNotResolveWhenProxyDetected() throws Exception {
    final String name = "foo.googleapis.com";
    final int port = 81;
    final InetSocketAddress proxyAddress =
        new InetSocketAddress(InetAddress.getByName("10.0.0.1"), 1000);
    ProxyDetector alwaysDetectProxy = new ProxyDetector() {
        @Override
        public HttpConnectProxiedSocketAddress proxyFor(SocketAddress targetAddress) {
          return HttpConnectProxiedSocketAddress.newBuilder()
              .setTargetAddress((InetSocketAddress) targetAddress)
              .setProxyAddress(proxyAddress)
              .setUsername("username")
              .setPassword("password").build();
        }
      };
    RetryingNameResolver resolver = newResolver(
        name, port, alwaysDetectProxy, Stopwatch.createUnstarted());
    DnsNameResolver dnsResolver = (DnsNameResolver) resolver.getRetriedNameResolver();
    AddressResolver mockAddressResolver = mock(AddressResolver.class);
    when(mockAddressResolver.resolveAddress(anyString())).thenThrow(new AssertionError());
    dnsResolver.setAddressResolver(mockAddressResolver);
    resolver.start(mockListener);
    assertEquals(1, fakeExecutor.runDueTasks());

    verify(mockListener).onResult2(resultCaptor.capture());
    List<EquivalentAddressGroup> result = resultCaptor.getValue().getAddressesOrError().getValue();
    assertThat(result).hasSize(1);
    EquivalentAddressGroup eag = result.get(0);
    assertThat(eag.getAddresses()).hasSize(1);

    HttpConnectProxiedSocketAddress socketAddress =
        (HttpConnectProxiedSocketAddress) eag.getAddresses().get(0);
    assertSame(proxyAddress, socketAddress.getProxyAddress());
    assertEquals("username", socketAddress.getUsername());
    assertEquals("password", socketAddress.getPassword());
    assertTrue(socketAddress.getTargetAddress().isUnresolved());

    assertEquals(0, fakeClock.numPendingTasks());
    assertEquals(0, fakeExecutor.numPendingTasks());
  }

  @Test
  public void maybeChooseServiceConfig_nullOnMispelling() {
    Map<String, Object> bad = new LinkedHashMap<>();
    bad.put("parcentage", 1.0);
    thrown.expectMessage("key '{parcentage=1.0}' missing in 'serviceConfig'");
    DnsNameResolver.maybeChooseServiceConfig(bad, new Random(), "host");
  }
  
  @Test
  public void maybeChooseServiceConfig_clientLanguageMatchesJava() {
    Map<String, Object> choice = new LinkedHashMap<>();
    List<String> langs = new ArrayList<>();
    langs.add("java");
    choice.put("clientLanguage", langs);
    choice.put("serviceConfig", serviceConfig);

    assertNotNull(DnsNameResolver.maybeChooseServiceConfig(choice, new Random(), "host"));
  }

  @Test
  public void maybeChooseServiceConfig_clientLanguageDoesntMatchGo() {
    Map<String, Object> choice = new LinkedHashMap<>();
    List<String> langs = new ArrayList<>();
    langs.add("go");
    choice.put("clientLanguage", langs);
    choice.put("serviceConfig", serviceConfig);

    assertNull(DnsNameResolver.maybeChooseServiceConfig(choice, new Random(), "host"));
  }

  @Test
  public void maybeChooseServiceConfig_clientLanguageCaseInsensitive() {
    Map<String, Object> choice = new LinkedHashMap<>();
    List<String> langs = new ArrayList<>();
    langs.add("JAVA");
    choice.put("clientLanguage", langs);
    choice.put("serviceConfig", serviceConfig);

    assertNotNull(DnsNameResolver.maybeChooseServiceConfig(choice, new Random(), "host"));
  }

  @Test
  public void maybeChooseServiceConfig_clientLanguageMatchesEmpty() {
    Map<String, Object> choice = new LinkedHashMap<>();
    List<String> langs = new ArrayList<>();
    choice.put("clientLanguage", langs);
    choice.put("serviceConfig", serviceConfig);

    assertNotNull(DnsNameResolver.maybeChooseServiceConfig(choice, new Random(), "host"));
  }

  @Test
  public void maybeChooseServiceConfig_clientLanguageMatchesMulti() {
    Map<String, Object> choice = new LinkedHashMap<>();
    List<String> langs = new ArrayList<>();
    langs.add("go");
    langs.add("java");
    choice.put("clientLanguage", langs);
    choice.put("serviceConfig", serviceConfig);

    assertNotNull(DnsNameResolver.maybeChooseServiceConfig(choice, new Random(), "host"));
  }

  @Test
  public void maybeChooseServiceConfig_percentageZeroAlwaysFails() {
    Map<String, Object> choice = new LinkedHashMap<>();
    choice.put("percentage", 0D);
    choice.put("serviceConfig", serviceConfig);

    assertNull(DnsNameResolver.maybeChooseServiceConfig(choice, new Random(), "host"));
  }

  @Test
  public void maybeChooseServiceConfig_percentageHundredAlwaysSucceeds() {
    Map<String, Object> choice = new LinkedHashMap<>();
    choice.put("percentage", 100D);
    choice.put("serviceConfig", serviceConfig);

    assertNotNull(DnsNameResolver.maybeChooseServiceConfig(choice, new Random(), "host"));
  }

  @Test
  public void maybeChooseServiceConfig_percentageAboveMatches50() {
    Map<String, Object> choice = new LinkedHashMap<>();
    choice.put("percentage", 50D);
    choice.put("serviceConfig", serviceConfig);

    Random r = new Random() {
      @Override
      public int nextInt(int bound) {
        return 49;
      }
    };

    assertNotNull(DnsNameResolver.maybeChooseServiceConfig(choice, r, "host"));
  }

  @Test
  public void maybeChooseServiceConfig_percentageAtFails50() {
    Map<String, Object> choice = new LinkedHashMap<>();
    choice.put("percentage", 50D);
    choice.put("serviceConfig", serviceConfig);

    Random r = new Random() {
      @Override
      public int nextInt(int bound) {
        return 50;
      }
    };

    assertNull(DnsNameResolver.maybeChooseServiceConfig(choice, r, "host"));
  }

  @Test
  public void maybeChooseServiceConfig_percentageAboveMatches99() {
    Map<String, Object> choice = new LinkedHashMap<>();
    choice.put("percentage", 99D);
    choice.put("serviceConfig", serviceConfig);

    Random r = new Random() {
      @Override
      public int nextInt(int bound) {
        return 98;
      }
    };

    assertNotNull(DnsNameResolver.maybeChooseServiceConfig(choice, r, "host"));
  }

  @Test
  public void maybeChooseServiceConfig_percentageAtFails99() {
    Map<String, Object> choice = new LinkedHashMap<>();
    choice.put("percentage", 99D);
    choice.put("serviceConfig", serviceConfig);

    Random r = new Random() {
      @Override
      public int nextInt(int bound) {
        return 99;
      }
    };

    assertNull(DnsNameResolver.maybeChooseServiceConfig(choice, r, "host"));
  }

  @Test
  public void maybeChooseServiceConfig_percentageAboveMatches1() {
    Map<String, Object> choice = new LinkedHashMap<>();
    choice.put("percentage", 1D);
    choice.put("serviceConfig", serviceConfig);

    Random r = new Random() {
      @Override
      public int nextInt(int bound) {
        return 0;
      }
    };

    assertNotNull(DnsNameResolver.maybeChooseServiceConfig(choice, r, "host"));
  }

  @Test
  public void maybeChooseServiceConfig_percentageAtFails1() {
    Map<String, Object> choice = new LinkedHashMap<>();
    choice.put("percentage", 1D);
    choice.put("serviceConfig", serviceConfig);

    Random r = new Random() {
      @Override
      public int nextInt(int bound) {
        return 1;
      }
    };

    assertNull(DnsNameResolver.maybeChooseServiceConfig(choice, r, "host"));
  }

  @Test
  public void maybeChooseServiceConfig_hostnameMatches() {
    Map<String, Object> choice = new LinkedHashMap<>();
    List<String> hosts = new ArrayList<>();
    hosts.add("localhost");
    choice.put("clientHostname", hosts);
    choice.put("serviceConfig", serviceConfig);

    assertNotNull(DnsNameResolver.maybeChooseServiceConfig(choice, new Random(), "localhost"));
  }

  @Test
  public void maybeChooseServiceConfig_hostnameDoesntMatch() {
    Map<String, Object> choice = new LinkedHashMap<>();
    List<String> hosts = new ArrayList<>();
    hosts.add("localhorse");
    choice.put("clientHostname", hosts);
    choice.put("serviceConfig", serviceConfig);

    assertNull(DnsNameResolver.maybeChooseServiceConfig(choice, new Random(), "localhost"));
  }

  @Test
  public void maybeChooseServiceConfig_clientLanguageCaseSensitive() {
    Map<String, Object> choice = new LinkedHashMap<>();
    List<String> hosts = new ArrayList<>();
    hosts.add("LOCALHOST");
    choice.put("clientHostname", hosts);
    choice.put("serviceConfig", serviceConfig);

    assertNull(DnsNameResolver.maybeChooseServiceConfig(choice, new Random(), "localhost"));
  }

  @Test
  public void maybeChooseServiceConfig_hostnameMatchesEmpty() {
    Map<String, Object> choice = new LinkedHashMap<>();
    List<String> hosts = new ArrayList<>();
    choice.put("clientHostname", hosts);
    choice.put("serviceConfig", serviceConfig);

    assertNotNull(DnsNameResolver.maybeChooseServiceConfig(choice, new Random(), "host"));
  }

  @Test
  public void maybeChooseServiceConfig_hostnameMatchesMulti() {
    Map<String, Object> choice = new LinkedHashMap<>();
    List<String> hosts = new ArrayList<>();
    hosts.add("localhorse");
    hosts.add("localhost");
    choice.put("clientHostname", hosts);
    choice.put("serviceConfig", serviceConfig);

    assertNotNull(DnsNameResolver.maybeChooseServiceConfig(choice, new Random(), "localhost"));
  }

  @Test
  public void parseTxtResults_misspelledName() throws Exception {
    List<String> txtRecords = new ArrayList<>();
    txtRecords.add("some_record");
    txtRecords.add("_grpc_config=[]");

    List<? extends Map<String, ?>> results = DnsNameResolver.parseTxtResults(txtRecords);

    assertThat(results).isEmpty();
  }

  @Test
  public void parseTxtResults_badTypeFails() throws Exception {
    List<String> txtRecords = new ArrayList<>();
    txtRecords.add("some_record");
    txtRecords.add("grpc_config={}");

    thrown.expect(ClassCastException.class);
    thrown.expectMessage("wrong type");
    DnsNameResolver.parseTxtResults(txtRecords);
  }

  @Test
  public void parseTxtResults_badInnerTypeFails() throws Exception {
    List<String> txtRecords = new ArrayList<>();
    txtRecords.add("some_record");
    txtRecords.add("grpc_config=[\"bogus\"]");

    thrown.expect(ClassCastException.class);
    thrown.expectMessage("not object");
    DnsNameResolver.parseTxtResults(txtRecords);
  }

  @Test
  public void parseTxtResults_combineAll() throws Exception {
    Logger logger = Logger.getLogger(DnsNameResolver.class.getName());
    Level level = logger.getLevel();
    logger.setLevel(Level.SEVERE);
    try {
      List<String> txtRecords = new ArrayList<>();
      txtRecords.add("some_record");
      txtRecords.add("grpc_config=[{}, {}]"); // 2 records
      txtRecords.add("grpc_config=[{\"\":{}}]"); // 1 record

      List<? extends Map<String, ?>> results = DnsNameResolver.parseTxtResults(txtRecords);

      assertThat(results).hasSize(2 + 1);
    } finally {
      logger.setLevel(level);
    }
  }

  @Test
  public void shouldUseJndi_alwaysFalseIfDisabled() {
    boolean enableJndi = false;
    boolean enableJndiLocalhost = true;
    String host = "seemingly.valid.host";

    assertFalse(DnsNameResolver.shouldUseJndi(enableJndi, enableJndiLocalhost, host));
  }

  @Test
  public void shouldUseJndi_falseIfDisabledForLocalhost() {
    boolean enableJndi = true;
    boolean enableJndiLocalhost = false;

    assertFalse(DnsNameResolver.shouldUseJndi(enableJndi, enableJndiLocalhost, "localhost"));
    assertFalse(DnsNameResolver.shouldUseJndi(enableJndi, enableJndiLocalhost, "LOCALHOST"));
  }

  @Test
  public void shouldUseJndi_trueIfLocalhostOverridden() {
    boolean enableJndi = true;
    boolean enableJndiLocalhost = true;
    String host = "localhost";

    assertTrue(DnsNameResolver.shouldUseJndi(enableJndi, enableJndiLocalhost, host));
  }

  @Test
  public void shouldUseJndi_falseForIpv6() {
    boolean enableJndi = true;
    boolean enableJndiLocalhost = false;

    assertFalse(DnsNameResolver.shouldUseJndi(enableJndi, enableJndiLocalhost, "::"));
    assertFalse(DnsNameResolver.shouldUseJndi(enableJndi, enableJndiLocalhost, "::1"));
    assertFalse(DnsNameResolver.shouldUseJndi(enableJndi, enableJndiLocalhost, "2001:db8:1234::"));
    assertFalse(DnsNameResolver.shouldUseJndi(
        enableJndi, enableJndiLocalhost, "[2001:db8:1234::]"));
    assertFalse(DnsNameResolver.shouldUseJndi(
        enableJndi, enableJndiLocalhost, "2001:db8:1234::%3"));
  }

  @Test
  public void shouldUseJndi_falseForIpv4() {
    boolean enableJndi = true;
    boolean enableJndiLocalhost = false;

    assertFalse(DnsNameResolver.shouldUseJndi(enableJndi, enableJndiLocalhost, "127.0.0.1"));
    assertFalse(DnsNameResolver.shouldUseJndi(enableJndi, enableJndiLocalhost, "192.168.0.1"));
    assertFalse(DnsNameResolver.shouldUseJndi(enableJndi, enableJndiLocalhost, "134744072"));
  }

  @Test
  public void shouldUseJndi_falseForEmpty() {
    boolean enableJndi = true;
    boolean enableJndiLocalhost = false;

    assertFalse(DnsNameResolver.shouldUseJndi(enableJndi, enableJndiLocalhost, ""));
  }

  @Test
  public void shouldUseJndi_trueIfItMightPossiblyBeValid() {
    boolean enableJndi = true;
    boolean enableJndiLocalhost = false;

    assertTrue(DnsNameResolver.shouldUseJndi(enableJndi, enableJndiLocalhost, "remotehost"));
    assertTrue(DnsNameResolver.shouldUseJndi(enableJndi, enableJndiLocalhost, "remotehost.gov"));
    assertTrue(DnsNameResolver.shouldUseJndi(enableJndi, enableJndiLocalhost, "f.q.d.n."));
    assertTrue(DnsNameResolver.shouldUseJndi(
        enableJndi, enableJndiLocalhost, "8.8.8.8.in-addr.arpa."));
    assertTrue(DnsNameResolver.shouldUseJndi(
        enableJndi, enableJndiLocalhost, "2001-db8-1234--as3.ipv6-literal.net"));
  }

  @Test
  public void parseServiceConfig_capturesParseError() {
    ConfigOrError result = DnsNameResolver.parseServiceConfig(
        Arrays.asList("grpc_config=bogus"), new Random(), "localhost");

    assertThat(result).isNotNull();
    assertThat(result.getError().getCode()).isEqualTo(Status.Code.UNKNOWN);
    assertThat(result.getError().getDescription()).contains("failed to parse TXT records");
  }

  @Test
  public void parseServiceConfig_capturesChoiceError() {
    ConfigOrError result = DnsNameResolver.parseServiceConfig(
        Arrays.asList("grpc_config=[{\"hi\":{}}]"), new Random(), "localhost");

    assertThat(result).isNotNull();
    assertThat(result.getError().getCode()).isEqualTo(Status.Code.UNKNOWN);
    assertThat(result.getError().getDescription()).contains("failed to pick");
  }

  @Test
  public void parseServiceConfig_noChoiceIsNull() {
    ConfigOrError result = DnsNameResolver.parseServiceConfig(
        Arrays.asList("grpc_config=[]"), new Random(), "localhost");

    assertThat(result).isNull();
  }

  @Test
  public void parseServiceConfig_matches() {
    ConfigOrError result = DnsNameResolver.parseServiceConfig(
        Arrays.asList("grpc_config=[{\"serviceConfig\":{}}]"), new Random(), "localhost");

    assertThat(result).isNotNull();
    assertThat(result.getError()).isNull();
    assertThat(result.getConfig()).isEqualTo(ImmutableMap.of());
  }

  private void testInvalidUri(URI uri) {
    try {
      provider.newNameResolver(uri, args);
      fail("Should have failed");
    } catch (IllegalArgumentException e) {
      // expected
    }
  }

  private void testValidUri(URI uri, String exportedAuthority, int expectedPort) {
    DnsNameResolver resolver = (DnsNameResolver) provider.newNameResolver(uri, args);
    assertNotNull(resolver);
    assertEquals(expectedPort, resolver.getPort());
    assertEquals(exportedAuthority, resolver.getServiceAuthority());
  }

  private byte lastByte = 0;

  private List<InetAddress> createAddressList(int n) throws UnknownHostException {
    List<InetAddress> list = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      list.add(InetAddress.getByAddress(new byte[] {127, 0, 0, ++lastByte}));
    }
    return list;
  }

  private static void assertAnswerMatches(
      List<InetAddress> addrs, int port, ResolutionResult resolutionResult) {
    assertThat(resolutionResult.getAddressesOrError().getValue()).hasSize(addrs.size());
    for (int i = 0; i < addrs.size(); i++) {
      EquivalentAddressGroup addrGroup = resolutionResult.getAddressesOrError().getValue().get(i);
      InetSocketAddress socketAddr =
          (InetSocketAddress) Iterables.getOnlyElement(addrGroup.getAddresses());
      assertEquals("Addr " + i, port, socketAddr.getPort());
      assertEquals("Addr " + i, addrs.get(i), socketAddr.getAddress());
    }
  }
}
