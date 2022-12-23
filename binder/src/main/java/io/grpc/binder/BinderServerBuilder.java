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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.app.Service;
import android.os.IBinder;
import com.google.errorprone.annotations.DoNotCall;
import io.grpc.ExperimentalApi;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.binder.internal.BinderServer;
import io.grpc.binder.internal.BinderTransportSecurity;
import io.grpc.ForwardingServerBuilder;
import io.grpc.internal.FixedObjectPool;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ServerImplBuilder;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourcePool;
import java.io.File;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Builder for a server that services requests from an Android Service.
 */
public final class BinderServerBuilder
    extends ForwardingServerBuilder<BinderServerBuilder> {

  /**
   * Creates a server builder that will listen for bindings to the specified address.
   *
   * <p>The listening {@link IBinder} associated with new {@link Server}s will be stored
   * in {@code binderReceiver} upon {@link #build()}. Callers should return it from {@link
   * Service#onBind(Intent)} when the binding intent matches {@code listenAddress}.
   *
   * @param listenAddress an Android Service and binding Intent associated with this server.
   * @param receiver an "out param" for the new {@link Server}'s listening {@link IBinder}
   * @return a new builder
   */
  public static BinderServerBuilder forAddress(AndroidComponentAddress listenAddress,
      IBinderReceiver receiver) {
    return new BinderServerBuilder(listenAddress, receiver);
  }

  /**
   * Always fails. Call {@link #forAddress(AndroidComponentAddress, IBinderReceiver)} instead.
   */
  @DoNotCall("Unsupported. Use forAddress() instead")
  public static BinderServerBuilder forPort(int port) {
    throw new UnsupportedOperationException("call forAddress() instead");
  }

  private final ServerImplBuilder serverImplBuilder;
  private ObjectPool<ScheduledExecutorService> schedulerPool =
      SharedResourcePool.forResource(GrpcUtil.TIMER_SERVICE);
  private ServerSecurityPolicy securityPolicy;
  private InboundParcelablePolicy inboundParcelablePolicy;
  private boolean isBuilt;

  private BinderServerBuilder(
      AndroidComponentAddress listenAddress,
      IBinderReceiver binderReceiver) {
    securityPolicy = SecurityPolicies.serverInternalOnly();
    inboundParcelablePolicy = InboundParcelablePolicy.DEFAULT;

    serverImplBuilder = new ServerImplBuilder(streamTracerFactories -> {
      BinderServer server = new BinderServer(
          listenAddress,
          schedulerPool,
          streamTracerFactories,
          securityPolicy,
          inboundParcelablePolicy);
      BinderInternal.setIBinder(binderReceiver, server.getHostBinder());
      return server;
    });

    // Disable stats and tracing by default.
    serverImplBuilder.setStatsEnabled(false);
    serverImplBuilder.setTracingEnabled(false);
  }

  @Override
  protected ServerBuilder<?> delegate() {
    return serverImplBuilder;
  }

  /** Enable stats collection using census. */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/8022")
  public BinderServerBuilder enableStats() {
    serverImplBuilder.setStatsEnabled(true);
    return this;
  }

  /** Enable tracing using census. */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/8022")
  public BinderServerBuilder enableTracing() {
    serverImplBuilder.setTracingEnabled(true);
    return this;
  }

  /**
   * Provides a custom scheduled executor service.
   *
   * <p>It's an optional parameter. If the user has not provided a scheduled executor service when
   * the channel is built, the builder will use a static cached thread pool.
   *
   * @return this
   */
  public BinderServerBuilder scheduledExecutorService(
      ScheduledExecutorService scheduledExecutorService) {
     schedulerPool =
          new FixedObjectPool<>(checkNotNull(scheduledExecutorService, "scheduledExecutorService"));
    return this;
  }

  /**
   * Provides a custom security policy.
   *
   * <p>This is optional. If the user has not provided a security policy, the server will default to
   * only accepting calls from the same application UID.
   *
   * @return this
   */
  public BinderServerBuilder securityPolicy(ServerSecurityPolicy securityPolicy) {
    this.securityPolicy = checkNotNull(securityPolicy, "securityPolicy");
    return this;
  }

  /** Sets the policy for inbound parcelable objects. */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/8022")
  public BinderServerBuilder inboundParcelablePolicy(
      InboundParcelablePolicy inboundParcelablePolicy) {
    this.inboundParcelablePolicy = checkNotNull(inboundParcelablePolicy, "inboundParcelablePolicy");
    return this;
  }

  /**
   * Always fails. TLS is not supported in BinderServer.
   */
  @Override
  public BinderServerBuilder useTransportSecurity(File certChain, File privateKey) {
    throw new UnsupportedOperationException("TLS not supported in BinderServer");
  }

  /**
   * Builds a {@link Server} according to this builder's parameters and stores its listening {@link
   * IBinder} in the {@link IBinderReceiver} passed to {@link #forAddress(AndroidComponentAddress,
   * IBinderReceiver)}.
   *
   * @return the new Server
   */
  @Override // For javadoc refinement only.
  public Server build() {
    // Since we install a final interceptor here, we need to ensure we're only built once.
    checkState(!isBuilt, "BinderServerBuilder can only be used to build one server instance.");
    isBuilt = true;
    // We install the security interceptor last, so it's closest to the transport.
    BinderTransportSecurity.installAuthInterceptor(this);
    return super.build();
  }
}
