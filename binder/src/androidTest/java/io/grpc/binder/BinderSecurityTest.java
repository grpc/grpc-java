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

package io.grpc.binder;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.base.Function;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.Empty;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.lite.ProtoLiteUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class BinderSecurityTest {
  private final Context appContext = ApplicationProvider.getApplicationContext();

  String[] serviceNames = new String[] {"foo", "bar", "baz"};
  List<ServerServiceDefinition> serviceDefinitions = new ArrayList<>();

  @Nullable ManagedChannel channel;
  Map<String, MethodDescriptor<Empty, Empty>> methods = new HashMap<>();
  List<MethodDescriptor<Empty, Empty>> calls = new ArrayList<>();
  CountingServerInterceptor countingServerInterceptor;

  @Before
  public void setupServiceDefinitionsAndMethods() {
    MethodDescriptor.Marshaller<Empty> marshaller =
        ProtoLiteUtils.marshaller(Empty.getDefaultInstance());
    for (String serviceName : serviceNames) {
      ServerServiceDefinition.Builder builder = ServerServiceDefinition.builder(serviceName);
      for (int i = 0; i < 2; i++) {
        // Add two methods to the service.
        String name = serviceName + "/method" + i;
        MethodDescriptor<Empty, Empty> method =
            MethodDescriptor.newBuilder(marshaller, marshaller)
                .setFullMethodName(name)
                .setType(MethodDescriptor.MethodType.UNARY)
                .setSampledToLocalTracing(true)
                .build();
        ServerCallHandler<Empty, Empty> callHandler =
            ServerCalls.asyncUnaryCall(
                (req, respObserver) -> {
                  calls.add(method);
                  respObserver.onNext(req);
                  respObserver.onCompleted();
                });
        builder.addMethod(method, callHandler);
        methods.put(name, method);
      }
      serviceDefinitions.add(builder.build());
    }
    countingServerInterceptor = new CountingServerInterceptor();
  }

  @After
  public void tearDown() throws Exception {
    if (channel != null) {
      channel.shutdownNow();
    }
    HostServices.awaitServiceShutdown();
  }

  private void createChannel() throws Exception {
    createChannel(SecurityPolicies.serverInternalOnly(), SecurityPolicies.internalOnly());
  }

  private void createChannel(ServerSecurityPolicy serverPolicy, SecurityPolicy channelPolicy)
      throws Exception {
    AndroidComponentAddress addr = HostServices.allocateService(appContext);
    HostServices.configureService(
        addr,
        HostServices.serviceParamsBuilder()
            .setServerFactory((service, receiver) -> buildServer(addr, receiver, serverPolicy))
            .build());

    channel =
        BinderChannelBuilder.forAddress(addr, appContext).securityPolicy(channelPolicy).build();
  }

  private Server buildServer(
      AndroidComponentAddress listenAddr,
      IBinderReceiver receiver,
      ServerSecurityPolicy serverPolicy) {
    BinderServerBuilder serverBuilder = BinderServerBuilder.forAddress(listenAddr, receiver);
    serverBuilder.securityPolicy(serverPolicy);
    serverBuilder.intercept(countingServerInterceptor);

    for (ServerServiceDefinition serviceDefinition : serviceDefinitions) {
      serverBuilder.addService(serviceDefinition);
    }
    return serverBuilder.build();
  }

  private void assertCallSuccess(MethodDescriptor<Empty, Empty> method) {
    assertThat(
            ClientCalls.blockingUnaryCall(
                channel, method, CallOptions.DEFAULT, Empty.getDefaultInstance()))
        .isNotNull();
  }

  @CanIgnoreReturnValue
  private StatusRuntimeException assertCallFailure(
      MethodDescriptor<Empty, Empty> method, Status status) {
    try {
      ClientCalls.blockingUnaryCall(channel, method, CallOptions.DEFAULT, null);
      fail("Expected call to " + method.getFullMethodName() + " to fail but it succeeded.");
      throw new AssertionError(); // impossible
    } catch (StatusRuntimeException sre) {
      assertThat(sre.getStatus().getCode()).isEqualTo(status.getCode());
      return sre;
    }
  }

  @Test
  public void testAllowedCall() throws Exception {
    createChannel();
    assertThat(methods).isNotEmpty();
    for (MethodDescriptor<Empty, Empty> method : methods.values()) {
      assertCallSuccess(method);
    }
  }

  @Test
  public void testServerDisallowsCalls() throws Exception {
    createChannel(
        ServerSecurityPolicy.newBuilder()
            .servicePolicy("foo", policy((uid) -> false))
            .servicePolicy("bar", policy((uid) -> false))
            .servicePolicy("baz", policy((uid) -> false))
            .build(),
        SecurityPolicies.internalOnly());
    assertThat(methods).isNotEmpty();
    for (MethodDescriptor<Empty, Empty> method : methods.values()) {
      assertCallFailure(method, Status.PERMISSION_DENIED);
    }
  }

  @Test
  public void testFailedFuturesPropagateOriginalException() throws Exception {
    String errorMessage = "something went wrong";
    IllegalStateException originalException = new IllegalStateException(errorMessage);
    createChannel(
        ServerSecurityPolicy.newBuilder()
            .servicePolicy(
                "foo",
                new AsyncSecurityPolicy() {
                  @Override
                  public ListenableFuture<Status> checkAuthorizationAsync(int uid) {
                    return Futures.immediateFailedFuture(originalException);
                  }
                })
            .build(),
        SecurityPolicies.internalOnly());
    MethodDescriptor<Empty, Empty> method = methods.get("foo/method0");

    StatusRuntimeException sre = assertCallFailure(method, Status.INTERNAL);
    assertThat(sre.getStatus().getDescription()).contains(errorMessage);
  }

  @Test
  public void testFailedFuturesAreNotCachedPermanently() throws Exception {
    AtomicReference<Boolean> firstAttempt = new AtomicReference<>(true);
    createChannel(
        ServerSecurityPolicy.newBuilder()
            .servicePolicy(
                "foo",
                new AsyncSecurityPolicy() {
                  @Override
                  public ListenableFuture<Status> checkAuthorizationAsync(int uid) {
                    if (firstAttempt.getAndSet(false)) {
                      return Futures.immediateFailedFuture(new IllegalStateException());
                    }
                    return Futures.immediateFuture(Status.OK);
                  }
                })
            .build(),
        SecurityPolicies.internalOnly());
    MethodDescriptor<Empty, Empty> method = methods.get("foo/method0");

    assertCallFailure(method, Status.INTERNAL);
    assertCallSuccess(method);
  }

  @Test
  public void testCancelledFuturesAreNotCachedPermanently() throws Exception {
    AtomicReference<Boolean> firstAttempt = new AtomicReference<>(true);
    createChannel(
        ServerSecurityPolicy.newBuilder()
            .servicePolicy(
                "foo",
                new AsyncSecurityPolicy() {
                  @Override
                  public ListenableFuture<Status> checkAuthorizationAsync(int uid) {
                    if (firstAttempt.getAndSet(false)) {
                      return Futures.immediateCancelledFuture();
                    }
                    return Futures.immediateFuture(Status.OK);
                  }
                })
            .build(),
        SecurityPolicies.internalOnly());
    MethodDescriptor<Empty, Empty> method = methods.get("foo/method0");

    assertCallFailure(method, Status.INTERNAL);
    assertCallSuccess(method);
  }

  @Test
  public void testClientDoesntTrustServer() throws Exception {
    createChannel(SecurityPolicies.serverInternalOnly(), policy((uid) -> false));
    assertThat(methods).isNotEmpty();
    for (MethodDescriptor<Empty, Empty> method : methods.values()) {
      assertCallFailure(method, Status.PERMISSION_DENIED);
    }
  }

  @Test
  public void testPerServicePolicy() throws Exception {
    createChannel(
        ServerSecurityPolicy.newBuilder()
            .servicePolicy("foo", policy((uid) -> true))
            .servicePolicy("bar", policy((uid) -> false))
            .build(),
        SecurityPolicies.internalOnly());

    assertThat(methods).isNotEmpty();
    for (MethodDescriptor<Empty, Empty> method : methods.values()) {
      if (method.getServiceName().equals("bar")) {
        assertCallFailure(method, Status.PERMISSION_DENIED);
      } else {
        assertCallSuccess(method);
      }
    }
  }

  @Test
  public void testPerServicePolicyAsync() throws Exception {
    createChannel(
        ServerSecurityPolicy.newBuilder()
            .servicePolicy("foo", asyncPolicy((uid) -> Futures.immediateFuture(true)))
            .servicePolicy("bar", asyncPolicy((uid) -> Futures.immediateFuture(false)))
            .build(),
        SecurityPolicies.internalOnly());

    assertThat(methods).isNotEmpty();
    for (MethodDescriptor<Empty, Empty> method : methods.values()) {
      if (method.getServiceName().equals("bar")) {
        assertCallFailure(method, Status.PERMISSION_DENIED);
      } else {
        assertCallSuccess(method);
      }
    }
  }

  @Test
  public void testSecurityInterceptorIsClosestToTransport() throws Exception {
    createChannel(
        ServerSecurityPolicy.newBuilder()
            .servicePolicy("foo", policy((uid) -> true))
            .servicePolicy("bar", policy((uid) -> false))
            .servicePolicy("baz", policy((uid) -> false))
            .build(),
        SecurityPolicies.internalOnly());
    assertThat(countingServerInterceptor.numInterceptedCalls).isEqualTo(0);
    for (MethodDescriptor<Empty, Empty> method : methods.values()) {
      try {
        ClientCalls.blockingUnaryCall(channel, method, CallOptions.DEFAULT, null);
      } catch (StatusRuntimeException sre) {
        // Ignore.
      }
    }
    // Only the foo calls should have made it to the user interceptor.
    assertThat(countingServerInterceptor.numInterceptedCalls).isEqualTo(2);
  }

  private static SecurityPolicy policy(Function<Integer, Boolean> func) {
    return new SecurityPolicy() {
      @Override
      public Status checkAuthorization(int uid) {
        return func.apply(uid) ? Status.OK : Status.PERMISSION_DENIED;
      }
    };
  }

  private static AsyncSecurityPolicy asyncPolicy(
      Function<Integer, ListenableFuture<Boolean>> func) {
    return new AsyncSecurityPolicy() {
      @Override
      public ListenableFuture<Status> checkAuthorizationAsync(int uid) {
        return Futures.transform(
            func.apply(uid),
            allowed -> allowed ? Status.OK : Status.PERMISSION_DENIED,
            MoreExecutors.directExecutor());
      }
    };
  }

  private final class CountingServerInterceptor implements ServerInterceptor {
    int numInterceptedCalls;

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      numInterceptedCalls += 1;
      return next.startCall(call, headers);
    }
  }
}
