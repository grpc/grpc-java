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

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseConfig(String json) throws Exception {
    return (Map<String, Object>) JsonParser.parse(json);
  }
}