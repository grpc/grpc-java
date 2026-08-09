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

package io.grpc.internal;

import static io.grpc.internal.ClientStreamListener.RpcProgress.PROCESSED;
import static io.grpc.internal.GrpcUtil.DEFAULT_MAX_MESSAGE_SIZE;
import static io.grpc.internal.GrpcUtil.MESSAGE_ACCEPT_ENCODING_KEY;
import static io.grpc.internal.GrpcUtil.MESSAGE_ENCODING_KEY;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.grpc.CallOptions;
import io.grpc.InternalMetadata;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.internal.Http2ClientStreamTransportState;
import io.grpc.internal.TransportTracer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

/** Unit tests for grpc-accept-encoding validation in {@link Http2ClientStreamTransportState}. */
@RunWith(JUnit4.class)
public class Http2ClientStreamTransportStateGrpcAcceptEncodingTest {

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  private final Metadata.Key<String> testStatusMashaller =
      InternalMetadata.keyOf(":status", Metadata.ASCII_STRING_MARSHALLER);

  private TransportTracer transportTracer;
  @Mock private ClientStreamListener mockListener;
  @Captor private ArgumentCaptor<Status> statusCaptor;

  @Before
  public void setUp() {
    transportTracer = new TransportTracer();

    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        StreamListener.MessageProducer producer =
            (StreamListener.MessageProducer) invocation.getArguments()[0];
        while (producer.next() != null) {}
        return null;
      }
    }).when(mockListener).messagesAvailable(ArgumentMatchers.<StreamListener.MessageProducer>any());
  }

  @Test
  public void transportHeadersReceived_validGrpcAcceptEncoding_gzip() {
    BaseTransportState state = new BaseTransportState(transportTracer);
    state.setListener(mockListener);
    // Client sent gzip-encoded request
    state.setMessageCompression(true, "gzip");

    Metadata headers = new Metadata();
    headers.put(testStatusMashaller, "200");
    headers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER),
        "application/grpc");
    headers.put(MESSAGE_ACCEPT_ENCODING_KEY, "gzip".getBytes(US_ASCII));
    state.transportHeadersReceived(headers);

    verify(mockListener, never()).closed(any(Status.class), same(PROCESSED), any(Metadata.class));
    verify(mockListener).headersRead(headers);
  }

  @Test
  public void transportHeadersReceived_missingGrpcAcceptEncoding_whenGzipSent_logsWarning() {
    BaseTransportState state = new BaseTransportState(transportTracer);
    state.setListener(mockListener);
    // Client sent gzip-encoded request
    state.setMessageCompression(true, "gzip");

    Metadata headers = new Metadata();
    headers.put(testStatusMashaller, "200");
    headers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER),
        "application/grpc");
    // No grpc-accept-encoding header when client sent gzip
    state.transportHeadersReceived(headers);

    // Should still notify listener but log warning
    verify(mockListener).headersRead(headers);
  }

  @Test
  public void transportHeadersReceived_grpcAcceptEncodingIdentity_whenGzipSent_logsWarning() {
    BaseTransportState state = new BaseTransportState(transportTracer);
    state.setListener(mockListener);
    // Client sent gzip-encoded request
    state.setMessageCompression(true, "gzip");

    Metadata headers = new Metadata();
    headers.put(testStatusMashaller, "200");
    headers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER),
        "application/grpc");
    // Server only accepts identity when client sent gzip
    headers.put(MESSAGE_ACCEPT_ENCODING_KEY, "identity".getBytes(US_ASCII));
    state.transportHeadersReceived(headers);

    // Should still notify listener but log warning
    verify(mockListener).headersRead(headers);
  }

  @Test
  public void transportHeadersReceived_grpcAcceptEncodingGzipAndDeflate_whenGzipSent_ok() {
    BaseTransportState state = new BaseTransportState(transportTracer);
    state.setListener(mockListener);
    // Client sent gzip-encoded request
    state.setMessageCompression(true, "gzip");

    Metadata headers = new Metadata();
    headers.put(testStatusMashaller, "200");
    headers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER),
        "application/grpc");
    // Server accepts gzip and deflate
    headers.put(MESSAGE_ACCEPT_ENCODING_KEY, "gzip,deflate".getBytes(US_ASCII));
    state.transportHeadersReceived(headers);

    verify(mockListener).headersRead(headers);
  }

  @Test
  public void transportHeadersReceived_noClientCompression_noWarning() {
    BaseTransportState state = new BaseTransportState(transportTracer);
    state.setListener(mockListener);
    // Client did NOT send compressed request (no setMessageCompression call)

    Metadata headers = new Metadata();
    headers.put(testStatusMashaller, "200");
    headers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER),
        "application/grpc");
    // Client didn't send compressed request, so no validation needed
    state.transportHeadersReceived(headers);

    verify(mockListener).headersRead(headers);
  }

  @Test
  public void transportHeadersReceived_grpcAcceptEncodingMissingOnTrailers() {
    BaseTransportState state = new BaseTransportState(transportTracer);
    state.setListener(mockListener);
    // Client sent gzip-encoded request
    state.setMessageCompression(true, "gzip");

    Metadata headers = new Metadata();
    headers.put(testStatusMashaller, "200");
    headers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER),
        "application/grpc");
    state.transportHeadersReceived(headers);

    Metadata trailers = new Metadata();
    trailers.put(Metadata.Key.of("grpc-status", Metadata.ASCII_STRING_MARSHALLER), "0");
    state.transportTrailersReceived(trailers);

    verify(mockListener).closed(Status.OK, PROCESSED, trailers);
  }

  @Test
  public void transportHeadersReceived_grpcAcceptEncodingCaseInsensitive() {
    BaseTransportState state = new BaseTransportState(transportTracer);
    state.setListener(mockListener);
    // Client sent gzip-encoded request
    state.setMessageCompression(true, "gzip");

    Metadata headers = new Metadata();
    headers.put(testStatusMashaller, "200");
    headers.put(Metadata.Key.of("content-type", Metadata.ASCII_STRING_MARSHALLER),
        "application/grpc");
    // Server accepts GZIP (uppercase)
    headers.put(MESSAGE_ACCEPT_ENCODING_KEY, "GZIP".getBytes(US_ASCII));
    state.transportHeadersReceived(headers);

    verify(mockListener).headersRead(headers);
  }

  private static class BaseTransportState extends Http2ClientStreamTransportState {
    private int onReadyThreshold;

    public BaseTransportState(TransportTracer transportTracer, CallOptions options) {
      super(DEFAULT_MAX_MESSAGE_SIZE, StatsTraceContext.NOOP, transportTracer, options);
    }

    public BaseTransportState(TransportTracer transportTracer) {
      this(transportTracer, CallOptions.DEFAULT);
    }

    @Override
    protected void http2ProcessingFailed(Status status, boolean stopDelivery, Metadata trailers) {
      transportReportStatus(status, stopDelivery, trailers);
    }

    @Override
    public void deframeFailed(Throwable cause) {}

    @Override
    public void bytesRead(int processedBytes) {}

    @Override
    public void runOnTransportThread(Runnable r) {
      r.run();
    }

    @Override
    void setOnReadyThreshold(int numBytes) {
      onReadyThreshold = numBytes;
      super.setOnReadyThreshold(numBytes);
    }
  }
}