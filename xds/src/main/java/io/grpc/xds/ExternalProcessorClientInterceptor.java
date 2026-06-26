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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;
import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.envoyproxy.envoy.config.core.v3.HeaderMap;
import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode;
import io.envoyproxy.envoy.service.ext_proc.v3.BodyMutation;
import io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.CommonResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorGrpc;
import io.envoyproxy.envoy.service.ext_proc.v3.HeaderMutation;
import io.envoyproxy.envoy.service.ext_proc.v3.HttpBody;
import io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders;
import io.envoyproxy.envoy.service.ext_proc.v3.HttpTrailers;
import io.envoyproxy.envoy.service.ext_proc.v3.ImmediateResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.ProtocolConfiguration;
import io.envoyproxy.envoy.service.ext_proc.v3.StreamedBodyResponse;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.DoubleHistogramMetricInstrument;
import io.grpc.Drainable;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.KnownLength;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MetricInstrumentRegistry;
import io.grpc.MetricRecorder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.internal.DelayedClientCall;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.SerializingExecutor;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.xds.ExternalProcessorFilter.ExternalProcessorFilterConfig;
import io.grpc.xds.ExternalProcessorFilter.HeaderForwardingRulesConfig;
import io.grpc.xds.Filter.FilterContext;
import io.grpc.xds.internal.grpcservice.CachedChannelManager;
import io.grpc.xds.internal.grpcservice.HeaderValue;
import io.grpc.xds.internal.grpcservice.HeaderValueValidationUtils;
import io.grpc.xds.internal.headermutations.HeaderMutationDisallowedException;
import io.grpc.xds.internal.headermutations.HeaderMutationFilter;
import io.grpc.xds.internal.headermutations.HeaderMutationRulesConfig;
import io.grpc.xds.internal.headermutations.HeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderMutator;
import io.grpc.xds.internal.headermutations.HeaderValueOption;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Client-side interceptor for external processing filter.
 */
final class ExternalProcessorClientInterceptor implements ClientInterceptor {



  @VisibleForTesting
  static final DoubleHistogramMetricInstrument clientHeadersDuration;
  @VisibleForTesting
  static final DoubleHistogramMetricInstrument clientHalfCloseDuration;
  @VisibleForTesting
  static final DoubleHistogramMetricInstrument serverHeadersDuration;
  @VisibleForTesting
  static final DoubleHistogramMetricInstrument serverTrailersDuration;

  // Copied from io.grpc.opentelemetry.internal.OpenTelemetryConstants.LATENCY_BUCKETS
  private static final List<Double> LATENCY_BUCKETS = ImmutableList.of(
      0d,     0.00001d, 0.00005d, 0.0001d, 0.0003d, 0.0006d, 0.0008d, 0.001d, 0.002d,
      0.003d, 0.004d,   0.005d,   0.006d,  0.008d,  0.01d,   0.013d,  0.016d, 0.02d,
      0.025d, 0.03d,    0.04d,    0.05d,   0.065d,  0.08d,   0.1d,    0.13d,  0.16d,
      0.2d,   0.25d,    0.3d,     0.4d,    0.5d,    0.65d,   0.8d,    1d,     2d,
      5d,     10d,      20d,      50d,     100d);

  static {
    if (GrpcUtil.getFlag("GRPC_EXPERIMENTAL_XDS_EXT_PROC_ON_CLIENT", false)) {
      MetricInstrumentRegistry registry = MetricInstrumentRegistry.getDefaultRegistry();

      clientHeadersDuration = registry.registerDoubleHistogram(
          "grpc.client_ext_proc.client_headers_duration",
          "Time between when the ext_proc filter sees the client's headers and when "
              + "it allows those headers to continue on to the next filter",
          "s",
          LATENCY_BUCKETS,
          ImmutableList.of("grpc.target"),
          ImmutableList.of("grpc.lb.backend_service"),
          true);

      clientHalfCloseDuration = registry.registerDoubleHistogram(
          "grpc.client_ext_proc.client_half_close_duration",
          "Time between when the ext_proc filter sees the client's half-close and when "
              + "it allows that half-close to continue on to the next filter",
          "s",
          LATENCY_BUCKETS,
          ImmutableList.of("grpc.target"),
          ImmutableList.of("grpc.lb.backend_service"),
          true);

      serverHeadersDuration = registry.registerDoubleHistogram(
          "grpc.client_ext_proc.server_headers_duration",
          "Time between when the ext_proc filter sees the server's headers and when "
              + "it allows those headers to continue on to the next filter",
          "s",
          LATENCY_BUCKETS,
          ImmutableList.of("grpc.target"),
          ImmutableList.of("grpc.lb.backend_service"),
          true);

      serverTrailersDuration = registry.registerDoubleHistogram(
          "grpc.client_ext_proc.server_trailers_duration",
          "Time between when the ext_proc filter sees the server's trailers and when "
              + "it allows those trailers to continue on to the next filter",
          "s",
          LATENCY_BUCKETS,
          ImmutableList.of("grpc.target"),
          ImmutableList.of("grpc.lb.backend_service"),
          true);
    } else {
      clientHeadersDuration = null;
      clientHalfCloseDuration = null;
      serverHeadersDuration = null;
      serverTrailersDuration = null;
    }
  }

  private final ExternalProcessorFilterConfig filterConfig;
  private final ScheduledExecutorService scheduler;
  private final MetricRecorder metricsRecorder;
  private final ManagedChannel extProcChannel;

  @VisibleForTesting
  ExternalProcessorClientInterceptor(ExternalProcessorFilterConfig filterConfig,
      CachedChannelManager cachedChannelManager,
      ScheduledExecutorService scheduler,
      FilterContext context) {
    this.filterConfig = filterConfig;
    checkNotNull(cachedChannelManager, "cachedChannelManager");
    this.scheduler = checkNotNull(scheduler, "scheduler");
    this.metricsRecorder = checkNotNull(context.metricsRecorder(), "metricsRecorder");
    this.extProcChannel = cachedChannelManager.getChannel(filterConfig.getGrpcServiceConfig());
  }

  @VisibleForTesting
  ExternalProcessorFilterConfig getFilterConfig() {
    return filterConfig;
  }

  @VisibleForTesting
  ManagedChannel getExtProcChannel() {
    return extProcChannel;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method,
      CallOptions callOptions,
      Channel next) {
    SerializingExecutor serializingExecutor = new SerializingExecutor(callOptions.getExecutor());
    
    ExternalProcessorGrpc.ExternalProcessorStub extProcStub = ExternalProcessorGrpc.newStub(
        extProcChannel)
        .withExecutor(serializingExecutor);
    
    if (filterConfig.getGrpcServiceConfig().timeout().isPresent()) {
      long timeoutNanos = filterConfig.getGrpcServiceConfig().timeout().get().toNanos();
      if (timeoutNanos > 0) {
        extProcStub = extProcStub.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS);
      }
    }
    if (filterConfig.getGrpcServiceConfig().initialMetadata() != null
        && !filterConfig.getGrpcServiceConfig().initialMetadata().isEmpty()) {
      Metadata extraHeaders = new Metadata();
      for (HeaderValue headerValue : filterConfig.getGrpcServiceConfig().initialMetadata()) {
        String key = headerValue.key();
        if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
          if (headerValue.rawValue().isPresent()) {
            Metadata.Key<byte[]> metadataKey =
                Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER);
            extraHeaders.put(metadataKey, headerValue.rawValue().get().toByteArray());
          }
        } else {
          if (headerValue.value().isPresent()) {
            Metadata.Key<String> metadataKey =
                Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
            extraHeaders.put(metadataKey, headerValue.value().get());
          }
        }
      }
      extProcStub = extProcStub.withInterceptors(
          io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(extraHeaders));
    }


    // The filter chain is preceded by RawMessageClientInterceptor, so ReqT and RespT are
    // InputStream.
    MethodDescriptor<InputStream, InputStream> rawMethod =
        (MethodDescriptor<InputStream, InputStream>) (MethodDescriptor<?, ?>) method;
    ClientCall<InputStream, InputStream> rawCall =
        (ClientCall<InputStream, InputStream>) (ClientCall<?, ?>)
            next.newCall(method, callOptions);

    // Create a local subclass instance to buffer outbound actions
    DataPlaneDelayedCall<InputStream, InputStream> delayedCall =
        new DataPlaneDelayedCall<>(
            serializingExecutor, scheduler, callOptions.getDeadline());

    DataPlaneClientCall dataPlaneCall = new DataPlaneClientCall(
        delayedCall, rawCall, extProcStub, filterConfig, filterConfig.getMutationRulesConfig(),
        scheduler, rawMethod, next, metricsRecorder, next.authority(),
        callOptions.getOption(XdsNameResolver.CLUSTER_SELECTION_KEY));

    return (ClientCall<ReqT, RespT>) (ClientCall<?, ?>) dataPlaneCall;
  }

  // --- SHARED UTILITY METHODS ---
  private static HeaderMap toHeaderMap(
      Metadata metadata, Optional<HeaderForwardingRulesConfig> forwardRules) {
    HeaderMap.Builder builder =
        HeaderMap.newBuilder();

    for (String key : metadata.keys()) {
      if (forwardRules.isPresent() && !forwardRules.get().isAllowed(key)) {
        continue;
      }
      // Map binary headers using raw_value
      if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
        Metadata.Key<byte[]> binKey = Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER);
        Iterable<byte[]> values = metadata.getAll(binKey);
        if (values != null) {
          for (byte[] binValue : values) {
            String base64Value = BaseEncoding.base64().encode(binValue);
            io.envoyproxy.envoy.config.core.v3.HeaderValue headerValue =
                io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
                    .setKey(key.toLowerCase(Locale.ROOT))
                    .setRawValue(ByteString.copyFromUtf8(base64Value))
                    .build();
            builder.addHeaders(headerValue);
          }
        }
      } else {
        Metadata.Key<String> asciiKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
        Iterable<String> values = metadata.getAll(asciiKey);
        if (values != null) {
          for (String value : values) {
            io.envoyproxy.envoy.config.core.v3.HeaderValue headerValue =
                io.envoyproxy.envoy.config.core.v3.HeaderValue.newBuilder()
                    .setKey(key.toLowerCase(Locale.ROOT))
                    .setRawValue(ByteString.copyFromUtf8(value))
                    .build();
            builder.addHeaders(headerValue);
          }
        }
      }
    }
    return builder.build();
  }

  private static ImmutableMap<String, Struct> collectAttributes(
      ImmutableList<String> requestedAttributes,
      MethodDescriptor<?, ?> method,
      Channel channel,
      Metadata headers) {
    if (requestedAttributes.isEmpty()) {
      return ImmutableMap.of();
    }
    ImmutableMap.Builder<String, Struct> attributes = ImmutableMap.builder();
    for (String attr : requestedAttributes) {
      switch (attr) {
        case "request.path":
        case "request.url_path":
          attributes.put(attr, encodeAttribute("/" + method.getFullMethodName()));
          break;
        case "request.host":
          attributes.put(attr, encodeAttribute(channel.authority()));
          break;
        case "request.method":
          attributes.put(attr, encodeAttribute("POST"));
          break;
        case "request.headers":
          attributes.put(attr, encodeHeaders(headers));
          break;
        case "request.referer":
          String referer = getHeaderValue(headers, "referer");
          if (referer != null) {
            attributes.put(attr, encodeAttribute(referer));
          }
          break;
        case "request.useragent":
          String ua = getHeaderValue(headers, "user-agent");
          if (ua != null) {
            attributes.put(attr, encodeAttribute(ua));
          }
          break;
        case "request.id":
          String id = getHeaderValue(headers, "x-request-id");
          if (id != null) {
            attributes.put(attr, encodeAttribute(id));
          }
          break;
        case "request.query":
          attributes.put(attr, encodeAttribute(""));
          break;
        default:
          // "Not set" attributes or unrecognized ones (already validated) are skipped.
          break;
      }
    }
    return attributes.buildOrThrow();
  }

  private static Struct encodeAttribute(String value) {
    return Struct.newBuilder()
        .putFields("", Value.newBuilder().setStringValue(value).build())
        .build();
  }

  private static Struct encodeHeaders(Metadata headers) {
    Struct.Builder builder = Struct.newBuilder();
    for (String key : headers.keys()) {
      String value = getHeaderValue(headers, key);
      if (value != null) {
        builder.putFields(key.toLowerCase(Locale.ROOT),
            Value.newBuilder().setStringValue(value).build());
      }
    }
    return builder.build();
  }

  @Nullable
  private static String getHeaderValue(Metadata headers, String headerName) {
    if (headerName.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
      Metadata.Key<byte[]> key = Metadata.Key.of(headerName, Metadata.BINARY_BYTE_MARSHALLER);
      Iterable<byte[]> values = headers.getAll(key);
      if (values == null) {
        return null;
      }
      List<String> encoded = new ArrayList<>();
      for (byte[] v : values) {
        encoded.add(BaseEncoding.base64().omitPadding().encode(v));
      }
      return Joiner.on(",").join(encoded);
    }
    Metadata.Key<String> key = Metadata.Key.of(headerName, Metadata.ASCII_STRING_MARSHALLER);
    Iterable<String> values = headers.getAll(key);
    return values == null ? null : Joiner.on(",").join(values);
  }

  /**
   * A local subclass to expose the protected constructor of DelayedClientCall.
   */
  private static class DataPlaneDelayedCall<ReqT, RespT> extends DelayedClientCall<ReqT, RespT> {
    DataPlaneDelayedCall(
        Executor executor, ScheduledExecutorService scheduler, @Nullable Deadline deadline) {
      super(executor, scheduler, deadline);
    }
  }

  /**
   * Handles the bidirectional stream with the External Processor.
   * Buffers the actual RPC start until the Ext Proc header response is received.
   */
  private static class DataPlaneClientCall 
      extends SimpleForwardingClientCall<InputStream, InputStream> {
    enum ExtProcStreamState {
      ACTIVE,
      DRAINING,
      COMPLETED,
      FAILED;

      boolean isCompleted() {
        return this == COMPLETED || this == FAILED;
      }

      boolean isFailed() {
        return this == FAILED;
      }

      boolean isDraining() {
        return this == DRAINING;
      }
    }

    enum DataPlaneCallState {
      IDLE,
      ACTIVE,
      CLOSED
    }

    private enum EventType {
      REQUEST_HEADERS,
      REQUEST_BODY,
      RESPONSE_HEADERS,
      RESPONSE_BODY,
      RESPONSE_TRAILERS
    }

    private final ExternalProcessorGrpc.ExternalProcessorStub stub;
    private final ExternalProcessorFilterConfig config;
    private final ClientCall<InputStream, InputStream> rawCall;
    private final DataPlaneDelayedCall<InputStream, InputStream> delayedCall;
    private final ScheduledExecutorService scheduler;
    private final Object streamLock = new Object();
    @Nullable private volatile EventType expectedRequestResponse;
    @Nullable private volatile EventType expectedResponseResponse;
    @Nullable private volatile ClientCallStreamObserver<ProcessingRequest>
        extProcClientCallRequestObserver;
    private final Queue<InputStream> pendingDrainingMessages =
        new ConcurrentLinkedQueue<>();
    @Nullable private volatile DataPlaneListener wrappedListener;
    private final HeaderMutationFilter mutationFilter;
    private final HeaderMutator mutator = HeaderMutator.create();
    private final AtomicInteger pendingRequests = new AtomicInteger(0);
    private final ProcessingMode currentProcessingMode;
    private final MethodDescriptor<?, ?> method;
    private final Channel channel;
    private final MetricRecorder metricsRecorder;
    private final String target;
    private final String backendService;
    private volatile Context callContext = Context.ROOT;

    private long clientHeadersStartNanos;
    private long clientHalfCloseStartNanos;
    private long serverHeadersStartNanos;
    private long serverTrailersStartNanos;

    private boolean protocolConfigSent = false;
    private ImmutableMap<String, Struct> collectedAttributes;
    private boolean requestAttributesSent = false;
    @Nullable private volatile Metadata requestHeaders;
    final AtomicReference<DataPlaneCallState> dataPlaneCallState =
        new AtomicReference<>(DataPlaneCallState.IDLE);
    final AtomicReference<ExtProcStreamState> extProcStreamState =
        new AtomicReference<>(ExtProcStreamState.ACTIVE);
    final AtomicBoolean passThroughMode = new AtomicBoolean(false);
    final AtomicBoolean requestSideClosed = new AtomicBoolean(false);
    final AtomicBoolean isProcessingTrailers = new AtomicBoolean(false);
    final AtomicBoolean pendingHalfClose = new AtomicBoolean(false);
    final AtomicBoolean bodyMessageSentToExtProc = new AtomicBoolean(false);

    protected DataPlaneClientCall(
        DataPlaneDelayedCall<InputStream, InputStream> delayedCall,
        ClientCall<InputStream, InputStream> rawCall,
        ExternalProcessorGrpc.ExternalProcessorStub stub,
        ExternalProcessorFilterConfig config,
        Optional<HeaderMutationRulesConfig> mutationRulesConfig,
        ScheduledExecutorService scheduler,
        MethodDescriptor<?, ?> method,
        Channel channel,
        MetricRecorder metricsRecorder,
        String target,
        String backendService) {
      super(delayedCall);
      this.delayedCall = delayedCall;
      this.rawCall = rawCall;
      this.stub = stub;
      this.config = config;
      this.currentProcessingMode = config.getExternalProcessor().getProcessingMode();
      this.mutationFilter = new HeaderMutationFilter(mutationRulesConfig);
      this.scheduler = scheduler;
      this.method = method;
      this.channel = channel;
      this.metricsRecorder = checkNotNull(metricsRecorder, "metricsRecorder");
      this.target = checkNotNull(target, "target");
      this.backendService = checkNotNull(backendService, "backendService");
    }


    boolean markExtProcStreamCompleted() {
      while (true) {
        ExtProcStreamState current = extProcStreamState.get();
        if (current == ExtProcStreamState.COMPLETED || current == ExtProcStreamState.FAILED) {
          return false;
        }
        if (extProcStreamState.compareAndSet(current, ExtProcStreamState.COMPLETED)) {
          return true;
        }
      }
    }

    boolean markExtProcStreamFailed() {
      while (true) {
        ExtProcStreamState current = extProcStreamState.get();
        if (current == ExtProcStreamState.COMPLETED || current == ExtProcStreamState.FAILED) {
          return false;
        }
        if (extProcStreamState.compareAndSet(current, ExtProcStreamState.FAILED)) {
          return true;
        }
      }
    }

    boolean markDataPlaneCallClosed() {
      while (true) {
        DataPlaneCallState current = dataPlaneCallState.get();
        if (current == DataPlaneCallState.CLOSED) {
          return false;
        }
        if (dataPlaneCallState.compareAndSet(current, DataPlaneCallState.CLOSED)) {
          return true;
        }
      }
    }

    private void activateCall() {
      if ((extProcStreamState.get() == ExtProcStreamState.FAILED
              && !config.getFailureModeAllow()
              && !config.getObservabilityMode())
          || !dataPlaneCallState.compareAndSet(
              DataPlaneCallState.IDLE, DataPlaneCallState.ACTIVE)) {
        return;
      }
      if (clientHeadersStartNanos > 0) {
        long durationNanos = System.nanoTime() - clientHeadersStartNanos;
        recordDuration(clientHeadersDuration, durationNanos);
        clientHeadersStartNanos = 0;
      }
      Runnable toRun = delayedCall.setCall(rawCall);
      if (toRun != null) {
        callContext.run(toRun);
      }
      drainPendingRequests();
      onReadyNotify();
    }

    private void recordDuration(DoubleHistogramMetricInstrument instrument, long durationNanos) {
      if (instrument != null) {
        double durationSecs = (double) durationNanos / 1_000_000_000.0;
        metricsRecorder.recordDoubleHistogram(
            instrument,
            durationSecs,
            ImmutableList.of(target),
            ImmutableList.of(backendService));
      }
    }

    /**
     * Validates whether the body response uses unsupported gRPC message compression.
     * If compression is unsupported, this method will cancel the call, transition the
     * stream to a failed state, send an error to the external processor, and return false.
     *
     * @param bodyResponse the response to validate
     * @return true if validation passes (compression is supported or not used),
     *     false if validation fails
     */
    private boolean validateCompressionSupport(BodyResponse bodyResponse) {
      if (bodyResponse.hasResponse() && bodyResponse.getResponse().hasBodyMutation()) {
        BodyMutation mutation = 
            bodyResponse.getResponse().getBodyMutation();
        if (mutation.hasStreamedResponse()
            && mutation.getStreamedResponse().getGrpcMessageCompressed()) {
          StatusRuntimeException ex = Status.UNAVAILABLE
              .withDescription("gRPC message compression not supported in ext_proc")
              .asRuntimeException();
          synchronized (streamLock) {
            if (!extProcStreamState.get().isCompleted()
                && extProcClientCallRequestObserver != null) {
              extProcClientCallRequestObserver.onError(ex);
            }
          }
          activateCall();
          markExtProcStreamFailed();
          delayedCall.cancel("gRPC message compression not supported in ext_proc", ex);
          closeExtProcStream();
          return false;
        }
      }
      return true;
    }

    private void applyHeaderMutations(Metadata metadata,
        HeaderMutation mutation)
        throws HeaderMutationDisallowedException {
      if (metadata == null) {
        return;
      }
      ImmutableList.Builder<HeaderValueOption> headersToModify = ImmutableList.builder();
      for (io.envoyproxy.envoy.config.core.v3.HeaderValueOption protoOption
          : mutation.getSetHeadersList()) {
        io.envoyproxy.envoy.config.core.v3.HeaderValue protoHeader = protoOption.getHeader();
        String key = protoHeader.getKey();
        HeaderValueValidationUtils.validateHeaderKey(key);

        ByteString rawBytes = protoHeader.getRawValue();
        if (rawBytes.isEmpty()) {
          rawBytes = ByteString.copyFromUtf8(protoHeader.getValue());
        }

        if (rawBytes.size() > HeaderValueValidationUtils.MAX_HEADER_LENGTH) {
          throw new IllegalArgumentException(
              "Header value length exceeds maximum allowed length: " + rawBytes.size());
        }

        HeaderValue headerValue;
        if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
          byte[] decodedBytes = BaseEncoding.base64().decode(rawBytes.toStringUtf8());
          headerValue = HeaderValue.create(key, ByteString.copyFrom(decodedBytes));
        } else {
          headerValue = HeaderValue.create(key, rawBytes.toStringUtf8());
        }
        headersToModify.add(HeaderValueOption.create(
            headerValue,
            HeaderValueOption.HeaderAppendAction.valueOf(protoOption.getAppendAction().name())));
      }

      ImmutableList.Builder<String> headersToRemove = ImmutableList.builder();
      for (String headerToRemove : mutation.getRemoveHeadersList()) {
        HeaderValueValidationUtils.validateHeaderKey(headerToRemove);
        headersToRemove.add(headerToRemove);
      }

      HeaderMutations mutations = HeaderMutations.create(
          headersToModify.build(),
          headersToRemove.build());

      HeaderMutations filteredMutations = mutationFilter.filter(mutations);
      mutator.applyMutations(filteredMutations, metadata);
    }

    @Override
    public void start(Listener<InputStream> responseListener, Metadata headers) {
      this.callContext = Context.current();
      clientHeadersStartNanos = System.nanoTime();
      this.requestHeaders = headers;
      this.wrappedListener = new DataPlaneListener(responseListener, rawCall, this);

      // DelayedClientCall.start will buffer the listener and headers until setCall is called.
      super.start(wrappedListener, headers);

      stub.process(new ClientResponseObserver<ProcessingRequest, ProcessingResponse>() {
        @Override
        public void beforeStart(ClientCallStreamObserver<ProcessingRequest> requestStream) {
          synchronized (streamLock) {
            extProcClientCallRequestObserver = requestStream;
          }
          requestStream.setOnReadyHandler(DataPlaneClientCall.this::onExtProcStreamReady);
        }

        @Override
        public void onNext(ProcessingResponse response) {
          try {
            if (config.getObservabilityMode()) {
              return;
            }

            if (response.hasImmediateResponse()) {
              if (config.getDisableImmediateResponse()) {
                internalOnError(Status.UNAVAILABLE
                    .withDescription(
                        "Immediate response is disabled but received from external processor")
                    .asRuntimeException());
                return;
              }
              handleImmediateResponse(response.getImmediateResponse(), wrappedListener);
              return;
            }

            if (response.hasRequestHeaders()) {
              EventType expected = expectedRequestResponse;
              if (expected == null || expected != EventType.REQUEST_HEADERS) {
                internalOnError(Status.UNAVAILABLE
                    .withDescription("Protocol error: received response out of order. Expected: " 
                        + expected + ", Received: REQUEST_HEADERS")
                    .asRuntimeException());
                return;
              }
              expectedRequestResponse = null;
            } else if (response.hasResponseHeaders()) {
              EventType expected = expectedResponseResponse;
              if (expected == null || expected != EventType.RESPONSE_HEADERS) {
                internalOnError(Status.UNAVAILABLE
                    .withDescription("Protocol error: received response out of order. Expected: " 
                        + expected + ", Received: RESPONSE_HEADERS")
                    .asRuntimeException());
                return;
              }
              expectedResponseResponse = null;
            } else if (response.hasResponseTrailers()) {
              EventType expected = expectedResponseResponse;
              if (expected == null || expected != EventType.RESPONSE_TRAILERS) {
                internalOnError(Status.UNAVAILABLE
                    .withDescription("Protocol error: received response out of order. Expected: " 
                        + expected + ", Received: RESPONSE_TRAILERS")
                    .asRuntimeException());
                return;
              }
              expectedResponseResponse = null;
            } else if (response.hasRequestBody()) {
              EventType expected = expectedRequestResponse;
              if (expected == EventType.REQUEST_HEADERS) {
                internalOnError(Status.UNAVAILABLE
                    .withDescription(
                        "Protocol error: received request_body before request_headers response.")
                    .asRuntimeException());
                return;
              }
            } else if (response.hasResponseBody()) {
              EventType expected = expectedResponseResponse;
              if (expected == EventType.RESPONSE_HEADERS) {
                internalOnError(Status.UNAVAILABLE
                    .withDescription(
                        "Protocol error: received response_body before headers response.")
                    .asRuntimeException());
                return;
              }
            }

            if (response.getRequestDrain()) {
              extProcStreamState.set(ExtProcStreamState.DRAINING);
              halfCloseExtProcStream();
              activateCall();
            }

            // 1. Client Headers
            if (response.hasRequestHeaders()) {
              if (response.getRequestHeaders().hasResponse()) {
                if (response.getRequestHeaders().getResponse().getStatus()
                    == CommonResponse.ResponseStatus.CONTINUE_AND_REPLACE) {
                  internalOnError(Status.UNAVAILABLE
                      .withDescription("CONTINUE_AND_REPLACE is not supported")
                      .asRuntimeException());
                  return;
                }
                applyHeaderMutations(
                    requestHeaders,
                    response.getRequestHeaders().getResponse().getHeaderMutation());
              }
              activateCall();
            }
            // 2. Client Message (Request Body)
            else if (response.hasRequestBody()) {
              if (validateCompressionSupport(response.getRequestBody())) {
                handleRequestBodyResponse(response.getRequestBody());
              }
            }
            // 4. Server Headers
            else if (response.hasResponseHeaders()) {
              if (response.getResponseHeaders().hasResponse()) {
                if (response.getResponseHeaders().getResponse().getStatus()
                    == CommonResponse.ResponseStatus.CONTINUE_AND_REPLACE) {
                  internalOnError(Status.UNAVAILABLE
                      .withDescription("CONTINUE_AND_REPLACE is not supported")
                      .asRuntimeException());
                  return;
                }
                Metadata target = wrappedListener.isTrailersOnly()
                    ? wrappedListener.getSavedTrailers() : wrappedListener.getSavedHeaders();
                applyHeaderMutations(
                    target, response.getResponseHeaders().getResponse().getHeaderMutation());
              }
              if (wrappedListener.isTrailersOnly()) {
                wrappedListener.proceedWithClose();
              } else {
                wrappedListener.proceedWithHeaders();
              }
            }
            // 5. Server Message (Response Body)
            else if (response.hasResponseBody()) {
              if (validateCompressionSupport(response.getResponseBody())) {
                handleResponseBodyResponse(response.getResponseBody(), wrappedListener);
              }
            }
            // 6. Response Trailers
            else if (response.hasResponseTrailers()) {
              if (response.getResponseTrailers().hasHeaderMutation()) {
                applyHeaderMutations(
                    wrappedListener.getSavedTrailers(),
                    response.getResponseTrailers().getHeaderMutation()
                );
              }
              wrappedListener.proceedWithClose();
            }

            checkEndOfStream(response);
          } catch (Throwable t) {
            internalOnError(t);
          }
        }

        @Override
        public void onError(Throwable t) {
          if (markExtProcStreamFailed()) {
            synchronized (streamLock) {
              extProcClientCallRequestObserver = null;
            }
            if (config.getObservabilityMode()
                || (config.getFailureModeAllow() && !bodyMessageSentToExtProc.get())) {
              handleFailOpen(wrappedListener);
            } else {
              String message = "External processor stream failed";
              delayedCall.cancel(message, t);
              wrappedListener.proceedWithClose();
            }
          }
        }

        @Override
        public void onCompleted() {
          if (markExtProcStreamCompleted()) {
            handleFailOpen(wrappedListener);
          }
        }
      });

      this.collectedAttributes = collectAttributes(
          config.getRequestAttributes(), method, channel, headers);

      boolean sendRequestHeaders =
          currentProcessingMode.getRequestHeaderMode() == ProcessingMode.HeaderSendMode.SEND
          || currentProcessingMode.getRequestHeaderMode()
              == ProcessingMode.HeaderSendMode.DEFAULT;

      if (sendRequestHeaders) {
        sendToExtProc(ProcessingRequest.newBuilder()
            .setRequestHeaders(HttpHeaders.newBuilder()
                .setHeaders(toHeaderMap(headers, config.getForwardRulesConfig()))
                .setEndOfStream(false)
                .build())
            .build());
      }

      if (config.getObservabilityMode() || !sendRequestHeaders) {
        activateCall();
      }
    }

    private void sendToExtProc(ProcessingRequest request) {
      synchronized (streamLock) {
        if (extProcStreamState.get().isCompleted()) {
          return;
        }
        
        if (request.hasRequestHeaders()) {
          expectedRequestResponse = EventType.REQUEST_HEADERS;
        } else if (request.hasResponseHeaders()) {
          expectedResponseResponse = EventType.RESPONSE_HEADERS;
        } else if (request.hasResponseTrailers()) {
          expectedResponseResponse = EventType.RESPONSE_TRAILERS;
        }

        ProcessingRequest requestToSend = request;
        if (!protocolConfigSent) {
          requestToSend = ProcessingRequest.newBuilder(requestToSend)
              .setProtocolConfig(ProtocolConfiguration.newBuilder()
                  .setRequestBodyMode(currentProcessingMode.getRequestBodyMode())
                  .setResponseBodyMode(currentProcessingMode.getResponseBodyMode())
                  .build())
              .build();
          protocolConfigSent = true;
        }

        boolean isClientServerMessage =
            requestToSend.hasRequestHeaders() || requestToSend.hasRequestBody();
        if (isClientServerMessage
            && !requestAttributesSent
            && collectedAttributes != null
            && !collectedAttributes.isEmpty()) {
          requestToSend = ProcessingRequest.newBuilder(requestToSend)
              .putAllAttributes(collectedAttributes)
              .build();
          requestAttributesSent = true;
        }

        if (config.getObservabilityMode()) {
          requestToSend = ProcessingRequest.newBuilder(requestToSend)
              .setObservabilityMode(true)
              .build();
        }

        extProcClientCallRequestObserver.onNext(requestToSend);
      }
    }

    private void onExtProcStreamReady() {
      drainPendingRequests();
      onReadyNotify();
    }

    private void drainPendingRequests() {
      int toRequest = pendingRequests.getAndSet(0);
      if (toRequest > 0) {
        super.request(toRequest);
      }
    }

    private void closeExtProcStream() {
      synchronized (streamLock) {
        if (markExtProcStreamCompleted()) {
          if (extProcClientCallRequestObserver != null) {
            extProcClientCallRequestObserver.onCompleted();
          }
        }
      }
    }

    private void internalOnError(Throwable t) {
      if (markExtProcStreamFailed()) {
        synchronized (streamLock) {
          if (extProcClientCallRequestObserver != null) {
            try {
              extProcClientCallRequestObserver.onError(t);
            } catch (Throwable ignored) {
              // Ignore exceptions during cancel/onError propagation
            }
            extProcClientCallRequestObserver = null;
          }
        }
        if (config.getObservabilityMode()
            || (config.getFailureModeAllow() && !bodyMessageSentToExtProc.get())) {
          handleFailOpen(wrappedListener);
        } else {
          String message = "External processor stream failed";
          delayedCall.cancel(message, t);
          wrappedListener.proceedWithClose();
        }
      }
    }

    private void halfCloseExtProcStream() {
      synchronized (streamLock) {
        if (!extProcStreamState.get().isCompleted() && extProcClientCallRequestObserver != null) {
          extProcClientCallRequestObserver.onCompleted();
        }
      }
    }

    private void onReadyNotify() {
      wrappedListener.onReadyNotify();
    }

    private boolean isSidecarReady() {
      ExtProcStreamState state = extProcStreamState.get();
      if (state.isCompleted()) {
        return true;
      }
      if (state.isDraining()) {
        return false;
      }
      synchronized (streamLock) {
        ClientCallStreamObserver<ProcessingRequest> observer = extProcClientCallRequestObserver;
        return observer != null && observer.isReady();
      }
    }

    @Override
    public boolean isReady() {
      if (passThroughMode.get()) {
        return super.isReady();
      }
      if (extProcStreamState.get().isCompleted()) {
        return super.isReady();
      }
      if (dataPlaneCallState.get() == DataPlaneCallState.IDLE && !config.getObservabilityMode()) {
        return false;
      }
      boolean sidecarReady = isSidecarReady();
      if (config.getObservabilityMode()) {
        return super.isReady() && sidecarReady;
      }
      return sidecarReady;
    }

    @Override
    public void request(int numMessages) {
      if (passThroughMode.get() || extProcStreamState.get().isCompleted()) {
        super.request(numMessages);
        return;
      }
      if (!isSidecarReady()) {
        pendingRequests.addAndGet(numMessages);
        return;
      }
      super.request(numMessages);
    }

    @Override
    public void sendMessage(InputStream message) {
      if (requestSideClosed.get()) {
        // External processor already closed the request stream. Discard further messages.
        return;
      }

      if (passThroughMode.get()) {
        super.sendMessage(message);
        return;
      }

      synchronized (streamLock) {
        if (passThroughMode.get()) {
          super.sendMessage(message);
          return;
        }

        ExtProcStreamState state = extProcStreamState.get();
        if (state.isDraining() || state.isCompleted()) {
          try {
            ByteString copiedBody = ByteString.readFrom(message);
            pendingDrainingMessages.add(new KnownLengthInputStream(copiedBody));
          } catch (IOException e) {
            rawCall.cancel("Failed to copy outbound message for buffering", e);
          }
          return;
        }
      }

      if (currentProcessingMode.getRequestBodyMode() == ProcessingMode.BodySendMode.NONE) {
        super.sendMessage(message);
        return;
      }

      // Mode is GRPC
      try {
        ByteString bodyByteString = outboundStreamToByteString(message);
        sendToExtProc(ProcessingRequest.newBuilder()
            .setRequestBody(HttpBody.newBuilder()
                .setBody(bodyByteString)
                .setEndOfStream(false)
                .build())
            .build());
        bodyMessageSentToExtProc.set(true);

        if (config.getObservabilityMode()) {
          super.sendMessage(new KnownLengthInputStream(bodyByteString));
        }
      } catch (IOException e) {
        rawCall.cancel("Failed to serialize message for External Processor", e);
      }
    }

    private void proceedWithHalfClose() {
      if (clientHalfCloseStartNanos > 0) {
        long durationNanos = System.nanoTime() - clientHalfCloseStartNanos;
        recordDuration(clientHalfCloseDuration, durationNanos);
        clientHalfCloseStartNanos = 0;
      }
      super.halfClose();
    }

    @Override
    public void halfClose() {
      clientHalfCloseStartNanos = System.nanoTime();
      if (passThroughMode.get() || extProcStreamState.get().isCompleted()) {
        if (requestSideClosed.compareAndSet(false, true)) {
          proceedWithHalfClose();
        }
        return;
      }

      pendingHalfClose.set(true);

      if (extProcStreamState.get().isDraining()) {
        return;
      }

      if (currentProcessingMode.getRequestBodyMode() == ProcessingMode.BodySendMode.NONE) {
        if (requestSideClosed.compareAndSet(false, true)) {
          proceedWithHalfClose();
        }
        return;
      }

      // Mode is GRPC
      sendToExtProc(ProcessingRequest.newBuilder()
          .setRequestBody(HttpBody.newBuilder()
              .setEndOfStreamWithoutMessage(true)
              .build())
          .build());
      
      // Defer super.halfClose() until ext-proc response signals end_of_stream.
    }

    @Override
    public void cancel(@Nullable String message, @Nullable Throwable cause) {
      synchronized (streamLock) {
        if (!extProcStreamState.get().isCompleted() && extProcClientCallRequestObserver != null) {
          extProcClientCallRequestObserver.onError(
              Status.CANCELLED
                  .withDescription(message)
                  .withCause(cause)
                  .asRuntimeException());
        }
      }
      super.cancel(message, cause);
    }

    private void handleRequestBodyResponse(BodyResponse bodyResponse) {
      if (bodyResponse.hasResponse() && bodyResponse.getResponse().hasBodyMutation()) {
        BodyMutation mutation = bodyResponse.getResponse().getBodyMutation();
        if (mutation.hasStreamedResponse()) {
          StreamedBodyResponse streamed = mutation.getStreamedResponse();
          if (!streamed.getEndOfStreamWithoutMessage()) {
            super.sendMessage(new KnownLengthInputStream(streamed.getBody()));
          }
          if (streamed.getEndOfStream() || streamed.getEndOfStreamWithoutMessage()) {
            if (requestSideClosed.compareAndSet(false, true)) {
              proceedWithHalfClose();
            }
          }
        }
      }
    }

    private void handleResponseBodyResponse(
        BodyResponse bodyResponse, DataPlaneListener listener) {
      if (bodyResponse.hasResponse() && bodyResponse.getResponse().hasBodyMutation()) {
        BodyMutation mutation = bodyResponse.getResponse().getBodyMutation();
        if (mutation.hasStreamedResponse()) {
          StreamedBodyResponse streamed = mutation.getStreamedResponse();
          listener.onExternalBody(streamed.getBody());
        }
      }
    }

    private void handleImmediateResponse(ImmediateResponse immediate, DataPlaneListener listener)
        throws HeaderMutationDisallowedException {
      Status status = Status.fromCodeValue(immediate.getGrpcStatus().getStatus());
      if (!immediate.getDetails().isEmpty()) {
        status = status.withDescription(immediate.getDetails());
      }

      Metadata trailers = new Metadata();
      if (immediate.hasHeaders()) {
        applyHeaderMutations(trailers, immediate.getHeaders());
      }

      listener.setImmediateResponse(status, trailers);

      if (isProcessingTrailers.get()) {
        // If sent in response to a server trailers event, sets the status and optionally
        // headers to be included in the trailers.
        listener.unblockAfterStreamComplete();
      } else {
        // If sent in response to any other event, it will cause the data plane RPC to
        // immediately fail with the specified status as if it were an out-of-band
        // cancellation.
        rawCall.cancel(status.getDescription(), null);
        listener.unblockAfterStreamComplete();
      }
      closeExtProcStream();
    }

    private void drainPendingDrainingMessages() {
      synchronized (streamLock) {
        InputStream msg;
        while ((msg = pendingDrainingMessages.poll()) != null) {
          super.sendMessage(msg);
        }
        passThroughMode.set(true);
        if (pendingHalfClose.get()) {
          if (requestSideClosed.compareAndSet(false, true)) {
            proceedWithHalfClose();
          }
        }
      }
    }

    private void handleFailOpen(DataPlaneListener listener) {
      activateCall();
      drainPendingRequests();
      listener.unblockAfterStreamComplete();
      closeExtProcStream();
    }

    private void checkEndOfStream(ProcessingResponse response) {
      boolean terminal = false;
      if (response.hasResponseTrailers()) {
        terminal = true;
      } else if (response.hasResponseHeaders() && wrappedListener.isTrailersOnly()) {
        terminal = true;
      }

      if (terminal) {
        wrappedListener.unblockAfterStreamComplete();
        closeExtProcStream();
      }
    }

    long getServerHeadersStartNanos() {
      return serverHeadersStartNanos;
    }

    void setServerHeadersStartNanos(long serverHeadersStartNanos) {
      this.serverHeadersStartNanos = serverHeadersStartNanos;
    }

    long getServerTrailersStartNanos() {
      return serverTrailersStartNanos;
    }

    void setServerTrailersStartNanos(long serverTrailersStartNanos) {
      this.serverTrailersStartNanos = serverTrailersStartNanos;
    }

    AtomicReference<ExtProcStreamState> getExtProcStreamState() {
      return extProcStreamState;
    }

    ProcessingMode getCurrentProcessingMode() {
      return currentProcessingMode;
    }

    AtomicBoolean getPassThroughMode() {
      return passThroughMode;
    }

    ExternalProcessorFilterConfig getConfig() {
      return config;
    }

    Context getCallContext() {
      return callContext;
    }

    ScheduledExecutorService getScheduler() {
      return scheduler;
    }

    AtomicBoolean getIsProcessingTrailers() {
      return isProcessingTrailers;
    }
  }

  private static class DataPlaneListener extends SimpleForwardingClientCallListener<InputStream> {
    private final ClientCall<?, ?> rawCall;
    private final DataPlaneClientCall dataPlaneClientCall;
    private final Queue<InputStream> savedMessages = new ConcurrentLinkedQueue<>();
    private boolean inboundPassThrough = false;
    @Nullable private volatile Metadata savedHeaders;
    @Nullable private volatile Metadata savedTrailers;
    @Nullable private volatile Status savedStatus;
    private final AtomicBoolean terminationTriggered = new AtomicBoolean(false);
    private final AtomicBoolean responseHeadersSent = new AtomicBoolean(false);
    private final AtomicBoolean trailersOnly = new AtomicBoolean(false);

    protected DataPlaneListener(
        ClientCall.Listener<InputStream> delegate,
        ClientCall<?, ?> rawCall,
        DataPlaneClientCall dataPlaneClientCall) {
      super(delegate);
      this.rawCall = rawCall;
      this.dataPlaneClientCall = dataPlaneClientCall;
    }

    boolean isTrailersOnly() {
      return trailersOnly.get();
    }

    Metadata getSavedHeaders() {
      return savedHeaders;
    }

    Metadata getSavedTrailers() {
      return savedTrailers;
    }

    void setImmediateResponse(Status status, Metadata trailers) {
      this.savedStatus = status;
      this.savedTrailers = trailers;
    }

    @Override
    public void onReady() {
      dataPlaneClientCall.drainPendingRequests();
      onReadyNotify();
    }

    @Override
    public void onHeaders(Metadata headers) {
      dataPlaneClientCall.setServerHeadersStartNanos(System.nanoTime());
      responseHeadersSent.set(true);
      if (dataPlaneClientCall.getExtProcStreamState().get().isDraining()) {
        this.savedHeaders = headers;
        return;
      }
      boolean sendResponseHeaders =
          dataPlaneClientCall.getCurrentProcessingMode().getResponseHeaderMode()
              == ProcessingMode.HeaderSendMode.SEND
          || dataPlaneClientCall.getCurrentProcessingMode().getResponseHeaderMode()
              == ProcessingMode.HeaderSendMode.DEFAULT;

      if (dataPlaneClientCall.getPassThroughMode().get() 
          || dataPlaneClientCall.getExtProcStreamState().get().isCompleted() 
          || !sendResponseHeaders) {
        proceedWithHeaders(headers);
        return;
      }

      this.savedHeaders = headers;
      dataPlaneClientCall.sendToExtProc(ProcessingRequest.newBuilder()
          .setResponseHeaders(HttpHeaders.newBuilder()
              .setHeaders(
                  toHeaderMap(headers, dataPlaneClientCall.getConfig().getForwardRulesConfig()))
              .build())
          .build());

      if (dataPlaneClientCall.getConfig().getObservabilityMode()) {
        proceedWithHeaders();
      }
    }

    @Override
    public void onMessage(InputStream message) {
      synchronized (savedMessages) {
        if (inboundPassThrough) {
          dataPlaneClientCall.getCallContext().run(() -> delegate().onMessage(message));
          return;
        }

        if (savedHeaders != null
            || dataPlaneClientCall.getExtProcStreamState().get().isDraining()) {
          try {
            ByteString copiedBody = ByteString.readFrom(message);
            savedMessages.add(new KnownLengthInputStream(copiedBody));
          } catch (IOException e) {
            rawCall.cancel("Failed to copy inbound message for buffering", e);
          }
          return;
        }
      }

      if (dataPlaneClientCall.getPassThroughMode().get()) {
        dataPlaneClientCall.getCallContext().run(() -> delegate().onMessage(message));
        return;
      }

      if (dataPlaneClientCall.getExtProcStreamState().get().isCompleted()
          || dataPlaneClientCall.getCurrentProcessingMode().getResponseBodyMode()
              != ProcessingMode.BodySendMode.GRPC) {
        dataPlaneClientCall.getCallContext().run(() -> delegate().onMessage(message));
        return;
      }

      try {
        ByteString bodyByteString = ByteString.readFrom(message);
        sendResponseBodyToExtProc(bodyByteString, false);
        dataPlaneClientCall.bodyMessageSentToExtProc.set(true);

        if (dataPlaneClientCall.getConfig().getObservabilityMode()) {
          // If needed, downstream reading can be made more optimal by creating a wrapped
          // Inputstream wraps the underlying bytestring and that implements HasByteBuffer,
          // Detachable, KnownLength
          dataPlaneClientCall.getCallContext().run(
              () -> delegate().onMessage(bodyByteString.newInput()));
        }
      } catch (IOException e) {
        rawCall.cancel("Failed to read server response", e);
      }
    }

    @Override
    public void onClose(Status status, Metadata trailers) {
      dataPlaneClientCall.setServerTrailersStartNanos(System.nanoTime());
      DataPlaneClientCall.ExtProcStreamState extProcStreamState =
          dataPlaneClientCall.getExtProcStreamState().get();
      if (extProcStreamState.isFailed()
          && !dataPlaneClientCall.getConfig().getObservabilityMode()
          && (!dataPlaneClientCall.getConfig().getFailureModeAllow()
              || dataPlaneClientCall.bodyMessageSentToExtProc.get())) {
        if (dataPlaneClientCall.markDataPlaneCallClosed()) {
          proceedWithClose(Status.INTERNAL.withDescription("External processor stream failed")
              .withCause(status.getCause()), new Metadata());
        }
        return;
      }
      if (dataPlaneClientCall.getPassThroughMode().get()) {
        if (dataPlaneClientCall.markDataPlaneCallClosed()) {
          proceedWithClose(status, trailers);
        }
        return;
      }

      if (this.savedStatus == null) {
        this.savedStatus = status;
        this.savedTrailers = trailers;
      }

      // If we are still waiting for the external processor to validate response headers,
      // buffer the close status/trailers and defer the close until headers are processed.
      if (savedHeaders != null) {
        return;
      }

      if (dataPlaneClientCall.getExtProcStreamState().get().isDraining()) {
        return;
      }

      if (!responseHeadersSent.get()) {
        trailersOnly.set(true);
      }

      triggerCloseHandshake();

      if (dataPlaneClientCall.getConfig().getObservabilityMode()) {
        proceedWithClose();
        @SuppressWarnings("unused")
        ScheduledFuture<?> unused = dataPlaneClientCall.getScheduler().schedule(
            dataPlaneClientCall::closeExtProcStream,
            dataPlaneClientCall.getConfig().getDeferredCloseTimeoutNanos(),
            TimeUnit.NANOSECONDS);
      }
    }

    void onReadyNotify() {
      dataPlaneClientCall.getCallContext().run(() -> delegate().onReady());
    }

    void proceedWithHeaders() {
      if (savedHeaders != null) {
        proceedWithHeaders(savedHeaders);
        synchronized (savedMessages) {
          savedHeaders = null;
          if (!dataPlaneClientCall.getExtProcStreamState().get().isDraining()) {
            InputStream msg;
            while ((msg = savedMessages.poll()) != null) {
              onMessage(msg);
            }
          }
        }
        onReadyNotify();
        if (savedStatus != null) {
          triggerCloseHandshake();
        }
      }
    }

    private void proceedWithHeaders(Metadata headers) {
      if (dataPlaneClientCall.getServerHeadersStartNanos() > 0) {
        long durationNanos = System.nanoTime() - dataPlaneClientCall.getServerHeadersStartNanos();
        dataPlaneClientCall.recordDuration(serverHeadersDuration, durationNanos);
        dataPlaneClientCall.setServerHeadersStartNanos(0);
      }
      dataPlaneClientCall.getCallContext().run(() -> delegate().onHeaders(headers));
    }

    void proceedWithClose() {
      if (savedStatus != null) {
        if (dataPlaneClientCall.markDataPlaneCallClosed()) {
          proceedWithClose(savedStatus, savedTrailers);
        }
        savedStatus = null;
        savedTrailers = null;
      }
    }

    private void proceedWithClose(Status status, Metadata trailers) {
      if (dataPlaneClientCall.getServerTrailersStartNanos() > 0) {
        long durationNanos = System.nanoTime() - dataPlaneClientCall.getServerTrailersStartNanos();
        dataPlaneClientCall.recordDuration(serverTrailersDuration, durationNanos);
        dataPlaneClientCall.setServerTrailersStartNanos(0);
      }
      dataPlaneClientCall.getCallContext().run(() -> delegate().onClose(status, trailers));
    }

    void onExternalBody(ByteString body) {
      // If needed, downstream reading can be made more optimal by creating a wrapped
      // Inputstream wraps the underlying bytestring and that implements HasByteBuffer,
      // Detachable, KnownLength
      dataPlaneClientCall.getCallContext().run(
          () -> delegate().onMessage(body.newInput()));
    }

    void unblockAfterStreamComplete() {
      proceedWithHeaders();
      proceedWithSavedMessages();
      dataPlaneClientCall.drainPendingDrainingMessages();
      proceedWithClose();
    }

    private void proceedWithSavedMessages() {
      synchronized (savedMessages) {
        InputStream msg;
        while ((msg = savedMessages.poll()) != null) {
          final InputStream finalMsg = msg;
          dataPlaneClientCall.getCallContext().run(() -> delegate().onMessage(finalMsg));
        }
        inboundPassThrough = true;
      }
    }

    private void triggerCloseHandshake() {
      if (dataPlaneClientCall.getExtProcStreamState().get().isCompleted()
          || !terminationTriggered.compareAndSet(false, true)) {
        return;
      }

      boolean sendResponseHeaders =
          dataPlaneClientCall.getCurrentProcessingMode().getResponseHeaderMode()
              == ProcessingMode.HeaderSendMode.SEND
          || dataPlaneClientCall.getCurrentProcessingMode().getResponseHeaderMode()
              == ProcessingMode.HeaderSendMode.DEFAULT;

      boolean sendResponseTrailers =
          dataPlaneClientCall.getCurrentProcessingMode().getResponseTrailerMode()
              == ProcessingMode.HeaderSendMode.SEND;

      if (trailersOnly.get()) {
        if (sendResponseHeaders) {
          dataPlaneClientCall.sendToExtProc(ProcessingRequest.newBuilder()
              .setResponseHeaders(HttpHeaders.newBuilder()
                  .setHeaders(
                      toHeaderMap(
                          savedTrailers,
                          dataPlaneClientCall.getConfig().getForwardRulesConfig()))
                  .setEndOfStream(true)
                  .build())
              .build());
        } else {
          proceedWithClose();
          if (!dataPlaneClientCall.getConfig().getObservabilityMode()) {
            dataPlaneClientCall.closeExtProcStream();
          }
        }
      } else if (sendResponseTrailers) {
        dataPlaneClientCall.getIsProcessingTrailers().set(true);
        dataPlaneClientCall.sendToExtProc(ProcessingRequest.newBuilder()
            .setResponseTrailers(HttpTrailers.newBuilder()
                .setTrailers(
                    toHeaderMap(
                        savedTrailers,
                        dataPlaneClientCall.getConfig().getForwardRulesConfig()))
                .build())
            .build());
      } else {
        proceedWithClose();
        if (!dataPlaneClientCall.getConfig().getObservabilityMode()) {
          dataPlaneClientCall.closeExtProcStream();
        }
      }
    }

    private void sendResponseBodyToExtProc(
        @Nullable ByteString bodyByteString, boolean endOfStream) {
      if (dataPlaneClientCall.getExtProcStreamState().get().isCompleted()
          || dataPlaneClientCall.getCurrentProcessingMode().getResponseBodyMode()
              != ProcessingMode.BodySendMode.GRPC) {
        return;
      }

      HttpBody.Builder bodyBuilder =
          HttpBody.newBuilder();
      if (bodyByteString != null) {
        bodyBuilder.setBody(bodyByteString);
      }
      bodyBuilder.setEndOfStream(endOfStream);

      dataPlaneClientCall.sendToExtProc(ProcessingRequest.newBuilder()
          .setResponseBody(bodyBuilder.build())
          .build());
    }
  }



  private static ByteString outboundStreamToByteString(InputStream message) throws IOException {
    if (message instanceof Drainable) {
      int size = message.available();
      ByteString.Output output =
          size > 0 ? ByteString.newOutput(size) : ByteString.newOutput();
      ((Drainable) message).drainTo(output);
      return output.toByteString();
    }
    return ByteString.readFrom(message);
  }

  private static final class KnownLengthInputStream extends InputStream
      implements KnownLength {
    private final InputStream delegate;

    KnownLengthInputStream(ByteString byteString) {
      this.delegate = byteString.newInput();
    }

    @Override
    public int read() throws IOException {
      return delegate.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      return delegate.read(b, off, len);
    }

    @Override
    public int available() throws IOException {
      return delegate.available();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }
}
