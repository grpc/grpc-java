/*
 * Copyright 2021 The gRPC Authors
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
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableMap;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.Deadline;
import io.grpc.Deadline.Ticker;
import io.grpc.IntegerMarshaller;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StringMarshaller;
import io.grpc.census.InternalCensusStatsAccessor;
import io.grpc.census.internal.DeprecatedCensusConstants;
import io.grpc.internal.FakeClock;
import io.grpc.internal.testing.StatsTestUtils.FakeStatsRecorder;
import io.grpc.internal.testing.StatsTestUtils.FakeTagContextBinarySerializer;
import io.grpc.internal.testing.StatsTestUtils.FakeTagger;
import io.grpc.internal.testing.StatsTestUtils.MetricsRecord;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.testing.GrpcCleanupRule;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.util.concurrent.ScheduledFuture;
import io.opencensus.contrib.grpc.metrics.RpcMeasureConstants;
import io.opencensus.stats.Measure;
import io.opencensus.stats.Measure.MeasureDouble;
import io.opencensus.stats.Measure.MeasureLong;
import io.opencensus.tags.TagValue;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class RetryTest {
  private static final FakeTagger tagger = new FakeTagger();
  private static final FakeTagContextBinarySerializer tagContextBinarySerializer =
      new FakeTagContextBinarySerializer();
  private static final MeasureLong RETRIES_PER_CALL =
      Measure.MeasureLong.create(
          "grpc.io/client/retries_per_call", "Number of retries per call", "1");
  private static final MeasureLong TRANSPARENT_RETRIES_PER_CALL =
      Measure.MeasureLong.create(
          "grpc.io/client/transparent_retries_per_call", "Transparent retries per call", "1");
  private static final MeasureDouble RETRY_DELAY_PER_CALL =
      Measure.MeasureDouble.create(
          "grpc.io/client/retry_delay_per_call", "Retry delay per call", "ms");

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();
  private final FakeClock fakeClock = new FakeClock();
  @Mock
  private ClientCall.Listener<Integer> mockCallListener;
  private CountDownLatch backoffLatch = new CountDownLatch(1);
  private final EventLoopGroup group = new DefaultEventLoopGroup() {
    @SuppressWarnings("FutureReturnValueIgnored")
    @Override
    public ScheduledFuture<?> schedule(
        final Runnable command, final long delay, final TimeUnit unit) {
      if (!command.getClass().getName().contains("RetryBackoffRunnable")) {
        return super.schedule(command, delay, unit);
      }
      fakeClock.getScheduledExecutorService().schedule(
          new Runnable() {
            @Override
            public void run() {
              group.execute(command);
            }
          },
          delay,
          unit);
      backoffLatch.countDown();
      return super.schedule(
          new Runnable() {
            @Override
            public void run() {} // no-op
          },
          0,
          TimeUnit.NANOSECONDS);
    }
  };
  private final FakeStatsRecorder clientStatsRecorder = new FakeStatsRecorder();
  private final ClientInterceptor statsInterceptor =
      InternalCensusStatsAccessor.getClientInterceptor(
          tagger, tagContextBinarySerializer, clientStatsRecorder,
          fakeClock.getStopwatchSupplier(), true, true, true,
          /* recordRealTimeMetrics= */ true, /* recordRetryMetrics= */ true);
  private final MethodDescriptor<String, Integer> clientStreamingMethod =
      MethodDescriptor.<String, Integer>newBuilder()
          .setType(MethodType.CLIENT_STREAMING)
          .setFullMethodName("service/method")
          .setRequestMarshaller(new StringMarshaller())
          .setResponseMarshaller(new IntegerMarshaller())
          .build();
  private final LinkedBlockingQueue<ServerCall<String, Integer>> serverCalls =
      new LinkedBlockingQueue<>();
  private final ServerMethodDefinition<String, Integer> methodDefinition =
      ServerMethodDefinition.create(
          clientStreamingMethod,
          new ServerCallHandler<String, Integer>() {
            @Override
            public Listener<String> startCall(ServerCall<String, Integer> call, Metadata headers) {
              serverCalls.offer(call);
              return new Listener<String>() {};
            }
          }
  );
  private final ServerServiceDefinition serviceDefinition =
      ServerServiceDefinition.builder(clientStreamingMethod.getServiceName())
          .addMethod(methodDefinition)
          .build();
  private final LocalAddress localAddress = new LocalAddress(this.getClass().getName());
  private Server localServer;
  private ManagedChannel channel;
  private Map<String, Object> retryPolicy = null;
  private long bufferLimit = 1L << 20; // 1M

  private void startNewServer() throws Exception {
    localServer = cleanupRule.register(NettyServerBuilder.forAddress(localAddress)
        .channelType(LocalServerChannel.class)
        .bossEventLoopGroup(group)
        .workerEventLoopGroup(group)
        .addService(serviceDefinition)
        .build());
    localServer.start();
  }

  private void createNewChannel() {
    Map<String, Object> methodConfig = new HashMap<>();
    Map<String, Object> name = new HashMap<>();
    name.put("service", "service");
    methodConfig.put("name", Arrays.<Object>asList(name));
    if (retryPolicy != null) {
      methodConfig.put("retryPolicy", retryPolicy);
    }
    Map<String, Object> rawServiceConfig = new HashMap<>();
    rawServiceConfig.put("methodConfig", Arrays.<Object>asList(methodConfig));
    channel = cleanupRule.register(
        NettyChannelBuilder.forAddress(localAddress)
            .channelType(LocalChannel.class)
            .eventLoopGroup(group)
            .usePlaintext()
            .enableRetry()
            .perRpcBufferLimit(bufferLimit)
            .defaultServiceConfig(rawServiceConfig)
            .intercept(statsInterceptor)
            .build());
  }

  private void elapseBackoff(long time, TimeUnit unit) throws Exception {
    assertThat(backoffLatch.await(5, SECONDS)).isTrue();
    backoffLatch = new CountDownLatch(1);
    fakeClock.forwardTime(time, unit);
  }

  private void assertRpcStartedRecorded() throws Exception {
    MetricsRecord record = clientStatsRecorder.pollRecord(5, SECONDS);
    assertThat(record.getMetricAsLongOrFail(RpcMeasureConstants.GRPC_CLIENT_STARTED_RPCS))
        .isEqualTo(1);
  }

  private void assertOutboundMessageRecorded() throws Exception {
    MetricsRecord record = clientStatsRecorder.pollRecord(5, SECONDS);
    assertThat(
            record.getMetricAsLongOrFail(
                RpcMeasureConstants.GRPC_CLIENT_SENT_MESSAGES_PER_METHOD))
        .isEqualTo(1);
  }

  private void assertInboundMessageRecorded() throws Exception {
    MetricsRecord record = clientStatsRecorder.pollRecord(5, SECONDS);
    assertThat(
            record.getMetricAsLongOrFail(
                RpcMeasureConstants.GRPC_CLIENT_RECEIVED_MESSAGES_PER_METHOD))
        .isEqualTo(1);
  }

  private void assertOutboundWireSizeRecorded(long length) throws Exception {
    MetricsRecord record = clientStatsRecorder.pollRecord(5, SECONDS);
    assertThat(record.getMetricAsLongOrFail(RpcMeasureConstants.GRPC_CLIENT_SENT_BYTES_PER_METHOD))
        .isEqualTo(length);
  }

  private void assertInboundWireSizeRecorded(long length) throws Exception {
    MetricsRecord record = clientStatsRecorder.pollRecord(5, SECONDS);
    assertThat(
            record.getMetricAsLongOrFail(RpcMeasureConstants.GRPC_CLIENT_RECEIVED_BYTES_PER_METHOD))
        .isEqualTo(length);
  }

  private void assertRpcStatusRecorded(
      Status.Code code, long roundtripLatencyMs, long outboundMessages) throws Exception {
    MetricsRecord record = clientStatsRecorder.pollRecord(5, SECONDS);
    TagValue statusTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_STATUS);
    assertThat(statusTag.asString()).isEqualTo(code.toString());
    assertThat(record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_FINISHED_COUNT))
        .isEqualTo(1);
    assertThat(record.getMetricAsLongOrFail(RpcMeasureConstants.GRPC_CLIENT_ROUNDTRIP_LATENCY))
        .isEqualTo(roundtripLatencyMs);
    assertThat(record.getMetricAsLongOrFail(RpcMeasureConstants.GRPC_CLIENT_SENT_MESSAGES_PER_RPC))
        .isEqualTo(outboundMessages);
  }

  private void assertRetryStatsRecorded(
      int numRetries, int numTransparentRetries, long retryDelayMs) throws Exception {
    MetricsRecord record = clientStatsRecorder.pollRecord(5, SECONDS);
    assertThat(record.getMetricAsLongOrFail(RETRIES_PER_CALL)).isEqualTo(numRetries);
    assertThat(record.getMetricAsLongOrFail(TRANSPARENT_RETRIES_PER_CALL))
        .isEqualTo(numTransparentRetries);
    assertThat(record.getMetricAsLongOrFail(RETRY_DELAY_PER_CALL)).isEqualTo(retryDelayMs);
  }

  @Test
  public void retryUntilBufferLimitExceeded() throws Exception {
    String message = "String of length 20.";

    startNewServer();
    bufferLimit = message.length() * 2L - 1; // Can buffer no more than 1 message.
    retryPolicy = ImmutableMap.<String, Object>builder()
        .put("maxAttempts", 4D)
        .put("initialBackoff", "10s")
        .put("maxBackoff", "10s")
        .put("backoffMultiplier", 1D)
        .put("retryableStatusCodes", Arrays.<Object>asList("UNAVAILABLE"))
        .buildOrThrow();
    createNewChannel();
    ClientCall<String, Integer> call = channel.newCall(clientStreamingMethod, CallOptions.DEFAULT);
    call.start(mockCallListener, new Metadata());
    call.sendMessage(message);

    ServerCall<String, Integer> serverCall = serverCalls.poll(5, SECONDS);
    serverCall.request(2);
    // trigger retry
    serverCall.close(
        Status.UNAVAILABLE.withDescription("original attempt failed"),
        new Metadata());
    elapseBackoff(10, SECONDS);
    // 2nd attempt received
    serverCall = serverCalls.poll(5, SECONDS);
    serverCall.request(2);
    verify(mockCallListener, never()).onClose(any(Status.class), any(Metadata.class));
    // send one more message, should exceed buffer limit
    call.sendMessage(message);
    // let attempt fail
    serverCall.close(
        Status.UNAVAILABLE.withDescription("2nd attempt failed"),
        new Metadata());
    // no more retry
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(mockCallListener, timeout(5000)).onClose(statusCaptor.capture(), any(Metadata.class));
    assertThat(statusCaptor.getValue().getDescription()).contains("2nd attempt failed");
  }

  @Test
  public void statsRecorded() throws Exception {
    startNewServer();
    retryPolicy = ImmutableMap.<String, Object>builder()
        .put("maxAttempts", 4D)
        .put("initialBackoff", "10s")
        .put("maxBackoff", "10s")
        .put("backoffMultiplier", 1D)
        .put("retryableStatusCodes", Arrays.<Object>asList("UNAVAILABLE"))
        .buildOrThrow();
    createNewChannel();

    ClientCall<String, Integer> call = channel.newCall(clientStreamingMethod, CallOptions.DEFAULT);
    call.start(mockCallListener, new Metadata());
    assertRpcStartedRecorded();
    String message = "String of length 20.";
    call.sendMessage(message);
    assertOutboundMessageRecorded();
    ServerCall<String, Integer> serverCall = serverCalls.poll(5, SECONDS);
    serverCall.request(2);
    assertOutboundWireSizeRecorded(message.length());
    // original attempt latency
    fakeClock.forwardTime(1, SECONDS);
    // trigger retry
    serverCall.close(
        Status.UNAVAILABLE.withDescription("original attempt failed"),
        new Metadata());
    assertRpcStatusRecorded(Status.Code.UNAVAILABLE, 1000, 1);
    elapseBackoff(10, SECONDS);
    assertRpcStartedRecorded();
    assertOutboundMessageRecorded();
    serverCall = serverCalls.poll(5, SECONDS);
    serverCall.request(2);
    assertOutboundWireSizeRecorded(message.length());
    message = "new message";
    call.sendMessage(message);
    assertOutboundMessageRecorded();
    assertOutboundWireSizeRecorded(message.length());
    // retry attempt latency
    fakeClock.forwardTime(2, SECONDS);
    serverCall.sendHeaders(new Metadata());
    serverCall.sendMessage(3);
    serverCall.close(Status.OK, new Metadata());
    call.request(1);
    assertInboundMessageRecorded();
    assertInboundWireSizeRecorded(1);
    assertRpcStatusRecorded(Status.Code.OK, 12000, 2);
    assertRetryStatsRecorded(1, 0, 0);
  }

  @Test
  public void statsRecorde_callCancelledBeforeCommit() throws Exception {
    startNewServer();
    retryPolicy = ImmutableMap.<String, Object>builder()
        .put("maxAttempts", 4D)
        .put("initialBackoff", "10s")
        .put("maxBackoff", "10s")
        .put("backoffMultiplier", 1D)
        .put("retryableStatusCodes", Arrays.<Object>asList("UNAVAILABLE"))
        .buildOrThrow();
    createNewChannel();

    // We will have streamClosed return at a particular moment that we want.
    final CountDownLatch streamClosedLatch = new CountDownLatch(1);
    ClientStreamTracer.Factory streamTracerFactory = new ClientStreamTracer.Factory() {
      @Override
      public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
        return new ClientStreamTracer() {
          @Override
          public void streamClosed(Status status) {
            if (status.getCode().equals(Code.CANCELLED)) {
              try {
                streamClosedLatch.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("streamClosedLatch interrupted", e);
              }
            }
          }
        };
      }
    };
    ClientCall<String, Integer> call = channel.newCall(
        clientStreamingMethod, CallOptions.DEFAULT.withStreamTracerFactory(streamTracerFactory));
    call.start(mockCallListener, new Metadata());
    assertRpcStartedRecorded();
    fakeClock.forwardTime(5, SECONDS);
    String message = "String of length 20.";
    call.sendMessage(message);
    assertOutboundMessageRecorded();
    ServerCall<String, Integer> serverCall = serverCalls.poll(5, SECONDS);
    serverCall.request(2);
    assertOutboundWireSizeRecorded(message.length());
    // trigger retry
    serverCall.close(
        Status.UNAVAILABLE.withDescription("original attempt failed"),
        new Metadata());
    assertRpcStatusRecorded(Code.UNAVAILABLE, 5000, 1);
    elapseBackoff(10, SECONDS);
    assertRpcStartedRecorded();
    assertOutboundMessageRecorded();
    serverCall = serverCalls.poll(5, SECONDS);
    serverCall.request(2);
    assertOutboundWireSizeRecorded(message.length());
    fakeClock.forwardTime(7, SECONDS);
    // A noop substream will commit. But call is not yet closed.
    call.cancel("Cancelled before commit", null);
    // Let the netty substream listener be closed.
    streamClosedLatch.countDown();
    // The call listener is closed.
    verify(mockCallListener, timeout(5000)).onClose(any(Status.class), any(Metadata.class));
    assertRpcStatusRecorded(Code.CANCELLED, 17_000, 1);
    assertRetryStatsRecorded(1, 0, 0);
  }

  @Test
  public void serverCancelledAndClientDeadlineExceeded() throws Exception {
    startNewServer();
    createNewChannel();

    class CloseDelayedTracer extends ClientStreamTracer {
      @Override
      public void streamClosed(Status status) {
        fakeClock.forwardTime(10, SECONDS);
      }
    }

    class CloseDelayedTracerFactory extends ClientStreamTracer.Factory {
      @Override
      public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
        return new CloseDelayedTracer();
      }
    }

    CallOptions callOptions = CallOptions.DEFAULT
        .withDeadline(Deadline.after(
            10,
            SECONDS,
            new Ticker() {
              @Override
              public long nanoTime() {
                return fakeClock.getTicker().read();
              }
            }))
        .withStreamTracerFactory(new CloseDelayedTracerFactory());
    ClientCall<String, Integer> call = channel.newCall(clientStreamingMethod, callOptions);
    call.start(mockCallListener, new Metadata());
    assertRpcStartedRecorded();
    ServerCall<String, Integer> serverCall = serverCalls.poll(5, SECONDS);
    serverCall.close(Status.CANCELLED, new Metadata());
    assertRpcStatusRecorded(Code.DEADLINE_EXCEEDED, 10_000, 0);
    assertRetryStatsRecorded(0, 0, 0);
  }

  @Test
  public void transparentRetryStatsRecorded() throws Exception {
    startNewServer();
    createNewChannel();

    final AtomicBoolean originalAttemptFailed = new AtomicBoolean();
    class TransparentRetryTriggeringTracer extends ClientStreamTracer {

      @Override
      public void streamCreated(Attributes transportAttrs, Metadata metadata) {
        if (originalAttemptFailed.get()) {
          return;
        }
        // Send GOAWAY from server. The client may either receive GOAWAY or create the underlying
        // netty stream and write headers first, even we await server termination as below.
        // In the latter case, we rerun the test. We can also call localServer.shutdown() to trigger
        // GOAWAY, but it takes a lot longer time to gracefully shut down.
        localServer.shutdownNow();
        try {
          localServer.awaitTermination();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError(e);
        }
      }

      @Override
      public void streamClosed(Status status) {
        if (originalAttemptFailed.get()) {
          return;
        }
        originalAttemptFailed.set(true);
        try {
          startNewServer();
          channel.resetConnectBackoff();
        } catch (Exception e) {
          throw new AssertionError("local server can not be restarted", e);
        }
      }
    }

    class TransparentRetryTracerFactory extends ClientStreamTracer.Factory {
      @Override
      public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
        return new TransparentRetryTriggeringTracer();
      }
    }

    CallOptions callOptions = CallOptions.DEFAULT
        .withWaitForReady()
        .withStreamTracerFactory(new TransparentRetryTracerFactory());
    while (true) {
      ClientCall<String, Integer> call = channel.newCall(clientStreamingMethod, callOptions);
      call.start(mockCallListener, new Metadata());
      assertRpcStartedRecorded(); // original attempt
      MetricsRecord record = clientStatsRecorder.pollRecord(5, SECONDS);
      assertThat(record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_FINISHED_COUNT))
          .isEqualTo(1);
      TagValue statusTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_STATUS);
      if (statusTag.asString().equals(Code.UNAVAILABLE.toString())) {
        break;
      } else {
        // Due to race condition, GOAWAY is not received/processed before the stream is closed due
        // to connection error. Rerun the test.
        assertThat(statusTag.asString()).isEqualTo(Code.UNKNOWN.toString());
        assertRetryStatsRecorded(0, 0, 0);
        originalAttemptFailed.set(false);
      }
    }
    assertRpcStartedRecorded(); // retry attempt
    ServerCall<String, Integer> serverCall = serverCalls.poll(5, SECONDS);
    serverCall.close(Status.INVALID_ARGUMENT, new Metadata());
    assertRpcStatusRecorded(Code.INVALID_ARGUMENT, 0, 0);
    assertRetryStatsRecorded(0, 1, 0);
  }
}
