package io.grpc.util;

import io.grpc.LoadBalancer;

public abstract class ForwardingLoadBalancer extends LoadBalancer {
  /**
   * Returns the underlying balancer.
   */
  protected abstract LoadBalancer delegate();

  @Override
  public void handleResolvedAddressGroups(
        List<EquivalentAddressGroup> servers,
        @NameResolver.ResolutionResultAttr Attributes attributes) {
    delegate().handleResolvedAddressGroups(servers, attributes);
  }

  @Override
  public void handleNameResolutionError(Status error) {
    delegate().handleNameResolutionError(error);
  }

  @Override
  public void handleSubchannelState(
      Subchannel subchannel, ConnectivityStateInfo stateInfo) {
    delegate().handleSubchannelState(subchannel, stateInfo);
  }

  @Override
  public void shutdown() {
    delegate().shutdown();
  }
}
