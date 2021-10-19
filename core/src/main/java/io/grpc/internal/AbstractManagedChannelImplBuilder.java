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

package io.grpc.internal;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.errorprone.annotations.DoNotCall;
import io.grpc.BinaryLog;
import io.grpc.ClientInterceptor;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.NameResolver;
import io.grpc.ProxyDetector;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Temporarily duplicates {@link io.grpc.ForwardingChannelBuilder} to fix ABI backward
 * compatibility.
 *
 * @param <T> The concrete type of this builder.
 * @see <a href="https://github.com/grpc/grpc-java/issues/7211">grpc/grpc-java#7211</a>
 */
public abstract class AbstractManagedChannelImplBuilder
    <T extends AbstractManagedChannelImplBuilder<T>> extends ManagedChannelBuilder<T> {

  /**
   * Added for ABI compatibility.
   *
   * <p>See details in {@link #maxInboundMessageSize(int)}.
   * TODO(sergiitk): move back to concrete classes as a private field, when this class is removed.
   */
  protected int maxInboundMessageSize = GrpcUtil.DEFAULT_MAX_MESSAGE_SIZE;

  /**
   * The default constructor.
   */
  protected AbstractManagedChannelImplBuilder() {}

  /**
   * This method serves to force sub classes to "hide" this static factory.
   */
  @DoNotCall("Unsupported")
  public static ManagedChannelBuilder<?> forAddress(String name, int port) {
    throw new UnsupportedOperationException("Subclass failed to hide static factory");
  }

  /**
   * This method serves to force sub classes to "hide" this static factory.
   */
  @DoNotCall("Unsupported")
  public static ManagedChannelBuilder<?> forTarget(String target) {
    throw new UnsupportedOperationException("Subclass failed to hide static factory");
  }

  /**
   * Returns the delegated {@code ManagedChannelBuilder}.
   */
  protected abstract ManagedChannelBuilder<?> delegate();

  @Override
  public T directExecutor() {
    delegate().directExecutor();
    return thisT();
  }

  @Override
  public T executor(Executor executor) {
    delegate().executor(executor);
    return thisT();
  }

  @Override
  public T offloadExecutor(Executor executor) {
    delegate().offloadExecutor(executor);
    return thisT();
  }

  @Override
  public T intercept(List<ClientInterceptor> interceptors) {
    delegate().intercept(interceptors);
    return thisT();
  }

  @Override
  public T intercept(ClientInterceptor... interceptors) {
    delegate().intercept(interceptors);
    return thisT();
  }

  @Override
  public T userAgent(String userAgent) {
    delegate().userAgent(userAgent);
    return thisT();
  }

  @Override
  public T overrideAuthority(String authority) {
    delegate().overrideAuthority(authority);
    return thisT();
  }

  @Override
  public T usePlaintext() {
    delegate().usePlaintext();
    return thisT();
  }

  @Override
  public T useTransportSecurity() {
    delegate().useTransportSecurity();
    return thisT();
  }

  @Deprecated
  @Override
  public T nameResolverFactory(NameResolver.Factory resolverFactory) {
    delegate().nameResolverFactory(resolverFactory);
    return thisT();
  }

  @Override
  public T defaultLoadBalancingPolicy(String policy) {
    delegate().defaultLoadBalancingPolicy(policy);
    return thisT();
  }

  @Override
  public T enableFullStreamDecompression() {
    delegate().enableFullStreamDecompression();
    return thisT();
  }

  @Override
  public T decompressorRegistry(DecompressorRegistry registry) {
    delegate().decompressorRegistry(registry);
    return thisT();
  }

  @Override
  public T compressorRegistry(CompressorRegistry registry) {
    delegate().compressorRegistry(registry);
    return thisT();
  }

  @Override
  public T idleTimeout(long value, TimeUnit unit) {
    delegate().idleTimeout(value, unit);
    return thisT();
  }

  @Override
  public T maxInboundMessageSize(int max) {
    /*
     Why this method is not delegating, as the rest of the methods?

     In refactoring described in #7211, the implementation of #maxInboundMessageSize(int)
     (and its corresponding field) was pulled down from internal AbstractManagedChannelImplBuilder
     to concrete classes that actually enforce this setting. For the same reason, it wasn't ported
     to ManagedChannelImplBuilder (the #delegate()).

     Then AbstractManagedChannelImplBuilder was brought back to fix ABI backward compatibility,
     and temporarily turned into a ForwardingChannelBuilder, ref PR #7564. Eventually it will
     be deleted, after a period with "bridge" ABI solution introduced in #7834.

     However, restoring AbstractManagedChannelImplBuilder unintentionally made ABI of
     #maxInboundMessageSize(int) implemented by the concrete classes backward incompatible:
     pre-refactoring builds expect it to be a method of AbstractManagedChannelImplBuilder,
     and not concrete classes, ref #8313.

     The end goal is to keep #maxInboundMessageSize(int) only in concrete classes that enforce it.
     To fix method's ABI, we temporary reintroduce it to the original layer it was removed from:
     AbstractManagedChannelImplBuilder. This class' only intention is to provide short-term
     ABI compatibility. Once we move forward with dropping the ABI, both fixes are no longer
     necessary, and both will perish with removing AbstractManagedChannelImplBuilder.
    */
    Preconditions.checkArgument(max >= 0, "negative max");
    maxInboundMessageSize = max;
    return thisT();
  }

  @Override
  public T maxInboundMetadataSize(int max) {
    delegate().maxInboundMetadataSize(max);
    return thisT();
  }

  @Override
  public T keepAliveTime(long keepAliveTime, TimeUnit timeUnit) {
    delegate().keepAliveTime(keepAliveTime, timeUnit);
    return thisT();
  }

  @Override
  public T keepAliveTimeout(long keepAliveTimeout, TimeUnit timeUnit) {
    delegate().keepAliveTimeout(keepAliveTimeout, timeUnit);
    return thisT();
  }

  @Override
  public T keepAliveWithoutCalls(boolean enable) {
    delegate().keepAliveWithoutCalls(enable);
    return thisT();
  }

  @Override
  public T maxRetryAttempts(int maxRetryAttempts) {
    delegate().maxRetryAttempts(maxRetryAttempts);
    return thisT();
  }

  @Override
  public T maxHedgedAttempts(int maxHedgedAttempts) {
    delegate().maxHedgedAttempts(maxHedgedAttempts);
    return thisT();
  }

  @Override
  public T retryBufferSize(long bytes) {
    delegate().retryBufferSize(bytes);
    return thisT();
  }

  @Override
  public T perRpcBufferLimit(long bytes) {
    delegate().perRpcBufferLimit(bytes);
    return thisT();
  }

  @Override
  public T disableRetry() {
    delegate().disableRetry();
    return thisT();
  }

  @Override
  public T enableRetry() {
    delegate().enableRetry();
    return thisT();
  }

  @Override
  public T setBinaryLog(BinaryLog binaryLog) {
    delegate().setBinaryLog(binaryLog);
    return thisT();
  }

  @Override
  public T maxTraceEvents(int maxTraceEvents) {
    delegate().maxTraceEvents(maxTraceEvents);
    return thisT();
  }

  @Override
  public T proxyDetector(ProxyDetector proxyDetector) {
    delegate().proxyDetector(proxyDetector);
    return thisT();
  }

  @Override
  public T defaultServiceConfig(@Nullable Map<String, ?> serviceConfig) {
    delegate().defaultServiceConfig(serviceConfig);
    return thisT();
  }

  @Override
  public T disableServiceConfigLookUp() {
    delegate().disableServiceConfigLookUp();
    return thisT();
  }

  /**
   * Returns the {@link ManagedChannel} built by the delegate by default. Overriding method can
   * return different value.
   */
  @Override
  public ManagedChannel build() {
    return delegate().build();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("delegate", delegate()).toString();
  }

  /**
   * Returns the correctly typed version of the builder.
   */
  protected final T thisT() {
    @SuppressWarnings("unchecked")
    T thisT = (T) this;
    return thisT;
  }
}
