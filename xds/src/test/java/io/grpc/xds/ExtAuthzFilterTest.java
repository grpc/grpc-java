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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.Any;
import com.google.protobuf.Empty;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz;
import io.envoyproxy.envoy.extensions.grpc_service.call_credentials.access_token.v3.AccessTokenCredentials;
import io.envoyproxy.envoy.extensions.grpc_service.channel_credentials.google_default.v3.GoogleDefaultCredentials;
import io.envoyproxy.envoy.extensions.grpc_service.channel_credentials.insecure.v3.InsecureCredentials;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.ServerInterceptor;
import io.grpc.xds.ExtAuthzFilter.ExtAuthzFilterConfig;
import io.grpc.xds.ExtAuthzFilter.ExtAuthzFilterConfigOverride;
import io.grpc.xds.internal.extauthz.BufferingAuthzClientCall;
import io.grpc.xds.internal.extauthz.CheckRequestBuilder;
import io.grpc.xds.internal.extauthz.CheckResponseHandler;
import io.grpc.xds.internal.extauthz.ExtAuthzCertificateProvider;
import io.grpc.xds.internal.extauthz.ExtAuthzClientInterceptor;
import io.grpc.xds.internal.extauthz.ExtAuthzConfig;
import io.grpc.xds.internal.extauthz.ExtAuthzParseException;
import io.grpc.xds.internal.extauthz.ExtAuthzServerInterceptor;
import io.grpc.xds.internal.extauthz.StubManager;
import io.grpc.xds.internal.headermutations.HeaderMutationFilter;
import io.grpc.xds.internal.headermutations.HeaderMutator;
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
 * Unit tests for {@link ExtAuthzFilter}.
 */
@RunWith(JUnit4.class)
public class ExtAuthzFilterTest {

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  private StubManager mockStubManager;
  @Mock
  private ThreadSafeRandom mockRandom;
  @Mock
  private BufferingAuthzClientCall.Factory mockBufferingAuthzClientCallFactory;
  @Mock
  private ExtAuthzCertificateProvider mockCertificateProvider;
  @Mock
  private CheckRequestBuilder.Factory mockCheckRequestBuilderFactory;
  @Mock
  private CheckRequestBuilder mockCheckRequestBuilder;
  @Mock
  private CheckResponseHandler.Factory mockCheckResponseHandlerFactory;
  @Mock
  private CheckResponseHandler mockCheckResponseHandler;
  @Mock
  private ExtAuthzClientInterceptor.Factory mockClientInterceptorFactory;
  @Mock
  private ExtAuthzServerInterceptor.Factory mockServerInterceptorFactory;
  @Mock
  private HeaderMutationFilter.Factory mockHeaderMutationFilterFactory;
  @Mock
  private HeaderMutationFilter mockHeaderMutationFilter;
  @Mock
  private HeaderMutator mockHeaderMutator;
  @Mock
  private ManagedChannel mockChannel;
  @Mock
  private ScheduledExecutorService mockScheduler;

  private ExtAuthzFilter filter;
  private final ExtAuthzFilter.Provider provider = new ExtAuthzFilter.Provider();
  private ExtAuthzConfig extAuthzConfig;
  private AuthorizationGrpc.AuthorizationStub authzStub;

  @Before
  public void setUp() {
    authzStub = AuthorizationGrpc.newStub(mockChannel);
    filter = new ExtAuthzFilter(mockStubManager, mockRandom,
            mockBufferingAuthzClientCallFactory, mockCertificateProvider,
        mockCheckRequestBuilderFactory, mockCheckResponseHandlerFactory,
        mockClientInterceptorFactory, mockServerInterceptorFactory, mockHeaderMutationFilterFactory,
        mockHeaderMutator);
  }

  private ExtAuthzConfig buildExtAuthzConfig() throws ExtAuthzParseException {
    ExtAuthz extAuthz = ExtAuthz.newBuilder()
        .setGrpcService(GrpcService.newBuilder()
            .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder().setTargetUri("authz.service.com")
                .addChannelCredentialsPlugin(Any.pack(InsecureCredentials.newBuilder().build()))
                .addCallCredentialsPlugin(
                    Any.pack(AccessTokenCredentials.newBuilder().setToken("fake-token").build()))
                .build())
            .build())
        .build();
    return ExtAuthzConfig.fromProto(extAuthz);
  }

  @Test
  public void buildClientInterceptor_success() throws ExtAuthzParseException {
    extAuthzConfig = buildExtAuthzConfig();
    ExtAuthzFilterConfig filterConfig = new ExtAuthzFilterConfig(extAuthzConfig);
    when(mockStubManager.getStub(extAuthzConfig)).thenReturn(authzStub);
    when(mockCheckRequestBuilderFactory.create(extAuthzConfig, mockCertificateProvider))
        .thenReturn(mockCheckRequestBuilder);
    when(mockHeaderMutationFilterFactory.create(any())).thenReturn(mockHeaderMutationFilter);
    when(mockCheckResponseHandlerFactory.create(mockHeaderMutator, mockHeaderMutationFilter,
        extAuthzConfig)).thenReturn(mockCheckResponseHandler);
    ExtAuthzClientInterceptor interceptor =
        (ExtAuthzClientInterceptor) ExtAuthzClientInterceptor.INSTANCE.create(null, null, null,
            null, null, null, null);
    when(mockClientInterceptorFactory.create(extAuthzConfig, authzStub, mockRandom,
            mockBufferingAuthzClientCallFactory, mockCheckRequestBuilder, mockCheckResponseHandler,
        mockHeaderMutator)).thenReturn(interceptor);

    ClientInterceptor created = filter.buildClientInterceptor(filterConfig, null, mockScheduler);
    assertThat(created).isSameInstanceAs(interceptor);
  }

  @Test
  public void buildClientInterceptor_withOverride_returnsNull() throws ExtAuthzParseException {
    extAuthzConfig = buildExtAuthzConfig();
    ClientInterceptor interceptor =
        filter.buildClientInterceptor(new ExtAuthzFilterConfig(extAuthzConfig),
                new ExtAuthzFilterConfigOverride(), mockScheduler);
    assertThat(interceptor).isNull();
  }

  @Test
  public void buildClientInterceptor_wrongConfigType_returnsNull() {
    ClientInterceptor interceptor =
        filter.buildClientInterceptor(mock(Filter.FilterConfig.class), null, mockScheduler);
    assertThat(interceptor).isNull();
  }

  @Test
  public void buildServerInterceptor_success() throws ExtAuthzParseException {
    extAuthzConfig = buildExtAuthzConfig();
    ExtAuthzFilterConfig filterConfig = new ExtAuthzFilterConfig(extAuthzConfig);
    when(mockStubManager.getStub(extAuthzConfig)).thenReturn(authzStub);
    when(mockCheckRequestBuilderFactory.create(extAuthzConfig, mockCertificateProvider))
        .thenReturn(mockCheckRequestBuilder);
    when(mockHeaderMutationFilterFactory.create(any())).thenReturn(mockHeaderMutationFilter);
    when(mockCheckResponseHandlerFactory.create(mockHeaderMutator, mockHeaderMutationFilter,
        extAuthzConfig)).thenReturn(mockCheckResponseHandler);
    ExtAuthzServerInterceptor interceptor =
        (ExtAuthzServerInterceptor) ExtAuthzServerInterceptor.INSTANCE.create(null, null, null,
            null, null, null);
    when(mockServerInterceptorFactory.create(extAuthzConfig, authzStub, mockRandom,
            mockCheckRequestBuilder, mockCheckResponseHandler, mockHeaderMutator))
            .thenReturn(interceptor);

    ServerInterceptor created = filter.buildServerInterceptor(filterConfig, null);
    assertThat(created).isSameInstanceAs(interceptor);
  }

  @Test
  public void buildServerInterceptor_withOverride_returnsNull() throws ExtAuthzParseException {
    extAuthzConfig = buildExtAuthzConfig();
    ServerInterceptor interceptor = filter.buildServerInterceptor(
        new ExtAuthzFilterConfig(extAuthzConfig), new ExtAuthzFilterConfigOverride());
    assertThat(interceptor).isNull();
  }

  @Test
  public void buildServerInterceptor_wrongConfigType_returnsNull() {
    ServerInterceptor interceptor =
        filter.buildServerInterceptor(mock(Filter.FilterConfig.class), null);
    assertThat(interceptor).isNull();
  }

  @Test
  public void close_shouldCloseStubManager() {
    filter.close();
    verify(mockStubManager).close();
  }

  @Test
  public void provider_typeUrls() {
    assertThat(provider.typeUrls()).asList().containsExactly(
        "type.googleapis.com/envoy.extensions.filters.http.ext_authz.v3.ExtAuthz",
        "type.googleapis.com/envoy.extensions.filters.http.ext_authz.v3.ExtAuthzPerRoute");
  }

  @Test
  public void provider_isClientAndServerFilter() {
    assertThat(provider.isClientFilter()).isTrue();
    assertThat(provider.isServerFilter()).isTrue();
  }

  @Test
  public void provider_newInstance() {
    ExtAuthzFilter instance = provider.newInstance("test-filter");
    assertThat(instance).isNotNull();
  }

  @Test
  public void provider_parseFilterConfig_success() {

    Any googleDefaultChannelCreds = Any.pack(GoogleDefaultCredentials.newBuilder().build());
    Any fakeAccessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("fake-token").build());
    ExtAuthz extAuthz = ExtAuthz.newBuilder()
        .setGrpcService(io.envoyproxy.envoy.config.core.v3.GrpcService.newBuilder()
            .setGoogleGrpc(io.envoyproxy.envoy.config.core.v3.GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("authz.service.com")
                .addChannelCredentialsPlugin(googleDefaultChannelCreds)
                .addCallCredentialsPlugin(fakeAccessTokenCreds).build())
            .build())
        .setStatusOnError(
            io.envoyproxy.envoy.type.v3.HttpStatus.newBuilder().setCodeValue(403).build())
        .build();
    Any anyProto = Any.pack(extAuthz);
    ConfigOrError<ExtAuthzFilterConfig> result = provider.parseFilterConfig(anyProto);
    assertThat(result.config).isNotNull();
    assertThat(result.errorDetail).isNull();
    assertThat(result.config.extAuthzConfig().grpcService().googleGrpc().target())
        .isEqualTo("authz.service.com");
  }

  @Test
  public void provider_parseFilterConfig_invalidProto() {
    Any anyProto = Any.pack(Empty.getDefaultInstance());

    ConfigOrError<ExtAuthzFilterConfig> result = provider.parseFilterConfig(anyProto);

    assertThat(result.config).isNull();
    assertThat(result.errorDetail).contains("Invalid proto");
  }

  @Test
  public void provider_parseFilterConfig_notAny() {
    ConfigOrError<ExtAuthzFilterConfig> result =
        provider.parseFilterConfig(Empty.getDefaultInstance());
    assertThat(result.config).isNull();
    assertThat(result.errorDetail).contains("Invalid config type");
  }

  @Test
  public void provider_parseFilterConfigOverride_success() {
    Any anyProto = Any.pack(ExtAuthz.getDefaultInstance());
    ConfigOrError<ExtAuthzFilterConfigOverride> result =
        provider.parseFilterConfigOverride(anyProto);
    assertThat(result.config).isNotNull();
    assertThat(result.errorDetail).isNull();
  }

  @Test
  public void provider_parseFilterConfigOverride_notAny() {
    ConfigOrError<ExtAuthzFilterConfigOverride> result =
        provider.parseFilterConfigOverride(Empty.getDefaultInstance());
    assertThat(result.config).isNull();
    assertThat(result.errorDetail).contains("Invalid config type");
  }

  @Test
  public void extAuthzFilterConfig_typeUrl() throws ExtAuthzParseException {
    extAuthzConfig = buildExtAuthzConfig();
    ExtAuthzFilterConfig config = new ExtAuthzFilterConfig(extAuthzConfig);
    assertThat(config.typeUrl())
        .isEqualTo("type.googleapis.com/envoy.extensions.filters.http.ext_authz.v3.ExtAuthz");
  }

  @Test
  public void extAuthzFilterConfigOverride_typeUrl() {
    ExtAuthzFilterConfigOverride override = new ExtAuthzFilterConfigOverride();
    assertThat(override.typeUrl()).isEqualTo(
        "type.googleapis.com/envoy.extensions.filters.http.ext_authz.v3.ExtAuthzPerRoute");
  }
}
