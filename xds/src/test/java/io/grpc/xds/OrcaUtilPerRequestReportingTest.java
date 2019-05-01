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
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.envoyproxy.udpa.data.orca.v1.OrcaLoadReport;
import io.grpc.ClientStreamTracer;
import io.grpc.Metadata;
import io.grpc.xds.OrcaUtil.OrcaReportListener;
import io.grpc.xds.OrcaUtil.OrcaReportingTracerFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link OrcaUtil}'s methods for per-request ORCA reporting.
 */
@RunWith(JUnit4.class)
public class OrcaUtilPerRequestReportingTest {

  private static final ClientStreamTracer.StreamInfo STREAM_INFO =
      ClientStreamTracer.StreamInfo.newBuilder().build();

  /**
   * Test a single load balance policy's listener receive per-request ORCA reports upon call trailer
   * arrives.
   */
  @Test
  public void singlePolicyPerRequestListener() {
    OrcaReportListener mockListener = mock(OrcaReportListener.class);
    // Use a mocked noop stream tracer factory as the original stream tracer factory.
    ClientStreamTracer.Factory fakeDelegateFactory = mock(ClientStreamTracer.Factory.class);
    ClientStreamTracer fakeTracer = mock(ClientStreamTracer.class);
    doNothing().when(fakeTracer).inboundTrailers(any(Metadata.class));
    when(fakeDelegateFactory.newClientStreamTracer(
            any(ClientStreamTracer.StreamInfo.class), any(Metadata.class)))
        .thenReturn(fakeTracer);

    // The OrcaReportingTracerFactory will augment the StreamInfo passed to its
    // newClientStreamTracer method. The augmented StreamInfo's CallOptions will contain
    // a OrcaReportBroker, in which has the registered listener.
    ClientStreamTracer.Factory factory =
        OrcaUtil.newOrcaClientStreamTracerFactory(fakeDelegateFactory, mockListener);
    ClientStreamTracer tracer = factory.newClientStreamTracer(STREAM_INFO, new Metadata());
    ArgumentCaptor<ClientStreamTracer.StreamInfo> streamInfoCaptor = ArgumentCaptor.forClass(null);
    verify(fakeDelegateFactory)
        .newClientStreamTracer(streamInfoCaptor.capture(), any(Metadata.class));
    ClientStreamTracer.StreamInfo capturedInfo = streamInfoCaptor.getValue();
    assertThat(capturedInfo).isNotEqualTo(STREAM_INFO);

    // When the trailer does not contain ORCA report, listener callback will not be invoked.
    Metadata trailer = new Metadata();
    tracer.inboundTrailers(trailer);
    verify(mockListener, never()).onLoadReport(any(OrcaLoadReport.class));

    // When the trailer contains an ORCA report, listener callback will be invoked.
    trailer.put(
        OrcaReportingTracerFactory.ORCA_ENDPOINT_LOAD_METRICS_KEY,
        OrcaLoadReport.getDefaultInstance());
    tracer.inboundTrailers(trailer);
    ArgumentCaptor<OrcaLoadReport> reportCaptor = ArgumentCaptor.forClass(null);
    verify(mockListener).onLoadReport(reportCaptor.capture());
    assertThat(reportCaptor.getValue()).isEqualTo(OrcaLoadReport.getDefaultInstance());
  }

  /**
   * Test parent-child load balance policies' listener both receive per-request ORCA reports upon
   * call trailer arrives and ORCA report deserialization happens only once.
   */
  @Test
  public void twoLevelPoliciesPerRequestListeners() {
    OrcaReportListener childListener = mock(OrcaReportListener.class);
    ClientStreamTracer.Factory childFactory =
        mock(ClientStreamTracer.Factory.class,
            delegatesTo(OrcaUtil.newOrcaClientStreamTracerFactory(childListener)));

    OrcaReportListener parentListener = mock(OrcaReportListener.class);
    ClientStreamTracer.Factory parentFactory =
        OrcaUtil.newOrcaClientStreamTracerFactory(childFactory, parentListener);
    // Parent factory will augment the StreamInfo with a broker added and pass it to the child
    // factory.
    ClientStreamTracer parentTracer =
        parentFactory.newClientStreamTracer(STREAM_INFO, new Metadata());
    ArgumentCaptor<ClientStreamTracer.StreamInfo> streamInfoCaptor = ArgumentCaptor.forClass(null);
    verify(childFactory).newClientStreamTracer(streamInfoCaptor.capture(), any(Metadata.class));
    ClientStreamTracer.StreamInfo childStreamInfo = streamInfoCaptor.getValue();
    assertThat(childStreamInfo).isNotEqualTo(STREAM_INFO);

    // When the trailer does not contain ORCA report, no listener callback will be invoked.
    Metadata trailer = new Metadata();
    parentTracer.inboundTrailers(trailer);
    verify(parentListener, never()).onLoadReport(any(OrcaLoadReport.class));
    verify(childListener, never()).onLoadReport(any(OrcaLoadReport.class));

    // When the trailer contains an ORCA report, callbacks for both listeners will be invoked.
    // Both listener will receive the same ORCA report instance, which means deserialization
    // happens only once.
    trailer.put(
        OrcaReportingTracerFactory.ORCA_ENDPOINT_LOAD_METRICS_KEY,
        OrcaLoadReport.getDefaultInstance());
    parentTracer.inboundTrailers(trailer);
    ArgumentCaptor<OrcaLoadReport> parentReportCap = ArgumentCaptor.forClass(null);
    ArgumentCaptor<OrcaLoadReport> childReportCap = ArgumentCaptor.forClass(null);
    verify(parentListener).onLoadReport(parentReportCap.capture());
    verify(childListener).onLoadReport(childReportCap.capture());
    assertThat(parentReportCap.getValue()).isEqualTo(OrcaLoadReport.getDefaultInstance());
    assertThat(childReportCap.getValue()).isSameInstanceAs(parentReportCap.getValue());
  }
}
