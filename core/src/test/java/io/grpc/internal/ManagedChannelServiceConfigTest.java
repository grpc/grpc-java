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
    assertThat(healthCheckingConfig).containsExactly("serviceName", "COVID-19");
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