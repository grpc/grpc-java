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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.MetricRecorder;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class TcpMetricsTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock private MetricRecorder metricRecorder;
  @Mock private Channel channel;
  @Mock
  private EventLoop eventLoop;
  @Mock
  private ScheduledFuture<?> scheduledFuture;

  private TcpMetrics.Tracker metrics;

  @Before
  public void setUp() {
    when(channel.eventLoop()).thenReturn(eventLoop);
    when(eventLoop.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> scheduledFuture);
    metrics = new TcpMetrics.Tracker(metricRecorder, "target1");
  }

  
  @Test
  public void registeredMetrics_haveCorrectOptionalLabels() {
    java.util.List<String> expectedOptionalLabels = Arrays.asList(
        "network.local.address",
        "network.local.port",
        "network.peer.address",
        "network.peer.port"
    );

    org.junit.Assert.assertEquals(
        expectedOptionalLabels, TcpMetrics.connectionsCreated.getOptionalLabelKeys());
    org.junit.Assert.assertEquals(
        expectedOptionalLabels, TcpMetrics.connectionCount.getOptionalLabelKeys());

    if (TcpMetrics.packetsRetransmitted != null) {
      org.junit.Assert.assertEquals(
          expectedOptionalLabels, TcpMetrics.packetsRetransmitted.getOptionalLabelKeys());
      org.junit.Assert.assertEquals(
          expectedOptionalLabels, TcpMetrics.recurringRetransmits.getOptionalLabelKeys());
      org.junit.Assert.assertEquals(
          expectedOptionalLabels, TcpMetrics.minRtt.getOptionalLabelKeys());
    }
  }

  @Test
  public void channelActive_extractsLabels_ipv4() throws Exception {

    InetAddress localInet = InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 });
    InetAddress remoteInet = InetAddress.getByAddress(new byte[] { 10, 0, 0, 1 });
    when(channel.localAddress()).thenReturn(new InetSocketAddress(localInet, 8080));
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress(remoteInet, 443));

    metrics.channelActive(channel);
    
    verify(metricRecorder).addLongCounter(
        eq(TcpMetrics.connectionsCreated), eq(1L), eq(Collections.singletonList("target1")),
        eq(Arrays.asList(
            localInet.getHostAddress(), "8080", remoteInet.getHostAddress(), "443")));
    verify(metricRecorder).addLongUpDownCounter(
        eq(TcpMetrics.connectionCount), eq(1L), eq(Collections.singletonList("target1")),
        eq(Arrays.asList(
            localInet.getHostAddress(), "8080", remoteInet.getHostAddress(), "443")));
    verifyNoMoreInteractions(metricRecorder);
  }

  @Test
  public void channelInactive_extractsLabels_ipv6() throws Exception {

    InetAddress localInet = InetAddress.getByAddress(
        new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 });
    InetAddress remoteInet = InetAddress.getByAddress(
        new byte[] { 32, 1, 13, -72, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1 });
    when(channel.localAddress()).thenReturn(new InetSocketAddress(localInet, 8080));
    when(channel.remoteAddress()).thenReturn(new InetSocketAddress(remoteInet, 443));

    metrics.channelInactive(channel);
    
    verify(metricRecorder).addLongUpDownCounter(
        eq(TcpMetrics.connectionCount), eq(-1L), eq(Collections.singletonList("target1")),
        eq(Arrays.asList(
            localInet.getHostAddress(), "8080", remoteInet.getHostAddress(), "443")));
    verifyNoMoreInteractions(metricRecorder);
  }

  @Test
  public void channelActive_extractsLabels_nonInetAddress() throws Exception {
    SocketAddress dummyAddress = new SocketAddress() {};
    when(channel.localAddress()).thenReturn(dummyAddress);
    when(channel.remoteAddress()).thenReturn(dummyAddress);

    metrics.channelActive(channel);
    
    verify(metricRecorder).addLongCounter(
        eq(TcpMetrics.connectionsCreated), eq(1L), eq(Collections.singletonList("target1")),
        eq(Arrays.asList("", "", "", "")));
    verify(metricRecorder).addLongUpDownCounter(
        eq(TcpMetrics.connectionCount), eq(1L), eq(Collections.singletonList("target1")),
        eq(Arrays.asList("", "", "", "")));
    verifyNoMoreInteractions(metricRecorder);
  }

  @Test
  public void channelActive_incrementsCounts() {
    metrics.channelActive(channel);
    verify(metricRecorder).addLongCounter(
        eq(TcpMetrics.connectionsCreated), eq(1L), eq(Collections.singletonList("target1")),
        eq(Arrays.asList("", "", "", "")));
    verify(metricRecorder).addLongUpDownCounter(
        eq(TcpMetrics.connectionCount), eq(1L), eq(Collections.singletonList("target1")),
        eq(Arrays.asList("", "", "", "")));
    verifyNoMoreInteractions(metricRecorder);
  }

  @Test
  public void channelInactive_decrementsCount_noEpoll_noError() {
    metrics.channelInactive(channel);
    verify(metricRecorder).addLongUpDownCounter(
        eq(TcpMetrics.connectionCount), eq(-1L), eq(Collections.singletonList("target1")),
        eq(Arrays.asList("", "", "", "")));
    verifyNoMoreInteractions(metricRecorder);
  }

  @Test
  public void channelActive_schedulesReportTimer() {
    when(channel.isActive()).thenReturn(true);
    metrics.channelActive(channel);

    ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
    ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);
    verify(eventLoop).schedule(
            runnableCaptor.capture(), delayCaptor.capture(), eq(TimeUnit.MILLISECONDS));

    Runnable task = runnableCaptor.getValue();
    long delay = delayCaptor.getValue();

    // Default RECORD_INTERVAL_MILLIS is 5 minutes (300,000 ms)
    // Jitter is 10% to 110%, so 30,000 ms to 330,000 ms
    org.junit.Assert.assertTrue("Delay should be >= 30000 but was " + delay, delay >= 30_000);
    org.junit.Assert.assertTrue("Delay should be <= 330000 but was " + delay, delay <= 330_000);

    // Run the task to verify rescheduling
    task.run();

    verify(eventLoop, org.mockito.Mockito.times(2))
            .schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
  }

  @Test
  public void channelInactive_cancelsReportTimer() {
    when(channel.isActive()).thenReturn(true);
    metrics.channelActive(channel);

    metrics.channelInactive(channel);

    verify(scheduledFuture).cancel(false);
  }
}
