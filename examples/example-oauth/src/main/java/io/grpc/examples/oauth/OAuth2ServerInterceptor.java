/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.examples.oauth;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

/**
 * This interceptor gets the OAuth2 access token from metadata, verifies it and sets the client
 * identifier obtained from the token into the context. The one check it does on the access token
 * is that the token has been refreshed at least once.
 *
 * A real implementation will validate the access token using the resource server (or the
 * authorization server).
 */
class OAuth2ServerInterceptor implements ServerInterceptor {

  private static final String BEARER_TYPE = "Bearer";

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> serverCall,
      Metadata metadata, ServerCallHandler<ReqT, RespT> serverCallHandler) {
    String authHeaderValue = metadata.get(Constant.AUTHORIZATION_METADATA_KEY);

    Status status = Status.OK;
    if (authHeaderValue == null) {
      status = Status.UNAUTHENTICATED.withDescription("Authorization token is missing");
    } else if (!authHeaderValue.startsWith(BEARER_TYPE)) {
      status = Status.UNAUTHENTICATED.withDescription("Unknown authorization type");
    } else {
      // remove authorization type prefix
      String tokenValue = authHeaderValue.substring(BEARER_TYPE.length()).trim();
      if (!tokenValue.startsWith(Constant.ACCESS_TOKEN)) {
        status = Status.UNAUTHENTICATED.withDescription("Invalid access token authHeaderValue");
      } else {
        String[] tokens = tokenValue.split(":");
        if (tokens.length >= 3 && tokens[2].equals(Constant.REFRESH_SUFFIX)) {
          // set access tokenValue into current context
          Context ctx = Context.current()
              .withValue(Constant.CLIENT_ID_CONTEXT_KEY, tokens[1]);
          return Contexts.interceptCall(ctx, serverCall, metadata, serverCallHandler);
        } else {
          status = Status.UNAUTHENTICATED.withDescription("stale credentials");
        }
      }
    }

    // at this point we have auth failure: skip further processing and close the call
    serverCall.close(status, new Metadata());
    return new ServerCall.Listener<ReqT>() {
      // noop
    };
  }

}
