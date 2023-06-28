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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.InternalServiceProviders;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.SynchronizationContext;
import io.grpc.internal.FakeClock;
import io.grpc.internal.JsonParser;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.WeightedRoundRobinLoadBalancerConfig;
import java.io.IOException;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link WeightedRoundRobinLoadBalancerProvider}. */
@RunWith(JUnit4.class)
public class WeightedRoundRobinLoadBalancerProviderTest {

  private final WeightedRoundRobinLoadBalancerProvider provider =
          new WeightedRoundRobinLoadBalancerProvider();

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });

  @Test
  public void provided() {
    for (LoadBalancerProvider current : InternalServiceProviders.getCandidatesViaServiceLoader(
            LoadBalancerProvider.class, getClass().getClassLoader())) {
      if (current instanceof WeightedRoundRobinLoadBalancerProvider) {
        return;
      }
    }
    fail("WeightedRoundRobinLoadBalancerProvider not registered");
  }

  @Test
  public void providesLoadBalancer() {
    LoadBalancer.Helper helper = mock(LoadBalancer.Helper.class);
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    when(helper.getScheduledExecutorService()).thenReturn(
            new FakeClock().getScheduledExecutorService());
    assertThat(provider.newLoadBalancer(helper))
            .isInstanceOf(WeightedRoundRobinLoadBalancer.class);
  }

  @Test
  public void parseLoadBalancingConfig() throws IOException {
    String lbConfig =
        "{\"blackoutPeriod\" : \"20s\","
        + " \"weightExpirationPeriod\" : \"300s\","
        + " \"oobReportingPeriod\" : \"100s\","
        + " \"enableOobLoadReport\" : true,"
        + " \"weightUpdatePeriod\" : \"2s\","
        + " \"errorUtilizationPenalty\" : \"1.75\""
        + " }";

    ConfigOrError configOrError = provider.parseLoadBalancingPolicyConfig(
            parseJsonObject(lbConfig));
    assertThat(configOrError.getConfig()).isNotNull();
    WeightedRoundRobinLoadBalancerConfig config =
            (WeightedRoundRobinLoadBalancerConfig) configOrError.getConfig();
    assertThat(config.blackoutPeriodNanos).isEqualTo(20_000_000_000L);
    assertThat(config.weightExpirationPeriodNanos).isEqualTo(300_000_000_000L);
    assertThat(config.oobReportingPeriodNanos).isEqualTo(100_000_000_000L);
    assertThat(config.enableOobLoadReport).isEqualTo(true);
    assertThat(config.weightUpdatePeriodNanos).isEqualTo(2_000_000_000L);
    assertThat(config.errorUtilizationPenalty).isEqualTo(1.75F);
  }

  @Test
  public void parseLoadBalancingConfigDefaultValues() throws IOException {
    String lbConfig = "{\"weightUpdatePeriod\" : \"0.02s\"}";

    ConfigOrError configOrError = provider.parseLoadBalancingPolicyConfig(
            parseJsonObject(lbConfig));
    assertThat(configOrError.getConfig()).isNotNull();
    WeightedRoundRobinLoadBalancerConfig config =
            (WeightedRoundRobinLoadBalancerConfig) configOrError.getConfig();
    assertThat(config.blackoutPeriodNanos).isEqualTo(10_000_000_000L);
    assertThat(config.weightExpirationPeriodNanos).isEqualTo(180_000_000_000L);
    assertThat(config.enableOobLoadReport).isEqualTo(false);
    assertThat(config.weightUpdatePeriodNanos).isEqualTo(100_000_000L);
    assertThat(config.errorUtilizationPenalty).isEqualTo(1.0F);
  }


  @SuppressWarnings("unchecked")
  private static Map<String, ?> parseJsonObject(String json) throws IOException {
    return (Map<String, ?>) JsonParser.parse(json);
  }
}
