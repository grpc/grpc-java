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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static io.grpc.ClientStreamTracer.NAME_RESOLUTION_DELAYED;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.EquivalentAddressGroup.ATTR_AUTHORITY_OVERRIDE;
import static io.grpc.PickSubchannelArgsMatcher.eqPickSubchannelArgs;
import static io.grpc.internal.ClientStreamListener.RpcProgress.PROCESSED;
import static junit.framework.TestCase.assertNotSame;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
import io.grpc.BinaryLog;
import io.grpc.CallCredentials;
import io.grpc.CallCredentials.RequestInfo;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ChannelCredentials;
import io.grpc.ChannelLogger;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.ClientTransportFilter;
import io.grpc.CompositeChannelCredentials;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.Context;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InsecureChannelCredentials;
import io.grpc.IntegerMarshaller;
import io.grpc.InternalChannelz;
import io.grpc.InternalChannelz.ChannelStats;
import io.grpc.InternalChannelz.ChannelTrace;
import io.grpc.InternalChannelz.ChannelTrace.Event.Severity;
import io.grpc.InternalConfigSelector;
import io.grpc.InternalInstrumented;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.LongCounterMetricInstrument;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.MetricInstrumentRegistry;
import io.grpc.MetricSink;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.NameResolver.ResolutionResult;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.ProxiedSocketAddress;
import io.grpc.ProxyDetector;
import io.grpc.SecurityLevel;
import io.grpc.ServerMethodDefinition;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusOr;
import io.grpc.StringMarshaller;
import io.grpc.SynchronizationContext;
import io.grpc.internal.ClientTransportFactory.ClientTransportOptions;
import io.grpc.internal.ClientTransportFactory.SwapChannelCredentialsResult;
import io.grpc.internal.InternalSubchannel.TransportLogger;
import io.grpc.internal.ManagedChannelImplBuilder.ClientTransportFactoryBuilder;
import io.grpc.internal.ManagedChannelImplBuilder.FixedPortProvider;
import io.grpc.internal.ManagedChannelImplBuilder.UnsupportedClientTransportFactoryBuilder;
import io.grpc.internal.ManagedChannelServiceConfig.MethodInfo;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.internal.TestUtils.MockClientTransportInfo;
import io.grpc.stub.ClientCalls;
import io.grpc.testing.TestMethodDescriptors;
import io.grpc.util.ForwardingSubchannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

/** Unit tests for {@link ManagedChannelImpl}. */
@RunWith(JUnit4.class)
// TODO(creamsoup) remove backward compatible check when fully migrated
@SuppressWarnings({"deprecation"})
public class ManagedChannelImplTest {
  private static final int DEFAULT_PORT = 447;

  private static final MethodDescriptor<String, Integer> method =
      MethodDescriptor.<String, Integer>newBuilder()
          .setType(MethodType.UNKNOWN)
          .setFullMethodName("service/method")
          .setRequestMarshaller(new StringMarshaller())
          .setResponseMarshaller(new IntegerMarshaller())
          .build();
  private static final Attributes.Key<String> SUBCHANNEL_ATTR_KEY =
      Attributes.Key.create("subchannel-attr-key");
  private static final long RECONNECT_BACKOFF_INTERVAL_NANOS = 10;
  private static final String SERVICE_NAME = "fake.example.com";
  private static final String AUTHORITY = SERVICE_NAME;
  private static final String USER_AGENT = "userAgent";
  private static final ClientTransportOptions clientTransportOptions =
      new ClientTransportOptions()
          .setAuthority(AUTHORITY)
          .setUserAgent(USER_AGENT);
  private static final String TARGET = "fake://" + SERVICE_NAME;
  private static final String MOCK_POLICY_NAME = "mock_lb";
  private static final NameResolver.Args NAMERESOLVER_ARGS = NameResolver.Args.newBuilder()
      .setDefaultPort(447)
      .setProxyDetector(mock(ProxyDetector.class))
      .setSynchronizationContext(
          new SynchronizationContext(mock(Thread.UncaughtExceptionHandler.class)))
      .setServiceConfigParser(mock(NameResolver.ServiceConfigParser.class))
      .setScheduledExecutorService(new FakeClock().getScheduledExecutorService())
      .build();
  private static final NameResolver.Args.Key<String> TEST_RESOLVER_CUSTOM_ARG_KEY =
      NameResolver.Args.Key.create("test-key");

  private URI expectedUri;
  private final SocketAddress socketAddress =
      new SocketAddress() {
        @Override
        public String toString() {
          return "test-addr";
        }
      };
  private final SocketAddress socketAddress2 =
      new SocketAddress() {
        @Override
        public String toString() {
          return "test-addr";
        }
      };
  private final EquivalentAddressGroup addressGroup = new EquivalentAddressGroup(socketAddress);
  private final EquivalentAddressGroup addressGroup2 =
      new EquivalentAddressGroup(Arrays.asList(socketAddress, socketAddress2));
  private final FakeClock timer = new FakeClock();
  private final FakeClock executor = new FakeClock();
  private final FakeClock balancerRpcExecutor = new FakeClock();

  private final InternalChannelz channelz = new InternalChannelz();

  private final MetricInstrumentRegistry metricInstrumentRegistry =
      MetricInstrumentRegistry.getDefaultRegistry();

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private ManagedChannelImpl channel;
  private Helper helper;
  @Captor
  private ArgumentCaptor<Status> statusCaptor;
  @Captor
  private ArgumentCaptor<CallOptions> callOptionsCaptor;
  @Captor
  private ArgumentCaptor<ClientStreamTracer[]> tracersCaptor;
  @Mock
  private LoadBalancer mockLoadBalancer;
  @Mock
  private SubchannelStateListener subchannelStateListener;
  private final LoadBalancerProvider mockLoadBalancerProvider =
      mock(LoadBalancerProvider.class, delegatesTo(new LoadBalancerProvider() {
          @Override
          public LoadBalancer newLoadBalancer(Helper helper) {
            return mockLoadBalancer;
          }

          @Override
          public boolean isAvailable() {
            return true;
          }

          @Override
          public int getPriority() {
            return 999;
          }

          @Override
          public String getPolicyName() {
            return MOCK_POLICY_NAME;
          }
        }));

  @Captor
  private ArgumentCaptor<ConnectivityStateInfo> stateInfoCaptor;
  @Mock
  private SubchannelPicker mockPicker;
  @Mock
  private ClientTransportFactory mockTransportFactory;
  @Mock
  private ClientCall.Listener<Integer> mockCallListener;
  @Mock
  private ClientCall.Listener<Integer> mockCallListener2;
  @Mock
  private ClientCall.Listener<Integer> mockCallListener3;
  @Mock
  private ClientCall.Listener<Integer> mockCallListener4;
  @Mock
  private ClientCall.Listener<Integer> mockCallListener5;
  @Mock
  private ObjectPool<Executor> executorPool;
  @Mock
  private ObjectPool<Executor> balancerRpcExecutorPool;
  @Mock
  private CallCredentials creds;
  @Mock
  private Executor offloadExecutor;
  private ManagedChannelImplBuilder channelBuilder;
  private boolean requestConnection = true;
  private BlockingQueue<MockClientTransportInfo> transports;
  private boolean panicExpected;
  @Captor
  private ArgumentCaptor<ResolvedAddresses> resolvedAddressCaptor;

  private ArgumentCaptor<ClientStreamListener> streamListenerCaptor =
      ArgumentCaptor.forClass(ClientStreamListener.class);

  private void createChannel(ClientInterceptor... interceptors) {
    createChannel(false, interceptors);
  }

  private void createChannel(boolean nameResolutionExpectedToFail,
      ClientInterceptor... interceptors) {
    checkState(channel == null);

    when(mockTransportFactory.getSupportedSocketAddressTypes()).thenReturn(Collections.singleton(
        InetSocketAddress.class));
    NameResolverProvider nameResolverProvider =
        channelBuilder.nameResolverRegistry.getProviderForScheme(expectedUri.getScheme());
    channel = new ManagedChannelImpl(
        channelBuilder, mockTransportFactory, expectedUri, nameResolverProvider,
        new FakeBackoffPolicyProvider(),
        balancerRpcExecutorPool, timer.getStopwatchSupplier(), Arrays.asList(interceptors),
        timer.getTimeProvider());

    if (requestConnection) {
      int numExpectedTasks = nameResolutionExpectedToFail ? 1 : 0;

      // Force-exit the initial idle-mode
      channel.syncContext.execute(new Runnable() {
          @Override
          public void run() {
            channel.exitIdleMode();
          }
        });
      if (channelBuilder.idleTimeoutMillis != ManagedChannelImpl.IDLE_TIMEOUT_MILLIS_DISABLE) {
        numExpectedTasks += 1;
      }

      assertEquals(numExpectedTasks, timer.numPendingTasks());

      ArgumentCaptor<Helper> helperCaptor = ArgumentCaptor.forClass(Helper.class);
      verify(mockLoadBalancerProvider).newLoadBalancer(helperCaptor.capture());
      helper = helperCaptor.getValue();
    }
  }

  @Before
  public void setUp() throws Exception {
    when(mockLoadBalancer.acceptResolvedAddresses(isA(ResolvedAddresses.class))).thenReturn(
        Status.OK);
    LoadBalancerRegistry.getDefaultRegistry().register(mockLoadBalancerProvider);
    expectedUri = new URI(TARGET);
    transports = TestUtils.captureTransports(mockTransportFactory);
    when(mockTransportFactory.getScheduledExecutorService())
        .thenReturn(timer.getScheduledExecutorService());
    when(executorPool.getObject()).thenReturn(executor.getScheduledExecutorService());
    when(balancerRpcExecutorPool.getObject())
        .thenReturn(balancerRpcExecutor.getScheduledExecutorService());

    channelBuilder = new ManagedChannelImplBuilder(TARGET,
        new UnsupportedClientTransportFactoryBuilder(), new FixedPortProvider(DEFAULT_PORT));
    channelBuilder.disableRetry();
    configureBuilder(channelBuilder);
  }

  private void configureBuilder(ManagedChannelImplBuilder channelBuilder) {
    channelBuilder
        .nameResolverFactory(new FakeNameResolverFactory.Builder(expectedUri).build())
        .defaultLoadBalancingPolicy(MOCK_POLICY_NAME)
        .userAgent(USER_AGENT)
        .idleTimeout(ManagedChannelImplBuilder.IDLE_MODE_MAX_TIMEOUT_DAYS, TimeUnit.DAYS)
        .offloadExecutor(offloadExecutor);
    channelBuilder.executorPool = executorPool;
    channelBuilder.binlog = null;
    channelBuilder.channelz = channelz;
  }

  @After
  public void allPendingTasksAreRun() throws Exception {
    // The "never" verifications in the tests only hold up if all due tasks are done.
    // As for timer, although there may be scheduled tasks in a future time, since we don't test
    // any time-related behavior in this test suite, we only care the tasks that are due. This
    // would ignore any time-sensitive tasks, e.g., back-off and the idle timer.
    assertTrue(timer.getDueTasks() + " should be empty", timer.getDueTasks().isEmpty());
    assertEquals(executor.getPendingTasks() + " should be empty", 0, executor.numPendingTasks());
    if (channel != null) {
      if (!panicExpected) {
        assertFalse(channel.isInPanicMode());
      }
      channel.shutdownNow();
      channel = null;
    }
  }

  @After
  public void cleanUp() {
    LoadBalancerRegistry.getDefaultRegistry().deregister(mockLoadBalancerProvider);
  }

  @Test
  public void createSubchannel_outsideSynchronizationContextShouldThrow() {
    createChannel();
    try {
      helper.createSubchannel(CreateSubchannelArgs.newBuilder()
          .setAddresses(addressGroup)
          .build());
      fail("Should throw");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().isEqualTo("Not called from the SynchronizationContext");
    }
  }

  @Test
  public void createSubchannel_resolverOverrideAuthority() {
    EquivalentAddressGroup addressGroup = new EquivalentAddressGroup(
        socketAddress,
        Attributes.newBuilder()
            .set(ATTR_AUTHORITY_OVERRIDE, "resolver.override.authority")
            .build());
    channelBuilder.nameResolverFactory(
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(addressGroup))
            .build());
    createChannel();

    Subchannel subchannel =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    requestConnectionSafely(helper, subchannel);
    ArgumentCaptor<ClientTransportOptions> transportOptionCaptor =
        ArgumentCaptor.forClass(ClientTransportOptions.class);
    verify(mockTransportFactory)
        .newClientTransport(
            any(SocketAddress.class), transportOptionCaptor.capture(), any(ChannelLogger.class));
    assertThat(transportOptionCaptor.getValue().getAuthority())
        .isEqualTo("resolver.override.authority");
  }

  @Test
  public void createSubchannel_channelBuilderOverrideAuthority() {
    channelBuilder.overrideAuthority("channel-builder.override.authority");
    EquivalentAddressGroup addressGroup = new EquivalentAddressGroup(
        socketAddress,
        Attributes.newBuilder()
            .set(ATTR_AUTHORITY_OVERRIDE, "resolver.override.authority")
            .build());
    channelBuilder.nameResolverFactory(
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(addressGroup))
            .build());
    createChannel();

    final Subchannel subchannel =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    requestConnectionSafely(helper, subchannel);
    ArgumentCaptor<ClientTransportOptions> transportOptionCaptor =
        ArgumentCaptor.forClass(ClientTransportOptions.class);
    verify(mockTransportFactory)
        .newClientTransport(
            any(SocketAddress.class), transportOptionCaptor.capture(), any(ChannelLogger.class));
    assertThat(transportOptionCaptor.getValue().getAuthority())
        .isEqualTo("channel-builder.override.authority");
    final List<EquivalentAddressGroup> subchannelEags = new ArrayList<>();
    helper.getSynchronizationContext().execute(
        new Runnable() {
          @Override
          public void run() {
            subchannelEags.addAll(subchannel.getAllAddresses());
          }
        });
    assertThat(subchannelEags).isEqualTo(ImmutableList.of(addressGroup));
  }

  @Test
  public void idleModeDisabled() {
    channelBuilder.nameResolverFactory(
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .build());
    createChannel();

    // In this test suite, the channel is always created with idle mode disabled.
    // No task is scheduled to enter idle mode
    assertEquals(0, timer.numPendingTasks());
    assertEquals(0, executor.numPendingTasks());
  }

  @Test
  public void immediateDeadlineExceeded() {
    createChannel();
    ClientCall<String, Integer> call =
        channel.newCall(method, CallOptions.DEFAULT.withDeadlineAfter(0, TimeUnit.NANOSECONDS));
    call.start(mockCallListener, new Metadata());
    assertEquals(1, executor.runDueTasks());

    verify(mockCallListener).onClose(statusCaptor.capture(), any(Metadata.class));
    Status status = statusCaptor.getValue();
    assertSame(Status.DEADLINE_EXCEEDED.getCode(), status.getCode());
  }

  @Test
  public void startCallBeforeNameResolution() throws Exception {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(ImmutableList.of(addressGroup)).build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    when(mockTransportFactory.getSupportedSocketAddressTypes()).thenReturn(Collections.singleton(
        InetSocketAddress.class));
    channel = new ManagedChannelImpl(
        channelBuilder, mockTransportFactory, expectedUri, nameResolverFactory,
        new FakeBackoffPolicyProvider(),
        balancerRpcExecutorPool, timer.getStopwatchSupplier(),
        Collections.<ClientInterceptor>emptyList(), timer.getTimeProvider());
    Map<String, Object> rawServiceConfig =
        parseConfig("{\"methodConfig\":[{"
            + "\"name\":[{\"service\":\"service\"}],"
            + "\"waitForReady\":true}]}");
    ManagedChannelServiceConfig managedChannelServiceConfig =
        createManagedChannelServiceConfig(rawServiceConfig, null);
    nameResolverFactory.nextConfigOrError.set(
        ConfigOrError.fromConfig(managedChannelServiceConfig));
    Metadata headers = new Metadata();
    ClientStream mockStream = mock(ClientStream.class);
    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    call.start(mockCallListener, headers);

    ArgumentCaptor<Helper> helperCaptor = ArgumentCaptor.forClass(Helper.class);
    verify(mockLoadBalancerProvider).newLoadBalancer(helperCaptor.capture());
    helper = helperCaptor.getValue();
    // Make the transport available
    Subchannel subchannel =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    verify(mockTransportFactory, never())
        .newClientTransport(
            any(SocketAddress.class), any(ClientTransportOptions.class), any(ChannelLogger.class));
    requestConnectionSafely(helper, subchannel);
    verify(mockTransportFactory)
        .newClientTransport(
            any(SocketAddress.class), any(ClientTransportOptions.class), any(ChannelLogger.class));
    MockClientTransportInfo transportInfo = transports.poll();
    ConnectionClientTransport mockTransport = transportInfo.transport;
    ManagedClientTransport.Listener transportListener = transportInfo.listener;
    when(mockTransport.newStream(
            same(method), same(headers), any(CallOptions.class),
            ArgumentMatchers.<ClientStreamTracer[]>any()))
        .thenReturn(mockStream);
    transportListener.transportReady();
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withSubchannel(subchannel));
    updateBalancingStateSafely(helper, READY, mockPicker);
    executor.runDueTasks();

    ArgumentCaptor<CallOptions> callOptionsCaptor = ArgumentCaptor.forClass(CallOptions.class);
    verify(mockTransport).newStream(
        same(method), same(headers), callOptionsCaptor.capture(),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    assertThat(callOptionsCaptor.getValue().isWaitForReady()).isTrue();
    verify(mockStream).start(streamListenerCaptor.capture());

    // Clean up as much as possible to allow the channel to terminate.
    shutdownSafely(helper, subchannel);
    timer.forwardNanos(
        TimeUnit.SECONDS.toNanos(ManagedChannelImpl.SUBCHANNEL_SHUTDOWN_DELAY_SECONDS));
  }

  @Test
  public void newCallWithConfigSelector() {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(ImmutableList.of(addressGroup)).build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    when(mockTransportFactory.getSupportedSocketAddressTypes()).thenReturn(Collections.singleton(
        InetSocketAddress.class));
    channel = new ManagedChannelImpl(
        channelBuilder, mockTransportFactory, expectedUri, nameResolverFactory,
        new FakeBackoffPolicyProvider(),
        balancerRpcExecutorPool, timer.getStopwatchSupplier(),
        Collections.<ClientInterceptor>emptyList(), timer.getTimeProvider());
    nameResolverFactory.nextConfigOrError.set(
        ConfigOrError.fromConfig(ManagedChannelServiceConfig.empty()));
    final Metadata.Key<String> metadataKey =
        Metadata.Key.of("test", Metadata.ASCII_STRING_MARSHALLER);
    final CallOptions.Key<String> callOptionsKey = CallOptions.Key.create("test");
    InternalConfigSelector configSelector = new InternalConfigSelector() {
      @Override
      public Result selectConfig(final PickSubchannelArgs args) {
        return Result.newBuilder()
            .setConfig(ManagedChannelServiceConfig.empty())
            .setInterceptor(
                // An interceptor that mutates CallOptions based on headers value.
                new ClientInterceptor() {
                  String value = args.getHeaders().get(metadataKey);
                  @Override
                  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                    callOptions = callOptions.withOption(callOptionsKey, value);
                    return next.newCall(method, callOptions);
                  }
                })
            .build();
      }
    };
    nameResolverFactory.nextAttributes.set(
        Attributes.newBuilder().set(InternalConfigSelector.KEY, configSelector).build());
    channel.getState(true);
    Metadata headers = new Metadata();
    headers.put(metadataKey, "fooValue");
    ClientStream mockStream = mock(ClientStream.class);
    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    call.start(mockCallListener, headers);

    ArgumentCaptor<Helper> helperCaptor = ArgumentCaptor.forClass(Helper.class);
    verify(mockLoadBalancerProvider).newLoadBalancer(helperCaptor.capture());
    helper = helperCaptor.getValue();
    // Make the transport available
    Subchannel subchannel =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    requestConnectionSafely(helper, subchannel);
    verify(mockTransportFactory)
        .newClientTransport(
            any(SocketAddress.class), any(ClientTransportOptions.class), any(ChannelLogger.class));
    MockClientTransportInfo transportInfo = transports.poll();
    ConnectionClientTransport mockTransport = transportInfo.transport;
    ManagedClientTransport.Listener transportListener = transportInfo.listener;
    when(mockTransport.newStream(
            same(method), same(headers), any(CallOptions.class),
            ArgumentMatchers.<ClientStreamTracer[]>any()))
        .thenReturn(mockStream);
    transportListener.transportReady();
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withSubchannel(subchannel));
    updateBalancingStateSafely(helper, READY, mockPicker);
    executor.runDueTasks();

    ArgumentCaptor<CallOptions> callOptionsCaptor = ArgumentCaptor.forClass(CallOptions.class);
    verify(mockTransport).newStream(
        same(method), same(headers), callOptionsCaptor.capture(),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    assertThat(callOptionsCaptor.getValue().getOption(callOptionsKey)).isEqualTo("fooValue");
    verify(mockStream).start(streamListenerCaptor.capture());

    // Clean up as much as possible to allow the channel to terminate.
    shutdownSafely(helper, subchannel);
    timer.forwardNanos(
        TimeUnit.SECONDS.toNanos(ManagedChannelImpl.SUBCHANNEL_SHUTDOWN_DELAY_SECONDS));
  }

  @Test
  public void pickSubchannelAddOptionalLabel_callsTracer() {
    channelBuilder.directExecutor();
    createChannel();

    updateBalancingStateSafely(helper, TRANSIENT_FAILURE, new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        args.getPickDetailsConsumer().addOptionalLabel("routed", "perfectly");
        return PickResult.withError(Status.UNAVAILABLE.withDescription("expected"));
      }
    });
    ClientStreamTracer tracer = mock(ClientStreamTracer.class);
    ClientStreamTracer.Factory tracerFactory = new ClientStreamTracer.Factory() {
      @Override
      public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
        return tracer;
      }
    };
    ClientCall<String, Integer> call = channel.newCall(
        method, CallOptions.DEFAULT.withStreamTracerFactory(tracerFactory));
    call.start(mockCallListener, new Metadata());

    verify(tracer).addOptionalLabel("routed", "perfectly");
  }

  @Test
  public void metricRecorder_recordsToMetricSink() {
    MetricSink mockSink = mock(MetricSink.class);
    channelBuilder.addMetricSink(mockSink);
    createChannel();

    LongCounterMetricInstrument counter = metricInstrumentRegistry.registerLongCounter(
        "recorder_duration", "Time taken by metric recorder", "s",
        ImmutableList.of("grpc.method"), Collections.emptyList(), false);
    List<String> requiredLabelValues = ImmutableList.of("testMethod");
    List<String> optionalLabelValues = Collections.emptyList();

    helper.getMetricRecorder()
        .addLongCounter(counter, 32, requiredLabelValues, optionalLabelValues);
    verify(mockSink).addLongCounter(eq(counter), eq(32L), eq(requiredLabelValues),
        eq(optionalLabelValues));
  }

  @Test
  public void metricRecorder_fromNameResolverArgs_recordsToMetricSink() {
    MetricSink mockSink1 = mock(MetricSink.class);
    MetricSink mockSink2 = mock(MetricSink.class);
    channelBuilder.addMetricSink(mockSink1);
    channelBuilder.addMetricSink(mockSink2);
    createChannel();

    LongCounterMetricInstrument counter = metricInstrumentRegistry.registerLongCounter(
        "test_counter", "Time taken by metric recorder", "s",
        ImmutableList.of("grpc.method"), Collections.emptyList(), false);
    List<String> requiredLabelValues = ImmutableList.of("testMethod");
    List<String> optionalLabelValues = Collections.emptyList();

    NameResolver.Args args = helper.getNameResolverArgs();
    assertThat(args.getMetricRecorder()).isNotNull();
    args.getMetricRecorder()
        .addLongCounter(counter, 10, requiredLabelValues, optionalLabelValues);
    verify(mockSink1).addLongCounter(eq(counter), eq(10L), eq(requiredLabelValues),
        eq(optionalLabelValues));
    verify(mockSink2).addLongCounter(eq(counter), eq(10L), eq(requiredLabelValues),
        eq(optionalLabelValues));
  }

  @Test
  public void shutdownWithNoTransportsEverCreated() {
    channelBuilder.nameResolverFactory(
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .build());
    createChannel();
    verify(executorPool).getObject();
    verify(executorPool, never()).returnObject(any());
    channel.shutdown();
    assertTrue(channel.isShutdown());
    assertTrue(channel.isTerminated());
    verify(executorPool).returnObject(executor.getScheduledExecutorService());
  }

  @Test
  public void shutdownNow_pendingCallShouldFail() {
    channelBuilder.nameResolverFactory(
        new FakeNameResolverFactory.Builder(expectedUri)
            .setResolvedAtStart(false)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .build());
    createChannel();
    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    call.start(mockCallListener, new Metadata());
    channel.shutdown();
    executor.runDueTasks();
    verify(mockCallListener, never()).onClose(any(Status.class), any(Metadata.class));
    channel.shutdownNow();
    executor.runDueTasks();
    verify(mockCallListener).onClose(statusCaptor.capture(), any(Metadata.class));
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.CANCELLED);
  }

  @Test
  public void shutdownWithNoNameResolution_newCallShouldFail() {
    channelBuilder.nameResolverFactory(
        new FakeNameResolverFactory.Builder(expectedUri)
            .setResolvedAtStart(false)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .build());
    createChannel();
    channel.shutdown();
    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    call.start(mockCallListener, new Metadata());
    executor.runDueTasks();
    verify(mockCallListener).onClose(statusCaptor.capture(), any(Metadata.class));
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Code.UNAVAILABLE);
  }

  @Test
  public void channelzMembership() throws Exception {
    createChannel();
    assertNotNull(channelz.getRootChannel(channel.getLogId().getId()));
    assertFalse(channelz.containsSubchannel(channel.getLogId()));
    channel.shutdownNow();
    channel.awaitTermination(5, TimeUnit.SECONDS);
    assertNull(channelz.getRootChannel(channel.getLogId().getId()));
    assertFalse(channelz.containsSubchannel(channel.getLogId()));
  }

  @Test
  public void channelzMembership_subchannel() throws Exception {
    createChannel();
    assertNotNull(channelz.getRootChannel(channel.getLogId().getId()));

    AbstractSubchannel subchannel =
        (AbstractSubchannel) createSubchannelSafely(
            helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    // subchannels are not root channels
    assertNull(
        channelz.getRootChannel(subchannel.getInstrumentedInternalSubchannel().getLogId().getId()));
    assertTrue(
        channelz.containsSubchannel(subchannel.getInstrumentedInternalSubchannel().getLogId()));
    assertThat(getStats(channel).subchannels)
        .containsExactly(subchannel.getInstrumentedInternalSubchannel());

    requestConnectionSafely(helper, subchannel);
    MockClientTransportInfo transportInfo = transports.poll();
    assertNotNull(transportInfo);
    assertTrue(channelz.containsClientSocket(transportInfo.transport.getLogId()));
    transportInfo.listener.transportReady();

    // terminate transport
    transportInfo.listener.transportShutdown(Status.CANCELLED);
    transportInfo.listener.transportTerminated();
    assertFalse(channelz.containsClientSocket(transportInfo.transport.getLogId()));

    // terminate subchannel
    assertTrue(
        channelz.containsSubchannel(subchannel.getInstrumentedInternalSubchannel().getLogId()));
    shutdownSafely(helper, subchannel);
    timer.forwardTime(ManagedChannelImpl.SUBCHANNEL_SHUTDOWN_DELAY_SECONDS, TimeUnit.SECONDS);
    timer.runDueTasks();
    assertFalse(
        channelz.containsSubchannel(subchannel.getInstrumentedInternalSubchannel().getLogId()));
    assertThat(getStats(channel).subchannels).isEmpty();

    // channel still appears
    assertNotNull(channelz.getRootChannel(channel.getLogId().getId()));
  }

  @Test
  public void channelzMembership_oob() throws Exception {
    createChannel();
    OobChannel oob = (OobChannel) helper.createOobChannel(
        Collections.singletonList(addressGroup), AUTHORITY);
    // oob channels are not root channels
    assertNull(channelz.getRootChannel(oob.getLogId().getId()));
    assertTrue(channelz.containsSubchannel(oob.getLogId()));
    assertThat(getStats(channel).subchannels).containsExactly(oob);
    assertTrue(channelz.containsSubchannel(oob.getLogId()));

    AbstractSubchannel subchannel = (AbstractSubchannel) oob.getSubchannel();
    assertTrue(
        channelz.containsSubchannel(subchannel.getInstrumentedInternalSubchannel().getLogId()));
    assertThat(getStats(oob).subchannels)
        .containsExactly(subchannel.getInstrumentedInternalSubchannel());
    assertTrue(
        channelz.containsSubchannel(subchannel.getInstrumentedInternalSubchannel().getLogId()));

    oob.getSubchannel().requestConnection();
    MockClientTransportInfo transportInfo = transports.poll();
    assertNotNull(transportInfo);
    assertTrue(channelz.containsClientSocket(transportInfo.transport.getLogId()));

    // terminate transport
    transportInfo.listener.transportShutdown(Status.INTERNAL);
    transportInfo.listener.transportTerminated();
    assertFalse(channelz.containsClientSocket(transportInfo.transport.getLogId()));

    // terminate oobchannel
    oob.shutdown();
    assertFalse(channelz.containsSubchannel(oob.getLogId()));
    assertThat(getStats(channel).subchannels).isEmpty();
    assertFalse(
        channelz.containsSubchannel(subchannel.getInstrumentedInternalSubchannel().getLogId()));

    // channel still appears
    assertNotNull(channelz.getRootChannel(channel.getLogId().getId()));
  }

  @Test
  public void callsAndShutdown() {
    subtestCallsAndShutdown(false, false);
  }

  @Test
  public void callsAndShutdownNow() {
    subtestCallsAndShutdown(true, false);
  }

  /** Make sure shutdownNow() after shutdown() has an effect. */
  @Test
  public void callsAndShutdownAndShutdownNow() {
    subtestCallsAndShutdown(false, true);
  }

  private void subtestCallsAndShutdown(boolean shutdownNow, boolean shutdownNowAfterShutdown) {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri).build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    createChannel();
    verify(executorPool).getObject();
    ClientStream mockStream = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    Metadata headers = new Metadata();
    Metadata headers2 = new Metadata();

    // Configure the picker so that first RPC goes to delayed transport, and second RPC goes to
    // real transport.
    Subchannel subchannel =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    requestConnectionSafely(helper, subchannel);
    verify(mockTransportFactory)
        .newClientTransport(
            any(SocketAddress.class), any(ClientTransportOptions.class), any(ChannelLogger.class));
    MockClientTransportInfo transportInfo = transports.poll();
    ConnectionClientTransport mockTransport = transportInfo.transport;
    verify(mockTransport).start(any(ManagedClientTransport.Listener.class));
    ManagedClientTransport.Listener transportListener = transportInfo.listener;
    when(mockTransport.newStream(
            same(method), same(headers), same(CallOptions.DEFAULT),
            ArgumentMatchers.<ClientStreamTracer[]>any()))
        .thenReturn(mockStream);
    when(mockTransport.newStream(
            same(method), same(headers2), same(CallOptions.DEFAULT),
            ArgumentMatchers.<ClientStreamTracer[]>any()))
        .thenReturn(mockStream2);
    transportListener.transportReady();
    when(mockPicker.pickSubchannel(
        eqPickSubchannelArgs(method, headers, CallOptions.DEFAULT))).thenReturn(
        PickResult.withNoResult());
    when(mockPicker.pickSubchannel(
        eqPickSubchannelArgs(method, headers2, CallOptions.DEFAULT))).thenReturn(
        PickResult.withSubchannel(subchannel));
    updateBalancingStateSafely(helper, READY, mockPicker);

    // First RPC, will be pending
    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    verify(mockTransportFactory)
        .newClientTransport(
            any(SocketAddress.class), any(ClientTransportOptions.class), any(ChannelLogger.class));
    call.start(mockCallListener, headers);

    verify(mockTransport, never()).newStream(
        same(method), same(headers), same(CallOptions.DEFAULT),
        ArgumentMatchers.<ClientStreamTracer[]>any());

    // Second RPC, will be assigned to the real transport
    ClientCall<String, Integer> call2 = channel.newCall(method, CallOptions.DEFAULT);
    call2.start(mockCallListener2, headers2);
    verify(mockTransport).newStream(
        same(method), same(headers2), same(CallOptions.DEFAULT),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    verify(mockTransport).newStream(
        same(method), same(headers2), same(CallOptions.DEFAULT),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    verify(mockStream2).start(any(ClientStreamListener.class));

    // Shutdown
    if (shutdownNow) {
      channel.shutdownNow();
    } else {
      channel.shutdown();
      if (shutdownNowAfterShutdown) {
        channel.shutdownNow();
        shutdownNow = true;
      }
    }
    assertTrue(channel.isShutdown());
    assertFalse(channel.isTerminated());
    assertThat(nameResolverFactory.resolvers).hasSize(1);
    verify(mockLoadBalancerProvider).newLoadBalancer(any(Helper.class));

    // Further calls should fail without going to the transport
    ClientCall<String, Integer> call3 = channel.newCall(method, CallOptions.DEFAULT);
    call3.start(mockCallListener3, headers2);
    timer.runDueTasks();
    executor.runDueTasks();

    verify(mockCallListener3).onClose(statusCaptor.capture(), any(Metadata.class));
    assertSame(Status.Code.UNAVAILABLE, statusCaptor.getValue().getCode());

    if (shutdownNow) {
      // LoadBalancer and NameResolver are shut down as soon as delayed transport is terminated.
      verify(mockLoadBalancer).shutdown();
      assertTrue(nameResolverFactory.resolvers.get(0).shutdown);
      // call should have been aborted by delayed transport
      executor.runDueTasks();
      verify(mockCallListener).onClose(same(ManagedChannelImpl.SHUTDOWN_NOW_STATUS),
          any(Metadata.class));
    } else {
      // LoadBalancer and NameResolver are still running.
      verify(mockLoadBalancer, never()).shutdown();
      assertFalse(nameResolverFactory.resolvers.get(0).shutdown);
      // call and call2 are still alive, and can still be assigned to a real transport
      SubchannelPicker picker2 = mock(SubchannelPicker.class);
      when(picker2.pickSubchannel(eqPickSubchannelArgs(method, headers, CallOptions.DEFAULT)))
          .thenReturn(PickResult.withSubchannel(subchannel));
      updateBalancingStateSafely(helper, READY, picker2);
      executor.runDueTasks();
      verify(mockTransport).newStream(
          same(method), same(headers), same(CallOptions.DEFAULT),
          ArgumentMatchers.<ClientStreamTracer[]>any());
      verify(mockStream).start(any(ClientStreamListener.class));
    }

    // After call is moved out of delayed transport, LoadBalancer, NameResolver and the transports
    // will be shutdown.
    verify(mockLoadBalancer).shutdown();
    assertTrue(nameResolverFactory.resolvers.get(0).shutdown);

    if (shutdownNow) {
      // Channel shutdownNow() all subchannels after shutting down LoadBalancer
      verify(mockTransport).shutdownNow(ManagedChannelImpl.SHUTDOWN_NOW_STATUS);
    } else {
      verify(mockTransport, never()).shutdownNow(any(Status.class));
    }
    // LoadBalancer should shutdown the subchannel
    shutdownSafely(helper, subchannel);
    if (shutdownNow) {
      verify(mockTransport).shutdown(same(ManagedChannelImpl.SHUTDOWN_NOW_STATUS));
    } else {
      verify(mockTransport).shutdown(same(ManagedChannelImpl.SHUTDOWN_STATUS));
    }

    // Killing the remaining real transport will terminate the channel
    transportListener.transportShutdown(Status.UNAVAILABLE);
    assertFalse(channel.isTerminated());
    verify(executorPool, never()).returnObject(any());
    transportListener.transportTerminated();
    assertTrue(channel.isTerminated());
    verify(executorPool).returnObject(executor.getScheduledExecutorService());
    verifyNoMoreInteractions(balancerRpcExecutorPool);

    verify(mockTransportFactory)
        .newClientTransport(
            any(SocketAddress.class), any(ClientTransportOptions.class), any(ChannelLogger.class));
    verify(mockTransportFactory).close();
    verify(mockTransport, atLeast(0)).getLogId();
    verifyNoMoreInteractions(mockTransport);
  }

  @Test
  public void noMoreCallbackAfterLoadBalancerShutdown() {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    Status resolutionError = Status.UNAVAILABLE.withDescription("Resolution failed");
    createChannel();

    FakeNameResolverFactory.FakeNameResolver resolver = nameResolverFactory.resolvers.get(0);
    verify(mockLoadBalancerProvider).newLoadBalancer(any(Helper.class));
    verify(mockLoadBalancer).acceptResolvedAddresses(resolvedAddressCaptor.capture());
    assertThat(resolvedAddressCaptor.getValue().getAddresses()).containsExactly(addressGroup);

    SubchannelStateListener stateListener1 = mock(SubchannelStateListener.class);
    SubchannelStateListener stateListener2 = mock(SubchannelStateListener.class);
    Subchannel subchannel1 =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, stateListener1);
    Subchannel subchannel2 =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, stateListener2);
    requestConnectionSafely(helper, subchannel1);
    requestConnectionSafely(helper, subchannel2);
    verify(mockTransportFactory, times(2))
        .newClientTransport(
            any(SocketAddress.class), any(ClientTransportOptions.class), any(ChannelLogger.class));
    MockClientTransportInfo transportInfo1 = transports.poll();
    MockClientTransportInfo transportInfo2 = transports.poll();

    // LoadBalancer receives all sorts of callbacks
    transportInfo1.listener.transportReady();

    verify(stateListener1, times(2)).onSubchannelState(stateInfoCaptor.capture());
    assertSame(CONNECTING, stateInfoCaptor.getAllValues().get(0).getState());
    assertSame(READY, stateInfoCaptor.getAllValues().get(1).getState());

    verify(stateListener2).onSubchannelState(stateInfoCaptor.capture());
    assertSame(CONNECTING, stateInfoCaptor.getValue().getState());

    resolver.listener.onError(resolutionError);
    verify(mockLoadBalancer).handleNameResolutionError(resolutionError);

    verifyNoMoreInteractions(mockLoadBalancer);

    channel.shutdown();
    verify(mockLoadBalancer).shutdown();
    verifyNoMoreInteractions(stateListener1, stateListener2);

    // LoadBalancer will normally shutdown all subchannels
    shutdownSafely(helper, subchannel1);
    shutdownSafely(helper, subchannel2);

    // Since subchannels are shutdown, SubchannelStateListeners will only get SHUTDOWN regardless of
    // the transport states.
    transportInfo1.listener.transportShutdown(Status.UNAVAILABLE);
    transportInfo2.listener.transportReady();
    verify(stateListener1).onSubchannelState(ConnectivityStateInfo.forNonError(SHUTDOWN));
    verify(stateListener2).onSubchannelState(ConnectivityStateInfo.forNonError(SHUTDOWN));
    verifyNoMoreInteractions(stateListener1, stateListener2);

    // No more callback should be delivered to LoadBalancer after it's shut down
    resolver.listener.onError(resolutionError);
    resolver.resolved();
    verifyNoMoreInteractions(mockLoadBalancer);
  }

  @Test  
  public void noMoreCallbackAfterLoadBalancerShutdown_configError() throws InterruptedException {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    Status resolutionError = Status.UNAVAILABLE.withDescription("Resolution failed");
    createChannel();

    FakeNameResolverFactory.FakeNameResolver resolver = nameResolverFactory.resolvers.get(0);
    verify(mockLoadBalancerProvider).newLoadBalancer(any(Helper.class));
    verify(mockLoadBalancer).acceptResolvedAddresses(resolvedAddressCaptor.capture());
    assertThat(resolvedAddressCaptor.getValue().getAddresses()).containsExactly(addressGroup);

    SubchannelStateListener stateListener1 = mock(SubchannelStateListener.class);
    SubchannelStateListener stateListener2 = mock(SubchannelStateListener.class);
    Subchannel subchannel1 =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, stateListener1);
    Subchannel subchannel2 =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, stateListener2);
    requestConnectionSafely(helper, subchannel1);
    requestConnectionSafely(helper, subchannel2);
    verify(mockTransportFactory, times(2))
        .newClientTransport(
            any(SocketAddress.class), any(ClientTransportOptions.class), any(ChannelLogger.class));
    MockClientTransportInfo transportInfo1 = transports.poll();
    MockClientTransportInfo transportInfo2 = transports.poll();

    // LoadBalancer receives all sorts of callbacks
    transportInfo1.listener.transportReady();

    verify(stateListener1, times(2)).onSubchannelState(stateInfoCaptor.capture());
    assertSame(CONNECTING, stateInfoCaptor.getAllValues().get(0).getState());
    assertSame(READY, stateInfoCaptor.getAllValues().get(1).getState());

    verify(stateListener2).onSubchannelState(stateInfoCaptor.capture());
    assertSame(CONNECTING, stateInfoCaptor.getValue().getState());

    channel.syncContext.execute(() ->
        resolver.listener.onResult2(
            ResolutionResult.newBuilder()
                .setAddressesOrError(StatusOr.fromStatus(resolutionError)).build()));
    verify(mockLoadBalancer).handleNameResolutionError(resolutionError);

    verifyNoMoreInteractions(mockLoadBalancer);

    channel.shutdown();
    verify(mockLoadBalancer).shutdown();
    verifyNoMoreInteractions(stateListener1, stateListener2);

    // LoadBalancer will normally shutdown all subchannels
    shutdownSafely(helper, subchannel1);
    shutdownSafely(helper, subchannel2);

    // Since subchannels are shutdown, SubchannelStateListeners will only get SHUTDOWN regardless of
    // the transport states.
    transportInfo1.listener.transportShutdown(Status.UNAVAILABLE);
    transportInfo2.listener.transportReady();
    verify(stateListener1).onSubchannelState(ConnectivityStateInfo.forNonError(SHUTDOWN));
    verify(stateListener2).onSubchannelState(ConnectivityStateInfo.forNonError(SHUTDOWN));
    verifyNoMoreInteractions(stateListener1, stateListener2);

    // No more callback should be delivered to LoadBalancer after it's shut down
    channel.syncContext.execute(() ->
        resolver.listener.onResult2(
            ResolutionResult.newBuilder()
                .setAddressesOrError(StatusOr.fromStatus(resolutionError)).build()));
    assertThat(timer.getPendingTasks()).isEmpty();
    resolver.resolved();
    verifyNoMoreInteractions(mockLoadBalancer);
  }

  @Test
  public void addressResolutionError_noPriorNameResolution_usesDefaultServiceConfig()
      throws Exception {
    Map<String, Object> rawServiceConfig =
        parseConfig("{\"methodConfig\":[{"
            + "\"name\":[{\"service\":\"service\"}],"
            + "\"waitForReady\":true}]}");
    ManagedChannelServiceConfig managedChannelServiceConfig =
        createManagedChannelServiceConfig(rawServiceConfig, null);
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .setResolvedAtStart(false)
            .build();
    nameResolverFactory.nextConfigOrError.set(
        ConfigOrError.fromConfig(managedChannelServiceConfig));
    channelBuilder.nameResolverFactory(nameResolverFactory);
    Map<String, Object> defaultServiceConfig =
        parseConfig("{\"methodConfig\":[{"
            + "\"name\":[{\"service\":\"service\"}],"
            + "\"waitForReady\":true}]}");
    channelBuilder.defaultServiceConfig(defaultServiceConfig);
    Status resolutionError = Status.UNAVAILABLE.withDescription("Resolution failed");
    channelBuilder.maxTraceEvents(10);
    createChannel();
    FakeNameResolverFactory.FakeNameResolver resolver = nameResolverFactory.resolvers.get(0);

    resolver.listener.onError(resolutionError);

    InternalConfigSelector configSelector = channel.getConfigSelector();
    ManagedChannelServiceConfig config =
        (ManagedChannelServiceConfig) configSelector.selectConfig(null).getConfig();
    MethodInfo methodConfig = config.getMethodConfig(method);
    assertThat(methodConfig.waitForReady).isTrue();
    timer.forwardNanos(1234);
    assertThat(getStats(channel).channelTrace.events).contains(new ChannelTrace.Event.Builder()
        .setDescription("Initial Name Resolution error, using default service config")
        .setSeverity(Severity.CT_ERROR)
        .setTimestampNanos(0)
        .build());

    // Check that "lastServiceConfig" variable has been set above: a config resolution with the same
    // config simply gets ignored and not gets reassigned.
    resolver.resolved();
    timer.forwardNanos(1234);
    assertThat(Iterables.filter(
          getStats(channel).channelTrace.events,
          event -> event.description.equals("Service config changed")))
        .isEmpty();
  }

  @Test
  public void interceptor() throws Exception {
    final AtomicLong atomic = new AtomicLong();
    ClientInterceptor interceptor = new ClientInterceptor() {
      @Override
      public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> interceptCall(
          MethodDescriptor<RequestT, ResponseT> method, CallOptions callOptions,
          Channel next) {
        atomic.set(1);
        return next.newCall(method, callOptions);
      }
    };
    createChannel(interceptor);
    assertNotNull(channel.newCall(method, CallOptions.DEFAULT));
    assertEquals(1, atomic.get());
  }

  @Test
  public void callOptionsExecutor() {
    Metadata headers = new Metadata();
    ClientStream mockStream = mock(ClientStream.class);
    FakeClock callExecutor = new FakeClock();
    createChannel();

    // Start a call with a call executor
    CallOptions options =
        CallOptions.DEFAULT.withExecutor(callExecutor.getScheduledExecutorService());
    ClientCall<String, Integer> call = channel.newCall(method, options);
    call.start(mockCallListener, headers);

    // Make the transport available
    Subchannel subchannel =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    verify(mockTransportFactory, never())
        .newClientTransport(
            any(SocketAddress.class), any(ClientTransportOptions.class), any(ChannelLogger.class));
    requestConnectionSafely(helper, subchannel);
    verify(mockTransportFactory)
        .newClientTransport(
            any(SocketAddress.class), any(ClientTransportOptions.class), any(ChannelLogger.class));
    MockClientTransportInfo transportInfo = transports.poll();
    ConnectionClientTransport mockTransport = transportInfo.transport;
    ManagedClientTransport.Listener transportListener = transportInfo.listener;
    when(mockTransport.newStream(
            same(method), same(headers), any(CallOptions.class),
            ArgumentMatchers.<ClientStreamTracer[]>any()))
        .thenReturn(mockStream);
    transportListener.transportReady();
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withSubchannel(subchannel));
    assertEquals(0, callExecutor.numPendingTasks());
    updateBalancingStateSafely(helper, READY, mockPicker);

    // Real streams are started in the call executor if they were previously buffered.
    assertEquals(1, callExecutor.runDueTasks());
    verify(mockTransport).newStream(
        same(method), same(headers), same(options), ArgumentMatchers.<ClientStreamTracer[]>any());
    verify(mockStream).start(streamListenerCaptor.capture());

    // Call listener callbacks are also run in the call executor
    ClientStreamListener streamListener = streamListenerCaptor.getValue();
    Metadata trailers = new Metadata();
    assertEquals(0, callExecutor.numPendingTasks());
    streamListener.closed(Status.CANCELLED, PROCESSED, trailers);
    verify(mockCallListener, never()).onClose(same(Status.CANCELLED), same(trailers));
    assertEquals(1, callExecutor.runDueTasks());
    verify(mockCallListener).onClose(same(Status.CANCELLED), same(trailers));


    transportListener.transportShutdown(Status.UNAVAILABLE);
    transportListener.transportTerminated();

    // Clean up as much as possible to allow the channel to terminate.
    shutdownSafely(helper, subchannel);
    timer.forwardNanos(
        TimeUnit.SECONDS.toNanos(ManagedChannelImpl.SUBCHANNEL_SHUTDOWN_DELAY_SECONDS));
  }

  @Test
  public void loadBalancerThrowsInHandleResolvedAddresses() {
    RuntimeException ex = new RuntimeException("simulated");
    // Delay the success of name resolution until allResolved() is called
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setResolvedAtStart(false)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    createChannel();

    verify(mockLoadBalancerProvider).newLoadBalancer(any(Helper.class));
    doThrow(ex).when(mockLoadBalancer).acceptResolvedAddresses(any(ResolvedAddresses.class));

    // NameResolver returns addresses.
    nameResolverFactory.allResolved();

    // Exception thrown from balancer is caught by ChannelExecutor, making channel enter panic mode.
    verifyPanicMode(ex);
  }

  @Test
  public void delayedNameResolution() throws Exception {
    ClientStream mockStream = mock(ClientStream.class);
    final ClientStreamTracer tracer = new ClientStreamTracer() {};
    ClientStreamTracer.Factory factory = new ClientStreamTracer.Factory() {
      @Override
      public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
        return tracer;
      }
    };
    FakeNameResolverFactory nsFactory = new FakeNameResolverFactory.Builder(expectedUri)
        .setResolvedAtStart(false).build();
    channelBuilder.nameResolverFactory(nsFactory);
    createChannel();

    CallOptions callOptions = CallOptions.DEFAULT.withStreamTracerFactory(factory);
    ClientCall<String, Integer> call = channel.newCall(method, callOptions);
    call.start(mockCallListener, new Metadata());

    Thread.sleep(500);
    nsFactory.allResolved();
    Subchannel subchannel =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    requestConnectionSafely(helper, subchannel);
    MockClientTransportInfo transportInfo = transports.poll();
    transportInfo.listener.transportReady();
    ClientTransport mockTransport = transportInfo.transport;
    when(mockTransport.newStream(
        any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class),
        ArgumentMatchers.<ClientStreamTracer[]>any()))
        .thenReturn(mockStream);
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class))).thenReturn(
        PickResult.withSubchannel(subchannel));

    updateBalancingStateSafely(helper, READY, mockPicker);
    assertEquals(2, executor.runDueTasks());

    verify(mockPicker).pickSubchannel(any(PickSubchannelArgs.class));
    verify(mockTransport).newStream(
        same(method), any(Metadata.class), callOptionsCaptor.capture(),
        tracersCaptor.capture());
    assertThat(Arrays.asList(tracersCaptor.getValue()).contains(tracer)).isTrue();
    Long realDelay = callOptionsCaptor.getValue().getOption(NAME_RESOLUTION_DELAYED);
    assertThat(realDelay).isNotNull();
    assertThat(realDelay).isAtLeast(
        TimeUnit.MILLISECONDS.toNanos(400));//sleep not precise
  }

  @Test
  public void nameResolvedAfterChannelShutdown() {
    // Delay the success of name resolution until allResolved() is called.
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri).setResolvedAtStart(false).build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    createChannel();

    channel.shutdown();

    assertTrue(channel.isShutdown());
    assertTrue(channel.isTerminated());
    verify(mockLoadBalancer).shutdown();
    // Name resolved after the channel is shut down, which is possible if the name resolution takes
    // time and is not cancellable. The resolved address will be dropped.
    nameResolverFactory.allResolved();
    verifyNoMoreInteractions(mockLoadBalancer);
  }

  /**
   * Verify that if the first resolved address points to a server that cannot be connected, the call
   * will end up with the second address which works.
   */
  @Test
  public void firstResolvedServerFailedToConnect() throws Exception {
    final SocketAddress goodAddress = new SocketAddress() {
        @Override public String toString() {
          return "goodAddress";
        }
      };
    final SocketAddress badAddress = new SocketAddress() {
        @Override public String toString() {
          return "badAddress";
        }
      };
    InOrder inOrder = inOrder(mockLoadBalancer, subchannelStateListener);

    List<SocketAddress> resolvedAddrs = Arrays.asList(badAddress, goodAddress);
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(resolvedAddrs)))
            .build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    createChannel();

    // Start the call
    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    Metadata headers = new Metadata();
    call.start(mockCallListener, headers);
    executor.runDueTasks();

    // Simulate name resolution results
    EquivalentAddressGroup addressGroup = new EquivalentAddressGroup(resolvedAddrs);
    inOrder.verify(mockLoadBalancer).acceptResolvedAddresses(resolvedAddressCaptor.capture());
    assertThat(resolvedAddressCaptor.getValue().getAddresses()).containsExactly(addressGroup);
    Subchannel subchannel =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withSubchannel(subchannel));
    requestConnectionSafely(helper, subchannel);
    inOrder.verify(subchannelStateListener).onSubchannelState(stateInfoCaptor.capture());
    assertEquals(CONNECTING, stateInfoCaptor.getValue().getState());

    // The channel will starts with the first address (badAddress)
    verify(mockTransportFactory)
        .newClientTransport(
            same(badAddress), any(ClientTransportOptions.class), any(ChannelLogger.class));
    verify(mockTransportFactory, times(0))
        .newClientTransport(
            same(goodAddress), any(ClientTransportOptions.class), any(ChannelLogger.class));

    MockClientTransportInfo badTransportInfo = transports.poll();
    // Which failed to connect
    badTransportInfo.listener.transportShutdown(Status.UNAVAILABLE);
    inOrder.verifyNoMoreInteractions();

    // The channel then try the second address (goodAddress)
    verify(mockTransportFactory)
        .newClientTransport(
            same(goodAddress), any(ClientTransportOptions.class), any(ChannelLogger.class));
    MockClientTransportInfo goodTransportInfo = transports.poll();
    when(goodTransportInfo.transport.newStream(
            any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class),
            ArgumentMatchers.<ClientStreamTracer[]>any()))
        .thenReturn(mock(ClientStream.class));

    goodTransportInfo.listener.transportReady();
    inOrder.verify(subchannelStateListener).onSubchannelState(stateInfoCaptor.capture());
    assertEquals(READY, stateInfoCaptor.getValue().getState());

    // A typical LoadBalancer will call this once the subchannel becomes READY
    updateBalancingStateSafely(helper, READY, mockPicker);
    // Delayed transport uses the app executor to create real streams.
    executor.runDueTasks();

    verify(goodTransportInfo.transport).newStream(
        same(method), same(headers), same(CallOptions.DEFAULT),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    // The bad transport was never used.
    verify(badTransportInfo.transport, times(0)).newStream(
        any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class),
        ArgumentMatchers.<ClientStreamTracer[]>any());
  }

  @Test
  public void failFastRpcFailFromErrorFromBalancer() {
    subtestFailRpcFromBalancer(false, false, true);
  }

  @Test
  public void failFastRpcFailFromDropFromBalancer() {
    subtestFailRpcFromBalancer(false, true, true);
  }

  @Test
  public void waitForReadyRpcImmuneFromErrorFromBalancer() {
    subtestFailRpcFromBalancer(true, false, false);
  }

  @Test
  public void waitForReadyRpcFailFromDropFromBalancer() {
    subtestFailRpcFromBalancer(true, true, true);
  }

  private void subtestFailRpcFromBalancer(boolean waitForReady, boolean drop, boolean shouldFail) {
    createChannel();

    // This call will be buffered by the channel, thus involve delayed transport
    CallOptions callOptions = CallOptions.DEFAULT;
    if (waitForReady) {
      callOptions = callOptions.withWaitForReady();
    } else {
      callOptions = callOptions.withoutWaitForReady();
    }
    ClientCall<String, Integer> call1 = channel.newCall(method, callOptions);
    call1.start(mockCallListener, new Metadata());

    SubchannelPicker picker = mock(SubchannelPicker.class);
    Status status = Status.UNAVAILABLE.withDescription("for test");

    when(picker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(drop ? PickResult.withDrop(status) : PickResult.withError(status));
    updateBalancingStateSafely(helper, READY, picker);

    executor.runDueTasks();
    if (shouldFail) {
      verify(mockCallListener).onClose(same(status), any(Metadata.class));
    } else {
      verifyNoInteractions(mockCallListener);
    }

    // This call doesn't involve delayed transport
    ClientCall<String, Integer> call2 = channel.newCall(method, callOptions);
    call2.start(mockCallListener2, new Metadata());

    executor.runDueTasks();
    if (shouldFail) {
      verify(mockCallListener2).onClose(same(status), any(Metadata.class));
    } else {
      verifyNoInteractions(mockCallListener2);
    }
  }

  /**
   * Verify that if all resolved addresses failed to connect, a fail-fast call will fail, while a
   * wait-for-ready call will still be buffered.
   */
  @Test
  public void allServersFailedToConnect() throws Exception {
    final SocketAddress addr1 = new SocketAddress() {
        @Override public String toString() {
          return "addr1";
        }
      };
    final SocketAddress addr2 = new SocketAddress() {
        @Override public String toString() {
          return "addr2";
        }
      };
    InOrder inOrder = inOrder(mockLoadBalancer, subchannelStateListener);

    List<SocketAddress> resolvedAddrs = Arrays.asList(addr1, addr2);

    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(resolvedAddrs)))
            .build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    createChannel();

    // Start a wait-for-ready call
    ClientCall<String, Integer> call =
        channel.newCall(method, CallOptions.DEFAULT.withWaitForReady());
    Metadata headers = new Metadata();
    call.start(mockCallListener, headers);
    // ... and a fail-fast call
    ClientCall<String, Integer> call2 =
        channel.newCall(method, CallOptions.DEFAULT.withoutWaitForReady());
    call2.start(mockCallListener2, headers);
    executor.runDueTasks();

    // Simulate name resolution results
    EquivalentAddressGroup addressGroup = new EquivalentAddressGroup(resolvedAddrs);
    inOrder.verify(mockLoadBalancer).acceptResolvedAddresses(resolvedAddressCaptor.capture());
    assertThat(resolvedAddressCaptor.getValue().getAddresses()).containsExactly(addressGroup);

    Subchannel subchannel =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withSubchannel(subchannel));
    requestConnectionSafely(helper, subchannel);

    inOrder.verify(subchannelStateListener).onSubchannelState(stateInfoCaptor.capture());
    assertEquals(CONNECTING, stateInfoCaptor.getValue().getState());

    // Connecting to server1, which will fail
    verify(mockTransportFactory)
        .newClientTransport(
            same(addr1), any(ClientTransportOptions.class), any(ChannelLogger.class));
    verify(mockTransportFactory, times(0))
        .newClientTransport(
            same(addr2), any(ClientTransportOptions.class), any(ChannelLogger.class));
    MockClientTransportInfo transportInfo1 = transports.poll();
    transportInfo1.listener.transportShutdown(Status.UNAVAILABLE);

    // Connecting to server2, which will fail too
    verify(mockTransportFactory)
        .newClientTransport(
            same(addr2), any(ClientTransportOptions.class), any(ChannelLogger.class));
    MockClientTransportInfo transportInfo2 = transports.poll();
    Status server2Error = Status.UNAVAILABLE.withDescription("Server2 failed to connect");
    transportInfo2.listener.transportShutdown(server2Error);

    // ... which makes the subchannel enter TRANSIENT_FAILURE. The last error Status is propagated
    // to LoadBalancer.
    inOrder.verify(subchannelStateListener).onSubchannelState(stateInfoCaptor.capture());
    assertEquals(TRANSIENT_FAILURE, stateInfoCaptor.getValue().getState());
    assertSame(server2Error, stateInfoCaptor.getValue().getStatus());

    // A typical LoadBalancer would create a picker with error
    SubchannelPicker picker2 = mock(SubchannelPicker.class);
    when(picker2.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withError(server2Error));
    updateBalancingStateSafely(helper, TRANSIENT_FAILURE, picker2);
    executor.runDueTasks();

    // ... which fails the fail-fast call
    verify(mockCallListener2).onClose(same(server2Error), any(Metadata.class));
    // ... while the wait-for-ready call stays
    verifyNoMoreInteractions(mockCallListener);
    // No real stream was ever created
    verify(transportInfo1.transport, times(0)).newStream(
        any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    verify(transportInfo2.transport, times(0)).newStream(
        any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class),
        ArgumentMatchers.<ClientStreamTracer[]>any());
  }

  @Test
  public void subchannels() {
    createChannel();

    // createSubchannel() always return a new Subchannel
    Attributes attrs1 = Attributes.newBuilder().set(SUBCHANNEL_ATTR_KEY, "attr1").build();
    Attributes attrs2 = Attributes.newBuilder().set(SUBCHANNEL_ATTR_KEY, "attr2").build();
    SubchannelStateListener listener1 = mock(SubchannelStateListener.class);
    SubchannelStateListener listener2 = mock(SubchannelStateListener.class);
    final Subchannel sub1 = createSubchannelSafely(helper, addressGroup, attrs1, listener1);
    final Subchannel sub2 = createSubchannelSafely(helper, addressGroup, attrs2, listener2);
    assertNotSame(sub1, sub2);
    assertNotSame(attrs1, attrs2);
    assertSame(attrs1, sub1.getAttributes());
    assertSame(attrs2, sub2.getAttributes());

    final AtomicBoolean snippetPassed = new AtomicBoolean(false);
    helper.getSynchronizationContext().execute(new Runnable() {
        @Override
        public void run() {
          // getAddresses() must be called from sync context
          assertSame(addressGroup, sub1.getAddresses());
          assertSame(addressGroup, sub2.getAddresses());
          snippetPassed.set(true);
        }
      });
    assertThat(snippetPassed.get()).isTrue();

    // requestConnection()
    verify(mockTransportFactory, never())
        .newClientTransport(
            any(SocketAddress.class),
            any(ClientTransportOptions.class),
            any(TransportLogger.class));
    requestConnectionSafely(helper, sub1);
    verify(mockTransportFactory)
        .newClientTransport(
            eq(socketAddress),
            eq(clientTransportOptions),
            isA(TransportLogger.class));
    MockClientTransportInfo transportInfo1 = transports.poll();
    assertNotNull(transportInfo1);

    requestConnectionSafely(helper, sub2);
    verify(mockTransportFactory, times(2))
        .newClientTransport(
            eq(socketAddress),
            eq(clientTransportOptions),
            isA(TransportLogger.class));
    MockClientTransportInfo transportInfo2 = transports.poll();
    assertNotNull(transportInfo2);

    requestConnectionSafely(helper, sub1);
    requestConnectionSafely(helper, sub2);
    // The subchannel doesn't matter since this isn't called
    verify(mockTransportFactory, times(2))
        .newClientTransport(
            eq(socketAddress), eq(clientTransportOptions), isA(TransportLogger.class));

    // updateAddresses()
    updateAddressesSafely(helper, sub1, Collections.singletonList(addressGroup2));
    assertThat(((InternalSubchannel) sub1.getInternalSubchannel()).getAddressGroups())
        .isEqualTo(Collections.singletonList(addressGroup2));

    // shutdown() has a delay
    shutdownSafely(helper, sub1);
    timer.forwardTime(ManagedChannelImpl.SUBCHANNEL_SHUTDOWN_DELAY_SECONDS - 1, TimeUnit.SECONDS);
    shutdownSafely(helper, sub1);
    verify(transportInfo1.transport, never()).shutdown(any(Status.class));
    timer.forwardTime(1, TimeUnit.SECONDS);
    verify(transportInfo1.transport).shutdown(same(ManagedChannelImpl.SUBCHANNEL_SHUTDOWN_STATUS));

    // ... but not after Channel is terminating
    verify(mockLoadBalancer, never()).shutdown();
    channel.shutdown();
    verify(mockLoadBalancer).shutdown();
    verify(transportInfo2.transport, never()).shutdown(any(Status.class));

    shutdownSafely(helper, sub2);
    verify(transportInfo2.transport).shutdown(same(ManagedChannelImpl.SHUTDOWN_STATUS));

    // Cleanup
    transportInfo1.listener.transportShutdown(Status.UNAVAILABLE);
    transportInfo1.listener.transportTerminated();
    transportInfo2.listener.transportShutdown(Status.UNAVAILABLE);
    transportInfo2.listener.transportTerminated();
    timer.forwardTime(ManagedChannelImpl.SUBCHANNEL_SHUTDOWN_DELAY_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  public void subchannelStringableBeforeStart() {
    createChannel();
    Subchannel subchannel = createUnstartedSubchannel(helper, addressGroup, Attributes.EMPTY);
    assertThat(subchannel.toString()).isNotNull();
  }

  @Test
  public void subchannelLoggerCreatedBeforeSubchannelStarted() {
    createChannel();
    Subchannel subchannel = createUnstartedSubchannel(helper, addressGroup, Attributes.EMPTY);
    assertThat(subchannel.getChannelLogger()).isNotNull();
  }

  @Test
  public void subchannelsWhenChannelShutdownNow() {
    createChannel();
    Subchannel sub1 =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    Subchannel sub2 =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    requestConnectionSafely(helper, sub1);
    requestConnectionSafely(helper, sub2);

    assertThat(transports).hasSize(2);
    MockClientTransportInfo ti1 = transports.poll();
    MockClientTransportInfo ti2 = transports.poll();

    ti1.listener.transportReady();
    ti2.listener.transportReady();

    channel.shutdownNow();
    verify(ti1.transport).shutdownNow(any(Status.class));
    verify(ti2.transport).shutdownNow(any(Status.class));

    ti1.listener.transportShutdown(Status.UNAVAILABLE.withDescription("shutdown now"));
    ti2.listener.transportShutdown(Status.UNAVAILABLE.withDescription("shutdown now"));
    ti1.listener.transportTerminated();

    assertFalse(channel.isTerminated());
    ti2.listener.transportTerminated();
    assertTrue(channel.isTerminated());
  }

  @Test
  public void subchannelsNoConnectionShutdown() {
    createChannel();
    Subchannel sub1 =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    Subchannel sub2 =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);

    channel.shutdown();
    verify(mockLoadBalancer).shutdown();
    shutdownSafely(helper, sub1);
    assertFalse(channel.isTerminated());
    shutdownSafely(helper, sub2);
    assertTrue(channel.isTerminated());
    verify(mockTransportFactory, never())
        .newClientTransport(
            any(SocketAddress.class), any(ClientTransportOptions.class), any(ChannelLogger.class));
  }

  @Test
  public void subchannelsNoConnectionShutdownNow() {
    createChannel();
    createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    channel.shutdownNow();

    verify(mockLoadBalancer).shutdown();
    // Channel's shutdownNow() will call shutdownNow() on all subchannels and oobchannels.
    // Therefore, channel is terminated without relying on LoadBalancer to shutdown subchannels.
    assertTrue(channel.isTerminated());
    verify(mockTransportFactory, never())
        .newClientTransport(
            any(SocketAddress.class), any(ClientTransportOptions.class), any(ChannelLogger.class));
  }

  @Test
  public void oobchannels() {
    createChannel();

    ManagedChannel oob1 = helper.createOobChannel(
        Collections.singletonList(addressGroup), "oob1authority");
    ManagedChannel oob2 = helper.createOobChannel(
        Collections.singletonList(addressGroup), "oob2authority");
    verify(balancerRpcExecutorPool, times(2)).getObject();

    assertEquals("oob1authority", oob1.authority());
    assertEquals("oob2authority", oob2.authority());

    // OOB channels create connections lazily.  A new call will initiate the connection.
    Metadata headers = new Metadata();
    ClientCall<String, Integer> call = oob1.newCall(method, CallOptions.DEFAULT);
    call.start(mockCallListener, headers);
    verify(mockTransportFactory)
        .newClientTransport(
            eq(socketAddress),
            eq(new ClientTransportOptions().setAuthority("oob1authority").setUserAgent(USER_AGENT)),
            isA(ChannelLogger.class));
    MockClientTransportInfo transportInfo = transports.poll();
    assertNotNull(transportInfo);

    assertEquals(0, balancerRpcExecutor.numPendingTasks());
    transportInfo.listener.transportReady();
    assertEquals(1, balancerRpcExecutor.runDueTasks());
    verify(transportInfo.transport).newStream(
        same(method), same(headers), same(CallOptions.DEFAULT),
        ArgumentMatchers.<ClientStreamTracer[]>any());

    // The transport goes away
    transportInfo.listener.transportShutdown(Status.UNAVAILABLE);
    transportInfo.listener.transportTerminated();

    // A new call will trigger a new transport
    ClientCall<String, Integer> call2 = oob1.newCall(method, CallOptions.DEFAULT);
    call2.start(mockCallListener2, headers);
    ClientCall<String, Integer> call3 =
        oob1.newCall(method, CallOptions.DEFAULT.withWaitForReady());
    call3.start(mockCallListener3, headers);
    verify(mockTransportFactory, times(2)).newClientTransport(
        eq(socketAddress),
        eq(new ClientTransportOptions().setAuthority("oob1authority").setUserAgent(USER_AGENT)),
        isA(ChannelLogger.class));
    transportInfo = transports.poll();
    assertNotNull(transportInfo);

    // This transport fails
    Status transportError = Status.UNAVAILABLE.withDescription("Connection refused");
    assertEquals(0, balancerRpcExecutor.numPendingTasks());
    transportInfo.listener.transportShutdown(transportError);
    assertTrue(balancerRpcExecutor.runDueTasks() > 0);

    // Fail-fast RPC will fail, while wait-for-ready RPC will still be pending
    verify(mockCallListener2).onClose(same(transportError), any(Metadata.class));
    verify(mockCallListener3, never()).onClose(any(Status.class), any(Metadata.class));

    // Shutdown
    assertFalse(oob1.isShutdown());
    assertFalse(oob2.isShutdown());
    oob1.shutdown();
    oob2.shutdownNow();
    assertTrue(oob1.isShutdown());
    assertTrue(oob2.isShutdown());
    assertTrue(oob2.isTerminated());
    verify(balancerRpcExecutorPool).returnObject(balancerRpcExecutor.getScheduledExecutorService());

    // New RPCs will be rejected.
    assertEquals(0, balancerRpcExecutor.numPendingTasks());
    ClientCall<String, Integer> call4 = oob1.newCall(method, CallOptions.DEFAULT);
    ClientCall<String, Integer> call5 = oob2.newCall(method, CallOptions.DEFAULT);
    call4.start(mockCallListener4, headers);
    call5.start(mockCallListener5, headers);
    assertTrue(balancerRpcExecutor.runDueTasks() > 0);
    verify(mockCallListener4).onClose(statusCaptor.capture(), any(Metadata.class));
    Status status4 = statusCaptor.getValue();
    assertEquals(Status.Code.UNAVAILABLE, status4.getCode());
    verify(mockCallListener5).onClose(statusCaptor.capture(), any(Metadata.class));
    Status status5 = statusCaptor.getValue();
    assertEquals(Status.Code.UNAVAILABLE, status5.getCode());

    // The pending RPC will still be pending
    verify(mockCallListener3, never()).onClose(any(Status.class), any(Metadata.class));

    // This will shutdownNow() the delayed transport, terminating the pending RPC
    assertEquals(0, balancerRpcExecutor.numPendingTasks());
    oob1.shutdownNow();
    assertTrue(balancerRpcExecutor.runDueTasks() > 0);
    verify(mockCallListener3).onClose(any(Status.class), any(Metadata.class));

    // Shut down the channel, and it will not terminated because OOB channel has not.
    channel.shutdown();
    assertFalse(channel.isTerminated());
    // Delayed transport has already terminated.  Terminating the transport terminates the
    // subchannel, which in turn terimates the OOB channel, which terminates the channel.
    assertFalse(oob1.isTerminated());
    verify(balancerRpcExecutorPool).returnObject(balancerRpcExecutor.getScheduledExecutorService());
    transportInfo.listener.transportTerminated();
    assertTrue(oob1.isTerminated());
    assertTrue(channel.isTerminated());
    verify(balancerRpcExecutorPool, times(2))
        .returnObject(balancerRpcExecutor.getScheduledExecutorService());
  }

  @Test
  public void oobChannelHasNoChannelCallCredentials() {
    Metadata.Key<String> metadataKey =
        Metadata.Key.of("token", Metadata.ASCII_STRING_MARSHALLER);
    String channelCredValue = "channel-provided call cred";
    channelBuilder = new ManagedChannelImplBuilder(
        TARGET, InsecureChannelCredentials.create(),
        new FakeCallCredentials(metadataKey, channelCredValue),
        new UnsupportedClientTransportFactoryBuilder(), new FixedPortProvider(DEFAULT_PORT));
    channelBuilder.disableRetry();
    configureBuilder(channelBuilder);
    createChannel();

    // Verify that the normal channel has call creds, to validate configuration
    Subchannel subchannel =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    requestConnectionSafely(helper, subchannel);
    MockClientTransportInfo transportInfo = transports.poll();
    assertNotNull(transportInfo);
    transportInfo.listener.transportReady();
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class))).thenReturn(
        PickResult.withSubchannel(subchannel));
    updateBalancingStateSafely(helper, READY, mockPicker);

    String callCredValue = "per-RPC call cred";
    CallOptions callOptions = CallOptions.DEFAULT
        .withCallCredentials(new FakeCallCredentials(metadataKey, callCredValue));
    Metadata headers = new Metadata();
    ClientCall<String, Integer> call = channel.newCall(method, callOptions);
    call.start(mockCallListener, headers);

    verify(transportInfo.transport).newStream(
        same(method), same(headers), same(callOptions),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    assertThat(headers.getAll(metadataKey))
        .containsExactly(channelCredValue, callCredValue).inOrder();

    // Verify that the oob channel does not
    ManagedChannel oob = helper.createOobChannel(
        Collections.singletonList(addressGroup), "oobauthority");

    headers = new Metadata();
    call = oob.newCall(method, callOptions);
    call.start(mockCallListener2, headers);

    transportInfo = transports.poll();
    assertNotNull(transportInfo);
    transportInfo.listener.transportReady();
    balancerRpcExecutor.runDueTasks();

    verify(transportInfo.transport).newStream(
        same(method), same(headers), same(callOptions),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    assertThat(headers.getAll(metadataKey)).containsExactly(callCredValue);
    oob.shutdownNow();

    // Verify that resolving oob channel does not
    oob = helper.createResolvingOobChannelBuilder("oobauthority")
        .nameResolverFactory(
            new FakeNameResolverFactory.Builder(URI.create("fake:///oobauthority")).build())
        .defaultLoadBalancingPolicy(MOCK_POLICY_NAME)
        .idleTimeout(ManagedChannelImplBuilder.IDLE_MODE_MAX_TIMEOUT_DAYS, TimeUnit.DAYS)
        .disableRetry() // irrelevant to what we test, disable retry to make verification easy
        .build();
    oob.getState(true);
    ArgumentCaptor<Helper> helperCaptor = ArgumentCaptor.forClass(Helper.class);
    verify(mockLoadBalancerProvider, times(2)).newLoadBalancer(helperCaptor.capture());
    Helper oobHelper = helperCaptor.getValue();

    subchannel =
        createSubchannelSafely(oobHelper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    requestConnectionSafely(oobHelper, subchannel);
    transportInfo = transports.poll();
    assertNotNull(transportInfo);
    transportInfo.listener.transportReady();
    SubchannelPicker mockPicker2 = mock(SubchannelPicker.class);
    when(mockPicker2.pickSubchannel(any(PickSubchannelArgs.class))).thenReturn(
        PickResult.withSubchannel(subchannel));
    updateBalancingStateSafely(oobHelper, READY, mockPicker2);

    headers = new Metadata();
    call = oob.newCall(method, callOptions);
    call.start(mockCallListener2, headers);

    // CallOptions may contain StreamTracerFactory for census that is added by default.
    verify(transportInfo.transport).newStream(
        same(method), same(headers), any(CallOptions.class),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    assertThat(headers.getAll(metadataKey)).containsExactly(callCredValue);
    oob.shutdownNow();
  }

  @Test
  public void oobChannelWithOobChannelCredsHasChannelCallCredentials() {
    Metadata.Key<String> metadataKey =
        Metadata.Key.of("token", Metadata.ASCII_STRING_MARSHALLER);
    String channelCredValue = "channel-provided call cred";
    when(mockTransportFactory.swapChannelCredentials(any(CompositeChannelCredentials.class)))
        .thenAnswer(new Answer<SwapChannelCredentialsResult>() {
          @Override
          public SwapChannelCredentialsResult answer(InvocationOnMock invocation) {
            CompositeChannelCredentials c =
                invocation.getArgument(0, CompositeChannelCredentials.class);
            return new SwapChannelCredentialsResult(mockTransportFactory, c.getCallCredentials());
          }
        });
    channelBuilder = new ManagedChannelImplBuilder(
        TARGET, InsecureChannelCredentials.create(),
        new FakeCallCredentials(metadataKey, channelCredValue),
        new UnsupportedClientTransportFactoryBuilder(), new FixedPortProvider(DEFAULT_PORT));
    channelBuilder.disableRetry();
    configureBuilder(channelBuilder);
    createChannel();

    // Verify that the normal channel has call creds, to validate configuration
    Subchannel subchannel =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    requestConnectionSafely(helper, subchannel);
    MockClientTransportInfo transportInfo = transports.poll();
    transportInfo.listener.transportReady();
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class))).thenReturn(
        PickResult.withSubchannel(subchannel));
    updateBalancingStateSafely(helper, READY, mockPicker);

    String callCredValue = "per-RPC call cred";
    CallOptions callOptions = CallOptions.DEFAULT
        .withCallCredentials(new FakeCallCredentials(metadataKey, callCredValue));
    Metadata headers = new Metadata();
    ClientCall<String, Integer> call = channel.newCall(method, callOptions);
    call.start(mockCallListener, headers);

    verify(transportInfo.transport).newStream(
        same(method), same(headers), same(callOptions),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    assertThat(headers.getAll(metadataKey))
        .containsExactly(channelCredValue, callCredValue).inOrder();

    // Verify that resolving oob channel with oob channel creds provides call creds
    String oobChannelCredValue = "oob-channel-provided call cred";
    ChannelCredentials oobChannelCreds = CompositeChannelCredentials.create(
        InsecureChannelCredentials.create(),
        new FakeCallCredentials(metadataKey, oobChannelCredValue));
    ManagedChannel oob = helper.createResolvingOobChannelBuilder(
            "fake://oobauthority/", oobChannelCreds)
        .nameResolverFactory(
            new FakeNameResolverFactory.Builder(URI.create("fake://oobauthority/")).build())
        .defaultLoadBalancingPolicy(MOCK_POLICY_NAME)
        .idleTimeout(ManagedChannelImplBuilder.IDLE_MODE_MAX_TIMEOUT_DAYS, TimeUnit.DAYS)
        .disableRetry() // irrelevant to what we test, disable retry to make verification easy
        .build();
    oob.getState(true);
    ArgumentCaptor<Helper> helperCaptor = ArgumentCaptor.forClass(Helper.class);
    verify(mockLoadBalancerProvider, times(2)).newLoadBalancer(helperCaptor.capture());
    Helper oobHelper = helperCaptor.getValue();

    subchannel =
        createSubchannelSafely(oobHelper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    requestConnectionSafely(oobHelper, subchannel);
    transportInfo = transports.poll();
    transportInfo.listener.transportReady();
    SubchannelPicker mockPicker2 = mock(SubchannelPicker.class);
    when(mockPicker2.pickSubchannel(any(PickSubchannelArgs.class))).thenReturn(
        PickResult.withSubchannel(subchannel));
    updateBalancingStateSafely(oobHelper, READY, mockPicker2);

    headers = new Metadata();
    call = oob.newCall(method, callOptions);
    call.start(mockCallListener2, headers);

    // CallOptions may contain StreamTracerFactory for census that is added by default.
    verify(transportInfo.transport).newStream(
        same(method), same(headers), any(CallOptions.class),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    assertThat(headers.getAll(metadataKey))
        .containsExactly(oobChannelCredValue, callCredValue).inOrder();
    oob.shutdownNow();
  }

  @Test
  public void oobChannelsWhenChannelShutdownNow() {
    createChannel();
    ManagedChannel oob1 = helper.createOobChannel(
        Collections.singletonList(addressGroup), "oob1Authority");
    ManagedChannel oob2 = helper.createOobChannel(
        Collections.singletonList(addressGroup), "oob2Authority");

    oob1.newCall(method, CallOptions.DEFAULT).start(mockCallListener, new Metadata());
    oob2.newCall(method, CallOptions.DEFAULT).start(mockCallListener2, new Metadata());

    assertThat(transports).hasSize(2);
    MockClientTransportInfo ti1 = transports.poll();
    MockClientTransportInfo ti2 = transports.poll();

    ti1.listener.transportReady();
    ti2.listener.transportReady();

    channel.shutdownNow();
    verify(ti1.transport).shutdownNow(any(Status.class));
    verify(ti2.transport).shutdownNow(any(Status.class));

    ti1.listener.transportShutdown(Status.UNAVAILABLE.withDescription("shutdown now"));
    ti2.listener.transportShutdown(Status.UNAVAILABLE.withDescription("shutdown now"));
    ti1.listener.transportTerminated();

    assertFalse(channel.isTerminated());
    ti2.listener.transportTerminated();
    assertTrue(channel.isTerminated());
  }

  @Test
  public void oobChannelsNoConnectionShutdown() {
    createChannel();
    ManagedChannel oob1 = helper.createOobChannel(
        Collections.singletonList(addressGroup), "oob1Authority");
    ManagedChannel oob2 = helper.createOobChannel(
        Collections.singletonList(addressGroup), "oob2Authority");
    channel.shutdown();

    verify(mockLoadBalancer).shutdown();
    oob1.shutdown();
    assertTrue(oob1.isTerminated());
    assertFalse(channel.isTerminated());
    oob2.shutdown();
    assertTrue(oob2.isTerminated());
    assertTrue(channel.isTerminated());
    verify(mockTransportFactory, never())
        .newClientTransport(
            any(SocketAddress.class), any(ClientTransportOptions.class), any(ChannelLogger.class));
  }

  @Test
  public void oobChannelsNoConnectionShutdownNow() {
    createChannel();
    helper.createOobChannel(Collections.singletonList(addressGroup), "oob1Authority");
    helper.createOobChannel(Collections.singletonList(addressGroup), "oob2Authority");
    channel.shutdownNow();

    verify(mockLoadBalancer).shutdown();
    assertTrue(channel.isTerminated());
    // Channel's shutdownNow() will call shutdownNow() on all subchannels and oobchannels.
    // Therefore, channel is terminated without relying on LoadBalancer to shutdown oobchannels.
    verify(mockTransportFactory, never())
        .newClientTransport(
            any(SocketAddress.class), any(ClientTransportOptions.class), any(ChannelLogger.class));
  }

  @Test
  public void subchannelChannel_normalUsage() {
    createChannel();
    Subchannel subchannel =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    verify(balancerRpcExecutorPool, never()).getObject();

    Channel sChannel = subchannel.asChannel();
    verify(balancerRpcExecutorPool).getObject();

    Metadata headers = new Metadata();
    CallOptions callOptions = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);

    // Subchannel must be READY when creating the RPC.
    requestConnectionSafely(helper, subchannel);
    verify(mockTransportFactory)
        .newClientTransport(
            any(SocketAddress.class), any(ClientTransportOptions.class), any(ChannelLogger.class));
    MockClientTransportInfo transportInfo = transports.poll();
    ConnectionClientTransport mockTransport = transportInfo.transport;
    ManagedClientTransport.Listener transportListener = transportInfo.listener;
    transportListener.transportReady();

    ClientCall<String, Integer> call = sChannel.newCall(method, callOptions);
    call.start(mockCallListener, headers);
    verify(mockTransport).newStream(
        same(method), same(headers), callOptionsCaptor.capture(),
        ArgumentMatchers.<ClientStreamTracer[]>any());

    CallOptions capturedCallOption = callOptionsCaptor.getValue();
    assertThat(capturedCallOption.getDeadline()).isSameInstanceAs(callOptions.getDeadline());
    assertThat(capturedCallOption.getOption(GrpcUtil.CALL_OPTIONS_RPC_OWNED_BY_BALANCER)).isTrue();
  }

  @Test
  public void subchannelChannel_failWhenNotReady() {
    createChannel();
    Subchannel subchannel =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    Channel sChannel = subchannel.asChannel();
    Metadata headers = new Metadata();

    requestConnectionSafely(helper, subchannel);
    verify(mockTransportFactory)
        .newClientTransport(
            any(SocketAddress.class), any(ClientTransportOptions.class), any(ChannelLogger.class));
    MockClientTransportInfo transportInfo = transports.poll();
    ConnectionClientTransport mockTransport = transportInfo.transport;

    assertEquals(0, balancerRpcExecutor.numPendingTasks());

    // Subchannel is still CONNECTING, but not READY yet
    ClientCall<String, Integer> call = sChannel.newCall(method, CallOptions.DEFAULT);
    call.start(mockCallListener, headers);
    verify(mockTransport, never()).newStream(
        any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class),
        ArgumentMatchers.<ClientStreamTracer[]>any());

    verifyNoInteractions(mockCallListener);
    assertEquals(1, balancerRpcExecutor.runDueTasks());
    verify(mockCallListener).onClose(
        same(SubchannelChannel.NOT_READY_ERROR), any(Metadata.class));
  }

  @Test
  public void subchannelChannel_failWaitForReady() {
    createChannel();
    Subchannel subchannel =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    Channel sChannel = subchannel.asChannel();
    Metadata headers = new Metadata();

    // Subchannel must be READY when creating the RPC.
    requestConnectionSafely(helper, subchannel);
    verify(mockTransportFactory)
        .newClientTransport(
            any(SocketAddress.class), any(ClientTransportOptions.class), any(ChannelLogger.class));
    MockClientTransportInfo transportInfo = transports.poll();
    ConnectionClientTransport mockTransport = transportInfo.transport;
    ManagedClientTransport.Listener transportListener = transportInfo.listener;
    transportListener.transportReady();
    assertEquals(0, balancerRpcExecutor.numPendingTasks());

    // Wait-for-ready RPC is not allowed
    ClientCall<String, Integer> call =
        sChannel.newCall(method, CallOptions.DEFAULT.withWaitForReady());
    call.start(mockCallListener, headers);
    verify(mockTransport, never()).newStream(
        any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class),
        ArgumentMatchers.<ClientStreamTracer[]>any());

    verifyNoInteractions(mockCallListener);
    assertEquals(1, balancerRpcExecutor.runDueTasks());
    verify(mockCallListener).onClose(
        same(SubchannelChannel.WAIT_FOR_READY_ERROR), any(Metadata.class));
  }

  @Test
  public void lbHelper_getScheduledExecutorService() {
    createChannel();

    ScheduledExecutorService ses = helper.getScheduledExecutorService();
    Runnable task = mock(Runnable.class);
    helper.getSynchronizationContext().schedule(task, 110, TimeUnit.NANOSECONDS, ses);
    timer.forwardNanos(109);
    verify(task, never()).run();
    timer.forwardNanos(1);
    verify(task).run();

    try {
      ses.shutdown();
      fail("Should throw");
    } catch (UnsupportedOperationException e) {
      // expected
    }

    try {
      ses.shutdownNow();
      fail("Should throw");
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  @Test
  public void lbHelper_getNameResolverArgs() {
    createChannel();

    NameResolver.Args args = helper.getNameResolverArgs();
    assertThat(args.getDefaultPort()).isEqualTo(DEFAULT_PORT);
    assertThat(args.getProxyDetector()).isSameInstanceAs(GrpcUtil.DEFAULT_PROXY_DETECTOR);
    assertThat(args.getSynchronizationContext())
        .isSameInstanceAs(helper.getSynchronizationContext());
    assertThat(args.getServiceConfigParser()).isNotNull();
    assertThat(args.getMetricRecorder()).isNotNull();
  }

  @Test
  public void lbHelper_getNonDefaultNameResolverRegistry() {
    createChannel();

    assertThat(helper.getNameResolverRegistry())
        .isNotSameInstanceAs(NameResolverRegistry.getDefaultRegistry());
  }

  @Test
  public void refreshNameResolution_whenOobChannelConnectionFailed_notIdle() {
    subtestNameResolutionRefreshWhenConnectionFailed(false);
  }

  @Test
  public void notRefreshNameResolution_whenOobChannelConnectionFailed_idle() {
    subtestNameResolutionRefreshWhenConnectionFailed(true);
  }

  private void subtestNameResolutionRefreshWhenConnectionFailed(boolean isIdle) {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    createChannel();
    OobChannel oobChannel = (OobChannel) helper.createOobChannel(
        Collections.singletonList(addressGroup), "oobAuthority");
    oobChannel.getSubchannel().requestConnection();

    MockClientTransportInfo transportInfo = transports.poll();
    assertNotNull(transportInfo);

    FakeNameResolverFactory.FakeNameResolver resolver = nameResolverFactory.resolvers.remove(0);

    if (isIdle) {
      channel.enterIdle();
      // Entering idle mode will result in a new resolver
      resolver = nameResolverFactory.resolvers.remove(0);
    }

    assertEquals(0, nameResolverFactory.resolvers.size());

    int expectedRefreshCount = 0;

    // Transport closed when connecting
    assertEquals(expectedRefreshCount, resolver.refreshCalled);
    transportInfo.listener.transportShutdown(Status.UNAVAILABLE);
    // When channel enters idle, new resolver is created but not started.
    if (!isIdle) {
      expectedRefreshCount++;
    }
    assertEquals(expectedRefreshCount, resolver.refreshCalled);

    timer.forwardNanos(RECONNECT_BACKOFF_INTERVAL_NANOS);
    transportInfo = transports.poll();
    assertNotNull(transportInfo);

    transportInfo.listener.transportReady();

    // Transport closed when ready
    assertEquals(expectedRefreshCount, resolver.refreshCalled);
    transportInfo.listener.transportShutdown(Status.UNAVAILABLE);
    // When channel enters idle, new resolver is created but not started.
    if (!isIdle) {
      expectedRefreshCount++;
    }
    assertEquals(expectedRefreshCount, resolver.refreshCalled);
  }

  /**
   * Test that information such as the Call's context, MethodDescriptor, authority, executor are
   * propagated to newStream() and applyRequestMetadata().
   */
  @Test
  public void informationPropagatedToNewStreamAndCallCredentials() {
    createChannel();
    CallOptions callOptions = CallOptions.DEFAULT.withCallCredentials(creds);
    final Context.Key<String> testKey = Context.key("testing");
    Context ctx = Context.current().withValue(testKey, "testValue");
    final LinkedList<Context> credsApplyContexts = new LinkedList<>();
    final LinkedList<Context> newStreamContexts = new LinkedList<>();
    doAnswer(new Answer<Void>() {
        @Override
        public Void answer(InvocationOnMock in) throws Throwable {
          credsApplyContexts.add(Context.current());
          return null;
        }
      }).when(creds).applyRequestMetadata(
          any(RequestInfo.class), any(Executor.class), any(CallCredentials.MetadataApplier.class));

    // First call will be on delayed transport.  Only newCall() is run within the expected context,
    // so that we can verify that the context is explicitly attached before calling newStream() and
    // applyRequestMetadata(), which happens after we detach the context from the thread.
    Context origCtx = ctx.attach();
    assertEquals("testValue", testKey.get());
    ClientCall<String, Integer> call = channel.newCall(method, callOptions);
    ctx.detach(origCtx);
    assertNull(testKey.get());
    call.start(mockCallListener, new Metadata());

    // Simulate name resolution results
    EquivalentAddressGroup addressGroup = new EquivalentAddressGroup(socketAddress);
    Subchannel subchannel =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    requestConnectionSafely(helper, subchannel);
    verify(mockTransportFactory)
        .newClientTransport(
            same(socketAddress), eq(clientTransportOptions), any(ChannelLogger.class));
    MockClientTransportInfo transportInfo = transports.poll();
    final ConnectionClientTransport transport = transportInfo.transport;
    when(transport.getAttributes()).thenReturn(Attributes.EMPTY);
    doAnswer(new Answer<ClientStream>() {
        @Override
        public ClientStream answer(InvocationOnMock in) throws Throwable {
          newStreamContexts.add(Context.current());
          return mock(ClientStream.class);
        }
      }).when(transport).newStream(
          any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class),
          ArgumentMatchers.<ClientStreamTracer[]>any());

    verify(creds, never()).applyRequestMetadata(
        any(RequestInfo.class), any(Executor.class), any(CallCredentials.MetadataApplier.class));

    // applyRequestMetadata() is called after the transport becomes ready.
    transportInfo.listener.transportReady();
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withSubchannel(subchannel));
    updateBalancingStateSafely(helper, READY, mockPicker);
    executor.runDueTasks();
    ArgumentCaptor<RequestInfo> infoCaptor = ArgumentCaptor.forClass(RequestInfo.class);
    ArgumentCaptor<Executor> executorArgumentCaptor = ArgumentCaptor.forClass(Executor.class);
    ArgumentCaptor<CallCredentials.MetadataApplier> applierCaptor =
        ArgumentCaptor.forClass(CallCredentials.MetadataApplier.class);
    verify(creds).applyRequestMetadata(infoCaptor.capture(),
        executorArgumentCaptor.capture(), applierCaptor.capture());
    assertSame(offloadExecutor,
            ((ManagedChannelImpl.ExecutorHolder) executorArgumentCaptor.getValue()).getExecutor());
    assertEquals("testValue", testKey.get(credsApplyContexts.poll()));
    assertEquals(AUTHORITY, infoCaptor.getValue().getAuthority());
    assertEquals(SecurityLevel.NONE, infoCaptor.getValue().getSecurityLevel());
    verify(transport, never()).newStream(
        any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class),
        ArgumentMatchers.<ClientStreamTracer[]>any());

    // newStream() is called after apply() is called
    applierCaptor.getValue().apply(new Metadata());
    verify(transport).newStream(
        same(method), any(Metadata.class), same(callOptions),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    assertEquals("testValue", testKey.get(newStreamContexts.poll()));
    // The context should not live beyond the scope of newStream() and applyRequestMetadata()
    assertNull(testKey.get());


    // Second call will not be on delayed transport
    origCtx = ctx.attach();
    call = channel.newCall(method, callOptions);
    ctx.detach(origCtx);
    call.start(mockCallListener, new Metadata());

    verify(creds, times(2)).applyRequestMetadata(infoCaptor.capture(),
            executorArgumentCaptor.capture(), applierCaptor.capture());
    assertSame(offloadExecutor,
            ((ManagedChannelImpl.ExecutorHolder) executorArgumentCaptor.getValue()).getExecutor());
    assertEquals("testValue", testKey.get(credsApplyContexts.poll()));
    assertEquals(AUTHORITY, infoCaptor.getValue().getAuthority());
    assertEquals(SecurityLevel.NONE, infoCaptor.getValue().getSecurityLevel());
    // This is from the first call
    verify(transport).newStream(
        any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class),
        ArgumentMatchers.<ClientStreamTracer[]>any());

    // Still, newStream() is called after apply() is called
    applierCaptor.getValue().apply(new Metadata());
    verify(transport, times(2)).newStream(
        same(method), any(Metadata.class), same(callOptions),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    assertEquals("testValue", testKey.get(newStreamContexts.poll()));

    assertNull(testKey.get());
  }

  @Test
  public void pickerReturnsStreamTracer_noDelay() {
    ClientStream mockStream = mock(ClientStream.class);
    final ClientStreamTracer tracer1 = new ClientStreamTracer() {};
    final ClientStreamTracer tracer2 = new ClientStreamTracer() {};
    ClientStreamTracer.Factory factory1 = new ClientStreamTracer.Factory() {
      @Override
      public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
        return tracer1;
      }
    };
    ClientStreamTracer.Factory factory2 = new ClientStreamTracer.Factory() {
      @Override
      public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
        return tracer2;
      }
    };
    createChannel();
    Subchannel subchannel =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    requestConnectionSafely(helper, subchannel);
    MockClientTransportInfo transportInfo = transports.poll();
    transportInfo.listener.transportReady();
    ClientTransport mockTransport = transportInfo.transport;
    when(mockTransport.newStream(
            any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class),
            ArgumentMatchers.<ClientStreamTracer[]>any()))
        .thenReturn(mockStream);

    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class))).thenReturn(
        PickResult.withSubchannel(subchannel, factory2));
    updateBalancingStateSafely(helper, READY, mockPicker);

    CallOptions callOptions = CallOptions.DEFAULT.withStreamTracerFactory(factory1);
    ClientCall<String, Integer> call = channel.newCall(method, callOptions);
    call.start(mockCallListener, new Metadata());

    verify(mockPicker).pickSubchannel(any(PickSubchannelArgs.class));
    verify(mockTransport).newStream(
        same(method), any(Metadata.class), callOptionsCaptor.capture(),
        tracersCaptor.capture());
    assertThat(tracersCaptor.getValue()).isEqualTo(new ClientStreamTracer[] {tracer1, tracer2});
  }

  @Test
  public void pickerReturnsStreamTracer_delayed() {
    ClientStream mockStream = mock(ClientStream.class);
    final ClientStreamTracer tracer1 = new ClientStreamTracer() {};
    final ClientStreamTracer tracer2 = new ClientStreamTracer() {};
    ClientStreamTracer.Factory factory1 = new ClientStreamTracer.Factory() {
      @Override
      public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
        return tracer1;
      }
    };
    ClientStreamTracer.Factory factory2 = new ClientStreamTracer.Factory() {
      @Override
      public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
        return tracer2;
      }
    };
    createChannel();

    CallOptions callOptions = CallOptions.DEFAULT.withStreamTracerFactory(factory1);
    ClientCall<String, Integer> call = channel.newCall(method, callOptions);
    call.start(mockCallListener, new Metadata());

    Subchannel subchannel =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    requestConnectionSafely(helper, subchannel);
    MockClientTransportInfo transportInfo = transports.poll();
    transportInfo.listener.transportReady();
    ClientTransport mockTransport = transportInfo.transport;
    when(mockTransport.newStream(
            any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class),
            ArgumentMatchers.<ClientStreamTracer[]>any()))
        .thenReturn(mockStream);
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class))).thenReturn(
        PickResult.withSubchannel(subchannel, factory2));

    updateBalancingStateSafely(helper, READY, mockPicker);
    assertEquals(1, executor.runDueTasks());

    verify(mockPicker).pickSubchannel(any(PickSubchannelArgs.class));
    verify(mockTransport).newStream(
        same(method), any(Metadata.class), callOptionsCaptor.capture(),
        tracersCaptor.capture());
    assertThat(tracersCaptor.getValue()).isEqualTo(new ClientStreamTracer[] {tracer1, tracer2});
  }

  @Test
  public void getState_loadBalancerSupportsChannelState() {
    channelBuilder.nameResolverFactory(
        new FakeNameResolverFactory.Builder(expectedUri).setResolvedAtStart(false).build());
    createChannel();
    assertEquals(CONNECTING, channel.getState(false));

    updateBalancingStateSafely(helper, TRANSIENT_FAILURE, mockPicker);
    assertEquals(TRANSIENT_FAILURE, channel.getState(false));
  }

  @Test
  public void getState_withRequestConnect() {
    channelBuilder.nameResolverFactory(
        new FakeNameResolverFactory.Builder(expectedUri).setResolvedAtStart(false).build());
    requestConnection = false;
    createChannel();

    assertEquals(IDLE, channel.getState(false));
    verify(mockLoadBalancerProvider, never()).newLoadBalancer(any(Helper.class));

    // call getState() with requestConnection = true
    assertEquals(IDLE, channel.getState(true));
    ArgumentCaptor<Helper> helperCaptor = ArgumentCaptor.forClass(Helper.class);
    verify(mockLoadBalancerProvider).newLoadBalancer(helperCaptor.capture());
    helper = helperCaptor.getValue();

    updateBalancingStateSafely(helper, CONNECTING, mockPicker);
    assertEquals(CONNECTING, channel.getState(false));
    assertEquals(CONNECTING, channel.getState(true));
    verify(mockLoadBalancerProvider).newLoadBalancer(any(Helper.class));
  }

  @SuppressWarnings("deprecation")
  @Test
  public void getState_withRequestConnect_IdleWithLbRunning() {
    channelBuilder.nameResolverFactory(
        new FakeNameResolverFactory.Builder(expectedUri).setResolvedAtStart(false).build());
    createChannel();
    verify(mockLoadBalancerProvider).newLoadBalancer(any(Helper.class));

    updateBalancingStateSafely(helper, IDLE, mockPicker);

    assertEquals(IDLE, channel.getState(true));
    verify(mockLoadBalancerProvider).newLoadBalancer(any(Helper.class));
    verify(mockPicker).requestConnection();
    verify(mockLoadBalancer).requestConnection();
  }

  @Test
  public void notifyWhenStateChanged() {
    final AtomicBoolean stateChanged = new AtomicBoolean();
    Runnable onStateChanged = new Runnable() {
      @Override
      public void run() {
        stateChanged.set(true);
      }
    };

    channelBuilder.nameResolverFactory(
        new FakeNameResolverFactory.Builder(expectedUri).setResolvedAtStart(false).build());
    createChannel();
    assertEquals(CONNECTING, channel.getState(false));

    channel.notifyWhenStateChanged(CONNECTING, onStateChanged);
    executor.runDueTasks();
    assertFalse(stateChanged.get());

    // state change from CONNECTING to IDLE
    updateBalancingStateSafely(helper, IDLE, mockPicker);
    // onStateChanged callback should run
    executor.runDueTasks();
    assertTrue(stateChanged.get());

    // clear and test form IDLE
    stateChanged.set(false);
    channel.notifyWhenStateChanged(CONNECTING, onStateChanged);
    // onStateChanged callback should run immediately
    executor.runDueTasks();
    assertTrue(stateChanged.get());
  }

  @Test
  public void channelStateWhenChannelShutdown() {
    final AtomicBoolean stateChanged = new AtomicBoolean();
    Runnable onStateChanged = new Runnable() {
      @Override
      public void run() {
        stateChanged.set(true);
      }
    };

    channelBuilder.nameResolverFactory(
        new FakeNameResolverFactory.Builder(expectedUri).setResolvedAtStart(false).build());
    createChannel();
    assertEquals(CONNECTING, channel.getState(false));
    channel.notifyWhenStateChanged(CONNECTING, onStateChanged);
    executor.runDueTasks();
    assertFalse(stateChanged.get());

    channel.shutdown();
    assertEquals(SHUTDOWN, channel.getState(false));
    executor.runDueTasks();
    assertTrue(stateChanged.get());

    stateChanged.set(false);
    channel.notifyWhenStateChanged(SHUTDOWN, onStateChanged);
    updateBalancingStateSafely(helper, CONNECTING, mockPicker);

    assertEquals(SHUTDOWN, channel.getState(false));
    executor.runDueTasks();
    assertFalse(stateChanged.get());
  }

  @Test
  public void stateIsIdleOnIdleTimeout() {
    long idleTimeoutMillis = 2000L;
    channelBuilder.idleTimeout(idleTimeoutMillis, TimeUnit.MILLISECONDS);
    createChannel();
    assertEquals(CONNECTING, channel.getState(false));

    timer.forwardNanos(TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis));
    assertEquals(IDLE, channel.getState(false));
  }

  @Test
  public void panic_whenIdle() {
    subtestPanic(IDLE);
  }

  @Test
  public void panic_whenConnecting() {
    subtestPanic(CONNECTING);
  }

  @Test
  public void panic_whenTransientFailure() {
    subtestPanic(TRANSIENT_FAILURE);
  }

  @Test
  public void panic_whenReady() {
    subtestPanic(READY);
  }

  private void subtestPanic(ConnectivityState initialState) {
    assertNotEquals("We don't test panic mode if it's already SHUTDOWN", SHUTDOWN, initialState);
    long idleTimeoutMillis = 2000L;
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri).build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    channelBuilder.idleTimeout(idleTimeoutMillis, TimeUnit.MILLISECONDS);
    createChannel();

    verify(mockLoadBalancerProvider).newLoadBalancer(any(Helper.class));
    assertThat(nameResolverFactory.resolvers).hasSize(1);
    FakeNameResolverFactory.FakeNameResolver resolver = nameResolverFactory.resolvers.remove(0);

    final Throwable panicReason = new Exception("Simulated uncaught exception");
    if (initialState == IDLE) {
      timer.forwardNanos(TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis));
    } else {
      updateBalancingStateSafely(helper, initialState, mockPicker);
    }
    assertEquals(initialState, channel.getState(false));

    if (initialState == IDLE) {
      // IDLE mode will shutdown resolver and balancer
      verify(mockLoadBalancer).shutdown();
      assertTrue(resolver.shutdown);
      // A new resolver is created
      assertThat(nameResolverFactory.resolvers).hasSize(1);
      resolver = nameResolverFactory.resolvers.remove(0);
      assertFalse(resolver.shutdown);
    } else {
      verify(mockLoadBalancer, never()).shutdown();
      assertFalse(resolver.shutdown);
    }

    // Make channel panic!
    channel.syncContext.execute(
        new Runnable() {
          @Override
          public void run() {
            channel.panic(panicReason);
          }
        });

    // Calls buffered in delayedTransport will fail

    // Resolver and balancer are shutdown
    verify(mockLoadBalancer).shutdown();
    assertTrue(resolver.shutdown);

    // Channel will stay in TRANSIENT_FAILURE. getState(true) will not revive it.
    assertEquals(TRANSIENT_FAILURE, channel.getState(true));
    assertEquals(TRANSIENT_FAILURE, channel.getState(true));
    verifyPanicMode(panicReason);

    // Besides the resolver created initially, no new resolver or balancer are created.
    verify(mockLoadBalancerProvider).newLoadBalancer(any(Helper.class));
    assertThat(nameResolverFactory.resolvers).isEmpty();

    // A misbehaving balancer that calls updateBalancingState() after it's shut down will not be
    // able to revive it.
    updateBalancingStateSafely(helper, READY, mockPicker);
    verifyPanicMode(panicReason);

    // Cannot be revived by exitIdleMode()
    channel.syncContext.execute(new Runnable() {
        @Override
        public void run() {
          channel.exitIdleMode();
        }
      });
    verifyPanicMode(panicReason);

    // Can still shutdown normally
    channel.shutdown();
    assertTrue(channel.isShutdown());
    assertTrue(channel.isTerminated());
    assertEquals(SHUTDOWN, channel.getState(false));

    // We didn't stub mockPicker, because it should have never been called in this test.
    verifyNoInteractions(mockPicker);
  }

  @Test
  public void panic_bufferedCallsWillFail() {
    createChannel();

    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withNoResult());
    updateBalancingStateSafely(helper, CONNECTING, mockPicker);

    // Start RPCs that will be buffered in delayedTransport
    ClientCall<String, Integer> call =
        channel.newCall(method, CallOptions.DEFAULT.withoutWaitForReady());
    call.start(mockCallListener, new Metadata());

    ClientCall<String, Integer> call2 =
        channel.newCall(method, CallOptions.DEFAULT.withWaitForReady());
    call2.start(mockCallListener2, new Metadata());

    executor.runDueTasks();
    verifyNoInteractions(mockCallListener, mockCallListener2);

    // Enter panic
    final Throwable panicReason = new Exception("Simulated uncaught exception");
    channel.syncContext.execute(
        new Runnable() {
          @Override
          public void run() {
            channel.panic(panicReason);
          }
        });

    // Buffered RPCs fail immediately
    executor.runDueTasks();
    verifyCallListenerClosed(mockCallListener, Status.Code.INTERNAL, panicReason);
    verifyCallListenerClosed(mockCallListener2, Status.Code.INTERNAL, panicReason);
    panicExpected = true;
  }

  @Test
  public void panic_atStart() {
    final RuntimeException panicReason = new RuntimeException("Simulated NR exception");
    final NameResolver failingResolver = new NameResolver() {
      @Override public String getServiceAuthority() {
        return "fake-authority";
      }

      @Override public void start(Listener2 listener) {
        throw panicReason;
      }

      @Override public void shutdown() {}
    };
    channelBuilder.nameResolverFactory(new NameResolver.Factory() {
      @Override public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        return failingResolver;
      }

      @Override public String getDefaultScheme() {
        return "fake";
      }
    });
    createChannel();

    // RPCs fail immediately
    ClientCall<String, Integer> call =
        channel.newCall(method, CallOptions.DEFAULT.withoutWaitForReady());
    call.start(mockCallListener, new Metadata());
    executor.runDueTasks();
    verifyCallListenerClosed(mockCallListener, Status.Code.INTERNAL, panicReason);
    panicExpected = true;
  }

  private void verifyPanicMode(Throwable cause) {
    panicExpected = true;
    @SuppressWarnings("unchecked")
    ClientCall.Listener<Integer> mockListener =
        (ClientCall.Listener<Integer>) mock(ClientCall.Listener.class);
    assertEquals(TRANSIENT_FAILURE, channel.getState(false));
    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    call.start(mockListener, new Metadata());
    executor.runDueTasks();
    verifyCallListenerClosed(mockListener, Status.Code.INTERNAL, cause);

    // Channel is dead.  No more pending task to possibly revive it.
    assertEquals(0, timer.numPendingTasks());
    assertEquals(0, executor.numPendingTasks());
    assertEquals(0, balancerRpcExecutor.numPendingTasks());
  }

  private void verifyCallListenerClosed(
      ClientCall.Listener<Integer> listener, Status.Code code, Throwable cause) {
    ArgumentCaptor<Status> captor = ArgumentCaptor.forClass(Status.class);
    verify(listener).onClose(captor.capture(), any(Metadata.class));
    Status rpcStatus = captor.getValue();
    assertEquals(code, rpcStatus.getCode());
    assertSame(cause, rpcStatus.getCause());
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void idleTimeoutAndReconnect() {
    long idleTimeoutMillis = 2000L;
    channelBuilder.idleTimeout(idleTimeoutMillis, TimeUnit.MILLISECONDS);
    createChannel();

    timer.forwardNanos(TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis));
    assertEquals(IDLE, channel.getState(true /* request connection */));

    ArgumentCaptor<Helper> helperCaptor = ArgumentCaptor.forClass(Helper.class);
    // Two times of requesting connection will create loadBalancer twice.
    verify(mockLoadBalancerProvider, times(2)).newLoadBalancer(helperCaptor.capture());
    Helper helper2 = helperCaptor.getValue();

    // Updating on the old helper (whose balancer has been shutdown) does not change the channel
    // state.
    updateBalancingStateSafely(helper, IDLE, mockPicker);
    assertEquals(CONNECTING, channel.getState(false));

    updateBalancingStateSafely(helper2, IDLE, mockPicker);
    assertEquals(IDLE, channel.getState(false));
  }

  @Test
  public void idleMode_resetsDelayedTransportPicker() {
    ClientStream mockStream = mock(ClientStream.class);
    Status pickError = Status.UNAVAILABLE.withDescription("pick result error");
    long idleTimeoutMillis = 1000L;
    channelBuilder.idleTimeout(idleTimeoutMillis, TimeUnit.MILLISECONDS);
    channelBuilder.nameResolverFactory(
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .build());
    createChannel();
    assertEquals(CONNECTING, channel.getState(false));

    // This call will be buffered in delayedTransport
    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    call.start(mockCallListener, new Metadata());

    // Move channel into TRANSIENT_FAILURE, which will fail the pending call
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withError(pickError));
    updateBalancingStateSafely(helper, TRANSIENT_FAILURE, mockPicker);
    assertEquals(TRANSIENT_FAILURE, channel.getState(false));
    executor.runDueTasks();
    verify(mockCallListener).onClose(same(pickError), any(Metadata.class));

    // Move channel to idle
    timer.forwardNanos(TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis));
    assertEquals(IDLE, channel.getState(false));

    // This call should be buffered, but will move the channel out of idle
    ClientCall<String, Integer> call2 = channel.newCall(method, CallOptions.DEFAULT);
    call2.start(mockCallListener2, new Metadata());
    executor.runDueTasks();
    verifyNoMoreInteractions(mockCallListener2);

    // Get the helper created on exiting idle
    ArgumentCaptor<Helper> helperCaptor = ArgumentCaptor.forClass(Helper.class);
    verify(mockLoadBalancerProvider, times(2)).newLoadBalancer(helperCaptor.capture());
    Helper helper2 = helperCaptor.getValue();

    // Establish a connection
    Subchannel subchannel =
        createSubchannelSafely(helper2, addressGroup, Attributes.EMPTY, subchannelStateListener);
    requestConnectionSafely(helper, subchannel);
    MockClientTransportInfo transportInfo = transports.poll();
    ConnectionClientTransport mockTransport = transportInfo.transport;
    ManagedClientTransport.Listener transportListener = transportInfo.listener;
    when(mockTransport.newStream(
            same(method), any(Metadata.class), any(CallOptions.class),
            ArgumentMatchers.<ClientStreamTracer[]>any()))
        .thenReturn(mockStream);
    transportListener.transportReady();

    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withSubchannel(subchannel));
    updateBalancingStateSafely(helper2, READY, mockPicker);
    assertEquals(READY, channel.getState(false));
    executor.runDueTasks();

    // Verify the buffered call was drained
    verify(mockTransport).newStream(
        same(method), any(Metadata.class), any(CallOptions.class),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    verify(mockStream).start(any(ClientStreamListener.class));
  }

  @Test
  public void enterIdleEntersIdle() {
    createChannel();
    updateBalancingStateSafely(helper, READY, mockPicker);
    assertEquals(READY, channel.getState(false));

    channel.enterIdle();

    assertEquals(IDLE, channel.getState(false));
  }

  @Test
  public void enterIdleAfterIdleTimerIsNoOp() {
    long idleTimeoutMillis = 2000L;
    channelBuilder.idleTimeout(idleTimeoutMillis, TimeUnit.MILLISECONDS);
    createChannel();
    timer.forwardNanos(TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis));
    assertEquals(IDLE, channel.getState(false));

    channel.enterIdle();

    assertEquals(IDLE, channel.getState(false));
  }

  @Test
  public void enterIdle_exitsIdleIfDelayedStreamPending() {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    createChannel();

    // Start a call that will be buffered in delayedTransport
    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    call.start(mockCallListener, new Metadata());

    // enterIdle() will shut down the name resolver and lb policy used to get a pick for the delayed
    // call
    channel.enterIdle();
    assertEquals(CONNECTING, channel.getState(false));

    // enterIdle() will restart the delayed call by exiting idle. This creates a new helper.
    ArgumentCaptor<Helper> helperCaptor = ArgumentCaptor.forClass(Helper.class);
    verify(mockLoadBalancerProvider, times(2)).newLoadBalancer(helperCaptor.capture());
    Helper helper2 = helperCaptor.getValue();

    // Establish a connection
    Subchannel subchannel =
        createSubchannelSafely(helper2, addressGroup, Attributes.EMPTY, subchannelStateListener);
    requestConnectionSafely(helper, subchannel);
    ClientStream mockStream = mock(ClientStream.class);
    MockClientTransportInfo transportInfo = transports.poll();
    ConnectionClientTransport mockTransport = transportInfo.transport;
    ManagedClientTransport.Listener transportListener = transportInfo.listener;
    when(mockTransport.newStream(
            same(method), any(Metadata.class), any(CallOptions.class),
            ArgumentMatchers.<ClientStreamTracer[]>any()))
        .thenReturn(mockStream);
    transportListener.transportReady();
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withSubchannel(subchannel));
    updateBalancingStateSafely(helper2, READY, mockPicker);
    assertEquals(READY, channel.getState(false));

    // Verify the original call was drained
    executor.runDueTasks();
    verify(mockTransport).newStream(
        same(method), any(Metadata.class), any(CallOptions.class),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    verify(mockStream).start(any(ClientStreamListener.class));
  }

  @Test
  public void updateBalancingStateDoesUpdatePicker() {
    ClientStream mockStream = mock(ClientStream.class);
    createChannel();

    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    call.start(mockCallListener, new Metadata());

    // Make the transport available with subchannel2
    Subchannel subchannel1 =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    Subchannel subchannel2 =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    requestConnectionSafely(helper, subchannel2);

    MockClientTransportInfo transportInfo = transports.poll();
    ConnectionClientTransport mockTransport = transportInfo.transport;
    ManagedClientTransport.Listener transportListener = transportInfo.listener;
    when(mockTransport.newStream(
            same(method), any(Metadata.class), any(CallOptions.class),
            ArgumentMatchers.<ClientStreamTracer[]>any()))
        .thenReturn(mockStream);
    transportListener.transportReady();

    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withSubchannel(subchannel1));
    updateBalancingStateSafely(helper, READY, mockPicker);

    executor.runDueTasks();
    verify(mockTransport, never()).newStream(
        any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    verify(mockStream, never()).start(any(ClientStreamListener.class));


    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withSubchannel(subchannel2));
    updateBalancingStateSafely(helper, READY, mockPicker);

    executor.runDueTasks();
    verify(mockTransport).newStream(
        same(method), any(Metadata.class), any(CallOptions.class),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    verify(mockStream).start(any(ClientStreamListener.class));
  }

  @Test
  public void updateBalancingState_withWrappedSubchannel() {
    ClientStream mockStream = mock(ClientStream.class);
    createChannel();

    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    call.start(mockCallListener, new Metadata());

    final Subchannel subchannel1 =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    requestConnectionSafely(helper, subchannel1);

    MockClientTransportInfo transportInfo = transports.poll();
    ConnectionClientTransport mockTransport = transportInfo.transport;
    ManagedClientTransport.Listener transportListener = transportInfo.listener;
    when(mockTransport.newStream(
            same(method), any(Metadata.class), any(CallOptions.class),
            ArgumentMatchers.<ClientStreamTracer[]>any()))
        .thenReturn(mockStream);
    transportListener.transportReady();

    Subchannel wrappedSubchannel1 = new ForwardingSubchannel() {
        @Override
        protected Subchannel delegate() {
          return subchannel1;
        }
      };
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withSubchannel(wrappedSubchannel1));
    updateBalancingStateSafely(helper, READY, mockPicker);

    executor.runDueTasks();
    verify(mockTransport).newStream(
        same(method), any(Metadata.class), any(CallOptions.class),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    verify(mockStream).start(any(ClientStreamListener.class));
  }

  @Test
  public void updateBalancingStateWithShutdownShouldBeIgnored() {
    channelBuilder.nameResolverFactory(
        new FakeNameResolverFactory.Builder(expectedUri).setResolvedAtStart(false).build());
    createChannel();
    assertEquals(CONNECTING, channel.getState(false));

    Runnable onStateChanged = mock(Runnable.class);
    channel.notifyWhenStateChanged(CONNECTING, onStateChanged);

    updateBalancingStateSafely(helper, SHUTDOWN, mockPicker);

    assertEquals(CONNECTING, channel.getState(false));
    executor.runDueTasks();
    verify(onStateChanged, never()).run();
  }

  @Test
  public void balancerRefreshNameResolution() {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri).build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    createChannel();

    FakeNameResolverFactory.FakeNameResolver resolver = nameResolverFactory.resolvers.get(0);
    int initialRefreshCount = resolver.refreshCalled;
    refreshNameResolutionSafely(helper);
    assertEquals(initialRefreshCount + 1, resolver.refreshCalled);
  }

  @Test
  public void resetConnectBackoff_noOpWhenChannelShutdown() {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri).build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    createChannel();

    channel.shutdown();
    assertTrue(channel.isShutdown());
    channel.resetConnectBackoff();

    FakeNameResolverFactory.FakeNameResolver nameResolver = nameResolverFactory.resolvers.get(0);
    assertEquals(0, nameResolver.refreshCalled);
  }

  @Test
  public void resetConnectBackoff_noOpWhenNameResolverNotStarted() {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri).build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    requestConnection = false;
    createChannel();

    channel.resetConnectBackoff();

    FakeNameResolverFactory.FakeNameResolver nameResolver = nameResolverFactory.resolvers.get(0);
    assertEquals(0, nameResolver.refreshCalled);
  }

  @Test
  public void channelsAndSubchannels_instrumented_name() throws Exception {
    createChannel();
    assertEquals(TARGET, getStats(channel).target);

    Subchannel subchannel =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    assertEquals(Collections.singletonList(addressGroup).toString(),
        getStats((AbstractSubchannel) subchannel).target);
  }

  @Test
  public void channelTracing_channelCreationEvent() throws Exception {
    timer.forwardNanos(1234);
    channelBuilder.maxTraceEvents(10);
    createChannel();
    assertThat(getStats(channel).channelTrace.events).contains(new ChannelTrace.Event.Builder()
        .setDescription("Channel for 'fake://fake.example.com' created")
        .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
        .setTimestampNanos(timer.getTicker().read())
        .build());
  }

  @Test
  public void channelTracing_subchannelCreationEvents() throws Exception {
    channelBuilder.maxTraceEvents(10);
    createChannel();
    timer.forwardNanos(1234);
    AbstractSubchannel subchannel =
        (AbstractSubchannel) createSubchannelSafely(
            helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    assertThat(getStats(channel).channelTrace.events).contains(new ChannelTrace.Event.Builder()
        .setDescription("Child Subchannel started")
        .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
        .setTimestampNanos(timer.getTicker().read())
        .setSubchannelRef(subchannel.getInstrumentedInternalSubchannel())
        .build());
    assertThat(getStats(subchannel).channelTrace.events).contains(new ChannelTrace.Event.Builder()
        .setDescription("Subchannel for [[[test-addr]/{}]] created")
        .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
        .setTimestampNanos(timer.getTicker().read())
        .build());
  }

  @Test
  public void channelTracing_nameResolvingErrorEvent() throws Exception {
    timer.forwardNanos(1234);
    channelBuilder.maxTraceEvents(10);

    Status error = Status.UNAVAILABLE.withDescription("simulated error");
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri).setError(error).build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    createChannel(true);

    assertThat(getStats(channel).channelTrace.events).contains(new ChannelTrace.Event.Builder()
        .setDescription("Failed to resolve name: " + error)
        .setSeverity(ChannelTrace.Event.Severity.CT_WARNING)
        .setTimestampNanos(timer.getTicker().read())
        .build());
  }

  @Test
  public void channelTracing_nameResolvedEvent() throws Exception {
    timer.forwardNanos(1234);
    channelBuilder.maxTraceEvents(10);
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    createChannel();
    assertThat(getStats(channel).channelTrace.events).contains(new ChannelTrace.Event.Builder()
        .setDescription("Address resolved: "
            + Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
        .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
        .setTimestampNanos(timer.getTicker().read())
        .build());
  }

  @Test
  public void channelTracing_nameResolvedEvent_zeorAndNonzeroBackends() throws Exception {
    timer.forwardNanos(1234);
    channelBuilder.maxTraceEvents(10);
    List<EquivalentAddressGroup> servers = new ArrayList<>();
    servers.add(new EquivalentAddressGroup(socketAddress));
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri).setServers(servers).build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    createChannel();

    int prevSize = getStats(channel).channelTrace.events.size();
    ResolutionResult resolutionResult1 = ResolutionResult.newBuilder()
        .setAddresses(Collections.singletonList(
            new EquivalentAddressGroup(
                Arrays.asList(new SocketAddress() {}, new SocketAddress() {}))))
        .build();
    nameResolverFactory.resolvers.get(0).listener.onResult(resolutionResult1);
    assertThat(getStats(channel).channelTrace.events).hasSize(prevSize);

    prevSize = getStats(channel).channelTrace.events.size();
    nameResolverFactory.resolvers.get(0).listener.onError(Status.INTERNAL);
    assertThat(getStats(channel).channelTrace.events).hasSize(prevSize + 1);

    prevSize = getStats(channel).channelTrace.events.size();
    nameResolverFactory.resolvers.get(0).listener.onError(Status.INTERNAL);
    assertThat(getStats(channel).channelTrace.events).hasSize(prevSize);

    prevSize = getStats(channel).channelTrace.events.size();
    ResolutionResult resolutionResult2 = ResolutionResult.newBuilder()
        .setAddresses(Collections.singletonList(
            new EquivalentAddressGroup(
              Arrays.asList(new SocketAddress() {}, new SocketAddress() {}))))
        .build();
    nameResolverFactory.resolvers.get(0).listener.onResult(resolutionResult2);
    assertThat(getStats(channel).channelTrace.events).hasSize(prevSize + 1);
  }

  @Test
  public void channelTracing_nameResolvedEvent_zeorAndNonzeroBackends_usesListener2onResult2()
      throws Exception {
    timer.forwardNanos(1234);
    channelBuilder.maxTraceEvents(10);
    List<EquivalentAddressGroup> servers = new ArrayList<>();
    servers.add(new EquivalentAddressGroup(socketAddress));
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri).setServers(servers).build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    createChannel();

    int prevSize = getStats(channel).channelTrace.events.size();
    ResolutionResult resolutionResult1 = ResolutionResult.newBuilder()
        .setAddresses(Collections.singletonList(
            new EquivalentAddressGroup(
                Arrays.asList(new SocketAddress() {}, new SocketAddress() {}))))
        .build();

    channel.syncContext.execute(
        () -> nameResolverFactory.resolvers.get(0).listener.onResult2(resolutionResult1));
    assertThat(getStats(channel).channelTrace.events).hasSize(prevSize);

    prevSize = getStats(channel).channelTrace.events.size();
    channel.syncContext.execute(() ->
        nameResolverFactory.resolvers.get(0).listener.onResult2(
            ResolutionResult.newBuilder()
              .setAddressesOrError(
                  StatusOr.fromStatus(Status.INTERNAL)).build()));
    assertThat(getStats(channel).channelTrace.events).hasSize(prevSize + 1);

    prevSize = getStats(channel).channelTrace.events.size();
    channel.syncContext.execute(() ->
        nameResolverFactory.resolvers.get(0).listener.onResult2(
            ResolutionResult.newBuilder()
                .setAddressesOrError(
                    StatusOr.fromStatus(Status.INTERNAL)).build()));
    assertThat(getStats(channel).channelTrace.events).hasSize(prevSize);

    prevSize = getStats(channel).channelTrace.events.size();
    ResolutionResult resolutionResult2 = ResolutionResult.newBuilder()
        .setAddresses(Collections.singletonList(
            new EquivalentAddressGroup(
                Arrays.asList(new SocketAddress() {}, new SocketAddress() {}))))
        .build();
    channel.syncContext.execute(
        () -> nameResolverFactory.resolvers.get(0).listener.onResult2(resolutionResult2));
    assertThat(getStats(channel).channelTrace.events).hasSize(prevSize + 1);
  }

  @Test
  public void channelTracing_serviceConfigChange() throws Exception {
    timer.forwardNanos(1234);
    channelBuilder.maxTraceEvents(10);
    List<EquivalentAddressGroup> servers = new ArrayList<>();
    servers.add(new EquivalentAddressGroup(socketAddress));
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri).setServers(servers).build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    createChannel();

    int prevSize = getStats(channel).channelTrace.events.size();
    ManagedChannelServiceConfig mcsc1 = createManagedChannelServiceConfig(
        ImmutableMap.<String, Object>of(),
        new PolicySelection(
            mockLoadBalancerProvider, null));
    ResolutionResult resolutionResult1 = ResolutionResult.newBuilder()
        .setAddresses(Collections.singletonList(
            new EquivalentAddressGroup(
                Arrays.asList(new SocketAddress() {}, new SocketAddress() {}))))
        .setServiceConfig(ConfigOrError.fromConfig(mcsc1))
        .build();
    nameResolverFactory.resolvers.get(0).listener.onResult(resolutionResult1);
    assertThat(getStats(channel).channelTrace.events).hasSize(prevSize + 1);
    assertThat(getStats(channel).channelTrace.events.get(prevSize))
        .isEqualTo(new ChannelTrace.Event.Builder()
            .setDescription("Service config changed")
            .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
            .setTimestampNanos(timer.getTicker().read())
            .build());

    prevSize = getStats(channel).channelTrace.events.size();
    ResolutionResult resolutionResult2 = ResolutionResult.newBuilder().setAddresses(
        Collections.singletonList(
            new EquivalentAddressGroup(
                Arrays.asList(new SocketAddress() {}, new SocketAddress() {}))))
        .setServiceConfig(ConfigOrError.fromConfig(mcsc1))
        .build();
    nameResolverFactory.resolvers.get(0).listener.onResult(resolutionResult2);
    assertThat(getStats(channel).channelTrace.events).hasSize(prevSize);

    prevSize = getStats(channel).channelTrace.events.size();
    timer.forwardNanos(1234);
    ResolutionResult resolutionResult3 = ResolutionResult.newBuilder()
        .setAddresses(Collections.singletonList(
            new EquivalentAddressGroup(
                Arrays.asList(new SocketAddress() {}, new SocketAddress() {}))))
        .setServiceConfig(ConfigOrError.fromConfig(ManagedChannelServiceConfig.empty()))
        .build();
    nameResolverFactory.resolvers.get(0).listener.onResult(resolutionResult3);
    assertThat(getStats(channel).channelTrace.events).hasSize(prevSize + 1);
    assertThat(getStats(channel).channelTrace.events.get(prevSize))
        .isEqualTo(new ChannelTrace.Event.Builder()
            .setDescription("Service config changed")
            .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
            .setTimestampNanos(timer.getTicker().read())
            .build());
  }

  @Test
  public void channelTracing_serviceConfigChange_usesListener2OnResult2() throws Exception {
    timer.forwardNanos(1234);
    channelBuilder.maxTraceEvents(10);
    List<EquivalentAddressGroup> servers = new ArrayList<>();
    servers.add(new EquivalentAddressGroup(socketAddress));
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri).setServers(servers).build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    createChannel();

    int prevSize = getStats(channel).channelTrace.events.size();
    ManagedChannelServiceConfig mcsc1 = createManagedChannelServiceConfig(
        ImmutableMap.<String, Object>of(),
        new PolicySelection(
            mockLoadBalancerProvider, null));
    ResolutionResult resolutionResult1 = ResolutionResult.newBuilder()
        .setAddresses(Collections.singletonList(
            new EquivalentAddressGroup(
                Arrays.asList(new SocketAddress() {}, new SocketAddress() {}))))
        .setServiceConfig(ConfigOrError.fromConfig(mcsc1))
        .build();

    channel.syncContext.execute(() ->
        nameResolverFactory.resolvers.get(0).listener.onResult2(resolutionResult1));
    assertThat(getStats(channel).channelTrace.events).hasSize(prevSize + 1);
    assertThat(getStats(channel).channelTrace.events.get(prevSize))
        .isEqualTo(new ChannelTrace.Event.Builder()
            .setDescription("Service config changed")
            .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
            .setTimestampNanos(timer.getTicker().read())
            .build());

    prevSize = getStats(channel).channelTrace.events.size();
    ResolutionResult resolutionResult2 = ResolutionResult.newBuilder().setAddresses(
            Collections.singletonList(
                new EquivalentAddressGroup(
                    Arrays.asList(new SocketAddress() {}, new SocketAddress() {}))))
        .setServiceConfig(ConfigOrError.fromConfig(mcsc1))
        .build();
    channel.syncContext.execute(() ->
        nameResolverFactory.resolvers.get(0).listener.onResult(resolutionResult2));
    assertThat(getStats(channel).channelTrace.events).hasSize(prevSize);

    prevSize = getStats(channel).channelTrace.events.size();
    timer.forwardNanos(1234);
    ResolutionResult resolutionResult3 = ResolutionResult.newBuilder()
        .setAddresses(Collections.singletonList(
            new EquivalentAddressGroup(
                Arrays.asList(new SocketAddress() {}, new SocketAddress() {}))))
        .setServiceConfig(ConfigOrError.fromConfig(ManagedChannelServiceConfig.empty()))
        .build();
    channel.syncContext.execute(() ->
        nameResolverFactory.resolvers.get(0).listener.onResult(resolutionResult3));
    assertThat(getStats(channel).channelTrace.events).hasSize(prevSize + 1);
    assertThat(getStats(channel).channelTrace.events.get(prevSize))
        .isEqualTo(new ChannelTrace.Event.Builder()
            .setDescription("Service config changed")
            .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
            .setTimestampNanos(timer.getTicker().read())
            .build());
  }

  @Test
  public void channelTracing_stateChangeEvent() throws Exception {
    channelBuilder.maxTraceEvents(10);
    createChannel();
    timer.forwardNanos(1234);
    updateBalancingStateSafely(helper, CONNECTING, mockPicker);
    assertThat(getStats(channel).channelTrace.events).contains(new ChannelTrace.Event.Builder()
        .setDescription("Entering CONNECTING state with picker: mockPicker")
        .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
        .setTimestampNanos(timer.getTicker().read())
        .build());
  }

  @Test
  public void channelTracing_subchannelStateChangeEvent() throws Exception {
    channelBuilder.maxTraceEvents(10);
    createChannel();
    AbstractSubchannel subchannel =
        (AbstractSubchannel) createSubchannelSafely(
            helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    timer.forwardNanos(1234);
    ((TransportProvider) subchannel.getInternalSubchannel()).obtainActiveTransport();
    assertThat(getStats(subchannel).channelTrace.events).contains(new ChannelTrace.Event.Builder()
        .setDescription("CONNECTING as requested")
        .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
        .setTimestampNanos(timer.getTicker().read())
        .build());
  }

  @Test
  public void channelTracing_oobChannelStateChangeEvent() throws Exception {
    channelBuilder.maxTraceEvents(10);
    createChannel();
    OobChannel oobChannel = (OobChannel) helper.createOobChannel(
        Collections.singletonList(addressGroup), "authority");
    timer.forwardNanos(1234);
    oobChannel.handleSubchannelStateChange(
        ConnectivityStateInfo.forNonError(ConnectivityState.CONNECTING));
    assertThat(getStats(oobChannel).channelTrace.events).contains(new ChannelTrace.Event.Builder()
        .setDescription("Entering CONNECTING state")
        .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
        .setTimestampNanos(timer.getTicker().read())
        .build());
  }

  @Test
  public void channelTracing_oobChannelCreationEvents() throws Exception {
    channelBuilder.maxTraceEvents(10);
    createChannel();
    timer.forwardNanos(1234);
    OobChannel oobChannel = (OobChannel) helper.createOobChannel(
        Collections.singletonList(addressGroup), "authority");
    assertThat(getStats(channel).channelTrace.events).contains(new ChannelTrace.Event.Builder()
        .setDescription("Child OobChannel created")
        .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
        .setTimestampNanos(timer.getTicker().read())
        .setChannelRef(oobChannel)
        .build());
    assertThat(getStats(oobChannel).channelTrace.events).contains(new ChannelTrace.Event.Builder()
        .setDescription("OobChannel for [[[test-addr]/{}]] created")
        .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
        .setTimestampNanos(timer.getTicker().read())
        .build());
    assertThat(getStats(oobChannel.getInternalSubchannel()).channelTrace.events).contains(
        new ChannelTrace.Event.Builder()
            .setDescription("Subchannel for [[[test-addr]/{}]] created")
            .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
            .setTimestampNanos(timer.getTicker().read())
            .build());
  }

  @Test
  public void channelsAndSubchannels_instrumented_state() throws Exception {
    createChannel();

    ArgumentCaptor<Helper> helperCaptor = ArgumentCaptor.forClass(Helper.class);
    verify(mockLoadBalancerProvider).newLoadBalancer(helperCaptor.capture());
    helper = helperCaptor.getValue();

    assertEquals(CONNECTING, getStats(channel).state);

    AbstractSubchannel subchannel =
        (AbstractSubchannel) createSubchannelSafely(
            helper, addressGroup, Attributes.EMPTY, subchannelStateListener);

    assertEquals(IDLE, getStats(subchannel).state);
    requestConnectionSafely(helper, subchannel);
    assertEquals(CONNECTING, getStats(subchannel).state);

    MockClientTransportInfo transportInfo = transports.poll();

    assertEquals(CONNECTING, getStats(subchannel).state);
    transportInfo.listener.transportReady();
    assertEquals(READY, getStats(subchannel).state);

    assertEquals(CONNECTING, getStats(channel).state);
    updateBalancingStateSafely(helper, READY, mockPicker);
    assertEquals(READY, getStats(channel).state);

    channel.shutdownNow();
    assertEquals(SHUTDOWN, getStats(channel).state);
    assertEquals(SHUTDOWN, getStats(subchannel).state);
  }

  @Test
  public void channelStat_callStarted() throws Exception {
    createChannel();
    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    assertEquals(0, getStats(channel).callsStarted);
    call.start(mockCallListener, new Metadata());
    assertEquals(1, getStats(channel).callsStarted);
    assertEquals(executor.getTicker().read(), getStats(channel).lastCallStartedNanos);
  }

  @Test
  public void channelsAndSubChannels_instrumented_success() throws Exception {
    channelsAndSubchannels_instrumented0(true);
  }

  @Test
  public void channelsAndSubChannels_instrumented_fail() throws Exception {
    channelsAndSubchannels_instrumented0(false);
  }

  private void channelsAndSubchannels_instrumented0(boolean success) throws Exception {
    createChannel();

    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);

    // Channel stat bumped when ClientCall.start() called
    assertEquals(0, getStats(channel).callsStarted);
    call.start(mockCallListener, new Metadata());
    assertEquals(1, getStats(channel).callsStarted);

    ClientStream mockStream = mock(ClientStream.class);
    ClientStreamTracer.Factory factory = mock(ClientStreamTracer.Factory.class);
    AbstractSubchannel subchannel =
        (AbstractSubchannel) createSubchannelSafely(
            helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    requestConnectionSafely(helper, subchannel);
    MockClientTransportInfo transportInfo = transports.poll();
    transportInfo.listener.transportReady();
    ClientTransport mockTransport = transportInfo.transport;
    when(mockTransport.newStream(
            any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class),
            ArgumentMatchers.<ClientStreamTracer[]>any()))
        .thenReturn(mockStream);
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class))).thenReturn(
        PickResult.withSubchannel(subchannel, factory));

    // subchannel stat bumped when call gets assigned to it
    assertEquals(0, getStats(subchannel).callsStarted);
    updateBalancingStateSafely(helper, READY, mockPicker);
    assertEquals(1, executor.runDueTasks());
    verify(mockStream).start(streamListenerCaptor.capture());
    assertEquals(1, getStats(subchannel).callsStarted);

    ClientStreamListener streamListener = streamListenerCaptor.getValue();
    call.halfClose();

    // closing stream listener affects subchannel stats immediately
    assertEquals(0, getStats(subchannel).callsSucceeded);
    assertEquals(0, getStats(subchannel).callsFailed);
    streamListener.closed(
        success ? Status.OK : Status.UNKNOWN, PROCESSED, new Metadata());
    if (success) {
      assertEquals(1, getStats(subchannel).callsSucceeded);
      assertEquals(0, getStats(subchannel).callsFailed);
    } else {
      assertEquals(0, getStats(subchannel).callsSucceeded);
      assertEquals(1, getStats(subchannel).callsFailed);
    }

    // channel stats bumped when the ClientCall.Listener is notified
    assertEquals(0, getStats(channel).callsSucceeded);
    assertEquals(0, getStats(channel).callsFailed);
    executor.runDueTasks();
    if (success) {
      assertEquals(1, getStats(channel).callsSucceeded);
      assertEquals(0, getStats(channel).callsFailed);
    } else {
      assertEquals(0, getStats(channel).callsSucceeded);
      assertEquals(1, getStats(channel).callsFailed);
    }
  }

  @Test
  public void channelsAndSubchannels_oob_instrumented_success() throws Exception {
    channelsAndSubchannels_oob_instrumented0(true);
  }

  @Test
  public void channelsAndSubchannels_oob_instrumented_fail() throws Exception {
    channelsAndSubchannels_oob_instrumented0(false);
  }

  private void channelsAndSubchannels_oob_instrumented0(boolean success) throws Exception {
    // set up
    ClientStream mockStream = mock(ClientStream.class);
    createChannel();

    OobChannel oobChannel = (OobChannel) helper.createOobChannel(
        Collections.singletonList(addressGroup), "oobauthority");
    AbstractSubchannel oobSubchannel = (AbstractSubchannel) oobChannel.getSubchannel();
    FakeClock callExecutor = new FakeClock();
    CallOptions options =
        CallOptions.DEFAULT.withExecutor(callExecutor.getScheduledExecutorService());
    ClientCall<String, Integer> call = oobChannel.newCall(method, options);
    Metadata headers = new Metadata();

    // Channel stat bumped when ClientCall.start() called
    assertEquals(0, getStats(oobChannel).callsStarted);
    call.start(mockCallListener, headers);
    assertEquals(1, getStats(oobChannel).callsStarted);

    MockClientTransportInfo transportInfo = transports.poll();
    ConnectionClientTransport mockTransport = transportInfo.transport;
    ManagedClientTransport.Listener transportListener = transportInfo.listener;
    when(mockTransport.newStream(
            same(method), same(headers), any(CallOptions.class),
            ArgumentMatchers.<ClientStreamTracer[]>any()))
        .thenReturn(mockStream);

    // subchannel stat bumped when call gets assigned to it
    assertEquals(0, getStats(oobSubchannel).callsStarted);
    transportListener.transportReady();
    callExecutor.runDueTasks();
    verify(mockStream).start(streamListenerCaptor.capture());
    assertEquals(1, getStats(oobSubchannel).callsStarted);

    ClientStreamListener streamListener = streamListenerCaptor.getValue();
    call.halfClose();

    // closing stream listener affects subchannel stats immediately
    assertEquals(0, getStats(oobSubchannel).callsSucceeded);
    assertEquals(0, getStats(oobSubchannel).callsFailed);
    streamListener.closed(success ? Status.OK : Status.UNKNOWN, PROCESSED, new Metadata());
    if (success) {
      assertEquals(1, getStats(oobSubchannel).callsSucceeded);
      assertEquals(0, getStats(oobSubchannel).callsFailed);
    } else {
      assertEquals(0, getStats(oobSubchannel).callsSucceeded);
      assertEquals(1, getStats(oobSubchannel).callsFailed);
    }

    // channel stats bumped when the ClientCall.Listener is notified
    assertEquals(0, getStats(oobChannel).callsSucceeded);
    assertEquals(0, getStats(oobChannel).callsFailed);
    callExecutor.runDueTasks();
    if (success) {
      assertEquals(1, getStats(oobChannel).callsSucceeded);
      assertEquals(0, getStats(oobChannel).callsFailed);
    } else {
      assertEquals(0, getStats(oobChannel).callsSucceeded);
      assertEquals(1, getStats(oobChannel).callsFailed);
    }
    // oob channel is separate from the original channel
    assertEquals(0, getStats(channel).callsSucceeded);
    assertEquals(0, getStats(channel).callsFailed);
  }

  @Test
  public void channelsAndSubchannels_oob_instrumented_name() throws Exception {
    createChannel();

    String authority = "oobauthority";
    OobChannel oobChannel = (OobChannel) helper.createOobChannel(
        Collections.singletonList(addressGroup), authority);
    assertEquals(authority, getStats(oobChannel).target);
  }

  @Test
  public void channelsAndSubchannels_oob_instrumented_state() throws Exception {
    createChannel();

    OobChannel oobChannel = (OobChannel) helper.createOobChannel(
        Collections.singletonList(addressGroup), "oobauthority");
    assertEquals(IDLE, getStats(oobChannel).state);

    oobChannel.getSubchannel().requestConnection();
    assertEquals(CONNECTING, getStats(oobChannel).state);

    MockClientTransportInfo transportInfo = transports.poll();
    ManagedClientTransport.Listener transportListener = transportInfo.listener;

    transportListener.transportReady();
    assertEquals(READY, getStats(oobChannel).state);

    // oobchannel state is separate from the ManagedChannel
    assertEquals(CONNECTING, getStats(channel).state);
    channel.shutdownNow();
    assertEquals(SHUTDOWN, getStats(channel).state);
    assertEquals(SHUTDOWN, getStats(oobChannel).state);
  }

  @Test
  public void binaryLogInstalled() throws Exception {
    final SettableFuture<Boolean> intercepted = SettableFuture.create();
    channelBuilder.binlog = new BinaryLog() {
      @Override
      public void close() throws IOException {
        // noop
      }

      @Override
      public <ReqT, RespT> ServerMethodDefinition<?, ?> wrapMethodDefinition(
          ServerMethodDefinition<ReqT, RespT> oMethodDef) {
        return oMethodDef;
      }

      @Override
      public Channel wrapChannel(Channel channel) {
        return ClientInterceptors.intercept(channel,
            new ClientInterceptor() {
              @Override
              public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                  MethodDescriptor<ReqT, RespT> method,
                  CallOptions callOptions,
                  Channel next) {
                intercepted.set(true);
                return next.newCall(method, callOptions);
              }
            });
      }
    };

    createChannel();
    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    call.start(mockCallListener, new Metadata());
    assertTrue(intercepted.get());
  }

  @Test
  public void retryBackoffThenChannelShutdown_retryShouldStillHappen_newCallShouldFail() {
    Map<String, Object> retryPolicy = new HashMap<>();
    retryPolicy.put("maxAttempts", 3D);
    retryPolicy.put("initialBackoff", "10s");
    retryPolicy.put("maxBackoff", "30s");
    retryPolicy.put("backoffMultiplier", 2D);
    retryPolicy.put("retryableStatusCodes", Arrays.<Object>asList("UNAVAILABLE"));
    Map<String, Object> methodConfig = new HashMap<>();
    Map<String, Object> name = new HashMap<>();
    name.put("service", "service");
    methodConfig.put("name", Arrays.<Object>asList(name));
    methodConfig.put("retryPolicy", retryPolicy);
    Map<String, Object> rawServiceConfig = new HashMap<>();
    rawServiceConfig.put("methodConfig", Arrays.<Object>asList(methodConfig));

    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .build();
    ManagedChannelServiceConfig managedChannelServiceConfig =
        createManagedChannelServiceConfig(rawServiceConfig, null);
    nameResolverFactory.nextConfigOrError.set(
        ConfigOrError.fromConfig(managedChannelServiceConfig));

    channelBuilder.nameResolverFactory(nameResolverFactory);
    channelBuilder.executor(MoreExecutors.directExecutor());
    channelBuilder.enableRetry();
    RetriableStream.setRandom(
        // not random
        new Random() {
          @Override
          public double nextDouble() {
            return 1D; // fake random
          }
        });

    requestConnection = false;
    createChannel();

    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    call.start(mockCallListener, new Metadata());
    ArgumentCaptor<Helper> helperCaptor = ArgumentCaptor.forClass(Helper.class);
    verify(mockLoadBalancerProvider).newLoadBalancer(helperCaptor.capture());
    helper = helperCaptor.getValue();
    verify(mockLoadBalancer).acceptResolvedAddresses(resolvedAddressCaptor.capture());
    ResolvedAddresses resolvedAddresses = resolvedAddressCaptor.getValue();
    assertThat(resolvedAddresses.getAddresses()).isEqualTo(nameResolverFactory.servers);
    assertThat(resolvedAddresses.getAttributes()
        .get(RetryingNameResolver.RESOLUTION_RESULT_LISTENER_KEY)).isNotNull();

    // simulating request connection and then transport ready after resolved address
    Subchannel subchannel =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withSubchannel(subchannel));
    requestConnectionSafely(helper, subchannel);
    MockClientTransportInfo transportInfo = transports.poll();
    ConnectionClientTransport mockTransport = transportInfo.transport;
    ClientStream mockStream = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    when(mockTransport.newStream(
            same(method), any(Metadata.class), any(CallOptions.class),
            ArgumentMatchers.<ClientStreamTracer[]>any()))
        .thenReturn(mockStream).thenReturn(mockStream2);
    transportInfo.listener.transportReady();
    updateBalancingStateSafely(helper, READY, mockPicker);

    ArgumentCaptor<ClientStreamListener> streamListenerCaptor =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream).start(streamListenerCaptor.capture());
    assertThat(timer.getPendingTasks()).isEmpty();

    // trigger retry
    streamListenerCaptor.getValue().closed(
        Status.UNAVAILABLE, PROCESSED, new Metadata());

    // in backoff
    timer.forwardTime(5, TimeUnit.SECONDS);
    assertThat(timer.getPendingTasks()).hasSize(1);
    verify(mockStream2, never()).start(any(ClientStreamListener.class));

    // shutdown during backoff period
    channel.shutdown();

    assertThat(timer.getPendingTasks()).hasSize(1);
    verify(mockCallListener, never()).onClose(any(Status.class), any(Metadata.class));

    ClientCall<String, Integer> call2 = channel.newCall(method, CallOptions.DEFAULT);
    call2.start(mockCallListener2, new Metadata());

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(mockCallListener2).onClose(statusCaptor.capture(), any(Metadata.class));
    assertSame(Status.Code.UNAVAILABLE, statusCaptor.getValue().getCode());
    assertEquals("Channel shutdown invoked", statusCaptor.getValue().getDescription());

    // backoff ends
    timer.forwardTime(5, TimeUnit.SECONDS);
    assertThat(timer.getPendingTasks()).isEmpty();
    verify(mockStream2).start(streamListenerCaptor.capture());
    verify(mockLoadBalancer, never()).shutdown();
    assertFalse(
        "channel.isTerminated() is expected to be false but was true",
        channel.isTerminated());

    streamListenerCaptor.getValue().closed(
        Status.INTERNAL, PROCESSED, new Metadata());
    verify(mockLoadBalancer).shutdown();
    // simulating the shutdown of load balancer triggers the shutdown of subchannel
    shutdownSafely(helper, subchannel);
    transportInfo.listener.transportShutdown(Status.INTERNAL);
    transportInfo.listener.transportTerminated(); // simulating transport terminated
    assertTrue(
        "channel.isTerminated() is expected to be true but was false",
        channel.isTerminated());
  }

  @Test
  public void hedgingScheduledThenChannelShutdown_hedgeShouldStillHappen_newCallShouldFail() {
    Map<String, Object> hedgingPolicy = new HashMap<>();
    hedgingPolicy.put("maxAttempts", 3D);
    hedgingPolicy.put("hedgingDelay", "10s");
    hedgingPolicy.put("nonFatalStatusCodes", Arrays.<Object>asList("UNAVAILABLE"));
    Map<String, Object> methodConfig = new HashMap<>();
    Map<String, Object> name = new HashMap<>();
    name.put("service", "service");
    methodConfig.put("name", Arrays.<Object>asList(name));
    methodConfig.put("hedgingPolicy", hedgingPolicy);
    Map<String, Object> rawServiceConfig = new HashMap<>();
    rawServiceConfig.put("methodConfig", Arrays.<Object>asList(methodConfig));

    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
            .build();
    ManagedChannelServiceConfig managedChannelServiceConfig =
        createManagedChannelServiceConfig(rawServiceConfig, null);
    nameResolverFactory.nextConfigOrError.set(
        ConfigOrError.fromConfig(managedChannelServiceConfig));

    channelBuilder.nameResolverFactory(nameResolverFactory);
    channelBuilder.executor(MoreExecutors.directExecutor());
    channelBuilder.enableRetry();

    requestConnection = false;
    createChannel();

    ClientCall<String, Integer> call = channel.newCall(method, CallOptions.DEFAULT);
    call.start(mockCallListener, new Metadata());
    ArgumentCaptor<Helper> helperCaptor = ArgumentCaptor.forClass(Helper.class);
    verify(mockLoadBalancerProvider).newLoadBalancer(helperCaptor.capture());
    helper = helperCaptor.getValue();
    verify(mockLoadBalancer).acceptResolvedAddresses(resolvedAddressCaptor.capture());
    ResolvedAddresses resolvedAddresses = resolvedAddressCaptor.getValue();
    assertThat(resolvedAddresses.getAddresses()).isEqualTo(nameResolverFactory.servers);
    assertThat(resolvedAddresses.getAttributes()
        .get(RetryingNameResolver.RESOLUTION_RESULT_LISTENER_KEY)).isNotNull();

    // simulating request connection and then transport ready after resolved address
    Subchannel subchannel =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    when(mockPicker.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(PickResult.withSubchannel(subchannel));
    requestConnectionSafely(helper, subchannel);
    MockClientTransportInfo transportInfo = transports.poll();
    ConnectionClientTransport mockTransport = transportInfo.transport;
    ClientStream mockStream = mock(ClientStream.class);
    ClientStream mockStream2 = mock(ClientStream.class);
    when(mockTransport.newStream(
            same(method), any(Metadata.class), any(CallOptions.class),
            ArgumentMatchers.<ClientStreamTracer[]>any()))
        .thenReturn(mockStream).thenReturn(mockStream2);
    transportInfo.listener.transportReady();
    updateBalancingStateSafely(helper, READY, mockPicker);

    ArgumentCaptor<ClientStreamListener> streamListenerCaptor =
        ArgumentCaptor.forClass(ClientStreamListener.class);
    verify(mockStream).start(streamListenerCaptor.capture());

    // in hedging delay backoff
    timer.forwardTime(5, TimeUnit.SECONDS);
    assertThat(timer.numPendingTasks()).isEqualTo(1);
    // first hedge fails
    streamListenerCaptor.getValue().closed(
        Status.UNAVAILABLE, PROCESSED, new Metadata());
    verify(mockStream2, never()).start(any(ClientStreamListener.class));

    // shutdown during backoff period
    channel.shutdown();

    assertThat(timer.numPendingTasks()).isEqualTo(1);
    verify(mockCallListener, never()).onClose(any(Status.class), any(Metadata.class));

    ClientCall<String, Integer> call2 = channel.newCall(method, CallOptions.DEFAULT);
    call2.start(mockCallListener2, new Metadata());

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(mockCallListener2).onClose(statusCaptor.capture(), any(Metadata.class));
    assertSame(Status.Code.UNAVAILABLE, statusCaptor.getValue().getCode());
    assertEquals("Channel shutdown invoked", statusCaptor.getValue().getDescription());

    // backoff ends
    timer.forwardTime(5, TimeUnit.SECONDS);
    assertThat(timer.numPendingTasks()).isEqualTo(1);
    verify(mockStream2).start(streamListenerCaptor.capture());
    verify(mockLoadBalancer, never()).shutdown();
    assertFalse(
        "channel.isTerminated() is expected to be false but was true",
        channel.isTerminated());

    streamListenerCaptor.getValue().closed(
        Status.INTERNAL, PROCESSED, new Metadata());
    assertThat(timer.numPendingTasks()).isEqualTo(0);
    verify(mockLoadBalancer).shutdown();
    // simulating the shutdown of load balancer triggers the shutdown of subchannel
    shutdownSafely(helper, subchannel);
    // simulating transport shutdown & terminated
    transportInfo.listener.transportShutdown(Status.INTERNAL);
    transportInfo.listener.transportTerminated();
    assertTrue(
        "channel.isTerminated() is expected to be true but was false",
        channel.isTerminated());
  }

  @Test
  public void badServiceConfigIsRecoverable() throws Exception {
    final List<EquivalentAddressGroup> addresses =
        ImmutableList.of(new EquivalentAddressGroup(new SocketAddress() {}));
    final class FakeNameResolver extends NameResolver {
      Listener2 listener;

      @Override
      public String getServiceAuthority() {
        return "also fake";
      }

      @Override
      public void start(Listener2 listener) {
        this.listener = listener;
        listener.onResult(
            ResolutionResult.newBuilder()
                .setAddresses(addresses)
                .setServiceConfig(
                    ConfigOrError.fromError(
                        Status.INTERNAL.withDescription("kaboom is invalid")))
                .build());
      }

      @Override
      public void shutdown() {}
    }

    final class FakeNameResolverFactory2 extends NameResolver.Factory {
      FakeNameResolver resolver;

      @Nullable
      @Override
      public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        return (resolver = new FakeNameResolver());
      }

      @Override
      public String getDefaultScheme() {
        return "fake";
      }
    }

    FakeNameResolverFactory2 factory = new FakeNameResolverFactory2();

    ManagedChannelImplBuilder customBuilder = new ManagedChannelImplBuilder(TARGET,
        new ClientTransportFactoryBuilder() {
          @Override
          public ClientTransportFactory buildClientTransportFactory() {
            return mockTransportFactory;
          }
        },
        null);
    when(mockTransportFactory.getSupportedSocketAddressTypes()).thenReturn(Collections.singleton(
        InetSocketAddress.class));
    customBuilder.executorPool = executorPool;
    customBuilder.channelz = channelz;
    ManagedChannel mychannel = customBuilder.nameResolverFactory(factory).build();

    ClientCall<Void, Void> call1 =
        mychannel.newCall(TestMethodDescriptors.voidMethod(), CallOptions.DEFAULT);
    ListenableFuture<Void> future1 = ClientCalls.futureUnaryCall(call1, null);
    executor.runDueTasks();
    try {
      future1.get(1, TimeUnit.SECONDS);
      Assert.fail();
    } catch (ExecutionException e) {
      assertThat(Throwables.getStackTraceAsString(e.getCause())).contains("kaboom");
    }

    // ok the service config is bad, let's fix it.
    Map<String, Object> rawServiceConfig =
        parseConfig("{\"loadBalancingConfig\": [{\"round_robin\": {}}]}");
    Object fakeLbConfig = new Object();
    PolicySelection lbConfigs =
        new PolicySelection(
            mockLoadBalancerProvider, fakeLbConfig);
    mockLoadBalancerProvider.parseLoadBalancingPolicyConfig(rawServiceConfig);
    ManagedChannelServiceConfig managedChannelServiceConfig =
        createManagedChannelServiceConfig(rawServiceConfig, lbConfigs);
    factory.resolver.listener.onResult(
        ResolutionResult.newBuilder()
            .setAddresses(addresses)
            .setServiceConfig(ConfigOrError.fromConfig(managedChannelServiceConfig))
            .build());

    ClientCall<Void, Void> call2 = mychannel.newCall(
        TestMethodDescriptors.voidMethod(),
        CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS));
    ListenableFuture<Void> future2 = ClientCalls.futureUnaryCall(call2, null);

    timer.forwardTime(1234, TimeUnit.SECONDS);

    executor.runDueTasks();
    try {
      future2.get();
      Assert.fail();
    } catch (ExecutionException e) {
      assertThat(Throwables.getStackTraceAsString(e.getCause())).contains("deadline");
    }

    mychannel.shutdownNow();
  }

  @Test
  public void badServiceConfigIsRecoverable_usesListener2OnResult2() throws Exception {
    final List<EquivalentAddressGroup> addresses =
        ImmutableList.of(new EquivalentAddressGroup(new SocketAddress() {}));
    final class FakeNameResolver extends NameResolver {
      Listener2 listener;
      private final SynchronizationContext syncContext;

      FakeNameResolver(Args args) {
        this.syncContext = args.getSynchronizationContext();
      }

      @Override
      public String getServiceAuthority() {
        return "also fake";
      }

      @Override
      public void start(Listener2 listener) {
        this.listener = listener;
        syncContext.execute(() ->
            listener.onResult2(
                ResolutionResult.newBuilder()
                    .setAddresses(addresses)
                    .setServiceConfig(
                        ConfigOrError.fromError(
                            Status.INTERNAL.withDescription("kaboom is invalid")))
                    .build()));
      }

      @Override
      public void shutdown() {}
    }

    final class FakeNameResolverFactory2 extends NameResolver.Factory {
      FakeNameResolver resolver;
      ManagedChannelImpl managedChannel;
      SynchronizationContext syncContext;

      @Nullable
      @Override
      public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        syncContext = args.getSynchronizationContext();
        return (resolver = new FakeNameResolver(args));
      }

      @Override
      public String getDefaultScheme() {
        return "fake";
      }
    }

    FakeNameResolverFactory2 factory = new FakeNameResolverFactory2();

    ManagedChannelImplBuilder customBuilder = new ManagedChannelImplBuilder(TARGET,
        new ClientTransportFactoryBuilder() {
          @Override
          public ClientTransportFactory buildClientTransportFactory() {
            return mockTransportFactory;
          }
        },
        null);
    when(mockTransportFactory.getSupportedSocketAddressTypes()).thenReturn(Collections.singleton(
        InetSocketAddress.class));
    customBuilder.executorPool = executorPool;
    customBuilder.channelz = channelz;
    ManagedChannel mychannel = customBuilder.nameResolverFactory(factory).build();

    ClientCall<Void, Void> call1 =
        mychannel.newCall(TestMethodDescriptors.voidMethod(), CallOptions.DEFAULT);
    ListenableFuture<Void> future1 = ClientCalls.futureUnaryCall(call1, null);
    executor.runDueTasks();
    try {
      future1.get(1, TimeUnit.SECONDS);
      Assert.fail();
    } catch (ExecutionException e) {
      assertThat(Throwables.getStackTraceAsString(e.getCause())).contains("kaboom");
    }

    // ok the service config is bad, let's fix it.
    Map<String, Object> rawServiceConfig =
        parseConfig("{\"loadBalancingConfig\": [{\"round_robin\": {}}]}");
    Object fakeLbConfig = new Object();
    PolicySelection lbConfigs =
        new PolicySelection(
            mockLoadBalancerProvider, fakeLbConfig);
    mockLoadBalancerProvider.parseLoadBalancingPolicyConfig(rawServiceConfig);
    ManagedChannelServiceConfig managedChannelServiceConfig =
        createManagedChannelServiceConfig(rawServiceConfig, lbConfigs);
    factory.syncContext.execute(() ->
        factory.resolver.listener.onResult2(
            ResolutionResult.newBuilder()
                .setAddresses(addresses)
                .setServiceConfig(ConfigOrError.fromConfig(managedChannelServiceConfig))
                .build()));

    ClientCall<Void, Void> call2 = mychannel.newCall(
        TestMethodDescriptors.voidMethod(),
        CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS));
    ListenableFuture<Void> future2 = ClientCalls.futureUnaryCall(call2, null);

    timer.forwardTime(1234, TimeUnit.SECONDS);

    executor.runDueTasks();
    try {
      future2.get();
      Assert.fail();
    } catch (ExecutionException e) {
      assertThat(Throwables.getStackTraceAsString(e.getCause())).contains("deadline");
    }

    mychannel.shutdownNow();
  }

  @Test
  public void nameResolverArgsPropagation() {
    final AtomicReference<NameResolver.Args> capturedArgs = new AtomicReference<>();
    final NameResolver noopResolver = new NameResolver() {
        @Override
        public String getServiceAuthority() {
          return "fake-authority";
        }

        @Override
        public void start(Listener2 listener) {
        }

        @Override
        public void shutdown() {}
      };
    ProxyDetector neverProxy = new ProxyDetector() {
        @Override
        public ProxiedSocketAddress proxyFor(SocketAddress targetAddress) {
          return null;
        }
      };
    NameResolver.Factory factory = new NameResolver.Factory() {
        @Override
        public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
          capturedArgs.set(args);
          return noopResolver;
        }

        @Override
        public String getDefaultScheme() {
          return "fake";
        }
      };
    channelBuilder
        .nameResolverFactory(factory)
        .proxyDetector(neverProxy)
        .setNameResolverArg(TEST_RESOLVER_CUSTOM_ARG_KEY, "test-value");

    createChannel();

    NameResolver.Args args = capturedArgs.get();
    assertThat(args).isNotNull();
    assertThat(args.getDefaultPort()).isEqualTo(DEFAULT_PORT);
    assertThat(args.getProxyDetector()).isSameInstanceAs(neverProxy);
    assertThat(args.getArg(TEST_RESOLVER_CUSTOM_ARG_KEY)).isEqualTo("test-value");

    verify(offloadExecutor, never()).execute(any(Runnable.class));
    args.getOffloadExecutor()
        .execute(
            new Runnable() {
              @Override
              public void run() {}
            });
    verify(offloadExecutor, times(1)).execute(any(Runnable.class));
  }

  @Test
  public void getAuthorityAfterShutdown() throws Exception {
    createChannel();
    assertEquals(SERVICE_NAME, channel.authority());
    channel.shutdownNow().awaitTermination(1, TimeUnit.SECONDS);
    assertEquals(SERVICE_NAME, channel.authority());
  }

  @Test
  public void nameResolverHelper_emptyConfigSucceeds() {
    boolean retryEnabled = false;
    int maxRetryAttemptsLimit = 2;
    int maxHedgedAttemptsLimit = 3;
    AutoConfiguredLoadBalancerFactory autoConfiguredLoadBalancerFactory =
        new AutoConfiguredLoadBalancerFactory("pick_first");

    ScParser parser = new ScParser(
        retryEnabled,
        maxRetryAttemptsLimit,
        maxHedgedAttemptsLimit,
        autoConfiguredLoadBalancerFactory);

    ConfigOrError coe = parser.parseServiceConfig(ImmutableMap.<String, Object>of());

    assertThat(coe.getError()).isNull();
    ManagedChannelServiceConfig cfg = (ManagedChannelServiceConfig) coe.getConfig();
    assertThat(cfg.getMethodConfig(method)).isEqualTo(
        ManagedChannelServiceConfig.empty().getMethodConfig(method));
  }

  @Test
  public void nameResolverHelper_badConfigFails() {
    boolean retryEnabled = false;
    int maxRetryAttemptsLimit = 2;
    int maxHedgedAttemptsLimit = 3;
    AutoConfiguredLoadBalancerFactory autoConfiguredLoadBalancerFactory =
        new AutoConfiguredLoadBalancerFactory("pick_first");

    ScParser parser = new ScParser(
        retryEnabled,
        maxRetryAttemptsLimit,
        maxHedgedAttemptsLimit,
        autoConfiguredLoadBalancerFactory);

    ConfigOrError coe =
        parser.parseServiceConfig(ImmutableMap.<String, Object>of("methodConfig", "bogus"));

    assertThat(coe.getError()).isNotNull();
    assertThat(coe.getError().getCode()).isEqualTo(Code.UNKNOWN);
    assertThat(coe.getError().getDescription()).contains("failed to parse service config");
    assertThat(coe.getError().getCause()).isInstanceOf(ClassCastException.class);
  }

  @Test
  public void nameResolverHelper_noConfigChosen() {
    boolean retryEnabled = false;
    int maxRetryAttemptsLimit = 2;
    int maxHedgedAttemptsLimit = 3;
    AutoConfiguredLoadBalancerFactory autoConfiguredLoadBalancerFactory =
        new AutoConfiguredLoadBalancerFactory("pick_first");

    ScParser parser = new ScParser(
        retryEnabled,
        maxRetryAttemptsLimit,
        maxHedgedAttemptsLimit,
        autoConfiguredLoadBalancerFactory);

    ConfigOrError coe =
        parser.parseServiceConfig(ImmutableMap.of("loadBalancingConfig", ImmutableList.of()));

    assertThat(coe.getError()).isNull();
    ManagedChannelServiceConfig cfg = (ManagedChannelServiceConfig) coe.getConfig();
    assertThat(cfg.getLoadBalancingConfig()).isNull();
  }

  @Test
  public void disableServiceConfigLookUp_noDefaultConfig() throws Exception {
    LoadBalancerRegistry.getDefaultRegistry().register(mockLoadBalancerProvider);
    try {
      FakeNameResolverFactory nameResolverFactory =
          new FakeNameResolverFactory.Builder(expectedUri)
              .setServers(ImmutableList.of(addressGroup)).build();
      channelBuilder.nameResolverFactory(nameResolverFactory);
      channelBuilder.disableServiceConfigLookUp();

      Map<String, Object> rawServiceConfig =
          parseConfig("{\"methodConfig\":[{"
              + "\"name\":[{\"service\":\"SimpleService1\"}],"
              + "\"waitForReady\":true}]}");
      ManagedChannelServiceConfig managedChannelServiceConfig =
          createManagedChannelServiceConfig(rawServiceConfig, null);
      nameResolverFactory.nextConfigOrError.set(
          ConfigOrError.fromConfig(managedChannelServiceConfig));
      nameResolverFactory.nextAttributes.set(
          Attributes.newBuilder()
              .set(InternalConfigSelector.KEY, mock(InternalConfigSelector.class))
              .build());

      createChannel();

      ArgumentCaptor<ResolvedAddresses> resultCaptor =
          ArgumentCaptor.forClass(ResolvedAddresses.class);
      verify(mockLoadBalancer).acceptResolvedAddresses(resultCaptor.capture());
      assertThat(resultCaptor.getValue().getAddresses()).containsExactly(addressGroup);
      assertThat(resultCaptor.getValue().getAttributes().get(InternalConfigSelector.KEY)).isNull();
      verify(mockLoadBalancer, never()).handleNameResolutionError(any(Status.class));
    } finally {
      LoadBalancerRegistry.getDefaultRegistry().deregister(mockLoadBalancerProvider);
    }
  }

  @Test
  public void disableServiceConfigLookUp_withDefaultConfig() throws Exception {
    LoadBalancerRegistry.getDefaultRegistry().register(mockLoadBalancerProvider);
    try {
      FakeNameResolverFactory nameResolverFactory =
          new FakeNameResolverFactory.Builder(expectedUri)
              .setServers(ImmutableList.of(addressGroup)).build();
      channelBuilder.nameResolverFactory(nameResolverFactory);
      channelBuilder.disableServiceConfigLookUp();
      Map<String, Object> defaultServiceConfig =
          parseConfig("{\"methodConfig\":[{"
              + "\"name\":[{\"service\":\"SimpleService1\"}],"
              + "\"waitForReady\":true}]}");
      channelBuilder.defaultServiceConfig(defaultServiceConfig);

      Map<String, Object> rawServiceConfig = new HashMap<>();
      ManagedChannelServiceConfig managedChannelServiceConfig =
          createManagedChannelServiceConfig(rawServiceConfig, null);
      nameResolverFactory.nextConfigOrError.set(
          ConfigOrError.fromConfig(managedChannelServiceConfig));
      nameResolverFactory.nextAttributes.set(
          Attributes.newBuilder()
              .set(InternalConfigSelector.KEY, mock(InternalConfigSelector.class))
              .build());

      createChannel();

      ArgumentCaptor<ResolvedAddresses> resultCaptor =
          ArgumentCaptor.forClass(ResolvedAddresses.class);
      verify(mockLoadBalancer).acceptResolvedAddresses(resultCaptor.capture());
      assertThat(resultCaptor.getValue().getAddresses()).containsExactly(addressGroup);
      assertThat(resultCaptor.getValue().getAttributes().get(InternalConfigSelector.KEY)).isNull();
      verify(mockLoadBalancer, never()).handleNameResolutionError(any(Status.class));
    } finally {
      LoadBalancerRegistry.getDefaultRegistry().deregister(mockLoadBalancerProvider);
    }
  }

  @Test
  public void disableServiceConfigLookUp_withDefaultConfig_withRetryThrottle() throws Exception {
    LoadBalancerRegistry.getDefaultRegistry().register(mockLoadBalancerProvider);
    try {
      FakeNameResolverFactory nameResolverFactory =
              new FakeNameResolverFactory.Builder(expectedUri)
                      .setServers(ImmutableList.of(addressGroup)).build();
      channelBuilder.nameResolverFactory(nameResolverFactory);
      channelBuilder.disableServiceConfigLookUp();
      channelBuilder.enableRetry();
      Map<String, Object> defaultServiceConfig =
              parseConfig("{"
                      + "\"retryThrottling\":{\"maxTokens\": 1, \"tokenRatio\": 1},"
                      + "\"methodConfig\":[{"
                      + "\"name\":[{\"service\":\"SimpleService1\"}],"
                      + "\"waitForReady\":true"
                      + "}]}");
      channelBuilder.defaultServiceConfig(defaultServiceConfig);

      createChannel();

      ArgumentCaptor<ResolvedAddresses> resultCaptor =
              ArgumentCaptor.forClass(ResolvedAddresses.class);
      verify(mockLoadBalancer).acceptResolvedAddresses(resultCaptor.capture());
      assertThat(resultCaptor.getValue().getAddresses()).containsExactly(addressGroup);
      assertThat(resultCaptor.getValue().getAttributes().get(InternalConfigSelector.KEY)).isNull();
      verify(mockLoadBalancer, never()).handleNameResolutionError(any(Status.class));
      assertThat(channel.hasThrottle()).isTrue();

    } finally {
      LoadBalancerRegistry.getDefaultRegistry().deregister(mockLoadBalancerProvider);
    }
  }

  @Test
  public void enableServiceConfigLookUp_noDefaultConfig() throws Exception {
    LoadBalancerRegistry.getDefaultRegistry().register(mockLoadBalancerProvider);
    try {
      FakeNameResolverFactory nameResolverFactory =
          new FakeNameResolverFactory.Builder(expectedUri)
              .setServers(ImmutableList.of(addressGroup)).build();
      channelBuilder.nameResolverFactory(nameResolverFactory);

      Map<String, Object> rawServiceConfig =
          parseConfig("{\"methodConfig\":[{"
              + "\"name\":[{\"service\":\"SimpleService1\"}],"
              + "\"waitForReady\":true}]}");
      ManagedChannelServiceConfig managedChannelServiceConfig =
          createManagedChannelServiceConfig(rawServiceConfig, null);
      nameResolverFactory.nextConfigOrError.set(
          ConfigOrError.fromConfig(managedChannelServiceConfig));

      createChannel();
      ArgumentCaptor<ResolvedAddresses> resultCaptor =
          ArgumentCaptor.forClass(ResolvedAddresses.class);
      verify(mockLoadBalancer).acceptResolvedAddresses(resultCaptor.capture());
      assertThat(resultCaptor.getValue().getAddresses()).containsExactly(addressGroup);
      verify(mockLoadBalancer, never()).handleNameResolutionError(any(Status.class));

      // new config
      rawServiceConfig =
          parseConfig("{\"methodConfig\":[{"
              + "\"name\":[{\"service\":\"SimpleService1\"}],"
              + "\"waitForReady\":false}]}");
      managedChannelServiceConfig =
          createManagedChannelServiceConfig(rawServiceConfig, null);
      nameResolverFactory.nextConfigOrError.set(
          ConfigOrError.fromConfig(managedChannelServiceConfig));
      nameResolverFactory.allResolved();

      resultCaptor = ArgumentCaptor.forClass(ResolvedAddresses.class);
      verify(mockLoadBalancer, times(2)).acceptResolvedAddresses(resultCaptor.capture());
      assertThat(resultCaptor.getValue().getAddresses()).containsExactly(addressGroup);
      verify(mockLoadBalancer, never()).handleNameResolutionError(any(Status.class));
    } finally {
      LoadBalancerRegistry.getDefaultRegistry().deregister(mockLoadBalancerProvider);
    }
  }

  @Test
  public void enableServiceConfigLookUp_withDefaultConfig() throws Exception {
    LoadBalancerRegistry.getDefaultRegistry().register(mockLoadBalancerProvider);
    try {
      FakeNameResolverFactory nameResolverFactory =
          new FakeNameResolverFactory.Builder(expectedUri)
              .setServers(ImmutableList.of(addressGroup)).build();
      channelBuilder.nameResolverFactory(nameResolverFactory);
      Map<String, Object> defaultServiceConfig =
          parseConfig("{\"methodConfig\":[{"
              + "\"name\":[{\"service\":\"SimpleService1\"}],"
              + "\"waitForReady\":true}]}");
      channelBuilder.defaultServiceConfig(defaultServiceConfig);

      Map<String, Object> rawServiceConfig =
          parseConfig("{\"methodConfig\":[{"
              + "\"name\":[{\"service\":\"SimpleService2\"}],"
              + "\"waitForReady\":false}]}");
      ManagedChannelServiceConfig managedChannelServiceConfig =
          createManagedChannelServiceConfig(rawServiceConfig, null);
      nameResolverFactory.nextConfigOrError.set(
          ConfigOrError.fromConfig(managedChannelServiceConfig));

      createChannel();
      ArgumentCaptor<ResolvedAddresses> resultCaptor =
          ArgumentCaptor.forClass(ResolvedAddresses.class);
      verify(mockLoadBalancer).acceptResolvedAddresses(resultCaptor.capture());
      assertThat(resultCaptor.getValue().getAddresses()).containsExactly(addressGroup);
      verify(mockLoadBalancer, never()).handleNameResolutionError(any(Status.class));
    } finally {
      LoadBalancerRegistry.getDefaultRegistry().deregister(mockLoadBalancerProvider);
    }
  }

  @Test
  public void enableServiceConfigLookUp_resolverReturnsNoConfig_withDefaultConfig()
      throws Exception {
    LoadBalancerRegistry.getDefaultRegistry().register(mockLoadBalancerProvider);
    try {
      FakeNameResolverFactory nameResolverFactory =
          new FakeNameResolverFactory.Builder(expectedUri)
              .setServers(ImmutableList.of(addressGroup)).build();
      channelBuilder.nameResolverFactory(nameResolverFactory);
      Map<String, Object> defaultServiceConfig =
          parseConfig("{\"methodConfig\":[{"
              + "\"name\":[{\"service\":\"SimpleService1\"}],"
              + "\"waitForReady\":true}]}");
      channelBuilder.defaultServiceConfig(defaultServiceConfig);

      nameResolverFactory.nextConfigOrError.set(null);

      createChannel();
      ArgumentCaptor<ResolvedAddresses> resultCaptor =
          ArgumentCaptor.forClass(ResolvedAddresses.class);
      verify(mockLoadBalancer).acceptResolvedAddresses(resultCaptor.capture());
      assertThat(resultCaptor.getValue().getAddresses()).containsExactly(addressGroup);
      verify(mockLoadBalancer, never()).handleNameResolutionError(any(Status.class));
    } finally {
      LoadBalancerRegistry.getDefaultRegistry().deregister(mockLoadBalancerProvider);
    }
  }


  @Test
  public void enableServiceConfigLookUp_usingDefaultConfig_withRetryThrottling() throws Exception {
    LoadBalancerRegistry.getDefaultRegistry().register(mockLoadBalancerProvider);
    try {
      FakeNameResolverFactory nameResolverFactory =
              new FakeNameResolverFactory.Builder(expectedUri)
                      .setServers(ImmutableList.of(addressGroup)).build();
      channelBuilder.nameResolverFactory(nameResolverFactory);
      channelBuilder.enableRetry();
      Map<String, Object> defaultServiceConfig =
              parseConfig("{"
                      + "\"retryThrottling\":{\"maxTokens\": 1, \"tokenRatio\": 1},"
                      + "\"methodConfig\":[{"
                      + "\"name\":[{\"service\":\"SimpleService1\"}],"
                      + "\"waitForReady\":true"
                      + "}]}");
      channelBuilder.defaultServiceConfig(defaultServiceConfig);

      createChannel();

      ArgumentCaptor<ResolvedAddresses> resultCaptor =
              ArgumentCaptor.forClass(ResolvedAddresses.class);
      verify(mockLoadBalancer).acceptResolvedAddresses(resultCaptor.capture());
      assertThat(resultCaptor.getValue().getAddresses()).containsExactly(addressGroup);
      assertThat(resultCaptor.getValue().getAttributes().get(InternalConfigSelector.KEY)).isNull();
      verify(mockLoadBalancer, never()).handleNameResolutionError(any(Status.class));
      assertThat(channel.hasThrottle()).isTrue();
    } finally {
      LoadBalancerRegistry.getDefaultRegistry().deregister(mockLoadBalancerProvider);
    }
  }

  @Test
  public void enableServiceConfigLookUp_resolverReturnsNoConfig_noDefaultConfig() {
    LoadBalancerRegistry.getDefaultRegistry().register(mockLoadBalancerProvider);
    try {
      FakeNameResolverFactory nameResolverFactory =
          new FakeNameResolverFactory.Builder(expectedUri)
              .setServers(ImmutableList.of(addressGroup)).build();
      channelBuilder.nameResolverFactory(nameResolverFactory);

      Map<String, Object> rawServiceConfig = Collections.emptyMap();
      ManagedChannelServiceConfig managedChannelServiceConfig =
          createManagedChannelServiceConfig(rawServiceConfig, null);
      nameResolverFactory.nextConfigOrError.set(
          ConfigOrError.fromConfig(managedChannelServiceConfig));

      createChannel();
      ArgumentCaptor<ResolvedAddresses> resultCaptor =
          ArgumentCaptor.forClass(ResolvedAddresses.class);
      verify(mockLoadBalancer).acceptResolvedAddresses(resultCaptor.capture());
      assertThat(resultCaptor.getValue().getAddresses()).containsExactly(addressGroup);
      verify(mockLoadBalancer, never()).handleNameResolutionError(any(Status.class));
    } finally {
      LoadBalancerRegistry.getDefaultRegistry().deregister(mockLoadBalancerProvider);
    }
  }

  @Test
  public void useDefaultImmediatelyIfDisableLookUp() throws Exception {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(ImmutableList.of(addressGroup)).build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    channelBuilder.disableServiceConfigLookUp();
    Map<String, Object> defaultServiceConfig =
        parseConfig("{\"methodConfig\":[{"
            + "\"name\":[{\"service\":\"SimpleService1\"}],"
            + "\"waitForReady\":true}]}");
    channelBuilder.defaultServiceConfig(defaultServiceConfig);
    requestConnection = false;
    channelBuilder.maxTraceEvents(10);

    createChannel();

    int size = getStats(channel).channelTrace.events.size();
    assertThat(getStats(channel).channelTrace.events.get(size - 1))
        .isEqualTo(new ChannelTrace.Event.Builder()
            .setDescription("Service config look-up disabled, using default service config")
            .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
            .setTimestampNanos(timer.getTicker().read())
            .build());
  }

  @Test
  public void notUseDefaultImmediatelyIfEnableLookUp() throws Exception {
    FakeNameResolverFactory nameResolverFactory =
        new FakeNameResolverFactory.Builder(expectedUri)
            .setServers(ImmutableList.of(addressGroup)).build();
    channelBuilder.nameResolverFactory(nameResolverFactory);
    Map<String, Object> defaultServiceConfig =
        parseConfig("{\"methodConfig\":[{"
            + "\"name\":[{\"service\":\"SimpleService1\"}],"
            + "\"waitForReady\":true}]}");
    channelBuilder.defaultServiceConfig(defaultServiceConfig);
    requestConnection = false;
    channelBuilder.maxTraceEvents(10);

    createChannel();

    int size = getStats(channel).channelTrace.events.size();
    assertThat(getStats(channel).channelTrace.events.get(size - 1))
        .isNotEqualTo(new ChannelTrace.Event.Builder()
            .setDescription("timer.forwardNanos(1234);")
            .setSeverity(ChannelTrace.Event.Severity.CT_INFO)
            .setTimestampNanos(timer.getTicker().read())
            .build());
  }

  @Test
  public void healthCheckingConfigPropagated() throws Exception {
    LoadBalancerRegistry.getDefaultRegistry().register(mockLoadBalancerProvider);
    try {
      FakeNameResolverFactory nameResolverFactory =
          new FakeNameResolverFactory.Builder(expectedUri)
              .setServers(Collections.singletonList(new EquivalentAddressGroup(socketAddress)))
              .build();
      channelBuilder.nameResolverFactory(nameResolverFactory);

      Map<String, Object> rawServiceConfig =
          parseConfig("{\"healthCheckConfig\": {\"serviceName\": \"service1\"}}");
      ManagedChannelServiceConfig managedChannelServiceConfig =
          createManagedChannelServiceConfig(rawServiceConfig, null);
      nameResolverFactory.nextConfigOrError.set(
          ConfigOrError.fromConfig(managedChannelServiceConfig));

      createChannel();

      ArgumentCaptor<ResolvedAddresses> resultCaptor =
          ArgumentCaptor.forClass(ResolvedAddresses.class);
      verify(mockLoadBalancer).acceptResolvedAddresses(resultCaptor.capture());
      assertThat(resultCaptor.getValue().getAttributes()
          .get(LoadBalancer.ATTR_HEALTH_CHECKING_CONFIG))
          .containsExactly("serviceName", "service1");
    } finally {
      LoadBalancerRegistry.getDefaultRegistry().deregister(mockLoadBalancerProvider);
    }
  }

  @Test
  public void createResolvingOobChannel() throws Exception {
    String oobTarget = "fake://second.example.com";
    URI oobUri = new URI(oobTarget);
    channelBuilder
        .nameResolverFactory(new FakeNameResolverFactory.Builder(expectedUri, oobUri).build());
    createChannel();

    ManagedChannel resolvedOobChannel = null;
    try {
      resolvedOobChannel = helper.createResolvingOobChannel(oobTarget);

      assertWithMessage("resolving oob channel should have same authority")
          .that(resolvedOobChannel.authority())
          .isEqualTo(channel.authority());
    } finally {
      if (resolvedOobChannel != null) {
        resolvedOobChannel.shutdownNow();
      }
    }
  }

  @Test
  public void transportFilters() {

    final AtomicInteger readyCallbackCalled = new AtomicInteger(0);
    final AtomicInteger terminationCallbackCalled = new AtomicInteger(0);
    ClientTransportFilter transportFilter = new ClientTransportFilter() {
      @Override
      public Attributes transportReady(Attributes transportAttrs) {
        readyCallbackCalled.incrementAndGet();
        return transportAttrs;
      }

      @Override
      public void transportTerminated(Attributes transportAttrs) {
        terminationCallbackCalled.incrementAndGet();
      }
    };

    channelBuilder.addTransportFilter(transportFilter);
    assertEquals(0, readyCallbackCalled.get());

    createChannel();
    final Subchannel subchannel =
        createSubchannelSafely(helper, addressGroup, Attributes.EMPTY, subchannelStateListener);
    requestConnectionSafely(helper, subchannel);
    verify(mockTransportFactory)
        .newClientTransport(
        any(SocketAddress.class), any(ClientTransportOptions.class), any(ChannelLogger.class));
    MockClientTransportInfo transportInfo = transports.poll();
    ManagedClientTransport.Listener transportListener = transportInfo.listener;

    transportListener.filterTransport(Attributes.EMPTY);
    transportListener.transportReady();
    assertEquals(1, readyCallbackCalled.get());
    assertEquals(0, terminationCallbackCalled.get());

    transportListener.transportShutdown(Status.OK);

    transportListener.transportTerminated();
    assertEquals(1, terminationCallbackCalled.get());
  }

  @Test
  public void validAuthorityTarget_overrideAuthority() throws Exception {
    String overrideAuthority = "override.authority";
    String serviceAuthority = "fakeauthority";
    NameResolverProvider nameResolverProvider = new NameResolverProvider() {
      @Override protected boolean isAvailable() {
        return true;
      }

      @Override protected int priority() {
        return 5;
      }

      @Override public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        return new NameResolver() {
          @Override public String getServiceAuthority() {
            return serviceAuthority;
          }

          @Override public void start(final Listener2 listener) {}

          @Override public void shutdown() {}
        };
      }

      @Override public String getDefaultScheme() {
        return "defaultscheme";
      }
    };

    URI targetUri = new URI("defaultscheme", "", "/foo.googleapis.com:8080", null);
    NameResolver nameResolver = ManagedChannelImpl.getNameResolver(
        targetUri, null, nameResolverProvider, NAMERESOLVER_ARGS);
    assertThat(nameResolver.getServiceAuthority()).isEqualTo(serviceAuthority);

    nameResolver = ManagedChannelImpl.getNameResolver(
        targetUri, overrideAuthority, nameResolverProvider, NAMERESOLVER_ARGS);
    assertThat(nameResolver.getServiceAuthority()).isEqualTo(overrideAuthority);
  }

  @Test
  public void validTargetNoResolver_throws() {
    NameResolverProvider nameResolverProvider = new NameResolverProvider() {
      @Override
      protected boolean isAvailable() {
        return true;
      }

      @Override
      protected int priority() {
        return 5;
      }

      @Override
      public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        return null;
      }

      @Override
      public String getDefaultScheme() {
        return "defaultscheme";
      }
    };
    try {
      ManagedChannelImpl.getNameResolver(
          URI.create("defaultscheme:///foo.gogoleapis.com:8080"),
          null, nameResolverProvider, NAMERESOLVER_ARGS);
      fail("Should fail");
    } catch (IllegalArgumentException e) {
      // expected
    }
  }


  private static final class FakeBackoffPolicyProvider implements BackoffPolicy.Provider {
    @Override
    public BackoffPolicy get() {
      return new BackoffPolicy() {
        int multiplier = 1;

        @Override
        public long nextBackoffNanos() {
          return RECONNECT_BACKOFF_INTERVAL_NANOS * multiplier++;
        }
      };
    }
  }

  private static final class FakeNameResolverFactory extends NameResolverProvider {
    final List<URI> expectedUris;
    final List<EquivalentAddressGroup> servers;
    final boolean resolvedAtStart;
    final Status error;
    final ArrayList<FakeNameResolverFactory.FakeNameResolver> resolvers = new ArrayList<>();
    final AtomicReference<ConfigOrError> nextConfigOrError = new AtomicReference<>();
    final AtomicReference<Attributes> nextAttributes = new AtomicReference<>(Attributes.EMPTY);

    FakeNameResolverFactory(
        List<URI> expectedUris,
        List<EquivalentAddressGroup> servers,
        boolean resolvedAtStart,
        Status error) {
      this.expectedUris = expectedUris;
      this.servers = servers;
      this.resolvedAtStart = resolvedAtStart;
      this.error = error;
    }

    @Override
    public NameResolver newNameResolver(final URI targetUri, NameResolver.Args args) {
      if (!expectedUris.contains(targetUri)) {
        return null;
      }
      assertEquals(DEFAULT_PORT, args.getDefaultPort());
      FakeNameResolverFactory.FakeNameResolver resolver =
          new FakeNameResolverFactory.FakeNameResolver(targetUri, error, args);
      resolvers.add(resolver);
      return resolver;
    }

    @Override
    public String getDefaultScheme() {
      return "fake";
    }

    @Override
    public int priority() {
      return 9;
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    void allResolved() {
      for (FakeNameResolverFactory.FakeNameResolver resolver : resolvers) {
        resolver.resolved();
      }
    }

    final class FakeNameResolver extends NameResolver {
      final URI targetUri;
      final SynchronizationContext syncContext;
      Listener2 listener;
      boolean shutdown;
      int refreshCalled;
      Status error;

      FakeNameResolver(URI targetUri, Status error, Args args) {
        this.targetUri = targetUri;
        this.error = error;
        syncContext = args.getSynchronizationContext();
      }

      @Override public String getServiceAuthority() {
        return targetUri.getAuthority();
      }

      @Override public void start(Listener2 listener) {
        this.listener = listener;
        if (resolvedAtStart) {
          resolved();
        }
      }

      @Override public void refresh() {
        refreshCalled++;
        resolved();
      }

      void resolved() {
        if (error != null) {
          syncContext.execute(() ->
              listener.onResult2(
                  ResolutionResult.newBuilder()
                      .setAddressesOrError(StatusOr.fromStatus(error)).build()));
          return;
        }
        ResolutionResult.Builder builder =
            ResolutionResult.newBuilder()
                .setAddresses(servers)
                .setAttributes(nextAttributes.get());
        ConfigOrError configOrError = nextConfigOrError.get();
        if (configOrError != null) {
          builder.setServiceConfig(configOrError);
        }
        syncContext.execute(() -> listener.onResult(builder.build()));
      }

      @Override public void shutdown() {
        shutdown = true;
      }

      @Override
      public String toString() {
        return "FakeNameResolver";
      }
    }

    static final class Builder {
      List<URI> expectedUris;
      List<EquivalentAddressGroup> servers = ImmutableList.of();
      boolean resolvedAtStart = true;
      Status error = null;

      Builder(URI... expectedUris) {
        this.expectedUris = Collections.unmodifiableList(Arrays.asList(expectedUris));
      }

      FakeNameResolverFactory.Builder setServers(List<EquivalentAddressGroup> servers) {
        this.servers = servers;
        return this;
      }

      FakeNameResolverFactory.Builder setResolvedAtStart(boolean resolvedAtStart) {
        this.resolvedAtStart = resolvedAtStart;
        return this;
      }

      FakeNameResolverFactory.Builder setError(Status error) {
        this.error = error;
        return this;
      }

      FakeNameResolverFactory build() {
        return new FakeNameResolverFactory(expectedUris, servers, resolvedAtStart, error);
      }
    }
  }

  private static ChannelStats getStats(AbstractSubchannel subchannel) throws Exception {
    return subchannel.getInstrumentedInternalSubchannel().getStats().get();
  }

  private static ChannelStats getStats(
      InternalInstrumented<ChannelStats> instrumented) throws Exception {
    return instrumented.getStats().get();
  }

  // Helper methods to call methods from SynchronizationContext
  private static Subchannel createSubchannelSafely(
      final Helper helper, final EquivalentAddressGroup addressGroup, final Attributes attrs,
      final SubchannelStateListener stateListener) {
    final AtomicReference<Subchannel> resultCapture = new AtomicReference<>();
    helper.getSynchronizationContext().execute(
        new Runnable() {
          @Override
          public void run() {
            Subchannel s = helper.createSubchannel(CreateSubchannelArgs.newBuilder()
                .setAddresses(addressGroup)
                .setAttributes(attrs)
                .build());
            s.start(stateListener);
            resultCapture.set(s);
          }
        });
    return resultCapture.get();
  }

  private static Subchannel createUnstartedSubchannel(
      final Helper helper, final EquivalentAddressGroup addressGroup, final Attributes attrs) {
    final AtomicReference<Subchannel> resultCapture = new AtomicReference<>();
    helper.getSynchronizationContext().execute(
        new Runnable() {
          @Override
          public void run() {
            Subchannel s = helper.createSubchannel(CreateSubchannelArgs.newBuilder()
                .setAddresses(addressGroup)
                .setAttributes(attrs)
                .build());
            resultCapture.set(s);
          }
        });
    return resultCapture.get();
  }

  private static void requestConnectionSafely(Helper helper, final Subchannel subchannel) {
    helper.getSynchronizationContext().execute(
        new Runnable() {
          @Override
          public void run() {
            subchannel.requestConnection();
          }
        });
  }

  private static void updateBalancingStateSafely(
      final Helper helper, final ConnectivityState state, final SubchannelPicker picker) {
    helper.getSynchronizationContext().execute(
        new Runnable() {
          @Override
          public void run() {
            helper.updateBalancingState(state, picker);
          }
        });
  }

  private static void refreshNameResolutionSafely(final Helper helper) {
    helper.getSynchronizationContext().execute(
        new Runnable() {
          @Override
          public void run() {
            helper.refreshNameResolution();
          }
        });
  }

  private static void updateAddressesSafely(
      Helper helper, final Subchannel subchannel, final List<EquivalentAddressGroup> addrs) {
    helper.getSynchronizationContext().execute(
        new Runnable() {
          @Override
          public void run() {
            subchannel.updateAddresses(addrs);
          }
        });
  }

  private static void shutdownSafely(
      final Helper helper, final Subchannel subchannel) {
    helper.getSynchronizationContext().execute(
        new Runnable() {
          @Override
          public void run() {
            subchannel.shutdown();
          }
        });
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseConfig(String json) throws Exception {
    return (Map<String, Object>) JsonParser.parse(json);
  }

  private static ManagedChannelServiceConfig createManagedChannelServiceConfig(
      Map<String, Object> rawServiceConfig, PolicySelection policySelection) {
    // Provides dummy variable for retry related params (not used in this test class)
    return ManagedChannelServiceConfig
        .fromServiceConfig(rawServiceConfig, true, 3, 4, policySelection);
  }
}
