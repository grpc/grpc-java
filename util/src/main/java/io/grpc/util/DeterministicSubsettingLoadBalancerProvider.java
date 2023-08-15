package io.grpc.util;

import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.JsonUtil;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import java.util.List;
import java.util.Map;

@Internal
public final class DeterministicSubsettingLoadBalancerProvider extends LoadBalancerProvider {

  @Override
  public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
    return new DeterministicSubsettingLoadBalancer(helper);
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
    return "deterministic_subsetting";
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawConfig) {
    try {
      return parseLoadBalancingPolicyConfigInternal(rawConfig);
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(
        Status.UNAVAILABLE.withCause(e).withDescription(
          "Failed parsing configuration for " + getPolicyName()));
    }
  }

  private ConfigOrError parseLoadBalancingPolicyConfigInternal(Map<String, ?> rawConfig) {
    Integer clientIndex = JsonUtil.getNumberAsInteger(rawConfig, "clientIndex");
    Integer subsetSize = JsonUtil.getNumberAsInteger(rawConfig, "subsetSize");
    Boolean sortAddresses = JsonUtil.getBoolean(rawConfig, "sortAddresses");

    List<ServiceConfigUtil.LbConfig> childConfigCandidates = ServiceConfigUtil.unwrapLoadBalancingConfigList(
      JsonUtil.getListOfObjects(rawConfig, "childPolicy"));
    if (childConfigCandidates == null || childConfigCandidates.isEmpty()) {
      return ConfigOrError.fromError(Status.INTERNAL.withDescription(
        "No child policy in deterministic_subsetting LB policy " + rawConfig
      ));
    }

    ConfigOrError selectedConfig =
      ServiceConfigUtil.selectLbPolicyFromList(childConfigCandidates,
        LoadBalancerRegistry.getDefaultRegistry());

    DeterministicSubsettingLoadBalancer.DeterministicSubsettingLoadBalancerConfig.Builder configBuilder =
      new DeterministicSubsettingLoadBalancer.DeterministicSubsettingLoadBalancerConfig.Builder();

    configBuilder.setChildPolicy((PolicySelection) selectedConfig.getConfig());

    if (clientIndex != null) {
      configBuilder.setClientIndex(clientIndex);
    } else {
      return ConfigOrError.fromError(Status.INTERNAL.withDescription(
        "No client index set, cannot determine subsets " + rawConfig)
      );
    }

    if (subsetSize != null) {
      configBuilder.setSubsetSize(subsetSize);
    }

    if (sortAddresses != null) {
      configBuilder.setSortAddresses(sortAddresses);
    }
    return ConfigOrError.fromConfig(configBuilder.build());
  }
}
