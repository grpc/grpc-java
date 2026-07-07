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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.grpc.xds.internal.extauthz.ExtAuthzConfig;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfig;
import io.grpc.xds.internal.headermutations.HeaderMutator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Unit tests for {@link ExtAuthzFilter} and its nested static {@code ExtAuthzClientInterceptor}.
 */
@RunWith(JUnit4.class)
public class ExtAuthzFilterTest {

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  @Rule
  public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  @Mock
  private ThreadSafeRandom mockRandom;
  @Mock
  private AuthorizationGrpc.AuthorizationImplBase mockAuthzService;

  private final HeaderMutator headerMutator = HeaderMutator.create();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor();

  private ManagedChannel channel;
  private CallOptions callOptions =
      CallOptions.DEFAULT.withExecutor(MoreExecutors.directExecutor());
  private volatile Metadata lastBackendHeaders;

  private ExtAuthzFilter filter;
  private ExtAuthzConfig extAuthzConfig;

  @Before
  public void setUp() throws Exception {
    // Single in-process server hosting both authz and backend services
    String serverName = InProcessServerBuilder.generateName();
    grpcCleanup.register(InProcessServerBuilder
        .forName(serverName)
        .directExecutor()
        .intercept(new io.grpc.ServerInterceptor() {
          @Override
          public <ReqT, RespT> io.grpc.ServerCall.Listener<ReqT> interceptCall(
              io.grpc.ServerCall<ReqT, RespT> call, Metadata headers,
              ServerCallHandler<ReqT, RespT> next) {
            lastBackendHeaders = headers;
            return next.startCall(call, headers);
          }
        })
        .addService(mockAuthzService)
        .addService(ServerServiceDefinition.builder(
            SimpleServiceGrpc.getUnaryRpcMethod().getServiceName())
            .addMethod(SimpleServiceGrpc.getUnaryRpcMethod(),
                (ServerCallHandler<SimpleRequest, SimpleResponse>) (call, headers) -> {
                  call.sendHeaders(new Metadata());
                  call.sendMessage(SimpleResponse.getDefaultInstance());
                  call.close(Status.OK, new Metadata());
                  return new io.grpc.ServerCall.Listener<SimpleRequest>() {};
                })
            .build())
        .build()
        .start());
    channel = grpcCleanup.register(InProcessChannelBuilder
        .forName(serverName)
        .directExecutor()
        .build());

    ExtAuthzFilter.ChannelProvider channelProvider =
        new ExtAuthzFilter.ChannelProvider() {
          @Override
          public ManagedChannel getChannel(GrpcServiceConfig config) {
            return channel;
          }

          @Override
          public void close() {
            channel.shutdownNow();
          }
        };

    filter = new ExtAuthzFilter(channelProvider, mockRandom, headerMutator);
  }

  private ExtAuthzConfig buildExtAuthzConfig() {
    return buildExtAuthzConfig(100);
  }

  private ExtAuthzConfig buildExtAuthzConfig(int percent) {
    GrpcServiceConfig.GoogleGrpcConfig googleGrpc = GrpcServiceConfig.GoogleGrpcConfig.builder()
        .target("test-cluster")
        .configuredChannelCredentials(io.grpc.xds.client.ConfiguredChannelCredentials.create(
            mock(io.grpc.ChannelCredentials.class),
            mock(io.grpc.xds.client.ConfiguredChannelCredentials.ChannelCredsConfig.class)))
        .build();

    GrpcServiceConfig dummyServiceConfig = GrpcServiceConfig.builder()
        .googleGrpc(googleGrpc)
        .initialMetadata(ImmutableList.of())
        .build();

    return ExtAuthzConfig.builder()
        .grpcService(dummyServiceConfig)
        .includePeerCertificate(true)
        .allowedHeaders(ImmutableList.of())
        .disallowedHeaders(ImmutableList.of())
        .failureModeAllow(true)
        .failureModeAllowHeaderAdd(false)
        .denyAtDisable(false)
        .filterEnabled(io.grpc.xds.internal.Matchers.FractionMatcher.create(percent, 100))
        .statusOnError(Status.INTERNAL)
        .build();
  }

  @Test
  public void buildClientInterceptor_success() {
    extAuthzConfig = buildExtAuthzConfig();
    ExtAuthzFilter.ExtAuthzFilterConfig filterConfig =
        new ExtAuthzFilter.ExtAuthzFilterConfig(extAuthzConfig);
    ClientInterceptor created = filter.buildClientInterceptor(filterConfig, null, scheduler);
    assertThat(created).isNotNull();
  }

  @Test
  public void buildClientInterceptor_withTimeout_appliesDeadline() {
    GrpcServiceConfig.GoogleGrpcConfig googleGrpc = GrpcServiceConfig.GoogleGrpcConfig.builder()
        .target("test-cluster")
        .configuredChannelCredentials(io.grpc.xds.client.ConfiguredChannelCredentials.create(
            mock(io.grpc.ChannelCredentials.class),
            mock(io.grpc.xds.client.ConfiguredChannelCredentials.ChannelCredsConfig.class)))
        .build();
    GrpcServiceConfig serviceConfigWithTimeout = GrpcServiceConfig.builder()
        .googleGrpc(googleGrpc)
        .initialMetadata(ImmutableList.of())
        .timeout(java.time.Duration.ofSeconds(5))
        .build();
    ExtAuthzConfig configWithTimeout = ExtAuthzConfig.builder()
        .grpcService(serviceConfigWithTimeout)
        .includePeerCertificate(true)
        .allowedHeaders(ImmutableList.of())
        .disallowedHeaders(ImmutableList.of())
        .failureModeAllow(true)
        .failureModeAllowHeaderAdd(false)
        .denyAtDisable(false)
        .filterEnabled(io.grpc.xds.internal.Matchers.FractionMatcher.create(100, 100))
        .statusOnError(Status.INTERNAL)
        .build();

    ExtAuthzFilter.ExtAuthzFilterConfig filterConfig =
        new ExtAuthzFilter.ExtAuthzFilterConfig(configWithTimeout);
    ClientInterceptor created = filter.buildClientInterceptor(filterConfig, null, scheduler);
    assertThat(created).isInstanceOf(ExtAuthzFilter.ExtAuthzClientInterceptor.class);
    ExtAuthzFilter.ExtAuthzClientInterceptor interceptor =
        (ExtAuthzFilter.ExtAuthzClientInterceptor) created;
    assertThat(interceptor.getAuthzStubForTest().getCallOptions().getDeadline()).isNotNull();
  }

  @Test
  public void buildClientInterceptor_withOverride_returnsNull() {
    extAuthzConfig = buildExtAuthzConfig();
    ClientInterceptor interceptor =
        filter.buildClientInterceptor(new ExtAuthzFilter.ExtAuthzFilterConfig(extAuthzConfig),
            new ExtAuthzFilter.ExtAuthzFilterConfigOverride(), scheduler);
    assertThat(interceptor).isNull();
  }

  @Test
  public void buildClientInterceptor_wrongConfigType_returnsNull() {
    ClientInterceptor interceptor =
        filter.buildClientInterceptor(mock(Filter.FilterConfig.class), null, scheduler);
    assertThat(interceptor).isNull();
  }

  @Test
  public void close_shouldShutdownChannel() {
    extAuthzConfig = buildExtAuthzConfig();
    filter.buildClientInterceptor(
        new ExtAuthzFilter.ExtAuthzFilterConfig(extAuthzConfig), null, scheduler);
    filter.close();
    assertThat(channel.isShutdown()).isTrue();
  }

  @Test
  public void buildClientInterceptor_withCallCredentials_appliesCallCredentials() {
    io.grpc.CallCredentials fakeCallCreds = mock(io.grpc.CallCredentials.class);
    GrpcServiceConfig.GoogleGrpcConfig googleGrpc = GrpcServiceConfig.GoogleGrpcConfig.builder()
        .target("test-cluster")
        .configuredChannelCredentials(io.grpc.xds.client.ConfiguredChannelCredentials.create(
            mock(io.grpc.ChannelCredentials.class),
            mock(io.grpc.xds.client.ConfiguredChannelCredentials.ChannelCredsConfig.class)))
        .callCredentials(fakeCallCreds)
        .build();
    GrpcServiceConfig serviceConfig = GrpcServiceConfig.builder()
        .googleGrpc(googleGrpc)
        .initialMetadata(ImmutableList.of())
        .build();
    ExtAuthzConfig configWithCreds = ExtAuthzConfig.builder()
        .grpcService(serviceConfig)
        .includePeerCertificate(true)
        .allowedHeaders(ImmutableList.of())
        .disallowedHeaders(ImmutableList.of())
        .failureModeAllow(true)
        .failureModeAllowHeaderAdd(false)
        .denyAtDisable(false)
        .filterEnabled(io.grpc.xds.internal.Matchers.FractionMatcher.create(100, 100))
        .statusOnError(Status.INTERNAL)
        .build();

    ExtAuthzFilter.ExtAuthzFilterConfig filterConfig =
        new ExtAuthzFilter.ExtAuthzFilterConfig(configWithCreds);
    ClientInterceptor created = filter.buildClientInterceptor(filterConfig, null, scheduler);
    assertThat(created).isInstanceOf(ExtAuthzFilter.ExtAuthzClientInterceptor.class);
    ExtAuthzFilter.ExtAuthzClientInterceptor interceptor =
        (ExtAuthzFilter.ExtAuthzClientInterceptor) created;
    assertThat(interceptor.getAuthzStubForTest().getCallOptions().getCredentials())
        .isSameInstanceAs(fakeCallCreds);
  }

  // ==========================================================================
  // ExtAuthzClientInterceptor Specific Unit Tests
  // ==========================================================================

  @Test
  public void interceptCall_denyAtDisable_failsWithConfiguredStatus() {
    when(mockRandom.nextInt(100)).thenReturn(50);
    ExtAuthzConfig config = ExtAuthzConfig.builder()
        .grpcService(buildExtAuthzConfig().grpcService())
        .includePeerCertificate(true)
        .allowedHeaders(ImmutableList.of())
        .disallowedHeaders(ImmutableList.of())
        .failureModeAllow(true)
        .failureModeAllowHeaderAdd(false)
        .denyAtDisable(true)
        .filterEnabled(io.grpc.xds.internal.Matchers.FractionMatcher.create(0, 100))
        .statusOnError(Status.PERMISSION_DENIED)
        .build();

    ExtAuthzFilter.ExtAuthzFilterConfig filterConfig =
        new ExtAuthzFilter.ExtAuthzFilterConfig(config);
    ClientInterceptor interceptor = filter.buildClientInterceptor(filterConfig, null, scheduler);
    assertThat(interceptor).isNotNull();

    ClientCall<SimpleRequest, SimpleResponse> call = interceptor.interceptCall(
        SimpleServiceGrpc.getUnaryRpcMethod(), callOptions, channel);
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    call.start(listener, new Metadata());

    // Verify the call delivers the configured status to the listener
    assertThat(listener.closeStatus).isNotNull();
    assertThat(listener.closeStatus.getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);

    // Verify the authz service was NEVER contacted (filter was disabled)
    verify(mockAuthzService, never()).check(any(CheckRequest.class), any());
  }

  @Test
  public void interceptCall_filterDisabled_delegatesToBackend() {
    when(mockRandom.nextInt(100)).thenReturn(50);
    ExtAuthzConfig config = ExtAuthzConfig.builder()
        .grpcService(buildExtAuthzConfig().grpcService())
        .includePeerCertificate(true)
        .allowedHeaders(ImmutableList.of())
        .disallowedHeaders(ImmutableList.of())
        .failureModeAllow(true)
        .failureModeAllowHeaderAdd(false)
        .denyAtDisable(false)
        .filterEnabled(io.grpc.xds.internal.Matchers.FractionMatcher.create(0, 100))
        .statusOnError(Status.INTERNAL)
        .build();

    ExtAuthzFilter.ExtAuthzFilterConfig filterConfig =
        new ExtAuthzFilter.ExtAuthzFilterConfig(config);
    ClientInterceptor interceptor = filter.buildClientInterceptor(filterConfig, null, scheduler);
    assertThat(interceptor).isNotNull();

    // Drive a real RPC through the interceptor — filter is disabled
    ClientCall<SimpleRequest, SimpleResponse> call = interceptor
        .interceptCall(SimpleServiceGrpc.getUnaryRpcMethod(), callOptions, channel);
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    call.start(listener, new Metadata());
    call.halfClose();
    call.request(1);

    // Assert: RPC completes successfully (reaches backend, bypasses authz)
    assertThat(listener.closeStatus).isNotNull();
    assertThat(listener.closeStatus.getCode()).isEqualTo(Status.Code.OK);

    // Assert: Authz service was NEVER contacted
    verify(mockAuthzService, never()).check(any(CheckRequest.class), any());
  }

  @Test
  public void interceptCall_filterEnabled_authzAllows_rpcSucceeds() {
    when(mockRandom.nextInt(100)).thenReturn(50);
    extAuthzConfig = buildExtAuthzConfig();

    // Configure mock authz service to return ALLOW (status code 0 = OK)
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onNext(CheckResponse.newBuilder()
          .setStatus(com.google.rpc.Status.newBuilder().setCode(com.google.rpc.Code.OK_VALUE))
          .build());
      observer.onCompleted();
      return null;
    }).when(mockAuthzService).check(any(CheckRequest.class), any());

    // Use buildClientInterceptor which creates real internal components
    ExtAuthzFilter.ExtAuthzFilterConfig filterConfig =
        new ExtAuthzFilter.ExtAuthzFilterConfig(extAuthzConfig);
    ClientInterceptor interceptor = filter.buildClientInterceptor(filterConfig, null, scheduler);
    assertThat(interceptor).isNotNull();

    ClientCall<SimpleRequest, SimpleResponse> call = interceptor.interceptCall(
        SimpleServiceGrpc.getUnaryRpcMethod(), callOptions, channel);
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    call.start(listener, new Metadata());
    call.halfClose();
    call.request(1);

    // Assert: RPC completes successfully through authz + backend
    assertThat(listener.closeStatus).isNotNull();
    assertThat(listener.closeStatus.getCode()).isEqualTo(Status.Code.OK);

    // Assert: Authz service WAS contacted (filter is active)
    verify(mockAuthzService).check(any(CheckRequest.class), any());
  }

  @Test
  public void interceptCall_filterEnabled_authzDenies_rpcFails() {
    when(mockRandom.nextInt(100)).thenReturn(50);
    extAuthzConfig = buildExtAuthzConfig();

    // Configure mock authz service to return DENY (non-OK status)
    doAnswer(invocation -> {
      StreamObserver<CheckResponse> observer = invocation.getArgument(1);
      observer.onNext(CheckResponse.newBuilder().setStatus(
          com.google.rpc.Status.newBuilder().setCode(com.google.rpc.Code.PERMISSION_DENIED_VALUE))
          .build());
      observer.onCompleted();
      return null;
    }).when(mockAuthzService).check(any(CheckRequest.class), any());

    // Use buildClientInterceptor which creates real internal components
    ExtAuthzFilter.ExtAuthzFilterConfig filterConfig =
        new ExtAuthzFilter.ExtAuthzFilterConfig(extAuthzConfig);
    ClientInterceptor interceptor = filter.buildClientInterceptor(filterConfig, null, scheduler);
    assertThat(interceptor).isNotNull();

    ClientCall<SimpleRequest, SimpleResponse> call = interceptor.interceptCall(
        SimpleServiceGrpc.getUnaryRpcMethod(), callOptions, channel);
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    call.start(listener, new Metadata());

    // Assert: RPC fails with PERMISSION_DENIED
    assertThat(listener.closeStatus).isNotNull();
    assertThat(listener.closeStatus.getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);

    // Assert: Authz service WAS contacted
    verify(mockAuthzService).check(any(CheckRequest.class), any());
  }

  @Test
  public void interceptCall_nullExecutor_failsWithInternal() {
    when(mockRandom.nextInt(100)).thenReturn(50);
    extAuthzConfig = buildExtAuthzConfig();

    // Use buildClientInterceptor which creates real internal components
    ExtAuthzFilter.ExtAuthzFilterConfig filterConfig =
        new ExtAuthzFilter.ExtAuthzFilterConfig(extAuthzConfig);
    ClientInterceptor interceptor = filter.buildClientInterceptor(filterConfig, null, scheduler);
    assertThat(interceptor).isNotNull();

    ClientCall<SimpleRequest, SimpleResponse> call = interceptor.interceptCall(
        SimpleServiceGrpc.getUnaryRpcMethod(), CallOptions.DEFAULT, channel);
    CapturingListener<SimpleResponse> listener = new CapturingListener<>();
    call.start(listener, new Metadata());
    assertThat(listener.closeStatus).isNotNull();
    assertThat(listener.closeStatus.getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(listener.closeStatus.getDescription()).contains("No executor provided");
  }

  // ==========================================================================
  // Provider.parseFilterConfig / parseFilterConfigOverride Tests
  // ==========================================================================

  private static io.grpc.xds.client.Bootstrapper.BootstrapInfo dummyBootstrapInfo() {
    io.grpc.xds.client.Bootstrapper.ServerInfo serverInfo = dummyServerInfo();
    return io.grpc.xds.client.Bootstrapper.BootstrapInfo.builder()
        .servers(java.util.Collections.singletonList(serverInfo))
        .node(io.grpc.xds.client.EnvoyProtoData.Node.newBuilder().build())
        .build();
  }

  private static io.grpc.xds.client.Bootstrapper.ServerInfo dummyServerInfo() {
    return io.grpc.xds.client.Bootstrapper.ServerInfo.create(
        "test_target", java.util.Collections.emptyMap(), false, true, false, false);
  }

  private static Filter.FilterConfigParseContext dummyParseContext() {
    return Filter.FilterConfigParseContext.builder()
        .bootstrapInfo(dummyBootstrapInfo())
        .serverInfo(dummyServerInfo())
        .build();
  }

  @Test
  public void parseFilterConfig_validProto_returnsConfig() {
    io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz extAuthzProto =
        io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz.newBuilder()
            .setGrpcService(
                io.envoyproxy.envoy.config.core.v3.GrpcService.newBuilder()
                    .setGoogleGrpc(
                        io.envoyproxy.envoy.config.core.v3.GrpcService.GoogleGrpc.newBuilder()
                            .setTargetUri("authz-cluster")
                            .addChannelCredentialsPlugin(
                                com.google.protobuf.Any.pack(
                                    io.envoyproxy.envoy.extensions.grpc_service
                                        .channel_credentials.google_default.v3
                                        .GoogleDefaultCredentials.newBuilder().build()))))
            .build();
    com.google.protobuf.Any anyMessage =
        com.google.protobuf.Any.pack(extAuthzProto);

    ExtAuthzFilter.Provider provider = new ExtAuthzFilter.Provider();
    ConfigOrError<?> result =
        provider.parseFilterConfig(anyMessage, dummyParseContext());

    assertThat(result.errorDetail).isNull();
    assertThat(result.config)
        .isInstanceOf(ExtAuthzFilter.ExtAuthzFilterConfig.class);
  }

  @Test
  public void parseFilterConfig_invalidProto_returnsError() {
    com.google.protobuf.Any anyMessage = com.google.protobuf.Any.newBuilder()
        .setTypeUrl(
            "type.googleapis.com/envoy.extensions.filters.http.ext_authz.v3.ExtAuthz")
        .setValue(com.google.protobuf.ByteString.copyFromUtf8("not-valid-proto"))
        .build();

    ExtAuthzFilter.Provider provider = new ExtAuthzFilter.Provider();
    ConfigOrError<?> result =
        provider.parseFilterConfig(anyMessage, dummyParseContext());

    assertThat(result.errorDetail).isNotNull();
    assertThat(result.errorDetail).contains("Invalid proto");
  }

  @Test
  public void parseFilterConfig_nonAnyMessage_returnsError() {
    com.google.protobuf.Message nonAnyMessage =
        io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz
            .getDefaultInstance();

    ExtAuthzFilter.Provider provider = new ExtAuthzFilter.Provider();
    ConfigOrError<?> result =
        provider.parseFilterConfig(nonAnyMessage, dummyParseContext());

    assertThat(result.errorDetail).isNotNull();
    assertThat(result.errorDetail).contains("Invalid config type");
  }

  @Test
  public void parseFilterConfigOverride_validAny_returnsOverride() {
    com.google.protobuf.Any anyMessage = com.google.protobuf.Any.pack(
        io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthzPerRoute
            .getDefaultInstance());

    ExtAuthzFilter.Provider provider = new ExtAuthzFilter.Provider();
    ConfigOrError<?> result =
        provider.parseFilterConfigOverride(anyMessage, dummyParseContext());

    assertThat(result.errorDetail).isNull();
    assertThat(result.config)
        .isInstanceOf(ExtAuthzFilter.ExtAuthzFilterConfigOverride.class);
  }

  @Test
  public void parseFilterConfigOverride_nonAnyMessage_returnsError() {
    com.google.protobuf.Message nonAnyMessage =
        io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthzPerRoute
            .getDefaultInstance();

    ExtAuthzFilter.Provider provider = new ExtAuthzFilter.Provider();
    ConfigOrError<?> result =
        provider.parseFilterConfigOverride(nonAnyMessage, dummyParseContext());

    assertThat(result.errorDetail).isNotNull();
    assertThat(result.errorDetail).contains("Invalid config type");
  }

  private static class CapturingListener<T> extends ClientCall.Listener<T> {
    volatile Status closeStatus;

    @Override
    public void onClose(Status status, Metadata trailers) {
      this.closeStatus = status;
    }
  }
}
