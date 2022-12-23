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

import io.grpc.ChannelLogger;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ServiceConfigParser;
import io.grpc.NameResolverRegistry;
import io.grpc.SynchronizationContext;
import io.grpc.internal.FakeClock;
import io.grpc.internal.GrpcUtil;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ClusterResolverLoadBalancerProvider}. */
@RunWith(JUnit4.class)
public class ClusterResolverLoadBalancerProviderTest {

  @Test
  public void provided() {
    LoadBalancerProvider provider =
        LoadBalancerRegistry.getDefaultRegistry().getProvider(
            XdsLbPolicies.CLUSTER_RESOLVER_POLICY_NAME);
    assertThat(provider).isInstanceOf(ClusterResolverLoadBalancerProvider.class);
  }

  @Test
  public void providesLoadBalancer()  {
    Helper helper = mock(Helper.class);

    SynchronizationContext syncContext = new SynchronizationContext(
        new Thread.UncaughtExceptionHandler() {
          @Override
          public void uncaughtException(Thread t, Throwable e) {
            throw new AssertionError(e);
          }
        });
    FakeClock fakeClock = new FakeClock();
    NameResolverRegistry nsRegistry = new NameResolverRegistry();
    NameResolver.Args args = NameResolver.Args.newBuilder()
        .setDefaultPort(8080)
        .setProxyDetector(GrpcUtil.NOOP_PROXY_DETECTOR)
        .setSynchronizationContext(syncContext)
        .setServiceConfigParser(mock(ServiceConfigParser.class))
        .setChannelLogger(mock(ChannelLogger.class))
        .build();
    when(helper.getNameResolverRegistry()).thenReturn(nsRegistry);
    when(helper.getNameResolverArgs()).thenReturn(args);
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    when(helper.getScheduledExecutorService()).thenReturn(fakeClock.getScheduledExecutorService());
    when(helper.getAuthority()).thenReturn("api.google.com");
    LoadBalancerProvider provider = new ClusterResolverLoadBalancerProvider();
    LoadBalancer loadBalancer = provider.newLoadBalancer(helper);
    assertThat(loadBalancer).isInstanceOf(ClusterResolverLoadBalancer.class);
  }
}
