/*
 * Copyright 2017 The gRPC Authors
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

package io.grpc.grpclb;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Stopwatch;
import com.google.protobuf.util.Durations;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.Context;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.grpclb.SubchannelPool.PooledSubchannelStateListener;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.TimeProvider;
import io.grpc.lb.v1.ClientStats;
import io.grpc.lb.v1.InitialLoadBalanceRequest;
import io.grpc.lb.v1.InitialLoadBalanceResponse;
import io.grpc.lb.v1.LoadBalanceRequest;
import io.grpc.lb.v1.LoadBalanceResponse;
import io.grpc.lb.v1.LoadBalanceResponse.LoadBalanceResponseTypeCase;
import io.grpc.lb.v1.LoadBalancerGrpc;
import io.grpc.lb.v1.Server;
import io.grpc.lb.v1.ServerList;
import io.grpc.stub.StreamObserver;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * The states of a GRPCLB working session of {@link GrpclbLoadBalancer}.  Created when
 * GrpclbLoadBalancer switches to GRPCLB mode.  Closed and discarded when GrpclbLoadBalancer
 * switches away from GRPCLB mode.
 */
@NotThreadSafe
final class GrpclbState {
  static final long FALLBACK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);
  private static final Attributes LB_PROVIDED_BACKEND_ATTRS =
      Attributes.newBuilder().set(GrpclbConstants.ATTR_LB_PROVIDED_BACKEND, true).build();

  // Temporary workaround to reduce log spam for a grpclb server that incessantly sends updates
  // Tracked by b/198440401
  static final boolean SHOULD_LOG_SERVER_LISTS =
      Boolean.parseBoolean(System.getProperty("io.grpc.grpclb.LogServerLists", "true"));

  @VisibleForTesting
  static final PickResult DROP_PICK_RESULT =
      PickResult.withDrop(Status.UNAVAILABLE.withDescription("Dropped as requested by balancer"));
  @VisibleForTesting
  static final Status NO_AVAILABLE_BACKENDS_STATUS =
      Status.UNAVAILABLE.withDescription("LoadBalancer responded without any backends");
  @VisibleForTesting
  static final Status BALANCER_TIMEOUT_STATUS =
      Status.UNAVAILABLE.withDescription("Timeout waiting for remote balancer");
  @VisibleForTesting
  static final Status BALANCER_REQUESTED_FALLBACK_STATUS =
      Status.UNAVAILABLE.withDescription("Fallback requested by balancer");
  @VisibleForTesting
  static final Status NO_FALLBACK_BACKENDS_STATUS =
      Status.UNAVAILABLE.withDescription("Unable to fallback, no fallback addresses found");
  // This error status should never be propagated to RPC failures, as "no backend or balancer
  // addresses found" should be directly handled as a name resolution error. So in cases of no
  // balancer address, fallback should never fail.
  private static final Status NO_LB_ADDRESS_PROVIDED_STATUS =
      Status.UNAVAILABLE.withDescription("No balancer address found");


  @VisibleForTesting
  static final RoundRobinEntry BUFFER_ENTRY = new RoundRobinEntry() {
      @Override
      public PickResult picked(Metadata headers) {
        return PickResult.withNoResult();
      }

      @Override
      public String toString() {
        return "BUFFER_ENTRY";
      }
    };
  @VisibleForTesting
  static final String NO_USE_AUTHORITY_SUFFIX = "-notIntendedToBeUsed";

  enum Mode {
    ROUND_ROBIN,
    PICK_FIRST,
  }

  private final String serviceName;
  private final long fallbackTimeoutMs;
  private final Helper helper;
  private final Context context;
  private final SynchronizationContext syncContext;
  @Nullable
  private final SubchannelPool subchannelPool;
  private final TimeProvider time;
  private final Stopwatch stopwatch;
  private final ScheduledExecutorService timerService;

  private static final Attributes.Key<AtomicReference<ConnectivityStateInfo>> STATE_INFO =
      Attributes.Key.create("io.grpc.grpclb.GrpclbLoadBalancer.stateInfo");
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final ChannelLogger logger;

  // Scheduled only once.  Never reset.
  @Nullable
  private ScheduledHandle fallbackTimer;
  private List<EquivalentAddressGroup> fallbackBackendList = Collections.emptyList();
  private boolean usingFallbackBackends;
  // Reason to fallback, will be used as RPC's error message if fail to fallback (e.g., no
  // fallback addresses found).
  @Nullable
  private Status fallbackReason;
  // True if the current balancer has returned a serverlist.  Will be reset to false when lost
  // connection to a balancer.
  private boolean balancerWorking;
  @Nullable
  private BackoffPolicy lbRpcRetryPolicy;
  @Nullable
  private ScheduledHandle lbRpcRetryTimer;

  @Nullable
  private ManagedChannel lbCommChannel;

  @Nullable
  private LbStream lbStream;
  private Map<List<EquivalentAddressGroup>, Subchannel> subchannels = Collections.emptyMap();
  private final GrpclbConfig config;

  // Has the same size as the round-robin list from the balancer.
  // A drop entry from the round-robin list becomes a DropEntry here.
  // A backend entry from the robin-robin list becomes a null here.
  private List<DropEntry> dropList = Collections.emptyList();
  // Contains only non-drop, i.e., backends from the round-robin list from the balancer.
  private List<BackendEntry> backendList = Collections.emptyList();
  private RoundRobinPicker currentPicker =
      new RoundRobinPicker(Collections.<DropEntry>emptyList(), Arrays.asList(BUFFER_ENTRY));
  private boolean requestConnectionPending;

  GrpclbState(
      GrpclbConfig config,
      Helper helper,
      Context context,
      SubchannelPool subchannelPool,
      TimeProvider time,
      Stopwatch stopwatch,
      BackoffPolicy.Provider backoffPolicyProvider) {
    this.config = checkNotNull(config, "config");
    this.helper = checkNotNull(helper, "helper");
    this.context = checkNotNull(context, "context");
    this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    if (config.getMode() == Mode.ROUND_ROBIN) {
      this.subchannelPool = checkNotNull(subchannelPool, "subchannelPool");
      subchannelPool.registerListener(
          new PooledSubchannelStateListener() {
            @Override
            public void onSubchannelState(
                Subchannel subchannel, ConnectivityStateInfo newState) {
              handleSubchannelState(subchannel, newState);
            }
          });
    } else {
      this.subchannelPool = null;
    }
    this.time = checkNotNull(time, "time provider");
    this.stopwatch = checkNotNull(stopwatch, "stopwatch");
    this.timerService = checkNotNull(helper.getScheduledExecutorService(), "timerService");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    if (config.getServiceName() != null) {
      this.serviceName = config.getServiceName();
    } else {
      this.serviceName = checkNotNull(helper.getAuthority(), "helper returns null authority");
    }
    this.fallbackTimeoutMs = config.getFallbackTimeoutMs();
    this.logger = checkNotNull(helper.getChannelLogger(), "logger");
    logger.log(ChannelLogLevel.INFO, "[grpclb-<{0}>] Created", serviceName);
  }

  void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
    if (newState.getState() == SHUTDOWN || !subchannels.containsValue(subchannel)) {
      return;
    }
    if (config.getMode() == Mode.ROUND_ROBIN && newState.getState() == IDLE) {
      subchannel.requestConnection();
    }
    if (newState.getState() == TRANSIENT_FAILURE || newState.getState() == IDLE) {
      helper.refreshNameResolution();
    }

    AtomicReference<ConnectivityStateInfo> stateInfoRef =
        subchannel.getAttributes().get(STATE_INFO);
    // If all RR servers are unhealthy, it's possible that at least one connection is CONNECTING at
    // every moment which causes RR to stay in CONNECTING. It's better to keep the TRANSIENT_FAILURE
    // state in that case so that fail-fast RPCs can fail fast.
    boolean keepState =
        config.getMode() == Mode.ROUND_ROBIN
        && stateInfoRef.get().getState() == TRANSIENT_FAILURE
        && (newState.getState() == CONNECTING || newState.getState() == IDLE);
    if (!keepState) {
      stateInfoRef.set(newState);
      maybeUseFallbackBackends();
      maybeUpdatePicker();
    }
  }

  /**
   * Handle new addresses of the balancer and backends from the resolver, and create connection if
   * not yet connected.
   */
  void handleAddresses(
      List<EquivalentAddressGroup> newLbAddressGroups,
      List<EquivalentAddressGroup> newBackendServers) {
    logger.log(
        ChannelLogLevel.DEBUG,
        "[grpclb-<{0}>] Resolved addresses: lb addresses {1}, backends: {2}",
        serviceName,
        newLbAddressGroups,
        newBackendServers);
    fallbackBackendList = newBackendServers;
    if (newLbAddressGroups.isEmpty()) {
      // No balancer address: close existing balancer connection and prepare to enter fallback
      // mode. If there is no successful backend connection, it enters fallback mode immediately.
      // Otherwise, fallback does not happen until backend connections are lost. This behavior
      // might be different from other languages (e.g., existing balancer connection is not
      // closed in C-core), but we aren't changing it at this time.
      shutdownLbComm();
      if (!usingFallbackBackends) {
        fallbackReason = NO_LB_ADDRESS_PROVIDED_STATUS;
        cancelFallbackTimer();
        maybeUseFallbackBackends();
      }
    } else {
      startLbComm(newLbAddressGroups);
      // Avoid creating a new RPC just because the addresses were updated, as it can cause a
      // stampeding herd. The current RPC may be on a connection to an address not present in
      // newLbAddressGroups, but we're considering that "okay". If we detected the RPC is to an
      // outdated backend, we could choose to re-create the RPC.
      if (lbStream == null) {
        cancelLbRpcRetryTimer();
        startLbRpc();
      }
      // Start the fallback timer if it's never started and we are not already using fallback
      // backends.
      if (fallbackTimer == null && !usingFallbackBackends) {
        fallbackTimer =
            syncContext.schedule(
                new FallbackModeTask(BALANCER_TIMEOUT_STATUS),
                fallbackTimeoutMs,
                TimeUnit.MILLISECONDS,
                timerService);
      }
    }
    if (usingFallbackBackends) {
      // Populate the new fallback backends to round-robin list.
      useFallbackBackends();
    }
    maybeUpdatePicker();
  }

  void requestConnection() {
    requestConnectionPending = true;
    for (RoundRobinEntry entry : currentPicker.pickList) {
      if (entry instanceof IdleSubchannelEntry) {
        ((IdleSubchannelEntry) entry).subchannel.requestConnection();
        requestConnectionPending = false;
      }
    }
  }

  private void maybeUseFallbackBackends() {
    if (balancerWorking || usingFallbackBackends) {
      return;
    }
    // Balancer RPC should have either been broken or timed out.
    checkState(fallbackReason != null, "no reason to fallback");
    for (Subchannel subchannel : subchannels.values()) {
      ConnectivityStateInfo stateInfo = subchannel.getAttributes().get(STATE_INFO).get();
      if (stateInfo.getState() == READY) {
        return;
      }
      // If we do have balancer-provided backends, use one of its error in the error message if
      // fail to fallback.
      if (stateInfo.getState() == TRANSIENT_FAILURE) {
        fallbackReason = stateInfo.getStatus();
      }
    }
    // Fallback conditions met
    useFallbackBackends();
  }

  /**
   * Populate backend servers to be used from the fallback backends.
   */
  private void useFallbackBackends() {
    usingFallbackBackends = true;
    logger.log(ChannelLogLevel.INFO, "[grpclb-<{0}>] Using fallback backends", serviceName);

    List<DropEntry> newDropList = new ArrayList<>();
    List<BackendAddressGroup> newBackendAddrList = new ArrayList<>();
    for (EquivalentAddressGroup eag : fallbackBackendList) {
      newDropList.add(null);
      newBackendAddrList.add(new BackendAddressGroup(eag, null));
    }
    updateServerList(newDropList, newBackendAddrList, null);
  }

  private void shutdownLbComm() {
    if (lbCommChannel != null) {
      lbCommChannel.shutdown();
      lbCommChannel = null;
    }
    shutdownLbRpc();
  }

  private void shutdownLbRpc() {
    if (lbStream != null) {
      lbStream.close(Status.CANCELLED.withDescription("balancer shutdown").asException());
      // lbStream will be set to null in LbStream.cleanup()
    }
  }

  private void startLbComm(List<EquivalentAddressGroup> overrideAuthorityEags) {
    checkNotNull(overrideAuthorityEags, "overrideAuthorityEags");
    assert !overrideAuthorityEags.isEmpty();
    String doNotUseAuthority = overrideAuthorityEags.get(0).getAttributes()
        .get(EquivalentAddressGroup.ATTR_AUTHORITY_OVERRIDE) + NO_USE_AUTHORITY_SUFFIX;
    if (lbCommChannel == null) {
      lbCommChannel = helper.createOobChannel(overrideAuthorityEags, doNotUseAuthority);
      logger.log(
          ChannelLogLevel.DEBUG,
          "[grpclb-<{0}>] Created grpclb channel: EAG={1}",
          serviceName,
          overrideAuthorityEags);
    } else {
      helper.updateOobChannelAddresses(lbCommChannel, overrideAuthorityEags);
    }
  }

  private void startLbRpc() {
    checkState(lbStream == null, "previous lbStream has not been cleared yet");
    LoadBalancerGrpc.LoadBalancerStub stub = LoadBalancerGrpc.newStub(lbCommChannel);
    lbStream = new LbStream(stub);
    Context prevContext = context.attach();
    try {
      lbStream.start();
    } finally {
      context.detach(prevContext);
    }
    stopwatch.reset().start();

    LoadBalanceRequest initRequest = LoadBalanceRequest.newBuilder()
        .setInitialRequest(InitialLoadBalanceRequest.newBuilder()
            .setName(serviceName).build())
        .build();
    logger.log(
        ChannelLogLevel.DEBUG,
        "[grpclb-<{0}>] Sent initial grpclb request {1}", serviceName, initRequest);
    try {
      lbStream.lbRequestWriter.onNext(initRequest);
    } catch (Exception e) {
      lbStream.close(e);
    }
  }

  private void cancelFallbackTimer() {
    if (fallbackTimer != null) {
      fallbackTimer.cancel();
    }
  }

  private void cancelLbRpcRetryTimer() {
    if (lbRpcRetryTimer != null) {
      lbRpcRetryTimer.cancel();
      lbRpcRetryTimer = null;
    }
  }

  void shutdown() {
    logger.log(ChannelLogLevel.INFO, "[grpclb-<{0}>] Shutdown", serviceName);
    shutdownLbComm();
    switch (config.getMode()) {
      case ROUND_ROBIN:
        // We close the subchannels through subchannelPool instead of helper just for convenience of
        // testing.
        for (Subchannel subchannel : subchannels.values()) {
          returnSubchannelToPool(subchannel);
        }
        subchannelPool.clear();
        break;
      case PICK_FIRST:
        if (!subchannels.isEmpty()) {
          checkState(subchannels.size() == 1, "Excessive Subchannels: %s", subchannels);
          subchannels.values().iterator().next().shutdown();
        }
        break;
      default:
        throw new AssertionError("Missing case for " + config.getMode());
    }
    subchannels = Collections.emptyMap();
    cancelFallbackTimer();
    cancelLbRpcRetryTimer();
  }

  void propagateError(Status status) {
    logger.log(ChannelLogLevel.DEBUG, "[grpclb-<{0}>] Error: {1}", serviceName, status);
    if (backendList.isEmpty()) {
      Status error =
          Status.UNAVAILABLE.withCause(status.getCause()).withDescription(status.getDescription());
      maybeUpdatePicker(
          TRANSIENT_FAILURE, new RoundRobinPicker(dropList, Arrays.asList(new ErrorEntry(error))));
    }
  }

  private void returnSubchannelToPool(Subchannel subchannel) {
    subchannelPool.returnSubchannel(subchannel, subchannel.getAttributes().get(STATE_INFO).get());
  }

  @VisibleForTesting
  @Nullable
  GrpclbClientLoadRecorder getLoadRecorder() {
    if (lbStream == null) {
      return null;
    }
    return lbStream.loadRecorder;
  }

  /**
   * Populate backend servers to be used based on the given list of addresses.
   */
  private void updateServerList(
      List<DropEntry> newDropList, List<BackendAddressGroup> newBackendAddrList,
      @Nullable GrpclbClientLoadRecorder loadRecorder) {
    HashMap<List<EquivalentAddressGroup>, Subchannel> newSubchannelMap =
        new HashMap<>();
    List<BackendEntry> newBackendList = new ArrayList<>();

    switch (config.getMode()) {
      case ROUND_ROBIN:
        for (BackendAddressGroup backendAddr : newBackendAddrList) {
          EquivalentAddressGroup eag = backendAddr.getAddresses();
          List<EquivalentAddressGroup> eagAsList = Collections.singletonList(eag);
          Subchannel subchannel = newSubchannelMap.get(eagAsList);
          if (subchannel == null) {
            subchannel = subchannels.get(eagAsList);
            if (subchannel == null) {
              subchannel = subchannelPool.takeOrCreateSubchannel(eag, createSubchannelAttrs());
              subchannel.requestConnection();
            }
            newSubchannelMap.put(eagAsList, subchannel);
          }
          BackendEntry entry;
          // Only picks with tokens are reported to LoadRecorder
          if (backendAddr.getToken() == null) {
            entry = new BackendEntry(subchannel);
          } else {
            entry = new BackendEntry(subchannel, loadRecorder, backendAddr.getToken());
          }
          newBackendList.add(entry);
        }
        // Close Subchannels whose addresses have been delisted
        for (Map.Entry<List<EquivalentAddressGroup>, Subchannel> entry : subchannels.entrySet()) {
          List<EquivalentAddressGroup> eagList = entry.getKey();
          if (!newSubchannelMap.containsKey(eagList)) {
            returnSubchannelToPool(entry.getValue());
          }
        }
        subchannels = Collections.unmodifiableMap(newSubchannelMap);
        break;
      case PICK_FIRST:
        checkState(subchannels.size() <= 1, "Unexpected Subchannel count: %s", subchannels);
        final Subchannel subchannel;
        if (newBackendAddrList.isEmpty()) {
          if (subchannels.size() == 1) {
            subchannel = subchannels.values().iterator().next();
            subchannel.shutdown();
            subchannels = Collections.emptyMap();
          }
          break;
        }
        List<EquivalentAddressGroup> eagList = new ArrayList<>();
        // Because for PICK_FIRST, we create a single Subchannel for all addresses, we have to
        // attach the tokens to the EAG attributes and use TokenAttachingLoadRecorder to put them on
        // headers.
        //
        // The PICK_FIRST code path doesn't cache Subchannels.
        for (BackendAddressGroup bag : newBackendAddrList) {
          EquivalentAddressGroup origEag = bag.getAddresses();
          Attributes eagAttrs = origEag.getAttributes();
          if (bag.getToken() != null) {
            eagAttrs = eagAttrs.toBuilder()
                .set(GrpclbConstants.TOKEN_ATTRIBUTE_KEY, bag.getToken()).build();
          }
          eagList.add(new EquivalentAddressGroup(origEag.getAddresses(), eagAttrs));
        }
        if (subchannels.isEmpty()) {
          subchannel =
              helper.createSubchannel(
                  CreateSubchannelArgs.newBuilder()
                      .setAddresses(eagList)
                      .setAttributes(createSubchannelAttrs())
                      .build());
          subchannel.start(new SubchannelStateListener() {
            @Override
            public void onSubchannelState(ConnectivityStateInfo newState) {
              handleSubchannelState(subchannel, newState);
            }
          });
          if (requestConnectionPending) {
            subchannel.requestConnection();
            requestConnectionPending = false;
          }
        } else {
          subchannel = subchannels.values().iterator().next();
          subchannel.updateAddresses(eagList);
        }
        subchannels = Collections.singletonMap(eagList, subchannel);
        newBackendList.add(
            new BackendEntry(subchannel, new TokenAttachingTracerFactory(loadRecorder)));
        break;
      default:
        throw new AssertionError("Missing case for " + config.getMode());
    }

    dropList = Collections.unmodifiableList(newDropList);
    backendList = Collections.unmodifiableList(newBackendList);
  }

  @VisibleForTesting
  class FallbackModeTask implements Runnable {
    private final Status reason;

    private FallbackModeTask(Status reason) {
      this.reason = reason;
    }

    @Override
    public void run() {
      // Timer should have been cancelled if entered fallback early.
      checkState(!usingFallbackBackends, "already in fallback");
      fallbackReason = reason;
      maybeUseFallbackBackends();
      maybeUpdatePicker();
    }
  }

  @VisibleForTesting
  class LbRpcRetryTask implements Runnable {
    @Override
    public void run() {
      startLbRpc();
    }
  }

  @VisibleForTesting
  static class LoadReportingTask implements Runnable {
    private final LbStream stream;

    LoadReportingTask(LbStream stream) {
      this.stream = stream;
    }

    @Override
    public void run() {
      stream.loadReportTimer = null;
      stream.sendLoadReport();
    }
  }

  private class LbStream implements StreamObserver<LoadBalanceResponse> {
    final GrpclbClientLoadRecorder loadRecorder;
    final LoadBalancerGrpc.LoadBalancerStub stub;
    StreamObserver<LoadBalanceRequest> lbRequestWriter;

    // These fields are only accessed from helper.runSerialized()
    boolean initialResponseReceived;
    boolean closed;
    long loadReportIntervalMillis = -1;
    ScheduledHandle loadReportTimer;

    LbStream(LoadBalancerGrpc.LoadBalancerStub stub) {
      this.stub = checkNotNull(stub, "stub");
      // Stats data only valid for current LbStream.  We do not carry over data from previous
      // stream.
      loadRecorder = new GrpclbClientLoadRecorder(time);
    }

    void start() {
      lbRequestWriter = stub.withWaitForReady().balanceLoad(this);
    }

    @Override public void onNext(final LoadBalanceResponse response) {
      syncContext.execute(new Runnable() {
          @Override
          public void run() {
            handleResponse(response);
          }
        });
    }

    @Override public void onError(final Throwable error) {
      syncContext.execute(new Runnable() {
          @Override
          public void run() {
            handleStreamClosed(Status.fromThrowable(error)
                .augmentDescription("Stream to GRPCLB LoadBalancer had an error"));
          }
        });
    }

    @Override public void onCompleted() {
      syncContext.execute(new Runnable() {
          @Override
          public void run() {
            handleStreamClosed(
                Status.UNAVAILABLE.withDescription("Stream to GRPCLB LoadBalancer was closed"));
          }
        });
    }

    // Following methods must be run in helper.runSerialized()

    private void sendLoadReport() {
      if (closed) {
        return;
      }
      ClientStats stats = loadRecorder.generateLoadReport();
      // TODO(zhangkun83): flow control?
      try {
        lbRequestWriter.onNext(LoadBalanceRequest.newBuilder().setClientStats(stats).build());
        scheduleNextLoadReport();
      } catch (Exception e) {
        close(e);
      }
    }

    private void scheduleNextLoadReport() {
      if (loadReportIntervalMillis > 0) {
        loadReportTimer = syncContext.schedule(
            new LoadReportingTask(this), loadReportIntervalMillis, TimeUnit.MILLISECONDS,
            timerService);
      }
    }

    private void handleResponse(LoadBalanceResponse response) {
      if (closed) {
        return;
      }

      LoadBalanceResponseTypeCase typeCase = response.getLoadBalanceResponseTypeCase();
      if (!initialResponseReceived) {
        logger.log(
            ChannelLogLevel.INFO,
            "[grpclb-<{0}>] Got an LB initial response: {1}", serviceName, response);
        if (typeCase != LoadBalanceResponseTypeCase.INITIAL_RESPONSE) {
          logger.log(
              ChannelLogLevel.WARNING,
              "[grpclb-<{0}>] Received a response without initial response",
              serviceName);
          return;
        }
        initialResponseReceived = true;
        InitialLoadBalanceResponse initialResponse = response.getInitialResponse();
        loadReportIntervalMillis =
            Durations.toMillis(initialResponse.getClientStatsReportInterval());
        scheduleNextLoadReport();
        return;
      }
      if (SHOULD_LOG_SERVER_LISTS) {
        logger.log(
            ChannelLogLevel.DEBUG, "[grpclb-<{0}>] Got an LB response: {1}", serviceName, response);
      } else {
        logger.log(ChannelLogLevel.DEBUG, "[grpclb-<{0}>] Got an LB response", serviceName);
      }

      if (typeCase == LoadBalanceResponseTypeCase.FALLBACK_RESPONSE) {
        // Force entering fallback requested by balancer.
        cancelFallbackTimer();
        fallbackReason = BALANCER_REQUESTED_FALLBACK_STATUS;
        useFallbackBackends();
        maybeUpdatePicker();
        return;
      } else if (typeCase != LoadBalanceResponseTypeCase.SERVER_LIST) {
        logger.log(
            ChannelLogLevel.WARNING,
            "[grpclb-<{0}>] Ignoring unexpected response type: {1}",
            serviceName,
            typeCase);
        return;
      }

      balancerWorking = true;
      // TODO(zhangkun83): handle delegate from initialResponse
      ServerList serverList = response.getServerList();
      List<DropEntry> newDropList = new ArrayList<>();
      List<BackendAddressGroup> newBackendAddrList = new ArrayList<>();
      // Construct the new collections. Create new Subchannels when necessary.
      for (Server server : serverList.getServersList()) {
        String token = server.getLoadBalanceToken();
        if (server.getDrop()) {
          newDropList.add(new DropEntry(loadRecorder, token));
        } else {
          newDropList.add(null);
          InetSocketAddress address;
          try {
            address = new InetSocketAddress(
                InetAddress.getByAddress(server.getIpAddress().toByteArray()), server.getPort());
          } catch (UnknownHostException e) {
            propagateError(
                Status.UNAVAILABLE
                    .withDescription("Invalid backend address: " + server)
                    .withCause(e));
            continue;
          }
          // ALTS code can use the presence of ATTR_LB_PROVIDED_BACKEND to select ALTS instead of
          // TLS, with Netty.
          EquivalentAddressGroup eag =
              new EquivalentAddressGroup(address, LB_PROVIDED_BACKEND_ATTRS);
          newBackendAddrList.add(new BackendAddressGroup(eag, token));
        }
      }
      // Exit fallback as soon as a new server list is received from the balancer.
      usingFallbackBackends = false;
      fallbackReason = null;
      cancelFallbackTimer();
      updateServerList(newDropList, newBackendAddrList, loadRecorder);
      maybeUpdatePicker();
    }

    private void handleStreamClosed(Status error) {
      checkArgument(!error.isOk(), "unexpected OK status");
      if (closed) {
        return;
      }
      closed = true;
      cleanUp();
      propagateError(error);
      balancerWorking = false;
      fallbackReason = error;
      cancelFallbackTimer();
      maybeUseFallbackBackends();
      maybeUpdatePicker();

      long delayNanos = 0;
      if (initialResponseReceived || lbRpcRetryPolicy == null) {
        // Reset the backoff sequence if balancer has sent the initial response, or backoff sequence
        // has never been initialized.
        lbRpcRetryPolicy = backoffPolicyProvider.get();
      }
      // Backoff only when balancer wasn't working previously.
      if (!initialResponseReceived) {
        // The back-off policy determines the interval between consecutive RPC upstarts, thus the
        // actual delay may be smaller than the value from the back-off policy, or even negative,
        // depending how much time was spent in the previous RPC.
        delayNanos =
            lbRpcRetryPolicy.nextBackoffNanos() - stopwatch.elapsed(TimeUnit.NANOSECONDS);
      }
      if (delayNanos <= 0) {
        startLbRpc();
      } else {
        lbRpcRetryTimer =
            syncContext.schedule(new LbRpcRetryTask(), delayNanos, TimeUnit.NANOSECONDS,
                timerService);
      }

      helper.refreshNameResolution();
    }

    void close(Exception error) {
      if (closed) {
        return;
      }
      closed = true;
      cleanUp();
      lbRequestWriter.onError(error);
    }

    private void cleanUp() {
      if (loadReportTimer != null) {
        loadReportTimer.cancel();
        loadReportTimer = null;
      }
      if (lbStream == this) {
        lbStream = null;
      }
    }
  }

  /**
   * Make and use a picker out of the current lists and the states of subchannels if they have
   * changed since the last picker created.
   */
  private void maybeUpdatePicker() {
    List<RoundRobinEntry> pickList;
    ConnectivityState state;
    if (backendList.isEmpty()) {
      // Note balancer (is working) may enforce using fallback backends, and that fallback may
      // fail. So we should check if currently in fallback first.
      if (usingFallbackBackends) {
        Status error =
            NO_FALLBACK_BACKENDS_STATUS
                .withCause(fallbackReason.getCause())
                .augmentDescription(fallbackReason.getDescription());
        pickList = Collections.<RoundRobinEntry>singletonList(new ErrorEntry(error));
        state = TRANSIENT_FAILURE;
      } else if (balancerWorking)  {
        pickList =
            Collections.<RoundRobinEntry>singletonList(
                new ErrorEntry(NO_AVAILABLE_BACKENDS_STATUS));
        state = TRANSIENT_FAILURE;
      } else {  // still waiting for balancer
        pickList = Collections.singletonList(BUFFER_ENTRY);
        state = CONNECTING;
      }
      maybeUpdatePicker(state, new RoundRobinPicker(dropList, pickList));
      return;
    }
    switch (config.getMode()) {
      case ROUND_ROBIN:
        pickList = new ArrayList<>(backendList.size());
        Status error = null;
        boolean hasPending = false;
        for (BackendEntry entry : backendList) {
          Subchannel subchannel = entry.subchannel;
          Attributes attrs = subchannel.getAttributes();
          ConnectivityStateInfo stateInfo = attrs.get(STATE_INFO).get();
          if (stateInfo.getState() == READY) {
            pickList.add(entry);
          } else if (stateInfo.getState() == TRANSIENT_FAILURE) {
            error = stateInfo.getStatus();
          } else {
            hasPending = true;
          }
        }
        if (pickList.isEmpty()) {
          if (hasPending) {
            pickList.add(BUFFER_ENTRY);
            state = CONNECTING;
          } else {
            pickList.add(new ErrorEntry(error));
            state = TRANSIENT_FAILURE;
          }
        } else {
          state = READY;
        }
        break;
      case PICK_FIRST: {
        checkState(backendList.size() == 1, "Excessive backend entries: %s", backendList);
        BackendEntry onlyEntry = backendList.get(0);
        ConnectivityStateInfo stateInfo =
            onlyEntry.subchannel.getAttributes().get(STATE_INFO).get();
        state = stateInfo.getState();
        switch (state) {
          case READY:
            pickList = Collections.<RoundRobinEntry>singletonList(onlyEntry);
            break;
          case TRANSIENT_FAILURE:
            pickList =
                Collections.<RoundRobinEntry>singletonList(new ErrorEntry(stateInfo.getStatus()));
            break;
          case CONNECTING:
            pickList = Collections.singletonList(BUFFER_ENTRY);
            break;
          default:
            pickList = Collections.<RoundRobinEntry>singletonList(
                new IdleSubchannelEntry(onlyEntry.subchannel, syncContext));
        }
        break;
      }
      default:
        throw new AssertionError("Missing case for " + config.getMode());
    }
    maybeUpdatePicker(state, new RoundRobinPicker(dropList, pickList));
  }

  /**
   * Update the given picker to the helper if it's different from the current one.
   */
  private void maybeUpdatePicker(ConnectivityState state, RoundRobinPicker picker) {
    // Discard the new picker if we are sure it won't make any difference, in order to save
    // re-processing pending streams, and avoid unnecessary resetting of the pointer in
    // RoundRobinPicker.
    if (picker.dropList.equals(currentPicker.dropList)
        && picker.pickList.equals(currentPicker.pickList)) {
      return;
    }
    currentPicker = picker;
    helper.updateBalancingState(state, picker);
  }

  private static Attributes createSubchannelAttrs() {
    return Attributes.newBuilder()
        .set(STATE_INFO,
            new AtomicReference<>(
                ConnectivityStateInfo.forNonError(IDLE)))
        .build();
  }

  @VisibleForTesting
  static final class DropEntry {
    private final GrpclbClientLoadRecorder loadRecorder;
    private final String token;

    DropEntry(GrpclbClientLoadRecorder loadRecorder, String token) {
      this.loadRecorder = checkNotNull(loadRecorder, "loadRecorder");
      this.token = checkNotNull(token, "token");
    }

    PickResult picked() {
      loadRecorder.recordDroppedRequest(token);
      return DROP_PICK_RESULT;
    }

    @Override
    public String toString() {
      // This is printed in logs.  Only include useful information.
      return "drop(" + token + ")";
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(loadRecorder, token);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof DropEntry)) {
        return false;
      }
      DropEntry that = (DropEntry) other;
      return Objects.equal(loadRecorder, that.loadRecorder) && Objects.equal(token, that.token);
    }
  }

  @VisibleForTesting
  interface RoundRobinEntry {
    PickResult picked(Metadata headers);
  }

  @VisibleForTesting
  static final class BackendEntry implements RoundRobinEntry {
    final Subchannel subchannel;
    @VisibleForTesting
    final PickResult result;
    @Nullable
    private final String token;

    /**
     * For ROUND_ROBIN: creates a BackendEntry whose usage will be reported to load recorder.
     */
    BackendEntry(Subchannel subchannel, GrpclbClientLoadRecorder loadRecorder, String token) {
      this.subchannel = checkNotNull(subchannel, "subchannel");
      this.result =
          PickResult.withSubchannel(subchannel, checkNotNull(loadRecorder, "loadRecorder"));
      this.token = checkNotNull(token, "token");
    }

    /**
     * For ROUND_ROBIN/PICK_FIRST: creates a BackendEntry whose usage will not be reported.
     */
    BackendEntry(Subchannel subchannel) {
      this.subchannel = checkNotNull(subchannel, "subchannel");
      this.result = PickResult.withSubchannel(subchannel);
      this.token = null;
    }

    /**
     * For PICK_FIRST: creates a BackendEntry that includes all addresses.
     */
    BackendEntry(Subchannel subchannel, TokenAttachingTracerFactory tracerFactory) {
      this.subchannel = checkNotNull(subchannel, "subchannel");
      this.result =
          PickResult.withSubchannel(subchannel, checkNotNull(tracerFactory, "tracerFactory"));
      this.token = null;
    }

    @Override
    public PickResult picked(Metadata headers) {
      headers.discardAll(GrpclbConstants.TOKEN_METADATA_KEY);
      if (token != null) {
        headers.put(GrpclbConstants.TOKEN_METADATA_KEY, token);
      }
      return result;
    }

    @Override
    public String toString() {
      // This is printed in logs.  Only give out useful information.
      return "[" + subchannel.getAllAddresses().toString() + "(" + token + ")]";
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(result, token);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof BackendEntry)) {
        return false;
      }
      BackendEntry that = (BackendEntry) other;
      return Objects.equal(result, that.result) && Objects.equal(token, that.token);
    }
  }

  @VisibleForTesting
  static final class IdleSubchannelEntry implements RoundRobinEntry {
    private final SynchronizationContext syncContext;
    private final Subchannel subchannel;
    private final AtomicBoolean connectionRequested = new AtomicBoolean(false);

    IdleSubchannelEntry(Subchannel subchannel, SynchronizationContext syncContext) {
      this.subchannel = checkNotNull(subchannel, "subchannel");
      this.syncContext = checkNotNull(syncContext, "syncContext");
    }

    @Override
    public PickResult picked(Metadata headers) {
      if (connectionRequested.compareAndSet(false, true)) {
        syncContext.execute(new Runnable() {
            @Override
            public void run() {
              subchannel.requestConnection();
            }
          });
      }
      return PickResult.withNoResult();
    }

    @Override
    public String toString() {
      // This is printed in logs.  Only give out useful information.
      return "(idle)[" + subchannel.getAllAddresses().toString() + "]";
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(subchannel, syncContext);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof IdleSubchannelEntry)) {
        return false;
      }
      IdleSubchannelEntry that = (IdleSubchannelEntry) other;
      return Objects.equal(subchannel, that.subchannel)
          && Objects.equal(syncContext, that.syncContext);
    }
  }

  @VisibleForTesting
  static final class ErrorEntry implements RoundRobinEntry {
    final PickResult result;

    ErrorEntry(Status status) {
      result = PickResult.withError(status);
    }

    @Override
    public PickResult picked(Metadata headers) {
      return result;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(result);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof ErrorEntry)) {
        return false;
      }
      return Objects.equal(result, ((ErrorEntry) other).result);
    }

    @Override
    public String toString() {
      // This is printed in logs.  Only include useful information.
      return result.getStatus().toString();
    }
  }

  @VisibleForTesting
  static final class RoundRobinPicker extends SubchannelPicker {
    @VisibleForTesting
    final List<DropEntry> dropList;
    private int dropIndex;

    @VisibleForTesting
    final List<? extends RoundRobinEntry> pickList;
    private int pickIndex;

    // dropList can be empty, which means no drop.
    // pickList must not be empty.
    RoundRobinPicker(List<DropEntry> dropList, List<? extends RoundRobinEntry> pickList) {
      this.dropList = checkNotNull(dropList, "dropList");
      this.pickList = checkNotNull(pickList, "pickList");
      checkArgument(!pickList.isEmpty(), "pickList is empty");
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      synchronized (pickList) {
        // Two-level round-robin.
        // First round-robin on dropList. If a drop entry is selected, request will be dropped.  If
        // a non-drop entry is selected, then round-robin on pickList.  This makes sure requests are
        // dropped at the same proportion as the drop entries appear on the round-robin list from
        // the balancer, while only backends from pickList are selected for the non-drop cases.
        if (!dropList.isEmpty()) {
          DropEntry drop = dropList.get(dropIndex);
          dropIndex++;
          if (dropIndex == dropList.size()) {
            dropIndex = 0;
          }
          if (drop != null) {
            return drop.picked();
          }
        }

        RoundRobinEntry pick = pickList.get(pickIndex);
        pickIndex++;
        if (pickIndex == pickList.size()) {
          pickIndex = 0;
        }
        return pick.picked(args.getHeaders());
      }
    }

    @Override
    public String toString() {
      if (SHOULD_LOG_SERVER_LISTS) {
        return MoreObjects.toStringHelper(RoundRobinPicker.class)
            .add("dropList", dropList)
            .add("pickList", pickList)
            .toString();
      }
      return MoreObjects.toStringHelper(RoundRobinPicker.class).toString();
    }
  }
}
