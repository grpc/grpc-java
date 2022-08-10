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

import com.google.common.collect.Iterables;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * Wraps a child {@code LoadBalancer} while monitoring for outliers backends and removing them from
 * use by the child LB.
 *
 * <p>This implements the outlier detection gRFC:
 * https://github.com/grpc/proposal/blob/master/A50-xds-outlier-detection.md
 */
public class OutlierDetectionLoadBalancer extends LoadBalancer {

  private final SynchronizationContext syncContext;
  private final Helper childHelper;
  private final GracefulSwitchLoadBalancer switchLb;
  private final Map<EquivalentAddressGroup, EquivalentAddressGroupTracker> eagTrackerMap;
  private TimeProvider timeProvider;
  private final ScheduledExecutorService timeService;
  private ScheduledHandle detectionTimerHandle;
  private Long detectionTimerStartNanos;

  /**
   * Creates a new instance of {@link OutlierDetectionLoadBalancer}.
   */
  public OutlierDetectionLoadBalancer(Helper helper, TimeProvider timeProvider) {
    childHelper = new ChildHelper(checkNotNull(helper, "helper"));
    switchLb = new GracefulSwitchLoadBalancer(childHelper);
    eagTrackerMap = new HashMap<>();
    this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    this.timeService = checkNotNull(helper.getScheduledExecutorService(), "timeService");
    this.timeProvider = timeProvider;
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    OutlierDetectionLoadBalancerConfig config
        = (OutlierDetectionLoadBalancerConfig) resolvedAddresses.getLoadBalancingPolicyConfig();

    // The map should only retain entries for addresses in this latest update.
    eagTrackerMap.keySet().retainAll(resolvedAddresses.getAddresses());

    // Add any new ones.
    for (EquivalentAddressGroup eag : resolvedAddresses.getAddresses()) {
      if (!eagTrackerMap.containsKey(eag)) {
        eagTrackerMap.put(eag, new EquivalentAddressGroupTracker(config));
      }
    }

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

      if (detectionTimerHandle != null) {
        detectionTimerHandle.cancel();
        // When starting the timer for the first time we reset all call counters for a clean start.
        for (EquivalentAddressGroupTracker tracker : eagTrackerMap.values()) {
          tracker.clearCallCounters();
        }
      }
      detectionTimerHandle = syncContext.scheduleWithFixedDelay(new DetectionTimer(config),
          initialDelayNanos, SECONDS.toNanos(config.intervalSecs), NANOSECONDS, timeService);
    } else if (detectionTimerHandle != null) {
      // Outlier detection is not configured, but we have a lingering timer. Let's cancel it and
      // uneject any addresses we may have ejected.
      detectionTimerHandle.cancel();
      detectionTimerStartNanos = null;
      for (EquivalentAddressGroupTracker tracker : eagTrackerMap.values()) {
        if (tracker.isEjected()) {
          tracker.uneject();
        }
        tracker.resetEjectionTimeMultiplier();
      }
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

      for (EquivalentAddressGroupTracker tracker : eagTrackerMap.values()) {
        tracker.swapCounters();
      }

      OutlierEjectionAlgorithm.forConfig(config)
          .ejectOutliers(eagTrackerMap, detectionTimerStartNanos);

      for (EquivalentAddressGroupTracker tracker : eagTrackerMap.values()) {
        if (!tracker.isEjected()) {
          tracker.decrementEjectionTimeMultiplier();
        }

        if (tracker.isEjected() && tracker.maxEjectionTimeElapsed(detectionTimerStartNanos)) {
          tracker.uneject();
        }
      }
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
      if (allAddresses.size() == 1 && eagTrackerMap.containsKey(allAddresses.get(0))) {
        EquivalentAddressGroupTracker eagInfo = eagTrackerMap.get(allAddresses.get(0));
        subchannel.setEquivalentAddressGroupInfo(eagInfo);
        eagInfo.addSubchannel(subchannel);

        // If this address has already been ejected, we need to immediately eject this Subchannel.
        if (eagInfo.ejectionTimeNanos != null) {
          subchannel.eject();
        }
      }

      return subchannel;
    }
  }

  class OutlierDetectionSubchannel extends ForwardingSubchannel {

    private final Subchannel delegate;
    private EquivalentAddressGroupTracker eagInfo;
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
    public void updateAddresses(List<EquivalentAddressGroup> addresses) {
      // Outlier detection only supports subchannels with a single address, but the list of
      // addresses associated with a subchannel can change at any time, so we need to react to
      // changes in the address list plurality.

      // No change in address plurality, we replace the single one with a new one.
      if (getAllAddresses().size() == 1 && addresses.size() == 1) {
        // Remove the current subchannel from the old address it is associated with in the map.
        if (eagTrackerMap.containsKey(getAddresses())) {
          eagTrackerMap.get(getAddresses()).removeSubchannel(this);
        }

        // If the map has an entry for the new address, we associate this subchannel with it.
        EquivalentAddressGroup newAddress = Iterables.getOnlyElement(addresses);
        if (eagTrackerMap.containsKey(newAddress)) {
          EquivalentAddressGroupTracker tracker = eagTrackerMap.get(newAddress);
          tracker.addSubchannel(this);

          // Make sure that the subchannel is in the same ejection state as the new tracker it is
          // associated with.
          if (tracker.isEjected() && !ejected) {
            eject();
          } else if (!tracker.isEjected() && ejected) {
            uneject();
          }
        }
      } else if (getAllAddresses().size() == 1 && addresses.size() > 1) {
        // We go from a single address to having multiple, making this subchannel uneligible for
        // outlier detection. Remove it from all trackers and reset the call counters of all the
        // associated trackers.
        // Remove the current subchannel from the old address it is associated with in the map.
        if (eagTrackerMap.containsKey(getAddresses())) {
          EquivalentAddressGroupTracker tracker = eagTrackerMap.get(getAddresses());
          tracker.removeSubchannel(this);
          tracker.clearCallCounters();
        }
      } else if (getAllAddresses().size() > 1 && addresses.size() == 1) {
        // If the map has an entry for the new address, we associate this subchannel with it.
        EquivalentAddressGroup eag = Iterables.getOnlyElement(addresses);
        if (eagTrackerMap.containsKey(eag)) {
          EquivalentAddressGroupTracker tracker = eagTrackerMap.get(eag);
          tracker.addSubchannel(this);

          // If the new address is already in the ejected state, we should also eject this
          // subchannel.
          if (tracker.isEjected()) {
            eject();
          }
        }
      }

      // We could also have multiple addresses and get an update for multiple new ones. This is
      // a no-op as we will just continue to ignore multiple address subchannels.

      super.updateAddresses(addresses);
    }

    /**
     * If the {@link Subchannel} is considered for outlier detection the associated {@link
     * EquivalentAddressGroupTracker} should be set.
     */
    void setEquivalentAddressGroupInfo(EquivalentAddressGroupTracker eagInfo) {
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

      // Because we wrap the helper used by the delegate LB we are assured that the subchannel
      // picked here will be an instance of our OutlierDetectionSubchannel.
      OutlierDetectionSubchannel subchannel
          = (OutlierDetectionSubchannel) pickResult.getSubchannel();

      // The subchannel wrapper has served its purpose, we can pass on the wrapped delegate on
      // in case another layer of wrapping assumes a particular subchannel sub-type.
      return PickResult.withSubchannel(subchannel.delegate(),
          new ResultCountingClientStreamTracerFactory(subchannel));
    }

    /**
     * Builds instances of {@link ResultCountingClientStreamTracer}.
     */
    class ResultCountingClientStreamTracerFactory extends ClientStreamTracer.Factory {

      private final OutlierDetectionSubchannel subchannel;

      ResultCountingClientStreamTracerFactory(OutlierDetectionSubchannel subchannel) {
        this.subchannel = subchannel;
      }

      @Override
      public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
        return new ResultCountingClientStreamTracer(subchannel);
      }
    }

    /**
     * Counts the results (successful/unsuccessful) of a particular {@link
     * OutlierDetectionSubchannel}s streams and increments the counter in the associated {@link
     * EquivalentAddressGroupTracker}.
     */
    class ResultCountingClientStreamTracer extends ClientStreamTracer {

      private final OutlierDetectionSubchannel subchannel;

      public ResultCountingClientStreamTracer(OutlierDetectionSubchannel subchannel) {
        this.subchannel = subchannel;
      }

      @Override
      public void streamClosed(Status status) {
        subchannel.eagInfo.incrementCallCount(status.isOk());
      }
    }
  }

  /**
   * Tracks additional information about a set of equivalent addresses needed for outlier
   * detection.
   */
  static class EquivalentAddressGroupTracker {

    private final OutlierDetectionLoadBalancerConfig config;
    private CallCounter activeCallCounter = new CallCounter();
    private CallCounter inactiveCallCounter = new CallCounter();
    private Long ejectionTimeNanos;
    private int ejectionTimeMultiplier;
    private final Set<OutlierDetectionSubchannel> subchannels = new HashSet<>();

    EquivalentAddressGroupTracker(OutlierDetectionLoadBalancerConfig config) {
      this.config = config;
    }

    boolean addSubchannel(OutlierDetectionSubchannel subchannel) {
      return subchannels.add(subchannel);
    }

    boolean removeSubchannel(OutlierDetectionSubchannel subchannel) {
      return subchannels.remove(subchannel);
    }

    boolean containsSubchannel(OutlierDetectionSubchannel subchannel) {
      return subchannels.contains(subchannel);
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

    /**
     * The total number of calls in the active call counter.
     */
    long volume() {
      return (long) activeCallCounter.successCount.get() + activeCallCounter.failureCount.get();
    }

    void clearCallCounters() {
      activeCallCounter.successCount.set(0);
      activeCallCounter.failureCount.set(0);
      inactiveCallCounter.successCount.set(0);
      inactiveCallCounter.failureCount.set(0);
    }

    void decrementEjectionTimeMultiplier() {
      // The multiplier should not go negative.
      ejectionTimeMultiplier = ejectionTimeMultiplier == 0 ? 0 : ejectionTimeMultiplier - 1;
    }

    void resetEjectionTimeMultiplier() {
      ejectionTimeMultiplier = 0;
    }

    void swapCounters() {
      CallCounter tempCounter = activeCallCounter;
      activeCallCounter = inactiveCallCounter;
      inactiveCallCounter = tempCounter;
    }

    /**
     * Ejects the address from use.
     */
    void eject(long ejectionTimeNanos) {
      this.ejectionTimeNanos = ejectionTimeNanos;
      ejectionTimeMultiplier++;
      for (OutlierDetectionSubchannel subchannel : subchannels) {
        subchannel.eject();
      }
    }

    /**
     * Uneject a currently ejected address.
     */
    void uneject() {
      checkState(ejectionTimeNanos == null, "not currently ejected");
      ejectionTimeNanos = null;
      for (OutlierDetectionSubchannel subchannel : subchannels) {
        subchannel.uneject();
      }
    }

    boolean isEjected() {
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
      AtomicInteger successCount = new AtomicInteger();
      AtomicInteger failureCount = new AtomicInteger();
    }
  }

  /**
   * Implementations provide different ways of ejecting outlier addresses..
   */
  interface OutlierEjectionAlgorithm {

    /**
     * Is the given {@link EquivalentAddressGroup} an outlier based on the past call results stored
     * in {@link EquivalentAddressGroupTracker}.
     */
    void ejectOutliers(
        Map<EquivalentAddressGroup, EquivalentAddressGroupTracker> eagTrackerMap,
        long ejectionTimeMillis);

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

  static class SuccessRateOutlierEjectionAlgorithm implements OutlierEjectionAlgorithm {

    private final OutlierDetectionLoadBalancerConfig config;

    SuccessRateOutlierEjectionAlgorithm(OutlierDetectionLoadBalancerConfig config) {
      checkArgument(config.successRateEjection != null, "success rate ejection config is null");
      this.config = config;
    }

    @Override
    public void ejectOutliers(
        Map<EquivalentAddressGroup, EquivalentAddressGroupTracker> eagTrackerMap,
        long ejectionTimeNanos) {

      // Only consider addresses that have the minimum request volume specified in the config.
      List<EquivalentAddressGroupTracker> trackersWithVolume = new ArrayList<>();
      for (EquivalentAddressGroupTracker tracker : eagTrackerMap.values()) {
        if (tracker.volume() >= config.successRateEjection.requestVolume) {
          trackersWithVolume.add(tracker);
        }
      }
      // If we don't have enough addresses with significant volume then there's nothing to do.
      if (trackersWithVolume.size() < config.successRateEjection.minimumHosts
          || trackersWithVolume.size() == 0) {
        return;
      }

      // Calculate mean and standard deviation of the successful calls.
      int totalSuccessCount = 0;
      for (EquivalentAddressGroupTracker tracker : trackersWithVolume) {
        totalSuccessCount += tracker.activeCallCounter.successCount.get();
      }
      double mean = totalSuccessCount / trackersWithVolume.size();

      double squaredDifferenceSum = 0;
      for (EquivalentAddressGroupTracker tracker : trackersWithVolume) {
        double difference = tracker.activeCallCounter.successCount.get() - mean;
        squaredDifferenceSum += difference * difference;
      }
      double variance = squaredDifferenceSum / trackersWithVolume.size();

      double stdev = Math.sqrt(variance);

      for (EquivalentAddressGroupTracker tracker : eagTrackerMap.values()) {
        // If we have already ejected addresses past the max percentage, stop here
        int totalAddresses = 0;
        int ejectedAddresses = 0;
        for (EquivalentAddressGroupTracker t : eagTrackerMap.values()) {
          totalAddresses++;
          if (t.isEjected()) {
            ejectedAddresses++;
          }
        }
        double ejectedPercentage = (ejectedAddresses / totalAddresses) * 100;
        if (ejectedPercentage > config.maxEjectionPercent) {
          return;
        }

        // If this address does not have enough volume to be considered, skip to the next one.
        if (tracker.volume() < config.successRateEjection.requestVolume) {
          continue;
        }

        // If success rate is below the threshold, eject the address.
        double successRate = tracker.activeCallCounter.successCount.get() / tracker.volume();
        if (successRate < mean - stdev * (config.successRateEjection.stdevFactor / 1000)) {
          // Only eject some addresses based on the enforcement percentage.
          if (new Random().nextInt(100) < config.successRateEjection.enforcementPercentage) {
            tracker.eject(ejectionTimeNanos);
          }
        }
      }
    }
  }

  static class FailurePercentageOutlierEjectionAlgorithm implements OutlierEjectionAlgorithm {

    private final OutlierDetectionLoadBalancerConfig config;

    FailurePercentageOutlierEjectionAlgorithm(OutlierDetectionLoadBalancerConfig config) {
      this.config = config;
    }

    @Override
    public void ejectOutliers(
        Map<EquivalentAddressGroup, EquivalentAddressGroupTracker> eagTrackerMap,
        long ejectionTimeMillis) {

      // If we don't have the minimum amount of addresses the config calls for, then return.
      if (eagTrackerMap.size() < config.failurePercentageEjection.minimumHosts) {
        return;
      }

      // If this address does not have enough volume to be considered, skip to the next one.
      for (EquivalentAddressGroupTracker tracker : eagTrackerMap.values()) {
        // If we have already ejected addresses past the max percentage, stop here.
        int totalAddresses = 0;
        int ejectedAddresses = 0;
        for (EquivalentAddressGroupTracker t : eagTrackerMap.values()) {
          totalAddresses++;
          if (t.isEjected()) {
            ejectedAddresses++;
          }
        }
        double ejectedPercentage = (ejectedAddresses / totalAddresses) * 100;

        if (ejectedPercentage > config.maxEjectionPercent) {
          return;
        }

        if (tracker.volume() < config.failurePercentageEjection.requestVolume) {
          continue;
        }

        // If the failure percentage is above the threshold.
        long failurePercentage =
            (tracker.activeCallCounter.failureCount.get() / tracker.volume()) * 100;
        if (failurePercentage > config.failurePercentageEjection.threshold) {
          // Only eject some addresses based on the enforcement percentage.
          if (new Random().nextInt(100) < config.failurePercentageEjection.enforcementPercentage) {
            tracker.eject(ejectionTimeMillis);
          }
        }
      }
    }
  }


  /**
   * The configuration for {@link OutlierDetectionLoadBalancer}.
   */
  static final class OutlierDetectionLoadBalancerConfig {

    final long intervalSecs;
    final long baseEjectionTimeSecs;
    final long maxEjectionTimeSecs;
    final Integer maxEjectionPercent;
    final SuccessRateEjection successRateEjection;
    final FailurePercentageEjection failurePercentageEjection;
    final PolicySelection childPolicy;

    OutlierDetectionLoadBalancerConfig(PolicySelection childPolicy, Long intervalSecs,
        Long baseEjectionTimeSecs,
        Long maxEjectionTimeSecs, Integer maxEjectionPercent,
        SuccessRateEjection successRateEjection,
        FailurePercentageEjection failurePercentageEjection) {
      this.intervalSecs = intervalSecs != null ? intervalSecs : 10;
      this.baseEjectionTimeSecs = baseEjectionTimeSecs != null ? baseEjectionTimeSecs : 30;
      this.maxEjectionTimeSecs = maxEjectionTimeSecs != null ? maxEjectionTimeSecs : 30;
      this.maxEjectionPercent = maxEjectionPercent != null ? maxEjectionPercent : 10;
      this.successRateEjection = successRateEjection;
      this.failurePercentageEjection = failurePercentageEjection;
      this.childPolicy = childPolicy;
    }

    static class SuccessRateEjection {

      final Integer stdevFactor;
      final Integer enforcementPercentage;
      final Integer minimumHosts;
      final Integer requestVolume;

      SuccessRateEjection(Integer stdevFactor, Integer enforcementPercentage, Integer minimumHosts,
          Integer requestVolume) {
        this.stdevFactor = stdevFactor != null ? stdevFactor : 1900;
        this.enforcementPercentage = enforcementPercentage != null ? enforcementPercentage : 100;
        this.minimumHosts = minimumHosts != null ? minimumHosts : 5;
        this.requestVolume = requestVolume != null ? requestVolume : 100;
      }
    }

    static class FailurePercentageEjection {

      final Integer threshold;
      final Integer enforcementPercentage;
      final Integer minimumHosts;
      final Integer requestVolume;

      FailurePercentageEjection(Integer threshold, Integer enforcementPercentage,
          Integer minimumHosts, Integer requestVolume) {
        this.threshold = threshold != null ? threshold : 85;
        this.enforcementPercentage = enforcementPercentage != null ? enforcementPercentage : 100;
        this.minimumHosts = minimumHosts != null ? minimumHosts : 5;
        this.requestVolume = requestVolume != null ? requestVolume : 50;
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
