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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.InternalTcpMetrics;
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

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  private MetricRecorder metricRecorder;
  @Mock
  private Channel channel;
  @Mock
  private EventLoop eventLoop;
  @Mock
  private ScheduledFuture<?> scheduledFuture;

  private TcpMetrics.Tracker metrics;

  @Before
  public void setUp() throws Exception {
    when(channel.eventLoop()).thenReturn(eventLoop);
    when(eventLoop.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
        .thenAnswer(invocation -> scheduledFuture);
    metrics = new TcpMetrics.Tracker(metricRecorder);
  }

  @Test
  public void metricsInitialization() throws Exception {

    org.junit.Assert.assertNotNull(InternalTcpMetrics.CONNECTIONS_CREATED_INSTRUMENT);
    org.junit.Assert.assertNotNull(InternalTcpMetrics.CONNECTION_COUNT_INSTRUMENT);
    org.junit.Assert.assertNotNull(InternalTcpMetrics.PACKETS_RETRANSMITTED_INSTRUMENT);
    org.junit.Assert.assertNotNull(InternalTcpMetrics.RECURRING_RETRANSMITS_INSTRUMENT);
    org.junit.Assert.assertNotNull(InternalTcpMetrics.MIN_RTT_INSTRUMENT);
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
  public void tracker_recordTcpInfo_reflectionSuccess() throws Exception {
    MetricRecorder recorder = mock(MetricRecorder.class);
    TcpMetrics.epollInfo = new TcpMetrics.EpollInfo(
        ConfigurableFakeWithTcpInfo.class, FakeEpollTcpInfo.class,
        FakeEpollTcpInfo.class.getConstructor(),
        ConfigurableFakeWithTcpInfo.class.getMethod("tcpInfo", FakeEpollTcpInfo.class),
        FakeEpollTcpInfo.class.getMethod("totalRetrans"),
        FakeEpollTcpInfo.class.getMethod("retrans"),
        FakeEpollTcpInfo.class.getMethod("rtt"));
    TcpMetrics.Tracker tracker = new TcpMetrics.Tracker(recorder);

    FakeEpollTcpInfo infoSource = new FakeEpollTcpInfo();
    infoSource.setValues(123, 4, 5000);
    ConfigurableFakeWithTcpInfo channel = new ConfigurableFakeWithTcpInfo(infoSource);
    channel.writeInbound("dummy");

    tracker.channelInactive(channel);

    verify(recorder).addLongCounter(
        eq(Objects.requireNonNull(InternalTcpMetrics.PACKETS_RETRANSMITTED_INSTRUMENT)),
        eq(123L), any(), any());
    verify(recorder).addLongCounter(
        eq(Objects.requireNonNull(InternalTcpMetrics.RECURRING_RETRANSMITS_INSTRUMENT)),
        eq(4L), any(), any());
    verify(recorder).recordDoubleHistogram(
        eq(Objects.requireNonNull(InternalTcpMetrics.MIN_RTT_INSTRUMENT)),
        eq(0.005), any(), any());
  }

  @Test
  public void tracker_periodicRecord_doesNotRecordRecurringRetransmits() throws Exception {
    MetricRecorder recorder = mock(MetricRecorder.class);
    TcpMetrics.epollInfo = new TcpMetrics.EpollInfo(
        ConfigurableFakeWithTcpInfo.class, FakeEpollTcpInfo.class,
        FakeEpollTcpInfo.class.getConstructor(),
        ConfigurableFakeWithTcpInfo.class.getMethod("tcpInfo", FakeEpollTcpInfo.class),
        FakeEpollTcpInfo.class.getMethod("totalRetrans"),
        FakeEpollTcpInfo.class.getMethod("retrans"),
        FakeEpollTcpInfo.class.getMethod("rtt"));
    TcpMetrics.Tracker tracker = new TcpMetrics.Tracker(recorder);

    FakeEpollTcpInfo infoSource = new FakeEpollTcpInfo();
    infoSource.setValues(123, 4, 5000);
    ConfigurableFakeWithTcpInfo channel = org.mockito.Mockito.spy(
        new ConfigurableFakeWithTcpInfo(infoSource));
    when(channel.eventLoop()).thenReturn(eventLoop);
    when(channel.isActive()).thenReturn(true);

    tracker.channelActive(channel);

    ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(eventLoop).schedule(runnableCaptor.capture(), anyLong(), eq(TimeUnit.MILLISECONDS));
    Runnable periodicTask = runnableCaptor.getValue();

    org.mockito.Mockito.clearInvocations(recorder);
    periodicTask.run();

    verify(recorder).addLongCounter(
        eq(Objects.requireNonNull(InternalTcpMetrics.PACKETS_RETRANSMITTED_INSTRUMENT)),
        eq(123L), any(), any());
    verify(recorder).recordDoubleHistogram(
        eq(Objects.requireNonNull(InternalTcpMetrics.MIN_RTT_INSTRUMENT)),
        eq(0.005), any(), any());
    // Should NOT record recurring retransmits during periodic polling
    verify(recorder, org.mockito.Mockito.never())
        .addLongCounter(
            eq(Objects.requireNonNull(InternalTcpMetrics.RECURRING_RETRANSMITS_INSTRUMENT)),
            anyLong(), any(), any());
  }

  @Test
  public void tracker_channelInactive_recordsRecurringRetransmits_raw_notDelta() throws Exception {
    MetricRecorder recorder = mock(MetricRecorder.class);
    TcpMetrics.epollInfo = new TcpMetrics.EpollInfo(
        ConfigurableFakeWithTcpInfo.class, FakeEpollTcpInfo.class,
        FakeEpollTcpInfo.class.getConstructor(),
        ConfigurableFakeWithTcpInfo.class.getMethod("tcpInfo", FakeEpollTcpInfo.class),
        FakeEpollTcpInfo.class.getMethod("totalRetrans"),
        FakeEpollTcpInfo.class.getMethod("retrans"),
        FakeEpollTcpInfo.class.getMethod("rtt"));
    TcpMetrics.Tracker tracker = new TcpMetrics.Tracker(recorder);

    FakeEpollTcpInfo infoSource = new FakeEpollTcpInfo();
    infoSource.setValues(123, 4, 5000);
    ConfigurableFakeWithTcpInfo channel = org.mockito.Mockito.spy(
        new ConfigurableFakeWithTcpInfo(infoSource));
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
    ConfigurableFakeWithTcpInfo channel2 = org.mockito.Mockito.spy(
        new ConfigurableFakeWithTcpInfo(infoSource2));
    when(channel2.eventLoop()).thenReturn(eventLoop);

    tracker.channelInactive(channel2);

    // It should record delta for totalRetrans (130 - 123 = 7)
    verify(recorder).addLongCounter(
        eq(Objects.requireNonNull(InternalTcpMetrics.PACKETS_RETRANSMITTED_INSTRUMENT)),
        eq(7L), any(), any());
    // But for recurringRetransmits it MUST record the raw value 5, not the delta!
    verify(recorder).addLongCounter(
        eq(Objects.requireNonNull(InternalTcpMetrics.RECURRING_RETRANSMITS_INSTRUMENT)),
        eq(5L), any(), any());
  }

  @Test
  public void tracker_periodicRecord_reportsDeltaForTotalRetrans() throws Exception {
    MetricRecorder recorder = mock(MetricRecorder.class);
    TcpMetrics.epollInfo = new TcpMetrics.EpollInfo(
        ConfigurableFakeWithTcpInfo.class, FakeEpollTcpInfo.class,
        FakeEpollTcpInfo.class.getConstructor(),
        ConfigurableFakeWithTcpInfo.class.getMethod("tcpInfo", FakeEpollTcpInfo.class),
        FakeEpollTcpInfo.class.getMethod("totalRetrans"),
        FakeEpollTcpInfo.class.getMethod("retrans"),
        FakeEpollTcpInfo.class.getMethod("rtt"));
    TcpMetrics.Tracker tracker = new TcpMetrics.Tracker(recorder);

    FakeEpollTcpInfo infoSource = new FakeEpollTcpInfo();
    infoSource.setValues(123, 4, 5000);
    ConfigurableFakeWithTcpInfo channel = org.mockito.Mockito.spy(
        new ConfigurableFakeWithTcpInfo(infoSource));
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
    verify(recorder).addLongCounter(
        eq(Objects.requireNonNull(InternalTcpMetrics.PACKETS_RETRANSMITTED_INSTRUMENT)),
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
    verify(recorder).addLongCounter(
        eq(Objects.requireNonNull(InternalTcpMetrics.PACKETS_RETRANSMITTED_INSTRUMENT)),
        eq(27L), any(), any());
    verify(recorder).recordDoubleHistogram(
        eq(Objects.requireNonNull(InternalTcpMetrics.MIN_RTT_INSTRUMENT)),
        eq(0.006), any(), any());
    verify(recorder, org.mockito.Mockito.never())
        .addLongCounter(
            eq(Objects.requireNonNull(InternalTcpMetrics.RECURRING_RETRANSMITS_INSTRUMENT)),
            anyLong(), any(), any());
  }

  @Test
  public void tracker_periodicRecord_doesNotReportZeroDeltaForTotalRetrans() throws Exception {
    MetricRecorder recorder = mock(MetricRecorder.class);
    TcpMetrics.epollInfo = new TcpMetrics.EpollInfo(
        ConfigurableFakeWithTcpInfo.class, FakeEpollTcpInfo.class,
        FakeEpollTcpInfo.class.getConstructor(),
        ConfigurableFakeWithTcpInfo.class.getMethod("tcpInfo", FakeEpollTcpInfo.class),
        FakeEpollTcpInfo.class.getMethod("totalRetrans"),
        FakeEpollTcpInfo.class.getMethod("retrans"),
        FakeEpollTcpInfo.class.getMethod("rtt"));
    TcpMetrics.Tracker tracker = new TcpMetrics.Tracker(recorder);

    FakeEpollTcpInfo infoSource = new FakeEpollTcpInfo();
    infoSource.setValues(123, 4, 5000);
    ConfigurableFakeWithTcpInfo channel = org.mockito.Mockito.spy(
        new ConfigurableFakeWithTcpInfo(infoSource));
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
        .addLongCounter(
            eq(Objects.requireNonNull(InternalTcpMetrics.PACKETS_RETRANSMITTED_INSTRUMENT)),
            anyLong(), any(), any());
    verify(recorder).recordDoubleHistogram(
        eq(Objects.requireNonNull(InternalTcpMetrics.MIN_RTT_INSTRUMENT)),
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
  public void tracker_reportsDeltas_correctly() throws Exception {
    MetricRecorder recorder = mock(MetricRecorder.class);

    TcpMetrics.epollInfo = new TcpMetrics.EpollInfo(
        ConfigurableFakeWithTcpInfo.class, FakeEpollTcpInfo.class,
        FakeEpollTcpInfo.class.getConstructor(),
        ConfigurableFakeWithTcpInfo.class.getMethod("tcpInfo", FakeEpollTcpInfo.class),
        FakeEpollTcpInfo.class.getMethod("totalRetrans"),
        FakeEpollTcpInfo.class.getMethod("retrans"),
        FakeEpollTcpInfo.class.getMethod("rtt"));
    TcpMetrics.Tracker tracker = new TcpMetrics.Tracker(recorder);

    FakeEpollTcpInfo infoSource = new FakeEpollTcpInfo();
    ConfigurableFakeWithTcpInfo channel = new ConfigurableFakeWithTcpInfo(infoSource);

    // 10 retransmits total
    infoSource.setValues(10, 2, 1000);
    tracker.recordTcpInfo(channel);

    verify(recorder).addLongCounter(
        eq(Objects.requireNonNull(InternalTcpMetrics.PACKETS_RETRANSMITTED_INSTRUMENT)),
        eq(10L), any(), any());

    // 15 retransmits total (delta 5)
    infoSource.setValues(15, 0, 1000);
    tracker.recordTcpInfo(channel);

    verify(recorder).addLongCounter(
        eq(Objects.requireNonNull(InternalTcpMetrics.PACKETS_RETRANSMITTED_INSTRUMENT)),
        eq(5L), any(), any());

    // 15 retransmits total (delta 0) - should NOT report
    // also set retransmits to 1
    infoSource.setValues(15, 1, 1000);
    tracker.recordTcpInfo(channel);
    // Verify no new interactions with this specific metric and value
    // We can't easily verify "no interaction" for specific value without capturing.
    verify(recorder, org.mockito.Mockito.times(1)).addLongCounter(
        eq(Objects.requireNonNull(InternalTcpMetrics.PACKETS_RETRANSMITTED_INSTRUMENT)),
        eq(10L), any(), any());
    verify(recorder, org.mockito.Mockito.times(1)).addLongCounter(
        eq(Objects.requireNonNull(InternalTcpMetrics.PACKETS_RETRANSMITTED_INSTRUMENT)),
        eq(5L), any(), any());
    // Total interactions for packetsRetransmitted should be 2
    verify(recorder, org.mockito.Mockito.times(2)).addLongCounter(
        eq(Objects.requireNonNull(InternalTcpMetrics.PACKETS_RETRANSMITTED_INSTRUMENT)),
        anyLong(), any(), any());

    // recurringRetransmits should NOT have been reported yet (periodic calls)
    verify(recorder, org.mockito.Mockito.times(0)).addLongCounter(
        eq(Objects.requireNonNull(InternalTcpMetrics.RECURRING_RETRANSMITS_INSTRUMENT)),
        anyLong(), any(), any());

    // Close channel - should report recurringRetransmits
    tracker.channelInactive(channel);
    verify(recorder, org.mockito.Mockito.times(1)).addLongCounter(
        eq(Objects.requireNonNull(InternalTcpMetrics.RECURRING_RETRANSMITS_INSTRUMENT)),
        eq(1L), // From last infoSource setValues(15, 1, 1000)
        any(), any());
  }

  @Test
  public void tracker_recordTcpInfo_reflectionFailure() throws Exception {
    MetricRecorder recorder = mock(MetricRecorder.class);

    TcpMetrics.epollInfo = null;
    TcpMetrics.Tracker tracker = new TcpMetrics.Tracker(recorder);

    Channel channel = org.mockito.Mockito.mock(Channel.class);
    when(channel.isActive()).thenReturn(true);

    // Should catch exception and ignore
    tracker.channelInactive(channel);
  }

  @Test
  public void registeredMetrics_haveCorrectOptionalLabels() throws Exception {
    List<String> expectedOptionalLabels = Arrays.asList(
        "network.local.address",
        "network.local.port",
        "network.peer.address",
        "network.peer.port");

    org.junit.Assert.assertEquals(
        expectedOptionalLabels,
        InternalTcpMetrics.CONNECTIONS_CREATED_INSTRUMENT.getOptionalLabelKeys());
    org.junit.Assert.assertEquals(
        expectedOptionalLabels,
        InternalTcpMetrics.CONNECTION_COUNT_INSTRUMENT.getOptionalLabelKeys());

    if (InternalTcpMetrics.PACKETS_RETRANSMITTED_INSTRUMENT != null) {
      org.junit.Assert.assertEquals(
          expectedOptionalLabels,
          Objects.requireNonNull(InternalTcpMetrics.PACKETS_RETRANSMITTED_INSTRUMENT)
              .getOptionalLabelKeys());
      org.junit.Assert.assertEquals(
          expectedOptionalLabels,
          Objects.requireNonNull(InternalTcpMetrics.RECURRING_RETRANSMITS_INSTRUMENT)
              .getOptionalLabelKeys());
      org.junit.Assert.assertEquals(
          expectedOptionalLabels,
          Objects.requireNonNull(InternalTcpMetrics.MIN_RTT_INSTRUMENT).getOptionalLabelKeys());
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
        eq(InternalTcpMetrics.CONNECTIONS_CREATED_INSTRUMENT), eq(1L),
        eq(Collections.emptyList()),
        eq(Arrays.asList(
            localInet.getHostAddress(), "8080", remoteInet.getHostAddress(), "443")));
    verify(metricRecorder).addLongUpDownCounter(
        eq(InternalTcpMetrics.CONNECTION_COUNT_INSTRUMENT), eq(1L),
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
        eq(InternalTcpMetrics.CONNECTION_COUNT_INSTRUMENT), eq(-1L),
        eq(Collections.emptyList()),
        eq(Arrays.asList(
            localInet.getHostAddress(), "8080", remoteInet.getHostAddress(), "443")));
    verifyNoMoreInteractions(metricRecorder);
  }

  @Test
  public void channelActive_extractsLabels_nonInetAddress() throws Exception {
    SocketAddress dummyAddress = new SocketAddress() {
    };
    when(channel.localAddress()).thenReturn(dummyAddress);
    when(channel.remoteAddress()).thenReturn(dummyAddress);

    metrics.channelActive(channel);

    verify(metricRecorder).addLongCounter(
        eq(InternalTcpMetrics.CONNECTIONS_CREATED_INSTRUMENT), eq(1L),
        eq(Collections.emptyList()),
        eq(Arrays.asList("", "", "", "")));
    verify(metricRecorder).addLongUpDownCounter(
        eq(InternalTcpMetrics.CONNECTION_COUNT_INSTRUMENT), eq(1L),
        eq(Collections.emptyList()),
        eq(Arrays.asList("", "", "", "")));
    verifyNoMoreInteractions(metricRecorder);
  }

  @Test
  public void channelActive_incrementsCounts() throws Exception {
    metrics.channelActive(channel);
    verify(metricRecorder).addLongCounter(
        eq(InternalTcpMetrics.CONNECTIONS_CREATED_INSTRUMENT), eq(1L),
        eq(Collections.emptyList()),
        eq(Arrays.asList("", "", "", "")));
    verify(metricRecorder).addLongUpDownCounter(
        eq(InternalTcpMetrics.CONNECTION_COUNT_INSTRUMENT), eq(1L),
        eq(Collections.emptyList()),
        eq(Arrays.asList("", "", "", "")));
    verifyNoMoreInteractions(metricRecorder);
  }

  @Test
  public void channelInactive_decrementsCount_noEpoll_noError() throws Exception {
    metrics.channelInactive(channel);
    verify(metricRecorder).addLongUpDownCounter(
        eq(InternalTcpMetrics.CONNECTION_COUNT_INSTRUMENT), eq(-1L),
        eq(Collections.emptyList()),
        eq(Arrays.asList("", "", "", "")));
    verifyNoMoreInteractions(metricRecorder);
  }

  @Test
  public void channelActive_schedulesReportTimer() throws Exception {
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
  public void channelInactive_cancelsReportTimer() throws Exception {
    when(channel.isActive()).thenReturn(true);
    metrics.channelActive(channel);

    metrics.channelInactive(channel);

    verify(scheduledFuture).cancel(false);
  }
}
