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

package io.grpc.netty;

import com.google.common.collect.ImmutableList;
import io.grpc.DoubleHistogramMetricInstrument;
import io.grpc.LongCounterMetricInstrument;
import io.grpc.LongUpDownCounterMetricInstrument;
import io.grpc.MetricInstrumentRegistry;
import io.grpc.MetricRecorder;
import io.netty.channel.Channel;
import java.lang.reflect.Method;
import java.util.Collections;

final class TcpMetrics {

  static final LongCounterMetricInstrument connectionsCreated;
  static final LongUpDownCounterMetricInstrument connectionCount;
  static final LongCounterMetricInstrument packetsRetransmitted;
  static final LongCounterMetricInstrument recurringRetransmits;
  static final DoubleHistogramMetricInstrument minRtt;

  // Note: Metrics like delivery_rate, bytes_sent, packets_sent,
  // bytes_retransmitted, etc., are not
  // currently exposed by Netty's EpollTcpInfo.java wrapper around
  // getSockOpt(TCP_INFO)."
  static {
    MetricInstrumentRegistry registry = MetricInstrumentRegistry.getDefaultRegistry();
    ImmutableList<String> requiredLabels = ImmutableList.of("grpc.target");
    ImmutableList<String> optionalLabels = ImmutableList.of(
        "network.local.address",
        "network.local.port",
        "network.peer.address",
        "network.peer.port"
    );
    
    connectionsCreated = registry.registerLongCounter(
        "grpc.tcp.connections_created",
        "Number of TCP connections created.",
        "{connection}",
        requiredLabels,
        optionalLabels,
        false
    );

    connectionCount = registry.registerLongUpDownCounter(
        "grpc.tcp.connection_count",
        "Number of active TCP connections.",
        "{connection}",
        requiredLabels,
        optionalLabels,
        false
    );

    packetsRetransmitted = registry.registerLongCounter(
        "grpc.tcp.packets_retransmitted",
        "Total packets sent by TCP except those sent for the first time.",
        "{packet}",
        requiredLabels,
        optionalLabels,
        false
    );

    recurringRetransmits = registry.registerLongCounter(
        "grpc.tcp.recurring_retransmits",
        "The number of times the latest TCP packet was retransmitted.",
        "{packet}",
        requiredLabels,
        optionalLabels,
        false
    );

    minRtt = registry.registerDoubleHistogram(
        "grpc.tcp.min_rtt",
        "TCP's current estimate of minimum round trip time (RTT).",
        "s",
        ImmutableList.of(
           0.0001, 0.0005, 0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1.0, 5.0, 10.0, 50.0, 100.0, 500.0,
           1000.0
        ),
        requiredLabels,
        optionalLabels,
        false
    );
  }


  static final class Tracker {
    private final MetricRecorder metricRecorder;
    private final String target;

    Tracker(MetricRecorder metricRecorder, String target) {
      this.metricRecorder = metricRecorder;
      this.target = target;
    }

    private static final long RECORD_INTERVAL_MILLIS;

    static {
      long interval = 5;
      try {
        String flagValue = System.getProperty("io.grpc.netty.tcpMetricsRecordIntervalMinutes");
        if (flagValue != null) {
          interval = Long.parseLong(flagValue);
        }
      } catch (NumberFormatException e) {
        // Use default
      }
      RECORD_INTERVAL_MILLIS = java.util.concurrent.TimeUnit.MINUTES.toMillis(interval);
    }

    private static final java.util.Random RANDOM = new java.util.Random();
    private io.netty.util.concurrent.ScheduledFuture<?> reportTimer;

    void channelActive(Channel channel) {
      if (metricRecorder != null && target != null) {
        java.util.List<String> labelValues = getLabelValues(channel);
        metricRecorder.addLongCounter(TcpMetrics.connectionsCreated, 1,
            Collections.singletonList(target), labelValues);
        metricRecorder.addLongUpDownCounter(TcpMetrics.connectionCount, 1,
            Collections.singletonList(target), labelValues);
        scheduleNextReport(channel);
      }
    }

    private void scheduleNextReport(final Channel channel) {
      if (RECORD_INTERVAL_MILLIS <= 0) {
        return;
      }
      if (!channel.isActive()) {
        return;
      }

      double jitter = 0.1 + RANDOM.nextDouble(); // 10% to 110%
      long delayMillis = (long) (RECORD_INTERVAL_MILLIS * jitter);

      try {
        reportTimer = channel.eventLoop().schedule(new Runnable() {
          @Override
          public void run() {
            if (channel.isActive()) {
              Tracker.this.recordTcpInfo(channel); // Renamed from channelInactive to recordTcpInfo
              scheduleNextReport(channel); // Re-arm
            }
          }
        }, delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
      } catch (Throwable t) {
        // Channel closed, event loop shut down, etc.
      }
    }

    void channelInactive(Channel channel) {
      if (reportTimer != null) {
        reportTimer.cancel(false);
      }
      if (metricRecorder != null && target != null) {
        java.util.List<String> labelValues = getLabelValues(channel);
        metricRecorder.addLongUpDownCounter(TcpMetrics.connectionCount, -1,
            Collections.singletonList(target), labelValues);
        // Final collection on close
        recordTcpInfo(channel);
      }
    }

    private void recordTcpInfo(Channel channel) {
      if (metricRecorder == null || target == null) {
        return;
      }
      java.util.List<String> labelValues = getLabelValues(channel);
      try {
        if (channel.getClass().getName().equals("io.netty.channel.epoll.EpollSocketChannel")) {
          Method tcpInfoMethod = channel.getClass().getMethod("tcpInfo",
              Class.forName("io.netty.channel.epoll.EpollTcpInfo"));
          Object info = Class.forName("io.netty.channel.epoll.EpollTcpInfo")
              .getDeclaredConstructor().newInstance();
          tcpInfoMethod.invoke(channel, info);

          Method totalRetransMethod = info.getClass().getMethod("totalRetrans");
          Method retransmitsMethod = info.getClass().getMethod("retransmits");
          Method rttMethod = info.getClass().getMethod("rtt");

          long totalRetrans = (Long) totalRetransMethod.invoke(info);
          int retransmits = (Integer) retransmitsMethod.invoke(info);
          long rtt = (Long) rttMethod.invoke(info);

          metricRecorder.addLongCounter(TcpMetrics.packetsRetransmitted, totalRetrans,
              Collections.singletonList(target), labelValues);
          metricRecorder.addLongCounter(TcpMetrics.recurringRetransmits, retransmits,
              Collections.singletonList(target), labelValues);
          metricRecorder.recordDoubleHistogram(TcpMetrics.minRtt,
              rtt / 1000000.0, // Convert microseconds to seconds
              Collections.singletonList(target), labelValues);
        }
      } catch (Throwable t) {
        // Epoll not available or error getting tcp_info, just ignore.
      }
    }
  }

  private static java.util.List<String> getLabelValues(Channel channel) {
    String localAddress = "";
    String localPort = "";
    String peerAddress = "";
    String peerPort = "";
    
    java.net.SocketAddress local = channel.localAddress();
    if (local instanceof java.net.InetSocketAddress) {
      java.net.InetSocketAddress inetLocal = (java.net.InetSocketAddress) local;
      localAddress = inetLocal.getAddress().getHostAddress();
      localPort = String.valueOf(inetLocal.getPort());
    }
    
    java.net.SocketAddress remote = channel.remoteAddress();
    if (remote instanceof java.net.InetSocketAddress) {
      java.net.InetSocketAddress inetRemote = (java.net.InetSocketAddress) remote;
      peerAddress = inetRemote.getAddress().getHostAddress();
      peerPort = String.valueOf(inetRemote.getPort());
    }
    
    return java.util.Arrays.asList(localAddress, localPort, peerAddress, peerPort);
  }


  private TcpMetrics() {}
}
