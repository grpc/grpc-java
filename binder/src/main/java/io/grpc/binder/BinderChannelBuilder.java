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
import androidx.annotation.RequiresApi;
import com.google.errorprone.annotations.DoNotCall;
import io.grpc.ExperimentalApi;
import io.grpc.ForwardingChannelBuilder;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.binder.internal.BinderClientTransportFactory;
import io.grpc.internal.FixedObjectPool;
import io.grpc.internal.ManagedChannelImplBuilder;
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
public final class BinderChannelBuilder extends ForwardingChannelBuilder<BinderChannelBuilder> {

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
   * @return a new builder
   */
  public static BinderChannelBuilder forTarget(String target, Context sourceContext) {
    return new BinderChannelBuilder(
        null, checkNotNull(target, "target"), sourceContext, BinderChannelCredentials.forDefault());
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

  /** Always fails. Call {@link #forAddress(AndroidComponentAddress, Context)} instead. */
  @DoNotCall("Unsupported. Use forAddress(AndroidComponentAddress, Context) instead")
  public static BinderChannelBuilder forAddress(String name, int port) {
    throw new UnsupportedOperationException(
        "call forAddress(AndroidComponentAddress, Context) instead");
  }

  /** Always fails. Call {@link #forAddress(AndroidComponentAddress, Context)} instead. */
  @DoNotCall("Unsupported. Use forTarget(String, Context) instead")
  public static BinderChannelBuilder forTarget(String target) {
    throw new UnsupportedOperationException(
        "call forAddress(AndroidComponentAddress, Context) instead");
  }

  private final ManagedChannelImplBuilder managedChannelImplBuilder;
  private final BinderClientTransportFactory.Builder transportFactoryBuilder;

  private boolean strictLifecycleManagement;

  private BinderChannelBuilder(
      @Nullable AndroidComponentAddress directAddress,
      @Nullable String target,
      Context sourceContext,
      BinderChannelCredentials channelCredentials) {
    transportFactoryBuilder =
        new BinderClientTransportFactory.Builder()
            .setSourceContext(sourceContext)
            .setChannelCredentials(channelCredentials);

    if (directAddress != null) {
      managedChannelImplBuilder =
          new ManagedChannelImplBuilder(
              directAddress, directAddress.getAuthority(), transportFactoryBuilder, null);
    } else {
      managedChannelImplBuilder =
          new ManagedChannelImplBuilder(target, transportFactoryBuilder, null);
    }
    idleTimeout(60, TimeUnit.SECONDS);
  }

  @Override
  @SuppressWarnings("deprecation") // Not extending ForwardingChannelBuilder2 to preserve ABI.
  protected ManagedChannelBuilder<?> delegate() {
    return managedChannelImplBuilder;
  }

  /** Specifies certain optional aspects of the underlying Android Service binding. */
  public BinderChannelBuilder setBindServiceFlags(BindServiceFlags bindServiceFlags) {
    transportFactoryBuilder.setBindServiceFlags(bindServiceFlags);
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
    transportFactoryBuilder.setScheduledExecutorPool(
        new FixedObjectPool<>(checkNotNull(scheduledExecutorService, "scheduledExecutorService")));
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
    transportFactoryBuilder.setMainThreadExecutor(mainThreadExecutor);
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
    transportFactoryBuilder.setSecurityPolicy(securityPolicy);
    return this;
  }

  /**
   * Specifies the {@link UserHandle} to be searched for the remote Android Service by default.
   *
   * <p>Used only as a fallback if the direct or resolved {@link AndroidComponentAddress} doesn't
   * specify a {@link UserHandle}. If neither the Channel nor the {@link AndroidComponentAddress}
   * specifies a target user, the {@link UserHandle} of the current process will be used.
   *
   * <p>Targeting a Service in a different Android user is uncommon and requires special permissions
   * normally reserved for system apps. See {@link android.content.Context#bindServiceAsUser} for
   * details.
   *
   * @deprecated This method's name is misleading because it implies an impersonated client identity
   *     when it's actually specifying part of the server's location. It's also no longer necessary
   *     since the target user is part of {@link AndroidComponentAddress}. Prefer to specify target
   *     user in the address instead, either directly or via a {@link io.grpc.NameResolverProvider}.
   * @param targetUserHandle the target user to bind into.
   * @return this
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/10173")
  @RequiresApi(30)
  @Deprecated
  public BinderChannelBuilder bindAsUser(UserHandle targetUserHandle) {
    transportFactoryBuilder.setDefaultTargetUserHandle(targetUserHandle);
    return this;
  }

  /** Sets the policy for inbound parcelable objects. */
  public BinderChannelBuilder inboundParcelablePolicy(
      InboundParcelablePolicy inboundParcelablePolicy) {
    transportFactoryBuilder.setInboundParcelablePolicy(inboundParcelablePolicy);
    return this;
  }

  /**
   * Disables the channel idle timeout and prevents it from being enabled. This allows a centralized
   * application method to configure the channel builder and return it, without worrying about
   * another part of the application accidentally enabling the idle timeout.
   */
  public BinderChannelBuilder strictLifecycleManagement() {
    strictLifecycleManagement = true;
    super.idleTimeout(1000, TimeUnit.DAYS); // >30 days disables timeouts entirely.
    return this;
  }

  @Override
  public BinderChannelBuilder idleTimeout(long value, TimeUnit unit) {
    checkState(
        !strictLifecycleManagement,
        "Idle timeouts are not supported when strict lifecycle management is enabled");
    super.idleTimeout(value, unit);
    return this;
  }

  @Override
  public ManagedChannel build() {
    transportFactoryBuilder.setOffloadExecutorPool(
        managedChannelImplBuilder.getOffloadExecutorPool());
    return super.build();
  }
}
