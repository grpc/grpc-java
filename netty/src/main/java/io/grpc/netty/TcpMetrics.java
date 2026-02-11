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

  static {
    MetricInstrumentRegistry registry = MetricInstrumentRegistry.getDefaultRegistry();
    ImmutableList<String> requiredLabels = ImmutableList.of("grpc.target");
    ImmutableList<String> optionalLabels = ImmutableList.of();
    
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

    void channelActive() {
      if (metricRecorder != null && target != null) {
        metricRecorder.addLongCounter(TcpMetrics.connectionsCreated, 1,
            Collections.singletonList(target), Collections.emptyList());
        metricRecorder.addLongUpDownCounter(TcpMetrics.connectionCount, 1,
            Collections.singletonList(target), Collections.emptyList());
      }
    }

    void channelInactive(Channel channel) {
      if (metricRecorder != null && target != null) {
        metricRecorder.addLongUpDownCounter(TcpMetrics.connectionCount, -1,
            Collections.singletonList(target), Collections.emptyList());
        
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
            long retransmits = (Long) retransmitsMethod.invoke(info);
            long rtt = ((Number) rttMethod.invoke(info)).longValue();
            
            metricRecorder.addLongCounter(TcpMetrics.packetsRetransmitted, totalRetrans,
                Collections.singletonList(target), Collections.emptyList());
            metricRecorder.addLongCounter(TcpMetrics.recurringRetransmits, retransmits,
                Collections.singletonList(target), Collections.emptyList());
            metricRecorder.recordDoubleHistogram(TcpMetrics.minRtt, rtt / 1000000.0,
                Collections.singletonList(target), Collections.emptyList());
          }
        } catch (Throwable t) {
          // Epoll not available or error getting tcp_info, just ignore.
        }
      }
    }
  }

  private TcpMetrics() {}
}
