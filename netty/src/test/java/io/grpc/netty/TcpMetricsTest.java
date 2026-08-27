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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.grpc.InternalTcpMetrics;
import io.grpc.MetricRecorder;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class TcpMetricsTest {

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  private MetricRecorder metricRecorder;

  private ConfigurableFakeWithTcpInfo channel;
  private TcpMetrics metrics;

  @Before
  public void setUp() throws Exception {
    FakeEpollTcpInfo dummyInfo = new FakeEpollTcpInfo();
    channel = new ConfigurableFakeWithTcpInfo(dummyInfo);
    metrics = new TcpMetrics(metricRecorder);
  }

  @After
  public void tearDown() throws Exception {
    TcpMetrics.epollInfo = TcpMetrics.loadEpollInfo();
  }

  @Test
  public void metricsInitialization() {

    assertNotNull(InternalTcpMetrics.CONNECTIONS_CREATED_INSTRUMENT);
    assertNotNull(InternalTcpMetrics.CONNECTION_COUNT_INSTRUMENT);
    assertNotNull(InternalTcpMetrics.PACKETS_RETRANSMITTED_INSTRUMENT);
    assertNotNull(InternalTcpMetrics.RECURRING_RETRANSMITS_INSTRUMENT);
    assertNotNull(InternalTcpMetrics.MIN_RTT_INSTRUMENT);
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
        ConfigurableFakeWithTcpInfo.class,
        FakeEpollTcpInfo.class.getConstructor(),
        ConfigurableFakeWithTcpInfo.class.getMethod("tcpInfo", FakeEpollTcpInfo.class),
        FakeEpollTcpInfo.class.getMethod("totalRetrans"),
        FakeEpollTcpInfo.class.getMethod("retrans"),
        FakeEpollTcpInfo.class.getMethod("rtt"));
    TcpMetrics tracker = new TcpMetrics(recorder);

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
        ConfigurableFakeWithTcpInfo.class,
        FakeEpollTcpInfo.class.getConstructor(),
        ConfigurableFakeWithTcpInfo.class.getMethod("tcpInfo", FakeEpollTcpInfo.class),
        FakeEpollTcpInfo.class.getMethod("totalRetrans"),
        FakeEpollTcpInfo.class.getMethod("retrans"),
        FakeEpollTcpInfo.class.getMethod("rtt"));
    TcpMetrics tracker = new TcpMetrics(recorder);

    FakeEpollTcpInfo infoSource = new FakeEpollTcpInfo();
    infoSource.setValues(123, 4, 5000);
    ConfigurableFakeWithTcpInfo channel = new ConfigurableFakeWithTcpInfo(infoSource);

    tracker.channelActive(channel);

    ScheduledFuture<?> timer = tracker.getReportTimer();
    assertNotNull("Timer should be scheduled", timer);

    long delay = timer.getDelay(TimeUnit.MILLISECONDS);
    channel.advanceTimeBy(delay + 1, TimeUnit.MILLISECONDS);
    channel.runScheduledPendingTasks();

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
        ConfigurableFakeWithTcpInfo.class,
        FakeEpollTcpInfo.class.getConstructor(),
        ConfigurableFakeWithTcpInfo.class.getMethod("tcpInfo", FakeEpollTcpInfo.class),
        FakeEpollTcpInfo.class.getMethod("totalRetrans"),
        FakeEpollTcpInfo.class.getMethod("retrans"),
        FakeEpollTcpInfo.class.getMethod("rtt"));
    TcpMetrics tracker = new TcpMetrics(recorder);

    FakeEpollTcpInfo infoSource = new FakeEpollTcpInfo();
    infoSource.setValues(123, 4, 5000);
    ConfigurableFakeWithTcpInfo channel = new ConfigurableFakeWithTcpInfo(infoSource);

    tracker.channelActive(channel);

    ScheduledFuture<?> timer = tracker.getReportTimer();
    assertNotNull("Timer should be scheduled", timer);

    long delay = timer.getDelay(TimeUnit.MILLISECONDS);
    channel.advanceTimeBy(delay + 1, TimeUnit.MILLISECONDS);
    channel.runScheduledPendingTasks();

    org.mockito.Mockito.clearInvocations(recorder);

    // Let's just create a new channel instance where tcpInfo sets retrans=5.
    FakeEpollTcpInfo infoSource2 = new FakeEpollTcpInfo();
    infoSource2.setValues(130, 5, 5000);
    ConfigurableFakeWithTcpInfo channel2 = new ConfigurableFakeWithTcpInfo(infoSource2);

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
        ConfigurableFakeWithTcpInfo.class,
        FakeEpollTcpInfo.class.getConstructor(),
        ConfigurableFakeWithTcpInfo.class.getMethod("tcpInfo", FakeEpollTcpInfo.class),
        FakeEpollTcpInfo.class.getMethod("totalRetrans"),
        FakeEpollTcpInfo.class.getMethod("retrans"),
        FakeEpollTcpInfo.class.getMethod("rtt"));
    TcpMetrics tracker = new TcpMetrics(recorder);

    FakeEpollTcpInfo infoSource = new FakeEpollTcpInfo();
    infoSource.setValues(123, 4, 5000);
    ConfigurableFakeWithTcpInfo channel = new ConfigurableFakeWithTcpInfo(infoSource);

    tracker.channelActive(channel);

    ScheduledFuture<?> timer = tracker.getReportTimer();
    assertNotNull("Timer should be scheduled", timer);

    long delay = timer.getDelay(TimeUnit.MILLISECONDS);
    channel.advanceTimeBy(delay + 1, TimeUnit.MILLISECONDS);
    channel.runScheduledPendingTasks();

    verify(recorder).addLongCounter(
        eq(Objects.requireNonNull(InternalTcpMetrics.PACKETS_RETRANSMITTED_INSTRUMENT)),
        eq(123L), any(), any());

    org.mockito.Mockito.clearInvocations(recorder);

    // Change tcpInfo for second periodic record
    infoSource.setValues(150, 2, 6000); // 150 - 123 = 27

    ScheduledFuture<?> newTimer = tracker.getReportTimer();
    assertNotNull("New timer should be scheduled", newTimer);
    assertNotSame("Timer should be a new instance", timer, newTimer);
    long newDelay = newTimer.getDelay(TimeUnit.MILLISECONDS);
    channel.advanceTimeBy(newDelay + 1, TimeUnit.MILLISECONDS);
    channel.runScheduledPendingTasks();

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
        ConfigurableFakeWithTcpInfo.class,
        FakeEpollTcpInfo.class.getConstructor(),
        ConfigurableFakeWithTcpInfo.class.getMethod("tcpInfo", FakeEpollTcpInfo.class),
        FakeEpollTcpInfo.class.getMethod("totalRetrans"),
        FakeEpollTcpInfo.class.getMethod("retrans"),
        FakeEpollTcpInfo.class.getMethod("rtt"));
    TcpMetrics tracker = new TcpMetrics(recorder);

    FakeEpollTcpInfo infoSource = new FakeEpollTcpInfo();
    infoSource.setValues(123, 4, 5000);
    ConfigurableFakeWithTcpInfo channel = new ConfigurableFakeWithTcpInfo(infoSource);

    tracker.channelActive(channel);

    ScheduledFuture<?> timer = tracker.getReportTimer();
    assertNotNull("Timer should be scheduled", timer);

    long delay = timer.getDelay(TimeUnit.MILLISECONDS);
    channel.advanceTimeBy(delay + 1, TimeUnit.MILLISECONDS);
    channel.runScheduledPendingTasks();

    verify(recorder).addLongCounter(
        eq(Objects.requireNonNull(InternalTcpMetrics.PACKETS_RETRANSMITTED_INSTRUMENT)),
        eq(123L), any(), any());

    org.mockito.Mockito.clearInvocations(recorder);

    // Keep tcpInfo the same for second periodic record
    ScheduledFuture<?> newTimer = tracker.getReportTimer();
    assertNotNull("New timer should be scheduled", newTimer);
    assertNotSame("Timer should be a new instance", timer, newTimer);
    long newDelay = newTimer.getDelay(TimeUnit.MILLISECONDS);
    channel.advanceTimeBy(newDelay + 1, TimeUnit.MILLISECONDS);
    channel.runScheduledPendingTasks();

    // NO delta (123 - 123 = 0), so it should not be recorded
    verify(recorder, org.mockito.Mockito.never())
        .addLongCounter(
            eq(Objects.requireNonNull(InternalTcpMetrics.PACKETS_RETRANSMITTED_INSTRUMENT)),
            anyLong(), any(), any());

    // MIN_RTT should be recorded again!
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

  private static class AddressOverrideEmbeddedChannel extends
      io.netty.channel.embedded.EmbeddedChannel {
    private final SocketAddress local;
    private final SocketAddress remote;

    public AddressOverrideEmbeddedChannel(SocketAddress local, SocketAddress remote) {
      this.local = local;
      this.remote = remote;
    }

    @Override
    public SocketAddress localAddress() {
      return local;
    }

    @Override
    public SocketAddress remoteAddress() {
      return remote;
    }
  }

  @Test
  public void tracker_reportsDeltas_correctly() throws Exception {
    MetricRecorder recorder = mock(MetricRecorder.class);

    TcpMetrics.epollInfo = new TcpMetrics.EpollInfo(
        ConfigurableFakeWithTcpInfo.class,
        FakeEpollTcpInfo.class.getConstructor(),
        ConfigurableFakeWithTcpInfo.class.getMethod("tcpInfo", FakeEpollTcpInfo.class),
        FakeEpollTcpInfo.class.getMethod("totalRetrans"),
        FakeEpollTcpInfo.class.getMethod("retrans"),
        FakeEpollTcpInfo.class.getMethod("rtt"));
    TcpMetrics tracker = new TcpMetrics(recorder);

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
  public void tracker_recordTcpInfo_reflectionFailure() {
    MetricRecorder recorder = mock(MetricRecorder.class);

    TcpMetrics.epollInfo = null;
    TcpMetrics tracker = new TcpMetrics(recorder);

    io.netty.channel.embedded.EmbeddedChannel channel = new
        io.netty.channel.embedded.EmbeddedChannel();

    // Should catch exception and ignore
    tracker.channelInactive(channel);
  }

  @Test
  public void registeredMetrics_haveCorrectOptionalLabels() {
    List<String> expectedOptionalLabels = Arrays.asList(
        "network.local.address",
        "network.local.port",
        "network.peer.address",
        "network.peer.port");

    assertEquals(
        expectedOptionalLabels,
        InternalTcpMetrics.CONNECTIONS_CREATED_INSTRUMENT.getOptionalLabelKeys());
    assertEquals(
        expectedOptionalLabels,
        InternalTcpMetrics.CONNECTION_COUNT_INSTRUMENT.getOptionalLabelKeys());

    assertEquals(
        expectedOptionalLabels,
        Objects.requireNonNull(InternalTcpMetrics.PACKETS_RETRANSMITTED_INSTRUMENT)
            .getOptionalLabelKeys());
    assertEquals(
        expectedOptionalLabels,
        Objects.requireNonNull(InternalTcpMetrics.RECURRING_RETRANSMITS_INSTRUMENT)
            .getOptionalLabelKeys());
    assertEquals(
        expectedOptionalLabels,
        Objects.requireNonNull(InternalTcpMetrics.MIN_RTT_INSTRUMENT).getOptionalLabelKeys());
  }

  @Test
  public void channelActive_extractsLabels_ipv4() throws Exception {
    InetAddress localInet = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
    InetAddress remoteInet = InetAddress.getByAddress(new byte[] {127, 0, 0, 2});

    AddressOverrideEmbeddedChannel channel = new AddressOverrideEmbeddedChannel(
        new InetSocketAddress(localInet, 8080),
        new InetSocketAddress(remoteInet, 443));

    metrics.channelActive(channel);

    verify(metricRecorder).addLongCounter(
        eq(InternalTcpMetrics.CONNECTIONS_CREATED_INSTRUMENT), eq(1L),
        eq(Collections.emptyList()),
        eq(Arrays.asList("127.0.0.1", "8080", "127.0.0.2", "443")));
    verify(metricRecorder).addLongUpDownCounter(
        eq(InternalTcpMetrics.CONNECTION_COUNT_INSTRUMENT), eq(1L),
        eq(Collections.emptyList()),
        eq(Arrays.asList("127.0.0.1", "8080", "127.0.0.2", "443")));
    verifyNoMoreInteractions(metricRecorder);
  }

  @Test
  public void channelInactive_extractsLabels_ipv6() throws Exception {
    InetAddress localInet = InetAddress.getByAddress(new byte[] {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1});
    InetAddress remoteInet = InetAddress.getByAddress(new byte[] {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,2});

    AddressOverrideEmbeddedChannel channel = new AddressOverrideEmbeddedChannel(
        new InetSocketAddress(localInet, 8080),
        new InetSocketAddress(remoteInet, 443));

    metrics.channelInactive(channel);

    verify(metricRecorder).addLongUpDownCounter(
        eq(InternalTcpMetrics.CONNECTION_COUNT_INSTRUMENT), eq(-1L),
        eq(Collections.emptyList()),
        eq(Arrays.asList("0:0:0:0:0:0:0:1", "8080", "0:0:0:0:0:0:0:2", "443")));
    verifyNoMoreInteractions(metricRecorder);
  }

  @Test
  public void channelActive_extractsLabels_nonInetAddress() {
    SocketAddress dummyAddress = new SocketAddress() {
    };
    AddressOverrideEmbeddedChannel channel = new AddressOverrideEmbeddedChannel(
        dummyAddress, dummyAddress);

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
  public void channelActive_incrementsCounts() {
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
  public void channelInactive_decrementsCount_noEpoll_noError() {
    metrics.channelInactive(channel);
    verify(metricRecorder).addLongUpDownCounter(
        eq(InternalTcpMetrics.CONNECTION_COUNT_INSTRUMENT), eq(-1L),
        eq(Collections.emptyList()),
        eq(Arrays.asList("", "", "", "")));
    verifyNoMoreInteractions(metricRecorder);
  }

  @Test
  public void channelActive_schedulesReportTimer() throws Exception {
    TcpMetrics.epollInfo = new TcpMetrics.EpollInfo(
        ConfigurableFakeWithTcpInfo.class,
        FakeEpollTcpInfo.class.getConstructor(),
        ConfigurableFakeWithTcpInfo.class.getMethod("tcpInfo", FakeEpollTcpInfo.class),
        FakeEpollTcpInfo.class.getMethod("totalRetrans"),
        FakeEpollTcpInfo.class.getMethod("retrans"),
        FakeEpollTcpInfo.class.getMethod("rtt"));

    metrics = new TcpMetrics(metricRecorder);

    FakeEpollTcpInfo infoSource = new FakeEpollTcpInfo();
    ConfigurableFakeWithTcpInfo channel = new ConfigurableFakeWithTcpInfo(infoSource);

    metrics.channelActive(channel);

    ScheduledFuture<?> timer = metrics.getReportTimer();
    assertNotNull("Timer should be scheduled", timer);

    long delay = timer.getDelay(TimeUnit.MILLISECONDS);
    assertTrue("Delay should be >= 30000 but was " + delay, delay >= 30_000);
    assertTrue("Delay should be <= 330000 but was " + delay, delay <= 330_000);

    // Advance time to trigger the task
    channel.advanceTimeBy(delay + 1, TimeUnit.MILLISECONDS);
    channel.runScheduledPendingTasks();

    // Verify rescheduling
    ScheduledFuture<?> newTimer = metrics.getReportTimer();
    assertNotNull("New timer should be scheduled", newTimer);
    assertNotSame("Timer should be a new instance", timer, newTimer);

    long newDelay = newTimer.getDelay(TimeUnit.MILLISECONDS);
    // Re-arming jitter is 90% to 110%, so 270,000 ms to 330,000 ms
    assertTrue("Delay should be >= 270000 but was " + newDelay, newDelay >= 270_000);
    assertTrue("Delay should be <= 330000 but was " + newDelay, newDelay <= 330_000);
  }

  @Test
  public void channelInactive_cancelsReportTimer() throws Exception {
    TcpMetrics.epollInfo = new TcpMetrics.EpollInfo(
        ConfigurableFakeWithTcpInfo.class,
        FakeEpollTcpInfo.class.getConstructor(),
        ConfigurableFakeWithTcpInfo.class.getMethod("tcpInfo", FakeEpollTcpInfo.class),
        FakeEpollTcpInfo.class.getMethod("totalRetrans"),
        FakeEpollTcpInfo.class.getMethod("retrans"),
        FakeEpollTcpInfo.class.getMethod("rtt"));

    metrics = new TcpMetrics(metricRecorder);

    FakeEpollTcpInfo infoSource = new FakeEpollTcpInfo();
    ConfigurableFakeWithTcpInfo channel = new ConfigurableFakeWithTcpInfo(infoSource);

    metrics.channelActive(channel);

    ScheduledFuture<?> timer = metrics.getReportTimer();
    assertNotNull("Timer should be scheduled", timer);

    metrics.channelInactive(channel);

    assertTrue("Timer should be cancelled", timer.isCancelled());
  }
}
