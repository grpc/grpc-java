/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.opentelemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.KnownLength;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.testing.GrpcCleanupRule;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Multi-threaded stress testing for OpenTelemetry metrics & tracing modules under concurrent
 * stream closure, name resolution failures, transport attempt failures, and cancellation mid-delay.
 */
@RunWith(JUnit4.class)
public class OpenTelemetryStressTest {
  @Rule
  public final OpenTelemetryRule openTelemetryTesting = OpenTelemetryRule.create();

  @Rule
  public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private static class StringInputStream extends InputStream implements KnownLength {
    final String string;

    StringInputStream(String string) {
      this.string = string;
    }

    @Override
    public int read() {
      throw new UnsupportedOperationException("Should not be called");
    }

    @Override
    public int available() throws IOException {
      return string == null ? 0 : string.length();
    }
  }

  private static final MethodDescriptor.Marshaller<String> MARSHALLER =
      new MethodDescriptor.Marshaller<String>() {
        @Override
        public InputStream stream(String value) {
          return new StringInputStream(value);
        }

        @Override
        public String parse(InputStream stream) {
          return ((StringInputStream) stream).string;
        }
      };

  private final MethodDescriptor<String, String> method =
      MethodDescriptor.<String, String>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setRequestMarshaller(MARSHALLER)
          .setResponseMarshaller(MARSHALLER)
          .setFullMethodName("stresstest.TestService/TestMethod")
          .build();

  private ExecutorService executor;

  @Before
  public void setUp() {
    System.setProperty("GRPC_EXPERIMENTAL_ENABLE_OTEL_TRACING", "true");
    System.setProperty("GRPC_EXPERIMENTAL_ENABLE_DELAY_OBSERVABILITY", "true");
    GrpcOpenTelemetry.ENABLE_OTEL_TRACING = true;
    executor = Executors.newFixedThreadPool(20);
  }

  @After
  public void tearDown() {
    System.clearProperty("GRPC_EXPERIMENTAL_ENABLE_OTEL_TRACING");
    System.clearProperty("GRPC_EXPERIMENTAL_ENABLE_DELAY_OBSERVABILITY");
    GrpcOpenTelemetry.ENABLE_OTEL_TRACING = false;
    executor.shutdownNow();
  }

  /**
   * Stress test 1: 200 concurrent calls failing at Name Resolution with Status.UNAVAILABLE.
   * Verifies zero unclosed spans and 100% metric recording accuracy.
   */
  @Test
  public void stressTest_concurrentNameResolutionFailures() throws Exception {
    NameResolverProvider failingProvider = new NameResolverProvider() {
      @Override
      public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        return new NameResolver() {
          @Override
          public String getServiceAuthority() {
            return "failing.authority";
          }

          @Override
          public void start(Listener2 listener) {
            listener.onError(
                Status.UNAVAILABLE.withDescription(
                    "Name resolution failed empirically"));
          }

          @Override
          public void shutdown() {}
        };
      }

      @Override
      protected boolean isAvailable() {
        return true;
      }

      @Override
      protected int priority() {
        return 5;
      }

      @Override
      public String getDefaultScheme() {
        return "stressfailingnr";
      }

      @Override
      public String getScheme() {
        return getDefaultScheme();
      }

      @Override
      public Collection<Class<? extends SocketAddress>> getProducedSocketAddressTypes() {
        return Collections.emptyList();
      }
    };

    NameResolverRegistry.getDefaultRegistry().register(failingProvider);

    try {
      GrpcOpenTelemetry otel = GrpcOpenTelemetry.newBuilder()
          .sdk(openTelemetryTesting.getOpenTelemetry())
          .build();

      String target = "stressfailingnr:///test.service";
      InProcessChannelBuilder channelBuilder = InProcessChannelBuilder.forTarget(target);
      otel.configureChannelBuilder(channelBuilder);
      ManagedChannel channel = grpcCleanup.register(channelBuilder.build());

      int totalCalls = 200;
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch doneLatch = new CountDownLatch(totalCalls);
      AtomicInteger unavailableCount = new AtomicInteger(0);

      for (int i = 0; i < totalCalls; i++) {
        executor.execute(() -> {
          try {
            startLatch.await();
            ClientCall<String, String> call = channel.newCall(method, CallOptions.DEFAULT);
            call.start(new ClientCall.Listener<String>() {
              @Override
              public void onClose(Status status, Metadata trailers) {
                if (status.getCode() == Status.Code.UNAVAILABLE) {
                  unavailableCount.incrementAndGet();
                }
                doneLatch.countDown();
              }
            }, new Metadata());
            call.sendMessage("request");
            call.halfClose();
            call.request(1);
          } catch (Exception e) {
            doneLatch.countDown();
          }
        });
      }

      startLatch.countDown();
      assertTrue("Calls did not complete in time", doneLatch.await(15, TimeUnit.SECONDS));
      assertEquals("All calls should fail with UNAVAILABLE", totalCalls, unavailableCount.get());

      // 1. Verify Spans: zero unclosed spans
      List<SpanData> spans = openTelemetryTesting.getSpans();
      assertThat(spans).isNotEmpty();
      for (SpanData span : spans) {
        assertTrue("Span " + span.getName() + " should be ended", span.hasEnded());
      }
      long clientSpanCount = spans.stream()
          .filter(s -> s.getName().equals("Sent.stresstest.TestService.TestMethod"))
          .count();
      assertEquals("Every call should have an ended client call span", totalCalls, clientSpanCount);

      // 2. Verify Metrics: 100% metric recording accuracy with status UNAVAILABLE
      List<MetricData> metrics = openTelemetryTesting.getMetrics();

      MetricData callDurationMetric = metrics.stream()
          .filter(m -> "grpc.client.call.duration".equals(m.getName()))
          .findFirst()
          .orElse(null);
      assertThat(callDurationMetric).isNotNull();

      AttributeKey<String> statusKey = AttributeKey.stringKey("grpc.status");
      long recordedCalls = callDurationMetric.getHistogramData().getPoints().stream()
          .filter(p -> "UNAVAILABLE".equals(p.getAttributes().get(statusKey)))
          .mapToLong(HistogramPointData::getCount)
          .sum();
      assertEquals(
          "grpc.client.call.duration count for status UNAVAILABLE", totalCalls, recordedCalls);

      MetricData attemptDurationMetric = metrics.stream()
          .filter(m -> "grpc.client.attempt.duration".equals(m.getName()))
          .findFirst()
          .orElse(null);
      assertThat(attemptDurationMetric).isNotNull();

      long recordedAttempts = attemptDurationMetric.getHistogramData().getPoints().stream()
          .filter(p -> "UNAVAILABLE".equals(p.getAttributes().get(statusKey)))
          .mapToLong(HistogramPointData::getCount)
          .sum();
      assertEquals(
          "grpc.client.attempt.duration count for status UNAVAILABLE",
          totalCalls,
          recordedAttempts);

    } finally {
      NameResolverRegistry.getDefaultRegistry().deregister(failingProvider);
    }
  }

  /**
   * Stress test 2: 200 concurrent calls failing during transport attempts with Status.UNAVAILABLE.
   * Verifies zero unclosed spans and 100% metric recording accuracy for call and attempt duration.
   */
  @Test
  public void stressTest_concurrentTransportFailures() throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    grpcCleanup.register(
        InProcessServerBuilder.forName(serverName)
            .addService(ServerServiceDefinition.builder("stresstest.TestService")
                .addMethod(method, new ServerCallHandler<String, String>() {
                  @Override
                  public ServerCall.Listener<String> startCall(
                      ServerCall<String, String> call, Metadata headers) {
                    call.close(
                        Status.UNAVAILABLE.withDescription(
                            "Transport failure empirically simulated"),
                        new Metadata());
                    return new ServerCall.Listener<String>() {};
                  }
                }).build())
            .build()
            .start());

    GrpcOpenTelemetry otel = GrpcOpenTelemetry.newBuilder()
        .sdk(openTelemetryTesting.getOpenTelemetry())
        .build();

    InProcessChannelBuilder channelBuilder = InProcessChannelBuilder.forName(serverName);
    otel.configureChannelBuilder(channelBuilder);
    ManagedChannel channel = grpcCleanup.register(channelBuilder.build());

    int totalCalls = 200;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(totalCalls);
    AtomicInteger unavailableCount = new AtomicInteger(0);

    for (int i = 0; i < totalCalls; i++) {
      executor.execute(() -> {
        try {
          startLatch.await();
          ClientCall<String, String> call = channel.newCall(method, CallOptions.DEFAULT);
          call.start(new ClientCall.Listener<String>() {
            @Override
            public void onClose(Status status, Metadata trailers) {
              if (status.getCode() == Status.Code.UNAVAILABLE) {
                unavailableCount.incrementAndGet();
              }
              doneLatch.countDown();
            }
          }, new Metadata());
          call.sendMessage("request");
          call.halfClose();
          call.request(1);
        } catch (Exception e) {
          doneLatch.countDown();
        }
      });
    }

    startLatch.countDown();
    assertTrue("Calls did not complete in time", doneLatch.await(15, TimeUnit.SECONDS));
    assertEquals("All calls should fail with UNAVAILABLE", totalCalls, unavailableCount.get());

    // 1. Verify Spans
    List<SpanData> spans = openTelemetryTesting.getSpans();
    assertThat(spans).isNotEmpty();
    for (SpanData span : spans) {
      assertTrue("Span " + span.getName() + " should be ended", span.hasEnded());
    }

    // 2. Verify Metrics
    List<MetricData> metrics = openTelemetryTesting.getMetrics();

    MetricData callDurationMetric = metrics.stream()
        .filter(m -> "grpc.client.call.duration".equals(m.getName()))
        .findFirst()
        .orElse(null);
    assertThat(callDurationMetric).isNotNull();

    AttributeKey<String> statusKey = AttributeKey.stringKey("grpc.status");
    long recordedCalls = callDurationMetric.getHistogramData().getPoints().stream()
        .filter(p -> "UNAVAILABLE".equals(p.getAttributes().get(statusKey)))
        .mapToLong(HistogramPointData::getCount)
        .sum();
    assertEquals(
        "grpc.client.call.duration count for status UNAVAILABLE", totalCalls, recordedCalls);

    MetricData attemptDurationMetric = metrics.stream()
        .filter(m -> "grpc.client.attempt.duration".equals(m.getName()))
        .findFirst()
        .orElse(null);
    assertThat(attemptDurationMetric).isNotNull();

    long recordedAttempts = attemptDurationMetric.getHistogramData().getPoints().stream()
        .filter(p -> "UNAVAILABLE".equals(p.getAttributes().get(statusKey)))
        .mapToLong(HistogramPointData::getCount)
        .sum();
    assertEquals(
        "grpc.client.attempt.duration count for status UNAVAILABLE",
        totalCalls,
        recordedAttempts);
  }

  /**
   * Stress test 3: Concurrent calls cancelled mid-delay (delayed name resolution).
   * Verifies activeCallDelaySpan and activeAttemptDelaySpan are ended with zero span leaks.
   */
  @Test
  public void stressTest_concurrentCancellationMidDelay() throws Exception {
    List<NameResolver.Listener2> listeners = Collections.synchronizedList(new ArrayList<>());

    NameResolverProvider delayedProvider = new NameResolverProvider() {
      @Override
      public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        return new NameResolver() {
          @Override
          public String getServiceAuthority() {
            return "delayed.authority";
          }

          @Override
          public void start(Listener2 listener) {
            listeners.add(listener);
          }

          @Override
          public void shutdown() {}
        };
      }

      @Override
      protected boolean isAvailable() {
        return true;
      }

      @Override
      protected int priority() {
        return 5;
      }

      @Override
      public String getDefaultScheme() {
        return "stressdelaynr";
      }

      @Override
      public String getScheme() {
        return getDefaultScheme();
      }

      @Override
      public Collection<Class<? extends SocketAddress>> getProducedSocketAddressTypes() {
        return Collections.emptyList();
      }
    };

    NameResolverRegistry.getDefaultRegistry().register(delayedProvider);

    try {
      GrpcOpenTelemetry otel = GrpcOpenTelemetry.newBuilder()
          .sdk(openTelemetryTesting.getOpenTelemetry())
          .build();

      String target = "stressdelaynr:///test.service";
      InProcessChannelBuilder channelBuilder = InProcessChannelBuilder.forTarget(target);
      otel.configureChannelBuilder(channelBuilder);
      ManagedChannel channel = grpcCleanup.register(channelBuilder.build());

      int totalCalls = 100;
      CountDownLatch callStartedLatch = new CountDownLatch(totalCalls);
      CountDownLatch doneLatch = new CountDownLatch(totalCalls);
      AtomicInteger cancelledCount = new AtomicInteger(0);

      List<ClientCall<String, String>> calls = Collections.synchronizedList(new ArrayList<>());

      for (int i = 0; i < totalCalls; i++) {
        executor.execute(() -> {
          ClientCall<String, String> call = channel.newCall(method, CallOptions.DEFAULT);
          calls.add(call);
          call.start(new ClientCall.Listener<String>() {
            @Override
            public void onClose(Status status, Metadata trailers) {
              if (status.getCode() == Status.Code.CANCELLED) {
                cancelledCount.incrementAndGet();
              }
              doneLatch.countDown();
            }
          }, new Metadata());
          callStartedLatch.countDown();
        });
      }

      assertTrue(
          "Calls should start and enter delayed NR",
          callStartedLatch.await(5, TimeUnit.SECONDS));

      // Concurrently cancel all calls while they are in delay state
      CountDownLatch cancelStartLatch = new CountDownLatch(1);
      CountDownLatch cancelDoneLatch = new CountDownLatch(totalCalls);

      for (int i = 0; i < totalCalls; i++) {
        final int index = i;
        executor.execute(() -> {
          try {
            cancelStartLatch.await();
            calls.get(index).cancel("Concurrent cancel mid-delay test", null);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            cancelDoneLatch.countDown();
          }
        });
      }

      cancelStartLatch.countDown();
      assertTrue("Cancellations should complete", cancelDoneLatch.await(5, TimeUnit.SECONDS));
      assertTrue("All calls should close", doneLatch.await(10, TimeUnit.SECONDS));
      assertEquals("All calls should close with CANCELLED", totalCalls, cancelledCount.get());

      // Finish name resolution for cleanup
      for (NameResolver.Listener2 listener : listeners) {
        listener.onError(Status.UNAVAILABLE.withDescription("Cleaned up"));
      }

      // Verify zero unclosed spans
      List<SpanData> spans = openTelemetryTesting.getSpans();
      assertThat(spans).isNotEmpty();
      for (SpanData span : spans) {
        assertTrue("Span " + span.getName() + " should be ended", span.hasEnded());
      }

      // Check delay spans specifically ("Call Delay" or "Attempt Delay")
      for (SpanData span : spans) {
        if (span.getName().contains("Delay")) {
          assertTrue("Delay span " + span.getName() + " must be ended", span.hasEnded());
        }
      }

    } finally {
      NameResolverRegistry.getDefaultRegistry().deregister(delayedProvider);
    }
  }
}
