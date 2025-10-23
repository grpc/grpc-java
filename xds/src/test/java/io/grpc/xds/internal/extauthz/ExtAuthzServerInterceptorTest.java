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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.Timestamp;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.RuntimeFeatureFlag;
import io.envoyproxy.envoy.config.core.v3.RuntimeFractionalPercent;
import io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz;
import io.envoyproxy.envoy.extensions.grpc_service.call_credentials.access_token.v3.AccessTokenCredentials;
import io.envoyproxy.envoy.extensions.grpc_service.channel_credentials.google_default.v3.GoogleDefaultCredentials;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.envoyproxy.envoy.type.v3.FractionalPercent.DenominatorType;
import io.envoyproxy.envoy.type.v3.HttpStatus;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.grpc.xds.internal.ThreadSafeRandom;
import io.grpc.xds.internal.headermutations.HeaderMutations.ResponseHeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderMutator;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

/** Integration tests for {@link ExtAuthzServerInterceptor}. */
@RunWith(JUnit4.class)
public class ExtAuthzServerInterceptorTest {

  @Rule
  public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock
  private ThreadSafeRandom mockRandom;
  @Mock
  private CheckRequestBuilder mockCheckRequestBuilder;
  @Mock
  private CheckResponseHandler mockResponseHandler;
  @Mock
  private HeaderMutator mockHeaderMutator;
  @Mock
  private AuthorizationGrpc.AuthorizationImplBase authzService;

  private Server server;
  private ManagedChannel channel;
  private final AtomicReference<Metadata> serverHeadersCapture = new AtomicReference<>();
  private final AtomicReference<Metadata> clientResponseHeadersCapture = new AtomicReference<>();
  private final AtomicReference<Metadata> clientResponseTrailersCapture = new AtomicReference<>();

  private final SimpleServiceGrpc.SimpleServiceImplBase simpleServiceImpl =
      new SimpleServiceGrpc.SimpleServiceImplBase() {
        @Override
        public void unaryRpc(SimpleRequest request,
            StreamObserver<SimpleResponse> responseObserver) {
          responseObserver.onNext(SimpleResponse.newBuilder()
              .setResponseMessage("Hello " + request.getRequestMessage()).build());
          responseObserver.onCompleted();
        }

        @Override
        public StreamObserver<SimpleRequest> bidiStreamingRpc(
            final StreamObserver<SimpleResponse> responseObserver) {
          return new StreamObserver<SimpleRequest>() {
            @Override
            public void onNext(SimpleRequest request) {
              responseObserver.onNext(SimpleResponse.newBuilder()
                  .setResponseMessage("Hello " + request.getRequestMessage()).build());
            }

            @Override
            public void onError(Throwable t) {
              responseObserver.onError(t);
            }

            @Override
            public void onCompleted() {
              responseObserver.onCompleted();
            }
          };
        }
      };

  @Before
  public void setUp() throws IOException {}

  @After
  public void tearDown() {
    if (server != null) {
      server.shutdownNow();
    }
    if (channel != null) {
      channel.shutdownNow();
    }
  }

  @Test
  public void interceptCall_allow() throws Exception {
    ExtAuthzConfig config = buildExtAuthzConfig(false, false, 403, false, 0);
    String serverName = InProcessServerBuilder.generateName();
    channel = buildChannel(serverName);
    AuthorizationGrpc.AuthorizationStub authzStub = AuthorizationGrpc.newStub(channel);
    server = buildAndStartServer(config, authzStub, serverName);
    CheckRequest checkRequest = CheckRequest.getDefaultInstance();
    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(com.google.rpc.Status.newBuilder().setCode(Status.Code.OK.value())).build();
    ResponseHeaderMutations responseHeaderMutations =
        ResponseHeaderMutations.create(ImmutableList.of());
    setUpAllowCheck(checkRequest, checkResponse, responseHeaderMutations);

    SimpleServiceUnaryResponseObserver responseObserver = new SimpleServiceUnaryResponseObserver();
    ClientCalls.asyncUnaryCall(
        channel.newCall(SimpleServiceGrpc.getUnaryRpcMethod(), io.grpc.CallOptions.DEFAULT),
        SimpleRequest.newBuilder().setRequestMessage("world").build(), responseObserver);
    responseObserver.await();
    assertThat(responseObserver.getResponse().getResponseMessage()).isEqualTo("Hello world");
    assertThat(responseObserver.getError()).isNull();
    assertThat(serverHeadersCapture.get()
        .get(Metadata.Key.of("auth-key", Metadata.ASCII_STRING_MARSHALLER)))
            .isEqualTo("auth-value");
    assertThat(clientResponseHeadersCapture.get()
        .get(Metadata.Key.of("client-resp-key", Metadata.ASCII_STRING_MARSHALLER)))
            .isEqualTo("client-resp-value");
    assertThat(clientResponseTrailersCapture.get()).isNotNull();
  }

  @Test
  public void interceptCall_deny() throws Exception {
    ExtAuthzConfig config = buildExtAuthzConfig(false, false, 403, false, 0);
    String serverName = InProcessServerBuilder.generateName();
    channel = buildChannel(serverName);
    AuthorizationGrpc.AuthorizationStub authzStub = AuthorizationGrpc.newStub(channel);
    server = buildAndStartServer(config, authzStub, serverName);
    when(mockRandom.nextInt(100)).thenReturn(50);
    CheckRequest checkRequest = CheckRequest.getDefaultInstance();
    when(mockCheckRequestBuilder.buildRequest(any(ServerCall.class), any(Metadata.class),
        any(Timestamp.class))).thenReturn(checkRequest);
    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(
            com.google.rpc.Status.newBuilder().setCode(Status.Code.PERMISSION_DENIED.value()))
        .build();
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onNext(checkResponse);
      observer.onCompleted();
      return null;
    }).when(authzService).check(eq(checkRequest), ArgumentMatchers.any());
    Status expectedStatus = Status.PERMISSION_DENIED.withDescription("ext authz denied");
    AuthzResponse denyResponse = AuthzResponse.deny(expectedStatus).build();
    when(mockResponseHandler.handleResponse(eq(checkResponse), any())).thenReturn(denyResponse);

    SimpleServiceUnaryResponseObserver responseObserver = new SimpleServiceUnaryResponseObserver();
    ClientCalls.asyncUnaryCall(
        channel.newCall(SimpleServiceGrpc.getUnaryRpcMethod(), io.grpc.CallOptions.DEFAULT),
        SimpleRequest.newBuilder().setRequestMessage("world").build(), responseObserver);
    responseObserver.await();

    assertThat(responseObserver.getResponse()).isNull();
    assertThat(responseObserver.getError()).isNotNull();
    assertThat(responseObserver.getError().getCode()).isEqualTo(expectedStatus.getCode());
    assertThat(responseObserver.getError().getDescription())
        .isEqualTo(expectedStatus.getDescription());
  }

  @Test
  public void interceptCall_authzServerError_failCall() throws Exception {
    ExtAuthzConfig config = buildExtAuthzConfig(false, false, 503, false, 0);
    String serverName = InProcessServerBuilder.generateName();
    channel = buildChannel(serverName);
    AuthorizationGrpc.AuthorizationStub authzStub = AuthorizationGrpc.newStub(channel);
    server = buildAndStartServer(config, authzStub, serverName);
    when(mockRandom.nextInt(100)).thenReturn(50);
    CheckRequest checkRequest = CheckRequest.getDefaultInstance();
    when(mockCheckRequestBuilder.buildRequest(any(ServerCall.class), any(Metadata.class),
        any(Timestamp.class))).thenReturn(checkRequest);
    Status authzError = Status.UNAVAILABLE.withDescription("authz server unavailable");
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onError(authzError.asRuntimeException());
      return null;
    }).when(authzService).check(eq(checkRequest), ArgumentMatchers.any());

    SimpleServiceUnaryResponseObserver responseObserver = new SimpleServiceUnaryResponseObserver();
    ClientCalls.asyncUnaryCall(
        channel.newCall(SimpleServiceGrpc.getUnaryRpcMethod(), io.grpc.CallOptions.DEFAULT),
        SimpleRequest.newBuilder().setRequestMessage("world").build(), responseObserver);
    responseObserver.await();

    assertThat(responseObserver.getResponse()).isNull();
    assertThat(responseObserver.getError()).isNotNull();
    assertThat(responseObserver.getError().getCode()).isEqualTo(config.statusOnError().getCode());
  }

  @Test
  public void interceptCall_authzServerError_allow() throws Exception {
    ExtAuthzConfig config = buildExtAuthzConfig(true, false, 503, false, 0);
    String serverName = InProcessServerBuilder.generateName();
    channel = buildChannel(serverName);
    AuthorizationGrpc.AuthorizationStub authzStub = AuthorizationGrpc.newStub(channel);
    server = buildAndStartServer(config, authzStub, serverName);
    when(mockRandom.nextInt(100)).thenReturn(50);
    CheckRequest checkRequest = CheckRequest.getDefaultInstance();
    when(mockCheckRequestBuilder.buildRequest(any(ServerCall.class), any(Metadata.class),
        any(Timestamp.class))).thenReturn(checkRequest);
    Status authzError = Status.UNAVAILABLE.withDescription("authz server unavailable");
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onError(authzError.asRuntimeException());
      return null;
    }).when(authzService).check(eq(checkRequest), ArgumentMatchers.any());

    SimpleServiceUnaryResponseObserver responseObserver = new SimpleServiceUnaryResponseObserver();
    ClientCalls.asyncUnaryCall(
        channel.newCall(SimpleServiceGrpc.getUnaryRpcMethod(), io.grpc.CallOptions.DEFAULT),
        SimpleRequest.newBuilder().setRequestMessage("world").build(), responseObserver);
    responseObserver.await();

    assertThat(responseObserver.getResponse().getResponseMessage()).isEqualTo("Hello world");
    assertThat(responseObserver.getError()).isNull();
    assertThat(serverHeadersCapture.get().get(
        Metadata.Key.of("x-envoy-auth-failure-mode-allowed", Metadata.ASCII_STRING_MARSHALLER)))
            .isNull();
  }

  @Test
  public void interceptCall_authzServerError_allowWithHeaderAdd() throws Exception {
    ExtAuthzConfig config = buildExtAuthzConfig(true, true, 503, false, 0);
    String serverName = InProcessServerBuilder.generateName();
    channel = buildChannel(serverName);
    AuthorizationGrpc.AuthorizationStub authzStub = AuthorizationGrpc.newStub(channel);
    server = buildAndStartServer(config, authzStub, serverName);
    when(mockRandom.nextInt(100)).thenReturn(50);
    CheckRequest checkRequest = CheckRequest.getDefaultInstance();
    when(mockCheckRequestBuilder.buildRequest(any(ServerCall.class), any(Metadata.class),
        any(Timestamp.class))).thenReturn(checkRequest);
    Status authzError = Status.UNAVAILABLE.withDescription("authz server unavailable");
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onError(authzError.asRuntimeException());
      return null;
    }).when(authzService).check(eq(checkRequest), ArgumentMatchers.any());

    SimpleServiceUnaryResponseObserver responseObserver = new SimpleServiceUnaryResponseObserver();
    ClientCalls.asyncUnaryCall(
        channel.newCall(SimpleServiceGrpc.getUnaryRpcMethod(), io.grpc.CallOptions.DEFAULT),
        SimpleRequest.newBuilder().setRequestMessage("world").build(), responseObserver);
    responseObserver.await();

    assertThat(responseObserver.getResponse().getResponseMessage()).isEqualTo("Hello world");
    assertThat(responseObserver.getError()).isNull();
    assertThat(serverHeadersCapture.get().get(
        Metadata.Key.of("x-envoy-auth-failure-mode-allowed", Metadata.ASCII_STRING_MARSHALLER)))
            .isEqualTo("true");
  }

  @Test
  public void interceptCall_filterDisabled_denyAtDisable() throws Exception {
    ExtAuthzConfig config = buildExtAuthzConfig(false, false, 403, true, 100);
    String serverName = InProcessServerBuilder.generateName();
    channel = buildChannel(serverName);
    AuthorizationGrpc.AuthorizationStub authzStub = AuthorizationGrpc.newStub(channel);
    server = buildAndStartServer(config, authzStub, serverName);
    when(mockRandom.nextInt(100)).thenReturn(50);

    SimpleServiceUnaryResponseObserver responseObserver = new SimpleServiceUnaryResponseObserver();
    ClientCalls.asyncUnaryCall(
        channel.newCall(SimpleServiceGrpc.getUnaryRpcMethod(), io.grpc.CallOptions.DEFAULT),
        SimpleRequest.newBuilder().setRequestMessage("world").build(), responseObserver);
    responseObserver.await();

    assertThat(responseObserver.getResponse()).isNull();
    assertThat(responseObserver.getError()).isNotNull();
    assertThat(responseObserver.getError().getCode()).isEqualTo(config.statusOnError().getCode());
    verify(authzService, never()).check(any(), any());
  }

  @Test
  public void interceptCall_filterDisabled_allow() throws Exception {
    ExtAuthzConfig config = buildExtAuthzConfig(false, false, 403, false, 100);
    String serverName = InProcessServerBuilder.generateName();
    channel = buildChannel(serverName);
    AuthorizationGrpc.AuthorizationStub authzStub = AuthorizationGrpc.newStub(channel);
    server = buildAndStartServer(config, authzStub, serverName);
    when(mockRandom.nextInt(100)).thenReturn(50);

    SimpleServiceUnaryResponseObserver responseObserver = new SimpleServiceUnaryResponseObserver();
    ClientCalls.asyncUnaryCall(
        channel.newCall(SimpleServiceGrpc.getUnaryRpcMethod(), io.grpc.CallOptions.DEFAULT),
        SimpleRequest.newBuilder().setRequestMessage("world").build(), responseObserver);
    responseObserver.await();

    assertThat(responseObserver.getResponse().getResponseMessage()).isEqualTo("Hello world");
    assertThat(responseObserver.getError()).isNull();
    verify(authzService, never()).check(any(), any());
  }

  @Test
  public void interceptCall_streaming_allow() throws Exception {
    ExtAuthzConfig config = buildExtAuthzConfig(false, false, 403, false, 0);
    String serverName = InProcessServerBuilder.generateName();
    channel = buildChannel(serverName);
    AuthorizationGrpc.AuthorizationStub authzStub = AuthorizationGrpc.newStub(channel);
    server = buildAndStartServer(config, authzStub, serverName);
    when(mockRandom.nextInt(100)).thenReturn(50);
    CheckRequest checkRequest = CheckRequest.getDefaultInstance();
    when(mockCheckRequestBuilder.buildRequest(any(ServerCall.class), any(Metadata.class),
        any(Timestamp.class))).thenReturn(checkRequest);
    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(com.google.rpc.Status.newBuilder().setCode(Status.Code.OK.value())).build();
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onNext(checkResponse);
      observer.onCompleted();
      return null;
    }).when(authzService).check(eq(checkRequest), ArgumentMatchers.any());
    AuthzResponse allowResponse = AuthzResponse.allow(new Metadata()).build();
    when(mockResponseHandler.handleResponse(eq(checkResponse), any())).thenReturn(allowResponse);

    SimpleServiceStreamingResponseObserver responseObserver =
        new SimpleServiceStreamingResponseObserver();
    StreamObserver<SimpleRequest> requestObserver = ClientCalls.asyncBidiStreamingCall(
        channel.newCall(SimpleServiceGrpc.getBidiStreamingRpcMethod(), io.grpc.CallOptions.DEFAULT),
        responseObserver);
    requestObserver.onNext(SimpleRequest.newBuilder().setRequestMessage("world").build());
    requestObserver.onCompleted();
    responseObserver.await();

    assertThat(responseObserver.getResponses()).hasSize(1);
    assertThat(responseObserver.getResponses().get(0).getResponseMessage())
        .isEqualTo("Hello world");
    assertThat(responseObserver.getError()).isNull();
  }

  @Test
  public void interceptCall_streaming_deny() throws Exception {
    ExtAuthzConfig config = buildExtAuthzConfig(false, false, 403, false, 0);
    String serverName = InProcessServerBuilder.generateName();
    channel = buildChannel(serverName);
    AuthorizationGrpc.AuthorizationStub authzStub = AuthorizationGrpc.newStub(channel);
    server = buildAndStartServer(config, authzStub, serverName);
    when(mockRandom.nextInt(100)).thenReturn(50);
    CheckRequest checkRequest = CheckRequest.getDefaultInstance();
    when(mockCheckRequestBuilder.buildRequest(any(ServerCall.class), any(Metadata.class),
        any(Timestamp.class))).thenReturn(checkRequest);
    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(
            com.google.rpc.Status.newBuilder().setCode(Status.Code.PERMISSION_DENIED.value()))
        .build();
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onNext(checkResponse);
      observer.onCompleted();
      return null;
    }).when(authzService).check(eq(checkRequest), ArgumentMatchers.any());
    Status expectedStatus = Status.PERMISSION_DENIED.withDescription("ext authz denied");
    AuthzResponse denyResponse = AuthzResponse.deny(expectedStatus).build();
    when(mockResponseHandler.handleResponse(eq(checkResponse), any())).thenReturn(denyResponse);

    SimpleServiceStreamingResponseObserver responseObserver =
        new SimpleServiceStreamingResponseObserver();
    StreamObserver<SimpleRequest> requestObserver = ClientCalls.asyncBidiStreamingCall(
        channel.newCall(SimpleServiceGrpc.getBidiStreamingRpcMethod(), io.grpc.CallOptions.DEFAULT),
        responseObserver);
    requestObserver.onNext(SimpleRequest.newBuilder().setRequestMessage("world").build());
    requestObserver.onCompleted();
    responseObserver.await();

    assertThat(responseObserver.getResponses()).isEmpty();
    assertThat(responseObserver.getError()).isNotNull();
    assertThat(responseObserver.getError().getCode()).isEqualTo(expectedStatus.getCode());
    assertThat(responseObserver.getError().getDescription())
        .isEqualTo(expectedStatus.getDescription());
  }

  private ManagedChannel buildChannel(String serverName) {
    return InProcessChannelBuilder.forName(serverName).intercept(MetadataUtils
        .newCaptureMetadataInterceptor(clientResponseHeadersCapture, clientResponseTrailersCapture))
        .directExecutor().build();

  }

  private Server buildAndStartServer(ExtAuthzConfig config,
      AuthorizationGrpc.AuthorizationStub authzStub, String serverName) throws IOException {
    ServerInterceptor interceptor = ExtAuthzServerInterceptor.INSTANCE.create(config, authzStub,
        mockRandom, mockCheckRequestBuilder, mockResponseHandler, mockHeaderMutator);

    return InProcessServerBuilder.forName(serverName).addService(authzService)
        .addService(ServerInterceptors.intercept(simpleServiceImpl,
            new MetadataCapturingServerInterceptor(serverHeadersCapture), interceptor))
        .directExecutor().build().start();

  }

  private ExtAuthzConfig buildExtAuthzConfig(boolean failureModeAllow,
      boolean failureModeAllowHeaderAdd, int httpStatusOnError, boolean denyAtDisable, int percent)
      throws ExtAuthzParseException {
    Any googleDefaultChannelCreds = Any.pack(GoogleDefaultCredentials.newBuilder().build());
    Any fakeAccessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("fake-token").build());
    ExtAuthz extAuthz = ExtAuthz.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder().setTargetUri("test-cluster")
                .addChannelCredentialsPlugin(googleDefaultChannelCreds)
                .addCallCredentialsPlugin(fakeAccessTokenCreds).build())
            .build())
        .setFailureModeAllow(failureModeAllow)
        .setFailureModeAllowHeaderAdd(failureModeAllowHeaderAdd)
        .setDenyAtDisable(
            RuntimeFeatureFlag.newBuilder().setDefaultValue(BoolValue.of(denyAtDisable)).build())
        .setStatusOnError(HttpStatus.newBuilder().setCodeValue(httpStatusOnError).build())
        .setFilterEnabled(RuntimeFractionalPercent.newBuilder()
            .setDefaultValue(FractionalPercent.newBuilder().setNumerator(percent)
                .setDenominator(DenominatorType.HUNDRED).build())
            .build())
        .setIncludePeerCertificate(denyAtDisable).build();
    return ExtAuthzConfig.fromProto(extAuthz);
  }

  private static class SimpleServiceUnaryResponseObserver
      implements StreamObserver<SimpleResponse> {

    final AtomicReference<SimpleResponse> responseCapture = new AtomicReference<>();
    final AtomicReference<Status> errorCapture = new AtomicReference<>();
    final CountDownLatch latch = new CountDownLatch(1);

    @Override
    public void onNext(SimpleResponse value) {
      responseCapture.set(value);
    }

    @Override
    public void onError(Throwable t) {
      errorCapture.set(Status.fromThrowable(t));
      latch.countDown();
    }

    @Override
    public void onCompleted() {
      latch.countDown();
    }

    public void await() throws InterruptedException {
      latch.await(5, TimeUnit.SECONDS);
    }

    public SimpleResponse getResponse() {
      return responseCapture.get();
    }

    public Status getError() {
      return errorCapture.get();
    }
  }

  private static class SimpleServiceStreamingResponseObserver
      implements StreamObserver<SimpleResponse> {

    final ImmutableList.Builder<SimpleResponse> responsesCapture = new ImmutableList.Builder<>();
    final AtomicReference<Status> errorCapture = new AtomicReference<>();
    final CountDownLatch latch = new CountDownLatch(1);

    @Override
    public void onNext(SimpleResponse value) {
      responsesCapture.add(value);
    }

    @Override
    public void onError(Throwable t) {
      errorCapture.set(Status.fromThrowable(t));
      latch.countDown();
    }

    @Override
    public void onCompleted() {
      latch.countDown();
    }

    public void await() throws InterruptedException {
      latch.await(5, TimeUnit.SECONDS);
    }

    public ImmutableList<SimpleResponse> getResponses() {
      return responsesCapture.build();
    }

    public Status getError() {
      return errorCapture.get();
    }
  }

  private static final class MetadataCapturingServerInterceptor implements ServerInterceptor {
    private final AtomicReference<Metadata> headersCapture;

    MetadataCapturingServerInterceptor(AtomicReference<Metadata> headersCapture) {
      this.headersCapture = headersCapture;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
        Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      Metadata metadataCopy = new Metadata();
      metadataCopy.merge(headers);
      headersCapture.set(metadataCopy);
      return next.startCall(call, headers);
    }
  }

  private void setUpAllowCheck(CheckRequest checkRequest, CheckResponse checkResponse,
      ResponseHeaderMutations responseHeaderMutations) {
    when(mockRandom.nextInt(100)).thenReturn(50);
    when(mockCheckRequestBuilder.buildRequest(any(ServerCall.class), any(Metadata.class),
        any(Timestamp.class))).thenReturn(checkRequest);
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onNext(checkResponse);
      observer.onCompleted();
      return null;
    }).when(authzService).check(eq(checkRequest), ArgumentMatchers.any());
    Metadata headersFromServer = new Metadata();
    headersFromServer.put(Metadata.Key.of("auth-key", Metadata.ASCII_STRING_MARSHALLER),
        "auth-value");
    AuthzResponse allowResponse = AuthzResponse.allow(headersFromServer)
        .setResponseHeaderMutations(responseHeaderMutations).build();
    when(mockResponseHandler.handleResponse(eq(checkResponse), any())).thenReturn(allowResponse);
    doAnswer(invocation -> {
      Metadata headers = invocation.getArgument(1);
      headers.put(Metadata.Key.of("client-resp-key", Metadata.ASCII_STRING_MARSHALLER),
          "client-resp-value");
      return null;
    }).when(mockHeaderMutator).applyResponseMutations(eq(responseHeaderMutations),
        any(Metadata.class));
  }
}
