/*
 * Copyright 2019 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import java.util.Map;

/** The library built-in implementation of service config parser. */
@VisibleForTesting
public final class ScParser extends NameResolver.ServiceConfigParser {

  private final boolean retryEnabled;
  private final int maxRetryAttemptsLimit;
  private final int maxHedgedAttemptsLimit;
  private final AutoConfiguredLoadBalancerFactory autoLoadBalancerFactory;

  /** Creates a parse with global retry settings and an auto configured lb factory. */
  public ScParser(
      boolean retryEnabled,
      int maxRetryAttemptsLimit,
      int maxHedgedAttemptsLimit,
      AutoConfiguredLoadBalancerFactory autoLoadBalancerFactory) {
    this.retryEnabled = retryEnabled;
    this.maxRetryAttemptsLimit = maxRetryAttemptsLimit;
    this.maxHedgedAttemptsLimit = maxHedgedAttemptsLimit;
    this.autoLoadBalancerFactory = checkNotNull(autoLoadBalancerFactory, "autoLoadBalancerFactory");
  }

  @Override
  public ConfigOrError parseServiceConfig(Map<String, ?> rawServiceConfig) {
    try {
      Object loadBalancingPolicySelection;
      ConfigOrError choiceFromLoadBalancer =
          autoLoadBalancerFactory.parseLoadBalancerPolicy(rawServiceConfig);
      if (choiceFromLoadBalancer == null) {
        loadBalancingPolicySelection = null;
      } else if (choiceFromLoadBalancer.getError() != null) {
        return ConfigOrError.fromError(choiceFromLoadBalancer.getError());
      } else {
        loadBalancingPolicySelection = choiceFromLoadBalancer.getConfig();
      }
      return ConfigOrError.fromConfig(
          ManagedChannelServiceConfig.fromServiceConfig(
              rawServiceConfig,
              retryEnabled,
              maxRetryAttemptsLimit,
              maxHedgedAttemptsLimit,
              loadBalancingPolicySelection));
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(
          Status.UNKNOWN.withDescription("failed to parse service config").withCause(e));
    }
  }
}
