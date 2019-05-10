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

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Duration;
import io.envoyproxy.envoy.api.v2.core.Locality;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats.DroppedRequests;
import io.envoyproxy.envoy.api.v2.endpoint.EndpointLoadMetricStats;
import io.envoyproxy.envoy.api.v2.endpoint.UpstreamLocalityStats;
import io.envoyproxy.udpa.data.orca.v1.OrcaLoadReport;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.util.ForwardingClientStreamTracer;
import io.grpc.xds.ClientLoadCounter.ClientLoadSnapshot;
import io.grpc.xds.ClientLoadCounter.MetricValue;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

/**
 * An {@link XdsLoadReportStore} instance holds the client side load stats for a cluster.
 */
@NotThreadSafe
final class XdsLoadReportStore {

  private final String clusterName;
  private final ConcurrentMap<Locality, ClientLoadCounter> localityLoadCounters;
  // Cluster level dropped request counts for each category specified in the DropOverload policy.
  private final ConcurrentMap<String, AtomicLong> dropCounters;

  XdsLoadReportStore(String clusterName) {
    this(clusterName, new ConcurrentHashMap<Locality, ClientLoadCounter>(),
        new ConcurrentHashMap<String, AtomicLong>());
  }

  @VisibleForTesting
  XdsLoadReportStore(String clusterName,
      ConcurrentMap<Locality, ClientLoadCounter> localityLoadCounters,
      ConcurrentMap<String, AtomicLong> dropCounters) {
    this.clusterName = checkNotNull(clusterName, "clusterName");
    this.localityLoadCounters = checkNotNull(localityLoadCounters, "localityLoadCounters");
    this.dropCounters = checkNotNull(dropCounters, "dropCounters");
  }

  /**
   * Generates a {@link ClusterStats} containing client side load stats and backend metrics
   * (if any) in locality granularity.
   * This method should be called in the same synchronized context that
   * {@link XdsLoadBalancer#helper#getSynchronizationContext} returns.
   */
  ClusterStats generateLoadReport(Duration interval) {
    ClusterStats.Builder statsBuilder = ClusterStats.newBuilder().setClusterName(clusterName)
        .setLoadReportInterval(interval);
    for (Map.Entry<Locality, ClientLoadCounter> entry : localityLoadCounters.entrySet()) {
      ClientLoadSnapshot snapshot = entry.getValue().snapshot();
      UpstreamLocalityStats.Builder localityStatsBuilder =
          UpstreamLocalityStats.newBuilder().setLocality(entry.getKey());
      localityStatsBuilder
          .setTotalSuccessfulRequests(snapshot.callsSucceed)
          .setTotalErrorRequests(snapshot.callsFailed)
          .setTotalRequestsInProgress(snapshot.callsInProgress);
      for (Map.Entry<String, MetricValue> metric : snapshot.metricValues.entrySet()) {
        localityStatsBuilder.addLoadMetricStats(
            EndpointLoadMetricStats.newBuilder()
                .setMetricName(metric.getKey())
                .setNumRequestsFinishedWithMetric(metric.getValue().numReports)
                .setTotalMetricValue(metric.getValue().totalValue));
      }
      statsBuilder.addUpstreamLocalityStats(localityStatsBuilder);
      // Discard counters for localities that are no longer exposed by the remote balancer and
      // no RPCs ongoing.
      if (!entry.getValue().isActive() && snapshot.callsInProgress == 0) {
        localityLoadCounters.remove(entry.getKey());
      }
    }
    for (Map.Entry<String, AtomicLong> entry : dropCounters.entrySet()) {
      statsBuilder.addDroppedRequests(DroppedRequests.newBuilder()
          .setCategory(entry.getKey())
          .setDroppedCount(entry.getValue().getAndSet(0)));
    }
    return statsBuilder.build();
  }

  /**
   * Create a {@link ClientLoadCounter} for the provided locality or make it active if already in
   * this {@link XdsLoadReportStore}. This method needs to be called at locality updates only for
   * newly assigned localities in balancer discovery responses.
   * This method should be called in the same synchronized context that
   * {@link XdsLoadBalancer#helper#getSynchronizationContext} returns.
   */
  void addLocality(final Locality locality) {
    ClientLoadCounter counter = localityLoadCounters.get(locality);
    checkState(counter == null || !counter.isActive(),
        "An active ClientLoadCounter for locality %s already exists", locality);
    if (counter == null) {
      localityLoadCounters.put(locality, new ClientLoadCounter());
    } else {
      counter.setActive(true);
    }
  }

  /**
   * Deactivate the {@link ClientLoadCounter} for the provided locality in by this
   * {@link XdsLoadReportStore}. Inactive {@link ClientLoadCounter}s are for localities
   * no longer exposed by the remote balancer. This method needs to be called at
   * locality updates only for localities newly removed from balancer discovery responses.
   * This method should be called in the same synchronized context that
   * {@link XdsLoadBalancer#helper#getSynchronizationContext} returns.
   */
  void removeLocality(final Locality locality) {
    ClientLoadCounter counter = localityLoadCounters.get(locality);
    checkState(counter != null && counter.isActive(),
        "No active ClientLoadCounter for locality %s exists", locality);
    counter.setActive(false);
  }

  /**
   * Returns the {@link ClientLoadCounter} instance that is responsible for aggregating load
   * stats for the provided locality, or {@code null} if the locality is untracked.
   */
  ClientLoadCounter getLocalityCounter(final Locality locality) {
    return localityLoadCounters.get(locality);
  }

  /**
   * Record that a request has been dropped by drop overload policy with the provided category
   * instructed by the remote balancer.
   */
  void recordDroppedRequest(String category) {
    AtomicLong counter = dropCounters.get(category);
    if (counter == null) {
      counter = dropCounters.putIfAbsent(category, new AtomicLong());
      if (counter == null) {
        counter = dropCounters.get(category);
      }
    }
    counter.getAndIncrement();
  }

  /**
   * An {@link XdsClientLoadRecorder} instance records and aggregates client-side load data into an
   * {@link ClientLoadCounter} object.
   */
  @ThreadSafe
  static final class XdsClientLoadRecorder extends ClientStreamTracer.Factory {

    private final ClientStreamTracer.Factory delegate;
    private final ClientLoadCounter counter;

    XdsClientLoadRecorder(ClientLoadCounter counter, ClientStreamTracer.Factory delegate) {
      this.counter = checkNotNull(counter, "counter");
      this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
      counter.incrementCallsInProgress();
      final ClientStreamTracer delegateTracer = delegate.newClientStreamTracer(info, headers);
      return new ForwardingClientStreamTracer() {
        @Override
        protected ClientStreamTracer delegate() {
          return delegateTracer;
        }

        @Override
        public void streamClosed(Status status) {
          counter.incrementCallsFinished();
          counter.decrementCallsInProgress();
          if (!status.isOk()) {
            counter.incrementCallsFailed();
          }
          delegate().streamClosed(status);
        }
      };
    }
  }

  // TODO (chengyuanzhang): implements OrcaPerRequestReportListener and OrcaOobReportListener
  // interfaces.
  @ThreadSafe
  static class MetricListener {
    private final ClientLoadCounter counter;

    MetricListener(ClientLoadCounter counter) {
      this.counter = checkNotNull(counter, "counter");
    }

    // TODO (chengyuanzhang): @Override
    void onLoadReport(OrcaLoadReport report) {
      counter.recordMetric("cpu_utilization", report.getCpuUtilization());
      counter.recordMetric("mem_utilization", report.getMemUtilization());
      for (Map.Entry<String, Double> entry : report.getRequestCostOrUtilizationMap().entrySet()) {
        counter.recordMetric(entry.getKey(), entry.getValue());
      }
    }
  }
}
