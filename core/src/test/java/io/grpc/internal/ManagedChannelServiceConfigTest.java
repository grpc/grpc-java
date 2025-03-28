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

package io.grpc.internal;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.MethodDescriptor.MethodType.UNARY;
import static io.grpc.Status.Code.UNAVAILABLE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.CallOptions;
import io.grpc.InternalConfigSelector;
import io.grpc.InternalConfigSelector.Result;
import io.grpc.LoadBalancer.PickDetailsConsumer;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.internal.ManagedChannelServiceConfig.MethodInfo;
import io.grpc.testing.TestMethodDescriptors;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ManagedChannelServiceConfigTest {

  @Test
  public void managedChannelServiceConfig_shouldParseHealthCheckingConfig() throws Exception {
    Map<String, ?> rawServiceConfig =
        parseConfig(
            "{\"healthCheckConfig\": "
                + "{\"serviceName\": \"COVID-19\", "
                + "\"description\": \"I can't visit korea, because of you\"}}");
    ManagedChannelServiceConfig mcsc =
        ManagedChannelServiceConfig.fromServiceConfig(rawServiceConfig, true, 3, 4, null);
    Map<String, ?> healthCheckingConfig = mcsc.getHealthCheckingConfig();
    assertThat(healthCheckingConfig).isNotNull();
    assertThat(healthCheckingConfig)
        .containsExactly(
            "serviceName", "COVID-19", "description", "I can't visit korea, because of you");
  }

  @Test
  public void managedChannelServiceConfig_shouldHandleNoHealthCheckingConfig() throws Exception {
    ManagedChannelServiceConfig mcsc =
        ManagedChannelServiceConfig
            .fromServiceConfig(Collections.<String, Object>emptyMap(), true, 3, 4, null);

    assertThat(mcsc.getHealthCheckingConfig()).isNull();
  }

  @Test
  public void createManagedChannelServiceConfig_failsOnDuplicateMethod() {
    Map<String, ?> name1 = ImmutableMap.of("service", "service", "method", "method");
    Map<String, ?> name2 = ImmutableMap.of("service", "service", "method", "method");
    Map<String, ?> methodConfig = ImmutableMap.of("name", ImmutableList.of(name1, name2));
    Map<String, ?> serviceConfig = ImmutableMap.of("methodConfig", ImmutableList.of(methodConfig));

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ManagedChannelServiceConfig.fromServiceConfig(serviceConfig, true, 3, 4, null));
    assertThat(e).hasMessageThat().isEqualTo("Duplicate method name service/method");
  }

  @Test
  public void createManagedChannelServiceConfig_failsOnDuplicateService() {
    Map<String, ?> name1 = ImmutableMap.of("service", "service");
    Map<String, ?> name2 = ImmutableMap.of("service", "service");
    Map<String, ?> methodConfig = ImmutableMap.of("name", ImmutableList.of(name1, name2));
    Map<String, ?> serviceConfig = ImmutableMap.of("methodConfig", ImmutableList.of(methodConfig));

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ManagedChannelServiceConfig.fromServiceConfig(serviceConfig, true, 3, 4, null));
    assertThat(e).hasMessageThat().isEqualTo("Duplicate service service");
  }

  @Test
  public void createManagedChannelServiceConfig_failsOnDuplicateServiceMultipleConfig() {
    Map<String, ?> name1 = ImmutableMap.of("service", "service");
    Map<String, ?> name2 = ImmutableMap.of("service", "service");
    Map<String, ?> methodConfig1 = ImmutableMap.of("name", ImmutableList.of(name1));
    Map<String, ?> methodConfig2 = ImmutableMap.of("name", ImmutableList.of(name2));
    Map<String, ?> serviceConfig =
        ImmutableMap.of("methodConfig", ImmutableList.of(methodConfig1, methodConfig2));

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ManagedChannelServiceConfig.fromServiceConfig(serviceConfig, true, 3, 4, null));
    assertThat(e).hasMessageThat().isEqualTo("Duplicate service service");
  }

  @Test
  public void createManagedChannelServiceConfig_failsOnMethodNameWithEmptyServiceName() {
    Map<String, ?> name = ImmutableMap.of("service", "", "method", "method1");
    Map<String, ?> methodConfig = ImmutableMap.of("name", ImmutableList.of(name));
    Map<String, ?> serviceConfig = ImmutableMap.of("methodConfig", ImmutableList.of(methodConfig));

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ManagedChannelServiceConfig.fromServiceConfig(serviceConfig, true, 3, 4, null));
    assertThat(e).hasMessageThat().isEqualTo("missing service name for method method1");
  }

  @Test
  public void createManagedChannelServiceConfig_failsOnMethodNameWithoutServiceName() {
    Map<String, ?> name = ImmutableMap.of("method", "method1");
    Map<String, ?> methodConfig = ImmutableMap.of("name", ImmutableList.of(name));
    Map<String, ?> serviceConfig = ImmutableMap.of("methodConfig", ImmutableList.of(methodConfig));

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ManagedChannelServiceConfig.fromServiceConfig(serviceConfig, true, 3, 4, null));
    assertThat(e).hasMessageThat().isEqualTo("missing service name for method method1");
  }

  @Test
  public void createManagedChannelServiceConfig_failsOnMissingServiceName() {
    Map<String, ?> name = ImmutableMap.of("method", "method");
    Map<String, ?> methodConfig = ImmutableMap.of("name", ImmutableList.of(name));
    Map<String, ?> serviceConfig = ImmutableMap.of("methodConfig", ImmutableList.of(methodConfig));

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ManagedChannelServiceConfig.fromServiceConfig(serviceConfig, true, 3, 4, null));
    assertThat(e).hasMessageThat().isEqualTo("missing service name for method method");
  }

  @Test
  public void managedChannelServiceConfig_parseMethodConfig() {
    Map<String, ?> name1 = ImmutableMap.of("service", "service1", "method", "method1");
    Map<String, ?> name2 = ImmutableMap.of("service", "service2");
    Map<String, ?> methodConfig = ImmutableMap.of(
        "name", ImmutableList.of(name1, name2),
        "timeout", "1.234s",
        "retryPolicy",
        ImmutableMap.builder()
            .put("maxAttempts", 3.0D)
            .put("initialBackoff", "1s")
            .put("maxBackoff", "10s")
            .put("backoffMultiplier", 1.5D)
            .put("perAttemptRecvTimeout", "2.5s")
            .put("retryableStatusCodes", ImmutableList.of("UNAVAILABLE"))
            .buildOrThrow());
    Map<String, ?> defaultMethodConfig = ImmutableMap.of(
        "name", ImmutableList.of(ImmutableMap.of()),
        "timeout", "4.321s");
    Map<String, ?> rawServiceConfig =
        ImmutableMap.of("methodConfig", ImmutableList.of(methodConfig, defaultMethodConfig));

    // retry disabled
    ManagedChannelServiceConfig serviceConfig =
        ManagedChannelServiceConfig.fromServiceConfig(rawServiceConfig, false, 0, 0, null);
    MethodInfo methodInfo = serviceConfig.getMethodConfig(methodForName("service1", "method1"));
    assertThat(methodInfo.timeoutNanos).isEqualTo(MILLISECONDS.toNanos(1234));
    assertThat(methodInfo.retryPolicy).isNull();
    methodInfo = serviceConfig.getMethodConfig(methodForName("service1", "methodX"));
    assertThat(methodInfo.timeoutNanos).isEqualTo(MILLISECONDS.toNanos(4321));
    assertThat(methodInfo.retryPolicy).isNull();
    methodInfo = serviceConfig.getMethodConfig(methodForName("service2", "methodX"));
    assertThat(methodInfo.timeoutNanos).isEqualTo(MILLISECONDS.toNanos(1234));
    assertThat(methodInfo.retryPolicy).isNull();

    // retry enabled
    serviceConfig =
        ManagedChannelServiceConfig.fromServiceConfig(rawServiceConfig, true, 2, 0, null);
    methodInfo = serviceConfig.getMethodConfig(methodForName("service1", "method1"));
    assertThat(methodInfo.timeoutNanos).isEqualTo(MILLISECONDS.toNanos(1234));
    assertThat(methodInfo.retryPolicy.maxAttempts).isEqualTo(2);
    assertThat(methodInfo.retryPolicy.perAttemptRecvTimeoutNanos)
        .isEqualTo(MILLISECONDS.toNanos(2500));
    assertThat(methodInfo.retryPolicy.retryableStatusCodes).containsExactly(UNAVAILABLE);
    methodInfo = serviceConfig.getMethodConfig(methodForName("service1", "methodX"));
    assertThat(methodInfo.timeoutNanos).isEqualTo(MILLISECONDS.toNanos(4321));
    assertThat(methodInfo.retryPolicy).isNull();
  }

  @Test
  public void getDefaultConfigSelectorFromConfig() {
    Map<String, ?> name = ImmutableMap.of("service", "service1", "method", "method1");
    Map<String, ?> methodConfig = ImmutableMap.of(
        "name", ImmutableList.of(name), "timeout", "1.234s");
    Map<String, ?> rawServiceConfig =
        ImmutableMap.of("methodConfig", ImmutableList.of(methodConfig));
    ManagedChannelServiceConfig serviceConfig =
        ManagedChannelServiceConfig.fromServiceConfig(rawServiceConfig, false, 0, 0, null);
    InternalConfigSelector configSelector = serviceConfig.getDefaultConfigSelector();
    MethodDescriptor<?, ?> method = methodForName("service1", "method1");
    Result result = configSelector.selectConfig(
        new PickSubchannelArgsImpl(
            method, new Metadata(), CallOptions.DEFAULT, new PickDetailsConsumer() {}));
    MethodInfo methodInfoFromDefaultConfigSelector =
        ((ManagedChannelServiceConfig) result.getConfig()).getMethodConfig(method);
    assertThat(methodInfoFromDefaultConfigSelector)
        .isEqualTo(serviceConfig.getMethodConfig(method));
  }

  @Test
  public void retryConfig_emptyRetriableStatusCodesAllowedWithPerAttemptRecvTimeoutGiven() {
    Map<String, ?> retryPolicy = ImmutableMap.<String, Object>builder()
        .put("maxAttempts", 3.0D)
        .put("initialBackoff", "1s")
        .put("maxBackoff", "10s")
        .put("backoffMultiplier", 1.5D)
        .put("perAttemptRecvTimeout", "2.5s")
        .put("retryableStatusCodes", ImmutableList.of())
        .buildOrThrow();
    Map<String, ?> methodConfig = ImmutableMap.of(
        "name", ImmutableList.of(ImmutableMap.of()), "retryPolicy", retryPolicy);
    Map<String, ?> rawServiceConfig =
        ImmutableMap.of("methodConfig", ImmutableList.of(methodConfig));
    assertThat(ManagedChannelServiceConfig.fromServiceConfig(rawServiceConfig, true, 5, 5, null))
        .isNotNull();
  }

  @Test
  public void retryConfig_PerAttemptRecvTimeoutUnsetAllowedIfRetryableStatusCodesNonempty() {
    Map<String, ?> retryPolicy = ImmutableMap.<String, Object>builder()
        .put("maxAttempts", 3.0D)
        .put("initialBackoff", "1s")
        .put("maxBackoff", "10s")
        .put("backoffMultiplier", 1.5D)
        .put("retryableStatusCodes", ImmutableList.of("UNAVAILABLE"))
        .buildOrThrow();
    Map<String, ?> methodConfig = ImmutableMap.of(
        "name", ImmutableList.of(ImmutableMap.of()), "retryPolicy", retryPolicy);
    Map<String, ?> rawServiceConfig =
        ImmutableMap.of("methodConfig", ImmutableList.of(methodConfig));
    assertThat(ManagedChannelServiceConfig.fromServiceConfig(rawServiceConfig, true, 5, 5, null))
        .isNotNull();
  }

  @Test
  public void retryConfig_emptyRetriableStatusCodesNotAllowedWithPerAttemptRecvTimeoutUnset() {
    Map<String, ?> retryPolicy = ImmutableMap.<String, Object>builder()
        .put("maxAttempts", 3.0D)
        .put("initialBackoff", "1s")
        .put("maxBackoff", "10s")
        .put("backoffMultiplier", 1.5D)
        .put("retryableStatusCodes", ImmutableList.of())
        .buildOrThrow();
    Map<String, ?> methodConfig = ImmutableMap.of(
        "name", ImmutableList.of(ImmutableMap.of()), "retryPolicy", retryPolicy);
    Map<String, ?> rawServiceConfig =
        ImmutableMap.of("methodConfig", ImmutableList.of(methodConfig));
    try {
      ManagedChannelServiceConfig.fromServiceConfig(rawServiceConfig, true, 5, 5, null);
      fail("The expected IllegalArgumentException is not thrown");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessageThat().contains(
          "retryableStatusCodes cannot be empty without perAttemptRecvTimeout");
    }
  }

  // For now we allow perAttemptRecvTimeout being zero although it does not make sense.
  // TODO(zdapeng): disallow zero perAttemptRecvTimeout if hedging is not enabled once we support
  //   hedge_on_per_try_timeout.
  @Test
  public void retryConfig_AllowPerAttemptRecvTimeoutZero() {
    Map<String, ?> retryPolicy = ImmutableMap.<String, Object>builder()
        .put("maxAttempts", 3.0D)
        .put("initialBackoff", "1s")
        .put("maxBackoff", "10s")
        .put("backoffMultiplier", 1.5D)
        .put("perAttemptRecvTimeout", "0s")
        .put("retryableStatusCodes", ImmutableList.of())
        .buildOrThrow();
    Map<String, ?> methodConfig = ImmutableMap.of(
        "name", ImmutableList.of(ImmutableMap.of()), "retryPolicy", retryPolicy);
    Map<String, ?> rawServiceConfig =
        ImmutableMap.of("methodConfig", ImmutableList.of(methodConfig));
    assertThat(ManagedChannelServiceConfig.fromServiceConfig(rawServiceConfig, true, 5, 5, null))
        .isNotNull();
  }

  private static MethodDescriptor<?, ?> methodForName(String service, String method) {
    return MethodDescriptor.<Void, Void>newBuilder()
        .setFullMethodName(service + "/" + method)
        .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
        .setResponseMarshaller(TestMethodDescriptors.voidMarshaller())
        .setType(UNARY)
        .build();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseConfig(String json) throws Exception {
    return (Map<String, Object>) JsonParser.parse(json);
  }
}
