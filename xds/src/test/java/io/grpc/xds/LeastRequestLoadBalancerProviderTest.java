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
import io.grpc.xds.LeastRequestLoadBalancer.LeastRequestConfig;
import java.io.IOException;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LeastRequestLoadBalancerProvider}. */
@RunWith(JUnit4.class)
public class LeastRequestLoadBalancerProviderTest {
  private static final String AUTHORITY = "foo.googleapis.com";

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final LeastRequestLoadBalancerProvider provider = new LeastRequestLoadBalancerProvider();

  @Test
  public void provided() {
    for (LoadBalancerProvider current : InternalServiceProviders.getCandidatesViaServiceLoader(
        LoadBalancerProvider.class, getClass().getClassLoader())) {
      if (current instanceof LeastRequestLoadBalancerProvider) {
        return;
      }
    }
    fail("LeastRequestLoadBalancerProvider not registered");
  }

  @Test
  public void providesLoadBalancer() {
    Helper helper = mock(Helper.class);
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    when(helper.getAuthority()).thenReturn(AUTHORITY);
    assertThat(provider.newLoadBalancer(helper))
        .isInstanceOf(LeastRequestLoadBalancer.class);
  }

  @Test
  public void parseLoadBalancingConfig_valid() throws IOException {
    String lbConfig = "{\"choiceCount\" : 3}";
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getConfig()).isNotNull();
    LeastRequestConfig config = (LeastRequestConfig) configOrError.getConfig();
    assertThat(config.choiceCount).isEqualTo(3);
  }

  @Test
  public void parseLoadBalancingConfig_missingChoiceCount_useDefaults() throws IOException {
    String lbConfig = "{}";
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getConfig()).isNotNull();
    LeastRequestConfig config = (LeastRequestConfig) configOrError.getConfig();
    assertThat(config.choiceCount)
        .isEqualTo(LeastRequestLoadBalancerProvider.DEFAULT_CHOICE_COUNT);
  }

  @Test
  public void parseLoadBalancingConfig_invalid_negativeSize() throws IOException {
    String lbConfig = "{\"choiceCount\" : -10}";
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getError()).isNotNull();
    assertThat(configOrError.getError().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(configOrError.getError().getDescription())
        .isEqualTo("Invalid 'choiceCount' in least_request_experimental config");
  }

  @Test
  public void parseLoadBalancingConfig_invalid_tooSmallSize() throws IOException {
    String lbConfig = "{\"choiceCount\" : 1}";
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getError()).isNotNull();
    assertThat(configOrError.getError().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(configOrError.getError().getDescription())
        .isEqualTo("Invalid 'choiceCount' in least_request_experimental config");
  }

  @Test
  public void parseLoadBalancingConfig_choiceCountCappedAtMax() throws IOException {
    String lbConfig = "{\"choiceCount\" : 11}";
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    assertThat(configOrError.getConfig()).isNotNull();
    LeastRequestConfig config = (LeastRequestConfig) configOrError.getConfig();
    assertThat(config.choiceCount).isEqualTo(LeastRequestLoadBalancerProvider.MAX_CHOICE_COUNT);
  }

  @Test
  public void parseLoadBalancingConfig_invalidInteger() throws IOException {
    Map<String, ?> lbConfig = parseJsonObject("{\"choiceCount\" : \"NaN\"}");
    ConfigOrError configOrError =
        provider.parseLoadBalancingPolicyConfig(lbConfig);
    assertThat(configOrError.getError()).isNotNull();
    assertThat(configOrError.getError().getDescription()).isEqualTo(
        "Failed to parse least_request_experimental LB config: " + lbConfig);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> parseJsonObject(String json) throws IOException {
    return (Map<String, ?>) JsonParser.parse(json);
  }
}
