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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;

import io.grpc.LoadBalancerRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link EdsLoadBalancerProvider}. */
@RunWith(JUnit4.class)
public class EdsLoadBalancerProviderTest {
  private final EdsLoadBalancerProvider provider = new EdsLoadBalancerProvider();

  @Test
  public void isAvailable() {
    assertThat(provider.isAvailable()).isTrue();
  }

  @Test
  public void provided() {
    LoadBalancerRegistry lbRegistry = LoadBalancerRegistry.getDefaultRegistry();
    assertThat(lbRegistry.getProvider(XdsLbPolicies.EDS_POLICY_NAME)).isNotNull();
  }
}
