/*
 * Copyright 2025 The gRPC Authors
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Function;
import com.google.protobuf.ByteString;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.testing.integration.Messages.Payload;
import io.grpc.testing.integration.Messages.SimpleRequest;
import io.grpc.testing.integration.Messages.SimpleResponse;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.HdrHistogram.Histogram;

/**
 * Shared implementation for rpc_soak and channel_soak. Unlike the tests in AbstractInteropTest,
 * these "test cases" are only intended to be run from the command line. They don't fit the regular
 * test patterns of AbstractInteropTest.
 * https://github.com/grpc/grpc/blob/master/doc/interop-test-descriptions.md#rpc_soak
 */
final class SoakClient {
  private static final Logger logger = Logger.getLogger(SoakClient.class.getName());

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

  private static class ThreadResults {
    private int threadFailures = 0;
    private int iterationsDone = 0;
    private Histogram latencies = new Histogram(4);

    public int getThreadFailures() {
      return threadFailures;
    }

    public int getIterationsDone() {
      return iterationsDone;
    }

    public Histogram getLatencies() {
      return latencies;
    }
  }

  private static SoakIterationResult performOneSoakIteration(
      TestServiceGrpc.TestServiceBlockingStub soakStub, int soakRequestSize, int soakResponseSize)
      throws InterruptedException {
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
  public static void performSoakTest(
      String serverUri,
      int soakIterations,
      int maxFailures,
      int maxAcceptablePerIterationLatencyMs,
      int minTimeMsBetweenRpcs,
      int overallTimeoutSeconds,
      int soakRequestSize,
      int soakResponseSize,
      int numThreads,
      ManagedChannel sharedChannel,
      Function<ManagedChannel, ManagedChannel> maybeCreateChannel)
      throws InterruptedException {
    if (soakIterations % numThreads != 0) {
      throw new IllegalArgumentException("soakIterations must be evenly divisible by numThreads.");
    }
    long startNs = System.nanoTime();
    Thread[] threads = new Thread[numThreads];
    int soakIterationsPerThread = soakIterations / numThreads;
    List<ThreadResults> threadResultsList = new ArrayList<>(numThreads);
    for (int i = 0; i < numThreads; i++) {
      threadResultsList.add(new ThreadResults());
    }
    for (int threadInd = 0; threadInd < numThreads; threadInd++) {
      final int currentThreadInd = threadInd;
      threads[threadInd] = new Thread(() -> {
        try {
          executeSoakTestInThread(
              soakIterationsPerThread,
              startNs,
              minTimeMsBetweenRpcs,
              soakRequestSize,
              soakResponseSize,
              maxAcceptablePerIterationLatencyMs,
              overallTimeoutSeconds,
              serverUri,
              threadResultsList.get(currentThreadInd),
              sharedChannel,
              maybeCreateChannel);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException("Thread interrupted: " + e.getMessage(), e);
        }
      });
      threads[threadInd].start();
    }
    for (Thread thread : threads) {
      thread.join();
    }

    int totalFailures = 0;
    int iterationsDone = 0;
    Histogram latencies = new Histogram(4);
    for (ThreadResults threadResult :threadResultsList) {
      totalFailures += threadResult.getThreadFailures();
      iterationsDone += threadResult.getIterationsDone();
      latencies.add(threadResult.getLatencies());
    }
    logger.info(
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
    sharedChannel.shutdownNow();
    sharedChannel.awaitTermination(10, TimeUnit.SECONDS);
  }

  private static void executeSoakTestInThread(
      int soakIterationsPerThread,
      long startNs,
      int minTimeMsBetweenRpcs,
      int soakRequestSize,
      int soakResponseSize,
      int maxAcceptablePerIterationLatencyMs,
      int overallTimeoutSeconds,
      String serverUri,
      ThreadResults threadResults,
      ManagedChannel sharedChannel,
      Function<ManagedChannel, ManagedChannel> maybeCreateChannel) throws InterruptedException {
    ManagedChannel currentChannel = sharedChannel;
    for (int i = 0; i < soakIterationsPerThread; i++) {
      if (System.nanoTime() - startNs >= TimeUnit.SECONDS.toNanos(overallTimeoutSeconds)) {
        break;
      }
      long earliestNextStartNs = System.nanoTime()
          + TimeUnit.MILLISECONDS.toNanos(minTimeMsBetweenRpcs);
      // recordClientCallInterceptor takes an AtomicReference.
      AtomicReference<ClientCall<?, ?>> soakThreadClientCallCapture = new AtomicReference<>();
      currentChannel = maybeCreateChannel.apply(currentChannel);
      TestServiceGrpc.TestServiceBlockingStub currentStub = TestServiceGrpc
          .newBlockingStub(currentChannel)
              .withInterceptors(recordClientCallInterceptor(soakThreadClientCallCapture));
      SoakIterationResult result = performOneSoakIteration(currentStub,
          soakRequestSize, soakResponseSize);
      SocketAddress peer = soakThreadClientCallCapture
          .get().getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
      StringBuilder logStr = new StringBuilder(
          String.format(
              Locale.US,
              "thread id: %d soak iteration: %d elapsed_ms: %d peer: %s server_uri: %s",
              Thread.currentThread().getId(),
              i, result.getLatencyMs(), peer != null ? peer.toString() : "null", serverUri));
      if (!result.getStatus().equals(Status.OK)) {
        threadResults.threadFailures++;
        logStr.append(String.format(" failed: %s", result.getStatus()));
        logger.warning(logStr.toString());
      } else if (result.getLatencyMs() > maxAcceptablePerIterationLatencyMs) {
        threadResults.threadFailures++;
        logStr.append(
            " exceeds max acceptable latency: " + maxAcceptablePerIterationLatencyMs);
        logger.warning(logStr.toString());
      } else {
        logStr.append(" succeeded");
        logger.info(logStr.toString());
      }
      threadResults.iterationsDone++;
      threadResults.getLatencies().recordValue(result.getLatencyMs());
      long remainingNs = earliestNextStartNs - System.nanoTime();
      if (remainingNs > 0) {
        TimeUnit.NANOSECONDS.sleep(remainingNs);
      }
    }
  }

  private static void assertResponse(SimpleResponse expected, SimpleResponse actual) {
    assertPayload(expected.getPayload(), actual.getPayload());
    assertEquals(expected.getUsername(), actual.getUsername());
    assertEquals(expected.getOauthScope(), actual.getOauthScope());
  }

  private static void assertPayload(Payload expected, Payload actual) {
    // Compare non deprecated fields in Payload, to make this test forward compatible.
    if (expected == null || actual == null) {
      assertEquals(expected, actual);
    } else {
      assertEquals(expected.getBody(), actual.getBody());
    }
  }

  /**
   * Captures the ClientCall. Useful for testing {@link ClientCall#getAttributes()}
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

}
