/*
 * Copyright 2018 The gRPC Authors
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

package io.grpc.services;

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.ExperimentalApi;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Factory;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.TimeProvider;

/**
 * A LoadBalancerProvider that wraps another provider and adds <a
 * href="https://github.com/grpc/proposal/blob/master/A17-client-side-health-checking.md">client-side
 * health checking</a>.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/5025")
public abstract class HealthCheckingLoadBalancerProvider extends LoadBalancerProvider {
  private final LoadBalancerProvider delegate;
  private final Factory healthCheckFactory;

  /**
   * Pass in the base LoadBalancer implementation.
   */
  protected HealthCheckingLoadBalancerProvider(LoadBalancerProvider delegate) {
    this.delegate = checkNotNull(delegate, "delegate");
    this.healthCheckFactory =
        new HealthCheckingLoadBalancerFactory(
            delegate, new ExponentialBackoffPolicy.Provider(), TimeProvider.SYSTEM_TIME_PROVIDER);
  }

  /**
   * Default implementation delegates to the base provider.
   */
  @Override
  public boolean isAvailable() {
    return delegate.isAvailable();
  }

  /**
   * Default implementation returns the base provider's priority plus 1.
   */
  @Override
  public int getPriority() {
    return delegate.getPriority() + 1;
  }

  /**
   * Default implementation returns the same policy name as the base provider.
   */
  @Override
  public String getPolicyName() {
    return delegate.getPolicyName();
  }

  @Override
  public final LoadBalancer newLoadBalancer(Helper helper) {
    return healthCheckFactory.newLoadBalancer(helper);
  }
}
