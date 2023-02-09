/*
 * Copyright 2023 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.TimeProvider;
import io.grpc.services.MetricReport;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.ForwardingSubchannel;
import io.grpc.util.RoundRobinLoadBalancer;
import io.grpc.xds.orca.OrcaOobUtil;
import io.grpc.xds.orca.OrcaOobUtil.OrcaOobReportListener;
import io.grpc.xds.orca.OrcaPerRequestUtil;
import io.grpc.xds.orca.OrcaPerRequestUtil.OrcaPerRequestReportListener;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link LoadBalancer} that provides weighted-round-robin load-balancing over
 * the {@link EquivalentAddressGroup}s from the {@link NameResolver}. The subchannel weights are
 * determined by backend metrics using ORCA.
 */
@Internal
public final class WeightedRoundRobinLoadBalancer extends RoundRobinLoadBalancer {
  private volatile WeightedRoundRobinLoadBalancerConfig config;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timeService;
  private ScheduledHandle weightUpdateTimer;
  private WeightedRoundRobinPicker readyPicker;

  public WeightedRoundRobinLoadBalancer(Helper helper, TimeProvider timeProvider) {
    super(new WrrHelper(OrcaOobUtil.newOrcaReportingHelper(helper),
            checkNotNull(timeProvider, "timeProvider")));
    this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    this.timeService = checkNotNull(helper.getScheduledExecutorService(), "timeService");
  }

  @Override
  public boolean acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    if (resolvedAddresses.getLoadBalancingPolicyConfig() == null) {
      handleNameResolutionError(Status.UNAVAILABLE.withDescription(
              "NameResolver returned no WeightedRoundRobinLoadBalancerConfig. addrs="
                      + resolvedAddresses.getAddresses()
                      + ", attrs=" + resolvedAddresses.getAttributes()));
      return false;
    }
    config =
            (WeightedRoundRobinLoadBalancerConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    boolean accepted = super.acceptResolvedAddresses(resolvedAddresses);
    new UpdateWeightTask().run();
    afterAcceptAddresses();
    return accepted;
  }

  @Override
  public RoundRobinPicker createReadyPicker(List<Subchannel> activeList, int startIndex) {
    this.readyPicker = new WeightedRoundRobinPicker(activeList, startIndex);
    return readyPicker;
  }

  private final class UpdateWeightTask implements Runnable {
    @Override
    public void run() {
      if (weightUpdateTimer != null && weightUpdateTimer.isPending()) {
        return;
      }
      if (readyPicker != null) {
        readyPicker.updateWeight();
      }
      weightUpdateTimer = syncContext.schedule(new UpdateWeightTask(),
              config.weightUpdatePeriodNanos, TimeUnit.NANOSECONDS, timeService);
    }
  }

  private void afterAcceptAddresses() {
    for (Subchannel subchannel : getSubchannels()) {
      WrrSubchannel weightedSubchannel = (WrrSubchannel) subchannel;
      weightedSubchannel.setConfig(config);
      if (config.enableOobLoadReport) {
        OrcaOobUtil.setListener(weightedSubchannel, weightedSubchannel.oobListener,
                OrcaOobUtil.OrcaReportingConfig.newBuilder()
                        .setReportInterval(config.oobReportingPeriodNanos, TimeUnit.NANOSECONDS)
                        .build());
      } else {
        OrcaOobUtil.setListener(weightedSubchannel, null, null);
      }
    }
  }

  @Override
  public void shutdown() {
    if (weightUpdateTimer != null) {
      weightUpdateTimer.cancel();
    }
    super.shutdown();
  }

  private static final class WrrHelper extends ForwardingLoadBalancerHelper {
    private final Helper delegate;
    private final TimeProvider timeProvider;

    WrrHelper(Helper helper, TimeProvider timeProvider) {
      this.delegate = helper;
      this.timeProvider = timeProvider;
    }

    @Override
    protected Helper delegate() {
      return delegate;
    }

    @Override
    public Subchannel createSubchannel(CreateSubchannelArgs args) {
      return new WrrSubchannel(delegate().createSubchannel(args), timeProvider);
    }
  }

  @VisibleForTesting
  static final class WrrSubchannel extends ForwardingSubchannel {
    private final Subchannel delegate;
    private final TimeProvider timeProvider;
    private final OrcaOobReportListener oobListener = this::onLoadReport;
    private final OrcaPerRequestReportListener perRpcListener = this::onLoadReport;
    volatile long lastUpdated;
    volatile long nonEmptySince;
    volatile double weight;
    private volatile WeightedRoundRobinLoadBalancerConfig config;

    WrrSubchannel(Subchannel delegate, TimeProvider timeProvider) {
      this.delegate = checkNotNull(delegate, "delegate");
      this.timeProvider = checkNotNull(timeProvider, "timeProvider");
    }

    private void setConfig(WeightedRoundRobinLoadBalancerConfig config) {
      this.config = config;
    }

    @VisibleForTesting
    void onLoadReport(MetricReport report) {
      double newWeight = report.getCpuUtilization() == 0 ? 0 :
              report.getQps() / report.getCpuUtilization();
      if (newWeight == 0) {
        return;
      }
      if (nonEmptySince == Integer.MAX_VALUE) {
        nonEmptySince = timeProvider.currentTimeNanos();
      }
      lastUpdated = timeProvider.currentTimeNanos();
      weight = newWeight;
    }

    @Override
    public void start(SubchannelStateListener listener) {
      delegate().start(new SubchannelStateListener() {
        @Override
        public void onSubchannelState(ConnectivityStateInfo newState) {
          if (newState.getState().equals(ConnectivityState.READY)) {
            nonEmptySince = Integer.MAX_VALUE;
          }
          listener.onSubchannelState(newState);
        }
      });
    }

    private double getWeight() {
      if (config == null) {
        return 0;
      }
      double now = timeProvider.currentTimeNanos();
      if (now - lastUpdated >= config.weightExpirationPeriodNanos) {
        nonEmptySince = Integer.MAX_VALUE;
        return 0;
      } else if (now - nonEmptySince < config.blackoutPeriodNanos) {
        return 0;
      } else {
        return weight;
      }
    }

    @Override
    protected Subchannel delegate() {
      return delegate;
    }
  }

  @VisibleForTesting
  final class WeightedRoundRobinPicker extends ReadyPicker {
    private final List<Subchannel> list;
    private final AtomicReference<EdfScheduler> schedulerRef;
    private volatile boolean rrMode = false;

    WeightedRoundRobinPicker(List<Subchannel> list, int startIndex) {
      super(list, startIndex);
      Preconditions.checkArgument(!list.isEmpty(), "empty list");
      this.list = list;
      this.schedulerRef = new AtomicReference<>(new EdfScheduler());
      updateWeight();
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      if (rrMode) {
        return super.pickSubchannel(args);
      }
      int pickIndex = schedulerRef.get().pick();
      WrrSubchannel subchannel = (WrrSubchannel) list.get(pickIndex);
      if (!config.enableOobLoadReport) {
        return PickResult.withSubchannel(
           subchannel,
           OrcaPerRequestUtil.getInstance().newOrcaClientStreamTracerFactory(
               subchannel.perRpcListener));
      } else {
        return PickResult.withSubchannel(subchannel);
      }
    }

    private void updateWeight() {
      EdfScheduler scheduler = new EdfScheduler();
      int weightedChannelCount = 0;
      double avgWeight = 0;
      for (Subchannel value : list) {
        double newWeight = ((WrrSubchannel) value).getWeight();
        if (newWeight > 0) {
          avgWeight += newWeight;
          weightedChannelCount++;
        }
      }
      rrMode = weightedChannelCount < 2;
      if (rrMode) {
        return;
      }
      for (int i = 0; i < list.size(); i++) {
        WrrSubchannel subchannel = (WrrSubchannel) list.get(i);
        double newWeight = subchannel.getWeight();
        scheduler.add(i, newWeight > 0 ? newWeight : avgWeight);
      }
      schedulerRef.set(scheduler);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(WeightedRoundRobinPicker.class)
          .add("list", list).toString();
    }

    @VisibleForTesting
    List<Subchannel> getList() {
      return list;
    }

    @Override
    public boolean isEquivalentTo(RoundRobinPicker picker) {
      if (!(picker instanceof WeightedRoundRobinPicker)) {
        return false;
      }
      WeightedRoundRobinPicker other = (WeightedRoundRobinPicker) picker;
      // the lists cannot contain duplicate subchannels
      return other == this
          || (list.size() == other.list.size() && new HashSet<>(list).containsAll(other.list));
    }
  }

  /**
   * The earliest deadline first implementation in which each object is
   * chosen deterministically and periodically with frequency proportional to its weight.
   *
   * <p>Specifically, each object added to chooser is given a period equal to the multiplicative
   * inverse of its weight. The place of each object in its period is tracked, and each call to
   * choose returns the object with the least remaining time in its period (1/weight).
   * (Ties are broken by the order in which the children were added to the chooser.)
   * For example, if items A and B are added
   * with weights 0.5 and 0.2, successive chooses return:
   *
   * <ul>
   *   <li>In the first call, the remaining periods are A=2 (1/0.5) and B=5 (1/0.2), so A is
   *   returned. The period of A (as it was picked), is substracted from periods of all other
   *   objects.
   *   <li>Next, the remaining periods are A=2 and B=3, so A is returned. The period of A (2) is
   *       substracted from all other objects (B=1) and A is re-added with A=2.
   *   <li>Remaining periods are A=2 and B=1, so B is returned. The period of B (1) is substracted
   *       from all other objects (A=1) and B is re-added with B=5.
   *   <li>Remaining periods are A=1 and B=5, so A is returned. The period of A (1) is substracted
   *       from all other objects (B=4) and A is re-added with A=2.
   *   <li>Remaining periods are A=2 and B=4, so A is returned. The period of A (2) is substracted
   *       from all other objects (B=2) and A is re-added with A=2.
   *   <li>Remaining periods are A=2 and B=2, so A is returned. The period of A (2) is substracted
   *       from all other objects (B=0) and A is re-added with A=2.
   *   <li>Remaining periods are A=2 and B=0, so B is returned. The period of B (0) is substracted
   *       from all other objects (A=2) and B is re-added with B=5.
   *   <li>etc.
   * </ul>
   *
   * <p>In short: the entry with the highest weight is preferred. In case of ties, the object that
   * was last returned will be preferred.
   *
   * <ul>
   *   <li>add() - O(lg n)
   *   <li>remove() - O(lg n)
   *   <li>pick() - O(lg n) with worst case O(n)
   * </ul>
   *
   */
  private static final class EdfScheduler {
    private final PriorityQueue<ObjectState> prioQueue;

    /**
     * Upon every pick() the "virtual time" is advanced closer to the period of next items.
     * Here we have an explicit "virtualTimeNow", which will be added to the period of all newly
     * scheduled objects (virtualTimeNow + period).
     */
    private double virtualTimeNow = 0.0;

    /**
     * Weights below this value will be logged and upped to this minimum weight.
     */
    private static final double MINIMUM_WEIGHT = 0.0001;

    private final Object lock = new Object();

    /**
     * Use the item's deadline as the order in the priority queue. If the deadlines are the same,
     * use the index. Index should be unique.
     */
    EdfScheduler() {
      this.prioQueue = new PriorityQueue<ObjectState>(10, (o1, o2) -> {
        if (o1.deadline == o2.deadline) {
          return o1.index - o2.index;
        } else if (o1.deadline < o2.deadline) {
          return -1;
        } else {
          return 1;
        }
      });
    }

    /**
     * Adds (or updates) the item in the scheduler. This is not thread safe.
     *
     * @param index The field {@link ObjectState#index} to be added/updated
     * @param weight positive weight for the added/updated object
     */
    void add(int index, double weight) {
      checkArgument(weight > 0.0, "Weights need to be positive.");
      ObjectState state = new ObjectState(Math.max(weight, MINIMUM_WEIGHT), index);
      state.deadline = virtualTimeNow + 1 / state.weight;
      prioQueue.add(state);
    }

    /**
     * Picks the next WRR object.
     */
    int pick() {
      synchronized (lock) {
        ObjectState minObject = prioQueue.remove();
        // Simulate advancing in time by setting the current time to the period of the nearest item
        // on the "time horizon".
        virtualTimeNow = minObject.deadline;
        minObject.deadline = virtualTimeNow + (1.0 / minObject.weight);
        prioQueue.add(minObject);
        return minObject.index;
      }
    }
  }

  /** Holds the state of the object. */
  @VisibleForTesting
  static class ObjectState {
    private final double weight;
    private final int index;
    volatile double deadline;

    ObjectState(double weight, int index) {
      this.weight = weight;
      this.index = index;
    }
  }

  static final class WeightedRoundRobinLoadBalancerConfig {
    final Long blackoutPeriodNanos;
    final Long weightExpirationPeriodNanos;
    final Boolean enableOobLoadReport;
    final Long oobReportingPeriodNanos;
    final Long weightUpdatePeriodNanos;

    public static Builder newBuilder() {
      return new Builder();
    }

    private WeightedRoundRobinLoadBalancerConfig(Long blackoutPeriodNanos,
                                                 Long weightExpirationPeriodNanos,
                                                 Boolean enableOobLoadReport,
                                                 Long oobReportingPeriodNanos,
                                                 Long weightUpdatePeriodNanos) {
      this.blackoutPeriodNanos = blackoutPeriodNanos;
      this.weightExpirationPeriodNanos = weightExpirationPeriodNanos;
      this.enableOobLoadReport = enableOobLoadReport;
      this.oobReportingPeriodNanos = oobReportingPeriodNanos;
      this.weightUpdatePeriodNanos = weightUpdatePeriodNanos;
    }

    static final class Builder {
      Long blackoutPeriodNanos = 10_000_000_000L; // 10s
      Long weightExpirationPeriodNanos = 180_000_000_000L; //3min
      Boolean enableOobLoadReport = false;
      Long oobReportingPeriodNanos = 10_000_000_000L; // 10s
      Long weightUpdatePeriodNanos = 1_000_000_000L; // 1s

      private Builder() {

      }

      Builder setBlackoutPeriodNanos(Long blackoutPeriodNanos) {
        checkArgument(blackoutPeriodNanos != null);
        this.blackoutPeriodNanos = blackoutPeriodNanos;
        return this;
      }

      Builder setWeightExpirationPeriodNanos(Long weightExpirationPeriodNanos) {
        checkArgument(weightExpirationPeriodNanos != null);
        this.weightExpirationPeriodNanos = weightExpirationPeriodNanos;
        return this;
      }

      Builder setEnableOobLoadReport(Boolean enableOobLoadReport) {
        checkArgument(enableOobLoadReport != null);
        this.enableOobLoadReport = enableOobLoadReport;
        return this;
      }

      Builder setOobReportingPeriodNanos(Long oobReportingPeriodNanos) {
        checkArgument(oobReportingPeriodNanos != null);
        this.oobReportingPeriodNanos = oobReportingPeriodNanos;
        return this;
      }

      Builder setWeightUpdatePeriodNanos(Long weightUpdatePeriodNanos) {
        checkArgument(weightUpdatePeriodNanos != null);
        this.weightUpdatePeriodNanos = weightUpdatePeriodNanos;
        return this;
      }

      WeightedRoundRobinLoadBalancerConfig build() {
        return new WeightedRoundRobinLoadBalancerConfig(blackoutPeriodNanos,
                weightExpirationPeriodNanos, enableOobLoadReport, oobReportingPeriodNanos,
                weightUpdatePeriodNanos);
      }
    }
  }
}
