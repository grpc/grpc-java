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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.JsonUtil;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.xds.EnvoyProtoData.Locality;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Provider for lrs load balancing policy.
 */
@Internal
public class LrsLoadBalancerProvider extends LoadBalancerProvider {

  private static final String LRS_POLICY_NAME = "lrs_experimental";

  @Override
  public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
    return new LrsLoadBalancer(helper);
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
    return LRS_POLICY_NAME;
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawConfig) {
    String clusterName = JsonUtil.getString(rawConfig, "clusterName");
    if (clusterName == null) {
      return
          ConfigOrError.fromError(
              Status.INTERNAL.withDescription(
                  "clusterName required for LRS policy:\n " + rawConfig));
    }
    String edsServiceName = JsonUtil.getString(rawConfig, "edsServiceName");
    String lrsServerName = JsonUtil.getString(rawConfig, "lrsLoadReportingServerName");
    if (lrsServerName == null) {
      return
          ConfigOrError.fromError(
              Status.INTERNAL.withDescription(
                  "lrsLoadReportingServerName required for LRS policy:\n " + rawConfig));
    }
    Map<String, ?> rawLocality = JsonUtil.getObject(rawConfig, "locality");
    if (rawLocality == null) {
      return
          ConfigOrError.fromError(
              Status.INTERNAL.withDescription(
                  "locality required for LRS policy:\n " + rawConfig));
    }
    Locality locality = parseLocality(rawLocality);
    if (locality == null) {
      return
          ConfigOrError.fromError(
              Status.INTERNAL.withDescription(
                  "Malformed locality:\n " + rawLocality));
    }
    List<LbConfig> childConfigCandidates = ServiceConfigUtil.unwrapLoadBalancingConfigList(
        JsonUtil.getListOfObjects(rawConfig, "childPolicy"));
    if (childConfigCandidates == null || childConfigCandidates.isEmpty()) {
      return
          ConfigOrError.fromError(
              Status.INTERNAL.withDescription(
                  "Missing child policy for LRS policy:\n " + rawConfig));
    }
    LoadBalancerRegistry lbRegistry = LoadBalancerRegistry.getDefaultRegistry();
    ConfigOrError selectedConfig =
        ServiceConfigUtil.selectLbPolicyFromList(childConfigCandidates, lbRegistry);
    if (selectedConfig.getError() != null) {
      return selectedConfig;
    }
    PolicySelection childPolicy = (PolicySelection) selectedConfig.getConfig();
    return
        ConfigOrError.fromConfig(
            new LrsConfig(clusterName, edsServiceName, lrsServerName, locality, childPolicy));
  }

  @Nullable
  private static Locality parseLocality(Map<String, ?> rawObject) {
    if (rawObject.size() != 3) {
      return null;
    }
    String region = JsonUtil.getString(rawObject, "region");
    if (region == null) {
      return null;
    }
    String zone = JsonUtil.getString(rawObject, "zone");
    if (zone == null) {
      return null;
    }
    String subZone = JsonUtil.getString(rawObject, "subZone");
    if (subZone == null) {
      return null;
    }
    return new Locality(region, zone, subZone);
  }

  static class LrsConfig {
    final String clusterName;
    @Nullable
    final String edsServiceName;
    final String lrsServerName;
    final Locality locality;
    final PolicySelection childPolicy;

    LrsConfig(
        String clusterName,
        @Nullable String edsServiceName,
        String lrsServerName,
        Locality locality,
        PolicySelection childPolicy) {
      this.clusterName = checkNotNull(clusterName, "clusterName");
      this.edsServiceName = edsServiceName;
      this.lrsServerName = checkNotNull(lrsServerName, "lrsServerName");
      this.locality = checkNotNull(locality, "locality");
      this.childPolicy = checkNotNull(childPolicy, "childPolicy");
    }
  }
}
