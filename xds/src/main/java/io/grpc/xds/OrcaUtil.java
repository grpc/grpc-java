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

import io.envoyproxy.udpa.data.orca.v1.OrcaLoadReport;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.LoadBalancer;
import io.grpc.Metadata;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.util.ForwardingClientStreamTracer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
   * installed to receive callback when an per-request ORCA report is received.
   *
   * @param listener contains the callback to be invoked when an per-request ORCA report is
   *     received.
   */
  public static ClientStreamTracer.Factory newOrcaClientStreamTracerFactory(
      OrcaReportListener listener) {
    return newOrcaClientStreamTracerFacotry(NOOP_CLIENT_STREAM_TRACER_FACTORY, listener);
  }

  /**
   * Creates a new {@link ClientStreamTracer.Factory} with provided {@link OrcaReportListener}
   * installed to receive callback when an per-request ORCA report is received.
   *
   * @param delegate the delegate factory to produce other client stream tracing.
   * @param listener contains the callback to be invoked when an per-request ORCA report is
   *     received.
   */
  public static ClientStreamTracer.Factory newOrcaClientStreamTracerFacotry(
      ClientStreamTracer.Factory delegate, OrcaReportListener listener) {
    return new OrcaClientStreamTracerFactory(delegate, listener);
  }

  /**
   * Creates a new {@link LoadBalancer.Helper} with provided {@link OrcaReportListener} installed to
   * receive callback when an out-of-band ORCA report is received.
   *
   * @param delegate the delegate helper that provides essentials for establishing subchannels to
   *     backends.
   * @param listener contains the callback to be invoked when an out-of-band ORCA report is
   *     received.
   * @param maxReportInterval the maximum expected interval of receiving periodical ORCA reports.
   * @param unit time unit of {@code maxReportInterval} value.
   */
  public static LoadBalancer.Helper newOrcaReportingHelper(
      LoadBalancer.Helper delegate,
      OrcaReportListener listener,
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

  private static final class OrcaClientStreamTracerFactory extends ClientStreamTracer.Factory {

    private static final CallOptions.Key<OrcaReportBroker> ORCA_REPORT_BROKER_KEY =
        CallOptions.Key.create("internal-orca-report-broker");
    private static final Metadata.Key<OrcaLoadReport> ORCA_ENDPOINT_LOAD_METRICS_KEY =
        Metadata.Key.of(
            "X-Endpoint-Load-Metrics-Bin",
            ProtoUtils.metadataMarshaller(OrcaLoadReport.getDefaultInstance()));

    private final ClientStreamTracer.Factory delegate;
    private final OrcaReportListener listener;

    OrcaClientStreamTracerFactory(
        ClientStreamTracer.Factory delegate, OrcaReportListener listener) {
      this.delegate = delegate;
      this.listener = listener;
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
}
