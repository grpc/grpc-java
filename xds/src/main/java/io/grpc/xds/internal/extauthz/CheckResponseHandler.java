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
import io.grpc.xds.internal.headermutations.HeaderMutationDisallowedException;
import io.grpc.xds.internal.headermutations.HeaderMutationFilter;
import io.grpc.xds.internal.headermutations.HeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderMutator;

/**
 * Handles the response from the external authorization service, processing it to determine the
 * authorization decision and applying any necessary header mutations.
 */
public interface CheckResponseHandler {

  /**
   * A factory for creating {@link CheckResponseHandler} instances.
   */
  @FunctionalInterface
  interface Factory {
    /**
     * Creates a new ResponseHandler.
     *
     * @param headerMutator Utility to apply header mutations.
     * @param headerMutationFilter Filter to apply to header mutations.
     * @param config The external authorization configuration.
     */
    CheckResponseHandler create(HeaderMutator headerMutator,
        HeaderMutationFilter headerMutationFilter, ExtAuthzConfig config);
  }

  /**
   * The default factory for creating {@link CheckResponseHandler} instances.
   */
  Factory INSTANCE = ResponseHandlerImpl::new;

  /**
   * Processes the CheckResponse from the external authorization service.
   *
   * @param response The response from the authorization service.
   * @param headers The request headers, which may be mutated as part of handling the response.
   * @return An {@link AuthzResponse} indicating the outcome of the authorization check.
   */
  AuthzResponse handleResponse(final CheckResponse response, Metadata headers);

  /** Default implementation of {@link CheckResponseHandler}. */
  static final class ResponseHandlerImpl implements CheckResponseHandler {
    private final HeaderMutator headerMutator;
    private final HeaderMutationFilter headerMutationFilter;
    private final ExtAuthzConfig config;

    ResponseHandlerImpl(HeaderMutator headerMutator, // NOPMD
        HeaderMutationFilter headerMutationFilter, ExtAuthzConfig config) {
      this.headerMutator = headerMutator;
      this.headerMutationFilter = headerMutationFilter;
      this.config = config;
    }

    @Override
    public AuthzResponse handleResponse(final CheckResponse response, Metadata headers) {
      try {
        if (response.getStatus().getCode() == Status.Code.OK.value()) {
          return handleOkResponse(response, headers);
        } else {
          return handleNotOkResponse(response);
        }
      } catch (HeaderMutationDisallowedException e) {
        return AuthzResponse.deny(e.getStatus()).build();
      }
    }

    private AuthzResponse handleOkResponse(final CheckResponse response, Metadata headers)
        throws HeaderMutationDisallowedException {
      if (!response.hasOkResponse()) {
        return AuthzResponse.allow(headers).build();
      }
      OkHttpResponse okResponse = response.getOkResponse();
      HeaderMutations requestedMutations = buildHeaderMutationsFromOkResponse(okResponse);
      HeaderMutations allowedMutations = headerMutationFilter.filter(requestedMutations);

      applyMutations(allowedMutations, headers);
      return AuthzResponse.allow(headers)
          .setResponseHeaderMutations(allowedMutations.responseMutations()).build();
    }

    private HeaderMutations buildHeaderMutationsFromOkResponse(OkHttpResponse okResponse) {
      return HeaderMutations.create(
          HeaderMutations.RequestHeaderMutations.create(
              ImmutableList.copyOf(okResponse.getHeadersList()),
              ImmutableList.copyOf(okResponse.getHeadersToRemoveList())),
          HeaderMutations.ResponseHeaderMutations
              .create(ImmutableList.copyOf(okResponse.getResponseHeadersToAddList())));
    }

    private AuthzResponse handleNotOkResponse(CheckResponse response)
        throws HeaderMutationDisallowedException {
      Status statusToReturn = config.statusOnError();
      if (!response.hasDeniedResponse()) {
        return AuthzResponse.deny(statusToReturn).build();
      }
      DeniedHttpResponse deniedResponse = response.getDeniedResponse();
      HeaderMutations requestedMutations = buildHeaderMutationsFromDeniedResponse(deniedResponse);
      HeaderMutations allowedMutations = headerMutationFilter.filter(requestedMutations);

      Status status = statusToReturn;
      if (deniedResponse.hasStatus()) {
        status = GrpcUtil.httpStatusToGrpcStatus(deniedResponse.getStatus().getCodeValue())
            .withDescription(deniedResponse.getBody());
      }
      return AuthzResponse.deny(status)
          .setResponseHeaderMutations(allowedMutations.responseMutations()).build();
    }

    private HeaderMutations buildHeaderMutationsFromDeniedResponse(
        DeniedHttpResponse deniedResponse) {
      return HeaderMutations.create(
          HeaderMutations.RequestHeaderMutations.create(ImmutableList.of(), ImmutableList.of()),
          HeaderMutations.ResponseHeaderMutations
              .create(ImmutableList.copyOf(deniedResponse.getHeadersList())));
    }


    private void applyMutations(final HeaderMutations mutations, Metadata headers) {
      headerMutator.applyRequestMutations(mutations.requestMutations(), headers);
    }
  }
}
