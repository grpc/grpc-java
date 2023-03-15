package io.grpc.examples.customloadbalance;

import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.examples.customloadbalance.ShufflingPickFirstLoadBalancer.ShufflingPickFirstLoadBalancerConfig;
import java.util.Map;

public class ShufflingPickFirstLoadBalancerProvider extends LoadBalancerProvider {

  private static final String RANDOM_SEED_KEY = "randomSeed";

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawLoadBalancingPolicyConfig) {
    Long randomSeed = null;
    if (rawLoadBalancingPolicyConfig.containsKey(RANDOM_SEED_KEY)) {
      randomSeed = ((Double) rawLoadBalancingPolicyConfig.get(RANDOM_SEED_KEY)).longValue();
    }
    return ConfigOrError.fromConfig(new ShufflingPickFirstLoadBalancerConfig(randomSeed));
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    return new ShufflingPickFirstLoadBalancer(helper);
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public int getPriority() {
    return 5;
  }

  @Override
  public String getPolicyName() {
    return "example.shuffling_pick_first";
  }
}
