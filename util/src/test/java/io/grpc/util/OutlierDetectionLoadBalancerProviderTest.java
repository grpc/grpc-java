/*
 * Copyright 2022 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.ChannelLogger;
import io.grpc.InternalServiceProviders;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.SynchronizationContext;
import io.grpc.internal.JsonParser;
import io.grpc.util.OutlierDetectionLoadBalancer.OutlierDetectionLoadBalancerConfig;
import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link OutlierDetectionLoadBalancerProvider}.
 */
@RunWith(JUnit4.class)
public class OutlierDetectionLoadBalancerProviderTest {

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final OutlierDetectionLoadBalancerProvider provider
      = new OutlierDetectionLoadBalancerProvider();

  @Test
  public void provided() {
    for (LoadBalancerProvider current : InternalServiceProviders.getCandidatesViaServiceLoader(
        LoadBalancerProvider.class, getClass().getClassLoader())) {
      if (current instanceof OutlierDetectionLoadBalancerProvider) {
        return;
      }
    }
    fail("OutlierDetectionLoadBalancerProvider not registered");
  }

  @Test
  public void providesLoadBalancer() {
    Helper helper = mock(Helper.class);
    ChannelLogger channelLogger = mock(ChannelLogger.class);

    when(helper.getChannelLogger()).thenReturn(channelLogger);
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    when(helper.getScheduledExecutorService()).thenReturn(mock(ScheduledExecutorService.class));
    assertThat(provider.newLoadBalancer(helper))
        .isInstanceOf(OutlierDetectionLoadBalancer.class);
  }

  @Test
  public void parseLoadBalancingConfig_defaults() throws IOException {
    String lbConfig =
        "{ \"successRateEjection\" : {}, "
        + "\"failurePercentageEjection\" : {}, "
        + "\"childPolicy\" : [{\"round_robin\" : {}}]}";
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getConfig()).isNotNull();
    OutlierDetectionLoadBalancerConfig config
        = (OutlierDetectionLoadBalancerConfig) configOrError.getConfig();
    assertThat(config.successRateEjection).isNotNull();
    assertThat(config.failurePercentageEjection).isNotNull();
    assertThat(config.childPolicy.getProvider().getPolicyName()).isEqualTo("round_robin");
  }

  @Test
  public void parseLoadBalancingConfig_valuesSet() throws IOException {
    String lbConfig =
        "{\"interval\" : \"100s\","
        + " \"baseEjectionTime\" : \"100s\","
        + " \"maxEjectionTime\" : \"100s\","
        + " \"maxEjectionPercentage\" : 100,"
        + " \"successRateEjection\" : {"
        + "     \"stdevFactor\" : 100,"
        + "     \"enforcementPercentage\" : 100,"
        + "     \"minimumHosts\" : 100,"
        + "     \"requestVolume\" : 100"
        + "   },"
        + " \"failurePercentageEjection\" : {"
        + "     \"threshold\" : 100,"
        + "     \"enforcementPercentage\" : 100,"
        + "     \"minimumHosts\" : 100,"
        + "     \"requestVolume\" : 100"
        + "   },"
        + "\"childPolicy\" : [{\"round_robin\" : {}}]}";
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getConfig()).isNotNull();
    OutlierDetectionLoadBalancerConfig config
        = (OutlierDetectionLoadBalancerConfig) configOrError.getConfig();

    assertThat(config.intervalNanos).isEqualTo(100_000_000_000L);
    assertThat(config.baseEjectionTimeNanos).isEqualTo(100_000_000_000L);
    assertThat(config.maxEjectionTimeNanos).isEqualTo(100_000_000_000L);
    assertThat(config.maxEjectionPercent).isEqualTo(100);

    assertThat(config.successRateEjection).isNotNull();
    assertThat(config.successRateEjection.stdevFactor).isEqualTo(100);
    assertThat(config.successRateEjection.enforcementPercentage).isEqualTo(100);
    assertThat(config.successRateEjection.minimumHosts).isEqualTo(100);
    assertThat(config.successRateEjection.requestVolume).isEqualTo(100);

    assertThat(config.failurePercentageEjection).isNotNull();
    assertThat(config.failurePercentageEjection.threshold).isEqualTo(100);
    assertThat(config.failurePercentageEjection.enforcementPercentage).isEqualTo(100);
    assertThat(config.failurePercentageEjection.minimumHosts).isEqualTo(100);
    assertThat(config.failurePercentageEjection.requestVolume).isEqualTo(100);

    assertThat(config.childPolicy.getProvider().getPolicyName()).isEqualTo("round_robin");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> parseJsonObject(String json) throws IOException {
    return (Map<String, ?>) JsonParser.parse(json);
  }
}
