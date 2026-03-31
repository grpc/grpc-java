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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.rpc.Code;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz;
import io.envoyproxy.envoy.extensions.grpc_service.call_credentials.access_token.v3.AccessTokenCredentials;
import io.envoyproxy.envoy.extensions.grpc_service.channel_credentials.google_default.v3.GoogleDefaultCredentials;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.envoyproxy.envoy.service.auth.v3.DeniedHttpResponse;
import io.envoyproxy.envoy.service.auth.v3.OkHttpResponse;
import io.envoyproxy.envoy.type.v3.HttpStatus;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.xds.internal.extauthz.AuthzResponse.Decision;
import io.grpc.xds.internal.headermutations.HeaderMutationDisallowedException;
import io.grpc.xds.internal.headermutations.HeaderMutationFilter;
import io.grpc.xds.internal.headermutations.HeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderMutator;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class CheckResponseHandlerTest {
  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock
  private HeaderMutator headerMutator;
  @Mock
  private HeaderMutationFilter headerMutationFilter;

  private CheckResponseHandler responseHandler;

  @Before
  public void setUp() throws Exception {
    responseHandler =
        CheckResponseHandler.INSTANCE.create(headerMutator, headerMutationFilter,
            buildExtAuthzConfig());
    when(headerMutationFilter.filter(any(HeaderMutations.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  public void handleResponse_ok() {
    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(com.google.rpc.Status.newBuilder().setCode(Code.OK_VALUE).build()).build();
    Metadata headers = new Metadata();
    AuthzResponse authzResponse = responseHandler.handleResponse(checkResponse, headers);
    assertThat(authzResponse.decision()).isEqualTo(Decision.ALLOW);
    assertThat(authzResponse.headers()).hasValue(headers);
  }

  @Test
  public void handleResponse_okWithMutations() {
    HeaderValueOption option = HeaderValueOption.newBuilder().build();
    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(com.google.rpc.Status.newBuilder().setCode(Code.OK_VALUE).build())
        .setOkResponse(OkHttpResponse.newBuilder().addHeaders(option)
            .addHeadersToRemove("remove-key").addResponseHeadersToAdd(option).build())
        .build();
    Metadata headers = new Metadata();
    AuthzResponse authzResponse = responseHandler.handleResponse(checkResponse, headers);
    assertThat(authzResponse.decision()).isEqualTo(Decision.ALLOW);
    assertThat(authzResponse.headers()).hasValue(headers);
    HeaderMutations expectedMutations = HeaderMutations.create(
        HeaderMutations.RequestHeaderMutations.create(ImmutableList.of(option),
            ImmutableList.of("remove-key")),
        HeaderMutations.ResponseHeaderMutations.create(ImmutableList.of(option)));
    verify(headerMutator).applyRequestMutations(expectedMutations.requestMutations(), headers);
    assertThat(authzResponse.responseHeaderMutations())
        .isEqualTo(expectedMutations.responseMutations());
  }

  @Test
  public void handleResponse_notOk() {
    CheckResponse checkResponse = CheckResponse.newBuilder().setStatus(com.google.rpc.Status
        .newBuilder().setCode(Code.PERMISSION_DENIED_VALUE).setMessage("denied").build()).build();
    Metadata headers = new Metadata();
    AuthzResponse authzResponse = responseHandler.handleResponse(checkResponse, headers);
    assertThat(authzResponse.decision()).isEqualTo(Decision.DENY);
    assertThat(authzResponse.status().isPresent()).isTrue();
    assertThat(authzResponse.status().get().getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
    assertThat(authzResponse.status().get().getDescription()).isEqualTo("HTTP status code 403");
    verify(headerMutator, never()).applyRequestMutations(any(), any());
  }

  @Test
  public void handleResponse_deniedResponseWithoutStatusOverride() {
    HeaderValueOption option = HeaderValueOption.newBuilder().build();
    DeniedHttpResponse deniedHttpResponse =
        DeniedHttpResponse.newBuilder().addHeaders(option).build();
    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(com.google.rpc.Status.newBuilder().setCode(Code.ABORTED_VALUE).build())
        .setDeniedResponse(deniedHttpResponse).build();
    Metadata headers = new Metadata();
    AuthzResponse authzResponse = responseHandler.handleResponse(checkResponse, headers);
    assertThat(authzResponse.decision()).isEqualTo(Decision.DENY);
    assertThat(authzResponse.status().get().getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
    assertThat(authzResponse.status().get().getDescription()).isEqualTo("HTTP status code 403");
    HeaderMutations.ResponseHeaderMutations expectedMutations =
        HeaderMutations.ResponseHeaderMutations.create(ImmutableList.of(option));
    assertThat(authzResponse.responseHeaderMutations()).isEqualTo(expectedMutations);
    verify(headerMutator, never()).applyRequestMutations(any(), any());
  }

  @Test
  public void handleResponse_deniedResponseWithStatusOverride() {
    DeniedHttpResponse deniedHttpResponse =
        DeniedHttpResponse.newBuilder().setStatus(HttpStatus.newBuilder().setCodeValue(401).build())
            .setBody("custom body").build();
    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(com.google.rpc.Status.newBuilder().setCode(Code.ABORTED_VALUE).build())
        .setDeniedResponse(deniedHttpResponse).build();
    Metadata headers = new Metadata();
    AuthzResponse authzResponse = responseHandler.handleResponse(checkResponse, headers);
    assertThat(authzResponse.decision()).isEqualTo(Decision.DENY);
    assertThat(authzResponse.status().isPresent()).isTrue();
    Status status = authzResponse.status().get();
    assertThat(status.getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(status.getDescription()).isEqualTo("custom body");
    HeaderMutations.ResponseHeaderMutations expectedMutations =
        HeaderMutations.ResponseHeaderMutations.create(ImmutableList.of());
    assertThat(authzResponse.responseHeaderMutations()).isEqualTo(expectedMutations);
    verify(headerMutator, never()).applyRequestMutations(any(), any());
  }

  @Test
  public void handleResponse_okWithDisallowedMutation() throws HeaderMutationDisallowedException {
    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(com.google.rpc.Status.newBuilder().setCode(Code.OK_VALUE).build())
        .setOkResponse(OkHttpResponse.newBuilder().build()).build();
    Metadata headers = new Metadata();
    HeaderMutationDisallowedException exception =
        new HeaderMutationDisallowedException("disallowed");
    when(headerMutationFilter.filter(any(HeaderMutations.class))).thenThrow(exception);

    AuthzResponse authzResponse = responseHandler.handleResponse(checkResponse, headers);

    assertThat(authzResponse.decision()).isEqualTo(Decision.DENY);
    assertThat(authzResponse.status().get().getCode()).isEqualTo(Status.INTERNAL.getCode());
    assertThat(authzResponse.status().get().getDescription()).isEqualTo("disallowed");
  }

  private ExtAuthzConfig buildExtAuthzConfig() throws ExtAuthzParseException {
    Any googleDefaultChannelCreds = Any.pack(GoogleDefaultCredentials.newBuilder().build());
    Any fakeAccessTokenCreds =
        Any.pack(AccessTokenCredentials.newBuilder().setToken("fake-token").build());
    ExtAuthz extAuthz = ExtAuthz.newBuilder()
        .setGrpcService(io.envoyproxy.envoy.config.core.v3.GrpcService.newBuilder()
            .setGoogleGrpc(io.envoyproxy.envoy.config.core.v3.GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri("test-cluster").addChannelCredentialsPlugin(googleDefaultChannelCreds)
                .addCallCredentialsPlugin(fakeAccessTokenCreds).build())
            .build())
        .setStatusOnError(
            io.envoyproxy.envoy.type.v3.HttpStatus.newBuilder().setCodeValue(403).build())
        .build();
    return ExtAuthzConfig.fromProto(extAuthz);
  }
}
