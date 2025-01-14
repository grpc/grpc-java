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

package io.grpc;

import com.google.common.base.MoreObjects;
import com.google.errorprone.annotations.DoNotCall;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * A {@link ManagedChannelBuilder} that delegates all its builder methods to another builder by
 * default.
 *
 * <p>Always choose this over {@link ForwardingChannelBuilder}, because
 * {@link ForwardingChannelBuilder2} is ABI-safe.
 *
 * @param <T> The type of the subclass extending this abstract class.
 * @since 1.59.0
 */
public abstract class ForwardingChannelBuilder2<T extends ManagedChannelBuilder<T>>
    extends ManagedChannelBuilder<T> {

  /**
   * The default constructor.
   */
  protected ForwardingChannelBuilder2() {
  }

  /**
   * This method serves to force subclasses to "hide" this static factory.
   */
  @DoNotCall("Unsupported")
  public static ManagedChannelBuilder<?> forAddress(String name, int port) {
    throw new UnsupportedOperationException("Subclass failed to hide static factory");
  }

  /**
   * This method serves to force subclasses to "hide" this static factory.
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
  protected T interceptWithTarget(InterceptorFactory factory) {
    delegate().interceptWithTarget(factory);
    return thisT();
  }

  @Override
  public T addTransportFilter(ClientTransportFilter transportFilter) {
    delegate().addTransportFilter(transportFilter);
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
    delegate().maxInboundMessageSize(max);
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

  @Override
  protected T addMetricSink(MetricSink metricSink) {
    delegate().addMetricSink(metricSink);
    return thisT();
  }

  @Override
  public <X> T setNameResolverArg(NameResolver.Args.Key<X> key, X value) {
    delegate().setNameResolverArg(key, value);
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
  private T thisT() {
    @SuppressWarnings("unchecked")
    T thisT = (T) this;
    return thisT;
  }
}
