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
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.NameResolver.Factory;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.JsonParser;
import io.grpc.internal.PickSubchannelArgsImpl;
import io.grpc.lookup.v1.RouteLookupServiceGrpc;
import io.grpc.rls.CachingRlsLbClient.RlsPicker;
import io.grpc.rls.RlsProtoConverters.RouteLookupResponseConverter;
import io.grpc.rls.RlsProtoData.RouteLookupRequest;
import io.grpc.rls.RlsProtoData.RouteLookupResponse;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class RlsLoadBalancerTest {

  @Rule
  public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  private final DoNotUseDirectScheduledExecutorService fakeScheduledExecutorService =
      mock(DoNotUseDirectScheduledExecutorService.class, CALLS_REAL_METHODS);
  private final SynchronizationContext syncContext =
      new SynchronizationContext(new UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new RuntimeException(e);
        }
      });
  private final Helper helper =
      mock(Helper.class, AdditionalAnswers.delegatesTo(new FakeHelper()));
  private final FakeRlsServerImpl fakeRlsServerImpl = new FakeRlsServerImpl();
  private final Deque<FakeSubchannel> subchannels = new LinkedList<>();
  @Mock
  private Marshaller<Object> mockMarshaller;
  @Captor
  private ArgumentCaptor<SubchannelPicker> pickerCaptor;
  private MethodDescriptor<Object, Object> fakeSearchMethod;
  private MethodDescriptor<Object, Object> fakeRescueMethod;
  private LoadBalancer rlsLb;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    fakeSearchMethod =
        MethodDescriptor.newBuilder()
            .setFullMethodName("com.google/Search")
            .setRequestMarshaller(mockMarshaller)
            .setResponseMarshaller(mockMarshaller)
            .setType(MethodType.CLIENT_STREAMING)
            .build();
    fakeRescueMethod =
        MethodDescriptor.newBuilder()
            .setFullMethodName("com.google/Rescue")
            .setRequestMarshaller(mockMarshaller)
            .setResponseMarshaller(mockMarshaller)
            .setType(MethodType.UNARY)
            .build();
    fakeRlsServerImpl.setLookupTable(
        ImmutableMap.of(
            new RouteLookupRequest(
                "localhost:8972", "com.google/Search", "grpc", ImmutableMap.<String, String>of()),
            new RouteLookupResponse("wilderness", "where are you?"),
            new RouteLookupRequest(
                "localhost:8972", "com.google/Rescue", "grpc", ImmutableMap.<String, String>of()),
            new RouteLookupResponse("civilization", "you are safe")));

    RlsLoadBalancerProvider provider = new RlsLoadBalancerProvider();
    ConfigOrError parsedConfigOrError =
        provider.parseLoadBalancingPolicyConfig(getServiceConfig());

    assertThat(parsedConfigOrError.getConfig()).isNotNull();
    rlsLb = provider.newLoadBalancer(helper);
    rlsLb.handleResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.of(new EquivalentAddressGroup(mock(SocketAddress.class))))
        .setLoadBalancingPolicyConfig(parsedConfigOrError.getConfig())
        .build());
    verify(helper).createResolvingOobChannel(anyString());
  }

  @After
  public void tearDown() throws Exception {
    rlsLb.shutdown();
  }

  @Test
  @Ignore
  public void lb_working() throws Exception {
    final InOrder inOrder = inOrder(helper);

    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.CONNECTING), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue()).isInstanceOf(RlsPicker.class);
    final RlsPicker picker = (RlsPicker) pickerCaptor.getValue();
    final Metadata headers = new Metadata();

    blockingRunInSyncContext(
        new Runnable() {
          @Override
          public void run() {
            PickResult res =
                picker.pickSubchannel(
                    new PickSubchannelArgsImpl(fakeSearchMethod, headers, CallOptions.DEFAULT));
            // verify pending
            assertThat(res.getSubchannel()).isNull();
            assertThat(res.getStatus().isOk()).isTrue();
          }
        });

    inOrder.verify(helper).createSubchannel(any(CreateSubchannelArgs.class));
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.CONNECTING), pickerCaptor.capture());
    assertThat(subchannels).hasSize(1);
    inOrder.verifyNoMoreInteractions();

    final FakeSubchannel searchSubchannel = subchannels.getLast();
    searchSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());

    assertThat(pickerCaptor.getValue()).isInstanceOf(RlsPicker.class);
    final RlsPicker picker2 = (RlsPicker) pickerCaptor.getValue();
    assertThat(picker2).isEqualTo(picker);
    blockingRunInSyncContext(
        new Runnable() {
          @Override
          public void run() {
            PickResult res = picker2.pickSubchannel(
                new PickSubchannelArgsImpl(fakeSearchMethod, headers, CallOptions.DEFAULT));
            // verify success. Subchannel is wrapped, so checking attributes.
            assertThat(res.getSubchannel()).isNotNull();
            assertThat(res.getSubchannel().getAddresses())
                .isEqualTo(searchSubchannel.getAddresses());
            assertThat(res.getSubchannel().getAttributes())
                .isEqualTo(searchSubchannel.getAttributes());
            assertThat(res.getStatus().isOk()).isTrue();
          }
        });

    inOrder.verifyNoMoreInteractions();

    // rescue should be pending status
    blockingRunInSyncContext(
        new Runnable() {
          @Override
          public void run() {
            PickResult res =
                picker.pickSubchannel(
                    new PickSubchannelArgsImpl(fakeRescueMethod, headers, CallOptions.DEFAULT));
            assertThat(res.getSubchannel()).isNull();
            assertThat(res.getStatus().isOk()).isTrue();
          }
        });

    inOrder.verify(helper).createSubchannel(any(CreateSubchannelArgs.class));
    // other rls picker itself is ready due to first channel.
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());
    assertThat(subchannels).hasSize(2);
    inOrder.verifyNoMoreInteractions();

    // rescue subchannel is connecting
    searchSubchannel.updateState(ConnectivityStateInfo.forTransientFailure(Status.NOT_FOUND));

    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.CONNECTING), pickerCaptor.capture());
    final FakeSubchannel rescueSubchannel = subchannels.getLast();

    rescueSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());

    // search again, use pending fallback because searchSubchannel is in failure mode
    blockingRunInSyncContext(
        new Runnable() {
          @Override
          public void run() {
            PickResult res =
                picker.pickSubchannel(
                    new PickSubchannelArgsImpl(fakeSearchMethod, headers, CallOptions.DEFAULT));
            assertThat(res.getSubchannel()).isNull();
            assertThat(res.getStatus().isOk()).isTrue();
          }
        });

    inOrder.verify(helper).createSubchannel(any(CreateSubchannelArgs.class));
    assertThat(subchannels).hasSize(3);
    final FakeSubchannel fallbackSubchannel = subchannels.getLast();
    fallbackSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    inOrder.verify(helper, times(2))
        .updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());
    inOrder.verifyNoMoreInteractions();

    blockingRunInSyncContext(
        new Runnable() {
          @Override
          public void run() {
            PickResult res =
                picker.pickSubchannel(
                    new PickSubchannelArgsImpl(fakeSearchMethod, headers, CallOptions.DEFAULT));
            assertThat(res.getSubchannel().getAddresses())
                .isEqualTo(fallbackSubchannel.getAddresses());
            assertThat(res.getSubchannel().getAttributes())
                .isEqualTo(fallbackSubchannel.getAttributes());
            assertThat(res.getStatus().isOk()).isTrue();
          }
        });
    blockingRunInSyncContext(
        new Runnable() {
          @Override
          public void run() {
            PickResult res =
                picker.pickSubchannel(
                    new PickSubchannelArgsImpl(fakeRescueMethod, headers, CallOptions.DEFAULT));
            assertThat(res.getSubchannel().getAddresses())
                .isEqualTo(rescueSubchannel.getAddresses());
            assertThat(res.getSubchannel().getAttributes())
                .isEqualTo(rescueSubchannel.getAttributes());
            assertThat(res.getStatus().isOk()).isTrue();
          }
        });

    // all channels are failed
    rescueSubchannel.updateState(ConnectivityStateInfo.forTransientFailure(Status.NOT_FOUND));
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());
    fallbackSubchannel.updateState(ConnectivityStateInfo.forTransientFailure(Status.NOT_FOUND));
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void lb_nameResolutionFailed() throws Exception {
    final InOrder inOrder = inOrder(helper);

    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.CONNECTING), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue()).isInstanceOf(RlsPicker.class);
    final RlsPicker picker = (RlsPicker) pickerCaptor.getValue();
    final Metadata headers = new Metadata();

    blockingRunInSyncContext(
        new Runnable() {
          @Override
          public void run() {
            PickResult res =
                picker.pickSubchannel(
                    new PickSubchannelArgsImpl(fakeSearchMethod, headers, CallOptions.DEFAULT));
            // verify pending
            assertThat(res.getSubchannel()).isNull();
            assertThat(res.getStatus().isOk()).isTrue();
          }
        });

    inOrder.verify(helper).createSubchannel(any(CreateSubchannelArgs.class));
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.CONNECTING), pickerCaptor.capture());
    assertThat(subchannels).hasSize(1);
    inOrder.verifyNoMoreInteractions();

    final FakeSubchannel searchSubchannel = subchannels.getLast();
    searchSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    inOrder.verify(helper)
        .updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());

    assertThat(pickerCaptor.getValue()).isInstanceOf(RlsPicker.class);
    final RlsPicker picker2 = (RlsPicker) pickerCaptor.getValue();
    assertThat(picker2).isEqualTo(picker);
    blockingRunInSyncContext(
        new Runnable() {
          @Override
          public void run() {
            PickResult res = picker2.pickSubchannel(
                new PickSubchannelArgsImpl(fakeSearchMethod, headers, CallOptions.DEFAULT));
            // verify success. Subchannel is wrapped, so checking attributes.
            assertThat(res.getSubchannel()).isNotNull();
            assertThat(res.getSubchannel().getAddresses())
                .isEqualTo(searchSubchannel.getAddresses());
            assertThat(res.getSubchannel().getAttributes())
                .isEqualTo(searchSubchannel.getAttributes());
            assertThat(res.getStatus().isOk()).isTrue();
          }
        });

    inOrder.verifyNoMoreInteractions();

    rlsLb.handleNameResolutionError(Status.UNAVAILABLE);

    verify(helper)
        .updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    final SubchannelPicker failedPicker = pickerCaptor.getValue();
    blockingRunInSyncContext(
        new Runnable() {
          @Override
          public void run() {
            PickResult res = failedPicker.pickSubchannel(
                new PickSubchannelArgsImpl(fakeSearchMethod, headers, CallOptions.DEFAULT));
            assertThat(res.getSubchannel()).isNull();
            assertThat(res.getStatus().isOk()).isFalse();
          }
        });
  }

  private void blockingRunInSyncContext(final Runnable command) throws Exception {
    final SettableFuture<Exception> exceptionFuture = SettableFuture.create();
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        try {
          command.run();
          exceptionFuture.set(null);
        } catch (Exception e) {
          exceptionFuture.set(e);
        }
      }
    });
    Exception exception = exceptionFuture.get(5, TimeUnit.SECONDS);
    if (exception != null) {
      throw exception;
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> getServiceConfig() throws IOException {
    String serviceConfig = "{"
        + "  \"routeLookupConfig\": " + getRlsConfigJsonStr() + ", "
        + "  \"childPolicy\": [{\"pick_first\": {}}],"
        + "  \"childPolicyConfigTargetFieldName\": \"serviceName\""
        + "}";
    return (Map<String, Object>) JsonParser.parse(serviceConfig);
  }

  private static String getRlsConfigJsonStr() {
    return "{\n"
        + "  \"grpcKeyBuilders\": [\n"
        + "    {\n"
        + "      \"names\": [\n"
        + "        {\n"
        + "          \"service\": \"com.google\",\n"
        + "          \"method\": \"*\"\n"
        + "        }\n"
        + "      ],\n"
        + "      \"headers\": [\n"
        + "        {\n"
        + "          \"key\": \"permit\","
        + "          \"names\": [\"PermitId\"],\n"
        + "          \"optional\": true\n"
        + "        }\n"
        + "      ]\n"
        + "    }\n"
        + "  ],\n"
        + "  \"lookupService\": \"localhost:8972\",\n"
        + "  \"lookupServiceTimeout\": 2,\n"
        + "  \"maxAge\": 300,\n"
        + "  \"staleAge\": 240,\n"
        + "  \"validTargets\": [\"localhost:9001\", \"localhost:9002\"],"
        + "  \"cacheSizeBytes\": 1000,\n"
        + "  \"defaultTarget\": \"defaultTarget\",\n"
        + "  \"requestProcessingStrategy\": \"SYNC_LOOKUP_DEFAULT_TARGET_ON_ERROR\"\n"
        + "}";
  }

  private final class FakeHelper extends Helper {

    @Override
    public Subchannel createSubchannel(CreateSubchannelArgs args) {
      FakeSubchannel subchannel = new FakeSubchannel(args.getAddresses(), args.getAttributes());
      subchannels.add(subchannel);
      return subchannel;
    }

    @Override
    public ManagedChannel createResolvingOobChannel(String target) {
      try {
        grpcCleanupRule.register(
            InProcessServerBuilder.forName(target)
                .addService(fakeRlsServerImpl)
                .directExecutor()
                .build()
                .start());
      } catch (IOException e) {
        throw new RuntimeException("cannot create server: " + target, e);
      }
      return grpcCleanupRule.register(
          InProcessChannelBuilder.forName(target).directExecutor().build());
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

    @Override
    public ChannelLogger getChannelLogger() {
      return mock(ChannelLogger.class);
    }
  }

  private static final class FakeRlsServerImpl
      extends RouteLookupServiceGrpc.RouteLookupServiceImplBase {

    private static final Converter<io.grpc.lookup.v1.RouteLookupRequest, RouteLookupRequest>
        REQUEST_CONVERTER = new RlsProtoConverters.RouteLookupRequestConverter();
    private static final Converter<RouteLookupResponse, io.grpc.lookup.v1.RouteLookupResponse>
        RESPONSE_CONVERTER = new RouteLookupResponseConverter().reverse();

    private Map<RouteLookupRequest, RouteLookupResponse> lookupTable = ImmutableMap.of();

    private void setLookupTable(Map<RouteLookupRequest, RouteLookupResponse> lookupTable) {
      this.lookupTable = checkNotNull(lookupTable, "lookupTable");
    }

    @Override
    public void routeLookup(io.grpc.lookup.v1.RouteLookupRequest request,
        StreamObserver<io.grpc.lookup.v1.RouteLookupResponse> responseObserver) {
      RouteLookupResponse response =
          lookupTable.get(REQUEST_CONVERTER.convert(request));
      if (response == null) {
        responseObserver.onError(new RuntimeException("not found"));
      } else {
        responseObserver.onNext(RESPONSE_CONVERTER.convert(response));
        responseObserver.onCompleted();
      }
    }
  }

  private static final class FakeSubchannel extends Subchannel {
    private final Attributes attributes;
    private List<EquivalentAddressGroup> eags;
    private SubchannelStateListener listener;

    public FakeSubchannel(List<EquivalentAddressGroup> eags, Attributes attributes) {
      this.eags = Collections.unmodifiableList(eags);
      this.attributes = attributes;
    }

    @Override
    public List<EquivalentAddressGroup> getAllAddresses() {
      return eags;
    }

    @Override
    public Attributes getAttributes() {
      return attributes;
    }

    @Override
    public void start(SubchannelStateListener listener) {
      this.listener = checkNotNull(listener, "listener");
    }

    @Override
    public void updateAddresses(List<EquivalentAddressGroup> addrs) {
      this.eags = Collections.unmodifiableList(addrs);
    }

    @Override
    public void shutdown() {
    }

    @Override
    public void requestConnection() {
    }

    public void updateState(ConnectivityStateInfo newState) {
      listener.onSubchannelState(newState);
    }
  }
}
