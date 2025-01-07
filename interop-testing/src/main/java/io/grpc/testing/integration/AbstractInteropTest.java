/*
 * Copyright 2014 The gRPC Authors
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

package io.grpc.testing.integration;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static io.grpc.stub.ClientCalls.blockingServerStreamingCall;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;
import com.google.protobuf.StringValue;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientStreamTracer;
import io.grpc.Context;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.census.InternalCensusStatsAccessor;
import io.grpc.census.internal.DeprecatedCensusConstants;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.testing.StatsTestUtils;
import io.grpc.internal.testing.StatsTestUtils.FakeStatsRecorder;
import io.grpc.internal.testing.StatsTestUtils.FakeTagContext;
import io.grpc.internal.testing.StatsTestUtils.FakeTagContextBinarySerializer;
import io.grpc.internal.testing.StatsTestUtils.FakeTagger;
import io.grpc.internal.testing.StatsTestUtils.MetricsRecord;
import io.grpc.internal.testing.StreamRecorder;
import io.grpc.internal.testing.TestClientStreamTracer;
import io.grpc.internal.testing.TestServerStreamTracer;
import io.grpc.internal.testing.TestStreamTracer;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.TestUtils;
import io.grpc.testing.integration.EmptyProtos.Empty;
import io.grpc.testing.integration.Messages.BoolValue;
import io.grpc.testing.integration.Messages.EchoStatus;
import io.grpc.testing.integration.Messages.Payload;
import io.grpc.testing.integration.Messages.ResponseParameters;
import io.grpc.testing.integration.Messages.SimpleRequest;
import io.grpc.testing.integration.Messages.SimpleResponse;
import io.grpc.testing.integration.Messages.StreamingInputCallRequest;
import io.grpc.testing.integration.Messages.StreamingInputCallResponse;
import io.grpc.testing.integration.Messages.StreamingOutputCallRequest;
import io.grpc.testing.integration.Messages.StreamingOutputCallResponse;
import io.opencensus.contrib.grpc.metrics.RpcMeasureConstants;
import io.opencensus.stats.Measure;
import io.opencensus.stats.Measure.MeasureDouble;
import io.opencensus.stats.Measure.MeasureLong;
import io.opencensus.tags.TagKey;
import io.opencensus.tags.TagValue;
import io.opencensus.trace.Span;
import io.opencensus.trace.SpanContext;
import io.opencensus.trace.Tracing;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import org.HdrHistogram.Histogram;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

/**
 * Abstract base class for all GRPC transport tests.
 *
 * <p>New tests should avoid using Mockito to support running on AppEngine.
 */
public abstract class AbstractInteropTest {
  private static Logger logger = Logger.getLogger(AbstractInteropTest.class.getName());

  @Rule public final TestRule globalTimeout;

  /** Must be at least {@link #unaryPayloadLength()}, plus some to account for encoding overhead. */
  public static final int MAX_MESSAGE_SIZE = 16 * 1024 * 1024;

  /**
   * Use a small flow control to help detect flow control bugs. Don't use 64KiB to test
   * SETTINGS/WINDOW_UPDATE exchange.
   */
  public static final int TEST_FLOW_CONTROL_WINDOW = 65 * 1024;
  private static final MeasureLong RETRIES_PER_CALL =
      Measure.MeasureLong.create(
          "grpc.io/client/retries_per_call", "Number of retries per call", "1");
  private static final MeasureLong TRANSPARENT_RETRIES_PER_CALL =
      Measure.MeasureLong.create(
          "grpc.io/client/transparent_retries_per_call", "Transparent retries per call", "1");
  private static final MeasureDouble RETRY_DELAY_PER_CALL =
      Measure.MeasureDouble.create(
          "grpc.io/client/retry_delay_per_call", "Retry delay per call", "ms");

  private static final FakeTagger tagger = new FakeTagger();
  private static final FakeTagContextBinarySerializer tagContextBinarySerializer =
      new FakeTagContextBinarySerializer();

  private final AtomicReference<ServerCall<?, ?>> serverCallCapture =
      new AtomicReference<>();
  private final AtomicReference<ClientCall<?, ?>> clientCallCapture =
      new AtomicReference<>();
  private final AtomicReference<Metadata> requestHeadersCapture =
      new AtomicReference<>();
  private final AtomicReference<Context> contextCapture =
      new AtomicReference<>();
  private final FakeStatsRecorder clientStatsRecorder = new FakeStatsRecorder();
  private final FakeStatsRecorder serverStatsRecorder = new FakeStatsRecorder();

  private ScheduledExecutorService testServiceExecutor;
  private Server server;
  private Server handshakerServer;

  private final LinkedBlockingQueue<ServerStreamTracerInfo> serverStreamTracers =
      new LinkedBlockingQueue<>();

  private static final class ServerStreamTracerInfo {
    final String fullMethodName;
    final InteropServerStreamTracer tracer;

    ServerStreamTracerInfo(String fullMethodName, InteropServerStreamTracer tracer) {
      this.fullMethodName = fullMethodName;
      this.tracer = tracer;
    }

    private static final class InteropServerStreamTracer extends TestServerStreamTracer {
      private volatile Context contextCapture;

      @Override
      public Context filterContext(Context context) {
        contextCapture = context;
        return super.filterContext(context);
      }
    }
  }

  /**
   * Constructor for tests.
   */
  protected AbstractInteropTest() {
    TestRule timeout = Timeout.seconds(90);
    try {
      timeout = new DisableOnDebug(timeout);
    } catch (Throwable t) {
      // This can happen on Android, which lacks some standard Java class.
      // Seen at https://github.com/grpc/grpc-java/pull/5832#issuecomment-499698086
      logger.log(Level.FINE, "Debugging not disabled.", t);
    }
    globalTimeout = timeout;
  }

  private final ServerStreamTracer.Factory serverStreamTracerFactory =
      new ServerStreamTracer.Factory() {
        @Override
        public ServerStreamTracer newServerStreamTracer(String fullMethodName, Metadata headers) {
          ServerStreamTracerInfo.InteropServerStreamTracer tracer
              = new ServerStreamTracerInfo.InteropServerStreamTracer();
          serverStreamTracers.add(new ServerStreamTracerInfo(fullMethodName, tracer));
          return tracer;
        }
      };

  protected static final Empty EMPTY = Empty.getDefaultInstance();

  private void configBuilder(@Nullable ServerBuilder<?> builder) {
    if (builder == null) {
      return;
    }
    testServiceExecutor = Executors.newScheduledThreadPool(2);

    List<ServerInterceptor> allInterceptors = ImmutableList.<ServerInterceptor>builder()
        .add(recordServerCallInterceptor(serverCallCapture))
        .add(TestUtils.recordRequestHeadersInterceptor(requestHeadersCapture))
        .add(recordContextInterceptor(contextCapture))
        .addAll(TestServiceImpl.interceptors())
        .build();

    builder
        .addService(
            ServerInterceptors.intercept(
                new TestServiceImpl(testServiceExecutor),
                allInterceptors))
        .addStreamTracerFactory(serverStreamTracerFactory);
  }

  protected void startServer(@Nullable ServerBuilder<?> builder) {
    maybeStartHandshakerServer();
    if (builder == null) {
      server = null;
      return;
    }

    try {
      server = builder.build().start();
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  private void maybeStartHandshakerServer() {
    ServerBuilder<?> handshakerServerBuilder = getHandshakerServerBuilder();
    if (handshakerServerBuilder != null) {
      try {
        handshakerServer = handshakerServerBuilder.build().start();
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }
  }

  private void stopServer() {
    if (server != null) {
      server.shutdownNow();
    }
    if (testServiceExecutor != null) {
      testServiceExecutor.shutdown();
    }
    if (handshakerServer != null) {
      handshakerServer.shutdownNow();
    }
  }

  @VisibleForTesting
  final SocketAddress getListenAddress() {
    return server.getListenSockets().iterator().next();
  }

  protected ManagedChannel channel;
  protected TestServiceGrpc.TestServiceBlockingStub blockingStub;
  protected TestServiceGrpc.TestServiceStub asyncStub;

  private final LinkedBlockingQueue<TestClientStreamTracer> clientStreamTracers =
      new LinkedBlockingQueue<>();
  private boolean enableClientStreamTracers = true;

  void setEnableClientStreamTracers(boolean enableClientStreamTracers) {
    this.enableClientStreamTracers = enableClientStreamTracers;
  }

  private final ClientStreamTracer.Factory clientStreamTracerFactory =
      new ClientStreamTracer.Factory() {
        @Override
        public ClientStreamTracer newClientStreamTracer(
            ClientStreamTracer.StreamInfo info, Metadata headers) {
          TestClientStreamTracer tracer = new TestClientStreamTracer();
          clientStreamTracers.add(tracer);
          return tracer;
        }
      };
  private final ClientInterceptor tracerSetupInterceptor = new ClientInterceptor() {
        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
          return next.newCall(
              method, callOptions.withStreamTracerFactory(clientStreamTracerFactory));
        }
      };

  /**
   * Must be called by the subclass setup method if overridden.
   */
  @Before
  public void setUp() {
    ServerBuilder<?> serverBuilder = getServerBuilder();
    configBuilder(serverBuilder);
    startServer(serverBuilder);
    channel = createChannel();

    if (enableClientStreamTracers) {
      blockingStub =
          TestServiceGrpc.newBlockingStub(channel).withInterceptors(tracerSetupInterceptor);
      asyncStub = TestServiceGrpc.newStub(channel).withInterceptors(tracerSetupInterceptor);
    } else {
      blockingStub = TestServiceGrpc.newBlockingStub(channel);
      asyncStub = TestServiceGrpc.newStub(channel);
    }

    ClientInterceptor[] additionalInterceptors = getAdditionalInterceptors();
    if (additionalInterceptors != null) {
      blockingStub = blockingStub.withInterceptors(additionalInterceptors);
      asyncStub = asyncStub.withInterceptors(additionalInterceptors);
    }

    requestHeadersCapture.set(null);
  }

  /** Clean up. */
  @After
  public void tearDown() {
    if (channel != null) {
      channel.shutdownNow();
      try {
        channel.awaitTermination(1, TimeUnit.SECONDS);
      } catch (InterruptedException ie) {
        logger.log(Level.FINE, "Interrupted while waiting for channel termination", ie);
        // Best effort. If there is an interruption, we want to continue cleaning up, but quickly
        Thread.currentThread().interrupt();
      }
    }
    stopServer();
  }

  protected ManagedChannel createChannel() {
    return createChannelBuilder().build();
  }

  protected abstract ManagedChannelBuilder<?> createChannelBuilder();

  @Nullable
  protected ClientInterceptor[] getAdditionalInterceptors() {
    return null;
  }

  /**
   * Returns the server builder used to create server for each test run.  Return {@code null} if
   * it shouldn't start a server in the same process.
   */
  @Nullable
  protected ServerBuilder<?> getServerBuilder() {
    return null;
  }

  @Nullable
  protected ServerBuilder<?> getHandshakerServerBuilder() {
    return null;
  }

  protected final ClientInterceptor createCensusStatsClientInterceptor() {
    return
        InternalCensusStatsAccessor
            .getClientInterceptor(
                tagger, tagContextBinarySerializer, clientStatsRecorder,
                GrpcUtil.STOPWATCH_SUPPLIER,
                true, true, true,
                /* recordRealTimeMetrics= */ false,
                /* recordRetryMetrics= */ true);
  }

  protected final ServerStreamTracer.Factory createCustomCensusTracerFactory() {
    return InternalCensusStatsAccessor.getServerStreamTracerFactory(
        tagger, tagContextBinarySerializer, serverStatsRecorder,
        GrpcUtil.STOPWATCH_SUPPLIER,
        true, true, true, false /* real-time metrics */);
  }

  /**
   * Override this when custom census module presence is different from {@link #metricsExpected()}.
   */
  protected boolean customCensusModulePresent() {
    return metricsExpected();
  }

  /**
   * Return true if exact metric values should be checked.
   */
  protected boolean metricsExpected() {
    return true;
  }

  @Test
  public void emptyUnary() throws Exception {
    assertEquals(EMPTY, blockingStub.emptyCall(EMPTY));
  }

  @Test
  public void emptyUnaryWithRetriableStream() throws Exception {
    channel.shutdown();
    channel = createChannelBuilder().enableRetry().build();
    assertEquals(EMPTY, TestServiceGrpc.newBlockingStub(channel).emptyCall(EMPTY));
  }

  @Test
  public void largeUnary() throws Exception {
    assumeEnoughMemory();
    final SimpleRequest request = SimpleRequest.newBuilder()
        .setResponseSize(314159)
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[271828])))
        .build();
    final SimpleResponse goldenResponse = SimpleResponse.newBuilder()
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[314159])))
        .build();

    assertResponse(goldenResponse, blockingStub.unaryCall(request));

    assertStatsTrace("grpc.testing.TestService/UnaryCall", Status.Code.OK,
        Collections.singleton(request), Collections.singleton(goldenResponse));
  }

  /**
   * Tests client per-message compression for unary calls. The Java API does not support inspecting
   * a message's compression level, so this is primarily intended to run against a gRPC C++ server.
   */
  public void clientCompressedUnary(boolean probe) throws Exception {
    assumeEnoughMemory();
    final SimpleRequest expectCompressedRequest =
        SimpleRequest.newBuilder()
            .setExpectCompressed(BoolValue.newBuilder().setValue(true))
            .setResponseSize(314159)
            .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[271828])))
            .build();
    final SimpleRequest expectUncompressedRequest =
        SimpleRequest.newBuilder()
            .setExpectCompressed(BoolValue.newBuilder().setValue(false))
            .setResponseSize(314159)
            .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[271828])))
            .build();
    final SimpleResponse goldenResponse =
        SimpleResponse.newBuilder()
            .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[314159])))
            .build();

    if (probe) {
      // Send a non-compressed message with expectCompress=true. Servers supporting this test case
      // should return INVALID_ARGUMENT.
      try {
        blockingStub.unaryCall(expectCompressedRequest);
        fail("expected INVALID_ARGUMENT");
      } catch (StatusRuntimeException e) {
        assertEquals(Status.INVALID_ARGUMENT.getCode(), e.getStatus().getCode());
      }
      assertStatsTrace("grpc.testing.TestService/UnaryCall", Status.Code.INVALID_ARGUMENT);
    }

    assertResponse(
        goldenResponse, blockingStub.withCompression("gzip").unaryCall(expectCompressedRequest));
    assertStatsTrace(
        "grpc.testing.TestService/UnaryCall",
        Status.Code.OK,
        Collections.singleton(expectCompressedRequest),
        Collections.singleton(goldenResponse));

    assertResponse(goldenResponse, blockingStub.unaryCall(expectUncompressedRequest));
    assertStatsTrace(
        "grpc.testing.TestService/UnaryCall",
        Status.Code.OK,
        Collections.singleton(expectUncompressedRequest),
        Collections.singleton(goldenResponse));
  }

  /**
   * Tests if the server can send a compressed unary response. Ideally we would assert that the
   * responses have the requested compression, but this is not supported by the API. Given a
   * compliant server, this test will exercise the code path for receiving a compressed response but
   * cannot itself verify that the response was compressed.
   */
  @Test
  public void serverCompressedUnary() throws Exception {
    assumeEnoughMemory();
    final SimpleRequest responseShouldBeCompressed =
        SimpleRequest.newBuilder()
            .setResponseCompressed(BoolValue.newBuilder().setValue(true))
            .setResponseSize(314159)
            .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[271828])))
            .build();
    final SimpleRequest responseShouldBeUncompressed =
        SimpleRequest.newBuilder()
            .setResponseCompressed(BoolValue.newBuilder().setValue(false))
            .setResponseSize(314159)
            .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[271828])))
            .build();
    final SimpleResponse goldenResponse =
        SimpleResponse.newBuilder()
            .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[314159])))
            .build();

    assertResponse(goldenResponse, blockingStub.unaryCall(responseShouldBeCompressed));
    assertStatsTrace(
        "grpc.testing.TestService/UnaryCall",
        Status.Code.OK,
        Collections.singleton(responseShouldBeCompressed),
        Collections.singleton(goldenResponse));

    assertResponse(goldenResponse, blockingStub.unaryCall(responseShouldBeUncompressed));
    assertStatsTrace(
        "grpc.testing.TestService/UnaryCall",
        Status.Code.OK,
        Collections.singleton(responseShouldBeUncompressed),
        Collections.singleton(goldenResponse));
  }

  @Test
  public void serverStreaming() throws Exception {
    final StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
        .addResponseParameters(ResponseParameters.newBuilder()
            .setSize(31415))
        .addResponseParameters(ResponseParameters.newBuilder()
            .setSize(9))
        .addResponseParameters(ResponseParameters.newBuilder()
            .setSize(2653))
        .addResponseParameters(ResponseParameters.newBuilder()
            .setSize(58979))
        .build();
    final List<StreamingOutputCallResponse> goldenResponses = Arrays.asList(
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[31415])))
            .build(),
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[9])))
            .build(),
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[2653])))
            .build(),
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[58979])))
            .build());

    StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
    asyncStub.streamingOutputCall(request, recorder);
    recorder.awaitCompletion();
    assertSuccess(recorder);
    assertResponses(goldenResponses, recorder.getValues());
  }

  @Test
  public void clientStreaming() throws Exception {
    final List<StreamingInputCallRequest> requests = Arrays.asList(
        StreamingInputCallRequest.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[27182])))
            .build(),
        StreamingInputCallRequest.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[8])))
            .build(),
        StreamingInputCallRequest.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[1828])))
            .build(),
        StreamingInputCallRequest.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[45904])))
            .build());
    final StreamingInputCallResponse goldenResponse = StreamingInputCallResponse.newBuilder()
        .setAggregatedPayloadSize(74922)
        .build();

    StreamRecorder<StreamingInputCallResponse> responseObserver = StreamRecorder.create();
    StreamObserver<StreamingInputCallRequest> requestObserver =
        asyncStub.streamingInputCall(responseObserver);
    for (StreamingInputCallRequest request : requests) {
      requestObserver.onNext(request);
    }
    requestObserver.onCompleted();

    assertEquals(goldenResponse, responseObserver.firstValue().get());
    responseObserver.awaitCompletion();
    assertThat(responseObserver.getValues()).hasSize(1);
    Throwable t = responseObserver.getError();
    if (t != null) {
      throw new AssertionError(t);
    }
  }

  /**
   * Tests client per-message compression for streaming calls. The Java API does not support
   * inspecting a message's compression level, so this is primarily intended to run against a gRPC
   * C++ server.
   */
  public void clientCompressedStreaming(boolean probe) throws Exception {
    final StreamingInputCallRequest expectCompressedRequest =
        StreamingInputCallRequest.newBuilder()
            .setExpectCompressed(BoolValue.newBuilder().setValue(true))
            .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[27182])))
            .build();
    final StreamingInputCallRequest expectUncompressedRequest =
        StreamingInputCallRequest.newBuilder()
            .setExpectCompressed(BoolValue.newBuilder().setValue(false))
            .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[45904])))
            .build();
    final StreamingInputCallResponse goldenResponse =
        StreamingInputCallResponse.newBuilder().setAggregatedPayloadSize(73086).build();

    StreamRecorder<StreamingInputCallResponse> responseObserver = StreamRecorder.create();
    StreamObserver<StreamingInputCallRequest> requestObserver =
        asyncStub.streamingInputCall(responseObserver);

    if (probe) {
      // Send a non-compressed message with expectCompress=true. Servers supporting this test case
      // should return INVALID_ARGUMENT.
      requestObserver.onNext(expectCompressedRequest);
      responseObserver.awaitCompletion(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
      Throwable e = responseObserver.getError();
      assertNotNull("expected INVALID_ARGUMENT", e);
      assertEquals(Status.INVALID_ARGUMENT.getCode(), Status.fromThrowable(e).getCode());
    }

    // Start a new stream
    responseObserver = StreamRecorder.create();
    @SuppressWarnings("unchecked")
    ClientCallStreamObserver<StreamingInputCallRequest> clientCallStreamObserver =
        (ClientCallStreamObserver)
            asyncStub.withCompression("gzip").streamingInputCall(responseObserver);
    clientCallStreamObserver.setMessageCompression(true);
    clientCallStreamObserver.onNext(expectCompressedRequest);
    clientCallStreamObserver.setMessageCompression(false);
    clientCallStreamObserver.onNext(expectUncompressedRequest);
    clientCallStreamObserver.onCompleted();
    responseObserver.awaitCompletion();
    assertSuccess(responseObserver);
    assertEquals(goldenResponse, responseObserver.firstValue().get());
  }

  /**
   * Tests server per-message compression in a streaming response. Ideally we would assert that the
   * responses have the requested compression, but this is not supported by the API. Given a
   * compliant server, this test will exercise the code path for receiving a compressed response but
   * cannot itself verify that the response was compressed.
   */
  public void serverCompressedStreaming() throws Exception {
    final StreamingOutputCallRequest request =
        StreamingOutputCallRequest.newBuilder()
            .addResponseParameters(
                ResponseParameters.newBuilder()
                    .setCompressed(BoolValue.newBuilder().setValue(true))
                    .setSize(31415))
            .addResponseParameters(
                ResponseParameters.newBuilder()
                    .setCompressed(BoolValue.newBuilder().setValue(false))
                    .setSize(92653))
            .build();
    final List<StreamingOutputCallResponse> goldenResponses =
        Arrays.asList(
            StreamingOutputCallResponse.newBuilder()
                .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[31415])))
                .build(),
            StreamingOutputCallResponse.newBuilder()
                .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[92653])))
                .build());

    StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
    asyncStub.streamingOutputCall(request, recorder);
    recorder.awaitCompletion();
    assertSuccess(recorder);
    assertResponses(goldenResponses, recorder.getValues());
  }

  @Test
  public void pingPong() throws Exception {
    final List<StreamingOutputCallRequest> requests = Arrays.asList(
        StreamingOutputCallRequest.newBuilder()
            .addResponseParameters(ResponseParameters.newBuilder()
                .setSize(31415))
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[27182])))
            .build(),
        StreamingOutputCallRequest.newBuilder()
            .addResponseParameters(ResponseParameters.newBuilder()
                .setSize(9))
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[8])))
            .build(),
        StreamingOutputCallRequest.newBuilder()
            .addResponseParameters(ResponseParameters.newBuilder()
                .setSize(2653))
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[1828])))
            .build(),
        StreamingOutputCallRequest.newBuilder()
            .addResponseParameters(ResponseParameters.newBuilder()
                .setSize(58979))
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[45904])))
            .build());
    final List<StreamingOutputCallResponse> goldenResponses = Arrays.asList(
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[31415])))
            .build(),
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[9])))
            .build(),
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[2653])))
            .build(),
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[58979])))
            .build());

    final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(5);
    StreamObserver<StreamingOutputCallRequest> requestObserver
        = asyncStub.fullDuplexCall(new StreamObserver<StreamingOutputCallResponse>() {
          @Override
          public void onNext(StreamingOutputCallResponse response) {
            queue.add(response);
          }

          @Override
          public void onError(Throwable t) {
            queue.add(t);
          }

          @Override
          public void onCompleted() {
            queue.add("Completed");
          }
        });
    for (int i = 0; i < requests.size(); i++) {
      assertNull(queue.peek());
      requestObserver.onNext(requests.get(i));
      Object actualResponse = queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
      assertNotNull("Timed out waiting for response", actualResponse);
      if (actualResponse instanceof Throwable) {
        throw new AssertionError(actualResponse);
      }
      assertResponse(goldenResponses.get(i), (StreamingOutputCallResponse) actualResponse);
    }
    requestObserver.onCompleted();
    assertEquals("Completed", queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS));
  }

  @Test
  public void emptyStream() throws Exception {
    StreamRecorder<StreamingOutputCallResponse> responseObserver = StreamRecorder.create();
    StreamObserver<StreamingOutputCallRequest> requestObserver
        = asyncStub.fullDuplexCall(responseObserver);
    requestObserver.onCompleted();
    responseObserver.awaitCompletion(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
    assertSuccess(responseObserver);
    assertTrue("Expected an empty stream", responseObserver.getValues().isEmpty());
  }

  @Test
  public void cancelAfterBegin() throws Exception {
    StreamRecorder<StreamingInputCallResponse> responseObserver = StreamRecorder.create();
    StreamObserver<StreamingInputCallRequest> requestObserver =
        asyncStub.streamingInputCall(responseObserver);
    requestObserver.onError(new RuntimeException());
    responseObserver.awaitCompletion();
    assertEquals(Arrays.<StreamingInputCallResponse>asList(), responseObserver.getValues());
    assertEquals(Status.Code.CANCELLED,
        Status.fromThrowable(responseObserver.getError()).getCode());

    if (metricsExpected()) {
      MetricsRecord clientStartRecord = clientStatsRecorder.pollRecord(5, TimeUnit.SECONDS);
      checkStartTags(clientStartRecord, "grpc.testing.TestService/StreamingInputCall", true);
      // CensusStreamTracerModule record final status in the interceptor, thus is guaranteed to be
      // recorded.  The tracer stats rely on the stream being created, which is not always the case
      // in this test.  Therefore we don't check the tracer stats.
      MetricsRecord clientEndRecord = clientStatsRecorder.pollRecord(5, TimeUnit.SECONDS);
      checkEndTags(
          clientEndRecord, "grpc.testing.TestService/StreamingInputCall",
          Status.CANCELLED.getCode(), true);
      // Do not check server-side metrics, because the status on the server side is undetermined.
    }
  }

  @Test
  public void cancelAfterFirstResponse() throws Exception {
    final StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
        .addResponseParameters(ResponseParameters.newBuilder()
            .setSize(31415))
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[27182])))
        .build();
    final StreamingOutputCallResponse goldenResponse = StreamingOutputCallResponse.newBuilder()
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[31415])))
        .build();

    StreamRecorder<StreamingOutputCallResponse> responseObserver = StreamRecorder.create();
    StreamObserver<StreamingOutputCallRequest> requestObserver
        = asyncStub.fullDuplexCall(responseObserver);
    requestObserver.onNext(request);
    assertResponse(goldenResponse, responseObserver.firstValue().get());
    requestObserver.onError(new RuntimeException());
    responseObserver.awaitCompletion(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
    assertEquals(1, responseObserver.getValues().size());
    assertEquals(Status.Code.CANCELLED,
                 Status.fromThrowable(responseObserver.getError()).getCode());

    assertStatsTrace("grpc.testing.TestService/FullDuplexCall", Status.Code.CANCELLED);
  }

  @Test
  public void fullDuplexCallShouldSucceed() throws Exception {
    // Build the request.
    List<Integer> responseSizes = Arrays.asList(50, 100, 150, 200);
    StreamingOutputCallRequest.Builder streamingOutputBuilder =
        StreamingOutputCallRequest.newBuilder();
    for (Integer size : responseSizes) {
      streamingOutputBuilder.addResponseParameters(
          ResponseParameters.newBuilder().setSize(size).setIntervalUs(0));
    }
    final StreamingOutputCallRequest request = streamingOutputBuilder.build();

    StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
    StreamObserver<StreamingOutputCallRequest> requestStream =
        asyncStub.fullDuplexCall(recorder);

    final int numRequests = 10;
    List<StreamingOutputCallRequest> requests =
        new ArrayList<>(numRequests);
    for (int ix = numRequests; ix > 0; --ix) {
      requests.add(request);
      requestStream.onNext(request);
    }
    requestStream.onCompleted();
    recorder.awaitCompletion();
    assertSuccess(recorder);
    assertEquals(responseSizes.size() * (long) numRequests, recorder.getValues().size());
    for (int ix = 0; ix < recorder.getValues().size(); ++ix) {
      StreamingOutputCallResponse response = recorder.getValues().get(ix);
      int length = response.getPayload().getBody().size();
      int expectedSize = responseSizes.get(ix % responseSizes.size());
      assertEquals("comparison failed at index " + ix, expectedSize, length);
    }

    assertStatsTrace("grpc.testing.TestService/FullDuplexCall", Status.Code.OK, requests,
        recorder.getValues());
  }

  @Test
  public void halfDuplexCallShouldSucceed() throws Exception {
    // Build the request.
    List<Integer> responseSizes = Arrays.asList(50, 100, 150, 200);
    StreamingOutputCallRequest.Builder streamingOutputBuilder =
        StreamingOutputCallRequest.newBuilder();
    for (Integer size : responseSizes) {
      streamingOutputBuilder.addResponseParameters(
          ResponseParameters.newBuilder().setSize(size).setIntervalUs(0));
    }
    final StreamingOutputCallRequest request = streamingOutputBuilder.build();

    StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
    StreamObserver<StreamingOutputCallRequest> requestStream = asyncStub.halfDuplexCall(recorder);

    final int numRequests = 10;
    for (int ix = numRequests; ix > 0; --ix) {
      requestStream.onNext(request);
    }
    requestStream.onCompleted();
    recorder.awaitCompletion();
    assertSuccess(recorder);
    assertEquals(responseSizes.size() * (long) numRequests, recorder.getValues().size());
    for (int ix = 0; ix < recorder.getValues().size(); ++ix) {
      StreamingOutputCallResponse response = recorder.getValues().get(ix);
      int length = response.getPayload().getBody().size();
      int expectedSize = responseSizes.get(ix % responseSizes.size());
      assertEquals("comparison failed at index " + ix, expectedSize, length);
    }
  }

  @Test
  public void serverStreamingShouldBeFlowControlled() throws Exception {
    final StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
        .addResponseParameters(ResponseParameters.newBuilder().setSize(100000))
        .addResponseParameters(ResponseParameters.newBuilder().setSize(100001))
        .build();
    final List<StreamingOutputCallResponse> goldenResponses = Arrays.asList(
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[100000]))).build(),
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[100001]))).build());

    long start = System.nanoTime();

    final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(10);
    ClientCall<StreamingOutputCallRequest, StreamingOutputCallResponse> call =
        channel.newCall(TestServiceGrpc.getStreamingOutputCallMethod(), CallOptions.DEFAULT);
    call.start(new ClientCall.Listener<StreamingOutputCallResponse>() {
      @Override
      public void onHeaders(Metadata headers) {}

      @Override
      public void onMessage(final StreamingOutputCallResponse message) {
        queue.add(message);
      }

      @Override
      public void onClose(Status status, Metadata trailers) {
        queue.add(status);
      }
    }, new Metadata());
    call.sendMessage(request);
    call.halfClose();

    // Time how long it takes to get the first response.
    call.request(1);
    Object response = queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
    assertTrue(response instanceof StreamingOutputCallResponse);
    assertResponse(goldenResponses.get(0), (StreamingOutputCallResponse) response);
    long firstCallDuration = System.nanoTime() - start;

    // Without giving additional flow control, make sure that we don't get another response. We wait
    // until we are comfortable the next message isn't coming. We may have very low nanoTime
    // resolution (like on Windows) or be using a testing, in-process transport where message
    // handling is instantaneous. In both cases, firstCallDuration may be 0, so round up sleep time
    // to at least 1ms.
    assertNull(queue.poll(Math.max(firstCallDuration * 4, 1 * 1000 * 1000), TimeUnit.NANOSECONDS));

    // Make sure that everything still completes.
    call.request(1);
    response = queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
    assertTrue(response instanceof StreamingOutputCallResponse);
    assertResponse(goldenResponses.get(1), (StreamingOutputCallResponse) response);
    assertEquals(Status.OK, queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS));
  }

  @Test
  public void veryLargeRequest() throws Exception {
    assumeEnoughMemory();
    final SimpleRequest request = SimpleRequest.newBuilder()
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[unaryPayloadLength()])))
        .setResponseSize(10)
        .build();
    final SimpleResponse goldenResponse = SimpleResponse.newBuilder()
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[10])))
        .build();

    assertResponse(goldenResponse, blockingStub.unaryCall(request));
  }

  @Test
  public void veryLargeResponse() throws Exception {
    assumeEnoughMemory();
    final SimpleRequest request = SimpleRequest.newBuilder()
        .setResponseSize(unaryPayloadLength())
        .build();
    final SimpleResponse goldenResponse = SimpleResponse.newBuilder()
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[unaryPayloadLength()])))
        .build();

    assertResponse(goldenResponse, blockingStub.unaryCall(request));
  }

  @Test
  public void exchangeMetadataUnaryCall() throws Exception {
    // Capture the metadata exchange
    Metadata fixedHeaders = new Metadata();
    // Send a metadata proto
    StringValue metadataValue = StringValue.newBuilder().setValue("dog").build();
    fixedHeaders.put(Util.METADATA_KEY, metadataValue);
    // .. and expect it to be echoed back in trailers
    AtomicReference<Metadata> trailersCapture = new AtomicReference<>();
    AtomicReference<Metadata> headersCapture = new AtomicReference<>();
    TestServiceGrpc.TestServiceBlockingStub stub = blockingStub.withInterceptors(
        MetadataUtils.newAttachHeadersInterceptor(fixedHeaders),
        MetadataUtils.newCaptureMetadataInterceptor(headersCapture, trailersCapture));

    assertNotNull(stub.emptyCall(EMPTY));

    // Assert that our side channel object is echoed back in both headers and trailers
    Assert.assertEquals(metadataValue, headersCapture.get().get(Util.METADATA_KEY));
    Assert.assertEquals(metadataValue, trailersCapture.get().get(Util.METADATA_KEY));
  }

  @Test
  public void exchangeMetadataStreamingCall() throws Exception {
    // Capture the metadata exchange
    Metadata fixedHeaders = new Metadata();
    // Send a metadata proto
    StringValue metadataValue = StringValue.newBuilder().setValue("dog").build();
    fixedHeaders.put(Util.METADATA_KEY, metadataValue);
    // .. and expect it to be echoed back in trailers
    AtomicReference<Metadata> trailersCapture = new AtomicReference<>();
    AtomicReference<Metadata> headersCapture = new AtomicReference<>();
    TestServiceGrpc.TestServiceStub stub = asyncStub.withInterceptors(
        MetadataUtils.newAttachHeadersInterceptor(fixedHeaders),
        MetadataUtils.newCaptureMetadataInterceptor(headersCapture, trailersCapture));

    List<Integer> responseSizes = Arrays.asList(50, 100, 150, 200);
    Messages.StreamingOutputCallRequest.Builder streamingOutputBuilder =
        Messages.StreamingOutputCallRequest.newBuilder();
    for (Integer size : responseSizes) {
      streamingOutputBuilder.addResponseParameters(
          ResponseParameters.newBuilder().setSize(size).setIntervalUs(0));
    }
    final Messages.StreamingOutputCallRequest request = streamingOutputBuilder.build();

    StreamRecorder<Messages.StreamingOutputCallResponse> recorder = StreamRecorder.create();
    StreamObserver<Messages.StreamingOutputCallRequest> requestStream =
        stub.fullDuplexCall(recorder);

    final int numRequests = 10;
    for (int ix = numRequests; ix > 0; --ix) {
      requestStream.onNext(request);
    }
    requestStream.onCompleted();
    recorder.awaitCompletion();
    assertSuccess(recorder);
    org.junit.Assert.assertEquals(
        responseSizes.size() * (long) numRequests, recorder.getValues().size());

    // Assert that our side channel object is echoed back in both headers and trailers
    Assert.assertEquals(metadataValue, headersCapture.get().get(Util.METADATA_KEY));
    Assert.assertEquals(metadataValue, trailersCapture.get().get(Util.METADATA_KEY));
  }

  @Test
  public void sendsTimeoutHeader() {
    Assume.assumeTrue("can not capture request headers on server side", server != null);
    long configuredTimeoutMinutes = 100;
    TestServiceGrpc.TestServiceBlockingStub stub =
        blockingStub.withDeadlineAfter(configuredTimeoutMinutes, TimeUnit.MINUTES);
    stub.emptyCall(EMPTY);
    long transferredTimeoutMinutes = TimeUnit.NANOSECONDS.toMinutes(
        requestHeadersCapture.get().get(GrpcUtil.TIMEOUT_KEY));
    Assert.assertTrue(
        "configuredTimeoutMinutes=" + configuredTimeoutMinutes
            + ", transferredTimeoutMinutes=" + transferredTimeoutMinutes,
        configuredTimeoutMinutes - transferredTimeoutMinutes >= 0
            && configuredTimeoutMinutes - transferredTimeoutMinutes <= 1);
  }

  @Test
  public void deadlineNotExceeded() {
    // warm up the channel and JVM
    blockingStub.emptyCall(Empty.getDefaultInstance());
    blockingStub
        .withDeadlineAfter(10, TimeUnit.SECONDS)
        .streamingOutputCall(StreamingOutputCallRequest.newBuilder()
            .addResponseParameters(ResponseParameters.newBuilder()
                .setIntervalUs(0))
                .build()).next();
  }

  @Test
  public void deadlineExceeded() throws Exception {
    // warm up the channel and JVM
    blockingStub.emptyCall(Empty.getDefaultInstance());
    TestServiceGrpc.TestServiceBlockingStub stub =
        blockingStub.withDeadlineAfter(1, TimeUnit.SECONDS);
    StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
        .addResponseParameters(ResponseParameters.newBuilder()
            .setIntervalUs((int) TimeUnit.SECONDS.toMicros(20)))
        .build();
    try {
      stub.streamingOutputCall(request).next();
      fail("Expected deadline to be exceeded");
    } catch (StatusRuntimeException ex) {
      assertEquals(Status.DEADLINE_EXCEEDED.getCode(), ex.getStatus().getCode());
      String desc = ex.getStatus().getDescription();
      assertTrue(desc,
          // There is a race between client and server-side deadline expiration.
          // If client expires first, it'd generate this message
          Pattern.matches("CallOptions deadline exceeded after .*s. \\[.*\\]", desc)
          // If server expires first, it'd reset the stream and client would generate a different
          // message
          || desc.startsWith("ClientCall was cancelled at or after deadline."));
    }

    assertStatsTrace("grpc.testing.TestService/EmptyCall", Status.Code.OK);
    if (metricsExpected()) {
      // Stream may not have been created before deadline is exceeded, thus we don't test the tracer
      // stats.
      MetricsRecord clientStartRecord = clientStatsRecorder.pollRecord(5, TimeUnit.SECONDS);
      checkStartTags(
          clientStartRecord, "grpc.testing.TestService/StreamingOutputCall", true);
      MetricsRecord clientEndRecord = clientStatsRecorder.pollRecord(5, TimeUnit.SECONDS);
      checkEndTags(
          clientEndRecord,
          "grpc.testing.TestService/StreamingOutputCall",
          Status.Code.DEADLINE_EXCEEDED, true);
      // Do not check server-side metrics, because the status on the server side is undetermined.
    }
  }

  @Test
  public void deadlineExceededServerStreaming() throws Exception {
    // warm up the channel and JVM
    blockingStub.emptyCall(Empty.getDefaultInstance());
    assertStatsTrace("grpc.testing.TestService/EmptyCall", Status.Code.OK);
    ResponseParameters.Builder responseParameters = ResponseParameters.newBuilder()
        .setSize(1)
        .setIntervalUs(10000);
    StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
        .addResponseParameters(responseParameters)
        .addResponseParameters(responseParameters)
        .addResponseParameters(responseParameters)
        .addResponseParameters(responseParameters)
        .build();
    StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
    asyncStub
        .withDeadlineAfter(30, TimeUnit.MILLISECONDS)
        .streamingOutputCall(request, recorder);
    recorder.awaitCompletion();
    assertEquals(Status.DEADLINE_EXCEEDED.getCode(),
        Status.fromThrowable(recorder.getError()).getCode());
    if (metricsExpected()) {
      // Stream may not have been created when deadline is exceeded, thus we don't check tracer
      // stats.
      MetricsRecord clientStartRecord = clientStatsRecorder.pollRecord(5, TimeUnit.SECONDS);
      checkStartTags(
          clientStartRecord, "grpc.testing.TestService/StreamingOutputCall", true);
      MetricsRecord clientEndRecord = clientStatsRecorder.pollRecord(5, TimeUnit.SECONDS);
      checkEndTags(
          clientEndRecord,
          "grpc.testing.TestService/StreamingOutputCall",
          Status.Code.DEADLINE_EXCEEDED, true);
      // Do not check server-side metrics, because the status on the server side is undetermined.
    }
  }

  @Test
  public void deadlineInPast() throws Exception {
    // Test once with idle channel and once with active channel
    try {
      blockingStub
          .withDeadlineAfter(-10, TimeUnit.SECONDS)
          .emptyCall(Empty.getDefaultInstance());
      fail("Should have thrown");
    } catch (StatusRuntimeException ex) {
      assertEquals(Status.Code.DEADLINE_EXCEEDED, ex.getStatus().getCode());
      assertThat(ex.getStatus().getDescription())
          .startsWith("ClientCall started after CallOptions deadline was exceeded");
    }

    // CensusStreamTracerModule record final status in the interceptor, thus is guaranteed to be
    // recorded.  The tracer stats rely on the stream being created, which is not the case if
    // deadline is exceeded before the call is created. Therefore we don't check the tracer stats.
    if (metricsExpected()) {
      MetricsRecord clientStartRecord = clientStatsRecorder.pollRecord(5, TimeUnit.SECONDS);
      checkStartTags(clientStartRecord, "grpc.testing.TestService/EmptyCall", true);
      MetricsRecord clientEndRecord = clientStatsRecorder.pollRecord(5, TimeUnit.SECONDS);
      checkEndTags(
          clientEndRecord, "grpc.testing.TestService/EmptyCall",
          Status.DEADLINE_EXCEEDED.getCode(), true);
      assertZeroRetryRecorded();
    }

    // warm up the channel
    blockingStub.emptyCall(Empty.getDefaultInstance());
    if (metricsExpected()) {
      // clientStartRecord
      clientStatsRecorder.pollRecord(5, TimeUnit.SECONDS);
      // clientEndRecord
      clientStatsRecorder.pollRecord(5, TimeUnit.SECONDS);
      assertZeroRetryRecorded();
    }
    try {
      blockingStub
          .withDeadlineAfter(-10, TimeUnit.SECONDS)
          .emptyCall(Empty.getDefaultInstance());
      fail("Should have thrown");
    } catch (StatusRuntimeException ex) {
      assertEquals(Status.Code.DEADLINE_EXCEEDED, ex.getStatus().getCode());
      assertThat(ex.getStatus().getDescription())
          .startsWith("ClientCall started after CallOptions deadline was exceeded");
    }
    if (metricsExpected()) {
      MetricsRecord clientStartRecord = clientStatsRecorder.pollRecord(5, TimeUnit.SECONDS);
      checkStartTags(clientStartRecord, "grpc.testing.TestService/EmptyCall", true);
      MetricsRecord clientEndRecord = clientStatsRecorder.pollRecord(5, TimeUnit.SECONDS);
      checkEndTags(
          clientEndRecord, "grpc.testing.TestService/EmptyCall",
          Status.DEADLINE_EXCEEDED.getCode(), true);
      assertZeroRetryRecorded();
    }
  }

  @Test
  public void maxInboundSize_exact() {
    StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
        .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
        .build();

    MethodDescriptor<StreamingOutputCallRequest, StreamingOutputCallResponse> md =
        TestServiceGrpc.getStreamingOutputCallMethod();
    ByteSizeMarshaller<StreamingOutputCallResponse> mar =
        new ByteSizeMarshaller<>(md.getResponseMarshaller());
    blockingServerStreamingCall(
        blockingStub.getChannel(),
        md.toBuilder(md.getRequestMarshaller(), mar).build(),
        blockingStub.getCallOptions(),
        request)
        .next();

    int size = mar.lastInSize;

    TestServiceGrpc.TestServiceBlockingStub stub =
        blockingStub.withMaxInboundMessageSize(size);

    stub.streamingOutputCall(request).next();
  }

  @Test
  public void maxInboundSize_tooBig() {
    StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
        .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
        .build();

    MethodDescriptor<StreamingOutputCallRequest, StreamingOutputCallResponse> md =
        TestServiceGrpc.getStreamingOutputCallMethod();
    ByteSizeMarshaller<StreamingOutputCallRequest> mar =
        new ByteSizeMarshaller<>(md.getRequestMarshaller());
    blockingServerStreamingCall(
        blockingStub.getChannel(),
        md.toBuilder(mar, md.getResponseMarshaller()).build(),
        blockingStub.getCallOptions(),
        request)
        .next();

    int size = mar.lastOutSize;

    TestServiceGrpc.TestServiceBlockingStub stub =
        blockingStub.withMaxInboundMessageSize(size - 1);

    try {
      stub.streamingOutputCall(request).next();
      fail();
    } catch (StatusRuntimeException ex) {
      Status s = ex.getStatus();
      assertWithMessage(s.toString()).that(s.getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
      assertThat(Throwables.getStackTraceAsString(ex)).contains("exceeds maximum");
    }
  }

  @Test
  public void maxOutboundSize_exact() {
    StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
        .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
        .build();

    MethodDescriptor<StreamingOutputCallRequest, StreamingOutputCallResponse> md =
        TestServiceGrpc.getStreamingOutputCallMethod();
    ByteSizeMarshaller<StreamingOutputCallRequest> mar =
        new ByteSizeMarshaller<>(md.getRequestMarshaller());
    blockingServerStreamingCall(
        blockingStub.getChannel(),
        md.toBuilder(mar, md.getResponseMarshaller()).build(),
        blockingStub.getCallOptions(),
        request)
        .next();

    int size = mar.lastOutSize;

    TestServiceGrpc.TestServiceBlockingStub stub =
        blockingStub.withMaxOutboundMessageSize(size);

    stub.streamingOutputCall(request).next();
  }

  @Test
  public void maxOutboundSize_tooBig() {
    // set at least one field to ensure the size is non-zero.
    StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
        .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
        .build();


    MethodDescriptor<StreamingOutputCallRequest, StreamingOutputCallResponse> md =
        TestServiceGrpc.getStreamingOutputCallMethod();
    ByteSizeMarshaller<StreamingOutputCallRequest> mar =
        new ByteSizeMarshaller<>(md.getRequestMarshaller());
    blockingServerStreamingCall(
        blockingStub.getChannel(),
        md.toBuilder(mar, md.getResponseMarshaller()).build(),
        blockingStub.getCallOptions(),
        request)
        .next();

    TestServiceGrpc.TestServiceBlockingStub stub =
        blockingStub.withMaxOutboundMessageSize(mar.lastOutSize - 1);
    try {
      stub.streamingOutputCall(request).next();
      fail();
    } catch (StatusRuntimeException ex) {
      Status s = ex.getStatus();
      assertWithMessage(s.toString()).that(s.getCode()).isEqualTo(Status.Code.CANCELLED);
      assertThat(Throwables.getStackTraceAsString(ex)).contains("message too large");
    }
  }

  protected int unaryPayloadLength() {
    // 10MiB.
    return 10485760;
  }

  @Test
  public void gracefulShutdown() throws Exception {
    final List<StreamingOutputCallRequest> requests = Arrays.asList(
        StreamingOutputCallRequest.newBuilder()
            .addResponseParameters(ResponseParameters.newBuilder()
                .setSize(3))
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[2])))
            .build(),
        StreamingOutputCallRequest.newBuilder()
            .addResponseParameters(ResponseParameters.newBuilder()
                .setSize(1))
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[7])))
            .build(),
        StreamingOutputCallRequest.newBuilder()
            .addResponseParameters(ResponseParameters.newBuilder()
                .setSize(4))
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[1])))
            .build());
    final List<StreamingOutputCallResponse> goldenResponses = Arrays.asList(
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[3])))
            .build(),
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[1])))
            .build(),
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
                .setBody(ByteString.copyFrom(new byte[4])))
            .build());

    final ArrayBlockingQueue<StreamingOutputCallResponse> responses =
        new ArrayBlockingQueue<>(3);
    final SettableFuture<Void> completed = SettableFuture.create();
    final SettableFuture<Void> errorSeen = SettableFuture.create();
    StreamObserver<StreamingOutputCallResponse> responseObserver =
        new StreamObserver<StreamingOutputCallResponse>() {

          @Override
          public void onNext(StreamingOutputCallResponse value) {
            responses.add(value);
          }

          @Override
          public void onError(Throwable t) {
            errorSeen.set(null);
          }

          @Override
          public void onCompleted() {
            completed.set(null);
          }
        };
    StreamObserver<StreamingOutputCallRequest> requestObserver
        = asyncStub.fullDuplexCall(responseObserver);
    requestObserver.onNext(requests.get(0));
    assertResponse(
        goldenResponses.get(0), responses.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS));
    // Initiate graceful shutdown.
    channel.shutdown();
    requestObserver.onNext(requests.get(1));
    assertResponse(
        goldenResponses.get(1), responses.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS));
    // The previous ping-pong could have raced with the shutdown, but this one certainly shouldn't.
    requestObserver.onNext(requests.get(2));
    assertResponse(
        goldenResponses.get(2), responses.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS));
    assertFalse(completed.isDone());
    requestObserver.onCompleted();
    completed.get(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
    assertFalse(errorSeen.isDone());
  }

  @Test
  public void customMetadata() throws Exception {
    final int responseSize = 314159;
    final int requestSize = 271828;
    final SimpleRequest request = SimpleRequest.newBuilder()
        .setResponseSize(responseSize)
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[requestSize])))
        .build();
    final StreamingOutputCallRequest streamingRequest = StreamingOutputCallRequest.newBuilder()
        .addResponseParameters(ResponseParameters.newBuilder().setSize(responseSize))
        .setPayload(Payload.newBuilder().setBody(ByteString.copyFrom(new byte[requestSize])))
        .build();
    final SimpleResponse goldenResponse = SimpleResponse.newBuilder()
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[responseSize])))
        .build();
    final StreamingOutputCallResponse goldenStreamingResponse =
        StreamingOutputCallResponse.newBuilder()
            .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[responseSize])))
        .build();
    final byte[] trailingBytes =
        {(byte) 0xa, (byte) 0xb, (byte) 0xa, (byte) 0xb, (byte) 0xa, (byte) 0xb};

    // Test UnaryCall
    Metadata metadata = new Metadata();
    metadata.put(Util.ECHO_INITIAL_METADATA_KEY, "test_initial_metadata_value");
    metadata.put(Util.ECHO_TRAILING_METADATA_KEY, trailingBytes);
    AtomicReference<Metadata> headersCapture = new AtomicReference<>();
    AtomicReference<Metadata> trailersCapture = new AtomicReference<>();
    TestServiceGrpc.TestServiceBlockingStub blockingStub = this.blockingStub.withInterceptors(
        MetadataUtils.newAttachHeadersInterceptor(metadata),
        MetadataUtils.newCaptureMetadataInterceptor(headersCapture, trailersCapture));
    SimpleResponse response = blockingStub.unaryCall(request);

    assertResponse(goldenResponse, response);
    assertEquals("test_initial_metadata_value",
        headersCapture.get().get(Util.ECHO_INITIAL_METADATA_KEY));
    assertTrue(
        Arrays.equals(trailingBytes, trailersCapture.get().get(Util.ECHO_TRAILING_METADATA_KEY)));
    assertStatsTrace("grpc.testing.TestService/UnaryCall", Status.Code.OK,
        Collections.singleton(request), Collections.singleton(goldenResponse));

    // Test FullDuplexCall
    metadata = new Metadata();
    metadata.put(Util.ECHO_INITIAL_METADATA_KEY, "test_initial_metadata_value");
    metadata.put(Util.ECHO_TRAILING_METADATA_KEY, trailingBytes);
    headersCapture = new AtomicReference<>();
    trailersCapture = new AtomicReference<>();
    TestServiceGrpc.TestServiceStub stub = asyncStub.withInterceptors(
        MetadataUtils.newAttachHeadersInterceptor(metadata),
        MetadataUtils.newCaptureMetadataInterceptor(headersCapture, trailersCapture));

    StreamRecorder<Messages.StreamingOutputCallResponse> recorder = StreamRecorder.create();
    StreamObserver<Messages.StreamingOutputCallRequest> requestStream =
        stub.fullDuplexCall(recorder);
    requestStream.onNext(streamingRequest);
    requestStream.onCompleted();
    recorder.awaitCompletion();

    assertSuccess(recorder);
    assertResponse(goldenStreamingResponse, recorder.firstValue().get());
    assertEquals("test_initial_metadata_value",
        headersCapture.get().get(Util.ECHO_INITIAL_METADATA_KEY));
    assertTrue(
        Arrays.equals(trailingBytes, trailersCapture.get().get(Util.ECHO_TRAILING_METADATA_KEY)));
    assertStatsTrace("grpc.testing.TestService/FullDuplexCall", Status.Code.OK,
        Collections.singleton(streamingRequest), Collections.singleton(goldenStreamingResponse));
  }

  @SuppressWarnings("deprecation")
  @Test(timeout = 10000)
  public void censusContextsPropagated() {
    Assume.assumeTrue("Skip the test because server is not in the same process.", server != null);
    Assume.assumeTrue(customCensusModulePresent());
    Span clientParentSpan = Tracing.getTracer().spanBuilder("Test.interopTest").startSpan();
    // A valid ID is guaranteed to be unique, so we can verify it is actually propagated.
    assertTrue(clientParentSpan.getContext().getTraceId().isValid());
    Context ctx =
        io.opencensus.tags.unsafe.ContextUtils.withValue(
            Context.ROOT,
            tagger
                .emptyBuilder()
                .putLocal(StatsTestUtils.EXTRA_TAG, TagValue.create("extra value"))
                .build());
    ctx = io.opencensus.trace.unsafe.ContextUtils.withValue(ctx, clientParentSpan);
    Context origCtx = ctx.attach();
    try {
      blockingStub.unaryCall(SimpleRequest.getDefaultInstance());
      Context serverCtx = contextCapture.get();
      assertNotNull(serverCtx);

      FakeTagContext statsCtx =
          (FakeTagContext) io.opencensus.tags.unsafe.ContextUtils.getValue(serverCtx);
      assertNotNull(statsCtx);
      Map<TagKey, TagValue> tags = statsCtx.getTags();
      boolean tagFound = false;
      for (Map.Entry<TagKey, TagValue> tag : tags.entrySet()) {
        if (tag.getKey().equals(StatsTestUtils.EXTRA_TAG)) {
          assertEquals(TagValue.create("extra value"), tag.getValue());
          tagFound = true;
        }
      }
      assertTrue("tag not found", tagFound);

      Span span = io.opencensus.trace.unsafe.ContextUtils.getValue(serverCtx);
      assertNotNull(span);
      SpanContext spanContext = span.getContext();
      assertEquals(clientParentSpan.getContext().getTraceId(), spanContext.getTraceId());
    } finally {
      ctx.detach(origCtx);
    }
  }

  @Test
  public void statusCodeAndMessage() throws Exception {
    int errorCode = 2;
    String errorMessage = "test status message";
    EchoStatus responseStatus = EchoStatus.newBuilder()
        .setCode(errorCode)
        .setMessage(errorMessage)
        .build();
    SimpleRequest simpleRequest = SimpleRequest.newBuilder()
        .setResponseStatus(responseStatus)
        .build();
    StreamingOutputCallRequest streamingRequest = StreamingOutputCallRequest.newBuilder()
        .setResponseStatus(responseStatus)
        .build();

    // Test UnaryCall
    try {
      blockingStub.unaryCall(simpleRequest);
      fail();
    } catch (StatusRuntimeException e) {
      assertEquals(Status.UNKNOWN.getCode(), e.getStatus().getCode());
      assertEquals(errorMessage, e.getStatus().getDescription());
    }
    assertStatsTrace("grpc.testing.TestService/UnaryCall", Status.Code.UNKNOWN);

    // Test FullDuplexCall
    StreamRecorder<StreamingOutputCallResponse> responseObserver = StreamRecorder.create();
    StreamObserver<StreamingOutputCallRequest> requestObserver
        = asyncStub.fullDuplexCall(responseObserver);
    requestObserver.onNext(streamingRequest);
    requestObserver.onCompleted();

    assertThat(responseObserver.awaitCompletion(operationTimeoutMillis(), TimeUnit.MILLISECONDS))
        .isTrue();
    assertThat(responseObserver.getError()).isNotNull();
    Status status = Status.fromThrowable(responseObserver.getError());
    assertEquals(Status.UNKNOWN.getCode(), status.getCode());
    assertEquals(errorMessage, status.getDescription());
    assertStatsTrace("grpc.testing.TestService/FullDuplexCall", Status.Code.UNKNOWN);
  }

  @Test
  public void specialStatusMessage() throws Exception {
    int errorCode = 2;
    String errorMessage = "\t\ntest with whitespace\r\nand Unicode BMP ☺ and non-BMP 😈\t\n";
    SimpleRequest simpleRequest = SimpleRequest.newBuilder()
        .setResponseStatus(EchoStatus.newBuilder()
            .setCode(errorCode)
            .setMessage(errorMessage)
            .build())
        .build();

    try {
      blockingStub.unaryCall(simpleRequest);
      fail();
    } catch (StatusRuntimeException e) {
      assertEquals(Status.UNKNOWN.getCode(), e.getStatus().getCode());
      assertEquals(errorMessage, e.getStatus().getDescription());
    }
    assertStatsTrace("grpc.testing.TestService/UnaryCall", Status.Code.UNKNOWN);
  }

  /** Sends an rpc to an unimplemented method within TestService. */
  @Test
  public void unimplementedMethod() {
    try {
      blockingStub.unimplementedCall(Empty.getDefaultInstance());
      fail();
    } catch (StatusRuntimeException e) {
      assertEquals(Status.UNIMPLEMENTED.getCode(), e.getStatus().getCode());
    }

    assertClientStatsTrace("grpc.testing.TestService/UnimplementedCall",
        Status.Code.UNIMPLEMENTED);
  }

  /** Sends an rpc to an unimplemented service on the server. */
  @Test
  public void unimplementedService() {
    UnimplementedServiceGrpc.UnimplementedServiceBlockingStub stub =
        UnimplementedServiceGrpc.newBlockingStub(channel).withInterceptors(tracerSetupInterceptor);
    try {
      stub.unimplementedCall(Empty.getDefaultInstance());
      fail();
    } catch (StatusRuntimeException e) {
      assertEquals(Status.UNIMPLEMENTED.getCode(), e.getStatus().getCode());
    }

    assertStatsTrace("grpc.testing.UnimplementedService/UnimplementedCall",
        Status.Code.UNIMPLEMENTED);
  }

  /** Start a fullDuplexCall which the server will not respond, and verify the deadline expires. */
  @SuppressWarnings("MissingFail")
  @Test
  public void timeoutOnSleepingServer() throws Exception {
    TestServiceGrpc.TestServiceStub stub =
        asyncStub.withDeadlineAfter(1, TimeUnit.MILLISECONDS);

    StreamRecorder<StreamingOutputCallResponse> responseObserver = StreamRecorder.create();
    StreamObserver<StreamingOutputCallRequest> requestObserver
        = stub.fullDuplexCall(responseObserver);

    StreamingOutputCallRequest request = StreamingOutputCallRequest.newBuilder()
        .setPayload(Payload.newBuilder()
            .setBody(ByteString.copyFrom(new byte[27182])))
        .build();
    try {
      requestObserver.onNext(request);
    } catch (IllegalStateException expected) {
      // This can happen if the stream has already been terminated due to deadline exceeded.
    }

    assertTrue(responseObserver.awaitCompletion(operationTimeoutMillis(), TimeUnit.MILLISECONDS));
    assertEquals(0, responseObserver.getValues().size());
    assertEquals(Status.DEADLINE_EXCEEDED.getCode(),
                 Status.fromThrowable(responseObserver.getError()).getCode());

    if (metricsExpected()) {
      // CensusStreamTracerModule record final status in the interceptor, thus is guaranteed to be
      // recorded.  The tracer stats rely on the stream being created, which is not always the case
      // in this test, thus we will not check that.
      MetricsRecord clientStartRecord = clientStatsRecorder.pollRecord(5, TimeUnit.SECONDS);
      checkStartTags(clientStartRecord, "grpc.testing.TestService/FullDuplexCall", true);
      MetricsRecord clientEndRecord = clientStatsRecorder.pollRecord(5, TimeUnit.SECONDS);
      checkEndTags(
          clientEndRecord,
          "grpc.testing.TestService/FullDuplexCall",
          Status.DEADLINE_EXCEEDED.getCode(), true);
    }
  }

  /**
   * Verifies remote server address and local client address are available from ClientCall
   * Attributes via ClientInterceptor.
   */
  @Test
  public void getServerAddressAndLocalAddressFromClient() {
    assertNotNull(obtainRemoteServerAddr());
    assertNotNull(obtainLocalClientAddr());
  }

  private static class SoakIterationResult {
    public SoakIterationResult(long latencyMs, Status status) {
      this.latencyMs = latencyMs;
      this.status = status;
    }

    public long getLatencyMs() {
      return latencyMs;
    }

    public Status getStatus() {
      return status;
    }

    private long latencyMs = -1;
    private Status status = Status.OK;
  }

  private SoakIterationResult performOneSoakIteration(
      TestServiceGrpc.TestServiceBlockingStub soakStub, int soakRequestSize, int soakResponseSize)
      throws Exception {
    long startNs = System.nanoTime();
    Status status = Status.OK;
    try {
      final SimpleRequest request =
          SimpleRequest.newBuilder()
              .setResponseSize(soakResponseSize)
              .setPayload(
                  Payload.newBuilder().setBody(ByteString.copyFrom(new byte[soakRequestSize])))
              .build();
      final SimpleResponse goldenResponse =
          SimpleResponse.newBuilder()
              .setPayload(
                  Payload.newBuilder().setBody(ByteString.copyFrom(new byte[soakResponseSize])))
              .build();
      assertResponse(goldenResponse, soakStub.unaryCall(request));
    } catch (StatusRuntimeException e) {
      status = e.getStatus();
    }
    long elapsedNs = System.nanoTime() - startNs;
    return new SoakIterationResult(TimeUnit.NANOSECONDS.toMillis(elapsedNs), status);
  }

  /**
    * Runs large unary RPCs in a loop with configurable failure thresholds
    * and channel creation behavior.
   */
  public void performSoakTest(
      String serverUri,
      boolean resetChannelPerIteration,
      int soakIterations,
      int maxFailures,
      int maxAcceptablePerIterationLatencyMs,
      int minTimeMsBetweenRpcs,
      int overallTimeoutSeconds,
      int soakRequestSize,
      int soakResponseSize)
      throws Exception {
    int iterationsDone = 0;
    int totalFailures = 0;
    Histogram latencies = new Histogram(4 /* number of significant value digits */);
    long startNs = System.nanoTime();
    ManagedChannel soakChannel = createChannel();
    TestServiceGrpc.TestServiceBlockingStub soakStub = TestServiceGrpc
        .newBlockingStub(soakChannel)
        .withInterceptors(recordClientCallInterceptor(clientCallCapture));
    for (int i = 0; i < soakIterations; i++) {
      if (System.nanoTime() - startNs >= TimeUnit.SECONDS.toNanos(overallTimeoutSeconds)) {
        break;
      }
      long earliestNextStartNs = System.nanoTime()
          + TimeUnit.MILLISECONDS.toNanos(minTimeMsBetweenRpcs);
      if (resetChannelPerIteration) {
        soakChannel.shutdownNow();
        soakChannel.awaitTermination(10, TimeUnit.SECONDS);
        soakChannel = createChannel();
        soakStub = TestServiceGrpc
            .newBlockingStub(soakChannel)
            .withInterceptors(recordClientCallInterceptor(clientCallCapture));
      }
      SoakIterationResult result = 
          performOneSoakIteration(soakStub, soakRequestSize, soakResponseSize);
      SocketAddress peer = clientCallCapture
          .get().getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
      StringBuilder logStr = new StringBuilder(
          String.format(
              Locale.US,
              "soak iteration: %d elapsed_ms: %d peer: %s server_uri: %s",
              i, result.getLatencyMs(), peer != null ? peer.toString() : "null", serverUri));
      if (!result.getStatus().equals(Status.OK)) {
        totalFailures++;
        logStr.append(String.format(" failed: %s", result.getStatus()));
      } else if (result.getLatencyMs() > maxAcceptablePerIterationLatencyMs) {
        totalFailures++;
        logStr.append(
            " exceeds max acceptable latency: " + maxAcceptablePerIterationLatencyMs);
      } else {
        logStr.append(" succeeded");
      }
      System.err.println(logStr.toString());
      iterationsDone++;
      latencies.recordValue(result.getLatencyMs());
      long remainingNs = earliestNextStartNs - System.nanoTime();
      if (remainingNs > 0) {
        TimeUnit.NANOSECONDS.sleep(remainingNs);
      }
    }
    soakChannel.shutdownNow();
    soakChannel.awaitTermination(10, TimeUnit.SECONDS);
    System.err.println(
        String.format(
            Locale.US,
            "(server_uri: %s) soak test ran: %d / %d iterations. total failures: %d. "
                + "p50: %d ms, p90: %d ms, p100: %d ms",
            serverUri,
            iterationsDone,
            soakIterations,
            totalFailures,
            latencies.getValueAtPercentile(50),
            latencies.getValueAtPercentile(90),
            latencies.getValueAtPercentile(100)));
    // check if we timed out
    String timeoutErrorMessage =
        String.format(
            Locale.US,
            "(server_uri: %s) soak test consumed all %d seconds of time and quit early, "
                + "only having ran %d out of desired %d iterations.",
            serverUri,
            overallTimeoutSeconds,
            iterationsDone,
            soakIterations);
    assertEquals(timeoutErrorMessage, iterationsDone, soakIterations);
    // check if we had too many failures
    String tooManyFailuresErrorMessage =
        String.format(
            Locale.US,
            "(server_uri: %s) soak test total failures: %d exceeds max failures "
                + "threshold: %d.",
            serverUri, totalFailures, maxFailures);
    assertTrue(tooManyFailuresErrorMessage, totalFailures <= maxFailures);
  }

  private static void assertSuccess(StreamRecorder<?> recorder) {
    if (recorder.getError() != null) {
      throw new AssertionError(recorder.getError());
    }
  }

  /** Helper for getting remote address from {@link io.grpc.ServerCall#getAttributes()}. */
  protected SocketAddress obtainRemoteClientAddr() {
    TestServiceGrpc.TestServiceBlockingStub stub =
        blockingStub.withDeadlineAfter(5, TimeUnit.SECONDS);

    stub.unaryCall(SimpleRequest.getDefaultInstance());

    return serverCallCapture.get().getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
  }

  /** Helper for getting remote address from {@link io.grpc.ClientCall#getAttributes()}. */
  protected SocketAddress obtainRemoteServerAddr() {
    TestServiceGrpc.TestServiceBlockingStub stub = blockingStub
        .withInterceptors(recordClientCallInterceptor(clientCallCapture))
        .withDeadlineAfter(5, TimeUnit.SECONDS);

    stub.unaryCall(SimpleRequest.getDefaultInstance());

    return clientCallCapture.get().getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
  }

  /** Helper for getting local address from {@link io.grpc.ServerCall#getAttributes()}. */
  protected SocketAddress obtainLocalServerAddr() {
    TestServiceGrpc.TestServiceBlockingStub stub =
        blockingStub.withDeadlineAfter(5, TimeUnit.SECONDS);

    stub.unaryCall(SimpleRequest.getDefaultInstance());

    return serverCallCapture.get().getAttributes().get(Grpc.TRANSPORT_ATTR_LOCAL_ADDR);
  }

  /** Helper for getting local address from {@link io.grpc.ClientCall#getAttributes()}. */
  protected SocketAddress obtainLocalClientAddr() {
    TestServiceGrpc.TestServiceBlockingStub stub = blockingStub
        .withInterceptors(recordClientCallInterceptor(clientCallCapture))
        .withDeadlineAfter(5, TimeUnit.SECONDS);

    stub.unaryCall(SimpleRequest.getDefaultInstance());

    return clientCallCapture.get().getAttributes().get(Grpc.TRANSPORT_ATTR_LOCAL_ADDR);
  }

  /** Helper for asserting TLS info in SSLSession {@link io.grpc.ServerCall#getAttributes()}. */
  protected void assertX500SubjectDn(String tlsInfo) {
    TestServiceGrpc.TestServiceBlockingStub stub =
        blockingStub.withDeadlineAfter(10, TimeUnit.SECONDS);

    stub.unaryCall(SimpleRequest.getDefaultInstance());

    List<Certificate> certificates;
    SSLSession sslSession =
        serverCallCapture.get().getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
    try {
      certificates = Arrays.asList(sslSession.getPeerCertificates());
    } catch (SSLPeerUnverifiedException e) {
      // Should never happen
      throw new AssertionError(e);
    }

    X509Certificate x509cert = (X509Certificate) certificates.get(0);

    assertEquals(1, certificates.size());
    assertEquals(tlsInfo, x509cert.getSubjectX500Principal().toString());
  }

  protected int operationTimeoutMillis() {
    return 7000;
  }

  /**
   * Some tests run on memory constrained environments.  Rather than OOM, just give up.  64 is
   * chosen as a maximum amount of memory a large test would need.
   */
  protected static void assumeEnoughMemory() {
    Runtime r = Runtime.getRuntime();
    long usedMem = r.totalMemory() - r.freeMemory();
    long actuallyFreeMemory = r.maxMemory() - usedMem;
    Assume.assumeTrue(
        actuallyFreeMemory + " is not sufficient to run this test",
        actuallyFreeMemory >= 64 * 1024 * 1024);
  }

  /**
   * Poll the next metrics record and check it against the provided information, including the
   * message sizes.
   */
  private void assertStatsTrace(String method, Status.Code status,
      Collection<? extends MessageLite> requests,
      Collection<? extends MessageLite> responses) {
    assertClientStatsTrace(method, status, requests, responses);
    assertServerStatsTrace(method, status, requests, responses);
  }

  /**
   * Poll the next metrics record and check it against the provided information, without checking
   * the message sizes.
   */
  private void assertStatsTrace(String method, Status.Code status) {
    assertStatsTrace(method, status, null, null);
  }

  private void assertZeroRetryRecorded() {
    MetricsRecord retryRecord = clientStatsRecorder.pollRecord();
    assertThat(retryRecord.getMetric(RETRIES_PER_CALL)).isEqualTo(0);
    assertThat(retryRecord.getMetric(TRANSPARENT_RETRIES_PER_CALL)).isEqualTo(0);
    assertThat(retryRecord.getMetric(RETRY_DELAY_PER_CALL)).isEqualTo(0D);
  }

  private void assertClientStatsTrace(String method, Status.Code code,
      Collection<? extends MessageLite> requests, Collection<? extends MessageLite> responses) {
    // Tracer-based stats
    TestClientStreamTracer tracer = clientStreamTracers.poll();
    assertNotNull(tracer);
    assertTrue(tracer.getOutboundHeaders());
    // assertClientStatsTrace() is called right after application receives status,
    // but streamClosed() may be called slightly later than that.  So we need a timeout.
    try {
      assertTrue(tracer.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
    assertEquals(code, tracer.getStatus().getCode());

    if (requests != null && responses != null) {
      checkTracers(tracer, requests, responses);
    }
    if (metricsExpected()) {
      // CensusStreamTracerModule records final status in interceptor, which is guaranteed to be
      // done before application receives status.
      MetricsRecord clientStartRecord = clientStatsRecorder.pollRecord();
      checkStartTags(clientStartRecord, method, true);
      MetricsRecord clientEndRecord = clientStatsRecorder.pollRecord();
      checkEndTags(clientEndRecord, method, code, true);

      if (requests != null && responses != null) {
        checkCensus(clientEndRecord, false, requests, responses);
      }
      assertZeroRetryRecorded();
    }
  }

  private void assertClientStatsTrace(String method, Status.Code status) {
    assertClientStatsTrace(method, status, null, null);
  }

  @SuppressWarnings("AssertionFailureIgnored") // Failure is checked in the end by the passed flag.
  private void assertServerStatsTrace(String method, Status.Code code,
      Collection<? extends MessageLite> requests, Collection<? extends MessageLite> responses) {
    if (server == null) {
      // Server is not in the same process.  We can't check server-side stats.
      return;
    }

    if (metricsExpected()) {
      MetricsRecord serverStartRecord;
      MetricsRecord serverEndRecord;
      try {
        // On the server, the stats is finalized in ServerStreamListener.closed(), which can be
        // run after the client receives the final status.  So we use a timeout.
        serverStartRecord = serverStatsRecorder.pollRecord(5, TimeUnit.SECONDS);
        serverEndRecord = serverStatsRecorder.pollRecord(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
      assertNotNull(serverStartRecord);
      assertNotNull(serverEndRecord);
      checkStartTags(serverStartRecord, method, false);
      checkEndTags(serverEndRecord, method, code, false);
      if (requests != null && responses != null) {
        checkCensus(serverEndRecord, true, requests, responses);
      }
    }

    ServerStreamTracerInfo tracerInfo;
    tracerInfo = serverStreamTracers.poll();
    assertNotNull(tracerInfo);
    assertEquals(method, tracerInfo.fullMethodName);
    assertNotNull(tracerInfo.tracer.contextCapture);
    // On the server, streamClosed() may be called after the client receives the final status.
    // So we use a timeout.
    try {
      assertTrue(tracerInfo.tracer.await(1, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
    assertEquals(code, tracerInfo.tracer.getStatus().getCode());
    if (requests != null && responses != null) {
      checkTracers(tracerInfo.tracer, responses, requests);
    }
  }

  private static void checkStartTags(MetricsRecord record, String methodName, boolean clientSide) {
    assertNotNull("record is not null", record);

    TagKey methodNameTagKey = clientSide
        ? RpcMeasureConstants.GRPC_CLIENT_METHOD
        : RpcMeasureConstants.GRPC_SERVER_METHOD;
    TagValue methodNameTag = record.tags.get(methodNameTagKey);
    assertNotNull("method name tagged", methodNameTag);
    assertEquals("method names match", methodName, methodNameTag.asString());
  }

  private static void checkEndTags(
      MetricsRecord record, String methodName, Status.Code status, boolean clientSide) {
    assertNotNull("record is not null", record);

    TagKey methodNameTagKey = clientSide
        ? RpcMeasureConstants.GRPC_CLIENT_METHOD
        : RpcMeasureConstants.GRPC_SERVER_METHOD;
    TagValue methodNameTag = record.tags.get(methodNameTagKey);
    assertNotNull("method name tagged", methodNameTag);
    assertEquals("method names match", methodName, methodNameTag.asString());

    TagKey statusTagKey = clientSide
        ? RpcMeasureConstants.GRPC_CLIENT_STATUS
        : RpcMeasureConstants.GRPC_SERVER_STATUS;
    TagValue statusTag = record.tags.get(statusTagKey);
    assertNotNull("status tagged", statusTag);
    assertEquals(status.toString(), statusTag.asString());
  }

  /**
   * Check information recorded by tracers.
   */
  private void checkTracers(
      TestStreamTracer tracer,
      Collection<? extends MessageLite> sentMessages,
      Collection<? extends MessageLite> receivedMessages) {
    long uncompressedSentSize = 0;
    int seqNo = 0;
    for (MessageLite msg : sentMessages) {
      assertThat(tracer.nextOutboundEvent())
          .isEqualTo(String.format(Locale.US, "outboundMessage(%d)", seqNo));
      assertThat(tracer.nextOutboundEvent()).matches(
          String.format(Locale.US, "outboundMessageSent\\(%d, -?[0-9]+, -?[0-9]+\\)", seqNo));
      seqNo++;
      uncompressedSentSize += msg.getSerializedSize();
    }
    assertNull(tracer.nextOutboundEvent());
    long uncompressedReceivedSize = 0;
    seqNo = 0;
    for (MessageLite msg : receivedMessages) {
      assertThat(tracer.nextInboundEvent())
          .isEqualTo(String.format(Locale.US, "inboundMessage(%d)", seqNo));
      assertThat(tracer.nextInboundEvent()).matches(
          String.format(Locale.US, "inboundMessageRead\\(%d, -?[0-9]+, -?[0-9]+\\)", seqNo));
      uncompressedReceivedSize += msg.getSerializedSize();
      seqNo++;
    }
    assertNull(tracer.nextInboundEvent());
    if (metricsExpected()) {
      assertEquals(uncompressedSentSize, tracer.getOutboundUncompressedSize());
      assertEquals(uncompressedReceivedSize, tracer.getInboundUncompressedSize());
    }
  }

  /**
   * Check information recorded by Census.
   */
  private void checkCensus(MetricsRecord record, boolean isServer,
      Collection<? extends MessageLite> requests, Collection<? extends MessageLite> responses) {
    int uncompressedRequestsSize = 0;
    for (MessageLite request : requests) {
      uncompressedRequestsSize += request.getSerializedSize();
    }
    int uncompressedResponsesSize = 0;
    for (MessageLite response : responses) {
      uncompressedResponsesSize += response.getSerializedSize();
    }
    if (isServer) {
      assertEquals(
          requests.size(),
          record.getMetricAsLongOrFail(RpcMeasureConstants.GRPC_SERVER_RECEIVED_MESSAGES_PER_RPC));
      assertEquals(
          responses.size(),
          record.getMetricAsLongOrFail(RpcMeasureConstants.GRPC_SERVER_SENT_MESSAGES_PER_RPC));
      assertEquals(
          uncompressedRequestsSize,
          record.getMetricAsLongOrFail(
              DeprecatedCensusConstants.RPC_SERVER_UNCOMPRESSED_REQUEST_BYTES));
      assertEquals(
          uncompressedResponsesSize,
          record.getMetricAsLongOrFail(
              DeprecatedCensusConstants.RPC_SERVER_UNCOMPRESSED_RESPONSE_BYTES));
      assertNotNull(record.getMetric(RpcMeasureConstants.GRPC_SERVER_SERVER_LATENCY));
      // It's impossible to get the expected wire sizes because it may be compressed, so we just
      // check if they are recorded.
      assertNotNull(record.getMetric(RpcMeasureConstants.GRPC_SERVER_RECEIVED_BYTES_PER_RPC));
      assertNotNull(record.getMetric(RpcMeasureConstants.GRPC_SERVER_SENT_BYTES_PER_RPC));
    } else {
      assertEquals(
          requests.size(),
          record.getMetricAsLongOrFail(RpcMeasureConstants.GRPC_CLIENT_SENT_MESSAGES_PER_RPC));
      assertEquals(
          responses.size(),
          record.getMetricAsLongOrFail(RpcMeasureConstants.GRPC_CLIENT_RECEIVED_MESSAGES_PER_RPC));
      assertEquals(
          uncompressedRequestsSize,
          record.getMetricAsLongOrFail(
              DeprecatedCensusConstants.RPC_CLIENT_UNCOMPRESSED_REQUEST_BYTES));
      assertEquals(
          uncompressedResponsesSize,
          record.getMetricAsLongOrFail(
              DeprecatedCensusConstants.RPC_CLIENT_UNCOMPRESSED_RESPONSE_BYTES));
      assertNotNull(record.getMetric(RpcMeasureConstants.GRPC_CLIENT_ROUNDTRIP_LATENCY));
      // It's impossible to get the expected wire sizes because it may be compressed, so we just
      // check if they are recorded.
      assertNotNull(record.getMetric(RpcMeasureConstants.GRPC_CLIENT_SENT_BYTES_PER_RPC));
      assertNotNull(record.getMetric(RpcMeasureConstants.GRPC_CLIENT_RECEIVED_BYTES_PER_RPC));
    }
  }

  // Helper methods for responses containing Payload since proto equals does not ignore deprecated
  // fields (PayloadType).
  private void assertResponses(
      Collection<StreamingOutputCallResponse> expected,
      Collection<StreamingOutputCallResponse> actual) {
    assertSame(expected.size(), actual.size());
    Iterator<StreamingOutputCallResponse> expectedIter = expected.iterator();
    Iterator<StreamingOutputCallResponse> actualIter = actual.iterator();

    while (expectedIter.hasNext()) {
      assertResponse(expectedIter.next(), actualIter.next());
    }
  }

  private void assertResponse(
      StreamingOutputCallResponse expected, StreamingOutputCallResponse actual) {
    if (expected == null || actual == null) {
      assertEquals(expected, actual);
    } else {
      assertPayload(expected.getPayload(), actual.getPayload());
    }
  }

  public void assertResponse(SimpleResponse expected, SimpleResponse actual) {
    assertPayload(expected.getPayload(), actual.getPayload());
    assertEquals(expected.getUsername(), actual.getUsername());
    assertEquals(expected.getOauthScope(), actual.getOauthScope());
  }

  private void assertPayload(Payload expected, Payload actual) {
    // Compare non deprecated fields in Payload, to make this test forward compatible.
    if (expected == null || actual == null) {
      assertEquals(expected, actual);
    } else {
      assertEquals(expected.getBody(), actual.getBody());
    }
  }

  /**
   * Captures the request attributes. Useful for testing ServerCalls.
   * {@link ServerCall#getAttributes()}
   */
  private static ServerInterceptor recordServerCallInterceptor(
      final AtomicReference<ServerCall<?, ?>> serverCallCapture) {
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call,
          Metadata requestHeaders,
          ServerCallHandler<ReqT, RespT> next) {
        serverCallCapture.set(call);
        return next.startCall(call, requestHeaders);
      }
    };
  }

  /**
   * Captures the request attributes. Useful for testing ClientCalls.
   * {@link ClientCall#getAttributes()}
   */
  private static ClientInterceptor recordClientCallInterceptor(
      final AtomicReference<ClientCall<?, ?>> clientCallCapture) {
    return new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        ClientCall<ReqT, RespT> clientCall = next.newCall(method,callOptions);
        clientCallCapture.set(clientCall);
        return clientCall;
      }
    };
  }

  private static ServerInterceptor recordContextInterceptor(
      final AtomicReference<Context> contextCapture) {
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call,
          Metadata requestHeaders,
          ServerCallHandler<ReqT, RespT> next) {
        contextCapture.set(Context.current());
        return next.startCall(call, requestHeaders);
      }
    };
  }

  /**
   * A marshaller that record input and output sizes.
   */
  private static final class ByteSizeMarshaller<T> implements MethodDescriptor.Marshaller<T> {

    private final MethodDescriptor.Marshaller<T> delegate;
    volatile int lastOutSize;
    volatile int lastInSize;

    ByteSizeMarshaller(MethodDescriptor.Marshaller<T> delegate) {
      this.delegate = delegate;
    }

    @Override
    public InputStream stream(T value) {
      InputStream is = delegate.stream(value);
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      try {
        lastOutSize = (int) ByteStreams.copy(is, baos);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return new ByteArrayInputStream(baos.toByteArray());
    }

    @Override
    public T parse(InputStream stream) {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      try {
        lastInSize = (int) ByteStreams.copy(stream, baos);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return delegate.parse(new ByteArrayInputStream(baos.toByteArray()));
    }
  }
}
