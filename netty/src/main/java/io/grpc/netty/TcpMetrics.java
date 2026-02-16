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

import io.grpc.DoubleHistogramMetricInstrument;
import io.grpc.LongCounterMetricInstrument;
import io.grpc.LongUpDownCounterMetricInstrument;
import io.grpc.MetricInstrument;
import io.grpc.MetricInstrumentRegistry;
import io.grpc.MetricRecorder;
import io.netty.channel.Channel;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class TcpMetrics {

  private static final Metrics DEFAULT_METRICS;

  static {
    boolean epollAvailable = false;
    try {
      Class<?> epollClass = Class.forName("io.netty.channel.epoll.Epoll");
      Method isAvailableMethod = epollClass.getDeclaredMethod("isAvailable");
      epollAvailable = (Boolean) isAvailableMethod.invoke(null);
    } catch (Throwable t) {
      // Ignored
    }
    DEFAULT_METRICS = new Metrics(MetricInstrumentRegistry.getDefaultRegistry(), epollAvailable);
  }

  static Metrics getDefaultMetrics() {
    return DEFAULT_METRICS;
  }

  static final class Metrics {
    final LongCounterMetricInstrument connectionsCreated;
    final LongUpDownCounterMetricInstrument connectionCount;
    final LongCounterMetricInstrument packetsRetransmitted;
    final LongCounterMetricInstrument recurringRetransmits;
    final DoubleHistogramMetricInstrument minRtt;

    Metrics(MetricInstrumentRegistry registry, boolean epollAvailable) {
      List<String> requiredLabels = Collections.singletonList("grpc.target");
      List<String> optionalLabels = Arrays.asList(
          "network.local.address",
          "network.local.port",
          "network.peer.address",
          "network.peer.port");

      connectionsCreated = safelyRegisterLongCounter(registry,
          "grpc.tcp.connections_created",
          "Number of TCP connections created.",
          "{connection}",
          requiredLabels,
          optionalLabels);

      connectionCount = safelyRegisterLongUpDownCounter(registry,
          "grpc.tcp.connection_count",
          "Number of currently open TCP connections.",
          "{connection}",
          requiredLabels,
          optionalLabels);

      if (epollAvailable) {
        packetsRetransmitted = safelyRegisterLongCounter(registry,
            "grpc.tcp.packets_retransmitted",
            "Total number of packets retransmitted for a single TCP connection.",
            "{packet}",
            requiredLabels,
            optionalLabels);

        recurringRetransmits = safelyRegisterLongCounter(registry,
            "grpc.tcp.recurring_retransmits",
            "Total number of unacknowledged packets to be retransmitted "
                + "since the last acknowledgment.",
            "{packet}",
            requiredLabels,
            optionalLabels);

        minRtt = safelyRegisterDoubleHistogram(registry,
            "grpc.tcp.min_rtt",
            "Minimum RTT observed for a single TCP connection.",
            "s",
            Arrays.asList(0.0001, 0.0005, 0.001, 0.005, 0.01, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5,
                5.0, 10.0, 25.0, 50.0, 100.0, 250.0),
            requiredLabels,
            optionalLabels);
      } else {
        packetsRetransmitted = null;
        recurringRetransmits = null;
        minRtt = null;
      }
    }
  }

  /**
   * Safe metric registration or retrieval for environments where TcpMetrics might
   * be loaded multiple times (e.g., shaded and unshaded).
   */
  private static LongCounterMetricInstrument safelyRegisterLongCounter(
      MetricInstrumentRegistry registry, String name, String description, String unit,
      List<String> requiredLabelKeys, List<String> optionalLabelKeys) {
    try {
      return registry.registerLongCounter(name, description, unit, requiredLabelKeys,
          optionalLabelKeys, false);
    } catch (IllegalStateException e) {
      if (e.getMessage() != null && e.getMessage().contains("already exists")) {
        for (MetricInstrument instrument : registry.getMetricInstruments()) {
          if (instrument.getName().equals(name)
              && instrument instanceof LongCounterMetricInstrument) {
            return (LongCounterMetricInstrument) instrument;
          }
        }
      }
      throw e;
    }
  }

  private static LongUpDownCounterMetricInstrument safelyRegisterLongUpDownCounter(
      MetricInstrumentRegistry registry, String name, String description, String unit,
      List<String> requiredLabelKeys, List<String> optionalLabelKeys) {
    try {
      return registry.registerLongUpDownCounter(name, description, unit, requiredLabelKeys,
          optionalLabelKeys, false);
    } catch (IllegalStateException e) {
      if (e.getMessage() != null && e.getMessage().contains("already exists")) {
        for (MetricInstrument instrument : registry.getMetricInstruments()) {
          if (instrument.getName().equals(name)
              && instrument instanceof LongUpDownCounterMetricInstrument) {
            return (LongUpDownCounterMetricInstrument) instrument;
          }
        }
      }
      throw e;
    }
  }

  private static DoubleHistogramMetricInstrument safelyRegisterDoubleHistogram(
      MetricInstrumentRegistry registry, String name, String description, String unit,
      List<Double> bucketBoundaries, List<String> requiredLabelKeys,
      List<String> optionalLabelKeys) {
    try {
      return registry.registerDoubleHistogram(name, description, unit, bucketBoundaries,
          requiredLabelKeys, optionalLabelKeys, false);
    } catch (IllegalStateException e) {
      if (e.getMessage() != null && e.getMessage().contains("already exists")) {
        for (MetricInstrument instrument : registry.getMetricInstruments()) {
          if (instrument.getName().equals(name)
              && instrument instanceof DoubleHistogramMetricInstrument) {
            return (DoubleHistogramMetricInstrument) instrument;
          }
        }
      }
      throw e;
    }
  }

  static final class Tracker {
    private final MetricRecorder metricRecorder;
    private final String target;
    private final Metrics metrics;
    private final String epollSocketChannelClassName;
    private final String epollTcpInfoClassName;

    Tracker(MetricRecorder metricRecorder, String target) {
      this(metricRecorder, target, DEFAULT_METRICS);
    }

    Tracker(MetricRecorder metricRecorder, String target, Metrics metrics) {
      this(metricRecorder, target, metrics,
          "io.netty.channel.epoll.EpollSocketChannel",
          "io.netty.channel.epoll.EpollTcpInfo");
    }

    Tracker(MetricRecorder metricRecorder, String target, Metrics metrics,
        String epollSocketChannelClassName, String epollTcpInfoClassName) {
      this.metricRecorder = metricRecorder;
      this.target = target;
      this.metrics = metrics;
      this.epollSocketChannelClassName = epollSocketChannelClassName;
      this.epollTcpInfoClassName = epollTcpInfoClassName;
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
        metricRecorder.addLongCounter(metrics.connectionsCreated, 1,
            Collections.singletonList(target), labelValues);
        metricRecorder.addLongUpDownCounter(metrics.connectionCount, 1,
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
        metricRecorder.addLongUpDownCounter(metrics.connectionCount, -1,
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
        if (channel.getClass().getName().equals(epollSocketChannelClassName)) {
          Class<?> tcpInfoClass = Class.forName(epollTcpInfoClassName);
          Method tcpInfoMethod = channel.getClass().getMethod("tcpInfo", tcpInfoClass);
          Object info = tcpInfoClass.getDeclaredConstructor().newInstance();
          tcpInfoMethod.invoke(channel, info);

          Method totalRetransMethod = tcpInfoClass.getMethod("totalRetrans");
          Method retransmitsMethod = tcpInfoClass.getMethod("retransmits");
          Method rttMethod = tcpInfoClass.getMethod("rtt");

          long totalRetrans = (Long) totalRetransMethod.invoke(info);
          int retransmits = (Integer) retransmitsMethod.invoke(info);
          long rtt = (Long) rttMethod.invoke(info);

          if (metrics.packetsRetransmitted != null) {
            metricRecorder.addLongCounter(metrics.packetsRetransmitted, totalRetrans,
                Collections.singletonList(target), labelValues);
          }
          if (metrics.recurringRetransmits != null) {
            metricRecorder.addLongCounter(metrics.recurringRetransmits, retransmits,
                Collections.singletonList(target), labelValues);
          }
          if (metrics.minRtt != null) {
            metricRecorder.recordDoubleHistogram(metrics.minRtt,
                rtt / 1000000.0, // Convert microseconds to seconds
                Collections.singletonList(target), labelValues);
          }
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
