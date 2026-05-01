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
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.SynchronizationContext;
import io.grpc.internal.JsonParser;
import io.grpc.xds.CdsLoadBalancerProvider.CdsConfig;
import java.io.IOException;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CdsLoadBalancerProvider}. */
@RunWith(JUnit4.class)
public class CdsLoadBalancerProviderTest {

  @Test
  public void parseCdsLoadBalancingPolicyConfig() throws IOException {
    CdsLoadBalancerProvider provider = new CdsLoadBalancerProvider();
    String rawCdsLbConfig = "{\n"
        + "  \"cluster\": \"cluster-foo.googleapis.com\"\n"
        + "}";

    @SuppressWarnings("unchecked")
    Map<String, ?> rawLbConfigMap = (Map<String, ?>) JsonParser.parse(rawCdsLbConfig);
    ConfigOrError result = provider.parseLoadBalancingPolicyConfig(rawLbConfigMap);
    assertThat(result.getConfig()).isNotNull();
    CdsConfig config = (CdsConfig) result.getConfig();
    assertThat(config.name).isEqualTo("cluster-foo.googleapis.com");
  }

  @Test
  public void provided() {
    LoadBalancerProvider provider =
        LoadBalancerRegistry.getDefaultRegistry().getProvider(XdsLbPolicies.CDS_POLICY_NAME);
    assertThat(provider).isInstanceOf(CdsLoadBalancerProvider.class);
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
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    LoadBalancerProvider provider = new CdsLoadBalancerProvider();
    LoadBalancer loadBalancer = provider.newLoadBalancer(helper);
    assertThat(loadBalancer).isInstanceOf(CdsLoadBalancer2.class);
  }
}
