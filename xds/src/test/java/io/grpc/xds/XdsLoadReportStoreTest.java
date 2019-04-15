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

import io.envoyproxy.envoy.api.v2.core.Locality;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientStreamTracer;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.xds.XdsClientLoadRecorder.ClientLoadCounter;
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
    Locality locality =
        Locality.newBuilder()
            .setRegion("test_region")
            .setZone("test_zone")
            .setSubZone("test_subzone")
            .build();
    PickResult pickResult = PickResult.withSubchannel(fakeSubchannel);
    // XdsClientLoadStore does not record loads for untracked localities.
    PickResult interceptedPickResult = loadStore.interceptPickResult(pickResult, locality);
    assertThat(localityLoadCounters).hasSize(0);
    assertThat(interceptedPickResult.getStreamTracerFactory()).isNull();
    // Client loads are recorded for tracked localities.
    loadStore.addLocality(locality);
    assertThat(localityLoadCounters).containsKey(locality);
    interceptedPickResult = loadStore.interceptPickResult(pickResult, locality);
    ClientStreamTracer tracer = interceptedPickResult
        .getStreamTracerFactory()
        .newClientStreamTracer(STREAM_INFO, new Metadata());
    ClientLoadCounter counter = localityLoadCounters.get(locality);
    XdsClientLoadRecorder.ClientLoadSnapshot snapshot = counter.snapshot();
    assertEquals(0, snapshot.callsSucceed);
    assertEquals(0, snapshot.callsFailed);
    assertEquals(1, snapshot.callsInProgress);
    // Client loads for localities no longer tracked are not recorded any more,
    // even if calls are in progress.
    loadStore.removeLocality(locality);
    assertThat(localityLoadCounters).doesNotContainKey(locality);
    tracer.streamClosed(Status.OK);
    counter.snapshot();
    assertEquals(0, snapshot.callsSucceed);
    assertEquals(0, snapshot.callsFailed);
    assertEquals(1, snapshot.callsInProgress);
  }

  @Test
  public void testInvalidPickResultNotIntercepted() {
    Locality locality =
        Locality.newBuilder()
            .setRegion("test_region")
            .setZone("test_zone")
            .setSubZone("test_subzone")
            .build();
    PickResult errorResult = PickResult.withError(Status.UNAVAILABLE.withDescription("Error"));
    PickResult emptyResult = PickResult.withNoResult();
    PickResult droppedResult = PickResult.withDrop(Status.UNAVAILABLE.withDescription("Dropped"));
    PickResult interceptedErrorResult = loadStore.interceptPickResult(errorResult, locality);
    PickResult interceptedEmptyResult = loadStore.interceptPickResult(emptyResult, locality);
    PickResult interceptedDroppedResult = loadStore.interceptPickResult(droppedResult, locality);
    assertThat(localityLoadCounters).hasSize(0);
    assertThat(interceptedErrorResult.getStreamTracerFactory()).isNull();
    assertThat(interceptedEmptyResult.getStreamTracerFactory()).isNull();
    assertThat(interceptedDroppedResult.getStreamTracerFactory()).isNull();
  }

  @Test
  public void testInterceptPreserveOriginStreamTracer() {
    Locality locality =
        Locality.newBuilder()
            .setRegion("test_region")
            .setZone("test_zone")
            .setSubZone("test_subzone")
            .build();
    loadStore.addLocality(locality);
    ClientStreamTracer.Factory mockFactory = mock(ClientStreamTracer.Factory.class);
    ClientStreamTracer mockTracer = mock(ClientStreamTracer.class);
    when(mockFactory
        .newClientStreamTracer(any(ClientStreamTracer.StreamInfo.class), any(Metadata.class)))
        .thenReturn(mockTracer);
    PickResult pickResult = PickResult.withSubchannel(fakeSubchannel, mockFactory);
    PickResult interceptedPickResult = loadStore.interceptPickResult(pickResult, locality);
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
    XdsClientLoadRecorder.ClientLoadSnapshot snapshot =
        localityLoadCounters.get(locality1).snapshot();
    assertEquals(0, snapshot.callsSucceed);
    assertEquals(0, snapshot.callsFailed);
    assertEquals(1, snapshot.callsInProgress);

    // Taking snapshot should not reset count for calls in progress.
    snapshot = localityLoadCounters.get(locality1).snapshot();
    assertEquals(1, snapshot.callsInProgress);

    tracer.streamClosed(Status.OK);
    snapshot = localityLoadCounters.get(locality1).snapshot();
    assertEquals(1, snapshot.callsSucceed);
    assertEquals(0, snapshot.callsFailed);
    assertEquals(0, snapshot.callsInProgress);

    // Taking snapshot should reset finished calls count for calls finished.
    snapshot = localityLoadCounters.get(locality1).snapshot();
    assertEquals(0, snapshot.callsSucceed);

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
    snapshot = localityLoadCounters.get(locality1).snapshot();
    assertEquals(0, snapshot.callsSucceed);
    assertEquals(2, snapshot.callsFailed);
    assertEquals(0, snapshot.callsInProgress);

    snapshot = localityLoadCounters.get(locality1).snapshot();
    assertEquals(0, snapshot.callsFailed);

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
    snapshot = localityLoadCounters.get(locality2).snapshot();
    assertEquals(0, snapshot.callsSucceed);
    assertEquals(0, snapshot.callsFailed);
    assertEquals(1, snapshot.callsInProgress);
  }
}
