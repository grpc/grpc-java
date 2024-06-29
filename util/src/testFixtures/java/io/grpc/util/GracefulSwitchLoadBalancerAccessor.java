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

package io.grpc.util;

import io.grpc.LoadBalancerProvider;

/**
 * Accessors for white-box testing involving GracefulSwitchLoadBalancer.
 */
public final class GracefulSwitchLoadBalancerAccessor {
  private GracefulSwitchLoadBalancerAccessor() {
    // Do not instantiate
  }

  public static LoadBalancerProvider getChildProvider(Object config) {
    return (LoadBalancerProvider) ((GracefulSwitchLoadBalancer.Config) config).childFactory;
  }

  public static Object getChildConfig(Object config) {
    return ((GracefulSwitchLoadBalancer.Config) config).childConfig;
  }
}
