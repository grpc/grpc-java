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
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusOr;
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
 * The {@code autosharding_experimental} load balancing policy. Shards RPCs across endpoints by an
 * application-defined key carried in a request header, using key ranges streamed from an external
 * sharding service. See gRFC A119.
 *
 * <p>Until its {@link AutoshardingClient} reports an assignment or an error, the policy queues
 * RPCs and reports IDLE. After an error, RPCs go to any resolved endpoint or fail, depending on
 * {@code enable_fallback}.
 *
 * <h3>Threading model</h3>
 *
 * <p>Every method, and every callback from the client and from the endpoints' child policies,
 * runs on the helper's {@link SynchronizationContext}, so the fields need no locking. Published
 * pickers run on RPC threads; they read only immutable snapshots and hand connection requests to
 * the synchronization context.
 */
final class AutoShardingLoadBalancer extends LoadBalancer {
  private static final Logger logger =
      Logger.getLogger(AutoShardingLoadBalancer.class.getName());

  /**
   * Published while waiting for the first assignment. The delay type is consumed by gRFC A121,
   * RPC Delay Observability.
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

  /** One lazily-created {@code pick_first} child per resolved endpoint, each with a dense index. */
  private final EndpointMap endpointMap;

  @Nullable private AutoShardingLoadBalancerConfig config;

  /**
   * The client streaming assignments, and the lease on the channel it streams on. Null until an
   * update is accepted, and again after one is rejected.
   */
  @Nullable private ShardingConnection shardingConnection;

  /**
   * Set when an update is rejected, or when the name resolver reports an error while no update is
   * in effect; cleared when an update is accepted. While set there is no client, and every RPC
   * fails with this error.
   */
  @Nullable private Status resolverError;

  /**
   * The last assignment or error reported by an {@link AutoshardingClient}. Survives client
   * replacement, so what the previous client reported stays in use until the new one reports.
   * Null until a client reports, which queues RPCs, and cleared when an update is rejected.
   */
  @Nullable private StatusOr<Assignment> assignmentOrError;

  /**
   * The key ranges of the assignment in {@link #assignmentOrError}, with the hostnames it names
   * endpoints by translated into indices into {@link #endpointMap}. Rebuilt whenever either
   * changes; null only before the first update.
   */
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
    // Null when this policy is the channel's default and there is no service config.
    Object rawConfig = resolvedAddresses.getLoadBalancingPolicyConfig();
    if (rawConfig == null) {
      return rejectUpdate("autosharding: no load balancing config");
    }
    AutoShardingLoadBalancerConfig newConfig = (AutoShardingLoadBalancerConfig) rawConfig;

    // The endpoints are applied even when a check below rejects the update. When the set is empty
    // this tears the children down, so that in-flight picks stop resolving to endpoints the
    // resolver has retracted.
    List<EquivalentAddressGroup> endpoints = resolvedAddresses.getAddresses();
    endpointMap.updateEndpoints(endpoints, resolvedAddresses.getAttributes());

    if (endpointMap.size() == 0) {
      return rejectUpdate("autosharding: name resolver returned no endpoints");
    }
    ChannelFactory factory =
        resolvedAddresses.getAttributes().get(AutoShardingAttributes.ATTR_CHANNEL_FACTORY);
    if (factory == null) {
      return rejectUpdate("autosharding: no channel factory supplied to the LB policy");
    }

    // Decide what changes. The key identifies the channel, so a different factory under the same
    // key changes nothing. The target depends on the locality of the endpoints, so it can change
    // while the configuration does not. With no client, as before the first update or after a
    // rejected one, both are new.
    boolean channelFactoryKeyChanged =
        shardingConnection == null
            || !config.getChannelFactoryKey().equals(newConfig.getChannelFactoryKey());
    String target = resolveTarget(newConfig.getAutoshardingTarget(), sharedLocality(endpoints));
    boolean targetChanged =
        shardingConnection == null || !target.equals(shardingConnection.client.getTarget());

    // Then apply it. The new client starts before the previous one is shut down, and the previous
    // channel is closed last, so no client is ever left streaming on a closed channel.
    resolverError = null;
    config = newConfig;
    if (channelFactoryKeyChanged || targetChanged) {
      ShardingConnection previous = shardingConnection;
      ChannelFactory.ChannelLease channelLease =
          channelFactoryKeyChanged
              ? leaseChannel(factory, newConfig.getChannelFactoryKey())
              : previous.channelLease;
      AutoshardingClient client =
          startClient(channelLease.channel(), target, newConfig.getInitialAssignmentTimeoutNanos());
      shardingConnection = new ShardingConnection(channelLease, client);
      if (previous != null) {
        previous.client.shutdown();
        if (channelFactoryKeyChanged) {
          previous.channelLease.close();
        }
      }
    }
    // The slice map indexes into the endpoints, so it is rebuilt with them.
    sliceMap = buildSliceMap();
    publishPicker();
    return Status.OK;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    if (shutdown) {
      return;
    }
    Status failure =
        error.isOk()
            ? Status.UNAVAILABLE.withDescription("autosharding: name resolution failed")
            : error;
    if (shardingConnection == null) {
      // No update is in effect, so there is nothing to serve with. As after a rejected update,
      // RPCs fail with the error until an update is accepted.
      resolverError = failure;
      helper.updateBalancingState(
          TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(failure)));
      return;
    }
    if (isStillServing()) {
      logger.log(Level.FINE, "Ignoring name resolution error, still serving: {0}", error);
      return;
    }
    // Not serving, so the resolver's error names the cause better than the endpoints' errors.
    helper.updateBalancingState(
        TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(failure)));
  }

  /**
   * Whether RPCs are being served with the endpoints from an earlier resolution, so a resolver
   * error can be ignored. Waiting for the sharding service counts: RPCs are queued for it, not
   * failing. So does IDLE: IDLE endpoints report nothing until picked, so a failing picker
   * published then would never be replaced.
   */
  private boolean isStillServing() {
    if (assignmentOrError == null) {
      return true;
    }
    ConnectivityState state = endpointMap.aggregateConnectivityState();
    return state == READY || state == IDLE;
  }

  @Override
  public void requestConnection() {
    // Nothing to do: picks connect the endpoint they choose, and child and resolver updates wake
    // one when needed.
  }

  @Override
  public void shutdown() {
    if (shutdown) {
      return;
    }
    shutdown = true;
    closeShardingConnection();

    endpointMap.shutdown();
  }

  /**
   * Leases a channel to the sharding service, or returns a {@link LameChannel} carrying the error
   * if the factory throws.
   */
  private static ChannelFactory.ChannelLease leaseChannel(
      ChannelFactory factory, String channelFactoryKey) {
    try {
      return factory.createChannel(channelFactoryKey);
    } catch (RuntimeException e) {
      logger.log(Level.WARNING, "Failed to create a channel to the sharding service", e);
      return new LameChannel(
          Status.UNAVAILABLE
              .withDescription(
                  "autosharding: channel factory rejected key '"
                      + channelFactoryKey
                      + "': "
                      + e.getMessage())
              .withCause(e));
    }
  }

  /**
   * Creates and starts an {@link AutoshardingClient}. Every target or channel change gets a new
   * client, so that an accepted generation never carries over to another server or target.
   */
  private AutoshardingClient startClient(
      Channel channel, String target, long initialAssignmentTimeoutNanos) {
    AutoshardingClient client =
        new AutoshardingClient(
            clientUuid,
            syncContext,
            timeService,
            backoffPolicyProvider,
            stopwatchSupplier,
            channel,
            target,
            initialAssignmentTimeoutNanos,
            new AssignmentWatcherImpl());
    client.start();
    return client;
  }

  /**
   * Returns the locality every endpoint is in, or null if any endpoint has none or they differ.
   * Under xDS this is the {@code weighted_target_experimental} child name.
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
   * Replaces the first {@code %s} in the configured target with the locality, or with the empty
   * string when there is none.
   */
  private static String resolveTarget(String configuredTarget, @Nullable String locality) {
    int token = configuredTarget.indexOf("%s");
    if (token == -1) {
      return configuredTarget;
    }
    return configuredTarget.substring(0, token)
        + (locality == null ? "" : locality)
        + configuredTarget.substring(token + 2);
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
      assignmentOrError = StatusOr.fromValue(newAssignment);
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
          new Object[] {config != null && config.isFallbackEnabled() ? "with" : "without", error});
      assignmentOrError = StatusOr.fromStatus(error);
      rebuildSliceMapAndPublish();
    }
  }

  /**
   * Called by {@link EndpointMap} when a child reports a new state or picker. The endpoint set
   * and the indices into it are unchanged, so the existing {@link SliceMap} still applies and
   * only the picker needs rebuilding.
   */
  private void onChildStateUpdate() {
    publishPicker();
  }

  private void rebuildSliceMapAndPublish() {
    sliceMap = buildSliceMap();
    publishPicker();
  }

  /**
   * Translates the endpoint names in the current assignment into indices into
   * {@link #endpointMap}, dropping names the resolver has not supplied. Without an assignment the
   * result has no slices, so every lookup misses and RPCs use the fallback pool.
   */
  private SliceMap buildSliceMap() {
    int endpointCount = endpointMap.size();
    List<Integer> fallbackPool = new ArrayList<>(endpointCount);
    for (int i = 0; i < endpointCount; i++) {
      fallbackPool.add(i);
    }
    if (assignmentOrError == null || !assignmentOrError.hasValue()) {
      return new SliceMap(ImmutableList.of(), fallbackPool);
    }

    Assignment assignment = assignmentOrError.getValue();
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
      entries.add(
          new SliceMap.SliceEntry(slice.getStartKey(), indices, slice.isFallbackDisabled()));
    }
    return new SliceMap(entries, fallbackPool);
  }

  private void publishPicker() {
    // Before the first accepted update there is nothing to publish. After a rejected one, or a
    // resolver error with no update in effect, the TRANSIENT_FAILURE already reported stands
    // until an update is accepted.
    if (shutdown || config == null || resolverError != null) {
      return;
    }
    if (assignmentOrError == null) {
      // RPCs are queued until a client reports, so the state is IDLE whatever the endpoints
      // report. Reporting CONNECTING instead would keep a parent priority policy's failover
      // timer running while the sharding service answers.
      helper.updateBalancingState(IDLE, ASSIGNMENT_PENDING_PICKER);
      return;
    }
    if (!assignmentOrError.hasValue() && !config.isFallbackEnabled()) {
      // No endpoint can be picked, so there is nothing to connect for either.
      helper.updateBalancingState(
          TRANSIENT_FAILURE,
          new FixedResultPicker(PickResult.withError(assignmentOrError.getStatus())));
      return;
    }
    helper.updateBalancingState(
        endpointMap.aggregateConnectivityState(),
        new AutoShardingPicker(
            sliceMap,
            endpointMap.toPickerEndpoints(),
            config.isFallbackEnabled(),
            config.getKeyHeader()));
    endpointMap.maybeWakeUpIdleEndpoint();
  }

  @VisibleForTesting
  EndpointMap getEndpointMap() {
    return endpointMap;
  }

  /**
   * Rejects an update the policy cannot use, returning the error for {@link
   * #acceptResolvedAddresses} to hand back to the channel. Every RPC fails with it until an update
   * is accepted. The client is shut down, its channel lease closed and what it reported dropped,
   * so the next accepted update starts afresh, queueing RPCs until its client reports.
   */
  private Status rejectUpdate(String description) {
    resolverError = Status.UNAVAILABLE.withDescription(description);
    closeShardingConnection();
    assignmentOrError = null;
    sliceMap = buildSliceMap();
    helper.updateBalancingState(
        TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(resolverError)));
    return resolverError;
  }

  /** Shuts down the client, then closes the lease on the channel it streamed on. */
  private void closeShardingConnection() {
    if (shardingConnection != null) {
      shardingConnection.client.shutdown();
      shardingConnection.channelLease.close();
      shardingConnection = null;
    }
  }

  /** An {@link AutoshardingClient} and the lease on the channel it streams on. */
  private static final class ShardingConnection {
    final ChannelFactory.ChannelLease channelLease;
    final AutoshardingClient client;

    ShardingConnection(ChannelFactory.ChannelLease channelLease, AutoshardingClient client) {
      this.channelLease = channelLease;
      this.client = client;
    }
  }

  /**
   * A channel whose every call fails with a fixed status. Stands in for a channel to the sharding
   * service that the {@link ChannelFactory} failed to create, so that the client reports the
   * failure like any other error from the sharding service. No factory created it, so it is its
   * own lease and closing it does nothing.
   */
  private static final class LameChannel extends Channel implements ChannelFactory.ChannelLease {
    private final Status error;

    LameChannel(Status error) {
      this.error = error;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions) {
      return new ClientCall<ReqT, RespT>() {
        @Override
        public void start(Listener<RespT> listener, Metadata headers) {
          listener.onClose(error, new Metadata());
        }

        @Override
        public void request(int numMessages) {}

        @Override
        public void cancel(@Nullable String message, @Nullable Throwable cause) {}

        @Override
        public void halfClose() {}

        @Override
        public void sendMessage(ReqT message) {}
      };
    }

    @Override
    public String authority() {
      return "lame-channel";
    }

    @Override
    public Channel channel() {
      return this;
    }

    @Override
    public void close() {}
  }
}
