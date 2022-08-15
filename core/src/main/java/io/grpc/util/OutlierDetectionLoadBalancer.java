/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.util;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ForwardingMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import io.grpc.Attributes;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.internal.TimeProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * Wraps a child {@code LoadBalancer} while monitoring for outlier backends and removing them from
 * the use of the child LB.
 *
 * <p>This implements the outlier detection gRFC:
 * https://github.com/grpc/proposal/blob/master/A50-xds-outlier-detection.md
 */
public class OutlierDetectionLoadBalancer extends LoadBalancer {

  @VisibleForTesting
  final AddressTrackerMap trackerMap;

  private final SynchronizationContext syncContext;
  private final Helper childHelper;
  private final GracefulSwitchLoadBalancer switchLb;
  private TimeProvider timeProvider;
  private final ScheduledExecutorService timeService;
  private ScheduledHandle detectionTimerHandle;
  private Long detectionTimerStartNanos;

  private static final Attributes.Key<AddressTracker> EAG_INFO_ATTR_KEY = Attributes.Key.create(
      "eagInfoKey");

  /**
   * Creates a new instance of {@link OutlierDetectionLoadBalancer}.
   */
  public OutlierDetectionLoadBalancer(Helper helper, TimeProvider timeProvider) {
    childHelper = new ChildHelper(checkNotNull(helper, "helper"));
    switchLb = new GracefulSwitchLoadBalancer(childHelper);
    trackerMap = new AddressTrackerMap();
    this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    this.timeService = checkNotNull(helper.getScheduledExecutorService(), "timeService");
    this.timeProvider = timeProvider;
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    OutlierDetectionLoadBalancerConfig config
        = (OutlierDetectionLoadBalancerConfig) resolvedAddresses.getLoadBalancingPolicyConfig();

    // The map should only retain entries for addresses in this latest update.
    trackerMap.keySet().retainAll(resolvedAddresses.getAddresses());

    // Add any new ones.
    trackerMap.putNewTrackers(config, resolvedAddresses.getAddresses());

    switchLb.switchTo(config.childPolicy.getProvider());

    // If outlier detection is actually configured, start a timer that will periodically try to
    // detect outliers.
    if (config.outlierDetectionEnabled()) {
      Long initialDelayNanos;

      if (detectionTimerStartNanos == null) {
        // On the first go we use the configured interval.
        initialDelayNanos = TimeUnit.SECONDS.toNanos(config.intervalSecs);
      } else {
        // If a timer has started earlier we cancel it and use the difference between the start
        // time and now as the interval.
        initialDelayNanos = Math.max(0L,
            TimeUnit.SECONDS.toNanos(config.intervalSecs) - (timeProvider.currentTimeNanos()
                - detectionTimerStartNanos));
      }

      // If a timer has been previously created we need to cancel it and reset all the call counters
      // for a fresh start.
      if (detectionTimerHandle != null) {
        detectionTimerHandle.cancel();
        trackerMap.resetCallCounters();
      }

      detectionTimerHandle = syncContext.scheduleWithFixedDelay(new DetectionTimer(config),
          initialDelayNanos, SECONDS.toNanos(config.intervalSecs), NANOSECONDS, timeService);
    } else if (detectionTimerHandle != null) {
      // Outlier detection is not configured, but we have a lingering timer. Let's cancel it and
      // uneject any addresses we may have ejected.
      detectionTimerHandle.cancel();
      detectionTimerStartNanos = null;
      trackerMap.cancelTracking();
    }

    switchLb.handleResolvedAddresses(resolvedAddresses);
  }

  @Override
  public void handleNameResolutionError(Status error) {
    switchLb.handleNameResolutionError(error);
  }

  @Override
  public void shutdown() {
    switchLb.shutdown();
  }

  /**
   * This timer will be invoked periodically, according to configuration, and it will look for any
   * outlier subchannels.
   */
  class DetectionTimer implements Runnable {

    OutlierDetectionLoadBalancerConfig config;

    DetectionTimer(OutlierDetectionLoadBalancerConfig config) {
      this.config = config;
    }

    @Override
    public void run() {
      detectionTimerStartNanos = timeProvider.currentTimeNanos();

      trackerMap.swapCounters();

      OutlierEjectionAlgorithm.forConfig(config)
          .ejectOutliers(trackerMap, detectionTimerStartNanos);

      trackerMap.maybeUnejectOutliers(detectionTimerStartNanos);
    }
  }

  /**
   * This child helper wraps the provided helper so that is can hand out wrapped {@link
   * OutlierDetectionSubchannel}s and manage the address info map.
   */
  class ChildHelper extends ForwardingLoadBalancerHelper {

    private Helper delegate;

    ChildHelper(Helper delegate) {
      this.delegate = delegate;
    }

    @Override
    protected Helper delegate() {
      return delegate;
    }

    @Override
    public Subchannel createSubchannel(CreateSubchannelArgs args) {
      // Subchannels are wrapped so that we can monitor call results and to trigger failures when
      // we decide to eject the subchannel.
      OutlierDetectionSubchannel subchannel = new OutlierDetectionSubchannel(
          delegate.createSubchannel(args));

      // If the subchannel is associated with a single address that is also already in the map
      // the subchannel will be added to the map and be included in outlier detection.
      List<EquivalentAddressGroup> allAddresses = subchannel.getAllAddresses();
      if (allAddresses.size() == 1 && trackerMap.containsKey(allAddresses.get(0))) {
        AddressTracker eagInfo = trackerMap.get(allAddresses.get(0));
        subchannel.setEquivalentAddressGroupInfo(eagInfo);
        eagInfo.addSubchannel(subchannel);

        // If this address has already been ejected, we need to immediately eject this Subchannel.
        if (eagInfo.ejectionTimeNanos != null) {
          subchannel.eject();
        }
      }

      return subchannel;
    }

    @Override
    public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
      delegate.updateBalancingState(newState, new OutlierDetectionPicker(newPicker));
    }
  }

  class OutlierDetectionSubchannel extends ForwardingSubchannel {

    private final Subchannel delegate;
    private AddressTracker eagInfo;
    private boolean ejected;
    private ConnectivityStateInfo lastSubchannelState;
    private OutlierDetectionSubchannelStateListener subchannelStateListener;

    OutlierDetectionSubchannel(Subchannel delegate) {
      this.delegate = delegate;
    }

    @Override
    public void start(SubchannelStateListener listener) {
      subchannelStateListener = new OutlierDetectionSubchannelStateListener(listener);
      super.start(subchannelStateListener);
    }

    @Override
    public Attributes getAttributes() {
      return delegate.getAttributes().toBuilder().set(EAG_INFO_ATTR_KEY, eagInfo).build();
    }

    @Override
    public void updateAddresses(List<EquivalentAddressGroup> addresses) {
      // Outlier detection only supports subchannels with a single address, but the list of
      // addresses associated with a subchannel can change at any time, so we need to react to
      // changes in the address list plurality.

      // No change in address plurality, we replace the single one with a new one.
      if (getAllAddresses().size() == 1 && addresses.size() == 1) {
        // Remove the current subchannel from the old address it is associated with in the map.
        if (trackerMap.containsKey(getAddresses())) {
          trackerMap.get(getAddresses()).removeSubchannel(this);
        }

        // If the map has an entry for the new address, we associate this subchannel with it.
        EquivalentAddressGroup newAddress = Iterables.getOnlyElement(addresses);
        if (trackerMap.containsKey(newAddress)) {
          AddressTracker tracker = trackerMap.get(newAddress);
          tracker.addSubchannel(this);
        }
      } else if (getAllAddresses().size() == 1 && addresses.size() > 1) {
        // We go from a single address to having multiple, making this subchannel uneligible for
        // outlier detection. Remove it from all trackers and reset the call counters of all the
        // associated trackers.
        // Remove the current subchannel from the old address it is associated with in the map.
        if (trackerMap.containsKey(getAddresses())) {
          AddressTracker tracker = trackerMap.get(getAddresses());
          tracker.removeSubchannel(this);
          tracker.resetCallCounters();
        }
      } else if (getAllAddresses().size() > 1 && addresses.size() == 1) {
        // We go from, previously uneligble, multiple address mode to a single address. If the map
        // has an entry for the new address, we associate this subchannel with it.
        EquivalentAddressGroup eag = Iterables.getOnlyElement(addresses);
        if (trackerMap.containsKey(eag)) {
          AddressTracker tracker = trackerMap.get(eag);
          tracker.addSubchannel(this);
        }
      }

      // We could also have multiple addresses and get an update for multiple new ones. This is
      // a no-op as we will just continue to ignore multiple address subchannels.

      delegate.updateAddresses(addresses);
    }

    /**
     * If the {@link Subchannel} is considered for outlier detection the associated {@link
     * AddressTracker} should be set.
     */
    void setEquivalentAddressGroupInfo(AddressTracker eagInfo) {
      this.eagInfo = eagInfo;
    }

    void eject() {
      ejected = true;
      subchannelStateListener.onSubchannelState(
          ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE));
    }

    void uneject() {
      ejected = false;
      if (lastSubchannelState != null) {
        subchannelStateListener.onSubchannelState(lastSubchannelState);
      }
    }

    boolean isEjected() {
      return ejected;
    }

    @Override
    protected Subchannel delegate() {
      return delegate;
    }

    /**
     * Wraps the actual listener so that state changes from the actual one can be intercepted.
     */
    class OutlierDetectionSubchannelStateListener implements SubchannelStateListener {

      private final SubchannelStateListener delegate;

      OutlierDetectionSubchannelStateListener(SubchannelStateListener delegate) {
        this.delegate = delegate;
      }

      @Override
      public void onSubchannelState(ConnectivityStateInfo newState) {
        if (!ejected) {
          lastSubchannelState = newState;
          delegate.onSubchannelState(newState);
        }
      }
    }
  }


  /**
   * This picker delegates the actual picking logic to a wrapped delegate, but associates a {@link
   * ClientStreamTracer} with each pick to track the results of each subchannel stream.
   */
  class OutlierDetectionPicker extends SubchannelPicker {

    private final SubchannelPicker delegate;

    OutlierDetectionPicker(SubchannelPicker delegate) {
      this.delegate = delegate;
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      PickResult pickResult = delegate.pickSubchannel(args);

      Subchannel subchannel = pickResult.getSubchannel();
      if (subchannel != null) {
        return PickResult.withSubchannel(subchannel,
            new ResultCountingClientStreamTracerFactory(
                subchannel.getAttributes().get(EAG_INFO_ATTR_KEY)));
      }

      return pickResult;
    }

    /**
     * Builds instances of {@link ResultCountingClientStreamTracer}.
     */
    class ResultCountingClientStreamTracerFactory extends ClientStreamTracer.Factory {

      private final AddressTracker tracker;

      ResultCountingClientStreamTracerFactory(AddressTracker tracker) {
        this.tracker = tracker;
      }

      @Override
      public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
        return new ResultCountingClientStreamTracer(tracker);
      }
    }

    /**
     * Counts the results (successful/unsuccessful) of a particular {@link
     * OutlierDetectionSubchannel}s streams and increments the counter in the associated {@link
     * AddressTracker}.
     */
    class ResultCountingClientStreamTracer extends ClientStreamTracer {

      AddressTracker tracker;

      public ResultCountingClientStreamTracer(AddressTracker tracker) {
        this.tracker = tracker;
      }

      @Override
      public void streamClosed(Status status) {
        tracker.incrementCallCount(status.isOk());
      }
    }
  }

  /**
   * Tracks additional information about a set of equivalent addresses needed for outlier
   * detection.
   */
  static class AddressTracker {

    private final OutlierDetectionLoadBalancerConfig config;
    private CallCounter activeCallCounter = new CallCounter();
    private CallCounter inactiveCallCounter = new CallCounter();
    private Long ejectionTimeNanos;
    private int ejectionTimeMultiplier;
    private final Set<OutlierDetectionSubchannel> subchannels = new HashSet<>();

    AddressTracker(OutlierDetectionLoadBalancerConfig config) {
      this.config = config;
    }

    boolean addSubchannel(OutlierDetectionSubchannel subchannel) {
      // Make sure that the subchannel is in the same ejection state as the new tracker it is
      // associated with.
      if (subchannelsEjected() && !subchannel.isEjected()) {
        subchannel.eject();
      } else if (!subchannelsEjected() && subchannel.isEjected()) {
        subchannel.uneject();
      }
      return subchannels.add(subchannel);
    }

    boolean removeSubchannel(OutlierDetectionSubchannel subchannel) {
      return subchannels.remove(subchannel);
    }

    boolean containsSubchannel(OutlierDetectionSubchannel subchannel) {
      return subchannels.contains(subchannel);
    }

    @VisibleForTesting
    Set<OutlierDetectionSubchannel> getSubchannels() {
      return ImmutableSet.copyOf(subchannels);
    }

    void incrementCallCount(boolean success) {
      // If neither algorithm is configured, no point in incrementing counters.
      if (config.successRateEjection == null && config.failurePercentageEjection == null) {
        return;
      }

      if (success) {
        activeCallCounter.successCount.getAndIncrement();
      } else {
        activeCallCounter.failureCount.getAndIncrement();
      }
    }

    @VisibleForTesting
    long activeVolume() {
      return activeCallCounter.successCount.get() + activeCallCounter.failureCount.get();
    }

    long inactiveVolume() {
      return inactiveCallCounter.successCount.get() + inactiveCallCounter.failureCount.get();
    }

    double successRate() {
      return ((double) inactiveCallCounter.successCount.get()) / inactiveVolume();
    }

    double failureRate() {
      return ((double)inactiveCallCounter.failureCount.get()) / inactiveVolume();
    }

    void resetCallCounters() {
      activeCallCounter.reset();
      inactiveCallCounter.reset();
    }

    void decrementEjectionTimeMultiplier() {
      // The multiplier should not go negative.
      ejectionTimeMultiplier = ejectionTimeMultiplier == 0 ? 0 : ejectionTimeMultiplier - 1;
    }

    void resetEjectionTimeMultiplier() {
      ejectionTimeMultiplier = 0;
    }

    void swapCounters() {
      inactiveCallCounter.reset();
      CallCounter tempCounter = activeCallCounter;
      activeCallCounter = inactiveCallCounter;
      inactiveCallCounter = tempCounter;
    }

    void ejectSubchannels(long ejectionTimeNanos) {
      this.ejectionTimeNanos = ejectionTimeNanos;
      ejectionTimeMultiplier++;
      for (OutlierDetectionSubchannel subchannel : subchannels) {
        subchannel.eject();
      }
    }

    /**
     * Uneject a currently ejected address.
     */
    void unejectSubchannels() {
      checkState(ejectionTimeNanos != null, "not currently ejected");
      ejectionTimeNanos = null;
      for (OutlierDetectionSubchannel subchannel : subchannels) {
        subchannel.uneject();
      }
    }

    boolean subchannelsEjected() {
      return ejectionTimeNanos != null;
    }

    public boolean maxEjectionTimeElapsed(long currentTimeNanos) {
      // The instant in time beyond which the address should no longer be ejected. Also making sure
      // we honor any maximum ejection time setting.
      long maxEjectionTimeNanos = ejectionTimeNanos + Math.min(
          SECONDS.toNanos(config.baseEjectionTimeSecs) * ejectionTimeMultiplier,
          Math.max(SECONDS.toNanos(config.baseEjectionTimeSecs),
              SECONDS.toNanos(config.maxEjectionTimeSecs)));

      return currentTimeNanos > maxEjectionTimeNanos;
    }

    private static class CallCounter {
      AtomicLong successCount = new AtomicLong();
      AtomicLong failureCount = new AtomicLong();

      void reset() {
        successCount.set(0);
        failureCount.set(0);
      }
    }
  }

  /**
   * Maintains a mapping from addresses to their trackers.
   */
  static class AddressTrackerMap extends ForwardingMap<EquivalentAddressGroup, AddressTracker> {
    private final Map<EquivalentAddressGroup, AddressTracker> trackerMap;

    AddressTrackerMap() {
      trackerMap = new HashMap<>();
    }

    @Override
    protected Map<EquivalentAddressGroup, AddressTracker> delegate() {
      return trackerMap;
    }

    /** Adds a new tracker for the addresses that don't already have one. */
    void putNewTrackers(OutlierDetectionLoadBalancerConfig config,
        Collection<EquivalentAddressGroup> addresses) {
      for (EquivalentAddressGroup address : addresses) {
        if (!trackerMap.containsKey(address)) {
          trackerMap.put(address, new AddressTracker(config));
        }
      }
    }

    /** Resets the call counters for all the trackers in the map. */
    void resetCallCounters() {
      for (AddressTracker tracker : trackerMap.values()) {
        tracker.resetCallCounters();
      }
    }

    /**
     * When OD gets disabled we need to uneject any subchannels that may have been ejected and
     * to reset the ejection time multiplier.
     */
    void cancelTracking() {
      for (AddressTracker tracker : trackerMap.values()) {
        if (tracker.subchannelsEjected()) {
          tracker.unejectSubchannels();
        }
        tracker.resetEjectionTimeMultiplier();
      }
    }

    /** Swaps the active and inactive counters for each tracker. */
    void swapCounters() {
      for (AddressTracker tracker : trackerMap.values()) {
        tracker.swapCounters();
      }
    }

    /**
     * At the end of a timer run we need to decrement the ejection time multiplier for trackers
     * that don't have ejected subchannels and uneject ones that have spent the maximum ejection
     * time allowed.
     */
    void maybeUnejectOutliers(Long detectionTimerStartNanos) {
      for (AddressTracker tracker : trackerMap.values()) {
        if (!tracker.subchannelsEjected()) {
          tracker.decrementEjectionTimeMultiplier();
        }

        if (tracker.subchannelsEjected() && tracker.maxEjectionTimeElapsed(
            detectionTimerStartNanos)) {
          tracker.unejectSubchannels();
        }
      }
    }

    /** Returns only the trackers that have the minimum configured volume to be considered. */
    List<AddressTracker> trackersWithVolume(OutlierDetectionLoadBalancerConfig config) {
      List<AddressTracker> trackersWithVolume = new ArrayList<>();
      for (AddressTracker tracker : trackerMap.values()) {
        if (tracker.inactiveVolume() >= config.successRateEjection.requestVolume) {
          trackersWithVolume.add(tracker);
        }
      }
      return trackersWithVolume;
    }

    /**
     * How many percent of the addresses would have their subchannels ejected if we proceeded
     * with the next ejection.
     */
    double nextEjectionPercentage() {
      int totalAddresses = 0;
      int ejectedAddresses = 0;
      for (AddressTracker tracker : trackerMap.values()) {
        totalAddresses++;
        if (tracker.subchannelsEjected()) {
          ejectedAddresses++;
        }
      }
      return ((double)(ejectedAddresses + 1) / totalAddresses) * 100;
    }
  }


  /**
   * Implementations provide different ways of ejecting outlier addresses..
   */
  interface OutlierEjectionAlgorithm {

    /**
     * Is the given {@link EquivalentAddressGroup} an outlier based on the past call results stored
     * in {@link AddressTracker}.
     */
    void ejectOutliers(AddressTrackerMap trackerMap, long ejectionTimeMillis);

    @Nullable
    static OutlierEjectionAlgorithm forConfig(OutlierDetectionLoadBalancerConfig config) {
      if (config.successRateEjection != null) {
        return new SuccessRateOutlierEjectionAlgorithm(config);
      } else if (config.failurePercentageEjection != null) {
        return new FailurePercentageOutlierEjectionAlgorithm(config);
      } else {
        return null;
      }
    }
  }

  /**
   * This algorithm ejects addresses that don't maintain a required rate of successful calls. The
   * required rate is not fixed, but is based on the mean and standard deviation of the success
   * rates of all of the addresses.
   */
  static class SuccessRateOutlierEjectionAlgorithm implements OutlierEjectionAlgorithm {

    private final OutlierDetectionLoadBalancerConfig config;

    SuccessRateOutlierEjectionAlgorithm(OutlierDetectionLoadBalancerConfig config) {
      checkArgument(config.successRateEjection != null, "success rate ejection config is null");
      this.config = config;
    }

    @Override
    public void ejectOutliers(AddressTrackerMap trackerMap, long ejectionTimeNanos) {

      // Only consider addresses that have the minimum request volume specified in the config.
      List<AddressTracker> trackersWithVolume = trackerMap.trackersWithVolume(config);
      // If we don't have enough addresses with significant volume then there's nothing to do.
      if (trackersWithVolume.size() < config.successRateEjection.minimumHosts
          || trackersWithVolume.size() == 0) {
        return;
      }

      // Calculate mean and standard deviation of the fractions of successful calls.
      List<Double> successRates = new ArrayList<>();
      for (AddressTracker tracker : trackersWithVolume) {
        successRates.add(tracker.successRate());
      }
      double mean = mean(successRates);
      double stdev = standardDeviation(successRates, mean);

      for (AddressTracker tracker : trackersWithVolume) {
        // If an ejection now would take us past the max configured ejection percentagem stop here.
        if (trackerMap.nextEjectionPercentage() > config.maxEjectionPercent) {
          return;
        }

        // If success rate is below the threshold, eject the address.
        double requiredSuccessRate =
            mean - stdev * (config.successRateEjection.stdevFactor / 1000f);
        if (tracker.successRate() < requiredSuccessRate) {
          // Only eject some addresses based on the enforcement percentage.
          if (new Random().nextInt(100) < config.successRateEjection.enforcementPercentage) {
            tracker.ejectSubchannels(ejectionTimeNanos);
          }
        }
      }
    }

    /** Calculates the mean of the given values. */
    static double mean(Collection<Double> values) {
      double totalValue = 0;
      for (double value : values) {
        totalValue += value;
      }

      return totalValue / values.size();
    }

    /** Calculates the standard deviation for the given values and their mean. */
    static double standardDeviation(Collection<Double> values, double mean) {
      double squaredDifferenceSum = 0;
      for (double value : values) {
        double difference = value - mean;
        squaredDifferenceSum += difference * difference;
      }
      double variance = squaredDifferenceSum / values.size();

      return Math.sqrt(variance);
    }
  }

  static class FailurePercentageOutlierEjectionAlgorithm implements OutlierEjectionAlgorithm {

    private final OutlierDetectionLoadBalancerConfig config;

    FailurePercentageOutlierEjectionAlgorithm(OutlierDetectionLoadBalancerConfig config) {
      this.config = config;
    }

    @Override
    public void ejectOutliers(AddressTrackerMap trackerMap, long ejectionTimeMillis) {

      // If we don't have the minimum amount of addresses the config calls for, then return.
      if (trackerMap.size() < config.failurePercentageEjection.minimumHosts) {
        return;
      }

      // If this address does not have enough volume to be considered, skip to the next one.
      for (AddressTracker tracker : trackerMap.values()) {
        // If an ejection now would take us past the max configured ejection percentagem stop here.
        if (trackerMap.nextEjectionPercentage() > config.maxEjectionPercent) {
          return;
        }

        if (tracker.inactiveVolume() < config.failurePercentageEjection.requestVolume) {
          continue;
        }

        // If the failure rate is above the threshold, we should eject...
        double maxFailureRate = ((double)config.failurePercentageEjection.threshold) / 100;
        if (tracker.failureRate() > maxFailureRate) {
          // ...but only enforce this based on the enforcement percentage.
          if (new Random().nextInt(100) < config.failurePercentageEjection.enforcementPercentage) {
            tracker.ejectSubchannels(ejectionTimeMillis);
          }
        }
      }
    }
  }


  /**
   * The configuration for {@link OutlierDetectionLoadBalancer}.
   */
  public static final class OutlierDetectionLoadBalancerConfig {

    final Long intervalSecs;
    final Long baseEjectionTimeSecs;
    final Long maxEjectionTimeSecs;
    final Integer maxEjectionPercent;
    final SuccessRateEjection successRateEjection;
    final FailurePercentageEjection failurePercentageEjection;
    final PolicySelection childPolicy;

    private OutlierDetectionLoadBalancerConfig(Long intervalSecs,
        Long baseEjectionTimeSecs,
        Long maxEjectionTimeSecs, Integer maxEjectionPercent,
        SuccessRateEjection successRateEjection,
        FailurePercentageEjection failurePercentageEjection,
        PolicySelection childPolicy) {
      this.intervalSecs = intervalSecs;
      this.baseEjectionTimeSecs = baseEjectionTimeSecs;
      this.maxEjectionTimeSecs = maxEjectionTimeSecs;
      this.maxEjectionPercent = maxEjectionPercent;
      this.successRateEjection = successRateEjection;
      this.failurePercentageEjection = failurePercentageEjection;
      this.childPolicy = childPolicy;
    }

    /** Builds a new {@link OutlierDetectionLoadBalancerConfig}. */
    public static class Builder {
      Long intervalSecs = 10L;
      Long baseEjectionTimeSecs = 30L;
      Long maxEjectionTimeSecs = 30L;
      Integer maxEjectionPercent = 10;
      SuccessRateEjection successRateEjection;
      FailurePercentageEjection failurePercentageEjection;
      PolicySelection childPolicy;

      /** The interval between outlier detection sweeps. */
      public Builder setIntervalSecs(Long intervalSecs) {
        checkArgument(intervalSecs != null);
        this.intervalSecs = intervalSecs;
        return this;
      }

      /** The base time an address is ejected for. */
      public Builder setBaseEjectionTimeSecs(Long baseEjectionTimeSecs) {
        checkArgument(baseEjectionTimeSecs != null);
        this.baseEjectionTimeSecs = baseEjectionTimeSecs;
        return this;
      }

      /** The longest time an address can be ejected. */
      public Builder setMaxEjectionTimeSecs(Long maxEjectionTimeSecs) {
        checkArgument(maxEjectionTimeSecs != null);
        this.maxEjectionTimeSecs = maxEjectionTimeSecs;
        return this;
      }

      /** The algorithm agnostic maximum percentage of addresses that can be ejected. */
      public Builder setMaxEjectionPercent(Integer maxEjectionPercent) {
        checkArgument(maxEjectionPercent != null);
        this.maxEjectionPercent = maxEjectionPercent;
        return this;
      }

      /** Set to enable success rate eejction. */
      public Builder setSuccessRateEjection(
          SuccessRateEjection successRateEjection) {
        this.successRateEjection = successRateEjection;
        return this;
      }

      /** Set to enable failure percentage ejection. */
      public Builder setFailurePercentageEjection(
          FailurePercentageEjection failurePercentageEjection) {
        this.failurePercentageEjection = failurePercentageEjection;
        return this;
      }

      /** Sets the child policy the {@link OutlierDetectionLoadBalancer} delegates to. */
      public Builder setChildPolicy(PolicySelection childPolicy) {
        checkState(childPolicy != null);
        this.childPolicy = childPolicy;
        return this;
      }

      /** Builds a new instance of {@link OutlierDetectionLoadBalancerConfig}. */
      public OutlierDetectionLoadBalancerConfig build() {
        checkState(childPolicy != null);
        return new OutlierDetectionLoadBalancerConfig(intervalSecs, baseEjectionTimeSecs,
            maxEjectionTimeSecs, maxEjectionPercent, successRateEjection, failurePercentageEjection,
            childPolicy);
      }
    }

    /** The configuration for success rate ejection. */
    public static class SuccessRateEjection {

      final Integer stdevFactor;
      final Integer enforcementPercentage;
      final Integer minimumHosts;
      final Integer requestVolume;

      SuccessRateEjection(Integer stdevFactor, Integer enforcementPercentage, Integer minimumHosts,
          Integer requestVolume) {
        this.stdevFactor = stdevFactor;
        this.enforcementPercentage = enforcementPercentage;
        this.minimumHosts = minimumHosts;
        this.requestVolume = requestVolume;
      }

      /** Builds new instances of {@link SuccessRateEjection}. */
      public static final class Builder {

        Integer stdevFactor = 1900;
        Integer enforcementPercentage = 100;
        Integer minimumHosts = 5;
        Integer requestVolume = 100;

        /** The product of this and the standard deviation of success rates determine the ejection
         * threshold.
         */
        public Builder setStdevFactor(Integer stdevFactor) {
          checkArgument(stdevFactor != null);
          this.stdevFactor = stdevFactor;
          return this;
        }

        /** Only eject this percentage of outliers. */
        public Builder setEnforcementPercentage(Integer enforcementPercentage) {
          checkArgument(enforcementPercentage != null);
          this.enforcementPercentage = enforcementPercentage;
          return this;
        }

        /** The minimum amount of hosts needed for success rate ejection. */
        public Builder setMinimumHosts(Integer minimumHosts) {
          checkArgument(minimumHosts != null);
          this.minimumHosts = minimumHosts;
          return this;
        }

        /** The minimum address request volume to be considered for success rate ejection. */
        public Builder setRequestVolume(Integer requestVolume) {
          checkArgument(requestVolume != null);
          this.requestVolume = requestVolume;
          return this;
        }

        /** Builds a new instance of {@link SuccessRateEjection}. */
        public SuccessRateEjection build() {
          return new SuccessRateEjection(stdevFactor, enforcementPercentage, minimumHosts,
              requestVolume);
        }
      }
    }

    /** The configuration for failure percentage ejection. */
    public static class FailurePercentageEjection {
      final Integer threshold;
      final Integer enforcementPercentage;
      final Integer minimumHosts;
      final Integer requestVolume;

      FailurePercentageEjection(Integer threshold, Integer enforcementPercentage,
          Integer minimumHosts, Integer requestVolume) {
        this.threshold = threshold;
        this.enforcementPercentage = enforcementPercentage;
        this.minimumHosts = minimumHosts;
        this.requestVolume = requestVolume;
      }

      /** For building new {@link FailurePercentageEjection} instances. */
      public static class Builder {
        Integer threshold = 85;
        Integer enforcementPercentage = 100;
        Integer minimumHosts = 5;
        Integer requestVolume = 50;

        /** The failure percentage that will result in an address being considered an outlier. */
        public Builder setThreshold(Integer threshold) {
          checkArgument(threshold != null);
          this.threshold = threshold;
          return this;
        }

        /** Only eject this percentage of outliers. */
        public Builder setEnforcementPercentage(Integer enforcementPercentage) {
          checkArgument(enforcementPercentage != null);
          this.enforcementPercentage = enforcementPercentage;
          return this;
        }

        /** The minimum amount of host for failure percentage ejection to be enabled. */
        public Builder setMinimumHosts(Integer minimumHosts) {
          checkArgument(minimumHosts != null);
          this.minimumHosts = minimumHosts;
          return this;
        }

        /**
         * The request volume required for an address to be considered for failure percentage
         * ejection.
         */
        public Builder setRequestVolume(Integer requestVolume) {
          checkArgument(requestVolume != null);
          this.requestVolume = requestVolume;
          return this;
        }

        /** Builds a new instance of {@link FailurePercentageEjection}. */
        public FailurePercentageEjection build() {
          return new FailurePercentageEjection(threshold, enforcementPercentage, minimumHosts,
              requestVolume);
        }
      }
    }

    /**
     * Determine if outlier detection is at all enabled in this config.
     */
    boolean outlierDetectionEnabled() {
      // One of the two supported algorithms needs to be configured.
      return successRateEjection != null || failurePercentageEjection != null;
    }
  }
}
