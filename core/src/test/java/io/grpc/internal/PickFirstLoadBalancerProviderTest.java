package io.grpc.internal;

import static com.google.common.truth.Truth.assertThat;

import io.grpc.NameResolver.ConfigOrError;
import io.grpc.internal.PickFirstLoadBalancer.PickFirstLoadBalancerConfig;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class PickFirstLoadBalancerProviderTest {

  @After
  public void resetConfigFlag() {
    PickFirstLoadBalancerProvider.enablePickFirstConfig = false;
  }

  @Test
  public void parseWithConfigEnabled() {
    PickFirstLoadBalancerProvider.enablePickFirstConfig = true;
    Map<String, Object> rawConfig = new HashMap<>();
    rawConfig.put("shuffleAddressList", true);
    ConfigOrError parsedConfig = new PickFirstLoadBalancerProvider().parseLoadBalancingPolicyConfig(
        rawConfig);
    PickFirstLoadBalancerConfig config = (PickFirstLoadBalancerConfig) parsedConfig.getConfig();

    assertThat(config.shuffleAddressList).isTrue();
  }

  @Test
  public void parseWithConfigDisabled() {
    PickFirstLoadBalancerProvider.enablePickFirstConfig = false;
    Map<String, Object> rawConfig = new HashMap<>();
    rawConfig.put("shuffleAddressList", true);
    ConfigOrError parsedConfig = new PickFirstLoadBalancerProvider().parseLoadBalancingPolicyConfig(
        rawConfig);
    String config = (String) parsedConfig.getConfig();

    assertThat(config).isEqualTo("no service config");
  }
}
