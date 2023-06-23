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
        ((WeightedRoundRobinPicker) currentPicker).updateWeight();
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
    private volatile StaticStrideScheduler ssScheduler;

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
      updateWeight();
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      Subchannel subchannel = list.get(ssScheduler.pick());
      if (!enableOobLoadReport) {
        return PickResult.withSubchannel(subchannel,
                OrcaPerRequestUtil.getInstance().newOrcaClientStreamTracerFactory(
                subchannelToReportListenerMap.getOrDefault(subchannel,
                ((WrrSubchannel) subchannel).new OrcaReportListener(errorUtilizationPenalty))));
      } else {
        return PickResult.withSubchannel(subchannel);
      }
    }

    private void updateWeight() {
      float[] newWeights = new float[list.size()];
      for (int i = 0; i < list.size(); i++) {
        WrrSubchannel subchannel = (WrrSubchannel) list.get(i);
        double newWeight = subchannel.getWeight();
        newWeights[i] = newWeight > 0 ? (float) newWeight : 0.0f;
      }

      StaticStrideScheduler ssScheduler = new StaticStrideScheduler(newWeights, random);
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

  /*
   * Implementation of Static Stride Scheduler, replaces EDFScheduler.
   * <p>
   * The Static Stride Scheduler works by iterating through the list of subchannel weights
   * and using modular arithmetic to evenly distribute picks and skips, favoring entries with the
   * highest weight. It generates a practically equivalent sequence of picks as the EDFScheduler.
   * Albeit needing more bandwidth, the Static Stride Scheduler is more performant than the
   * EDFScheduler, as it removes the need for a priority queue (and thus mutex locks).
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
    private final int[] scaledWeights;
    private final int sizeDivisor;
    private final AtomicInteger sequence;
    private static final int K_MAX_WEIGHT = 0xFFFF;

    StaticStrideScheduler(float[] weights, Random random) {
      checkArgument(weights.length >= 1, "Couldn't build scheduler: requires at least one weight");
      int numChannels = weights.length;
      int numWeightedChannels = 0;
      double sumWeight = 0;
      float maxWeight = 0;
      int meanWeight = 0;
      for (float weight : weights) {
        if (weight > 0.0001) {
          sumWeight += weight;
          maxWeight = Math.max(weight, maxWeight);
          numWeightedChannels++;
        }
      }

      double scalingFactor = K_MAX_WEIGHT / maxWeight;
      if (numWeightedChannels > 0) {
        meanWeight = (int) Math.round(scalingFactor * sumWeight / numWeightedChannels);
      } else {
        meanWeight = 1;
      }

      // scales weights s.t. max(weights) == K_MAX_WEIGHT, meanWeight is scaled accordingly
      int[] scaledWeights = new int[numChannels];
      for (int i = 0; i < numChannels; i++) {
        if (weights[i] < 0.0001) {
          scaledWeights[i] = meanWeight;
        } else {
          scaledWeights[i] = (int) Math.round(weights[i] * scalingFactor);
        }
      }

      this.scaledWeights = scaledWeights;
      this.sizeDivisor = numChannels;
      this.sequence = new AtomicInteger(random.nextInt());

    }

    /** Returns the next sequence number and atomically increases sequence with wraparound. */
    private long nextSequence() {
      return Integer.toUnsignedLong(sequence.getAndIncrement());
    }

    public long getSequence() {
      return Integer.toUnsignedLong(sequence.get());
    }

    /*
     * Selects index of next backend server.
     * <p>
     * A 2D array is compactly represented where the row represents the generation and the column
     * represents the backend index. The value of an element is a boolean value which indicates
     * whether or not a backend should be picked now. An atomically incremented counter keeps track
     * of our backend and generation through modular arithmetic within the pick() method.
     * An offset is also included to minimize consecutive non-picks of a backend.
     */
    int pick() {
      while (true) {
        long sequence = this.nextSequence();
        int backendIndex = (int) (sequence % this.sizeDivisor);
        long generation = sequence / this.sizeDivisor;
        long weight = this.scaledWeights[backendIndex];
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
