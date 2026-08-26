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

import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.cloud.autosharding.v1main.PerSliceEndpointState;
import com.google.cloud.autosharding.v1main.SliceAssignment;
import io.grpc.Attributes;
import io.grpc.Channel;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.autosharding.AutoShardingLoadBalancerProvider.AutoShardingConfig;
import io.grpc.autosharding.EndpointMap.EndpointHolder;
import io.grpc.autosharding.SliceMap.SliceEntry;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AutoShardingLoadBalancer extends LoadBalancer {
  private static final Logger logger =
      Logger.getLogger(AutoShardingLoadBalancer.class.getName());

  /**
   * ChannelFactory is used to create a Channel to the sharding service.
   */
  public interface ChannelFactory {
    final class ChannelHolder implements AutoCloseable {
      private final Channel channel;
      private final Runnable releaseCallback;

      public ChannelHolder(Channel channel, Runnable releaseCallback) {
        this.channel = channel;
        this.releaseCallback = releaseCallback;
      }

      public Channel getChannel() {
        return channel;
      }

      @Override
      public void close() {
        if (releaseCallback != null) {
          releaseCallback.run();
        }
      }
    }

    ChannelHolder createChannel(String target);
  }

  public static final Attributes.Key<ChannelFactory> CHANNEL_FACTORY_KEY =
      Attributes.Key.create("io.grpc.autosharding.AutoShardingLoadBalancer.CHANNEL_FACTORY");

  public static final Attributes.Key<String> LOCALITY_KEY =
      Attributes.Key.create("io.grpc.autosharding.AutoShardingLoadBalancer.LOCALITY");

  private final Helper helper;
  private final SynchronizationContext syncContext;
  private final LoadBalancerProvider pickFirstProvider;

  private ChannelFactory.ChannelHolder shardingChannelHolder;
  private Channel shardingChannel;
  private ShardingClient shardingClient;
  private String currentChannelFactoryKey;
  private String currentAutoshardingTarget;

  private boolean fallbackEnabled = false;
  private String sliceKeyHeaderName = "";
  private long initialAssignmentTimeoutNanos = TimeUnit.SECONDS.toNanos(60);

  // Endpoint map: hostname -> EndpointHolder
  private final EndpointMap endpointMap = new EndpointMap();

  private SliceMap currentSliceMap;
  private List<SliceAssignment> latestSliceAssignments;
  private List<com.google.cloud.autosharding.v1main.EndpointState> latestEndpointsProto;
  private long latestGeneration = 0;

  private SynchronizationContext.ScheduledHandle fallbackTimer;
  private boolean fallbackTimerFired = false;

  public AutoShardingLoadBalancer(Helper helper) {
    this.helper = helper;
    this.syncContext = helper.getSynchronizationContext();
    this.pickFirstProvider =
        LoadBalancerRegistry.getDefaultRegistry().getProvider("pick_first");
  }

  @Override
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    AutoShardingConfig config =
        (AutoShardingConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    if (config == null) {
      return Status.INVALID_ARGUMENT.withDescription("Missing AutoShardingConfig");
    }

    this.fallbackEnabled = config.enableFallback;
    this.sliceKeyHeaderName = config.sliceKeyHeaderName;
    if (config.initialAssignmentTimeoutNanos != null) {
      this.initialAssignmentTimeoutNanos = config.initialAssignmentTimeoutNanos;
    }

    // Connect to sharding service if channelFactoryKey or autoshardingTarget changed
    if (shardingClient == null
        || !config.channelFactoryKey.equals(currentChannelFactoryKey)
        || !config.autoshardingTarget.equals(currentAutoshardingTarget)) {
      initShardingClient(
          resolvedAddresses.getAttributes(),
          config.channelFactoryKey,
          config.autoshardingTarget);
    }

    // Process endpoints from Name Resolver
    List<EquivalentAddressGroup> addresses = resolvedAddresses.getAddresses();
    if (addresses.isEmpty()) {
      endpointMap.shutdownAll();
      currentSliceMap = null;
      helper.updateBalancingState(
          TRANSIENT_FAILURE,
          new FixedResultPicker(PickResult.withError(
              Status.UNAVAILABLE.withDescription(
                  "NameResolver returned empty list of endpoints"))));
      return Status.OK;
    }

    // Group addresses by hostname
    Map<String, List<EquivalentAddressGroup>> groupedByHostname = new LinkedHashMap<>();
    for (EquivalentAddressGroup eag : addresses) {
      String hostname = getHostname(eag);
      List<EquivalentAddressGroup> eags = groupedByHostname.get(hostname);
      if (eags == null) {
        eags = new ArrayList<>();
        groupedByHostname.put(hostname, eags);
      }
      eags.add(eag);
    }

    Set<String> newHostnames = groupedByHostname.keySet();
    int index = 0;
    for (Map.Entry<String, List<EquivalentAddressGroup>> entry : groupedByHostname.entrySet()) {
      String hostname = entry.getKey();
      List<EquivalentAddressGroup> eags = entry.getValue();

      EndpointHolder holder = endpointMap.get(hostname);
      if (holder == null) {
        holder = new EndpointHolder(
            index, helper, pickFirstProvider, this::updateAggregatedState);
        endpointMap.put(hostname, holder);
      } else {
        holder.index = index;
      }
      holder.updateAddresses(eags, resolvedAddresses.getAttributes());
      index++;
    }

    // Remove obsolete endpoints
    List<String> toRemove = new ArrayList<>();
    for (String oldHost : endpointMap.keySet()) {
      if (!newHostnames.contains(oldHost)) {
        toRemove.add(oldHost);
      }
    }
    for (String host : toRemove) {
      EndpointHolder removed = endpointMap.remove(host);
      if (removed != null) {
        removed.shutdown();
      }
    }

    // Re-index remaining endpoints so indices form contiguous 0..N-1
    endpointMap.reindex();

    // Build slice map if assignment received or if timer has fired
    if (latestSliceAssignments != null || fallbackTimerFired) {
      rebuildSliceMap();
    }
    updateAggregatedState();
    return Status.OK;
  }

  private void closeShardingChannel() {
    if (shardingClient != null) {
      shardingClient.stop();
      shardingClient = null;
    }
    if (shardingChannelHolder != null) {
      try {
        shardingChannelHolder.close();
      } catch (Exception e) {
        logger.log(Level.WARNING, "Error closing sharding channel", e);
      }
      shardingChannelHolder = null;
    }
    shardingChannel = null;
  }

  private void initShardingClient(
      Attributes attributes, String channelFactoryKey, String autoshardingTarget) {
    closeShardingChannel();
    if (fallbackTimer != null) {
      fallbackTimer.cancel();
      fallbackTimer = null;
    }

    currentChannelFactoryKey = channelFactoryKey;
    currentAutoshardingTarget = autoshardingTarget;

    ChannelFactory factory = attributes.get(CHANNEL_FACTORY_KEY);
    if (factory == null) {
      logger.log(
          Level.WARNING,
          "No ChannelFactory attribute provided to AutoShardingLoadBalancer");
      return;
    }

    String locality = attributes.get(LOCALITY_KEY);
    if (locality == null) {
      locality = "";
    }
    String actualTarget = autoshardingTarget.replace("%s", locality);

    shardingChannelHolder = factory.createChannel(channelFactoryKey);
    if (shardingChannelHolder == null || shardingChannelHolder.getChannel() == null) {
      logger.log(
          Level.WARNING,
          "ChannelFactory returned null channel for target {0}",
          channelFactoryKey);
      return;
    }
    shardingChannel = shardingChannelHolder.getChannel();
    shardingClient =
        new ShardingClient(
            shardingChannel,
            actualTarget,
            latestGeneration,
            syncContext,
            helper.getScheduledExecutorService(),
            new ShardingCallback());
    shardingClient.start();

    // Start fallback-at-startup timer
    fallbackTimerFired = false;
    fallbackTimer = syncContext.schedule(
        this::onFallbackTimerExpired,
        initialAssignmentTimeoutNanos,
        TimeUnit.NANOSECONDS,
        helper.getScheduledExecutorService());
  }

  private void onFallbackTimerExpired() {
    fallbackTimerFired = true;
    fallbackTimer = null;
    logger.log(Level.WARNING, "Initial assignment timeout expired. Entering fallback mode.");
    latestSliceAssignments = null;
    latestEndpointsProto = null;
    latestGeneration = 0;
    rebuildSliceMap();
    updateAggregatedState();
  }

  private void rebuildSliceMap() {
    if (endpointMap.isEmpty()) {
      currentSliceMap = null;
      return;
    }

    // Populate fallback_pool deterministically sorted by endpoint index
    List<Integer> fallbackPool = new ArrayList<>();
    for (int i = 0; i < endpointMap.size(); i++) {
      fallbackPool.add(i);
    }

    // If no assignment received yet (startup case), return early with empty slices
    if (latestSliceAssignments == null || latestEndpointsProto == null) {
      currentSliceMap = new SliceMap(Collections.emptyList(), fallbackPool, 0);
      return;
    }

    List<SliceEntry> sliceEntries = new ArrayList<>();
    for (SliceAssignment protoSlice : latestSliceAssignments) {
      List<Integer> sliceEndpoints = new ArrayList<>();
      for (PerSliceEndpointState perSliceEp : protoSlice.getEndpointsList()) {
        int protoEpIdx = perSliceEp.getEndpointIndex();
        if (protoEpIdx >= 0 && protoEpIdx < latestEndpointsProto.size()) {
          String hostname = latestEndpointsProto.get(protoEpIdx).getEndpoint();
          EndpointHolder holder = endpointMap.get(hostname);
          if (holder != null) {
            sliceEndpoints.add(holder.index);
          }
        }
      }
      sliceEntries.add(
          new SliceEntry(
              protoSlice.getSlice().getStartKeyInclusive().toByteArray(), sliceEndpoints));
    }

    currentSliceMap = new SliceMap(sliceEntries, fallbackPool, latestGeneration);
  }

  private void updateAggregatedState() {
    if (endpointMap.isEmpty()) {
      helper.updateBalancingState(
          TRANSIENT_FAILURE,
          new FixedResultPicker(PickResult.withError(
              Status.UNAVAILABLE.withDescription(
                  "NameResolver returned empty list of endpoints"))));
      return;
    }

    int readyCount = 0;
    int tfCount = 0;
    int connectingCount = 0;
    int idleCount = 0;

    EndpointHolder firstIdle = null;

    for (EndpointHolder holder : endpointMap.values()) {
      ConnectivityState state = holder.state;
      if (state == READY) {
        readyCount++;
      } else if (state == TRANSIENT_FAILURE) {
        tfCount++;
      } else if (state == CONNECTING) {
        connectingCount++;
      } else if (state == IDLE) {
        idleCount++;
        if (firstIdle == null) {
          firstIdle = holder;
        }
      }
    }

    ConnectivityState aggregated;
    int total = endpointMap.size();

    if (readyCount > 0) {
      aggregated = READY;
    } else if (tfCount >= 2) {
      aggregated = TRANSIENT_FAILURE;
    } else if (connectingCount > 0) {
      aggregated = CONNECTING;
    } else if (tfCount == 1 && total > 1) {
      aggregated = CONNECTING;
    } else if (idleCount > 0) {
      aggregated = IDLE;
    } else {
      aggregated = TRANSIENT_FAILURE;
    }

    // gRFC A119 heuristic: ensure at least one IDLE endpoint starts connecting
    // if aggregated state is CONNECTING or TRANSIENT_FAILURE and none are CONNECTING
    if ((aggregated == CONNECTING || aggregated == TRANSIENT_FAILURE)
        && connectingCount == 0 && firstIdle != null) {
      firstIdle.requestConnection();
    }

    SubchannelPicker picker;
    if (currentSliceMap != null) {
      List<PickerEndpoint> pickerEndpoints =
          new ArrayList<>(Collections.nCopies(endpointMap.size(), null));
      for (EndpointHolder holder : endpointMap.values()) {
        pickerEndpoints.set(
            holder.index,
            new PickerEndpoint(
                holder.state,
                holder.picker,
                () -> syncContext.execute(holder::requestConnection)));
      }
      picker = new AutoShardingPicker(
          currentSliceMap, pickerEndpoints, fallbackEnabled, sliceKeyHeaderName);
    } else {
      picker = new FixedResultPicker(
          PickResult.withNoResult(
              "autosharding_assignment_pending", "Waiting for initial sharding assignment"));
    }

    helper.updateBalancingState(aggregated, picker);
  }

  @Override
  public void handleNameResolutionError(Status error) {
    helper.updateBalancingState(
        TRANSIENT_FAILURE,
        new FixedResultPicker(PickResult.withError(error)));
  }

  @Override
  public void shutdown() {
    closeShardingChannel();
    if (fallbackTimer != null) {
      fallbackTimer.cancel();
      fallbackTimer = null;
    }
    endpointMap.shutdownAll();
  }

  private static String getHostname(EquivalentAddressGroup eag) {
    String hostname = eag.getAttributes().get(EquivalentAddressGroup.ATTR_AUTHORITY_OVERRIDE);
    if (hostname != null && !hostname.isEmpty()) {
      return hostname;
    }
    SocketAddress address = eag.getAddresses().get(0);
    if (address instanceof InetSocketAddress) {
      return ((InetSocketAddress) address).getHostString();
    }
    return address.toString();
  }

  private final class ShardingCallback implements ShardingClient.Callback {
    @Override
    public void onAssignmentReceived(
        List<SliceAssignment> sliceAssignments,
        List<com.google.cloud.autosharding.v1main.EndpointState> endpoints,
        long generation) {
      if (fallbackTimer != null) {
        fallbackTimer.cancel();
        fallbackTimer = null;
      }
      latestSliceAssignments = sliceAssignments;
      latestEndpointsProto = endpoints;
      latestGeneration = generation;
      rebuildSliceMap();
      updateAggregatedState();
    }

    @Override
    public void onError(Throwable t) {
      logger.log(Level.WARNING, "ShardingClient stream error", t);
    }
  }
}
