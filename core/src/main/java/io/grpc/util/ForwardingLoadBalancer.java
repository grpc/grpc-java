package io.grpc.util;

import io.grpc.Attributes;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer;
import io.grpc.NameResolver;
import io.grpc.Status;
import java.util.List;

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
