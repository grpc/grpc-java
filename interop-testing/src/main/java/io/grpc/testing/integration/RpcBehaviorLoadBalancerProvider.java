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

package io.grpc.testing.integration;

import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Metadata;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.JsonUtil;
import io.grpc.util.ForwardingLoadBalancer;
import io.grpc.util.ForwardingLoadBalancerHelper;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Provides a xDS interop test {@link LoadBalancer} designed to work with {@link XdsTestServer}. It
 * looks for an "rpc_behavior" field in its configuration and includes the value in the
 * "rpc-behavior" metadata entry that is sent to the server. This will cause the test server to
 * behave in a predefined way. Endpoint picking logic is delegated to the
 * io.grpc.util.RoundRobinLoadBalancer.
 *
 * <p>Initial use case is to prove that a custom load balancer can be configured by the control
 * plane via xDS. An interop test will configure this LB and then verify it has been correctly
 * configured by observing a specific RPC behavior by the server(s).
 *
 * <p>For more details on what behaviors can be specified, please see:
 * https://github.com/grpc/grpc/blob/master/doc/xds-test-descriptions.md#server
 */
public class RpcBehaviorLoadBalancerProvider extends LoadBalancerProvider {

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawLoadBalancingPolicyConfig) {
    String rpcBehavior = JsonUtil.getString(rawLoadBalancingPolicyConfig, "rpcBehavior");
    if (rpcBehavior == null) {
      return ConfigOrError.fromError(
          Status.UNAVAILABLE.withDescription("no 'rpcBehavior' defined"));
    }
    return ConfigOrError.fromConfig(new RpcBehaviorConfig(rpcBehavior));
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    RpcBehaviorHelper rpcBehaviorHelper = new RpcBehaviorHelper(helper);
    return new RpcBehaviorLoadBalancer(rpcBehaviorHelper,
        LoadBalancerRegistry.getDefaultRegistry().getProvider("round_robin")
            .newLoadBalancer(rpcBehaviorHelper));
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
    return "test.RpcBehaviorLoadBalancer";
  }

  static class RpcBehaviorConfig {

    final String rpcBehavior;

    RpcBehaviorConfig(String rpcBehavior) {
      this.rpcBehavior = rpcBehavior;
    }
  }

  /**
   * Delegates all calls to another LB and wraps the given helper in {@link RpcBehaviorHelper} that
   * assures that the rpc-behavior metadata header gets added to all calls.
   */
  static class RpcBehaviorLoadBalancer extends ForwardingLoadBalancer {

    private final RpcBehaviorHelper helper;
    private final LoadBalancer delegateLb;

    RpcBehaviorLoadBalancer(RpcBehaviorHelper helper, LoadBalancer delegateLb) {
      this.helper = helper;
      this.delegateLb = delegateLb;
    }

    @Override
    protected LoadBalancer delegate() {
      return delegateLb;
    }

    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      helper.setRpcBehavior(
          ((RpcBehaviorConfig) resolvedAddresses.getLoadBalancingPolicyConfig()).rpcBehavior);
      delegateLb.handleResolvedAddresses(resolvedAddresses);
    }
  }

  /**
   * Wraps the picker that is provided when the balancing change updates with the {@link
   * RpcBehaviorPicker} that injects the rpc-behavior metadata entry.
   */
  static class RpcBehaviorHelper extends ForwardingLoadBalancerHelper {

    private final Helper delegateHelper;
    private String rpcBehavior;

    RpcBehaviorHelper(Helper delegateHelper) {
      this.delegateHelper = delegateHelper;
    }

    void setRpcBehavior(String rpcBehavior) {
      this.rpcBehavior = rpcBehavior;
    }

    @Override
    protected Helper delegate() {
      return delegateHelper;
    }

    @Override
    public void updateBalancingState(@Nonnull ConnectivityState newState,
        @Nonnull SubchannelPicker newPicker) {
      delegateHelper.updateBalancingState(newState, new RpcBehaviorPicker(newPicker, rpcBehavior));
    }
  }

  /**
   * Includes the rpc-behavior metadata entry on each subchannel pick.
   */
  static class RpcBehaviorPicker extends SubchannelPicker {

    private static final String RPC_BEHAVIOR_HEADER_KEY = "rpc-behavior";

    private final SubchannelPicker delegatePicker;
    private final String rpcBehavior;

    RpcBehaviorPicker(SubchannelPicker delegatePicker, String rpcBehavior) {
      this.delegatePicker = delegatePicker;
      this.rpcBehavior = rpcBehavior;
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      args.getHeaders()
          .put(Metadata.Key.of(RPC_BEHAVIOR_HEADER_KEY, Metadata.ASCII_STRING_MARSHALLER),
              rpcBehavior);
      return delegatePicker.pickSubchannel(args);
    }
  }
}
