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
import com.google.common.base.Supplier;
import com.google.errorprone.annotations.DoNotCall;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.ExperimentalApi;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerStreamTracer;
import io.grpc.binder.internal.BinderServer;
import io.grpc.binder.internal.BinderTransportSecurity;
import io.grpc.ForwardingServerBuilder;
import io.grpc.internal.FixedObjectPool;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ServerImplBuilder;
import io.grpc.internal.ServerImplBuilder.ClientTransportServersBuilder;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourcePool;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;

/**
 * Builder for a server that services requests from an Android Service.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8022")
public final class BinderServerBuilder
    extends ForwardingServerBuilder<BinderServerBuilder> {

  /**
   * Creates a server builder that will bind with the given name.
   *
   * <p>The listening {@link IBinder} associated with new {@link Server}s will be stored in {@code
   * binderReceiver} upon {@link #build()}.
   *
   * @param service the concrete Android Service that will host this server.
   * @param receiver an "out param" for the new {@link Server}'s listening {@link IBinder}
   * @return a new builder
   */
  public static BinderServerBuilder forService(Service service, IBinderReceiver receiver) {
    return new BinderServerBuilder(service, receiver);
  }

  /**
   * Always fails. Call {@link #forService(Service, IBinderReceiver)} instead.
   */
  @DoNotCall("Unsupported. Use forService() instead")
  public static BinderServerBuilder forPort(int port) {
    throw new UnsupportedOperationException("call forService() instead");
  }

  private final ServerImplBuilder serverImplBuilder;
  private ObjectPool<ScheduledExecutorService> schedulerPool =
      SharedResourcePool.forResource(GrpcUtil.TIMER_SERVICE);
  private ServerSecurityPolicy securityPolicy;
  private InboundParcelablePolicy inboundParcelablePolicy;

  private BinderServerBuilder(Service service, IBinderReceiver binderReceiver) {
    securityPolicy = SecurityPolicies.serverInternalOnly();
    inboundParcelablePolicy = InboundParcelablePolicy.DEFAULT;

    serverImplBuilder = new ServerImplBuilder(streamTracerFactories -> {
      BinderServer server = new BinderServer(
          AndroidComponentAddress.forContext(service),
          schedulerPool,
          streamTracerFactories,
          securityPolicy,
          inboundParcelablePolicy);
      binderReceiver.set(server.getHostBinder());
      return server;
    });

    // Disable compression by default, since there's little benefit when all communication is
    // on-device, and it means sending supported-encoding headers with every call.
    decompressorRegistry(DecompressorRegistry.emptyInstance());
    compressorRegistry(CompressorRegistry.newEmptyInstance());

    // Disable stats and tracing by default.
    serverImplBuilder.setStatsEnabled(false);
    serverImplBuilder.setTracingEnabled(false);

    BinderTransportSecurity.installAuthInterceptor(this);
  }

  @Override
  protected ServerBuilder<?> delegate() {
    return serverImplBuilder;
  }

  /** Enable stats collection using census. */
  public BinderServerBuilder enableStats() {
    serverImplBuilder.setStatsEnabled(true);
    return this;
  }

  /** Enable tracing using census. */
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
  public BinderServerBuilder inboundParcelablePolicy(
      InboundParcelablePolicy inboundParcelablePolicy) {
    this.inboundParcelablePolicy = checkNotNull(inboundParcelablePolicy, "inboundParcelablePolicy");
    return this;
  }

  @Override
  public BinderServerBuilder useTransportSecurity(File certChain, File privateKey) {
    throw new UnsupportedOperationException("TLS not supported in BinderServer");
  }

  /**
   * Builds a {@link Server} according to this builder's parameters and stores its listening {@link
   * IBinder} in the {@link IBinderReceiver} passed to {@link #forService(Service,
   * IBinderReceiver)}.
   *
   * @return the new Server
   */
  @Override // For javadoc refinement only.
  public Server build() {
    return super.build();
  }
}
