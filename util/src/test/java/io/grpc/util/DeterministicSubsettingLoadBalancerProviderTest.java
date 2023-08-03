package io.grpc.util;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import io.grpc.InternalServiceProviders;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.internal.JsonParser;
import io.grpc.util.DeterministicSubsettingLoadBalancer.DeterministicSubsettingLoadBalancerConfig;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DeterministicSubsettingLoadBalancerProviderTest {

  private final DeterministicSubsettingLoadBalancerProvider provider = new DeterministicSubsettingLoadBalancerProvider();
  @Test
  public void registered() {
    for (LoadBalancerProvider current : InternalServiceProviders.getCandidatesViaServiceLoader(
      LoadBalancerProvider.class, getClass().getClassLoader())) {
      if (current instanceof DeterministicSubsettingLoadBalancerProvider) {
        return;
      }
    }
    fail("DeterministicSubsettingLoadBalancerProvider not registered");
  }

  @Test
  public void providesLoadBalancer() {
    Helper helper = mock(Helper.class);
    assertThat(provider.newLoadBalancer(helper)).isInstanceOf(DeterministicSubsettingLoadBalancer.class);
  }

  @Test
  public void parseConfigRequiresClientIdx() throws IOException {
    String lbConfig =
      "{ \"clientIndex\" :  null }";
    String lbConfig2 =
      "{ \"clientIndex\" : -1 }";
    ArrayList<String> configs = new ArrayList<>();
    configs.add(lbConfig);
    configs.add(lbConfig2);

    ConfigOrError configOrError;
    for (String config : configs) {
      configOrError = provider.parseLoadBalancingPolicyConfig(parseJsonObject(config));
      assertThat(configOrError.getError()).isNotNull();
    }
  }

  @Test
  public void parseConfigWithDefaults() throws IOException {
    String lbConfig =
      "{ \"clientIndex\" : 0, "
        + "\"childPolicy\" : [{\"round_robin\" : {}}], "
        + "\"sortAddresses\" : false }";
    ConfigOrError configOrError = provider.parseLoadBalancingPolicyConfig(parseJsonObject(lbConfig));
    System.out.println(configOrError);
    assertThat(configOrError.getConfig()).isNotNull();
    DeterministicSubsettingLoadBalancerConfig config = (DeterministicSubsettingLoadBalancerConfig) configOrError.getConfig();

    assertThat(config.clientIndex).isEqualTo(0);
    assertThat(config.sortAddresses).isEqualTo(false);
    assertThat(config.childPolicy.getProvider().getPolicyName()).isEqualTo("round_robin");

    assertThat(config.subsetSize).isEqualTo(10);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> parseJsonObject(String json) throws IOException {
    return (Map<String, ?>) JsonParser.parse(json);
  }
}
