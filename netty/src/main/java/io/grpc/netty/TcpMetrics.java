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

import io.grpc.InternalTcpMetrics;
import io.grpc.MetricRecorder;
import io.netty.channel.Channel;
import io.netty.util.concurrent.ScheduledFuture;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility for collecting TCP metrics from Netty channels.
 */
final class TcpMetrics {
  private static final Logger log = Logger.getLogger(TcpMetrics.class.getName());

  static EpollInfo epollInfo = loadEpollInfo();

  static final class EpollInfo {
    final Class<?> channelClass;
    final Class<?> infoClass;
    final java.lang.reflect.Constructor<?> infoConstructor;
    final Method tcpInfo;
    final Method totalRetrans;
    final Method retransmits;
    final Method rtt;

    EpollInfo(
        Class<?> channelClass,
        Class<?> infoClass,
        java.lang.reflect.Constructor<?> infoConstructor,
        Method tcpInfo,
        Method totalRetrans,
        Method retransmits,
        Method rtt) {
      this.channelClass = channelClass;
      this.infoClass = infoClass;
      this.infoConstructor = infoConstructor;
      this.tcpInfo = tcpInfo;
      this.totalRetrans = totalRetrans;
      this.retransmits = retransmits;
      this.rtt = rtt;
    }
  }

  private static EpollInfo loadEpollInfo() {
    boolean epollAvailable = false;
    try {
      Class<?> epollClass = Class.forName("io.netty.channel.epoll.Epoll");
      Method isAvailableMethod = epollClass.getDeclaredMethod("isAvailable");
      epollAvailable = (Boolean) isAvailableMethod.invoke(null);
      if (epollAvailable) {
        Class<?> channelClass = Class.forName("io.netty.channel.epoll.EpollSocketChannel");
        Class<?> infoClass = Class.forName("io.netty.channel.epoll.EpollTcpInfo");
        return new EpollInfo(
            channelClass,
            infoClass,
            infoClass.getDeclaredConstructor(),
            channelClass.getMethod("tcpInfo", infoClass),
            infoClass.getMethod("totalRetrans"),
            infoClass.getMethod("retrans"),
            infoClass.getMethod("rtt"));
      }
    } catch (ReflectiveOperationException | RuntimeException e) {
      log.log(Level.FINE, "Failed to initialize Epoll tcp_info reflection", e);
    } catch (Error e) {
      log.log(Level.FINE, "Failed to load native Epoll library", e);
    } finally {
      log.log(Level.INFO, "Epoll available during static init of TcpMetrics:"
          + "{0}", epollAvailable);
    }
    return null;
  }

  static final class Tracker {
    private final MetricRecorder metricRecorder;
    private final Object tcpInfo;

    private long lastTotalRetrans = 0;

    Tracker(MetricRecorder metricRecorder) {
      this.metricRecorder = metricRecorder;

      Object tcpInfo = null;
      if (epollInfo != null) {
        try {
          tcpInfo = epollInfo.infoConstructor.newInstance();
        } catch (ReflectiveOperationException e) {
          log.log(Level.FINE, "Failed to instantiate EpollTcpInfo", e);
        }
      }
      this.tcpInfo = tcpInfo;
    }

    private static final long RECORD_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private ScheduledFuture<?> reportTimer;

    void channelActive(Channel channel) {
      List<String> labelValues = getLabelValues(channel);
      metricRecorder.addLongCounter(InternalTcpMetrics.CONNECTIONS_CREATED_INSTRUMENT, 1,
          Collections.emptyList(), labelValues);
      metricRecorder.addLongUpDownCounter(InternalTcpMetrics.CONNECTION_COUNT_INSTRUMENT, 1,
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

      reportTimer = channel.eventLoop().schedule(() -> {
        if (channel.isActive()) {
          Tracker.this.recordTcpInfo(channel, false);
          scheduleNextReport(channel, false); // Re-arm
        }
      }, rearmingDelay, TimeUnit.MILLISECONDS);
    }

    void channelInactive(Channel channel) {
      if (reportTimer != null) {
        reportTimer.cancel(false);
      }
      List<String> labelValues = getLabelValues(channel);
      metricRecorder.addLongUpDownCounter(InternalTcpMetrics.CONNECTION_COUNT_INSTRUMENT, -1,
          Collections.emptyList(), labelValues);
      // Final collection on close
      recordTcpInfo(channel, true);
    }

    void recordTcpInfo(Channel channel) {
      recordTcpInfo(channel, false);
    }

    private void recordTcpInfo(Channel channel, boolean isClose) {
      if (epollInfo == null) {
        log.log(Level.FINE, "Skipping recordTcpInfo because"
            + "epollInfo is null");
        return;
      }
      if (!epollInfo.channelClass.isInstance(channel)) {
        log.log(Level.FINE, "Skipping recordTcpInfo because channel is not an"
            + "instance of epollSocketChannelClass: {0}",
            channel.getClass()
                .getName());
        return;
      }
      List<String> labelValues = getLabelValues(channel);
      long totalRetrans;
      long retransmits;
      long rtt;
      try {
        epollInfo.tcpInfo.invoke(channel, tcpInfo);
        totalRetrans = (Long) epollInfo.totalRetrans.invoke(tcpInfo);
        retransmits = (Long) epollInfo.retransmits.invoke(tcpInfo);
        rtt = (Long) epollInfo.rtt.invoke(tcpInfo);
      } catch (ReflectiveOperationException e) {
        log.log(Level.FINE, "Error computing TCP metrics", e);
        return;
      }

      long deltaTotal = totalRetrans - lastTotalRetrans;
      if (deltaTotal > 0) {
        metricRecorder.addLongCounter(InternalTcpMetrics.PACKETS_RETRANSMITTED_INSTRUMENT,
            deltaTotal, Collections.emptyList(), labelValues);
        lastTotalRetrans = totalRetrans;
      }
      if (isClose && retransmits > 0) {
        metricRecorder.addLongCounter(InternalTcpMetrics.RECURRING_RETRANSMITS_INSTRUMENT,
            retransmits, Collections.emptyList(), labelValues);
      }
      metricRecorder.recordDoubleHistogram(InternalTcpMetrics.MIN_RTT_INSTRUMENT,
          rtt / 1000000.0, // Convert microseconds to seconds
          Collections.emptyList(), labelValues);
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

  private TcpMetrics() {
  }
}
