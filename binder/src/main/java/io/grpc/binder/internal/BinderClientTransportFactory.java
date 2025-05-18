/*
 * Copyright 2024 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.os.UserHandle;
import androidx.core.content.ContextCompat;
import io.grpc.ChannelCredentials;
import io.grpc.ChannelLogger;
import io.grpc.Internal;
import io.grpc.binder.AndroidComponentAddress;
import io.grpc.binder.BindServiceFlags;
import io.grpc.binder.BinderChannelCredentials;
import io.grpc.binder.InboundParcelablePolicy;
import io.grpc.binder.SecurityPolicies;
import io.grpc.binder.SecurityPolicy;
import io.grpc.internal.ClientTransportFactory;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ManagedChannelImplBuilder.ClientTransportFactoryBuilder;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourcePool;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;

/** Creates new binder transports. */
@Internal
public final class BinderClientTransportFactory implements ClientTransportFactory {
  final Context sourceContext;
  final BinderChannelCredentials channelCredentials;
  final Executor mainThreadExecutor;
  final ObjectPool<ScheduledExecutorService> scheduledExecutorPool;
  final ObjectPool<? extends Executor> offloadExecutorPool;
  final SecurityPolicy securityPolicy;
  @Nullable final UserHandle defaultTargetUserHandle;
  final BindServiceFlags bindServiceFlags;
  final InboundParcelablePolicy inboundParcelablePolicy;
  final OneWayBinderProxy.Decorator binderDecorator;
  final long readyTimeoutMillis;

  ScheduledExecutorService executorService;
  Executor offloadExecutor;
  private boolean closed;

  private BinderClientTransportFactory(Builder builder) {
    sourceContext = checkNotNull(builder.sourceContext);
    channelCredentials = checkNotNull(builder.channelCredentials);
    mainThreadExecutor =
        builder.mainThreadExecutor != null
            ? builder.mainThreadExecutor
            : ContextCompat.getMainExecutor(sourceContext);
    scheduledExecutorPool = checkNotNull(builder.scheduledExecutorPool);
    offloadExecutorPool = checkNotNull(builder.offloadExecutorPool);
    securityPolicy = checkNotNull(builder.securityPolicy);
    defaultTargetUserHandle = builder.defaultTargetUserHandle;
    bindServiceFlags = checkNotNull(builder.bindServiceFlags);
    inboundParcelablePolicy = checkNotNull(builder.inboundParcelablePolicy);
    binderDecorator = checkNotNull(builder.binderDecorator);
    readyTimeoutMillis = builder.readyTimeoutMillis;

    executorService = scheduledExecutorPool.getObject();
    offloadExecutor = offloadExecutorPool.getObject();
  }

  @Override
  public BinderTransport.BinderClientTransport newClientTransport(
      SocketAddress addr, ClientTransportOptions options, ChannelLogger channelLogger) {
    if (closed) {
      throw new IllegalStateException("The transport factory is closed.");
    }
    return new BinderTransport.BinderClientTransport(this, (AndroidComponentAddress) addr, options);
  }

  @Override
  public ScheduledExecutorService getScheduledExecutorService() {
    return executorService;
  }

  @Override
  public SwapChannelCredentialsResult swapChannelCredentials(ChannelCredentials channelCreds) {
    return null;
  }

  @Override
  public void close() {
    closed = true;
    executorService = scheduledExecutorPool.returnObject(executorService);
    offloadExecutor = offloadExecutorPool.returnObject(offloadExecutor);
  }

  @Override
  public Collection<Class<? extends SocketAddress>> getSupportedSocketAddressTypes() {
    return Collections.singleton(AndroidComponentAddress.class);
  }

  /** Allows fluent construction of ClientTransportFactory. */
  public static final class Builder implements ClientTransportFactoryBuilder {
    // Required.
    Context sourceContext;
    ObjectPool<? extends Executor> offloadExecutorPool;

    // Optional.
    BinderChannelCredentials channelCredentials = BinderChannelCredentials.forDefault();
    Executor mainThreadExecutor; // Default filled-in at build time once sourceContext is decided.
    ObjectPool<ScheduledExecutorService> scheduledExecutorPool =
        SharedResourcePool.forResource(GrpcUtil.TIMER_SERVICE);
    SecurityPolicy securityPolicy = SecurityPolicies.internalOnly();
    @Nullable UserHandle defaultTargetUserHandle;
    BindServiceFlags bindServiceFlags = BindServiceFlags.DEFAULTS;
    InboundParcelablePolicy inboundParcelablePolicy = InboundParcelablePolicy.DEFAULT;
    OneWayBinderProxy.Decorator binderDecorator = OneWayBinderProxy.IDENTITY_DECORATOR;
    long readyTimeoutMillis = 60_000;

    @Override
    public BinderClientTransportFactory buildClientTransportFactory() {
      return new BinderClientTransportFactory(this);
    }

    public Builder setSourceContext(Context sourceContext) {
      this.sourceContext = checkNotNull(sourceContext);
      return this;
    }

    public Builder setOffloadExecutorPool(ObjectPool<? extends Executor> offloadExecutorPool) {
      this.offloadExecutorPool = checkNotNull(offloadExecutorPool, "offloadExecutorPool");
      return this;
    }

    public Builder setChannelCredentials(BinderChannelCredentials channelCredentials) {
      this.channelCredentials = checkNotNull(channelCredentials, "channelCredentials");
      return this;
    }

    public Builder setMainThreadExecutor(Executor mainThreadExecutor) {
      this.mainThreadExecutor = checkNotNull(mainThreadExecutor, "mainThreadExecutor");
      return this;
    }

    public Builder setScheduledExecutorPool(
        ObjectPool<ScheduledExecutorService> scheduledExecutorPool) {
      this.scheduledExecutorPool = checkNotNull(scheduledExecutorPool, "scheduledExecutorPool");
      return this;
    }

    public Builder setSecurityPolicy(SecurityPolicy securityPolicy) {
      this.securityPolicy = checkNotNull(securityPolicy, "securityPolicy");
      return this;
    }

    public Builder setDefaultTargetUserHandle(@Nullable UserHandle defaultTargetUserHandle) {
      this.defaultTargetUserHandle = defaultTargetUserHandle;
      return this;
    }

    public Builder setBindServiceFlags(BindServiceFlags bindServiceFlags) {
      this.bindServiceFlags = checkNotNull(bindServiceFlags, "bindServiceFlags");
      return this;
    }

    public Builder setInboundParcelablePolicy(InboundParcelablePolicy inboundParcelablePolicy) {
      this.inboundParcelablePolicy =
          checkNotNull(inboundParcelablePolicy, "inboundParcelablePolicy");
      return this;
    }

    /**
     * Decorates both the "endpoint" and "server" binders, for fault injection.
     *
     * <p>Optional. If absent, these objects will go undecorated.
     */
    public Builder setBinderDecorator(OneWayBinderProxy.Decorator binderDecorator) {
      this.binderDecorator = checkNotNull(binderDecorator, "binderDecorator");
      return this;
    }

    /**
     * Limits how long it can take to for a new transport to become ready after being started.
     *
     * <p>This process currently includes:
     *
     * <ul>
     *   <li>Creating an Android binding.
     *   <li>Waiting for Android to create the server process.
     *   <li>Waiting for the remote Service to be created and handle onBind().
     *   <li>Exchanging handshake transactions according to the wire protocol.
     *   <li>Evaluating a {@link SecurityPolicy} on both sides.
     * </ul>
     *
     * <p>This setting doesn't change the need for deadlines at the call level. It merely ensures
     * that gRPC features like <a
     * href="https://github.com/grpc/grpc/blob/master/doc/load-balancing.md">load balancing</a> and
     * <a href="https://github.com/grpc/grpc/blob/master/doc/wait-for-ready.md">fail-fast</a> work
     * as expected despite certain edge cases that could otherwise stall the transport indefinitely.
     *
     * <p>Optional but enabled by default. Use a negative value to wait indefinitely.
     */
    public Builder setReadyTimeoutMillis(long readyTimeoutMillis) {
      this.readyTimeoutMillis = readyTimeoutMillis;
      return this;
    }
  }
}
