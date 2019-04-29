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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.protobuf.util.Durations;
import io.envoyproxy.udpa.data.orca.v1.OrcaLoadReport;
import io.envoyproxy.udpa.service.orca.v1.OpenRcaServiceGrpc;
import io.envoyproxy.udpa.service.orca.v1.OrcaLoadReportRequest;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ClientCall;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
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
import io.grpc.protobuf.ProtoUtils;
import io.grpc.util.ForwardingClientStreamTracer;
import io.grpc.util.ForwardingLoadBalancerHelper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Utility class that provides method for {@link LoadBalancer} to install listeners to receive
 * backend cost metrics in the format of Open Request Cost Aggregation (ORCA).
 */
public final class OrcaUtil {

  private static final Logger logger = Logger.getLogger(OrcaUtil.class.getName());
  private static final ClientStreamTracer NOOP_CLIENT_STREAM_TRACER = new ClientStreamTracer() {};
  private static final ClientStreamTracer.Factory NOOP_CLIENT_STREAM_TRACER_FACTORY =
      new ClientStreamTracer.Factory() {
        @Override
        public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
          return NOOP_CLIENT_STREAM_TRACER;
        }
      };

  /**
   * Creates a new {@link ClientStreamTracer.Factory} with provided {@link OrcaReportListener}
   * installed to receive callback when a per-request ORCA report is received.
   *
   * @param listener contains the callback to be invoked when a per-request ORCA report is
   *     received.
   */
  public static ClientStreamTracer.Factory newOrcaClientStreamTracerFactory(
      OrcaReportListener listener) {
    return newOrcaClientStreamTracerFactory(NOOP_CLIENT_STREAM_TRACER_FACTORY, listener);
  }

  /**
   * Creates a new {@link ClientStreamTracer.Factory} with provided {@link OrcaReportListener}
   * installed to receive callback when a per-request ORCA report is received.
   *
   * @param delegate the delegate factory to produce other client stream tracing.
   * @param listener contains the callback to be invoked when a per-request ORCA report is
   *     received.
   */
  public static ClientStreamTracer.Factory newOrcaClientStreamTracerFactory(
      ClientStreamTracer.Factory delegate, OrcaReportListener listener) {
    return new OrcaReportingTracerFactory(delegate, listener);
  }

  /**
   * Creates a new {@link LoadBalancer.Helper} with provided {@link OrcaReportListener} installed to
   * receive callback when an out-of-band ORCA report is received.
   *
   * <p>Note the original {@code LoadBalancer} must call returned helper's
   * {@code Helper.createSubchannel()} from its SynchronizationContext, or it will throw.
   *
   * @param delegate the delegate helper that provides essentials for establishing subchannels to
   *     backends.
   * @param listener contains the callback to be invoked when an out-of-band ORCA report is
   *     received.
   * @param backoffPolicyProvider the provider of backoff policy used to backoff failure of ORCA
   *     service streaming.
   * @param stopwatchSupplier supplies stopwatch utility.
   * @param reportingConfig configuration for receiving out-of-band ORCA reports.
   */
  public static LoadBalancer.Helper newOrcaReportingHelper(
      LoadBalancer.Helper delegate,
      OrcaReportListener listener,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier,
      OrcaReportingConfig reportingConfig) {
    return new OrcaReportingHelper(delegate, listener, backoffPolicyProvider, stopwatchSupplier,
        reportingConfig);
  }

  /**
   * The listener interface for receiving backend ORCA reports. The class that is interested in
   * processing backend cost metrics implements this interface, and the object created with that
   * class is registered with a component, using methods in {@link OrcaUtil}. When an ORCA report is
   * received, that object's {@code onLoadReport} method is invoked.
   */
  public interface OrcaReportListener {

    /**
     * Invoked when an ORCA report is received.
     *
     * <p>For out-of-band reporting, the actual reporting might be more frequently and the reports
     * might contain more entries of named cost metrics than configured due to other load balancing
     * polices requesting for more frequent and detailed reports.
     */
    void onLoadReport(OrcaLoadReport report);
  }

  /**
   * An {@link OrcaReportingTracerFactory} wraps a delegated {@link ClientStreamTracer.Factory} with
   * additional functionality to produce {@link ClientStreamTracer} instances that extract
   * per-request ORCA reports and push to registered listeners for calls they trace.
   */
  @VisibleForTesting
  static final class OrcaReportingTracerFactory extends ClientStreamTracer.Factory {

    @VisibleForTesting
    static final CallOptions.Key<OrcaReportBroker> ORCA_REPORT_BROKER_KEY =
        CallOptions.Key.create("internal-orca-report-broker");
    @VisibleForTesting
    static final Metadata.Key<OrcaLoadReport> ORCA_ENDPOINT_LOAD_METRICS_KEY =
        Metadata.Key.of(
            "x-endpoint-load-metrics-bin",
            ProtoUtils.metadataMarshaller(OrcaLoadReport.getDefaultInstance()));

    private final ClientStreamTracer.Factory delegate;
    private final OrcaReportListener listener;

    OrcaReportingTracerFactory(
        ClientStreamTracer.Factory delegate, OrcaReportListener listener) {
      this.delegate = checkNotNull(delegate, "delegate");
      this.listener = checkNotNull(listener, "listener");
    }

    @Override
    public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
      OrcaReportBroker broker = info.getCallOptions().getOption(ORCA_REPORT_BROKER_KEY);
      boolean augmented = false;
      if (broker == null) {
        broker = new OrcaReportBroker();
        final StreamInfo currInfo = info;
        final OrcaReportBroker currBroker = broker;
        info = new ClientStreamTracer.StreamInfo() {
          @Override
          public Attributes getTransportAttrs() {
            return currInfo.getTransportAttrs();
          }

          @Override
          public CallOptions getCallOptions() {
            return currInfo.getCallOptions().withOption(ORCA_REPORT_BROKER_KEY, currBroker);
          }
        };
        augmented = true;
      }
      broker.addListener(listener);
      ClientStreamTracer tracer = delegate.newClientStreamTracer(info, headers);
      if (augmented) {
        final ClientStreamTracer currTracer = tracer;
        final OrcaReportBroker currBroker = broker;
        // The actual tracer that performs ORCA report deserialization.
        tracer = new ForwardingClientStreamTracer() {
          @Override
          protected ClientStreamTracer delegate() {
            return currTracer;
          }

          @Override
          public void inboundTrailers(Metadata trailers) {
            OrcaLoadReport report = trailers.get(ORCA_ENDPOINT_LOAD_METRICS_KEY);
            if (report != null) {
              currBroker.onReport(report);
            }
            delegate().inboundTrailers(trailers);
          }
        };
      }
      return tracer;
    }
  }

  /**
   * An {@link OrcaReportBroker} instance holds registered {@link OrcaReportListener}s and invoke
   * all of them when an {@link OrcaLoadReport} is received.
   */
  private static final class OrcaReportBroker {

    private final List<OrcaReportListener> listeners = new ArrayList<>();

    void addListener(OrcaReportListener listener) {
      listeners.add(listener);
    }

    void onReport(OrcaLoadReport report) {
      for (OrcaReportListener listener : listeners) {
        listener.onLoadReport(report);
      }
    }
  }

  /**
   * An {@link OrcaReportingHelper} wraps a delegated {@link LoadBalancer.Helper} with additional
   * functionality to manage RPCs for out-of-band ORCA reporting for each backend it establishes
   * connection to.
   */
  private static final class OrcaReportingHelper extends ForwardingLoadBalancerHelper {

    private static final CreateSubchannelArgs.Key<OrcaReportingState> ORCA_REPORTING_STATE_KEY =
        CreateSubchannelArgs.Key.create("internal-orca-reporting-state");
    private final LoadBalancer.Helper delegate;
    private final OrcaReportListener listener;
    private final SynchronizationContext syncContext;
    private final BackoffPolicy.Provider backoffPolicyProvider;
    private final Supplier<Stopwatch> stopwatchSupplier;
    private final OrcaReportingConfig orcaConfig;

    OrcaReportingHelper(LoadBalancer.Helper delegate,
        OrcaReportListener listener,
        BackoffPolicy.Provider backoffPolicyProvider,
        Supplier<Stopwatch> stopwatchSupplier,
        OrcaReportingConfig orcaConfig) {
      this.delegate = checkNotNull(delegate, "delegate");
      this.listener = checkNotNull(listener, "listener");
      this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
      this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatchSupplier");
      this.orcaConfig = checkNotNull(orcaConfig, "orcaConfig");
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
        // Only the root load balanceing policy instantiate an OrcaReportingState instance to
        // request for ORCA report from the backend that the delegated helper is trying to
        // create subchannel to.
        orcaState = new OrcaReportingState(new OrcaReportBroker(),
            args.getStateListener(),
            syncContext,
            delegate().getScheduledExecutorService());
        args = args.toBuilder().addOption(ORCA_REPORTING_STATE_KEY, orcaState).build();
        augmented = true;
      }
      orcaState.broker.addListener(listener);
      orcaState.setOrcaReportingConfig(orcaConfig);
      Subchannel subchannel;
      if (augmented) {
        subchannel = super.createSubchannel(args.toBuilder().setStateListener(orcaState).build());
        orcaState.init(subchannel);
      } else {
        subchannel = super.createSubchannel(args);
      }
      return subchannel;
    }

    /**
     * An {@link OrcaReportingState} is a client of ORCA service running on a single backend.
     *
     * <p>All methods are run from {@code syncContext}.
     */
    private final class OrcaReportingState implements SubchannelStateListener {

      private final SubchannelStateListener stateListener;
      private final SynchronizationContext syncContext;
      private final ScheduledExecutorService timeService;
      private final OrcaReportBroker broker;
      @Nullable
      private Subchannel subchannel;
      @Nullable
      private ChannelLogger subchannelLogger;
      @Nullable
      private BackoffPolicy backoffPolicy;
      @Nullable
      private OrcaReportingStream orcaRpc;
      private final Runnable retryTask = new Runnable() {
        @Override
        public void run() {
          startRpc();
        }
      };
      @Nullable
      private ScheduledHandle retryTimer;
      @Nullable
      private OrcaReportingConfig overallConfig;
      private ConnectivityStateInfo state = ConnectivityStateInfo.forNonError(IDLE);
      // True if server returned UNIMPLEMENTED.
      private boolean disabled;

      OrcaReportingState(
          OrcaReportBroker broker,
          SubchannelStateListener stateListener,
          SynchronizationContext syncContext,
          ScheduledExecutorService timeService) {
        this.broker = checkNotNull(broker, "broker");
        this.stateListener = checkNotNull(stateListener, "stateListener");
        this.syncContext = checkNotNull(syncContext, "syncContext");
        this.timeService = checkNotNull(timeService, "timeService");
      }

      void init(Subchannel subchannel) {
        checkState(this.subchannel == null, "init() already called");
        this.subchannel = checkNotNull(subchannel, "subchannel");
        this.subchannelLogger = checkNotNull(subchannel.getChannelLogger(), "subchannelLogger");
      }

      void setOrcaReportingConfig(OrcaReportingConfig config) {
        boolean reconfigured = false;
        // The overall config is the superset of existing config and new config requested by some
        // load balancing policy.
        if (overallConfig != null) {
          if (config.reportIntervalNanos < overallConfig.reportIntervalNanos) {
            overallConfig.reportIntervalNanos = config.reportIntervalNanos;
            reconfigured = true;
          }
          if (!overallConfig.costNames.isEmpty()
              && !overallConfig.costNames.containsAll(config.costNames)) {
            overallConfig.costNames.addAll(config.costNames);
            reconfigured = true;
          } else {
            overallConfig.costNames.clear();
          }
        } else {
          overallConfig = config;
          reconfigured = true;
        }
        if (reconfigured) {
          stopRpc("ORCA reporting reconfigured");
          adjustOrcaReporting();
        }
      }

      @Override
      public void onSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
        checkArgument(subchannel == this.subchannel, "Subchannel mismatch: %s vs %s", subchannel,
            this.subchannel);
        if (Objects.equal(state.getState(), READY) && !Objects.equal(newState.getState(), READY)) {
          // A connection was lost.  We will reset disabled flag because ORCA service
          // may be available on the new connection.
          disabled = false;
        }
        state = newState;
        adjustOrcaReporting();
        // Propagate subchannel state update to downstream listeners.
        stateListener.onSubchannelState(subchannel, newState);
      }

      void adjustOrcaReporting() {
        if (!disabled && orcaConfig != null && Objects.equal(state.getState(), READY)) {
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
            .add("connectivityState", state)
            .toString();
      }

      private class OrcaReportingStream extends ClientCall.Listener<OrcaLoadReport> {

        private final ClientCall<OrcaLoadReportRequest, OrcaLoadReport> call;
        private final Stopwatch stopwatch;
        private boolean callHasResponded;

        OrcaReportingStream(Channel channel, Stopwatch stopwatch) {
          call = checkNotNull(channel, "channel")
              .newCall(OpenRcaServiceGrpc.getStreamCoreMetricsMethod(), CallOptions.DEFAULT);
          this.stopwatch = checkNotNull(stopwatch, "stopwatch");
        }

        void start() {
          stopwatch.reset().start();
          call.start(this, new Metadata());
          call.sendMessage(OrcaLoadReportRequest.newBuilder()
              .setReportInterval(Durations.fromNanos(orcaConfig.getReportIntervalNanos()))
              .addAllRequestCostNames(orcaConfig.getCostNames())
              .build());
          call.halfClose();
          call.request(1);
        }

        @Override
        public void onMessage(final OrcaLoadReport response) {
          syncContext.execute(new Runnable() {
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
          syncContext.execute(new Runnable() {
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
          broker.onReport(response);
          call.request(1);
        }

        void handleStreamClosed(Status status) {
          if (Objects.equal(status.getCode(), Code.UNIMPLEMENTED)) {
            disabled = true;
            logger
                .log(Level.WARNING, "Backend {0} OpenRcaService is disabled. Server returned: {1}",
                    new Object[]{subchannel.getAllAddresses(), status});
            subchannelLogger.log(ChannelLogLevel.WARNING, "OpenRcaService disabled: {0}", status);
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
              "ORCA reporting stream closed with {0}, backoff in {1} ns", status,
              delayNanos <= 0 ? 0 : delayNanos);
          if (delayNanos <= 0) {
            startRpc();
          } else {
            checkState(!isRetryTimerPending(), "Retry double scheduled");
            retryTimer = syncContext
                .schedule(retryTask, delayNanos, TimeUnit.NANOSECONDS, timeService);
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

  /**
   * Configuration for out-of-band ORCA reporting service RPC.
   */
  public static final class OrcaReportingConfig {

    private long reportIntervalNanos;
    private Set<String> costNames;

    private OrcaReportingConfig(long reportIntervalNanos, Set<String> costNames) {
      this.reportIntervalNanos = reportIntervalNanos;
      this.costNames = checkNotNull(costNames, "costNames");
    }

    /**
     * Creates a new builder.
     */
    public static Builder newBuilder() {
      return new Builder();
    }

    /**
     * Returns the configured maximum interval of receiving out-of-band ORCA reports.
     */
    public long getReportIntervalNanos() {
      return reportIntervalNanos;
    }

    /**
     * Returns the set of configured cost metric names to be reported in ORCA report. If this is
     * empty, all known requests costs tracked by the load reporting agent will be returned.
     */
    public Set<String> getCostNames() {
      return Collections.unmodifiableSet(costNames);
    }

    /**
     * Returns a builder with the same initial values as this object.
     */
    public Builder toBuilder() {
      return newBuilder()
          .setReportInterval(reportIntervalNanos, TimeUnit.NANOSECONDS)
          .addCostNames(costNames);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("reportIntervalNanos", reportIntervalNanos)
          .add("costNames", costNames)
          .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(reportIntervalNanos, costNames);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof OrcaReportingConfig) {
        OrcaReportingConfig other = (OrcaReportingConfig) obj;
        return reportIntervalNanos == other.reportIntervalNanos
            && costNames.equals(other.costNames);
      }
      return false;
    }

    public static final class Builder {

      private long reportIntervalNanos;
      private Set<String> costNames = new HashSet<>();

      Builder() {
      }

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

      /**
       * Adds a custom named backend metric to be reported. This provides an opportunity for the
       * client to selectively obtain a subset of tracked costs.
       *
       * @param costName name for custom metric to be reported.
       */
      public Builder addCostName(String costName) {
        costNames.add(costName);
        return this;
      }

      /**
       * Adds a set of custom named backend metric to be reported. This provides an opportunity for
       * the client to selectively obtain a subset of tracked costs.
       *
       * @param costNames collection of names for custom metric to be reported.
       */
      public Builder addCostNames(Collection<String> costNames) {
        this.costNames.addAll(costNames);
        return this;
      }

      /**
       * Creates a new {@link OrcaReportingConfig} object.
       */
      public OrcaReportingConfig build() {
        return new OrcaReportingConfig(reportIntervalNanos, costNames);
      }
    }
  }
}
