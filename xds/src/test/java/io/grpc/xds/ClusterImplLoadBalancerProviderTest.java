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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link ClusterImplLoadBalancerProvider}.
 */
@RunWith(JUnit4.class)
public class ClusterImplLoadBalancerProviderTest {

  @Test
  public void provided() {
    LoadBalancerProvider provider =
        LoadBalancerRegistry.getDefaultRegistry().getProvider(
            XdsLbPolicies.CLUSTER_IMPL_POLICY_NAME);
    assertThat(provider).isInstanceOf(ClusterImplLoadBalancerProvider.class);
  }

  @Test
  public void providesLoadBalancer()  {
    Helper helper = mock(Helper.class);
    when(helper.getAuthority()).thenReturn("api.google.com");
    LoadBalancerProvider provider = new ClusterImplLoadBalancerProvider();
    LoadBalancer loadBalancer = provider.newLoadBalancer(helper);
    assertThat(loadBalancer).isInstanceOf(ClusterImplLoadBalancer.class);
  }
}
