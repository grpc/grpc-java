package io.grpc.util;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.Lists;
import io.grpc.EquivalentAddressGroup;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

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

    // The map should only retain entries for addresses in this latest update.
    ArrayList<SocketAddress> allAddresses = new ArrayList<>();
    for (EquivalentAddressGroup addressGroup : resolvedAddresses.getAddresses()){
      allAddresses.addAll(addressGroup.getAddresses());
    }

    switchLb.switchTo(config.childPolicy.getProvider());

    ResolvedAddresses subsetAddresses = buildSubsets(resolvedAddresses, config);

    switchLb.handleResolvedAddresses(
      subsetAddresses.toBuilder().setLoadBalancingPolicyConfig(config.childPolicy.getConfig())
        .build());
    return true;
  }

  // implements the subsetting algorithm, as described in A68: https://github.com/grpc/proposal/pull/383
  private ResolvedAddresses buildSubsets(ResolvedAddresses allAddresses, DeterministicSubsettingLoadBalancerConfig config){
    // The map should only retain entries for addresses in this latest update.
    ArrayList<SocketAddress> addresses = new ArrayList<>();
    for (EquivalentAddressGroup addressGroup : allAddresses.getAddresses()){
      addresses.addAll(addressGroup.getAddresses());
    }

    if (addresses.size() <= config.subsetSize) return allAddresses;
    if (config.sortAddresses) {
      // If we sort, we do so via destination hashcode. This is deterministic but differs from the goland instrumentation.
      addresses.sort(new AddressComparator());
    }

    Integer backendCount = addresses.size();
    Integer subsetCount = backendCount / config.subsetSize;

    Integer round = config.clientIndex / subsetCount;
    
    Integer excludedCount = backendCount % config.subsetSize;
    Integer excludedStart = (round * excludedCount) % backendCount;
    Integer excludedEnd = (excludedStart + excludedCount) % backendCount;
    if (excludedStart <= excludedEnd) {
      List<SocketAddress> subList = addresses.subList(0, excludedStart);
      subList.addAll(addresses.subList(excludedEnd, backendCount));
      addresses = new ArrayList<>(subList);
    } else {
      addresses = new ArrayList<>(addresses.subList(excludedEnd, excludedStart));
    }

    Random r = new Random(round);
    Collections.shuffle(addresses, r);

    Integer subsetId = config.clientIndex % subsetCount;

    Integer start = subsetId * config.subsetSize;
    Integer end = start + config.subsetSize;

    List<SocketAddress> subset = addresses.subList(start, end);

    ArrayList<EquivalentAddressGroup>  eaglist = new ArrayList<>();

    for (EquivalentAddressGroup eag : allAddresses.getAddresses()) {
      List<SocketAddress> addrs = Lists.newArrayList();
      for (SocketAddress addr :eag.getAddresses()) {
        if (subset.contains(addr)) {
          addrs.add(addr);
        }
      }
      if (addrs.size() != 0) {
        eaglist.add(new EquivalentAddressGroup(addrs));
      }
    }

    ResolvedAddresses.Builder builder = allAddresses.toBuilder();
    return builder.setAddresses(eaglist).build();
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

  class AddressComparator implements Comparator<SocketAddress> {
    @Override
    public int compare(SocketAddress o1, SocketAddress o2){
      return o1.hashCode() - o2.hashCode();
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
      Integer clientIndex;
      Integer subsetSize = 10;

      Boolean sortAddresses;
      PolicySelection childPolicy;

      public Builder setClientIndex (Integer clientIndex){
        checkState(clientIndex != null);
        // Indices must be positive integers.
        checkState(clientIndex >= 0);
        this.clientIndex = clientIndex;
        return this;
      }

      public Builder setSubsetSize (Integer subsetSize){
        checkArgument(subsetSize != null);
        // subsetSize of 1 is equivalent to `pick_first`. Use that policy if that behavior is desired.
        // Fallback to default of 10 of condition is not satisfied.
        checkArgument(subsetSize > 1);
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
