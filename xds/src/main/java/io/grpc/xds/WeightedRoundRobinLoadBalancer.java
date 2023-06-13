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
import io.grpc.Deadline.Ticker;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ExperimentalApi;
import io.grpc.LoadBalancer;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.services.MetricReport;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.ForwardingSubchannel;
import io.grpc.util.RoundRobinLoadBalancer;
import io.grpc.xds.orca.OrcaOobUtil;
import io.grpc.xds.orca.OrcaOobUtil.OrcaOobReportListener;
import io.grpc.xds.orca.OrcaPerRequestUtil;
import io.grpc.xds.orca.OrcaPerRequestUtil.OrcaPerRequestReportListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link LoadBalancer} that provides weighted-round-robin load-balancing over
 * the {@link EquivalentAddressGroup}s from the {@link NameResolver}. The subchannel weights are
 * determined by backend metrics using ORCA.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/9885")
final class WeightedRoundRobinLoadBalancer extends RoundRobinLoadBalancer {
  private static final Logger log = Logger.getLogger(
      WeightedRoundRobinLoadBalancer.class.getName());
  private WeightedRoundRobinLoadBalancerConfig config;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timeService;
  private ScheduledHandle weightUpdateTimer;
  private final Runnable updateWeightTask;
  private final Random random;
  private final long infTime;
  private final Ticker ticker;

  public WeightedRoundRobinLoadBalancer(Helper helper, Ticker ticker) {
    this(new WrrHelper(OrcaOobUtil.newOrcaReportingHelper(helper)), ticker, new Random());
  }

  public WeightedRoundRobinLoadBalancer(WrrHelper helper, Ticker ticker, Random random) {
    super(helper);
    helper.setLoadBalancer(this);
    this.ticker = checkNotNull(ticker, "ticker");
    this.infTime = ticker.nanoTime() + Long.MAX_VALUE;
    this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    this.timeService = checkNotNull(helper.getScheduledExecutorService(), "timeService");
    this.updateWeightTask = new UpdateWeightTask();
    this.random = random;
    log.log(Level.FINE, "weighted_round_robin LB created");
  }

  @VisibleForTesting
  WeightedRoundRobinLoadBalancer(Helper helper, Ticker ticker, Random random) {
    this(new WrrHelper(OrcaOobUtil.newOrcaReportingHelper(helper)), ticker, random);
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
    if (weightUpdateTimer != null && weightUpdateTimer.isPending()) {
      weightUpdateTimer.cancel();
    }
    updateWeightTask.run();
    afterAcceptAddresses();
    return accepted;
  }

  @Override
  public RoundRobinPicker createReadyPicker(List<Subchannel> activeList) {
    return new WeightedRoundRobinPicker(activeList, config.enableOobLoadReport,
        config.errorUtilizationPenalty);
  }

  private final class UpdateWeightTask implements Runnable {
    @Override
    public void run() {
      if (currentPicker != null && currentPicker instanceof WeightedRoundRobinPicker) {
        ((WeightedRoundRobinPicker) currentPicker).updateWeightSS();
      }
      weightUpdateTimer = syncContext.schedule(this, config.weightUpdatePeriodNanos,
          TimeUnit.NANOSECONDS, timeService);
    }
  }

  private void afterAcceptAddresses() {
    for (Subchannel subchannel : getSubchannels()) {
      WrrSubchannel weightedSubchannel = (WrrSubchannel) subchannel;
      if (config.enableOobLoadReport) {
        OrcaOobUtil.setListener(weightedSubchannel,
            weightedSubchannel.new OrcaReportListener(config.errorUtilizationPenalty),
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
    private WeightedRoundRobinLoadBalancer wrr;

    WrrHelper(Helper helper) {
      this.delegate = helper;
    }

    void setLoadBalancer(WeightedRoundRobinLoadBalancer lb) {
      this.wrr = lb;
    }

    @Override
    protected Helper delegate() {
      return delegate;
    }

    @Override
    public Subchannel createSubchannel(CreateSubchannelArgs args) {
      return wrr.new WrrSubchannel(delegate().createSubchannel(args));
    }
  }

  @VisibleForTesting
  final class WrrSubchannel extends ForwardingSubchannel {
    private final Subchannel delegate;
    private volatile long lastUpdated;
    private volatile long nonEmptySince;
    private volatile double weight;

    WrrSubchannel(Subchannel delegate) {
      this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    public void start(SubchannelStateListener listener) {
      delegate().start(new SubchannelStateListener() {
        @Override
        public void onSubchannelState(ConnectivityStateInfo newState) {
          if (newState.getState().equals(ConnectivityState.READY)) {
            nonEmptySince = infTime;
          }
          listener.onSubchannelState(newState);
        }
      });
    }

    private double getWeight() {
      if (config == null) {
        return 0;
      }
      long now = ticker.nanoTime();
      if (now - lastUpdated >= config.weightExpirationPeriodNanos) {
        nonEmptySince = infTime;
        return 0;
      } else if (now - nonEmptySince < config.blackoutPeriodNanos
          && config.blackoutPeriodNanos > 0) {
        return 0;
      } else {
        return weight;
      }
    }

    @Override
    protected Subchannel delegate() {
      return delegate;
    }

    final class OrcaReportListener implements OrcaPerRequestReportListener, OrcaOobReportListener {
      private final float errorUtilizationPenalty;

      OrcaReportListener(float errorUtilizationPenalty) {
        this.errorUtilizationPenalty = errorUtilizationPenalty;
      }

      @Override
      public void onLoadReport(MetricReport report) {
        double newWeight = 0;
        // Prefer application utilization and fallback to CPU utilization if unset.
        double utilization =
            report.getApplicationUtilization() > 0 ? report.getApplicationUtilization()
                : report.getCpuUtilization();
        if (utilization > 0 && report.getQps() > 0) {
          double penalty = 0;
          if (report.getEps() > 0 && errorUtilizationPenalty > 0) {
            penalty = report.getEps() / report.getQps() * errorUtilizationPenalty;
          }
          newWeight = report.getQps() / (utilization + penalty);
        }
        if (newWeight == 0) {
          return;
        }
        if (nonEmptySince == infTime) {
          nonEmptySince = ticker.nanoTime();
        }
        lastUpdated = ticker.nanoTime();
        weight = newWeight;
      }
    }
  }

  @VisibleForTesting
  final class WeightedRoundRobinPicker extends RoundRobinPicker {
    private final List<Subchannel> list;
    private final Map<Subchannel, OrcaPerRequestReportListener> subchannelToReportListenerMap =
        new HashMap<>();
    private final boolean enableOobLoadReport;
    private final float errorUtilizationPenalty;
    private volatile EdfScheduler edfScheduler;
    private volatile StaticStrideScheduler ssScheduler; // what does volatile mean?

    WeightedRoundRobinPicker(List<Subchannel> list, boolean enableOobLoadReport,
        float errorUtilizationPenalty) {
      checkNotNull(list, "list");
      Preconditions.checkArgument(!list.isEmpty(), "empty list");
      this.list = list;
      for (Subchannel subchannel : list) {
        this.subchannelToReportListenerMap.put(subchannel,
            ((WrrSubchannel) subchannel).new OrcaReportListener(errorUtilizationPenalty));
      }
      this.enableOobLoadReport = enableOobLoadReport;
      this.errorUtilizationPenalty = errorUtilizationPenalty;
      updateWeightSS();
      updateWeightEdf(); // handles style error
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      Subchannel subchannel = list.get(ssScheduler.pickChannel());
      edfScheduler.pick(); // handles style error
      if (!enableOobLoadReport) {
        return PickResult.withSubchannel(subchannel,
            OrcaPerRequestUtil.getInstance().newOrcaClientStreamTracerFactory(
                subchannelToReportListenerMap.getOrDefault(subchannel,
                    ((WrrSubchannel) subchannel).new OrcaReportListener(errorUtilizationPenalty))));
      } else {
        return PickResult.withSubchannel(subchannel);
      }
    }

    private void updateWeightEdf() {
      int weightedChannelCount = 0;
      double avgWeight = 0;
      for (Subchannel value : list) {
        double newWeight = ((WrrSubchannel) value).getWeight();
        if (newWeight > 0) {
          avgWeight += newWeight;
          weightedChannelCount++;
        }
      }
      EdfScheduler edfScheduler = new EdfScheduler(list.size(), random);
      if (weightedChannelCount >= 1) {
        avgWeight /= 1.0 * weightedChannelCount;
      } else {
        avgWeight = 1;
      }
      for (int i = 0; i < list.size(); i++) {
        WrrSubchannel subchannel = (WrrSubchannel) list.get(i);
        double newWeight = subchannel.getWeight();
        edfScheduler.add(i, newWeight > 0 ? newWeight : avgWeight);
      }
      this.edfScheduler = edfScheduler;
    }
    
    // verbose work done (requires optimization)
    private void updateWeightSS() {
      int weightedChannelCount = 0;
      double avgWeight = 0;
      for (Subchannel value : list) {
        double newWeight = ((WrrSubchannel) value).getWeight();
        if (newWeight > 0) {
          avgWeight += newWeight;
          weightedChannelCount++;
        }
      }

      if (weightedChannelCount >= 1) {
        avgWeight /= 1.0 * weightedChannelCount;
      } else {
        avgWeight = 1;
      }

      float[] newWeights = new float[list.size()];
      for (int i = 0; i < list.size(); i++) {
        WrrSubchannel subchannel = (WrrSubchannel) list.get(i);
        double newWeight = subchannel.getWeight();
        newWeights[i] = newWeight > 0 ? (float) newWeight : (float) avgWeight;
      }

      StaticStrideScheduler ssScheduler = new StaticStrideScheduler(newWeights);
      this.ssScheduler = ssScheduler;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(WeightedRoundRobinPicker.class)
          .add("enableOobLoadReport", enableOobLoadReport)
          .add("errorUtilizationPenalty", errorUtilizationPenalty)
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
      if (other == this) {
        return true;
      }
      // the lists cannot contain duplicate subchannels
      return enableOobLoadReport == other.enableOobLoadReport
          && Float.compare(errorUtilizationPenalty, other.errorUtilizationPenalty) == 0
          && list.size() == other.list.size() && new HashSet<>(list).containsAll(other.list);
    }
  }

  /**
   * The earliest deadline first implementation in which each object is
   * chosen deterministically and periodically with frequency proportional to its weight.
   *
   * <p>Specifically, each object added to chooser is given a deadline equal to the multiplicative
   * inverse of its weight. The place of each object in its deadline is tracked, and each call to
   * choose returns the object with the least remaining time in its deadline.
   * (Ties are broken by the order in which the children were added to the chooser.) The deadline
   * advances by the multiplicative inverse of the object's weight.
   * For example, if items A and B are added with weights 0.5 and 0.2, successive chooses return:
   *
   * <ul>
   *   <li>In the first call, the deadlines are A=2 (1/0.5) and B=5 (1/0.2), so A is returned.
   *   The deadline of A is updated to 4.
   *   <li>Next, the remaining deadlines are A=4 and B=5, so A is returned. The deadline of A (2) is
   *       updated to A=6.
   *   <li>Remaining deadlines are A=6 and B=5, so B is returned. The deadline of B is updated with
   *       with B=10.
   *   <li>Remaining deadlines are A=6 and B=10, so A is returned. The deadline of A is updated with
   *        A=8.
   *   <li>Remaining deadlines are A=8 and B=10, so A is returned. The deadline of A is updated with
   *       A=10.
   *   <li>Remaining deadlines are A=10 and B=10, so A is returned. The deadline of A is updated
   *      with A=12.
   *   <li>Remaining deadlines are A=12 and B=10, so B is returned. The deadline of B is updated
   *      with B=15.
   *   <li>etc.
   * </ul>
   *
   * <p>In short: the entry with the highest weight is preferred.
   *
   * <ul>
   *   <li>add() - O(lg n)
   *   <li>pick() - O(lg n)
   * </ul>
   *
   */
  @VisibleForTesting
  static final class EdfScheduler {
    private final PriorityQueue<ObjectState> prioQueue;

    /**
     * Weights below this value will be upped to this minimum weight.
     */
    private static final double MINIMUM_WEIGHT = 0.0001;

    private final Object lock = new Object();

    private final Random random;

    /**
     * Use the item's deadline as the order in the priority queue. If the deadlines are the same,
     * use the index. Index should be unique.
     */
    EdfScheduler(int initialCapacity, Random random) {
      this.prioQueue = new PriorityQueue<ObjectState>(initialCapacity, (o1, o2) -> {
        if (o1.deadline == o2.deadline) {
          return Integer.compare(o1.index, o2.index);
        } else {
          return Double.compare(o1.deadline, o2.deadline);
        }
      });
      this.random = random;
    }

    /**
     * Adds the item in the scheduler. This is not thread safe.
     *
     * @param index The field {@link ObjectState#index} to be added
     * @param weight positive weight for the added object
     */
    void add(int index, double weight) {
      checkArgument(weight > 0.0, "Weights need to be positive.");
      ObjectState state = new ObjectState(Math.max(weight, MINIMUM_WEIGHT), index);
      // Randomize the initial deadline.
      state.deadline = random.nextDouble() * (1 / state.weight);
      prioQueue.add(state);
    }

    /**
     * Picks the next WRR object.
     */
    int pick() {
      synchronized (lock) {
        ObjectState minObject = prioQueue.remove();
        minObject.deadline += 1.0 / minObject.weight;
        prioQueue.add(minObject);
        return minObject.index;
      }
    }
  }

  /*
   * Implementation of Static Stride Scheduler
   * Replaces EDFScheduler
   * 
   * go/static-stride-scheduler
   */
  @VisibleForTesting
  static final class StaticStrideScheduler {
    private final long[] scaledWeights;
    private final AtomicInteger sizeDivisor;
    private long sequence;
    private static final int K_MAX_WEIGHT = 65535; // uint16? can be uint8
    private static final long UINT32_MAX = 429967295L; // max value for uint32

    StaticStrideScheduler(float[] weights) {
      int numChannels = weights.length;
      int numZeroWeightChannels = 0;
      double sumWeight = 0;
      float maxWeight = 0;
      for (float weight : weights) {
        if (weight == 0) {
          numZeroWeightChannels++;
        }
        sumWeight += weight;
        maxWeight = Math.max(weight, maxWeight);
      }

      checkArgument(numChannels >= 1, "Couldn't build scheduler: requires at least one weight");

      double scalingFactor = K_MAX_WEIGHT / maxWeight;
      long meanWeight = numZeroWeightChannels == numChannels ? 1 :
          Math.round(scalingFactor * sumWeight / (numChannels - numZeroWeightChannels));

      // scales weights s.t. max(weights) == K_MAX_WEIGHT, meanWeight is scaled accordingly
      long[] scaledWeights = new long[numChannels];
      for (int i = 0; i < numChannels; i++) {
        if (weights[i] == 0) {
          scaledWeights[i] = meanWeight;
        } else {
          scaledWeights[i] = Math.round(weights[i] * scalingFactor);
        }
      }

      this.scaledWeights = scaledWeights;
      this.sizeDivisor = new AtomicInteger(numChannels); // why not call numChannels or numBackends
      this.sequence = (long) (Math.random() * UINT32_MAX); // why not initialize to [0,numChannels)
    }

    private long nextSequence() {
      long sequence = this.sequence;
      this.sequence = (this.sequence + 1) % UINT32_MAX; // check wraparound logic
      return sequence;
    }

    // selects index of our next backend server
    int pickChannel() {
      while (true) {
        long sequence = this.nextSequence();
        int backendIndex = (int) sequence % this.sizeDivisor.get();
        long generation = sequence / this.sizeDivisor.get();
        // is this really that much more efficient than a 2d array?
        long weight = this.scaledWeights[backendIndex];
        long kOffset = K_MAX_WEIGHT / 2;
        long mod = (weight * generation + backendIndex * kOffset) % K_MAX_WEIGHT;
        if (mod < K_MAX_WEIGHT - weight) { // review this math
          continue;
        }
        return backendIndex;
      }
    }
  }

  /** Holds the state of the object. */
  @VisibleForTesting
  static class ObjectState {
    private final double weight;
    private final int index;
    private volatile double deadline;

    ObjectState(double weight, int index) {
      this.weight = weight;
      this.index = index;
    }
  }

  static final class WeightedRoundRobinLoadBalancerConfig {
    final long blackoutPeriodNanos;
    final long weightExpirationPeriodNanos;
    final boolean enableOobLoadReport;
    final long oobReportingPeriodNanos;
    final long weightUpdatePeriodNanos;
    final float errorUtilizationPenalty;

    public static Builder newBuilder() {
      return new Builder();
    }

    private WeightedRoundRobinLoadBalancerConfig(long blackoutPeriodNanos,
                                                 long weightExpirationPeriodNanos,
                                                 boolean enableOobLoadReport,
                                                 long oobReportingPeriodNanos,
                                                 long weightUpdatePeriodNanos,
                                                 float errorUtilizationPenalty) {
      this.blackoutPeriodNanos = blackoutPeriodNanos;
      this.weightExpirationPeriodNanos = weightExpirationPeriodNanos;
      this.enableOobLoadReport = enableOobLoadReport;
      this.oobReportingPeriodNanos = oobReportingPeriodNanos;
      this.weightUpdatePeriodNanos = weightUpdatePeriodNanos;
      this.errorUtilizationPenalty = errorUtilizationPenalty;
    }

    static final class Builder {
      long blackoutPeriodNanos = 10_000_000_000L; // 10s
      long weightExpirationPeriodNanos = 180_000_000_000L; //3min
      boolean enableOobLoadReport = false;
      long oobReportingPeriodNanos = 10_000_000_000L; // 10s
      long weightUpdatePeriodNanos = 1_000_000_000L; // 1s
      float errorUtilizationPenalty = 1.0F;

      private Builder() {

      }

      Builder setBlackoutPeriodNanos(long blackoutPeriodNanos) {
        this.blackoutPeriodNanos = blackoutPeriodNanos;
        return this;
      }

      Builder setWeightExpirationPeriodNanos(long weightExpirationPeriodNanos) {
        this.weightExpirationPeriodNanos = weightExpirationPeriodNanos;
        return this;
      }

      Builder setEnableOobLoadReport(boolean enableOobLoadReport) {
        this.enableOobLoadReport = enableOobLoadReport;
        return this;
      }

      Builder setOobReportingPeriodNanos(long oobReportingPeriodNanos) {
        this.oobReportingPeriodNanos = oobReportingPeriodNanos;
        return this;
      }

      Builder setWeightUpdatePeriodNanos(long weightUpdatePeriodNanos) {
        this.weightUpdatePeriodNanos = weightUpdatePeriodNanos;
        return this;
      }

      Builder setErrorUtilizationPenalty(float errorUtilizationPenalty) {
        this.errorUtilizationPenalty = errorUtilizationPenalty;
        return this;
      }

      WeightedRoundRobinLoadBalancerConfig build() {
        return new WeightedRoundRobinLoadBalancerConfig(blackoutPeriodNanos,
                weightExpirationPeriodNanos, enableOobLoadReport, oobReportingPeriodNanos,
                weightUpdatePeriodNanos, errorUtilizationPenalty);
      }
    }
  }
}
