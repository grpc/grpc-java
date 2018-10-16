package io.grpc.services;

public final class HealthCheckingLoadBalancerFactory extends LoadBalancer.Factory {
  private final LoadBalancer.Factory delegate;

  public HealthCheckingLoadBalancerFactory(LoadBalancer.Factory delegate) {
    this.delegate = checkNotNull(delegate, "delegate");
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    
  }

  private static class HelperImpl extends ForwardingLoadBalancerHelper {
    @Override
    public Subchannel createSubchannel
  }
}
