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
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.envoyproxy.envoy.service.auth.v3.DeniedHttpResponse;
import io.envoyproxy.envoy.service.auth.v3.OkHttpResponse;
import io.envoyproxy.envoy.type.v3.HttpStatus;
import io.envoyproxy.envoy.type.v3.StatusCode;
import io.grpc.CallOptions;
import io.grpc.ChannelCredentials;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.DelayedClientCall;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.grpc.xds.client.ConfiguredChannelCredentials;
import io.grpc.xds.internal.Matchers;
import io.grpc.xds.internal.extauthz.ExtAuthzTestHelper.CapturingListener;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfig;
import io.grpc.xds.internal.headermutations.HeaderMutationFilter;
import io.grpc.xds.internal.headermutations.HeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderMutator;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Behavioral unit tests for {@link AuthzCallbackObserver}.
 */
@RunWith(JUnit4.class)
public class AuthzCallbackObserverTest {

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  private AuthorizationGrpc.AuthorizationImplBase authzService;

  private final CheckResponseHandler responseHandler =
      new CheckResponseHandler(new HeaderMutationFilter(Optional.empty()));
  private final HeaderMutator headerMutator = HeaderMutator.create();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor();

  private Server server;
  private ManagedChannel channel;
  private volatile Metadata capturedBackendHeaders;
  private volatile SimpleRequest capturedBackendMessage;

  @Before
  public void setUp() throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    server = InProcessServerBuilder.forName(serverName)
        .directExecutor()
        .addService(authzService)
        .intercept(new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers,
              ServerCallHandler<ReqT, RespT> next) {
            if (call.getMethodDescriptor().getServiceName().equals(
                SimpleServiceGrpc.getServiceDescriptor().getName())) {
              capturedBackendHeaders = headers;
            }
            return next.startCall(call, headers);
          }
        })
        .addService(ServerServiceDefinition.builder(
            SimpleServiceGrpc.getUnaryRpcMethod().getServiceName())
            .addMethod(SimpleServiceGrpc.getUnaryRpcMethod(),
                (ServerCallHandler<SimpleRequest, SimpleResponse>) (call, headers) -> {
                  call.request(2);
                  return new ServerCall.Listener<SimpleRequest>() {
                    @Override
                    public void onMessage(SimpleRequest message) {
                      capturedBackendMessage = message;
                      call.sendHeaders(new Metadata());
                      call.sendMessage(SimpleResponse.getDefaultInstance());
                      call.close(Status.OK, new Metadata());
                    }
                  };
                })
            .build())
        .build().start();
    channel = InProcessChannelBuilder.forName(serverName)
        .directExecutor().build();
  }

  @After
  public void tearDown() {
    if (channel != null) {
      channel.shutdownNow();
    }
    if (server != null) {
      server.shutdownNow();
    }
    scheduler.shutdownNow();
  }

  @Test
  public void allow_proxiesCallAndMessageToBackend() {
    capturedBackendHeaders = null;
    capturedBackendMessage = null;
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> obs = invocation.getArgument(1);
      obs.onNext(CheckResponse.newBuilder()
          .setStatus(com.google.rpc.Status.newBuilder().setCode(0).build())
          .setOkResponse(OkHttpResponse.getDefaultInstance())
          .build());
      obs.onCompleted();
      return null;
    }).when(authzService).check(any(), any());

    TestDelayedCall<SimpleRequest, SimpleResponse> delayedCall =
        new TestDelayedCall<>(MoreExecutors.directExecutor(), scheduler, null);
    Context.CancellableContext authzCtx = Context.current().withCancellation();
    AuthzCallbackObserver<SimpleRequest, SimpleResponse> observer =
        new AuthzCallbackObserver<>(
            delayedCall, channel,
            SimpleServiceGrpc.getUnaryRpcMethod(),
            CallOptions.DEFAULT,
            MoreExecutors.directExecutor(),
            responseHandler, headerMutator, failClosedConfig(), authzCtx);

    SimpleRequest request =
        SimpleRequest.newBuilder().setRequestMessage("allow-payload").build();
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER), "req-123");

    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    delayedCall.start(listener, headers);
    delayedCall.sendMessage(request);
    delayedCall.halfClose();
    delayedCall.request(1);

    authzCtx.run(() -> {
      AuthorizationGrpc.newStub(channel)
          .check(CheckRequest.getDefaultInstance(), observer);
    });

    assertThat(capturedBackendHeaders).isNotNull();
    assertThat(capturedBackendHeaders.get(
        Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("req-123");
    assertThat(capturedBackendMessage).isEqualTo(request);
  }

  @Test
  public void allow_withHeaderMutations_backendReceivesMutatedHeadersAndMessage() {
    capturedBackendHeaders = null;
    capturedBackendMessage = null;
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> obs = invocation.getArgument(1);
      obs.onNext(CheckResponse.newBuilder()
          .setStatus(com.google.rpc.Status.newBuilder().setCode(0).build())
          .setOkResponse(OkHttpResponse.newBuilder()
              .addHeaders(HeaderValueOption.newBuilder()
                  .setHeader(HeaderValue.newBuilder().setKey("x-custom").setValue("injected")))
              .build())
          .build());
      obs.onCompleted();
      return null;
    }).when(authzService).check(any(), any());

    TestDelayedCall<SimpleRequest, SimpleResponse> delayedCall =
        new TestDelayedCall<>(MoreExecutors.directExecutor(), scheduler, null);
    Context.CancellableContext authzCtx = Context.current().withCancellation();
    AuthzCallbackObserver<SimpleRequest, SimpleResponse> observer =
        new AuthzCallbackObserver<>(
            delayedCall, channel,
            SimpleServiceGrpc.getUnaryRpcMethod(),
            CallOptions.DEFAULT,
            MoreExecutors.directExecutor(),
            responseHandler, headerMutator, failClosedConfig(), authzCtx);

    SimpleRequest request =
        SimpleRequest.newBuilder().setRequestMessage("mutated-header-payload").build();
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    delayedCall.start(listener, new Metadata());
    delayedCall.sendMessage(request);
    delayedCall.halfClose();
    delayedCall.request(1);

    authzCtx.run(() -> {
      AuthorizationGrpc.newStub(channel)
          .check(CheckRequest.getDefaultInstance(), observer);
    });

    assertThat(capturedBackendHeaders).isNotNull();
    assertThat(capturedBackendHeaders.get(
        Metadata.Key.of("x-custom", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("injected");
    assertThat(capturedBackendMessage).isEqualTo(request);
  }

  @Test
  public void deny_returnsPermissionDeniedWithoutContactingBackend() {
    capturedBackendHeaders = null;
    capturedBackendMessage = null;
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> obs = invocation.getArgument(1);
      obs.onNext(CheckResponse.newBuilder()
          .setStatus(com.google.rpc.Status.newBuilder()
              .setCode(com.google.rpc.Code.PERMISSION_DENIED_VALUE))
          .setDeniedResponse(DeniedHttpResponse.newBuilder()
              .setStatus(HttpStatus.newBuilder().setCode(StatusCode.Forbidden)))
          .build());
      obs.onCompleted();
      return null;
    }).when(authzService).check(any(), any());

    TestDelayedCall<SimpleRequest, SimpleResponse> delayedCall =
        new TestDelayedCall<>(MoreExecutors.directExecutor(), scheduler, null);
    Context.CancellableContext authzCtx = Context.current().withCancellation();
    AuthzCallbackObserver<SimpleRequest, SimpleResponse> observer =
        new AuthzCallbackObserver<>(
            delayedCall, channel,
            SimpleServiceGrpc.getUnaryRpcMethod(),
            CallOptions.DEFAULT,
            MoreExecutors.directExecutor(),
            responseHandler, headerMutator, failClosedConfig(), authzCtx);

    SimpleRequest request =
        SimpleRequest.newBuilder().setRequestMessage("deny-payload").build();
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    delayedCall.start(listener, new Metadata());
    delayedCall.sendMessage(request);
    delayedCall.halfClose();
    delayedCall.request(1);

    authzCtx.run(() -> {
      AuthorizationGrpc.newStub(channel)
          .check(CheckRequest.getDefaultInstance(), observer);
    });

    assertThat(listener.getCloseStatus().getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(capturedBackendHeaders).isNull();
    assertThat(capturedBackendMessage).isNull();
  }

  @Test
  public void deny_withTrailerMutations_clientReceivesMutatedTrailers() {
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> obs = invocation.getArgument(1);
      obs.onNext(CheckResponse.newBuilder()
          .setStatus(com.google.rpc.Status.newBuilder()
              .setCode(com.google.rpc.Code.PERMISSION_DENIED_VALUE))
          .setDeniedResponse(DeniedHttpResponse.newBuilder()
              .setStatus(HttpStatus.newBuilder().setCode(StatusCode.Forbidden))
              .addHeaders(HeaderValueOption.newBuilder()
                  .setHeader(HeaderValue.newBuilder().setKey("x-deny-reason").setValue("policy"))))
          .build());
      obs.onCompleted();
      return null;
    }).when(authzService).check(any(), any());

    TestDelayedCall<SimpleRequest, SimpleResponse> delayedCall =
        new TestDelayedCall<>(MoreExecutors.directExecutor(), scheduler, null);
    Context.CancellableContext authzCtx = Context.current().withCancellation();
    AuthzCallbackObserver<SimpleRequest, SimpleResponse> observer =
        new AuthzCallbackObserver<>(
            delayedCall, channel,
            SimpleServiceGrpc.getUnaryRpcMethod(),
            CallOptions.DEFAULT,
            MoreExecutors.directExecutor(),
            responseHandler, headerMutator, failClosedConfig(), authzCtx);

    SimpleRequest request =
        SimpleRequest.newBuilder().setRequestMessage("deny-trailers-payload").build();
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    delayedCall.start(listener, new Metadata());
    delayedCall.sendMessage(request);
    delayedCall.halfClose();
    delayedCall.request(1);

    authzCtx.run(() -> {
      AuthorizationGrpc.newStub(channel)
          .check(CheckRequest.getDefaultInstance(), observer);
    });

    assertThat(listener.getCloseStatus().getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(listener.getCloseTrailers().get(
        Metadata.Key.of("x-deny-reason", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("policy");
  }

  @Test
  public void authzError_failClosed_returnsConfiguredStatus() {
    capturedBackendHeaders = null;
    capturedBackendMessage = null;
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> obs = invocation.getArgument(1);
      obs.onError(Status.UNAVAILABLE.asRuntimeException());
      return null;
    }).when(authzService).check(any(), any());

    TestDelayedCall<SimpleRequest, SimpleResponse> delayedCall =
        new TestDelayedCall<>(MoreExecutors.directExecutor(), scheduler, null);
    Context.CancellableContext authzCtx = Context.current().withCancellation();
    AuthzCallbackObserver<SimpleRequest, SimpleResponse> observer =
        new AuthzCallbackObserver<>(
            delayedCall, channel,
            SimpleServiceGrpc.getUnaryRpcMethod(),
            CallOptions.DEFAULT,
            MoreExecutors.directExecutor(),
            responseHandler, headerMutator, failClosedConfig(), authzCtx);

    SimpleRequest request =
        SimpleRequest.newBuilder().setRequestMessage("error-closed-payload").build();
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    delayedCall.start(listener, new Metadata());
    delayedCall.sendMessage(request);
    delayedCall.halfClose();
    delayedCall.request(1);

    authzCtx.run(() -> {
      AuthorizationGrpc.newStub(channel)
          .check(CheckRequest.getDefaultInstance(), observer);
    });

    assertThat(listener.getCloseStatus().getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(capturedBackendHeaders).isNull();
    assertThat(capturedBackendMessage).isNull();
  }

  @Test
  public void authzError_failOpen_proxiesToBackendWithMessage() {
    capturedBackendHeaders = null;
    capturedBackendMessage = null;
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> obs = invocation.getArgument(1);
      obs.onError(Status.UNAVAILABLE.asRuntimeException());
      return null;
    }).when(authzService).check(any(), any());

    TestDelayedCall<SimpleRequest, SimpleResponse> delayedCall =
        new TestDelayedCall<>(MoreExecutors.directExecutor(), scheduler, null);
    Context.CancellableContext authzCtx = Context.current().withCancellation();
    AuthzCallbackObserver<SimpleRequest, SimpleResponse> observer =
        new AuthzCallbackObserver<>(
            delayedCall, channel,
            SimpleServiceGrpc.getUnaryRpcMethod(),
            CallOptions.DEFAULT,
            MoreExecutors.directExecutor(),
            responseHandler, headerMutator,
            failOpenConfig(/*headerAdd=*/false), authzCtx);

    SimpleRequest request =
        SimpleRequest.newBuilder().setRequestMessage("error-open-payload").build();
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    delayedCall.start(listener, new Metadata());
    delayedCall.sendMessage(request);
    delayedCall.halfClose();
    delayedCall.request(1);

    authzCtx.run(() -> {
      AuthorizationGrpc.newStub(channel)
          .check(CheckRequest.getDefaultInstance(), observer);
    });

    assertThat(capturedBackendHeaders).isNotNull();
    assertThat(capturedBackendMessage).isEqualTo(request);
  }

  @Test
  public void authzError_failOpenWithHeaderAdd_backendGetsFailureModeHeader() {
    capturedBackendHeaders = null;
    capturedBackendMessage = null;
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> obs = invocation.getArgument(1);
      obs.onError(Status.UNAVAILABLE.asRuntimeException());
      return null;
    }).when(authzService).check(any(), any());

    TestDelayedCall<SimpleRequest, SimpleResponse> delayedCall =
        new TestDelayedCall<>(MoreExecutors.directExecutor(), scheduler, null);
    Context.CancellableContext authzCtx = Context.current().withCancellation();
    AuthzCallbackObserver<SimpleRequest, SimpleResponse> observer =
        new AuthzCallbackObserver<>(
            delayedCall, channel,
            SimpleServiceGrpc.getUnaryRpcMethod(),
            CallOptions.DEFAULT,
            MoreExecutors.directExecutor(),
            responseHandler, headerMutator,
            failOpenConfig(/*headerAdd=*/true), authzCtx);

    SimpleRequest request =
        SimpleRequest.newBuilder().setRequestMessage("error-header-payload").build();
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    delayedCall.start(listener, new Metadata());
    delayedCall.sendMessage(request);
    delayedCall.halfClose();
    delayedCall.request(1);

    authzCtx.run(() -> {
      AuthorizationGrpc.newStub(channel)
          .check(CheckRequest.getDefaultInstance(), observer);
    });

    assertThat(capturedBackendHeaders).isNotNull();
    assertThat(capturedBackendHeaders.get(
        Metadata.Key.of("x-envoy-auth-failure-mode-allowed",
            Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("true");
    assertThat(capturedBackendMessage).isEqualTo(request);
  }

  @Test
  public void buggyServer_completesWithoutResponse_failsCall() {
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> obs = invocation.getArgument(1);
      obs.onCompleted();
      return null;
    }).when(authzService).check(any(), any());

    TestDelayedCall<SimpleRequest, SimpleResponse> delayedCall =
        new TestDelayedCall<>(MoreExecutors.directExecutor(), scheduler, null);
    Context.CancellableContext authzCtx = Context.current().withCancellation();
    AuthzCallbackObserver<SimpleRequest, SimpleResponse> observer =
        new AuthzCallbackObserver<>(
            delayedCall, channel,
            SimpleServiceGrpc.getUnaryRpcMethod(),
            CallOptions.DEFAULT,
            MoreExecutors.directExecutor(),
            responseHandler, headerMutator, failClosedConfig(), authzCtx);

    SimpleRequest request =
        SimpleRequest.newBuilder().setRequestMessage("buggy-server-payload").build();
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    delayedCall.start(listener, new Metadata());
    delayedCall.sendMessage(request);
    delayedCall.halfClose();
    delayedCall.request(1);

    authzCtx.run(() -> {
      AuthorizationGrpc.newStub(channel)
          .check(CheckRequest.getDefaultInstance(), observer);
    });

    assertThat(listener.getCloseStatus().isOk()).isFalse();
    assertThat(listener.getCloseStatus().getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(listener.getCloseStatus().getCause()).isNotNull();
    assertThat(listener.getCloseStatus().getCause().getMessage())
        .contains("server cancelled stream");
  }

  @Test
  public void allow_withResponseOnlyHeaderMutations_backendReceivesMutatedHeadersAndMessage() {
    capturedBackendHeaders = null;
    capturedBackendMessage = null;
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> obs = invocation.getArgument(1);
      obs.onNext(CheckResponse.newBuilder()
          .setStatus(com.google.rpc.Status.newBuilder().setCode(0).build())
          .setOkResponse(OkHttpResponse.newBuilder()
              .addResponseHeadersToAdd(HeaderValueOption.newBuilder()
                  .setHeader(HeaderValue.newBuilder().setKey("x-resp-header").setValue("val"))))
          .build());
      obs.onCompleted();
      return null;
    }).when(authzService).check(any(), any());

    TestDelayedCall<SimpleRequest, SimpleResponse> delayedCall =
        new TestDelayedCall<>(MoreExecutors.directExecutor(), scheduler, null);
    Context.CancellableContext authzCtx = Context.current().withCancellation();
    AuthzCallbackObserver<SimpleRequest, SimpleResponse> observer =
        new AuthzCallbackObserver<>(
            delayedCall, channel,
            SimpleServiceGrpc.getUnaryRpcMethod(),
            CallOptions.DEFAULT,
            MoreExecutors.directExecutor(),
            responseHandler, headerMutator, failClosedConfig(), authzCtx);

    SimpleRequest request =
        SimpleRequest.newBuilder().setRequestMessage("resp-header-payload").build();
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    delayedCall.start(listener, new Metadata());
    delayedCall.sendMessage(request);
    delayedCall.halfClose();
    delayedCall.request(1);

    authzCtx.run(() -> {
      AuthorizationGrpc.newStub(channel)
          .check(CheckRequest.getDefaultInstance(), observer);
    });

    assertThat(capturedBackendHeaders).isNotNull();
    assertThat(capturedBackendMessage).isEqualTo(request);
  }

  @Test
  public void allow_withHeadersToRemoveOnly_backendReceivesMutatedHeaders() {
    capturedBackendHeaders = null;
    capturedBackendMessage = null;
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> obs = invocation.getArgument(1);
      obs.onNext(CheckResponse.newBuilder()
          .setStatus(com.google.rpc.Status.newBuilder().setCode(0).build())
          .setOkResponse(OkHttpResponse.newBuilder()
              .addHeadersToRemove("header-to-remove"))
          .build());
      obs.onCompleted();
      return null;
    }).when(authzService).check(any(), any());

    TestDelayedCall<SimpleRequest, SimpleResponse> delayedCall =
        new TestDelayedCall<>(MoreExecutors.directExecutor(), scheduler, null);
    Context.CancellableContext authzCtx = Context.current().withCancellation();
    AuthzCallbackObserver<SimpleRequest, SimpleResponse> observer =
        new AuthzCallbackObserver<>(
            delayedCall, channel,
            SimpleServiceGrpc.getUnaryRpcMethod(),
            CallOptions.DEFAULT,
            MoreExecutors.directExecutor(),
            responseHandler, headerMutator, failClosedConfig(), authzCtx);

    SimpleRequest request =
        SimpleRequest.newBuilder().setRequestMessage("remove-header-payload").build();
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("header-to-remove", Metadata.ASCII_STRING_MARSHALLER), "val");
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    delayedCall.start(listener, headers);
    delayedCall.sendMessage(request);
    delayedCall.halfClose();
    delayedCall.request(1);

    authzCtx.run(() -> {
      AuthorizationGrpc.newStub(channel)
          .check(CheckRequest.getDefaultInstance(), observer);
    });

    assertThat(capturedBackendHeaders).isNotNull();
    assertThat(capturedBackendHeaders.containsKey(
        Metadata.Key.of("header-to-remove", Metadata.ASCII_STRING_MARSHALLER))).isFalse();
    assertThat(capturedBackendMessage).isEqualTo(request);
  }

  @Test(expected = IllegalArgumentException.class)
  public void deny_withMissingStatus_throwsIllegalArgumentException() {
    CheckResponseHandler mockHandler = mock(CheckResponseHandler.class);
    AuthzResponse fakeAuthzResponse = new AuthzResponse() {
      @Override
      public Decision decision() {
        return Decision.DENY;
      }

      @Override
      public Optional<Status> status() {
        return Optional.empty();
      }

      @Override
      public HeaderMutations requestHeaderMutations() {
        return HeaderMutations.create(ImmutableList.of(), ImmutableList.of());
      }

      @Override
      public HeaderMutations responseHeaderMutations() {
        return HeaderMutations.create(ImmutableList.of(), ImmutableList.of());
      }
    };
    when(mockHandler.handleResponse(any())).thenReturn(fakeAuthzResponse);

    TestDelayedCall<SimpleRequest, SimpleResponse> delayedCall =
        new TestDelayedCall<>(MoreExecutors.directExecutor(), scheduler, null);
    Context.CancellableContext authzCtx = Context.current().withCancellation();
    AuthzCallbackObserver<SimpleRequest, SimpleResponse> observer =
        new AuthzCallbackObserver<>(
            delayedCall, channel,
            SimpleServiceGrpc.getUnaryRpcMethod(),
            CallOptions.DEFAULT,
            MoreExecutors.directExecutor(),
            mockHandler, headerMutator, failClosedConfig(), authzCtx);

    observer.onNext(CheckResponse.getDefaultInstance());
  }

  @Test
  public void allow_whenDelayedCallNotStarted_setCallReturnsNull() {
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> obs = invocation.getArgument(1);
      obs.onNext(CheckResponse.newBuilder()
          .setStatus(com.google.rpc.Status.newBuilder().setCode(0).build())
          .setOkResponse(OkHttpResponse.getDefaultInstance())
          .build());
      obs.onCompleted();
      return null;
    }).when(authzService).check(any(), any());

    TestDelayedCall<SimpleRequest, SimpleResponse> delayedCall =
        new TestDelayedCall<>(MoreExecutors.directExecutor(), scheduler, null);
    Context.CancellableContext authzCtx = Context.current().withCancellation();
    AuthzCallbackObserver<SimpleRequest, SimpleResponse> observer =
        new AuthzCallbackObserver<>(
            delayedCall, channel,
            SimpleServiceGrpc.getUnaryRpcMethod(),
            CallOptions.DEFAULT,
            MoreExecutors.directExecutor(),
            responseHandler, headerMutator, failClosedConfig(), authzCtx);

    authzCtx.run(() -> {
      AuthorizationGrpc.newStub(channel)
          .check(CheckRequest.getDefaultInstance(), observer);
    });

    assertThat(capturedBackendHeaders).isNull();
  }

  private static final class TestDelayedCall<ReqT, RespT>
      extends DelayedClientCall<ReqT, RespT> {
    TestDelayedCall(
        java.util.concurrent.Executor executor,
        ScheduledExecutorService scheduler,
        Deadline deadline) {
      super("TestDelayedCall", executor, scheduler, deadline);
    }
  }

  private static ExtAuthzConfig failClosedConfig() {
    GrpcServiceConfig.GoogleGrpcConfig googleGrpc =
        GrpcServiceConfig.GoogleGrpcConfig.builder()
            .target("test-cluster")
            .configuredChannelCredentials(
                ConfiguredChannelCredentials.create(
                    mock(ChannelCredentials.class),
                    mock(ConfiguredChannelCredentials.ChannelCredsConfig.class)))
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
        .filterEnabled(Matchers.FractionMatcher.create(100, 100))
        .statusOnError(Status.PERMISSION_DENIED)
        .build();
  }

  private static ExtAuthzConfig failOpenConfig(boolean headerAdd) {
    GrpcServiceConfig.GoogleGrpcConfig googleGrpc =
        GrpcServiceConfig.GoogleGrpcConfig.builder()
            .target("test-cluster")
            .configuredChannelCredentials(
                ConfiguredChannelCredentials.create(
                    mock(ChannelCredentials.class),
                    mock(ConfiguredChannelCredentials.ChannelCredsConfig.class)))
            .build();
    GrpcServiceConfig grpcServiceConfig =
        GrpcServiceConfig.builder()
            .googleGrpc(googleGrpc)
            .initialMetadata(ImmutableList.of())
            .build();
    return ExtAuthzConfig.builder()
        .grpcService(grpcServiceConfig)
        .failureModeAllow(true)
        .failureModeAllowHeaderAdd(headerAdd)
        .includePeerCertificate(false)
        .denyAtDisable(false)
        .filterEnabled(Matchers.FractionMatcher.create(100, 100))
        .statusOnError(Status.PERMISSION_DENIED)
        .build();
  }
}
