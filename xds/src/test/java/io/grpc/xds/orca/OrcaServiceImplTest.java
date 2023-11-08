/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.xds.orca;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.github.xds.data.orca.v3.OrcaLoadReport;
import com.github.xds.service.orca.v3.OpenRcaServiceGrpc;
import com.github.xds.service.orca.v3.OrcaLoadReportRequest;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Duration;
import io.grpc.BindableService;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.FakeClock;
import io.grpc.services.MetricRecorder;
import io.grpc.testing.GrpcCleanupRule;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.After;
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
public class OrcaServiceImplTest {
  @Rule
  public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  private ManagedChannel channel;
  private Server oobServer;
  private final FakeClock fakeClock = new FakeClock();
  private MetricRecorder defaultTestService;
  private BindableService orcaServiceImpl;
  private final Random random = new Random();
  @Mock
  ClientCall.Listener<OrcaLoadReport> listener;

  @Before
  public void setup() throws Exception {
    defaultTestService = MetricRecorder.newInstance();
    orcaServiceImpl = OrcaServiceImpl.createService(fakeClock.getScheduledExecutorService(),
        defaultTestService, 1, TimeUnit.SECONDS);
    startServerAndGetChannel(orcaServiceImpl);
  }

  @After
  public void teardown() throws Exception {
    channel.shutdownNow();
  }

  private void startServerAndGetChannel(BindableService orcaService) throws Exception {
    oobServer = grpcCleanup.register(
        InProcessServerBuilder.forName("orca-service-test")
            .addService(orcaService)
            .directExecutor()
            .build()
            .start());
    channel = grpcCleanup.register(
        InProcessChannelBuilder.forName("orca-service-test")
            .directExecutor().build());
  }

  @Test
  public void testReportingLifeCycle() {
    defaultTestService.setCpuUtilizationMetric(0.1);
    Iterator<OrcaLoadReport> reports = OpenRcaServiceGrpc.newBlockingStub(channel)
        .streamCoreMetrics(OrcaLoadReportRequest.newBuilder().build());
    assertThat(reports.next()).isEqualTo(
        OrcaLoadReport.newBuilder().setCpuUtilization(0.1).build());
    assertThat(((OrcaServiceImpl)orcaServiceImpl).clientCount.get()).isEqualTo(1);
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);
    assertThat(fakeClock.forwardTime(1, TimeUnit.SECONDS)).isEqualTo(1);
    assertThat(reports.next()).isEqualTo(
        OrcaLoadReport.newBuilder().setCpuUtilization(0.1).build());
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);
    channel.shutdownNow();
    assertThat(((OrcaServiceImpl)orcaServiceImpl).clientCount.get()).isEqualTo(0);
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(0);
  }

  @Test
  public void testReportingLifeCycle_serverShutdown() {
    ClientCall<OrcaLoadReportRequest, OrcaLoadReport> call = channel.newCall(
        OpenRcaServiceGrpc.getStreamCoreMetricsMethod(), CallOptions.DEFAULT);
    defaultTestService.putUtilizationMetric("buffer", 0.2);
    call.start(listener, new Metadata());
    call.sendMessage(OrcaLoadReportRequest.newBuilder()
        .setReportInterval(Duration.newBuilder().setSeconds(0).setNanos(500).build()).build());
    call.halfClose();
    call.request(1);
    OrcaLoadReport expect = OrcaLoadReport.newBuilder().putUtilization("buffer", 0.2).build();
    assertThat(((OrcaServiceImpl)orcaServiceImpl).clientCount.get()).isEqualTo(1);
    verify(listener).onMessage(eq(expect));
    verify(listener, never()).onClose(any(), any());
    oobServer.shutdownNow();
    assertThat(fakeClock.forwardTime(1, TimeUnit.SECONDS)).isEqualTo(0);
    assertThat(((OrcaServiceImpl)orcaServiceImpl).clientCount.get()).isEqualTo(0);
    ArgumentCaptor<Status> callCloseCaptor = ArgumentCaptor.forClass(Status.class);
    verify(listener).onClose(callCloseCaptor.capture(), any());
    assertThat(callCloseCaptor.getValue().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testRequestIntervalLess() {
    ClientCall<OrcaLoadReportRequest, OrcaLoadReport> call = channel.newCall(
        OpenRcaServiceGrpc.getStreamCoreMetricsMethod(), CallOptions.DEFAULT);
    defaultTestService.putUtilizationMetric("buffer", 0.2);
    defaultTestService.setApplicationUtilizationMetric(0.314159);
    defaultTestService.setQpsMetric(1.9);
    defaultTestService.setEpsMetric(0.2233);
    call.start(listener, new Metadata());
    call.sendMessage(OrcaLoadReportRequest.newBuilder()
        .setReportInterval(Duration.newBuilder().setSeconds(0).setNanos(500).build()).build());
    call.halfClose();
    call.request(1);
    OrcaLoadReport expect = OrcaLoadReport.newBuilder().putUtilization("buffer", 0.2)
        .setApplicationUtilization(0.314159).setRpsFractional(1.9).setEps(0.2233).build();
    verify(listener).onMessage(eq(expect));
    reset(listener);
    defaultTestService.removeUtilizationMetric("buffer0");
    defaultTestService.clearApplicationUtilizationMetric();
    defaultTestService.clearQpsMetric();
    defaultTestService.clearEpsMetric();
    assertThat(fakeClock.forwardTime(500, TimeUnit.NANOSECONDS)).isEqualTo(0);
    verifyNoInteractions(listener);
    assertThat(fakeClock.forwardTime(1, TimeUnit.SECONDS)).isEqualTo(1);
    call.request(1);
    expect = OrcaLoadReport.newBuilder().putUtilization("buffer", 0.2).build();
    verify(listener).onMessage(eq(expect));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testRequestIntervalGreater() {
    ClientCall<OrcaLoadReportRequest, OrcaLoadReport> call = channel.newCall(
        OpenRcaServiceGrpc.getStreamCoreMetricsMethod(), CallOptions.DEFAULT);
    defaultTestService.putUtilizationMetric("buffer", 0.2);
    call.start(listener, new Metadata());
    call.sendMessage(OrcaLoadReportRequest.newBuilder()
        .setReportInterval(Duration.newBuilder().setSeconds(10).build()).build());
    call.halfClose();
    call.request(1);
    OrcaLoadReport expect = OrcaLoadReport.newBuilder().putUtilization("buffer", 0.2).build();
    verify(listener).onMessage(eq(expect));
    reset(listener);
    defaultTestService.removeUtilizationMetric("buffer0");
    assertThat(fakeClock.forwardTime(1, TimeUnit.SECONDS)).isEqualTo(0);
    verifyNoInteractions(listener);
    assertThat(fakeClock.forwardTime(9, TimeUnit.SECONDS)).isEqualTo(1);
    call.request(1);
    verify(listener).onMessage(eq(expect));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testRequestIntervalDefault() throws Exception {
    defaultTestService = MetricRecorder.newInstance();
    oobServer.shutdownNow();
    startServerAndGetChannel(OrcaServiceImpl.createService(
        fakeClock.getScheduledExecutorService(), defaultTestService));
    ClientCall<OrcaLoadReportRequest, OrcaLoadReport> call = channel.newCall(
        OpenRcaServiceGrpc.getStreamCoreMetricsMethod(), CallOptions.DEFAULT);
    defaultTestService.putUtilizationMetric("buffer", 0.2);
    call.start(listener, new Metadata());
    call.sendMessage(OrcaLoadReportRequest.newBuilder()
        .setReportInterval(Duration.newBuilder().setSeconds(10).build()).build());
    call.halfClose();
    call.request(1);
    OrcaLoadReport expect = OrcaLoadReport.newBuilder().putUtilization("buffer", 0.2).build();
    verify(listener).onMessage(eq(expect));
    reset(listener);
    defaultTestService.removeUtilizationMetric("buffer0");
    assertThat(fakeClock.forwardTime(10, TimeUnit.SECONDS)).isEqualTo(0);
    verifyNoInteractions(listener);
    assertThat(fakeClock.forwardTime(20, TimeUnit.SECONDS)).isEqualTo(1);
    call.request(1);
    verify(listener).onMessage(eq(expect));
  }

  @Test
  public void testMultipleClients() {
    ClientCall<OrcaLoadReportRequest, OrcaLoadReport> call = channel.newCall(
        OpenRcaServiceGrpc.getStreamCoreMetricsMethod(), CallOptions.DEFAULT);
    defaultTestService.putUtilizationMetric("omg", 1.00);
    call.start(listener, new Metadata());
    call.sendMessage(OrcaLoadReportRequest.newBuilder().build());
    call.halfClose();
    call.request(1);
    OrcaLoadReport expect = OrcaLoadReport.newBuilder().putUtilization("omg", 1.00).build();
    verify(listener).onMessage(eq(expect));
    defaultTestService.setMemoryUtilizationMetric(0.5);
    ClientCall<OrcaLoadReportRequest, OrcaLoadReport> call2 = channel.newCall(
        OpenRcaServiceGrpc.getStreamCoreMetricsMethod(), CallOptions.DEFAULT);
    call2.start(listener, new Metadata());
    call2.sendMessage(OrcaLoadReportRequest.newBuilder().build());
    call2.halfClose();
    call2.request(1);
    expect = OrcaLoadReport.newBuilder(expect).setMemUtilization(0.5).build();
    verify(listener).onMessage(eq(expect));
    assertThat(((OrcaServiceImpl)orcaServiceImpl).clientCount.get()).isEqualTo(2);
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(2);
    channel.shutdownNow();
    assertThat(fakeClock.forwardTime(1, TimeUnit.SECONDS)).isEqualTo(0);
    assertThat(((OrcaServiceImpl)orcaServiceImpl).clientCount.get()).isEqualTo(0);
    ArgumentCaptor<Status> callCloseCaptor = ArgumentCaptor.forClass(Status.class);
    verify(listener, times(2)).onClose(callCloseCaptor.capture(), any());
    assertThat(callCloseCaptor.getValue().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
  }

  @Test
  public void testApis() throws Exception {
    ImmutableMap<String, Double> firstUtilization = ImmutableMap.of("util", 0.1);
    OrcaLoadReport goldenReport = OrcaLoadReport.newBuilder()
        .setCpuUtilization(random.nextDouble() * 10)
        .setApplicationUtilization(random.nextDouble() * 10)
        .setMemUtilization(random.nextDouble())
        .putAllUtilization(firstUtilization)
        .putUtilization("queue", 1.0)
        .setRpsFractional(1239.01)
        .setEps(1.618)
        .build();
    defaultTestService.setCpuUtilizationMetric(goldenReport.getCpuUtilization());
    defaultTestService.setApplicationUtilizationMetric(goldenReport.getApplicationUtilization());
    defaultTestService.setMemoryUtilizationMetric(goldenReport.getMemUtilization());
    defaultTestService.setAllUtilizationMetrics(firstUtilization);
    defaultTestService.putUtilizationMetric("queue", 1.0);
    defaultTestService.setQpsMetric(1239.01);
    defaultTestService.setEpsMetric(1.618);
    Iterator<OrcaLoadReport> reports = OpenRcaServiceGrpc.newBlockingStub(channel)
        .streamCoreMetrics(OrcaLoadReportRequest.newBuilder().build());
    assertThat(reports.next()).isEqualTo(goldenReport);

    defaultTestService.clearCpuUtilizationMetric();
    defaultTestService.clearApplicationUtilizationMetric();
    defaultTestService.clearMemoryUtilizationMetric();
    defaultTestService.clearQpsMetric();
    defaultTestService.clearEpsMetric();
    fakeClock.forwardTime(1, TimeUnit.SECONDS);
    goldenReport = OrcaLoadReport.newBuilder()
        .putAllUtilization(firstUtilization)
        .putUtilization("queue", 1.0)
        .putUtilization("util", 0.1)
        .build();
    assertThat(reports.next()).isEqualTo(goldenReport);
    defaultTestService.removeUtilizationMetric("util-not-exist");
    defaultTestService.removeUtilizationMetric("queue-not-exist");
    fakeClock.forwardTime(1, TimeUnit.SECONDS);
    assertThat(reports.next()).isEqualTo(goldenReport);

    defaultTestService.setCpuUtilizationMetric(-0.001);
    defaultTestService.setApplicationUtilizationMetric(-0.001);
    defaultTestService.setMemoryUtilizationMetric(-0.001);
    defaultTestService.setMemoryUtilizationMetric(1.001);
    defaultTestService.setQpsMetric(-0.001);
    defaultTestService.setEpsMetric(-0.001);
    defaultTestService.putUtilizationMetric("util-out-of-range", -0.001);
    defaultTestService.putUtilizationMetric("util-out-of-range", 1.001);
    fakeClock.forwardTime(1, TimeUnit.SECONDS);
    assertThat(reports.next()).isEqualTo(goldenReport);

    CyclicBarrier barrier = new CyclicBarrier(2);
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          barrier.await();
        } catch (Exception ex) {
          throw new AssertionError(ex);
        }
        defaultTestService.removeUtilizationMetric("util");
        defaultTestService.setMemoryUtilizationMetric(0.4);
        defaultTestService.setAllUtilizationMetrics(firstUtilization);
        try {
          barrier.await();
        } catch (Exception ex) {
          throw new AssertionError(ex);
        }
      }
    }).start();
    barrier.await();
    defaultTestService.setMemoryUtilizationMetric(0.4);
    defaultTestService.removeUtilizationMetric("util");
    defaultTestService.setAllUtilizationMetrics(firstUtilization);
    barrier.await();
    goldenReport = OrcaLoadReport.newBuilder()
        .putAllUtilization(firstUtilization)
        .setMemUtilization(0.4)
        .build();
    fakeClock.forwardTime(1, TimeUnit.SECONDS);
    assertThat(reports.next()).isEqualTo(goldenReport);
  }
}
