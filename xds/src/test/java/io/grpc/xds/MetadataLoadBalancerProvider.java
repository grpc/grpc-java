/*
 * Copyright 2022 The gRPC Authors
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

import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Metadata;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.internal.JsonUtil;
import io.grpc.util.ForwardingLoadBalancer;
import io.grpc.util.ForwardingLoadBalancerHelper;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * A custom LB for testing purposes that simply delegates to round_robin and adds a metadata entry
 * to each request.
 */
public class MetadataLoadBalancerProvider extends LoadBalancerProvider {

  @Override
  public NameResolver.ConfigOrError parseLoadBalancingPolicyConfig(
      Map<String, ?> rawLoadBalancingPolicyConfig) {
    String metadataKey = JsonUtil.getString(rawLoadBalancingPolicyConfig, "metadataKey");
    if (metadataKey == null) {
      return NameResolver.ConfigOrError.fromError(
          Status.UNAVAILABLE.withDescription("no 'metadataKey' defined"));
    }

    String metadataValue = JsonUtil.getString(rawLoadBalancingPolicyConfig, "metadataValue");
    if (metadataValue == null) {
      return NameResolver.ConfigOrError.fromError(
          Status.UNAVAILABLE.withDescription("no 'metadataValue' defined"));
    }

    return NameResolver.ConfigOrError.fromConfig(
        new MetadataLoadBalancerConfig(metadataKey, metadataValue));
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    MetadataHelper metadataHelper = new MetadataHelper(helper);
    return new MetadataLoadBalancer(metadataHelper,
        LoadBalancerRegistry.getDefaultRegistry().getProvider("round_robin")
            .newLoadBalancer(metadataHelper));
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
    return "test.MetadataLoadBalancer";
  }

  static class MetadataLoadBalancerConfig {

    final String metadataKey;
    final String metadataValue;

    MetadataLoadBalancerConfig(String metadataKey, String metadataValue) {
      this.metadataKey = metadataKey;
      this.metadataValue = metadataValue;
    }
  }

  static class MetadataLoadBalancer extends ForwardingLoadBalancer {

    private final MetadataHelper helper;
    private final LoadBalancer delegateLb;

    MetadataLoadBalancer(MetadataHelper helper, LoadBalancer delegateLb) {
      this.helper = helper;
      this.delegateLb = delegateLb;
    }

    @Override
    protected LoadBalancer delegate() {
      return delegateLb;
    }

    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      MetadataLoadBalancerConfig config
          = (MetadataLoadBalancerConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
      helper.setMetadata(config.metadataKey, config.metadataValue);
      delegateLb.handleResolvedAddresses(resolvedAddresses);
    }
  }

  /**
   * Wraps the picker that is provided when the balancing change updates with the {@link
   * MetadataPicker} that injects the metadata entry.
   */
  static class MetadataHelper extends ForwardingLoadBalancerHelper {

    private final Helper delegateHelper;
    private String metadataKey;
    private String metadataValue;

    MetadataHelper(Helper delegateHelper) {
      this.delegateHelper = delegateHelper;
    }

    void setMetadata(String metadataKey, String metadataValue) {
      this.metadataKey = metadataKey;
      this.metadataValue = metadataValue;
    }

    @Override
    protected Helper delegate() {
      return delegateHelper;
    }

    @Override
    public void updateBalancingState(@Nonnull ConnectivityState newState,
        @Nonnull SubchannelPicker newPicker) {
      delegateHelper.updateBalancingState(newState,
          new MetadataPicker(newPicker, metadataKey, metadataValue));
    }
  }

  /**
   * Includes the rpc-behavior metadata entry on each subchannel pick.
   */
  static class MetadataPicker extends SubchannelPicker {

    private final SubchannelPicker delegatePicker;
    private final String metadataKey;
    private final String metadataValue;

    MetadataPicker(SubchannelPicker delegatePicker, String metadataKey, String metadataValue) {
      this.delegatePicker = delegatePicker;
      this.metadataKey = metadataKey;
      this.metadataValue = metadataValue;
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      args.getHeaders()
          .put(Metadata.Key.of(metadataKey, Metadata.ASCII_STRING_MARSHALLER), metadataValue);
      return delegatePicker.pickSubchannel(args);
    }
  }
}
