package io.grpc.services;

public final class HealthCheckingLoadBalancerFactory extends LoadBalancer.Factory {
  private static final Attributes.Key<Ref<ConnectivityStateInfo>> STATE_INFO =
      Attributes.Key.create("io.grpc.services.HealthCheckingLoadBalancerFactory.stateInfo");

  private final LoadBalancer.Factory delegate;

  public HealthCheckingLoadBalancerFactory(LoadBalancer.Factory delegate) {
    this.delegate = checkNotNull(delegate, "delegate");
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    
  }

  private static final class HelperImpl extends ForwardingLoadBalancerHelper {
    private final LoadBalancer.Helper delegate;

    HelperImpl(LoadBalancer.Helper delegate) {
      this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    protected LoadBalancer.Helper delegate() {
      return delegate;
    }

    @Override
    public Subchannel createSubchannel(List<EquivalentAddressGroup> addrs, Attributes attrs) {
      return super.createSubchannel(
          addrs,
          attrs.toBuilder()
              .set(STATE_INFO, new Ref(ConnectivityStateInfo.forNonError(IDLE))).build());
    }
  }

  /*
  private static class SubchannelImpl extends ForwardingSubchannel {
  }
  */

  private static final class LoadBalancerImpl extends ForwardingLoadBalancer {
    private final LoadBalancer delegate;

    LoadBalancerImpl(LoadBalancer delegate) {
      this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    protected LoadBalancer delegate() {
      return delegate;
    }

    @Override
    public void handleSubchannelState(
        Subchannel subchannel, ConnectivityStateInfo stateInfo) {
      StateRef stateRef = checkNotNull(subchannel.getAttributes().get(STATE_INFO), "STATE_INFO");
      ConnectivityStateInfo prevState = stateRef.value;
      if (stateInfo.getState().equals(ConnectivityState.READY)) {

      }
      super.handleSubchannelState(subchannel, stateInfo);
    }
  }

  private static final class StateRef {
    ConnectivityStateInfo value;

    Ref(ConnectivityStateInfo value) {
      this.value = value;
    }
  }

  private static final class HealthChecker {
  }
}
