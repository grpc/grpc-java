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

import com.github.udpa.udpa.data.orca.v1.OrcaLoadReport;
import com.google.common.annotations.VisibleForTesting;
import io.grpc.CallOptions;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.LoadBalancer;
import io.grpc.Metadata;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.util.ForwardingClientStreamTracer;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class that provides method for {@link LoadBalancer} to install listeners to receive
 * per-request backend cost metrics in the format of Open Request Cost Aggregation (ORCA).
 */
abstract class OrcaPerRequestUtil {
  private static final ClientStreamTracer NOOP_CLIENT_STREAM_TRACER = new ClientStreamTracer() {};
  private static final ClientStreamTracer.Factory NOOP_CLIENT_STREAM_TRACER_FACTORY =
      new ClientStreamTracer.Factory() {
        @Override
        public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
          return NOOP_CLIENT_STREAM_TRACER;
        }
      };
  private static final OrcaPerRequestUtil DEFAULT_INSTANCE =
      new OrcaPerRequestUtil() {
        @Override
        public ClientStreamTracer.Factory newOrcaClientStreamTracerFactory(
            OrcaPerRequestReportListener listener) {
          return newOrcaClientStreamTracerFactory(NOOP_CLIENT_STREAM_TRACER_FACTORY, listener);
        }

        @Override
        public ClientStreamTracer.Factory newOrcaClientStreamTracerFactory(
            ClientStreamTracer.Factory delegate, OrcaPerRequestReportListener listener) {
          return new OrcaReportingTracerFactory(delegate, listener);
        }
      };

  /**
   * Gets an {@code OrcaPerRequestUtil} instance that provides actual implementation of
   * {@link #newOrcaClientStreamTracerFactory}.
   */
  public static OrcaPerRequestUtil getInstance() {
    return DEFAULT_INSTANCE;
  }

  /**
   * Creates a new {@link ClientStreamTracer.Factory} with provided {@link
   * OrcaPerRequestReportListener} installed to receive callback when a per-request ORCA report is
   * received.
   *
   * <p>Example usages for leaf level policy (e.g., WRR policy)
   *
   * <pre>
   *   {@code
   *   class WrrPicker extends SubchannelPicker {
   *
   *     public PickResult pickSubchannel(PickSubchannelArgs args) {
   *       Subchannel subchannel = ...  // WRR picking logic
   *       return PickResult.withSubchannel(
   *           subchannel,
   *           OrcaPerRequestReportUtil.getInstance().newOrcaClientStreamTracerFactory(listener));
   *     }
   *   }
   *   }
   * </pre>
   *
   * @param listener contains the callback to be invoked when a per-request ORCA report is received.
   */
  public abstract ClientStreamTracer.Factory newOrcaClientStreamTracerFactory(
      OrcaPerRequestReportListener listener);

  /**
   * Creates a new {@link ClientStreamTracer.Factory} with provided {@link
   * OrcaPerRequestReportListener} installed to receive callback when a per-request ORCA report is
   * received.
   *
   * <p>Example usages:
   *
   * <ul>
   *   <li> Delegating policy (e.g., xDS)
   *     <pre>
   *       {@code
   *       class XdsPicker extends SubchannelPicker {
   *
   *         public PickResult pickSubchannel(PickSubchannelArgs args) {
   *           SubchannelPicker perLocalityPicker = ...  // locality picking logic
   *           Result result = perLocalityPicker.pickSubchannel(args);
   *           return PickResult.withSubchannel(
   *               result.getSubchannel(),
   *               OrcaPerRequestReportUtil.getInstance().newOrcaClientTracerFactory(
   *                   result.getStreamTracerFactory(), listener));
   *
   *         }
   *       }
   *       }
   *     </pre>
   *   </li>
   *   <li> Delegating policy with additional tracing logic
   *     <pre>
   *       {@code
   *       class WrappingPicker extends SubchannelPicker {
   *
   *         public PickResult pickSubchannel(PickSubchannelArgs args) {
   *           Result result = delegate.pickSubchannel(args);
   *           return PickResult.withSubchannel(
   *               result.getSubchannel(),
   *               new ClientStreamTracer.Factory() {
   *                 public ClientStreamTracer newClientStreamTracer(
   *                     StreamInfo info, Metadata metadata) {
   *                   ClientStreamTracer.Factory orcaTracerFactory =
   *                       OrcaPerRequestReportUtil.getInstance().newOrcaClientStreamTracerFactory(
   *                           result.getStreamTracerFactory(), listener);
   *
   *                   // Wrap the tracer from the delegate factory if you need to trace the
   *                   // stream for your own.
   *                   final ClientStreamTracer orcaTracer =
   *                       orcaTracerFactory.newClientStreamTracer(info, metadata);
   *
   *                   return ForwardingClientStreamTracer() {
   *                     protected ClientStreamTracer delegate() {
   *                       return orcaTracer;
   *                     }
   *
   *                     public void inboundMessage(int seqNo) {
   *                       // Handle this event.
   *                       ...
   *                     }
   *                   };
   *                 }
   *               });
   *         }
   *       }
   *       }
   *     </pre>
   *   </li>
   * </ul>
   *
   * @param delegate the delegate factory to produce other client stream tracing.
   * @param listener contains the callback to be invoked when a per-request ORCA report is received.
   */
  public abstract ClientStreamTracer.Factory newOrcaClientStreamTracerFactory(
      ClientStreamTracer.Factory delegate, OrcaPerRequestReportListener listener);

  /**
   * The listener interface for receiving per-request ORCA reports from backends. The class that is
   * interested in processing backend cost metrics implements this interface, and the object created
   * with that class is registered with a component, using methods in {@link OrcaPerRequestUtil}.
   * When an ORCA report is received, that object's {@code onLoadReport} method is invoked.
   */
  public interface OrcaPerRequestReportListener {

    /**
     * Invoked when an per-request ORCA report is received.
     *
     * <p>Note this callback will be invoked from the network thread as the RPC finishes,
     * implementations should not block.
     *
     * @param report load report in the format of ORCA format.
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
    static final Metadata.Key<OrcaLoadReport> ORCA_ENDPOINT_LOAD_METRICS_KEY =
        Metadata.Key.of(
            "x-endpoint-load-metrics-bin",
            ProtoUtils.metadataMarshaller(OrcaLoadReport.getDefaultInstance()));

    private static final CallOptions.Key<OrcaReportBroker> ORCA_REPORT_BROKER_KEY =
        CallOptions.Key.create("internal-orca-report-broker");
    private final ClientStreamTracer.Factory delegate;
    private final OrcaPerRequestReportListener listener;

    OrcaReportingTracerFactory(
        ClientStreamTracer.Factory delegate, OrcaPerRequestReportListener listener) {
      this.delegate = checkNotNull(delegate, "delegate");
      this.listener = checkNotNull(listener, "listener");
    }

    @Override
    public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
      OrcaReportBroker broker = info.getCallOptions().getOption(ORCA_REPORT_BROKER_KEY);
      boolean augmented = false;
      if (broker == null) {
        broker = new OrcaReportBroker();
        info =
            info.toBuilder()
                .setCallOptions(info.getCallOptions().withOption(ORCA_REPORT_BROKER_KEY, broker))
                .build();
        augmented = true;
      }
      broker.addListener(listener);
      ClientStreamTracer tracer = delegate.newClientStreamTracer(info, headers);
      if (augmented) {
        final ClientStreamTracer currTracer = tracer;
        final OrcaReportBroker currBroker = broker;
        // The actual tracer that performs ORCA report deserialization.
        tracer =
            new ForwardingClientStreamTracer() {
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
   * A container class to hold registered {@link OrcaPerRequestReportListener}s and invoke all of
   * them when an {@link OrcaLoadReport} is received.
   */
  private static final class OrcaReportBroker {

    private final List<OrcaPerRequestReportListener> listeners = new ArrayList<>();

    void addListener(OrcaPerRequestReportListener listener) {
      listeners.add(listener);
    }

    void onReport(OrcaLoadReport report) {
      for (OrcaPerRequestReportListener listener : listeners) {
        listener.onLoadReport(report);
      }
    }
  }
}
