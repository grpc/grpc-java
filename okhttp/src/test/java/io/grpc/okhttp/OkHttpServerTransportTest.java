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

package io.grpc.okhttp;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.truth.Truth.assertThat;
import static io.grpc.okhttp.Headers.CONTENT_TYPE_HEADER;
import static io.grpc.okhttp.Headers.HTTP_SCHEME_HEADER;
import static io.grpc.okhttp.Headers.METHOD_HEADER;
import static io.grpc.okhttp.Headers.TE_HEADER;
import static org.mockito.AdditionalAnswers.answerVoid;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.google.common.io.ByteStreams;
import io.grpc.Attributes;
import io.grpc.InternalChannelz.SocketStats;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.FakeClock;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.KeepAliveEnforcer;
import io.grpc.internal.ServerStream;
import io.grpc.internal.ServerStreamListener;
import io.grpc.internal.ServerTransportListener;
import io.grpc.okhttp.internal.framed.ErrorCode;
import io.grpc.okhttp.internal.framed.FrameReader;
import io.grpc.okhttp.internal.framed.FrameWriter;
import io.grpc.okhttp.internal.framed.Header;
import io.grpc.okhttp.internal.framed.HeadersMode;
import io.grpc.okhttp.internal.framed.Http2;
import io.grpc.okhttp.internal.framed.Settings;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okio.Buffer;
import okio.BufferedSource;
import okio.ByteString;
import okio.Okio;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

/**
 * Tests for {@link OkHttpServerTransport}.
 */
@RunWith(JUnit4.class)
public class OkHttpServerTransportTest {
  private static final int TIME_OUT_MS = 2000;
  private static final int INITIAL_WINDOW_SIZE = 65535;
  private static final long MAX_CONNECTION_IDLE = TimeUnit.SECONDS.toNanos(1);

  private MockServerTransportListener mockTransportListener = new MockServerTransportListener();
  private ServerTransportListener transportListener
      = mock(ServerTransportListener.class, delegatesTo(mockTransportListener));
  private OkHttpServerTransport serverTransport;
  private final ExecutorService threadPool = Executors.newCachedThreadPool();
  private final SocketPair socketPair = SocketPair.create(threadPool);
  private final FrameWriter clientFrameWriter
      = new Http2().newWriter(Okio.buffer(Okio.sink(socketPair.getClientOutputStream())), true);
  private final FrameReader clientFrameReader
      = new Http2().newReader(Okio.buffer(Okio.source(socketPair.getClientInputStream())), true);
  private final FrameReader.Handler clientFramesRead = mock(FrameReader.Handler.class);
  private final DataFrameHandler clientDataFrames = mock(DataFrameHandler.class);
  private HandshakerSocketFactory handshakerSocketFactory
      = mock(HandshakerSocketFactory.class, delegatesTo(new PlaintextHandshakerSocketFactory()));
  private final FakeClock fakeClock = new FakeClock();
  private OkHttpServerBuilder serverBuilder
      = new OkHttpServerBuilder(new InetSocketAddress(1234), handshakerSocketFactory)
      .executor(new FakeClock().getScheduledExecutorService()) // Executor unused
      .scheduledExecutorService(fakeClock.getScheduledExecutorService())
      .transportExecutor(new Executor() {
        @Override public void execute(Runnable runnable) {
          if (runnable instanceof OkHttpServerTransport.FrameHandler) {
            threadPool.execute(runnable);
          } else {
            // Writing is buffered in the PipeSocket, so AsyncSinc can be executed immediately
            runnable.run();
          }
        }
      })
      .flowControlWindow(INITIAL_WINDOW_SIZE)
      .maxConnectionIdle(MAX_CONNECTION_IDLE, TimeUnit.NANOSECONDS)
      .permitKeepAliveWithoutCalls(true)
      .permitKeepAliveTime(0, TimeUnit.SECONDS);

  @Rule public final Timeout globalTimeout = Timeout.seconds(10);

  @Before
  @SuppressWarnings("DirectInvocationOnMock")
  public void setUp() throws Exception {
    doAnswer(answerVoid((Boolean outDone, Integer streamId, BufferedSource in, Integer length) -> {
      in.require(length);
      Buffer buf = new Buffer();
      buf.write(in.getBuffer(), length);
      clientDataFrames.data(outDone, streamId, buf);
    })).when(clientFramesRead).data(anyBoolean(), anyInt(), any(BufferedSource.class), anyInt());
  }

  @After
  public void tearDown() throws Exception {
    threadPool.shutdownNow();
    try {
      socketPair.client.close();
    } finally {
      socketPair.server.close();
    }
  }

  @Test
  public void startThenShutdown() throws Exception {
    initTransport();
    handshake();
    shutdownAndTerminate(/*lastStreamId=*/ 0);
  }

  @Test
  public void maxConnectionAge() throws Exception {
    serverBuilder.maxConnectionAge(5, TimeUnit.SECONDS)
        .maxConnectionAgeGrace(3, TimeUnit.SECONDS);
    initTransport();
    handshake();
    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        TE_HEADER));
    clientFrameWriter.synStream(true, false, 1, -1, Arrays.asList(
        new Header("some-client-sent-trailer", "trailer-value")));
    pingPong();
    fakeClock.forwardNanos(TimeUnit.SECONDS.toNanos(6)); // > 1.1 * 5
    verifyGracefulShutdown(1);
    pingPong();
    fakeClock.forwardNanos(TimeUnit.SECONDS.toNanos(3));
    assertThat(socketPair.server.isClosed()).isTrue();
  }

  @Test
  public void maxConnectionAge_shutdown() throws Exception {
    serverBuilder.maxConnectionAge(5, TimeUnit.SECONDS)
        .maxConnectionAgeGrace(3, TimeUnit.SECONDS);
    initTransport();
    handshake();
    shutdownAndTerminate(0);
    assertThat(fakeClock.numPendingTasks()).isEqualTo(0);
  }

  @Test
  public void maxConnectionIdleTimer() throws Exception {
    initTransport();
    handshake();
    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        TE_HEADER));
    clientFrameWriter.synStream(true, false, 1, -1, Arrays.asList(
        new Header("some-client-sent-trailer", "trailer-value")));
    pingPong();

    MockStreamListener streamListener = mockTransportListener.newStreams.pop();
    assertThat(streamListener.messages.peek()).isNull();
    assertThat(streamListener.halfClosedCalled).isTrue();

    streamListener.stream.close(Status.OK, new Metadata());

    List<Header> responseTrailers = Arrays.asList(
        new Header(":status", "200"),
        CONTENT_TYPE_HEADER,
        new Header("grpc-status", "0"));
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead)
        .headers(false, true, 1, -1, responseTrailers, HeadersMode.HTTP_20_HEADERS);

    fakeClock.forwardNanos(MAX_CONNECTION_IDLE);
    fakeClock.forwardNanos(MAX_CONNECTION_IDLE);
    verifyGracefulShutdown(1);
  }

  @Test
  public void maxConnectionIdleTimer_respondWithError() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        TE_HEADER,
        new Header("host", "example.com:80"),
        new Header("host", "example.com:80")));
    clientFrameWriter.flush();

    verifyHttpError(
        1, 400, Status.Code.INTERNAL, "Multiple host headers disallowed. RFC7230 section 5.4");

    pingPong();
    fakeClock.forwardNanos(MAX_CONNECTION_IDLE);
    fakeClock.forwardNanos(MAX_CONNECTION_IDLE);
    verifyGracefulShutdown(1);
  }

  @Test
  public void startThenShutdownTwice() throws Exception {
    initTransport();
    handshake();
    serverTransport.shutdown();
    shutdownAndTerminate(/*lastStreamId=*/ 0);
  }

  @Test
  public void shutdownDuringHandshake() throws Exception {
    doAnswer(invocation -> {
      ((Socket) invocation.getArguments()[0]).getInputStream().read();
      throw new IOException("handshake purposefully failed");
    }).when(handshakerSocketFactory).handshake(any(Socket.class), any(Attributes.class));
    serverBuilder.transportExecutor(threadPool);
    initTransport();
    serverTransport.shutdown();

    verify(transportListener, timeout(TIME_OUT_MS)).transportTerminated();
    verify(transportListener, never()).transportReady(any(Attributes.class));
  }

  @Test
  public void shutdownNowDuringHandshake() throws Exception {
    doAnswer(invocation -> {
      ((Socket) invocation.getArguments()[0]).getInputStream().read();
      throw new IOException("handshake purposefully failed");
    }).when(handshakerSocketFactory).handshake(any(Socket.class), any(Attributes.class));
    serverBuilder.transportExecutor(threadPool);
    initTransport();
    serverTransport.shutdownNow(Status.UNAVAILABLE.withDescription("shutdown now"));

    verify(transportListener, timeout(TIME_OUT_MS)).transportTerminated();
    verify(transportListener, never()).transportReady(any(Attributes.class));
  }

  @Test
  public void clientCloseDuringHandshake() throws Exception {
    doAnswer(invocation -> {
      ((Socket) invocation.getArguments()[0]).getInputStream().read();
      throw new IOException("handshake purposefully failed");
    }).when(handshakerSocketFactory).handshake(any(Socket.class), any(Attributes.class));
    serverBuilder.transportExecutor(threadPool);
    initTransport();
    socketPair.client.close();

    verify(transportListener, timeout(TIME_OUT_MS)).transportTerminated();
    verify(transportListener, never()).transportReady(any(Attributes.class));
  }

  @Test
  public void closeDuringHttp2Preface() throws Exception {
    initTransport();
    socketPair.client.close();

    verify(transportListener, timeout(TIME_OUT_MS)).transportTerminated();
    verify(transportListener, never()).transportReady(any(Attributes.class));
  }

  @Test
  public void noSettingsDuringHttp2HandshakeSettings() throws Exception {
    initTransport();
    clientFrameWriter.connectionPreface();
    clientFrameWriter.flush();
    socketPair.client.close();

    verify(transportListener, timeout(TIME_OUT_MS)).transportTerminated();
    verify(transportListener, never()).transportReady(any(Attributes.class));
  }

  @Test
  public void noSettingsDuringHttp2Handshake() throws Exception {
    initTransport();
    clientFrameWriter.connectionPreface();
    clientFrameWriter.ping(false, 0, 0x1234);
    clientFrameWriter.flush();

    verify(transportListener, timeout(TIME_OUT_MS)).transportTerminated();
    verify(transportListener, never()).transportReady(any(Attributes.class));
  }

  @Test
  public void startThenClientDisconnect() throws Exception {
    initTransport();
    handshake();

    socketPair.client.close();
    verify(transportListener, timeout(TIME_OUT_MS)).transportTerminated();
  }

  @Test
  public void basicRpc_succeeds() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        TE_HEADER,
        new Header("some-metadata", "this could be anything")));
    Buffer requestMessageFrame = createMessageFrame("Hello server");
    clientFrameWriter.data(true, 1, requestMessageFrame, (int) requestMessageFrame.size());
    pingPong();

    MockStreamListener streamListener = mockTransportListener.newStreams.pop();
    assertThat(streamListener.stream.getAuthority()).isEqualTo("example.com:80");
    assertThat(streamListener.method).isEqualTo("com.example/SimpleService.doit");
    assertThat(streamListener.headers.get(
          Metadata.Key.of("Some-Metadata", Metadata.ASCII_STRING_MARSHALLER)))
        .isEqualTo("this could be anything");
    streamListener.stream.request(1);
    pingPong();
    assertThat(streamListener.messages.pop()).isEqualTo("Hello server");
    assertThat(streamListener.halfClosedCalled).isTrue();

    streamListener.stream.writeHeaders(metadata("User-Data", "best data"));
    streamListener.stream.writeMessage(new ByteArrayInputStream("Howdy client".getBytes(UTF_8)));
    streamListener.stream.close(Status.OK, metadata("End-Metadata", "bye"));

    List<Header> responseHeaders = Arrays.asList(
        new Header(":status", "200"),
        CONTENT_TYPE_HEADER,
        new Header("user-data", "best data"));
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead)
        .headers(false, false, 1, -1, responseHeaders, HeadersMode.HTTP_20_HEADERS);

    Buffer responseMessageFrame = createMessageFrame("Howdy client");
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead)
        .data(eq(false), eq(1), any(BufferedSource.class), eq((int) responseMessageFrame.size()));
    verify(clientDataFrames).data(false, 1, responseMessageFrame);

    List<Header> responseTrailers = Arrays.asList(
        new Header("end-metadata", "bye"),
        new Header("grpc-status", "0"));
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead)
        .headers(false, true, 1, -1, responseTrailers, HeadersMode.HTTP_20_HEADERS);

    SocketStats stats = serverTransport.getStats().get();
    assertThat(stats.data.streamsStarted).isEqualTo(1);
    assertThat(stats.data.streamsSucceeded).isEqualTo(1);
    assertThat(stats.data.streamsFailed).isEqualTo(0);
    assertThat(stats.data.messagesSent).isEqualTo(1);
    assertThat(stats.data.messagesReceived).isEqualTo(1);

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void activeRpc_delaysShutdownTermination() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        TE_HEADER,
        new Header("some-metadata", "this could be anything")));
    Buffer requestMessageFrame = createMessageFrame("Hello server");
    clientFrameWriter.data(true, 1, requestMessageFrame, (int) requestMessageFrame.size());
    pingPong();

    serverTransport.shutdown();
    verifyGracefulShutdown(1);
    verify(transportListener, never()).transportTerminated();

    MockStreamListener streamListener = mockTransportListener.newStreams.pop();
    streamListener.stream.request(1);
    pingPong();
    assertThat(streamListener.messages.pop()).isEqualTo("Hello server");
    assertThat(streamListener.halfClosedCalled).isTrue();

    streamListener.stream.writeHeaders(new Metadata());
    streamListener.stream.writeMessage(new ByteArrayInputStream("Howdy client".getBytes(UTF_8)));
    streamListener.stream.flush();

    List<Header> responseHeaders = Arrays.asList(
        new Header(":status", "200"),
        CONTENT_TYPE_HEADER);
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead)
        .headers(false, false, 1, -1, responseHeaders, HeadersMode.HTTP_20_HEADERS);

    Buffer responseMessageFrame = createMessageFrame("Howdy client");
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead)
        .data(eq(false), eq(1), any(BufferedSource.class), eq((int) responseMessageFrame.size()));
    verify(clientDataFrames).data(false, 1, responseMessageFrame);
    pingPong();
    assertThat(serverTransport.getActiveStreams().length).isEqualTo(1);
    verify(transportListener, never()).transportTerminated();

    streamListener.stream.close(Status.OK, new Metadata());
    List<Header> responseTrailers = Arrays.asList(
        new Header("grpc-status", "0"));
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead)
        .headers(false, true, 1, -1, responseTrailers, HeadersMode.HTTP_20_HEADERS);

    assertThat(serverTransport.getActiveStreams().length).isEqualTo(0);
    verify(transportListener, timeout(TIME_OUT_MS)).transportTerminated();
  }

  @Test
  public void headersForStream0_failsWithGoAway() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(0, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        TE_HEADER));
    clientFrameWriter.flush();

    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).goAway(
        0, ErrorCode.INTERNAL_ERROR,
        ByteString.encodeUtf8("Error in frame decoder"));
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isFalse();
    verify(transportListener, timeout(TIME_OUT_MS)).transportTerminated();
  }

  @Test
  public void headersForEvenStream_failsWithGoAway() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(2, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        TE_HEADER));
    clientFrameWriter.flush();

    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).goAway(
        0, ErrorCode.PROTOCOL_ERROR,
        ByteString.encodeUtf8("Clients cannot open even numbered streams. RFC7540 section 5.1.1"));
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isFalse();
    verify(transportListener, timeout(TIME_OUT_MS)).transportTerminated();
  }

  @Test
  public void headersTooLarge_failsWith431() throws Exception {
    initTransport();
    handshake();

    StringBuilder largeString = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      largeString.append(
          "Row, row, row your boat, gently down the stream. Merrily, merrily, merrily, merrily, "
          + "life is but a dream. ");
    }
    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        TE_HEADER,
        new Header("too-large", largeString.toString())));
    clientFrameWriter.flush();

    verifyHttpError(
        1, 431, Status.Code.RESOURCE_EXHAUSTED, "Request metadata larger than 8192: 10953");

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void invalidPseudoHeader_failsWithRst() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        new Header(":status", "999"), // Invalid for requests
        CONTENT_TYPE_HEADER,
        TE_HEADER));
    clientFrameWriter.flush();

    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).rstStream(1, ErrorCode.PROTOCOL_ERROR);

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void multipleAuthorityHeaders_failsWithRst() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_AUTHORITY, "example.com:8080"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        TE_HEADER));
    clientFrameWriter.flush();

    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).rstStream(1, ErrorCode.PROTOCOL_ERROR);

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void pseudoHeaderAfterRegularHeader_failsWithRst() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        CONTENT_TYPE_HEADER,
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        TE_HEADER));
    clientFrameWriter.flush();

    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).rstStream(1, ErrorCode.PROTOCOL_ERROR);

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void missingSchemeHeader_failsWithRst() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        TE_HEADER));
    clientFrameWriter.flush();

    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).rstStream(1, ErrorCode.PROTOCOL_ERROR);

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void connectionHeader_failsWithRst() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        new Header("connection", "content-type"),
        TE_HEADER));
    clientFrameWriter.flush();

    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).rstStream(1, ErrorCode.PROTOCOL_ERROR);

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void trailersAfterEndStream_failsWithRst() throws Exception {
    initTransport();
    handshake();

    List<Header> headers = Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        TE_HEADER);
    clientFrameWriter.synStream(true, false, 1, -1, headers);
    clientFrameWriter.synStream(true, false, 1, -1, headers);
    clientFrameWriter.flush();

    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).rstStream(1, ErrorCode.STREAM_CLOSED);
    pingPong();

    MockStreamListener streamListener = mockTransportListener.newStreams.pop();
    assertThat(streamListener.status).isNotNull();
    assertThat(streamListener.status.getCode()).isNotEqualTo(Status.Code.OK);

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void trailers_endStream() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        TE_HEADER));
    clientFrameWriter.synStream(true, false, 1, -1, Arrays.asList(
        new Header("some-client-sent-trailer", "trailer-value")));
    pingPong();

    MockStreamListener streamListener = mockTransportListener.newStreams.pop();
    assertThat(streamListener.messages.peek()).isNull();
    assertThat(streamListener.halfClosedCalled).isTrue();

    streamListener.stream.close(Status.OK, new Metadata());

    List<Header> responseTrailers = Arrays.asList(
        new Header(":status", "200"),
        CONTENT_TYPE_HEADER,
        new Header("grpc-status", "0"));
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead)
        .headers(false, true, 1, -1, responseTrailers, HeadersMode.HTTP_20_HEADERS);

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void headersInMiddleOfRequest_failsWithRst() throws Exception {
    initTransport();
    handshake();

    List<Header> headers = Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        TE_HEADER);
    clientFrameWriter.headers(1, headers);
    clientFrameWriter.headers(1, headers);
    clientFrameWriter.flush();

    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).rstStream(1, ErrorCode.PROTOCOL_ERROR);
    pingPong();

    MockStreamListener streamListener = mockTransportListener.newStreams.pop();
    assertThat(streamListener.status).isNotNull();
    assertThat(streamListener.status.getCode()).isNotEqualTo(Status.Code.OK);

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void multipleHostHeaders_failsWith400() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        TE_HEADER,
        new Header("host", "example.com:80"),
        new Header("host", "example.com:80")));
    clientFrameWriter.flush();

    verifyHttpError(
        1, 400, Status.Code.INTERNAL, "Multiple host headers disallowed. RFC7230 section 5.4");

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void hostWithoutAuthority_usesHost() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        new Header("host", "example.com:80"),
        CONTENT_TYPE_HEADER,
        TE_HEADER));
    clientFrameWriter.rstStream(1, ErrorCode.CANCEL);
    pingPong();

    MockStreamListener streamListener = mockTransportListener.newStreams.pop();
    assertThat(streamListener.stream.getAuthority()).isEqualTo("example.com:80");

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void authorityAndHost_usesAuthority() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        new Header("host", "example2.com:8080"),
        CONTENT_TYPE_HEADER,
        TE_HEADER));
    clientFrameWriter.rstStream(1, ErrorCode.CANCEL);
    pingPong();

    MockStreamListener streamListener = mockTransportListener.newStreams.pop();
    assertThat(streamListener.stream.getAuthority()).isEqualTo("example.com:80");

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void missingAuthorityAndHost_hasNullAuthority() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        TE_HEADER));
    clientFrameWriter.rstStream(1, ErrorCode.CANCEL);
    pingPong();

    MockStreamListener streamListener = mockTransportListener.newStreams.pop();
    assertThat(streamListener.stream.getAuthority()).isNull();

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void emptyPath_failsWith404() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, ""),
        CONTENT_TYPE_HEADER,
        TE_HEADER));
    clientFrameWriter.flush();

    verifyHttpError(1, 404, Status.Code.UNIMPLEMENTED, "Expected path to start with /: ");

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void nonAbsolutePath_failsWith404() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "https://example.com/"),
        CONTENT_TYPE_HEADER,
        TE_HEADER));
    clientFrameWriter.flush();

    verifyHttpError(
        1, 404, Status.Code.UNIMPLEMENTED, "Expected path to start with /: https://example.com/");

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void missingContentType_failsWith415() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        TE_HEADER));
    clientFrameWriter.flush();

    verifyHttpError(1, 415, Status.Code.INTERNAL, "Content-Type is missing or duplicated");

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void repeatedContentType_failsWith415() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        CONTENT_TYPE_HEADER,
        TE_HEADER));
    clientFrameWriter.flush();

    verifyHttpError(1, 415, Status.Code.INTERNAL, "Content-Type is missing or duplicated");

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void textContentType_failsWith415() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        new Header("content-type", "text/plain"),
        TE_HEADER));
    clientFrameWriter.flush();

    verifyHttpError(1, 415, Status.Code.INTERNAL, "Content-Type is not supported: text/plain");

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void httpGet_failsWith405() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        new Header(":method", "GET"),
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        TE_HEADER));
    clientFrameWriter.flush();

    verifyHttpError(1, 405, Status.Code.INTERNAL, "HTTP Method is not supported: GET");

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void missingTeTrailers_failsWithInternal() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER));
    clientFrameWriter.flush();

    List<Header> responseHeaders = Arrays.asList(
        new Header(":status", "200"),
        new Header("content-type", "application/grpc"),
        new Header("grpc-status", "" + Status.Code.INTERNAL.value()),
        new Header("grpc-message", "Expected header TE: trailers, but <missing> is received. "
          + "Some intermediate proxy may not support trailers"));
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead)
        .headers(false, true, 1, -1, responseHeaders, HeadersMode.HTTP_20_HEADERS);

    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).rstStream(1, ErrorCode.NO_ERROR);

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void httpErrorsAdhereToFlowControl() throws Exception {
    Settings settings = new Settings();
    OkHttpSettingsUtil.set(settings, OkHttpSettingsUtil.INITIAL_WINDOW_SIZE, 1);

    initTransport();
    handshake(settings);

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        new Header(":method", "GET"), // Invalid
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        TE_HEADER));
    clientFrameWriter.flush();

    String errorDescription = "HTTP Method is not supported: GET";
    List<Header> responseHeaders = Arrays.asList(
        new Header(":status", "405"),
        new Header("content-type", "text/plain; charset=utf-8"),
        new Header("grpc-status", "" + Status.Code.INTERNAL.value()),
        new Header("grpc-message", errorDescription));
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead)
        .headers(false, false, 1, -1, responseHeaders, HeadersMode.HTTP_20_HEADERS);

    Buffer responseDataFrame = new Buffer().writeUtf8(errorDescription.substring(0, 1));
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).data(
        eq(false), eq(1), any(BufferedSource.class), eq((int) responseDataFrame.size()));
    verify(clientDataFrames).data(false, 1, responseDataFrame);

    clientFrameWriter.windowUpdate(1, 1000);
    clientFrameWriter.flush();

    responseDataFrame = new Buffer().writeUtf8(errorDescription.substring(1));
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).data(
        eq(true), eq(1), any(BufferedSource.class), eq((int) responseDataFrame.size()));
    verify(clientDataFrames).data(true, 1, responseDataFrame);

    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).rstStream(1, ErrorCode.NO_ERROR);

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void dataForStream0_failsWithGoAway() throws Exception {
    initTransport();
    handshake();

    Buffer requestMessageFrame = createMessageFrame("Nope");
    clientFrameWriter.data(true, 0, requestMessageFrame, (int) requestMessageFrame.size());
    clientFrameWriter.flush();

    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).goAway(
        0, ErrorCode.PROTOCOL_ERROR,
        ByteString.encodeUtf8("Stream 0 is reserved for control messages. RFC7540 section 5.1.1"));
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isFalse();
    verify(transportListener, timeout(TIME_OUT_MS)).transportTerminated();
  }

  @Test
  public void dataForEvenStream_failsWithGoAway() throws Exception {
    initTransport();
    handshake();

    Buffer requestMessageFrame = createMessageFrame("Nope");
    clientFrameWriter.data(true, 2, requestMessageFrame, (int) requestMessageFrame.size());
    clientFrameWriter.flush();

    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).goAway(
        0, ErrorCode.PROTOCOL_ERROR,
        ByteString.encodeUtf8("Clients cannot open even numbered streams. RFC7540 section 5.1.1"));
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isFalse();
    verify(transportListener, timeout(TIME_OUT_MS)).transportTerminated();
  }

  @Test
  public void dataAfterHalfClose_failsWithRst() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        TE_HEADER,
        new Header("some-metadata", "this could be anything")));
    Buffer requestMessageFrame = createMessageFrame("Hello server");
    clientFrameWriter.data(true, 1, requestMessageFrame, (int) requestMessageFrame.size());
    requestMessageFrame = createMessageFrame("oh, I forgot");
    clientFrameWriter.data(true, 1, requestMessageFrame, (int) requestMessageFrame.size());
    clientFrameWriter.flush();

    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).rstStream(1, ErrorCode.STREAM_CLOSED);
    MockStreamListener streamListener = mockTransportListener.newStreams.pop();
    pingPong();
    assertThat(streamListener.status).isNotNull();
    assertThat(streamListener.status.getCode()).isNotEqualTo(Status.Code.OK);

    shutdownAndTerminate(/*lastStreamId=*/ 1);
  }

  @Test
  public void pushPromise_failsWithGoAway() throws Exception {
    initTransport();
    handshake();

    clientFrameWriter.pushPromise(2, 3, Arrays.asList());
    clientFrameWriter.flush();

    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).goAway(
        0, ErrorCode.PROTOCOL_ERROR,
        ByteString.encodeUtf8(
            "PUSH_PROMISE only allowed on peer-initiated streams. RFC7540 section 6.6"));
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isFalse();
    verify(transportListener, timeout(TIME_OUT_MS)).transportTerminated();
  }

  @Test
  public void channelzStats() throws Exception {
    serverBuilder.flowControlWindow(60000);
    initTransport();
    handshake();
    clientFrameWriter.windowUpdate(0, 1000); // connection stream id
    pingPong();

    SocketStats stats = serverTransport.getStats().get();
    assertThat(stats.data.streamsStarted).isEqualTo(0);
    assertThat(stats.data.streamsSucceeded).isEqualTo(0);
    assertThat(stats.data.streamsFailed).isEqualTo(0);
    assertThat(stats.data.messagesSent).isEqualTo(0);
    assertThat(stats.data.messagesReceived).isEqualTo(0);
    assertThat(stats.data.remoteFlowControlWindow).isEqualTo(30000); // Lower bound
    assertThat(stats.data.localFlowControlWindow).isEqualTo(66535);
    assertThat(stats.local).isEqualTo(socketPair.server.getLocalSocketAddress());
    assertThat(stats.remote).isEqualTo(socketPair.server.getRemoteSocketAddress());
  }

  @Test
  public void keepAliveEnforcer_enforcesPings() throws Exception {
    serverBuilder.permitKeepAliveTime(1, TimeUnit.HOURS)
            .permitKeepAliveWithoutCalls(false);
    initTransport();
    handshake();

    for (int i = 0; i < KeepAliveEnforcer.MAX_PING_STRIKES; i++) {
      pingPong();
    }
    pingPongId++;
    clientFrameWriter.ping(false, pingPongId, 0);
    clientFrameWriter.flush();
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).goAway(0, ErrorCode.ENHANCE_YOUR_CALM,
        ByteString.encodeString("too_many_pings", GrpcUtil.US_ASCII));
  }

  @Test
  public void keepAliveEnforcer_sendingDataResetsCounters() throws Exception {
    serverBuilder.permitKeepAliveTime(1, TimeUnit.HOURS)
        .permitKeepAliveWithoutCalls(false);
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        TE_HEADER,
        new Header("some-metadata", "this could be anything")));
    Buffer requestMessageFrame = createMessageFrame("Hello server");
    clientFrameWriter.data(false, 1, requestMessageFrame, (int) requestMessageFrame.size());
    pingPong();
    MockStreamListener streamListener = mockTransportListener.newStreams.pop();

    streamListener.stream.request(1);
    pingPong();
    assertThat(streamListener.messages.pop()).isEqualTo("Hello server");

    streamListener.stream.writeHeaders(metadata("User-Data", "best data"));
    streamListener.stream.writeMessage(new ByteArrayInputStream("Howdy client".getBytes(UTF_8)));
    streamListener.stream.flush();
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();

    for (int i = 0; i < 10; i++) {
      assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
      pingPong();
      streamListener.stream.writeMessage(new ByteArrayInputStream("Howdy client".getBytes(UTF_8)));
      streamListener.stream.flush();
    }
  }

  @Test
  public void keepAliveEnforcer_initialIdle() throws Exception {
    serverBuilder.permitKeepAliveTime(0, TimeUnit.SECONDS)
        .permitKeepAliveWithoutCalls(false);
    initTransport();
    handshake();

    for (int i = 0; i < KeepAliveEnforcer.MAX_PING_STRIKES; i++) {
      pingPong();
    }
    pingPongId++;
    clientFrameWriter.ping(false, pingPongId, 0);
    clientFrameWriter.flush();
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).goAway(0, ErrorCode.ENHANCE_YOUR_CALM,
        ByteString.encodeString("too_many_pings", GrpcUtil.US_ASCII));
  }

  @Test
  public void keepAliveEnforcer_noticesActive() throws Exception {
    serverBuilder.permitKeepAliveTime(0, TimeUnit.SECONDS)
        .permitKeepAliveWithoutCalls(false);
    initTransport();
    handshake();

    clientFrameWriter.headers(1, Arrays.asList(
        HTTP_SCHEME_HEADER,
        METHOD_HEADER,
        new Header(Header.TARGET_AUTHORITY, "example.com:80"),
        new Header(Header.TARGET_PATH, "/com.example/SimpleService.doit"),
        CONTENT_TYPE_HEADER,
        TE_HEADER,
        new Header("some-metadata", "this could be anything")));
    for (int i = 0; i < 10; i++) {
      pingPong();
    }
    verify(clientFramesRead, never()).goAway(anyInt(), eq(ErrorCode.ENHANCE_YOUR_CALM),
        eq(ByteString.encodeString("too_many_pings", GrpcUtil.US_ASCII)));
  }

  private void initTransport() throws Exception {
    serverTransport = new OkHttpServerTransport(
        new OkHttpServerTransport.Config(serverBuilder, Arrays.asList()),
        socketPair.server);
    serverTransport.start(transportListener);
  }

  private void handshake() throws Exception {
    handshake(new Settings());
  }

  private void handshake(Settings settings) throws Exception {
    clientFrameWriter.connectionPreface();
    clientFrameWriter.settings(settings);
    clientFrameWriter.flush();
    clientFrameReader.readConnectionPreface();
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    ArgumentCaptor<Settings> settingsCaptor = ArgumentCaptor.forClass(Settings.class);
    verify(clientFramesRead).settings(eq(false), settingsCaptor.capture());
    clientFrameWriter.ackSettings(settingsCaptor.getValue());
    clientFrameWriter.flush();
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).ackSettings();
    verify(transportListener, timeout(TIME_OUT_MS)).transportReady(any(Attributes.class));
  }

  private static Buffer createMessageFrame(String stringMessage) {
    byte[] message = stringMessage.getBytes(UTF_8);
    Buffer buffer = new Buffer();
    buffer.writeByte(0 /* UNCOMPRESSED */);
    buffer.writeInt(message.length);
    buffer.write(message);
    return buffer;
  }

  private Metadata metadata(String... keysAndValues) {
    Metadata metadata = new Metadata();
    assertThat(keysAndValues.length % 2).isEqualTo(0);
    for (int i = 0; i < keysAndValues.length; i += 2) {
      metadata.put(
          Metadata.Key.of(keysAndValues[i], Metadata.ASCII_STRING_MARSHALLER),
          keysAndValues[i + 1]);
    }
    return metadata;
  }

  private void verifyGracefulShutdown(int lastStreamId)
      throws IOException {
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).goAway(2147483647, ErrorCode.NO_ERROR, ByteString.EMPTY);
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).ping(false, 0, 0x1111);
    clientFrameWriter.ping(true, 0, 0x1111);
    clientFrameWriter.flush();
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).goAway(lastStreamId, ErrorCode.NO_ERROR, ByteString.EMPTY);
  }

  private void shutdownAndTerminate(int lastStreamId) throws IOException {
    assertThat(serverTransport.getActiveStreams().length).isEqualTo(0);
    serverTransport.shutdown();
    verifyGracefulShutdown(lastStreamId);
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isFalse();
    verify(transportListener, timeout(TIME_OUT_MS)).transportTerminated();
  }

  private int pingPongId = 0;

  /** Send a ping and wait for the ping ack. */
  private void pingPong() throws IOException {
    pingPongId++;
    clientFrameWriter.ping(false, pingPongId, 0);
    clientFrameWriter.flush();
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).ping(true, pingPongId, 0);
  }

  private void verifyHttpError(
      int streamId, int httpCode, Status.Code grpcCode, String errorDescription) throws Exception {
    List<Header> responseHeaders = Arrays.asList(
        new Header(":status", "" + httpCode),
        new Header("content-type", "text/plain; charset=utf-8"),
        new Header("grpc-status", "" + grpcCode.value()),
        new Header("grpc-message", errorDescription));
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead)
        .headers(false, false, streamId, -1, responseHeaders, HeadersMode.HTTP_20_HEADERS);

    Buffer responseDataFrame = new Buffer().writeUtf8(errorDescription);
    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).data(
        eq(true), eq(streamId), any(BufferedSource.class), eq((int) responseDataFrame.size()));
    verify(clientDataFrames).data(true, streamId, responseDataFrame);

    assertThat(clientFrameReader.nextFrame(clientFramesRead)).isTrue();
    verify(clientFramesRead).rstStream(streamId, ErrorCode.NO_ERROR);
  }

  private static class MockServerTransportListener implements ServerTransportListener {
    Deque<MockStreamListener> newStreams = new ArrayDeque<>();

    @Override public void streamCreated(ServerStream stream, String method, Metadata headers) {
      MockStreamListener streamListener = new MockStreamListener(stream, method, headers);
      stream.setListener(streamListener);
      newStreams.add(streamListener);
    }

    @Override public Attributes transportReady(Attributes attributes) {
      return attributes;
    }

    @Override public void transportTerminated() {}
  }

  private static class MockStreamListener implements ServerStreamListener {
    final ServerStream stream;
    final String method;
    final Metadata headers;

    Deque<String> messages = new ArrayDeque<>();
    boolean halfClosedCalled;
    Status status;
    CountDownLatch closed = new CountDownLatch(1);

    MockStreamListener(ServerStream stream, String method, Metadata headers) {
      this.stream = stream;
      this.method = method;
      this.headers = headers;
    }

    @Override
    public void messagesAvailable(MessageProducer producer) {
      InputStream inputStream;
      while ((inputStream = producer.next()) != null) {
        try {
          String msg = getContent(inputStream);
          if (msg != null) {
            messages.add(msg);
          }
        } catch (IOException ex) {
          while ((inputStream = producer.next()) != null) {
            GrpcUtil.closeQuietly(inputStream);
          }
          throw new RuntimeException(ex);
        }
      }
    }

    @Override
    public void halfClosed() {
      halfClosedCalled = true;
    }

    @Override
    public void closed(Status status) {
      this.status = status;
      closed.countDown();
    }

    @Override
    public void onReady() {
    }

    static String getContent(InputStream message) throws IOException {
      try {
        return new String(ByteStreams.toByteArray(message), UTF_8);
      } finally {
        message.close();
      }
    }
  }

  private static class SocketPair {
    public final Socket client;
    public final Socket server;

    public SocketPair(Socket client, Socket server) {
      this.client = client;
      this.server = server;
    }

    public InputStream getClientInputStream() {
      try {
        return client.getInputStream();
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }

    public OutputStream getClientOutputStream() {
      try {
        return client.getOutputStream();
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }

    public static SocketPair create(ExecutorService threadPool) {
      try {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
          Future<Socket> serverFuture = threadPool.submit(() -> serverSocket.accept());
          Socket client = new Socket();
          client.connect(serverSocket.getLocalSocketAddress());
          Socket server = serverFuture.get();
          return new SocketPair(client, server);
        }
      } catch (Exception ex) {
        throw new RuntimeException(ex);
      }
    }
  }

  private interface DataFrameHandler {
    void data(boolean inFinished, int streamId, Buffer payload);
  }
}
