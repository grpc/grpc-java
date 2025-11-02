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

import com.google.common.collect.ImmutableList;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.envoyproxy.envoy.service.auth.v3.DeniedHttpResponse;
import io.envoyproxy.envoy.service.auth.v3.OkHttpResponse;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.GrpcUtil;
import io.grpc.xds.internal.grpcservice.HeaderValue;
import io.grpc.xds.internal.grpcservice.HeaderValueValidationUtils;
import io.grpc.xds.internal.headermutations.HeaderMutationDisallowedException;
import io.grpc.xds.internal.headermutations.HeaderMutationFilter;
import io.grpc.xds.internal.headermutations.HeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderValueOption;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Handles the response from the external authorization service, processing it to determine the
 * authorization decision and applying any necessary header mutations.
 */
@ThreadSafe
public class CheckResponseHandler {
  private final HeaderMutationFilter headerMutationFilter;

  public CheckResponseHandler(HeaderMutationFilter headerMutationFilter) {
    this.headerMutationFilter = headerMutationFilter;
  }

  AuthzResponse handleResponse(final CheckResponse response) {
    try {
      if (response.getStatus().getCode() == Status.Code.OK.value()) {
        return handleOkResponse(response);
      } else {
        return handleNotOkResponse(response);
      }
    } catch (HeaderMutationDisallowedException e) {
      return AuthzResponse.deny(e.getStatus()).build();
    }
  }

  private AuthzResponse handleOkResponse(final CheckResponse response)
      throws HeaderMutationDisallowedException {
    if (!response.hasOkResponse()) {
      return AuthzResponse.allow(
          HeaderMutations.create(ImmutableList.of(), ImmutableList.of())).build();
    }
    OkHttpResponse okResponse = response.getOkResponse();
    CheckResponseMutations allowedMutations = buildHeaderMutationsFromOkResponse(okResponse);

    return AuthzResponse.allow(allowedMutations.requestMutations())
        .setResponseHeaderMutations(allowedMutations.responseMutations()).build();
  }

  private CheckResponseMutations buildHeaderMutationsFromOkResponse(OkHttpResponse okResponse)
      throws HeaderMutationDisallowedException {
    HeaderMutations requestMutations = HeaderMutations.create(
        convertHeaders(okResponse.getHeadersList()),
        ImmutableList.copyOf(okResponse.getHeadersToRemoveList()));
    HeaderMutations responseMutations = HeaderMutations.create(
        convertHeaders(okResponse.getResponseHeadersToAddList()),
        ImmutableList.of());
    return CheckResponseMutations.create(
        headerMutationFilter.filter(requestMutations),
        headerMutationFilter.filter(responseMutations));
  }

  private AuthzResponse handleNotOkResponse(CheckResponse response)
      throws HeaderMutationDisallowedException {
    String baseMsg = "RPC denied by external authorization server";
    String outerMsg = response.getStatus().getMessage();
    String description = outerMsg.isEmpty() ? baseMsg : baseMsg + ": " + outerMsg;

    if (!response.hasDeniedResponse()) {
      return AuthzResponse.deny(Status.PERMISSION_DENIED.withDescription(description)).build();
    }
    DeniedHttpResponse deniedResponse = response.getDeniedResponse();
    CheckResponseMutations allowedMutations =
        buildHeaderMutationsFromDeniedResponse(deniedResponse);

    Status status = Status.PERMISSION_DENIED;
    if (deniedResponse.hasStatus()) {
      status = GrpcUtil.httpStatusToGrpcStatus(deniedResponse.getStatus().getCodeValue());
    }
    // Per gRFC A92: deniedResponse.body is ignored for gRPC (doesn't apply to gRPC).
    return AuthzResponse.deny(status.withDescription(description))
        .setResponseHeaderMutations(allowedMutations.responseMutations()).build();
  }

  private CheckResponseMutations buildHeaderMutationsFromDeniedResponse(
      DeniedHttpResponse deniedResponse) throws HeaderMutationDisallowedException {
    HeaderMutations requestMutations =
        HeaderMutations.create(ImmutableList.of(), ImmutableList.of());
    HeaderMutations responseMutations = HeaderMutations.create(
        convertHeaders(deniedResponse.getHeadersList()),
        ImmutableList.of());
    return CheckResponseMutations.create(
        headerMutationFilter.filter(requestMutations),
        headerMutationFilter.filter(responseMutations));
  }

  private ImmutableList<HeaderValueOption> convertHeaders(
      java.util.List<io.envoyproxy.envoy.config.core.v3.HeaderValueOption> headersList)
      throws HeaderMutationDisallowedException {
    ImmutableList.Builder<HeaderValueOption> builder = ImmutableList.builder();
    for (io.envoyproxy.envoy.config.core.v3.HeaderValueOption optionProto : headersList) {
      io.envoyproxy.envoy.config.core.v3.HeaderValue header = optionProto.getHeader();
      String key = header.getKey();
      HeaderValue internalHeader;
      if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
        internalHeader = HeaderValue.create(key, header.getRawValue());
      } else {
        internalHeader = HeaderValue.create(key, header.getValue());
      }
      if (HeaderValueValidationUtils.isDisallowed(internalHeader)) {
        continue;
      }
      HeaderValueOption.HeaderAppendAction action;
      switch (optionProto.getAppendAction()) {
        case APPEND_IF_EXISTS_OR_ADD:
          action = HeaderValueOption.HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD;
          break;
        case ADD_IF_ABSENT:
          action = HeaderValueOption.HeaderAppendAction.ADD_IF_ABSENT;
          break;
        case OVERWRITE_IF_EXISTS_OR_ADD:
          action = HeaderValueOption.HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD;
          break;
        case OVERWRITE_IF_EXISTS:
          action = HeaderValueOption.HeaderAppendAction.OVERWRITE_IF_EXISTS;
          break;
        case UNRECOGNIZED:
        default:
          // Envoy Parity / Spec Parity: Unconditionally reject invalid/unrecognized append actions
          throw new HeaderMutationDisallowedException(
              "Unrecognized HeaderAppendAction: " + optionProto.getAppendAction());
      }
      builder
          .add(HeaderValueOption.create(internalHeader, action));
    }
    return builder.build();
  }
}

