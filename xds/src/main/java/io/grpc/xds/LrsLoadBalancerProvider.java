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

import com.google.common.base.MoreObjects;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.xds.EnvoyProtoData.Locality;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Provider for lrs load balancing policy.
 */
@Internal
public final class LrsLoadBalancerProvider extends LoadBalancerProvider {

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
    throw new UnsupportedOperationException();
  }

  static final class LrsConfig {
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

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("clusterName", clusterName)
          .add("edsServiceName", edsServiceName)
          .add("lrsServerName", lrsServerName)
          .add("locality", locality)
          .add("childPolicy", childPolicy)
          .toString();
    }
  }
}
