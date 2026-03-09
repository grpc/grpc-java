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
import io.grpc.InternalTcpMetrics;
import io.grpc.LongCounterMetricInstrument;
import io.grpc.LongUpDownCounterMetricInstrument;
import io.grpc.MetricRecorder;
import io.netty.channel.Channel;
import io.netty.util.concurrent.ScheduledFuture;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility for collecting TCP metrics from Netty channels.
 */
final class TcpMetrics {
  private static final Logger log = Logger.getLogger(TcpMetrics.class.getName());

  private static final Metrics DEFAULT_METRICS;

  static {
    boolean epollAvailable = false;
    try {
      Class<?> epollClass = Class.forName("io.netty.channel.epoll.Epoll");
      Method isAvailableMethod = epollClass.getDeclaredMethod("isAvailable");
      epollAvailable = (Boolean) isAvailableMethod.invoke(null);
    } catch (ClassNotFoundException e) {
      log.log(Level.FINE, "Epoll is not available", e);
    } catch (Exception e) {
      log.log(Level.FINE, "Failed to determine Epoll availability", e);
    } catch (Error e) {
      log.log(Level.FINE, "Failed to load native Epoll library", e);
    }
    log.log(Level.INFO, "Epoll available during static init of TcpMetrics:"
        + "{0}", epollAvailable);
    DEFAULT_METRICS = new Metrics(epollAvailable);
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

    Metrics(boolean epollAvailable) {
      connectionsCreated = InternalTcpMetrics.CONNECTIONS_CREATED_INSTRUMENT;
      connectionCount = InternalTcpMetrics.CONNECTION_COUNT_INSTRUMENT;

      if (epollAvailable) {
        packetsRetransmitted = InternalTcpMetrics.PACKETS_RETRANSMITTED_INSTRUMENT;
        recurringRetransmits = InternalTcpMetrics.RECURRING_RETRANSMITS_INSTRUMENT;
        minRtt = InternalTcpMetrics.MIN_RTT_INSTRUMENT;
      } else {
        packetsRetransmitted = null;
        recurringRetransmits = null;
        minRtt = null;
      }
    }
  }

  static final class Tracker {
    private final MetricRecorder metricRecorder;
    private final Metrics metrics;
    private final Class<?> epollSocketChannelClass;
    private final Method tcpInfoMethod;
    private final Method totalRetransMethod;
    private final Method retransmitsMethod;
    private final Method rttMethod;
    private final Object tcpInfo;

    private long lastTotalRetrans = 0;

    Tracker(MetricRecorder metricRecorder) {
      this(metricRecorder, DEFAULT_METRICS);
    }

    Tracker(MetricRecorder metricRecorder, Metrics metrics) {
      this(
            metricRecorder, metrics,
            "io.netty.channel.epoll.EpollSocketChannel",
            "io.netty.channel.epoll.EpollTcpInfo");
    }

    Tracker(MetricRecorder metricRecorder, Metrics metrics,
        String epollSocketChannelClassName, String epollTcpInfoClassName) {
      this.metricRecorder = metricRecorder;
      this.metrics = metrics;

      Class<?> epollSocketChannelClass;
      Method tcpInfoMethod;
      Object tcpInfo;
      Method totalRetransMethod;
      Method retransMethod;
      Method rttMethod;

      try {
        epollSocketChannelClass = Class.forName(epollSocketChannelClassName);
        Class<?> epollTcpInfoClass = Class.forName(epollTcpInfoClassName);
        tcpInfo = epollTcpInfoClass.getDeclaredConstructor().newInstance();
        tcpInfoMethod = epollSocketChannelClass.getMethod("tcpInfo", epollTcpInfoClass);
        totalRetransMethod = epollTcpInfoClass.getMethod("totalRetrans");
        retransMethod = epollTcpInfoClass.getMethod("retrans");
        rttMethod = epollTcpInfoClass.getMethod("rtt");
      } catch (Exception | Error t) {
        // Epoll not available or error getting tcp_info, features disabled
        log.log(Level.FINE, "Failed to initialize Epoll tcp_info reflection", t);
        epollSocketChannelClass = null;
        tcpInfoMethod = null;
        tcpInfo = null;
        totalRetransMethod = null;
        retransMethod = null;
        rttMethod = null;
      }
      this.epollSocketChannelClass = epollSocketChannelClass;
      this.tcpInfoMethod = tcpInfoMethod;
      this.tcpInfo = tcpInfo;
      this.totalRetransMethod = totalRetransMethod;
      this.retransmitsMethod = retransMethod;
      this.rttMethod = rttMethod;
    }

    private static final long RECORD_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private ScheduledFuture<?> reportTimer;

    void channelActive(Channel channel) {
      List<String> labelValues = getLabelValues(channel);
      metricRecorder.addLongCounter(metrics.connectionsCreated, 1,
          Collections.emptyList(), labelValues);
      metricRecorder.addLongUpDownCounter(metrics.connectionCount, 1,
          Collections.emptyList(), labelValues);
      scheduleNextReport(channel, true);
    }

    private void scheduleNextReport(final Channel channel, boolean isInitial) {
      if (!channel.isActive()) {
        return;
      }

      double jitter = isInitial
          ? 0.1 + ThreadLocalRandom.current().nextDouble() // 10% to 110%
          : 0.9 + ThreadLocalRandom.current().nextDouble() * 0.2; // 90% to 110%
      long rearmingDelay = (long) (RECORD_INTERVAL_MILLIS * jitter);

      try {
        reportTimer = channel.eventLoop().schedule(() -> {
          if (channel.isActive()) {
            Tracker.this.recordTcpInfo(channel, false);
            scheduleNextReport(channel, false); // Re-arm
          }
        }, rearmingDelay, TimeUnit.MILLISECONDS);
      } catch (RejectedExecutionException e) {
        log.log(Level.FINE, "Failed to schedule next TCP metrics report", e);
        // The event loop is likely shutting down. We can safely ignore this.
      }
    }

    void channelInactive(Channel channel) {
      if (reportTimer != null) {
        reportTimer.cancel(false);
      }
      List<String> labelValues = getLabelValues(channel);
      metricRecorder.addLongUpDownCounter(metrics.connectionCount, -1,
          Collections.emptyList(), labelValues);
      // Final collection on close
      recordTcpInfo(channel, true);
    }

    void recordTcpInfo(Channel channel) {
      recordTcpInfo(channel, false);
    }

    private void recordTcpInfo(Channel channel, boolean isClose) {
      if (epollSocketChannelClass == null) {
        log.log(Level.FINE, "Skipping recordTcpInfo because"
            + "epollSocketChannelClass is null");
        return;
      }
      if (!epollSocketChannelClass.isInstance(channel)) {
        log.log(Level.FINE, "Skipping recordTcpInfo because channel is not an"
            + "instance of epollSocketChannelClass: {0}", channel.getClass()
            .getName());
        return;
      }
      List<String> labelValues = getLabelValues(channel);
      long totalRetrans;
      long retransmits;
      long rtt;
      try {
        tcpInfoMethod.invoke(channel, tcpInfo);

        totalRetrans = (Long) totalRetransMethod.invoke(tcpInfo);
        retransmits = (Long) retransmitsMethod.invoke(tcpInfo);
        rtt = (Long) rttMethod.invoke(tcpInfo);
      } catch (Exception e) {
        log.log(Level.FINE, "Error computing TCP metrics", e);
        return;
      }

      if (metrics.packetsRetransmitted != null) {
        long deltaTotal = totalRetrans - lastTotalRetrans;
        if (deltaTotal > 0) {
          metricRecorder.addLongCounter(metrics.packetsRetransmitted, deltaTotal,
              Collections.emptyList(), labelValues);
          lastTotalRetrans = totalRetrans;
        }
      }
      if (metrics.recurringRetransmits != null && isClose) {
        if (retransmits > 0) {
          metricRecorder.addLongCounter(metrics.recurringRetransmits, retransmits,
              Collections.emptyList(), labelValues);
        }
      }
      if (metrics.minRtt != null) {
        metricRecorder.recordDoubleHistogram(metrics.minRtt,
            rtt / 1000000.0, // Convert microseconds to seconds
            Collections.emptyList(), labelValues);
      }
    }
  }

  private static List<String> getLabelValues(Channel channel) {
    String localAddress = "";
    String localPort = "";
    String peerAddress = "";
    String peerPort = "";
    
    SocketAddress local = channel.localAddress();
    if (local instanceof InetSocketAddress) {
      InetSocketAddress inetLocal = (InetSocketAddress) local;
      localAddress = inetLocal.getAddress().getHostAddress();
      localPort = String.valueOf(inetLocal.getPort());
    }
    
    SocketAddress remote = channel.remoteAddress();
    if (remote instanceof InetSocketAddress) {
      InetSocketAddress inetRemote = (InetSocketAddress) remote;
      peerAddress = inetRemote.getAddress().getHostAddress();
      peerPort = String.valueOf(inetRemote.getPort());
    }
    
    return Arrays.asList(localAddress, localPort, peerAddress, peerPort);
  }


  private TcpMetrics() {}
}
