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
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.rpc.Code;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.envoyproxy.envoy.service.auth.v3.DeniedHttpResponse;
import io.envoyproxy.envoy.service.auth.v3.OkHttpResponse;
import io.envoyproxy.envoy.type.v3.HttpStatus;
import io.grpc.Status;
import io.grpc.xds.internal.extauthz.AuthzResponse.Decision;
import io.grpc.xds.internal.headermutations.HeaderMutationDisallowedException;
import io.grpc.xds.internal.headermutations.HeaderMutationFilter;
import io.grpc.xds.internal.headermutations.HeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderValueOption.HeaderAppendAction;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Unit tests for {@link CheckResponseHandler}.
 */
@RunWith(JUnit4.class)
public class CheckResponseHandlerTest {

  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock
  private HeaderMutationFilter headerMutationFilter;

  private CheckResponseHandler responseHandler;

  @Before
  public void setUp() throws Exception {
    responseHandler = new CheckResponseHandler(headerMutationFilter);
    when(headerMutationFilter.filter(any(HeaderMutations.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  public void handleResponse_ok() {
    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(com.google.rpc.Status.newBuilder().setCode(Code.OK_VALUE).build()).build();
    AuthzResponse authzResponse = responseHandler.handleResponse(checkResponse);
    assertThat(authzResponse.decision()).isEqualTo(Decision.ALLOW);
    assertThat(authzResponse.requestHeaderMutations())
        .isEqualTo(HeaderMutations.create(ImmutableList.of(), ImmutableList.of()));
  }

  @Test
  public void handleResponse_okWithMutations() {
    HeaderValueOption option =
        HeaderValueOption.newBuilder().setHeader(HeaderValue
            .newBuilder().setKey("test-key").setValue("test-value")).build();
    io.grpc.xds.internal.headermutations.HeaderValueOption expectedOption =
        io.grpc.xds.internal.headermutations.HeaderValueOption.create(
            io.grpc.xds.internal.grpcservice.HeaderValue.create("test-key", "test-value"),
            HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD);
    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(com.google.rpc.Status.newBuilder().setCode(Code.OK_VALUE).build())
        .setOkResponse(OkHttpResponse.newBuilder().addHeaders(option)
            .addHeadersToRemove("remove-key").addResponseHeadersToAdd(option).build())
        .build();
    AuthzResponse authzResponse = responseHandler.handleResponse(checkResponse);
    assertThat(authzResponse.decision()).isEqualTo(Decision.ALLOW);
    HeaderMutations expectedRequestMutations = HeaderMutations.create(
        ImmutableList.of(expectedOption), ImmutableList.of("remove-key"));
    HeaderMutations expectedResponseMutations = HeaderMutations.create(
        ImmutableList.of(expectedOption), ImmutableList.of());
    assertThat(authzResponse.requestHeaderMutations()).isEqualTo(expectedRequestMutations);
    assertThat(authzResponse.responseHeaderMutations())
        .isEqualTo(expectedResponseMutations);
  }

  @Test
  public void handleResponse_notOk() {
    CheckResponse checkResponse = CheckResponse.newBuilder().setStatus(com.google.rpc.Status
        .newBuilder().setCode(Code.PERMISSION_DENIED_VALUE).setMessage("denied").build()).build();
    AuthzResponse authzResponse = responseHandler.handleResponse(checkResponse);
    assertThat(authzResponse.decision()).isEqualTo(Decision.DENY);
    assertThat(authzResponse.status().isPresent()).isTrue();
    assertThat(authzResponse.status().get().getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
    assertThat(authzResponse.status().get().getDescription())
        .isEqualTo("RPC denied by external authorization server: denied");
  }

  @Test
  public void handleResponse_deniedResponseWithoutStatusOverride() {
    HeaderValueOption option =
        HeaderValueOption.newBuilder().setHeader(HeaderValue
            .newBuilder().setKey("test-key").setValue("test-value")).build();
    io.grpc.xds.internal.headermutations.HeaderValueOption expectedOption =
        io.grpc.xds.internal.headermutations.HeaderValueOption.create(
            io.grpc.xds.internal.grpcservice.HeaderValue.create("test-key", "test-value"),
            HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD);
    DeniedHttpResponse deniedHttpResponse =
        DeniedHttpResponse.newBuilder().addHeaders(option).build();
    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(com.google.rpc.Status.newBuilder().setCode(Code.ABORTED_VALUE).build())
        .setDeniedResponse(deniedHttpResponse).build();
    AuthzResponse authzResponse = responseHandler.handleResponse(checkResponse);
    assertThat(authzResponse.decision()).isEqualTo(Decision.DENY);
    assertThat(authzResponse.status().get().getCode())
        .isEqualTo(Status.PERMISSION_DENIED.getCode());
    assertThat(authzResponse.status().get().getDescription())
        .isEqualTo("RPC denied by external authorization server");
    HeaderMutations expectedMutations =
        HeaderMutations.create(ImmutableList.of(expectedOption), ImmutableList.of());
    assertThat(authzResponse.responseHeaderMutations()).isEqualTo(expectedMutations);
  }

  @Test
  public void handleResponse_deniedResponseWithStatusOverride() {
    DeniedHttpResponse deniedHttpResponse =
        DeniedHttpResponse.newBuilder().setStatus(HttpStatus.newBuilder().setCodeValue(401).build())
            .setBody("custom body").build();
    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(com.google.rpc.Status.newBuilder().setCode(Code.ABORTED_VALUE).build())
        .setDeniedResponse(deniedHttpResponse).build();
    AuthzResponse authzResponse = responseHandler.handleResponse(checkResponse);
    assertThat(authzResponse.decision()).isEqualTo(Decision.DENY);
    assertThat(authzResponse.status().isPresent()).isTrue();
    Status status = authzResponse.status().get();
    assertThat(status.getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    // Per gRFC A92: body is ignored for gRPC, so description comes from status message
    assertThat(status.getDescription())
        .isEqualTo("RPC denied by external authorization server");
    HeaderMutations expectedMutations =
        HeaderMutations.create(ImmutableList.of(), ImmutableList.of());
    assertThat(authzResponse.responseHeaderMutations()).isEqualTo(expectedMutations);
  }

  @Test
  public void handleResponse_okWithDisallowedMutation() throws HeaderMutationDisallowedException {
    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(com.google.rpc.Status.newBuilder().setCode(Code.OK_VALUE).build())
        .setOkResponse(OkHttpResponse.newBuilder().build()).build();
    HeaderMutationDisallowedException exception =
        new HeaderMutationDisallowedException("disallowed");
    when(headerMutationFilter.filter(any(HeaderMutations.class))).thenThrow(exception);

    AuthzResponse authzResponse = responseHandler.handleResponse(checkResponse);

    assertThat(authzResponse.decision()).isEqualTo(Decision.DENY);
    assertThat(authzResponse.status().get().getCode()).isEqualTo(Status.INTERNAL.getCode());
    assertThat(authzResponse.status().get().getDescription()).isEqualTo("disallowed");
  }

  @Test
  public void handleResponse_ok_edgeCaseHeaders() {
    HeaderValueOption binaryOption =
        HeaderValueOption.newBuilder().setHeader(HeaderValue.newBuilder().setKey("test-bin")
            .setRawValue(com.google.protobuf.ByteString.copyFromUtf8("test"))).build();
    HeaderValueOption disallowedOption = HeaderValueOption.newBuilder()
        .setHeader(HeaderValue.newBuilder().setKey("host").setValue("disallowed")).build();

    io.grpc.xds.internal.headermutations.HeaderValueOption expectedBinaryOption =
        io.grpc.xds.internal.headermutations.HeaderValueOption.create(
            io.grpc.xds.internal.grpcservice.HeaderValue.create("test-bin",
                com.google.protobuf.ByteString.copyFromUtf8("test")),
            HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD);

    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(com.google.rpc.Status.newBuilder().setCode(Code.OK_VALUE).build())
        .setOkResponse(OkHttpResponse.newBuilder().addHeaders(binaryOption)
            .addHeaders(disallowedOption).build())
        .build();
    AuthzResponse authzResponse = responseHandler.handleResponse(checkResponse);

    assertThat(authzResponse.decision()).isEqualTo(Decision.ALLOW);
    HeaderMutations expectedRequestMutations = HeaderMutations
        .create(ImmutableList.of(expectedBinaryOption), ImmutableList.of());

    assertThat(authzResponse.requestHeaderMutations()).isEqualTo(expectedRequestMutations);
  }

  @Test
  public void handleResponse_ok_invalidAppendAction_deniesCall() {
    HeaderValueOption invalidActionOption = HeaderValueOption.newBuilder()
        .setHeader(HeaderValue.newBuilder().setKey("test-unknown-action").setValue("test-value"))
        .setAppendActionValue(999).build();

    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(com.google.rpc.Status.newBuilder().setCode(Code.OK_VALUE).build())
        .setOkResponse(OkHttpResponse.newBuilder().addHeaders(invalidActionOption).build())
        .build();
    AuthzResponse authzResponse = responseHandler.handleResponse(checkResponse);

    assertThat(authzResponse.decision()).isEqualTo(Decision.DENY);
    assertThat(authzResponse.status().get().getCode()).isEqualTo(Status.INTERNAL.getCode());
    assertThat(authzResponse.status().get().getDescription())
        .contains("Unrecognized HeaderAppendAction: UNRECOGNIZED");
  }

  @Test
  public void handleResponse_deniedResponseBodyIgnored() {
    DeniedHttpResponse deniedHttpResponse =
        DeniedHttpResponse.newBuilder().setStatus(HttpStatus.newBuilder().setCodeValue(403).build())
            .setBody("custom body text").build();
    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(com.google.rpc.Status.newBuilder().setCode(Code.PERMISSION_DENIED_VALUE).build())
        .setDeniedResponse(deniedHttpResponse).build();

    AuthzResponse authzResponse = responseHandler.handleResponse(checkResponse);

    assertThat(authzResponse.decision()).isEqualTo(Decision.DENY);
    assertThat(authzResponse.status().isPresent()).isTrue();
    Status status = authzResponse.status().get();
    assertThat(status.getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
    // Per gRFC A92: body is ignored for gRPC
    assertThat(status.getDescription())
        .isEqualTo("RPC denied by external authorization server");
  }

  @Test
  public void handleResponse_ok_overwriteIfExistsAction() {
    HeaderValueOption overwriteOption = HeaderValueOption.newBuilder()
        .setHeader(HeaderValue.newBuilder().setKey("x-custom").setValue("val"))
        .setAppendAction(
            io.envoyproxy.envoy.config.core.v3.HeaderValueOption
                .HeaderAppendAction.OVERWRITE_IF_EXISTS)
        .build();

    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(com.google.rpc.Status.newBuilder().setCode(Code.OK_VALUE).build())
        .setOkResponse(OkHttpResponse.newBuilder().addHeaders(overwriteOption).build())
        .build();
    AuthzResponse authzResponse = responseHandler.handleResponse(checkResponse);

    assertThat(authzResponse.decision()).isEqualTo(Decision.ALLOW);
    assertThat(authzResponse.requestHeaderMutations().headers()).hasSize(1);
    assertThat(authzResponse.requestHeaderMutations().headers().get(0).appendAction())
        .isEqualTo(HeaderAppendAction.OVERWRITE_IF_EXISTS);
  }

  @Test
  public void handleResponse_ok_addIfAbsentAction() {
    HeaderValueOption addIfAbsentOption = HeaderValueOption.newBuilder()
        .setHeader(HeaderValue.newBuilder().setKey("x-custom").setValue("val"))
        .setAppendAction(
            io.envoyproxy.envoy.config.core.v3.HeaderValueOption
                .HeaderAppendAction.ADD_IF_ABSENT)
        .build();

    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(com.google.rpc.Status.newBuilder().setCode(Code.OK_VALUE).build())
        .setOkResponse(OkHttpResponse.newBuilder().addHeaders(addIfAbsentOption).build())
        .build();
    AuthzResponse authzResponse = responseHandler.handleResponse(checkResponse);

    assertThat(authzResponse.decision()).isEqualTo(Decision.ALLOW);
    assertThat(authzResponse.requestHeaderMutations().headers()).hasSize(1);
    assertThat(authzResponse.requestHeaderMutations().headers().get(0).appendAction())
        .isEqualTo(HeaderAppendAction.ADD_IF_ABSENT);
  }

  @Test
  public void handleResponse_ok_overwriteIfExistsOrAddAction() {
    HeaderValueOption overwriteOrAddOption = HeaderValueOption.newBuilder()
        .setHeader(HeaderValue.newBuilder().setKey("x-custom").setValue("val"))
        .setAppendAction(
            io.envoyproxy.envoy.config.core.v3.HeaderValueOption
                .HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD)
        .build();

    CheckResponse checkResponse = CheckResponse.newBuilder()
        .setStatus(com.google.rpc.Status.newBuilder().setCode(Code.OK_VALUE).build())
        .setOkResponse(OkHttpResponse.newBuilder().addHeaders(overwriteOrAddOption).build())
        .build();
    AuthzResponse authzResponse = responseHandler.handleResponse(checkResponse);

    assertThat(authzResponse.decision()).isEqualTo(Decision.ALLOW);
    assertThat(authzResponse.requestHeaderMutations().headers()).hasSize(1);
    assertThat(authzResponse.requestHeaderMutations().headers().get(0).appendAction())
        .isEqualTo(HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD);
  }

}
