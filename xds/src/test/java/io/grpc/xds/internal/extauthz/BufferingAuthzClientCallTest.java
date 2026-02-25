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
import com.google.protobuf.Timestamp;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz;
import io.envoyproxy.envoy.extensions.grpc_service.call_credentials.access_token.v3.AccessTokenCredentials;
import io.envoyproxy.envoy.extensions.grpc_service.channel_credentials.google_default.v3.GoogleDefaultCredentials;
import io.envoyproxy.envoy.service.auth.v3.AttributeContext;
import io.envoyproxy.envoy.service.auth.v3.AttributeContext.HttpRequest;
import io.envoyproxy.envoy.service.auth.v3.AttributeContext.Request;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.envoyproxy.envoy.service.auth.v3.OkHttpResponse;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
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
import org.mockito.stubbing.Answer;

/** Unit tests for {@link BufferingAuthzClientCall}. */
@RunWith(JUnit4.class)
public class BufferingAuthzClientCallTest {

  @Rule
  public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock
  private AuthorizationGrpc.AuthorizationImplBase authzService;
  @Mock
  private CheckRequestBuilder checkRequestBuilder;
  @Mock
  private CheckResponseHandler responseHandler;
  @Mock
  private HeaderMutator headerMutator;

  private ManagedChannel channel;
  private Server server;

  private final AtomicReference<Metadata> serverHeadersCapture = new AtomicReference<>();
  private final AtomicReference<Metadata> clientHeadersCapture = new AtomicReference<>();
  private final AtomicReference<Metadata> clientTrailersCapture = new AtomicReference<>();


  @Before
  public void setUp() throws IOException {
    server = InProcessServerBuilder
        .forName("in-process-server").addService(authzService).addService(ServerInterceptors
            .intercept(new SimpleServiceImpl(),
                new MetadataCapturingServerInterceptor(serverHeadersCapture)))
        .directExecutor()
        .build().start();
    channel =
        InProcessChannelBuilder
            .forName("in-process-server").intercept(MetadataUtils
                .newCaptureMetadataInterceptor(clientHeadersCapture, clientTrailersCapture))
            .directExecutor()
            .build();
  }

  @After
  public void tearDown() {
    server.shutdownNow();
    channel.shutdownNow();
  }

  @Test
  public void onUnary_allowresponse() throws InterruptedException, ExtAuthzParseException {
    ExtAuthzConfig config = buildExtAuthzConfig(false, false, 403);

    CheckRequest checkRequest = CheckRequest.newBuilder().setAttributes(AttributeContext
        .newBuilder()
            .setRequest(Request.newBuilder()
                .setHttp(HttpRequest.newBuilder().setId("RequestId").build()).build())
            .build())
        .build();
    when(checkRequestBuilder.buildRequest(eq(SimpleServiceGrpc.getUnaryRpcMethod()),
        any(Metadata.class), any(Timestamp.class))).thenReturn(checkRequest);

    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(com.google.rpc.Status.newBuilder().setCode(Status.Code.OK.value()).build())
        .setOkResponse(OkHttpResponse.getDefaultInstance()).build();
    doAnswer((Answer<Void>) invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onNext(checkResponse);
      observer.onCompleted();
      return null;
    }).when(authzService).check(eq(checkRequest),
        ArgumentMatchers.<StreamObserver<CheckResponse>>any());

    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("key1", Metadata.ASCII_STRING_MARSHALLER), "value1");
    ResponseHeaderMutations responseHeaderMutations =
        ResponseHeaderMutations.create(ImmutableList.of());
    AuthzResponse allowResponse =
        AuthzResponse.allow(metadata)
            .setResponseHeaderMutations(responseHeaderMutations).build();
    when(responseHandler.handleResponse(eq(checkResponse), any(Metadata.class)))
        .thenReturn(allowResponse);

    doAnswer((Answer<Void>) invocation -> {
      Metadata headers = invocation.getArgument(1);
      headers.put(Metadata.Key.of("key2", Metadata.ASCII_STRING_MARSHALLER), "value2");
      return null;
    }).when(headerMutator).applyResponseMutations(eq(responseHeaderMutations),
            any(Metadata.class));

    ClientCall<SimpleRequest, SimpleResponse> realCall =
        channel.newCall(SimpleServiceGrpc.getUnaryRpcMethod(), CallOptions.DEFAULT);
    AuthorizationGrpc.AuthorizationStub authzStub = AuthorizationGrpc.newStub(channel);
    ClientCall<SimpleRequest, SimpleResponse> call = BufferingAuthzClientCall.FACTORY_INSTANCE
        .create(realCall, config, authzStub, checkRequestBuilder, responseHandler, headerMutator,
            SimpleServiceGrpc.getUnaryRpcMethod(), new CallBuffer());
    SimpleServiceUnaryResponseObserver simpleServiceResponseObserver =
        new SimpleServiceUnaryResponseObserver();
    SimpleRequest simpleRequest = SimpleRequest.newBuilder().setRequestMessage("World").build();
    ClientCalls.asyncUnaryCall(call, simpleRequest, simpleServiceResponseObserver);
    simpleServiceResponseObserver.await();

    assertThat(simpleServiceResponseObserver.getResponse().getResponseMessage())
        .isEqualTo("Hello World");
    assertThat(
        serverHeadersCapture.get().get(Metadata.Key.of("key1", Metadata.ASCII_STRING_MARSHALLER)))
            .isEqualTo("value1");
    assertThat(
        clientHeadersCapture.get().get(Metadata.Key.of("key2", Metadata.ASCII_STRING_MARSHALLER)))
            .isEqualTo("value2");
    assertThat(clientTrailersCapture.get()).isNotNull();
    verify(headerMutator).applyResponseMutations(eq(responseHeaderMutations), any(Metadata.class));
  }

  @Test
  public void onUnary_denyResponse() throws InterruptedException, ExtAuthzParseException {
    ExtAuthzConfig config = buildExtAuthzConfig(false, false, 403);
    CheckRequest checkRequest = CheckRequest.newBuilder().build();
    when(checkRequestBuilder.buildRequest(eq(SimpleServiceGrpc.getUnaryRpcMethod()),
        any(Metadata.class), any(Timestamp.class))).thenReturn(checkRequest);

    CheckResponse checkResponse = CheckResponse.newBuilder().setStatus(
        com.google.rpc.Status.newBuilder().setCode(Status.Code.PERMISSION_DENIED.value()).build())
        .build();
    doAnswer((Answer<Void>) invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onNext(checkResponse);
      observer.onCompleted();
      return null;
    }).when(authzService).check(eq(checkRequest),
        ArgumentMatchers.<StreamObserver<CheckResponse>>any());

    Status expectedStatus = Status.PERMISSION_DENIED.withDescription("ext authz denied");
    AuthzResponse denyResponse = AuthzResponse.deny(expectedStatus).build();
    when(responseHandler.handleResponse(eq(checkResponse), any(Metadata.class)))
        .thenReturn(denyResponse);

    ClientCall<SimpleRequest, SimpleResponse> realCall =
        channel.newCall(SimpleServiceGrpc.getUnaryRpcMethod(), CallOptions.DEFAULT);
    AuthorizationGrpc.AuthorizationStub authzStub = AuthorizationGrpc.newStub(channel);
    CallBuffer callBuffer = new CallBuffer();
    ClientCall<SimpleRequest, SimpleResponse> call = BufferingAuthzClientCall.FACTORY_INSTANCE
        .create(realCall, config, authzStub, checkRequestBuilder, responseHandler, headerMutator,
            SimpleServiceGrpc.getUnaryRpcMethod(), callBuffer);
    SimpleServiceUnaryResponseObserver simpleServiceResponseObserver =
        new SimpleServiceUnaryResponseObserver();
    SimpleRequest simpleRequest = SimpleRequest.newBuilder().setRequestMessage("World").build();
    ClientCalls.asyncUnaryCall(call,
        simpleRequest, simpleServiceResponseObserver);
    simpleServiceResponseObserver.await();

    assertThat(simpleServiceResponseObserver.getResponse()).isNull();
    assertThat(simpleServiceResponseObserver.getError()).isNotNull();
    assertThat(simpleServiceResponseObserver.getError().getCode())
        .isEqualTo(expectedStatus.getCode());
    assertThat(simpleServiceResponseObserver.getError().getDescription())
        .isEqualTo(expectedStatus.getDescription());
    assertThat(callBuffer.isProcessed()).isTrue();
  }

  @Test
  public void onUnary_authzServerError_failTheCall()
      throws InterruptedException, ExtAuthzParseException {
    CheckRequest checkRequest = CheckRequest.newBuilder().build();
    when(checkRequestBuilder.buildRequest(eq(SimpleServiceGrpc.getUnaryRpcMethod()),
        any(Metadata.class), any(Timestamp.class))).thenReturn(checkRequest);

    Status authzError = Status.UNAVAILABLE.withDescription("ext authz server unavailable");
    doAnswer((Answer<Void>) invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onError(authzError.asRuntimeException());
      return null;
    }).when(authzService).check(eq(checkRequest),
        ArgumentMatchers.<StreamObserver<CheckResponse>>any());

    ExtAuthzConfig config = buildExtAuthzConfig(false, false, 503);


    ClientCall<SimpleRequest, SimpleResponse> realCall =
        channel.newCall(SimpleServiceGrpc.getUnaryRpcMethod(), CallOptions.DEFAULT);
    AuthorizationGrpc.AuthorizationStub authzStub = AuthorizationGrpc.newStub(channel);
    CallBuffer callBuffer = new CallBuffer();
    ClientCall<SimpleRequest, SimpleResponse> call = BufferingAuthzClientCall.FACTORY_INSTANCE
        .create(realCall, config, authzStub, checkRequestBuilder, responseHandler, headerMutator,
            SimpleServiceGrpc.getUnaryRpcMethod(), callBuffer);
    SimpleServiceUnaryResponseObserver simpleServiceResponseObserver =
        new SimpleServiceUnaryResponseObserver();
    SimpleRequest simpleRequest = SimpleRequest.newBuilder().setRequestMessage("World").build();
    ClientCalls.asyncUnaryCall(call, simpleRequest, simpleServiceResponseObserver);
    simpleServiceResponseObserver.await();

    assertThat(simpleServiceResponseObserver.getResponse()).isNull();
    assertThat(simpleServiceResponseObserver.getError()).isNotNull();
    assertThat(simpleServiceResponseObserver.getError().getCode())
        .isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(callBuffer.isProcessed()).isTrue();
    verify(responseHandler, never()).handleResponse(any(CheckResponse.class), any(Metadata.class));
  }

  @Test
  public void onUnary_authzServerError_failureModeAllow()
      throws InterruptedException, ExtAuthzParseException {
    ExtAuthzConfig config = buildExtAuthzConfig(true, false, 503);
    CheckRequest checkRequest = CheckRequest.newBuilder().build();
    when(checkRequestBuilder.buildRequest(eq(SimpleServiceGrpc.getUnaryRpcMethod()),
        any(Metadata.class), any(Timestamp.class))).thenReturn(checkRequest);

    Status authzError = Status.UNAVAILABLE.withDescription("authz server unavailable");
    doAnswer((Answer<Void>) invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onError(authzError.asRuntimeException());
      return null;
    }).when(authzService).check(eq(checkRequest),
        ArgumentMatchers.<StreamObserver<CheckResponse>>any());

    ClientCall<SimpleRequest, SimpleResponse> realCall =
        channel.newCall(SimpleServiceGrpc.getUnaryRpcMethod(), CallOptions.DEFAULT);
    AuthorizationGrpc.AuthorizationStub authzStub = AuthorizationGrpc.newStub(channel);
    ClientCall<SimpleRequest, SimpleResponse> call = BufferingAuthzClientCall.FACTORY_INSTANCE
        .create(realCall, config, authzStub, checkRequestBuilder, responseHandler, headerMutator,
            SimpleServiceGrpc.getUnaryRpcMethod(), new CallBuffer());
    SimpleServiceUnaryResponseObserver simpleServiceResponseObserver =
        new SimpleServiceUnaryResponseObserver();
    SimpleRequest simpleRequest = SimpleRequest.newBuilder().setRequestMessage("World").build();
    ClientCalls.asyncUnaryCall(call, simpleRequest, simpleServiceResponseObserver);
    simpleServiceResponseObserver.await();

    assertThat(simpleServiceResponseObserver.getResponse().getResponseMessage())
        .isEqualTo("Hello World");
    assertThat(
        serverHeadersCapture.get().get(Metadata.Key.of("key1", Metadata.ASCII_STRING_MARSHALLER)))
            .isNull();
    assertThat(
        clientHeadersCapture.get().get(Metadata.Key.of("key2", Metadata.ASCII_STRING_MARSHALLER)))
            .isNull();
    assertThat(clientTrailersCapture.get()).isNotNull();
    verify(headerMutator, never()).applyResponseMutations(any(ResponseHeaderMutations.class),
        any(Metadata.class));
    verify(responseHandler, never()).handleResponse(any(CheckResponse.class), any(Metadata.class));
  }

  @Test
  public void onUnary_authzServerError_failureModeAllowHeaderAdd()
      throws InterruptedException, ExtAuthzParseException {
    ExtAuthzConfig config = buildExtAuthzConfig(true, true, 503);
    CheckRequest checkRequest = CheckRequest.newBuilder().build();
    when(checkRequestBuilder.buildRequest(eq(SimpleServiceGrpc.getUnaryRpcMethod()),
        any(Metadata.class), any(Timestamp.class))).thenReturn(checkRequest);

    Status authzError = Status.UNAVAILABLE.withDescription("authz server unavailable");
    doAnswer((Answer<Void>) invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onError(authzError.asRuntimeException());
      return null;
    }).when(authzService).check(eq(checkRequest),
        ArgumentMatchers.<StreamObserver<CheckResponse>>any());

    ClientCall<SimpleRequest, SimpleResponse> realCall =
        channel.newCall(SimpleServiceGrpc.getUnaryRpcMethod(), CallOptions.DEFAULT);
    AuthorizationGrpc.AuthorizationStub authzStub = AuthorizationGrpc.newStub(channel);
    ClientCall<SimpleRequest, SimpleResponse> call = BufferingAuthzClientCall.FACTORY_INSTANCE
        .create(realCall, config, authzStub, checkRequestBuilder, responseHandler, headerMutator,
            SimpleServiceGrpc.getUnaryRpcMethod(), new CallBuffer());
    SimpleServiceUnaryResponseObserver simpleServiceResponseObserver =
        new SimpleServiceUnaryResponseObserver();
    SimpleRequest simpleRequest = SimpleRequest.newBuilder().setRequestMessage("World").build();
    ClientCalls.asyncUnaryCall(call, simpleRequest, simpleServiceResponseObserver);
    simpleServiceResponseObserver.await();

    assertThat(simpleServiceResponseObserver.getResponse().getResponseMessage())
        .isEqualTo("Hello World");
    assertThat(serverHeadersCapture.get().get(
        Metadata.Key.of("x-envoy-auth-failure-mode-allowed", Metadata.ASCII_STRING_MARSHALLER)))
            .isEqualTo("true");
    assertThat(clientTrailersCapture.get()).isNotNull();
    verify(headerMutator, never()).applyResponseMutations(any(ResponseHeaderMutations.class),
        any(Metadata.class));
    verify(responseHandler, never()).handleResponse(any(CheckResponse.class), any(Metadata.class));
  }

  @Test
  public void onStreaming_allowResponse() throws InterruptedException, ExtAuthzParseException {
    ExtAuthzConfig config = buildExtAuthzConfig(false, false, 403);
    MethodDescriptor<SimpleRequest, SimpleResponse> streamingMethod =
        SimpleServiceGrpc.getBidiStreamingRpcMethod();
    CheckRequest checkRequest = CheckRequest.newBuilder()
        .setAttributes(AttributeContext.newBuilder()
            .setRequest(Request.newBuilder()
                .setHttp(HttpRequest.newBuilder().setId("RequestId").build()).build())
            .build())
        .build();
    when(checkRequestBuilder.buildRequest(eq(streamingMethod), any(Metadata.class),
        any(Timestamp.class))).thenReturn(checkRequest);

    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(com.google.rpc.Status.newBuilder().setCode(Status.Code.OK.value()).build())
        .setOkResponse(OkHttpResponse.getDefaultInstance()).build();
    doAnswer((Answer<Void>) invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onNext(checkResponse);
      observer.onCompleted();
      return null;
    }).when(authzService).check(eq(checkRequest),
        ArgumentMatchers.<StreamObserver<CheckResponse>>any());

    Metadata metadata = new Metadata();
    metadata.put(Metadata.Key.of("key1", Metadata.ASCII_STRING_MARSHALLER), "value1");
    ResponseHeaderMutations responseHeaderMutations =
        ResponseHeaderMutations.create(ImmutableList.of());
    AuthzResponse allowResponse =
        AuthzResponse.allow(metadata).setResponseHeaderMutations(responseHeaderMutations).build();
    when(responseHandler.handleResponse(eq(checkResponse), any(Metadata.class)))
        .thenReturn(allowResponse);

    doAnswer((Answer<Void>) invocation -> {
      Metadata headers = invocation.getArgument(1);
      headers.put(Metadata.Key.of("key2", Metadata.ASCII_STRING_MARSHALLER), "value2");
      return null;
    }).when(headerMutator).applyResponseMutations(eq(responseHeaderMutations), any(Metadata.class));

    ClientCall<SimpleRequest, SimpleResponse> realCall =
        channel.newCall(streamingMethod, CallOptions.DEFAULT);
    AuthorizationGrpc.AuthorizationStub authzStub = AuthorizationGrpc.newStub(channel);
    ClientCall<SimpleRequest, SimpleResponse> call =
        BufferingAuthzClientCall.FACTORY_INSTANCE.create(realCall, config, authzStub,
            checkRequestBuilder, responseHandler, headerMutator, streamingMethod, new CallBuffer());
    SimpleServiceStreamingResponseObserver simpleServiceResponseObserver =
        new SimpleServiceStreamingResponseObserver();
    StreamObserver<SimpleRequest> requestObserver =
        ClientCalls.asyncBidiStreamingCall(call, simpleServiceResponseObserver);
    requestObserver.onNext(SimpleRequest.newBuilder().setRequestMessage("World").build());
    requestObserver.onNext(SimpleRequest.newBuilder().setRequestMessage("gRPC").build());
    requestObserver.onCompleted();
    simpleServiceResponseObserver.await();

    assertThat(simpleServiceResponseObserver.getResponses())
        .containsExactly(SimpleResponse.newBuilder().setResponseMessage("Hello World").build(),
            SimpleResponse.newBuilder().setResponseMessage("Hello gRPC").build())
        .inOrder();
    assertThat(
        serverHeadersCapture.get().get(Metadata.Key.of("key1", Metadata.ASCII_STRING_MARSHALLER)))
            .isEqualTo("value1");
    assertThat(
        clientHeadersCapture.get().get(Metadata.Key.of("key2", Metadata.ASCII_STRING_MARSHALLER)))
            .isEqualTo("value2");
    assertThat(clientTrailersCapture.get()).isNotNull();
    verify(headerMutator).applyResponseMutations(eq(responseHeaderMutations), any(Metadata.class));
  }

  @Test
  public void onStreaming_denyResponse() throws InterruptedException, ExtAuthzParseException {
    ExtAuthzConfig config = buildExtAuthzConfig(false, false, 403);
    MethodDescriptor<SimpleRequest, SimpleResponse> streamingMethod =
        SimpleServiceGrpc.getBidiStreamingRpcMethod();
    CheckRequest checkRequest = CheckRequest.newBuilder().build();
    when(checkRequestBuilder.buildRequest(eq(streamingMethod), any(Metadata.class),
        any(Timestamp.class))).thenReturn(checkRequest);

    CheckResponse checkResponse = CheckResponse.newBuilder().setStatus(
        com.google.rpc.Status.newBuilder().setCode(Status.Code.PERMISSION_DENIED.value()).build())
        .build();
    doAnswer((Answer<Void>) invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onNext(checkResponse);
      observer.onCompleted();
      return null;
    }).when(authzService).check(eq(checkRequest),
        ArgumentMatchers.<StreamObserver<CheckResponse>>any());

    Status expectedStatus = Status.PERMISSION_DENIED.withDescription("ext authz denied");
    AuthzResponse denyResponse = AuthzResponse.deny(expectedStatus).build();
    when(responseHandler.handleResponse(eq(checkResponse), any(Metadata.class)))
        .thenReturn(denyResponse);

    ClientCall<SimpleRequest, SimpleResponse> realCall =
        channel.newCall(streamingMethod, CallOptions.DEFAULT);
    AuthorizationGrpc.AuthorizationStub authzStub = AuthorizationGrpc.newStub(channel);
    CallBuffer callBuffer = new CallBuffer();
    ClientCall<SimpleRequest, SimpleResponse> call =
        BufferingAuthzClientCall.FACTORY_INSTANCE.create(realCall, config, authzStub,
            checkRequestBuilder, responseHandler, headerMutator, streamingMethod, callBuffer);
    SimpleServiceStreamingResponseObserver simpleServiceResponseObserver =
        new SimpleServiceStreamingResponseObserver();
    StreamObserver<SimpleRequest> requestObserver =
        ClientCalls.asyncBidiStreamingCall(call, simpleServiceResponseObserver);
    requestObserver.onNext(SimpleRequest.newBuilder().setRequestMessage("World").build());
    requestObserver.onNext(SimpleRequest.newBuilder().setRequestMessage("gRPC").build());
    requestObserver.onCompleted();
    simpleServiceResponseObserver.await();

    assertThat(simpleServiceResponseObserver.getResponses()).isEmpty();
    assertThat(simpleServiceResponseObserver.getError()).isNotNull();
    assertThat(simpleServiceResponseObserver.getError().getCode())
        .isEqualTo(expectedStatus.getCode());
    assertThat(simpleServiceResponseObserver.getError().getDescription())
        .isEqualTo(expectedStatus.getDescription());
    assertThat(callBuffer.isProcessed()).isTrue();
  }

  private ExtAuthzConfig buildExtAuthzConfig(boolean failureModeAllow,
      boolean failureModeAllowHeaderAdd, int httpStatusOnError) throws ExtAuthzParseException {
    Any googleDefaultChannelCreds = Any.pack(GoogleDefaultCredentials.newBuilder().build());
    Any fakeAccessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("fake-token").build());
    ExtAuthz.Builder builder = ExtAuthz.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("test-cluster").addChannelCredentialsPlugin(googleDefaultChannelCreds)
                .addCallCredentialsPlugin(fakeAccessTokenCreds).build())
            .build())
        .setFailureModeAllow(failureModeAllow)
        .setFailureModeAllowHeaderAdd(failureModeAllowHeaderAdd)
        .setStatusOnError(io.envoyproxy.envoy.type.v3.HttpStatus.newBuilder()
            .setCodeValue(httpStatusOnError).build())
        .setIncludePeerCertificate(true);
    return ExtAuthzConfig.fromProto(builder.build());
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

    final AtomicReference<Metadata> headersCapture;

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

  private static class SimpleServiceImpl extends SimpleServiceGrpc.SimpleServiceImplBase {
    @Override
    public void unaryRpc(SimpleRequest request, StreamObserver<SimpleResponse> streamObserver) {
      streamObserver.onNext(SimpleResponse.newBuilder()
          .setResponseMessage("Hello " + request.getRequestMessage()).build());
      streamObserver.onCompleted();
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
  }
}
