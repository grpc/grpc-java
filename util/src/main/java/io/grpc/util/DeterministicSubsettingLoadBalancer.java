package io.grpc.util;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import io.grpc.EquivalentAddressGroup;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import java.net.SocketAddress;
import java.util.ArrayList;

/**
 * Wraps a child {@code LoadBalancer}, separating the total set of backends
 * into smaller subsets for the child balancer to balance across.
 *
 * This implements deterministic subsetting gRFC:
 * https://github.com/grpc/proposal/blob/master/A68-deterministic-subsetting-lb-policy.md
 */
@Internal
public final class DeterministicSubsettingLoadBalancer extends LoadBalancer {

  private final GracefulSwitchLoadBalancer switchLb;

  @Override
  public boolean acceptResolvedAddresses(ResolvedAddresses resolvedAddresses){
    DeterministicSubsettingLoadBalancerConfig config
      = (DeterministicSubsettingLoadBalancerConfig) resolvedAddresses.getLoadBalancingPolicyConfig();


    ArrayList<SocketAddress> newAddresses = new ArrayList<>();
    for (EquivalentAddressGroup addressGroup : resolvedAddresses.getAddresses()){
      newAddresses.addAll(addressGroup.getAddresses());
    }

    switchLb.switchTo(config.childPolicy.getProvider());

    // TODO: SUBSET
    ResolvedAddresses subsetAddresses;

    switchLb.handleResolvedAddresses(
      subsetAddresses.toBuilder().setLoadBalancingPolicyConfig(config.childPolicy.getConfig())
        .build());
    return true;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    switchLb.handleNameResolutionError(error);
  }

  @Override
  public void shutdown() {
    switchLb.shutdown();
  }

  public DeterministicSubsettingLoadBalancer(Helper helper){
    ChildHelper childHelper = new ChildHelper(checkNotNull(helper, "helper"));
    switchLb = new GracefulSwitchLoadBalancer(childHelper);
  }

  class ChildHelper extends ForwardingLoadBalancerHelper {
    private Helper delegate;

    ChildHelper(Helper delegate){
      this.delegate = delegate;
    }

    @Override
    protected Helper delegate() {
      return delegate;
    }

    @Override
    public Subchannel createSubchannel(CreateSubchannelArgs args) {
      DeterministicSubsettingSubchannel subchannel = new DeterministicSubsettingSubchannel(delegate.createSubchannel(args));
      return subchannel;
    }
  }

  class DeterministicSubsettingSubchannel extends ForwardingSubchannel {

    private final Subchannel delegate;

    DeterministicSubsettingSubchannel(Subchannel delegate) {
      this.delegate = delegate;
    }

    @Override
    protected Subchannel delegate() {
      return this.delegate;
    }
  }

  public static final class DeterministicSubsettingLoadBalancerConfig {

    public final Integer clientIndex;
    public final Integer subsetSize;
    public final Boolean sortAddresses;

    public final PolicySelection childPolicy;

    private DeterministicSubsettingLoadBalancerConfig(
        Integer clientIndex,
        Integer subsetSize,
        Boolean sortAddresses,
        PolicySelection childPolicy) {
      this.clientIndex = clientIndex;
      this.subsetSize = subsetSize;
      this.sortAddresses = sortAddresses;
      this.childPolicy = childPolicy;
    }


    public static class Builder {
      Integer clientIndex; // There's really no great way to set a default here.
      Integer subsetSize = 10;

      Boolean sortAddresses;
      PolicySelection childPolicy;

      public Builder setClientIndex (Integer clientIndex){
        checkArgument(clientIndex != null);
        this.clientIndex = clientIndex;
        return this;
      }

      public Builder setSubsetSize (Integer subsetSize){
        checkArgument(subsetSize != null);
        this.subsetSize = subsetSize;
        return this;
      }

      public Builder setSortAddresses (Boolean sortAddresses){
        checkArgument(sortAddresses != null);
        this.sortAddresses = sortAddresses;
        return this;
      }

      public Builder setChildPolicy (PolicySelection childPolicy){
        checkState(childPolicy != null);
        this.childPolicy = childPolicy;
        return this;
      }

      public DeterministicSubsettingLoadBalancerConfig build () {
        checkState(childPolicy != null);
        checkState(clientIndex != null);
        return new DeterministicSubsettingLoadBalancerConfig(clientIndex, subsetSize, sortAddresses, childPolicy);
      }
    }
  }
}
