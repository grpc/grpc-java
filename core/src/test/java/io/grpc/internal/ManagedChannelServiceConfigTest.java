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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Collections;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ManagedChannelServiceConfigTest {

  @Rule
  public final ExpectedException thrown = ExpectedException.none();

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

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Duplicate method");

    ManagedChannelServiceConfig.fromServiceConfig(serviceConfig, true, 3, 4, null);
  }

  @Test
  public void createManagedChannelServiceConfig_failsOnDuplicateService() {
    Map<String, ?> name1 = ImmutableMap.of("service", "service");
    Map<String, ?> name2 = ImmutableMap.of("service", "service");
    Map<String, ?> methodConfig = ImmutableMap.of("name", ImmutableList.of(name1, name2));
    Map<String, ?> serviceConfig = ImmutableMap.of("methodConfig", ImmutableList.of(methodConfig));

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Duplicate service");

    ManagedChannelServiceConfig.fromServiceConfig(serviceConfig, true, 3, 4, null);
  }

  @Test
  public void createManagedChannelServiceConfig_failsOnDuplicateServiceMultipleConfig() {
    Map<String, ?> name1 = ImmutableMap.of("service", "service");
    Map<String, ?> name2 = ImmutableMap.of("service", "service");
    Map<String, ?> methodConfig1 = ImmutableMap.of("name", ImmutableList.of(name1));
    Map<String, ?> methodConfig2 = ImmutableMap.of("name", ImmutableList.of(name2));
    Map<String, ?> serviceConfig =
        ImmutableMap.of("methodConfig", ImmutableList.of(methodConfig1, methodConfig2));

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Duplicate service");

    ManagedChannelServiceConfig.fromServiceConfig(serviceConfig, true, 3, 4, null);
  }

  @Test
  public void createManagedChannelServiceConfig_failsOnMethodNameWithEmptyServiceName() {
    Map<String, ?> name = ImmutableMap.of("service", "", "method", "method1");
    Map<String, ?> methodConfig = ImmutableMap.of("name", ImmutableList.of(name));
    Map<String, ?> serviceConfig = ImmutableMap.of("methodConfig", ImmutableList.of(methodConfig));

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("missing service name for method method1");

    ManagedChannelServiceConfig.fromServiceConfig(serviceConfig, true, 3, 4, null);
  }

  @Test
  public void createManagedChannelServiceConfig_failsOnMethodNameWithoutServiceName() {
    Map<String, ?> name = ImmutableMap.of("method", "method1");
    Map<String, ?> methodConfig = ImmutableMap.of("name", ImmutableList.of(name));
    Map<String, ?> serviceConfig = ImmutableMap.of("methodConfig", ImmutableList.of(methodConfig));

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("missing service name for method method1");

    ManagedChannelServiceConfig.fromServiceConfig(serviceConfig, true, 3, 4, null);
  }

  @Test
  public void createManagedChannelServiceConfig_failsOnMissingServiceName() {
    Map<String, ?> name = ImmutableMap.of("method", "method");
    Map<String, ?> methodConfig = ImmutableMap.of("name", ImmutableList.of(name));
    Map<String, ?> serviceConfig = ImmutableMap.of("methodConfig", ImmutableList.of(methodConfig));

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("missing service");

    ManagedChannelServiceConfig.fromServiceConfig(serviceConfig, true, 3, 4, null);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseConfig(String json) throws Exception {
    return (Map<String, Object>) JsonParser.parse(json);
  }
}