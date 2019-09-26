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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.github.udpa.udpa.data.orca.v1.OrcaLoadReport;
import io.grpc.ClientStreamTracer;
import io.grpc.Metadata;
import io.grpc.xds.OrcaPerRequestUtil.OrcaPerRequestReportListener;
import io.grpc.xds.OrcaPerRequestUtil.OrcaReportingTracerFactory;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link OrcaPerRequestUtil} class.
 */
@RunWith(JUnit4.class)
public class OrcaPerRequestUtilTest {

  private static final ClientStreamTracer.StreamInfo STREAM_INFO =
      ClientStreamTracer.StreamInfo.newBuilder().build();

  @Mock
  private OrcaPerRequestReportListener orcaListener1;
  @Mock
  private OrcaPerRequestReportListener orcaListener2;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
  }

  /**
   * Tests a single load balance policy's listener receive per-request ORCA reports upon call
   * trailer arrives.
   */
  @Test
  public void singlePolicyTypicalWorkflow() {
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
        OrcaPerRequestUtil.getInstance()
            .newOrcaClientStreamTracerFactory(fakeDelegateFactory, orcaListener1);
    ClientStreamTracer tracer = factory.newClientStreamTracer(STREAM_INFO, new Metadata());
    ArgumentCaptor<ClientStreamTracer.StreamInfo> streamInfoCaptor = ArgumentCaptor.forClass(null);
    verify(fakeDelegateFactory)
        .newClientStreamTracer(streamInfoCaptor.capture(), any(Metadata.class));
    ClientStreamTracer.StreamInfo capturedInfo = streamInfoCaptor.getValue();
    assertThat(capturedInfo).isNotEqualTo(STREAM_INFO);

    // When the trailer does not contain ORCA report, listener callback will not be invoked.
    Metadata trailer = new Metadata();
    tracer.inboundTrailers(trailer);
    verifyNoMoreInteractions(orcaListener1);

    // When the trailer contains an ORCA report, listener callback will be invoked.
    trailer.put(
        OrcaReportingTracerFactory.ORCA_ENDPOINT_LOAD_METRICS_KEY,
        OrcaLoadReport.getDefaultInstance());
    tracer.inboundTrailers(trailer);
    ArgumentCaptor<OrcaLoadReport> reportCaptor = ArgumentCaptor.forClass(null);
    verify(orcaListener1).onLoadReport(reportCaptor.capture());
    assertThat(reportCaptor.getValue()).isEqualTo(OrcaLoadReport.getDefaultInstance());
  }

  /**
   * Tests parent-child load balance policies' listeners both receive per-request ORCA reports upon
   * call trailer arrives and ORCA report deserialization happens only once.
   */
  @Test
  public void twoLevelPoliciesTypicalWorkflow() {
    ClientStreamTracer.Factory parentFactory =
        mock(ClientStreamTracer.Factory.class,
            delegatesTo(
                OrcaPerRequestUtil.getInstance().newOrcaClientStreamTracerFactory(orcaListener1)));

    ClientStreamTracer.Factory childFactory =
        OrcaPerRequestUtil.getInstance()
            .newOrcaClientStreamTracerFactory(parentFactory, orcaListener2);
    // Child factory will augment the StreamInfo and pass it to the parent factory.
    ClientStreamTracer childTracer =
        childFactory.newClientStreamTracer(STREAM_INFO, new Metadata());
    ArgumentCaptor<ClientStreamTracer.StreamInfo> streamInfoCaptor = ArgumentCaptor.forClass(null);
    verify(parentFactory).newClientStreamTracer(streamInfoCaptor.capture(), any(Metadata.class));
    ClientStreamTracer.StreamInfo parentStreamInfo = streamInfoCaptor.getValue();
    assertThat(parentStreamInfo).isNotEqualTo(STREAM_INFO);

    // When the trailer does not contain ORCA report, no listener callback will be invoked.
    Metadata trailer = new Metadata();
    childTracer.inboundTrailers(trailer);
    verifyNoMoreInteractions(orcaListener1);
    verifyNoMoreInteractions(orcaListener2);

    // When the trailer contains an ORCA report, callbacks for both listeners will be invoked.
    // Both listener will receive the same ORCA report instance, which means deserialization
    // happens only once.
    trailer.put(
        OrcaReportingTracerFactory.ORCA_ENDPOINT_LOAD_METRICS_KEY,
        OrcaLoadReport.getDefaultInstance());
    childTracer.inboundTrailers(trailer);
    ArgumentCaptor<OrcaLoadReport> parentReportCap = ArgumentCaptor.forClass(null);
    ArgumentCaptor<OrcaLoadReport> childReportCap = ArgumentCaptor.forClass(null);
    verify(orcaListener1).onLoadReport(parentReportCap.capture());
    verify(orcaListener2).onLoadReport(childReportCap.capture());
    assertThat(parentReportCap.getValue()).isEqualTo(OrcaLoadReport.getDefaultInstance());
    assertThat(childReportCap.getValue()).isSameInstanceAs(parentReportCap.getValue());
  }

  /**
   * Tests the case when parent policy creates its own {@link ClientStreamTracer.Factory}, ORCA
   * reports are only forwarded to the parent's listener.
   */
  @Test
  public void onlyParentPolicyReceivesReportsIfCreatesOwnTracer() {
    ClientStreamTracer.Factory parentFactory =
        OrcaPerRequestUtil.getInstance().newOrcaClientStreamTracerFactory(orcaListener1);
    ClientStreamTracer.Factory childFactory =
        mock(ClientStreamTracer.Factory.class,
            delegatesTo(OrcaPerRequestUtil.getInstance()
                .newOrcaClientStreamTracerFactory(parentFactory, orcaListener2)));
    ClientStreamTracer parentTracer =
        parentFactory.newClientStreamTracer(STREAM_INFO, new Metadata());
    Metadata trailer = new Metadata();
    trailer.put(
        OrcaReportingTracerFactory.ORCA_ENDPOINT_LOAD_METRICS_KEY,
        OrcaLoadReport.getDefaultInstance());
    parentTracer.inboundTrailers(trailer);
    verify(orcaListener1).onLoadReport(eq(OrcaLoadReport.getDefaultInstance()));
    verifyZeroInteractions(childFactory);
    verifyZeroInteractions(orcaListener2);
  }
}
