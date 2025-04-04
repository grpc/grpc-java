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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.Deadline.Ticker;
import io.grpc.DoubleHistogramMetricInstrument;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.LongCounterMetricInstrument;
import io.grpc.MetricInstrumentRegistry;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.services.MetricReport;
import io.grpc.util.ForwardingSubchannel;
import io.grpc.util.MultiChildLoadBalancer;
import io.grpc.xds.orca.OrcaOobUtil;
import io.grpc.xds.orca.OrcaOobUtil.OrcaOobReportListener;
import io.grpc.xds.orca.OrcaPerRequestUtil;
import io.grpc.xds.orca.OrcaPerRequestUtil.OrcaPerRequestReportListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A {@link LoadBalancer} that provides weighted-round-robin load-balancing over the
 * {@link EquivalentAddressGroup}s from the {@link NameResolver}. The subchannel weights are
 * determined by backend metrics using ORCA.
 * To use WRR, users may configure through channel serviceConfig. Example config:
 * <pre> {@code
 *       String wrrConfig = "{\"loadBalancingConfig\":" +
 *           "[{\"weighted_round_robin\":{\"enableOobLoadReport\":true, " +
 *           "\"blackoutPeriod\":\"10s\"," +
 *           "\"oobReportingPeriod\":\"10s\"," +
 *           "\"weightExpirationPeriod\":\"180s\"," +
 *           "\"errorUtilizationPenalty\":\"1.0\"," +
 *           "\"weightUpdatePeriod\":\"1s\"}}]}";
 *        serviceConfig = (Map<String, ?>) JsonParser.parse(wrrConfig);
 *        channel = ManagedChannelBuilder.forTarget("test:///lb.test.grpc.io")
 *            .defaultServiceConfig(serviceConfig)
 *            .build();
 *  }
 *  </pre>
 *  Users may also configure through xDS control plane via custom lb policy. But that is much more
 *  complex to set up. Example config:
 *  <pre>
 *  localityLbPolicies:
 *   - customPolicy:
 *       name: weighted_round_robin
 *       data: '{ "enableOobLoadReport": true }'
 *  </pre>
 *  See related documentation: https://cloud.google.com/service-mesh/legacy/load-balancing-apis/proxyless-configure-advanced-traffic-management#custom-lb-config
 */
final class WeightedRoundRobinLoadBalancer extends MultiChildLoadBalancer {

  private static final LongCounterMetricInstrument RR_FALLBACK_COUNTER;
  private static final LongCounterMetricInstrument ENDPOINT_WEIGHT_NOT_YET_USEABLE_COUNTER;
  private static final LongCounterMetricInstrument ENDPOINT_WEIGHT_STALE_COUNTER;
  private static final DoubleHistogramMetricInstrument ENDPOINT_WEIGHTS_HISTOGRAM;
  private static final Logger log = Logger.getLogger(
      WeightedRoundRobinLoadBalancer.class.getName());
  private WeightedRoundRobinLoadBalancerConfig config;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timeService;
  private ScheduledHandle weightUpdateTimer;
  private final Runnable updateWeightTask;
  private final AtomicInteger sequence;
  private final long infTime;
  private final Ticker ticker;
  private String locality = "";
  private String backendService = "";
  private SubchannelPicker currentPicker = new FixedResultPicker(PickResult.withNoResult());

  // The metric instruments are only registered once and shared by all instances of this LB.
  static {
    MetricInstrumentRegistry metricInstrumentRegistry
        = MetricInstrumentRegistry.getDefaultRegistry();
    RR_FALLBACK_COUNTER = metricInstrumentRegistry.registerLongCounter(
        "grpc.lb.wrr.rr_fallback",
        "EXPERIMENTAL. Number of scheduler updates in which there were not enough endpoints "
            + "with valid weight, which caused the WRR policy to fall back to RR behavior",
        "{update}",
        Lists.newArrayList("grpc.target"),
        Lists.newArrayList("grpc.lb.locality", "grpc.lb.backend_service"),
        false);
    ENDPOINT_WEIGHT_NOT_YET_USEABLE_COUNTER = metricInstrumentRegistry.registerLongCounter(
        "grpc.lb.wrr.endpoint_weight_not_yet_usable",
        "EXPERIMENTAL. Number of endpoints from each scheduler update that don't yet have usable "
            + "weight information",
        "{endpoint}",
        Lists.newArrayList("grpc.target"),
        Lists.newArrayList("grpc.lb.locality", "grpc.lb.backend_service"),
        false);
    ENDPOINT_WEIGHT_STALE_COUNTER = metricInstrumentRegistry.registerLongCounter(
        "grpc.lb.wrr.endpoint_weight_stale",
        "EXPERIMENTAL. Number of endpoints from each scheduler update whose latest weight is "
            + "older than the expiration period",
        "{endpoint}",
        Lists.newArrayList("grpc.target"),
        Lists.newArrayList("grpc.lb.locality", "grpc.lb.backend_service"),
        false);
    ENDPOINT_WEIGHTS_HISTOGRAM = metricInstrumentRegistry.registerDoubleHistogram(
        "grpc.lb.wrr.endpoint_weights",
        "EXPERIMENTAL. The histogram buckets will be endpoint weight ranges.",
        "{weight}",
        Lists.newArrayList(),
        Lists.newArrayList("grpc.target"),
        Lists.newArrayList("grpc.lb.locality", "grpc.lb.backend_service"),
        false);
  }

  public WeightedRoundRobinLoadBalancer(Helper helper, Ticker ticker) {
    this(helper, ticker, new Random());
  }

  @VisibleForTesting
  WeightedRoundRobinLoadBalancer(Helper helper, Ticker ticker, Random random) {
    super(OrcaOobUtil.newOrcaReportingHelper(helper));
    this.ticker = checkNotNull(ticker, "ticker");
    this.infTime = ticker.nanoTime() + Long.MAX_VALUE;
    this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    this.timeService = checkNotNull(helper.getScheduledExecutorService(), "timeService");
    this.updateWeightTask = new UpdateWeightTask();
    this.sequence = new AtomicInteger(random.nextInt());
    log.log(Level.FINE, "weighted_round_robin LB created");
  }

  @Override
  protected ChildLbState createChildLbState(Object key) {
    return new WeightedChildLbState(key, pickFirstLbProvider);
  }

  @Override
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    if (resolvedAddresses.getLoadBalancingPolicyConfig() == null) {
      Status unavailableStatus = Status.UNAVAILABLE.withDescription(
              "NameResolver returned no WeightedRoundRobinLoadBalancerConfig. addrs="
                      + resolvedAddresses.getAddresses()
                      + ", attrs=" + resolvedAddresses.getAttributes());
      handleNameResolutionError(unavailableStatus);
      return unavailableStatus;
    }
    String locality = resolvedAddresses.getAttributes().get(WeightedTargetLoadBalancer.CHILD_NAME);
    if (locality != null) {
      this.locality = locality;
    } else {
      this.locality = "";
    }
    String backendService
        = resolvedAddresses.getAttributes().get(NameResolver.ATTR_BACKEND_SERVICE);
    if (backendService != null) {
      this.backendService = backendService;
    } else {
      this.backendService = "";
    }
    config =
            (WeightedRoundRobinLoadBalancerConfig) resolvedAddresses.getLoadBalancingPolicyConfig();

    if (weightUpdateTimer != null && weightUpdateTimer.isPending()) {
      weightUpdateTimer.cancel();
    }
    updateWeightTask.run();

    Status status = super.acceptResolvedAddresses(resolvedAddresses);

    createAndApplyOrcaListeners();

    return status;
  }

  /**
   * Updates picker with the list of active subchannels (state == READY).
   */
  @Override
  protected void updateOverallBalancingState() {
    List<ChildLbState> activeList = getReadyChildren();
    if (activeList.isEmpty()) {
      // No READY subchannels

      // MultiChildLB will request connection immediately on subchannel IDLE.
      boolean isConnecting = false;
      for (ChildLbState childLbState : getChildLbStates()) {
        ConnectivityState state = childLbState.getCurrentState();
        if (state == ConnectivityState.CONNECTING || state == ConnectivityState.IDLE) {
          isConnecting = true;
          break;
        }
      }

      if (isConnecting) {
        updateBalancingState(
            ConnectivityState.CONNECTING, new FixedResultPicker(PickResult.withNoResult()));
      } else {
        updateBalancingState(
            ConnectivityState.TRANSIENT_FAILURE, createReadyPicker(getChildLbStates()));
      }
    } else {
      updateBalancingState(ConnectivityState.READY, createReadyPicker(activeList));
    }
  }

  private SubchannelPicker createReadyPicker(Collection<ChildLbState> activeList) {
    WeightedRoundRobinPicker picker = new WeightedRoundRobinPicker(ImmutableList.copyOf(activeList),
        config.enableOobLoadReport, config.errorUtilizationPenalty, sequence);
    updateWeight(picker);
    return picker;
  }

  private void updateWeight(WeightedRoundRobinPicker picker) {
    Helper helper = getHelper();
    float[] newWeights = new float[picker.children.size()];
    AtomicInteger staleEndpoints = new AtomicInteger();
    AtomicInteger notYetUsableEndpoints = new AtomicInteger();
    for (int i = 0; i < picker.children.size(); i++) {
      double newWeight = ((WeightedChildLbState) picker.children.get(i)).getWeight(staleEndpoints,
          notYetUsableEndpoints);
      helper.getMetricRecorder()
          .recordDoubleHistogram(ENDPOINT_WEIGHTS_HISTOGRAM, newWeight,
              ImmutableList.of(helper.getChannelTarget()),
              ImmutableList.of(locality, backendService));
      newWeights[i] = newWeight > 0 ? (float) newWeight : 0.0f;
    }

    if (staleEndpoints.get() > 0) {
      helper.getMetricRecorder()
          .addLongCounter(ENDPOINT_WEIGHT_STALE_COUNTER, staleEndpoints.get(),
              ImmutableList.of(helper.getChannelTarget()),
              ImmutableList.of(locality, backendService));
    }
    if (notYetUsableEndpoints.get() > 0) {
      helper.getMetricRecorder()
          .addLongCounter(ENDPOINT_WEIGHT_NOT_YET_USEABLE_COUNTER, notYetUsableEndpoints.get(),
              ImmutableList.of(helper.getChannelTarget()),
              ImmutableList.of(locality, backendService));
    }
    boolean weightsEffective = picker.updateWeight(newWeights);
    if (!weightsEffective) {
      helper.getMetricRecorder()
          .addLongCounter(RR_FALLBACK_COUNTER, 1, ImmutableList.of(helper.getChannelTarget()),
              ImmutableList.of(locality, backendService));
    }
  }

  private void updateBalancingState(ConnectivityState state, SubchannelPicker picker) {
    if (state != currentConnectivityState || !picker.equals(currentPicker)) {
      getHelper().updateBalancingState(state, picker);
      currentConnectivityState = state;
      currentPicker = picker;
    }
  }

  @VisibleForTesting
  final class WeightedChildLbState extends ChildLbState {

    private final Set<WrrSubchannel> subchannels = new HashSet<>();
    private volatile long lastUpdated;
    private volatile long nonEmptySince;
    private volatile double weight = 0;

    private OrcaReportListener orcaReportListener;

    public WeightedChildLbState(Object key, LoadBalancerProvider policyProvider) {
      super(key, policyProvider);
    }

    @Override
    protected ChildLbStateHelper createChildHelper() {
      return new WrrChildLbStateHelper();
    }

    private double getWeight(AtomicInteger staleEndpoints, AtomicInteger notYetUsableEndpoints) {
      if (config == null) {
        return 0;
      }
      long now = ticker.nanoTime();
      if (now - lastUpdated >= config.weightExpirationPeriodNanos) {
        nonEmptySince = infTime;
        staleEndpoints.incrementAndGet();
        return 0;
      } else if (now - nonEmptySince < config.blackoutPeriodNanos
          && config.blackoutPeriodNanos > 0) {
        notYetUsableEndpoints.incrementAndGet();
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

    final class WrrChildLbStateHelper extends ChildLbStateHelper {
      @Override
      public Subchannel createSubchannel(CreateSubchannelArgs args) {
        return new WrrSubchannel(super.createSubchannel(args), WeightedChildLbState.this);
      }

      @Override
      public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
        super.updateBalancingState(newState, newPicker);
        if (!resolvingAddresses && newState == ConnectivityState.IDLE) {
          getLb().requestConnection();
        }
      }
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
        updateWeight((WeightedRoundRobinPicker) currentPicker);
      }
      weightUpdateTimer = syncContext.schedule(this, config.weightUpdatePeriodNanos,
          TimeUnit.NANOSECONDS, timeService);
    }
  }

  private void createAndApplyOrcaListeners() {
    for (ChildLbState child : getChildLbStates()) {
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
  static final class WeightedRoundRobinPicker extends SubchannelPicker {
    // Parallel lists (column-based storage instead of normal row-based storage of List<Struct>).
    // The ith element of children corresponds to the ith element of pickers, listeners, and even
    // updateWeight(float[]).
    private final List<ChildLbState> children; // May only be accessed from sync context
    private final List<SubchannelPicker> pickers;
    private final List<OrcaPerRequestReportListener> reportListeners;
    private final boolean enableOobLoadReport;
    private final float errorUtilizationPenalty;
    private final AtomicInteger sequence;
    private final int hashCode;
    private volatile StaticStrideScheduler scheduler;

    WeightedRoundRobinPicker(List<ChildLbState> children, boolean enableOobLoadReport,
        float errorUtilizationPenalty, AtomicInteger sequence) {
      checkNotNull(children, "children");
      Preconditions.checkArgument(!children.isEmpty(), "empty child list");
      this.children = children;
      List<SubchannelPicker> pickers = new ArrayList<>(children.size());
      List<OrcaPerRequestReportListener> reportListeners = new ArrayList<>(children.size());
      for (ChildLbState child : children) {
        WeightedChildLbState wChild = (WeightedChildLbState) child;
        pickers.add(wChild.getCurrentPicker());
        reportListeners.add(wChild.getOrCreateOrcaListener(errorUtilizationPenalty));
      }
      this.pickers = pickers;
      this.reportListeners = reportListeners;
      this.enableOobLoadReport = enableOobLoadReport;
      this.errorUtilizationPenalty = errorUtilizationPenalty;
      this.sequence = checkNotNull(sequence, "sequence");

      // For equality we treat pickers as a set; use hash code as defined by Set
      int sum = 0;
      for (SubchannelPicker picker : pickers) {
        sum += picker.hashCode();
      }
      this.hashCode = sum
          ^ Boolean.hashCode(enableOobLoadReport)
          ^ Float.hashCode(errorUtilizationPenalty);
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      int pick = scheduler.pick();
      PickResult pickResult = pickers.get(pick).pickSubchannel(args);
      Subchannel subchannel = pickResult.getSubchannel();
      if (subchannel == null) {
        return pickResult;
      }
      if (!enableOobLoadReport) {
        return PickResult.withSubchannel(subchannel,
            OrcaPerRequestUtil.getInstance().newOrcaClientStreamTracerFactory(
                reportListeners.get(pick)));
      } else {
        return PickResult.withSubchannel(subchannel);
      }
    }

    /** Returns {@code true} if weights are different than round_robin. */
    private boolean updateWeight(float[] newWeights) {
      this.scheduler = new StaticStrideScheduler(newWeights, sequence);
      return !this.scheduler.usesRoundRobin();
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(WeightedRoundRobinPicker.class)
          .add("enableOobLoadReport", enableOobLoadReport)
          .add("errorUtilizationPenalty", errorUtilizationPenalty)
          .add("pickers", pickers)
          .toString();
    }

    @VisibleForTesting
    List<ChildLbState> getChildren() {
      return children;
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof WeightedRoundRobinPicker)) {
        return false;
      }
      WeightedRoundRobinPicker other = (WeightedRoundRobinPicker) o;
      if (other == this) {
        return true;
      }
      // the lists cannot contain duplicate subchannels
      return hashCode == other.hashCode
          && sequence == other.sequence
          && enableOobLoadReport == other.enableOobLoadReport
          && Float.compare(errorUtilizationPenalty, other.errorUtilizationPenalty) == 0
          && pickers.size() == other.pickers.size()
          && new HashSet<>(pickers).containsAll(other.pickers);
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
    private final boolean usesRoundRobin;
    private static final int K_MAX_WEIGHT = 0xFFFF;

    // Assuming the mean of all known weights is M, StaticStrideScheduler will clamp
    // weights bigger than M*kMaxRatio and weights smaller than M*kMinRatio.
    //
    // This is done as a performance optimization by limiting the number of rounds for picks
    // for edge cases where channels have large differences in subchannel weights.
    // In this case, without these clips, it would potentially require the scheduler to
    // frequently traverse through the entire subchannel list within the pick method.
    //
    // The current values of 10 and 0.1 were chosen without any experimenting. It should
    // decrease the amount of sequences that the scheduler must traverse through in order
    // to pick a high weight subchannel in such corner cases.
    // But, it also makes WeightedRoundRobin to send slightly more requests to
    // potentially very bad tasks (that would have near-zero weights) than zero.
    // This is not necessarily a downside, though. Perhaps this is not a problem at
    // all, and we can increase this value if needed to save CPU cycles.
    private static final double K_MAX_RATIO = 10;
    private static final double K_MIN_RATIO = 0.1;

    StaticStrideScheduler(float[] weights, AtomicInteger sequence) {
      checkArgument(weights.length >= 1, "Couldn't build scheduler: requires at least one weight");
      int numChannels = weights.length;
      int numWeightedChannels = 0;
      double sumWeight = 0;
      double unscaledMeanWeight;
      float unscaledMaxWeight = 0;
      for (float weight : weights) {
        if (weight > 0) {
          sumWeight += weight;
          unscaledMaxWeight = Math.max(weight, unscaledMaxWeight);
          numWeightedChannels++;
        }
      }

      // Adjust max value s.t. ratio does not exceed K_MAX_RATIO. This should
      // ensure that we on average do at most K_MAX_RATIO rounds for picks.
      if (numWeightedChannels > 0) {
        unscaledMeanWeight = sumWeight / numWeightedChannels;
        unscaledMaxWeight = Math.min(unscaledMaxWeight, (float) (K_MAX_RATIO * unscaledMeanWeight));
      } else {
        // Fall back to round robin if all values are non-positives. Note that
        // numWeightedChannels == 1 also behaves like RR because the weights are all the same, but
        // the weights aren't 1, so it doesn't go through this path.
        unscaledMeanWeight = 1;
        unscaledMaxWeight = 1;
      }
      // We need at least two weights for WRR to be distinguishable from round_robin.
      usesRoundRobin = numWeightedChannels < 2;

      // Scales weights s.t. max(weights) == K_MAX_WEIGHT, meanWeight is scaled accordingly.
      // Note that, since we cap the weights to stay within K_MAX_RATIO, meanWeight might not
      // match the actual mean of the values that end up in the scheduler.
      double scalingFactor = K_MAX_WEIGHT / unscaledMaxWeight;
      // We compute weightLowerBound and clamp it to 1 from below so that in the
      // worst case, we represent tiny weights as 1.
      int weightLowerBound = (int) Math.ceil(scalingFactor * unscaledMeanWeight * K_MIN_RATIO);
      short[] scaledWeights = new short[numChannels];
      for (int i = 0; i < numChannels; i++) {
        if (weights[i] <= 0) {
          scaledWeights[i] = (short) Math.round(scalingFactor * unscaledMeanWeight);
        } else {
          int weight = (int) Math.round(scalingFactor * Math.min(weights[i], unscaledMaxWeight));
          scaledWeights[i] = (short) Math.max(weight, weightLowerBound);
        }
      }

      this.scaledWeights = scaledWeights;
      this.sequence = sequence;
    }

    // Without properly weighted channels, we do plain vanilla round_robin.
    boolean usesRoundRobin() {
      return usesRoundRobin;
    }

    /**
     * Returns the next sequence number and atomically increases sequence with wraparound.
     */
    private long nextSequence() {
      return Integer.toUnsignedLong(sequence.getAndIncrement());
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
