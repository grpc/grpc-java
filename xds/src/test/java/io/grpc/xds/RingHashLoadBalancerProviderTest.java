/*
 * Copyright 2021 The gRPC Authors
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
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.internal.JsonParser;
import io.grpc.xds.RingHashLoadBalancer.RingHashConfig;
import io.grpc.xds.RingHashOptions;
import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Locale;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RingHashLoadBalancerProvider}. */
@RunWith(JUnit4.class)
public class RingHashLoadBalancerProviderTest {
  private static final String AUTHORITY = "foo.googleapis.com";

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final RingHashLoadBalancerProvider provider = new RingHashLoadBalancerProvider();

  @Test
  public void provided() {
    for (LoadBalancerProvider current : InternalServiceProviders.getCandidatesViaServiceLoader(
        LoadBalancerProvider.class, getClass().getClassLoader())) {
      if (current instanceof RingHashLoadBalancerProvider) {
        return;
      }
    }
    fail("RingHashLoadBalancerProvider not registered");
  }

  @Test
  public void providesLoadBalancer() {
    Helper helper = mock(Helper.class);
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    when(helper.getAuthority()).thenReturn(AUTHORITY);
    assertThat(provider.newLoadBalancer(helper))
        .isInstanceOf(RingHashLoadBalancer.class);
  }

  @Test
  public void parseLoadBalancingConfig_valid() throws IOException {
    String lbConfig = "{\"minRingSize\" : 10, \"maxRingSize\" : 100}";
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getConfig()).isNotNull();
    RingHashConfig config = (RingHashConfig) configOrError.getConfig();
    assertThat(config.minRingSize).isEqualTo(10L);
    assertThat(config.maxRingSize).isEqualTo(100L);
  }

  @Test
  public void parseLoadBalancingConfig_missingRingSize_useDefaults() throws IOException {
    String lbConfig = "{}";
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getConfig()).isNotNull();
    RingHashConfig config = (RingHashConfig) configOrError.getConfig();
    assertThat(config.minRingSize).isEqualTo(RingHashLoadBalancerProvider.DEFAULT_MIN_RING_SIZE);
    assertThat(config.maxRingSize).isEqualTo(RingHashLoadBalancerProvider.DEFAULT_MAX_RING_SIZE);
  }

  @Test
  public void parseLoadBalancingConfig_invalid_negativeSize() throws IOException {
    String lbConfig = "{\"minRingSize\" : -10}";
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getError()).isNotNull();
    assertThat(configOrError.getError().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(configOrError.getError().getDescription())
        .isEqualTo("Invalid 'mingRingSize'/'maxRingSize'");
  }

  @Test
  public void parseLoadBalancingConfig_invalid_minGreaterThanMax() throws IOException {
    String lbConfig = "{\"minRingSize\" : 1000, \"maxRingSize\" : 100}";
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getError()).isNotNull();
    assertThat(configOrError.getError().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(configOrError.getError().getDescription())
        .isEqualTo("Invalid 'mingRingSize'/'maxRingSize'");
  }

  @Test
  public void parseLoadBalancingConfig_ringTooLargeUsesCap() throws IOException {
    long ringSize = RingHashOptions.MAX_RING_SIZE_CAP + 1;
    String lbConfig =
        String.format(Locale.US, "{\"minRingSize\" : 10, \"maxRingSize\" : %d}", ringSize);
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getConfig()).isNotNull();
    RingHashConfig config = (RingHashConfig) configOrError.getConfig();
    assertThat(config.minRingSize).isEqualTo(10);
    assertThat(config.maxRingSize).isEqualTo(RingHashOptions.DEFAULT_RING_SIZE_CAP);
  }

  @Test
  public void parseLoadBalancingConfig_ringCapCanBeRaised() throws IOException {
    RingHashOptions.setRingSizeCap(RingHashOptions.MAX_RING_SIZE_CAP);
    long ringSize = RingHashOptions.MAX_RING_SIZE_CAP;
    String lbConfig =
        String.format(
            Locale.US, "{\"minRingSize\" : %d, \"maxRingSize\" : %d}", ringSize, ringSize);
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getConfig()).isNotNull();
    RingHashConfig config = (RingHashConfig) configOrError.getConfig();
    assertThat(config.minRingSize).isEqualTo(RingHashOptions.MAX_RING_SIZE_CAP);
    assertThat(config.maxRingSize).isEqualTo(RingHashOptions.MAX_RING_SIZE_CAP);
    // Reset to avoid affecting subsequent test cases
    RingHashOptions.setRingSizeCap(RingHashOptions.DEFAULT_RING_SIZE_CAP);
  }

  @Test
  public void parseLoadBalancingConfig_ringCapIsClampedTo8M() throws IOException {
    RingHashOptions.setRingSizeCap(RingHashOptions.MAX_RING_SIZE_CAP + 1);
    long ringSize = RingHashOptions.MAX_RING_SIZE_CAP + 1;
    String lbConfig =
        String.format(
            Locale.US, "{\"minRingSize\" : %d, \"maxRingSize\" : %d}", ringSize, ringSize);
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getConfig()).isNotNull();
    RingHashConfig config = (RingHashConfig) configOrError.getConfig();
    assertThat(config.minRingSize).isEqualTo(RingHashOptions.MAX_RING_SIZE_CAP);
    assertThat(config.maxRingSize).isEqualTo(RingHashOptions.MAX_RING_SIZE_CAP);
    // Reset to avoid affecting subsequent test cases
    RingHashOptions.setRingSizeCap(RingHashOptions.DEFAULT_RING_SIZE_CAP);
  }

  @Test
  public void parseLoadBalancingConfig_ringCapCanBeLowered() throws IOException {
    RingHashOptions.setRingSizeCap(1);
    long ringSize = 2;
    String lbConfig =
        String.format(
            Locale.US, "{\"minRingSize\" : %d, \"maxRingSize\" : %d}", ringSize, ringSize);
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getConfig()).isNotNull();
    RingHashConfig config = (RingHashConfig) configOrError.getConfig();
    assertThat(config.minRingSize).isEqualTo(1);
    assertThat(config.maxRingSize).isEqualTo(1);
    // Reset to avoid affecting subsequent test cases
    RingHashOptions.setRingSizeCap(RingHashOptions.DEFAULT_RING_SIZE_CAP);
  }

  @Test
  public void parseLoadBalancingConfig_ringCapLowerLimitIs1() throws IOException {
    RingHashOptions.setRingSizeCap(0);
    long ringSize = 2;
    String lbConfig =
        String.format(
            Locale.US, "{\"minRingSize\" : %d, \"maxRingSize\" : %d}", ringSize, ringSize);
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getConfig()).isNotNull();
    RingHashConfig config = (RingHashConfig) configOrError.getConfig();
    assertThat(config.minRingSize).isEqualTo(1);
    assertThat(config.maxRingSize).isEqualTo(1);
    // Reset to avoid affecting subsequent test cases
    RingHashOptions.setRingSizeCap(RingHashOptions.DEFAULT_RING_SIZE_CAP);
  }

  @Test
  public void parseLoadBalancingConfig_zeroMinRingSize() throws IOException {
    String lbConfig = "{\"minRingSize\" : 0, \"maxRingSize\" : 100}";
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getError()).isNotNull();
    assertThat(configOrError.getError().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(configOrError.getError().getDescription())
        .isEqualTo("Invalid 'mingRingSize'/'maxRingSize'");
  }

  @Test
  public void parseLoadBalancingConfig_minRingSizeGreaterThanMaxRingSize() throws IOException {
    String lbConfig = "{\"minRingSize\" : 100, \"maxRingSize\" : 10}";
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getError()).isNotNull();
    assertThat(configOrError.getError().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(configOrError.getError().getDescription())
        .isEqualTo("Invalid 'mingRingSize'/'maxRingSize'");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> parseJsonObject(String json) throws IOException {
    return (Map<String, ?>) JsonParser.parse(json);
  }
}
