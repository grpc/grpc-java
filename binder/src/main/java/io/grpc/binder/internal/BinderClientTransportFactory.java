package io.grpc.binder.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.os.UserHandle;
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
import io.grpc.internal.ConnectionClientTransport;
import io.grpc.internal.ManagedChannelImplBuilder.ClientTransportFactoryBuilder;
import io.grpc.internal.ObjectPool;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;

/**
 * Creates new binder transports.
 */
@Internal
public final class BinderClientTransportFactory implements ClientTransportFactory {
  // Fixed properties of the Channel.

  final Context sourceContext;
  final BinderChannelCredentials channelCredentials;
  final Executor mainThreadExecutor;
  final ObjectPool<ScheduledExecutorService> scheduledExecutorPool;
  final ObjectPool<? extends Executor> offloadExecutorPool;
  final SecurityPolicy securityPolicy;
  @Nullable
  final UserHandle targetUserHandle;
  final BindServiceFlags bindServiceFlags;
  final InboundParcelablePolicy inboundParcelablePolicy;
  final OneWayBinderProxy.Decorator binderDecorator;

  ScheduledExecutorService executorService;
  Executor offloadExecutor;
  private boolean closed;

  private BinderClientTransportFactory(Builder builder) {
    this.sourceContext = checkNotNull(builder.sourceContext);
    this.channelCredentials = checkNotNull(builder.channelCredentials);
    this.mainThreadExecutor = checkNotNull(builder.mainThreadExecutor);
    this.scheduledExecutorPool = checkNotNull(builder.scheduledExecutorPool);
    this.offloadExecutorPool = checkNotNull(builder.offloadExecutorPool);
    this.securityPolicy = checkNotNull(builder.securityPolicy);
    this.targetUserHandle = builder.targetUserHandle;
    this.bindServiceFlags = checkNotNull(builder.bindServiceFlags);
    this.inboundParcelablePolicy = checkNotNull(builder.inboundParcelablePolicy);
    this.binderDecorator = checkNotNull(builder.binderDecorator);

    executorService = scheduledExecutorPool.getObject();
    offloadExecutor = offloadExecutorPool.getObject();
  }

  @Override
  public ConnectionClientTransport newClientTransport(
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

  /**
   * Yes its a factory factory essentially ...
   */
  public static class Builder implements ClientTransportFactoryBuilder {
    Context sourceContext;
    BinderChannelCredentials channelCredentials = BinderChannelCredentials.forDefault();
    Executor mainThreadExecutor;
    ObjectPool<ScheduledExecutorService> scheduledExecutorPool;
    ObjectPool<? extends Executor> offloadExecutorPool;
    SecurityPolicy securityPolicy = SecurityPolicies.internalOnly();
    @Nullable
    UserHandle targetUserHandle;
    BindServiceFlags bindServiceFlags = BindServiceFlags.DEFAULTS;
    InboundParcelablePolicy inboundParcelablePolicy = InboundParcelablePolicy.DEFAULT;
    OneWayBinderProxy.Decorator binderDecorator = OneWayBinderProxy.IDENTITY_DECORATOR;

    @Override
    public BinderClientTransportFactory buildClientTransportFactory() {
      return new BinderClientTransportFactory(this);
    }

    public Builder setSourceContext(Context sourceContext) {
      this.sourceContext = checkNotNull(sourceContext);
      return this;
    }

    public Builder setChannelCredentials(BinderChannelCredentials channelCredentials) {
      this.channelCredentials = checkNotNull(channelCredentials);
      return this;
    }

    public Builder setMainThreadExecutor(Executor mainThreadExecutor) {
      this.mainThreadExecutor = checkNotNull(mainThreadExecutor);
      return this;
    }

    public Builder setScheduledExecutorPool(
        ObjectPool<ScheduledExecutorService> scheduledExecutorPool) {
      this.scheduledExecutorPool = checkNotNull(scheduledExecutorPool);
      return this;
    }

    public Builder setOffloadExecutorPool(
        ObjectPool<? extends Executor> offloadExecutorPool) {
      this.offloadExecutorPool = checkNotNull(offloadExecutorPool);
      return this;
    }

    public Builder setSecurityPolicy(SecurityPolicy securityPolicy) {
      this.securityPolicy = checkNotNull(securityPolicy);
      return this;
    }

    public Builder setTargetUserHandle(@Nullable UserHandle targetUserHandle) {
      this.targetUserHandle = targetUserHandle;
      return this;
    }

    public Builder setBindServiceFlags(BindServiceFlags bindServiceFlags) {
      this.bindServiceFlags = checkNotNull(bindServiceFlags);
      return this;
    }

    public Builder setInboundParcelablePolicy(InboundParcelablePolicy inboundParcelablePolicy) {
      this.inboundParcelablePolicy = checkNotNull(inboundParcelablePolicy);
      return this;
    }

    public Builder setBinderDecorator(OneWayBinderProxy.Decorator binderDecorator) {
      this.binderDecorator = checkNotNull(binderDecorator);
      return this;
    }
  }
}
