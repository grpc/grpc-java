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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.ByteStreams;
import io.grpc.CallOptions;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.inprocess.InProcessSocketAddress;
import io.grpc.stub.ClientCalls;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.util.ForwardingLoadBalancer;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Standalone telemetry harness and automated verification tests for Milestone 1 of the gRPC-Java
 * Attempt-Level RPC Delay Observability project (gRFC Proposal A121).
 *
 * <p>Verifies deterministic wrapper-based delay injection for both name resolution and load
 * balancing pick delays using in-process OpenTelemetry metric readers and span exporters.
 */
@RunWith(JUnit4.class)
public class A121DelayObservabilityWrapperTest {

  @Rule
  public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();

  private static final MethodDescriptor.Marshaller<String> MARSHALLER =
      new MethodDescriptor.Marshaller<String>() {
        @Override
        public InputStream stream(String value) {
          return new ByteArrayInputStream(value.getBytes(UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
          try {
            return new String(ByteStreams.toByteArray(stream), UTF_8);
          } catch (IOException ex) {
            throw new RuntimeException(ex);
          }
        }
      };

  private final MethodDescriptor<String, String> method =
      MethodDescriptor.<String, String>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setRequestMarshaller(MARSHALLER)
          .setResponseMarshaller(MARSHALLER)
          .setFullMethodName("test.service/method")
          .build();

  private InMemoryMetricReader metricReader;
  private InMemorySpanExporter spanExporter;
  private OpenTelemetrySdk sdk;
  private ScheduledExecutorService scheduler;
  private boolean originalEnableOtelTracing;

  @Before
  public void setUp() {
    originalEnableOtelTracing = GrpcOpenTelemetry.ENABLE_OTEL_TRACING;
    System.setProperty("GRPC_EXPERIMENTAL_ENABLE_DELAY_OBSERVABILITY", "true");
    scheduler = Executors.newSingleThreadScheduledExecutor();

    metricReader = InMemoryMetricReader.create();
    spanExporter = InMemorySpanExporter.create();

    SdkMeterProvider meterProvider = SdkMeterProvider.builder()
        .registerMetricReader(metricReader)
        .build();

    SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
        .build();

    sdk = OpenTelemetrySdk.builder()
        .setMeterProvider(meterProvider)
        .setTracerProvider(tracerProvider)
        .build();
  }

  @After
  public void tearDown() {
    GrpcOpenTelemetry.ENABLE_OTEL_TRACING = originalEnableOtelTracing;
    System.clearProperty("GRPC_EXPERIMENTAL_ENABLE_DELAY_OBSERVABILITY");
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
    if (sdk != null) {
      sdk.close();
    }
  }

  @Test
  @SuppressWarnings("deprecation")
  public void testNameResolutionDelayInjection_500ms() throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    ServerServiceDefinition serviceDef = ServerServiceDefinition.builder("test.service")
        .addMethod(method, new ServerCallHandler<String, String>() {
          @Override
          public ServerCall.Listener<String> startCall(
              ServerCall<String, String> call, Metadata headers) {
            call.sendHeaders(new Metadata());
            call.sendMessage("response");
            call.close(Status.OK, new Metadata());
            return new ServerCall.Listener<String>() {};
          }
        })
        .build();

    grpcCleanupRule.register(
        InProcessServerBuilder.forName(serverName).directExecutor()
            .addService(serviceDef).build().start());

    DelayInjectingNameResolverProvider nameResolverProvider =
        new DelayInjectingNameResolverProvider(serverName, 500L, scheduler);

    GrpcOpenTelemetry grpcOpenTelemetry = GrpcOpenTelemetry.newBuilder()
        .sdk(sdk)
        .enableMetrics(Arrays.asList(
            "grpc.client.attempt.duration",
            "grpc.client.call.delay.duration",
            "grpc.client.attempt.started"))
        .enableTracing(true)
        .addOptionalLabel("grpc.delay_type")
        .build();

    ManagedChannelBuilder<?> channelBuilder =
        InProcessChannelBuilder.forTarget("test://" + serverName)
            .nameResolverFactory(nameResolverProvider)
            .directExecutor();
    grpcOpenTelemetry.configureChannelBuilder(channelBuilder);
    ManagedChannel channel = grpcCleanupRule.register(channelBuilder.build());

    String response = ClientCalls.blockingUnaryCall(
        channel, method, CallOptions.DEFAULT, "request");
    assertThat(response).isEqualTo("response");

    // Assert Metric Names, Units, and Attribute Tags ("grpc.delay_type" = "resolving")
    OpenTelemetryAssertions.assertThat(metricReader.collectAllMetrics())
        .anySatisfy(metric -> {
          OpenTelemetryAssertions.assertThat(metric)
              .hasName("grpc.client.call.delay.duration")
              .hasUnit("s")
              .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(point -> {
                point.hasAttribute(AttributeKey.stringKey("grpc.delay_type"), "resolving");
              }));
        });

    boolean foundAndVerifiedMetric = false;
    for (MetricData metric : metricReader.collectAllMetrics()) {
      if ("grpc.client.call.delay.duration".equals(metric.getName())) {
        assertThat(metric.getUnit()).isEqualTo("s");
        for (HistogramPointData point : metric.getHistogramData().getPoints()) {
          if ("resolving".equals(
              point.getAttributes().get(AttributeKey.stringKey("grpc.delay_type")))) {
            assertThat(point.getSum()).isAtLeast(0.45);
            assertThat(point.getSum()).isAtMost(0.55);
            foundAndVerifiedMetric = true;
          }
        }
      }
    }
    assertThat(foundAndVerifiedMetric).isTrue();

    // Assert Span Attribute & Event Formatting
    List<SpanData> spans = spanExporter.getFinishedSpanItems();
    SpanData callDelaySpan = spans.stream()
        .filter(s -> "Call Delay".equals(s.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected 'Call Delay' span not found"));

    assertThat(callDelaySpan.getAttributes().get(AttributeKey.stringKey("grpc.delay_type")))
        .isEqualTo("resolving");

    long durationMillis = TimeUnit.NANOSECONDS.toMillis(
        callDelaySpan.getEndEpochNanos() - callDelaySpan.getStartEpochNanos());
    assertThat(durationMillis).isAtLeast(450L);
    assertThat(durationMillis).isAtMost(550L);

    boolean hasTransitionEvent = callDelaySpan.getEvents().stream()
        .anyMatch(e -> "Delay state transition".equals(e.getName()));
    assertThat(hasTransitionEvent).isTrue();
  }

  @Test
  @SuppressWarnings("deprecation")
  public void testLbPolicyPickDelayInjection_500ms() throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    ServerServiceDefinition serviceDef = ServerServiceDefinition.builder("test.service")
        .addMethod(method, new ServerCallHandler<String, String>() {
          @Override
          public ServerCall.Listener<String> startCall(
              ServerCall<String, String> call, Metadata headers) {
            call.sendHeaders(new Metadata());
            call.sendMessage("response");
            call.close(Status.OK, new Metadata());
            return new ServerCall.Listener<String>() {};
          }
        })
        .build();

    grpcCleanupRule.register(
        InProcessServerBuilder.forName(serverName).directExecutor()
            .addService(serviceDef).build().start());

    DelayInjectingNameResolverProvider nameResolverProvider =
        new DelayInjectingNameResolverProvider(serverName, 0L, scheduler);
    DelayInjectingLoadBalancerProvider lbProvider =
        new DelayInjectingLoadBalancerProvider(500L, scheduler);
    LoadBalancerRegistry.getDefaultRegistry().register(lbProvider);

    try {
      GrpcOpenTelemetry grpcOpenTelemetry = GrpcOpenTelemetry.newBuilder()
          .sdk(sdk)
          .enableMetrics(Arrays.asList(
              "grpc.client.attempt.duration",
              "grpc.client.attempt.delay.duration",
              "grpc.client.attempt.started"))
          .enableTracing(true)
          .addOptionalLabel("grpc.delay_type")
          .build();

      ManagedChannelBuilder<?> channelBuilder =
          InProcessChannelBuilder.forTarget("test://" + serverName)
              .nameResolverFactory(nameResolverProvider)
              .defaultLoadBalancingPolicy("delay_injecting_pick_first")
              .directExecutor();
      grpcOpenTelemetry.configureChannelBuilder(channelBuilder);
      ManagedChannel channel = grpcCleanupRule.register(channelBuilder.build());

      String response = ClientCalls.blockingUnaryCall(
          channel, method, CallOptions.DEFAULT, "request");
      assertThat(response).isEqualTo("response");

      // Assert Metric Names, Units, and Attribute Tags ("grpc.delay_type" = "connecting")
      OpenTelemetryAssertions.assertThat(metricReader.collectAllMetrics())
          .anySatisfy(metric -> {
            OpenTelemetryAssertions.assertThat(metric)
                .hasName("grpc.client.attempt.delay.duration")
                .hasUnit("s")
                .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(point -> {
                  point.hasAttribute(AttributeKey.stringKey("grpc.delay_type"), "connecting");
                }));
          });

      boolean foundAndVerifiedMetric = false;
      for (MetricData metric : metricReader.collectAllMetrics()) {
        if ("grpc.client.attempt.delay.duration".equals(metric.getName())) {
          assertThat(metric.getUnit()).isEqualTo("s");
          for (HistogramPointData point : metric.getHistogramData().getPoints()) {
            if ("connecting".equals(
                point.getAttributes().get(AttributeKey.stringKey("grpc.delay_type")))) {
              assertThat(point.getSum()).isAtLeast(0.45);
              assertThat(point.getSum()).isAtMost(0.55);
              foundAndVerifiedMetric = true;
            }
          }
        }
      }
      assertThat(foundAndVerifiedMetric).isTrue();

      // Assert Span Attribute & Event Formatting
      List<SpanData> spans = spanExporter.getFinishedSpanItems();
      SpanData attemptDelaySpan = spans.stream()
          .filter(s -> "Attempt Delay".equals(s.getName()))
          .findFirst()
          .orElseThrow(() -> new AssertionError("Expected 'Attempt Delay' span not found"));

      assertThat(attemptDelaySpan.getAttributes().get(AttributeKey.stringKey("grpc.delay_type")))
          .isEqualTo("connecting");

      long durationMillis = TimeUnit.NANOSECONDS.toMillis(
          attemptDelaySpan.getEndEpochNanos() - attemptDelaySpan.getStartEpochNanos());
      assertThat(durationMillis).isAtLeast(450L);
      assertThat(durationMillis).isAtMost(550L);

      boolean hasTransitionEvent = attemptDelaySpan.getEvents().stream()
          .anyMatch(e -> "Delay state transition".equals(e.getName()));
      assertThat(hasTransitionEvent).isTrue();
    } finally {
      LoadBalancerRegistry.getDefaultRegistry().deregister(lbProvider);
    }
  }

  /**
   * Custom NameResolverProvider that resolves to an InProcessSocketAddress after a configurable
   * delay.
   */
  public static final class DelayInjectingNameResolverProvider extends NameResolverProvider {
    private final String serverName;
    private final long delayMillis;
    private final ScheduledExecutorService scheduler;

    public DelayInjectingNameResolverProvider(
        String serverName, long delayMillis, ScheduledExecutorService scheduler) {
      this.serverName = serverName;
      this.delayMillis = delayMillis;
      this.scheduler = scheduler;
    }

    @Override
    public String getDefaultScheme() {
      return "test";
    }

    @Override
    protected boolean isAvailable() {
      return true;
    }

    @Override
    public int priority() {
      return 10;
    }

    @Override
    public Collection<Class<? extends SocketAddress>> getProducedSocketAddressTypes() {
      return Collections.singleton(InProcessSocketAddress.class);
    }

    @Override
    public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
      return new DelayInjectingNameResolver(
          serverName, delayMillis, scheduler, args.getSynchronizationContext());
    }
  }

  /**
   * Custom NameResolver that injects a deterministic delay before calling Listener2.onResult().
   */
  public static final class DelayInjectingNameResolver extends NameResolver {
    private final String serverName;
    private final long delayMillis;
    private final ScheduledExecutorService scheduler;
    private final SynchronizationContext syncContext;
    private Listener2 listener;

    public DelayInjectingNameResolver(
        String serverName, long delayMillis, ScheduledExecutorService scheduler,
        SynchronizationContext syncContext) {
      this.serverName = serverName;
      this.delayMillis = delayMillis;
      this.scheduler = scheduler;
      this.syncContext = syncContext;
    }

    @Override
    public String getServiceAuthority() {
      return serverName;
    }

    @Override
    public void shutdown() {}

    @Override
    public void refresh() {
      resolve();
    }

    @Override
    public void start(Listener2 listener) {
      this.listener = listener;
      resolve();
    }

    private void resolve() {
      final ResolutionResult result = ResolutionResult.newBuilder()
          .setAddressesOrError(StatusOr.fromValue(Collections.singletonList(
              new EquivalentAddressGroup(new InProcessSocketAddress(serverName)))))
          .build();
      if (delayMillis > 0) {
        scheduler.schedule(new Runnable() {
          @Override
          public void run() {
            syncContext.execute(new Runnable() {
              @Override
              public void run() {
                if (listener != null) {
                  listener.onResult(result);
                }
              }
            });
          }
        }, delayMillis, TimeUnit.MILLISECONDS);
      } else {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (listener != null) {
              listener.onResult(result);
            }
          }
        });
      }
    }
  }

  /**
   * Custom LoadBalancerProvider that wraps pick_first and injects a pick delay.
   */
  public static final class DelayInjectingLoadBalancerProvider extends LoadBalancerProvider {
    private final String policyName = "delay_injecting_pick_first";
    private final long delayMillis;
    private final ScheduledExecutorService scheduler;

    public DelayInjectingLoadBalancerProvider(
        long delayMillis, ScheduledExecutorService scheduler) {
      this.delayMillis = delayMillis;
      this.scheduler = scheduler;
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int getPriority() {
      return 5;
    }

    @Override
    public String getPolicyName() {
      return policyName;
    }

    @Override
    public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
      return new DelayInjectingLoadBalancer(helper, delayMillis, scheduler);
    }
  }

  /**
   * Custom LoadBalancer wrapping pick_first with delay injection.
   */
  public static final class DelayInjectingLoadBalancer extends ForwardingLoadBalancer {
    private final LoadBalancer delegate;

    public DelayInjectingLoadBalancer(
        LoadBalancer.Helper helper, long delayMillis, ScheduledExecutorService scheduler) {
      LoadBalancerProvider pickFirstProvider =
          LoadBalancerRegistry.getDefaultRegistry().getProvider("pick_first");
      this.delegate = pickFirstProvider.newLoadBalancer(
          new DelayInjectingLoadBalancerHelper(helper, delayMillis, scheduler));
    }

    @Override
    protected LoadBalancer delegate() {
      return delegate;
    }
  }

  /**
   * Custom LoadBalancer.Helper that injects a pick delay when state transitions to READY.
   */
  public static final class DelayInjectingLoadBalancerHelper
      extends ForwardingLoadBalancerHelper {
    private final LoadBalancer.Helper delegate;
    private final long delayMillis;
    private final ScheduledExecutorService scheduler;

    public DelayInjectingLoadBalancerHelper(
        LoadBalancer.Helper delegate, long delayMillis, ScheduledExecutorService scheduler) {
      this.delegate = delegate;
      this.delayMillis = delayMillis;
      this.scheduler = scheduler;
    }

    @Override
    protected LoadBalancer.Helper delegate() {
      return delegate;
    }

    @Override
    public void updateBalancingState(
        final ConnectivityState newState, final SubchannelPicker newPicker) {
      if (newState == ConnectivityState.READY) {
        delegate.updateBalancingState(ConnectivityState.CONNECTING, new SubchannelPicker() {
          @Override
          public PickResult pickSubchannel(PickSubchannelArgs args) {
            return PickResult.withNoResult("connecting", "injected LB pick delay");
          }
        });
        scheduler.schedule(new Runnable() {
          @Override
          public void run() {
            delegate.getSynchronizationContext().execute(new Runnable() {
              @Override
              public void run() {
                delegate.updateBalancingState(newState, newPicker);
              }
            });
          }
        }, delayMillis, TimeUnit.MILLISECONDS);
      } else {
        delegate.updateBalancingState(newState, newPicker);
      }
    }
  }
}
