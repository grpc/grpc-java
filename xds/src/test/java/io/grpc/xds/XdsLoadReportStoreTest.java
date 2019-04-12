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

import io.envoyproxy.envoy.api.v2.core.Locality;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientStreamTracer;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

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
  private Map<Locality, XdsClientLoadRecorder.ClientLoadCounter> localityLoadCounters;
  private XdsLoadReportStore loadStore;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    localityLoadCounters = new HashMap<>();
    loadStore = new XdsLoadReportStore(SERVICE_NAME, localityLoadCounters);
  }

  @Test
  public void testLoadStatsRecording() {
    Locality locality1 =
        Locality.newBuilder()
            .setRegion("test_region1")
            .setZone("test_zone")
            .setSubZone("test_subzone")
            .build();
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
    tracer.streamClosed(Status.OK);
    snapshot = localityLoadCounters.get(locality1).snapshot();
    assertEquals(1, snapshot.callsSucceed);
    assertEquals(0, snapshot.callsFailed);
    assertEquals(0, snapshot.callsInProgress);

    // PickResult within the same locality should aggregate to the same counter.
    PickResult pickResult2 = PickResult.withSubchannel(fakeSubchannel);
    PickResult interceptedPickResult2 = loadStore.interceptPickResult(pickResult2, locality1);
    assertThat(localityLoadCounters).hasSize(1);
    interceptedPickResult2
        .getStreamTracerFactory()
        .newClientStreamTracer(STREAM_INFO, new Metadata())
        .streamClosed(Status.CANCELLED);
    snapshot = localityLoadCounters.get(locality1).snapshot();
    assertEquals(0, snapshot.callsSucceed);
    assertEquals(1, snapshot.callsFailed);
    assertEquals(0, snapshot.callsInProgress);

    Locality locality2 =
        Locality.newBuilder()
            .setRegion("test_region2")
            .setZone("test_zone")
            .setSubZone("test_subzone")
            .build();
    PickResult pickResult3 = PickResult.withSubchannel(fakeSubchannel);
    PickResult interceptedPickResult3 = loadStore.interceptPickResult(pickResult1, locality2);
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
