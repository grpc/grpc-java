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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Stopwatch;
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
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.util.ForwardingClientStreamTracer;
import io.grpc.util.ForwardingLoadBalancerHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Utility class that provides method for {@link LoadBalancer} to install listeners to receive
 * backend cost metrics in the format of Open Request Cost Aggregation (ORCA).
 */
public final class OrcaUtil {

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
   * @param delegate the delegate helper that provides essentials for establishing subchannels to
   *     backends.
   * @param listener contains the callback to be invoked when an out-of-band ORCA report is
   *     received.
   * @param backoffPolicyProvider the provider of backoff policy used to backoff failure of ORCA
   *     service streaming.
   * @param maxReportInterval the maximum expected interval of receiving periodical ORCA reports.
   * @param unit time unit of {@code maxReportInterval} value.
   */
  public static LoadBalancer.Helper newOrcaReportingHelper(
      LoadBalancer.Helper delegate,
      OrcaReportListener listener,
      BackoffPolicy.Provider backoffPolicyProvider,
      long maxReportInterval,
      TimeUnit unit) {
    // TODO(chengyuanzhang): create a LoadReportingHelper that intercepts createSubChannel() method.
    // This requires OOB ORCA service impl to be done first in order to create an OrcaClient.
    return null;
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
     */
    void onLoadReport(OrcaLoadReport report);
  }

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
  @VisibleForTesting
  static final class OrcaReportBroker {

    private final List<OrcaReportListener> listeners = new ArrayList<>();

    void addListener(OrcaReportListener listener) {
      listeners.add(listener);
    }

    @VisibleForTesting
    List<OrcaReportListener> getListeners() {
      return Collections.unmodifiableList(listeners);
    }

    void onReport(OrcaLoadReport report) {
      for (OrcaReportListener listener : listeners) {
        listener.onLoadReport(report);
      }
    }
  }


  /**
   * An {@link OrcaReportingHelper} wraps a delegated {@link LoadBalancer.Helper} with additional
   * functionality to manages ORCA reporting processes to all the backends of the service.
   */
  private static final class OrcaReportingHelper extends ForwardingLoadBalancerHelper {
    private final LoadBalancer.Helper delegate;
    private final OrcaReportListener listener;
    private final SynchronizationContext syncContext;
    private final BackoffPolicy.Provider backoffPolicyProvider;
    private final Set<OrcaReportingState> orcaReportingStates = new HashSet<>();

    OrcaReportingHelper(LoadBalancer.Helper delegate,
        OrcaReportListener listener,
        BackoffPolicy.Provider backoffPolicyProvider) {
      this.delegate = checkNotNull(delegate, "delegate");
      this.listener = checkNotNull(listener, "listener");
      this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
      syncContext = checkNotNull(delegate.getSynchronizationContext(), "syncContext");
    }

    @Override
    protected Helper delegate() {
      return delegate;
    }

    @Override
    public Subchannel createSubchannel(CreateSubchannelArgs args) {
      syncContext.throwIfNotInThisSynchronizationContext();
      return super.createSubchannel(args);
    }
  }

  /**
   * An {@link OrcaReportingState} is the client of {@link OpenRcaServiceGrpc} for a single backend.
   */
  private static final class OrcaReportingState implements SubchannelStateListener {
    private final Runnable retryTask = new Runnable() {
      @Override
      public void run() {
        startRpc();
      }
    };

    private final SubchannelStateListener stateListener;
    private final SynchronizationContext syncContext;
    private final ScheduledExecutorService timeService;

    private Subchannel subchannel;
    private ChannelLogger subchannelLogger;
    private String serviceName;
    private BackoffPolicy backoffPolicy;

    @Nullable
    private OrcaReportingStream activeRpc;
    @Nullable
    private ScheduledHandle retryTimer;

    OrcaReportingState(SubchannelStateListener stateListener,
        SynchronizationContext syncContext,
        ScheduledExecutorService timeService) {
      this.stateListener = checkNotNull(stateListener, "stateListener");
      this.syncContext = checkNotNull(syncContext, "syncContext");
      this.timeService = checkNotNull(timeService, "timeService");
    }

    void init(Subchannel subchannel) {
      checkState(this.subchannel == null, "init() already called");
      this.subchannel = checkNotNull(subchannel, "subchannel");
      this.subchannelLogger = checkNotNull(subchannel.getChannelLogger(), "subchannelLogger");
    }

    void setServiceName(@Nullable String serviceName) {
      if (Objects.equals(serviceName, this.serviceName)) {
        return;
      }
      this.serviceName = serviceName;

    }

    @Override
    public void onSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
      checkArgument(subchannel == this.subchannel, "Subchannel mismatch: %s vs %s", subchannel, this.subchannel);

    }

    private void adjustOrcaReporting() {

    }

    private void startRpc() {

    }

    private void stopRpc() {

    }

    /**
     * An {@link OrcaReportingStream} represents the ORCA service connection between the client
     * and a single backend.
     */
    private class OrcaReportingStream extends ClientCall.Listener<OrcaLoadReport> {
      private final ClientCall<OrcaLoadReportRequest, OrcaLoadReport> call;
      private final String callServiceName;
      private final Stopwatch stopwatch;
      private boolean callHasResponded;

      OrcaReportingStream(String serviceName, Channel channel, Stopwatch stopwatch) {
        this.callServiceName = checkNotNull(serviceName, "serviceName");
        // FIXME: is this really the most appropriate time to start the stopwatch?
        this.stopwatch = checkNotNull(stopwatch, "stopwatch").reset().start();
        call = checkNotNull(channel, "channel").newCall(OpenRcaServiceGrpc.getStreamCoreMetricsMethod(), CallOptions.DEFAULT);
      }

      void start() {
        call.start(this, new Metadata());
        // TODO: set report interval and request cost names in the request message.
        call.sendMessage(OrcaLoadReportRequest.newBuilder().build());
        call.halfClose();
        call.request(1);
      }

      void cancel(String msg) {
        call.cancel(msg, null);
      }

      @Override
      public void onMessage(OrcaLoadReport message) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {

          }
        });
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {

          }
        });
      }

      void handleResponse(OrcaLoadReport reponse) {
        callHasResponded = true;

        // TODO: invoke registered listeners
        call.request(1);
      }

      void handleStreamClosed(Status status) {

      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("serviceName", callServiceName)
            .add("callStarted", call != null)
            .toString();
      }


    }




  }
}
