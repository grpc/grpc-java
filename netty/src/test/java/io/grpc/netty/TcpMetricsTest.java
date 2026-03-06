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
import java.util.List;
import java.util.Objects;
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
    metrics = new TcpMetrics.Tracker(metricRecorder);
  }

  @Test
  public void metricsInitialization_epollUnavailable() {
    TcpMetrics.Metrics metrics = new TcpMetrics.Metrics(false);

    org.junit.Assert.assertNotNull(metrics.connectionsCreated);
    org.junit.Assert.assertNotNull(metrics.connectionCount);
    org.junit.Assert.assertNull(metrics.packetsRetransmitted);
    org.junit.Assert.assertNull(metrics.recurringRetransmits);
    org.junit.Assert.assertNull(metrics.minRtt);
  }

  @Test
  public void metricsInitialization_epollAvailable() {
    TcpMetrics.Metrics metrics = new TcpMetrics.Metrics(true);

    org.junit.Assert.assertNotNull(metrics.connectionsCreated);
    org.junit.Assert.assertNotNull(metrics.connectionCount);
    org.junit.Assert.assertNotNull(metrics.packetsRetransmitted);
    org.junit.Assert.assertNotNull(metrics.recurringRetransmits);
    org.junit.Assert.assertNotNull(metrics.minRtt);
  }

  public static class FakeEpollTcpInfo {
    long totalRetrans;
    long retransmits;
    long rtt;

    public void setValues(long totalRetrans, long retransmits, long rtt) {
      this.totalRetrans = totalRetrans;
      this.retransmits = retransmits;
      this.rtt = rtt;
    }

    @SuppressWarnings("unused")
    public long totalRetrans() {
      return totalRetrans;
    }

    @SuppressWarnings("unused")
    public long retrans() {
      return retransmits;
    }

    @SuppressWarnings("unused")
    public long rtt() {
      return rtt;
    }
  }

  @Test
  public void tracker_recordTcpInfo_reflectionSuccess() {
    MetricRecorder recorder = org.mockito.Mockito.mock(MetricRecorder.class);
    TcpMetrics.Metrics metrics = new TcpMetrics.Metrics(true);

    TcpMetrics.Tracker tracker = new TcpMetrics.Tracker(recorder, metrics,
        ConfigurableFakeWithTcpInfo.class.getName(),
            FakeEpollTcpInfo.class.getName());

    FakeEpollTcpInfo infoSource = new FakeEpollTcpInfo();
    infoSource.setValues(123, 4, 5000);
    ConfigurableFakeWithTcpInfo channel = new ConfigurableFakeWithTcpInfo(infoSource);
    channel.writeInbound("dummy");

    tracker.channelInactive(channel);

    verify(recorder).addLongCounter(eq(Objects.requireNonNull(metrics.packetsRetransmitted)),
        eq(123L), any(), any());
    verify(recorder).addLongCounter(eq(Objects.requireNonNull(metrics.recurringRetransmits)),
        eq(4L), any(), any());
    verify(recorder).recordDoubleHistogram(eq(Objects.requireNonNull(metrics.minRtt)),
        eq(0.005), any(), any());
  }

  @Test
  public void tracker_periodicRecord_doesNotRecordRecurringRetransmits() {
    MetricRecorder recorder = org.mockito.Mockito.mock(MetricRecorder.class);
    TcpMetrics.Metrics metrics = new TcpMetrics.Metrics(true);

    TcpMetrics.Tracker tracker = new TcpMetrics.Tracker(recorder, metrics,
        ConfigurableFakeWithTcpInfo.class.getName(),
            FakeEpollTcpInfo.class.getName());

    FakeEpollTcpInfo infoSource = new FakeEpollTcpInfo();
    infoSource.setValues(123, 4, 5000);
    ConfigurableFakeWithTcpInfo channel =
        org.mockito.Mockito.spy(new ConfigurableFakeWithTcpInfo(infoSource));
    when(channel.eventLoop()).thenReturn(eventLoop);
    when(channel.isActive()).thenReturn(true);

    tracker.channelActive(channel);

    ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(eventLoop).schedule(runnableCaptor.capture(), anyLong(), eq(TimeUnit.MILLISECONDS));
    Runnable periodicTask = runnableCaptor.getValue();

    org.mockito.Mockito.clearInvocations(recorder);
    periodicTask.run();

    verify(recorder).addLongCounter(eq(Objects.requireNonNull(metrics.packetsRetransmitted)),
        eq(123L), any(), any());
    verify(recorder).recordDoubleHistogram(eq(Objects.requireNonNull(metrics.minRtt)),
        eq(0.005), any(), any());
    // Should NOT record recurring retransmits during periodic polling
    verify(recorder, org.mockito.Mockito.never())
        .addLongCounter(eq(Objects.requireNonNull(metrics.recurringRetransmits)),
            anyLong(), any(), any());
  }

  @Test
  public void tracker_channelInactive_recordsRecurringRetransmits_raw_notDelta() {
    MetricRecorder recorder = org.mockito.Mockito.mock(MetricRecorder.class);
    TcpMetrics.Metrics metrics = new TcpMetrics.Metrics(true);

    TcpMetrics.Tracker tracker = new TcpMetrics.Tracker(recorder, metrics,
        ConfigurableFakeWithTcpInfo.class.getName(),
            FakeEpollTcpInfo.class.getName());

    FakeEpollTcpInfo infoSource = new FakeEpollTcpInfo();
    infoSource.setValues(123, 4, 5000);
    ConfigurableFakeWithTcpInfo channel =
        org.mockito.Mockito.spy(new ConfigurableFakeWithTcpInfo(infoSource));
    when(channel.eventLoop()).thenReturn(eventLoop);
    when(channel.isActive()).thenReturn(true);

    // Mimic the periodic schedule invocation
    tracker.channelActive(channel);
    ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(eventLoop).schedule(runnableCaptor.capture(), anyLong(), eq(TimeUnit.MILLISECONDS));

    // Fire periodic task once. TotalRetrans=123, retransmits=4.
    runnableCaptor.getValue().run();

    org.mockito.Mockito.clearInvocations(recorder);

    // Let's just create a new channel instance where tcpInfo sets retrans=5.
    FakeEpollTcpInfo infoSource2 = new FakeEpollTcpInfo();
    infoSource2.setValues(130, 5, 5000);
    ConfigurableFakeWithTcpInfo channel2 =
        org.mockito.Mockito.spy(new ConfigurableFakeWithTcpInfo(infoSource2));
    when(channel2.eventLoop()).thenReturn(eventLoop);

    tracker.channelInactive(channel2);

    // It should record delta for totalRetrans (130 - 123 = 7)
    verify(recorder).addLongCounter(eq(Objects.requireNonNull(metrics.packetsRetransmitted)),
        eq(7L), any(), any());
    // But for recurringRetransmits it MUST record the raw value 5, not the delta!
    verify(recorder).addLongCounter(eq(Objects.requireNonNull(metrics.recurringRetransmits)),
        eq(5L), any(), any());
  }

  @Test
  public void tracker_periodicRecord_reportsDeltaForTotalRetrans() {
    MetricRecorder recorder = org.mockito.Mockito.mock(MetricRecorder.class);
    TcpMetrics.Metrics metrics = new TcpMetrics.Metrics(true);

    TcpMetrics.Tracker tracker = new TcpMetrics.Tracker(recorder, metrics,
        ConfigurableFakeWithTcpInfo.class.getName(),
            FakeEpollTcpInfo.class.getName());

    FakeEpollTcpInfo infoSource = new FakeEpollTcpInfo();
    infoSource.setValues(123, 4, 5000);
    ConfigurableFakeWithTcpInfo channel =
        org.mockito.Mockito.spy(new ConfigurableFakeWithTcpInfo(infoSource));
    when(channel.eventLoop()).thenReturn(eventLoop);
    when(channel.isActive()).thenReturn(true);

    // Initial Active Trigger
    tracker.channelActive(channel);
    ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(eventLoop).schedule(runnableCaptor.capture(), anyLong(), eq(TimeUnit.MILLISECONDS));
    Runnable periodicTask = runnableCaptor.getValue();

    // First periodic record
    org.mockito.Mockito.clearInvocations(recorder);
    periodicTask.run();
    verify(recorder).addLongCounter(eq(Objects.requireNonNull(metrics.packetsRetransmitted)),
        eq(123L), any(), any());

    // Change tcpInfo for second periodic record
    org.mockito.Mockito.doAnswer(invocation -> {
      FakeEpollTcpInfo info = invocation.getArgument(0);
      info.totalRetrans = 150;
      info.retransmits = 2; // Should not be recorded
      info.rtt = 6000;
      return null;
    }).when(channel).tcpInfo(any(FakeEpollTcpInfo.class));

    org.mockito.Mockito.clearInvocations(recorder);
    periodicTask.run();

    // Only the delta (150 - 123 = 27) should be recorded
    verify(recorder).addLongCounter(eq(Objects.requireNonNull(metrics.packetsRetransmitted)),
        eq(27L), any(), any());
    verify(recorder).recordDoubleHistogram(eq(Objects.requireNonNull(metrics.minRtt)),
        eq(0.006), any(), any());
    verify(recorder, org.mockito.Mockito.never())
        .addLongCounter(eq(Objects.requireNonNull(metrics.recurringRetransmits)),
            anyLong(), any(), any());
  }

  @Test
  public void tracker_periodicRecord_doesNotReportZeroDeltaForTotalRetrans() {
    MetricRecorder recorder = org.mockito.Mockito.mock(MetricRecorder.class);
    TcpMetrics.Metrics metrics = new TcpMetrics.Metrics(true);

    TcpMetrics.Tracker tracker = new TcpMetrics.Tracker(recorder, metrics,
        ConfigurableFakeWithTcpInfo.class.getName(),
            FakeEpollTcpInfo.class.getName());

    FakeEpollTcpInfo infoSource = new FakeEpollTcpInfo();
    infoSource.setValues(123, 4, 5000);
    ConfigurableFakeWithTcpInfo channel =
        org.mockito.Mockito.spy(new ConfigurableFakeWithTcpInfo(infoSource));
    when(channel.eventLoop()).thenReturn(eventLoop);
    when(channel.isActive()).thenReturn(true);

    // Initial Active Trigger
    tracker.channelActive(channel);
    ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(eventLoop).schedule(runnableCaptor.capture(), anyLong(), eq(TimeUnit.MILLISECONDS));
    Runnable periodicTask = runnableCaptor.getValue();

    // First periodic record
    periodicTask.run();
    org.mockito.Mockito.clearInvocations(recorder);

    // Keep tcpInfo the same for second periodic record
    periodicTask.run();

    // NO delta (123 - 123 = 0), so it should not be recorded
    verify(recorder, org.mockito.Mockito.never())
        .addLongCounter(eq(Objects.requireNonNull(metrics.packetsRetransmitted)),
            anyLong(), any(), any());
    verify(recorder).recordDoubleHistogram(eq(Objects.requireNonNull(metrics.minRtt)),
        eq(0.005), any(), any());
  }

  public static class ConfigurableFakeWithTcpInfo extends
      io.netty.channel.embedded.EmbeddedChannel {
    private final FakeEpollTcpInfo infoToCopy;

    public ConfigurableFakeWithTcpInfo(FakeEpollTcpInfo infoToCopy) {
      this.infoToCopy = infoToCopy;
    }

    public void tcpInfo(FakeEpollTcpInfo info) {
      info.totalRetrans = infoToCopy.totalRetrans;
      info.retransmits = infoToCopy.retransmits;
      info.rtt = infoToCopy.rtt;
    }
  }

  @Test
  public void tracker_reportsDeltas_correctly() {
    MetricRecorder recorder = org.mockito.Mockito.mock(MetricRecorder.class);
    TcpMetrics.Metrics metrics = new TcpMetrics.Metrics(true);

    String fakeChannelName = ConfigurableFakeWithTcpInfo.class.getName();
    String fakeInfoName = FakeEpollTcpInfo.class.getName();

    TcpMetrics.Tracker tracker = new TcpMetrics.Tracker(recorder, metrics,
            fakeChannelName, fakeInfoName);

    FakeEpollTcpInfo infoSource = new FakeEpollTcpInfo();
    ConfigurableFakeWithTcpInfo channel = new ConfigurableFakeWithTcpInfo(infoSource);

    // 10 retransmits total
    infoSource.setValues(10, 2, 1000);
    tracker.recordTcpInfo(channel);

    verify(recorder).addLongCounter(eq(Objects.requireNonNull(metrics.packetsRetransmitted)),
        eq(10L), any(), any());

    // 15 retransmits total (delta 5)
    infoSource.setValues(15, 0, 1000);
    tracker.recordTcpInfo(channel);

    verify(recorder).addLongCounter(eq(Objects.requireNonNull(metrics.packetsRetransmitted)),
        eq(5L), any(), any());

    // 15 retransmits total (delta 0) - should NOT report
    tracker.recordTcpInfo(channel);
    // Verify no new interactions with this specific metric and value
    // We can't easily verify "no interaction" for specific value without capturing.
    verify(recorder, org.mockito.Mockito.times(1)).addLongCounter(
        eq(Objects.requireNonNull(metrics.packetsRetransmitted)),
            eq(10L), any(), any());
    verify(recorder, org.mockito.Mockito.times(1)).addLongCounter(
        eq(Objects.requireNonNull(metrics.packetsRetransmitted)),
            eq(5L), any(), any());
    // Total interactions for packetsRetransmitted should be 2
    verify(recorder, org.mockito.Mockito.times(2)).addLongCounter(
        eq(Objects.requireNonNull(metrics.packetsRetransmitted)),
            anyLong(), any(), any());

    // recurringRetransmits should NOT have been reported yet (periodic calls)
    verify(recorder, org.mockito.Mockito.times(0)).addLongCounter(
        eq(Objects.requireNonNull(metrics.recurringRetransmits)),
            anyLong(), any(), any());

    // Close channel - should report recurringRetransmits
    tracker.channelInactive(channel);
    verify(recorder, org.mockito.Mockito.times(1)).addLongCounter(
        eq(Objects.requireNonNull(metrics.recurringRetransmits)),
            eq(0L), // From last infoSource setValues(15, 0, 1000)
        any(), any());
  }

  @Test
  public void tracker_recordTcpInfo_reflectionFailure() {
    MetricRecorder recorder = org.mockito.Mockito.mock(MetricRecorder.class);
    TcpMetrics.Metrics metrics = new TcpMetrics.Metrics(true);

    TcpMetrics.Tracker tracker = new TcpMetrics.Tracker(recorder, metrics,
            "non.existent.Class", "non.existent.Info");

    Channel channel = org.mockito.Mockito.mock(Channel.class);
    when(channel.isActive()).thenReturn(true);

    // Should catch exception and ignore
    tracker.channelInactive(channel);
  }
  
  @Test
  public void registeredMetrics_haveCorrectOptionalLabels() {
    List<String> expectedOptionalLabels = Arrays.asList(
        "network.local.address",
        "network.local.port",
        "network.peer.address",
        "network.peer.port"
    );

    org.junit.Assert.assertEquals(
        expectedOptionalLabels,
        TcpMetrics.getDefaultMetrics().connectionsCreated.getOptionalLabelKeys());
    org.junit.Assert.assertEquals(
        expectedOptionalLabels,
        TcpMetrics.getDefaultMetrics().connectionCount.getOptionalLabelKeys());

    if (TcpMetrics.getDefaultMetrics().packetsRetransmitted != null) {
      org.junit.Assert.assertEquals(
          expectedOptionalLabels,
          Objects.requireNonNull(TcpMetrics.getDefaultMetrics().packetsRetransmitted)
              .getOptionalLabelKeys());
      org.junit.Assert.assertEquals(
          expectedOptionalLabels,
          Objects.requireNonNull(TcpMetrics.getDefaultMetrics().recurringRetransmits)
              .getOptionalLabelKeys());
      org.junit.Assert.assertEquals(
          expectedOptionalLabels,
          Objects.requireNonNull(TcpMetrics.getDefaultMetrics().minRtt).getOptionalLabelKeys());
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
        eq(TcpMetrics.getDefaultMetrics().connectionsCreated), eq(1L), 
        eq(Collections.emptyList()),
            eq(Arrays.asList(
            localInet.getHostAddress(), "8080", remoteInet.getHostAddress(), "443")));
    verify(metricRecorder).addLongUpDownCounter(
        eq(TcpMetrics.getDefaultMetrics().connectionCount), eq(1L), 
        eq(Collections.emptyList()),
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
        eq(TcpMetrics.getDefaultMetrics().connectionCount), eq(-1L), 
        eq(Collections.emptyList()),
            eq(Arrays.asList(
            localInet.getHostAddress(), "8080", remoteInet.getHostAddress(), "443")));
    verifyNoMoreInteractions(metricRecorder);
  }

  @Test
  public void channelActive_extractsLabels_nonInetAddress() {
    SocketAddress dummyAddress = new SocketAddress() {};
    when(channel.localAddress()).thenReturn(dummyAddress);
    when(channel.remoteAddress()).thenReturn(dummyAddress);

    metrics.channelActive(channel);
    
    verify(metricRecorder).addLongCounter(
        eq(TcpMetrics.getDefaultMetrics().connectionsCreated), eq(1L), 
        eq(Collections.emptyList()),
            eq(Arrays.asList("", "", "", "")));
    verify(metricRecorder).addLongUpDownCounter(
        eq(TcpMetrics.getDefaultMetrics().connectionCount), eq(1L), 
        eq(Collections.emptyList()),
            eq(Arrays.asList("", "", "", "")));
    verifyNoMoreInteractions(metricRecorder);
  }

  @Test
  public void channelActive_incrementsCounts() {
    metrics.channelActive(channel);
    verify(metricRecorder).addLongCounter(
        eq(TcpMetrics.getDefaultMetrics().connectionsCreated), eq(1L), 
        eq(Collections.emptyList()),
            eq(Arrays.asList("", "", "", "")));
    verify(metricRecorder).addLongUpDownCounter(
        eq(TcpMetrics.getDefaultMetrics().connectionCount), eq(1L), 
        eq(Collections.emptyList()),
            eq(Arrays.asList("", "", "", "")));
    verifyNoMoreInteractions(metricRecorder);
  }

  @Test
  public void channelInactive_decrementsCount_noEpoll_noError() {
    metrics.channelInactive(channel);
    verify(metricRecorder).addLongUpDownCounter(
        eq(TcpMetrics.getDefaultMetrics().connectionCount), eq(-1L), 
        eq(Collections.emptyList()),
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
    // Initial jitter is 10% to 110%, so 30,000 ms to 330,000 ms
    org.junit.Assert.assertTrue("Delay should be >= 30000 but was " + delay, delay >= 30_000);
    org.junit.Assert.assertTrue("Delay should be <= 330000 but was " + delay, delay <= 330_000);

    // Run the task to verify rescheduling
    task.run();

    verify(eventLoop, org.mockito.Mockito.times(2))
        .schedule(any(Runnable.class), delayCaptor.capture(), eq(TimeUnit.MILLISECONDS));

    // Re-arming jitter is 90% to 110%, so 270,000 ms to 330,000 ms
    long rearmDelay = delayCaptor.getValue();
    org.junit.Assert.assertTrue(
        "Delay should be >= 270000 but was " + rearmDelay, rearmDelay >= 270_000);
    org.junit.Assert.assertTrue(
        "Delay should be <= 330000 but was " + rearmDelay, rearmDelay <= 330_000);
  }

  @Test
  public void channelInactive_cancelsReportTimer() {
    when(channel.isActive()).thenReturn(true);
    metrics.channelActive(channel);

    metrics.channelInactive(channel);

    verify(scheduledFuture).cancel(false);
  }
}
