/*
 * Copyright 2016, gRPC Authors All rights reserved.
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

package io.grpc.grpclb;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Attributes;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.grpclb.GrpclbConstants.LbPolicy;
import io.grpc.internal.LogId;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.WithLogId;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A {@link LoadBalancer} that uses the GRPCLB protocol.
 *
 * <p>Optionally, when requested by the naming system, will delegate the work to a local pick-first
 * or round-robin balancer.
 */
class GrpclbLoadBalancer extends LoadBalancer implements WithLogId {
  private static final Logger logger = Logger.getLogger(GrpclbLoadBalancer.class.getName());

  private final LogId logId = LogId.allocate(getClass().getName());

  private final Helper helper;
  private final Factory pickFirstBalancerFactory;
  private final Factory roundRobinBalancerFactory;
  private final ObjectPool<ScheduledExecutorService> timerServicePool;
  private final TimeProvider time;

  // All mutable states in this class are mutated ONLY from Channel Executor

  private ScheduledExecutorService timerService;

  // If not null, all work is delegated to it.
  @Nullable
  private LoadBalancer delegate;
  private LbPolicy lbPolicy;

  // Null if lbPolicy != GRPCLB
  @Nullable
  private GrpclbState grpclbState;

  GrpclbLoadBalancer(Helper helper, Factory pickFirstBalancerFactory,
      Factory roundRobinBalancerFactory, ObjectPool<ScheduledExecutorService> timerServicePool,
      TimeProvider time) {
    this.helper = checkNotNull(helper, "helper");
    this.pickFirstBalancerFactory =
        checkNotNull(pickFirstBalancerFactory, "pickFirstBalancerFactory");
    this.roundRobinBalancerFactory =
        checkNotNull(roundRobinBalancerFactory, "roundRobinBalancerFactory");
    this.timerServicePool = checkNotNull(timerServicePool, "timerServicePool");
    this.timerService = checkNotNull(timerServicePool.getObject(), "timerService");
    this.time = checkNotNull(time, "time provider");
    setLbPolicy(LbPolicy.GRPCLB);
  }

  @Override
  public LogId getLogId() {
    return logId;
  }

  @Override
  public void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
    if (delegate != null) {
      delegate.handleSubchannelState(subchannel, newState);
      return;
    }
    if (grpclbState != null) {
      grpclbState.handleSubchannelState(subchannel, newState);
    }
  }

  @Override
  public void handleResolvedAddressGroups(
      List<EquivalentAddressGroup> updatedServers, Attributes attributes) {
    LbPolicy newLbPolicy = attributes.get(GrpclbConstants.ATTR_LB_POLICY);
    // LB addresses and backend addresses are treated separately
    List<LbAddressGroup> newLbAddressGroups = new ArrayList<LbAddressGroup>();
    List<EquivalentAddressGroup> newBackendServers = new ArrayList<EquivalentAddressGroup>();
    for (EquivalentAddressGroup server : updatedServers) {
      String lbAddrAuthority = server.getAttributes().get(GrpclbConstants.ATTR_LB_ADDR_AUTHORITY);
      if (lbAddrAuthority != null) {
        newLbAddressGroups.add(new LbAddressGroup(server, lbAddrAuthority));
      } else {
        newBackendServers.add(server);
      }
    }

    if (!newLbAddressGroups.isEmpty()) {
      if (newLbPolicy != LbPolicy.GRPCLB) {
        newLbPolicy = LbPolicy.GRPCLB;
        logger.log(
            Level.FINE, "[{0}] Switching to GRPCLB because there is at least one balancer", logId);
      }
    }
    if (newLbPolicy == null) {
      logger.log(Level.FINE, "[{0}] New config missing policy. Using PICK_FIRST", logId);
      newLbPolicy = LbPolicy.PICK_FIRST;
    }

    // Switch LB policy if requested
    setLbPolicy(newLbPolicy);

    // Consume the new addresses
    switch (lbPolicy) {
      case PICK_FIRST:
      case ROUND_ROBIN:
        checkNotNull(delegate, "delegate should not be null. newLbPolicy=" + newLbPolicy);
        delegate.handleResolvedAddressGroups(newBackendServers, attributes);
        break;
      case GRPCLB:
        if (newLbAddressGroups.isEmpty()) {
          grpclbState.propagateError(Status.UNAVAILABLE.withDescription(
                  "NameResolver returned no LB address while asking for GRPCLB"));
        } else {
          grpclbState.setLbAddress(flattenLbAddressGroups(newLbAddressGroups));
        }
        break;
      default:
        // Do nothing
    }
  }

  private void setLbPolicy(LbPolicy newLbPolicy) {
    if (newLbPolicy != lbPolicy) {
      resetStates();
      switch (newLbPolicy) {
        case PICK_FIRST:
          delegate = checkNotNull(pickFirstBalancerFactory.newLoadBalancer(helper),
              "pickFirstBalancerFactory.newLoadBalancer()");
          break;
        case ROUND_ROBIN:
          delegate = checkNotNull(roundRobinBalancerFactory.newLoadBalancer(helper),
              "roundRobinBalancerFactory.newLoadBalancer()");
          break;
        case GRPCLB:
          grpclbState = new GrpclbState(helper, time, timerService, logId);
          break;
        default:
          // Do nohting
      }
      // TODO(zhangkun83): if switched away from GRPCLB, clear all GRPCLB states and shutdown all
      // Subchannels so that when switched back to GRPCLB it will have a fresh start.
      //
      // TODO(zhangkun83): intercept the Helper to transfer Subchannels created by the delegate
      // balancers to GRPCLB if their addresses re-appear in the first response from the
      // balancer.  It returns intercepted Subchannel that let GRPCLB to control whether shutdown()
      // is passed to the actual Subchannel.  It wraps the picker that unwrap Subchannels from
      // the delegate, because the channel only accepts the original Subchannel.
      //
      // If fallback to RR happens after switching back to GRPCLB, maybe we don't want to
      // reconnect the Subchannels for the RR delegate. We may just keep the delegate balancer
      // unclosed to keep their Subchannels until balancer's first response arrives.
      //  1. When fallback timer expires, feed the resolver-returned backend addresses to the RR
      //     delegate, and use it as the current delegate.  If the RR delegate has Subchannels
      //     from the last time it was used, they may be re-used as long as addresses match.
      //  2. When first LB response arrives, set the current delegate to null, transfer any
      //     re-usable Subchannels (same address) from the RR delegate, and shut down the RR
      //     delegate.  The intercepted Helper returns interceptred Subchannels, which will not
      //     actually shutdown the Subchannel if it's been transferred from RR delegate to GRPCLB.
    }
    lbPolicy = newLbPolicy;
  }

  private void resetStates() {
    if (delegate != null) {
      delegate.shutdown();
      delegate = null;
    }
    if (grpclbState != null) {
      grpclbState.shutdown();
      grpclbState = null;
    }
  }

  @Override
  public void shutdown() {
    resetStates();
    timerService = timerServicePool.returnObject(timerService);
  }

  @Override
  public void handleNameResolutionError(Status error) {
    if (delegate != null) {
      delegate.handleNameResolutionError(error);
    }
    if (grpclbState != null) {
      grpclbState.propagateError(error);
    }
  }

  @VisibleForTesting
  @Nullable
  GrpclbState getGrpclbState() {
    return grpclbState;
  }

  @VisibleForTesting
  LoadBalancer getDelegate() {
    return delegate;
  }

  @VisibleForTesting
  LbPolicy getLbPolicy() {
    return lbPolicy;
  }

  private LbAddressGroup flattenLbAddressGroups(List<LbAddressGroup> groupList) {
    assert !groupList.isEmpty();
    List<EquivalentAddressGroup> eags = new ArrayList<EquivalentAddressGroup>(groupList.size());
    String authority = groupList.get(0).getAuthority();
    for (LbAddressGroup group : groupList) {
      if (!authority.equals(group.getAuthority())) {
        // TODO(ejona): Allow different authorities for different addresses. Requires support from
        // Helper.
        logger.log(Level.WARNING,
            "[{0}] Multiple authorities found for LB. "
            + "Skipping addresses for {0} in preference to {1}",
            new Object[] {logId, group.getAuthority(), authority});
      } else {
        eags.add(group.getAddresses());
      }
    }
    return new LbAddressGroup(flattenEquivalentAddressGroup(eags), authority);
  }

  /**
   * Flattens list of EquivalentAddressGroup objects into one EquivalentAddressGroup object.
   */
  private static EquivalentAddressGroup flattenEquivalentAddressGroup(
      List<EquivalentAddressGroup> groupList) {
    List<SocketAddress> addrs = new ArrayList<SocketAddress>();
    for (EquivalentAddressGroup group : groupList) {
      addrs.addAll(group.getAddresses());
    }
    return new EquivalentAddressGroup(addrs);
  }
}
