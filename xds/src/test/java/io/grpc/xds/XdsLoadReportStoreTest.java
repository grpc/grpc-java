/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.Duration;
import io.envoyproxy.envoy.api.v2.core.Locality;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats;
import io.envoyproxy.envoy.api.v2.endpoint.UpstreamLocalityStats;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientStreamTracer;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.xds.XdsClientLoadRecorder.ClientLoadCounter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link XdsLoadReportStore}. */
public class XdsLoadReportStoreTest {
  private static final String SERVICE_NAME = "api.google.com";
  private static final ClientStreamTracer.StreamInfo STREAM_INFO =
      new ClientStreamTracer.StreamInfo() {
        @Override
        public Attributes getTransportAttrs() {
          return Attributes.EMPTY;
        }

        @Override
        public CallOptions getCallOptions() {
          return CallOptions.DEFAULT;
        }
      };
  private static final Locality TEST_LOCALITY = Locality.newBuilder()
      .setRegion("test_region")
      .setZone("test_zone")
      .setSubZone("test_subzone")
      .build();
  @Mock Subchannel fakeSubchannel;
  private ConcurrentMap<Locality, ClientLoadCounter> localityLoadCounters;
  private ConcurrentMap<String, AtomicLong> dropCounters;
  private XdsLoadReportStore loadStore;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    localityLoadCounters = new ConcurrentHashMap<>();
    dropCounters = new ConcurrentHashMap<>();
    loadStore = new XdsLoadReportStore(SERVICE_NAME, localityLoadCounters, dropCounters);
  }

  @Test
  public void testUntrackedLocalityNoLoadRecording() {
    PickResult pickResult = PickResult.withSubchannel(fakeSubchannel);
    // XdsClientLoadStore does not record loads for untracked localities.
    PickResult interceptedPickResult = loadStore.interceptPickResult(pickResult, TEST_LOCALITY);
    assertThat(localityLoadCounters).hasSize(0);
    assertThat(interceptedPickResult.getStreamTracerFactory()).isNull();
  }

  @Test
  public void testInvalidPickResultNotIntercepted() {
    PickResult errorResult = PickResult.withError(Status.UNAVAILABLE.withDescription("Error"));
    PickResult emptyResult = PickResult.withNoResult();
    PickResult droppedResult = PickResult.withDrop(Status.UNAVAILABLE.withDescription("Dropped"));
    PickResult interceptedErrorResult = loadStore.interceptPickResult(errorResult, TEST_LOCALITY);
    PickResult interceptedEmptyResult = loadStore.interceptPickResult(emptyResult, TEST_LOCALITY);
    PickResult interceptedDroppedResult = loadStore
        .interceptPickResult(droppedResult, TEST_LOCALITY);
    assertThat(localityLoadCounters).hasSize(0);
    assertThat(interceptedErrorResult.getStreamTracerFactory()).isNull();
    assertThat(interceptedEmptyResult.getStreamTracerFactory()).isNull();
    assertThat(interceptedDroppedResult.getStreamTracerFactory()).isNull();
  }

  @Test
  public void testInterceptPreserveOriginStreamTracer() {
    loadStore.addLocality(TEST_LOCALITY);
    ClientStreamTracer.Factory mockFactory = mock(ClientStreamTracer.Factory.class);
    ClientStreamTracer mockTracer = mock(ClientStreamTracer.class);
    when(mockFactory
        .newClientStreamTracer(any(ClientStreamTracer.StreamInfo.class), any(Metadata.class)))
        .thenReturn(mockTracer);
    PickResult pickResult = PickResult.withSubchannel(fakeSubchannel, mockFactory);
    PickResult interceptedPickResult = loadStore.interceptPickResult(pickResult, TEST_LOCALITY);
    Metadata metadata = new Metadata();
    interceptedPickResult.getStreamTracerFactory().newClientStreamTracer(STREAM_INFO, metadata)
        .streamClosed(Status.OK);
    ArgumentCaptor<ClientStreamTracer.StreamInfo> streamInfoArgumentCaptor = ArgumentCaptor
        .forClass(ClientStreamTracer.StreamInfo.class);
    ArgumentCaptor<Metadata> metadataArgumentCaptor = ArgumentCaptor.forClass(Metadata.class);
    verify(mockFactory).newClientStreamTracer(streamInfoArgumentCaptor.capture(),
        metadataArgumentCaptor.capture());
    assertThat(streamInfoArgumentCaptor.getValue()).isSameAs(STREAM_INFO);
    assertThat(metadataArgumentCaptor.getValue()).isSameAs(metadata);
    verify(mockTracer).streamClosed(Status.OK);
  }

  @Test
  public void testLoadStatsRecording() {
    Locality locality1 =
        Locality.newBuilder()
            .setRegion("test_region1")
            .setZone("test_zone")
            .setSubZone("test_subzone")
            .build();
    loadStore.addLocality(locality1);
    PickResult pickResult1 = PickResult.withSubchannel(fakeSubchannel);
    PickResult interceptedPickResult1 = loadStore.interceptPickResult(pickResult1, locality1);
    assertThat(interceptedPickResult1.getSubchannel()).isSameAs(fakeSubchannel);
    assertThat(localityLoadCounters).containsKey(locality1);
    ClientStreamTracer tracer =
        interceptedPickResult1
            .getStreamTracerFactory()
            .newClientStreamTracer(STREAM_INFO, new Metadata());
    Duration interval = Duration.newBuilder().setNanos(342).build();
    ClusterStats expectedLoadReport = buildClusterStats(interval,
        Collections.singletonList(buildUpstreamLocalityStats(locality1, 0, 0, 1)));
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));

    // Make another load report should not reset count for calls in progress.
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));

    tracer.streamClosed(Status.OK);
    expectedLoadReport = buildClusterStats(interval,
        Collections.singletonList(buildUpstreamLocalityStats(locality1, 1, 0, 0)));
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));

    // Make another load report should reset finished calls count for calls finished.
    expectedLoadReport = buildClusterStats(interval,
        Collections.singletonList(buildUpstreamLocalityStats(locality1, 0, 0, 0)));
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));

    // PickResult within the same locality should aggregate to the same counter.
    PickResult pickResult2 = PickResult.withSubchannel(fakeSubchannel);
    PickResult interceptedPickResult2 = loadStore.interceptPickResult(pickResult2, locality1);
    assertThat(localityLoadCounters).hasSize(1);
    interceptedPickResult1
        .getStreamTracerFactory()
        .newClientStreamTracer(STREAM_INFO, new Metadata())
        .streamClosed(Status.ABORTED);
    interceptedPickResult2
        .getStreamTracerFactory()
        .newClientStreamTracer(STREAM_INFO, new Metadata())
        .streamClosed(Status.CANCELLED);
    expectedLoadReport = buildClusterStats(interval,
        Collections.singletonList(buildUpstreamLocalityStats(locality1, 0, 2, 0)));
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));

    expectedLoadReport = buildClusterStats(interval,
        Collections.singletonList(buildUpstreamLocalityStats(locality1, 0, 0, 0)));
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));

    Locality locality2 =
        Locality.newBuilder()
            .setRegion("test_region2")
            .setZone("test_zone")
            .setSubZone("test_subzone")
            .build();
    loadStore.addLocality(locality2);
    PickResult pickResult3 = PickResult.withSubchannel(fakeSubchannel);
    PickResult interceptedPickResult3 = loadStore.interceptPickResult(pickResult3, locality2);
    assertThat(localityLoadCounters).containsKey(locality2);
    assertThat(localityLoadCounters).hasSize(2);
    interceptedPickResult3
        .getStreamTracerFactory()
        .newClientStreamTracer(STREAM_INFO, new Metadata());
    List<UpstreamLocalityStats> upstreamLocalityStatsList = new ArrayList<>();
    upstreamLocalityStatsList.add(buildUpstreamLocalityStats(locality1, 0, 0, 0));
    upstreamLocalityStatsList.add(buildUpstreamLocalityStats(locality2, 0, 0, 1));
    expectedLoadReport = buildClusterStats(interval, upstreamLocalityStatsList);
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));
  }

  @Test
  public void testLocalityRemovedContinueRecordingOngoingLoads() {
    loadStore.addLocality(TEST_LOCALITY);
    assertThat(localityLoadCounters).containsKey(TEST_LOCALITY);
    PickResult pickResult = PickResult.withSubchannel(fakeSubchannel);
    PickResult interceptedPickResult = loadStore.interceptPickResult(pickResult, TEST_LOCALITY);
    ClientStreamTracer tracer = interceptedPickResult
        .getStreamTracerFactory()
        .newClientStreamTracer(STREAM_INFO, new Metadata());

    Duration interval = Duration.newBuilder().setNanos(342).build();
    ClusterStats expectedLoadReport = buildClusterStats(interval,
        Collections.singletonList(buildUpstreamLocalityStats(TEST_LOCALITY, 0, 0, 1)));
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));
    // Remote balancer instructs to remove the locality while client has in-progress calls
    // to backends in the locality, the XdsClientLoadStore continues tracking its load stats.
    loadStore.removeLocality(TEST_LOCALITY);
    assertThat(localityLoadCounters).containsKey(TEST_LOCALITY);
    expectedLoadReport = buildClusterStats(interval,
        Collections.singletonList(buildUpstreamLocalityStats(TEST_LOCALITY, 0, 0, 1)));
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));

    tracer.streamClosed(Status.OK);
    expectedLoadReport = buildClusterStats(interval,
        Collections.singletonList(buildUpstreamLocalityStats(TEST_LOCALITY, 1, 0, 0)));
    assertClusterStatsEqual(expectedLoadReport, loadStore.generateLoadReport(interval));
    assertThat(localityLoadCounters).doesNotContainKey(TEST_LOCALITY);
  }

  private UpstreamLocalityStats buildUpstreamLocalityStats(Locality locality, long callsSucceed,
      long callsFailed, long callsInProgress) {
    return UpstreamLocalityStats.newBuilder()
        .setLocality(locality)
        .setTotalSuccessfulRequests(callsSucceed)
        .setTotalErrorRequests(callsFailed)
        .setTotalRequestsInProgress(callsInProgress)
        .build();
  }

  private ClusterStats buildClusterStats(Duration interval,
      List<UpstreamLocalityStats> upstreamLocalityStatsList) {
    return ClusterStats.newBuilder().setClusterName(SERVICE_NAME)
        .addAllUpstreamLocalityStats(upstreamLocalityStatsList)
        .setLoadReportInterval(interval)
        .build();
  }

  private void assertClusterStatsEqual(ClusterStats stats1, ClusterStats stats2) {
    assertEquals(stats1.getClusterName(), stats2.getClusterName());
    assertEquals(stats1.getLoadReportInterval(), stats2.getLoadReportInterval());
    assertEquals(stats1.getUpstreamLocalityStatsCount(), stats2.getUpstreamLocalityStatsCount());
    assertEquals(new HashSet<>(stats1.getUpstreamLocalityStatsList()),
        new HashSet<>(stats2.getUpstreamLocalityStatsList()));
  }
}
