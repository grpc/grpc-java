/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.autosharding;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.InternalServiceProviders;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.JsonParser;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link AutoShardingLoadBalancerProvider}. */
@RunWith(JUnit4.class)
public class AutoShardingLoadBalancerProviderTest {

  private final SynchronizationContext syncContext =
      new SynchronizationContext((t, e) -> {
        throw new AssertionError(e);
      });

  private final AutoShardingLoadBalancerProvider provider =
      new AutoShardingLoadBalancerProvider();

  @Test
  public void provided() {
    for (LoadBalancerProvider current :
        InternalServiceProviders.getCandidatesViaServiceLoader(
            LoadBalancerProvider.class, getClass().getClassLoader())) {
      if (current instanceof AutoShardingLoadBalancerProvider) {
        return;
      }
    }
    fail("AutoShardingLoadBalancerProvider not registered");
  }

  @Test
  public void providerProperties() {
    assertThat(provider.getPolicyName()).isEqualTo("autosharding_experimental");
    assertThat(provider.isAvailable()).isTrue();
    assertThat(provider.getPriority()).isEqualTo(5);
  }

  @Test
  public void providesLoadBalancer() {
    Helper helper = mock(Helper.class);
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    assertThat(provider.newLoadBalancer(helper)).isInstanceOf(AutoShardingLoadBalancer.class);
  }

  @Test
  public void parse_allFieldsPresent() throws IOException {
    AutoShardingLoadBalancerConfig config =
        parseSuccessfully(
            "{"
                + "\"channelFactoryKey\": \"shard-service\","
                + "\"autoshardingTarget\": \"my-service/%s\","
                + "\"keyHeaderName\": \"x-shard-key\","
                + "\"enableFallback\": true,"
                + "\"initialAssignmentTimeout\": \"5.5s\""
                + "}");

    assertThat(config.channelFactoryKey).isEqualTo("shard-service");
    assertThat(config.autoshardingTarget).isEqualTo("my-service/%s");
    assertThat(config.keyHeaderName).isEqualTo("x-shard-key");
    assertThat(config.enableFallback).isTrue();
    assertThat(config.initialAssignmentTimeoutNanos)
        .isEqualTo(TimeUnit.MILLISECONDS.toNanos(5500));
  }

  @Test
  public void parse_optionalFieldsAbsent_useGrfcDefaults() throws IOException {
    AutoShardingLoadBalancerConfig config =
        parseSuccessfully("{\"keyHeaderName\": \"x-shard-key\"}");

    assertThat(config.channelFactoryKey).isEmpty();
    assertThat(config.autoshardingTarget).isEmpty();
    assertThat(config.enableFallback).isFalse();
    assertThat(config.initialAssignmentTimeoutNanos)
        .isEqualTo(AutoShardingLoadBalancerConfig.DEFAULT_INITIAL_ASSIGNMENT_TIMEOUT_NANOS);
    assertThat(config.initialAssignmentTimeoutNanos).isEqualTo(TimeUnit.SECONDS.toNanos(60));
  }

  @Test
  public void parse_binaryKeyHeaderName() throws IOException {
    AutoShardingLoadBalancerConfig config =
        parseSuccessfully("{\"keyHeaderName\": \"x-shard-key-bin\"}");

    assertThat(config.keyHeaderName).isEqualTo("x-shard-key-bin");
  }

  @Test
  public void parse_missingKeyHeaderName_isRejected() throws IOException {
    assertThat(parseError("{}")).contains("'keyHeaderName' is required");
  }

  @Test
  public void parse_emptyKeyHeaderName_isRejected() throws IOException {
    // There would be no header to read the routing key from.
    assertThat(parseError("{\"keyHeaderName\": \"\"}")).contains("'keyHeaderName' is required");
  }

  @Test
  public void parse_malformedKeyHeaderName_isRejected() throws IOException {
    // Reported here rather than thrown out of Metadata.Key on the synchronization context.
    assertThat(parseError("{\"keyHeaderName\": \"not a header\"}"))
        .contains("'keyHeaderName' is not a valid header name");
  }

  @Test
  public void parse_zeroInitialAssignmentTimeout_isAccepted() throws IOException {
    // A coherent request: skip the wait, start in fallback, and upgrade once the first assignment
    // lands. The gRFC does not forbid it, so the parser does not either.
    AutoShardingLoadBalancerConfig config =
        parseSuccessfully(
            "{\"keyHeaderName\": \"x-shard-key\", \"initialAssignmentTimeout\": \"0s\"}");

    assertThat(config.initialAssignmentTimeoutNanos).isEqualTo(0);
  }

  @Test
  public void parse_negativeInitialAssignmentTimeout_isRejected() throws IOException {
    assertThat(
            parseError(
                "{\"keyHeaderName\": \"x-shard-key\", \"initialAssignmentTimeout\": \"-1s\"}"))
        .contains("'initialAssignmentTimeout' must not be negative");
  }

  @Test
  public void parse_unparseableInitialAssignmentTimeout_isReportedAsFailure() throws IOException {
    // Durations are JSON strings ending in "s"; a bare number is a ClassCastException inside
    // JsonUtil, which the provider turns into an error rather than letting it escape.
    assertThat(
            parseError("{\"keyHeaderName\": \"x-shard-key\", \"initialAssignmentTimeout\": 60}"))
        .isEqualTo("Failed parsing configuration for autosharding_experimental");
  }

  @Test
  public void parse_wronglyTypedField_isReportedAsFailure() throws IOException {
    assertThat(parseError("{\"keyHeaderName\": 42}"))
        .isEqualTo("Failed parsing configuration for autosharding_experimental");
  }

  private AutoShardingLoadBalancerConfig parseSuccessfully(String json) throws IOException {
    ConfigOrError configOrError = provider.parseLoadBalancingPolicyConfig(parseJsonObject(json));
    assertThat(configOrError.getError()).isNull();
    return (AutoShardingLoadBalancerConfig) configOrError.getConfig();
  }

  /** Parses a config expected to be rejected, returning the error description. */
  private String parseError(String json) throws IOException {
    ConfigOrError configOrError = provider.parseLoadBalancingPolicyConfig(parseJsonObject(json));
    assertThat(configOrError.getConfig()).isNull();
    Status error = configOrError.getError();
    assertThat(error.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    return error.getDescription();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> parseJsonObject(String json) throws IOException {
    return (Map<String, ?>) JsonParser.parse(json);
  }
}
