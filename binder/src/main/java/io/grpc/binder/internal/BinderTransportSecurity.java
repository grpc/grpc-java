/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.binder.internal;

import io.grpc.Attributes;
import io.grpc.Internal;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.SecurityLevel;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.binder.ServerSecurityPolicy;
import io.grpc.internal.GrpcAttributes;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.CheckReturnValue;

/**
 * Manages security for an Android Service hosted gRPC server.
 *
 * <p>Attaches authorization state to a newly-created transport, and contains a
 * ServerInterceptor which ensures calls are authorized before allowing them to proceed.
 */
public final class BinderTransportSecurity {

  private static final Attributes.Key<TransportAuthorizationState> TRANSPORT_AUTHORIZATION_STATE =
      Attributes.Key.create("internal:transport-authorization-state");

  private BinderTransportSecurity() {}

  /**
   * Install a security policy on an about-to-be created server.
   *
   * @param serverBuilder The ServerBuilder being used to create the server.
   */
  @Internal
  public static void installAuthInterceptor(ServerBuilder<?> serverBuilder) {
    serverBuilder.intercept(new ServerAuthInterceptor());
  }

  /**
   * Attach the given security policy to the transport attributes being built. Will be used by the
   * auth interceptor to confirm accept or reject calls.
   *
   * @param builder The {@link Attributes.Builder} for the transport being created.
   * @param remoteUid The remote UID of the transport.
   * @param securityPolicy The policy to enforce on this transport.
   */
  @Internal
  public static void attachAuthAttrs(
      Attributes.Builder builder, int remoteUid, ServerSecurityPolicy securityPolicy) {
    builder
        .set(
            TRANSPORT_AUTHORIZATION_STATE,
            new TransportAuthorizationState(remoteUid, securityPolicy))
        .set(GrpcAttributes.ATTR_SECURITY_LEVEL, SecurityLevel.PRIVACY_AND_INTEGRITY);
  }

  /**
   * Intercepts server calls and ensures they're authorized before allowing them to proceed.
   * Authentication state is fetched from the call attributes, inherited from the transport.
   */
  private static final class ServerAuthInterceptor implements ServerInterceptor {
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      Status authStatus =
          call.getAttributes()
              .get(TRANSPORT_AUTHORIZATION_STATE)
              .checkAuthorization(call.getMethodDescriptor());
      if (authStatus.isOk()) {
        return next.startCall(call, headers);
      } else {
        call.close(authStatus, new Metadata());
        return new ServerCall.Listener<ReqT>() {};
      }
    }
  }

  /**
   * Maintaines the authorization state for a single transport instance. This class lives for the
   * lifetime of a single transport.
   */
  private static final class TransportAuthorizationState {
    private final int uid;
    private final ServerSecurityPolicy policy;
    private final ConcurrentHashMap<String, Status> serviceAuthorization;

    TransportAuthorizationState(int uid, ServerSecurityPolicy policy) {
      this.uid = uid;
      this.policy = policy;
      serviceAuthorization = new ConcurrentHashMap<>(8);
    }

    /** Get whether we're authorized to make this call. */
    @CheckReturnValue
    Status checkAuthorization(MethodDescriptor<?, ?> method) {
      String serviceName = method.getServiceName();
      // Only cache decisions if the method can be sampled for tracing,
      // which is true for all generated methods. Otherwise, programatically
      // created methods could casue this cahe to grow unbounded.
      boolean useCache = method.isSampledToLocalTracing();
      Status authorization;
      if (useCache) {
        authorization = serviceAuthorization.get(serviceName);
        if (authorization != null) {
          return authorization;
        }
      }
      authorization = policy.checkAuthorizationForService(uid, serviceName);
      if (useCache) {
        serviceAuthorization.putIfAbsent(serviceName, authorization);
      }
      return authorization;
    }
  }
}
