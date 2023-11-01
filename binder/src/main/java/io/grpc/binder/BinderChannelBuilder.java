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

import android.content.Context;
import android.os.UserHandle;
import androidx.core.content.ContextCompat;
import com.google.errorprone.annotations.DoNotCall;
import io.grpc.ChannelCredentials;
import io.grpc.ChannelLogger;
import io.grpc.ExperimentalApi;
import io.grpc.ForwardingChannelBuilder;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.binder.internal.BinderTransport;
import io.grpc.internal.ClientTransportFactory;
import io.grpc.internal.ConnectionClientTransport;
import io.grpc.internal.FixedObjectPool;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ManagedChannelImplBuilder;
import io.grpc.internal.ManagedChannelImplBuilder.ClientTransportFactoryBuilder;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourcePool;
import java.net.SocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Builder for a gRPC channel which communicates with an Android bound service.
 *
 * @see <a href="https://developer.android.com/guide/components/bound-services.html">Bound
 *     Services</a>
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8022")
public final class BinderChannelBuilder
    extends ForwardingChannelBuilder<BinderChannelBuilder> {

  /**
   * Creates a channel builder that will bind to a remote Android service.
   *
   * <p>The underlying Android binding will be torn down when the channel becomes idle. This happens
   * after 30 minutes without use by default but can be configured via {@link
   * ManagedChannelBuilder#idleTimeout(long, TimeUnit)} or triggered manually with {@link
   * ManagedChannel#enterIdle()}.
   *
   * <p>You the caller are responsible for managing the lifecycle of any channels built by the
   * resulting builder. They will not be shut down automatically.
   *
   * @param directAddress the {@link AndroidComponentAddress} referencing the service to bind to.
   * @param sourceContext the context to bind from (e.g. The current Activity or Application).
   * @return a new builder
   */
  public static BinderChannelBuilder forAddress(
      AndroidComponentAddress directAddress, Context sourceContext) {
    return new BinderChannelBuilder(
        checkNotNull(directAddress, "directAddress"),
        null,
        sourceContext,
        BinderChannelCredentials.forDefault());
  }

  /**
   * Creates a channel builder that will bind to a remote Android service with provided
   * BinderChannelCredentials.
   *
   * <p>The underlying Android binding will be torn down when the channel becomes idle. This happens
   * after 30 minutes without use by default but can be configured via {@link
   * ManagedChannelBuilder#idleTimeout(long, TimeUnit)} or triggered manually with {@link
   * ManagedChannel#enterIdle()}.
   *
   * <p>You the caller are responsible for managing the lifecycle of any channels built by the
   * resulting builder. They will not be shut down automatically.
   *
   * @param directAddress the {@link AndroidComponentAddress} referencing the service to bind to.
   * @param sourceContext the context to bind from (e.g. The current Activity or Application).
   * @param channelCredentials the arbitrary binder specific channel credentials to be used to
   *     establish a binder connection.
   * @return a new builder
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/10173")
  public static BinderChannelBuilder forAddress(
      AndroidComponentAddress directAddress,
      Context sourceContext,
      BinderChannelCredentials channelCredentials) {
    return new BinderChannelBuilder(
        checkNotNull(directAddress, "directAddress"), null, sourceContext, channelCredentials);
  }

  /**
   * Creates a channel builder that will bind to a remote Android service, via a string
   * target name which will be resolved.
   *
   * <p>The underlying Android binding will be torn down when the channel becomes idle. This happens
   * after 30 minutes without use by default but can be configured via {@link
   * ManagedChannelBuilder#idleTimeout(long, TimeUnit)} or triggered manually with {@link
   * ManagedChannel#enterIdle()}.
   *
   * <p>You the caller are responsible for managing the lifecycle of any channels built by the
   * resulting builder. They will not be shut down automatically.
   *
   * @param target A target uri which should resolve into an {@link AndroidComponentAddress}
   * referencing the service to bind to.
   * @param sourceContext the context to bind from (e.g. The current Activity or Application).
   * @return a new builder
   */
  public static BinderChannelBuilder forTarget(String target, Context sourceContext) {
    return new BinderChannelBuilder(
        null,
        checkNotNull(target, "target"),
        sourceContext,
        BinderChannelCredentials.forDefault());
  }

  /**
   * Creates a channel builder that will bind to a remote Android service, via a string target name
   * which will be resolved.
   *
   * <p>The underlying Android binding will be torn down when the channel becomes idle. This happens
   * after 30 minutes without use by default but can be configured via {@link
   * ManagedChannelBuilder#idleTimeout(long, TimeUnit)} or triggered manually with {@link
   * ManagedChannel#enterIdle()}.
   *
   * <p>You the caller are responsible for managing the lifecycle of any channels built by the
   * resulting builder. They will not be shut down automatically.
   *
   * @param target A target uri which should resolve into an {@link AndroidComponentAddress}
   *     referencing the service to bind to.
   * @param sourceContext the context to bind from (e.g. The current Activity or Application).
   * @param channelCredentials the arbitrary binder specific channel credentials to be used to
   *     establish a binder connection.
   * @return a new builder
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/10173")
  public static BinderChannelBuilder forTarget(
      String target, Context sourceContext, BinderChannelCredentials channelCredentials) {
    return new BinderChannelBuilder(
        null, checkNotNull(target, "target"), sourceContext, channelCredentials);
  }

  /**
   * Always fails. Call {@link #forAddress(AndroidComponentAddress, Context)} instead.
   */
  @DoNotCall("Unsupported. Use forAddress(AndroidComponentAddress, Context) instead")
  public static BinderChannelBuilder forAddress(String name, int port) {
    throw new UnsupportedOperationException(
        "call forAddress(AndroidComponentAddress, Context) instead");
  }

  /**
   * Always fails. Call {@link #forAddress(AndroidComponentAddress, Context)} instead.
   */
  @DoNotCall("Unsupported. Use forTarget(String, Context) instead")
  public static BinderChannelBuilder forTarget(String target) {
    throw new UnsupportedOperationException(
        "call forAddress(AndroidComponentAddress, Context) instead");
  }

  private final ManagedChannelImplBuilder managedChannelImplBuilder;

  private Executor mainThreadExecutor;
  private ObjectPool<ScheduledExecutorService> schedulerPool =
      SharedResourcePool.forResource(GrpcUtil.TIMER_SERVICE);
  private SecurityPolicy securityPolicy;
  private InboundParcelablePolicy inboundParcelablePolicy;
  private BindServiceFlags bindServiceFlags;
  @Nullable private UserHandle targetUserHandle;
  private boolean strictLifecycleManagement;

  private BinderChannelBuilder(
      @Nullable AndroidComponentAddress directAddress,
      @Nullable String target,
      Context sourceContext,
      BinderChannelCredentials channelCredentials) {
    mainThreadExecutor =
        ContextCompat.getMainExecutor(checkNotNull(sourceContext, "sourceContext"));
    securityPolicy = SecurityPolicies.internalOnly();
    inboundParcelablePolicy = InboundParcelablePolicy.DEFAULT;
    bindServiceFlags = BindServiceFlags.DEFAULTS;

    final class BinderChannelTransportFactoryBuilder
        implements ClientTransportFactoryBuilder {
      @Override
      public ClientTransportFactory buildClientTransportFactory() {
        return new TransportFactory(
            sourceContext,
            channelCredentials,
            mainThreadExecutor,
            schedulerPool,
            managedChannelImplBuilder.getOffloadExecutorPool(),
            securityPolicy,
            targetUserHandle,
            bindServiceFlags,
            inboundParcelablePolicy);
      }
    }

    if (directAddress != null) {
      managedChannelImplBuilder =
          new ManagedChannelImplBuilder(
              directAddress,
              directAddress.getAuthority(),
              new BinderChannelTransportFactoryBuilder(),
              null);
    } else {
      managedChannelImplBuilder =
          new ManagedChannelImplBuilder(
              target,
              new BinderChannelTransportFactoryBuilder(),
              null);
    }
    idleTimeout(60, TimeUnit.SECONDS);
  }

  @Override
  protected ManagedChannelBuilder<?> delegate() {
    return managedChannelImplBuilder;
  }

  /** Specifies certain optional aspects of the underlying Android Service binding. */
  public BinderChannelBuilder setBindServiceFlags(BindServiceFlags bindServiceFlags) {
    this.bindServiceFlags = bindServiceFlags;
    return this;
  }

  /**
   * Provides a custom scheduled executor service.
   *
   * <p>This is an optional parameter. If the user has not provided a scheduled executor service
   * when the channel is built, the builder will use a static cached thread pool.
   *
   * @return this
   */
  public BinderChannelBuilder scheduledExecutorService(
      ScheduledExecutorService scheduledExecutorService) {
   schedulerPool =
        new FixedObjectPool<>(checkNotNull(scheduledExecutorService, "scheduledExecutorService"));
    return this;
  }

  /**
   * Provides a custom {@link Executor} for accessing this application's main thread.
   *
   * <p>Optional. A default implementation will be used if no custom Executor is provided.
   *
   * @return this
   */
  public BinderChannelBuilder mainThreadExecutor(Executor mainThreadExecutor) {
    this.mainThreadExecutor = mainThreadExecutor;
    return this;
  }

  /**
   * Provides a custom security policy.
   *
   * <p>This is optional. If the user has not provided a security policy, this channel will only
   * communicate with the same application UID.
   *
   * @return this
   */
  public BinderChannelBuilder securityPolicy(SecurityPolicy securityPolicy) {
    this.securityPolicy = checkNotNull(securityPolicy, "securityPolicy");
    return this;
  }

/**
   * Provides the target {@UserHandle} of the remote Android service.
   *
   * <p>When targetUserHandle is set, Context.bindServiceAsUser will used and additional Android
   * permissions will be required. If your usage does not require cross-user communications, please
   * do not set this field. It is the caller's responsibility to make sure that it holds the
   * corresponding permissions.
   *
   * @param targetUserHandle the target user to bind into.
   * @return this
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/10173")
  public BinderChannelBuilder bindAsUser(UserHandle targetUserHandle) {
    this.targetUserHandle = targetUserHandle;
    return this;
  }

  /** Sets the policy for inbound parcelable objects. */
  public BinderChannelBuilder inboundParcelablePolicy(
      InboundParcelablePolicy inboundParcelablePolicy) {
    this.inboundParcelablePolicy = checkNotNull(inboundParcelablePolicy, "inboundParcelablePolicy");
    return this;
  }

  /** 
   * Disables the channel idle timeout and prevents it from being enabled. This
   * allows a centralized application method to configure the channel builder
   * and return it, without worrying about another part of the application
   * accidentally enabling the idle timeout.
   */
  public BinderChannelBuilder strictLifecycleManagement() {
    strictLifecycleManagement = true;
    super.idleTimeout(1000, TimeUnit.DAYS); // >30 days disables timeouts entirely.
    return this;
  }

  @Override
  public BinderChannelBuilder idleTimeout(long value, TimeUnit unit) {
    checkState(!strictLifecycleManagement, "Idle timeouts are not supported when strict lifecycle management is enabled");
    super.idleTimeout(value, unit);
    return this;
  }

  /** Creates new binder transports. */
  private static final class TransportFactory implements ClientTransportFactory {
    private final Context sourceContext;
    private final BinderChannelCredentials channelCredentials;
    private final Executor mainThreadExecutor;
    private final ObjectPool<ScheduledExecutorService> scheduledExecutorPool;
    private final ObjectPool<? extends Executor> offloadExecutorPool;
    private final SecurityPolicy securityPolicy;
    @Nullable private final UserHandle targetUserHandle;
    private final BindServiceFlags bindServiceFlags;
    private final InboundParcelablePolicy inboundParcelablePolicy;

    private ScheduledExecutorService executorService;
    private Executor offloadExecutor;
    private boolean closed;

    TransportFactory(
        Context sourceContext,
        BinderChannelCredentials channelCredentials,
        Executor mainThreadExecutor,
        ObjectPool<ScheduledExecutorService> scheduledExecutorPool,
        ObjectPool<? extends Executor> offloadExecutorPool,
        SecurityPolicy securityPolicy,
        @Nullable UserHandle targetUserHandle,
        BindServiceFlags bindServiceFlags,
        InboundParcelablePolicy inboundParcelablePolicy) {
      this.sourceContext = sourceContext;
      this.channelCredentials = channelCredentials;
      this.mainThreadExecutor = mainThreadExecutor;
      this.scheduledExecutorPool = scheduledExecutorPool;
      this.offloadExecutorPool = offloadExecutorPool;
      this.securityPolicy = securityPolicy;
      this.targetUserHandle = targetUserHandle;
      this.bindServiceFlags = bindServiceFlags;
      this.inboundParcelablePolicy = inboundParcelablePolicy;

      executorService = scheduledExecutorPool.getObject();
      offloadExecutor = offloadExecutorPool.getObject();
    }

    @Override
    public ConnectionClientTransport newClientTransport(
        SocketAddress addr, ClientTransportOptions options, ChannelLogger channelLogger) {
      if (closed) {
        throw new IllegalStateException("The transport factory is closed.");
      }
      return new BinderTransport.BinderClientTransport(
          sourceContext,
          channelCredentials,
          (AndroidComponentAddress) addr,
          targetUserHandle,
          bindServiceFlags,
          mainThreadExecutor,
          scheduledExecutorPool,
          offloadExecutorPool,
          securityPolicy,
          inboundParcelablePolicy,
          options.getEagAttributes());
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
  }
}
