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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ForwardingMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.internal.TimeProvider;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * Wraps a child {@code LoadBalancer} while monitoring for outlier backends and removing them from
 * the use of the child LB.
 *
 * <p>This implements the outlier detection gRFC:
 * https://github.com/grpc/proposal/blob/master/A50-xds-outlier-detection.md
 */
@Internal
public final class OutlierDetectionLoadBalancer extends LoadBalancer {

  @VisibleForTesting
  final AddressTrackerMap trackerMap;

  private final SynchronizationContext syncContext;
  private final Helper childHelper;
  private final GracefulSwitchLoadBalancer switchLb;
  private TimeProvider timeProvider;
  private final ScheduledExecutorService timeService;
  private ScheduledHandle detectionTimerHandle;
  private Long detectionTimerStartNanos;

  private final ChannelLogger logger;

  private static final Attributes.Key<AddressTracker> ADDRESS_TRACKER_ATTR_KEY
      = Attributes.Key.create("addressTrackerKey");

  /**
   * Creates a new instance of {@link OutlierDetectionLoadBalancer}.
   */
  public OutlierDetectionLoadBalancer(Helper helper, TimeProvider timeProvider) {
    logger = helper.getChannelLogger();
    childHelper = new ChildHelper(checkNotNull(helper, "helper"));
    switchLb = new GracefulSwitchLoadBalancer(childHelper);
    trackerMap = new AddressTrackerMap();
    this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    this.timeService = checkNotNull(helper.getScheduledExecutorService(), "timeService");
    this.timeProvider = timeProvider;
    logger.log(ChannelLogLevel.DEBUG, "OutlierDetection lb created.");
  }

  @Override
  public boolean acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    logger.log(ChannelLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    OutlierDetectionLoadBalancerConfig config
        = (OutlierDetectionLoadBalancerConfig) resolvedAddresses.getLoadBalancingPolicyConfig();

    // The map should only retain entries for addresses in this latest update.
    ArrayList<SocketAddress> addresses = new ArrayList<>();
    for (EquivalentAddressGroup addressGroup : resolvedAddresses.getAddresses()) {
      addresses.addAll(addressGroup.getAddresses());
    }
    trackerMap.keySet().retainAll(addresses);

    trackerMap.updateTrackerConfigs(config);

    // Add any new ones.
    trackerMap.putNewTrackers(config, addresses);

    switchLb.switchTo(config.childPolicy.getProvider());

    // If outlier detection is actually configured, start a timer that will periodically try to
    // detect outliers.
    if (config.outlierDetectionEnabled()) {
      Long initialDelayNanos;

      if (detectionTimerStartNanos == null) {
        // On the first go we use the configured interval.
        initialDelayNanos = config.intervalNanos;
      } else {
        // If a timer has started earlier we cancel it and use the difference between the start
        // time and now as the interval.
        initialDelayNanos = Math.max(0L,
            config.intervalNanos - (timeProvider.currentTimeNanos() - detectionTimerStartNanos));
      }

      // If a timer has been previously created we need to cancel it and reset all the call counters
      // for a fresh start.
      if (detectionTimerHandle != null) {
        detectionTimerHandle.cancel();
        trackerMap.resetCallCounters();
      }

      detectionTimerHandle = syncContext.scheduleWithFixedDelay(new DetectionTimer(config, logger),
          initialDelayNanos, config.intervalNanos, NANOSECONDS, timeService);
    } else if (detectionTimerHandle != null) {
      // Outlier detection is not configured, but we have a lingering timer. Let's cancel it and
      // uneject any addresses we may have ejected.
      detectionTimerHandle.cancel();
      detectionTimerStartNanos = null;
      trackerMap.cancelTracking();
    }

    switchLb.handleResolvedAddresses(
        resolvedAddresses.toBuilder().setLoadBalancingPolicyConfig(config.childPolicy.getConfig())
            .build());
    return true;
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
    ChannelLogger logger;

    DetectionTimer(OutlierDetectionLoadBalancerConfig config, ChannelLogger logger) {
      this.config = config;
      this.logger = logger;
    }

    @Override
    public void run() {
      detectionTimerStartNanos = timeProvider.currentTimeNanos();

      trackerMap.swapCounters();

      for (OutlierEjectionAlgorithm algo : OutlierEjectionAlgorithm.forConfig(config, logger)) {
        algo.ejectOutliers(trackerMap, detectionTimerStartNanos);
      }

      trackerMap.maybeUnejectOutliers(detectionTimerStartNanos);
    }
  }

  /**
   * This child helper wraps the provided helper so that it can hand out wrapped {@link
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
      List<EquivalentAddressGroup> addressGroups = args.getAddresses();
      if (hasSingleAddress(addressGroups)
          && trackerMap.containsKey(addressGroups.get(0).getAddresses().get(0))) {
        AddressTracker tracker = trackerMap.get(addressGroups.get(0).getAddresses().get(0));
        tracker.addSubchannel(subchannel);

        // If this address has already been ejected, we need to immediately eject this Subchannel.
        if (tracker.ejectionTimeNanos != null) {
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
    private AddressTracker addressTracker;
    private boolean ejected;
    private ConnectivityStateInfo lastSubchannelState;
    private SubchannelStateListener subchannelStateListener;
    private final ChannelLogger logger;

    OutlierDetectionSubchannel(Subchannel delegate) {
      this.delegate = delegate;
      this.logger = delegate.getChannelLogger();
    }

    @Override
    public void start(SubchannelStateListener listener) {
      subchannelStateListener = listener;
      super.start(new OutlierDetectionSubchannelStateListener(listener));
    }

    @Override
    public Attributes getAttributes() {
      if (addressTracker != null) {
        return delegate.getAttributes().toBuilder().set(ADDRESS_TRACKER_ATTR_KEY, addressTracker)
            .build();
      } else {
        return delegate.getAttributes();
      }
    }

    @Override
    public void updateAddresses(List<EquivalentAddressGroup> addressGroups) {
      // Outlier detection only supports subchannels with a single address, but the list of
      // addressGroups associated with a subchannel can change at any time, so we need to react to
      // changes in the address list plurality.

      // No change in address plurality, we replace the single one with a new one.
      if (hasSingleAddress(getAllAddresses()) && hasSingleAddress(addressGroups)) {
        // Remove the current subchannel from the old address it is associated with in the map.
        if (trackerMap.containsValue(addressTracker)) {
          addressTracker.removeSubchannel(this);
        }

        // If the map has an entry for the new address, we associate this subchannel with it.
        SocketAddress address = addressGroups.get(0).getAddresses().get(0);
        if (trackerMap.containsKey(address)) {
          trackerMap.get(address).addSubchannel(this);
        }
      } else if (hasSingleAddress(getAllAddresses()) && !hasSingleAddress(addressGroups)) {
        // We go from a single address to having multiple, making this subchannel uneligible for
        // outlier detection. Remove it from all trackers and reset the call counters of all the
        // associated trackers.
        // Remove the current subchannel from the old address it is associated with in the map.
        if (trackerMap.containsKey(getAddresses().getAddresses().get(0))) {
          AddressTracker tracker = trackerMap.get(getAddresses().getAddresses().get(0));
          tracker.removeSubchannel(this);
          tracker.resetCallCounters();
        }
      } else if (!hasSingleAddress(getAllAddresses()) && hasSingleAddress(addressGroups)) {
        // We go from, previously uneligble, multiple address mode to a single address. If the map
        // has an entry for the new address, we associate this subchannel with it.
        SocketAddress address = addressGroups.get(0).getAddresses().get(0);
        if (trackerMap.containsKey(address)) {
          AddressTracker tracker = trackerMap.get(address);
          tracker.addSubchannel(this);
        }
      }

      // We could also have multiple addressGroups and get an update for multiple new ones. This is
      // a no-op as we will just continue to ignore multiple address subchannels.

      delegate.updateAddresses(addressGroups);
    }

    /**
     * If the {@link Subchannel} is considered for outlier detection the associated {@link
     * AddressTracker} should be set.
     */
    void setAddressTracker(AddressTracker addressTracker) {
      this.addressTracker = addressTracker;
    }

    void clearAddressTracker() {
      this.addressTracker = null;
    }

    void eject() {
      ejected = true;
      subchannelStateListener.onSubchannelState(
          ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE));
      logger.log(ChannelLogLevel.INFO, "Subchannel ejected: {0}", this);
    }

    void uneject() {
      ejected = false;
      if (lastSubchannelState != null) {
        subchannelStateListener.onSubchannelState(lastSubchannelState);
        logger.log(ChannelLogLevel.INFO, "Subchannel unejected: {0}", this);
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
        lastSubchannelState = newState;
        if (!ejected) {
          delegate.onSubchannelState(newState);
        }
      }
    }

    @Override
    public String toString() {
      return "OutlierDetectionSubchannel{"
              + "addresses=" + delegate.getAllAddresses()
              + '}';
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
        return PickResult.withSubchannel(subchannel, new ResultCountingClientStreamTracerFactory(
            subchannel.getAttributes().get(ADDRESS_TRACKER_ATTR_KEY),
            pickResult.getStreamTracerFactory()));
      }

      return pickResult;
    }

    /**
     * Builds instances of a {@link ClientStreamTracer} that increments the call count in the
     * tracker for each closed stream.
     */
    class ResultCountingClientStreamTracerFactory extends ClientStreamTracer.Factory {

      private final AddressTracker tracker;

      @Nullable
      private final ClientStreamTracer.Factory delegateFactory;

      ResultCountingClientStreamTracerFactory(AddressTracker tracker,
          @Nullable ClientStreamTracer.Factory delegateFactory) {
        this.tracker = tracker;
        this.delegateFactory = delegateFactory;
      }

      @Override
      public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
        if (delegateFactory != null) {
          ClientStreamTracer delegateTracer = delegateFactory.newClientStreamTracer(info, headers);
          return new ForwardingClientStreamTracer() {
            @Override
            protected ClientStreamTracer delegate() {
              return delegateTracer;
            }

            @Override
            public void streamClosed(Status status) {
              tracker.incrementCallCount(status.isOk());
              delegate().streamClosed(status);
            }
          };
        } else {
          return new ClientStreamTracer() {
            @Override
            public void streamClosed(Status status) {
              tracker.incrementCallCount(status.isOk());
            }
          };
        }
      }
    }
  }

  /**
   * Tracks additional information about a set of equivalent addresses needed for outlier
   * detection.
   */
  static class AddressTracker {

    private OutlierDetectionLoadBalancerConfig config;
    // Marked as volatile to assure that when the inactive counter is swapped in as the new active
    // one, all threads see the change and don't hold on to a reference to the now inactive counter.
    private volatile CallCounter activeCallCounter = new CallCounter();
    private CallCounter inactiveCallCounter = new CallCounter();
    private Long ejectionTimeNanos;
    private int ejectionTimeMultiplier;
    private final Set<OutlierDetectionSubchannel> subchannels = new HashSet<>();

    AddressTracker(OutlierDetectionLoadBalancerConfig config) {
      this.config = config;
    }

    void setConfig(OutlierDetectionLoadBalancerConfig config) {
      this.config = config;
    }

    /**
     * Adds a subchannel to the tracker, while assuring that the subchannel ejection status is
     * updated to match the tracker's if needed.
     */
    boolean addSubchannel(OutlierDetectionSubchannel subchannel) {
      // Make sure that the subchannel is in the same ejection state as the new tracker it is
      // associated with.
      if (subchannelsEjected() && !subchannel.isEjected()) {
        subchannel.eject();
      } else if (!subchannelsEjected() && subchannel.isEjected()) {
        subchannel.uneject();
      }
      subchannel.setAddressTracker(this);
      return subchannels.add(subchannel);
    }

    boolean removeSubchannel(OutlierDetectionSubchannel subchannel) {
      subchannel.clearAddressTracker();
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

    /**
     * Swaps the active and inactive counters.
     *
     * <p>Note that this method is not thread safe as the swap is not done atomically. This is
     * expected to only be called from the timer that is scheduled at a fixed delay, assuring that
     * only one timer is active at a time.
     */
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
      long maxEjectionDurationSecs
          = Math.max(config.baseEjectionTimeNanos, config.maxEjectionTimeNanos);
      long maxEjectionTimeNanos =
          ejectionTimeNanos + Math.min(
              config.baseEjectionTimeNanos * ejectionTimeMultiplier,
              maxEjectionDurationSecs);

      return currentTimeNanos > maxEjectionTimeNanos;
    }

    /** Tracks both successful and failed call counts. */
    private static class CallCounter {
      AtomicLong successCount = new AtomicLong();
      AtomicLong failureCount = new AtomicLong();

      void reset() {
        successCount.set(0);
        failureCount.set(0);
      }
    }

    @Override
    public String toString() {
      return "AddressTracker{"
              + "subchannels=" + subchannels
              + '}';
    }
  }

  /**
   * Maintains a mapping from addresses to their trackers.
   */
  static class AddressTrackerMap extends ForwardingMap<SocketAddress, AddressTracker> {
    private final Map<SocketAddress, AddressTracker> trackerMap;

    AddressTrackerMap() {
      trackerMap = new HashMap<>();
    }

    @Override
    protected Map<SocketAddress, AddressTracker> delegate() {
      return trackerMap;
    }

    void updateTrackerConfigs(OutlierDetectionLoadBalancerConfig config) {
      for (AddressTracker tracker: trackerMap.values()) {
        tracker.setConfig(config);
      }
    }

    /** Adds a new tracker for every given address. */
    void putNewTrackers(OutlierDetectionLoadBalancerConfig config,
        Collection<SocketAddress> addresses) {
      for (SocketAddress address : addresses) {
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

    /**
     * How many percent of the addresses have been ejected.
     */
    double ejectionPercentage() {
      if (trackerMap.isEmpty()) {
        return 0;
      }
      int totalAddresses = 0;
      int ejectedAddresses = 0;
      for (AddressTracker tracker : trackerMap.values()) {
        totalAddresses++;
        if (tracker.subchannelsEjected()) {
          ejectedAddresses++;
        }
      }
      return ((double)ejectedAddresses / totalAddresses) * 100;
    }
  }


  /**
   * Implementations provide different ways of ejecting outlier addresses..
   */
  interface OutlierEjectionAlgorithm {

    /** Eject any outlier addresses. */
    void ejectOutliers(AddressTrackerMap trackerMap, long ejectionTimeNanos);

    /** Builds a list of algorithms that are enabled in the given config. */
    @Nullable
    static List<OutlierEjectionAlgorithm> forConfig(OutlierDetectionLoadBalancerConfig config,
                                                    ChannelLogger logger) {
      ImmutableList.Builder<OutlierEjectionAlgorithm> algoListBuilder = ImmutableList.builder();
      if (config.successRateEjection != null) {
        algoListBuilder.add(new SuccessRateOutlierEjectionAlgorithm(config, logger));
      }
      if (config.failurePercentageEjection != null) {
        algoListBuilder.add(new FailurePercentageOutlierEjectionAlgorithm(config, logger));
      }
      return algoListBuilder.build();
    }
  }

  /**
   * This algorithm ejects addresses that don't maintain a required rate of successful calls. The
   * required rate is not fixed, but is based on the mean and standard deviation of the success
   * rates of all of the addresses.
   */
  static class SuccessRateOutlierEjectionAlgorithm implements OutlierEjectionAlgorithm {

    private final OutlierDetectionLoadBalancerConfig config;

    private final ChannelLogger logger;

    SuccessRateOutlierEjectionAlgorithm(OutlierDetectionLoadBalancerConfig config,
                                        ChannelLogger logger) {
      checkArgument(config.successRateEjection != null, "success rate ejection config is null");
      this.config = config;
      this.logger = logger;
    }

    @Override
    public void ejectOutliers(AddressTrackerMap trackerMap, long ejectionTimeNanos) {

      // Only consider addresses that have the minimum request volume specified in the config.
      List<AddressTracker> trackersWithVolume = trackersWithVolume(trackerMap,
          config.successRateEjection.requestVolume);
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

      double requiredSuccessRate =
          mean - stdev * (config.successRateEjection.stdevFactor / 1000f);

      for (AddressTracker tracker : trackersWithVolume) {
        // If we are above or equal to the max ejection percentage, don't eject any more. This will
        // allow the total ejections to go one above the max, but at the same time it assures at
        // least one ejection, which the spec calls for. This behavior matches what Envoy proxy
        // does.
        if (trackerMap.ejectionPercentage() >= config.maxEjectionPercent) {
          return;
        }

        // If success rate is below the threshold, eject the address.
        if (tracker.successRate() < requiredSuccessRate) {
          logger.log(ChannelLogLevel.DEBUG,
                  "SuccessRate algorithm detected outlier: {0}. "
                          + "Parameters: successRate={1}, mean={2}, stdev={3}, "
                          + "requiredSuccessRate={4}",
                  tracker, tracker.successRate(),  mean, stdev, requiredSuccessRate);
          // Only eject some addresses based on the enforcement percentage.
          if (new Random().nextInt(100) < config.successRateEjection.enforcementPercentage) {
            tracker.ejectSubchannels(ejectionTimeNanos);
          }
        }
      }
    }

    /** Calculates the mean of the given values. */
    @VisibleForTesting
    static double mean(Collection<Double> values) {
      double totalValue = 0;
      for (double value : values) {
        totalValue += value;
      }

      return totalValue / values.size();
    }

    /** Calculates the standard deviation for the given values and their mean. */
    @VisibleForTesting
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

    private final ChannelLogger logger;

    FailurePercentageOutlierEjectionAlgorithm(OutlierDetectionLoadBalancerConfig config,
                                              ChannelLogger logger) {
      this.config = config;
      this.logger = logger;
    }

    @Override
    public void ejectOutliers(AddressTrackerMap trackerMap, long ejectionTimeNanos) {

      // Only consider addresses that have the minimum request volume specified in the config.
      List<AddressTracker> trackersWithVolume = trackersWithVolume(trackerMap,
          config.failurePercentageEjection.requestVolume);
      // If we don't have enough addresses with significant volume then there's nothing to do.
      if (trackersWithVolume.size() < config.failurePercentageEjection.minimumHosts
          || trackersWithVolume.size() == 0) {
        return;
      }

      // If this address does not have enough volume to be considered, skip to the next one.
      for (AddressTracker tracker : trackersWithVolume) {
        // If we are above or equal to the max ejection percentage, don't eject any more. This will
        // allow the total ejections to go one above the max, but at the same time it assures at
        // least one ejection, which the spec calls for. This behavior matches what Envoy proxy
        // does.
        if (trackerMap.ejectionPercentage() >= config.maxEjectionPercent) {
          return;
        }

        if (tracker.inactiveVolume() < config.failurePercentageEjection.requestVolume) {
          continue;
        }

        // If the failure rate is above the threshold, we should eject...
        double maxFailureRate = ((double)config.failurePercentageEjection.threshold) / 100;
        if (tracker.failureRate() > maxFailureRate) {
          logger.log(ChannelLogLevel.DEBUG,
                  "FailurePercentage algorithm detected outlier: {0}, failureRate={1}",
                  tracker, tracker.failureRate());
          // ...but only enforce this based on the enforcement percentage.
          if (new Random().nextInt(100) < config.failurePercentageEjection.enforcementPercentage) {
            tracker.ejectSubchannels(ejectionTimeNanos);
          }
        }
      }
    }
  }

  /** Returns only the trackers that have the minimum configured volume to be considered. */
  private static List<AddressTracker> trackersWithVolume(AddressTrackerMap trackerMap,
      int volume) {
    List<AddressTracker> trackersWithVolume = new ArrayList<>();
    for (AddressTracker tracker : trackerMap.values()) {
      if (tracker.inactiveVolume() >= volume) {
        trackersWithVolume.add(tracker);
      }
    }
    return trackersWithVolume;
  }

  /** Counts how many addresses are in a given address group. */
  private static boolean hasSingleAddress(List<EquivalentAddressGroup> addressGroups) {
    int addressCount = 0;
    for (EquivalentAddressGroup addressGroup : addressGroups) {
      addressCount += addressGroup.getAddresses().size();
      if (addressCount > 1) {
        return false;
      }
    }
    return true;
  }

  /**
   * The configuration for {@link OutlierDetectionLoadBalancer}.
   */
  public static final class OutlierDetectionLoadBalancerConfig {

    public final Long intervalNanos;
    public final Long baseEjectionTimeNanos;
    public final Long maxEjectionTimeNanos;
    public final Integer maxEjectionPercent;
    public final SuccessRateEjection successRateEjection;
    public final FailurePercentageEjection failurePercentageEjection;
    public final PolicySelection childPolicy;

    private OutlierDetectionLoadBalancerConfig(Long intervalNanos,
        Long baseEjectionTimeNanos,
        Long maxEjectionTimeNanos,
        Integer maxEjectionPercent,
        SuccessRateEjection successRateEjection,
        FailurePercentageEjection failurePercentageEjection,
        PolicySelection childPolicy) {
      this.intervalNanos = intervalNanos;
      this.baseEjectionTimeNanos = baseEjectionTimeNanos;
      this.maxEjectionTimeNanos = maxEjectionTimeNanos;
      this.maxEjectionPercent = maxEjectionPercent;
      this.successRateEjection = successRateEjection;
      this.failurePercentageEjection = failurePercentageEjection;
      this.childPolicy = childPolicy;
    }

    /** Builds a new {@link OutlierDetectionLoadBalancerConfig}. */
    public static class Builder {
      Long intervalNanos = 10_000_000_000L; // 10s
      Long baseEjectionTimeNanos = 30_000_000_000L; // 30s
      Long maxEjectionTimeNanos = 300_000_000_000L; // 300s
      Integer maxEjectionPercent = 10;
      SuccessRateEjection successRateEjection;
      FailurePercentageEjection failurePercentageEjection;
      PolicySelection childPolicy;

      /** The interval between outlier detection sweeps. */
      public Builder setIntervalNanos(Long intervalNanos) {
        checkArgument(intervalNanos != null);
        this.intervalNanos = intervalNanos;
        return this;
      }

      /** The base time an address is ejected for. */
      public Builder setBaseEjectionTimeNanos(Long baseEjectionTimeNanos) {
        checkArgument(baseEjectionTimeNanos != null);
        this.baseEjectionTimeNanos = baseEjectionTimeNanos;
        return this;
      }

      /** The longest time an address can be ejected. */
      public Builder setMaxEjectionTimeNanos(Long maxEjectionTimeNanos) {
        checkArgument(maxEjectionTimeNanos != null);
        this.maxEjectionTimeNanos = maxEjectionTimeNanos;
        return this;
      }

      /** The algorithm agnostic maximum percentage of addresses that can be ejected. */
      public Builder setMaxEjectionPercent(Integer maxEjectionPercent) {
        checkArgument(maxEjectionPercent != null);
        this.maxEjectionPercent = maxEjectionPercent;
        return this;
      }

      /** Set to enable success rate ejection. */
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
        return new OutlierDetectionLoadBalancerConfig(intervalNanos, baseEjectionTimeNanos,
            maxEjectionTimeNanos, maxEjectionPercent, successRateEjection,
            failurePercentageEjection, childPolicy);
      }
    }

    /** The configuration for success rate ejection. */
    public static class SuccessRateEjection {

      public final Integer stdevFactor;
      public final Integer enforcementPercentage;
      public final Integer minimumHosts;
      public final Integer requestVolume;

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
          checkArgument(enforcementPercentage >= 0 && enforcementPercentage <= 100);
          this.enforcementPercentage = enforcementPercentage;
          return this;
        }

        /** The minimum amount of hosts needed for success rate ejection. */
        public Builder setMinimumHosts(Integer minimumHosts) {
          checkArgument(minimumHosts != null);
          checkArgument(minimumHosts >= 0);
          this.minimumHosts = minimumHosts;
          return this;
        }

        /** The minimum address request volume to be considered for success rate ejection. */
        public Builder setRequestVolume(Integer requestVolume) {
          checkArgument(requestVolume != null);
          checkArgument(requestVolume >= 0);
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
      public final Integer threshold;
      public final Integer enforcementPercentage;
      public final Integer minimumHosts;
      public final Integer requestVolume;

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
          checkArgument(threshold >= 0 && threshold <= 100);
          this.threshold = threshold;
          return this;
        }

        /** Only eject this percentage of outliers. */
        public Builder setEnforcementPercentage(Integer enforcementPercentage) {
          checkArgument(enforcementPercentage != null);
          checkArgument(enforcementPercentage >= 0 && enforcementPercentage <= 100);
          this.enforcementPercentage = enforcementPercentage;
          return this;
        }

        /** The minimum amount of host for failure percentage ejection to be enabled. */
        public Builder setMinimumHosts(Integer minimumHosts) {
          checkArgument(minimumHosts != null);
          checkArgument(minimumHosts >= 0);
          this.minimumHosts = minimumHosts;
          return this;
        }

        /**
         * The request volume required for an address to be considered for failure percentage
         * ejection.
         */
        public Builder setRequestVolume(Integer requestVolume) {
          checkArgument(requestVolume != null);
          checkArgument(requestVolume >= 0);
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

    /** Determine if any outlier detection algorithms are enabled in the config. */
    boolean outlierDetectionEnabled() {
      return successRateEjection != null || failurePercentageEjection != null;
    }
  }
}
