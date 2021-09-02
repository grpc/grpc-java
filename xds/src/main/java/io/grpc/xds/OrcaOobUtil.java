/*
 * Copyright 2019 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;

import com.github.udpa.udpa.data.orca.v1.OrcaLoadReport;
import com.github.udpa.udpa.service.orca.v1.OpenRcaServiceGrpc;
import com.github.udpa.udpa.service.orca.v1.OrcaLoadReportRequest;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.protobuf.util.Durations;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ClientCall;
import io.grpc.ConnectivityStateInfo;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.GrpcUtil;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.ForwardingSubchannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Utility class that provides method for {@link LoadBalancer} to install listeners to receive
 * out-of-band backend cost metrics in the format of Open Request Cost Aggregation (ORCA).
 */
abstract class OrcaOobUtil {

  private static final Logger logger = Logger.getLogger(OrcaPerRequestUtil.class.getName());
  private static final OrcaOobUtil DEFAULT_INSTANCE =
      new OrcaOobUtil() {

        @Override
        public OrcaReportingHelperWrapper newOrcaReportingHelperWrapper(
            LoadBalancer.Helper delegate,
            OrcaOobReportListener listener) {
          return newOrcaReportingHelperWrapper(
              delegate,
              listener,
              new ExponentialBackoffPolicy.Provider(),
              GrpcUtil.STOPWATCH_SUPPLIER);
        }
      };

  /**
   * Gets an {@code OrcaOobUtil} instance that provides actual implementation of
   * {@link #newOrcaReportingHelperWrapper}.
   */
  public static OrcaOobUtil getInstance() {
    return DEFAULT_INSTANCE;
  }

  /**
   * Creates a new {@link LoadBalancer.Helper} with provided {@link OrcaOobReportListener} installed
   * to receive callback when an out-of-band ORCA report is received.
   *
   * <p>Example usages:
   *
   * <ul>
   *   <li> Leaf policy (e.g., WRR policy)
   *     <pre>
   *       {@code
   *       class WrrLoadbalancer extends LoadBalancer {
   *         private final Helper originHelper;  // the original Helper
   *
   *         public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
   *           // listener implements the logic for WRR's usage of backend metrics.
   *           OrcaReportingHelperWrapper orcaWrapper =
   *               OrcaOobUtil.getInstance().newOrcaReportingHelperWrapper(originHelper, listener);
   *           orcaWrapper.setReportingConfig(
   *               OrcaRerportingConfig.newBuilder().setReportInterval(30, SECOND).build());
   *           Subchannel subchannel =
   *               orcaWrapper.asHelper().createSubchannel(CreateSubchannelArgs.newBuilder()...);
   *           ...
   *         }
   *       }
   *       }
   *     </pre>
   *   </li>
   *   <li> Delegating policy doing per-child-policy aggregation
   *     <pre>
   *       {@code
   *       class XdsLoadBalancer extends LoadBalancer {
   *         private final Helper originHelper;  // the original Helper
   *
   *         private void createChildPolicy(
   *             Locality locality, LoadBalancerProvider childPolicyProvider) {
   *           // Each Locality has a child policy, and the parent does per-locality aggregation by
   *           // summing everything up.
   *
   *           // Create an OrcaReportingHelperWrapper for each Locality.
   *           // listener implements the logic for locality-level backend metric aggregation.
   *           OrcaReportingHelperWrapper orcaWrapper =
   *               OrcaOobUtil.getInstance().newOrcaReportingHelperWrapper(originHelper, listener);
   *           orcaWrapper.setReportingConfig(
   *               OrcaRerportingConfig.newBuilder().setReportInterval(30, SECOND).build());
   *           LoadBalancer childLb = childPolicyProvider.newLoadBalancer(orcaWrapper.asHelper());
   *         }
   *       }
   *       }
   *     </pre>
   *   </li>
   * </ul>
   *
   * @param delegate the delegate helper that provides essentials for establishing subchannels to
   *     backends.
   * @param listener contains the callback to be invoked when an out-of-band ORCA report is
   *     received.
   */
  public abstract OrcaReportingHelperWrapper newOrcaReportingHelperWrapper(
      LoadBalancer.Helper delegate,
      OrcaOobReportListener listener);

  @VisibleForTesting
  static OrcaReportingHelperWrapper newOrcaReportingHelperWrapper(
      LoadBalancer.Helper delegate,
      OrcaOobReportListener listener,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier) {
    final OrcaReportingHelper orcaHelper =
        new OrcaReportingHelper(delegate, listener, backoffPolicyProvider, stopwatchSupplier);

    return new OrcaReportingHelperWrapper() {
      @Override
      public void setReportingConfig(OrcaReportingConfig config) {
        orcaHelper.setReportingConfig(config);
      }

      @Override
      public Helper asHelper() {
        return orcaHelper;
      }
    };
  }

  /**
   * The listener interface for receiving out-of-band ORCA reports from backends. The class that is
   * interested in processing backend cost metrics implements this interface, and the object created
   * with that class is registered with a component, using methods in {@link OrcaPerRequestUtil}.
   * When an ORCA report is received, that object's {@code onLoadReport} method is invoked.
   */
  public interface OrcaOobReportListener {

    /**
     * Invoked when an out-of-band ORCA report is received.
     *
     * <p>Note this callback will be invoked from the {@link SynchronizationContext} of the
     * delegated helper, implementations should not block.
     *
     * @param report load report in the format of ORCA protocol.
     */
    void onLoadReport(OrcaLoadReport report);
  }

  /**
   * Blueprint for the wrapper that wraps a {@link LoadBalancer.Helper} with the capability of
   * allowing {@link LoadBalancer}s interested in receiving out-of-band ORCA reports to update the
   * reporting configuration such as reporting interval.
   */
  public abstract static class OrcaReportingHelperWrapper {

    /**
     * Sets the configuration of receiving ORCA reports, such as the interval of receiving reports.
     *
     * <p>This method needs to be called from the SynchronizationContext returned by the wrapped
     * helper's {@link Helper#getSynchronizationContext()}.
     *
     * <p>Each load balancing policy must call this method to configure the backend load reporting.
     * Otherwise, it will not receive ORCA reports.
     *
     * <p>If multiple load balancing policies configure reporting with different intervals, reports
     * come with the minimum of those intervals.
     *
     * @param config the configuration to be set.
     */
    public abstract void setReportingConfig(OrcaReportingConfig config);

    /**
     * Returns a wrapped {@link LoadBalancer.Helper}. Subchannels created through it will retrieve
     * ORCA load reports if the server supports it.
     */
    public abstract LoadBalancer.Helper asHelper();
  }

  /**
   * An {@link OrcaReportingHelper} wraps a delegated {@link LoadBalancer.Helper} with additional
   * functionality to manage RPCs for out-of-band ORCA reporting for each backend it establishes
   * connection to.
   */
  private static final class OrcaReportingHelper extends ForwardingLoadBalancerHelper
      implements OrcaOobReportListener {

    private static final CreateSubchannelArgs.Key<OrcaReportingState> ORCA_REPORTING_STATE_KEY =
        CreateSubchannelArgs.Key.create("internal-orca-reporting-state");
    private final LoadBalancer.Helper delegate;
    private final OrcaOobReportListener listener;
    private final SynchronizationContext syncContext;
    private final BackoffPolicy.Provider backoffPolicyProvider;
    private final Supplier<Stopwatch> stopwatchSupplier;
    private final Set<OrcaReportingState> orcaStates = new HashSet<>();
    @Nullable private OrcaReportingConfig orcaConfig;

    OrcaReportingHelper(
        LoadBalancer.Helper delegate,
        OrcaOobReportListener listener,
        BackoffPolicy.Provider backoffPolicyProvider,
        Supplier<Stopwatch> stopwatchSupplier) {
      this.delegate = checkNotNull(delegate, "delegate");
      this.listener = checkNotNull(listener, "listener");
      this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
      this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
      syncContext = checkNotNull(delegate.getSynchronizationContext(), "syncContext");
    }

    @Override
    protected Helper delegate() {
      return delegate;
    }

    @Override
    public Subchannel createSubchannel(CreateSubchannelArgs args) {
      syncContext.throwIfNotInThisSynchronizationContext();
      OrcaReportingState orcaState = args.getOption(ORCA_REPORTING_STATE_KEY);
      boolean augmented = false;
      if (orcaState == null) {
        // Only the first load balancing policy requesting ORCA reports instantiates an
        // OrcaReportingState.
        orcaState = new OrcaReportingState(this, syncContext,
            delegate().getScheduledExecutorService());
        args = args.toBuilder().addOption(ORCA_REPORTING_STATE_KEY, orcaState).build();
        augmented = true;
      }
      orcaStates.add(orcaState);
      orcaState.listeners.add(this);
      Subchannel subchannel = super.createSubchannel(args);
      if (augmented) {
        subchannel = new SubchannelImpl(subchannel, orcaState);
      }
      if (orcaConfig != null) {
        orcaState.setReportingConfig(this, orcaConfig);
      }
      return subchannel;
    }

    void setReportingConfig(final OrcaReportingConfig config) {
      syncContext.throwIfNotInThisSynchronizationContext();
      orcaConfig = config;
      for (OrcaReportingState state : orcaStates) {
        state.setReportingConfig(OrcaReportingHelper.this, config);
      }
    }

    @Override
    public void onLoadReport(OrcaLoadReport report) {
      syncContext.throwIfNotInThisSynchronizationContext();
      if (orcaConfig != null) {
        listener.onLoadReport(report);
      }
    }

    /**
     * An {@link OrcaReportingState} is a client of ORCA service running on a single backend.
     *
     * <p>All methods are run from {@code syncContext}.
     */
    private final class OrcaReportingState implements SubchannelStateListener {

      private final OrcaReportingHelper orcaHelper;
      private final SynchronizationContext syncContext;
      private final ScheduledExecutorService timeService;
      private final List<OrcaOobReportListener> listeners = new ArrayList<>();
      private final Map<OrcaReportingHelper, OrcaReportingConfig> configs = new HashMap<>();
      @Nullable private Subchannel subchannel;
      @Nullable private ChannelLogger subchannelLogger;
      @Nullable
      private SubchannelStateListener stateListener;
      @Nullable private BackoffPolicy backoffPolicy;
      @Nullable private OrcaReportingStream orcaRpc;
      @Nullable private ScheduledHandle retryTimer;
      @Nullable private OrcaReportingConfig overallConfig;
      private final Runnable retryTask =
          new Runnable() {
            @Override
            public void run() {
              startRpc();
            }
          };
      private ConnectivityStateInfo state = ConnectivityStateInfo.forNonError(IDLE);
      // True if server returned UNIMPLEMENTED.
      private boolean disabled;

      OrcaReportingState(
          OrcaReportingHelper orcaHelper,
          SynchronizationContext syncContext,
          ScheduledExecutorService timeService) {
        this.orcaHelper = checkNotNull(orcaHelper, "orcaHelper");
        this.syncContext = checkNotNull(syncContext, "syncContext");
        this.timeService = checkNotNull(timeService, "timeService");
      }

      void init(Subchannel subchannel, SubchannelStateListener stateListener) {
        checkState(this.subchannel == null, "init() already called");
        this.subchannel = checkNotNull(subchannel, "subchannel");
        this.subchannelLogger = checkNotNull(subchannel.getChannelLogger(), "subchannelLogger");
        this.stateListener = checkNotNull(stateListener, "stateListener");
      }

      void setReportingConfig(OrcaReportingHelper helper, OrcaReportingConfig config) {
        boolean reconfigured = false;
        configs.put(helper, config);
        // Real reporting interval is the minimum of intervals requested by all participating
        // helpers.
        if (overallConfig == null) {
          overallConfig = config.toBuilder().build();
          reconfigured = true;
        } else {
          long minInterval = Long.MAX_VALUE;
          for (OrcaReportingConfig c : configs.values()) {
            if (c.getReportIntervalNanos() < minInterval) {
              minInterval = c.getReportIntervalNanos();
            }
          }
          if (overallConfig.getReportIntervalNanos() != minInterval) {
            overallConfig = overallConfig.toBuilder()
                .setReportInterval(minInterval, TimeUnit.NANOSECONDS).build();
            reconfigured = true;
          }
        }
        if (reconfigured) {
          stopRpc("ORCA reporting reconfigured");
          adjustOrcaReporting();
        }
      }

      @Override
      public void onSubchannelState(ConnectivityStateInfo newState) {
        if (Objects.equal(state.getState(), READY) && !Objects.equal(newState.getState(), READY)) {
          // A connection was lost.  We will reset disabled flag because ORCA service
          // may be available on the new connection.
          disabled = false;
        }
        if (Objects.equal(newState.getState(), SHUTDOWN)) {
          orcaHelper.orcaStates.remove(this);
        }
        state = newState;
        adjustOrcaReporting();
        // Propagate subchannel state update to downstream listeners.
        stateListener.onSubchannelState(newState);
      }

      void adjustOrcaReporting() {
        if (!disabled && overallConfig != null && Objects.equal(state.getState(), READY)) {
          if (orcaRpc == null && !isRetryTimerPending()) {
            startRpc();
          }
        } else {
          stopRpc("Client stops ORCA reporting");
          backoffPolicy = null;
        }
      }

      void startRpc() {
        checkState(orcaRpc == null, "previous orca reporting RPC has not been cleaned up");
        checkState(subchannel != null, "init() not called");
        subchannelLogger.log(
            ChannelLogLevel.DEBUG, "Starting ORCA reporting for {0}", subchannel.getAllAddresses());
        orcaRpc = new OrcaReportingStream(subchannel.asChannel(), stopwatchSupplier.get());
        orcaRpc.start();
      }

      void stopRpc(String msg) {
        if (orcaRpc != null) {
          orcaRpc.cancel(msg);
          orcaRpc = null;
        }
        if (retryTimer != null) {
          retryTimer.cancel();
          retryTimer = null;
        }
      }

      boolean isRetryTimerPending() {
        return retryTimer != null && retryTimer.isPending();
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("disabled", disabled)
            .add("orcaRpc", orcaRpc)
            .add("reportingConfig", overallConfig)
            .add("connectivityState", state)
            .toString();
      }

      private class OrcaReportingStream extends ClientCall.Listener<OrcaLoadReport> {

        private final ClientCall<OrcaLoadReportRequest, OrcaLoadReport> call;
        private final Stopwatch stopwatch;
        private boolean callHasResponded;

        OrcaReportingStream(Channel channel, Stopwatch stopwatch) {
          call =
              checkNotNull(channel, "channel")
                  .newCall(OpenRcaServiceGrpc.getStreamCoreMetricsMethod(), CallOptions.DEFAULT);
          this.stopwatch = checkNotNull(stopwatch, "stopwatch");
        }

        void start() {
          stopwatch.reset().start();
          call.start(this, new Metadata());
          call.sendMessage(
              OrcaLoadReportRequest.newBuilder()
                  .setReportInterval(Durations.fromNanos(overallConfig.getReportIntervalNanos()))
                  .build());
          call.halfClose();
          call.request(1);
        }

        @Override
        public void onMessage(final OrcaLoadReport response) {
          syncContext.execute(
              new Runnable() {
                @Override
                public void run() {
                  if (orcaRpc == OrcaReportingStream.this) {
                    handleResponse(response);
                  }
                }
              });
        }

        @Override
        public void onClose(final Status status, Metadata trailers) {
          syncContext.execute(
              new Runnable() {
                @Override
                public void run() {
                  if (orcaRpc == OrcaReportingStream.this) {
                    orcaRpc = null;
                    handleStreamClosed(status);
                  }
                }
              });
        }

        void handleResponse(OrcaLoadReport response) {
          callHasResponded = true;
          backoffPolicy = null;
          subchannelLogger.log(ChannelLogLevel.DEBUG, "Received an ORCA report: {0}", response);
          for (OrcaOobReportListener listener : listeners) {
            listener.onLoadReport(response);
          }
          call.request(1);
        }

        void handleStreamClosed(Status status) {
          if (Objects.equal(status.getCode(), Code.UNIMPLEMENTED)) {
            disabled = true;
            logger.log(
                Level.SEVERE,
                "Backend {0} OpenRcaService is disabled. Server returned: {1}",
                new Object[] {subchannel.getAllAddresses(), status});
            subchannelLogger.log(ChannelLogLevel.ERROR, "OpenRcaService disabled: {0}", status);
            return;
          }
          long delayNanos = 0;
          // Backoff only when no response has been received.
          if (!callHasResponded) {
            if (backoffPolicy == null) {
              backoffPolicy = backoffPolicyProvider.get();
            }
            delayNanos = backoffPolicy.nextBackoffNanos() - stopwatch.elapsed(TimeUnit.NANOSECONDS);
          }
          subchannelLogger.log(
              ChannelLogLevel.DEBUG,
              "ORCA reporting stream closed with {0}, backoff in {1} ns",
              status,
              delayNanos <= 0 ? 0 : delayNanos);
          if (delayNanos <= 0) {
            startRpc();
          } else {
            checkState(!isRetryTimerPending(), "Retry double scheduled");
            retryTimer =
                syncContext.schedule(retryTask, delayNanos, TimeUnit.NANOSECONDS, timeService);
          }
        }

        void cancel(String msg) {
          call.cancel(msg, null);
        }

        @Override
        public String toString() {
          return MoreObjects.toStringHelper(this)
              .add("callStarted", call != null)
              .add("callHasResponded", callHasResponded)
              .toString();
        }
      }
    }
  }

  @VisibleForTesting
  static final class SubchannelImpl extends ForwardingSubchannel {

    private final Subchannel delegate;
    private final OrcaReportingHelper.OrcaReportingState orcaState;

    SubchannelImpl(Subchannel delegate, OrcaReportingHelper.OrcaReportingState orcaState) {
      this.delegate = checkNotNull(delegate, "delegate");
      this.orcaState = checkNotNull(orcaState, "orcaState");
    }

    @Override
    protected Subchannel delegate() {
      return delegate;
    }

    @Override
    public void start(SubchannelStateListener listener) {
      orcaState.init(this, listener);
      super.start(orcaState);
    }
  }

  /** Configuration for out-of-band ORCA reporting service RPC. */
  public static final class OrcaReportingConfig {

    private final long reportIntervalNanos;

    private OrcaReportingConfig(long reportIntervalNanos) {
      this.reportIntervalNanos = reportIntervalNanos;
    }

    /** Creates a new builder. */
    public static Builder newBuilder() {
      return new Builder();
    }

    /** Returns the configured maximum interval of receiving out-of-band ORCA reports. */
    public long getReportIntervalNanos() {
      return reportIntervalNanos;
    }

    /** Returns a builder with the same initial values as this object. */
    public Builder toBuilder() {
      return newBuilder().setReportInterval(reportIntervalNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("reportIntervalNanos", reportIntervalNanos)
          .toString();
    }

    public static final class Builder {

      private long reportIntervalNanos;

      Builder() {}

      /**
       * Sets the maximum expected interval of receiving out-of-band ORCA report. The actual
       * reporting interval might be smaller if there are other load balancing policies requesting
       * for more frequent cost metric report.
       *
       * @param reportInterval the maximum expected interval of receiving periodical ORCA reports.
       * @param unit time unit of {@code reportInterval} value.
       */
      public Builder setReportInterval(long reportInterval, TimeUnit unit) {
        reportIntervalNanos = unit.toNanos(reportInterval);
        return this;
      }

      /** Creates a new {@link OrcaReportingConfig} object. */
      public OrcaReportingConfig build() {
        return new OrcaReportingConfig(reportIntervalNanos);
      }
    }
  }
}
