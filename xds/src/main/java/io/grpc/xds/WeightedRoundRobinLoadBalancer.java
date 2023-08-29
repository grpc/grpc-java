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
import static com.google.common.base.Preconditions.checkElementIndex;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.Deadline.Ticker;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ExperimentalApi;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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
  protected Map<Object, ChildLbState> getChildLbMap(ResolvedAddresses resolvedAddresses) {
    Map<Object, ChildLbState> childLbMap = new HashMap<>();
    List<EquivalentAddressGroup> addresses = resolvedAddresses.getAddresses();
    Object policyConfig = resolvedAddresses.getLoadBalancingPolicyConfig();
    for (EquivalentAddressGroup eag : addresses) {
      EquivalentAddressGroup key = stripAttrs(eag);
      ChildLbState childLbState = new WeightedChildLbState(key, pickFirstLbProvider, policyConfig,
          getInitialPicker());
      childLbMap.put(key, childLbState);
    }
    return childLbMap;
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
  public RoundRobinPicker createReadyPicker(Collection<ChildLbState> activeList) {
    return new WeightedRoundRobinPicker(ImmutableList.copyOf(activeList),
        config.enableOobLoadReport, config.errorUtilizationPenalty);
  }

  @Override
  protected ChildLbState getChild(EquivalentAddressGroup eag) {
    return super.getChild(eag);
  }

  @VisibleForTesting
  final class WeightedChildLbState extends ChildLbState {

    private final Set<WrrSubchannel> subchannels = new HashSet<>();
    private volatile long lastUpdated;
    private volatile long nonEmptySince;
    private volatile double weight = 0;

    private OrcaReportListener orcaReportListener;

    public WeightedChildLbState(Object key, LoadBalancerProvider policyProvider, Object childConfig,
        SubchannelPicker initialPicker) {
      super(key, policyProvider, childConfig, initialPicker);
    }

    @VisibleForTesting
    EquivalentAddressGroup getEag() {
      return stripAttrs((EquivalentAddressGroup) getKey());
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

    public void addSubchannel(WrrSubchannel wrrSubchannel) {
      subchannels.add(wrrSubchannel);
    }

    public OrcaReportListener getOrCreateOrcaListener(float errorUtilizationPenalty) {
      if (orcaReportListener != null
          && orcaReportListener.errorUtilizationPenalty == errorUtilizationPenalty) {
        return orcaReportListener;
      }
      orcaReportListener = new OrcaReportListener(errorUtilizationPenalty);
      return orcaReportListener;
    }
    public void removeSubchannel(WrrSubchannel wrrSubchannel) {
      subchannels.remove(wrrSubchannel);
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

  private final class UpdateWeightTask implements Runnable {
    @Override
    public void run() {
      if (currentPicker != null && currentPicker instanceof WeightedRoundRobinPicker) {
        ((WeightedRoundRobinPicker) currentPicker).updateWeight();
      }
      weightUpdateTimer = syncContext.schedule(this, config.weightUpdatePeriodNanos,
          TimeUnit.NANOSECONDS, timeService);
    }
  }

  private void afterAcceptAddresses() {
    for (ChildLbState child : getChildren()) {
      WeightedChildLbState wChild = (WeightedChildLbState) child;
      for (WrrSubchannel weightedSubchannel : wChild.subchannels) {
        if (config.enableOobLoadReport) {
          OrcaOobUtil.setListener(weightedSubchannel,
              wChild.getOrCreateOrcaListener(config.errorUtilizationPenalty),
              OrcaOobUtil.OrcaReportingConfig.newBuilder()
                  .setReportInterval(config.oobReportingPeriodNanos, TimeUnit.NANOSECONDS)
                  .build());
        } else {
          OrcaOobUtil.setListener(weightedSubchannel, null, null);
        }
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
      checkElementIndex(0, args.getAddresses().size(), "Empty address group");
      WeightedChildLbState childLbState =
          (WeightedChildLbState) wrr.getChild(args.getAddresses().get(0));
      return wrr.new WrrSubchannel(delegate().createSubchannel(args), childLbState);
    }
  }

  @VisibleForTesting
  final class WrrSubchannel extends ForwardingSubchannel {
    private final Subchannel delegate;
    private final WeightedChildLbState owner;

    WrrSubchannel(Subchannel delegate, WeightedChildLbState owner) {
      this.delegate = checkNotNull(delegate, "delegate");
      this.owner = checkNotNull(owner, "owner");
    }

    @Override
    public void start(SubchannelStateListener listener) {
      owner.addSubchannel(this);
      delegate().start(new SubchannelStateListener() {
        @Override
        public void onSubchannelState(ConnectivityStateInfo newState) {
          if (newState.getState().equals(ConnectivityState.READY)) {
            owner.nonEmptySince = infTime;
          }
          listener.onSubchannelState(newState);
        }
      });
    }

    @Override
    protected Subchannel delegate() {
      return delegate;
    }

    @Override
    public void shutdown() {
      super.shutdown();
      owner.removeSubchannel(this);
    }
  }

  @VisibleForTesting
  final class WeightedRoundRobinPicker extends RoundRobinPicker {
    private final List<ChildLbState> children;
    private final Map<Subchannel, OrcaPerRequestReportListener> subchannelToReportListenerMap =
        new HashMap<>();
    private final boolean enableOobLoadReport;
    private final float errorUtilizationPenalty;
    private volatile StaticStrideScheduler scheduler;

    WeightedRoundRobinPicker(List<ChildLbState> children, boolean enableOobLoadReport,
        float errorUtilizationPenalty) {
      checkNotNull(children, "children");
      Preconditions.checkArgument(!children.isEmpty(), "empty child list");
      this.children = children;
      for (ChildLbState child : children) {
        WeightedChildLbState wChild = (WeightedChildLbState) child;
        for (WrrSubchannel subchannel : wChild.subchannels) {
          this.subchannelToReportListenerMap
              .put(subchannel, wChild.getOrCreateOrcaListener(errorUtilizationPenalty));
        }
      }
      this.enableOobLoadReport = enableOobLoadReport;
      this.errorUtilizationPenalty = errorUtilizationPenalty;
      updateWeight();
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      ChildLbState childLbState = children.get(scheduler.pick());
      WeightedChildLbState wChild = (WeightedChildLbState) childLbState;
      PickResult pickResult = childLbState.getCurrentPicker().pickSubchannel(args);
      Subchannel subchannel = pickResult.getSubchannel();
      if (!enableOobLoadReport) {
        return PickResult.withSubchannel(subchannel,
            OrcaPerRequestUtil.getInstance().newOrcaClientStreamTracerFactory(
                subchannelToReportListenerMap.getOrDefault(subchannel,
                    wChild.getOrCreateOrcaListener(errorUtilizationPenalty))));
      } else {
        return PickResult.withSubchannel(subchannel);
      }
    }

    private void updateWeight() {
      float[] newWeights = new float[children.size()];
      for (int i = 0; i < children.size(); i++) {
        double newWeight = ((WeightedChildLbState)children.get(i)).getWeight();
        newWeights[i] = newWeight > 0 ? (float) newWeight : 0.0f;
      }

      scheduler = (scheduler != null)
          ? new StaticStrideScheduler(newWeights, random, scheduler.sequence.get())
          : new StaticStrideScheduler(newWeights, random);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(WeightedRoundRobinPicker.class)
          .add("enableOobLoadReport", enableOobLoadReport)
          .add("errorUtilizationPenalty", errorUtilizationPenalty)
          .add("list", children).toString();
    }

    @VisibleForTesting
    List<ChildLbState> getChildren() {
      return children;
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
          && children.size() == other.children.size() && new HashSet<>(
          children).containsAll(other.children);
    }
  }

  /*
   * The Static Stride Scheduler is an implementation of an earliest deadline first (EDF) scheduler
   * in which each object's deadline is the multiplicative inverse of the object's weight.
   * <p>
   * The way in which this is implemented is through a static stride scheduler. 
   * The Static Stride Scheduler works by iterating through the list of subchannel weights
   * and using modular arithmetic to proportionally distribute picks, favoring entries 
   * with higher weights. It is based on the observation that the intended sequence generated 
   * from an EDF scheduler is a periodic one that can be achieved through modular arithmetic. 
   * The Static Stride Scheduler is more performant than other implementations of the EDF
   * Scheduler, as it removes the need for a priority queue (and thus mutex locks).
   * <p>
   * go/static-stride-scheduler
   * <p>
   *
   * <ul>
   *  <li>nextSequence() - O(1)
   *  <li>pick() - O(n)
   */
  @VisibleForTesting
  static final class StaticStrideScheduler {
    private final short[] scaledWeights;
    private final AtomicInteger sequence;
    private static final int K_MAX_WEIGHT = 0xFFFF;

    StaticStrideScheduler(float[] weights, Random random) {
      this(weights, random, -1);
    }

    StaticStrideScheduler(float[] weights, Random random, int initialSequence) {
      checkArgument(weights.length >= 1, "Couldn't build scheduler: requires at least one weight");
      int numChannels = weights.length;
      int numWeightedChannels = 0;
      double sumWeight = 0;
      float maxWeight = 0;
      short meanWeight = 0;
      for (float weight : weights) {
        if (weight > 0) {
          sumWeight += weight;
          maxWeight = Math.max(weight, maxWeight);
          numWeightedChannels++;
        }
      }

      double scalingFactor = K_MAX_WEIGHT / maxWeight;
      if (numWeightedChannels > 0) {
        meanWeight = (short) Math.round(scalingFactor * sumWeight / numWeightedChannels);
      } else {
        meanWeight = (short) Math.round(scalingFactor);
      }

      // scales weights s.t. max(weights) == K_MAX_WEIGHT, meanWeight is scaled accordingly
      short[] scaledWeights = new short[numChannels];
      for (int i = 0; i < numChannels; i++) {
        if (weights[i] <= 0) {
          scaledWeights[i] = meanWeight;
        } else {
          scaledWeights[i] = (short) Math.round(weights[i] * scalingFactor);
        }
      }

      this.scaledWeights = scaledWeights;
      int seq = (initialSequence >= 0) ? initialSequence : random.nextInt();
      this.sequence = new AtomicInteger(seq);
    }

    /** Returns the next sequence number and atomically increases sequence with wraparound. */
    private long nextSequence() {
      return Integer.toUnsignedLong(sequence.getAndIncrement());
    }

    @VisibleForTesting
    long getSequence() {
      return Integer.toUnsignedLong(sequence.get());
    }

    /*
     * Selects index of next backend server.
     * <p>
     * A 2D array is compactly represented as a function of W(backend), where the row
     * represents the generation and the column represents the backend index:
     * X(backend,generation) | generation ∈ [0,kMaxWeight).
     * Each element in the conceptual array is a boolean indicating whether the backend at
     * this index should be picked now. If false, the counter is incremented again,
     * and the new element is checked. An atomically incremented counter keeps track of our
     * backend and generation through modular arithmetic within the pick() method.
     * <p>
     * Modular arithmetic allows us to evenly distribute picks and skips between
     * generations based on W(backend).
     * X(backend,generation) = (W(backend) * generation) % kMaxWeight >= kMaxWeight - W(backend)
     * If we have the same three backends with weights:
     * W(backend) = {2,3,6} scaled to max(W(backend)) = 6, then X(backend,generation) is:
     * <p>
     * B0    B1    B2
     * T     T     T
     * F     F     T
     * F     T     T
     * T     F     T
     * F     T     T
     * F     F     T
     * The sequence of picked backend indices is given by
     * walking across and down: {0,1,2,2,1,2,0,2,1,2,2}.
     * <p>
     * To reduce the variance and spread the wasted work among different picks,
     * an offset that varies per backend index is also included to the calculation.
     */
    int pick() {
      while (true) {
        long sequence = this.nextSequence();
        int backendIndex = (int) (sequence % scaledWeights.length);
        long generation = sequence / scaledWeights.length;
        int weight = Short.toUnsignedInt(scaledWeights[backendIndex]);
        long offset = (long) K_MAX_WEIGHT / 2 * backendIndex;
        if ((weight * generation + offset) % K_MAX_WEIGHT < K_MAX_WEIGHT - weight) {
          continue;
        }
        return backendIndex;
      }
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

      @SuppressWarnings("UnusedReturnValue")
      Builder setBlackoutPeriodNanos(long blackoutPeriodNanos) {
        this.blackoutPeriodNanos = blackoutPeriodNanos;
        return this;
      }

      @SuppressWarnings("UnusedReturnValue")
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
