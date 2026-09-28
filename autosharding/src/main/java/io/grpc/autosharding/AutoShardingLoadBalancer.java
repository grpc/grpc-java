/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.autosharding;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import io.grpc.Channel;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.GrpcUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * The {@code autosharding_experimental} load balancing policy.
 *
 * <p>This policy shards RPCs across endpoints by an application-defined key carried in a request
 * header. The mapping from key ranges to endpoints comes from an external sharding service, which
 * an {@link AutoshardingClient} streams assignments from. See gRFC A119.
 *
 * <h3>Moving parts</h3>
 *
 * <ul>
 *   <li>{@link EndpointMap} owns one lazily-created {@code pick_first} child per resolved
 *       endpoint and assigns each a dense index.
 *   <li>{@link AutoshardingClient} produces validated {@link Assignment}s, which name endpoints
 *       by hostname.
 *   <li>{@link SliceMap} is the join of the two: the assignment's key ranges with hostnames
 *       translated into endpoint indices. It is rebuilt whenever either input changes.
 *   <li>{@link AutoShardingPicker} performs the per-RPC lookup against a {@link SliceMap} and a
 *       snapshot of endpoint states. It is rebuilt on every child state update too, reusing the
 *       existing {@link SliceMap} because the endpoint indices did not move.
 * </ul>
 *
 * <h3>Startup</h3>
 *
 * <p>Each {@link AutoshardingClient} reports either valid assignments or, until it has reported
 * one, errors (including its initial assignment timer firing). Until the first report RPCs are
 * queued, while the reported connectivity state still follows the endpoints, starting at IDLE.
 * After an error, RPCs either spread across every resolved endpoint or fail with that
 * error, depending on {@code enable_fallback}. A new client is created whenever the channel
 * factory key or the sharding target changes; whatever the previous client last reported keeps
 * being used until the new one reports, so a change of sharding service does not interrupt
 * traffic. A failure to create the channel is handled like an error from the client.
 *
 * <h3>Threading model</h3>
 *
 * <p>All state lives on the {@link SynchronizationContext}.
 */
final class AutoShardingLoadBalancer extends LoadBalancer {
  private static final Logger logger =
      Logger.getLogger(AutoShardingLoadBalancer.class.getName());

  /**
   * Published while waiting for the first assignment. The delay type is consumed by the
   * name-resolution delay tracking in gRFC A121.
   */
  private static final SubchannelPicker ASSIGNMENT_PENDING_PICKER =
      new FixedResultPicker(
          PickResult.withNoResult(
              "autosharding_assignment_pending", "Waiting for initial sharding assignment"));

  private final Helper helper;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timeService;
  private final LoadBalancerProvider childProvider;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final Supplier<Stopwatch> stopwatchSupplier;

  /** Identifies this client to the sharding service; stable across stream restarts. */
  private final String clientUuid;

  private final EndpointMap endpointMap;

  @Nullable private AutoShardingLoadBalancerConfig config;
  @Nullable private Metadata.Key<byte[]> keyHeader;

  /** The factory last seen in the resolver attributes. */
  @Nullable private ChannelFactory channelFactory;

  /** Channel borrowed from {@link #channelFactory}; must be given back when we are done. */
  @Nullable private Channel shardingChannel;

  /**
   * The {@code autosharding_target} the current {@link #client} was created with, after {@code %s}
   * substitution. Tracked separately from the config because the substitution depends on the
   * resolved endpoints, so the target can change while the config does not.
   */
  @Nullable private String shardingTarget;

  /**
   * Locality last derived from the endpoints, substituted for {@code %s} in the target. Kept
   * through an empty endpoint update, which carries no locality, so that retracting the endpoints
   * does not move the client to another target and back. Null when the endpoints did not share
   * one.
   */
  @Nullable private String locality;

  @Nullable private AutoshardingClient client;

  /**
   * Most recent assignment reported by an {@link AutoshardingClient}. Survives client
   * replacement, and is cleared only when a client reports an error instead.
   */
  @Nullable private Assignment assignment;

  /**
   * Most recent error reported by an {@link AutoshardingClient}, or the failure to create the
   * channel for one. Never set together with {@link #assignment}; with both null, nothing has
   * been reported yet and RPCs are queued.
   */
  @Nullable private Status clientError;

  /** Join of {@link #assignment} and {@link #endpointMap}; null only before the first update. */
  @Nullable private SliceMap sliceMap;

  private boolean shutdown;

  AutoShardingLoadBalancer(Helper helper) {
    this(
        helper,
        LoadBalancerRegistry.getDefaultRegistry().getProvider("pick_first"),
        new ExponentialBackoffPolicy.Provider(),
        GrpcUtil.STOPWATCH_SUPPLIER,
        UUID.randomUUID().toString());
  }

  /**
   * Constructs a load balancer with injectable collaborators.
   *
   * @param childProvider provides the per-endpoint child load balancer, {@code pick_first} in
   *     production. {@link EndpointMap} takes care of deferring its instantiation, so this must
   *     not be wrapped in a {@link io.grpc.util.LazyLoadBalancer.Factory} by the caller
   */
  @VisibleForTesting
  AutoShardingLoadBalancer(
      Helper helper,
      LoadBalancerProvider childProvider,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier,
      String clientUuid) {
    this.helper = checkNotNull(helper, "helper");
    this.syncContext = helper.getSynchronizationContext();
    this.timeService = helper.getScheduledExecutorService();
    this.childProvider = checkNotNull(childProvider, "childProvider");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
    this.clientUuid = checkNotNull(clientUuid, "clientUuid");
    this.endpointMap = new EndpointMap(helper, this.childProvider, this::onChildStateUpdate);
  }

  @Override
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    if (shutdown) {
      return Status.OK;
    }
    Object rawConfig = resolvedAddresses.getLoadBalancingPolicyConfig();
    if (!(rawConfig instanceof AutoShardingLoadBalancerConfig)) {
      // Without a config, publishPicker() publishes nothing, so a later child update cannot put
      // back a picker built from the previous one. The next valid config starts over as if it
      // were the first.
      config = null;
      return failPermanently("autosharding: missing or malformed load balancing configuration");
    }
    AutoShardingLoadBalancerConfig newConfig = (AutoShardingLoadBalancerConfig) rawConfig;

    // May be null, which updateShardingServiceChannel() treats as a failure to create the
    // channel.
    ChannelFactory factory =
        resolvedAddresses.getAttributes().get(AutoShardingAttributes.ATTR_CHANNEL_FACTORY);

    // gRFC A119 gives the configuration and the endpoints separate handling rules, and an empty
    // endpoint set only speaks to the latter. Everything below that is driven by comparing the
    // new configuration against the old one therefore has to run first: storing the new
    // configuration without acting on it would destroy the comparison, and the change would then
    // be lost for good, since the following update would no longer look like a change at all.
    List<EquivalentAddressGroup> endpoints = resolvedAddresses.getAddresses();

    Channel previousChannel = shardingChannel;
    ChannelFactory previousFactory = channelFactory;
    Status channelStatus = updateShardingServiceChannel(factory, newConfig);

    if (config == null || !config.keyHeaderName.equals(newConfig.keyHeaderName)) {
      keyHeader = AutoShardingPicker.createKeyHeader(newConfig.keyHeaderName);
    }
    config = newConfig;

    // When the set is empty this tears the children down, so that in-flight picks stop resolving
    // to endpoints the resolver has retracted.
    endpointMap.updateEndpoints(endpoints, resolvedAddresses.getAttributes());
    if (!endpoints.isEmpty()) {
      locality = sharedLocality(endpoints);
    }

    if (channelStatus.isOk()) {
      // The locality comes from the endpoints, so the target can change even when the config did
      // not.
      maybeRecreateClient(
          shardingChannel != previousChannel,
          resolveTarget(newConfig.autoshardingTarget, locality),
          newConfig.initialAssignmentTimeoutNanos);
    } else {
      // Handled like an error from the client: RPCs go to fallback or fail with this status.
      shutdownClient();
      assignment = null;
      clientError = channelStatus;
    }

    // Only now that the old stream has been cancelled. Release through the factory that produced
    // it, which is not necessarily the new one.
    if (previousChannel != null && previousChannel != shardingChannel) {
      previousFactory.releaseChannel(previousChannel);
    }

    if (endpoints.isEmpty()) {
      // Any assignment is kept: it stays valid if the endpoints come back. Until they do,
      // publishPicker() leaves this failure in place rather than publishing over it.
      return failPermanently("autosharding: name resolver returned no endpoints");
    }

    rebuildSliceMapAndPublish();
    // A failed channel is reported back too, so that the resolver refreshes and the next update
    // retries creating it.
    return channelStatus;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    if (shutdown) {
      return;
    }
    // Endpoints from an earlier resolution stay usable, and the error is
    // only reported when we are not already serving with them. Reporting it in that case is what
    // makes a broken resolver visible; otherwise RPCs would fail with whatever the stale
    // endpoints happen to be failing with, which names the wrong cause.
    // IDLE counts as serving: it is where a lazy policy rests before traffic, and IDLE endpoints
    // report nothing until picked, so a failing picker published then would never be replaced.
    // CONNECTING and TRANSIENT_FAILURE endpoints do report, which restores the picker.
    // The one addition is the wait for the sharding service: RPCs queue until a client reports
    // an assignment or an error, so a failed refresh must not turn that queue into failures.
    boolean queueingForAssignment = assignment == null && clientError == null;
    ConnectivityState state = endpointMap.aggregateConnectivityState();
    if (endpointMap.size() > 0
        && (queueingForAssignment
            || state == ConnectivityState.READY
            || state == ConnectivityState.IDLE)) {
      logger.log(Level.FINE, "Ignoring name resolution error, still serving: {0}", error);
      return;
    }
    helper.updateBalancingState(
        TRANSIENT_FAILURE,
        new FixedResultPicker(
            PickResult.withError(
                error.getCode() == Status.Code.OK
                    ? Status.UNAVAILABLE.withDescription("autosharding: name resolution failed")
                    : error)));
  }

  @Override
  public void requestConnection() {
    endpointMap.maybeWakeUpIdleEndpoint();
  }

  @Override
  public void shutdown() {
    if (shutdown) {
      return;
    }
    shutdown = true;
    shutdownClient();
    if (shardingChannel != null) {
      channelFactory.releaseChannel(shardingChannel);
      shardingChannel = null;
    }

    endpointMap.shutdown();
  }

  /**
   * Creates a channel to the sharding service if this is the first configuration update, if the
   * {@code channel_factory_key} or the factory itself changed, or if the previous attempt failed.
   * Leaves {@link #shardingChannel} untouched when nothing changed, which is how the caller
   * detects that no new channel was needed. On failure, including a missing factory, it is set to
   * null and the error returned. The previous channel is not released here: the caller does that
   * once the stream on it has been cancelled.
   */
  private Status updateShardingServiceChannel(
      @Nullable ChannelFactory factory, AutoShardingLoadBalancerConfig newConfig) {
    boolean keyChanged =
        config == null || !config.channelFactoryKey.equals(newConfig.channelFactoryKey);
    if (shardingChannel != null && factory == channelFactory && !keyChanged) {
      return Status.OK;
    }

    if (factory == null) {
      shardingChannel = null;
      channelFactory = null;
      return Status.UNAVAILABLE.withDescription(
          "autosharding: no channel factory supplied to the LB policy");
    }
    Channel newChannel;
    try {
      newChannel = factory.createChannel(newConfig.channelFactoryKey);
    } catch (RuntimeException e) {
      logger.log(Level.WARNING, "Failed to create a channel to the sharding service", e);
      shardingChannel = null;
      channelFactory = null;
      return Status.UNAVAILABLE
          .withDescription(
              "autosharding: channel factory rejected key '"
                  + newConfig.channelFactoryKey
                  + "': "
                  + e.getMessage())
          .withCause(e);
    }

    shardingChannel = newChannel;
    channelFactory = factory;
    return Status.OK;
  }

  /**
   * Replaces the {@link AutoshardingClient} when there is none yet, or when the channel to the
   * sharding service or the resolved target changed.
   *
   * <p>What gRFC A119 requires is a new channel and a new stream on it; whether the existing
   * client is handed the new channel or a new client is built around it is left open. We replace
   * the client because its accepted-generation watermark is only meaningful against the server
   * and the resource it was learned from; carrying it over could make a different server withhold
   * assignments indefinitely.
   *
   * <p>Each new client starts its own initial assignment timer. Whatever the previous client
   * reported keeps being served until the new one reports.
   */
  private void maybeRecreateClient(boolean channelChanged, String newTarget, long timeoutNanos) {
    if (client != null && !channelChanged && newTarget.equals(shardingTarget)) {
      return;
    }
    if (client != null) {
      client.shutdown();
    }
    shardingTarget = newTarget;
    client =
        new AutoshardingClient(
            clientUuid,
            syncContext,
            timeService,
            backoffPolicyProvider,
            stopwatchSupplier,
            shardingChannel,
            newTarget,
            timeoutNanos,
            new AssignmentWatcherImpl());
    client.start();
  }

  /**
   * Returns the locality every endpoint is in, or null if any endpoint has none or they differ.
   *
   * <p>Under a locality picker such as {@code weighted_target_experimental}, this policy only
   * receives the endpoints of its own locality, so they all agree; under xDS this yields the same
   * value that {@code weighted_target_experimental} publishes as its child name. When the policy
   * does its own locality picking, it is handed endpoints from every locality, which disagree and
   * yield null, so the result never depends on resolver ordering.
   */
  @Nullable
  private static String sharedLocality(List<EquivalentAddressGroup> endpoints) {
    String shared = null;
    for (EquivalentAddressGroup endpoint : endpoints) {
      String endpointLocality =
          endpoint.getAttributes().get(EquivalentAddressGroup.ATTR_LOCALITY_NAME);
      if (endpointLocality == null || (shared != null && !shared.equals(endpointLocality))) {
        return null;
      }
      shared = endpointLocality;
    }
    return shared;
  }

  /**
   * Substitutes the optional {@code %s} token in the configured target with the locality, or with
   * the empty string when there is none. gRFC A119 says the token is not meant to be used when
   * this policy does its own locality picking, which is the case that has no locality.
   */
  private static String resolveTarget(String configuredTarget, @Nullable String locality) {
    return configuredTarget.replace("%s", locality == null ? "" : locality);
  }

  private void shutdownClient() {
    if (client != null) {
      client.shutdown();
      client = null;
    }
    shardingTarget = null;
  }

  /**
   * Receives what the current {@link AutoshardingClient} reports. Both callbacks arrive on the
   * synchronization context.
   *
   * <p>A client that has been replaced cannot deliver anything, because {@link
   * AutoshardingClient#shutdown()} closes its stream and cancels its timer, so there is no need to
   * check which client a callback came from.
   */
  private final class AssignmentWatcherImpl implements AutoshardingClient.AssignmentWatcher {
    @Override
    public void onAssignment(Assignment newAssignment) {
      if (shutdown) {
        return;
      }
      assignment = newAssignment;
      clientError = null;
      rebuildSliceMapAndPublish();
    }

    @Override
    public void onError(Status error) {
      if (shutdown) {
        return;
      }
      // A client only reports errors before it has reported an assignment, so an assignment in
      // hand here came from a previous client. The new client's state replaces it.
      logger.log(
          Level.WARNING,
          "No assignment from the sharding service; proceeding {0} fallback: {1}",
          new Object[] {config != null && config.enableFallback ? "with" : "without", error});
      assignment = null;
      clientError = error;
      rebuildSliceMapAndPublish();
    }
  }

  /**
   * Called by {@link EndpointMap} when a child reports a new state or picker. The endpoint set
   * and the indices into it are unchanged, so the existing {@link SliceMap} still applies and
   * only the picker needs rebuilding.
   */
  private void onChildStateUpdate() {
    if (shutdown) {
      return;
    }
    publishPicker();
  }

  private void rebuildSliceMapAndPublish() {
    sliceMap = buildSliceMap();
    publishPicker();
  }

  /**
   * Joins the current assignment with the current endpoints, translating the assignment's
   * hostnames into endpoint indices. Hostnames the resolver has not given us are dropped, which
   * can leave a slice with no endpoints; the picker treats such a slice as being in fallback.
   *
   * <p>Before any assignment has been received the result has no slices, so every lookup misses
   * and the picker routes through the fallback pool or fails, according to configuration.
   */
  private SliceMap buildSliceMap() {
    int endpointCount = endpointMap.size();
    List<Integer> fallbackPool = new ArrayList<>(endpointCount);
    for (int i = 0; i < endpointCount; i++) {
      fallbackPool.add(i);
    }
    if (assignment == null) {
      return new SliceMap(ImmutableList.of(), fallbackPool);
    }

    ImmutableList<String> endpointNames = assignment.getEndpointNames();
    List<SliceMap.SliceEntry> entries = new ArrayList<>(assignment.getSlices().size());
    for (Assignment.Slice slice : assignment.getSlices()) {
      List<Integer> indices = new ArrayList<>(slice.getEndpoints().size());
      for (int nameIndex : slice.getEndpoints()) {
        int endpointIndex = endpointMap.indexOf(endpointNames.get(nameIndex));
        if (endpointIndex != -1) {
          indices.add(endpointIndex);
        }
      }
      entries.add(new SliceMap.SliceEntry(slice.getStartKey(), indices));
    }
    return new SliceMap(entries, fallbackPool);
  }

  private void publishPicker() {
    if (shutdown || config == null) {
      return;
    }
    if (endpointMap.size() == 0) {
      // acceptResolvedAddresses already reported TRANSIENT_FAILURE for this case.
      return;
    }
    ConnectivityState state = endpointMap.aggregateConnectivityState();
    if (assignment == null && clientError == null) {
      // RPCs are queued, but the state still follows the endpoints, so the policy starts IDLE
      // like any lazily-connecting one. Reporting CONNECTING instead would keep a parent
      // priority policy's failover timer running while the sharding service answers.
      helper.updateBalancingState(state, ASSIGNMENT_PENDING_PICKER);
    } else if (assignment == null && !config.enableFallback) {
      // No endpoint can be picked, so there is nothing to connect for either.
      helper.updateBalancingState(
          TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(clientError)));
      return;
    } else {
      helper.updateBalancingState(
          state,
          new AutoShardingPicker(
              sliceMap, endpointMap.toPickerEndpoints(), config.enableFallback, keyHeader));
    }

    // Nothing else will drive progress: this policy only connects in response to picks, so a
    // CONNECTING or TRANSIENT_FAILURE aggregate could otherwise stick with no attempt in flight.
    // The woken endpoint reports CONNECTING synchronously, re-entering publishPicker() once to
    // publish the fresher picker; that pass finds an endpoint CONNECTING and wakes no one else.
    // An all-IDLE aggregate is left alone, which is what keeps the policy lazy.
    if (state == CONNECTING || state == TRANSIENT_FAILURE) {
      endpointMap.maybeWakeUpIdleEndpoint();
    }
  }

  @VisibleForTesting
  EndpointMap getEndpointMap() {
    return endpointMap;
  }

  /**
   * Reports TRANSIENT_FAILURE with a picker that fails every RPC, and returns the same error for
   * {@link #acceptResolvedAddresses} to hand back to the channel.
   */
  private Status failPermanently(String description) {
    Status error = Status.UNAVAILABLE.withDescription(description);
    helper.updateBalancingState(TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(
        error)));
    return error;
  }
}
