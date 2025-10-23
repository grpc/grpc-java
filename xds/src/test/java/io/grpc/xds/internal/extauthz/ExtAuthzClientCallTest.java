/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds.internal.extauthz;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ChannelCredentials;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.grpc.xds.client.ConfiguredChannelCredentials;
import io.grpc.xds.internal.Matchers;
import io.grpc.xds.internal.extauthz.ExtAuthzTestHelper.CapturingListener;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfig;
import io.grpc.xds.internal.grpcservice.HeaderValue;
import io.grpc.xds.internal.headermutations.HeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderMutator;
import io.grpc.xds.internal.headermutations.HeaderValueOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link ExtAuthzClientCall}. */
@RunWith(JUnit4.class)
public class ExtAuthzClientCallTest {

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  private CheckRequestBuilder mockCheckRequestBuilder;
  @Mock
  private CheckResponseHandler mockResponseHandler;
  @Mock
  private HeaderMutator mockHeaderMutator;
  @Mock
  private AuthorizationGrpc.AuthorizationImplBase authzService;

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor();
  private Server server;
  private ManagedChannel channel;
  private volatile Metadata lastBackendHeaders;
  private ExtAuthzConfig config;
  private CallOptions callOptions;

  @Before
  public void setUp() throws Exception {
    // Single in-process server hosting both authz and backend services
    String serverName =
        InProcessServerBuilder.generateName();
    server = InProcessServerBuilder
        .forName(serverName)
        .directExecutor()
        .intercept(new ServerInterceptor() {
          @Override
          public <ReqT, RespT> io.grpc.ServerCall.Listener<ReqT> interceptCall(
              io.grpc.ServerCall<ReqT, RespT> call, Metadata headers,
              ServerCallHandler<ReqT, RespT> next) {
            lastBackendHeaders = headers;
            return next.startCall(call, headers);
          }
        })
        .addService(authzService)
        .addService(ServerServiceDefinition.builder(
            SimpleServiceGrpc.getUnaryRpcMethod().getServiceName())
            .addMethod(SimpleServiceGrpc.getUnaryRpcMethod(),
                (ServerCallHandler<SimpleRequest, SimpleResponse>) (call, headers) -> {
                  call.sendHeaders(new Metadata());
                  call.close(Status.OK, new Metadata());
                  return new io.grpc.ServerCall.Listener<SimpleRequest>() {};
                })
            .build())
        .build()
        .start();
    channel = InProcessChannelBuilder
        .forName(serverName)
        .directExecutor()
        .build();

    config = buildConfig();
    callOptions = CallOptions.DEFAULT.withExecutor(scheduler);
    when(mockCheckRequestBuilder.buildRequest(
        any(MethodDescriptor.class), any(Metadata.class),
        any(com.google.protobuf.Timestamp.class)))
        .thenReturn(CheckRequest.getDefaultInstance());
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.shutdownNow();
    }
    if (channel != null) {
      channel.shutdownNow();
    }
    scheduler.shutdownNow();
  }

  @Test
  public void cancel_cancelsAuthzContext() {
    ExtAuthzClientCall<SimpleRequest, SimpleResponse> call =
        createCall();

    Context.CancellableContext authzContext =
        call.getAuthzContextForTest();
    assertThat(authzContext.isCancelled()).isFalse();

    call.cancel("user cancelled", null);

    assertThat(authzContext.isCancelled()).isTrue();
  }

  @Test
  public void cancel_whileCheckInFlight_cancelsContext() {
    CountDownLatch checkCalled = new CountDownLatch(1);
    doAnswer(invocation -> {
      checkCalled.countDown();
      // Don't respond to simulate in-flight check
      return null;
    }).when(authzService)
        .check(any(CheckRequest.class),
            ArgumentMatchers.any());

    ExtAuthzClientCall<SimpleRequest, SimpleResponse> call =
        createCall();

    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    call.start(listener, new Metadata());

    try {
      assertThat(
          checkCalled.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    Context.CancellableContext authzContext =
        call.getAuthzContextForTest();
    assertThat(authzContext.isCancelled()).isFalse();

    call.cancel("client cancelled", null);

    assertThat(authzContext.isCancelled()).isTrue();
  }

  @Test
  public void start_runsCheckUnderAuthzContext() {
    Context[] capturedContext = new Context[1];
    doAnswer(invocation -> {
      capturedContext[0] = Context.current();
      StreamObserver<CheckResponse> observer =
          invocation.getArgument(1);
      observer.onNext(CheckResponse.getDefaultInstance());
      observer.onCompleted();
      return null;
    }).when(authzService)
        .check(any(CheckRequest.class),
            ArgumentMatchers.any());

    HeaderMutations emptyMutations = HeaderMutations.create(
        ImmutableList.of(), ImmutableList.of());
    AuthzResponse authzResponse =
        AuthzResponse.allow(emptyMutations)
            .setResponseHeaderMutations(emptyMutations)
            .build();
    when(mockResponseHandler.handleResponse(
        any(CheckResponse.class))).thenReturn(authzResponse);

    ExtAuthzClientCall<SimpleRequest, SimpleResponse> call =
        createCall();

    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    call.start(listener, new Metadata());

    // The check was called under the authzContext
    assertThat(capturedContext[0]).isNotNull();
  }

  @Test
  public void start_triggersCheckRequest() {
    doAnswer(invocation -> {
      return null;
    }).when(authzService)
        .check(any(CheckRequest.class),
            ArgumentMatchers.any());

    ExtAuthzClientCall<SimpleRequest, SimpleResponse> call =
        createCall();

    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    call.start(listener, new Metadata());

    verify(authzService)
        .check(any(CheckRequest.class),
            ArgumentMatchers.any());
  }

  @Test
  public void authzCheckCompletes_contextIsCancelled()
      throws InterruptedException {
    CountDownLatch checkDone = new CountDownLatch(1);
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> observer =
          invocation.getArgument(1);
      observer.onNext(CheckResponse.getDefaultInstance());
      observer.onCompleted();
      checkDone.countDown();
      return null;
    }).when(authzService)
        .check(any(CheckRequest.class),
            ArgumentMatchers.any());

    HeaderMutations emptyMutations = HeaderMutations.create(
        ImmutableList.of(), ImmutableList.of());
    AuthzResponse authzResponse =
        AuthzResponse.allow(emptyMutations)
            .setResponseHeaderMutations(emptyMutations)
            .build();
    when(mockResponseHandler.handleResponse(
        any(CheckResponse.class))).thenReturn(authzResponse);

    ExtAuthzClientCall<SimpleRequest, SimpleResponse> call =
        createCall();

    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    call.start(listener, new Metadata());

    assertThat(checkDone.await(5, TimeUnit.SECONDS)).isTrue();

    // After observer.onNext the context should be cancelled
    Context.CancellableContext authzContext =
        call.getAuthzContextForTest();
    assertThat(authzContext.isCancelled()).isTrue();
  }

  @Test
  public void authzCheckError_contextIsCancelled()
      throws InterruptedException {
    CountDownLatch checkDone = new CountDownLatch(1);
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> observer =
          invocation.getArgument(1);
      observer.onError(
          Status.UNAVAILABLE.asRuntimeException());
      checkDone.countDown();
      return null;
    }).when(authzService)
        .check(any(CheckRequest.class),
            ArgumentMatchers.any());

    ExtAuthzClientCall<SimpleRequest, SimpleResponse> call =
        createCall();

    CapturingListener<SimpleResponse> listener =
        new CapturingListener<>();
    call.start(listener, new Metadata());

    assertThat(checkDone.await(5, TimeUnit.SECONDS)).isTrue();

    // After observer.onError the context should be cancelled
    Context.CancellableContext authzContext =
        call.getAuthzContextForTest();
    assertThat(authzContext.isCancelled()).isTrue();
  }

  @Test
  public void authzCheckCompletes_allow_forwardsCall() throws Exception {
    lastBackendHeaders = null;

    CountDownLatch checkDone = new CountDownLatch(1);
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onNext(CheckResponse.getDefaultInstance());
      observer.onCompleted();
      checkDone.countDown();
      return null;
    }).when(authzService).check(any(CheckRequest.class), ArgumentMatchers.any());

    HeaderMutations emptyMutations = HeaderMutations.create(
        ImmutableList.of(), ImmutableList.of());
    AuthzResponse authzResponse = AuthzResponse.allow(emptyMutations)
        .setResponseHeaderMutations(emptyMutations)
        .build();
    when(mockResponseHandler.handleResponse(any(CheckResponse.class))).thenReturn(authzResponse);

    ExtAuthzClientCall<SimpleRequest, SimpleResponse> call = createCall(
        com.google.common.util.concurrent.MoreExecutors.directExecutor(),
        channel, config);
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    Metadata headers = new Metadata();
    call.start(listener, headers);

    assertThat(checkDone.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(lastBackendHeaders).isNotNull();
    verify(mockHeaderMutator, org.mockito.Mockito.never()).applyMutations(any(), any());
  }

  @Test
  public void authzCheckCompletes_allowWithMutations_wrapsCall() throws Exception {
    lastBackendHeaders = null;

    CountDownLatch checkDone = new CountDownLatch(1);
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onNext(CheckResponse.getDefaultInstance());
      observer.onCompleted();
      checkDone.countDown();
      return null;
    }).when(authzService).check(any(CheckRequest.class), ArgumentMatchers.any());

    HeaderMutations requestMutations = HeaderMutations.create(
        ImmutableList.of(HeaderValueOption.create(
            HeaderValue.create("foo", "bar"),
            HeaderValueOption.HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD)),
        ImmutableList.of());
    HeaderMutations responseMutations = HeaderMutations.create(
        ImmutableList.of(), ImmutableList.of());
    AuthzResponse authzResponse = AuthzResponse.allow(requestMutations)
        .setResponseHeaderMutations(responseMutations)
        .build();
    when(mockResponseHandler.handleResponse(any(CheckResponse.class))).thenReturn(authzResponse);

    ExtAuthzClientCall<SimpleRequest, SimpleResponse> call = createCall(
        com.google.common.util.concurrent.MoreExecutors.directExecutor(),
        channel, config);
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    Metadata headers = new Metadata();
    call.start(listener, headers);

    assertThat(checkDone.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(lastBackendHeaders).isNotNull();
    verify(mockHeaderMutator).applyMutations(ArgumentMatchers.eq(requestMutations), any());
  }

  @Test
  public void authzCheckCompletes_deny_failsCall() throws Exception {
    CountDownLatch checkDone = new CountDownLatch(1);
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onNext(CheckResponse.getDefaultInstance());
      observer.onCompleted();
      checkDone.countDown();
      return null;
    }).when(authzService).check(any(CheckRequest.class), ArgumentMatchers.any());

    HeaderMutations emptyMutations = HeaderMutations.create(
        ImmutableList.of(), ImmutableList.of());
    AuthzResponse authzResponse = AuthzResponse.deny(Status.PERMISSION_DENIED)
        .setResponseHeaderMutations(emptyMutations)
        .build();
    when(mockResponseHandler.handleResponse(any(CheckResponse.class))).thenReturn(authzResponse);

    ExtAuthzClientCall<SimpleRequest, SimpleResponse> call = createCall(
        com.google.common.util.concurrent.MoreExecutors.directExecutor(), channel, config);
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    call.start(listener, new Metadata());

    assertThat(checkDone.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(listener.getCloseStatus()).isEqualTo(Status.PERMISSION_DENIED);
    verify(mockHeaderMutator, org.mockito.Mockito.never()).applyMutations(any(), any());
  }

  @Test
  public void authzCheckCompletes_denyWithMutations_failsCallWithTrailers() throws Exception {
    CountDownLatch checkDone = new CountDownLatch(1);
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onNext(CheckResponse.getDefaultInstance());
      observer.onCompleted();
      checkDone.countDown();
      return null;
    }).when(authzService).check(any(CheckRequest.class), ArgumentMatchers.any());

    HeaderMutations responseMutations = HeaderMutations.create(
        ImmutableList.of(HeaderValueOption.create(
            HeaderValue.create("foo", "bar"),
            HeaderValueOption.HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD)),
        ImmutableList.of());
    AuthzResponse authzResponse = AuthzResponse.deny(Status.PERMISSION_DENIED)
        .setResponseHeaderMutations(responseMutations)
        .build();
    when(mockResponseHandler.handleResponse(any(CheckResponse.class))).thenReturn(authzResponse);

    ExtAuthzClientCall<SimpleRequest, SimpleResponse> call = createCall(
        com.google.common.util.concurrent.MoreExecutors.directExecutor(), channel, config);
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    call.start(listener, new Metadata());

    assertThat(checkDone.await(5, TimeUnit.SECONDS)).isTrue();
    verify(mockHeaderMutator).applyMutations(ArgumentMatchers.eq(responseMutations), any());
    assertThat(listener.getCloseStatus()).isEqualTo(Status.PERMISSION_DENIED);
  }

  @Test
  public void authzCheckError_failClosed_failsCall() throws Exception {
    ExtAuthzConfig failClosedConfig = ExtAuthzConfig.builder()
        .grpcService(config.grpcService())
        .failureModeAllow(false)
        .failureModeAllowHeaderAdd(false)
        .includePeerCertificate(false)
        .denyAtDisable(false)
        .filterEnabled(Matchers.FractionMatcher.create(100, 100))
        .statusOnError(Status.PERMISSION_DENIED)
        .build();

    CountDownLatch checkDone = new CountDownLatch(1);
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
      checkDone.countDown();
      return null;
    }).when(authzService).check(any(CheckRequest.class), ArgumentMatchers.any());

    ExtAuthzClientCall<SimpleRequest, SimpleResponse> call = createCall(
        com.google.common.util.concurrent.MoreExecutors.directExecutor(), channel,
        failClosedConfig);
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    call.start(listener, new Metadata());

    assertThat(checkDone.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(listener.getCloseStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(listener.getCloseStatus().getCause()).isInstanceOf(StatusRuntimeException.class);
    assertThat(
        ((StatusRuntimeException) listener.getCloseStatus().getCause())
            .getStatus().getCode())
        .isEqualTo(Status.Code.UNAVAILABLE);
  }

  @Test
  public void authzCheckError_failOpen_forwardsCall() throws Exception {
    ExtAuthzConfig failOpenConfig = ExtAuthzConfig.builder()
        .grpcService(config.grpcService())
        .failureModeAllow(true)
        .failureModeAllowHeaderAdd(false)
        .includePeerCertificate(false)
        .denyAtDisable(false)
        .filterEnabled(Matchers.FractionMatcher.create(100, 100))
        .statusOnError(Status.PERMISSION_DENIED)
        .build();

    lastBackendHeaders = null;

    CountDownLatch checkDone = new CountDownLatch(1);
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
      checkDone.countDown();
      return null;
    }).when(authzService).check(any(CheckRequest.class), ArgumentMatchers.any());

    ExtAuthzClientCall<SimpleRequest, SimpleResponse> call = createCall(
        com.google.common.util.concurrent.MoreExecutors.directExecutor(),
        channel, failOpenConfig);
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    Metadata headers = new Metadata();
    call.start(listener, headers);

    assertThat(checkDone.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(lastBackendHeaders).isNotNull();
    verify(mockHeaderMutator, org.mockito.Mockito.never()).applyMutations(any(), any());
  }

  @Test
  public void authzCheckError_failOpenWithHeaderAdd_wrapsCall() throws Exception {
    ExtAuthzConfig failOpenWithHeaderConfig = ExtAuthzConfig.builder()
        .grpcService(config.grpcService())
        .failureModeAllow(true)
        .failureModeAllowHeaderAdd(true)
        .includePeerCertificate(false)
        .denyAtDisable(false)
        .filterEnabled(Matchers.FractionMatcher.create(100, 100))
        .statusOnError(Status.PERMISSION_DENIED)
        .build();

    lastBackendHeaders = null;

    CountDownLatch checkDone = new CountDownLatch(1);
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onError(Status.UNAVAILABLE.asRuntimeException());
      checkDone.countDown();
      return null;
    }).when(authzService).check(any(CheckRequest.class), ArgumentMatchers.any());

    ExtAuthzClientCall<SimpleRequest, SimpleResponse> call = createCall(
        com.google.common.util.concurrent.MoreExecutors.directExecutor(),
        channel, failOpenWithHeaderConfig);
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    Metadata headers = new Metadata();
    call.start(listener, headers);

    assertThat(checkDone.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(lastBackendHeaders).isNotNull();
  }

  @Test
  public void drain_executesOnCallExecutor() throws Exception {
    java.util.concurrent.Executor mockExecutor = mock(java.util.concurrent.Executor.class);
    doAnswer(invocation -> {
      Runnable r = invocation.getArgument(0);
      r.run();
      return null;
    }).when(mockExecutor).execute(any(Runnable.class));

    lastBackendHeaders = null;

    CountDownLatch checkDone = new CountDownLatch(1);
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onNext(CheckResponse.getDefaultInstance());
      observer.onCompleted();
      checkDone.countDown();
      return null;
    }).when(authzService).check(any(CheckRequest.class), ArgumentMatchers.any());

    HeaderMutations emptyMutations = HeaderMutations.create(
        ImmutableList.of(), ImmutableList.of());
    AuthzResponse authzResponse = AuthzResponse.allow(emptyMutations)
        .setResponseHeaderMutations(emptyMutations)
        .build();
    when(mockResponseHandler.handleResponse(any(CheckResponse.class))).thenReturn(authzResponse);

    ExtAuthzClientCall<SimpleRequest, SimpleResponse> call =
        createCall(mockExecutor, channel, config);
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    call.start(listener, new Metadata());

    assertThat(checkDone.await(5, TimeUnit.SECONDS)).isTrue();
    verify(mockExecutor, org.mockito.Mockito.atLeastOnce()).execute(any(Runnable.class));
  }

  private ExtAuthzClientCall<SimpleRequest, SimpleResponse>
      createCall() {
    return createCall(channel, config);
  }

  private ExtAuthzClientCall<SimpleRequest, SimpleResponse>
      createCall(Channel next, ExtAuthzConfig config) {
    return createCall(callOptions.getExecutor(), next, config);
  }

  private ExtAuthzClientCall<SimpleRequest, SimpleResponse>
      createCall(
          java.util.concurrent.Executor executor, Channel next, ExtAuthzConfig config) {
    return new ExtAuthzClientCall<>(
        executor, scheduler, callOptions,
        next,
        SimpleServiceGrpc.getUnaryRpcMethod(),
        AuthorizationGrpc.newStub(channel),
        mockCheckRequestBuilder, mockResponseHandler,
        mockHeaderMutator, config);
  }

  private static ExtAuthzConfig buildConfig() {
    GrpcServiceConfig.GoogleGrpcConfig googleGrpc =
        GrpcServiceConfig.GoogleGrpcConfig.builder()
            .target("test-cluster")
            .configuredChannelCredentials(
                ConfiguredChannelCredentials.create(
                    mock(ChannelCredentials.class),
                    mock(ConfiguredChannelCredentials
                        .ChannelCredsConfig.class)))
            .build();
    GrpcServiceConfig grpcServiceConfig =
        GrpcServiceConfig.builder()
            .googleGrpc(googleGrpc)
            .initialMetadata(ImmutableList.of())
            .build();
    return ExtAuthzConfig.builder()
        .grpcService(grpcServiceConfig)
        .failureModeAllow(false)
        .failureModeAllowHeaderAdd(false)
        .includePeerCertificate(false)
        .denyAtDisable(false)
        .filterEnabled(
            Matchers.FractionMatcher.create(100, 100))
        .statusOnError(Status.PERMISSION_DENIED)
        .build();
  }
}
